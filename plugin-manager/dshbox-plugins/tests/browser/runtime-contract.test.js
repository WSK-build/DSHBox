'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fixtures = require('./runtime-response-fixtures.json')
const { safeResponse, validateArgs } = require('../../assets/plugins/browser-use/plugin/lib/client')
const { buildTools } = require('../../assets/plugins/browser-use/plugin/lib/tools')
const { normalizeResponse, validateRequest: validateCliRequest } = require('../../../../browser/browser-bridge/src/main/assets/browser/lib/browser-cli.cjs')

function acceptThroughCliAndAdapter(method, fixture) {
  const action = fixture.data && (fixture.data.condition || fixture.data.action || fixture.data.strategy)
  const response = normalizeResponse(method, fixture.requestId, structuredClone(fixture), action)
  assert.ok(response, `${fixture.code} fixture must match the Browser CLI response contract`)
  return safeResponse(`${JSON.stringify(response)}\n`, fixture.requestId, method, action)
}

test('control transport uncertainty survives both validators without claiming approval or retry', () => {
  const args = { action: 'request', summary: 'continue' }
  const result = { version: 1, requestId: 'control-unknown', code: 'UNKNOWN', executed: null,
    sessionGeneration: null, controlGeneration: null, state: null, owner: null,
    data: { cancelStage: 'queued', reason: 'transport_unavailable', actionOutcome: 'unknown', automaticRetryAllowed: false } }
  for (const check of [
    value => normalizeResponse('control', result.requestId, value, 'request', args),
    value => safeResponse(JSON.stringify(value), result.requestId, 'control', 'request', args),
  ]) {
    assert.deepEqual(check(result), result)
    assert.equal(check({ ...result, executed: true }), null)
    assert.equal(check({ ...result, data: { ...result.data, automaticRetryAllowed: true } }), null)
    assert.equal(check({ ...result, approvalId: 'a'.repeat(32) }), null)
  }
})

test('approved upload list crosses both production validators without exposing staging paths', () => {
  const args = { action: 'list', approvalId: 'a'.repeat(32) }
  const result = { ...structuredClone(fixtures.success), requestId: 'upload-list', executed: false,
    data: { approvalId: args.approvalId, state: 'ready', expiresInMs: 10000,
      files: [{ fileId: 'b'.repeat(32), name: 'report.txt', mimeType: 'text/plain', size: 8 }] } }
  const normalized = normalizeResponse('upload', result.requestId, result, 'list', args)
  assert.ok(normalized)
  assert.ok(safeResponse(JSON.stringify(normalized) + '\n', result.requestId, 'upload', 'list', args))
  const exposed = structuredClone(result)
  exposed.data.files[0].stagingPath = '/opt/browser/upload/' + args.approvalId + '/file.txt'
  assert.equal(normalizeResponse('upload', exposed.requestId, exposed, 'list', args), null)
  assert.equal(safeResponse(JSON.stringify(exposed) + '\n', exposed.requestId, 'upload', 'list', args), null)
})

test('fixed wire-contract examples pass the shipped CLI and Cordis adapter validators', () => {
  const success = acceptThroughCliAndAdapter('open', fixtures.success)
  assert.equal(success.code, 'OK')
  assert.equal(success.executed, true)
  assert.equal(success.owner, 'ai')
  assert.equal(success.sessionGeneration, 4)
  assert.equal(success.controlGeneration, 9)

  const rejected = acceptThroughCliAndAdapter('click', fixtures.rejected)
  assert.equal(rejected.code, 'AUTOMATION_DISABLED')
  assert.equal(rejected.executed, false)

  const unknown = acceptThroughCliAndAdapter('click', fixtures.unknown)
  assert.equal(unknown.code, 'UNKNOWN')
  assert.equal(unknown.executed, null)

  const screenshot = acceptThroughCliAndAdapter('screenshot', fixtures.screenshot)
  assert.equal(screenshot.data.artifactPath, '/opt/browser/out/shot/shot-fixture.png')
  assert.equal(screenshot.data.format, 'png')
})

