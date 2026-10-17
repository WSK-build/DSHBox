'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const { createRequire } = require('node:module')
const { pathToFileURL } = require('node:url')
const { createSelectionIntegration } = require('../../assets/plugins/browser-use/plugin/lib/selection-integration')
const { OUTPUT, buildTools } = require('../../assets/plugins/browser-use/plugin/lib/tools')

const fixtureRoot = path.resolve(__dirname, '../../../../../tools/browser-p0/dsh-api-contract')
const fixtureRequire = createRequire(path.join(fixtureRoot, 'package.json'))

async function publishedRuntime() {
  const toolsEntry = fixtureRequire.resolve('@deepseek-ai/dsh-tools')
  const cordisEntry = fixtureRequire.resolve('@deepseek-ai/cordis')
  const scopeEntry = fixtureRequire.resolve('@deepseek-ai/dsh-scope')
  const [toolsApi, cordis, scopeApi] = await Promise.all([
    import(pathToFileURL(toolsEntry).href), import(pathToFileURL(cordisEntry).href),
    import(pathToFileURL(scopeEntry).href),
  ])
  return { toolsApi, cordis, scopeApi }
}

const selectionId = (suffix) => `00000000-0000-4000-8000-${String(suffix).padStart(12, '0')}`

test('status tool reports the current binding and bounded stopped-relay reason', async () => {
  const tools = buildTools({ defineTool: value => value, dependencies: {
    selectionIntegration: { status: () => ({ selection: 'INACTIVE', taskFeedback: 'ACTIVE', selectionReason: 'CLI_USAGE' }) },
    execFile: (_file, argv, _options, callback) => {
      const request = JSON.parse(argv[2])
      callback(null, JSON.stringify({ version: 1, requestId: request.requestId, code: 'OK', executed: false,
        state: 'IDLE', sessionGeneration: 7, controlGeneration: 2, owner: 'ai',
        data: { automationEnabled: true, owner: 'ai' } }), '')
    },
  } })
  const reply = await tools.find(tool => tool.name === 'browser_status').execute({}, { agent: { id: 'status-agent' } })
  assert.equal(reply.code, 'OK')
  assert.deepEqual(JSON.parse(reply.dataJson).browserBinding,
    { selection: 'INACTIVE', taskFeedback: 'ACTIVE', selectionReason: 'CLI_USAGE' })
})

async function flush() {
  for (let i = 0; i < 10; i++) await Promise.resolve()
}

function delay(ms) { return new Promise((resolve) => setTimeout(resolve, ms)) }

function fakeTimers() {
  let time = 100
  let nextId = 0
  const tasks = new Map()
  return {
    now: () => time,
    schedule(fn, delayMs) {
      const key = ++nextId
      tasks.set(key, { fn, at: time + delayMs })
      return key
    },
    cancel(key) { tasks.delete(key) },
    async advance(delayMs) {
      const end = time + delayMs
      while (true) {
        const due = [...tasks.entries()].filter(([, task]) => task.at <= end).sort((a, b) => a[1].at - b[1].at)[0]
        if (!due) break
        tasks.delete(due[0])
        time = due[1].at
        due[1].fn()
        await flush()
      }
      time = end
      await flush()
    },
    pending: () => tasks.size,
  }
}

function normalized(code, sessionGeneration, extra = {}) {
  return {
    ok: code === 'OK',
    code,
    executed: code === 'OK' ? 'false' : 'false',
    sessionGeneration,
    sessionGenerationAvailable: sessionGeneration > 0,
    state: 'RUNNING',
    dataJson: '{}',
    ...extra,
  }
}

function noTaskFeedback(afterSequence) {
  return normalized('OK', 7, { executed: 'false', dataJson: JSON.stringify({
    afterSequence, nextSequence: afterSequence, items: [],
  }) })
}

