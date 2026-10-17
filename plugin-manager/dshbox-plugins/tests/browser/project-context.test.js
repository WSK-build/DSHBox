'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { createProjectContextResolver, capturedProjectContext } = require('../../assets/plugins/browser-use/plugin/lib/project-context')
const service = { serviceId: 'a'.repeat(32), projectId: 'b'.repeat(24), kind: 'external',
  url: 'http://127.0.0.1:8123/app/', state: 'RUNNING', leaseExpiresAtMillis: 20000 }
const response = data => ({ ok: true, code: 'OK', dataJson: JSON.stringify(data) })

test('native capture metadata remains tied to its saved project and cannot be promoted from current lookup', () => {
  const context = { serviceId: service.serviceId, projectId: service.projectId, kind: 'owned-static',
    origin: 'http://127.0.0.1:8123', route: '/app/editor', projectRootRelative: 'demo',
    documentNonce: 'c'.repeat(32), issuedAtMillis: 1000, capturedAtMillis: 1100,
    relation: 'authenticated_document_source', captureProvenanceVerified: true, token: 'must-not-escape' }
  const page = 'http://127.0.0.1:8123/app/editor'
  assert.equal(capturedProjectContext({ projectContext: context }, page).projectRootRelative, 'demo')
  assert.equal(capturedProjectContext({ projectContext: context }, page).token, undefined)
  for (const patch of [{ captureProvenanceVerified: false }, { relation: 'current_registered_route_match' },
    { route: '/other' }, { origin: 'http://127.0.0.1:8124' }, { projectRootRelative: '../other' },
    { projectRootRelative: 'a\\b' }, { capturedAtMillis: 999 }, { documentNonce: 'bad' }]) {
    assert.equal(capturedProjectContext({ projectContext: { ...context, ...patch } }, page), null)
  }
});

test('local selection resolves only after matching fresh service status and strips query/fragment', async () => {
  const calls = []
  const resolve = createProjectContextResolver({ now: () => 1000, request: async args => {
    calls.push(args)
    return response(args.action === 'list' ? { services: [service] } : service)
  } })
  assert.deepEqual(await resolve({ pageUrl: 'http://127.0.0.1:8123/app/editor?secret=value#private' }), {
    serviceId: service.serviceId, projectId: service.projectId, kind: 'external', route: '/app/editor',
    relation: 'current_registered_route_match', captureProvenanceVerified: false,
  })
  assert.deepEqual(calls, [{ action: 'list' }, { action: 'status', serviceId: service.serviceId }])
})

test('external/private addresses never trigger service enumeration', async () => {
  let calls = 0
  const resolve = createProjectContextResolver({ request: async () => { calls++; return response({ services: [service] }) } })
  for (const pageUrl of ['https://example.test/private/path?token=x', 'http://localhost:8123/app/',
    'http://user:pass@127.0.0.1:8123/app/', 'file:///root/projects/a.html', 'http://127.0.0.1/app/']) {
    assert.equal(await resolve({ pageUrl }), null)
  }
  assert.equal(calls, 0)
})

test('ambiguous, expired, nonmatching path and changed service identities fail closed', async () => {
  for (const services of [[service, { ...service, serviceId: 'c'.repeat(32) }],
    [{ ...service, leaseExpiresAtMillis: 900 }], [{ ...service, url: 'http://127.0.0.1:8123/other/' }],
    [{ ...service, state: 'UNKNOWN' }]]) {
    let calls = 0
    const resolve = createProjectContextResolver({ now: () => 1000, request: async () => {
      calls++; return response({ services })
    } })
    assert.equal(await resolve({ pageUrl: service.url }), null)
    assert.equal(calls, 1)
  }
  const resolve = createProjectContextResolver({ now: () => 1000,
    request: async args => response(args.action === 'list' ? { services: [service] } : { ...service, projectId: 'c'.repeat(24) }) })
  assert.equal(await resolve({ pageUrl: service.url }), null)
})

test('cancel and bounded timeout stop even a transport ignoring abort, without retry', async () => {
  let calls = 0
  const controller = new AbortController()
  controller.abort()
  const resolve = createProjectContextResolver({ timeoutMs: 10, request: () => { calls++; return new Promise(() => {}) } })
  assert.equal(await resolve({ pageUrl: service.url, signal: controller.signal }), null)
  assert.equal(calls, 0)
  assert.equal(await resolve({ pageUrl: service.url }), null)
  assert.equal(calls, 1)
})
