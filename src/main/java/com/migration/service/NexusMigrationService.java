package com.migration.service;

import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
public class NexusMigrationService {

    public Map<String, Object> migrate(String projectPath,
                                        String nexusUrl,
                                        String artifactoryUrl,
                                        Map<String, String> repoMappings,
                                        Map<String, Object> analysis) throws Exception {

        String normNexus = normalize(nexusUrl);
        String normArtifactory = normalize(artifactoryUrl);

        @SuppressWarnings("unchecked")
        List<String> pomFiles = (List<String>) analysis.getOrDefault("pomFiles", List.of());
        @SuppressWarnings("unchecked")
        List<String> settingsFiles = (List<String>) analysis.getOrDefault("settingsFiles", List.of());

        List<Map<String, Object>> changes = new ArrayList<>();
        int totalReplacements = 0;

        List<String> allFiles = new ArrayList<>(pomFiles);
        allFiles.addAll(settingsFiles);

        for (String filePath : allFiles) {
            Map<String, Object> fileChange = migrateFile(filePath, normNexus, normArtifactory, repoMappings);
            int replacements = ((Number) fileChange.getOrDefault("replacements", 0)).intValue();
            if (replacements > 0) {
                changes.add(fileChange);
                totalReplacements += replacements;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("changes", changes);
        result.put("filesModified", changes.size());
        result.put("totalReplacements", totalReplacements);
        result.put("nexusUrl", normNexus);
        result.put("artifactoryUrl", normArtifactory);
        return result;
    }

    private Map<String, Object> migrateFile(String filePath,
                                              String nexusUrl,
                                              String artifactoryUrl,
                                              Map<String, String> repoMappings) {
        Map<String, Object> result = new LinkedHashMap<>();
        String fileName = Path.of(filePath).getFileName().toString();
        result.put("file", fileName);
        result.put("path", filePath);

        try {
            String original = Files.readString(Path.of(filePath));

            if (!original.contains(nexusUrl)) {
                result.put("replacements", 0);
                return result;
            }

            String modified = original.replace(nexusUrl, artifactoryUrl);

            if (repoMappings != null) {
                for (Map.Entry<String, String> mapping : repoMappings.entrySet()) {
                    if (!mapping.getKey().isBlank() && !mapping.getValue().isBlank()) {
                        modified = modified.replace(mapping.getKey(), mapping.getValue());
                    }
                }
            }

            Files.writeString(Path.of(filePath), modified);

            String[] origLines = original.split("\n");
            String[] newLines = modified.split("\n");
            List<Map<String, String>> lineChanges = new ArrayList<>();
            for (int i = 0; i < origLines.length && i < newLines.length; i++) {
                if (!origLines[i].equals(newLines[i])) {
                    Map<String, String> lc = new LinkedHashMap<>();
                    lc.put("line", String.valueOf(i + 1));
                    lc.put("before", origLines[i].trim());
                    lc.put("after", newLines[i].trim());
                    lineChanges.add(lc);
                }
            }

            int count = 0;
            int idx = 0;
            while ((idx = original.indexOf(nexusUrl, idx)) >= 0) { count++; idx += nexusUrl.length(); }

            result.put("replacements", count);
            result.put("lineChanges", lineChanges);

        } catch (Exception e) {
            result.put("replacements", 0);
            result.put("error", e.getMessage());
        }
        return result;
    }

    private String normalize(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
