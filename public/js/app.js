let currentMigrationId = null;
let pollingInterval = null;

function showView(viewName) {
    document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
    document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));
    document.getElementById(viewName + '-view').classList.add('active');
    document.querySelector(`[data-view="${viewName}"]`).classList.add('active');

    if (viewName === 'history') loadHistory();
    if (viewName === 'recipes') loadRecipes();
}

async function startMigration(e) {
    e.preventDefault();

    const repoUrl = document.getElementById('repo-url').value.trim();
    const branch = document.getElementById('branch').value.trim();
    const username = document.getElementById('username').value.trim();
    const password = document.getElementById('password').value.trim();

    if (!repoUrl || !branch) return;

    const submitBtn = document.getElementById('submit-btn');
    submitBtn.disabled = true;
    submitBtn.querySelector('.btn-text').style.display = 'none';
    submitBtn.querySelector('.btn-loading').style.display = 'inline';

    try {
        const body = { repoUrl, branch };
        if (username || password) {
            body.credentials = { username, password };
        }

        const res = await fetch('/api/migrations', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });

        const data = await res.json();

        if (!res.ok) {
            alert(data.error || 'Failed to start migration');
            return;
        }

        currentMigrationId = data.migrationId;
        document.getElementById('migration-progress').style.display = 'block';
        document.getElementById('migration-report').style.display = 'none';
        startPolling(currentMigrationId);
    } catch (err) {
        alert('Error: ' + err.message);
    } finally {
        submitBtn.disabled = false;
        submitBtn.querySelector('.btn-text').style.display = 'inline';
        submitBtn.querySelector('.btn-loading').style.display = 'none';
    }
}

function startPolling(migrationId) {
    if (pollingInterval) clearInterval(pollingInterval);
    pollMigration(migrationId);
    pollingInterval = setInterval(() => pollMigration(migrationId), 2000);
}

async function pollMigration(migrationId) {
    try {
        const res = await fetch(`/api/migrations/${migrationId}`);
        const migration = await res.json();

        updateProgressUI(migration);

        if (migration.status === 'completed' || migration.status === 'failed') {
            clearInterval(pollingInterval);
            pollingInterval = null;

            if (migration.status === 'completed' && migration.report) {
                showReport(migration.report);
            }
        }
    } catch (err) {
        console.error('Polling error:', err);
    }
}

function updateProgressUI(migration) {
    const statusBadge = document.getElementById('migration-status');
    statusBadge.textContent = migration.status.replace('_', ' ');
    statusBadge.className = 'status-badge ' + migration.status;

    const stepsContainer = document.getElementById('progress-steps');

    const stepNames = {
        clone: 'Clone Repository',
        analyze: 'Analyze Project',
        configure: 'Configure OpenRewrite',
        migrate: 'Apply Migration',
        report: 'Generate Report'
    };

    const stepIcons = {
        pending: '\u25CB',
        in_progress: '\u25CF',
        completed: '\u2713',
        failed: '\u2717'
    };

    const allSteps = ['clone', 'analyze', 'configure', 'migrate', 'report'];
    let html = '';

    for (const stepName of allSteps) {
        const step = migration.steps.find(s => s.name === stepName);
        const status = step ? step.status : 'pending';
        const message = step ? step.message : 'Waiting...';

        html += `
            <div class="step-item">
                <div class="step-icon ${status}">${stepIcons[status] || '\u25CB'}</div>
                <div class="step-content">
                    <h3>${stepNames[stepName]}</h3>
                    <p>${message}</p>
                </div>
            </div>
        `;
    }

    stepsContainer.innerHTML = html;
}

