'use strict'

const { execFile: nodeExecFile } = require('node:child_process')
const { randomUUID } = require('node:crypto')

const CLI = '/opt/browser/bin/browser'
const RESPONSE_LIMIT = 16 * 1024
const MAX_LEASE_MS = 8 * 60 * 60 * 1000
const SERVICE_ID = /^[a-f0-9]{32}$/
const REQUEST_ID = /^[a-f0-9]{24}$/
const STATES = new Set(['CLOSED', 'RUNNING', 'STOPPING', 'UNKNOWN'])
const TIMEOUT_MS = { start: 15_000, register: 8_000, status: 8_000, list: 8_000, logs: 8_000, stop: 6_000, unregister: 8_000 }

function ownKeysOnly(value, allowed) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  return Object.keys(value).every((key) => allowed.includes(key))
}

function validateServeArgs(args) {
  if (!args || typeof args !== 'object' || Array.isArray(args) || typeof args.action !== 'string') return 'action is required'
  switch (args.action) {
    case 'start':
      if (!ownKeysOnly(args, ['action', 'dir', 'port', 'leaseMs', 'spaFallback'])) return 'serve start has unsupported fields'
      if (typeof args.dir !== 'string' || args.dir.trim().length < 1 || args.dir.length > 1024 || args.dir.includes('\0')) return 'dir must be a path of 1..1024 characters'
      if (args.port !== undefined && (!Number.isInteger(args.port) || args.port < 0 || args.port > 65535)) return 'port must be an integer from 0 to 65535'
      if (args.leaseMs !== undefined && (!Number.isSafeInteger(args.leaseMs) || args.leaseMs < 60_000 || args.leaseMs > MAX_LEASE_MS)) return 'leaseMs must be between 60000 and 28800000'
      if (args.spaFallback !== undefined && !validRelativeFile(args.spaFallback)) return 'spaFallback must be a safe relative file path'
      return ''
    case 'list':
      return ownKeysOnly(args, ['action']) ? '' : 'serve list accepts no arguments'
    case 'register':
      if (!ownKeysOnly(args, ['action', 'dir', 'url', 'healthPath', 'leaseMs'])) return 'serve register has unsupported fields'
      if (typeof args.dir !== 'string' || args.dir.trim().length < 1 || args.dir.length > 1024 || args.dir.includes('\0')) return 'dir must be a path of 1..1024 characters'
      if (typeof args.url !== 'string' || args.url.length > 2048) return 'url must be a loopback HTTP URL'
      try {
        const url = new URL(args.url)
        if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || !url.port || url.username || url.password || url.search || url.hash) return 'url must be an HTTP URL on 127.0.0.1'
      } catch { return 'url must be an HTTP URL on 127.0.0.1' }
      if (args.healthPath !== undefined && (typeof args.healthPath !== 'string' || args.healthPath.length > 1024 || !args.healthPath.startsWith('/') || args.healthPath.includes('?') || args.healthPath.includes('#') || args.healthPath.includes('\\'))) return 'healthPath must be a safe absolute path'
      if (args.leaseMs !== undefined && (!Number.isSafeInteger(args.leaseMs) || args.leaseMs < 60_000 || args.leaseMs > MAX_LEASE_MS)) return 'leaseMs must be between 60000 and 28800000'
      return ''
    case 'status': case 'stop': case 'unregister':
      if (!ownKeysOnly(args, ['action', 'serviceId'])) return `serve ${args.action} has unsupported fields`
      if (args.serviceId !== undefined && !SERVICE_ID.test(args.serviceId)) return 'serviceId is invalid'
      return ''
    case 'logs':
      if (!ownKeysOnly(args, ['action', 'serviceId', 'limit'])) return 'serve logs has unsupported fields'
      if (typeof args.serviceId !== 'string' || !SERVICE_ID.test(args.serviceId)) return 'logs requires a valid serviceId'
      if (args.limit !== undefined && (!Number.isInteger(args.limit) || args.limit < 1 || args.limit > 100)) return 'limit must be an integer from 1 to 100'
      return ''
    default:
      return 'action must be start, register, status, list, logs, stop, or unregister'
  }
}

