'use strict';

const fs = require('node:fs');
const fsp = require('node:fs/promises');
const http = require('node:http');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');

const VERSION = 1;
const DEFAULT_LEASE_MS = 30 * 60 * 1000;
const MAX_LEASE_MS = 8 * 60 * 60 * 1000;
const MAX_LOG_BYTES = 64 * 1024;
const MAX_LOG_LINES = 200;
const MAX_BODY_BYTES = 1024;
const MAX_ACTIVE_SERVICES = 4;
const MAX_HTTP_CONNECTIONS = 32;
const MAX_ACTIVE_STREAMS = 8;
const MAX_RETAINED_LOGS = 64;
const LOG_RETENTION_MS = 24 * 60 * 60 * 1000;
const MAX_START_LOCK_MS = 20_000;
const MAX_HEALTH_TIMEOUT_MS = 2_000;
const MAX_HEALTH_BODY_BYTES = 16 * 1024;
const DEFAULT_PROJECTS_ROOT = '/root/projects';
const SERVICE_ID = /^[a-f0-9]{32}$/;
const MIME = new Map([
  ['.html', 'text/html; charset=utf-8'], ['.htm', 'text/html; charset=utf-8'],
  ['.css', 'text/css; charset=utf-8'], ['.js', 'text/javascript; charset=utf-8'],
  ['.mjs', 'text/javascript; charset=utf-8'], ['.json', 'application/json; charset=utf-8'],
  ['.svg', 'image/svg+xml'], ['.png', 'image/png'], ['.jpg', 'image/jpeg'],
  ['.jpeg', 'image/jpeg'], ['.gif', 'image/gif'], ['.webp', 'image/webp'],
  ['.ico', 'image/x-icon'], ['.txt', 'text/plain; charset=utf-8'], ['.xml', 'application/xml; charset=utf-8'],
  ['.wasm', 'application/wasm'], ['.woff', 'font/woff'], ['.woff2', 'font/woff2'],
  ['.ttf', 'font/ttf'], ['.map', 'application/json; charset=utf-8'],
]);
const DENIED_NAMES = new Set([
  '.dsh', '.git', '.ssh', '.config', '.android', '.gradle', '.npm', '.aws', '.kube', '.npmrc', '.pypirc', '.netrc',
  'user-data', 'node_modules', 'credentials', 'credential', 'secrets', 'secret', 'id_rsa', 'id_ed25519', 'id_ecdsa', 'authorized_keys',
]);
let logWriteChain = Promise.resolve();

function envelope(requestId, code, executed, state, data = {}) {
  return { version: VERSION, requestId, code, executed, state, data };
}

function isSafeName(name) {
  const lower = name.toLowerCase();
  if (DENIED_NAMES.has(lower) || /^\.env(?:\.|$)/i.test(lower) || /(?:^|[._-])(?:credentials?|secrets?|tokens?)(?:[._-]|$)/i.test(lower)) return false;
  if (/\.(?:pem|key|p12|pfx|jks|keystore|mobileprovision|asc)$/i.test(lower)) return false;
  if (/^(?:keystore|credentials|secrets)(?:\.|$)/i.test(lower)) return false;
  return true;
}

function safeRelative(value) {
  if (typeof value !== 'string' || value.length === 0 || value.length > 1024 || value.includes('\0') || value.includes('\\')) return null;
  let decoded;
  try { decoded = decodeURIComponent(value); } catch { return null; }
  if (decoded.startsWith('/') || /^[A-Za-z]:/.test(decoded)) return null;
  const segments = decoded.split('/');
  if (segments.some((segment) => segment === '..' || segment === '.' || !segment || !isSafeName(segment))) return null;
  return segments.join(path.sep);
}

function parsePort(value) {
  if (!/^(?:0|[1-9][0-9]{0,4})$/.test(String(value))) return null;
  const port = Number(value);
  return Number.isInteger(port) && port >= 0 && port <= 65535 ? port : null;
}

function parseServeArguments(argv) {
  const [action, ...rest] = argv;
  if (!['start', 'register', 'status', 'list', 'logs', 'stop', 'unregister'].includes(action)) throw new Error('expected serve start|register|status|list|logs|stop|unregister');
  const options = { action };
  const positional = [];
  for (let i = 0; i < rest.length; i += 1) {
    const key = rest[i];
    if (key === '--dir' || key === '--port' || key === '--spa-fallback' || key === '--lease-ms' || key === '--limit' || key === '--url' || key === '--health-path') {
      if (options[key.slice(2)] !== undefined || i + 1 >= rest.length || rest[i + 1].startsWith('--')) throw new Error(`${key} requires one value and may appear once`);
      options[key.slice(2)] = rest[++i];
    } else if (key.startsWith('--')) throw new Error(`unsupported serve option: ${key}`);
    else positional.push(key);
  }
  if (action === 'start') {
    if (positional.length || typeof options.dir !== 'string' || options.dir.trim().length === 0) throw new Error('serve start requires --dir and accepts no positional arguments');
    if (options.port !== undefined && parsePort(options.port) === null) throw new Error('port must be 0..65535');
    const lease = options['lease-ms'] === undefined ? DEFAULT_LEASE_MS : Number(options['lease-ms']);
    if (!Number.isSafeInteger(lease) || lease < 60_000 || lease > MAX_LEASE_MS) throw new Error('lease-ms must be 60000..28800000');
    options.port = options.port === undefined ? 0 : parsePort(options.port);
    options.leaseMs = lease;
    delete options['lease-ms'];
    if (options['spa-fallback'] !== undefined) {
      options.spaFallback = safeRelative(options['spa-fallback']);
      if (!options.spaFallback) throw new Error('spa-fallback must be a safe relative file path');
      delete options['spa-fallback'];
    }
  } else if (action === 'register') {
    if (positional.length || typeof options.dir !== 'string' || !options.dir.trim() || typeof options.url !== 'string') {
      throw new Error('serve register requires --dir and --url');
    }
    if (Object.keys(options).some((key) => !['action', 'dir', 'url', 'health-path', 'lease-ms'].includes(key))) {
      throw new Error('serve register accepts only --dir, --url, --health-path, and --lease-ms');
    }
    const lease = options['lease-ms'] === undefined ? DEFAULT_LEASE_MS : Number(options['lease-ms']);
    if (!Number.isSafeInteger(lease) || lease < 60_000 || lease > MAX_LEASE_MS) throw new Error('lease-ms must be 60000..28800000');
    options.leaseMs = lease;
    delete options['lease-ms'];
    const target = parseLoopbackUrl(options.url);
    options.healthPath = options['health-path'] === undefined ? safeHealthPath(target.pathname) : safeHealthPath(options['health-path']);
    if (!options.healthPath) throw new Error('health-path must be a safe local path');
    delete options['health-path'];
    options.url = target.toString();
  } else if (action === 'list') {
    if (positional.length || Object.keys(options).length !== 1) throw new Error('serve list takes no arguments');
  } else if (action === 'status' || action === 'stop' || action === 'unregister') {
    if (positional.length > 1 || (positional[0] && !SERVICE_ID.test(positional[0])) || Object.keys(options).length !== 1) throw new Error(`serve ${action} accepts at most one serviceId`);
    options.serviceId = positional[0] || null;
  } else if (action === 'logs') {
    if (positional.length !== 1 || !SERVICE_ID.test(positional[0]) || Object.keys(options).some((key) => !['action', 'limit'].includes(key))) throw new Error('serve logs requires serviceId and optional --limit');
    options.serviceId = positional[0];
    options.limit = options.limit === undefined ? 50 : Number(options.limit);
    if (!Number.isInteger(options.limit) || options.limit < 1 || options.limit > 100) throw new Error('logs limit must be 1..100');
  }
  return options;
}