test('response validators reject request-id mismatches and unsafe screenshot paths', () => {
  const wrongId = { ...structuredClone(fixtures.success), requestId: 'other-request' }
  assert.equal(normalizeResponse('open', fixtures.success.requestId, wrongId), null)

  const unsafeScreenshot = structuredClone(fixtures.screenshot)
  unsafeScreenshot.data.artifactPath = '/opt/browser/out/shot/../private.png'
  assert.equal(normalizeResponse('screenshot', unsafeScreenshot.requestId, unsafeScreenshot), null)
  assert.equal(safeResponse(`${JSON.stringify(unsafeScreenshot)}\n`, unsafeScreenshot.requestId, 'screenshot'), null)
})

test('upload client schema and response adapter enforce explicit one-shot project grants', () => {
  assert.equal(validateArgs('upload', { action: 'request', paths: ['reports/report.pdf'], acceptTypes: ['application/pdf'] }), '')
  for (const filePath of ['/etc/passwd', '../secret', 'user-data/private.txt', '.dsh/config', '.env.local', 'id_rsa', 'api_token.txt', 'x.p12']) {
    assert.notEqual(validateArgs('upload', { action: 'request', paths: [filePath] }), '')
  }
  assert.notEqual(validateArgs('upload', { action: 'submit', approvalId: 'a'.repeat(32), fileIds: ['../bad'] }), '')
  const observedNode = `b:4:9:3:1:${'b'.repeat(32)}:0`
  assert.equal(validateArgs('upload', { action: 'activate', approvalId: 'a'.repeat(32), nodeId: observedNode }), '')
  assert.notEqual(validateArgs('upload', { action: 'activate', approvalId: 'a'.repeat(32), nodeId: 'stale' }), '')
  const base = { version: 1, requestId: 'upload_req', code: 'OK', executed: true, sessionGeneration: 4,
    controlGeneration: 9, state: 'RUNNING', owner: 'ai', data: { state: 'awaiting_approval', approvalId: 'a'.repeat(32),
      pageId: 'pg-0123456789abcdefabcd', documentGeneration: 3, fileCount: 1, bytesTotal: 10, expiresInMs: 120000 } }
  const response = safeResponse(`${JSON.stringify(base)}\n`, 'upload_req', 'upload', 'request', { action: 'request' })
  assert.equal(response.code, 'OK')
  assert.equal(safeResponse(`${JSON.stringify({ ...base, data: { ...base.data, pageId: '../page' } })}\n`, 'upload_req', 'upload', 'request', { action: 'request' }), null)
  assert.equal(safeResponse(`${JSON.stringify({ ...base, data: { ...base.data, extra: true } })}\n`, 'upload_req', 'upload', 'request', { action: 'request' }), null)
  const activation = { ...base, code: 'OK', data: { approvalId: 'a'.repeat(32), state: 'chooser_pending' } }
  assert.ok(safeResponse(`${JSON.stringify(activation)}\n`, 'upload_req', 'upload', 'activate', { action: 'activate', approvalId: 'a'.repeat(32) }))
  assert.ok(safeResponse(`${JSON.stringify({ ...base, code: 'UPLOAD_ACTIVATION_UNRESOLVED', data: {} })}\n`, 'upload_req', 'upload', 'activate', { action: 'activate', approvalId: 'a'.repeat(32) }))
})

test('response validators require version 1 and a canonical lifecycle state', () => {
  const base = structuredClone(fixtures.rejected)
  assert.ok(safeResponse(`${JSON.stringify(base)}\n`, base.requestId))
  assert.equal(safeResponse(`${JSON.stringify({ ...base, version: undefined })}\n`, base.requestId), null)
  assert.equal(safeResponse(`${JSON.stringify({ ...base, state: 'REJECTED' })}\n`, base.requestId), null)
  assert.equal(safeResponse(`${JSON.stringify({ ...base, state: 'UNKNOWN' })}\n`, base.requestId), null)
  assert.ok(safeResponse(`${JSON.stringify({ ...base, owner: null })}\n`, base.requestId))
  assert.equal(safeResponse(`${JSON.stringify({ ...base, owner: 'root' })}\n`, base.requestId), null)
})

