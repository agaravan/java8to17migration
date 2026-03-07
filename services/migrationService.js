const fs = require('fs');
const path = require('path');
const xml2js = require('xml2js');
const { execSync } = require('child_process');

const TARGET_JAVA_VERSION = '17';
const TARGET_MAVEN_COMPILER_SOURCE = '17';
const TARGET_MAVEN_COMPILER_TARGET = '17';

const OPENREWRITE_PLUGIN_VERSION = '5.42.2';
const REWRITE_RECIPE_VERSION = '2.25.0';

const AVAILABLE_RECIPES = [
  {
    id: 'org.openrewrite.java.migrate.UpgradeToJava17',
    name: 'Upgrade to Java 17',
    description: 'Migrates Java 8 code to Java 17, including deprecated API replacements, removed API alternatives, and compiler settings.',
    category: 'core',
    required: true
  },
  {
    id: 'org.openrewrite.java.migrate.javax.AddJaxbDependencies',
    name: 'Add JAXB Dependencies',
    description: 'Adds explicit JAXB dependencies since javax.xml.bind was removed from the JDK in Java 11.',
    category: 'api-removal',
    required: false
  },
  {
    id: 'org.openrewrite.java.migrate.javax.AddJaxwsDependencies',
    name: 'Add JAX-WS Dependencies',
    description: 'Adds explicit JAX-WS dependencies since javax.xml.ws was removed from the JDK in Java 11.',
    category: 'api-removal',
    required: false
  },
  {
    id: 'org.openrewrite.java.migrate.lombok.UpdateLombokToJava17',
    name: 'Update Lombok for Java 17',
    description: 'Updates Lombok to a version compatible with Java 17 and adjusts configurations.',
    category: 'dependency',
    required: false
  },
  {
    id: 'org.openrewrite.maven.UpgradePluginVersion',
    name: 'Upgrade Maven Plugin Versions',
    description: 'Updates Maven plugins to versions compatible with Java 17.',
    category: 'build',
    required: true
  },
  {
    id: 'org.openrewrite.java.migrate.RemovedJavaXMLWSModuleInfo',
    name: 'Handle Removed java.xml.ws Module',
    description: 'Handles the removal of java.xml.ws module from Java 11+.',
    category: 'api-removal',
    required: false
  },
  {
    id: 'org.openrewrite.java.migrate.JavaVersion17',
    name: 'Set Java Version 17',
    description: 'Sets the Java version to 17 in build configuration files.',
    category: 'core',
    required: true
  },
  {
    id: 'org.openrewrite.java.migrate.RemoveMethodInvocation',
    name: 'Remove Deprecated Method Invocations',
    description: 'Removes invocations of methods that were deprecated and removed in later Java versions.',
    category: 'deprecated',
    required: false
  }
];

function getAvailableRecipes() {
  return AVAILABLE_RECIPES;
}

async function analyzeProject(projectPath) {
  const analysis = {
    projectPath,
    modules: [],
    sourceVersion: 'unknown',
    targetVersion: TARGET_JAVA_VERSION,
    hasLombok: false,
    hasJaxb: false,
    hasJaxws: false,
    hasSpring: false,
    hasSpringBoot: false,
    pomFiles: [],
    javaFiles: [],
    issues: [],
    recommendations: []
  };

  const pomFiles = findFiles(projectPath, 'pom.xml');
  analysis.pomFiles = pomFiles;

  for (const pomFile of pomFiles) {
    const pomAnalysis = await analyzePom(pomFile);
    analysis.modules.push(pomAnalysis);

    if (pomAnalysis.sourceVersion && pomAnalysis.sourceVersion !== 'unknown') {
      analysis.sourceVersion = pomAnalysis.sourceVersion;
    }
    if (pomAnalysis.hasLombok) analysis.hasLombok = true;
    if (pomAnalysis.hasJaxb) analysis.hasJaxb = true;
    if (pomAnalysis.hasJaxws) analysis.hasJaxws = true;
    if (pomAnalysis.hasSpring) analysis.hasSpring = true;
    if (pomAnalysis.hasSpringBoot) analysis.hasSpringBoot = true;
  }

  analysis.javaFiles = findFiles(projectPath, '*.java');

  const javaIssues = analyzeJavaFiles(analysis.javaFiles);
  analysis.issues = javaIssues;

  analysis.recommendations = generateRecommendations(analysis);

  return analysis;
}