for (const openData of [
  { event: 'opened', selectionSequence: 1 },
  { event: 'navigation_accepted', selectionSequence: 1, navigationId: 2, loadState: 'accepted',
    requestedUrl: 'https://example.com/', requestedUrlQueryRedacted: false, requestedUrlFragmentRedacted: false,
    urlBefore: null, urlBeforeQueryRedacted: false, urlBeforeFragmentRedacted: false,
    urlAfter: null, urlAfterQueryRedacted: false, urlAfterFragmentRedacted: false, committed: false },
]) {
test(`module integration binds ${openData.event}, relays selections, writes receipt, and disposes`, async () => {
  let latest = { selectionId: selectionId(1), selectionSequence: 1, mode: 'area', comment: 'before relay' }
  const prompts = []
  const selectionRequests = []
  const cleanupCallbacks = []
  const browserRequests = []
  const toolWireRequests = []
  const exec = { agent: { id: 'session-actual-42' }, signal: new AbortController().signal }
  const sessionController = {
    async prompt(request, signal) {
      prompts.push({ request, signal })
      return { accepted: true }
    },
  }
  const ctx = {
    sessionController,
    effect(registerCleanup) { cleanupCallbacks.push(registerCleanup()) },
  }
  const browserRequest = async (method, args, envelope, dependencies = {}) => {
    browserRequests.push({ method, args, envelope, dependencies })
    if (method === 'status') return normalized('OK', 0, {
      state: 'CLOSED', sessionGeneration: 0, sessionGenerationAvailable: false,
      dataJson: JSON.stringify({ automationEnabled: true, owner: 'none' }),
    })
    if (method === 'open') return normalized('OK', 7, { executed: 'true', state: 'RUNNING' })
    if (method === 'selection' && args.action === 'pending') {
      selectionRequests.push({ args, envelope, signal: dependencies.signal })
      assert.equal(Number.isSafeInteger(args.afterSequence), true)
      assert.equal(args.destinationSessionKey, exec.agent.id)
      return latest.selectionSequence > args.afterSequence
        ? normalized('OK', 7, { dataJson: JSON.stringify(latest) })
        : normalized('NOT_FOUND', 7)
    }
    if (method === 'selection' && args.action === 'task_pending') return noTaskFeedback(args.afterSequence)
    if (method === 'selection' && args.action === 'receipt') {
      selectionRequests.push({ args, envelope, signal: dependencies.signal })
      return normalized('OK', 7, { executed: 'true' })
    }
    if (method === 'selection' && args.action === 'claim') {
      selectionRequests.push({ args, envelope, signal: dependencies.signal })
      return normalized('CLAIMED', 7, { executed: 'true' })
    }
    if (method === 'selection' && args.action === 'admit') {
      selectionRequests.push({ args, envelope, signal: dependencies.signal })
      return normalized('ADMITTING', 7, { executed: 'true' })
    }
    if (method === 'selection' && args.action === 'admit') return normalized('ADMITTING', 7, { executed: 'true' })
    throw new Error(`Unexpected request ${method}/${args.action || ''}`)
  }
  const integration = createSelectionIntegration(ctx, {
    browserRequest,
  })
  assert.ok(integration)
  const integrationCalls = []
  const wrappedIntegration = {
    ...integration,
    afterOpen(actualExec, result) {
      integrationCalls.push({ actualExec, result, relay: integration.afterOpen(actualExec, result) })
      return integrationCalls.at(-1).relay
    },
  }
  const execFile = (_file, argv, _options, callback) => {
    const method = argv[0]
    const request = JSON.parse(argv[2])
    toolWireRequests.push({ method, request })
    const base = {
      version: 1,
      requestId: request.requestId,
      code: 'OK',
      executed: method === 'open',
      sessionGeneration: method === 'open' ? 7 : null,
      controlGeneration: method === 'open' ? 2 : null,
      state: method === 'status' ? 'CLOSED' : 'RUNNING',
      owner: method === 'status' ? 'none' : 'ai',
      data: method === 'status' ? { automationEnabled: true, owner: 'none' } : {},
    }
    if (method === 'open') base.data = openData
    callback(null, JSON.stringify(base), '')
  }
  const definitions = buildTools({ defineTool: (definition) => definition,
    dependencies: { selectionIntegration: wrappedIntegration, execFile } })
  const open = definitions.find((tool) => tool.name === 'browser_open')
  assert.ok(open)

  const openResult = await open.execute({}, exec)
  assert.equal(openResult.code, 'OK', JSON.stringify(openResult))
  assert.equal(openResult.ok, true, JSON.stringify(openResult))
  assert.deepEqual(toolWireRequests.map((request) => request.method), ['status', 'open'])
  assert.equal(integrationCalls.length, 1, JSON.stringify(openResult))
  assert.equal(integrationCalls[0].actualExec, exec)
  assert.equal(integrationCalls[0].relay.code, 'ACTIVE', JSON.stringify(integrationCalls[0].relay))
  await flush()
  assert.equal(selectionRequests.length, 0) // open response sequence is the baseline; no race-prone startup read

  // A selection that existed at open's baseline is not silently replayed.
  await delay(2_100)
  assert.equal(prompts.length, 0)
  assert.equal(selectionRequests[0].args.afterSequence, 1)

  latest = { selectionId: selectionId(2), selectionSequence: 2, mode: 'element', comment: 'explicitly shared now' }
  await delay(2_100)

  assert.equal(prompts.length, 1)
  assert.equal(prompts[0].request.sessionId, 'session-actual-42')
  assert.equal(prompts[0].request.mode, 'queue')
  assert.ok(prompts[0].request.content.some((part) => part.type === 'text' && part.text.includes('explicitly shared now')))
  assert.equal(selectionRequests.at(-1).args.action, 'receipt')
  assert.equal(selectionRequests.at(-1).args.selectionId, selectionId(2))
  assert.equal(selectionRequests.at(-1).args.deliveryCode, 'DELIVERED_TEXT_ONLY')
  assert.equal(selectionRequests.at(-1).envelope.sessionGeneration, 7)
  assert.equal(selectionRequests.at(-1).args.imageIncluded, false)
  cleanupCallbacks.forEach((cleanup) => cleanup())
  await flush()
  assert.equal(prompts[0].signal.aborted, true)
  const callsAfterDispose = selectionRequests.length
  await delay(2_100)
  assert.equal(selectionRequests.length, callsAfterDispose)
})
}

