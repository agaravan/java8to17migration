package com.migration.service;

import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
public class RewriteConfigService {

    private static final String OPENREWRITE_PLUGIN_VERSION = "5.42.2";
    private static final String REWRITE_RECIPE_VERSION = "2.25.0";
    private static final String REWRITE_TESTING_VERSION = "2.21.0";
    private static final String MAVEN_COMPILER_PLUGIN_VERSION = "3.13.0";
    private static final String MAVEN_RESOURCES_PLUGIN_VERSION = "3.3.1";
    private static final String MAVEN_SUREFIRE_PLUGIN_VERSION = "3.2.5";
    private static final String MAVEN_FAILSAFE_PLUGIN_VERSION = "3.2.5";
    private static final String MAVEN_JAR_PLUGIN_VERSION = "3.3.0";
    private static final String MAVEN_WAR_PLUGIN_VERSION = "3.4.0";
    private static final String MAVEN_DEPENDENCY_PLUGIN_VERSION = "3.6.1";
    private static final String LOMBOK_VERSION = "1.18.30";
    private static final String JAXWS_API_VERSION = "2.3.1";
    private static final String SUREFIRE_JUNIT47_VERSION = "3.2.5";

    public Map<String, Object> configureRewrite(String projectPath, Map<String, Object> analysis, int targetVersion) throws Exception {
        List<String> recipes = buildRecipeList(analysis, targetVersion);

        String rewriteYml = generateRewriteYaml(recipes, targetVersion);
        Files.writeString(Path.of(projectPath, "rewrite.yml"), rewriteYml);

        @SuppressWarnings("unchecked")
        List<String> pomFiles = (List<String>) analysis.get("pomFiles");
        for (String pomFile : pomFiles) {
            injectRewritePlugin(pomFile, recipes, String.valueOf(targetVersion), analysis);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recipesApplied", recipes);
        result.put("rewriteConfig", rewriteYml);
        result.put("pluginVersion", OPENREWRITE_PLUGIN_VERSION);
        result.put("recipeLibVersion", REWRITE_RECIPE_VERSION);
        return result;
    }

    private List<String> buildRecipeList(Map<String, Object> analysis, int targetVersion) {
        List<String> recipes = new ArrayList<>();

        recipes.add(getMainRecipeForVersion(targetVersion));
        recipes.add(getVersionRecipeForVersion(targetVersion));

        if (Boolean.TRUE.equals(analysis.get("hasJaxb"))) {
            recipes.add("org.openrewrite.java.migrate.javax.AddJaxbDependencies");
        }
        if (Boolean.TRUE.equals(analysis.get("hasJaxws"))) {
            recipes.add("org.openrewrite.java.migrate.javax.AddJaxwsDependencies");
        }
        if (Boolean.TRUE.equals(analysis.get("hasLombok"))) {
            String lombokRecipe = getLombokRecipeForVersion(targetVersion);
            if (lombokRecipe != null) recipes.add(lombokRecipe);
        }
        if (Boolean.TRUE.equals(analysis.get("hasJunit4"))) {
            recipes.add("org.openrewrite.java.testing.junit5.JUnit4to5Migration");
        }
        if (Boolean.TRUE.equals(analysis.get("hasFinalize"))) {
            recipes.add("org.openrewrite.java.migrate.RemoveFinalizeMethod");
        }
        return recipes;
    }

    private String getMainRecipeForVersion(int version) {
        switch (version) {
            case 11: return "org.openrewrite.java.migrate.UpgradeToJava11";
            case 17: return "org.openrewrite.java.migrate.UpgradeToJava17";
            case 21: return "org.openrewrite.java.migrate.UpgradeToJava21";
            default: return "org.openrewrite.java.migrate.UpgradeToJava17";
        }
    }

    private String getVersionRecipeForVersion(int version) {
        switch (version) {
            case 11: return "org.openrewrite.java.migrate.JavaVersion11";
            case 17: return "org.openrewrite.java.migrate.JavaVersion17";
            case 21: return "org.openrewrite.java.migrate.JavaVersion21";
            default: return "org.openrewrite.java.migrate.JavaVersion17";
        }
    }

    private String getLombokRecipeForVersion(int version) {
        if (version >= 17) {
            return "org.openrewrite.java.migrate.lombok.UpdateLombokToJava17";
        }
        return null;
    }

    private String generateRewriteYaml(List<String> recipes, int targetVersion) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("---\n");
        yaml.append("type: specs.openrewrite.org/v1beta/recipe\n");
        yaml.append("name: com.migration.UpgradeToJava").append(targetVersion).append("\n");
        yaml.append("displayName: Migrate to Java ").append(targetVersion).append("\n");
        yaml.append("description: Comprehensive migration to OpenJDK ").append(targetVersion).append("\n");
        yaml.append("recipeList:\n");
        for (String recipe : recipes) {
            yaml.append("  - ").append(recipe).append("\n");
        }
        return yaml.toString();
    }

