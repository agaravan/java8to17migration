package com.migration.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Duration;
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
    private Instant completedAt;
    private List<MigrationStep> steps;
    private Map<String, Object> report;
    private String error;
    private boolean pushToNewBranch;
    private String targetBranchName;
    private Map<String, Object> pushResult;
    private int sourceVersion = 8;
    private int targetVersion = 17;

    public MigrationJob() {
        this.steps = new ArrayList<>();
    }

    public MigrationJob(String id, String repoUrl, String branch) {
        this.id = id;
        this.repoUrl = repoUrl;
        this.branch = branch;
        this.status = "queued";
        this.createdAt = Instant.now();
        this.steps = new ArrayList<>();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getStatus() { return status; }
    public void setStatus(String status) {
        this.status = status;
    }

    public void markStatus(String status) {
        this.status = status;
        if ("completed".equals(status) || "failed".equals(status)) {
            this.completedAt = Instant.now();
        }
    }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public List<MigrationStep> getSteps() { return steps; }
    public void setSteps(List<MigrationStep> steps) { this.steps = steps; }
    public Map<String, Object> getReport() { return report; }
    public void setReport(Map<String, Object> report) { this.report = report; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public boolean isPushToNewBranch() { return pushToNewBranch; }
    public void setPushToNewBranch(boolean pushToNewBranch) { this.pushToNewBranch = pushToNewBranch; }
    public String getTargetBranchName() { return targetBranchName; }
    public void setTargetBranchName(String targetBranchName) { this.targetBranchName = targetBranchName; }
    public Map<String, Object> getPushResult() { return pushResult; }
    public void setPushResult(Map<String, Object> pushResult) { this.pushResult = pushResult; }
    public int getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(int sourceVersion) { this.sourceVersion = sourceVersion; }
    public int getTargetVersion() { return targetVersion; }
    public void setTargetVersion(int targetVersion) { this.targetVersion = targetVersion; }

    @JsonIgnore
    public String getTotalTimeTaken() {
        if (completedAt == null) {
            if ("in_progress".equals(status) || "queued".equals(status)) {
                return formatDuration(Duration.between(createdAt, Instant.now()));
            }
            return "-";
        }
        return formatDuration(Duration.between(createdAt, completedAt));
    }

    @JsonIgnore
    public int getTotalJavaFiles() {
        if (report != null && report.get("summary") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = (Map<String, Object>) report.get("summary");
            Object val = summary.get("totalJavaFiles");
            if (val instanceof Number) return ((Number) val).intValue();
        }
        return 0;
    }

    @JsonIgnore
    public int getTotalIssues() {
        if (report != null && report.get("summary") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = (Map<String, Object>) report.get("summary");
            Object val = summary.get("totalIssuesFound");
            if (val instanceof Number) return ((Number) val).intValue();
        }
        return 0;
    }

    @JsonIgnore
    public int getTotalModules() {
        if (report != null && report.get("summary") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = (Map<String, Object>) report.get("summary");
            Object val = summary.get("totalModules");
            if (val instanceof Number) return ((Number) val).intValue();
        }
        return 0;
    }

    private String formatDuration(Duration d) {
        long totalSecs = d.getSeconds();
        if (totalSecs < 60) return totalSecs + "s";
        long mins = totalSecs / 60;
        long secs = totalSecs % 60;
        if (mins < 60) return mins + "m " + secs + "s";
        long hours = mins / 60;
        mins = mins % 60;
        return hours + "h " + mins + "m " + secs + "s";
    }

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
            this.completedAt = Instant.now();
        } else {
            this.status = "in_progress";
        }
    }
}
