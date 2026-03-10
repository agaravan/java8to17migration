package com.migration.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.*;

@Service
public class ChangeHistoryService {

    public List<Map<String, Object>> captureChanges(String projectPath) {
        List<Map<String, Object>> fileChanges = new ArrayList<>();

        try (Git git = Git.open(new File(projectPath))) {
            Repository repo = git.getRepository();

            Iterable<RevCommit> commits = git.log().setMaxCount(1).call();
            RevCommit headCommit = commits.iterator().next();

            ObjectReader reader = repo.newObjectReader();
            CanonicalTreeParser oldTreeParser = new CanonicalTreeParser();
            oldTreeParser.reset(reader, headCommit.getTree());

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DiffFormatter df = new DiffFormatter(out);
            df.setRepository(repo);
            df.setDiffComparator(RawTextComparator.DEFAULT);
            df.setDetectRenames(true);

            List<DiffEntry> diffs = df.scan(oldTreeParser, new FileTreeIterator(repo));

            for (DiffEntry entry : diffs) {
                Map<String, Object> change = new LinkedHashMap<>();
                String filePath = entry.getChangeType() == DiffEntry.ChangeType.DELETE
                        ? entry.getOldPath() : entry.getNewPath();

                change.put("file", filePath);
                change.put("changeType", entry.getChangeType().name());

                String ext = "";
                int dotIdx = filePath.lastIndexOf('.');
                if (dotIdx > 0) ext = filePath.substring(dotIdx + 1);
                change.put("fileType", categorizeFileType(ext));

                try {
                    FileHeader fh = df.toFileHeader(entry);
                    EditList edits = fh.toEditList();
                    int linesAdded = 0;
                    int linesRemoved = 0;
                    for (var edit : edits) {
                        linesAdded += edit.getEndB() - edit.getBeginB();
                        linesRemoved += edit.getEndA() - edit.getBeginA();
                    }
                    change.put("linesAdded", linesAdded);
                    change.put("linesRemoved", linesRemoved);
                    change.put("hunks", edits.size());

                    out.reset();
                    df.format(entry);
                    String diffText = out.toString();
                    if (diffText.length() > 3000) {
                        diffText = diffText.substring(0, 3000) + "\n... (truncated)";
                    }
                    change.put("diff", diffText);
                } catch (Exception e) {
                    change.put("linesAdded", 0);
                    change.put("linesRemoved", 0);
                    change.put("hunks", 0);
                    change.put("diff", "");
                }

                fileChanges.add(change);
            }

            df.close();

        } catch (Exception e) {
            Map<String, Object> errorEntry = new LinkedHashMap<>();
            errorEntry.put("file", "error");
            errorEntry.put("changeType", "ERROR");
            errorEntry.put("message", "Could not capture changes: " + e.getMessage());
            fileChanges.add(errorEntry);
        }

        return fileChanges;
    }

    private String categorizeFileType(String ext) {
        switch (ext.toLowerCase()) {
            case "java": return "java";
            case "xml": return "config";
            case "properties": return "config";
            case "yml": case "yaml": return "config";
            case "gradle": return "build";
            default: return "other";
        }
    }

    public Map<String, Object> summarizeChanges(List<Map<String, Object>> changes) {
        Map<String, Object> summary = new LinkedHashMap<>();
        int totalFiles = 0;
        int totalAdded = 0;
        int totalRemoved = 0;
        int javaFilesChanged = 0;
        int configFilesChanged = 0;
        int modified = 0;
        int added = 0;
        int deleted = 0;
        String captureError = null;

        for (Map<String, Object> c : changes) {
            String type = (String) c.getOrDefault("changeType", "");
            if ("ERROR".equals(type)) {
                captureError = (String) c.getOrDefault("message", "Unknown error");
                continue;
            }
            totalFiles++;

            switch (type) {
                case "MODIFY": modified++; break;
                case "ADD": added++; break;
                case "DELETE": deleted++; break;
            }

            Object la = c.get("linesAdded");
            Object lr = c.get("linesRemoved");
            if (la instanceof Number) totalAdded += ((Number) la).intValue();
            if (lr instanceof Number) totalRemoved += ((Number) lr).intValue();

            String ft = (String) c.getOrDefault("fileType", "other");
            if ("java".equals(ft)) javaFilesChanged++;
            if ("config".equals(ft)) configFilesChanged++;
        }

        summary.put("totalFilesChanged", totalFiles);
        summary.put("filesModified", modified);
        summary.put("filesAdded", added);
        summary.put("filesDeleted", deleted);
        summary.put("totalLinesAdded", totalAdded);
        summary.put("totalLinesRemoved", totalRemoved);
        summary.put("javaFilesChanged", javaFilesChanged);
        summary.put("configFilesChanged", configFilesChanged);
        if (captureError != null) {
            summary.put("captureError", captureError);
        }

        return summary;
    }
}
