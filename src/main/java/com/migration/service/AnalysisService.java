package com.migration.service;

import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

@Service
public class AnalysisService {

    public Map<String, Object> analyzeProject(String projectPath) throws Exception {
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("projectPath", projectPath);

        List<String> pomFiles = findFiles(projectPath, "pom.xml");
        List<String> javaFiles = findFiles(projectPath, ".java");
        analysis.put("pomFiles", pomFiles);
        analysis.put("javaFileCount", javaFiles.size());

        String sourceVersion = "unknown";
        boolean hasLombok = false;
        boolean hasJaxb = false;
        boolean hasJaxws = false;
        boolean hasSpring = false;
        boolean hasSpringBoot = false;
        boolean hasJunit4 = false;
        boolean hasPersistence = false;
        boolean hasJavafx = false;
        List<Map<String, Object>> modules = new ArrayList<>();

        for (String pomFile : pomFiles) {
            Map<String, Object> pomAnalysis = analyzePom(pomFile);
            modules.add(pomAnalysis);

            String ver = (String) pomAnalysis.getOrDefault("sourceVersion", "unknown");
            if (!"unknown".equals(ver)) sourceVersion = ver;
            if (Boolean.TRUE.equals(pomAnalysis.get("hasLombok"))) hasLombok = true;
            if (Boolean.TRUE.equals(pomAnalysis.get("hasJaxb"))) hasJaxb = true;
            if (Boolean.TRUE.equals(pomAnalysis.get("hasJaxws"))) hasJaxws = true;
            if (Boolean.TRUE.equals(pomAnalysis.get("hasSpring"))) hasSpring = true;
            if (Boolean.TRUE.equals(pomAnalysis.get("hasSpringBoot"))) hasSpringBoot = true;
            if (Boolean.TRUE.equals(pomAnalysis.get("hasJunit4"))) hasJunit4 = true;
            if (Boolean.TRUE.equals(pomAnalysis.get("hasPersistence"))) hasPersistence = true;
            if (Boolean.TRUE.equals(pomAnalysis.get("hasJavafx"))) hasJavafx = true;
        }

        analysis.put("modules", modules);
        analysis.put("sourceVersion", sourceVersion);
        analysis.put("hasLombok", hasLombok);
        analysis.put("hasJaxb", hasJaxb);
        analysis.put("hasJaxws", hasJaxws);
        analysis.put("hasSpring", hasSpring);
        analysis.put("hasSpringBoot", hasSpringBoot);
        analysis.put("hasJunit4", hasJunit4);
        analysis.put("hasPersistence", hasPersistence);
        analysis.put("hasJavafx", hasJavafx);

        List<Map<String, String>> issues = analyzeJavaFiles(javaFiles);
        analysis.put("issues", issues);
        analysis.put("hasFinalize", issues.stream().anyMatch(i -> "deprecated-finalize".equals(i.get("type"))));
        analysis.put("hasSecurityManager", issues.stream().anyMatch(i -> "removed-security-manager".equals(i.get("type"))));

        List<Map<String, String>> recommendations = generateRecommendations(analysis);
        analysis.put("recommendations", recommendations);

        return analysis;
    }