    private void injectRewritePlugin(String pomFile, List<String> recipes, String targetJavaVersion, Map<String, Object> analysis) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder docBuilder = factory.newDocumentBuilder();
        Document doc = docBuilder.parse(new File(pomFile));
        doc.getDocumentElement().normalize();

        Element project = doc.getDocumentElement();

        Element properties = getOrCreateChild(doc, project, "properties");
        setChildText(doc, properties, "maven.compiler.source", targetJavaVersion);
        setChildText(doc, properties, "maven.compiler.target", targetJavaVersion);
        setChildText(doc, properties, "maven.compiler.release", targetJavaVersion);

        NodeList javaVersionNodes = properties.getElementsByTagName("java.version");
        if (javaVersionNodes.getLength() > 0) {
            javaVersionNodes.item(0).setTextContent(targetJavaVersion);
        }

        Element build = getOrCreateChild(doc, project, "build");
        Element plugins = getOrCreateChild(doc, build, "plugins");

        upgradeMavenCompilerPlugin(doc, plugins, targetJavaVersion);
        upgradeMavenResourcesPlugin(doc, plugins);
        upgradeMavenSurefirePlugin(doc, plugins, analysis);
        upgradeFailsafePlugin(doc, plugins, analysis);
        upgradePluginIfExists(doc, plugins, "maven-jar-plugin", "org.apache.maven.plugins", MAVEN_JAR_PLUGIN_VERSION);
        upgradePluginIfExists(doc, plugins, "maven-war-plugin", "org.apache.maven.plugins", MAVEN_WAR_PLUGIN_VERSION);
        upgradePluginIfExists(doc, plugins, "maven-dependency-plugin", "org.apache.maven.plugins", MAVEN_DEPENDENCY_PLUGIN_VERSION);

        boolean hasLombok = Boolean.TRUE.equals(analysis.get("hasLombok"));
        boolean hasJaxws = Boolean.TRUE.equals(analysis.get("hasJaxws"));
        int tv = Integer.parseInt(targetJavaVersion);

        if (hasLombok && tv >= 17) {
            upgradeLombokDependency(doc, project);
        }
        if (hasJaxws) {
            injectJaxwsApiDependency(doc, project);
        }

        updateJdkVersionTags(doc, targetJavaVersion);

        Element existingRewrite = findPluginByArtifactId(plugins, "rewrite-maven-plugin");
        if (existingRewrite != null) {
            plugins.removeChild(existingRewrite);
        }

        Element plugin = doc.createElement("plugin");
        appendTextElement(doc, plugin, "groupId", "org.openrewrite.maven");
        appendTextElement(doc, plugin, "artifactId", "rewrite-maven-plugin");
        appendTextElement(doc, plugin, "version", OPENREWRITE_PLUGIN_VERSION);

        Element configuration = doc.createElement("configuration");
        Element activeRecipes = doc.createElement("activeRecipes");
        for (String recipe : recipes) {
            appendTextElement(doc, activeRecipes, "recipe", recipe);
        }
        configuration.appendChild(activeRecipes);
        plugin.appendChild(configuration);

        Element dependencies = doc.createElement("dependencies");
        Element dep = doc.createElement("dependency");
        appendTextElement(doc, dep, "groupId", "org.openrewrite.recipe");
        appendTextElement(doc, dep, "artifactId", "rewrite-migrate-java");
        appendTextElement(doc, dep, "version", REWRITE_RECIPE_VERSION);
        dependencies.appendChild(dep);
        if (Boolean.TRUE.equals(analysis.get("hasJunit4"))) {
            Element testingDep = doc.createElement("dependency");
            appendTextElement(doc, testingDep, "groupId", "org.openrewrite.recipe");
            appendTextElement(doc, testingDep, "artifactId", "rewrite-testing-frameworks");
            appendTextElement(doc, testingDep, "version", REWRITE_TESTING_VERSION);
            dependencies.appendChild(testingDep);
        }
        plugin.appendChild(dependencies);