function defaultRunDir() { return path.join(path.dirname('/opt/browser/run/channel.json'), 'serve'); }

function safeHealthPath(value) {
  if (typeof value !== 'string' || !value.startsWith('/') || value.includes('\\') || value.includes('\0')) return null;
  let parsed;
  try { parsed = new URL(value, 'http://127.0.0.1'); } catch { return null; }
  if (parsed.origin !== 'http://127.0.0.1' || parsed.search || parsed.hash || parsed.username || parsed.password) return null;
  if (parsed.pathname === '/') return '/';
  const hasTrailingSlash = parsed.pathname.endsWith('/');
  const relative = safeRelative(parsed.pathname.slice(1).replace(/\/+$/, ''));
  return relative ? `/${relative.split(path.sep).join('/')}${hasTrailingSlash ? '/' : ''}` : null;
}

function parseLoopbackUrl(value) {
  let parsed;
  try { parsed = new URL(value); } catch { throw new Error('url must be an HTTP loopback URL'); }
  if (parsed.protocol !== 'http:' || parsed.hostname !== '127.0.0.1' || parsed.username || parsed.password ||
      parsed.search || parsed.hash || !parsed.port || parsed.port === '0' ||
      !safeHealthPath(parsed.pathname)) throw new Error('url must be an HTTP URL on 127.0.0.1 with a safe path');
  return parsed;
}

function isPathWithin(root, candidate) {
  const relative = path.relative(root, candidate);
  return relative !== '' && relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative);
}

async function resolveProjectRoot(value, projectsRoot = DEFAULT_PROJECTS_ROOT) {
  let allowedRoot;
  let root;
  try {
    allowedRoot = await fsp.realpath(path.resolve(projectsRoot));
    root = await fsp.realpath(path.resolve(value));
  } catch { throw Object.assign(new Error('project directory is unavailable'), { code: 'PROJECT_UNAVAILABLE' }); }
  if (!isPathWithin(allowedRoot, root)) throw Object.assign(new Error('project root is outside the user projects directory'), { code: 'PROJECT_ROOT_DENIED' });
  if (root.split(path.sep).some((segment) => !isSafeName(segment))) throw Object.assign(new Error('project root contains a sensitive path'), { code: 'PROJECT_ROOT_DENIED' });
  const stat = await fsp.stat(root);
  if (!stat.isDirectory()) throw Object.assign(new Error('project path is not a directory'), { code: 'PROJECT_INVALID' });
  return { root, projectId: crypto.createHash('sha256').update(root).digest('hex').slice(0, 24) };
}

async function readBootClock() {
  if (process.platform !== 'linux') return null;
  try {
    const bootId = (await fsp.readFile('/proc/sys/kernel/random/boot_id', 'utf8')).trim();
    const uptime = await fsp.readFile('/proc/uptime', 'utf8');
    const uptimeMillis = Math.floor(Number(uptime.trim().split(/\s+/)[0]) * 1000);
    if (!/^[a-f0-9-]{16,64}$/i.test(bootId) || !Number.isSafeInteger(uptimeMillis) || uptimeMillis < 0) return null;
    return { bootId, uptimeMillis };
  } catch { return null; }
}

async function ensureRunDir(runDir) {
  const absolute = path.resolve(runDir);
  await fsp.mkdir(absolute, { recursive: true, mode: 0o700 });
  const finalStat = await fsp.lstat(absolute);
  if (!finalStat.isDirectory() || finalStat.isSymbolicLink()) throw new Error('service state directory must be a real directory');
  const real = await fsp.realpath(absolute);
  const stat = await fsp.stat(real);
  if (!stat.isDirectory()) throw new Error('service state directory is not a directory');
  return real;
}

async function readRegularFile(file, maximum) {
  const before = await fsp.lstat(file);
  if (!before.isFile() || before.isSymbolicLink() || before.size > maximum) throw new Error('file is not regular or is oversized');
  const handle = await fsp.open(file, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0));
  try {
    const after = await handle.stat();
    if (!after.isFile() || after.dev !== before.dev || after.ino !== before.ino || after.size > maximum) throw new Error('file changed while opening');
    const bytes = await handle.readFile();
    if (bytes.length > maximum) throw new Error('file is oversized');
    return { bytes, stat: after };
  } finally { await handle.close(); }
}

async function writeJsonAtomic(file, value) {
  const temporary = `${file}.${crypto.randomBytes(8).toString('hex')}.tmp`;
  await fsp.writeFile(temporary, JSON.stringify(value), { mode: 0o600, flag: 'wx' });
  try { await fsp.rename(temporary, file); } finally { await fsp.rm(temporary, { force: true }).catch(() => {}); }
}

function requestControl(port, serviceId, token, op, timeoutMs = 1500) {
  return new Promise((resolve) => {
    const body = Buffer.from(JSON.stringify({ version: VERSION, serviceId, token, op }));
    const request = http.request({
      host: '127.0.0.1', port, path: '/__browser_serve_control', method: 'POST', timeout: timeoutMs,
      headers: { 'Content-Type': 'application/json', 'Content-Length': String(body.length), Connection: 'close' },
    }, (response) => {
      const chunks = [];
      let size = 0;
      response.on('data', (chunk) => { size += chunk.length; if (size <= MAX_BODY_BYTES) chunks.push(chunk); else request.destroy(); });
      response.on('end', () => {
        try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); } catch { resolve(null); }
      });
    });
    request.on('timeout', () => request.destroy());
    request.on('error', () => resolve(null));
    request.end(body);
  });
}

function requestExternalHealth(record, timeoutMs = MAX_HEALTH_TIMEOUT_MS) {
  return new Promise((resolve) => {
    let settled = false;
    const finish = (value) => {
      if (settled) return;
      settled = true;
      resolve(value);
    };
    let target;
    try { target = new URL(record.url); } catch { return finish(null); }
    const request = http.request({
      host: '127.0.0.1', port: Number(target.port), path: record.healthPath,
      method: 'GET', timeout: timeoutMs,
      headers: { Host: `127.0.0.1:${target.port}`, Connection: 'close', Accept: 'text/html,application/json,*/*' },
    }, (response) => {
      let size = 0;
      response.on('data', (chunk) => {
        size += chunk.length;
        if (size > MAX_HEALTH_BODY_BYTES) { response.destroy(); finish(false); }
      });
      response.once('end', () => finish(response.statusCode >= 200 && response.statusCode < 300));
      response.once('error', () => finish(null));
    });
    request.once('timeout', () => { request.destroy(); finish(null); });
    request.once('error', () => finish(null));
    request.end();
  });
}