function showReport(report) {
    document.getElementById('migration-report').style.display = 'block';
    const container = document.getElementById('report-content');

    let html = '';

    html += `
        <div class="report-section">
            <h3>Summary</h3>
            <div class="stats-grid">
                <div class="stat-card">
                    <div class="stat-value">${report.sourceVersion}</div>
                    <div class="stat-label">Source Version</div>
                </div>
                <div class="stat-card">
                    <div class="stat-value low">${report.targetVersion}</div>
                    <div class="stat-label">Target Version</div>
                </div>
                <div class="stat-card">
                    <div class="stat-value">${report.summary.totalModules}</div>
                    <div class="stat-label">Maven Modules</div>
                </div>
                <div class="stat-card">
                    <div class="stat-value">${report.summary.totalJavaFiles}</div>
                    <div class="stat-label">Java Files</div>
                </div>
                <div class="stat-card">
                    <div class="stat-value high">${report.summary.highSeverityIssues}</div>
                    <div class="stat-label">High Issues</div>
                </div>
                <div class="stat-card">
                    <div class="stat-value medium">${report.summary.mediumSeverityIssues}</div>
                    <div class="stat-label">Medium Issues</div>
                </div>
                <div class="stat-card">
                    <div class="stat-value low">${report.summary.lowSeverityIssues}</div>
                    <div class="stat-label">Low Issues</div>
                </div>
                <div class="stat-card">
                    <div class="stat-value">${report.summary.pomFilesModified}</div>
                    <div class="stat-label">POMs Modified</div>
                </div>
            </div>
        </div>
    `;

    if (report.issues && report.issues.length > 0) {
        html += `<div class="report-section"><h3>Issues Found (${report.issues.length})</h3>`;
        for (const issue of report.issues) {
            const fileName = issue.file.split('/').pop();
            html += `
                <div class="issue-item">
                    <div class="issue-severity ${issue.severity}"></div>
                    <div class="issue-content">
                        <h4>${issue.message}</h4>
                        <p><code>${fileName}</code> - ${issue.suggestion}</p>
                    </div>
                </div>
            `;
        }
        html += '</div>';
    }

    if (report.recommendations && report.recommendations.length > 0) {
        html += `<div class="report-section"><h3>Recommendations</h3>`;
        for (const rec of report.recommendations) {
            html += `
                <div class="recommendation-item">
                    <span class="rec-priority ${rec.priority}">${rec.priority}</span>
                    <div>
                        <strong>${rec.title}</strong>
                        <p style="font-size:13px;color:var(--gray-600);margin-top:4px">${rec.description}</p>
                        ${rec.recipe ? `<code style="margin-top:6px;display:inline-block">${rec.recipe}</code>` : ''}
                    </div>
                </div>
            `;
        }
        html += '</div>';
    }

    if (report.changes && report.changes.pom && report.changes.pom.length > 0) {
        html += `<div class="report-section"><h3>Changes Applied</h3>`;
        for (const change of report.changes.pom) {
            const fileName = change.file.split('/').pop();
            html += `
                <div style="margin-bottom:12px">
                    <strong style="font-size:14px">${fileName}</strong>
                    <ul class="changes-list">
                        ${change.changes.map(c => `<li>${c}</li>`).join('')}
                    </ul>
                </div>
            `;
        }
        html += '</div>';
    }

    if (report.warnings && report.warnings.length > 0) {
        html += `<div class="report-section"><h3>Warnings</h3>`;
        for (const warning of report.warnings) {
            html += `<div class="warning-item">${warning}</div>`;
        }
        html += '</div>';
    }

    if (report.nextSteps && report.nextSteps.length > 0) {
        html += `
            <div class="report-section">
                <h3>Next Steps</h3>
                <ul class="next-steps-list">
                    ${report.nextSteps.map(s => `<li>${s}</li>`).join('')}
                </ul>
            </div>
        `;
    }

    html += `
        <div class="report-section">
            <h3>OpenRewrite Configuration</h3>
            <p style="font-size:13px;color:var(--gray-600);margin-bottom:8px">
                Plugin version: <code>${report.openRewriteConfig.pluginVersion}</code> |
                Recipe version: <code>${report.openRewriteConfig.recipeVersion}</code>
            </p>
            <div>
                ${report.openRewriteConfig.recipes.map(r => `<code style="display:block;margin-bottom:4px">${r}</code>`).join('')}
            </div>
        </div>
    `;

    container.innerHTML = html;
}

async function loadHistory() {
    try {
        const res = await fetch('/api/migrations');
        const migrations = await res.json();

        const container = document.getElementById('history-list');

        if (!migrations || migrations.length === 0) {
            container.innerHTML = '<p class="empty-state">No migrations yet. Start a new migration to see results here.</p>';
            return;
        }

        let html = '';
        for (const m of migrations) {
            const date = new Date(m.createdAt).toLocaleString();
            const repoName = m.repoUrl.split('/').pop().replace('.git', '');

            html += `
                <div class="history-item" onclick="viewMigration('${m.id}')">
                    <div class="history-info">
                        <h3>${repoName}</h3>
                        <p>${m.repoUrl} - ${m.branch}</p>
                    </div>
                    <div class="history-meta">
                        <span class="history-date">${date}</span>
                        <span class="status-badge ${m.status}">${m.status.replace('_', ' ')}</span>
                    </div>
                </div>
            `;
        }

        container.innerHTML = html;
    } catch (err) {
        console.error('Failed to load history:', err);
    }
}

async function viewMigration(id) {
    showView('migrate');
    currentMigrationId = id;
    document.getElementById('migration-progress').style.display = 'block';

    try {
        const res = await fetch(`/api/migrations/${id}`);
        const migration = await res.json();

        updateProgressUI(migration);

        if (migration.status === 'completed' && migration.report) {
            showReport(migration.report);
        } else if (migration.status === 'in_progress') {
            startPolling(id);
        }
    } catch (err) {
        console.error('Failed to load migration:', err);
    }
}

async function loadRecipes() {
    try {
        const res = await fetch('/api/recipes');
        const recipes = await res.json();

        const container = document.getElementById('recipes-list');
        let html = '';

        for (const recipe of recipes) {
            html += `
                <div class="recipe-card ${recipe.required ? 'required' : ''}">
                    <div class="recipe-header">
                        <h3>${recipe.name}</h3>
                        <span class="recipe-tag ${recipe.required ? 'required' : 'optional'}">
                            ${recipe.required ? 'Required' : 'Optional'}
                        </span>
                    </div>
                    <p>${recipe.description}</p>
                    <code>${recipe.id}</code>
                </div>
            `;
        }

        container.innerHTML = html;
    } catch (err) {
        console.error('Failed to load recipes:', err);
    }
}

loadRecipes();