    private List<String> findFiles(String dir, String pattern) throws IOException {
        List<String> results = new ArrayList<>();
        Path root = Path.of(dir);
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                String name = d.getFileName().toString();
                if ("node_modules".equals(name) || "target".equals(name) || ".git".equals(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String fileName = file.getFileName().toString();
                if (pattern.startsWith(".")) {
                    if (fileName.endsWith(pattern)) results.add(file.toAbsolutePath().toString());
                } else {
                    if (fileName.equals(pattern)) results.add(file.toAbsolutePath().toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return results;
    }

    private Map<String, Object> analyzePom(String pomFile) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", pomFile);
        result.put("sourceVersion", "unknown");
        result.put("hasLombok", false);
        result.put("hasJaxb", false);
        result.put("hasJaxws", false);
        result.put("hasSpring", false);
        result.put("hasSpringBoot", false);
        result.put("hasJunit4", false);
        result.put("hasPersistence", false);
        result.put("hasJavafx", false);
        result.put("packaging", "jar");

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new File(pomFile));
            doc.getDocumentElement().normalize();

            result.put("groupId", getElementText(doc, "groupId"));
            result.put("artifactId", getElementText(doc, "artifactId"));
            result.put("version", getElementText(doc, "version"));

            NodeList properties = doc.getElementsByTagName("properties");
            if (properties.getLength() > 0) {
                Element props = (Element) properties.item(0);
                String compilerSource = getChildText(props, "maven.compiler.source");
                String compilerTarget = getChildText(props, "maven.compiler.target");
                String javaVersion = getChildText(props, "java.version");

                if (compilerSource != null) result.put("sourceVersion", compilerSource);
                else if (javaVersion != null) result.put("sourceVersion", javaVersion);
                if (compilerTarget != null) result.put("targetVersion", compilerTarget);
            }

            NodeList deps = doc.getElementsByTagName("dependency");
            List<Map<String, String>> dependencies = new ArrayList<>();
            for (int i = 0; i < deps.getLength(); i++) {
                Element dep = (Element) deps.item(i);
                String groupId = getChildText(dep, "groupId");
                String artifactId = getChildText(dep, "artifactId");
                String version = getChildText(dep, "version");

                if (groupId == null) continue;

                Map<String, String> depInfo = new LinkedHashMap<>();
                depInfo.put("groupId", groupId);
                depInfo.put("artifactId", artifactId != null ? artifactId : "");
                depInfo.put("version", version != null ? version : "");
                dependencies.add(depInfo);

                if ("lombok".equals(artifactId)) result.put("hasLombok", true);
                if (groupId.contains("javax.xml.bind") || (artifactId != null && artifactId.contains("jaxb")))
                    result.put("hasJaxb", true);
                if (groupId.contains("javax.xml.ws") || (artifactId != null && artifactId.contains("jaxws")))
                    result.put("hasJaxws", true);
                if (groupId.contains("springframework")) result.put("hasSpring", true);
                if (artifactId != null && artifactId.contains("spring-boot")) result.put("hasSpringBoot", true);
                if ("junit".equals(groupId) && (artifactId == null || "junit".equals(artifactId)))
                    result.put("hasJunit4", true);
                if ("org.junit.vintage".equals(groupId)) result.put("hasJunit4", true);
                if (groupId.contains("javax.persistence") || groupId.contains("jakarta.persistence") ||
                        (artifactId != null && (artifactId.contains("hibernate-core") || artifactId.contains("persistence-api"))))
                    result.put("hasPersistence", true);
                if (groupId.contains("javafx") || (artifactId != null && artifactId.contains("javafx")))
                    result.put("hasJavafx", true);
            }
            result.put("dependencies", dependencies);
            result.put("dependencyCount", dependencies.size());

            NodeList packagingNodes = doc.getElementsByTagName("packaging");
            if (packagingNodes.getLength() > 0) {
                result.put("packaging", packagingNodes.item(0).getTextContent().trim());
            }

        } catch (Exception e) {
            result.put("parseError", e.getMessage());
        }
        return result;
    }

    private String getElementText(Document doc, String tagName) {
        NodeList list = doc.getDocumentElement().getElementsByTagName(tagName);
        if (list.getLength() > 0) return list.item(0).getTextContent().trim();
        return "";
    }

    private String getChildText(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagName(tagName);
        if (list.getLength() > 0) return list.item(0).getTextContent().trim();
        return null;
    }

    private List<Map<String, String>> analyzeJavaFiles(List<String> javaFiles) {
        List<Map<String, String>> issues = new ArrayList<>();

        for (String filePath : javaFiles) {
            try {
                String content = Files.readString(Path.of(filePath));
                String fileName = Path.of(filePath).getFileName().toString();

                checkIssue(issues, content, fileName, "sun.misc.", "sun.reflect.",
                        "internal-api", "high",
                        "Uses internal sun.* APIs that are encapsulated in Java 17",
                        "Replace with public API alternatives");

                if (content.contains("javax.xml.bind") && !content.contains("import jakarta")) {
                    issues.add(createIssue(fileName, "removed-module", "high",
                            "Uses javax.xml.bind (JAXB) which was removed in Java 11",
                            "Add explicit JAXB dependency and update imports"));
                }

                checkIssue(issues, content, fileName, "javax.xml.ws", null,
                        "removed-module", "high",
                        "Uses javax.xml.ws (JAX-WS) which was removed in Java 11",
                        "Add explicit JAX-WS dependency");

                checkIssue(issues, content, fileName, "javax.annotation", null,
                        "removed-module", "medium",
                        "Uses javax.annotation which was removed in Java 11",
                        "Add javax.annotation-api dependency");

                checkIssue(issues, content, fileName, "javax.activation", null,
                        "removed-module", "medium",
                        "Uses javax.activation which was removed in Java 11",
                        "Add jakarta.activation dependency");

                checkIssue(issues, content, fileName, "java.security.acl", null,
                        "removed-api", "medium",
                        "Uses java.security.acl which was removed in Java 14",
                        "Migrate to java.security package");

                if (content.contains("Nashorn") || content.contains("jdk.nashorn")) {
                    issues.add(createIssue(fileName, "removed-engine", "high",
                            "Uses Nashorn JavaScript engine which was removed in Java 15",
                            "Migrate to GraalVM JavaScript or another JS engine"));
                }

                if (content.contains("setAccessible(true)")) {
                    issues.add(createIssue(fileName, "strong-encapsulation", "medium",
                            "Uses reflection with setAccessible(true) which may fail under strong encapsulation in Java 17",
                            "Use --add-opens JVM flags or refactor to avoid deep reflection"));
                }

                if (content.contains("new Integer(") || content.contains("new Long(") ||
                        content.contains("new Double(") || content.contains("new Float(")) {
                    issues.add(createIssue(fileName, "deprecated-constructor", "low",
                            "Uses deprecated wrapper class constructors (removed in later versions)",
                            "Use valueOf() factory methods instead"));
                }

                if (content.contains("SecurityManager") || content.contains("setSecurityManager")) {
                    issues.add(createIssue(fileName, "removed-security-manager", "high",
                            "Uses SecurityManager which was permanently removed in Java 17",
                            "Remove all SecurityManager usage - there is no direct replacement in Java 17+"));
                }

                if ((content.contains(".stop()") || content.contains(".suspend()") || content.contains(".resume()"))
                        && (content.contains("Thread") || content.contains("thread"))) {
                    issues.add(createIssue(fileName, "removed-api", "high",
                            "Uses Thread.stop()/suspend()/resume() which were removed in Java 21",
                            "Replace with Thread.interrupt() and cooperative interruption patterns"));
                }

                if (content.contains("protected void finalize(") || content.contains("public void finalize(")) {
                    issues.add(createIssue(fileName, "deprecated-finalize", "medium",
                            "Overrides finalize() which is deprecated for removal since Java 18",
                            "Replace with java.lang.ref.Cleaner or try-with-resources pattern"));
                }

                if (content.contains("javax.persistence") && !content.contains("jakarta.persistence")) {
                    issues.add(createIssue(fileName, "removed-module", "high",
                            "Uses javax.persistence (JPA) which requires an explicit dependency in Java 11+",
                            "Add jakarta.persistence-api dependency and update imports to jakarta.persistence"));
                }

                if (content.contains("import javafx.") || content.contains("import com.sun.javafx")) {
                    issues.add(createIssue(fileName, "removed-module", "high",
                            "Uses JavaFX which was removed from the JDK in Java 11",
                            "Add OpenJFX dependency explicitly: org.openjfx:javafx-controls:17+"));
                }

                if (content.contains("java.lang.Compiler") || content.contains("Compiler.enable()") || content.contains("Compiler.disable()")) {
                    issues.add(createIssue(fileName, "removed-api", "medium",
                            "Uses java.lang.Compiler which was removed in Java 17",
                            "Remove all usages - this class was a no-op stub and has no replacement"));
                }

                if (content.contains("@RunWith") || content.contains("import org.junit.Test") ||
                        content.contains("import org.junit.Before") || content.contains("import org.junit.runner")) {
                    issues.add(createIssue(fileName, "test-framework", "medium",
                            "Uses JUnit 4 which should be migrated to JUnit 5 for full Java 17+ compatibility",
                            "Migrate to JUnit 5: @RunWith → @ExtendWith, @Before/@After → @BeforeEach/@AfterEach, @Test stays"));
                }

            } catch (Exception ignored) {}
        }
        return issues;
    }

    private void checkIssue(List<Map<String, String>> issues, String content, String fileName,
                            String pattern1, String pattern2, String type, String severity,
                            String message, String suggestion) {
        if (content.contains(pattern1) || (pattern2 != null && content.contains(pattern2))) {
            issues.add(createIssue(fileName, type, severity, message, suggestion));
        }
    }

    private Map<String, String> createIssue(String file, String type, String severity,
                                            String message, String suggestion) {
        Map<String, String> issue = new LinkedHashMap<>();
        issue.put("file", file);
        issue.put("type", type);
        issue.put("severity", severity);
        issue.put("message", message);
        issue.put("suggestion", suggestion);
        return issue;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> generateRecommendations(Map<String, Object> analysis) {
        List<Map<String, String>> recs = new ArrayList<>();

        recs.add(createRec("critical", "Apply OpenRewrite UpgradeToJava17 Recipe",
                "Primary migration recipe that handles most Java 8 to 17 migration tasks automatically.",
                "org.openrewrite.java.migrate.UpgradeToJava17"));

        if (Boolean.TRUE.equals(analysis.get("hasJaxb"))) {
            recs.add(createRec("high", "Add JAXB Dependencies",
                    "JAXB was removed from the JDK in Java 11. You need to add explicit dependencies.",
                    "org.openrewrite.java.migrate.javax.AddJaxbDependencies"));
        }
        if (Boolean.TRUE.equals(analysis.get("hasJaxws"))) {
            recs.add(createRec("high", "Add JAX-WS Dependencies",
                    "JAX-WS was removed from the JDK in Java 11. You need to add explicit dependencies.",
                    "org.openrewrite.java.migrate.javax.AddJaxwsDependencies"));
        }
        if (Boolean.TRUE.equals(analysis.get("hasLombok"))) {
            recs.add(createRec("medium", "Update Lombok for Java 17",
                    "Lombok needs to be updated to a version that supports Java 17.",
                    "org.openrewrite.java.migrate.lombok.UpdateLombokToJava17"));
        }
        if (Boolean.TRUE.equals(analysis.get("hasSpringBoot"))) {
            recs.add(createRec("high", "Check Spring Boot Compatibility",
                    "Ensure Spring Boot version is 2.5+ for Java 17 support. Consider upgrading to Spring Boot 3.x for long-term support.",
                    null));
        }
        if (Boolean.TRUE.equals(analysis.get("hasJunit4"))) {
            recs.add(createRec("high", "Migrate JUnit 4 to JUnit 5",
                    "JUnit 4 should be upgraded to JUnit 5 for full Java 17+ compatibility. OpenRewrite JUnit4to5Migration recipe has been configured.",
                    "org.openrewrite.java.testing.junit5.JUnit4to5Migration"));
        }
        if (Boolean.TRUE.equals(analysis.get("hasSecurityManager"))) {
            recs.add(createRec("critical", "Remove SecurityManager Usage",
                    "SecurityManager was permanently removed in Java 17. All usages must be manually removed as there is no replacement.",
                    null));
        }
        if (Boolean.TRUE.equals(analysis.get("hasFinalize"))) {
            recs.add(createRec("medium", "Replace finalize() Methods",
                    "finalize() is deprecated for removal since Java 18. Replace with java.lang.ref.Cleaner or try-with-resources. OpenRewrite RemoveFinalizeMethod recipe has been configured.",
                    "org.openrewrite.java.migrate.RemoveFinalizeMethod"));
        }
        if (Boolean.TRUE.equals(analysis.get("hasPersistence"))) {
            recs.add(createRec("high", "Add JPA Dependency Explicitly",
                    "JPA (javax.persistence) is not bundled with the JDK. Add jakarta.persistence-api as an explicit dependency and update imports.",
                    null));
        }
        if (Boolean.TRUE.equals(analysis.get("hasJavafx"))) {
            recs.add(createRec("high", "Add OpenJFX Dependency",
                    "JavaFX was removed from the JDK in Java 11. Add OpenJFX dependencies explicitly (org.openjfx:javafx-controls:17+).",
                    null));
        }

        List<Map<String, String>> issues = (List<Map<String, String>>) analysis.get("issues");
        boolean hasReflection = issues != null && issues.stream()
                .anyMatch(i -> "strong-encapsulation".equals(i.get("type")));
        if (hasReflection) {
            recs.add(createRec("high", "Handle Strong Encapsulation",
                    "Java 17 enforces strong encapsulation. Add --add-opens JVM flags or refactor to avoid deep reflection.",
                    null));
        }

        return recs;
    }

    private Map<String, String> createRec(String priority, String title, String description, String recipe) {
        Map<String, String> rec = new LinkedHashMap<>();
        rec.put("priority", priority);
        rec.put("title", title);
        rec.put("description", description);
        if (recipe != null) rec.put("recipe", recipe);
        return rec;
    }
}