async function readRegistry(runDir, serviceId) {
  const file = path.join(runDir, `${serviceId}.json`);
  try {
    const { bytes } = await readRegularFile(file, 16 * 1024);
    const parsed = JSON.parse(bytes.toString('utf8'));
    const kind = parsed.kind || 'owned-static';
    if (parsed.version !== VERSION || parsed.serviceId !== serviceId || !SERVICE_ID.test(serviceId) ||
        !Number.isInteger(parsed.port) || parsed.port < 1 || parsed.port > 65535 ||
        !Number.isSafeInteger(parsed.leaseExpiresAtMillis) ||
        (parsed.projectId !== undefined && (typeof parsed.projectId !== 'string' || !/^[a-f0-9]{24}$/.test(parsed.projectId))) ||
        typeof parsed.root !== 'string' || !path.isAbsolute(parsed.root) ||
        !['owned-static', 'external'].includes(kind)) return null;
    if (kind === 'external') {
      const target = parseLoopbackUrl(parsed.url);
      if (Number(target.port) !== parsed.port || !safeHealthPath(parsed.healthPath)) return null;
      return { ...parsed, kind };
    }
    if (typeof parsed.token !== 'string' || !/^[a-f0-9]{64}$/.test(parsed.token)) return null;
    if (parsed.pid !== null && parsed.pid !== undefined && (!Number.isSafeInteger(parsed.pid) || parsed.pid < 1)) return null;
    if (parsed.processStartTime !== null && parsed.processStartTime !== undefined && !/^\d+$/.test(parsed.processStartTime)) return null;
    if (parsed.bootId !== null && parsed.bootId !== undefined && !/^[a-f0-9-]{16,64}$/i.test(parsed.bootId)) return null;
    return { ...parsed, kind };
  } catch { return null; }
}

async function verifyRecord(record, op = 'status', sendControl = requestControl) {
  if (!record || record.kind === 'external') return null;
  const result = await sendControl(record.port, record.serviceId, record.token, op);
  if (!result || result.version !== VERSION || result.serviceId !== record.serviceId || result.authenticated !== true) return null;
  return result;
}

async function removeRecord(runDir, record) {
  const file = path.join(runDir, `${record.serviceId}.json`);
  const current = await readRegistry(runDir, record.serviceId);
  if (current && current.kind === record.kind && current.port === record.port && current.projectId === record.projectId &&
      (record.kind === 'external' || current.token === record.token)) await fsp.rm(file, { force: true });
}

async function appendLog(config, event) {
  const write = async () => {
    const line = `${new Date().toISOString()} ${event}\n`;
    const file = config.logFile;
    let handle;
    try {
      handle = await fsp.open(file, fs.constants.O_RDWR | fs.constants.O_APPEND | fs.constants.O_CREAT | (fs.constants.O_NOFOLLOW || 0), 0o600);
      const stat = await handle.stat();
      if (!stat.isFile()) { await handle.close(); handle = null; return; }
      if (stat.size + Buffer.byteLength(line) > MAX_LOG_BYTES) {
        const old = (await handle.readFile('utf8')).split('\n').filter(Boolean).slice(-Math.floor(MAX_LOG_LINES / 2));
        await handle.truncate(0);
        await handle.writeFile(`${old.join('\n')}\n`);
      }
    } catch {}
    try { if (handle) await handle.writeFile(line); } catch {}
    if (handle) await handle.close().catch(() => {});
  };
  logWriteChain = logWriteChain.then(write, write);
  return logWriteChain;
}

async function openVerifiedFile(root, found) {
  const current = await fsp.realpath(found.path).catch(() => '');
  if (!current || (current !== root && !current.startsWith(`${root}${path.sep}`))) throw Object.assign(new Error('path changed'), { code: 'EACCES' });
  const relative = path.relative(root, current).split(path.sep);
  if (relative.some((segment) => !isSafeName(segment))) throw Object.assign(new Error('sensitive path'), { code: 'EACCES' });
  const flags = fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0);
  const handle = await fsp.open(current, flags);
  const opened = await handle.stat();
  if (!opened.isFile() || opened.size !== found.size || opened.dev !== found.dev || opened.ino !== found.ino) {
    await handle.close();
    throw Object.assign(new Error('file changed'), { code: 'EACCES' });
  }
  return handle;
}

async function acquireStartLock(runDir, options = {}) {
  const lockPath = path.join(runDir, 'start.lock');
  const nonce = crypto.randomBytes(24).toString('hex');
  const acquiredAtMillis = Date.now();
  const ownerProbe = options.ownerProbe || readLinuxProcessIdentity;
  const capturedOwner = options.ownerIdentity || await readLinuxProcessIdentity(process.pid);
  const owner = {
    pid: capturedOwner.pid || process.pid,
    processStartTime: capturedOwner.state === 'live' ? capturedOwner.processStartTime : null,
    bootId: capturedOwner.state === 'live' ? capturedOwner.bootId : null,
  };
  const lockValue = { version: 1, nonce, acquiredAtMillis, owner };
  let handle;
  let createdIdentity;
  try {
    handle = await fsp.open(lockPath, 'wx', 0o600);
    createdIdentity = await handle.stat();
    await handle.writeFile(JSON.stringify(lockValue));
    await handle.sync();
    await handle.close();
    handle = null;
  } catch (error) {
    if (handle) await handle.close().catch(() => {});
    if (createdIdentity) await removeOwnedLock(lockPath, createdIdentity, nonce);
    if (error.code !== 'EEXIST') throw error;
    const stale = await takeOverConfirmedDeadLock(lockPath, runDir, ownerProbe);
    if (stale) return acquireStartLock(runDir, options);
    throw Object.assign(new Error('another service start is in progress or its owner cannot be verified'), { code: 'START_BUSY' });
  }

  let released = false;
  const matches = async () => {
    const current = await fsp.lstat(lockPath).catch(() => null);
    if (!current || !current.isFile() || current.isSymbolicLink() || current.dev !== createdIdentity.dev || current.ino !== createdIdentity.ino) return false;
    try {
      const { bytes, stat } = await readRegularFile(lockPath, 2048);
      const value = JSON.parse(bytes.toString('utf8'));
      return stat.dev === createdIdentity.dev && stat.ino === createdIdentity.ino && value.nonce === nonce;
    } catch { return false; }
  };
  const assertOwned = async () => {
    if (released || Date.now() - acquiredAtMillis > MAX_START_LOCK_MS || !(await matches())) {
      throw Object.assign(new Error('service start lock ownership was lost or expired'), { code: 'START_LOCK_LOST' });
    }
  };
  const release = async () => {
    if (released) return;
    released = true;
    await removeOwnedLock(lockPath, createdIdentity, nonce);
  };
  return { assertOwned, release, nonce };
}

async function readLinuxProcessIdentity(pid) {
  if (process.platform !== 'linux' || !Number.isSafeInteger(pid) || pid < 1) return { state: 'unknown' };
  let bootId;
  try { bootId = (await fsp.readFile('/proc/sys/kernel/random/boot_id', 'utf8')).trim(); } catch { return { state: 'unknown' }; }
  if (!bootId) return { state: 'unknown' };
  let statText;
  try { statText = await fsp.readFile(`/proc/${pid}/stat`, 'utf8'); } catch (error) {
    return error.code === 'ENOENT' ? { state: 'dead', bootId } : { state: 'unknown' };
  }
  const closeParen = statText.lastIndexOf(')');
  if (closeParen < 0) return { state: 'unknown' };
  const fields = statText.slice(closeParen + 1).trim().split(/\s+/);
  const processStartTime = fields[19];
  if (!/^\d+$/.test(processStartTime || '')) return { state: 'unknown' };
  return { state: 'live', pid, processStartTime, bootId };
}

