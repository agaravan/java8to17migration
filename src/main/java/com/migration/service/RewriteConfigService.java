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
    private static final String MAVEN_COMPILER_PLUGIN_VERSION = "3.13.0";
    private static final String MAVEN_RESOURCES_PLUGIN_VERSION = "3.3.1";

    public Map<String, Object> configureRewrite(String projectPath, Map<String, Object> analysis, int targetVersion) throws Exception {
        List<String> recipes = buildRecipeList(analysis, targetVersion);

        String rewriteYml = generateRewriteYaml(recipes, targetVersion);
        Files.writeString(Path.of(projectPath, "rewrite.yml"), rewriteYml);

        @SuppressWarnings("unchecked")
        List<String> pomFiles = (List<String>) analysis.get("pomFiles");
        for (String pomFile : pomFiles) {
            injectRewritePlugin(pomFile, recipes, String.valueOf(targetVersion));
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

        String mainRecipe = getMainRecipeForVersion(targetVersion);
        String versionRecipe = getVersionRecipeForVersion(targetVersion);
        recipes.add(mainRecipe);
        recipes.add(versionRecipe);

        if (Boolean.TRUE.equals(analysis.get("hasJaxb"))) {
            recipes.add("org.openrewrite.java.migrate.javax.AddJaxbDependencies");
        }
        if (Boolean.TRUE.equals(analysis.get("hasJaxws"))) {
            recipes.add("org.openrewrite.java.migrate.javax.AddJaxwsDependencies");
        }
        if (Boolean.TRUE.equals(analysis.get("hasLombok"))) {
            String lombokRecipe = getLombokRecipeForVersion(targetVersion);
            if (lombokRecipe != null) {
                recipes.add(lombokRecipe);
            }
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

    private void injectRewritePlugin(String pomFile, List<String> recipes, String targetJavaVersion) throws Exception {
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
        plugin.appendChild(dependencies);

        plugins.appendChild(plugin);

        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.transform(new DOMSource(doc), new StreamResult(new File(pomFile)));
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
