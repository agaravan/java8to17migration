package com.migration.service;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

@Service
public class GitService {

    @Value("${migration.workspace.dir}")
    private String workspaceDir;

    public String cloneRepo(String repoUrl, String branch, String migrationId, String username, String password) throws Exception {
        Path workspacePath = Path.of(workspaceDir);
        Files.createDirectories(workspacePath);

        Path clonePath = workspacePath.resolve(migrationId);
        if (Files.exists(clonePath)) {
            Files.walk(clonePath)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }

        CloneCommand cloneCommand = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(clonePath.toFile())
                .setBranch(branch)
                .setCloneAllBranches(false)
                .setDepth(1);

        if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
            CredentialsProvider creds = new UsernamePasswordCredentialsProvider(username, password);
            cloneCommand.setCredentialsProvider(creds);
        }

        try (Git git = cloneCommand.call()) {
            return clonePath.toAbsolutePath().toString();
        }
    }

    public void cleanup(String clonePath) {
        if (clonePath == null) return;
        try {
            Path path = Path.of(clonePath);
            if (Files.exists(path)) {
                Files.walk(path)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        } catch (Exception ignored) {}
    }
}