        plugins.appendChild(plugin);

        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.transform(new DOMSource(doc), new StreamResult(new File(pomFile)));
    }

    /**
     * Updates any bare {@code <jdk>} version-range elements (e.g. {@code [1.8,)}) found anywhere
     * in the POM to use the target Java version range (e.g. {@code [17,)}).
     * Leaf {@code <jdk>} nodes whose content matches a known Java version or range are updated;
     * structured {@code <jdk>} blocks with child elements are left untouched.
     */
    private void updateJdkVersionTags(Document doc, String targetJavaVersion) {
        NodeList jdkNodes = doc.getElementsByTagName("jdk");
        for (int i = 0; i < jdkNodes.getLength(); i++) {
            Node jdk = jdkNodes.item(i);
            boolean hasElementChildren = false;
            NodeList children = jdk.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                if (children.item(j) instanceof Element) { hasElementChildren = true; break; }
            }
            if (!hasElementChildren) {
                String content = jdk.getTextContent().trim();
                if (content.matches("\\[1\\.8,\\)|\\[8,\\)|\\[11,\\)|\\[17,\\)|1\\.8|8|11|17")) {
                    jdk.setTextContent("[" + targetJavaVersion + ",)");
                }
            }
        }
    }

    private void upgradeMavenCompilerPlugin(Document doc, Element plugins, String targetJavaVersion) {
        Element existing = findPluginByArtifactId(plugins, "maven-compiler-plugin");

        if (existing != null) {
            NodeList versionNodes = existing.getChildNodes();
            for (int i = 0; i < versionNodes.getLength(); i++) {
                Node child = versionNodes.item(i);
                if (child instanceof Element && "version".equals(child.getNodeName())) {
                    child.setTextContent(MAVEN_COMPILER_PLUGIN_VERSION);
                    break;
                }
            }
            boolean hasVersion = false;
            for (int i = 0; i < versionNodes.getLength(); i++) {
                Node child = versionNodes.item(i);
                if (child instanceof Element && "version".equals(child.getNodeName())) {
                    hasVersion = true;
                    break;
                }
            }
            if (!hasVersion) {
                Element versionEl = doc.createElement("version");
                versionEl.setTextContent(MAVEN_COMPILER_PLUGIN_VERSION);
                existing.insertBefore(versionEl, existing.getFirstChild());
            }

            Element config = null;
            for (int i = 0; i < versionNodes.getLength(); i++) {
                Node child = versionNodes.item(i);
                if (child instanceof Element && "configuration".equals(child.getNodeName())) {
                    config = (Element) child;
                    break;
                }
            }
            if (config != null) {
                setChildText(doc, config, "source", targetJavaVersion);
                setChildText(doc, config, "target", targetJavaVersion);
                setChildText(doc, config, "release", targetJavaVersion);
            } else {
                config = doc.createElement("configuration");
                appendTextElement(doc, config, "release", targetJavaVersion);
                existing.appendChild(config);
            }
        } else {
            Element plugin = doc.createElement("plugin");
            appendTextElement(doc, plugin, "groupId", "org.apache.maven.plugins");
            appendTextElement(doc, plugin, "artifactId", "maven-compiler-plugin");
            appendTextElement(doc, plugin, "version", MAVEN_COMPILER_PLUGIN_VERSION);
            Element config = doc.createElement("configuration");
            appendTextElement(doc, config, "release", targetJavaVersion);
            plugin.appendChild(config);
            plugins.appendChild(plugin);
        }
    }

    private void upgradeMavenSurefirePlugin(Document doc, Element plugins, Map<String, Object> analysis) {
        boolean hasJunit4 = Boolean.TRUE.equals(analysis.get("hasJunit4"));
        Element existing = findPluginByArtifactId(plugins, "maven-surefire-plugin");
        if (existing != null) {
            NodeList children = existing.getChildNodes();
            boolean hasVersion = false;
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child instanceof Element && "version".equals(child.getNodeName())) {
                    child.setTextContent(MAVEN_SUREFIRE_PLUGIN_VERSION);
                    hasVersion = true;
                    break;
                }
            }
            if (!hasVersion) {
                Element versionEl = doc.createElement("version");
                versionEl.setTextContent(MAVEN_SUREFIRE_PLUGIN_VERSION);
                existing.insertBefore(versionEl, existing.getFirstChild());
            }
            if (hasJunit4) {
                injectSurefireJunit47Dep(doc, existing);
            }
        } else {
            Element plugin = doc.createElement("plugin");
            appendTextElement(doc, plugin, "groupId", "org.apache.maven.plugins");
            appendTextElement(doc, plugin, "artifactId", "maven-surefire-plugin");
            appendTextElement(doc, plugin, "version", MAVEN_SUREFIRE_PLUGIN_VERSION);
            if (hasJunit4) {
                injectSurefireJunit47Dep(doc, plugin);
            }
            plugins.appendChild(plugin);
        }
    }

    private void upgradeFailsafePlugin(Document doc, Element plugins, Map<String, Object> analysis) {
        boolean hasJunit4 = Boolean.TRUE.equals(analysis.get("hasJunit4"));
        Element existing = findPluginByArtifactId(plugins, "maven-failsafe-plugin");
        if (existing == null) return;
        NodeList children = existing.getChildNodes();
        boolean hasVersion = false;
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && "version".equals(child.getNodeName())) {
                child.setTextContent(MAVEN_FAILSAFE_PLUGIN_VERSION);
                hasVersion = true;
                break;
            }
        }
        if (!hasVersion) {
            Element versionEl = doc.createElement("version");
            versionEl.setTextContent(MAVEN_FAILSAFE_PLUGIN_VERSION);
            existing.insertBefore(versionEl, existing.getFirstChild());
        }
        if (hasJunit4) {
            injectSurefireJunit47Dep(doc, existing);
        }
    }

    private void injectSurefireJunit47Dep(Document doc, Element pluginElement) {
        Element depsContainer = null;
        NodeList children = pluginElement.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && "dependencies".equals(child.getNodeName())) {
                depsContainer = (Element) child;
                break;
            }
        }
        if (depsContainer == null) {
            depsContainer = doc.createElement("dependencies");
            pluginElement.appendChild(depsContainer);
        }
        NodeList existing = depsContainer.getElementsByTagName("artifactId");
        for (int i = 0; i < existing.getLength(); i++) {
            if ("surefire-junit47".equals(existing.item(i).getTextContent().trim())) return;
        }
        Element dep = doc.createElement("dependency");
        appendTextElement(doc, dep, "groupId", "org.apache.maven.surefire");
        appendTextElement(doc, dep, "artifactId", "surefire-junit47");
        appendTextElement(doc, dep, "version", SUREFIRE_JUNIT47_VERSION);
        depsContainer.appendChild(dep);
    }

    private void upgradeLombokDependency(Document doc, Element project) {
        Element dependenciesEl = null;
        NodeList children = project.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && "dependencies".equals(child.getNodeName())) {
                dependenciesEl = (Element) child;
                break;
            }
        }
        if (dependenciesEl == null) {
            dependenciesEl = doc.createElement("dependencies");
            project.appendChild(dependenciesEl);
        }
        NodeList depList = dependenciesEl.getElementsByTagName("dependency");
        for (int i = 0; i < depList.getLength(); i++) {
            Element dep = (Element) depList.item(i);
            NodeList artIds = dep.getElementsByTagName("artifactId");
            if (artIds.getLength() > 0 && "lombok".equals(artIds.item(0).getTextContent().trim())) {
                NodeList versions = dep.getElementsByTagName("version");
                if (versions.getLength() > 0) {
                    versions.item(0).setTextContent(LOMBOK_VERSION);
                } else {
                    appendTextElement(doc, dep, "version", LOMBOK_VERSION);
                }
                NodeList scopes = dep.getElementsByTagName("scope");
                if (scopes.getLength() == 0) {
                    appendTextElement(doc, dep, "scope", "provided");
                }
                return;
            }
        }
        Element dep = doc.createElement("dependency");
        appendTextElement(doc, dep, "groupId", "org.projectlombok");
        appendTextElement(doc, dep, "artifactId", "lombok");
        appendTextElement(doc, dep, "version", LOMBOK_VERSION);
        appendTextElement(doc, dep, "scope", "provided");
        dependenciesEl.appendChild(dep);
    }

    private void injectJaxwsApiDependency(Document doc, Element project) {
        Element dependenciesEl = null;
        NodeList children = project.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && "dependencies".equals(child.getNodeName())) {
                dependenciesEl = (Element) child;
                break;
            }
        }
        if (dependenciesEl == null) {
            dependenciesEl = doc.createElement("dependencies");
            project.appendChild(dependenciesEl);
        }
        NodeList depList = dependenciesEl.getElementsByTagName("dependency");
        for (int i = 0; i < depList.getLength(); i++) {
            Element dep = (Element) depList.item(i);
            NodeList artIds = dep.getElementsByTagName("artifactId");
            if (artIds.getLength() > 0 && "jaxws-api".equals(artIds.item(0).getTextContent().trim())) {
                NodeList versions = dep.getElementsByTagName("version");
                if (versions.getLength() > 0) {
                    versions.item(0).setTextContent(JAXWS_API_VERSION);
                }
                return;
            }
        }
        Element dep = doc.createElement("dependency");
        appendTextElement(doc, dep, "groupId", "javax.xml.ws");
        appendTextElement(doc, dep, "artifactId", "jaxws-api");
        appendTextElement(doc, dep, "version", JAXWS_API_VERSION);
        dependenciesEl.appendChild(dep);
    }

    private void upgradePluginIfExists(Document doc, Element plugins, String artifactId, String groupId, String version) {
        Element existing = findPluginByArtifactId(plugins, artifactId);
        if (existing == null) return;
        NodeList children = existing.getChildNodes();
        boolean hasVersion = false;
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && "version".equals(child.getNodeName())) {
                child.setTextContent(version);
                hasVersion = true;
                break;
            }
        }
        if (!hasVersion) {
            Element versionEl = doc.createElement("version");
            versionEl.setTextContent(version);
            existing.insertBefore(versionEl, existing.getFirstChild());
        }
    }

    private void upgradeMavenResourcesPlugin(Document doc, Element plugins) {
        Element existing = findPluginByArtifactId(plugins, "maven-resources-plugin");

        if (existing != null) {
            NodeList children = existing.getChildNodes();
            boolean hasVersion = false;
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child instanceof Element && "version".equals(child.getNodeName())) {
                    child.setTextContent(MAVEN_RESOURCES_PLUGIN_VERSION);
                    hasVersion = true;
                    break;
                }
            }
            if (!hasVersion) {
                Element versionEl = doc.createElement("version");
                versionEl.setTextContent(MAVEN_RESOURCES_PLUGIN_VERSION);
                existing.insertBefore(versionEl, existing.getFirstChild());
            }

            Element config = null;
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child instanceof Element && "configuration".equals(child.getNodeName())) {
                    config = (Element) child;
                    break;
                }
            }
            if (config != null) {
                setChildText(doc, config, "encoding", "UTF-8");
            } else {
                config = doc.createElement("configuration");
                appendTextElement(doc, config, "encoding", "UTF-8");
                existing.appendChild(config);
            }
        } else {
            Element plugin = doc.createElement("plugin");
            appendTextElement(doc, plugin, "groupId", "org.apache.maven.plugins");
            appendTextElement(doc, plugin, "artifactId", "maven-resources-plugin");
            appendTextElement(doc, plugin, "version", MAVEN_RESOURCES_PLUGIN_VERSION);
            Element config = doc.createElement("configuration");
            appendTextElement(doc, config, "encoding", "UTF-8");
            plugin.appendChild(config);
            plugins.appendChild(plugin);
        }
    }

    private Element getOrCreateChild(Document doc, Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && child.getNodeName().equals(tagName)) {
                return (Element) child;
            }
        }
        Element newChild = doc.createElement(tagName);
        parent.appendChild(newChild);
        return newChild;
    }

    private void setChildText(Document doc, Element parent, String tagName, String text) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && child.getNodeName().equals(tagName)) {
                child.setTextContent(text);
                return;
            }
        }
        appendTextElement(doc, parent, tagName, text);
    }

    private void appendTextElement(Document doc, Element parent, String tagName, String text) {
        Element elem = doc.createElement(tagName);
        elem.setTextContent(text);
        parent.appendChild(elem);
    }

    private Element findPluginByArtifactId(Element plugins, String artifactId) {
        NodeList children = plugins.getElementsByTagName("plugin");
        for (int i = 0; i < children.getLength(); i++) {
            Element plugin = (Element) children.item(i);
            NodeList artIds = plugin.getElementsByTagName("artifactId");
            if (artIds.getLength() > 0 && artifactId.equals(artIds.item(0).getTextContent().trim())) {
                return plugin;
            }
        }
        return null;
    }

    public String getPluginVersion() { return OPENREWRITE_PLUGIN_VERSION; }
    public String getRecipeVersion() { return REWRITE_RECIPE_VERSION; }
}
