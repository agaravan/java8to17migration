package com.migration.service;

import com.migration.model.MigrationJob;
import com.migration.model.Recipe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    private final Map<String, MigrationJob> migrations = new ConcurrentHashMap<>();
    private final ExecutorService threadPool = Executors.newFixedThreadPool(3);
    private final AtomicInteger activeMigrations = new AtomicInteger(0);

    @Value("${migration.max-concurrent}")
    private int maxConcurrent;

    public MigrationOrchestrator(GitService gitService, AnalysisService analysisService,
                                  RewriteConfigService rewriteConfigService,
                                  MigrationExecutorService executorService) {
        this.gitService = gitService;
        this.analysisService = analysisService;
        this.rewriteConfigService = rewriteConfigService;
        this.executorService = executorService;
    }

    public MigrationJob startMigration(String repoUrl, String branch, String username, String password,
                                        boolean pushToNewBranch, String targetBranchName) {
        if (activeMigrations.get() >= maxConcurrent) {
            throw new IllegalStateException("Maximum " + maxConcurrent + " concurrent migrations allowed. Please wait.");
        }

        String id = UUID.randomUUID().toString();
        MigrationJob job = new MigrationJob(id, repoUrl, branch);
        job.setPushToNewBranch(pushToNewBranch);
        job.setTargetBranchName(targetBranchName);
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
            Map<String, Object> config = rewriteConfigService.configureRewrite(clonePath, analysis);
            List<?> recipes = (List<?>) config.get("recipesApplied");
            job.updateStep("configure", "completed", String.format("Configured %d recipe(s)", recipes.size()));

            job.updateStep("migrate", "in_progress", "Applying migration recipes...");
            Map<String, Object> result = executorService.applyMigration(clonePath, analysis);
            boolean mavenSuccess = Boolean.TRUE.equals(result.get("mavenSuccess"));
            String migrateMsg = mavenSuccess
                    ? "Migration recipes applied successfully"
                    : "POM configured with OpenRewrite. Maven execution had warnings - run mvn rewrite:run locally for full transformation.";
            job.updateStep("migrate", "completed", migrateMsg);

            job.updateStep("report", "in_progress", "Generating migration report...");
            Map<String, Object> report = executorService.generateReport(clonePath, analysis, result);
            job.setReport(report);
            job.updateStep("report", "completed", "Migration report generated");

            if (job.isPushToNewBranch()) {
                try {
                    job.updateStep("push", "in_progress", "Pushing migrated code to new branch...");
                    Map<String, Object> pushResult = gitService.commitAndPush(
                            clonePath, job.getTargetBranchName(), username, password);
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

            job.setStatus("completed");

        } catch (Exception e) {
            job.setStatus("failed");
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

    public List<Recipe> getAvailableRecipes() {
        return List.of(
            new Recipe("org.openrewrite.java.migrate.UpgradeToJava17",
                    "Upgrade to Java 17",
                    "Migrates Java 8 code to Java 17, including deprecated API replacements, removed API alternatives, and compiler settings.",
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
                    "Update Lombok for Java 17",
                    "Updates Lombok to a version compatible with Java 17 and adjusts configurations.",
                    "dependency", false),
            new Recipe("org.openrewrite.maven.UpgradePluginVersion",
                    "Upgrade Maven Plugin Versions",
                    "Updates Maven plugins to versions compatible with Java 17.",
                    "build", true),
            new Recipe("org.openrewrite.java.migrate.RemovedJavaXMLWSModuleInfo",
                    "Handle Removed java.xml.ws Module",
                    "Handles the removal of java.xml.ws module from Java 11+.",
                    "api-removal", false),
            new Recipe("org.openrewrite.java.migrate.JavaVersion17",
                    "Set Java Version 17",
                    "Sets the Java version to 17 in build configuration files.",
                    "core", true),
            new Recipe("org.openrewrite.java.migrate.RemoveMethodInvocation",
                    "Remove Deprecated Method Invocations",
                    "Removes invocations of methods that were deprecated and removed in later Java versions.",
                    "deprecated", false)
        );
    }
}