test('WebSocket frames contract stays bounded and available through the Browser tool schema', () => {
  const cliRequest = (args) => JSON.stringify({ version: 1, requestId: 'network-frames-test', method: 'network',
    deadlineAtMillis: Date.now() + 10_000, args, owner: 'ai', sessionGeneration: 2, controlGeneration: 3 })
  assert.equal(validateCliRequest('network', cliRequest({ action: 'frames', cursor: 0, limit: 20 })).method, 'network')
  for (const args of [{ action: 'frames', cursor: -1 }, { action: 'frames', limit: 21 },
    { action: 'frames', webSocketId: 'ws-0123456789abcdef0123' }]) assert.throws(() => validateCliRequest('network', cliRequest(args)))
  assert.equal(validateArgs('network', { action: 'frames', cursor: 0, limit: 20 }), '')
  for (const args of [
    { action: 'frames', cursor: -1 }, { action: 'frames', limit: 21 },
    { action: 'frames', webSocketId: 'ws-0123456789abcdef0123' },
  ]) assert.notEqual(validateArgs('network', args), '')
  const networkTool = buildTools({ defineTool: (tool) => tool }).find((tool) => tool.name === 'browser_network')
  assert.ok(networkTool.parameters.action.enum.includes('frames'))
  assert.match(networkTool.description, /HTTP HAR excludes WebSocket frames/)
  assert.ok(networkTool.parameters.action.enum.includes('export_frames'))
  assert.ok(networkTool.parameters.action.enum.includes('replay_prepare'))
  assert.ok(networkTool.parameters.action.enum.includes('replay_status'))
  assert.match(networkTool.description, /shared ServiceWorker targets are reported unsupported/)
  assert.match(networkTool.description, /never retry UNKNOWN/)

  const response = {
    version: 1, requestId: 'network-frames-test', code: 'OK', executed: false,
    sessionGeneration: 2, controlGeneration: 3, state: 'RUNNING', owner: 'ai',
    data: {
      captureId: 'cap-0123456789abcdef0123', frames: [
        { sequence: 1, webSocketId: 'ws-0123456789abcdef0123', kind: 'created', url: 'wss://example.test/socket' },
        { sequence: 2, webSocketId: 'ws-0123456789abcdef0123', kind: 'sent', timestamp: 1, opcode: 2,
          direction: 'outgoing', masked: true, payloadEncoding: 'base64', payloadBytes: 2, payload: 'AQI=' },
      ], cursor: 0, nextCursor: null, total: 2, droppedFrames: 0, eventDrops: 0, recording: false,
      includeBodies: true, payloadUnredacted: true, headersRedacted: true, redacted: false,
      payloadBytes: 2, maxFramePayloadBytes: 8192, maxCapturePayloadBytes: 1048576,
      maxFrames: 1000, maxPageBytes: 14336, loss: false, truncated: false,
    },
  }
  const encoded = (value) => `${JSON.stringify(value)}\n`
  assert.ok(safeResponse(encoded(response), response.requestId, 'network', 'frames'))
  const incomplete = structuredClone(response)
  incomplete.data.frames = [
    { sequence: 1, webSocketId: 'ws-0123456789abcdef0123', kind: 'created', metadataIncomplete: true, urlTruncated: true },
    { sequence: 2, webSocketId: 'ws-0123456789abcdef0123', kind: 'sent', metadataIncomplete: true, payloadUnavailable: true },
  ]
  incomplete.data.loss = true
  incomplete.data.truncated = true
  assert.ok(safeResponse(encoded(incomplete), incomplete.requestId, 'network', 'frames'))
  incomplete.data.frames[1].opcode = 99
  assert.equal(safeResponse(encoded(incomplete), incomplete.requestId, 'network', 'frames'), null)
  assert.ok(normalizeResponse('network', response.requestId, structuredClone(response), 'frames', { action: 'frames', cursor: 0, limit: 20 }))
  for (const mutate of [
    (value) => { value.executed = true },
    (value) => { value.data.frames[1].requestId = 'raw-cdp-id' },
    (value) => { value.data.frames[1].payload = 'x'.repeat(8193) },
    (value) => { value.data.frames[0].url = 'wss://example.test/?token=secret' },
    (value) => { value.data.frames[1].kind = 'rawCDP' },
    (value) => { value.data.loss = true },
    (value) => { value.data.frames[1].payloadEncoding = 'text' },
    (value) => { value.data.redacted = true },
    (value) => { value.data.payloadUnredacted = false },
  ]) {
    const malformed = structuredClone(response)
    mutate(malformed)
    assert.equal(safeResponse(encoded(malformed), response.requestId, 'network', 'frames'), null)
  }
  const secretHeader = structuredClone(response)
  secretHeader.data.frames = [{ sequence: 1, webSocketId: 'ws-0123456789abcdef0123', kind: 'handshakeRequest', timestamp: 1,
    requestHeaders: { 'x-csrf-token': 'leak' }, wallTime: 2 }]
  secretHeader.data.total = 1
  secretHeader.data.nextCursor = null
  assert.equal(safeResponse(encoded(secretHeader), response.requestId, 'network', 'frames'), null)
})

