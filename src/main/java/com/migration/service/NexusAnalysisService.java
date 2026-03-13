package com.migration.service;

import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class NexusAnalysisService {

    public Map<String, Object> analyze(String projectPath, String nexusUrl) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();

        String normalizedNexus = normalize(nexusUrl);

        List<String> pomFiles = new ArrayList<>();
        List<String> settingsFiles = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(Path.of(projectPath))) {
            walk.filter(p -> {
                    String s = p.toString().replace("\\", "/");
                    return !s.contains("/.git/") && !s.contains("/target/") && !s.contains("\\.git\\") && !s.contains("\\target\\");
                })
                .filter(Files::isRegularFile)
                .forEach(p -> {
                    String name = p.getFileName().toString();
                    if ("pom.xml".equals(name)) pomFiles.add(p.toString());
                    else if ("settings.xml".equals(name)) settingsFiles.add(p.toString());
                });
        }

        result.put("pomFiles", pomFiles);
        result.put("settingsFiles", settingsFiles);
        result.put("totalFiles", pomFiles.size() + settingsFiles.size());

        List<Map<String, Object>> findings = new ArrayList<>();
        int totalReferences = 0;

        List<String> allFiles = new ArrayList<>(pomFiles);
        allFiles.addAll(settingsFiles);

        for (String filePath : allFiles) {
            List<Map<String, Object>> refs = findNexusReferences(filePath, normalizedNexus);
            if (!refs.isEmpty()) {
                Map<String, Object> finding = new LinkedHashMap<>();
                finding.put("file", Path.of(filePath).getFileName().toString());
                finding.put("path", filePath);
                finding.put("referenceCount", refs.size());
                finding.put("references", refs);
                findings.add(finding);
                totalReferences += refs.size();
            }
        }

        result.put("findings", findings);
        result.put("filesWithNexus", findings.size());
        result.put("totalReferences", totalReferences);
        result.put("nexusUrl", normalizedNexus);

        return result;
    }

    private List<Map<String, Object>> findNexusReferences(String filePath, String nexusUrl) {
        List<Map<String, Object>> refs = new ArrayList<>();
        try {
            String content = Files.readString(Path.of(filePath));
            String[] lines = content.split("\n");
            Pattern urlPattern = Pattern.compile("(" + Pattern.quote(nexusUrl) + "[^<\"'\\s]*)");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(nexusUrl)) {
                    Map<String, Object> ref = new LinkedHashMap<>();
                    ref.put("line", i + 1);
                    ref.put("context", lines[i].trim());
                    Matcher m = urlPattern.matcher(lines[i]);
                    if (m.find()) ref.put("url", m.group(1));
                    refs.add(ref);
                }
            }
        } catch (Exception ignored) {}
        return refs;
    }

    public String normalize(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
