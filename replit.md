# Java Migration Service

## Overview
A web-based service that automates the migration of Java projects from OpenJDK 8 to OpenJDK 17.0.2 using OpenRewrite recipes. Similar to Amazon Q's migration capabilities.

## Architecture
- **Backend**: Node.js + Express (port 5000)
- **Frontend**: Vanilla HTML/CSS/JS (served from `public/`)
- **Migration Engine**: OpenRewrite Maven Plugin (`org.openrewrite.maven:rewrite-maven-plugin`)

## Project Structure
```
server.js              - Express server, API routes, migration orchestration
services/
  gitService.js        - Git clone/branch operations via simple-git
  migrationService.js  - Project analysis, OpenRewrite config, migration logic
public/
  index.html           - Main UI (SPA with 3 views)
  css/styles.css       - Styling
  js/app.js            - Frontend logic (polling, rendering)
workspace/             - Temporary directory for cloned repos (gitignored)
```

## Key Features
1. **Repository Input**: Bitbucket URL + branch (feature/develop) with optional auth
2. **Project Analysis**: Scans pom.xml files, detects dependencies (Lombok, JAXB, JAX-WS, Spring)
3. **Java Source Scanning**: Identifies deprecated APIs, removed modules, reflection issues
4. **OpenRewrite Configuration**: Auto-injects rewrite-maven-plugin with appropriate recipes
5. **Migration Report**: Summary stats, issues by severity, recommendations, next steps

## OpenRewrite Recipes Used
- `org.openrewrite.java.migrate.UpgradeToJava17` (core)
- `org.openrewrite.java.migrate.JavaVersion17` (compiler settings)
- `org.openrewrite.java.migrate.javax.AddJaxbDependencies` (conditional)
- `org.openrewrite.java.migrate.javax.AddJaxwsDependencies` (conditional)
- `org.openrewrite.java.migrate.lombok.UpdateLombokToJava17` (conditional)

## Dependencies
- express, express-session, uuid, simple-git, xml2js, archiver, glob
- System: git, maven

## Running
```
node server.js
```
Runs on port 5000.
