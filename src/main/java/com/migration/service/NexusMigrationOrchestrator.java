package com.migration.service;

import com.migration.model.NexusJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class NexusMigrationOrchestrator {

    private final Map<String, NexusJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AtomicInteger activeJobs = new AtomicInteger(0);

    private final GitService gitService;
    private final NexusAnalysisService analysisService;
    private final NexusMigrationService migrationService;

    @Value("${migration.max-concurrent:3}")
    private int maxConcurrent;

    public NexusMigrationOrchestrator(GitService gitService,
                                       NexusAnalysisService analysisService,
                                       NexusMigrationService migrationService) {
        this.gitService = gitService;
        this.analysisService = analysisService;
        this.migrationService = migrationService;
    }

    public NexusJob startMigration(String repoUrl, String branch,
                                    String username, String password,
                                    boolean pushToNewBranch, String targetBranchName,
                                    String nexusUrl, String artifactoryUrl,
                                    Map<String, String> repoMappings) {
        if (activeJobs.get() >= maxConcurrent) {
            throw new IllegalStateException("Maximum " + maxConcurrent + " concurrent jobs allowed. Please wait.");
        }
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        NexusJob job = new NexusJob(id, repoUrl, branch, nexusUrl, artifactoryUrl, pushToNewBranch, targetBranchName);
        jobs.put(id, job);
        executor.submit(() -> runMigration(job, username, password, repoMappings));
        return job;
    }

    private void runMigration(NexusJob job, String username, String password,
                               Map<String, String> repoMappings) {
        activeJobs.incrementAndGet();
        String clonePath = null;
        try {
            job.setStatus("in_progress");

            job.updateStep("clone", "in_progress", "Cloning repository from Bitbucket...");
            boolean fullClone = job.isPushToNewBranch();
            clonePath = gitService.cloneRepo(job.getRepoUrl(), job.getBranch(), job.getId(),
                    username, password, fullClone);
            job.updateStep("clone", "completed", "Repository cloned — branch: " + job.getBranch());

            job.updateStep("scan", "in_progress", "Scanning for pom.xml and settings.xml files...");
            Map<String, Object> analysis = analysisService.analyze(clonePath, job.getNexusUrl());

            @SuppressWarnings("unchecked")
            List<String> pomFiles = (List<String>) analysis.getOrDefault("pomFiles", List.of());
            @SuppressWarnings("unchecked")
            List<String> settingsFiles = (List<String>) analysis.getOrDefault("settingsFiles", List.of());
            int total = pomFiles.size() + settingsFiles.size();
            job.updateStep("scan", "completed",
                    String.format("Found %d file(s): %d pom.xml, %d settings.xml",
                            total, pomFiles.size(), settingsFiles.size()));

            job.updateStep("analyze", "in_progress", "Analyzing Nexus repository references...");
            int filesWithNexus = (int) analysis.getOrDefault("filesWithNexus", 0);
            int totalRefs = (int) analysis.getOrDefault("totalReferences", 0);

            if (filesWithNexus == 0) {
                job.updateStep("analyze", "completed",
                        "No Nexus URL references found in " + total + " file(s) scanned");
                job.updateStep("migrate", "completed", "No changes required — project has no Nexus references");
                if (job.isPushToNewBranch()) {
                    job.updateStep("push", "completed", "Nothing to push — no Nexus references found");
                }
                job.updateStep("report", "completed", "Report ready");
                job.setReport(buildReport(analysis, null, null, job));
                job.markStatus("completed");
                return;
            }
            job.updateStep("analyze", "completed",
                    String.format("Found %d Nexus reference(s) across %d file(s)", totalRefs, filesWithNexus));

            job.updateStep("migrate", "in_progress",
                    String.format("Replacing %d Nexus URL(s) with Artifactory URLs...", totalRefs));
            Map<String, Object> migrationResult = migrationService.migrate(
                    clonePath, job.getNexusUrl(), job.getArtifactoryUrl(), repoMappings, analysis);
            int filesModified = (int) migrationResult.getOrDefault("filesModified", 0);
            int replacements = (int) migrationResult.getOrDefault("totalReplacements", 0);
            job.updateStep("migrate", "completed",
                    String.format("Updated %d file(s) — %d URL replacement(s) applied", filesModified, replacements));

            Map<String, Object> pushResult = null;
            if (job.isPushToNewBranch()) {
                job.updateStep("push", "in_progress", "Pushing changes to new branch...");
                pushResult = gitService.commitAndPushNexus(
                        clonePath, job.getTargetBranchName(),
                        username, password,
                        job.getNexusUrl(), job.getArtifactoryUrl());
                boolean pushed = Boolean.TRUE.equals(pushResult.get("pushed"));
                String pushedBranch = (String) pushResult.get("branch");
                job.updateStep("push", pushed ? "completed" : "failed",
                        (String) pushResult.getOrDefault("message",
                                pushed ? "Pushed to " + pushedBranch : "Push failed"));
            }

            job.updateStep("report", "in_progress", "Generating migration report...");
            Map<String, Object> report = buildReport(analysis, migrationResult, pushResult, job);
            job.setReport(report);
            job.updateStep("report", "completed",
                    String.format("Report ready — %d file(s) modified", filesModified));

            job.markStatus("completed");

        } catch (Exception e) {
            job.markStatus("failed");
            job.setError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            job.getSteps().stream()
                    .filter(s -> "in_progress".equals(s.getStatus()))
                    .findFirst()
                    .ifPresent(s -> { s.setStatus("failed"); s.setMessage(e.getMessage()); });
        } finally {
            activeJobs.decrementAndGet();
            if (clonePath != null) {
                gitService.cleanup(clonePath);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildReport(Map<String, Object> analysis,
                                              Map<String, Object> migrationResult,
                                              Map<String, Object> pushResult,
                                              NexusJob job) {
        Map<String, Object> report = new LinkedHashMap<>();

        List<String> pomFiles = (List<String>) analysis.getOrDefault("pomFiles", List.of());
        List<String> settingsFiles = (List<String>) analysis.getOrDefault("settingsFiles", List.of());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("repoUrl", job.getRepoUrl());
        summary.put("branch", job.getBranch());
        summary.put("nexusUrl", job.getNexusUrl());
        summary.put("artifactoryUrl", job.getArtifactoryUrl());
        summary.put("pomFilesScanned", pomFiles.size());
        summary.put("settingsFilesScanned", settingsFiles.size());
        summary.put("totalFilesScanned", pomFiles.size() + settingsFiles.size());
        summary.put("filesWithNexusRefs", analysis.getOrDefault("filesWithNexus", 0));
        summary.put("totalNexusReferences", analysis.getOrDefault("totalReferences", 0));
        summary.put("timeTaken", job.getTimeTaken());

        if (migrationResult != null) {
            summary.put("filesModified", migrationResult.getOrDefault("filesModified", 0));
            summary.put("totalReplacements", migrationResult.getOrDefault("totalReplacements", 0));
            report.put("changes", migrationResult.get("changes"));
        } else {
            summary.put("filesModified", 0);
            summary.put("totalReplacements", 0);
        }

        if (pushResult != null) {
            summary.put("pushed", pushResult.get("pushed"));
            summary.put("pushedBranch", pushResult.get("branch"));
            summary.put("pushMessage", pushResult.get("message"));
        }

        report.put("summary", summary);
        report.put("findings", analysis.getOrDefault("findings", List.of()));
        report.put("timestamp", Instant.now().toString());
        return report;
    }

    public NexusJob getJob(String id) { return jobs.get(id); }

    public List<NexusJob> getAllJobs() {
        List<NexusJob> list = new ArrayList<>(jobs.values());
        list.sort((a, b) -> {
            if (a.getCreatedAt() == null) return 1;
            if (b.getCreatedAt() == null) return -1;
            return b.getCreatedAt().compareTo(a.getCreatedAt());
        });
        return list;
    }
}
