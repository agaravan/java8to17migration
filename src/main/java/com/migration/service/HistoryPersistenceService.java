package com.migration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.migration.model.MigrationJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Service
public class HistoryPersistenceService {

    @Value("${migration.history.dir:./data/history}")
    private String historyDir;

    private final ObjectMapper mapper;

    public HistoryPersistenceService() {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(Path.of(historyDir));
    }

    public void saveJob(MigrationJob job) {
        try {
            File file = Path.of(historyDir, job.getId() + ".json").toFile();
            mapper.writeValue(file, job);
        } catch (IOException e) {
            System.err.println("Failed to persist migration job " + job.getId() + ": " + e.getMessage());
        }
    }

    public Map<String, MigrationJob> loadAllJobs() {
        Map<String, MigrationJob> jobs = new ConcurrentHashMap<>();
        File dir = new File(historyDir);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return jobs;

        for (File file : files) {
            try {
                MigrationJob job = mapper.readValue(file, MigrationJob.class);
                jobs.put(job.getId(), job);
            } catch (IOException e) {
                System.err.println("Failed to load migration history from " + file.getName() + ": " + e.getMessage());
            }
        }
        System.out.println("Loaded " + jobs.size() + " migration(s) from history");
        return jobs;
    }

    public void deleteJob(String jobId) {
        try {
            Files.deleteIfExists(Path.of(historyDir, jobId + ".json"));
        } catch (IOException e) {
            System.err.println("Failed to delete history file for " + jobId + ": " + e.getMessage());
        }
    }
}
