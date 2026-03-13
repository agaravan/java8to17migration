package com.migration.service;

import com.migration.model.MigrationJob;
import com.migration.model.Recipe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class MigrationOrchestrator {

    private final GitService gitService;
    private final AnalysisService analysisService;
    private final RewriteConfigService rewriteConfigService;
    private final MigrationExecutorService executorService;
    private final ChangeHistoryService changeHistoryService;
    private final HistoryPersistenceService historyPersistenceService;

    private final Map<String, MigrationJob> migrations = new ConcurrentHashMap<>();
    private final ExecutorService threadPool = Executors.newFixedThreadPool(3);
    private final AtomicInteger activeMigrations = new AtomicInteger(0);

    @Value("${migration.max-concurrent}")
    private int maxConcurrent;

    public MigrationOrchestrator(GitService gitService, AnalysisService analysisService,
                                  RewriteConfigService rewriteConfigService,
                                  MigrationExecutorService executorService,
                                  ChangeHistoryService changeHistoryService,
                                  HistoryPersistenceService historyPersistenceService) {
        this.gitService = gitService;
        this.analysisService = analysisService;
        this.rewriteConfigService = rewriteConfigService;
        this.executorService = executorService;
        this.changeHistoryService = changeHistoryService;
        this.historyPersistenceService = historyPersistenceService;
    }

    @jakarta.annotation.PostConstruct
    public void loadHistory() {
        migrations.putAll(historyPersistenceService.loadAllJobs());
    }

    public MigrationJob startMigration(String repoUrl, String branch, String username, String password,
                                        boolean pushToNewBranch, String targetBranchName,
                                        int sourceVersion, int targetVersion) {
        if (activeMigrations.get() >= maxConcurrent) {
            throw new IllegalStateException("Maximum " + maxConcurrent + " concurrent migrations allowed. Please wait.");
        }

        String id = UUID.randomUUID().toString();
        MigrationJob job = new MigrationJob(id, repoUrl, branch);
        job.setPushToNewBranch(pushToNewBranch);
        job.setTargetBranchName(targetBranchName);
        job.setSourceVersion(sourceVersion);
        job.setTargetVersion(targetVersion);
        migrations.put(id, job);

        threadPool.submit(() -> processMigration(job, username, password));

        return job;
    }

    private void processMigration(MigrationJob job, String username, String password) {
        activeMigrations.incrementAndGet();
        String clonePath = null;

        try {
            job.updateStep("clone", "in_progress", "Cloning repository...");
            boolean fullClone = job.isPushToNewBranch();
            clonePath = gitService.cloneRepo(job.getRepoUrl(), job.getBranch(), job.getId(), username, password, fullClone);
            job.updateStep("clone", "completed", "Repository cloned successfully");

            job.updateStep("analyze", "in_progress", "Analyzing project structure...");
            Map<String, Object> analysis = analysisService.analyzeProject(clonePath);
            job.updateStep("analyze", "completed", String.format("Found %d module(s), current Java version: %s",
                    ((List<?>) analysis.get("modules")).size(), analysis.get("sourceVersion")));

            job.updateStep("configure", "in_progress", "Configuring OpenRewrite recipes...");
            Map<String, Object> config = rewriteConfigService.configureRewrite(clonePath, analysis, job.getTargetVersion());
            List<?> recipes = (List<?>) config.get("recipesApplied");
            job.updateStep("configure", "completed", String.format("Configured %d recipe(s) for Java %d", recipes.size(), job.getTargetVersion()));

            job.updateStep("migrate", "in_progress", "Applying migration recipes...");
            Map<String, Object> result = executorService.applyMigration(clonePath, analysis, job.getTargetVersion());
            boolean mavenSuccess = Boolean.TRUE.equals(result.get("mavenSuccess"));
            String migrateMsg = mavenSuccess
                    ? "Migration recipes applied successfully"
                    : "POM configured with OpenRewrite. Maven execution had warnings - run mvn rewrite:run locally for full transformation.";
            job.updateStep("migrate", "completed", migrateMsg);

            job.updateStep("report", "in_progress", "Capturing changes and generating report...");
            List<Map<String, Object>> fileChanges = changeHistoryService.captureChanges(clonePath);
            Map<String, Object> changeSummary = changeHistoryService.summarizeChanges(fileChanges);
            Map<String, Object> report = executorService.generateReport(clonePath, analysis, result, job.getSourceVersion(), job.getTargetVersion());
            report.put("fileChanges", fileChanges);
            report.put("changeSummary", changeSummary);
            long elapsedSeconds = Duration.between(job.getCreatedAt(), java.time.Instant.now()).getSeconds();
            Map<String, Object> effortEstimate = estimateEffort(analysis, fileChanges, changeSummary, elapsedSeconds);
            report.put("effortEstimate", effortEstimate);
            job.setReport(report);
            job.updateStep("report", "completed",
                    String.format("Report generated - %d files changed, %d lines added, %d lines removed",
                            changeSummary.get("totalFilesChanged"),
                            changeSummary.get("totalLinesAdded"),
                            changeSummary.get("totalLinesRemoved")));

            if (job.isPushToNewBranch()) {
                try {
                    job.updateStep("push", "in_progress", "Pushing migrated code to new branch...");
                    Map<String, Object> pushResult = gitService.commitAndPush(
                            clonePath, job.getTargetBranchName(), username, password,
                            job.getSourceVersion(), job.getTargetVersion());
                    job.setPushResult(pushResult);
                    boolean pushed = Boolean.TRUE.equals(pushResult.get("pushed"));
                    String pushMsg = (String) pushResult.get("message");
                    job.updateStep("push", pushed ? "completed" : "warning", pushMsg);
                } catch (Exception pushEx) {
                    Map<String, Object> failResult = new LinkedHashMap<>();
                    failResult.put("pushed", false);
                    failResult.put("message", "Push failed: " + pushEx.getMessage());
                    job.setPushResult(failResult);
                    job.updateStep("push", "warning", "Push failed: " + pushEx.getMessage());
                }
            }

            job.markStatus("completed");

        } catch (Exception e) {
            job.markStatus("failed");
            job.setError(e.getMessage());
            job.getSteps().stream()
                    .filter(s -> "in_progress".equals(s.getStatus()))
                    .findFirst()
                    .ifPresent(s -> {
                        s.setStatus("failed");
                        s.setMessage(e.getMessage());
                    });
        } finally {
            activeMigrations.decrementAndGet();
            historyPersistenceService.saveJob(job);
            if (clonePath != null) {
                gitService.cleanup(clonePath);
            }
        }
    }

    public MigrationJob getJob(String id) {
        return migrations.get(id);
    }

    public List<MigrationJob> getAllJobs() {
        List<MigrationJob> jobs = new ArrayList<>(migrations.values());
        jobs.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        return jobs;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> estimateEffort(Map<String, Object> analysis,
                                                List<Map<String, Object>> fileChanges,
                                                Map<String, Object> changeSummary,
                                                long toolTimeSeconds) {
        Map<String, Object> effort = new LinkedHashMap<>();

        int javaFiles = analysis.get("javaFileCount") instanceof Number
                ? ((Number) analysis.get("javaFileCount")).intValue() : 0;
        List<Map<String, String>> issues = (List<Map<String, String>>) analysis.get("issues");
        int issueCount = issues != null ? issues.size() : 0;
        long highIssues = issues != null ? issues.stream().filter(i -> "high".equals(i.get("severity"))).count() : 0;
        long mediumIssues = issues != null ? issues.stream().filter(i -> "medium".equals(i.get("severity"))).count() : 0;
        long lowIssues = issues != null ? issues.stream().filter(i -> "low".equals(i.get("severity"))).count() : 0;
        List<Map<String, Object>> modules = (List<Map<String, Object>>) analysis.get("modules");
        int moduleCount = modules != null ? modules.size() : 1;
        int filesChanged = changeSummary.get("totalFilesChanged") instanceof Number
                ? ((Number) changeSummary.get("totalFilesChanged")).intValue() : 0;
        int linesChanged = 0;
        if (changeSummary.get("totalLinesAdded") instanceof Number)
            linesChanged += ((Number) changeSummary.get("totalLinesAdded")).intValue();
        if (changeSummary.get("totalLinesRemoved") instanceof Number)
            linesChanged += ((Number) changeSummary.get("totalLinesRemoved")).intValue();

        double manualHoursPerModule = 4.0;
        double manualHoursPerJavaFile = 0.5;
        double manualHoursPerHighIssue = 2.0;
        double manualHoursPerMediumIssue = 1.0;
        double manualHoursPerLowIssue = 0.25;
        double testingHoursPerModule = 8.0;
        double codeReviewHours = Math.max(2.0, filesChanged * 0.15);

        double manualMigrationHours = (moduleCount * manualHoursPerModule)
                + (javaFiles * manualHoursPerJavaFile)
                + (highIssues * manualHoursPerHighIssue)
                + (mediumIssues * manualHoursPerMediumIssue)
                + (lowIssues * manualHoursPerLowIssue);

        double manualTestingHours = moduleCount * testingHoursPerModule;
        double totalManualHours = manualMigrationHours + manualTestingHours + codeReviewHours;

        double toolTimeHours = toolTimeSeconds / 3600.0;

        double remainingManualHours = (highIssues * manualHoursPerHighIssue)
                + (mediumIssues * manualHoursPerMediumIssue * 0.5)
                + manualTestingHours
                + codeReviewHours;

        double automatedHours = totalManualHours - remainingManualHours;
        double automationPercentage = totalManualHours > 0 ? (automatedHours / totalManualHours) * 100 : 0;
        double timeSavedHours = automatedHours - toolTimeHours;

        effort.put("estimatedManualEffortHours", Math.round(totalManualHours * 10) / 10.0);
        effort.put("manualMigrationHours", Math.round(manualMigrationHours * 10) / 10.0);
        effort.put("manualTestingHours", Math.round(manualTestingHours * 10) / 10.0);
        effort.put("codeReviewHours", Math.round(codeReviewHours * 10) / 10.0);
        effort.put("toolTimeSeconds", toolTimeSeconds);
        effort.put("toolTimeFormatted", formatSeconds(toolTimeSeconds));
        effort.put("remainingManualHours", Math.round(remainingManualHours * 10) / 10.0);
        effort.put("automatedHours", Math.round(automatedHours * 10) / 10.0);
        effort.put("automationPercentage", Math.round(automationPercentage));
        effort.put("timeSavedHours", Math.round(timeSavedHours * 10) / 10.0);

        List<Map<String, Object>> breakdown = new ArrayList<>();
        if (highIssues > 0) breakdown.add(Map.of(
                "category", "High Severity Issues", "count", highIssues,
                "manualHours", highIssues * manualHoursPerHighIssue,
                "status", "Requires manual review"));
        if (mediumIssues > 0) breakdown.add(Map.of(
                "category", "Medium Severity Issues", "count", mediumIssues,
                "manualHours", mediumIssues * manualHoursPerMediumIssue * 0.5,
                "status", "Partially automated, review recommended"));
        if (lowIssues > 0) breakdown.add(Map.of(
                "category", "Low Severity Issues", "count", lowIssues,
                "manualHours", 0,
                "status", "Fully automated by OpenRewrite"));
        breakdown.add(Map.of(
                "category", "Integration Testing", "count", moduleCount,
                "manualHours", manualTestingHours,
                "status", "Manual testing required"));
        breakdown.add(Map.of(
                "category", "Code Review", "count", filesChanged,
                "manualHours", codeReviewHours,
                "status", "Review migrated changes"));
        effort.put("breakdown", breakdown);

        return effort;
    }

    public Map<String, Object> getDashboardMetrics() {
        Map<String, Object> dashboard = new LinkedHashMap<>();
        List<MigrationJob> allJobs = getAllJobs();

        int total = allJobs.size();
        int completed = 0;
        int failed = 0;
        int inProgress = 0;
        double totalToolTimeSeconds = 0;
        double totalEstimatedManualHours = 0;
        double totalRemainingManualHours = 0;
        double totalAutomatedHours = 0;
        int totalJavaFiles = 0;
        int totalModules = 0;
        int totalIssues = 0;
        int totalFilesChanged = 0;
        int totalLinesChanged = 0;
        Set<String> uniqueRepos = new LinkedHashSet<>();
        List<Map<String, Object>> repoSummaries = new ArrayList<>();

        for (MigrationJob job : allJobs) {
            String repoName = job.getRepoUrl().substring(job.getRepoUrl().lastIndexOf('/') + 1).replace(".git", "");
            uniqueRepos.add(repoName);

            switch (job.getStatus()) {
                case "completed": completed++; break;
                case "failed": failed++; break;
                default: inProgress++; break;
            }

            if (job.getCompletedAt() != null && job.getCreatedAt() != null) {
                totalToolTimeSeconds += Duration.between(job.getCreatedAt(), job.getCompletedAt()).getSeconds();
            }

            totalJavaFiles += job.getTotalJavaFiles();
            totalModules += job.getTotalModules();
            totalIssues += job.getTotalIssues();

            Map<String, Object> report = job.getReport();
            if (report != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> effortEstimate = (Map<String, Object>) report.get("effortEstimate");
                if (effortEstimate != null) {
                    Object emh = effortEstimate.get("estimatedManualEffortHours");
                    Object rmh = effortEstimate.get("remainingManualHours");
                    Object ah = effortEstimate.get("automatedHours");
                    if (emh instanceof Number) totalEstimatedManualHours += ((Number) emh).doubleValue();
                    if (rmh instanceof Number) totalRemainingManualHours += ((Number) rmh).doubleValue();
                    if (ah instanceof Number) totalAutomatedHours += ((Number) ah).doubleValue();
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> changeSummary = (Map<String, Object>) report.get("changeSummary");
                if (changeSummary != null) {
                    Object tfc = changeSummary.get("totalFilesChanged");
                    Object tla = changeSummary.get("totalLinesAdded");
                    Object tlr = changeSummary.get("totalLinesRemoved");
                    if (tfc instanceof Number) totalFilesChanged += ((Number) tfc).intValue();
                    if (tla instanceof Number) totalLinesChanged += ((Number) tla).intValue();
                    if (tlr instanceof Number) totalLinesChanged += ((Number) tlr).intValue();
                }

                Map<String, Object> repoSummary = new LinkedHashMap<>();
                repoSummary.put("repoName", repoName);
                repoSummary.put("branch", job.getBranch());
                repoSummary.put("status", job.getStatus());
                repoSummary.put("toolTime", job.getTotalTimeTaken());
                repoSummary.put("javaFiles", job.getTotalJavaFiles());
                repoSummary.put("issues", job.getTotalIssues());
                repoSummary.put("modules", job.getTotalModules());
                if (effortEstimate != null) {
                    repoSummary.put("estimatedManualHours", effortEstimate.get("estimatedManualEffortHours"));
                    repoSummary.put("remainingManualHours", effortEstimate.get("remainingManualHours"));
                    repoSummary.put("automationPercentage", effortEstimate.get("automationPercentage"));
                }
                if (changeSummary != null) {
                    repoSummary.put("filesChanged", changeSummary.get("totalFilesChanged"));
                    repoSummary.put("linesAdded", changeSummary.get("totalLinesAdded"));
                    repoSummary.put("linesRemoved", changeSummary.get("totalLinesRemoved"));
                }
                repoSummaries.add(repoSummary);
            }
        }

        double totalTimeSaved = totalAutomatedHours - (totalToolTimeSeconds / 3600.0);
        double overallAutomation = totalEstimatedManualHours > 0
                ? (totalAutomatedHours / totalEstimatedManualHours) * 100 : 0;

        dashboard.put("totalMigrations", total);
        dashboard.put("completedMigrations", completed);
        dashboard.put("failedMigrations", failed);
        dashboard.put("inProgressMigrations", inProgress);
        dashboard.put("uniqueRepos", uniqueRepos.size());
        dashboard.put("totalJavaFiles", totalJavaFiles);
        dashboard.put("totalModules", totalModules);
        dashboard.put("totalIssuesFound", totalIssues);
        dashboard.put("totalFilesChanged", totalFilesChanged);
        dashboard.put("totalLinesChanged", totalLinesChanged);
        dashboard.put("totalToolTimeSeconds", Math.round(totalToolTimeSeconds));
        dashboard.put("totalToolTimeFormatted", formatSeconds((long) totalToolTimeSeconds));
        dashboard.put("totalEstimatedManualHours", Math.round(totalEstimatedManualHours * 10) / 10.0);
        dashboard.put("totalRemainingManualHours", Math.round(totalRemainingManualHours * 10) / 10.0);
        dashboard.put("totalAutomatedHours", Math.round(totalAutomatedHours * 10) / 10.0);
        dashboard.put("totalTimeSavedHours", Math.round(totalTimeSaved * 10) / 10.0);
        dashboard.put("overallAutomationPercentage", Math.round(overallAutomation));
        dashboard.put("repoSummaries", repoSummaries);

        return dashboard;
    }

    private String formatSeconds(long totalSecs) {
        if (totalSecs < 60) return totalSecs + "s";
        long mins = totalSecs / 60;
        long secs = totalSecs % 60;
        if (mins < 60) return mins + "m " + secs + "s";
        long hours = mins / 60;
        mins = mins % 60;
        return hours + "h " + mins + "m " + secs + "s";
    }

    public List<Recipe> getAvailableRecipes() {
        return List.of(
            new Recipe("org.openrewrite.java.migrate.UpgradeToJava11",
                    "Upgrade to Java 11",
                    "Migrates Java 8 code to Java 11, including deprecated API replacements and removed API alternatives.",
                    "core", true),
            new Recipe("org.openrewrite.java.migrate.UpgradeToJava17",
                    "Upgrade to Java 17",
                    "Migrates Java code to Java 17, including deprecated API replacements, removed API alternatives, and compiler settings.",
                    "core", true),
            new Recipe("org.openrewrite.java.migrate.UpgradeToJava21",
                    "Upgrade to Java 21",
                    "Migrates Java code to Java 21, including virtual threads support, pattern matching, and modern API usage.",
                    "core", true),
            new Recipe("org.openrewrite.java.migrate.javax.AddJaxbDependencies",
                    "Add JAXB Dependencies",
                    "Adds explicit JAXB dependencies since javax.xml.bind was removed from the JDK in Java 11.",
                    "api-removal", false),
            new Recipe("org.openrewrite.java.migrate.javax.AddJaxwsDependencies",
                    "Add JAX-WS Dependencies",
                    "Adds explicit JAX-WS dependencies since javax.xml.ws was removed from the JDK in Java 11.",
                    "api-removal", false),
            new Recipe("org.openrewrite.java.migrate.lombok.UpdateLombokToJava17",
                    "Update Lombok for Java 17+",
                    "Updates Lombok to a version compatible with Java 17+ and adjusts configurations.",
                    "dependency", false),
            new Recipe("org.openrewrite.maven.UpgradePluginVersion",
                    "Upgrade Maven Plugin Versions",
                    "Updates Maven plugins to versions compatible with the target Java version.",
                    "build", true),
            new Recipe("org.openrewrite.java.migrate.RemovedJavaXMLWSModuleInfo",
                    "Handle Removed java.xml.ws Module",
                    "Handles the removal of java.xml.ws module from Java 11+.",
                    "api-removal", false),
            new Recipe("org.openrewrite.java.migrate.RemoveMethodInvocation",
                    "Remove Deprecated Method Invocations",
                    "Removes invocations of methods that were deprecated and removed in later Java versions.",
                    "deprecated", false),
            new Recipe("org.openrewrite.java.testing.junit5.JUnit4to5Migration",
                    "Migrate JUnit 4 to JUnit 5",
                    "Migrates JUnit 4 tests to JUnit 5. Replaces @RunWith with @ExtendWith, @Before/@After with @BeforeEach/@AfterEach, and updates assertions.",
                    "testing", false),
            new Recipe("org.openrewrite.java.migrate.RemoveFinalizeMethod",
                    "Remove finalize() Methods",
                    "Removes finalize() method overrides which are deprecated for removal since Java 18 and scheduled for removal in Java 21+.",
                    "deprecated", false)
        );
    }
}