function findFiles(dir, pattern) {
  const results = [];
  try {
    const globModule = require('glob');
    const files = globModule.sync(`**/${pattern}`, { cwd: dir, nodir: true, ignore: ['**/node_modules/**', '**/target/**', '**/.git/**'] });
    return files.map(f => path.join(dir, f));
  } catch {
    return findFilesRecursive(dir, pattern, results);
  }
}

function findFilesRecursive(dir, pattern, results) {
  try {
    const entries = fs.readdirSync(dir, { withFileTypes: true });
    for (const entry of entries) {
      if (entry.name === 'node_modules' || entry.name === 'target' || entry.name === '.git') continue;
      const fullPath = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        findFilesRecursive(fullPath, pattern, results);
      } else if (matchPattern(entry.name, pattern)) {
        results.push(fullPath);
      }
    }
  } catch {}
  return results;
}

function matchPattern(filename, pattern) {
  if (pattern.startsWith('*.')) {
    return filename.endsWith(pattern.substring(1));
  }
  return filename === pattern;
}

async function analyzePom(pomFile) {
  const content = fs.readFileSync(pomFile, 'utf-8');
  const parser = new xml2js.Parser();
  const pom = await parser.parseStringPromise(content);

  const result = {
    path: pomFile,
    groupId: '',
    artifactId: '',
    version: '',
    packaging: 'jar',
    sourceVersion: 'unknown',
    targetVersion: 'unknown',
    hasLombok: false,
    hasJaxb: false,
    hasJaxws: false,
    hasSpring: false,
    hasSpringBoot: false,
    dependencies: [],
    plugins: []
  };

  if (!pom.project) return result;

  const project = pom.project;
  result.groupId = getFirst(project.groupId);
  result.artifactId = getFirst(project.artifactId);
  result.version = getFirst(project.version);
  result.packaging = getFirst(project.packaging) || 'jar';

  if (project.properties) {
    const props = project.properties[0];
    if (props['maven.compiler.source']) {
      result.sourceVersion = getFirst(props['maven.compiler.source']);
    }
    if (props['maven.compiler.target']) {
      result.targetVersion = getFirst(props['maven.compiler.target']);
    }
    if (props['java.version']) {
      result.sourceVersion = getFirst(props['java.version']);
    }
  }

  if (project.dependencies && project.dependencies[0] && project.dependencies[0].dependency) {
    for (const dep of project.dependencies[0].dependency) {
      const groupId = getFirst(dep.groupId) || '';
      const artifactId = getFirst(dep.artifactId) || '';
      const version = getFirst(dep.version) || '';

      result.dependencies.push({ groupId, artifactId, version });

      if (artifactId === 'lombok') result.hasLombok = true;
      if (groupId.includes('javax.xml.bind') || artifactId.includes('jaxb')) result.hasJaxb = true;
      if (groupId.includes('javax.xml.ws') || artifactId.includes('jaxws')) result.hasJaxws = true;
      if (groupId.includes('springframework')) result.hasSpring = true;
      if (artifactId.includes('spring-boot')) result.hasSpringBoot = true;
    }
  }

  if (project.build && project.build[0] && project.build[0].plugins && project.build[0].plugins[0]) {
    const plugins = project.build[0].plugins[0].plugin || [];
    for (const plugin of plugins) {
      result.plugins.push({
        groupId: getFirst(plugin.groupId) || '',
        artifactId: getFirst(plugin.artifactId) || '',
        version: getFirst(plugin.version) || ''
      });
    }
  }

  return result;
}

function getFirst(arr) {
  if (Array.isArray(arr)) return arr[0];
  return arr;
}

