let currentMigrationId = null;
let pollingInterval = null;

function showView(viewName) {
    document.querySelectorAll('.view').forEach(function(v) { v.classList.remove('active'); });
    document.querySelectorAll('.nav-btn').forEach(function(b) { b.classList.remove('active'); });
    document.getElementById(viewName + '-view').classList.add('active');
    document.querySelector('[data-view="' + viewName + '"]').classList.add('active');

    if (viewName === 'history') loadHistory();
    if (viewName === 'recipes') loadRecipes();
}

function startMigration(e) {
    e.preventDefault();

    var repoUrl = document.getElementById('repo-url').value.trim();
    var branch = document.getElementById('branch').value.trim();
    var username = document.getElementById('username').value.trim();
    var password = document.getElementById('password').value.trim();

    if (!repoUrl || !branch) return;

    var submitBtn = document.getElementById('submit-btn');
    submitBtn.disabled = true;
    submitBtn.querySelector('.btn-text').style.display = 'none';
    submitBtn.querySelector('.btn-loading').style.display = 'inline';

    var body = { repoUrl: repoUrl, branch: branch };
    if (username) body.username = username;
    if (password) body.password = password;

    fetch('/api/migrations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    })
    .then(function(res) { return res.json().then(function(data) { return { ok: res.ok, data: data }; }); })
    .then(function(result) {
        if (!result.ok) {
            alert(result.data.error || 'Failed to start migration');
            return;
        }
        currentMigrationId = result.data.migrationId;
        document.getElementById('migration-progress').style.display = 'block';
        document.getElementById('migration-report').style.display = 'none';
        startPolling(currentMigrationId);
    })
    .catch(function(err) { alert('Error: ' + err.message); })
    .finally(function() {
        submitBtn.disabled = false;
        submitBtn.querySelector('.btn-text').style.display = 'inline';
        submitBtn.querySelector('.btn-loading').style.display = 'none';
    });
}

function startPolling(migrationId) {
    if (pollingInterval) clearInterval(pollingInterval);
    pollMigration(migrationId);
    pollingInterval = setInterval(function() { pollMigration(migrationId); }, 2000);
}

function pollMigration(migrationId) {
    fetch('/api/migrations/' + migrationId)
        .then(function(res) { return res.json(); })
        .then(function(migration) {
            updateProgressUI(migration);
            if (migration.status === 'completed' || migration.status === 'failed') {
                clearInterval(pollingInterval);
                pollingInterval = null;
                if (migration.status === 'completed' && migration.report) {
                    showReport(migration.report);
                }
            }
        })
        .catch(function(err) { console.error('Polling error:', err); });
}

function updateProgressUI(migration) {
    var statusBadge = document.getElementById('migration-status');
    statusBadge.textContent = migration.status.replace('_', ' ');
    statusBadge.className = 'status-badge ' + migration.status;

    var stepsContainer = document.getElementById('progress-steps');
    var stepNames = {
        clone: 'Clone Repository',
        analyze: 'Analyze Project',
        configure: 'Configure OpenRewrite',
        migrate: 'Apply Migration',
        report: 'Generate Report'
    };
    var stepIcons = { pending: '\u25CB', in_progress: '\u25CF', completed: '\u2713', failed: '\u2717' };
    var allSteps = ['clone', 'analyze', 'configure', 'migrate', 'report'];
    var html = '';

    for (var i = 0; i < allSteps.length; i++) {
        var stepName = allSteps[i];
        var step = null;
        if (migration.steps) {
            for (var j = 0; j < migration.steps.length; j++) {
                if (migration.steps[j].name === stepName) { step = migration.steps[j]; break; }
            }
        }
        var status = step ? step.status : 'pending';
        var message = step ? step.message : 'Waiting...';
        html += '<div class="step-item">' +
            '<div class="step-icon ' + status + '">' + (stepIcons[status] || '\u25CB') + '</div>' +
            '<div class="step-content"><h3>' + stepNames[stepName] + '</h3><p>' + message + '</p></div></div>';
    }
    stepsContainer.innerHTML = html;
}

function showReport(report) {
    document.getElementById('migration-report').style.display = 'block';
    var container = document.getElementById('report-content');
    var html = '';

    var s = report.summary || {};
    html += '<div class="report-section"><h3>Summary</h3><div class="stats-grid">' +
        statCard(report.sourceVersion || 'unknown', 'Source Version', '') +
        statCard(report.targetVersion || '17', 'Target Version', 'low') +
        statCard(s.totalModules || 0, 'Maven Modules', '') +
        statCard(s.totalJavaFiles || 0, 'Java Files', '') +
        statCard(s.highSeverityIssues || 0, 'High Issues', 'high') +
        statCard(s.mediumSeverityIssues || 0, 'Medium Issues', 'medium') +
        statCard(s.lowSeverityIssues || 0, 'Low Issues', 'low') +
        statCard(s.pomFilesModified || 0, 'POMs Modified', '') +
        '</div></div>';

    if (report.issues && report.issues.length > 0) {
        html += '<div class="report-section"><h3>Issues Found (' + report.issues.length + ')</h3>';
        for (var i = 0; i < report.issues.length; i++) {
            var issue = report.issues[i];
            html += '<div class="issue-item"><div class="issue-severity ' + issue.severity + '"></div>' +
                '<div class="issue-content"><h4>' + issue.message + '</h4>' +
                '<p><code>' + issue.file + '</code> - ' + issue.suggestion + '</p></div></div>';
        }
        html += '</div>';
    }

    if (report.recommendations && report.recommendations.length > 0) {
        html += '<div class="report-section"><h3>Recommendations</h3>';
        for (var i = 0; i < report.recommendations.length; i++) {
            var rec = report.recommendations[i];
            html += '<div class="recommendation-item"><span class="rec-priority ' + rec.priority + '">' + rec.priority + '</span><div>' +
                '<strong>' + rec.title + '</strong><p style="font-size:13px;color:var(--gray-600);margin-top:4px">' + rec.description + '</p>' +
                (rec.recipe ? '<code style="margin-top:6px;display:inline-block">' + rec.recipe + '</code>' : '') +
                '</div></div>';
        }
        html += '</div>';
    }

    var changes = report.changes || {};
    if (changes.pomChanges && changes.pomChanges.length > 0) {
        html += '<div class="report-section"><h3>Changes Applied</h3>';
        for (var i = 0; i < changes.pomChanges.length; i++) {
            var change = changes.pomChanges[i];
            html += '<div style="margin-bottom:12px"><strong style="font-size:14px">' + change.file + '</strong><ul class="changes-list">';
            var cList = change.changes || [];
            for (var j = 0; j < cList.length; j++) { html += '<li>' + cList[j] + '</li>'; }
            html += '</ul></div>';
        }
        html += '</div>';
    }

    var warnings = report.warnings || [];
    if (warnings.length > 0) {
        html += '<div class="report-section"><h3>Warnings</h3>';
        for (var i = 0; i < warnings.length; i++) {
            html += '<div class="warning-item">' + warnings[i] + '</div>';
        }
        html += '</div>';
    }

    var nextSteps = report.nextSteps || [];
    if (nextSteps.length > 0) {
        html += '<div class="report-section"><h3>Next Steps</h3><ul class="next-steps-list">';
        for (var i = 0; i < nextSteps.length; i++) { html += '<li>' + nextSteps[i] + '</li>'; }
        html += '</ul></div>';
    }

    var oc = report.openRewriteConfig || {};
    html += '<div class="report-section"><h3>OpenRewrite Configuration</h3>' +
        '<p style="font-size:13px;color:var(--gray-600);margin-bottom:8px">Plugin version: <code>' +
        (oc.pluginVersion || '') + '</code> | Recipe version: <code>' + (oc.recipeVersion || '') + '</code></p><div>';
    var recipes = oc.recipes || [];
    for (var i = 0; i < recipes.length; i++) {
        html += '<code style="display:block;margin-bottom:4px">' + recipes[i] + '</code>';
    }
    html += '</div></div>';

    container.innerHTML = html;
}

function statCard(value, label, cls) {
    return '<div class="stat-card"><div class="stat-value ' + cls + '">' + value +
        '</div><div class="stat-label">' + label + '</div></div>';
}

function formatStepDuration(step) {
    if (!step || !step.createdAt || !step.updatedAt) return '-';
    var start = new Date(step.createdAt).getTime();
    var end = new Date(step.updatedAt).getTime();
    var diffMs = end - start;
    if (diffMs < 0) return '-';
    var secs = Math.round(diffMs / 1000);
    if (secs < 60) return secs + 's';
    var mins = Math.floor(secs / 60);
    secs = secs % 60;
    return mins + 'm ' + secs + 's';
}

function getStepStatusIcon(status) {
    if (status === 'completed') return '<span class="step-status-dot completed"></span>';
    if (status === 'failed') return '<span class="step-status-dot failed"></span>';
    if (status === 'in_progress') return '<span class="step-status-dot in_progress"></span>';
    return '<span class="step-status-dot pending"></span>';
}

function loadHistory() {
    fetch('/api/migrations')
        .then(function(res) { return res.json(); })
        .then(function(migrations) {
            var container = document.getElementById('history-list');
            if (!migrations || migrations.length === 0) {
                container.innerHTML = '<p class="empty-state">No migrations yet. Start a new migration to see results here.</p>';
                return;
            }

            var stepLabels = {
                clone: 'Clone Repository',
                analyze: 'Analyze Project',
                configure: 'Configure OpenRewrite',
                migrate: 'Apply Migration',
                report: 'Generate Report'
            };
            var allStepKeys = ['clone', 'analyze', 'configure', 'migrate', 'report'];

            var html = '';
            for (var i = 0; i < migrations.length; i++) {
                var m = migrations[i];
                var date = new Date(m.createdAt).toLocaleString();
                var repoName = m.repoUrl.split('/').pop().replace('.git', '');
                var totalTime = m.totalTimeTaken || '-';
                var javaFiles = m.totalJavaFiles || 0;
                var modules = m.totalModules || 0;
                var issues = m.totalIssues || 0;

                html += '<div class="history-card">';
                html += '<div class="history-card-header" onclick="toggleHistoryDetail(\'' + m.id + '\')">';
                html += '<div class="history-info"><h3>' + repoName + '</h3>';
                html += '<p>' + m.repoUrl + ' &mdash; branch: <strong>' + m.branch + '</strong></p></div>';
                html += '<div class="history-meta">';
                html += '<div class="history-stats">';
                html += '<span class="history-stat" title="Total Time"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg> ' + totalTime + '</span>';
                html += '<span class="history-stat" title="Modules">' + modules + ' module' + (modules !== 1 ? 's' : '') + '</span>';
                html += '<span class="history-stat" title="Java Files">' + javaFiles + ' files</span>';
                if (issues > 0) {
                    html += '<span class="history-stat issues" title="Issues Found">' + issues + ' issue' + (issues !== 1 ? 's' : '') + '</span>';
                }
                html += '</div>';
                html += '<span class="history-date">' + date + '</span>';
                html += '<span class="status-badge ' + m.status + '">' + m.status.replace('_', ' ') + '</span>';
                html += '</div></div>';

                html += '<div class="history-detail" id="history-detail-' + m.id + '" style="display:none">';
                html += '<table class="timing-table"><thead><tr>';
                html += '<th>Step</th><th>Status</th><th>Time Taken</th><th>Details</th>';
                html += '</tr></thead><tbody>';

                for (var j = 0; j < allStepKeys.length; j++) {
                    var key = allStepKeys[j];
                    var step = null;
                    if (m.steps) {
                        for (var k = 0; k < m.steps.length; k++) {
                            if (m.steps[k].name === key) { step = m.steps[k]; break; }
                        }
                    }
                    var status = step ? step.status : 'pending';
                    var msg = step ? step.message : '-';
                    var duration = (status === 'completed' || status === 'failed') ? formatStepDuration(step) : '-';

                    html += '<tr>';
                    html += '<td class="step-name-cell">' + stepLabels[key] + '</td>';
                    html += '<td>' + getStepStatusIcon(status) + ' <span class="step-status-text ' + status + '">' + status.replace('_', ' ') + '</span></td>';
                    html += '<td class="duration-cell">' + duration + '</td>';
                    html += '<td class="message-cell">' + msg + '</td>';
                    html += '</tr>';
                }

                html += '</tbody></table>';

                html += '<div class="history-actions">';
                html += '<button class="btn btn-primary btn-sm" onclick="viewMigration(\'' + m.id + '\')">View Full Report</button>';
                html += '</div>';
                html += '</div>';
                html += '</div>';
            }
            container.innerHTML = html;
        })
        .catch(function(err) { console.error('Failed to load history:', err); });
}

function toggleHistoryDetail(id) {
    var el = document.getElementById('history-detail-' + id);
    if (el) {
        el.style.display = el.style.display === 'none' ? 'block' : 'none';
    }
}

function viewMigration(id) {
    showView('migrate');
    currentMigrationId = id;
    document.getElementById('migration-progress').style.display = 'block';

    fetch('/api/migrations/' + id)
        .then(function(res) { return res.json(); })
        .then(function(migration) {
            updateProgressUI(migration);
            if (migration.status === 'completed' && migration.report) {
                showReport(migration.report);
            } else if (migration.status === 'in_progress') {
                startPolling(id);
            }
        })
        .catch(function(err) { console.error('Failed to load migration:', err); });
}

function loadRecipes() {
    fetch('/api/recipes')
        .then(function(res) { return res.json(); })
        .then(function(recipes) {
            var container = document.getElementById('recipes-list');
            var html = '';
            for (var i = 0; i < recipes.length; i++) {
                var recipe = recipes[i];
                html += '<div class="recipe-card ' + (recipe.required ? 'required' : '') + '">' +
                    '<div class="recipe-header"><h3>' + recipe.name + '</h3>' +
                    '<span class="recipe-tag ' + (recipe.required ? 'required' : 'optional') + '">' +
                    (recipe.required ? 'Required' : 'Optional') + '</span></div>' +
                    '<p>' + recipe.description + '</p><code>' + recipe.id + '</code></div>';
            }
            container.innerHTML = html;
        })
        .catch(function(err) { console.error('Failed to load recipes:', err); });
}

loadRecipes();
