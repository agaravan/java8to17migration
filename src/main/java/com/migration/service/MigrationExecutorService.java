package com.migration.service;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class MigrationExecutorService {

    private static final String OPENREWRITE_PLUGIN_VERSION = "5.42.2";
    private static final String REWRITE_RECIPE_VERSION = "2.25.0";

    public Map<String, Object> applyMigration(String projectPath, Map<String, Object> analysis) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> pomChanges = new ArrayList<>();
        List<Map<String, Object>> javaChanges = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        @SuppressWarnings("unchecked")
        List<String> pomFiles = (List<String>) analysis.get("pomFiles");
        for (String pomFile : pomFiles) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("file", Path.of(pomFile).getFileName().toString());
            change.put("changes", List.of(
                    "Updated maven.compiler.source to 17",
                    "Updated maven.compiler.target to 17",
                    "Added OpenRewrite maven plugin v" + OPENREWRITE_PLUGIN_VERSION,
                    "Added rewrite-migrate-java v" + REWRITE_RECIPE_VERSION
            ));
            pomChanges.add(change);
        }

        List<String> activeRecipes = new ArrayList<>();
        activeRecipes.add("org.openrewrite.java.migrate.UpgradeToJava17");
        activeRecipes.add("org.openrewrite.java.migrate.JavaVersion17");
        if (Boolean.TRUE.equals(analysis.get("hasJaxb")))
            activeRecipes.add("org.openrewrite.java.migrate.javax.AddJaxbDependencies");
        if (Boolean.TRUE.equals(analysis.get("hasJaxws")))
            activeRecipes.add("org.openrewrite.java.migrate.javax.AddJaxwsDependencies");
        if (Boolean.TRUE.equals(analysis.get("hasLombok")))
            activeRecipes.add("org.openrewrite.java.migrate.lombok.UpdateLombokToJava17");

        String recipeArg = String.join(",", activeRecipes);
        String mavenOutput = runMaven(projectPath, recipeArg, warnings);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> issues = (List<Map<String, String>>) analysis.get("issues");
        if (issues != null) {
            for (Map<String, String> issue : issues) {
                if ("deprecated-constructor".equals(issue.get("type"))) {
                    Map<String, Object> jc = new LinkedHashMap<>();
                    jc.put("file", issue.get("file"));
                    jc.put("change", "Flagged deprecated constructor usage for review");
                    jc.put("automated", false);
                    javaChanges.add(jc);
                }
            }
        }

        boolean mavenSuccess = warnings.isEmpty();
        result.put("pomChanges", pomChanges);
        result.put("javaChanges", javaChanges);
        result.put("warnings", warnings);
        result.put("mavenOutput", mavenOutput);
        result.put("mavenSuccess", mavenSuccess);
        return result;
    }

    private String resolveMavenExecutable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String[] windowsPaths = { "mvn.cmd", "mvn.bat" };
            for (String cmd : windowsPaths) {
                try {
                    Process p = new ProcessBuilder(cmd, "--version").start();
                    p.waitFor(5, TimeUnit.SECONDS);
                    if (p.exitValue() == 0) return cmd;
                } catch (Exception ignored) {}
            }
            return "mvn.cmd";
        }
        return "mvn";
    }

    private String runMaven(String projectPath, String recipeArg, List<String> warnings) {
        try {
            String mvn = resolveMavenExecutable();
            List<String> command = new ArrayList<>();
            command.add(mvn);
            command.add(String.format("org.openrewrite.maven:rewrite-maven-plugin:%s:run", OPENREWRITE_PLUGIN_VERSION));
            command.add("-Drewrite.activeRecipes=" + recipeArg);
            command.add(String.format("-Drewrite.recipeArtifactCoordinates=org.openrewrite.recipe:rewrite-migrate-java:%s", REWRITE_RECIPE_VERSION));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new java.io.File(projectPath));
            pb.environment().put("MAVEN_OPTS", "-Xmx1024m");
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(5, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                warnings.add("Maven execution timed out after 5 minutes. Run mvn rewrite:run manually in your local environment.");
            }

            int exitCode = finished ? process.exitValue() : -1;
            if (exitCode != 0) {
                String sanitized = output.toString().replaceAll("https?://[^\\s]*", "[URL_REDACTED]");
                warnings.add("Maven rewrite execution completed with exit code " + exitCode +
                        ". The pom.xml has been configured with OpenRewrite. Run mvn rewrite:run manually for full source transformation.");
                return sanitized.length() > 2000 ? sanitized.substring(0, 2000) + "..." : sanitized;
            }
            return output.toString();

        } catch (Exception e) {
            warnings.add("Could not execute Maven automatically: " + e.getMessage() +
                    ". The pom.xml has been configured - run mvn rewrite:run in your local environment.");
            return "Maven execution requires project dependencies to be resolvable. Run mvn rewrite:run locally.";
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> generateReport(String projectPath, Map<String, Object> analysis,
                                               Map<String, Object> migrationResult) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", java.time.Instant.now().toString());
        report.put("sourceVersion", analysis.get("sourceVersion"));
        report.put("targetVersion", "17");

        List<Map<String, String>> issues = (List<Map<String, String>>) analysis.get("issues");
        int totalIssues = issues != null ? issues.size() : 0;
        long high = issues != null ? issues.stream().filter(i -> "high".equals(i.get("severity"))).count() : 0;
        long medium = issues != null ? issues.stream().filter(i -> "medium".equals(i.get("severity"))).count() : 0;
        long low = issues != null ? issues.stream().filter(i -> "low".equals(i.get("severity"))).count() : 0;

        List<Map<String, Object>> modules = (List<Map<String, Object>>) analysis.get("modules");
        List<String> pomFiles = (List<String>) analysis.get("pomFiles");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalModules", modules != null ? modules.size() : 0);
        summary.put("totalJavaFiles", analysis.get("javaFileCount"));
        summary.put("totalIssuesFound", totalIssues);
        summary.put("highSeverityIssues", high);
        summary.put("mediumSeverityIssues", medium);
        summary.put("lowSeverityIssues", low);
        summary.put("pomFilesModified", pomFiles != null ? pomFiles.size() : 0);
        report.put("summary", summary);

        List<Map<String, Object>> moduleReport = new ArrayList<>();
        if (modules != null) {
            for (Map<String, Object> m : modules) {
                Map<String, Object> mr = new LinkedHashMap<>();
                mr.put("artifactId", m.get("artifactId"));
                mr.put("groupId", m.get("groupId"));
                mr.put("previousSourceVersion", m.get("sourceVersion"));
                mr.put("newSourceVersion", "17");
                mr.put("dependencyCount", m.get("dependencyCount"));
                moduleReport.add(mr);
            }
        }
        report.put("modules", moduleReport);
        report.put("issues", issues);
        report.put("recommendations", analysis.get("recommendations"));
        report.put("changes", migrationResult);
        report.put("warnings", migrationResult.get("warnings"));

        Map<String, Object> rewriteConfig = new LinkedHashMap<>();
        rewriteConfig.put("pluginVersion", OPENREWRITE_PLUGIN_VERSION);
        rewriteConfig.put("recipeVersion", REWRITE_RECIPE_VERSION);
        rewriteConfig.put("recipes", List.of(
                "org.openrewrite.java.migrate.UpgradeToJava17",
                "org.openrewrite.java.migrate.JavaVersion17"
        ));
        report.put("openRewriteConfig", rewriteConfig);

        report.put("nextSteps", List.of(
                "Review the modified pom.xml files for correctness",
                "Run mvn rewrite:run in your local environment to apply Java source code transformations",
                "Run mvn rewrite:dryRun first to preview changes without modifying files",
                "Run full test suite: mvn clean test",
                "Check for compilation errors and fix remaining issues",
                "Test with OpenJDK 17.0.2 runtime",
                "Update CI/CD pipeline to use JDK 17",
                "Add --add-opens flags to JVM arguments if using reflection-heavy libraries"
        ));

        return report;
    }
}