test('tool surfaces a cross-session relay conflict and keeps that generation tombstoned until a newer generation', async () => {
  const timers = fakeTimers()
  const reads = []
  const prompts = []
  const execA = { agent: { id: 'session-a' }, signal: new AbortController().signal }
  const execB = { agent: { id: 'session-b' }, signal: new AbortController().signal }
  const ctx = {
    sessionController: { async prompt(request) { prompts.push(request); return { accepted: true } } },
    effect() {},
  }
  let openCount = 0
  let latest = null
  const browserRequest = async (method, args) => {
    if (method === 'status') {
      return openCount === 0
        ? normalized('OK', 0, { state: 'CLOSED', sessionGeneration: 0, sessionGenerationAvailable: false,
          dataJson: JSON.stringify({ automationEnabled: true, owner: 'none' }) })
        : normalized('OK', 7, { state: 'RUNNING', controlGeneration: 2,
          dataJson: JSON.stringify({ automationEnabled: true, owner: 'ai' }) })
    }
    if (method === 'open') {
      openCount++
      return normalized('OK', 7, {
        executed: openCount === 1 ? 'true' : 'false', state: 'RUNNING', controlGeneration: 2,
        dataJson: JSON.stringify({ event: 'opened', selectionSequence: openCount === 1 ? 1 : openCount === 2 ? 5 : 20 }),
      })
    }
    if (method === 'selection' && args.action === 'pending') {
      reads.push(args.afterSequence)
      return latest && latest.selectionSequence > args.afterSequence
        ? normalized('OK', 7, { dataJson: JSON.stringify(latest) })
        : normalized('NOT_FOUND', 7)
    }
    if (method === 'selection' && args.action === 'task_pending') return noTaskFeedback(args.afterSequence)
    if (method === 'selection' && args.action === 'receipt') return normalized('OK', 7, { executed: 'true' })
    if (method === 'selection' && args.action === 'claim') return normalized('CLAIMED', 7, { executed: 'true' })
    if (method === 'selection' && args.action === 'admit') return normalized('ADMITTING', 7, { executed: 'true' })
    throw new Error(`Unexpected request ${method}/${args.action || ''}`)
  }
  const integration = createSelectionIntegration(ctx, {
    browserRequest,
    relayOptions: { now: timers.now, schedule: timers.schedule, cancel: timers.cancel },
    taskFeedbackOptions: { now: timers.now, schedule: timers.schedule, cancel: timers.cancel },
  })
  let toolOpenCount = 0
  const execFile = (_file, argv, _options, callback) => {
    const method = argv[0]
    const request = JSON.parse(argv[2])
    const response = {
      version: 1,
      requestId: request.requestId,
      code: 'OK',
      executed: false,
      sessionGeneration: method === 'status' && toolOpenCount > 0 ? 7 : null,
      controlGeneration: method === 'status' && toolOpenCount > 0 ? 2 : null,
      state: method === 'status' && toolOpenCount > 0 ? 'RUNNING' : 'CLOSED',
      owner: method === 'status' && toolOpenCount > 0 ? 'ai' : 'none',
      data: method === 'status' && toolOpenCount > 0
        ? { automationEnabled: true, owner: 'ai' }
        : { automationEnabled: true, owner: 'none' },
    }
    if (method === 'open') {
      toolOpenCount++
      response.executed = toolOpenCount === 1
      response.sessionGeneration = 7
      response.controlGeneration = 2
      response.state = 'RUNNING'
      response.owner = 'ai'
      response.data = { event: 'opened', selectionSequence: toolOpenCount === 1 ? 1 : toolOpenCount === 2 ? 5 : 20 }
    }
    callback(null, JSON.stringify(response), '')
  }
  const tools = buildTools({ defineTool: (definition) => definition,
    dependencies: { selectionIntegration: integration, browserRequest, execFile } })
  const open = tools.find((tool) => tool.name === 'browser_open')

  const first = await open.execute({}, execA)
  assert.equal(first.ok, true, JSON.stringify(first))
  assert.equal(first.code, 'OK')
  assert.equal(first.executed, 'true')
  await timers.advance(2_000)
  assert.deepEqual(reads, [1])

  const conflict = await open.execute({}, execB)
  assert.equal(conflict.ok, true)
  assert.equal(conflict.code, 'OK')
  assert.equal(conflict.executed, 'false')
  assert.match(conflict.text, /Browser open succeeded.*SESSION_BINDING_CONFLICT/)
  assert.equal(Object.keys(conflict).every((key) => Object.hasOwn(OUTPUT.properties, key)), true)
  assert.equal(OUTPUT.additionalProperties, false)
  await timers.advance(2_000)
  assert.deepEqual(reads, [1]) // The previously bound relay was stopped.
  assert.equal(prompts.length, 0)

  // A same-generation reopen cannot clear the conflict tombstone.
  const reopenedA = await open.execute({}, execA)
  assert.equal(reopenedA.ok, true)
  await timers.advance(2_000)
  assert.equal(integration.status().selection, 'SESSION_BINDING_CONFLICT')
  assert.deepEqual(reads, [1])

  const newerOpen = normalized('OK', 8, { executed: 'true', state: 'RUNNING',
    dataJson: JSON.stringify({ event: 'opened', selectionSequence: 20 }) })
  assert.equal(integration.afterOpen(execA, newerOpen).code, 'ACTIVE')
  await timers.advance(2_000)
  assert.equal(reads.at(-1), 20)
  assert.equal(prompts.length, 0)
  integration.dispose()
})

