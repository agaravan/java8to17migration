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

        for (String filePath : pomFiles) {
            Map<String, Object> fileChange = migrateFile(filePath, normNexus, normArtifactory, repoMappings, true);
            int replacements = ((Number) fileChange.getOrDefault("replacements", 0)).intValue();
            if (replacements > 0) { changes.add(fileChange); totalReplacements += replacements; }
        }

        for (String filePath : settingsFiles) {
            Map<String, Object> fileChange = migrateFile(filePath, normNexus, normArtifactory, repoMappings, false);
            int replacements = ((Number) fileChange.getOrDefault("replacements", 0)).intValue();
            if (replacements > 0) { changes.add(fileChange); totalReplacements += replacements; }
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
                                              Map<String, String> repoMappings,
                                              boolean skipDependencyManagement) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", Path.of(filePath).getFileName().toString());
        result.put("path", filePath);

        try {
            String original = Files.readString(Path.of(filePath));

            if (!original.contains(nexusUrl)) {
                result.put("replacements", 0);
                return result;
            }

            String modified;
            if (skipDependencyManagement) {
                modified = replaceSkippingSection(original, nexusUrl, artifactoryUrl, "dependencyManagement");
            } else {
                modified = original.replace(nexusUrl, artifactoryUrl);
            }

            if (repoMappings != null) {
                for (Map.Entry<String, String> mapping : repoMappings.entrySet()) {
                    if (!mapping.getKey().isBlank() && !mapping.getValue().isBlank()) {
                        if (skipDependencyManagement) {
                            modified = replaceSkippingSection(modified, mapping.getKey(), mapping.getValue(), "dependencyManagement");
                        } else {
                            modified = modified.replace(mapping.getKey(), mapping.getValue());
                        }
                    }
                }
            }

            if (modified.equals(original)) {
                result.put("replacements", 0);
                return result;
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

            int count = countOccurrences(original, modified, nexusUrl, skipDependencyManagement);
            result.put("replacements", count);
            result.put("lineChanges", lineChanges);
            if (skipDependencyManagement) {
                result.put("note", "dependencyManagement section was not modified");
            }

        } catch (Exception e) {
            result.put("replacements", 0);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * Replaces all occurrences of {@code search} with {@code replacement} in {@code content},
     * but leaves the entire {@code <sectionTag>...</sectionTag>} block untouched.
     */
    static String replaceSkippingSection(String content, String search, String replacement, String sectionTag) {
        String openTag = "<" + sectionTag;
        String closeTag = "</" + sectionTag + ">";

        StringBuilder result = new StringBuilder();
        int pos = 0;

        while (pos < content.length()) {
            int dmStart = content.indexOf(openTag, pos);
            if (dmStart < 0) {
                result.append(content.substring(pos).replace(search, replacement));
                break;
            }
            result.append(content.substring(pos, dmStart).replace(search, replacement));

            int dmEnd = content.indexOf(closeTag, dmStart);
            if (dmEnd < 0) {
                result.append(content.substring(dmStart));
                break;
            }
            dmEnd += closeTag.length();
            result.append(content, dmStart, dmEnd);
            pos = dmEnd;
        }

        return result.toString();
    }

    private int countOccurrences(String original, String modified, String search, boolean skipDependencyManagement) {
        String searchable = skipDependencyManagement
                ? stripSection(original, "dependencyManagement")
                : original;
        int count = 0;
        int idx = 0;
        while ((idx = searchable.indexOf(search, idx)) >= 0) { count++; idx += search.length(); }
        return count;
    }

    private String stripSection(String content, String sectionTag) {
        String openTag = "<" + sectionTag;
        String closeTag = "</" + sectionTag + ">";
        StringBuilder result = new StringBuilder();
        int pos = 0;
        while (pos < content.length()) {
            int start = content.indexOf(openTag, pos);
            if (start < 0) { result.append(content.substring(pos)); break; }
            result.append(content, pos, start);
            int end = content.indexOf(closeTag, start);
            if (end < 0) break;
            pos = end + closeTag.length();
        }
        return result.toString();
    }

    private String normalize(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