function analyzeJavaFiles(javaFiles) {
  const issues = [];

  for (const file of javaFiles) {
    try {
      const content = fs.readFileSync(file, 'utf-8');
      const relPath = file;

      if (content.includes('sun.misc.') || content.includes('sun.reflect.')) {
        issues.push({
          file: relPath,
          type: 'internal-api',
          severity: 'high',
          message: 'Uses internal sun.* APIs that are encapsulated in Java 17',
          suggestion: 'Replace with public API alternatives'
        });
      }

      if (content.includes('javax.xml.bind') && !content.includes('import jakarta')) {
        issues.push({
          file: relPath,
          type: 'removed-module',
          severity: 'high',
          message: 'Uses javax.xml.bind (JAXB) which was removed in Java 11',
          suggestion: 'Add explicit JAXB dependency and update imports'
        });
      }

      if (content.includes('javax.xml.ws')) {
        issues.push({
          file: relPath,
          type: 'removed-module',
          severity: 'high',
          message: 'Uses javax.xml.ws (JAX-WS) which was removed in Java 11',
          suggestion: 'Add explicit JAX-WS dependency'
        });
      }

      if (content.includes('javax.annotation')) {
        issues.push({
          file: relPath,
          type: 'removed-module',
          severity: 'medium',
          message: 'Uses javax.annotation which was removed in Java 11',
          suggestion: 'Add javax.annotation-api dependency'
        });
      }

      if (content.includes('javax.activation')) {
        issues.push({
          file: relPath,
          type: 'removed-module',
          severity: 'medium',
          message: 'Uses javax.activation which was removed in Java 11',
          suggestion: 'Add jakarta.activation dependency'
        });
      }

      if (content.includes('java.security.acl')) {
        issues.push({
          file: relPath,
          type: 'removed-api',
          severity: 'medium',
          message: 'Uses java.security.acl which was removed in Java 14',
          suggestion: 'Migrate to java.security package'
        });
      }

      if (content.includes('Nashorn') || content.includes('jdk.nashorn')) {
        issues.push({
          file: relPath,
          type: 'removed-engine',
          severity: 'high',
          message: 'Uses Nashorn JavaScript engine which was removed in Java 15',
          suggestion: 'Migrate to GraalVM JavaScript or another JS engine'
        });
      }

      const reflectionPattern = /setAccessible\s*\(\s*true\s*\)/g;
      if (reflectionPattern.test(content)) {
        issues.push({
          file: relPath,
          type: 'strong-encapsulation',
          severity: 'medium',
          message: 'Uses reflection with setAccessible(true) which may fail under strong encapsulation in Java 17',
          suggestion: 'Use --add-opens JVM flags or refactor to avoid deep reflection'
        });
      }

      if (content.includes('new URL(') || content.includes('new Integer(') || content.includes('new Long(') || content.includes('new Double(')) {
        issues.push({
          file: relPath,
          type: 'deprecated-constructor',
          severity: 'low',
          message: 'Uses deprecated wrapper class constructors',
          suggestion: 'Use valueOf() factory methods instead'
        });
      }
    } catch {}
  }

  return issues;
}

function generateRecommendations(analysis) {
  const recs = [];

  recs.push({
    priority: 'critical',
    title: 'Apply OpenRewrite UpgradeToJava17 Recipe',
    description: 'This is the primary migration recipe that handles most Java 8 to 17 migration tasks automatically.',
    recipe: 'org.openrewrite.java.migrate.UpgradeToJava17'
  });

  if (analysis.hasJaxb) {
    recs.push({
      priority: 'high',
      title: 'Add JAXB Dependencies',
      description: 'JAXB was removed from the JDK in Java 11. You need to add explicit dependencies.',
      recipe: 'org.openrewrite.java.migrate.javax.AddJaxbDependencies'
    });
  }

  if (analysis.hasJaxws) {
    recs.push({
      priority: 'high',
      title: 'Add JAX-WS Dependencies',
      description: 'JAX-WS was removed from the JDK in Java 11. You need to add explicit dependencies.',
      recipe: 'org.openrewrite.java.migrate.javax.AddJaxwsDependencies'
    });
  }

  if (analysis.hasLombok) {
    recs.push({
      priority: 'medium',
      title: 'Update Lombok for Java 17',
      description: 'Lombok needs to be updated to a version that supports Java 17.',
      recipe: 'org.openrewrite.java.migrate.lombok.UpdateLombokToJava17'
    });
  }

  if (analysis.hasSpringBoot) {
    recs.push({
      priority: 'high',
      title: 'Check Spring Boot Compatibility',
      description: 'Ensure Spring Boot version is 2.5+ for Java 17 support. Consider upgrading to Spring Boot 3.x.',
      recipe: null
    });
  }

  const hasReflectionIssues = analysis.issues.some(i => i.type === 'strong-encapsulation');
  if (hasReflectionIssues) {
    recs.push({
      priority: 'high',
      title: 'Handle Strong Encapsulation',
      description: 'Java 17 enforces strong encapsulation. You may need --add-opens flags for libraries using deep reflection.',
      recipe: null
    });
  }

  return recs;
}