test('cross-session conflict aborts relay work but cannot retract an already admitted Session prompt', async () => {
  const timers = fakeTimers()
  let releasePrompt
  const promptRequests = []
  const receiptRequests = []
  let pendingReads = 0
  const execA = { agent: { id: 'session-a' }, signal: new AbortController().signal }
  const execB = { agent: { id: 'session-b' }, signal: new AbortController().signal }
  const ctx = {
    sessionController: {
      prompt(request, signal) {
        promptRequests.push({ request, signal })
        return new Promise((resolve) => { releasePrompt = resolve })
      },
    },
    effect() {},
  }
  let openCount = 0
  const browserRequest = async (method, args) => {
    if (method === 'status') return openCount === 0
      ? normalized('OK', 0, { state: 'CLOSED', sessionGeneration: 0, sessionGenerationAvailable: false,
        dataJson: JSON.stringify({ automationEnabled: true, owner: 'none' }) })
      : normalized('OK', 7, { state: 'RUNNING', controlGeneration: 2,
        dataJson: JSON.stringify({ automationEnabled: true, owner: 'ai' }) })
    if (method === 'open') {
      openCount++
      return normalized('OK', 7, {
        executed: openCount === 1 ? 'true' : 'false', state: 'RUNNING', controlGeneration: 2,
        dataJson: JSON.stringify({ event: 'opened', selectionSequence: openCount }),
      })
    }
    if (method === 'selection' && args.action === 'pending') {
      pendingReads++
      return normalized('OK', 7, {
        dataJson: JSON.stringify({ selectionId: selectionId(10), selectionSequence: 2, mode: 'area', comment: 'shared' }),
      })
    }
    if (method === 'selection' && args.action === 'task_pending') return noTaskFeedback(args.afterSequence)
    if (method === 'selection' && args.action === 'receipt') {
      receiptRequests.push(args)
      return normalized('OK', 7, { executed: 'true' })
    }
    if (method === 'selection' && args.action === 'claim') return normalized('CLAIMED', 7, { executed: 'true' })
    if (method === 'selection' && args.action === 'admit') return normalized('ADMITTING', 7, { executed: 'true' })
    throw new Error(`Unexpected request ${method}/${args.action || ''}`)
  }
  const integration = createSelectionIntegration(ctx, {
    browserRequest,
    relayOptions: { now: timers.now, schedule: timers.schedule, cancel: timers.cancel },
    taskFeedbackOptions: { now: timers.now, schedule: timers.schedule, cancel: timers.cancel },
  })
  let toolOpenCount = 0
  const execFile = (_file, argv, _options, callback) => {
    const method = argv[0]
    const request = JSON.parse(argv[2])
    const response = {
      version: 1, requestId: request.requestId, code: 'OK', executed: false,
      sessionGeneration: method === 'status' && toolOpenCount > 0 ? 7 : null,
      controlGeneration: method === 'status' && toolOpenCount > 0 ? 2 : null,
      state: method === 'status' && toolOpenCount > 0 ? 'RUNNING' : 'CLOSED',
      owner: method === 'status' && toolOpenCount > 0 ? 'ai' : 'none',
      data: { automationEnabled: true, owner: method === 'status' && toolOpenCount > 0 ? 'ai' : 'none' },
    }
    if (method === 'open') {
      toolOpenCount++
      response.executed = toolOpenCount === 1
      response.sessionGeneration = 7
      response.controlGeneration = 2
      response.state = 'RUNNING'
      response.owner = 'ai'
      response.data = { event: 'opened', selectionSequence: toolOpenCount }
    }
    callback(null, JSON.stringify(response), '')
  }
  const tools = buildTools({ defineTool: (definition) => definition,
    dependencies: { selectionIntegration: integration, browserRequest, execFile } })
  const open = tools.find((tool) => tool.name === 'browser_open')
  await open.execute({}, execA)
  await timers.advance(2_000)
  assert.equal(promptRequests.length, 1, JSON.stringify({ promptRequests: promptRequests.length, pendingReads, receiptRequests, timerCount: timers.pending() }))
  assert.equal(promptRequests[0].signal.aborted, false)

  const conflict = await open.execute({}, execB)
  assert.equal(conflict.ok, true)
  assert.match(conflict.text, /already submitted to DSH may still arrive.*cannot be retracted/)
  assert.equal(promptRequests[0].signal.aborted, true)
  releasePrompt({ accepted: true })
  await flush()
  await timers.advance(4_000)
  assert.equal(receiptRequests.length, 0) // The stopped relay cannot record a stale completion.
  integration.dispose()
})

