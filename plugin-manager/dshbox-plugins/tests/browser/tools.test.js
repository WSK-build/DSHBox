'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const net = require('node:net')
const { execFile: nodeExecFile } = require('node:child_process')
const path = require('node:path')
const { createRequire } = require('node:module')
const { pathToFileURL } = require('node:url')
const { buildTools } = require('../../assets/plugins/browser-use/plugin/lib/tools')
const { browserRequest, resolveScreenshotPath, resolveArtifactPath, safeResponse, validateArgs } = require('../../assets/plugins/browser-use/plugin/lib/client')
const { normalizeResponse, validateRequest: validateCliRequest } = require('../../../../browser/browser-bridge/src/main/assets/browser/lib/browser-cli.cjs')

function fixture() {
  const calls = []
  const responses = []
  const tools = buildTools({
    defineTool: (tool) => tool,
    dependencies: {
      now: () => 1000,
      requestId: () => `request-${calls.length + 1}`,
      execFile: (file, argv, options, callback) => {
        calls.push({ file, argv, options })
        const request = JSON.parse(argv[2])
        validateCliRequest(request.method, argv[2], 1000)
        const next = responses.shift()
        if (next instanceof Error) callback(next, '')
        else callback(null, `${JSON.stringify(next && next.requestId === '$request' ? { ...next, requestId: request.requestId } : next)}\n`)
      }
    }
  })
  return { tools, calls, responses }
}

function response(request, data = {}, state = 'RUNNING') {
  return {
    version: 1,
    requestId: '$request',
    code: 'OK',
    executed: request.method === 'status' ? false : true,
    sessionGeneration: 4,
    controlGeneration: 9,
    state,
    data
  }
}

function parseCall(call) {
  assert.equal(call.file, '/opt/browser/bin/browser')
  assert.equal(call.argv[1], '--request-json')
  return JSON.parse(call.argv[2])
}

test('rendering request and response contracts agree across CLI and Cordis', () => {
  const context = { owner: 'ai', sessionGeneration: 4, controlGeneration: 9 }
  const settings = { userAgentMode: 'default', layoutWidthMode: 'device', overviewMode: false, textScalePercent: 110, revision: 1 }
  const setArgs = { action: 'rendering', operation: 'set', key: 'textScalePercent', value: 110, expectedRevision: 0 }
  const getArgs = { action: 'rendering', operation: 'get' }
  const parse = args => validateCliRequest('debug', JSON.stringify({ version: 1, requestId: 'render-test', method: 'debug',
    deadlineAtMillis: 10000, args, ...context }), 1000)
  for (const args of [getArgs, setArgs]) {
    assert.equal(validateArgs('debug', args), '')
    assert.equal(parse(args).method, 'debug')
  }
  for (const bad of [{ ...getArgs, value: 1 }, { ...setArgs, expectedRevision: 1.5 },
    { ...setArgs, key: 'revision' }, { ...setArgs, value: '110' }, { ...setArgs, value: 201 },
    { ...setArgs, targetId: 'foreign' }, { ...setArgs, operation: 'reset' }]) {
    assert.notEqual(validateArgs('debug', bad), '')
    assert.throws(() => parse(bad))
  }
  const check = (code, executed, data, args, accepted) => {
    const value = { version: 1, requestId: 'render-test', code, executed, sessionGeneration: 4,
      controlGeneration: 9, state: 'RUNNING', owner: 'ai', data }
    assert.equal(!!normalizeResponse('debug', 'render-test', value, 'rendering', args), accepted)
    assert.equal(!!safeResponse(JSON.stringify(value), 'render-test', 'debug', 'rendering', args), accepted)
  }
  const data = { settings, changed: true, reloadRequired: true, invalidatesVisualReferences: true, persisted: true }
  check('OK', true, data, setArgs, true)
  check('OK', false, { ...data, changed: false, reloadRequired: false, invalidatesVisualReferences: false, persisted: false }, getArgs, true)
  check('OK', true, { ...data, settings: { ...settings, revision: 2 } }, setArgs, false)
  check('OK', true, { ...data, settings: { ...settings, secret: 'forbidden' } }, setArgs, false)
  check('OK', true, { ...data, reloadRequired: false }, setArgs, false)
  check('STALE_REVISION', false, { ...data, changed: false, reloadRequired: false, invalidatesVisualReferences: false, persisted: false }, setArgs, true)
  check('STALE_REVISION', false, { ...data, settings: { ...settings, revision: 0 }, changed: false,
    reloadRequired: false, invalidatesVisualReferences: false, persisted: false }, setArgs, false)
  check('SETTINGS_PERSISTENCE_FAILED', null, { ...data, persisted: false }, setArgs, true)
  check('SETTINGS_PERSISTENCE_FAILED', null, { ...data, changed: false, reloadRequired: false,
    invalidatesVisualReferences: false, persisted: false }, setArgs, true)
  check('SETTINGS_PERSISTENCE_FAILED', false, { ...data, persisted: false }, setArgs, false)
})

