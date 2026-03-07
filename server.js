const express = require('express');
const session = require('express-session');
const path = require('path');
const { v4: uuidv4 } = require('uuid');
const migrationService = require('./services/migrationService');
const gitService = require('./services/gitService');

const fs = require('fs');

const app = express();
const PORT = 5000;
const MAX_CONCURRENT_MIGRATIONS = 3;
let activeMigrations = 0;

app.use(express.json());
app.use(express.urlencoded({ extended: true }));
app.use(express.static(path.join(__dirname, 'public')));

app.use(session({
  secret: process.env.SESSION_SECRET || 'migration-service-secret',
  resave: false,
  saveUninitialized: false,
  cookie: { maxAge: 24 * 60 * 60 * 1000 }
}));

const migrations = new Map();

app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

app.post('/api/migrations', async (req, res) => {
  try {
    const { repoUrl, branch, credentials } = req.body;

    if (!repoUrl || !branch) {
      return res.status(400).json({ error: 'Repository URL and branch are required' });
    }

    const migrationId = uuidv4();
    const migration = {
      id: migrationId,
      repoUrl,
      branch,
      status: 'queued',
      createdAt: new Date().toISOString(),
      steps: [],
      report: null,
      error: null
    };

    migrations.set(migrationId, migration);

    if (activeMigrations >= MAX_CONCURRENT_MIGRATIONS) {
      return res.status(429).json({ error: `Maximum ${MAX_CONCURRENT_MIGRATIONS} concurrent migrations allowed. Please wait for current migrations to complete.` });
    }

    processMigration(migrationId, repoUrl, branch, credentials);

    res.json({ migrationId, status: 'queued' });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.get('/api/migrations', (req, res) => {
  const list = Array.from(migrations.values())
    .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
  res.json(list);
});

app.get('/api/migrations/:id', (req, res) => {
  const migration = migrations.get(req.params.id);
  if (!migration) {
    return res.status(404).json({ error: 'Migration not found' });
  }
  res.json(migration);
});

app.get('/api/migrations/:id/report', (req, res) => {
  const migration = migrations.get(req.params.id);
  if (!migration) {
    return res.status(404).json({ error: 'Migration not found' });
  }
  if (!migration.report) {
    return res.status(404).json({ error: 'Report not yet available' });
  }
  res.json(migration.report);
});

app.get('/api/recipes', (req, res) => {
  res.json(migrationService.getAvailableRecipes());
});

async function processMigration(migrationId, repoUrl, branch, credentials) {
  const migration = migrations.get(migrationId);
  let clonePath = null;
  activeMigrations++;

  try {
    updateStep(migration, 'clone', 'in_progress', 'Cloning repository...');
    clonePath = await gitService.cloneRepo(repoUrl, branch, migrationId, credentials);
    updateStep(migration, 'clone', 'completed', 'Repository cloned successfully');

    updateStep(migration, 'analyze', 'in_progress', 'Analyzing project structure...');
    const analysis = await migrationService.analyzeProject(clonePath);
    updateStep(migration, 'analyze', 'completed', `Found ${analysis.modules.length} module(s), current Java version: ${analysis.sourceVersion}`);
    migration.analysis = analysis;

    updateStep(migration, 'configure', 'in_progress', 'Configuring OpenRewrite recipes...');
    const config = await migrationService.configureRewrite(clonePath, analysis);
    updateStep(migration, 'configure', 'completed', `Configured ${config.recipesApplied.length} recipe(s)`);

    updateStep(migration, 'migrate', 'in_progress', 'Applying migration recipes...');
    const result = await migrationService.applyMigration(clonePath, analysis);
    updateStep(migration, 'migrate', 'completed', 'Migration recipes applied');

    updateStep(migration, 'report', 'in_progress', 'Generating migration report...');
    const report = await migrationService.generateReport(clonePath, analysis, result);
    migration.report = report;
    updateStep(migration, 'report', 'completed', 'Migration report generated');

    migration.status = 'completed';
  } catch (err) {
    migration.status = 'failed';
    migration.error = err.message;
    const currentStep = migration.steps.find(s => s.status === 'in_progress');
    if (currentStep) {
      currentStep.status = 'failed';
      currentStep.message = err.message;
    }
  } finally {
    activeMigrations--;
    if (clonePath) {
      try {
        fs.rmSync(clonePath, { recursive: true, force: true });
      } catch {}
    }
  }
}

function updateStep(migration, stepName, status, message) {
  const existing = migration.steps.find(s => s.name === stepName);
  if (existing) {
    existing.status = status;
    existing.message = message;
    existing.updatedAt = new Date().toISOString();
  } else {
    migration.steps.push({
      name: stepName,
      status,
      message,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    });
  }
  migration.status = status === 'failed' ? 'failed' : 'in_progress';
}

app.get('/{*path}', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`Java Migration Service running on port ${PORT}`);
});