function validRelativeFile(value) {
  if (typeof value !== 'string' || value.length < 1 || value.length > 1024 || value.includes('\0') || value.includes('\\')) return false
  let decoded
  try { decoded = decodeURIComponent(value) } catch { return false }
  if (decoded.startsWith('/') || /^[A-Za-z]:/.test(decoded)) return false
  const segments = decoded.split('/')
  return segments.every((segment) => segment.length > 0 && segment !== '.' && segment !== '..' && !isSensitiveName(segment))
}

function isSensitiveName(segment) {
  const name = segment.toLowerCase()
  return new Set([
    '.dsh', '.git', '.ssh', '.config', '.android', '.gradle', '.npm', '.aws', '.kube', '.npmrc', '.pypirc', '.netrc',
    'user-data', 'node_modules', 'credentials', 'credential', 'secrets', 'secret', 'id_rsa', 'id_ed25519', 'id_ecdsa', 'authorized_keys',
  ]).has(name) || /^\.env(?:\.|$)/i.test(name) || /(?:^|[._-])(?:credentials?|secrets?|tokens?)(?:[._-]|$)/i.test(name) ||
    /\.(?:pem|key|p12|pfx|jks|keystore|mobileprovision|asc)$/i.test(name)
}

function cliArguments(args) {
  const argv = ['serve', args.action]
  if (args.action === 'start') {
    argv.push('--dir', args.dir)
    if (args.port !== undefined) argv.push('--port', String(args.port))
    if (args.leaseMs !== undefined) argv.push('--lease-ms', String(args.leaseMs))
    if (args.spaFallback !== undefined) argv.push('--spa-fallback', args.spaFallback)
  } else if (args.action === 'register') {
    argv.push('--dir', args.dir, '--url', args.url)
    if (args.healthPath !== undefined) argv.push('--health-path', args.healthPath)
    if (args.leaseMs !== undefined) argv.push('--lease-ms', String(args.leaseMs))
  } else if (['status', 'stop', 'unregister'].includes(args.action) && args.serviceId !== undefined) {
    argv.push(args.serviceId)
  } else if (args.action === 'logs') {
    argv.push(args.serviceId)
    if (args.limit !== undefined) argv.push('--limit', String(args.limit))
  }
  return argv
}

function serviceRecord(value) {
  if (!value || !ownKeysOnly(value, ['serviceId', 'host', 'port', 'url', 'state', 'leaseExpiresAtMillis', 'kind', 'projectId']) ||
      typeof value.serviceId !== 'string' || !SERVICE_ID.test(value.serviceId) || value.host !== '127.0.0.1' ||
      !Number.isInteger(value.port) || value.port < 1 || value.port > 65535 ||
      typeof value.url !== 'string' || !value.url.startsWith(`http://127.0.0.1:${value.port}/`) || !['RUNNING', 'STOPPING', 'UNKNOWN'].includes(value.state) ||
      !['owned-static', 'external'].includes(value.kind) || typeof value.projectId !== 'string' || !/^[a-f0-9]{24}$/.test(value.projectId) ||
      !Number.isSafeInteger(value.leaseExpiresAtMillis)) return false
  return true
}

