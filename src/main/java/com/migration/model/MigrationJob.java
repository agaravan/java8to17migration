package com.migration.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MigrationJob {
    private String id;
    private String repoUrl;
    private String branch;
    private String status;
    private Instant createdAt;
    private List<MigrationStep> steps;
    private Map<String, Object> report;
    private String error;

    public MigrationJob(String id, String repoUrl, String branch) {
        this.id = id;
        this.repoUrl = repoUrl;
        this.branch = branch;
        this.status = "queued";
        this.createdAt = Instant.now();
        this.steps = new ArrayList<>();
    }

    public String getId() { return id; }
    public String getRepoUrl() { return repoUrl; }
    public String getBranch() { return branch; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public List<MigrationStep> getSteps() { return steps; }
    public Map<String, Object> getReport() { return report; }
    public void setReport(Map<String, Object> report) { this.report = report; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public void updateStep(String stepName, String status, String message) {
        MigrationStep existing = steps.stream()
                .filter(s -> s.getName().equals(stepName))
                .findFirst().orElse(null);
        if (existing != null) {
            existing.setStatus(status);
            existing.setMessage(message);
        } else {
            steps.add(new MigrationStep(stepName, status, message));
        }
        if ("failed".equals(status)) {
            this.status = "failed";
        } else {
            this.status = "in_progress";
        }
    }
}
