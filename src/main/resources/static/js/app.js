let currentMigrationId = null;
let pollingInterval = null;

(function loadAppVersion() {
    fetch('/api/version')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            var el = document.getElementById('app-version');
            if (el && data.version) el.textContent = 'v' + data.version;
        })
        .catch(function() {});
})();

function showView(viewName) {
    document.querySelectorAll('.view').forEach(function(v) { v.classList.remove('active'); });
    document.querySelectorAll('.nav-btn').forEach(function(b) { b.classList.remove('active'); });
    document.getElementById(viewName + '-view').classList.add('active');
    document.querySelector('[data-view="' + viewName + '"]').classList.add('active');

    if (viewName === 'dashboard') loadDashboard();
    if (viewName === 'history') loadHistory();
    if (viewName === 'recipes') loadRecipes();
}

function startMigration(e) {
    e.preventDefault();

    var repoUrl = document.getElementById('repo-url').value.trim();
    var branch = document.getElementById('branch').value.trim();
    var username = document.getElementById('username').value.trim();
    var password = document.getElementById('password').value.trim();
    var sourceVersion = parseInt(document.getElementById('source-version').value);
    var targetVersion = parseInt(document.getElementById('target-version').value);

    if (!repoUrl || !branch) return;

    if (targetVersion <= sourceVersion) {
        alert('Target version must be higher than source version.');
        return;
    }

    var submitBtn = document.getElementById('submit-btn');
    submitBtn.disabled = true;
    submitBtn.querySelector('.btn-text').style.display = 'none';
    submitBtn.querySelector('.btn-loading').style.display = 'inline';

    var pushToNewBranch = document.getElementById('push-to-branch').checked;
    var targetBranchName = document.getElementById('target-branch').value.trim();

    if (pushToNewBranch && !username && !password) {
        alert('Authentication is required when pushing to a new branch. Please provide credentials.');
        submitBtn.disabled = false;
        submitBtn.querySelector('.btn-text').style.display = 'inline';
        submitBtn.querySelector('.btn-loading').style.display = 'none';
        return;
    }

    var body = { repoUrl: repoUrl, branch: branch, sourceVersion: sourceVersion, targetVersion: targetVersion };
    if (username) body.username = username;
    if (password) body.password = password;
    if (pushToNewBranch) {
        body.pushToNewBranch = true;
        if (targetBranchName) body.targetBranchName = targetBranchName;
    }

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
                    showReport(migration.report, migration.pushResult);
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
        report: 'Generate Report',
        push: 'Push to New Branch'
    };
    var stepIcons = { pending: '\u25CB', in_progress: '\u25CF', completed: '\u2713', failed: '\u2717', warning: '\u26A0' };
    var allSteps = ['clone', 'analyze', 'configure', 'migrate', 'report'];
    var hasPushStep = migration.steps && migration.steps.some(function(s) { return s.name === 'push'; });
    if (hasPushStep || migration.pushToNewBranch) allSteps.push('push');
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

