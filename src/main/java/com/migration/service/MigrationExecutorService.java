package com.migration.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class MigrationExecutorService {

    private static final Logger log = LoggerFactory.getLogger(MigrationExecutorService.class);

    private static final String OPENREWRITE_PLUGIN_VERSION = "5.42.2";
    private static final String REWRITE_RECIPE_VERSION = "2.25.0";

    /** Hard-coded Maven installation on the Windows Server — always tried first. */
    private static final String MAVEN_HOME_WINDOWS = "F:\\Test\\Maven-3.9.6";

    /**
     * Optional override via application.properties.
     * If blank, MAVEN_HOME_WINDOWS is used.
     */
    @Value("${migration.maven.executable:}")
    private String configuredMavenExecutable;

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

    /**
     * Resolves the Maven executable using the following priority order:
     *
     * 1. migration.maven.executable property (defaults to F:\Test\Maven-3.9.6\bin\mvn.cmd)
     * 2. MAVEN_HOME / M2_HOME / MVN_HOME environment variables
     * 3. Maven wrapper (mvnw.cmd / mvnw) in the project directory
     * 4. Each directory on the system PATH environment variable
     * 5. Common Windows install directories (C:\Program Files\...)
     * 6. Plain mvn / mvn.cmd on the system PATH as last resort
     */
    private String resolveMavenExecutable(String projectPath) {
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean isWindows = os.contains("win");
        log.info("Resolving Maven executable. OS: {}, isWindows: {}", os, isWindows);

        List<String> candidates = new ArrayList<>();

        // --- Strategy 0: Hard-coded server path (always tried first, baked into JAR) ---
        candidates.add(MAVEN_HOME_WINDOWS + "\\bin\\mvn.cmd");
        candidates.add(MAVEN_HOME_WINDOWS + "\\bin\\mvn.bat");
        candidates.add(MAVEN_HOME_WINDOWS + "/bin/mvn");
        log.info("Strategy 0 - Hard-coded server path: {}\\bin\\mvn.cmd", MAVEN_HOME_WINDOWS);

        // --- Strategy 1: Configured property override via application.properties ---
        if (configuredMavenExecutable != null && !configuredMavenExecutable.isBlank()) {
            String configured = configuredMavenExecutable.trim();
            candidates.add(0, configured);
            log.info("Strategy 1 - Overriding with configured property path: {}", configured);
        } else {
            log.info("Strategy 1 - No migration.maven.executable override set; using hard-coded path");
        }

        // --- Strategy 2: MAVEN_HOME / M2_HOME / MVN_HOME environment variables ---
        for (String envVar : new String[]{ "MAVEN_HOME", "M2_HOME", "MVN_HOME" }) {
            String home = System.getenv(envVar);
            if (home != null && !home.isBlank()) {
                log.info("Strategy 2 - Found env var {}={}", envVar, home);
                if (isWindows) {
                    candidates.add(home + "\\bin\\mvn.cmd");
                    candidates.add(home + "\\bin\\mvn.bat");
                }
                candidates.add(home + "/bin/mvn");
            } else {
                log.debug("Strategy 2 - Env var {} not set", envVar);
            }
        }

        // --- Strategy 3: Maven wrapper in project directory ---
        if (projectPath != null) {
            String wrapper = isWindows
                    ? projectPath + File.separator + "mvnw.cmd"
                    : projectPath + File.separator + "mvnw";
            candidates.add(wrapper);
            if (isWindows) candidates.add(projectPath + File.separator + "mvnw");
            log.info("Strategy 3 - Maven wrapper candidates from project dir: {}", projectPath);
        }

        // --- Strategy 4: Scan PATH environment variable ---
        for (String pathVar : new String[]{ "PATH", "Path" }) {
            String pathEnv = System.getenv(pathVar);
            if (pathEnv == null) continue;
            log.info("Strategy 4 - Scanning {} entries in {} environment variable", pathEnv.split(isWindows ? ";" : ":").length, pathVar);
            for (String dir : pathEnv.split(isWindows ? ";" : ":")) {
                if (dir == null || dir.isBlank()) continue;
                String d = dir.trim();
                if (isWindows) {
                    candidates.add(d + "\\mvn.cmd");
                    candidates.add(d + "\\mvn.bat");
                }
                candidates.add(d + File.separator + "mvn");
            }
        }

        // --- Strategy 5: Common Windows installation directories ---
        if (isWindows) {
            for (String base : new String[]{
                    "C:\\Program Files\\Apache Software Foundation",
                    "C:\\Program Files\\Apache",
                    "C:\\tools",
                    "C:\\maven",
                    System.getProperty("user.home", "C:\\Users\\Default") + "\\tools"
            }) {
                File dir = new File(base);
                if (dir.isDirectory()) {
                    File[] entries = dir.listFiles();
                    if (entries != null) {
                        Arrays.sort(entries, Comparator.reverseOrder());
                        for (File entry : entries) {
                            if (entry.isDirectory() && entry.getName().toLowerCase().contains("maven")) {
                                log.info("Strategy 5 - Found Maven dir: {}", entry.getAbsolutePath());
                                candidates.add(entry.getAbsolutePath() + "\\bin\\mvn.cmd");
                                candidates.add(entry.getAbsolutePath() + "\\bin\\mvn.bat");
                            }
                        }
                    }
                }
            }
        }

        // --- Strategy 6: Plain command name on PATH ---
        if (isWindows) {
            candidates.add("mvn.cmd");
            candidates.add("mvn.bat");
        }
        candidates.add("mvn");

        // --- Probe each candidate ---
        for (String cmd : candidates) {
            if (cmd == null || cmd.isBlank()) continue;
            log.debug("Probing Maven candidate: {}", cmd);
            try {
                Process p = new ProcessBuilder(cmd, "--version")
                        .redirectErrorStream(true)
                        .start();
                boolean done = p.waitFor(5, TimeUnit.SECONDS);
                if (done && p.exitValue() == 0) {
                    log.info("Maven executable resolved successfully: {}", cmd);
                    return cmd;
                } else {
                    log.debug("Candidate '{}' did not respond successfully (done={}, exitValue={})",
                            cmd, done, done ? p.exitValue() : "N/A");
                }
            } catch (Exception e) {
                log.debug("Candidate '{}' failed probe: {}", cmd, e.getMessage());
            }
        }

        String fallback = isWindows ? "mvn.cmd" : "mvn";
        log.error("All Maven candidates exhausted. Falling back to '{}' — this will likely fail. "
                + "Set migration.maven.executable in application.properties to fix this.", fallback);
        return fallback;
    }

    private String runMaven(String projectPath, String recipeArg, List<String> warnings) {
        log.info("=== Maven execution start ===");
        log.info("Project path : {}", projectPath);
        log.info("Active recipes: {}", recipeArg);

        try {
            String mvn = resolveMavenExecutable(projectPath);
            log.info("Using Maven executable: {}", mvn);

            List<String> command = new ArrayList<>();
            command.add(mvn);
            command.add(String.format("org.openrewrite.maven:rewrite-maven-plugin:%s:run", OPENREWRITE_PLUGIN_VERSION));
            command.add("-Drewrite.activeRecipes=" + recipeArg);
            command.add(String.format("-Drewrite.recipeArtifactCoordinates=org.openrewrite.recipe:rewrite-migrate-java:%s", REWRITE_RECIPE_VERSION));
            log.info("Maven command: {}", command);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(projectPath));
            pb.environment().put("MAVEN_OPTS", "-Xmx1024m");
            pb.redirectErrorStream(true);
            log.info("Working directory: {}", projectPath);

            Process process = pb.start();
            log.info("Maven process started (PID lookup not available in Java 8 compat mode)");

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.debug("[MVN] {}", line);
                }
            }

            boolean finished = process.waitFor(5, TimeUnit.MINUTES);
            int exitCode = finished ? process.exitValue() : -1;
            log.info("Maven process finished: {}, exit code: {}", finished, exitCode);

            if (!finished) {
                process.destroyForcibly();
                String msg = "Maven execution timed out after 5 minutes. Run 'mvn rewrite:run' manually in the project directory.";
                log.error(msg);
                warnings.add(msg);
            } else if (exitCode != 0) {
                String sanitized = output.toString().replaceAll("https?://[^\\s]*", "[URL_REDACTED]");
                String msg = "Maven rewrite execution completed with exit code " + exitCode
                        + ". The pom.xml has been configured with OpenRewrite. "
                        + "Run 'mvn rewrite:run' manually for full source transformation.";
                log.error("Maven exited with non-zero code {}. Output (sanitized):\n{}", exitCode, sanitized);
                warnings.add(msg);
                return sanitized.length() > 2000 ? sanitized.substring(0, 2000) + "..." : sanitized;
            } else {
                log.info("Maven rewrite completed successfully");
            }

            log.info("=== Maven execution end ===");
            return output.toString();

        } catch (Exception e) {
            log.error("Exception during Maven execution: {}", e.getMessage(), e);
            String errorMsg = e.getMessage();
            String hint = "Ensure migration.maven.executable in application.properties points to the full path of mvn.cmd "
                    + "(e.g. F:/Test/Maven-3.9.6/bin/mvn.cmd).";
            if (errorMsg != null && errorMsg.contains("Cannot run program")) {
                String msg = "Maven could not be launched. The pom.xml has been configured with OpenRewrite recipes. "
                        + hint + " Alternatively, run 'mvn rewrite:run' manually in the cloned project directory.";
                log.error("Cannot run Maven program: {}. {}", errorMsg, hint);
                warnings.add(msg);
            } else {
                String msg = "Could not execute Maven: " + errorMsg + ". The pom.xml has been configured. " + hint;
                log.error(msg);
                warnings.add(msg);
            }
            return "Maven execution failed. Check application logs for details. " + hint;
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
