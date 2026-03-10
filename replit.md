# Java Migration Service

## Overview
A pure Java (Spring Boot) web service that automates the migration of Java Maven projects from OpenJDK 8 to OpenJDK 17.0.2 using OpenRewrite recipes. Built to run as a Windows service.

## Architecture
- **Backend**: Java Spring Boot 3.2.5 (embedded Tomcat, port 5000)
- **Frontend**: Static HTML/CSS/JS served from Spring Boot classpath
- **Build**: Maven (`mvn clean package -DskipTests`)
- **Run**: `java -jar target/java-migration-service-1.0.0.jar`

## Project Structure
```
pom.xml
src/main/java/com/migration/
  MigrationApplication.java          - Spring Boot entry point
  controller/
    MigrationController.java         - REST API endpoints
  model/
    MigrationJob.java                - Migration job state (with push tracking)
    MigrationRequest.java            - API request DTO (pushToNewBranch, targetBranchName)
    MigrationStep.java               - Step tracking
    Recipe.java                      - OpenRewrite recipe info
  service/
    MigrationOrchestrator.java       - Orchestrates the migration pipeline
    GitService.java                  - JGit-based repo cloning + commit/push
    AnalysisService.java             - Pom.xml + Java source analysis
    RewriteConfigService.java        - OpenRewrite plugin injection
    MigrationExecutorService.java    - Maven execution + report generation
src/main/resources/
  application.properties             - Server config
  static/                            - Frontend (index.html, css/, js/)
```

## Key Dependencies
- Spring Boot 3.2.5 (web starter)
- JGit 6.9.0 (Git operations)
- Jackson (JSON + XML processing)
- OpenRewrite Maven Plugin 5.42.2 (injected into target projects)
- rewrite-migrate-java 2.25.0 (recipe library)

## API Endpoints
- POST /api/migrations - Start a migration (supports pushToNewBranch, targetBranchName)
- GET /api/migrations - List all migrations
- GET /api/migrations/{id} - Get migration status
- GET /api/migrations/{id}/report - Get migration report
- GET /api/recipes - List available recipes
- GET /api/dashboard - Aggregated metrics across all migrations
- GET /api/health - Health check

## Supported Migration Paths
- Java 8 → 11, 17, or 21
- Java 11 → 17 or 21
- Java 17 → 21
- Version selection via dropdowns in the UI; target options filtered to only show versions higher than source

## Migration Pipeline
1. Clone repo via JGit (shallow clone by default; full clone when push enabled)
2. Analyze pom.xml (detect JDK version, dependencies)
3. Scan Java files (JAXB, JAX-WS, Nashorn, reflection, deprecated APIs)
4. Inject OpenRewrite plugin + configure version-appropriate recipes
5. Run mvn rewrite:run
6. Generate migration report
7. (Optional) Commit and push changes to new branch (e.g., migration/jdk{target}-{timestamp})
8. Cleanup cloned repo

## Push to Branch Feature
- When "Push migrated code to a new branch" is checked, the service:
  - Does a full (non-shallow) clone to support push
  - Creates a new branch (auto-named `migration/jdk17-{timestamp}` or user-specified)
  - Commits all changes with a descriptive commit message
  - Pushes to the remote origin
  - Requires authentication (username + app password/token)
- Push result is tracked in the migration job and displayed in both the progress UI and history

## Dashboard & Effort Analysis
- Dashboard tab shows aggregated metrics across all completed migrations
- Effort estimation uses industry averages (0.5h/Java file, 2h/high issue, 1h/medium, 4h/module migration, 8h/module testing)
- Change history captures JGit diffs with file-level line counts, change types, and inline diff text
- Per-migration reports include Effort Analysis and Change History sections with expandable diffs

## Development Notes
- Static resources (HTML/CSS/JS) in `src/main/resources/static/` are served at runtime without recompile
- Java source changes require: `mvn clean package -DskipTests` then restart workflow
- History is in-memory only (no database; lost on restart)
- Max 3 concurrent migrations (configurable in application.properties)
- Cross-platform Maven execution: auto-selects mvn.cmd on Windows, mvn on Linux
