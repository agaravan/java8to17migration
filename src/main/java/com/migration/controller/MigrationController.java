package com.migration.controller;

import com.migration.model.MigrationJob;
import com.migration.model.MigrationRequest;
import com.migration.model.Recipe;
import com.migration.service.MigrationOrchestrator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MigrationController {

    private final MigrationOrchestrator orchestrator;

    public MigrationController(MigrationOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("timestamp", java.time.Instant.now().toString());
        return response;
    }

    @PostMapping("/migrations")
    public ResponseEntity<?> startMigration(@RequestBody MigrationRequest request) {
        if (request.getRepoUrl() == null || request.getRepoUrl().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Repository URL is required"));
        }
        if (request.getBranch() == null || request.getBranch().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Branch is required"));
        }

        if (request.isPushToNewBranch()) {
            if (request.getUsername() == null || request.getUsername().isBlank()
                    || request.getPassword() == null || request.getPassword().isBlank()) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "Authentication (username and password/token) is required when pushing to a new branch"));
            }
        }

        try {
            MigrationJob job = orchestrator.startMigration(
                    request.getRepoUrl(), request.getBranch(),
                    request.getUsername(), request.getPassword(),
                    request.isPushToNewBranch(), request.getTargetBranchName());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("migrationId", job.getId());
            response.put("status", job.getStatus());
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(429).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/migrations")
    public List<MigrationJob> listMigrations() {
        return orchestrator.getAllJobs();
    }

    @GetMapping("/migrations/{id}")
    public ResponseEntity<?> getMigration(@PathVariable String id) {
        MigrationJob job = orchestrator.getJob(id);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(job);
    }

    @GetMapping("/migrations/{id}/report")
    public ResponseEntity<?> getReport(@PathVariable String id) {
        MigrationJob job = orchestrator.getJob(id);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        if (job.getReport() == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Report not yet available"));
        }
        return ResponseEntity.ok(job.getReport());
    }

    @GetMapping("/recipes")
    public List<Recipe> getRecipes() {
        return orchestrator.getAvailableRecipes();
    }
}
