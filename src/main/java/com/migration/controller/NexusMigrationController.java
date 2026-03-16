package com.migration.controller;

import com.migration.model.NexusJob;
import com.migration.service.NexusMigrationOrchestrator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/nexus")
public class NexusMigrationController {

    private final NexusMigrationOrchestrator orchestrator;

    public NexusMigrationController(NexusMigrationOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/start")
    public ResponseEntity<?> startMigration(@RequestBody Map<String, Object> request) {
        String repoUrl = (String) request.get("repoUrl");
        String branch = (String) request.get("branch");
        String username = (String) request.getOrDefault("username", "");
        String password = (String) request.getOrDefault("password", "");
        boolean pushToNewBranch = Boolean.TRUE.equals(request.get("pushToNewBranch"));
        String targetBranchName = (String) request.getOrDefault("targetBranchName", "");
        String nexusUrl = (String) request.get("nexusUrl");
        String artifactoryUrl = (String) request.get("artifactoryUrl");

        if (repoUrl == null || repoUrl.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Repository URL is required"));
        if (branch == null || branch.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Branch is required"));
        if (nexusUrl == null || nexusUrl.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Nexus base URL is required"));
        if (artifactoryUrl == null || artifactoryUrl.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Artifactory base URL is required"));

        if (pushToNewBranch && (username == null || username.isBlank() || password == null || password.isBlank())) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Username and password/token are required when pushing to a new branch"));
        }
        if (pushToNewBranch && targetBranchName != null && !targetBranchName.isBlank()) {
            String b = targetBranchName.trim().toLowerCase();
            if (b.equals("master") || b.equals("main") || b.equals("develop")
                    || b.equals("release") || b.startsWith("release/")) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "Target branch '" + targetBranchName.trim() + "' is a protected branch. "
                        + "Please choose a feature branch name (e.g. nexus-to-artifactory/myapp)."));
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, String> repoMappings = request.containsKey("repoMappings")
                ? (Map<String, String>) request.get("repoMappings")
                : null;

        try {
            NexusJob job = orchestrator.startMigration(
                    repoUrl.trim(), branch.trim(),
                    username, password,
                    pushToNewBranch, targetBranchName,
                    nexusUrl.trim(), artifactoryUrl.trim(),
                    repoMappings);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jobId", job.getId());
            response.put("status", job.getStatus());
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(429).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<?> getJob(@PathVariable String id) {
        NexusJob job = orchestrator.getJob(id);
        if (job == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(job);
    }

    @GetMapping("/jobs")
    public List<NexusJob> getAllJobs() {
        return orchestrator.getAllJobs();
    }
}