test('cancelled event wait returns a real stage and cause without asserting action outcome', () => {
  const args = { action: 'wait', subscriptionId: 'a'.repeat(32), timeoutMs: 1000 }
  const data = { subscriptionId: args.subscriptionId, matched: false, stage: 'event_wait', reason: 'user_cancelled',
    nextAction: 'read_status_and_rearm', automaticRetryAllowed: false }
  const check = (candidate, accepted) => {
    const value = { version: 1, requestId: 'event-cancel', code: 'CANCELLED', executed: false,
      sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai', data: candidate }
    assert.equal(!!normalizeResponse('event', 'event-cancel', value, 'wait', args), accepted)
    assert.equal(!!safeResponse(JSON.stringify(value), 'event-cancel', 'event', 'wait', args), accepted)
  }
  for (const reason of ['user_cancelled', 'session_changed', 'page_changed', 'task_changed', 'action_invalidated', 'subscription_removed']) {
    check({ ...data, reason }, true)
  }
  check({ ...data, stage: 'action_dispatch' }, false)
  check({ ...data, reason: 'made_up' }, false)
  check({ ...data, automaticRetryAllowed: true }, false)
  check({ ...data, subscriptionId: 'b'.repeat(32) }, false)
})

test('condition wait cancellation distinguishes explicit cancel from context revocation', () => {
  const snapshot = { matched: false, condition: 'load', pageId: 'pg-' + 'a'.repeat(20), navigationId: 1,
    documentGeneration: 2, url: 'https://example.test/', loadState: 'LOADING', stage: 'condition_wait',
    nextAction: 'read_status_and_observe', automaticRetryAllowed: false }
  for (const code of ['CANCELLED', 'SESSION_REVOKED']) {
    const data = { ...snapshot, cancellationReason: code === 'CANCELLED' ? 'user_cancelled' : 'context_changed',
      ...(code === 'CANCELLED' ? { reason: 'cancelled' } : {}) }
    const check = (candidate, accepted) => {
      const value = { version: 1, requestId: 'condition-cancel', code, executed: false,
        sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai', data: candidate }
      assert.equal(!!normalizeResponse('wait', 'condition-cancel', value, 'load', {}), accepted)
      assert.equal(!!safeResponse(JSON.stringify(value), 'condition-cancel', 'wait', 'load', {}), accepted)
    }
    check(data, true)
    check({ ...data, cancellationReason: 'unknown_fake' }, false)
    check({ ...data, stage: 'action_dispatch' }, false)
    check({ ...data, automaticRetryAllowed: true }, false)
    const missing = { ...data }; delete missing.nextAction; check(missing, false)
  }
})

test('Cordis accepts only a strict cancellation receipt and never as a wait terminal', () => {
  const receipt = {
    version: 1, requestId: 'cancel-receipt', code: 'CANCELLED', executed: null,
    sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai',
    data: {
      receiptKind: 'cancel_request', cancelStage: 'event_wait', reason: 'cancel_accepted',
      actionOutcome: 'unknown', nextAction: 'read_original_request_result',
      automaticRetryAllowed: false,
    },
  }
  assert.ok(safeResponse(`${JSON.stringify(receipt)}\n`, receipt.requestId, 'cancel'))
  assert.equal(safeResponse(`${JSON.stringify(receipt)}\n`, receipt.requestId, 'wait', 'load'), null)
  for (const mutate of [
    value => { value.executed = false },
    value => { delete value.data.receiptKind },
    value => { value.data.cancelStage = 'action_dispatch' },
    value => { value.data.reason = 'made_up' },
    value => { value.data.actionOutcome = 'not_dispatched' },
    value => { value.data.automaticRetryAllowed = true },
    value => { value.data.extra = true },
  ]) {
    const invalid = structuredClone(receipt)
    mutate(invalid)
    assert.equal(safeResponse(`${JSON.stringify(invalid)}\n`, receipt.requestId, 'cancel'), null)
  }

  const queuedTerminal = {
    version: 1, requestId: 'queued-terminal', code: 'CANCELLED', executed: false,
    sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai',
    data: { cancelStage: 'queued', reason: 'cancel_before_dispatch',
      actionOutcome: 'not_dispatched', automaticRetryAllowed: false },
  }
  assert.ok(safeResponse(`${JSON.stringify(queuedTerminal)}\n`, queuedTerminal.requestId, 'click'))
  assert.equal(safeResponse(`${JSON.stringify(queuedTerminal)}\n`, queuedTerminal.requestId, 'cancel'), null)
})

test('AX producer sample is accepted by both strict consumers and corrupted scopes are rejected', () => {
  const producerTest = fs.readFileSync(path.resolve(__dirname,
    '../../../../browser/browser-devtools/src/test/java/com/dshbox/browser/devtools/BrowserAxTreeObserverTest.kt'), 'utf8')
  const fixture = producerTest.match(/private const val PRODUCTION_SAMPLE_JSON = """([\s\S]*?)"""/)
  assert.ok(fixture, 'JVM producer test must retain its exact production sample')
  const data = JSON.parse(fixture[1])
  const args = { action: 'ax_tree', maxDepth: 8 }
  const check = (candidate, accepted) => {
    const value = { version: 1, requestId: 'ax-producer', code: 'OK', executed: false,
      sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai', data: candidate }
    assert.equal(!!normalizeResponse('debug', 'ax-producer', value, 'ax_tree', args), accepted)
    assert.equal(!!safeResponse(JSON.stringify(value), 'ax-producer', 'debug', 'ax_tree', args), accepted)
  }
  check(data, true)
  check({ ...data, fullPageAxCovered: true }, false)
  check({ ...data, nodes: [{ ...data.nodes[0], backendDOMNodeId: 123 }] }, false)
  check({ ...data, nodes: [{ ...data.nodes[0], frameId: 'unseen' }] }, false)
  check({ ...data, uniqueAxRefs: 2 }, false)
  check({ ...data, nodes: [{ ...data.nodes[0], childAxRefs: ['ax:main:missing'] }] }, false)
  check({ ...data, commandCount: 13 }, false)
})

test('typed browser_rendering tool maps exactly one policy and preserves AI credentials', async () => {
  const f = fixture()
  const tool = f.tools.find(item => item.name === 'browser_rendering')
  const args = { sessionGeneration: 4, controlGeneration: 9, operation: 'set', key: 'overviewMode', overviewMode: true, expectedRevision: 0 }
  f.responses.push({ version: 1, requestId: '$request', code: 'OK', executed: true, sessionGeneration: 4,
    controlGeneration: 9, state: 'RUNNING', owner: 'ai', data: {
      settings: { userAgentMode: 'default', layoutWidthMode: 'device', overviewMode: true, textScalePercent: 100, revision: 1 },
      changed: true, reloadRequired: true, invalidatesVisualReferences: true, persisted: true
    } })
  assert.equal((await tool.execute(args)).code, 'OK')
  const call = parseCall(f.calls[0])
  assert.deepEqual(call.args, { action: 'rendering', operation: 'set', key: 'overviewMode', value: true, expectedRevision: 0 })
  assert.equal(call.owner, 'ai')
  for (const bad of [{ ...args, textScalePercent: 120 }, { ...args, value: true },
    { ...args, operation: 'get' }, { ...args, key: 'textScalePercent' }]) {
    assert.equal((await tool.execute(bad)).code, 'INVALID_ARGUMENT')
  }
  assert.equal(f.calls.length, 1)
})

test('CLI and Cordis preserve navigation timing and reject partial or contradictory commit metadata', () => {
  const data = {urlBefore: 'https://old.test/', urlBeforeQueryRedacted: false, urlBeforeFragmentRedacted: false,
    requestedUrl: 'https://new.test/path', requestedUrlQueryRedacted: true, requestedUrlFragmentRedacted: true,
    urlAfter: null, urlAfterQueryRedacted: false, urlAfterFragmentRedacted: false, committed: false};
  for (const method of ['open', 'observe', 'navigate', 'wait']) {
    const action = method === 'wait' ? 'load' : 'reload';
    const base = method === 'navigate' ? {action: 'reload', accepted: true, pageId: 'pg-0123456789abcdefabcd', navigationId: 2,
      url: 'https://new.test/path'} : method === 'wait' ? {matched: true, condition: 'load',
        pageId: 'pg-0123456789abcdefabcd', navigationId: 2, documentGeneration: 3, url: 'https://new.test/path', loadState: 'FINISHED'} : {};
    const frame = {version: 1, requestId: 'nav-request', code: 'OK', executed: true,
      sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai', data: {...base, ...data}};
    const check = value => {
      value = {...value, data: {...base, ...value.data}};
      const cli = normalizeResponse(method, 'nav-request', value, action, {});
      const cordis = safeResponse(JSON.stringify(value), 'nav-request', method, action, {});
      assert.equal(Boolean(cli), Boolean(cordis));
      return Boolean(cli);
    };
    assert.equal(check(frame), true);
    assert.equal(check({...frame, data: {...data, committed: true}}), false);
    assert.equal(check({...frame, data: {...data, urlAfter: 'https://new.test/path'}}), false);
    assert.equal(check({...frame, data: {...data, committed: true, urlAfter: 'https://redirect.test/end'}}), true);
    const incomplete = {...data}; delete incomplete.urlBeforeQueryRedacted;
    assert.equal(check({...frame, data: incomplete}), false);
    assert.equal(check({...frame, data: {...data, urlBefore: 'https://user:pass@old.test/'}}), false);
    assert.equal(check({...frame, data: {}}), true);
  }
});

test('AX requests stay read-only, bounded and cannot select other targets', async () => {
  const f = fixture();
  const args = {sessionGeneration: 4, controlGeneration: 9, action: 'ax_tree', maxNodes: 64, maxDepth: 4, includeIgnored: false};
  f.responses.push({...response({method: 'debug'}), code: 'UNSUPPORTED', executed: false});
  const result = await f.tools[13].execute(args);
  assert.equal(result.code, 'UNSUPPORTED');
  assert.deepEqual(parseCall(f.calls[0]).args, {action: 'ax_tree', maxNodes: 64, maxDepth: 4, includeIgnored: false});
  for (const invalid of [{...args, maxNodes: 257}, {...args, maxDepth: '4'}, {...args, includeIgnored: 1},
    {...args, targetId: 'other'}, {...args, frameId: 'other'}]) {
    assert.equal((await f.tools[13].execute(invalid)).code, 'INVALID_ARGUMENT');
  }
  assert.equal(f.calls.length, 1);
});

test('published DSH SDK accepts task metadata edit and delete actions with legacy reads', async () => {
  const fixtureRoot = path.resolve(__dirname, '../../../../../tools/browser-p0/dsh-api-contract')
  const fixtureRequire = createRequire(path.join(fixtureRoot, 'package.json'))
  const toolsEntry = fixtureRequire.resolve('@deepseek-ai/dsh-tools')
  const cordisEntry = fixtureRequire.resolve('@deepseek-ai/cordis')
  const [toolsApi, cordis] = await Promise.all([
    import(pathToFileURL(toolsEntry).href), import(pathToFileURL(cordisEntry).href),
  ])
  const calls = []
  const browserTools = buildTools({
    defineTool: toolsApi.defineTool,
    dependencies: {
      now: () => 1000,
      requestId: () => `task-${calls.length + 1}`,
      execFile: (_file, argv, _options, callback) => {
        const request = JSON.parse(argv[2])
        calls.push(request)
        const data = request.method === 'control'
          ? { approvalId: 'a'.repeat(32), status: 'PENDING', summary: request.args.summary,
            taskId: request.args.taskId ?? null, createdAtMillis: 1000, expiresAtMillis: 121000, coalesced: false }
          : request.method === 'task' && request.args.action === 'list'
          ? { tasks: [], totalCount: 0, nextOffset: null }
          : request.method === 'upload' && request.args.action === 'activate'
            ? { approvalId: request.args.approvalId, state: 'chooser_pending' }
          : { task: { taskId: 'task_01' } }
        callback(null, `${JSON.stringify({ version: 1, requestId: request.requestId,
          code: 'OK', executed: request.method !== 'control',
          sessionGeneration: null, controlGeneration: null, state: 'IDLE', data })}\n`)
      },
    },
  })
  const ctx = new cordis.Context()
  ctx.systemPrompt = { tools() {}, section() {} }
  const runtime = new toolsApi.ToolRuntime(ctx)
  for (const tool of browserTools) ctx.tools.register(tool)
  for (const [callId, args] of [
    ['update', { action: 'update', taskId: 'task_01', expectedRevision: 3, goal: 'Updated goal' }],
    ['delete', { action: 'delete', taskId: 'task_01', expectedRevision: 4 }],
    ['status', { action: 'status', taskId: 'task_01' }],
    ['list', { action: 'list' }],
  ]) {
    await runtime.execute({ callId: `task-${callId}`, name: 'browser_task', arguments: args,
      signal: new AbortController().signal, agent: { id: 'sdk-task-session' } })
  }
  await runtime.execute({ callId: 'task-upload-activate', name: 'browser_upload', arguments: {
    action: 'activate', approvalId: 'a'.repeat(32), nodeId: `b:4:9:3:1:${'b'.repeat(32)}:0`,
    sessionGeneration: 4, controlGeneration: 9, taskId: 'task_01'
  }, signal: new AbortController().signal, agent: { id: 'sdk-task-session' } })
  await runtime.execute({ callId: 'control-request', name: 'browser_control', arguments: {
    action: 'request', summary: 'Need a human-approved handoff', taskId: 'task_01'
  }, signal: new AbortController().signal, agent: { id: 'sdk-task-session' } })
  assert.equal(calls.at(-2).method, 'upload')
  assert.equal(calls.at(-1).method, 'control')
  assert.deepEqual(calls.at(-2).args, { action: 'activate', approvalId: 'a'.repeat(32), nodeId: `b:4:9:3:1:${'b'.repeat(32)}:0` })
  assert.deepEqual(calls.at(-1).args, { action: 'request', summary: 'Need a human-approved handoff', taskId: 'task_01' })
  assert.deepEqual(calls.map(({ args }) => args.action), ['update', 'delete', 'status', 'list', 'activate', 'request'])
  assert.deepEqual(calls[0].args, { action: 'update', taskId: 'task_01', expectedRevision: 3, goal: 'Updated goal' })
  assert.deepEqual(calls[1].args, { action: 'delete', taskId: 'task_01', expectedRevision: 4 })
  assert.equal(validateArgs('task', { action: 'update', taskId: 'task_01', expectedRevision: 0, goal: 'Initial revision' }), '')
  assert.equal(validateArgs('task', { action: 'delete', taskId: 'task_01', expectedRevision: 0 }), '')
  assert.equal(validateArgs('task', { action: 'fail', taskId: 'task_01', reason: 'navigation failed' }), '')
  assert.equal(validateArgs('task', { action: 'fail', taskId: 'task_01' }), '')
  assert.notEqual(validateArgs('task', { action: 'fail', taskId: 'task_01', reason: '   ' }), '')
  assert.notEqual(validateArgs('task', { action: 'fail', taskId: 'task_01', reason: 'x'.repeat(1001) }), '')
  assert.notEqual(validateArgs('task', { action: 'wait', taskId: 'task_01', reason: 'free text' }), '')
  assert.match(validateArgs('task', { action: 'reconcile', taskId: 'task_01', expectedRevision: 0 }), /does not accept expectedRevision/)
  assert.notEqual(validateArgs('task', { action: 'update', taskId: 'task_01', expectedRevision: -1, goal: 'Invalid revision' }), '')
  assert.notEqual(validateArgs('task', { action: 'delete', taskId: 'task_01', expectedRevision: -1 }), '')
  assert.equal(validateArgs('task', { action: 'update', taskId: 'task_01', expectedRevision: 1 }), 'task update fields are invalid')
  assert.equal(validateArgs('task', { action: 'delete', taskId: 'task_01' }), 'task delete fields are invalid')
})

test('unknown results keep unavailable state and generations unavailable', () => {
  const unknown = { version: 1, requestId: 'request-unknown', code: 'UNKNOWN', executed: null,
    sessionGeneration: null, controlGeneration: null, state: null, owner: null, data: {} }
  assert.deepEqual(safeResponse(`${JSON.stringify(unknown)}\n`, 'request-unknown', 'screenshot'), unknown)
  assert.equal(safeResponse(`${JSON.stringify({ ...unknown, state: 'CLOSED' })}\n`, 'request-unknown', 'screenshot').state, 'CLOSED')
  assert.equal(safeResponse(`${JSON.stringify({ ...unknown, code: 'CANCELLED', executed: false, state: null })}\n`,
    'request-unknown', 'screenshot'), null)
})

test('observe and obscured type results preserve only the agreed bounded metadata', () => {
  const obscured = { version: 1, requestId: 'obscured', code: 'OBSCURED', executed: false,
    sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai',
    data: { obscuredBy: { tag: 'div', id: 'dialog-shade', className: 'shade active' } } }
  const cliResult = normalizeResponse('type', 'obscured', obscured, '', {})
  assert.deepEqual(cliResult.data.obscuredBy, obscured.data.obscuredBy)
  assert.deepEqual(safeResponse(`${JSON.stringify(cliResult)}\n`, 'obscured', 'type').data, obscured.data)
  assert.equal(normalizeResponse('type', 'obscured', { ...obscured,
    data: { obscuredBy: { ...obscured.data.obscuredBy, nodeId: 'fabricated' } } }), null)
  assert.equal(safeResponse(`${JSON.stringify({ ...obscured,
    data: { obscuredBy: { ...obscured.data.obscuredBy, nodeId: 'fabricated' } } })}\n`, 'obscured', 'type'), null)
  assert.equal(safeResponse(`${JSON.stringify({ ...obscured, untrusted: 'extra' })}\n`, 'obscured', 'type'), null)

  const failureStages = { BACKGROUND_UNAVAILABLE: 'surface_attach', WINDOW_NOT_ACTIVE: 'surface_attach',
    CLIPPED_VIEWPORT: 'surface_attach', NO_FRAME: 'pixel_read', PIXEL_READ_FAILED: 'pixel_read',
    VIEWPORT_UNAVAILABLE: 'surface_attach', TARGET_UNAVAILABLE: 'target_attach', AMBIGUOUS_TARGET: 'target_attach',
    CDP_UNSUPPORTED: 'pixel_read', CDP_TIMEOUT: 'pixel_read', PNG_INVALID: 'decode', PNG_TOO_LARGE: 'decode',
    JOURNAL_UNAVAILABLE: 'journal', DEADLINE_EXPIRED: 'pixel_read', IDENTITY_CHANGED: 'identity_match',
    ENCODE_FAILED: 'encode', STORAGE_FAILED: 'storage', SCREENSHOT_QUOTA_EXCEEDED: 'storage' }
  for (const [code, cancelStage] of Object.entries(failureStages)) {
    const responseValue = { version: 1, requestId: `screenshot-${code}`, code, executed: false,
      sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai',
      data: { cancelStage, reason: code } }
    const normalized = normalizeResponse('screenshot', responseValue.requestId, responseValue)
    assert.equal(safeResponse(`${JSON.stringify(normalized)}\n`, responseValue.requestId, 'screenshot').code, code)
    assert.equal(safeResponse(`${JSON.stringify({ ...normalized, data: { cancelStage: 'wrong', reason: code } })}\n`,
      responseValue.requestId, 'screenshot'), null)
    assert.equal(safeResponse(`${JSON.stringify({ ...normalized, untrusted: true })}\n`, responseValue.requestId, 'screenshot'), null)
  }
  const preDispatchDeadline = { version: 1, requestId: 'deadline-before-dispatch', code: 'DEADLINE_EXPIRED',
    executed: false, sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai', data: {} }
  assert.ok(safeResponse(`${JSON.stringify(preDispatchDeadline)}\n`, preDispatchDeadline.requestId, 'screenshot'))
  assert.equal(safeResponse(`${JSON.stringify({ ...preDispatchDeadline, data: { cancelStage: 'pixel_read', reason: 'DEADLINE_EXPIRED' } })}\n`,
    preDispatchDeadline.requestId, 'screenshot').code, 'DEADLINE_EXPIRED')
  assert.equal(safeResponse(`${JSON.stringify({ ...preDispatchDeadline,
    data: { cancelStage: 'surface_attach', reason: 'DEADLINE_EXPIRED' } })}\n`, preDispatchDeadline.requestId, 'screenshot'), null)
})

test('task list pagination is bounded and validates complete persisted records', () => {
  const args = { action: 'list', offset: 10, limit: 20 }
  assert.equal(validateArgs('task', args), '')
  assert.notEqual(validateArgs('task', { action: 'list', offset: -1 }), '')
  assert.notEqual(validateArgs('task', { action: 'list', limit: 101 }), '')
  const task = { taskId: 'task_01', revision: 3, status: 'paused', deadlineElapsedMillis: 100000,
    operationLimit: 500, operationsUsed: 10, remainingOperations: 490, waitReason: null,
    title: 'A title', goal: 'A goal', notes: 'A note', createdAtWallMillis: 1000, updatedAtWallMillis: 2000,
    canResume: true, canDelete: false }
  const base = { version: 1, requestId: 'request-list', code: 'OK', executed: false, sessionGeneration: null,
    controlGeneration: null, state: 'IDLE', owner: null, data: { tasks: [task], totalCount: 31, nextOffset: 11 } }
  assert.ok(safeResponse(`${JSON.stringify(base)}\n`, 'request-list', 'task', 'list', args))
  const withFailureReason = failureReason => ({ ...base, data: { ...base.data,
    tasks: [{ ...task, status: 'failed', failureReason }] } })
  for (const reason of [null, '网站拒绝提交', 'x'.repeat(1000)]) {
    assert.ok(safeResponse(`${JSON.stringify(withFailureReason(reason))}\n`, 'request-list', 'task', 'list', args))
  }
  for (const reason of ['', '  ', 4, 'x'.repeat(1001)]) {
    assert.equal(safeResponse(`${JSON.stringify(withFailureReason(reason))}\n`, 'request-list', 'task', 'list', args), null)
  }
  assert.equal(safeResponse(`${JSON.stringify({ ...base, data: { ...base.data,
    tasks: [{ ...task, failureReason: 'Not failed' }] } })}\n`, 'request-list', 'task', 'list', args), null)
  const initialTask = { ...task, revision: 0 }
  assert.ok(safeResponse(`${JSON.stringify({ ...base, data: { ...base.data, tasks: [initialTask] } })}\n`,
    'request-list', 'task', 'list', args))
  assert.equal(safeResponse(`${JSON.stringify({ ...base, data: { ...base.data, tasks: [{ ...task, revision: -1 }] } })}\n`,
    'request-list', 'task', 'list', args), null)
  assert.equal(safeResponse(`${JSON.stringify({ ...base, data: { ...base.data, nextOffset: 12 } })}\n`,
    'request-list', 'task', 'list', args), null)
})

test('registers the Browser tools and invokes fixed CLI without a shell', async () => {
  const f = fixture()
  assert.deepEqual(f.tools.map((tool) => tool.name), [
    'browser_status', 'browser_task', 'browser_open', 'browser_observe', 'browser_wait', 'browser_navigate', 'browser_collect',
    'browser_locate', 'browser_click', 'browser_type', 'browser_scroll', 'browser_screenshot', 'browser_close',
    'browser_debug', 'browser_network', 'browser_selection', 'browser_page', 'browser_serve', 'browser_file', 'browser_upload', 'browser_event', 'browser_control', 'browser_rendering', 'browser_ax'
  ])
  f.tools[0].execute()
  const request = parseCall(f.calls[0])
  assert.equal(request.version, 1)
  assert.equal(request.method, 'status')
  assert.equal(request.deadlineAtMillis, 6000)
  assert.deepEqual(request.args, {})
  assert.equal(f.calls[0].options.shell, undefined)
})

test('native Browser tools keep taskId in the request envelope across method paths', async () => {
  const calls = []
  const tools = buildTools({
    defineTool: (tool) => tool,
    dependencies: {
      execFile: (_file, argv, _options, callback) => {
        calls.push(JSON.parse(argv[2]))
        const error = new Error('fixture runtime unavailable')
        error.code = 'ENOENT'
        callback(error, '')
      }
    }
  })
  const session = { sessionGeneration: 4, controlGeneration: 9, taskId: 'task_A-1' }
  const inputs = {
    browser_status: { taskId: session.taskId },
    browser_observe: { ...session },
    browser_wait: { sessionGeneration: 4, taskId: session.taskId, condition: 'load' },
    browser_navigate: { ...session, action: 'reload' },
    browser_event: { ...session, action: 'arm', eventKinds: ['navigation_finished'] },
    browser_collect: { ...session, action: 'window', containerSelector: '#list', messageSelector: 'article', keyAttribute: 'data-id', declaredRange: 'latest', maxRecords: 10, maxContentChars: 100 },
    browser_locate: { ...session, strategy: 'text', value: 'Continue', exact: true },
    browser_click: { ...session, nodeId: 'b:4:9:3:2:0123456789abcdef0123456789abcdef:0' },
    browser_type: { ...session, nodeId: 'b:4:9:3:2:0123456789abcdef0123456789abcdef:0', text: 'x' },
    browser_scroll: { ...session, direction: 'down' },
    browser_screenshot: { ...session },
    browser_close: { ...session },
    browser_debug: { ...session, action: 'status' },
    browser_network: { ...session, action: 'stop' },
    browser_selection: { sessionGeneration: 4, taskId: session.taskId, action: 'list' },
    browser_page: { action: 'list', sessionGeneration: 4, taskId: session.taskId },
    browser_file: { action: 'list', sessionGeneration: 4, taskId: session.taskId },
    browser_upload: { ...session, action: 'request', paths: ['reports/report.pdf'] }
  }
  for (const [name, args] of Object.entries(inputs)) {
    const tool = tools.find((item) => item.name === name)
    assert.ok(tool.parameters.taskId, `${name} exposes optional taskId`)
    await tool.execute(args)
  }
  assert.deepEqual(calls.map((request) => request.method), [
    'status', 'observe', 'wait', 'navigate', 'event', 'collect', 'locate', 'click', 'type', 'scroll', 'screenshot',
    'close', 'debug', 'network', 'selection', 'page', 'file', 'upload'
  ])
  for (const request of calls) {
    assert.equal(request.taskId, session.taskId, request.method)
    assert.equal(Object.hasOwn(request.args, 'taskId'), false, request.method)
  }
  const taskTool = tools.find((item) => item.name === 'browser_task')
  assert.equal(taskTool.parameters.taskId.pattern, undefined)
})

test('browser_event arms, correlates action credentials, and returns scoped observations without retry', async () => {
  const f = fixture()
  const session = { sessionGeneration: 4, controlGeneration: 9, taskId: 'task_A-1' }
  const subscriptionId = 'a'.repeat(32)
  const nonce = 'b'.repeat(64)
  f.responses.push(response({ method: 'event' }, {
    subscriptionId, nonce, expiresAtElapsedMillis: 20000,
    identity: { sessionGeneration: 4, controlGeneration: 9, pageId: 'pg-0123456789abcdefabcd', documentGeneration: 2, navigationId: 3 }
  }))
  const event = f.tools.find((tool) => tool.name === 'browser_event')
  const armed = await event.execute({ ...session, action: 'arm', eventKinds: ['navigation_finished'] })
  assert.match(armed.dataJson, new RegExp(subscriptionId))
  const armRequest = parseCall(f.calls[0])
  assert.equal(armRequest.method, 'event')
  assert.equal(armRequest.taskId, session.taskId)
  assert.equal(Object.hasOwn(armRequest.args, 'taskId'), false)

  f.responses.push(response({ method: 'click' }, {}))
  await f.tools.find((tool) => tool.name === 'browser_click').execute({
    ...session, nodeId: 'node_1', eventSubscriptionId: subscriptionId, eventNonce: nonce
  })
  const clickRequest = parseCall(f.calls[1])
  assert.equal(clickRequest.method, 'click')
  assert.equal(clickRequest.taskId, session.taskId)
  assert.equal(clickRequest.args.eventSubscriptionId, subscriptionId)
  assert.equal(clickRequest.args.eventNonce, nonce)

  f.responses.push(response({ method: 'event' }, {
    eventId: 'c'.repeat(32), subscriptionId, actionRequestId: 'request-2', eventKind: 'navigation_finished',
    attribution: 'action_scoped_observation', pageId: 'pg-0123456789abcdefabcd', documentGeneration: 2, navigationId: 4,
    url: 'https://example.test/'
  }))
  const waited = await event.execute({ ...session, action: 'wait', subscriptionId, timeoutMs: 5000 })
  const observation = JSON.parse(waited.dataJson)
  assert.equal(observation.attribution, 'action_scoped_observation')
  assert.equal(parseCall(f.calls[2]).taskId, session.taskId)
  assert.equal(parseCall(f.calls[2]).args.subscriptionId, subscriptionId)

  const callsBeforeInvalid = f.calls.length
  const invalid = await event.execute({ ...session, taskId: undefined, action: 'arm', eventKinds: ['navigation_finished'] })
  assert.equal(invalid.code, 'INVALID_ARGUMENT')
  assert.equal(f.calls.length, callsBeforeInvalid)

  f.responses.push({ ...response({ method: 'event' }, { diagnostic: 'may-have-dispatched' }), code: 'UNKNOWN', executed: null })
  const beforeUnknown = f.calls.length
  const unknown = await event.execute({ ...session, action: 'arm', eventKinds: ['download'] })
  assert.equal(unknown.code, 'UNKNOWN')
  assert.equal(unknown.executed, 'unknown')
  assert.equal(f.calls.length, beforeUnknown + 1, 'uncertain event action is never retried')
})

test('event argument validation rejects duplicates, malformed ids, unpaired credentials, and prompt misuse', () => {
  assert.equal(validateArgs('event', { action: 'arm', eventKinds: ['download', 'download'] }) !== '', true)
  assert.equal(validateArgs('event', { action: 'arm', eventKinds: ['unknown'] }) !== '', true)
  assert.equal(validateArgs('event', { action: 'wait', subscriptionId: 'A'.repeat(32) }) !== '', true)
  assert.equal(validateArgs('event', { action: 'resolve_dialog', eventId: 'a'.repeat(32), decision: 'dismiss', promptText: 'x' }) !== '', true)
  assert.equal(validateArgs('navigate', { action: 'reload', eventSubscriptionId: 'a'.repeat(32) }) !== '', true)
  assert.equal(validateArgs('click', { nodeId: 'node_1', eventSubscriptionId: 'a'.repeat(32), eventNonce: 'b'.repeat(64) }), '')
})

test('event wait strictly validates optional managed page and download associations', () => {
  const base = { version: 1, requestId: 'req_01', code: 'OK', executed: true, sessionGeneration: 4,
    controlGeneration: 9, state: 'RUNNING', owner: 'ai' }
  const args = { subscriptionId: 'a'.repeat(32) }
  const receipt = { eventId: 'c'.repeat(32), subscriptionId: args.subscriptionId, actionRequestId: 'request-2',
    eventKind: 'new_window', attribution: 'action_scoped_observation', pageId: 'pg-0123456789abcdefabcd',
    documentGeneration: 2, navigationId: 4, newPageId: 'pg-0123456789abcdefabcd' }
  const wire = (data) => JSON.stringify({ ...base, data })
  assert.equal(safeResponse(wire(receipt), 'req_01', 'event', 'wait', args).data.newPageId, receipt.newPageId)
  assert.equal(safeResponse(wire({ ...receipt, eventKind: 'download' }), 'req_01', 'event', 'wait', args), null)
  assert.equal(safeResponse(wire({ ...receipt, newPageId: 'pg-0123456789ABCDEfabcd' }), 'req_01', 'event', 'wait', args), null)
  const download = { ...receipt, eventKind: 'download', newPageId: undefined, downloadId: 'd'.repeat(32) }
  delete download.newPageId
  assert.equal(safeResponse(wire(download), 'req_01', 'event', 'wait', args).data.downloadId, download.downloadId)
  assert.equal(safeResponse(wire({ ...download, newPageId: receipt.newPageId }), 'req_01', 'event', 'wait', args), null)
  assert.equal(safeResponse(wire({ ...download, downloadId: 'D'.repeat(32) }), 'req_01', 'event', 'wait', args), null)
})

test('event client preserves exact task and journal denials as nonexecuted', () => {
  const base = { version: 1, requestId: 'req_01', executed: false, sessionGeneration: 4,
    controlGeneration: 9, state: 'RUNNING', owner: 'ai', data: {} }
  const deniedCodes = ['TASK_PAGE_MISMATCH', 'JOURNAL_UNAVAILABLE', 'BUSY', 'TASK_NOT_FOUND',
    'TASK_UNKNOWN_EFFECT_UNVERIFIED', 'TASK_EXPIRED', 'TASK_NOT_RUNNING', 'TASK_BUDGET_EXHAUSTED']
  for (const code of deniedCodes) {
    const result = safeResponse(JSON.stringify({ ...base, code }), 'req_01', 'event', 'arm', {})
    assert.equal(result?.code, code)
    assert.equal(result?.executed, false)
  }
  assert.equal(safeResponse(JSON.stringify({ ...base, code: 'TASK_INVENTED' }), 'req_01', 'event', 'arm', {}), null)
  assert.equal(safeResponse(JSON.stringify({ ...base, code: 'BUSY', executed: null }), 'req_01', 'event', 'arm', {}), null)
})

test('taskId rejects malformed IDs and cold open never binds an existing task to a new page', async () => {
  let calls = 0
  const invalid = await browserRequest('observe', {}, { sessionGeneration: 1, taskId: 'bad/id' }, {
    execFile: () => { calls += 1 }
  })
  assert.equal(invalid.code, 'INVALID_ARGUMENT')
  assert.equal(calls, 0)

  const f = fixture()
  f.responses.push({ version: 1, requestId: '$request', code: 'OK', executed: false,
    sessionGeneration: 0, controlGeneration: 0, state: 'CLOSED', data: { automationEnabled: true } })
  const coldOpen = await f.tools.find((tool) => tool.name === 'browser_open').execute({
    url: 'https://example.test/', taskId: 'task_A-1'
  })
  assert.equal(coldOpen.code, 'TASK_PAGE_MISMATCH')
  assert.equal(f.calls.length, 1)
  assert.equal(parseCall(f.calls[0]).method, 'status')

  const active = fixture()
  active.responses.push({ version: 1, requestId: '$request', code: 'OK', executed: false,
    sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', data: { automationEnabled: true, owner: 'ai' } })
  active.responses.push({ version: 1, requestId: '$request', code: 'OK', executed: true,
    sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', data: {} })
  const opened = await active.tools.find((tool) => tool.name === 'browser_open').execute({
    url: 'https://example.test/', taskId: 'task_A-1'
  })
  assert.equal(opened.ok, true)
  assert.equal(parseCall(active.calls[0]).taskId, 'task_A-1')
  assert.equal(parseCall(active.calls[1]).taskId, 'task_A-1')
  assert.equal(Object.hasOwn(parseCall(active.calls[1]).args, 'taskId'), false)
})

test('wait uses a session-only envelope and budgets time for the runtime result', async () => {
  const f = fixture()
  f.responses.push({
    version: 1, requestId: '$request', code: 'WAIT_TIMEOUT', executed: false,
    sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING',
    data: { matched: false, condition: 'text', pageId: 'pg-0123456789abcdefabcd', navigationId: 7,
      documentGeneration: 3, url: 'https://example.test/', loadState: 'FINISHED' }
  })
  const tool = f.tools.find((entry) => entry.name === 'browser_wait')
  const result = await tool.execute({ sessionGeneration: 4, condition: 'text', text: 'ready', timeoutMs: 12000 })
  assert.equal(result.code, 'WAIT_TIMEOUT')
  const request = parseCall(f.calls[0])
  assert.equal(request.method, 'wait')
  assert.equal(request.deadlineAtMillis, 18000)
  assert.equal(request.sessionGeneration, 4)
  assert.equal('controlGeneration' in request, false)
  assert.equal('owner' in request, false)
})

test('navigate requires AI control and distinguishes accepted navigation from missing history', async () => {
  const f = fixture()
  f.responses.push({
    version: 1, requestId: '$request', code: 'OK', executed: true,
    sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING',
    data: { action: 'back', accepted: true, pageId: 'pg-0123456789abcdefabcd', navigationId: 8, url: 'https://example.test/' }
  })
  const tool = f.tools.find((entry) => entry.name === 'browser_navigate')
  assert.equal((await tool.execute({ sessionGeneration: 4, controlGeneration: 9, action: 'back' })).ok, true)
  const request = parseCall(f.calls[0])
  assert.equal(request.method, 'navigate')
  assert.equal(request.owner, 'ai')
  assert.equal(request.controlGeneration, 9)

  const rejected = fixture()
  rejected.responses.push({
    version: 1, requestId: '$request', code: 'NO_HISTORY', executed: false,
    sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING',
    data: { action: 'forward', accepted: false, canGoBack: false, canGoForward: false }
  })
  const noHistory = await rejected.tools.find((entry) => entry.name === 'browser_navigate')
    .execute({ sessionGeneration: 4, controlGeneration: 9, action: 'forward' })
  assert.equal(noHistory.code, 'NO_HISTORY')
  assert.equal(noHistory.executed, 'false')
})

test('collect exposes only the bounded window action and current AI generations', async () => {
  const f = fixture()
  f.responses.push(response({ method: 'collect' }, {
    action: 'window', declaredRange: 'latest 20', truncated: false,
    identity: { pageId: 'pg-0123456789abcdefabcd', documentGeneration: 3, navigationId: 7, observationId: 2, sessionGeneration: 4, controlGeneration: 9 },
    containerNodeId: `b:4:9:3:2:${'a'.repeat(32)}:0`,
    container: { scrollTop: 0, scrollHeight: 1000, clientHeight: 400, startReached: true, endReached: false, totalCount: 100 },
    records: [{ key: 'key-1', author: null, content: 'sample', order: 1 }], excludedCount: 0
  }))
  const result = await f.tools.find((tool) => tool.name === 'browser_collect').execute({
    sessionGeneration: 4, controlGeneration: 9, action: 'window', containerSelector: '#list',
    messageSelector: 'article.item', keyAttribute: 'data-id', excludeSelector: '.draft', declaredRange: 'latest 20',
    maxRecords: 20, maxContentChars: 1024
  })
  assert.equal(result.code, 'OK')
  const request = parseCall(f.calls[0])
  assert.equal(request.method, 'collect')
  assert.equal(request.owner, 'ai')
  assert.equal(request.sessionGeneration, 4)
  assert.equal(request.controlGeneration, 9)
  assert.equal(request.args.action, 'window')
  assert.equal(request.args.excludeSelector, '.draft')
  assert.equal((await f.tools.find((tool) => tool.name === 'browser_collect').execute({
    sessionGeneration: 4, controlGeneration: 9, action: 'window', containerSelector: '#list',
    messageSelector: 'article.item', keyAttribute: 'data-id', declaredRange: 'latest 20',
    maxRecords: 201, maxContentChars: 1024
  })).code, 'INVALID_ARGUMENT')
  assert.equal(f.calls.length, 1)
  assert.equal((await f.tools.find((tool) => tool.name === 'browser_collect').execute({
    sessionGeneration: 4, controlGeneration: 9, action: 'window', containerSelector: '#list',
    messageSelector: 'article.item', keyAttribute: 'data-id', excludeSelector: '.draft\nunsafe',
    declaredRange: 'latest 20', maxRecords: 20, maxContentChars: 1024
  })).code, 'INVALID_ARGUMENT')
  assert.equal((await f.tools.find((tool) => tool.name === 'browser_collect').execute({
    sessionGeneration: 4, controlGeneration: 9, action: 'window', containerSelector: '#list',
    messageSelector: 'article.item', keyAttribute: 'data-id', contentSelector: '',
    declaredRange: 'latest 20', maxRecords: 20, maxContentChars: 1024
  })).code, 'INVALID_ARGUMENT')
  assert.equal(f.calls.length, 1)
})

test('collect scan uses task envelope for every bounded read and returns structured completeness', async () => {
  const f = fixture()
  f.responses.push(response({ method: 'collect' }, {
    action: 'window', declaredRange: 'all rows', truncated: false, excludedCount: 0,
    identity: { pageId: 'pg-0123456789abcdefabcd', documentGeneration: 3, navigationId: 7,
      observationId: 2, sessionGeneration: 4, controlGeneration: 9 },
    containerNodeId: `b:4:9:3:2:${'a'.repeat(32)}:0`,
    container: { scrollTop: 0, scrollHeight: 1000, clientHeight: 400, startReached: true, endReached: true, totalCount: 1 },
    records: [{ key: 'row-1', author: 'assistant', content: 'complete record', order: 0 }]
  }))
  const tool = f.tools.find((entry) => entry.name === 'browser_collect')
  const result = await tool.execute({ action: 'scan', taskId: 'task_scan-1', sessionGeneration: 4, controlGeneration: 9,
    containerSelector: '#list', messageSelector: '.row', keyAttribute: 'data-id', contentSelector: '.text',
    declaredRange: 'all rows', maxRecords: 80, maxContentChars: 4096 })
  assert.equal(result.code, 'OK')
  const request = parseCall(f.calls[0])
  assert.equal(request.method, 'collect')
  assert.equal(request.taskId, 'task_scan-1')
  assert.deepEqual(request.args, {
    action: 'window', containerSelector: '#list', messageSelector: '.row', keyAttribute: 'data-id',
    contentSelector: '.text', declaredRange: 'all rows', maxRecords: 80, maxContentChars: 4096
  })
  assert.equal(request.sessionGeneration, 4)
  assert.equal(request.controlGeneration, 9)
  assert.equal(request.owner, 'ai')
  assert.equal(result.path, '')
  const data = JSON.parse(result.dataJson)
  assert.equal(data.atomicSnapshot, false)
  assert.equal(data.coverageComplete, true)
  assert.equal(data.coverageScope, 'entire_selected_container')
  assert.deepEqual(data.records.map((record) => record.key), ['row-1'])
  assert.equal(tool.parameters.taskId.required, undefined, 'flat SDK schema keeps window taskId optional; scan enforces it in the handler')
})

test('collect scan rejects malformed task IDs and never repeats UNKNOWN scrolls', async () => {
  const invalid = fixture()
  const tool = invalid.tools.find((entry) => entry.name === 'browser_collect')
  const args = { action: 'scan', sessionGeneration: 4, controlGeneration: 9, containerSelector: '#list',
    messageSelector: '.row', keyAttribute: 'data-id', declaredRange: 'all rows', maxRecords: 80, maxContentChars: 4096 }
  assert.equal((await tool.execute({ ...args, taskId: 'bad/id' })).code, 'INVALID_ARGUMENT')
  assert.equal((await tool.execute({ ...args, taskId: 'valid_task', maxTotalBytes: 5 * 1024 * 1024 + 1 })).code, 'INVALID_ARGUMENT')
  assert.equal((await tool.execute({ ...args, taskId: 'valid_task', maxDurationMs: 0 })).code, 'INVALID_ARGUMENT')
  assert.equal((await tool.execute({ ...args, taskId: 'valid_task', sessionGeneration: 0 })).code, 'GENERATION_UNAVAILABLE')
  assert.equal(invalid.calls.length, 0)

  const alreadyAborted = fixture()
  const controller = new AbortController()
  controller.abort()
  const cancelled = await alreadyAborted.tools.find((entry) => entry.name === 'browser_collect')
    .execute({ ...args, taskId: 'valid_task' }, { signal: controller.signal })
  assert.equal(cancelled.code, 'UNKNOWN')
  assert.equal(cancelled.executed, 'unknown')
  assert.equal(alreadyAborted.calls.length, 0)

  const f = fixture()
  f.responses.push(response({ method: 'collect' }, {
    action: 'window', declaredRange: 'all rows', truncated: false, excludedCount: 0,
    identity: { pageId: 'pg-0123456789abcdefabcd', documentGeneration: 3, navigationId: 7,
      observationId: 2, sessionGeneration: 4, controlGeneration: 9 },
    containerNodeId: `b:4:9:3:2:${'a'.repeat(32)}:0`,
    container: { scrollTop: 0, scrollHeight: 1000, clientHeight: 400, startReached: true, endReached: false, totalCount: 2 },
    records: [{ key: 'row-1', author: 'assistant', content: 'partial', order: 0 }]
  }))
  const timeout = new Error('scroll timed out')
  timeout.code = 'ETIMEDOUT'
  f.responses.push(timeout)
  const result = await f.tools.find((entry) => entry.name === 'browser_collect').execute({ ...args, taskId: 'task_scan-1' })
  assert.equal(result.code, 'UNKNOWN')
  assert.equal(result.executed, 'unknown')
  assert.deepEqual(f.calls.map((call) => parseCall(call).method), ['collect', 'scroll'])
  assert.equal(f.calls.filter((call) => parseCall(call).method === 'scroll').length, 1)
  assert.equal(JSON.parse(result.dataJson).coverageComplete, false)
})

test('locate registers a controlled semantic search and returns a current observation reference', async () => {
  const f = fixture()
  const ref = 'b:4:9:3:7:0123456789abcdef0123456789abcdef:0'
  f.responses.push({
    version: 1, requestId: '$request', code: 'OK', executed: true,
    sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai',
    data: { ref, count: 1, strategy: 'role', pageId: 'pg-0123456789abcdefabcd', documentGeneration: 3, observationId: 7 }
  })
  const result = await f.tools.find((entry) => entry.name === 'browser_locate').execute({
    sessionGeneration: 4, controlGeneration: 9, strategy: 'role', value: 'button', exact: true, name: 'Continue'
  })
  assert.equal(result.ok, true)
  const request = parseCall(f.calls[0])
  assert.equal(request.method, 'locate')
  assert.equal(request.owner, 'ai')
  assert.equal(request.sessionGeneration, 4)
  assert.equal(request.controlGeneration, 9)
  assert.deepEqual(request.args, { strategy: 'role', value: 'button', exact: true, name: 'Continue' })
  f.responses.push(response({ method: 'click' }))
  const clicked = await f.tools.find((entry) => entry.name === 'browser_click').execute({
    sessionGeneration: 4, controlGeneration: 9, nodeId: JSON.parse(result.dataJson).ref
  })
  assert.equal(clicked.ok, true)
  assert.equal(parseCall(f.calls[1]).method, 'click')
  assert.equal(parseCall(f.calls[1]).args.nodeId, ref)
  f.responses.push(response({ method: 'type' }))
  const typed = await f.tools.find((entry) => entry.name === 'browser_type').execute({
    sessionGeneration: 4, controlGeneration: 9, nodeId: ref, text: 'safe input', replace: true
  })
  assert.equal(typed.ok, true)
  assert.equal(parseCall(f.calls[2]).method, 'type')
  assert.equal(parseCall(f.calls[2]).args.nodeId, ref)

  const rejected = fixture()
  rejected.responses.push({ version: 1, requestId: '$request', code: 'AMBIGUOUS', executed: false,
    sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai',
    data: { count: 2, matches: [{ tag: 'button', role: 'button', name: 'Continue' }] } })
  const ambiguous = await rejected.tools.find((entry) => entry.name === 'browser_locate').execute({
    sessionGeneration: 4, controlGeneration: 9, strategy: 'role', value: 'button', exact: true
  })
  assert.equal(ambiguous.code, 'AMBIGUOUS')
  assert.equal(ambiguous.executed, 'false')
  assert.equal(JSON.parse(ambiguous.dataJson).ref, undefined)
})

test('browser_click accepts either a node reference or a screenshot-bound DOM point', async () => {
  const f = fixture()
  f.responses.push({ version: 1, requestId: '$request', code: 'OK', executed: true,
    sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai',
    data: { screenshotId: 'a'.repeat(32), bitmapX: 4.5, bitmapY: 8, cssX: 2.25, cssY: 4,
      targetKind: 'DOM_HTML' } })
  const result = await f.tools.find((entry) => entry.name === 'browser_click').execute({
    sessionGeneration: 4, controlGeneration: 9, screenshotId: 'a'.repeat(32), x: 4.5, y: 8
  })
  assert.equal(result.ok, true)
  assert.deepEqual(parseCall(f.calls[0]).args, { screenshotId: 'a'.repeat(32), x: 4.5, y: 8 })
  const invalid = await f.tools.find((entry) => entry.name === 'browser_click').execute({
    sessionGeneration: 4, controlGeneration: 9, nodeId: 'n1', screenshotId: 'a'.repeat(32), x: 1, y: 2
  })
  assert.equal(invalid.code, 'INVALID_ARGUMENT')
  assert.equal(f.calls.length, 1)
})

test('Cordis client validates optional locate and observe scope metadata strictly', () => {
  const coverage = (overrides = {}) => ({
    complete: true, scannedElements: 4, frameCount: 1, shadowRootCount: 1, inaccessibleFrameCount: 0,
    frameLimitReached: false, shadowLimitReached: false, elementLimitReached: false,
    textNodesVisited: 3, textLimitReached: false, ...overrides,
  })
  const frameScopes = [{ ordinal: 1, parentOrdinal: 0, depth: 1, status: 'accessible', reason: null }]
  const locate = {
    version: 1, requestId: 'scope-locate', code: 'OK', executed: true, sessionGeneration: 4,
    controlGeneration: 9, state: 'RUNNING', owner: 'ai',
    data: { ref: 'b:4:9:3:7:0123456789abcdef0123456789abcdef:1', count: 1, strategy: 'role',
      pageId: 'pg-0123456789abcdefabcd', documentGeneration: 3, observationId: 7,
      frameOrdinal: 1, shadowDepth: 2, scopeCoverage: coverage(), frameScopes },
  }
  assert.equal(safeResponse(JSON.stringify(locate), 'scope-locate', 'locate', 'role').code, 'OK')
  const textLimitedSuccess = structuredClone(locate)
  textLimitedSuccess.data.scopeCoverage.textLimitReached = true
  assert.equal(safeResponse(JSON.stringify(textLimitedSuccess), 'scope-locate', 'locate', 'role'), null)
  const partial = structuredClone(locate)
  delete partial.data.frameScopes
  assert.equal(safeResponse(JSON.stringify(partial), 'scope-locate', 'locate', 'role'), null)
  const incomplete = {
    ...locate, code: 'INCOMPLETE_SCOPE', executed: false,
    data: { count: 4, scopeCoverage: coverage({ complete: false, inaccessibleFrameCount: 1 }),
      frameScopes: [{ ordinal: 1, parentOrdinal: 0, depth: 1, status: 'inaccessible', reason: 'CROSS_ORIGIN_OR_OPAQUE' }] },
  }
  assert.equal(safeResponse(JSON.stringify(incomplete), 'scope-locate', 'locate', 'role').code, 'INCOMPLETE_SCOPE')
  const invalidParent = structuredClone(incomplete)
  invalidParent.data.frameScopes[0].parentOrdinal = 1
  assert.equal(safeResponse(JSON.stringify(invalidParent), 'scope-locate', 'locate', 'role'), null)
  const scopedNotFound = { ...locate, code: 'NOT_FOUND', executed: false,
    data: { count: 0, scopeCoverage: coverage(), frameScopes } }
  assert.equal(safeResponse(JSON.stringify(scopedNotFound), 'scope-locate', 'locate', 'role').code, 'NOT_FOUND')
  const scopedAmbiguous = { ...locate, code: 'AMBIGUOUS', executed: false,
    data: { count: 2, scopeCoverage: coverage(), frameScopes, matches: [
      { tag: 'button', role: 'button', name: 'Go', frameOrdinal: 1, shadowDepth: 2 },
    ] } }
  assert.equal(safeResponse(JSON.stringify(scopedAmbiguous), 'scope-locate', 'locate', 'role').code, 'AMBIGUOUS')
  const scopedLimit = { ...locate, code: 'LOCATE_LIMIT', executed: false,
    data: { count: 5000, scopeCoverage: coverage(), frameScopes } }
  assert.equal(safeResponse(JSON.stringify(scopedLimit), 'scope-locate', 'locate', 'role').code, 'LOCATE_LIMIT')
  const partialError = structuredClone(scopedNotFound)
  delete partialError.data.scopeCoverage
  assert.equal(safeResponse(JSON.stringify(partialError), 'scope-locate', 'locate', 'role'), null)

  const observe = {
    version: 1, requestId: 'scope-observe', code: 'OK', executed: false, sessionGeneration: 4,
    controlGeneration: null, state: 'RUNNING', owner: 'ai',
    data: { title: 'Fixture', url: 'https://example.test/', readyState: 'complete', text: 'ready',
      textTruncated: true, truncated: true, elementsTruncated: false,
      urlQueryRedacted: false, urlFragmentRedacted: false, loadState: 'finished', observationGeneration: 7,
      elements: [{ nodeId: 'b:4:9:3:7:0123456789abcdef0123456789abcdef:0', hrefQueryRedacted: false, hrefFragmentRedacted: false, tag: 'button', type: '', role: 'button', label: 'Go', href: '', frameOrdinal: 1, shadowDepth: 2 }],
      scopeCoverage: coverage({ textLimitReached: true }), frameScopes,
      topFrameOnly: false, openShadowRootsSupported: true, closedShadowRootsSupported: false, crossOriginFramesSupported: false },
  }
  assert.equal(safeResponse(JSON.stringify(observe), 'scope-observe', 'observe').code, 'OK')
  const extra = structuredClone(observe)
  extra.data.frameScopes[0].unexpected = true
  assert.equal(safeResponse(JSON.stringify(extra), 'scope-observe', 'observe'), null)
  const capabilityMismatch = structuredClone(observe)
  capabilityMismatch.data.topFrameOnly = true
  assert.equal(safeResponse(JSON.stringify(capabilityMismatch), 'scope-observe', 'observe'), null)
  const legacy = { ...observe, data: { text: 'old v1 fixture' } }
  assert.equal(safeResponse(JSON.stringify(legacy), 'scope-observe', 'observe').code, 'OK')
})

test('locate rejects malformed arguments locally without dispatching', async () => {
  let invocations = 0
  const envelope = { sessionGeneration: 4, controlGeneration: 9, owner: 'ai' }
  for (const args of [
    { strategy: 'xpath', value: '//button', exact: true },
    { strategy: 'css', value: '#submit' },
    { strategy: 'label', value: 'Email', exact: false, name: 'Email' },
    { strategy: 'role', value: 'button', exact: true, name: '' },
    { strategy: 'role', value: 'button', exact: true, scopeRef: 'n1' },
    { strategy: 'text', value: 'x'.repeat(2049), exact: true }
  ]) {
    const result = await browserRequest('locate', args, envelope, {
      requestId: () => 'bad-locate',
      execFile: () => { invocations += 1 }
    })
    assert.equal(result.code, 'INVALID_ARGUMENT')
  }
  assert.equal(invocations, 0)
})

test('aborting browser_wait receives one receipt and returns the original wait terminal', { skip: process.platform === 'win32' }, async () => {
  const socketName = `browser-wait-cancel-${process.pid}-${Date.now()}`
  const token = 'c'.repeat(64)
  const peers = new Set()
  let server
  let requestPeer
  const receivedRequest = new Promise((resolve, reject) => {
    server = net.createServer((peer) => {
      peers.add(peer)
      peer.once('close', () => peers.delete(peer))
      let data = ''
      peer.setEncoding('utf8')
      peer.on('data', (part) => {
        data += part
        if (!data.includes('\n')) return
        let frame
        try { frame = JSON.parse(data.slice(0, data.indexOf('\n'))) } catch (error) { return reject(error) }
        if (frame.token !== token) return reject(new Error('unexpected Broker token'))
        if (frame.op === 'request') {
          requestPeer = peer
          resolve({ server, requestId: frame.request.requestId })
          return
        }
        if (frame.op !== 'cancel') return reject(new Error(`unexpected Broker op ${frame.op}`))
        const result = {
          version: 1, requestId: frame.requestId, code: 'CANCELLED', executed: null,
          sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai',
          data: { receiptKind: 'cancel_request', cancelStage: 'condition_wait',
            reason: 'cancel_accepted', actionOutcome: 'unknown',
            nextAction: 'read_original_request_result', automaticRetryAllowed: false }
        }
        peer.end(`${JSON.stringify(result)}\n`)
        setImmediate(() => requestPeer?.end(`${JSON.stringify({
          version: 1, requestId: frame.requestId, code: 'CANCELLED', executed: false,
          sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai',
          data: { matched: false, condition: 'text', pageId: 'pg-' + 'a'.repeat(20),
            navigationId: 1, documentGeneration: 2, url: 'https://example.test/',
            loadState: 'FINISHED', reason: 'cancelled', stage: 'condition_wait',
            cancellationReason: 'user_cancelled', nextAction: 'read_status_and_observe',
            automaticRetryAllowed: false }
        })}\n`))
      })
    })
    server.listen(`\0${socketName}`, (error) => error && reject(error))
  })
  let accepted
  const controller = new AbortController()
  const modulePath = require.resolve('../../../../browser/browser-bridge/src/main/assets/browser/lib/browser-cli.cjs')
  const script = [
    'const cli=require(process.argv[1]);',
    'const [socketName,token,method,flag,raw]=process.argv.slice(2);',
    'if(method==="cancel"){cli.cancelRequest(raw, {socketName,token}).then((value)=>process.stdout.write(JSON.stringify(value)+"\\n"));}',
    'else{const request=cli.validateRequest(method,raw);',
    'cli.execute(method,request,{socketName,token}).then((value)=>process.stdout.write(JSON.stringify(value)+"\\n"));}'
  ].join('')
  const tools = buildTools({
    defineTool: (tool) => tool,
    dependencies: {
      requestId: () => 'abortable-wait',
      cli: 'test-browser-cli',
      execFile: (_file, args, options, callback) => nodeExecFile(process.execPath,
        ['-e', script, modulePath, socketName, token, ...args], options, callback)
    }
  })
  try {
    const wait = tools.find((tool) => tool.name === 'browser_wait')
    const pending = wait.execute({ sessionGeneration: 4, condition: 'text', text: 'ready' }, { signal: controller.signal })
    accepted = await Promise.race([
      receivedRequest,
      new Promise((_, reject) => setTimeout(() => reject(new Error('Browser CLI did not send the wait request frame')), 5000))
    ])
    controller.abort()
    const result = await pending
    assert.equal(result.code, 'CANCELLED', JSON.stringify(result))
    assert.equal(result.executed, 'false')
    const data = JSON.parse(result.dataJson)
    assert.equal(data.stage, 'condition_wait')
    assert.equal(data.receiptKind, undefined)
    assert.equal(data.actionOutcome, undefined)
    await new Promise((resolve) => accepted.server.close(resolve))
  } finally {
    for (const peer of peers) peer.destroy()
    if (server && server.listening) await new Promise((resolve) => server.close(resolve))
  }
})

test('a pre-aborted browser_wait returns local cancellation without spawning the CLI', async () => {
  const controller = new AbortController()
  controller.abort()
  let invocations = 0
  const tools = buildTools({
    defineTool: (tool) => tool,
    dependencies: { execFile: () => { invocations += 1 } }
  })
  const result = await tools.find((tool) => tool.name === 'browser_wait').execute(
    { sessionGeneration: 4, condition: 'load' }, { signal: controller.signal })
  assert.equal(result.code, 'CANCELLED')
  assert.equal(result.executed, 'false')
  assert.equal(invocations, 0)
})

test('an invalid wait cancellation receipt becomes non-replayable UNKNOWN after one cancel', async () => {
  const controller = new AbortController()
  const calls = []
  let requestKilled = 0
  const fakeChild = () => ({
    exitCode: null,
    signalCode: null,
    kill() { requestKilled += 1 },
  })
  const tools = buildTools({
    defineTool: (tool) => tool,
    dependencies: {
      requestId: () => 'invalid-cancel-receipt',
      execFile: (_file, args, _options, callback) => {
        calls.push(args)
        if (args[0] === 'cancel') {
          setImmediate(() => callback(null, `${JSON.stringify({
            version: 1,
            requestId: 'invalid-cancel-receipt',
            code: 'CANCELLED',
            executed: false,
            sessionGeneration: 4,
            controlGeneration: 9,
            state: 'RUNNING',
            owner: 'ai',
            data: { receiptKind: 'cancel_request', reason: 'invalid_runtime_receipt' },
          })}\n`))
          return fakeChild()
        }
        return fakeChild()
      },
    },
  })
  const pending = tools.find((tool) => tool.name === 'browser_wait').execute(
    { sessionGeneration: 4, condition: 'load' },
    { signal: controller.signal },
  )
  controller.abort()
  const result = await pending
  assert.equal(result.code, 'UNKNOWN')
  assert.equal(result.executed, 'unknown')
  const data = JSON.parse(result.dataJson)
  assert.equal(data.cancelStage, 'transport')
  assert.equal(data.reason, 'invalid_runtime_receipt')
  assert.equal(data.actionOutcome, 'unknown')
  assert.equal(data.automaticRetryAllowed, false)
  assert.equal(calls.filter((args) => args[0] === 'cancel').length, 1)
  assert.equal(requestKilled, 1)
})

test('status is generation-free; observe is read-only and does not claim owner', async () => {
  const f = fixture()
  f.responses.push({ version: 1, requestId: '$request', code: 'OK', executed: false, sessionGeneration: 3, controlGeneration: 8, state: 'RUNNING', data: { owner: 'human', automationEnabled: true } })
  await f.tools[0].execute()
  assert.deepEqual(Object.keys(parseCall(f.calls[0])).sort(), ['args', 'deadlineAtMillis', 'method', 'requestId', 'version'])

  f.responses.push({ version: 1, requestId: '$request', code: 'OK', executed: false, sessionGeneration: 3, controlGeneration: 8, state: 'RUNNING', data: { text: 'bounded' } })
  await f.tools[3].execute({ sessionGeneration: 3 })
  const observe = parseCall(f.calls[1])
  assert.equal(observe.method, 'observe')
  assert.equal(observe.sessionGeneration, 3)
  assert.equal('controlGeneration' in observe, false)
  assert.equal('owner' in observe, false)
})

test('open checks status and only sends a closed-session request after automation is enabled', async () => {
  const f = fixture()
  f.responses.push({ version: 1, requestId: '$request', code: 'OK', executed: false, sessionGeneration: 0, controlGeneration: 0, state: 'CLOSED', data: { automationEnabled: true, owner: 'human' } })
  f.responses.push({ version: 1, requestId: '$request', code: 'OK', executed: true, sessionGeneration: 1, controlGeneration: 1, state: 'RUNNING', data: {} })
  const opened = await f.tools[2].execute({ url: 'https://example.test/' })
  assert.equal(opened.ok, true, JSON.stringify(opened))
  assert.equal(parseCall(f.calls[0]).method, 'status')
  const open = parseCall(f.calls[1])
  assert.equal(open.method, 'open')
  assert.equal(open.owner, 'ai')
  assert.equal('sessionGeneration' in open, false)
  assert.equal('controlGeneration' in open, false)

  const denied = fixture()
  denied.responses.push({ version: 1, requestId: '$request', code: 'OK', executed: false, state: 'CLOSED', data: { automationEnabled: false } })
  const result = await denied.tools[2].execute({ url: 'https://example.test/' })
  assert.equal(result.code, 'AUTOMATION_DISABLED')
  assert.equal(denied.calls.length, 1)
})

test('browser_open reports sanitized selection and task-feedback binding codes in dataJson', async () => {
  const calls = []
  const selectionIntegration = {
    afterOpen() { return { code: 'ACTIVE' } },
    status() { return { selection: 'ACTIVE', taskFeedback: 'SESSION_CONTROLLER_UNAVAILABLE' } },
  }
  const tools = buildTools({ defineTool: (tool) => tool, dependencies: {
    selectionIntegration,
    now: () => 1000,
    requestId: () => `binding-${calls.length + 1}`,
    execFile: (_file, argv, _options, callback) => {
      const request = JSON.parse(argv[2])
      calls.push(request)
      const response = request.method === 'status'
        ? { version: 1, requestId: request.requestId, code: 'OK', executed: false, sessionGeneration: 4,
          controlGeneration: 9, state: 'RUNNING', data: { automationEnabled: true, owner: 'ai' } }
        : { version: 1, requestId: request.requestId, code: 'OK', executed: true, sessionGeneration: 4,
          controlGeneration: 9, state: 'RUNNING', data: { event: 'opened', selectionSequence: 0 } }
      callback(null, `${JSON.stringify(response)}\n`)
    },
  } })
  const result = await tools.find((tool) => tool.name === 'browser_open').execute({}, {
    agent: { id: 'private-real-agent-id' }, signal: new AbortController().signal,
  })
  assert.equal(result.ok, true)
  assert.deepEqual(JSON.parse(result.dataJson).browserBinding, {
    selection: 'ACTIVE', taskFeedback: 'SESSION_CONTROLLER_UNAVAILABLE',
  })
  assert.doesNotMatch(result.dataJson, /private-real-agent-id/)
})

test('active navigation, click, type, scroll, screenshot, and close carry ownership generations', async () => {
  const f = fixture()
  const calls = [
    ['browser_open', { url: 'https://example.test/' }],
    ['browser_click', { sessionGeneration: 4, controlGeneration: 9, nodeId: 'n1' }],
    ['browser_type', { sessionGeneration: 4, controlGeneration: 9, nodeId: 'n1', text: 'hello' }],
    ['browser_scroll', { sessionGeneration: 4, controlGeneration: 9, direction: 'down' }],
    ['browser_screenshot', { sessionGeneration: 4, controlGeneration: 9 }],
    ['browser_close', { sessionGeneration: 4, controlGeneration: 9 }]
  ]
  for (let index = 0; index < calls.length; index++) {
    const tool = f.tools.find((entry) => entry.name === calls[index][0])
    const args = calls[index][1]
    if (index === 0) {
      f.responses.push({ version: 1, requestId: '$request', code: 'OK', executed: false, sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', data: { automationEnabled: true, owner: 'ai' } })
      f.responses.push({ version: 1, requestId: '$request', code: 'OK', executed: true, sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', data: {} })
      const result = await tool.execute(args)
      assert.equal(result.ok, true, JSON.stringify(result))
      const activeOpen = parseCall(f.calls[1])
      assert.equal(activeOpen.sessionGeneration, 4)
      assert.equal(activeOpen.controlGeneration, 9)
      assert.equal(activeOpen.owner, 'ai')
    } else {
      const request = { requestId: `request-${index + 1}`, method: calls[index][0].replace('browser_', '') }
      f.responses.push(response(request))
      await tool.execute(args)
      const sent = parseCall(f.calls.at(-1))
      assert.equal(sent.sessionGeneration, 4)
      assert.equal(sent.controlGeneration, 9)
      assert.equal(sent.owner, 'ai')
    }
  }
})

test('rejects unsafe URLs, oversized input, stale generation forms, and verifies screenshot paths', async () => {
  const f = fixture()
  const badUrl = await f.tools[2].execute({ url: 'javascript:alert(1)' })
  assert.equal(badUrl.code, 'INVALID_ARGUMENT')
  const tooLong = await f.tools[9].execute({ sessionGeneration: 1, controlGeneration: 2, nodeId: 'x', text: 'x'.repeat(4097) })
  assert.equal(tooLong.code, 'INVALID_ARGUMENT')
  const missingControl = await browserRequest('click', { nodeId: 'x' }, { sessionGeneration: 1 }, { requestId: () => 'r' })
  assert.equal(missingControl.code, 'INVALID_ARGUMENT')
  assert.equal(f.calls.length, 0)
  assert.equal(resolveScreenshotPath('/tmp/elsewhere.png'), '')
  assert.equal(resolveScreenshotPath('/opt/browser/out/shot/../secret.png'), '')
})

test('generation sentinel zero is never sent for an active request', async () => {
  const f = fixture()
  const observe = await f.tools[3].execute({ sessionGeneration: 0 })
  const click = await f.tools[8].execute({
    sessionGeneration: 0,
    controlGeneration: 0,
    nodeId: 'n1'
  })
  assert.equal(observe.code, 'GENERATION_UNAVAILABLE')
  assert.equal(click.code, 'GENERATION_UNAVAILABLE')
  assert.equal(f.calls.length, 0)
  const lowLevel = await browserRequest('click', { nodeId: 'n1' }, {
    sessionGeneration: 0, controlGeneration: 0, owner: 'ai'
  }, { requestId: () => 'zero' })
  assert.equal(lowLevel.code, 'INVALID_ARGUMENT')
})

test('idle active Browser preserves generations when opening a URL', async () => {
  const f = fixture()
  f.responses.push({ version: 1, requestId: '$request', code: 'OK', executed: false, sessionGeneration: 4, controlGeneration: 9, state: 'IDLE', data: { automationEnabled: true, owner: 'ai' } })
  f.responses.push({ version: 1, requestId: '$request', code: 'OK', executed: true, sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', data: {} })
  const result = await f.tools[2].execute({ url: 'https://example.test/' })
  assert.equal(result.ok, true)
  const sent = parseCall(f.calls[1])
  assert.equal(sent.sessionGeneration, 4)
  assert.equal(sent.controlGeneration, 9)
  assert.equal(sent.owner, 'ai')
  assert.equal(f.tools[0].output.schema.properties.sessionGeneration.type, 'integer')
  assert.equal(f.tools[0].output.schema.properties.controlGeneration.type, 'integer')
})

test('does not retry when execution outcome is unknown', async () => {
  let calls = 0
  const result = await browserRequest('click', { nodeId: 'n1' }, {
    sessionGeneration: 1, controlGeneration: 2, owner: 'ai'
  }, {
    requestId: () => 'once',
    execFile: (_file, _argv, _options, callback) => {
      calls += 1
      const error = new Error('timeout')
      error.code = 'ETIMEDOUT'
      callback(error, '')
    }
  })
  assert.equal(calls, 1)
  assert.equal(result.code, 'UNKNOWN')
  assert.equal(result.executed, 'unknown')
  const diagnostics = JSON.parse(result.dataJson)
  assert.deepEqual(Object.keys(diagnostics).sort(), [
    'actionOutcome', 'automaticRetryAllowed', 'cancelStage', 'dispatched', 'originalFailure', 'reason', 'requestId', 'stage',
  ])
  assert.equal(diagnostics.requestId, 'once')
  assert.equal(diagnostics.stage, 'transport')
  assert.equal(diagnostics.dispatched, 'unknown')
  assert.equal(diagnostics.originalFailure, 'timeout')
  assert.equal(diagnostics.automaticRetryAllowed, false)
})

test('screenshot client validates backend diagnostics and transport UNKNOWN identity', () => {
  const runtimeTest = path.resolve(__dirname,
    '../../../../browser/browser-runtime/src/test/java/com/dshbox/browser/runtime/BrowserScreenshotCaptureCoordinatorTest.kt');
  const source = fs.readFileSync(runtimeTest, 'utf8');
  const match = source.match(/PRODUCTION_FAILURE_SAMPLE_JSON = """([\s\S]*?)"""/);
  assert.ok(match, 'runtime producer fixture is present');
  const responseFixture = JSON.parse(match[1]);
  assert.deepEqual(safeResponse(JSON.stringify(responseFixture), responseFixture.requestId, 'screenshot'), responseFixture);
  assert.equal(safeResponse(JSON.stringify({ ...responseFixture,
    data: { ...responseFixture.data, unexpected: true } }), responseFixture.requestId, 'screenshot'), null);
  for (const [code, cancelStage, primaryFailure] of [
    ['IDENTITY_CHANGED', 'identity_match', 'OK'],
    ['DEADLINE_EXPIRED', 'pixel_read', 'DEADLINE_EXPIRED'],
    ['CAPTURE_EMPTY_RESULT', 'pixel_read', 'CAPTURE_EMPTY_RESULT'],
  ]) {
    const value = { ...responseFixture, code, data: { cancelStage, reason: code,
      primaryBackend: 'virtual_display', primaryFailure, debugEnabled: true, userActionRequired: false } };
    assert.ok(safeResponse(JSON.stringify(value), value.requestId, 'screenshot'), code);
  }
  const fallbackFixture = { ...responseFixture, data: { ...responseFixture.data, debugEnabled: true,
    fallbackBackend: 'cdp', fallbackFailure: 'PIXEL_READ_FAILED' } };
  assert.ok(safeResponse(JSON.stringify(fallbackFixture), fallbackFixture.requestId, 'screenshot'));
  assert.equal(safeResponse(JSON.stringify({ ...fallbackFixture,
    data: { ...fallbackFixture.data, fallbackFailure: undefined } }), fallbackFixture.requestId, 'screenshot'), null);
  const busyFixture = { ...responseFixture, code: 'CAPTURE_BUSY', data: { cancelStage: 'surface_attach', reason: 'CAPTURE_BUSY' } };
  assert.ok(safeResponse(JSON.stringify(busyFixture), busyFixture.requestId, 'screenshot'));

  const response = {
    version: 1, requestId: 'shot-client', code: 'OK', executed: true,
    sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai',
    data: { artifactPath: '/opt/browser/out/shot/shot-1.png', width: 10, height: 20, format: 'png', bytes: 80,
      backend: 'cdp', primaryBackend: 'virtual_display', primaryFailure: 'NO_FRAME' },
  }
  assert.ok(safeResponse(`${JSON.stringify(response)}\n`, response.requestId, 'screenshot'))
  assert.equal(safeResponse(`${JSON.stringify({ ...response, data: { ...response.data, fallbackBackend: 'cdp' } })}\n`,
    response.requestId, 'screenshot'), null)

  const unknown = { ...response, code: 'UNKNOWN', executed: null, state: 'RUNNING', data: {
    cancelStage: 'transport', reason: 'transport_unavailable', actionOutcome: 'unknown', automaticRetryAllowed: false,
    requestId: response.requestId, stage: 'transport', dispatched: 'unknown', originalFailure: 'timeout',
  } }
  assert.ok(safeResponse(`${JSON.stringify(unknown)}\n`, response.requestId, 'screenshot'))
  assert.equal(safeResponse(`${JSON.stringify({ ...unknown, data: { ...unknown.data, requestId: 'other' } })}\n`,
    response.requestId, 'screenshot'), null)
})

test('screenshot path strings outside the fixed artifact directory are rejected', () => {
  assert.equal(resolveScreenshotPath('/tmp/elsewhere.png'), '')
  assert.equal(resolveScreenshotPath('/opt/browser/out/shot/../secret.png'), '')
  assert.equal(resolveScreenshotPath('/opt/browser/out/shot/fake.png'), '')
})

test('advanced tools carry the fixed method envelope and keep selection read-only', async () => {
  const f = fixture()
  f.responses.push(response({ method: 'debug' }, { value: 2 }))
  await f.tools[13].execute({ sessionGeneration: 4, controlGeneration: 9, action: 'evaluate', expression: '1 + 1' })
  const debug = parseCall(f.calls[0])
  assert.equal(debug.method, 'debug')
  assert.equal(debug.owner, 'ai')
  assert.equal(debug.sessionGeneration, 4)
  assert.equal(debug.controlGeneration, 9)
  assert.deepEqual(debug.args, { action: 'evaluate', expression: '1 + 1' })

  f.responses.push(response({ method: 'selection' }, { text: 'shared' }))
  await f.tools[15].execute({ sessionGeneration: 4, action: 'latest' })
  const selection = parseCall(f.calls[1])
  assert.equal(selection.method, 'selection')
  assert.equal(selection.sessionGeneration, 4)
  assert.equal('owner' in selection, false)
  assert.equal('controlGeneration' in selection, false)

  f.responses.push(response({ method: 'network' }, { rulesCount: 1 }))
  await f.tools[14].execute({
    sessionGeneration: 4, controlGeneration: 9, action: 'rules',
    rules: [{ urlPattern: 'https://example.test/*', stage: 'response', action: 'fulfill', body: 'ok' }]
  })
  const network = parseCall(f.calls[2])
  assert.equal(network.method, 'network')
  assert.equal(network.owner, 'ai')
  assert.deepEqual(network.args, { action: 'rules', rules: [{ urlPattern: 'https://example.test/*', stage: 'response', action: 'fulfill', body: 'ok' }] })
})

test('advanced calls reject missing generations, invalid fields, and unsafe coordinates before dispatch', async () => {
  const f = fixture()
  assert.equal((await f.tools[13].execute({ action: 'status' })).code, 'GENERATION_UNAVAILABLE')
  assert.equal((await f.tools[13].execute({ sessionGeneration: 4, controlGeneration: 9, action: 'interact', kind: 'tap', x: Infinity, y: 1 })).code, 'INVALID_ARGUMENT')
  assert.equal((await f.tools[14].execute({ sessionGeneration: 4, controlGeneration: 9, action: 'resolve', pausedId: 'p1', decision: 'continue', body: 'no' })).code, 'INVALID_ARGUMENT')
  assert.equal((await f.tools[15].execute({ sessionGeneration: 4, controlGeneration: 9, action: 'latest' })).code, 'INVALID_ARGUMENT')
  assert.equal(f.calls.length, 0)
  assert.equal(resolveArtifactPath('/opt/browser/out/network/../private', 'network', 'body'), '')
  assert.equal(resolveArtifactPath('/opt/browser/out/selection/picture.png', 'network', 'body'), '')
})

test('debug emulate clears without dimensions and rejects mixed or malformed arguments', async () => {
  const f = fixture()
  f.responses.push(response({ method: 'debug' }))
  const cleared = await f.tools[13].execute({
    sessionGeneration: 4, controlGeneration: 9, action: 'emulate', clear: true
  })
  assert.equal(cleared.ok, true, JSON.stringify(cleared))
  assert.deepEqual(parseCall(f.calls[0]).args, { action: 'emulate', clear: true })

  f.responses.push(response({ method: 'debug' }))
  const emulated = await f.tools[13].execute({
    sessionGeneration: 4, controlGeneration: 9, action: 'emulate', width: 240, height: 2560, mobile: false
  })
  assert.equal(emulated.ok, true, JSON.stringify(emulated))
  assert.deepEqual(parseCall(f.calls[1]).args, { action: 'emulate', width: 240, height: 2560, mobile: false })

  for (const args of [
    { action: 'emulate', clear: true, width: 800 },
    { action: 'emulate', clear: true, height: 600 },
    { action: 'emulate', clear: true, mobile: true },
    { action: 'emulate', clear: true, unknown: false },
    { action: 'emulate', clear: 1 },
    { action: 'emulate', width: 239, height: 600, mobile: true },
    { action: 'emulate', width: 800, height: 2561, mobile: true },
    { action: 'emulate', width: 800.5, height: 600, mobile: true },
    { action: 'emulate', width: 800, height: 600, mobile: true, unknown: false },
    { action: 'emulate', clear: false },
  ]) {
    const result = await f.tools[13].execute({ sessionGeneration: 4, controlGeneration: 9, ...args })
    assert.equal(result.code, 'INVALID_ARGUMENT', JSON.stringify({ args, result }))
  }
  assert.equal(f.calls.length, 2)
});

test('browser_control request is a bounded approval-only exception when AI control is disabled', async () => {
  const f = fixture()
  const tool = f.tools.find((item) => item.name === 'browser_control')
  assert.ok(tool)
  f.responses.push({ version: 1, requestId: '$request', code: 'NO_ACTIVE_SESSION', executed: false,
    sessionGeneration: null, controlGeneration: null, state: 'CLOSED', owner: 'none', data: {} })
  const noSession = await tool.execute({ action: 'request', summary: 'Need to inspect one detail' })
  assert.equal(noSession.code, 'NO_ACTIVE_SESSION')
  assert.equal(parseCall(f.calls[0]).method, 'control')
  assert.deepEqual(parseCall(f.calls[0]).args, { action: 'request', summary: 'Need to inspect one detail' })
  assert.equal(parseCall(f.calls[0]).owner, undefined)
  assert.equal(parseCall(f.calls[0]).sessionGeneration, undefined)
  assert.equal(validateArgs('control', { action: 'request', summary: '   ' }) !== '', true)
  assert.equal(validateArgs('control', { action: 'request', summary: 'x'.repeat(1001) }) !== '', true)
  assert.equal(validateArgs('control', { action: 'cancel', approvalId: 'A'.repeat(32) }) !== '', true)

  f.responses.push({ version: 1, requestId: '$request', code: 'OK', executed: false,
    sessionGeneration: 8, controlGeneration: 11, state: 'RUNNING', owner: 'human',
    data: { approvalId: 'a'.repeat(32), status: 'PENDING', summary: 'Need to inspect one detail', taskId: 'task_01',
      createdAtMillis: 1000, expiresAtMillis: 121000, coalesced: true } })
  const pending = await tool.execute({ action: 'request', summary: 'Need to inspect one detail', taskId: 'task_01' })
  assert.equal(pending.code, 'OK')
  assert.equal(pending.executed, 'false')
  assert.equal(parseCall(f.calls[1]).args.taskId, 'task_01')

  const base = { version: 1, requestId: 'control-response', executed: false, sessionGeneration: 8,
    controlGeneration: 11, state: 'RUNNING', owner: 'human', data: {} }
  for (const [action, code] of [['request', 'CONTROL_TRANSFER_PENDING'], ['request', 'APPROVAL_SURFACE_UNAVAILABLE'],
    ['status', 'APPROVAL_NOT_FOUND'], ['cancel', 'APPROVAL_NOT_PENDING'], ['cancel', 'APPROVAL_EXPIRED'],
    ['status', 'DEADLINE_EXPIRED']]) {
    const value = { ...base, code }
    const args = action === 'request' ? { action, summary: 'Need to inspect one detail', taskId: 'task_01' }
      : { action, approvalId: 'a'.repeat(32) }
    assert.ok(safeResponse(`${JSON.stringify(value)}\n`, value.requestId, 'control', action, args))
  }
  for (const [action, status] of [['request', 'PENDING'], ['status', 'EXPIRED'], ['cancel', 'CANCELLED']]) {
    const args = action === 'request' ? { action, summary: 'Need a human-approved handoff', taskId: 'task_01' }
      : { action, approvalId: 'a'.repeat(32) }
    const data = { approvalId: 'a'.repeat(32), status, summary: 'Need a human-approved handoff', taskId: 'task_01',
      createdAtMillis: 1000, expiresAtMillis: 121000, coalesced: false }
    const value = { ...base, requestId: `control-${action}-${status}`, code: 'OK', data }
    assert.ok(safeResponse(`${JSON.stringify(value)}\n`, value.requestId, 'control', action, args))
  }
  const wrongAction = { ...base, code: 'APPROVAL_EXPIRED' }
  assert.equal(safeResponse(`${JSON.stringify(wrongAction)}\n`, wrongAction.requestId, 'control', 'request',
    { action: 'request', summary: 'Need a human-approved handoff' }), null)
  const transfer = { ...base, code: 'CONTROL_TRANSFER_PENDING' }
  assert.ok(safeResponse(`${JSON.stringify(transfer)}\n`, transfer.requestId, 'click'))
  assert.equal(safeResponse(`${JSON.stringify(transfer)}\n`, transfer.requestId, 'observe'), null)
  assert.equal(safeResponse(`${JSON.stringify(transfer)}\n`, transfer.requestId, 'status'), null)
});

test('page list is read-only while mutations require current AI ownership generations', async () => {
  const f = fixture()
  const pageId = 'pg-0123456789abcdefabcd'
  const pageData = {
    pages: [{ pageId, title: 'Example', url: 'https://example.test/', active: true }],
    activePageId: pageId,
    limit: 4
  }
  f.responses.push(response({ method: 'page' }, pageData))
  await f.tools[16].execute({ action: 'list', sessionGeneration: 4 })
  const list = parseCall(f.calls[0])
  assert.equal(list.method, 'page')
  assert.deepEqual(list.args, { action: 'list' })
  assert.equal(list.sessionGeneration, 4)
  assert.equal('controlGeneration' in list, false)
  assert.equal('owner' in list, false)

  for (const action of ['new', 'switch', 'close']) {
    f.responses.push(response({ method: 'page' }, pageData))
    await f.tools[16].execute({
      action,
      sessionGeneration: 4,
      controlGeneration: 9,
      ...(action === 'switch' || action === 'close' ? { pageId } : {})
    })
    const sent = parseCall(f.calls.at(-1))
    assert.equal(sent.owner, 'ai')
    assert.equal(sent.sessionGeneration, 4)
    assert.equal(sent.controlGeneration, 9)
  }

  assert.equal((await f.tools[16].execute({ action: 'close', sessionGeneration: 4, controlGeneration: 9, pageId: '../x' })).code, 'INVALID_ARGUMENT')
  assert.equal((await f.tools[16].execute({ action: 'new', sessionGeneration: 4, controlGeneration: 0 })).code, 'GENERATION_UNAVAILABLE')
  assert.equal(f.calls.length, 4)
})

test('downloads list uses session only, and mutation without control cannot reach the CLI', async () => {
  const { tools, calls, responses } = fixture()
  const tool = tools.find((item) => item.name === 'browser_file')
  responses.push({ ...response({ method: 'file' }, { items: [], nextCursor: null, truncated: false }), executed: false })
  const listed = await tool.execute({ action: 'list', sessionGeneration: 4 })
  assert.equal(listed.code, 'OK')
  const request = JSON.parse(calls[0].argv[2])
  assert.equal(request.method, 'file')
  assert.equal(request.controlGeneration, undefined)
  assert.equal(request.owner, undefined)
  const rejected = await tool.execute({ action: 'start', url: 'https://example.test/a.pdf', sessionGeneration: 4 })
  assert.equal(rejected.code, 'GENERATION_UNAVAILABLE')
  assert.equal(calls.length, 1)
  assert.equal((await tool.execute({ action: 'list', sessionGeneration: 4, controlGeneration: 9 })).code, 'INVALID_ARGUMENT')
  assert.equal(calls.length, 1)
})

test('browser_serve is appended and uses the owned/external service CLI contract without page generations', async () => {
  const serviceId = 'a'.repeat(32)
  const calls = []
  const tools = buildTools({
    defineTool: (tool) => tool,
    dependencies: {
      serve: {
        execFile: (file, argv, options, callback) => {
          calls.push({ file, argv, options })
          callback(null, `${JSON.stringify({
            version: 1,
            requestId: 'b'.repeat(24),
            code: 'OK',
            executed: true,
            state: 'CLOSED',
            data: {
              serviceId,
              kind: 'owned-static',
              projectId: 'c'.repeat(24),
              url: 'http://127.0.0.1:43127/',
              host: '127.0.0.1',
              port: 43127,
              ready: true,
              leaseExpiresAtMillis: 90000,
              leaseMs: 60000
            }
          })}\n`)
        }
      }
    }
  })
  const result = await tools[17].execute({ action: 'start', dir: '/tmp/site', leaseMs: 60000 })
  assert.equal(result.ok, true, JSON.stringify(result))
  assert.equal(result.sessionGenerationAvailable, false)
  assert.equal(result.controlGenerationAvailable, false)
  assert.equal(result.path, '')
  assert.equal(calls[0].file, '/opt/browser/bin/browser')
  assert.deepEqual(calls[0].argv, ['serve', 'start', '--dir', '/tmp/site', '--lease-ms', '60000'])
  assert.equal(calls[0].options.shell, undefined)
})