async function removeOwnedLock(lockPath, identity, nonce) {
  if (!identity) return false;
  const current = await fsp.lstat(lockPath).catch(() => null);
  if (!current || !current.isFile() || current.isSymbolicLink() || current.dev !== identity.dev || current.ino !== identity.ino) return false;
  try {
    const { bytes, stat } = await readRegularFile(lockPath, 2048);
    const value = JSON.parse(bytes.toString('utf8'));
    if (stat.dev !== identity.dev || stat.ino !== identity.ino || value.nonce !== nonce) return false;
    await fsp.rm(lockPath);
    return true;
  } catch { return false; }
}

async function takeOverConfirmedDeadLock(lockPath, runDir, ownerProbe) {
  let before;
  let bytes;
  try {
    const read = await readRegularFile(lockPath, 2048);
    before = read.stat;
    bytes = read.bytes;
  } catch { return false; }
  let prior;
  try { prior = JSON.parse(bytes.toString('utf8')); } catch { return false; }
  const priorOwner = prior && prior.owner;
  if (prior.version !== 1 || !/^[a-f0-9]{48}$/.test(prior.nonce || '') ||
      !priorOwner || !Number.isSafeInteger(priorOwner.pid) || priorOwner.pid < 1 ||
      typeof priorOwner.processStartTime !== 'string' || !/^\d+$/.test(priorOwner.processStartTime) ||
      typeof priorOwner.bootId !== 'string' || priorOwner.bootId.length < 8) return false;
  let observed;
  try { observed = await ownerProbe(priorOwner.pid); } catch { return false; }
  const definitelyStale = observed?.state === 'dead' ||
    (observed?.state === 'live' &&
      (observed.bootId !== priorOwner.bootId || observed.processStartTime !== priorOwner.processStartTime));
  if (!definitelyStale) return false;

  const current = await fsp.lstat(lockPath).catch(() => null);
  if (!current || !current.isFile() || current.isSymbolicLink() || current.dev !== before.dev || current.ino !== before.ino) return false;
  const quarantine = path.join(runDir, `.start-lock-reap-${crypto.randomBytes(12).toString('hex')}`);
  try { await fsp.rename(lockPath, quarantine); } catch { return false; }
  const moved = await fsp.lstat(quarantine).catch(() => null);
  let confirmedMove = false;
  if (moved && moved.isFile() && !moved.isSymbolicLink() && moved.dev === before.dev && moved.ino === before.ino) {
    try {
      const { bytes: movedBytes, stat } = await readRegularFile(quarantine, 2048);
      const movedValue = JSON.parse(movedBytes.toString('utf8'));
      confirmedMove = stat.dev === before.dev && stat.ino === before.ino && movedValue.nonce === prior.nonce;
    } catch { /* Preserve an uncertain moved lock below. */ }
  }
  if (confirmedMove) {
    await fsp.rm(quarantine, { force: true });
    return true;
  }
  try { await fsp.link(quarantine, lockPath); } catch { /* Another owner may already have acquired the canonical path. */ }
  await fsp.rm(quarantine, { force: true });
  return false;
}

async function pruneRunDir(runDir) {
  const now = Date.now();
  const names = await fsp.readdir(runDir).catch(() => []);
  const active = new Set(names.filter((name) => /^[a-f0-9]{32}\.json$/.test(name)).map((name) => name.slice(0, 32)));
  const logs = [];
  for (const name of names) {
    const full = path.join(runDir, name);
    const stat = await fsp.lstat(full).catch(() => null);
    if (!stat || !stat.isFile() || stat.isSymbolicLink()) continue;
    if (/^[a-f0-9]{32}\.config\.json$/.test(name) && now - stat.mtimeMs > 5 * 60 * 1000) {
      await fsp.rm(full, { force: true });
    } else if (/^[a-f0-9]{32}\.log$/.test(name)) {
      logs.push({ name, full, age: now - stat.mtimeMs, active: active.has(name.slice(0, 32)) });
    }
  }
  for (const log of logs.filter((item) => !item.active && item.age > LOG_RETENTION_MS)) await fsp.rm(log.full, { force: true });
  const retained = logs.filter((item) => item.active || item.age <= LOG_RETENTION_MS).sort((a, b) => a.age - b.age);
  for (const log of retained.slice(MAX_RETAINED_LOGS)) {
    if (!log.active) await fsp.rm(log.full, { force: true });
  }
}

async function resolveServedFile(root, requestPath, spaFallback, accept) {
  const relative = safeRelative(requestPath.replace(/^\/+/, ''));
  if (requestPath === '/' || requestPath === '') {
    const index = await resolveServedFile(root, 'index.html', null, accept);
    return index;
  }
  if (!relative) return { error: 403 };
  const candidate = path.resolve(root, relative);
  const rootPrefix = `${root}${path.sep}`;
  if (candidate !== root && !candidate.startsWith(rootPrefix)) return { error: 403 };
  let real;
  try { real = await fsp.realpath(candidate); } catch (error) {
    if (error.code !== 'ENOENT') return { error: 404 };
    if (spaFallback && accept.includes('text/html') && !path.posix.basename(requestPath).includes('.')) {
      return resolveServedFile(root, spaFallback.split(path.sep).join('/'), null, '');
    }
    return { error: 404 };
  }
  if (real !== root && !real.startsWith(rootPrefix)) return { error: 403 };
  const realRelative = path.relative(root, real).split(path.sep);
  if (realRelative.some((segment) => !isSafeName(segment))) return { error: 403 };
  let stat;
  try { stat = await fsp.stat(real); } catch { return { error: 404 }; }
  if (!stat.isFile() || stat.size > 64 * 1024 * 1024) return { error: 404 };
  return { path: real, size: stat.size, dev: stat.dev, ino: stat.ino, mime: MIME.get(path.extname(real).toLowerCase()) || 'application/octet-stream' };
}