test('an afterOpen exception uses the fail-closed integration hook before warning the caller', async () => {
  const timers = fakeTimers()
  const taskTimers = fakeTimers()
  const reads = []
  const taskReads = []
  const execA = { agent: { id: 'session-a' }, signal: new AbortController().signal }
  const execB = { agent: { id: 'session-b' }, signal: new AbortController().signal }
  const ctx = { sessionController: { prompt: async () => ({ accepted: true }) }, effect() {} }
  const integration = createSelectionIntegration(ctx, {
    browserRequest: async (method, args) => {
      if (method === 'selection' && args.action === 'pending') {
        reads.push(args.afterSequence)
        return normalized('NOT_FOUND', 7)
      }
      if (method === 'selection' && args.action === 'task_pending') {
        taskReads.push(args.afterSequence)
        return noTaskFeedback(args.afterSequence)
      }
      if (method === 'selection' && args.action === 'receipt') return normalized('OK', 7, { executed: 'true' })
      if (method === 'selection' && args.action === 'claim') return normalized('CLAIMED', 7, { executed: 'true' })
      if (method === 'selection' && args.action === 'admit') return normalized('ADMITTING', 7, { executed: 'true' })
      throw new Error(`Unexpected request ${method}/${args.action || ''}`)
    },
    relayOptions: { now: timers.now, schedule: timers.schedule, cancel: timers.cancel },
    taskFeedbackOptions: { now: taskTimers.now, schedule: taskTimers.schedule, cancel: taskTimers.cancel },
  })
  const confirmedOpen = normalized('OK', 7, {
    executed: 'true', state: 'RUNNING',
    dataJson: JSON.stringify({ event: 'opened', selectionSequence: 1 }),
  })
  const opened = integration.afterOpen(execA, confirmedOpen)
  assert.equal(opened.code, 'ACTIVE')
  assert.deepEqual(Object.keys(opened).sort(), ['code', 'expiresInMillis', 'sessionGeneration'])

  const execFile = (_file, argv, _options, callback) => {
    const request = JSON.parse(argv[2])
    const response = argv[0] === 'status'
      ? { version: 1, requestId: request.requestId, code: 'OK', executed: false, sessionGeneration: 7,
        controlGeneration: 2, state: 'RUNNING', owner: 'ai', data: { automationEnabled: true, owner: 'ai' } }
      : { version: 1, requestId: request.requestId, code: 'OK', executed: false, sessionGeneration: 7,
        controlGeneration: 2, state: 'RUNNING', owner: 'ai', data: { event: 'opened', selectionSequence: 2 } }
    callback(null, JSON.stringify(response), '')
  }
  const selectionIntegration = {
    afterOpen() { throw new Error('integration dispatch failure') },
    rejectOpen: integration.rejectOpen,
  }
  const tools = buildTools({ defineTool: (definition) => definition,
    dependencies: { selectionIntegration, execFile } })
  const result = await tools.find((tool) => tool.name === 'browser_open').execute({}, execB)
  assert.equal(result.ok, true)
  assert.equal(result.code, 'OK')
  assert.equal(result.executed, 'false')
  assert.match(result.text, /SESSION_BINDING_CONFLICT.*prior relay was stopped/i)
  assert.equal(Object.keys(result).every((key) => Object.hasOwn(OUTPUT.properties, key)), true)
  await timers.advance(2_000)
  assert.deepEqual(reads, [])
  await taskTimers.advance(2_000)
  assert.deepEqual(taskReads, [], 'a same-generation owner conflict stops both relays')
  integration.dispose()
})

