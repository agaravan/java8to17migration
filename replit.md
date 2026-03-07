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
    MigrationJob.java                - Migration job state
    MigrationRequest.java            - API request DTO
    MigrationStep.java               - Step tracking
    Recipe.java                      - OpenRewrite recipe info
  service/
    MigrationOrchestrator.java       - Orchestrates the migration pipeline
    GitService.java                  - JGit-based repo cloning
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
- POST /api/migrations - Start a migration
- GET /api/migrations - List all migrations
- GET /api/migrations/{id} - Get migration status
- GET /api/migrations/{id}/report - Get migration report
- GET /api/recipes - List available recipes
- GET /api/health - Health check

## Migration Pipeline
1. Clone repo via JGit (supports Bitbucket auth)
2. Analyze pom.xml (detect JDK version, dependencies)
3. Scan Java files (JAXB, JAX-WS, Nashorn, reflection, deprecated APIs)
4. Inject OpenRewrite plugin + configure recipes
5. Run mvn rewrite:run
6. Generate migration report
7. Cleanup cloned repo