async function makeServer(config, dependencies = {}) {
  const root = await fsp.realpath(config.root);
  const openFile = dependencies.openFile || openVerifiedFile;
  const exitProcess = dependencies.exitProcess || process.exit;
  let activeStreams = 0;
  const handler = async (request, response) => {
    let stream = null;
    let openHandle = null;
    let reservedStream = false;
    let cleaned = false;
    const cleanup = () => {
      if (cleaned) return;
      cleaned = true;
      if (stream && !stream.destroyed) stream.destroy();
      else if (openHandle) openHandle.close().catch(() => {});
      if (reservedStream) {
        reservedStream = false;
        activeStreams = Math.max(0, activeStreams - 1);
      }
    };
    request.once('aborted', cleanup);
    response.once('close', cleanup);
    response.once('finish', cleanup);
    const pathname = (() => { try { return new URL(request.url, 'http://127.0.0.1').pathname; } catch { return ''; } })();
    const listenPort = server.address() && server.address().port;
    const host = request.headers.host;
    if (host !== `127.0.0.1:${listenPort}` && host !== `localhost:${listenPort}`) { response.writeHead(421, { Connection: 'close' }).end(); return; }
    if (pathname === '/__browser_serve_control') {
      if (request.method !== 'POST') { response.writeHead(405, { Allow: 'POST' }).end(); return; }
      const origin = request.headers.origin;
      if ((origin && origin !== `http://127.0.0.1:${listenPort}` && origin !== `http://localhost:${listenPort}`) ||
          request.headers['content-type'] !== 'application/json') { response.writeHead(404, { Connection: 'close' }).end(); return; }
      const chunks = [];
      let size = 0;
      request.on('data', (chunk) => { size += chunk.length; if (size <= MAX_BODY_BYTES) chunks.push(chunk); else request.destroy(); });
      request.on('end', () => {
        if (size > MAX_BODY_BYTES) { response.writeHead(413).end(); return; }
        let body;
        try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { response.writeHead(400).end(); return; }
        if (!body || typeof body !== 'object' || Array.isArray(body)) { response.writeHead(400).end(); return; }
        const supplied = typeof body.token === 'string' ? Buffer.from(body.token) : Buffer.alloc(0);
        const expected = Buffer.from(config.token);
        const authenticated = body.version === VERSION && body.serviceId === config.serviceId && supplied.length === expected.length && crypto.timingSafeEqual(supplied, expected);
        if (!authenticated || !['status', 'stop'].includes(body.op)) { response.writeHead(404).end(); return; }
        const address = server.address();
        const answer = { version: VERSION, serviceId: config.serviceId, authenticated: true, state: (stopping || body.op === 'stop') ? 'STOPPING' : 'RUNNING', leaseExpiresAtMillis: config.leaseExpiresAtMillis, port: address && address.port };
        response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', Connection: 'close' });
        response.end(JSON.stringify(answer));
        if (body.op === 'stop' && !stopping) setTimeout(() => shutdown('stopped'), 20).unref();
      });
      return;
    }
    if (request.method !== 'GET' && request.method !== 'HEAD') { response.writeHead(405, { Allow: 'GET, HEAD' }).end(); return; }
    if ((Number(request.headers['content-length'] || 0) > 0) || request.headers['transfer-encoding']) { response.writeHead(400).end(); return; }
    let url;
    try { url = new URL(request.url, 'http://127.0.0.1'); } catch { response.writeHead(400).end(); return; }
    const found = await resolveServedFile(root, url.pathname, config.spaFallback, request.headers.accept || '');
    if (found.error) {
      await appendLog(config, `http ${found.error}`);
      response.writeHead(found.error, { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' }).end();
      return;
    }
    if (request.method === 'GET' && activeStreams >= MAX_ACTIVE_STREAMS) {
      response.writeHead(503, { 'Cache-Control': 'no-store', Connection: 'close', 'Retry-After': '1' }).end();
      return;
    }
    if (request.method === 'GET') {
      activeStreams += 1;
      reservedStream = true;
    }
    try { openHandle = await openFile(root, found); } catch {
      response.writeHead(403, { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' }).end();
      return;
    }
    if (request.aborted || response.destroyed) {
      const abandoned = openHandle;
      openHandle = null;
      if (abandoned) await abandoned.close().catch(() => {});
      cleanup();
      return;
    }
    let prefix = null;
    let consumedPrefix = 0;
    if (found.mime.startsWith('text/html')) {
      const first = Buffer.alloc(Math.min(found.size, 4096));
      const read = await openHandle.read(first, 0, first.length, 0);
      consumedPrefix = read.bytesRead;
      prefix = addProjectDocumentProof(first.subarray(0, consumedPrefix), config, pathname);
    }
    const headers = {
      'Content-Type': found.mime, 'Content-Length': String(found.size + (prefix ? prefix.length - consumedPrefix : 0)), 'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer', Connection: 'close',
    };
    if (request.method === 'HEAD') {
      response.writeHead(200, headers).end();
      await openHandle.close();
      openHandle = null;
      return;
    }
    response.writeHead(200, headers);
    if (prefix) response.write(prefix);
    stream = openHandle.createReadStream({ autoClose: true, start: consumedPrefix });
    openHandle = null;
    stream.on('error', () => response.destroy());
    stream.pipe(response);
    await appendLog(config, `http 200 ${path.extname(found.path).toLowerCase() || 'file'}`);
  };
  let stopping = false;
  let closed = false;
  let server;
  let leaseTimer;
  const shutdown = async (reason) => {
    if (stopping || closed) return;
    stopping = true;
    clearTimeout(leaseTimer);
    await appendLog(config, `service ${reason}`);
    if (server) server.close(() => { closed = true; exitProcess(0); });
    setTimeout(() => exitProcess(0), 1500).unref();
  };
  server = http.createServer((request, response) => {
    Promise.resolve(handler(request, response)).catch(async () => {
      await appendLog(config, 'http handler error');
      if (response.destroyed) return;
      if (response.headersSent) response.destroy();
      else response.writeHead(500, { 'Cache-Control': 'no-store', Connection: 'close' }).end();
    });
  });
  server.maxConnections = MAX_HTTP_CONNECTIONS;
  server.headersTimeout = 5000;
  server.requestTimeout = 10_000;
  server.maxHeadersCount = 64;
  server.keepAliveTimeout = 1000;
  server.on('clientError', (_error, socket) => socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n'));
  return { server, shutdown, setTimer: () => {
    leaseTimer = setTimeout(() => { shutdown('lease-expired'); }, Math.min(MAX_LEASE_MS, Math.max(1, config.leaseExpiresAtMillis - Date.now())));
    leaseTimer.unref();
  } };
}

async function runServeChild(configFile, allowedRunDir = defaultRunDir()) {
  const permittedRoot = await fsp.realpath(path.resolve(allowedRunDir));
  const resolvedConfig = path.resolve(configFile || '');
  const configParent = await fsp.realpath(path.dirname(resolvedConfig)).catch(() => '');
  if (typeof configFile !== 'string' || configParent !== permittedRoot || !/^[a-f0-9]{32}\.config\.json$/.test(path.basename(resolvedConfig))) throw new Error('invalid service config path');
  configFile = resolvedConfig;
  const { bytes } = await readRegularFile(configFile, 16 * 1024);
  const config = JSON.parse(bytes.toString('utf8'));
  if (config.version !== VERSION || !SERVICE_ID.test(config.serviceId) || !/^[a-f0-9]{64}$/.test(config.token) ||
      !Number.isSafeInteger(config.leaseExpiresAtMillis) || !Number.isInteger(config.port) ||
      typeof config.root !== 'string' || typeof config.logFile !== 'string') throw new Error('invalid service configuration');
  const root = await fsp.realpath(config.root);
  if (!path.isAbsolute(root)) throw new Error('invalid service root');
  const { server, shutdown, setTimer } = await makeServer({ ...config, root });
  server.on('error', async (error) => {
    await appendLog(config, error.code === 'EADDRINUSE' ? 'port occupied' : 'service error');
    process.stdout.write(`${JSON.stringify({ ready: false, code: error.code === 'EADDRINUSE' ? 'PORT_IN_USE' : 'SERVICE_ERROR' })}\n`);
    process.exit(1);
  });
  server.listen({ host: '127.0.0.1', port: config.port, exclusive: true });
  await new Promise((resolve) => server.once('listening', resolve));
  setTimer();
  process.on('SIGTERM', () => shutdown('stopped'));
  process.on('SIGINT', () => shutdown('stopped'));
  await appendLog(config, 'service ready');
  process.stdout.write(`${JSON.stringify({ ready: true, port: server.address().port })}\n`);
  await new Promise(() => {});
}

function spawnAndWait(configFile, cliScript) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [cliScript, '--serve-child', configFile], { detached: true, windowsHide: true, stdio: ['ignore', 'pipe', 'ignore'] });
    child.unref();
    if (child.stdout && typeof child.stdout.unref === 'function') child.stdout.unref();
    let buffered = '';
    let settled = false;
    const timer = setTimeout(() => finish(new Error('service readiness timed out')), 8_000);
    const finish = (error, result) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (child.stdout) child.stdout.removeAllListeners('data');
      if (child.stdout && !error) child.stdout.destroy();
      if (error) {
        try { child.kill('SIGTERM'); } catch {}
        reject(error);
      } else resolve({ ...result, child });
    };
    child.stdout.on('data', (chunk) => {
      buffered += chunk.toString('utf8');
      if (Buffer.byteLength(buffered, 'utf8') > 2048) return finish(new Error('service readiness response exceeded limit'));
      const newline = buffered.indexOf('\n');
      if (newline < 0) return;
      let message;
      try { message = JSON.parse(buffered.slice(0, newline)); } catch { return finish(new Error('service readiness response was invalid')); }
      if (!message.ready) return finish(Object.assign(new Error('service could not start'), { code: message.code }));
      if (!Number.isInteger(message.port) || message.port < 1 || message.port > 65535) return finish(new Error('service readiness port was invalid'));
      finish(null, message);
    });
    child.once('error', (error) => finish(error));
    child.once('exit', (code) => { if (!settled) finish(Object.assign(new Error('service exited before becoming ready'), { exitCode: code })); });
  });
}

