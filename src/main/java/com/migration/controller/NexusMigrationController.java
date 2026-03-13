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
        String projectPath = (String) request.get("projectPath");
        String nexusUrl = (String) request.get("nexusUrl");
        String artifactoryUrl = (String) request.get("artifactoryUrl");

        if (projectPath == null || projectPath.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Project path is required"));
        if (nexusUrl == null || nexusUrl.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Nexus base URL is required"));
        if (artifactoryUrl == null || artifactoryUrl.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Artifactory base URL is required"));

        @SuppressWarnings("unchecked")
        Map<String, String> repoMappings = request.containsKey("repoMappings")
                ? (Map<String, String>) request.get("repoMappings")
                : null;

        NexusJob job = orchestrator.startMigration(projectPath.trim(), nexusUrl.trim(),
                artifactoryUrl.trim(), repoMappings);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", job.getId());
        response.put("status", job.getStatus());
        return ResponseEntity.ok(response);
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