test('network replay prepare/status and write ticket remain generation-free in args and deny cleanly', () => {
  const cliRequest = (args) => JSON.stringify({ version: 1, requestId: 'network-replay-schema', method: 'network',
    deadlineAtMillis: Date.now() + 10_000, args, owner: 'ai', sessionGeneration: 2, controlGeneration: 3 })
  const read = { action: 'replay', entryId: 'entry-1' }
  const write = { action: 'replay', entryId: 'entry-1', riskToken: 'a'.repeat(32), captureId: `cap-${'b'.repeat(20)}` }
  for (const args of [
    { action: 'start', includeBodies: true, includeRequestBodies: true }, { action: 'export_frames' },
    { action: 'export_frames', includeBodies: true }, { action: 'export_frames', includeBodies: false },
    { action: 'replay_prepare', entryId: 'entry-1' }, { action: 'replay_status', requestId: 'a'.repeat(32) }, read, write,
  ]) {
    assert.equal(validateCliRequest('network', cliRequest(args)).method, 'network')
    assert.equal(validateArgs('network', args), '')
  }
  for (const args of [
    { action: 'export_frames', path: '/tmp/a.json' }, { action: 'export_frames', includeBodies: 'true' },
    { action: 'replay_prepare', entryId: 'entry-1', sessionGeneration: 2 },
    { action: 'replay_status', requestId: 'a'.repeat(32), controlGeneration: 3 },
    { action: 'replay', entryId: 'entry-1', riskToken: 'a'.repeat(32) },
  ]) {
    assert.throws(() => validateCliRequest('network', cliRequest(args)))
    assert.notEqual(validateArgs('network', args), '')
  }
  const status = (data) => ({ version: 1, requestId: 'a'.repeat(32), code: 'OK', executed: false,
    sessionGeneration: 2, controlGeneration: 3, state: 'RUNNING', owner: 'ai', data })
  for (const state of ['awaiting_user', 'denied']) {
    const value = status({ requestId: 'a'.repeat(32), status: state, expiresInMillis: 0 })
    assert.ok(normalizeResponse('network', value.requestId, structuredClone(value), 'replay_status', { action: 'replay_status', requestId: value.requestId }))
    assert.ok(safeResponse(`${JSON.stringify(value)}\n`, value.requestId, 'network', 'replay_status', { requestId: value.requestId }))
  }
  const approved = status({ requestId: 'a'.repeat(32), status: 'approved', riskToken: 'c'.repeat(32), entryId: 'entry-1',
    captureId: `cap-${'b'.repeat(20)}`, sessionGeneration: 2, controlGeneration: 3, expiresInMillis: 30000 })
  assert.ok(normalizeResponse('network', approved.requestId, structuredClone(approved), 'replay_status', { action: 'replay_status', requestId: approved.requestId }))
  const stale = structuredClone(approved); stale.data.controlGeneration = 4
  assert.equal(normalizeResponse('network', stale.requestId, stale, 'replay_status', { action: 'replay_status', requestId: stale.requestId }), null)
  const forgedDenied = status({ requestId: 'a'.repeat(32), status: 'denied', expiresInMillis: 0, riskToken: 'd'.repeat(32) })
  assert.equal(safeResponse(`${JSON.stringify(forgedDenied)}\n`, forgedDenied.requestId, 'network', 'replay_status', { requestId: forgedDenied.requestId }), null)
})