function validateResponseData(action, code, data) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return false
  if (data.message !== undefined && (typeof data.message !== 'string' || data.message.length > 256 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(data.message))) return false
  if (data.maximum !== undefined && (!Number.isInteger(data.maximum) || data.maximum < 0 || data.maximum > 4)) return false
  if (data.serviceId !== undefined && data.serviceId !== null && (typeof data.serviceId !== 'string' || !SERVICE_ID.test(data.serviceId))) return false
  if (data.services !== undefined && (!Array.isArray(data.services) || data.services.length > 4 || !data.services.every(serviceRecord))) return false

  if (action === 'start' && code === 'OK') {
    return ownKeysOnly(data, ['serviceId', 'url', 'host', 'port', 'ready', 'leaseExpiresAtMillis', 'leaseMs', 'kind', 'projectId']) &&
      typeof data.serviceId === 'string' && SERVICE_ID.test(data.serviceId) && data.host === '127.0.0.1' &&
      Number.isInteger(data.port) && data.port >= 1 && data.port <= 65535 && data.url === `http://127.0.0.1:${data.port}/` &&
      data.kind === 'owned-static' && typeof data.projectId === 'string' && /^[a-f0-9]{24}$/.test(data.projectId) &&
      data.ready === true && Number.isSafeInteger(data.leaseExpiresAtMillis) && Number.isSafeInteger(data.leaseMs) &&
      data.leaseMs >= 60_000 && data.leaseMs <= MAX_LEASE_MS
  }
  if (action === 'register' && code === 'OK') {
    return ownKeysOnly(data, ['serviceId', 'kind', 'projectId', 'url', 'host', 'port', 'ready', 'leaseExpiresAtMillis', 'leaseMs']) &&
      typeof data.serviceId === 'string' && SERVICE_ID.test(data.serviceId) && data.kind === 'external' &&
      typeof data.projectId === 'string' && /^[a-f0-9]{24}$/.test(data.projectId) && data.host === '127.0.0.1' &&
      Number.isInteger(data.port) && data.port >= 1 && data.port <= 65535 && typeof data.url === 'string' && data.url.startsWith(`http://127.0.0.1:${data.port}/`) &&
      data.ready === true && Number.isSafeInteger(data.leaseExpiresAtMillis) && Number.isSafeInteger(data.leaseMs) && data.leaseMs >= 60_000 && data.leaseMs <= MAX_LEASE_MS
  }
  if (action === 'list' && code === 'OK') return ownKeysOnly(data, ['services']) && Array.isArray(data.services) && data.services.length <= 4 && data.services.every(serviceRecord)
  if (action === 'status' && code === 'OK') return serviceRecord(data)
  if (action === 'logs' && code === 'OK') {
    return ownKeysOnly(data, ['serviceId', 'lines']) && typeof data.serviceId === 'string' && SERVICE_ID.test(data.serviceId) &&
      Array.isArray(data.lines) && data.lines.length <= 100 && data.lines.every((line) =>
        typeof line === 'string' && line.length <= 1024 && !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(line))
  }
  if (action === 'stop' && code === 'OK') return ownKeysOnly(data, ['serviceId', 'stopped']) && typeof data.serviceId === 'string' && SERVICE_ID.test(data.serviceId) && data.stopped === true
  if (action === 'unregister' && code === 'OK') return ownKeysOnly(data, ['serviceId', 'unregistered']) && typeof data.serviceId === 'string' && SERVICE_ID.test(data.serviceId) && data.unregistered === true

  const allowed = ['message', 'maximum', 'serviceId', 'services', 'stopped', 'unregistered']
  if (!ownKeysOnly(data, allowed)) return false
  if (data.stopped !== undefined && typeof data.stopped !== 'boolean') return false
  if (data.unregistered !== undefined && typeof data.unregistered !== 'boolean') return false
  return true
}

function localResult(code, text, requestId = '', executed = 'false', state = 'CLOSED', data = {}) {
  return {
    ok: false,
    code,
    text,
    requestId,
    executed,
    sessionGeneration: 0,
    controlGeneration: 0,
    sessionGenerationAvailable: false,
    controlGenerationAvailable: false,
    state,
    dataJson: JSON.stringify(data),
    path: '',
  }
}