function showReport(report, pushResult) {
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

    var ee = report.effortEstimate || {};
    if (ee.estimatedManualEffortHours) {
        html += '<div class="report-section"><h3>Effort Analysis</h3>';
        html += '<div class="stats-grid">' +
            statCard(ee.estimatedManualEffortHours + 'h', 'Est. Manual Effort', 'high') +
            statCard(ee.toolTimeFormatted || '-', 'Tool Time', 'low') +
            statCard(ee.automationPercentage + '%', 'Automated', 'low') +
            statCard(ee.remainingManualHours + 'h', 'Remaining Manual', 'medium') +
            statCard(ee.timeSavedHours + 'h', 'Time Saved', 'low') +
            '</div>';
        if (ee.breakdown && ee.breakdown.length > 0) {
            html += '<table class="timing-table" style="margin-top:16px"><thead><tr>' +
                '<th>Category</th><th>Count</th><th>Est. Manual Hours</th><th>Status</th></tr></thead><tbody>';
            for (var i = 0; i < ee.breakdown.length; i++) {
                var b = ee.breakdown[i];
                html += '<tr><td class="step-name-cell">' + escapeHtml(b.category) + '</td>' +
                    '<td>' + b.count + '</td>' +
                    '<td class="duration-cell">' + b.manualHours + 'h</td>' +
                    '<td class="message-cell">' + escapeHtml(b.status) + '</td></tr>';
            }
            html += '</tbody></table>';
        }
        html += '</div>';
    }

    var cs = report.changeSummary || {};
    var fc = report.fileChanges || [];
    if (fc.length > 0) {
        html += '<div class="report-section"><h3>Change History (' + fc.length + ' files)</h3>';
        html += '<div class="stats-grid" style="margin-bottom:16px">' +
            statCard(cs.totalFilesChanged || 0, 'Files Changed', '') +
            statCard(cs.javaFilesChanged || 0, 'Java Files', '') +
            statCard(cs.configFilesChanged || 0, 'Config Files', '') +
            statCard('+' + (cs.totalLinesAdded || 0), 'Lines Added', 'low') +
            statCard('-' + (cs.totalLinesRemoved || 0), 'Lines Removed', 'high') +
            '</div>';
        html += '<table class="timing-table"><thead><tr>' +
            '<th>File</th><th>Change</th><th>Lines +/-</th><th>Type</th></tr></thead><tbody>';
        for (var i = 0; i < fc.length; i++) {
            var f = fc[i];
            var changeClass = f.changeType === 'ADD' ? 'low' : (f.changeType === 'DELETE' ? 'high' : '');
            html += '<tr class="change-row" onclick="toggleDiff(\'' + i + '\')">' +
                '<td class="step-name-cell" style="cursor:pointer">' + escapeHtml(f.file) + '</td>' +
                '<td><span class="change-badge ' + changeClass + '">' + (f.changeType || '-') + '</span></td>' +
                '<td class="duration-cell">+' + (f.linesAdded || 0) + ' / -' + (f.linesRemoved || 0) + '</td>' +
                '<td>' + escapeHtml(f.fileType || '') + '</td></tr>';
            if (f.diff) {
                html += '<tr id="diff-row-' + i + '" style="display:none"><td colspan="4">' +
                    '<pre class="diff-block">' + escapeHtml(f.diff) + '</pre></td></tr>';
            }
        }
        html += '</tbody></table></div>';
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

    if (pushResult) {
        var pr = pushResult;
        html += '<div class="report-section"><h3>Push Result</h3>';
        html += '<div class="push-info ' + (pr.pushed ? 'success' : 'warning') + '">';
        html += '<strong>' + (pr.pushed ? 'Successfully pushed to: ' : 'Push status: ') + '</strong>';
        html += '<code>' + escapeHtml(pr.branch || '') + '</code>';
        if (pr.changedFiles) html += ' (' + pr.changedFiles + ' files changed)';
        html += '<p style="margin-top:6px;font-size:12px">' + escapeHtml(pr.message || '') + '</p>';
        html += '</div></div>';
    }

    container.innerHTML = html;
}

function escapeHtml(str) {
    if (!str) return '';
    return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
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
                report: 'Generate Report',
                push: 'Push to New Branch'
            };
            var defaultStepKeys = ['clone', 'analyze', 'configure', 'migrate', 'report'];

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

                var allStepKeys = defaultStepKeys.slice();
                var hasPush = m.steps && m.steps.some(function(s) { return s.name === 'push'; });
                if (hasPush || m.pushToNewBranch) allStepKeys.push('push');

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

                if (m.pushResult) {
                    var pushed = m.pushResult.pushed;
                    var pushBranch = escapeHtml(m.pushResult.branch || '');
                    var pushMsg = escapeHtml(m.pushResult.message || '');
                    html += '<div class="push-info' + (pushed ? ' success' : ' warning') + '">';
                    html += '<strong>' + (pushed ? 'Pushed to branch: ' : 'Push status: ') + '</strong>';
                    html += '<code>' + pushBranch + '</code>';
                    if (m.pushResult.changedFiles) html += ' (' + m.pushResult.changedFiles + ' files changed)';
                    if (pushMsg) html += '<p style="margin-top:4px;font-size:12px">' + pushMsg + '</p>';
                    html += '</div>';
                }

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
                showReport(migration.report, migration.pushResult);
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

function toggleTargetBranch() {
    var checked = document.getElementById('push-to-branch').checked;
    document.getElementById('target-branch-group').style.display = checked ? 'block' : 'none';
    var credSection = document.querySelector('.credentials-section');
    if (checked) {
        credSection.open = true;
    }
}

function updateTargetVersions() {
    var sourceVal = parseInt(document.getElementById('source-version').value);
    var targetSelect = document.getElementById('target-version');
    var allVersions = [
        { value: 11, label: 'Java 11' },
        { value: 17, label: 'Java 17' },
        { value: 21, label: 'Java 21' }
    ];
    var currentTarget = parseInt(targetSelect.value);
    targetSelect.innerHTML = '';
    var hasSelected = false;
    for (var i = 0; i < allVersions.length; i++) {
        if (allVersions[i].value > sourceVal) {
            var opt = document.createElement('option');
            opt.value = allVersions[i].value;
            opt.textContent = allVersions[i].label;
            if (allVersions[i].value === currentTarget) {
                opt.selected = true;
                hasSelected = true;
            }
            targetSelect.appendChild(opt);
        }
    }
    if (!hasSelected && targetSelect.options.length > 0) {
        targetSelect.options[0].selected = true;
    }
}

function toggleDiff(idx) {
    var row = document.getElementById('diff-row-' + idx);
    if (row) {
        row.style.display = row.style.display === 'none' ? 'table-row' : 'none';
    }
}

function loadDashboard() {
    fetch('/api/dashboard')
        .then(function(res) { return res.json(); })
        .then(function(d) {
            var container = document.getElementById('dashboard-content');
            if (d.totalMigrations === 0) {
                container.innerHTML = '<p class="empty-state">No migrations yet. Run a migration to see dashboard metrics.</p>';
                return;
            }

            var html = '';

            html += '<div class="dashboard-section"><h3>Overview</h3>';
            html += '<div class="stats-grid">' +
                statCard(d.totalMigrations, 'Total Migrations', '') +
                statCard(d.completedMigrations, 'Completed', 'low') +
                statCard(d.failedMigrations, 'Failed', d.failedMigrations > 0 ? 'high' : '') +
                statCard(d.uniqueRepos, 'Unique Repos', '') +
                statCard(d.totalModules, 'Total Modules', '') +
                statCard(d.totalJavaFiles, 'Java Files Processed', '') +
                statCard(d.totalFilesChanged, 'Files Changed', '') +
                statCard(d.totalLinesChanged, 'Lines Changed', '') +
                '</div></div>';

            html += '<div class="dashboard-section"><h3>Effort Metrics</h3>';
            html += '<div class="effort-comparison">';

            html += '<div class="effort-card manual">';
            html += '<div class="effort-header">Without This Tool</div>';
            html += '<div class="effort-value">' + d.totalEstimatedManualHours + '<span>hours</span></div>';
            html += '<div class="effort-label">Estimated manual migration effort</div>';
            html += '</div>';

            html += '<div class="effort-card tool">';
            html += '<div class="effort-header">With This Tool</div>';
            html += '<div class="effort-value">' + d.totalToolTimeFormatted + '<span></span></div>';
            html += '<div class="effort-label">Actual tool processing time</div>';
            html += '</div>';

            html += '<div class="effort-card saved">';
            html += '<div class="effort-header">Time Saved</div>';
            html += '<div class="effort-value">' + d.totalTimeSavedHours + '<span>hours</span></div>';
            html += '<div class="effort-label">Automated by OpenRewrite recipes</div>';
            html += '</div>';

            html += '</div>';

            html += '<div class="progress-bar-container">';
            html += '<div class="progress-labels">';
            html += '<span>Automated: ' + d.overallAutomationPercentage + '%</span>';
            html += '<span>Remaining Manual: ' + d.totalRemainingManualHours + 'h</span>';
            html += '</div>';
            html += '<div class="progress-bar">';
            html += '<div class="progress-fill" style="width:' + d.overallAutomationPercentage + '%"></div>';
            html += '</div>';
            html += '</div>';
            html += '</div>';

            if (d.repoSummaries && d.repoSummaries.length > 0) {
                html += '<div class="dashboard-section"><h3>Per-Repository Breakdown</h3>';
                html += '<table class="timing-table"><thead><tr>';
                html += '<th>Repository</th><th>Branch</th><th>Status</th><th>Tool Time</th>';
                html += '<th>Java Files</th><th>Issues</th><th>Files Changed</th>';
                html += '<th>Est. Manual</th><th>Remaining</th><th>Automated</th>';
                html += '</tr></thead><tbody>';
                for (var i = 0; i < d.repoSummaries.length; i++) {
                    var r = d.repoSummaries[i];
                    html += '<tr>';
                    html += '<td class="step-name-cell">' + escapeHtml(r.repoName) + '</td>';
                    html += '<td>' + escapeHtml(r.branch) + '</td>';
                    html += '<td><span class="status-badge ' + r.status + '">' + r.status + '</span></td>';
                    html += '<td class="duration-cell">' + escapeHtml(r.toolTime) + '</td>';
                    html += '<td>' + (r.javaFiles || 0) + '</td>';
                    html += '<td>' + (r.issues || 0) + '</td>';
                    html += '<td>' + (r.filesChanged || 0) + '</td>';
                    html += '<td class="duration-cell">' + (r.estimatedManualHours || '-') + 'h</td>';
                    html += '<td class="duration-cell">' + (r.remainingManualHours || '-') + 'h</td>';
                    html += '<td><span class="automation-badge">' + (r.automationPercentage || 0) + '%</span></td>';
                    html += '</tr>';
                }
                html += '</tbody></table></div>';
            }

            container.innerHTML = html;
        })
        .catch(function(err) {
            document.getElementById('dashboard-content').innerHTML =
                '<p class="empty-state">Failed to load dashboard: ' + escapeHtml(err.message) + '</p>';
        });
}

loadRecipes();