test('wait and navigation runtime responses keep terminal and accepted outcomes distinct', () => {
  const base = {
    version: 1, requestId: 'wait-1', executed: false, sessionGeneration: 4, controlGeneration: 9,
    state: 'RUNNING', owner: 'ai',
    data: { matched: false, condition: 'text', pageId: 'pg-0123456789abcdefabcd', navigationId: 7,
      documentGeneration: 3, url: 'https://example.test/path', loadState: 'FINISHED' }
  }
  const matched = { ...structuredClone(base), code: 'OK', executed: true, requestId: 'wait-matched',
    data: { ...base.data, matched: true } }
  assert.equal(acceptThroughCliAndAdapter('wait', matched).executed, true)
  for (const code of ['WAIT_TIMEOUT', 'NAVIGATION_CHANGED', 'NAVIGATION_FAILED', 'INVALID_SELECTOR', 'SESSION_REVOKED']) {
    const response = { ...structuredClone(base), code, requestId: `wait-${code}` }
    const accepted = acceptThroughCliAndAdapter('wait', response)
    assert.equal(accepted.code, code)
    assert.equal(accepted.executed, false)
  }
  const watchdog = { ...structuredClone(base), code: 'SESSION_REVOKED', requestId: 'wait-watchdog',
    data: { matched: false, condition: 'load', pageId: null, navigationId: null,
      documentGeneration: null, url: null, loadState: 'unknown' } }
  assert.equal(acceptThroughCliAndAdapter('wait', watchdog).code, 'SESSION_REVOKED')
  const cancelled = { ...structuredClone(base), code: 'CANCELLED', requestId: 'wait-cancelled',
    data: { ...base.data, reason: 'cancelled' } }
  assert.equal(acceptThroughCliAndAdapter('wait', cancelled).code, 'CANCELLED')

  for (const [code, executed, accepted, data] of [
    ['OK', true, true, { action: 'back', accepted: true, pageId: 'pg-0123456789abcdefabcd', navigationId: 8, url: 'https://example.test/path' }],
    ['NO_HISTORY', false, false, { action: 'forward', accepted: false, canGoBack: true, canGoForward: false }]
  ]) {
    const response = { ...structuredClone(base), requestId: `navigate-${code}`, code, executed, data }
    assert.equal(acceptThroughCliAndAdapter('navigate', response).executed, executed)
    assert.equal(response.data.accepted, accepted)
  }

  const unsanitized = { ...structuredClone(base), code: 'OK', requestId: 'wait-unsanitized',
    data: { ...base.data, matched: true, url: 'https://example.test/path?token=secret' } }
  assert.equal(normalizeResponse('wait', unsanitized.requestId, unsanitized, 'text'), null)
})

test('locate runtime responses admit only a unique current reference and non-actionable miss descriptions', () => {
  const base = {
    version: 1, requestId: 'locate-1', executed: true, sessionGeneration: 4, controlGeneration: 9,
    state: 'RUNNING', owner: 'ai',
    data: { ref: 'b:4:9:3:7:0123456789abcdef0123456789abcdef:0', count: 1, strategy: 'role',
      pageId: 'pg-0123456789abcdefabcd', documentGeneration: 3, observationId: 7 }
  }
  const success = { ...structuredClone(base), code: 'OK' }
  assert.equal(acceptThroughCliAndAdapter('locate', success).executed, true)

  for (const [code, data] of [
    ['NOT_FOUND', { count: 0 }],
    ['AMBIGUOUS', { count: 2, matches: [{ tag: 'button', role: 'button', name: 'Continue' }] }],
    ['LOCATE_LIMIT', { count: 5001 }],
    ['INVALID_SELECTOR', { count: 0 }],
    ['STALE_REFERENCE', {}],
    ['STALE_REFERENCE', { count: 0 }],
    ['EXECUTION_FAILED', { count: 0 }],
  ]) {
    const response = { ...structuredClone(base), requestId: `locate-${code}-${JSON.stringify(data).length}`,
      code, executed: false, data }
    assert.equal(acceptThroughCliAndAdapter('locate', response).code, code)
  }
  for (const malformed of [
    { ...success, data: { ...base.data, ref: 'n1' } },
    { ...success, data: { ...base.data, strategy: 'css' } },
    { ...success, data: { ...base.data, count: 2 } },
    { ...success, data: { ...base.data, observationId: 0 } },
    { ...success, data: { ...base.data, pageId: null } },
    { ...success, data: { ...base.data, ref: 'b:4:9:3:7:0123456789abcdef0123456789abcdef:0', extra: 'forbidden' } },
    { ...success, code: 'AMBIGUOUS', executed: false,
      data: { count: 2, matches: [{ tag: 'button', role: 'button', name: 'Continue', ref: base.data.ref }] } },
  ]) {
    const result = { ...malformed, requestId: 'locate-malformed' }
    assert.equal(normalizeResponse('locate', result.requestId, result, 'role'), null)
    assert.equal(safeResponse(`${JSON.stringify(result)}\n`, result.requestId, 'locate', 'role'), null)
  }
})
