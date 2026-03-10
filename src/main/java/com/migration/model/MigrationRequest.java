package com.migration.model;

public class MigrationRequest {
    private String repoUrl;
    private String branch;
    private String username;
    private String password;
    private boolean pushToNewBranch;
    private String targetBranchName;
    private int sourceVersion = 8;
    private int targetVersion = 17;

    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public boolean isPushToNewBranch() { return pushToNewBranch; }
    public void setPushToNewBranch(boolean pushToNewBranch) { this.pushToNewBranch = pushToNewBranch; }
    public String getTargetBranchName() { return targetBranchName; }
    public void setTargetBranchName(String targetBranchName) { this.targetBranchName = targetBranchName; }
    public int getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(int sourceVersion) { this.sourceVersion = sourceVersion; }
    public int getTargetVersion() { return targetVersion; }
    public void setTargetVersion(int targetVersion) { this.targetVersion = targetVersion; }
}