test('an invalid open response cannot stop an existing relay through rejectOpen', async () => {
  const timers = fakeTimers()
  const taskTimers = fakeTimers()
  const reads = []
  const taskReads = []
  const exec = { agent: { id: 'session-a' }, signal: new AbortController().signal }
  const integration = createSelectionIntegration({
    sessionController: { prompt: async () => ({ accepted: true }) }, effect() {},
  }, {
    browserRequest: async (method, args) => {
      if (method === 'selection' && args.action === 'pending') {
        reads.push(args.afterSequence)
        return normalized('NOT_FOUND', 7)
      }
      if (method === 'selection' && args.action === 'task_pending') {
        taskReads.push(args.afterSequence)
        return noTaskFeedback(args.afterSequence)
      }
      throw new Error(`Unexpected request ${method}/${args.action || ''}`)
    },
    relayOptions: { now: timers.now, schedule: timers.schedule, cancel: timers.cancel },
    taskFeedbackOptions: { now: taskTimers.now, schedule: taskTimers.schedule, cancel: taskTimers.cancel },
  })
  assert.equal(integration.afterOpen(exec, normalized('OK', 7, {
    executed: 'true', state: 'RUNNING',
    dataJson: JSON.stringify({ event: 'opened', selectionSequence: 1 }),
  })).code, 'ACTIVE')
  const rejected = integration.rejectOpen(exec, normalized('OK', 7, {
    executed: 'true', state: 'RUNNING', dataJson: '{}',
  }))
  assert.deepEqual(rejected, { code: 'BINDING_UNAVAILABLE', relayStopped: false })
  assert.equal(rejected.relayStopped, false)
  await timers.advance(2_000)
  assert.deepEqual(reads, [1])
  await taskTimers.advance(2_000)
  assert.deepEqual(taskReads, [0])
  integration.dispose()
})

test('integration reports unavailable binding without Session Controller and keeps basic construction available', () => {
  const controller = { prompt: async () => ({ accepted: true }) }
  assert.equal(createSelectionIntegration({ sessionController: controller }, {}), null)
  const integration = createSelectionIntegration({ effect() {} }, {})
  assert.equal(integration.status().taskFeedback, 'SESSION_CONTROLLER_UNAVAILABLE')
  integration.dispose()
  assert.equal(createSelectionIntegration({}, {}), null)
  const available = createSelectionIntegration({ sessionController: controller, effect() {} }, {})
  assert.equal(available.status().selection, 'UNBOUND')
  assert.equal(available.status().taskFeedback, 'UNBOUND')
  available.dispose()
})

