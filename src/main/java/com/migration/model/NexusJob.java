package com.migration.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class NexusJob {
    private String id;
    private String repoUrl;
    private String branch;
    private String nexusUrl;
    private String artifactoryUrl;
    private boolean pushToNewBranch;
    private String targetBranchName;
    private String status;
    private Instant createdAt;
    private Instant completedAt;
    private List<NexusStep> steps;
    private Map<String, Object> report;
    private String error;

    public NexusJob() { this.steps = new ArrayList<>(); }

    public NexusJob(String id, String repoUrl, String branch,
                    String nexusUrl, String artifactoryUrl,
                    boolean pushToNewBranch, String targetBranchName) {
        this.id = id;
        this.repoUrl = repoUrl;
        this.branch = branch;
        this.nexusUrl = nexusUrl;
        this.artifactoryUrl = artifactoryUrl;
        this.pushToNewBranch = pushToNewBranch;
        this.targetBranchName = targetBranchName;
        this.status = "queued";
        this.createdAt = Instant.now();
        this.steps = new ArrayList<>();
    }

    public void markStatus(String status) {
        this.status = status;
        if ("completed".equals(status) || "failed".equals(status)) {
            this.completedAt = Instant.now();
        }
    }

    public void updateStep(String name, String stepStatus, String message) {
        NexusStep existing = steps.stream().filter(s -> s.getName().equals(name)).findFirst().orElse(null);
        if (existing != null) {
            existing.setStatus(stepStatus);
            existing.setMessage(message);
        } else {
            steps.add(new NexusStep(name, stepStatus, message));
        }
    }

    @JsonIgnore
    public String getTimeTaken() {
        if (createdAt == null) return "-";
        Instant end = completedAt != null ? completedAt : Instant.now();
        long ms = Duration.between(createdAt, end).toMillis();
        if (ms < 1000) return ms + "ms";
        long secs = ms / 1000;
        if (secs < 60) return secs + "s";
        long mins = secs / 60;
        secs = secs % 60;
        return mins + "m " + secs + "s";
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getNexusUrl() { return nexusUrl; }
    public void setNexusUrl(String nexusUrl) { this.nexusUrl = nexusUrl; }
    public String getArtifactoryUrl() { return artifactoryUrl; }
    public void setArtifactoryUrl(String artifactoryUrl) { this.artifactoryUrl = artifactoryUrl; }
    public boolean isPushToNewBranch() { return pushToNewBranch; }
    public void setPushToNewBranch(boolean pushToNewBranch) { this.pushToNewBranch = pushToNewBranch; }
    public String getTargetBranchName() { return targetBranchName; }
    public void setTargetBranchName(String targetBranchName) { this.targetBranchName = targetBranchName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public List<NexusStep> getSteps() { return steps; }
    public void setSteps(List<NexusStep> steps) { this.steps = steps; }
    public Map<String, Object> getReport() { return report; }
    public void setReport(Map<String, Object> report) { this.report = report; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