async function configureRewrite(projectPath, analysis) {
  const recipesApplied = [];

  recipesApplied.push('org.openrewrite.java.migrate.UpgradeToJava17');
  recipesApplied.push('org.openrewrite.java.migrate.JavaVersion17');

  if (analysis.hasJaxb) {
    recipesApplied.push('org.openrewrite.java.migrate.javax.AddJaxbDependencies');
  }
  if (analysis.hasJaxws) {
    recipesApplied.push('org.openrewrite.java.migrate.javax.AddJaxwsDependencies');
  }
  if (analysis.hasLombok) {
    recipesApplied.push('org.openrewrite.java.migrate.lombok.UpdateLombokToJava17');
  }

  const rewriteYml = generateRewriteYaml(recipesApplied);
  fs.writeFileSync(path.join(projectPath, 'rewrite.yml'), rewriteYml);

  for (const pomFile of analysis.pomFiles) {
    await injectRewritePlugin(pomFile, recipesApplied);
  }

  return { recipesApplied, rewriteConfig: rewriteYml };
}

function generateRewriteYaml(recipes) {
  let yaml = `---
type: specs.openrewrite.org/v1beta/recipe
name: com.migration.UpgradeToJava17
displayName: Migrate from Java 8 to Java 17
description: Comprehensive migration from OpenJDK 8 to OpenJDK 17.0.2
recipeList:
`;

  for (const recipe of recipes) {
    yaml += `  - ${recipe}\n`;
  }

  return yaml;
}

async function injectRewritePlugin(pomFile, recipes) {
  const content = fs.readFileSync(pomFile, 'utf-8');
  const parser = new xml2js.Parser({ preserveChildrenOrder: true, explicitArray: true });
  const builder = new xml2js.Builder({ headless: false, renderOpts: { pretty: true, indent: '    ' } });

  const pom = await parser.parseStringPromise(content);

  if (!pom.project.build) {
    pom.project.build = [{}];
  }
  if (!pom.project.build[0].plugins) {
    pom.project.build[0].plugins = [{ plugin: [] }];
  }
  if (!pom.project.build[0].plugins[0].plugin) {
    pom.project.build[0].plugins[0].plugin = [];
  }

  const plugins = pom.project.build[0].plugins[0].plugin;
  const rewritePluginIndex = plugins.findIndex(p =>
    getFirst(p.artifactId) === 'rewrite-maven-plugin'
  );

  const rewritePlugin = {
    groupId: ['org.openrewrite.maven'],
    artifactId: ['rewrite-maven-plugin'],
    version: [OPENREWRITE_PLUGIN_VERSION],
    configuration: [{
      activeRecipes: [{ recipe: recipes }],
      failOnDryRunResults: ['false']
    }],
    dependencies: [{
      dependency: [{
        groupId: ['org.openrewrite.recipe'],
        artifactId: ['rewrite-migrate-java'],
        version: [REWRITE_RECIPE_VERSION]
      }]
    }]
  };

  if (rewritePluginIndex >= 0) {
    plugins[rewritePluginIndex] = rewritePlugin;
  } else {
    plugins.push(rewritePlugin);
  }

  if (!pom.project.properties) {
    pom.project.properties = [{}];
  }
  pom.project.properties[0]['maven.compiler.source'] = [TARGET_MAVEN_COMPILER_SOURCE];
  pom.project.properties[0]['maven.compiler.target'] = [TARGET_MAVEN_COMPILER_TARGET];
  if (pom.project.properties[0]['java.version']) {
    pom.project.properties[0]['java.version'] = [TARGET_JAVA_VERSION];
  }

  const updatedXml = builder.buildObject(pom);
  fs.writeFileSync(pomFile, updatedXml);
}