async function ownedRecordForChild({ serviceId, token, projectId, port, root, leaseExpiresAtMillis, leaseMs, logFile, child }) {
  const identity = child && Number.isSafeInteger(child.pid) ? await readLinuxProcessIdentity(child.pid) : { state: 'unknown' };
  const clock = await readBootClock();
  return {
    version: VERSION, kind: 'owned-static', serviceId, token, projectId, port, root, leaseExpiresAtMillis, logFile,
    pid: identity.state === 'live' ? identity.pid : null,
    processStartTime: identity.state === 'live' ? identity.processStartTime : null,
    bootId: identity.state === 'live' ? identity.bootId : clock?.bootId || null,
    leaseExpiresAtUptimeMillis: clock ? clock.uptimeMillis + leaseMs : null,
  };
}

async function persistUnknownOwned(runDir, record) {
  try { await writeJsonAtomic(path.join(runDir, `${record.serviceId}.json`), record); return true; }
  catch { return false; }
}

async function confirmOwnedProcessGone(record) {
  if (!record.pid || !record.processStartTime || !record.bootId) return false;
  const identity = await readLinuxProcessIdentity(record.pid);
  return identity.state === 'dead' && identity.bootId === record.bootId ||
    identity.state === 'live' && (identity.bootId !== record.bootId || identity.processStartTime !== record.processStartTime);
}

async function runServeCommand(parsed, injected = {}) {
  const runDir = await ensureRunDir(injected.runDir || defaultRunDir());
  const requestId = crypto.randomBytes(12).toString('hex');
  try {
    switch (parsed.action) {
      case 'start': return await startService(parsed, runDir, requestId, injected);
      case 'register': return await registerExternal(parsed, runDir, requestId, injected);
      case 'unregister': return await unregisterExternal(runDir, parsed.serviceId, requestId);
      case 'list': return await listServices(runDir, requestId, injected);
      case 'status': return await statusService(runDir, parsed.serviceId, requestId, injected);
      case 'logs': return await readLogs(runDir, parsed.serviceId, parsed.limit, requestId, injected);
      case 'stop': return await stopService(runDir, parsed.serviceId, requestId, injected);
      default: return envelope(requestId, 'INVALID_ARGUMENT', false, 'CLOSED');
    }
  } catch (error) {
    const code = error && error.code === 'EADDRINUSE' ? 'PORT_IN_USE'
      : error && error.code === 'START_BUSY' ? 'START_BUSY'
        : error && error.code === 'START_LOCK_LOST' ? 'START_LOCK_LOST' : 'SERVICE_ERROR';
    const messages = {
      PORT_IN_USE: 'The requested loopback port is occupied.',
      START_BUSY: 'Another service start is in progress.',
      START_LOCK_LOST: 'The service start lock expired or changed.',
      PROJECT_ROOT_DENIED: 'The project directory must be a canonical child of the user projects directory.',
      PROJECT_UNAVAILABLE: 'The project directory is unavailable.',
      SERVICE_ERROR: 'Service operation failed.',
    };
    const reported = ['PROJECT_ROOT_DENIED', 'PROJECT_UNAVAILABLE'].includes(error?.code) ? error.code : code;
    return envelope(requestId, reported, false, reported === 'START_LOCK_LOST' ? 'UNKNOWN' : 'CLOSED', { message: messages[reported] });
  }
}

async function startService(parsed, runDir, requestId, injected) {
  const lock = await acquireStartLock(runDir);
  try {
    await lock.assertOwned();
    await pruneRunDir(runDir);
    await lock.assertOwned();
    const existing = await records(runDir);
    await lock.assertOwned();
    if (existing.length >= MAX_ACTIVE_SERVICES) return envelope(requestId, 'SERVICE_LIMIT', false, 'RUNNING', { maximum: MAX_ACTIVE_SERVICES });
    return await startServiceLocked(parsed, runDir, requestId, injected, lock);
  } finally { await lock.release(); }
}

async function registerExternal(parsed, runDir, requestId, injected) {
  const lock = await acquireStartLock(runDir);
  try {
    await lock.assertOwned();
    await pruneRunDir(runDir);
    const project = await resolveProjectRoot(parsed.dir, injected.projectsRoot || DEFAULT_PROJECTS_ROOT);
    const url = parseLoopbackUrl(parsed.url);
    const clock = await readBootClock();
    const serviceId = crypto.randomBytes(16).toString('hex');
    const leaseExpiresAtMillis = Date.now() + parsed.leaseMs;
    const record = {
      version: VERSION, kind: 'external', serviceId, projectId: project.projectId, root: project.root,
      url: url.toString(), port: Number(url.port), healthPath: parsed.healthPath,
      leaseExpiresAtMillis, bootId: clock?.bootId || null,
      leaseExpiresAtUptimeMillis: clock ? clock.uptimeMillis + parsed.leaseMs : null,
    };
    if (await requestExternalHealth(record) !== true) return envelope(requestId, 'HEALTHCHECK_FAILED', false, 'UNKNOWN', { message: 'The loopback service did not pass its health check; nothing was registered.' });
    if ((await records(runDir)).length >= MAX_ACTIVE_SERVICES) return envelope(requestId, 'SERVICE_LIMIT', false, 'RUNNING', { maximum: MAX_ACTIVE_SERVICES });
    await lock.assertOwned();
    await writeJsonAtomic(path.join(runDir, `${serviceId}.json`), record);
    return envelope(requestId, 'OK', false, 'RUNNING', {
      serviceId, kind: 'external', projectId: project.projectId, url: record.url, host: '127.0.0.1', port: record.port,
      ready: true, leaseExpiresAtMillis, leaseMs: parsed.leaseMs,
    });
  } finally { await lock.release(); }
}

async function unregisterExternal(runDir, serviceId, requestId) {
  const selected = await selectRecord(runDir, serviceId);
  if (selected?.ambiguous) return envelope(requestId, 'SERVICE_ID_REQUIRED', false, 'CLOSED', { services: selected.entries.map(publicRecord) });
  if (!selected) return envelope(requestId, 'NOT_REGISTERED', false, 'CLOSED', { serviceId: serviceId || null });
  if (selected.record.kind !== 'external') return envelope(requestId, 'NOT_EXTERNAL', false, selected.live.state, { serviceId: selected.record.serviceId });
  await removeRecord(runDir, selected.record);
  return envelope(requestId, 'OK', false, selected.live.state, { serviceId: selected.record.serviceId, unregistered: true });
}