function normalizeServeResponse(stdout, action) {
  if (typeof stdout !== 'string' || Buffer.byteLength(stdout, 'utf8') > RESPONSE_LIMIT) return null
  const lines = stdout.trim().split(/\r?\n/)
  if (lines.length !== 1 || !lines[0]) return null
  let response
  try { response = JSON.parse(lines[0]) } catch { return null }
  if (!response || typeof response !== 'object' || Array.isArray(response) || response.version !== 1 ||
      typeof response.requestId !== 'string' || !REQUEST_ID.test(response.requestId) ||
      typeof response.code !== 'string' || !/^[A-Z][A-Z0-9_]{0,63}$/.test(response.code) ||
      !(response.executed === true || response.executed === false || response.executed === null) ||
      !STATES.has(response.state) || !ownKeysOnly(response, ['version', 'requestId', 'code', 'executed', 'state', 'data']) ||
      !response.data || typeof response.data !== 'object' || Array.isArray(response.data) ||
      !validateResponseData(action, response.code, response.data)) return null
  if (response.code === 'OK') {
    const expectedExecuted = action === 'start' || action === 'stop'
    if (response.executed !== expectedExecuted) return null
  }
  const data = response.data
  const dataJson = JSON.stringify(data)
  if (Buffer.byteLength(dataJson, 'utf8') > RESPONSE_LIMIT) return null
  const ok = response.code === 'OK'
  const text = ok ? successText(action, data) : errorText(response.code)
  return {
    ok,
    code: response.code,
    text,
    requestId: response.requestId,
    executed: response.executed === true ? 'true' : response.executed === false ? 'false' : 'unknown',
    sessionGeneration: 0,
    controlGeneration: 0,
    sessionGenerationAvailable: false,
    controlGenerationAvailable: false,
    state: response.state,
    dataJson,
    path: '',
  }
}

function successText(action, data) {
  switch (action) {
    case 'start': return `Local Browser service is ready at ${data.url}.`
    case 'register': return `External loopback service registered at ${data.url}. Browser will not stop it.`
    case 'list': return `Found ${data.services.length} Browser service registration(s).`
    case 'status': return `Browser service registration is ${data.state.toLowerCase()}.`
    case 'logs': return `Read ${data.lines.length} bounded log line(s) for the owned Browser service.`
    case 'stop': return 'Owned Browser service stopped.'
    case 'unregister': return 'External service registration removed; the service was not stopped.'
    default: return 'Browser service operation completed.'
  }
}

function errorText(code) {
  if (code === 'UNKNOWN' || code === 'STOP_UNCONFIRMED' || code === 'START_LOCK_LOST' || code === 'START_UNCONFIRMED') {
    return 'The Browser service result is unknown. Check status or list before taking another action; do not repeat start or stop automatically.'
  }
  return `Browser service operation was not completed (${code}).`
}

function browserServeRequest(args, dependencies = {}) {
  const invalid = validateServeArgs(args)
  if (invalid) return Promise.resolve(localResult('INVALID_ARGUMENT', invalid))
  const execute = dependencies.execFile || nodeExecFile
  const cli = dependencies.cli || CLI
  const argv = cliArguments(args)
  const timeout = dependencies.timeoutMs === undefined ? TIMEOUT_MS[args.action] : dependencies.timeoutMs
  return new Promise((resolve) => {
    execute(cli, argv, {
      encoding: 'utf8',
      timeout,
      maxBuffer: RESPONSE_LIMIT + 1,
      windowsHide: true,
    }, (error, stdout) => {
      const normalized = normalizeServeResponse(stdout, args.action)
      if (normalized) return resolve(normalized)
      if (error && (error.code === 'ENOENT' || error.code === 'EACCES')) {
        return resolve(localResult('CLI_UNAVAILABLE', 'The Browser service command is unavailable.'))
      }
      if (error && error.code === 2) return resolve(localResult('CLI_USAGE', 'The Browser service command rejected the request.', '', 'false'))
      if (error && (error.code === 'ETIMEDOUT' || error.killed === true || error.code === 'ERR_CHILD_PROCESS_STDIO_MAXBUFFER')) {
        return resolve(localResult('UNKNOWN', errorText('UNKNOWN'), '', 'unknown', 'UNKNOWN'))
      }
      resolve(localResult('PROTOCOL_ERROR', 'The Browser service command returned an invalid response.', '', 'unknown', 'UNKNOWN'))
    })
  })
}

module.exports = {
  CLI,
  RESPONSE_LIMIT,
  TIMEOUT_MS,
  browserServeRequest,
  cliArguments,
  normalizeServeResponse,
  validateServeArgs,
}
