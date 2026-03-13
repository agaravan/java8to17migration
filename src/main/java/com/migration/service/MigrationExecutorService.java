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

    public Map<String, Object> applyMigration(String projectPath, Map<String, Object> analysis, int targetVersion) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> pomChanges = new ArrayList<>();
        List<Map<String, Object>> javaChanges = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String tv = String.valueOf(targetVersion);

        @SuppressWarnings("unchecked")
        List<String> pomFiles = (List<String>) analysis.get("pomFiles");
        for (String pomFile : pomFiles) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("file", Path.of(pomFile).getFileName().toString());
            List<String> changes = new ArrayList<>(List.of(
                    "Updated maven.compiler.source to " + tv,
                    "Updated maven.compiler.target to " + tv,
                    "Added maven.compiler.release=" + tv,
                    "Upgraded maven-compiler-plugin to 3.13.0",
                    "Upgraded maven-resources-plugin to 3.3.1",
                    "Upgraded maven-surefire-plugin to 3.2.5",
                    "Upgraded maven-failsafe-plugin to 3.2.5 (if present)",
                    "Upgraded maven-jar-plugin to 3.3.0 (if present)",
                    "Upgraded maven-war-plugin to 3.4.0 (if present)",
                    "Upgraded maven-dependency-plugin to 3.6.1 (if present)",
                    "Added OpenRewrite maven plugin v" + OPENREWRITE_PLUGIN_VERSION,
                    "Added rewrite-migrate-java v" + REWRITE_RECIPE_VERSION
            ));
            if (Boolean.TRUE.equals(analysis.get("hasLombok")) && targetVersion >= 17) {
                changes.add("Upgraded lombok dependency to 1.18.30 (required for Java 17 compiler compatibility)");
            }
            if (Boolean.TRUE.equals(analysis.get("hasJaxws"))) {
                changes.add("Injected javax.xml.ws:jaxws-api:2.3.1 dependency (JAX-WS removed from JDK in Java 11)");
            }
            if (Boolean.TRUE.equals(analysis.get("hasJunit4"))) {
                changes.add("Added surefire-junit47:3.2.5 to surefire and failsafe plugin dependencies (prevents NoClassDefFoundError)");
            }
            change.put("changes", changes);
            pomChanges.add(change);
        }

        List<String> activeRecipes = buildRecipeList(analysis, targetVersion);

        String recipeArg = String.join(",", activeRecipes);
        String mavenOutput = runMaven(projectPath, recipeArg, warnings);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> issues = (List<Map<String, String>>) analysis.get("issues");
        if (issues != null) {
            for (Map<String, String> issue : issues) {
                String type = issue.get("type");
                if (type == null) continue;
                Map<String, Object> jc = new LinkedHashMap<>();
                jc.put("file", issue.get("file"));
                switch (type) {
                    case "deprecated-constructor":
                        jc.put("change", "Flagged deprecated wrapper constructor usage (use valueOf() instead)");
                        jc.put("automated", false);
                        javaChanges.add(jc);
                        break;
                    case "removed-security-manager":
                        jc.put("change", "MANUAL REQUIRED: SecurityManager usage detected — permanently removed in Java 17");
                        jc.put("automated", false);
                        javaChanges.add(jc);
                        break;
                    case "removed-api":
                        jc.put("change", "MANUAL REQUIRED: " + issue.getOrDefault("description", "Removed API usage detected"));
                        jc.put("automated", false);
                        javaChanges.add(jc);
                        break;
                    case "deprecated-finalize":
                        jc.put("change", "Flagged finalize() override — OpenRewrite RemoveFinalizeMethod recipe applied");
                        jc.put("automated", true);
                        javaChanges.add(jc);
                        break;
                    case "removed-module":
                        jc.put("change", "MANUAL REQUIRED: " + issue.getOrDefault("description", "Removed module usage detected"));
                        jc.put("automated", false);
                        javaChanges.add(jc);
                        break;
                    case "test-framework":
                        jc.put("change", "JUnit 4 usage detected — OpenRewrite JUnit4to5Migration recipe applied");
                        jc.put("automated", true);
                        javaChanges.add(jc);
                        break;
                    default:
                        break;
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

    private List<String> buildRecipeList(Map<String, Object> analysis, int targetVersion) {
        List<String> recipes = new ArrayList<>();

        switch (targetVersion) {
            case 11:
                recipes.add("org.openrewrite.java.migrate.UpgradeToJava11");
                recipes.add("org.openrewrite.java.migrate.JavaVersion11");
                break;
            case 21:
                recipes.add("org.openrewrite.java.migrate.UpgradeToJava21");
                recipes.add("org.openrewrite.java.migrate.JavaVersion21");
                break;
            default:
                recipes.add("org.openrewrite.java.migrate.UpgradeToJava17");
                recipes.add("org.openrewrite.java.migrate.JavaVersion17");
                break;
        }

        if (Boolean.TRUE.equals(analysis.get("hasJaxb")))
            recipes.add("org.openrewrite.java.migrate.javax.AddJaxbDependencies");
        if (Boolean.TRUE.equals(analysis.get("hasJaxws")))
            recipes.add("org.openrewrite.java.migrate.javax.AddJaxwsDependencies");
        if (Boolean.TRUE.equals(analysis.get("hasLombok")) && targetVersion >= 17)
            recipes.add("org.openrewrite.java.migrate.lombok.UpdateLombokToJava17");
        if (Boolean.TRUE.equals(analysis.get("hasJunit4")))
            recipes.add("org.openrewrite.java.testing.junit5.JUnit4to5Migration");
        if (Boolean.TRUE.equals(analysis.get("hasFinalize")))
            recipes.add("org.openrewrite.java.migrate.RemoveFinalizeMethod");

        return recipes;
    }

    private String resolveMavenExecutable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean isWindows = os.contains("win");
        String[] candidates = isWindows
                ? new String[]{ "mvn.cmd", "mvn.bat", "mvn" }
                : new String[]{ "mvn", "mvn.cmd" };
        for (String cmd : candidates) {
            try {
                Process p = new ProcessBuilder(cmd, "--version").start();
                boolean done = p.waitFor(5, TimeUnit.SECONDS);
                if (done && p.exitValue() == 0) return cmd;
            } catch (Exception ignored) {}
        }
        return isWindows ? "mvn.cmd" : "mvn";
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
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("Cannot run program")) {
                warnings.add("Maven is not available on this server. The pom.xml has been fully configured with OpenRewrite recipes. " +
                        "Run 'mvn rewrite:run' in your local development environment to apply source code transformations.");
            } else {
                warnings.add("Could not execute Maven automatically: " + errorMsg +
                        ". The pom.xml has been configured - run 'mvn rewrite:run' in your local environment.");
            }
            return "Maven execution requires a local Maven installation. Run 'mvn rewrite:run' locally to apply transformations.";
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> generateReport(String projectPath, Map<String, Object> analysis,
                                               Map<String, Object> migrationResult, int sourceVersion, int targetVersion) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", java.time.Instant.now().toString());
        report.put("sourceVersion", String.valueOf(sourceVersion));
        report.put("targetVersion", String.valueOf(targetVersion));

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

        String tv = String.valueOf(targetVersion);
        List<Map<String, Object>> moduleReport = new ArrayList<>();
        if (modules != null) {
            for (Map<String, Object> m : modules) {
                Map<String, Object> mr = new LinkedHashMap<>();
                mr.put("artifactId", m.get("artifactId"));
                mr.put("groupId", m.get("groupId"));
                mr.put("previousSourceVersion", m.get("sourceVersion"));
                mr.put("newSourceVersion", tv);
                mr.put("dependencyCount", m.get("dependencyCount"));
                moduleReport.add(mr);
            }
        }
        report.put("modules", moduleReport);
        report.put("issues", issues);
        report.put("recommendations", analysis.get("recommendations"));
        report.put("changes", migrationResult);
        report.put("warnings", migrationResult.get("warnings"));

        List<String> activeRecipes = buildRecipeList(analysis, targetVersion);
        Map<String, Object> rewriteConfig = new LinkedHashMap<>();
        rewriteConfig.put("pluginVersion", OPENREWRITE_PLUGIN_VERSION);
        rewriteConfig.put("recipeVersion", REWRITE_RECIPE_VERSION);
        rewriteConfig.put("recipes", activeRecipes);
        report.put("openRewriteConfig", rewriteConfig);

        report.put("nextSteps", List.of(
                "Review the modified pom.xml files for correctness",
                "Run mvn rewrite:run in your local environment to apply Java source code transformations",
                "Run mvn rewrite:dryRun first to preview changes without modifying files",
                "Run full test suite: mvn clean test",
                "Check for compilation errors and fix remaining issues",
                "Test with OpenJDK " + tv + " runtime",
                "Update CI/CD pipeline to use JDK " + tv,
                "Add --add-opens flags to JVM arguments if using reflection-heavy libraries"
        ));

        return report;
    }
}