async function startServiceLocked(parsed, runDir, requestId, injected, lock) {
  await lock.assertOwned();
  let project;
  try { project = await resolveProjectRoot(parsed.dir, injected.projectsRoot || DEFAULT_PROJECTS_ROOT); }
  catch (error) { return envelope(requestId, error.code || 'DIRECTORY_UNAVAILABLE', false, 'CLOSED'); }
  const { root, projectId } = project;
  await lock.assertOwned();
  if (root.split(path.sep).some((segment) => !isSafeName(segment))) return envelope(requestId, 'SENSITIVE_ROOT_DENIED', false, 'CLOSED');
  const rootStat = await fsp.stat(root);
  await lock.assertOwned();
  if (!rootStat.isDirectory()) return envelope(requestId, 'DIRECTORY_INVALID', false, 'CLOSED');
  const fallback = parsed.spaFallback ? await resolveServedFile(root, `/${parsed.spaFallback.split(path.sep).join('/')}`, null, '') : null;
  await lock.assertOwned();
  if (parsed.spaFallback && (!fallback || fallback.error || !fallback.path || !fallback.mime.startsWith('text/html'))) return envelope(requestId, 'SPA_FALLBACK_INVALID', false, 'CLOSED');
  const serviceId = crypto.randomBytes(16).toString('hex');
  const token = crypto.randomBytes(32).toString('hex');
  const leaseExpiresAtMillis = Date.now() + parsed.leaseMs;
  const logFile = path.join(runDir, `${serviceId}.log`);
  const configFile = path.join(runDir, `${serviceId}.config.json`);
  const registryFile = path.join(runDir, `${serviceId}.json`);
  const config = { version: VERSION, serviceId, token, root, port: parsed.port, leaseExpiresAtMillis, spaFallback: parsed.spaFallback || null, logFile };
  await writeJsonAtomic(configFile, config);
  let childReady;
  try {
    await lock.assertOwned();
    childReady = injected.spawnChild ? await injected.spawnChild(configFile) : await spawnAndWait(configFile, injected.cliScript || path.resolve(__dirname, '../bin/browser'));
    await lock.assertOwned();
  } catch (error) {
    let keptRecord = false;
    if (childReady && Number.isInteger(childReady.port)) {
      const recovery = await ownedRecordForChild({ serviceId, token, projectId, port: childReady.port, root, leaseExpiresAtMillis, leaseMs: parsed.leaseMs, logFile, child: childReady.child });
      keptRecord = await persistUnknownOwned(runDir, recovery);
      await requestControl(childReady.port, serviceId, token, 'stop');
      const gone = await confirmOwnedProcessGone(recovery);
      if (gone) {
        await removeRecord(runDir, recovery);
        keptRecord = false;
      }
      if (keptRecord || gone) await fsp.rm(configFile, { force: true });
    }
    else await fsp.rm(configFile, { force: true });
    await appendLog({ logFile }, 'service failed to become ready');
    const lockLost = error.code === 'START_LOCK_LOST';
    const uncertain = Boolean(childReady && Number.isInteger(childReady.port));
    return envelope(requestId, uncertain ? 'START_UNCONFIRMED' : error.code === 'PORT_IN_USE' ? 'PORT_IN_USE' : lockLost ? 'START_LOCK_LOST' : 'NOT_READY', uncertain || lockLost ? null : false, uncertain || lockLost ? 'UNKNOWN' : 'CLOSED', { message: uncertain || lockLost ? 'The service start result is unknown. Check status or list before retrying.' : 'The service did not become ready.' });
  }
  const actualPort = childReady.port;
  try {
    await lock.assertOwned();
    const probe = await requestControl(actualPort, serviceId, token, 'status');
    await lock.assertOwned();
    if (!probe || probe.authenticated !== true || probe.serviceId !== serviceId) {
      await requestControl(actualPort, serviceId, token, 'stop');
      await fsp.rm(configFile, { force: true });
      await appendLog({ logFile }, 'service identity verification failed');
      return envelope(requestId, 'IDENTITY_UNVERIFIED', null, 'UNKNOWN', { message: 'The listener could not be verified as this service.' });
    }
    const serverInfo = await readPublicStatus(actualPort, serviceId, token);
    await lock.assertOwned();
    const port = serverInfo && serverInfo.port ? serverInfo.port : actualPort;
    const record = await ownedRecordForChild({ serviceId, token, projectId, port, root, leaseExpiresAtMillis, leaseMs: parsed.leaseMs, logFile, child: childReady.child });
    await writeJsonAtomic(registryFile, record);
    await fsp.rm(configFile, { force: true });
    return envelope(requestId, 'OK', true, 'RUNNING', {
      serviceId, kind: 'owned-static', projectId, url: `http://127.0.0.1:${port}/`, host: '127.0.0.1', port,
      ready: true, leaseExpiresAtMillis, leaseMs: parsed.leaseMs,
    });
  } catch (error) {
    const recovery = await ownedRecordForChild({ serviceId, token, projectId, port: actualPort, root, leaseExpiresAtMillis, leaseMs: parsed.leaseMs, logFile, child: childReady.child });
    const keptRecord = await persistUnknownOwned(runDir, recovery);
    await requestControl(actualPort, serviceId, token, 'stop');
    const gone = await confirmOwnedProcessGone(recovery);
    if (gone) await removeRecord(runDir, recovery);
    if (keptRecord || gone) await fsp.rm(configFile, { force: true });
    await appendLog({ logFile }, 'service start ownership was lost');
    return envelope(requestId, error.code === 'START_LOCK_LOST' ? 'START_LOCK_LOST' : 'START_UNCONFIRMED', null, 'UNKNOWN', { message: 'The service start result is unknown. Check status or list before retrying.' });
  }
}

async function readPublicStatus(port, serviceId, token) {
  if (!Number.isInteger(port) || port < 1 || port > 65535) return null;
  return requestControl(port, serviceId, token, 'status');
}

async function records(runDir, sendControl = requestControl) {
  const names = await fsp.readdir(runDir).catch(() => []);
  return Promise.all(names.filter((name) => /^[a-f0-9]{32}\.json$/.test(name)).map(async (name) => {
    const record = await readRegistry(runDir, name.slice(0, 32));
    if (!record) return null;
    const clock = await readBootClock();
    const leaseExpired = record.bootId && clock?.bootId === record.bootId && Number.isSafeInteger(record.leaseExpiresAtUptimeMillis)
      ? clock.uptimeMillis >= record.leaseExpiresAtUptimeMillis
      : false;
    if (record.kind === 'external') {
      if (leaseExpired) { await removeRecord(runDir, record); return null; }
      const healthy = await requestExternalHealth(record);
      return { record, live: { state: healthy === true ? 'RUNNING' : 'UNKNOWN' } };
    }
    const live = await verifyRecord(record, 'status', sendControl);
    if (live) {
      if (leaseExpired) {
        const stopped = await sendControl(record.port, record.serviceId, record.token, 'stop');
        if (stopped?.authenticated === true) {
          await new Promise((resolve) => setTimeout(resolve, 80));
          const after = await verifyRecord(record, 'status', sendControl);
          if (!after && await confirmOwnedProcessGone(record)) { await removeRecord(runDir, record); return null; }
        }
        return { record, live: { state: 'UNKNOWN' } };
      }
      return { record, live };
    }
    if (record.bootId && clock?.bootId && record.bootId !== clock.bootId) {
      await removeRecord(runDir, record);
      return null;
    }
    if (record.pid && record.processStartTime && record.bootId) {
      const identity = await readLinuxProcessIdentity(record.pid);
      const definitelyDead = identity.state === 'dead' && identity.bootId === record.bootId;
      const definitelyReused = identity.state === 'live' && (identity.bootId !== record.bootId || identity.processStartTime !== record.processStartTime);
      if (definitelyDead || definitelyReused) { await removeRecord(runDir, record); return null; }
    }
    return { record, live: { state: 'UNKNOWN' } };
  })).then((items) => items.filter(Boolean));
}