async function applyMigration(projectPath, analysis) {
  const result = {
    pomChanges: [],
    javaChanges: [],
    configChanges: [],
    warnings: [],
    mavenOutput: null
  };

  for (const pomFile of analysis.pomFiles) {
    result.pomChanges.push({
      file: pomFile,
      changes: [
        'Updated maven.compiler.source to 17',
        'Updated maven.compiler.target to 17',
        'Added OpenRewrite maven plugin',
        'Added rewrite-migrate-java dependency'
      ]
    });
  }

  const activeRecipes = ['org.openrewrite.java.migrate.UpgradeToJava17', 'org.openrewrite.java.migrate.JavaVersion17'];
  if (analysis.hasJaxb) activeRecipes.push('org.openrewrite.java.migrate.javax.AddJaxbDependencies');
  if (analysis.hasJaxws) activeRecipes.push('org.openrewrite.java.migrate.javax.AddJaxwsDependencies');
  if (analysis.hasLombok) activeRecipes.push('org.openrewrite.java.migrate.lombok.UpdateLombokToJava17');

  const recipeArg = activeRecipes.join(',');

  try {
    const mvnResult = execSync(
      `mvn org.openrewrite.maven:rewrite-maven-plugin:${OPENREWRITE_PLUGIN_VERSION}:run -Drewrite.activeRecipes=${recipeArg} -Drewrite.recipeArtifactCoordinates=org.openrewrite.recipe:rewrite-migrate-java:${REWRITE_RECIPE_VERSION}`,
      {
        cwd: projectPath,
        timeout: 300000,
        encoding: 'utf-8',
        stdio: ['pipe', 'pipe', 'pipe'],
        env: { ...process.env, MAVEN_OPTS: '-Xmx1024m' }
      }
    );
    result.mavenOutput = mvnResult;
  } catch (err) {
    const sanitizedMsg = (err.message || '').replace(/https?:\/\/[^\s]*/g, '[URL_REDACTED]').substring(0, 500);
    result.warnings.push(`Maven rewrite execution note: ${sanitizedMsg || 'Could not run Maven automatically. The pom.xml has been configured - run mvn rewrite:run manually.'}`);
    result.mavenOutput = (err.stdout || err.stderr || 'Maven execution requires project dependencies. Run mvn rewrite:run in your local environment.').replace(/https?:\/\/[^\s]*/g, '[URL_REDACTED]');
  }

  for (const issue of analysis.issues) {
    if (issue.type === 'deprecated-constructor') {
      result.javaChanges.push({
        file: issue.file,
        change: 'Flagged deprecated constructor usage for review',
        automated: false
      });
    }
  }

  return result;
}

async function generateReport(projectPath, analysis, migrationResult) {
  const report = {
    generatedAt: new Date().toISOString(),
    sourceVersion: analysis.sourceVersion,
    targetVersion: TARGET_JAVA_VERSION,
    projectPath,
    summary: {
      totalModules: analysis.modules.length,
      totalJavaFiles: analysis.javaFiles.length,
      totalIssuesFound: analysis.issues.length,
      highSeverityIssues: analysis.issues.filter(i => i.severity === 'high').length,
      mediumSeverityIssues: analysis.issues.filter(i => i.severity === 'medium').length,
      lowSeverityIssues: analysis.issues.filter(i => i.severity === 'low').length,
      pomFilesModified: analysis.pomFiles.length,
      recommendationsCount: analysis.recommendations.length
    },
    modules: analysis.modules.map(m => ({
      artifactId: m.artifactId,
      groupId: m.groupId,
      previousSourceVersion: m.sourceVersion,
      newSourceVersion: TARGET_JAVA_VERSION,
      dependencies: m.dependencies.length,
      plugins: m.plugins.length
    })),
    issues: analysis.issues,
    recommendations: analysis.recommendations,
    changes: {
      pom: migrationResult.pomChanges,
      java: migrationResult.javaChanges,
      config: migrationResult.configChanges
    },
    warnings: migrationResult.warnings,
    openRewriteConfig: {
      pluginVersion: OPENREWRITE_PLUGIN_VERSION,
      recipeVersion: REWRITE_RECIPE_VERSION,
      recipes: AVAILABLE_RECIPES.filter(r => r.required).map(r => r.id)
    },
    nextSteps: [
      'Review the modified pom.xml files for correctness',
      'Run mvn rewrite:run in your local environment to apply Java source code transformations',
      'Run mvn rewrite:dryRun first to preview changes without modifying files',
      'Run full test suite: mvn clean test',
      'Check for compilation errors and fix remaining issues',
      'Test with OpenJDK 17.0.2 runtime',
      'Update CI/CD pipeline to use JDK 17',
      'Add --add-opens flags to JVM arguments if using reflection-heavy libraries'
    ]
  };

  const reportPath = path.join(projectPath, 'migration-report.json');
  fs.writeFileSync(reportPath, JSON.stringify(report, null, 2));

  return report;
}

module.exports = {
  getAvailableRecipes,
  analyzeProject,
  configureRewrite,
  applyMigration,
  generateReport
};