test('manager conflict never delegates a same-generation open to inactive child relays', async () => {
  const selectionTimers = fakeTimers()
  const taskTimers = fakeTimers()
  let selectionReads = 0
  let taskReads = 0
  let claims = 0
  let prompts = 0
  const controller = { async prompt() { prompts++; return { accepted: true } } }
  const integration = createSelectionIntegration({ sessionController: controller, effect() {} }, {
    browserRequest: async (_method, args, envelope) => {
      if (args.action === 'pending') { selectionReads++; return normalized('FAILED', envelope.sessionGeneration) }
      if (args.action === 'task_pending') { taskReads++; return normalized('FAILED', envelope.sessionGeneration) }
      if (args.action === 'claim') { claims++; return normalized('CLAIMED', envelope.sessionGeneration) }
      throw new Error(`Unexpected request ${args.action}`)
    },
    relayOptions: { now: selectionTimers.now, schedule: selectionTimers.schedule, cancel: selectionTimers.cancel },
    taskFeedbackOptions: { now: taskTimers.now, schedule: taskTimers.schedule, cancel: taskTimers.cancel },
  })
  const open = (generation = 7) => normalized('OK', generation, {
    executed: 'true', state: 'RUNNING', sessionGenerationAvailable: true,
    dataJson: JSON.stringify({ event: 'opened', selectionSequence: 0 }),
  })
  assert.equal(integration.afterOpen({ agent: { id: 'agent-a' } }, open()).code, 'ACTIVE')
  await selectionTimers.advance(2_000)
  await taskTimers.advance(2_000)
  assert.equal(integration.status().selection, 'INACTIVE')
  assert.equal(integration.status().taskFeedback, 'INACTIVE')
  const before = { selectionReads, taskReads, claims, prompts }

  assert.equal(integration.afterOpen({ agent: { id: 'agent-b' } }, open()).code, 'SESSION_BINDING_CONFLICT')
  await selectionTimers.advance(2_000)
  await taskTimers.advance(2_000)
  assert.deepEqual({ selectionReads, taskReads, claims, prompts }, before)

  assert.equal(integration.afterOpen({ agent: { id: 'agent-a' } }, open(8)).code, 'ACTIVE')
  await selectionTimers.advance(2_000)
  await taskTimers.advance(2_000)
  assert.equal(selectionReads, before.selectionReads + 1)
  assert.equal(taskReads, before.taskReads + 1)
  assert.equal(claims, 0)
  assert.equal(prompts, 0)
  integration.dispose()
})

test('confirmed close without Session Controller clears late binding and disposed integration rejects callbacks', () => {
  const listeners = new Set()
  let cleanup
  let pendingReads = 0
  const ctx = {
    reflect: { get: () => undefined },
    on(_event, listener) { listeners.add(listener) },
    off(_event, listener) { listeners.delete(listener) },
    effect(register) { cleanup = register() },
  }
  const integration = createSelectionIntegration(ctx, {
    browserRequest: async () => { pendingReads++; return normalized('FAILED', 7) },
  })
  const exec = { agent: { id: 'session-a' } }
  assert.equal(integration.afterOpen(exec, normalized('OK', 7, { executed: 'true', state: 'RUNNING',
    dataJson: JSON.stringify({ event: 'opened', selectionSequence: 0 }) })).code, 'SESSION_CONTROLLER_UNAVAILABLE')
  const closed = normalized('OK', 8, { executed: 'true', state: 'CLOSED',
    dataJson: JSON.stringify({ event: 'closed' }) })
  assert.equal(integration.afterClose(exec, closed).code, 'SESSION_CONTROLLER_UNAVAILABLE')

  const serviceListener = [...listeners][0]
  const beforeBind = pendingReads
  serviceListener('sessionController', { prompt: async () => ({ accepted: true }) })
  assert.equal(pendingReads, beforeBind)
  assert.equal(integration.status().taskFeedback, 'UNBOUND')

  integration.dispose()
  assert.equal(integration.afterOpen(exec, normalized('OK', 9, { executed: 'true', state: 'RUNNING',
    dataJson: JSON.stringify({ event: 'opened', selectionSequence: 0 }) })).code, 'DISPOSED')
  serviceListener('sessionController', { prompt: async () => ({ accepted: true }) })
  assert.deepEqual(integration.status(), { selection: 'DISPOSED', taskFeedback: 'DISPOSED' })
  cleanup()
})

