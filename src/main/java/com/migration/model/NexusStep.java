package com.migration.model;

import java.time.Instant;

public class NexusStep {
    private String name;
    private String status;
    private String message;
    private Instant updatedAt;

    public NexusStep() {}

    public NexusStep(String name, String status, String message) {
        this.name = name;
        this.status = status;
        this.message = message;
        this.updatedAt = Instant.now();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; this.updatedAt = Instant.now(); }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
