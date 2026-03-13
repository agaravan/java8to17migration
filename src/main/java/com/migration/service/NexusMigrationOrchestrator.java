package com.migration.service;

import com.migration.model.NexusJob;
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

    private final NexusAnalysisService analysisService;
    private final NexusMigrationService migrationService;

    public NexusMigrationOrchestrator(NexusAnalysisService analysisService,
                                       NexusMigrationService migrationService) {
        this.analysisService = analysisService;
        this.migrationService = migrationService;
    }

    public NexusJob startMigration(String projectPath, String nexusUrl,
                                    String artifactoryUrl, Map<String, String> repoMappings) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        NexusJob job = new NexusJob(id, projectPath, nexusUrl, artifactoryUrl);
        jobs.put(id, job);
        executor.submit(() -> runMigration(job, repoMappings));
        return job;
    }

    private void runMigration(NexusJob job, Map<String, String> repoMappings) {
        activeJobs.incrementAndGet();
        try {
            job.setStatus("in_progress");

            job.updateStep("scan", "in_progress", "Scanning project for pom.xml and settings.xml files...");
            Map<String, Object> analysis = analysisService.analyze(job.getProjectPath(), job.getNexusUrl());

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
                job.updateStep("report", "completed", "Report ready");
                job.setReport(buildReport(analysis, null, job));
                job.markStatus("completed");
                return;
            }
            job.updateStep("analyze", "completed",
                String.format("Found %d Nexus reference(s) across %d file(s)", totalRefs, filesWithNexus));

            job.updateStep("migrate", "in_progress",
                String.format("Replacing %d Nexus URL(s) with Artifactory URLs...", totalRefs));
            Map<String, Object> migrationResult = migrationService.migrate(
                job.getProjectPath(), job.getNexusUrl(), job.getArtifactoryUrl(), repoMappings, analysis);
            int filesModified = (int) migrationResult.getOrDefault("filesModified", 0);
            int replacements = (int) migrationResult.getOrDefault("totalReplacements", 0);
            job.updateStep("migrate", "completed",
                String.format("Updated %d file(s) — %d URL replacement(s) applied", filesModified, replacements));

            job.updateStep("report", "in_progress", "Generating migration report...");
            Map<String, Object> report = buildReport(analysis, migrationResult, job);
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
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildReport(Map<String, Object> analysis,
                                              Map<String, Object> migrationResult,
                                              NexusJob job) {
        Map<String, Object> report = new LinkedHashMap<>();

        Map<String, Object> summary = new LinkedHashMap<>();
        List<String> pomFiles = (List<String>) analysis.getOrDefault("pomFiles", List.of());
        List<String> settingsFiles = (List<String>) analysis.getOrDefault("settingsFiles", List.of());
        summary.put("pomFilesScanned", pomFiles.size());
        summary.put("settingsFilesScanned", settingsFiles.size());
        summary.put("totalFilesScanned", pomFiles.size() + settingsFiles.size());
        summary.put("filesWithNexusRefs", analysis.getOrDefault("filesWithNexus", 0));
        summary.put("totalNexusReferences", analysis.getOrDefault("totalReferences", 0));
        summary.put("nexusUrl", job.getNexusUrl());
        summary.put("artifactoryUrl", job.getArtifactoryUrl());
        summary.put("projectPath", job.getProjectPath());
        summary.put("timeTaken", job.getTimeTaken());

        if (migrationResult != null) {
            summary.put("filesModified", migrationResult.getOrDefault("filesModified", 0));
            summary.put("totalReplacements", migrationResult.getOrDefault("totalReplacements", 0));
            report.put("changes", migrationResult.get("changes"));
        } else {
            summary.put("filesModified", 0);
            summary.put("totalReplacements", 0);
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
