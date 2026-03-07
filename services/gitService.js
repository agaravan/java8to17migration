const simpleGit = require('simple-git');
const path = require('path');
const fs = require('fs');

const WORKSPACE_DIR = path.join(__dirname, '..', 'workspace');

function ensureWorkspaceDir() {
  if (!fs.existsSync(WORKSPACE_DIR)) {
    fs.mkdirSync(WORKSPACE_DIR, { recursive: true });
  }
}

function buildAuthUrl(repoUrl, credentials) {
  if (!credentials || (!credentials.username && !credentials.token)) {
    return repoUrl;
  }

  try {
    const url = new URL(repoUrl);
    if (credentials.token) {
      url.username = 'x-token-auth';
      url.password = credentials.token;
    } else if (credentials.username && credentials.password) {
      url.username = credentials.username;
      url.password = credentials.password;
    }
    return url.toString();
  } catch {
    return repoUrl;
  }
}

async function cloneRepo(repoUrl, branch, migrationId, credentials) {
  ensureWorkspaceDir();

  const clonePath = path.join(WORKSPACE_DIR, migrationId);

  if (fs.existsSync(clonePath)) {
    fs.rmSync(clonePath, { recursive: true, force: true });
  }

  const authUrl = buildAuthUrl(repoUrl, credentials);
  const git = simpleGit();

  await git.clone(authUrl, clonePath, ['--branch', branch, '--single-branch', '--depth', '1']);

  return clonePath;
}

async function getBranches(repoUrl, credentials) {
  const authUrl = buildAuthUrl(repoUrl, credentials);
  const git = simpleGit();

  try {
    const result = await git.listRemote(['--heads', authUrl]);
    const branches = result
      .split('\n')
      .filter(line => line.trim())
      .map(line => {
        const parts = line.split('\t');
        return parts[1] ? parts[1].replace('refs/heads/', '') : null;
      })
      .filter(Boolean);
    return branches;
  } catch (err) {
    throw new Error(`Failed to list branches: ${err.message}`);
  }
}

module.exports = { cloneRepo, getBranches };
