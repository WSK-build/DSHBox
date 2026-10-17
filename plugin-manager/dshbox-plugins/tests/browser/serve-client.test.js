'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { browserServeRequest, cliArguments, normalizeServeResponse, validateServeArgs } = require('../../assets/plugins/browser-use/plugin/lib/serve-client')

const serviceId = 'a'.repeat(32)
const requestId = 'b'.repeat(24)

function response(action, code, executed, state, data) {
  return JSON.stringify({ version: 1, requestId, code, executed, state, data }) + '\n'
}

test('serve arguments are strict, bounded, and prevent sensitive fallback paths', () => {
  assert.equal(validateServeArgs({ action: 'start', dir: '/opt/browser/www', port: 0, leaseMs: 60000, spaFallback: 'index.html' }), '')
  assert.equal(validateServeArgs({ action: 'register', dir: '/opt/browser/site', url: 'http://127.0.0.1:4173/', healthPath: '/health' }), '')
  assert.match(validateServeArgs({ action: 'register', dir: '/opt/browser/site', url: 'http://localhost:4173/' }), /127\.0\.0\.1/)
  assert.match(validateServeArgs({ action: 'start', dir: '/opt/browser/www', shell: 'id' }), /unsupported fields/)
  assert.match(validateServeArgs({ action: 'start', dir: '/opt/browser/www', spaFallback: '../secret.html' }), /safe relative/)
  assert.match(validateServeArgs({ action: 'start', dir: '/opt/browser/www', spaFallback: '.env.local' }), /safe relative/)
  assert.match(validateServeArgs({ action: 'stop', serviceId: '../' }), /serviceId/)
  assert.match(validateServeArgs({ action: 'logs', serviceId, limit: 101 }), /limit/)
})

test('serve command uses fixed argv and contains no shell or credential flags', () => {
  assert.deepEqual(cliArguments({ action: 'start', dir: '/opt/browser/www demo', port: 0, leaseMs: 120000, spaFallback: 'index.html' }), [
    'serve', 'start', '--dir', '/opt/browser/www demo', '--port', '0', '--lease-ms', '120000', '--spa-fallback', 'index.html',
  ])
  assert.deepEqual(cliArguments({ action: 'stop', serviceId }), ['serve', 'stop', serviceId])
  assert.deepEqual(cliArguments({ action: 'register', dir: '/tmp/site', url: 'http://127.0.0.1:4173/', healthPath: '/health', leaseMs: 60000 }), [
    'serve', 'register', '--dir', '/tmp/site', '--url', 'http://127.0.0.1:4173/', '--health-path', '/health', '--lease-ms', '60000',
  ])
  assert.deepEqual(cliArguments({ action: 'unregister', serviceId }), ['serve', 'unregister', serviceId])
  assert.deepEqual(cliArguments({ action: 'list' }), ['serve', 'list'])
})

test('response normalization admits public owned-service data but rejects token/path leakage', () => {
  const started = normalizeServeResponse(response('start', 'OK', true, 'RUNNING', {
    serviceId, kind: 'owned-static', projectId: 'c'.repeat(24), url: 'http://127.0.0.1:4000/', host: '127.0.0.1', port: 4000,
    ready: true, leaseExpiresAtMillis: 123456, leaseMs: 60000,
  }), 'start')
  assert.equal(started.ok, true)
  assert.equal(started.sessionGenerationAvailable, false)
  assert.equal(JSON.parse(started.dataJson).url, 'http://127.0.0.1:4000/')
  assert.equal(normalizeServeResponse(response('list', 'OK', false, 'RUNNING', { token: 'secret' }), 'list'), null)
  assert.equal(normalizeServeResponse(response('start', 'OK', true, 'RUNNING', {
    serviceId, kind: 'owned-static', projectId: 'c'.repeat(24), url: 'http://127.0.0.1:4000/', host: '127.0.0.1', port: 4000, ready: true,
    leaseExpiresAtMillis: 123456, leaseMs: 60000, root: '/tmp/private',
  }), 'start'), null)
})

test('start invocation passes arguments without shell and enforces bounded process output', async () => {
  let observed
  const result = await browserServeRequest({ action: 'start', dir: '/tmp/site' }, {
    cli: '/opt/browser/bin/browser',
    execFile(file, argv, options, callback) {
      observed = { file, argv, options }
      callback(Object.assign(new Error('nonzero for structured error'), { code: 3 }), response('start', 'OK', true, 'RUNNING', {
        serviceId, kind: 'owned-static', projectId: 'c'.repeat(24), url: 'http://127.0.0.1:4000/', host: '127.0.0.1', port: 4000,
        ready: true, leaseExpiresAtMillis: 123456, leaseMs: 1800000,
      }))
    },
  })
  assert.equal(result.ok, true)
  assert.deepEqual(observed.argv, ['serve', 'start', '--dir', '/tmp/site'])
  assert.equal(observed.options.shell, undefined)
  assert.equal(observed.options.timeout, 15000)
  assert.equal(observed.options.maxBuffer, 16 * 1024 + 1)
})

test('uncertain stop timeout is UNKNOWN and never retries the side effect', async () => {
  let calls = 0
  const result = await browserServeRequest({ action: 'stop', serviceId }, {
    execFile(_file, _argv, _options, callback) {
      calls += 1
      const error = new Error('timed out')
      error.code = 'ETIMEDOUT'
      callback(error, '')
    },
  })
  assert.equal(calls, 1)
  assert.equal(result.code, 'UNKNOWN')
  assert.equal(result.executed, 'unknown')
  assert.equal(result.state, 'UNKNOWN')
})