test('published Cordis late service binding sees Host service through real Agent scope and uses its identity', async () => {
  const { toolsApi, cordis, scopeApi } = await publishedRuntime()
  const ctx = new cordis.Context()
  ctx.systemPrompt = { tools() {}, section() {} }
  const runtime = new toolsApi.ToolRuntime(ctx)
  const timers = fakeTimers()
  const destinationIds = []
  const promptCalls = []
  let admitted = false
  const guidance = 'Recovery guidance: call browser_task status with this Task id before acting to locate this task and confirm its current revision, title, goal, and status. If the task is missing or any value differs from this draft, stop and do not continue business actions. This draft may be stale; prior results do not prove success. Never replay an UNKNOWN effect. Make fresh Browser observations before deciding what happened.\nTask id: task_1\nRevision: 0\nTitle: Continue work\nCreated wall time: 1700000000000\nTask status: paused\nGoal: Finish the task.'
  const request = async (_method, args, envelope) => {
    if (args.action === 'task_pending') {
      destinationIds.push(args.destinationSessionKey)
      const data = { afterSequence: args.afterSequence, nextSequence: admitted ? args.afterSequence : 1, items: args.afterSequence === 0 && !admitted ? [{
        feedbackId: 'feedback_1', kind: 'task_recovery', taskId: 'task_1', revision: 0,
        title: 'Continue work', text: guidance, sequence: 1, state: 'pending', sessionGeneration: envelope.sessionGeneration,
      }] : [] }
      return normalized('OK', envelope.sessionGeneration, { executed: 'false', dataJson: JSON.stringify(data) })
    }
    if (args.action === 'task_claim' || args.action === 'task_admit') {
      const state = args.action === 'task_claim' ? 'claimed' : 'admitted'
      if (state === 'admitted') admitted = true
      return normalized('OK', envelope.sessionGeneration, { executed: 'true', dataJson: JSON.stringify({
        feedbackId: args.feedbackId, state, requestId: args.requestId, payloadDigest: args.payloadDigest,
      }) })
    }
    if (args.action === 'task_receipt') return normalized('OK', envelope.sessionGeneration, { executed: 'true' })
    return normalized('NOT_FOUND', envelope.sessionGeneration)
  }
  let integration
  const ownerFiber = ctx.plugin((pluginCtx) => {
    integration = createSelectionIntegration(pluginCtx, {
      browserRequest: request,
      taskFeedbackOptions: { now: timers.now, schedule: timers.schedule, cancel: timers.cancel },
    })
    pluginCtx.tools.register(toolsApi.defineTool({
      name: 'browser_late_binding_contract', description: 'Exercise Browser binding with the real SDK caller.',
      parameters: {},
      output: { schema: { type: 'object', additionalProperties: true }, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] },
      execute(_args, exec) {
        return integration.afterOpen(exec, normalized('OK', 7, { executed: 'true', state: 'RUNNING',
          sessionGenerationAvailable: true, dataJson: JSON.stringify({ event: 'opened', selectionSequence: 0 }) }))
      },
    }))
  })
  await ownerFiber.await()

  const agentId = 'sdk-real-agent-17'
  const agentScope = scopeApi.createScope(ctx, agentId)
  const agent = { id: agentId, ctx: agentScope.ctx }
  const first = await runtime.execute({ callId: 'late-open-1', name: 'browser_late_binding_contract', arguments: {},
    signal: new AbortController().signal, agent })
  assert.equal(first.isError, false)
  assert.match(first.content[0].text, /SESSION_CONTROLLER_UNAVAILABLE/)
  assert.equal(ctx.tools.schemas().some((schema) => schema.name === 'browser_late_binding_contract'), true)

  const controller1 = { async prompt(request) { promptCalls.push(['first', request]); return { accepted: true } } }
  let provider = ctx.plugin((providerCtx) => providerCtx.provide('sessionController', controller1))
  await provider.await()
  await flush()
  assert.equal(integration.status().taskFeedback, 'ACTIVE')
  await timers.advance(2_000)
  assert.deepEqual(destinationIds, [agentId])
  assert.equal(promptCalls.length, 1)
  assert.equal(promptCalls[0][1].sessionId, agentId)

  const conflict = integration.afterOpen({ agent: { id: 'different-sdk-agent' }, signal: new AbortController().signal },
    normalized('OK', 7, { executed: 'true', state: 'RUNNING', sessionGenerationAvailable: true,
      dataJson: JSON.stringify({ event: 'opened', selectionSequence: 0 }) }))
  assert.equal(conflict.code, 'SESSION_BINDING_CONFLICT')
  assert.equal(integration.status().taskFeedback, 'SESSION_BINDING_CONFLICT')

  await provider.dispose()
  await flush()
  assert.equal(integration.status().taskFeedback, 'SESSION_BINDING_CONFLICT')
  const controller2 = { async prompt(request) { promptCalls.push(['second', request]); return { accepted: true } } }
  provider = ctx.plugin((providerCtx) => providerCtx.provide('sessionController', controller2))
  await provider.await()
  await flush()
  assert.equal(integration.status().taskFeedback, 'SESSION_BINDING_CONFLICT')
  const newerOpen = normalized('OK', 8, { executed: 'true', state: 'RUNNING', sessionGenerationAvailable: true,
    dataJson: JSON.stringify({ event: 'opened', selectionSequence: 2 }) })
  assert.equal(integration.afterOpen({ agent: { id: agentId }, signal: new AbortController().signal }, newerOpen).code, 'ACTIVE')
  assert.equal(integration.status().taskFeedback, 'ACTIVE')
  await timers.advance(2_000)
  assert.equal(promptCalls.length, 1, 'a feedback item already admitted under the first service must not be prompted again')
  assert.deepEqual(destinationIds, [agentId, agentId])

  await provider.dispose()
  await ownerFiber.dispose()
  await agentScope.dispose()
  assert.equal(ctx.tools.schemas().some((schema) => schema.name === 'browser_late_binding_contract'), false)
})