function publicRecord({ record, live }) {
  return {
    serviceId: record.serviceId, host: '127.0.0.1', port: record.port,
    url: record.url || `http://127.0.0.1:${record.port}/`, state: live.state,
    kind: record.kind || 'owned-static', projectId: record.projectId || crypto.createHash('sha256').update(record.root).digest('hex').slice(0, 24),
    leaseExpiresAtMillis: record.leaseExpiresAtMillis,
  };
}

async function listServices(runDir, requestId, injected = {}) {
  const entries = await records(runDir, injected.requestControl || requestControl);
  const state = entries.length === 0 ? 'CLOSED' : entries.some(({ live }) => live.state === 'RUNNING' || live.state === 'STOPPING') ? 'RUNNING' : 'UNKNOWN';
  return envelope(requestId, 'OK', false, state, { services: entries.map(publicRecord) });
}

async function selectRecord(runDir, serviceId, injected = {}) {
  const entries = await records(runDir, injected.requestControl || requestControl);
  if (serviceId) return entries.find(({ record }) => record.serviceId === serviceId) || null;
  if (entries.length === 1) return entries[0];
  return entries.length === 0 ? null : { ambiguous: true, entries };
}

async function statusService(runDir, serviceId, requestId, injected = {}) {
  const selected = await selectRecord(runDir, serviceId, injected);
  if (selected && selected.ambiguous) return envelope(requestId, 'SERVICE_ID_REQUIRED', false, 'CLOSED', { services: selected.entries.map(publicRecord) });
  if (!selected) return envelope(requestId, 'NOT_RUNNING', false, 'CLOSED', { serviceId: serviceId || null });
  return envelope(requestId, 'OK', false, selected.live.state, publicRecord(selected));
}

async function readLogs(runDir, serviceId, limit, requestId, injected = {}) {
  const file = path.join(runDir, `${serviceId}.log`);
  try {
    const { bytes } = await readRegularFile(file, MAX_LOG_BYTES);
    const lines = bytes.toString('utf8').split(/\r?\n/).filter(Boolean).slice(-limit);
    const record = await readRegistry(runDir, serviceId);
    const live = record ? record.kind === 'external'
      ? { state: await requestExternalHealth(record) === true ? 'RUNNING' : 'UNKNOWN' }
      : await verifyRecord(record, 'status', injected.requestControl || requestControl) : null;
    return envelope(requestId, 'OK', false, live ? live.state : record ? 'UNKNOWN' : 'CLOSED', { serviceId, lines });
  } catch { return envelope(requestId, 'LOG_NOT_FOUND', false, 'CLOSED', { serviceId }); }
}

async function stopService(runDir, serviceId, requestId, injected = {}) {
  const sendControl = injected.requestControl || requestControl;
  const selected = await selectRecord(runDir, serviceId, injected);
  if (selected && selected.ambiguous) return envelope(requestId, 'SERVICE_ID_REQUIRED', false, 'CLOSED', { services: selected.entries.map(publicRecord) });
  if (!selected) return envelope(requestId, 'NOT_RUNNING', false, 'CLOSED', { serviceId: serviceId || null });
  if (selected.record.kind === 'external') return envelope(requestId, 'EXTERNAL_NOT_OWNED', false, selected.live.state, { serviceId: selected.record.serviceId });
  const result = await sendControl(selected.record.port, selected.record.serviceId, selected.record.token, 'stop');
  if (!result || result.authenticated !== true || result.serviceId !== selected.record.serviceId) return envelope(requestId, 'STOP_UNCONFIRMED', null, 'UNKNOWN', { serviceId: selected.record.serviceId });
  const waitMs = selected.record.pid ? 2500 : 250;
  const deadline = process.hrtime.bigint() + BigInt(waitMs) * 1_000_000n;
  while (process.hrtime.bigint() < deadline) {
    const alive = await verifyRecord(selected.record, 'status', sendControl);
    if (!alive && await confirmOwnedProcessGone(selected.record)) {
      await removeRecord(runDir, selected.record);
      return envelope(requestId, 'OK', true, 'CLOSED', { serviceId: selected.record.serviceId, stopped: true });
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  return envelope(requestId, 'STOP_UNCONFIRMED', null, 'UNKNOWN', { serviceId: selected.record.serviceId });
}

/** Attests the serving instance and request route; it does not attest later page scripts or source lines. */
function addProjectDocumentProof(firstBytes, config, route, now = Date.now()) {
  if (!Buffer.isBuffer(firstBytes) || !SERVICE_ID.test(config.serviceId) ||
      !/^[a-f0-9]{64}$/.test(config.token) || typeof config.root !== 'string' ||
      typeof route !== 'string' || !route.startsWith('/') || route.length > 512 || /[\u0000-\u001f\u007f?#]/.test(route) ||
      !Number.isSafeInteger(now) || !Number.isSafeInteger(config.leaseExpiresAtMillis) || config.leaseExpiresAtMillis <= now) return firstBytes;
  const payload = { version: 1, serviceId: config.serviceId,
    projectId: crypto.createHash('sha256').update(config.root).digest('hex').slice(0, 24), route,
    issuedAtMillis: now, expiresAtMillis: Math.min(config.leaseExpiresAtMillis, now + DEFAULT_LEASE_MS),
    documentNonce: crypto.randomBytes(16).toString('hex') };
  const encoded = Buffer.from(JSON.stringify(payload)).toString('base64url');
  const signature = crypto.createHmac('sha256', Buffer.from(config.token, 'hex')).update(encoded).digest('hex');
  const marker = Buffer.from(`<meta name="dsh-browser-project" content="${encoded}.${signature}">`);
  // Preserve the original DOCTYPE bytes so attestation does not switch a standards document into quirks mode.
  const initial = firstBytes.toString('latin1');
  const doctype = /^(?:\xEF\xBB\xBF)?\s*<!doctype\s[^>]*>/i.exec(initial);
  if (/^(?:\xEF\xBB\xBF)?\s*<!doctype/i.test(initial) && !doctype) return firstBytes;
  const offset = doctype ? doctype[0].length : (firstBytes.subarray(0, 3).equals(Buffer.from([0xef, 0xbb, 0xbf])) ? 3 : 0);
  return Buffer.concat([firstBytes.subarray(0, offset), marker, firstBytes.subarray(offset)]);
}

module.exports = {
  DEFAULT_LEASE_MS, MAX_LEASE_MS, MAX_ACTIVE_SERVICES, MAX_HTTP_CONNECTIONS, MAX_ACTIVE_STREAMS, MAX_START_LOCK_MS,
  parseServeArguments, runServeCommand, runServeChild, safeRelative, resolveServedFile,
  acquireStartLock, makeServer, readLinuxProcessIdentity, requestControl, addProjectDocumentProof,
};
