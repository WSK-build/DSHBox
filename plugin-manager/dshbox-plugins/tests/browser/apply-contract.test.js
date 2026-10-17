'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { createRequire } = require('node:module')
const { pathToFileURL } = require('node:url')
const plugin = require('../../assets/plugins/browser-use/plugin/lib/index')
const { createSelectionIntegration } = require('../../assets/plugins/browser-use/plugin/lib/selection-integration')

const apiEntry = process.env.DSH_TOOLS_API_ENTRY || ''

function findPackageRoot(entry) {
  let current = path.dirname(path.resolve(entry))
  while (path.dirname(current) !== current) {
    const manifest = path.join(current, 'package.json')
    if (fs.existsSync(manifest)) {
      try {
        if (JSON.parse(fs.readFileSync(manifest, 'utf8')).name === '@deepseek-ai/dsh-tools') return current
      } catch {}
    }
    current = path.dirname(current)
  }
  throw new Error('The configured DSH tools API package could not be located')
}

async function withRealDshTools(run) {
  const packageRoot = findPackageRoot(apiEntry)
  const runtimeRoot = path.resolve(packageRoot, '../../..')
  const previousCwd = process.cwd()
  process.chdir(runtimeRoot)
  try {
    const api = await import(pathToFileURL(path.resolve(apiEntry)).href)
    const runtimeRequire = createRequire(path.join(runtimeRoot, 'package.json'))
    const cordisEntry = runtimeRequire.resolve('@deepseek-ai/cordis')
    const cordis = await import(pathToFileURL(cordisEntry).href)
    const scopeEntry = runtimeRequire.resolve('@deepseek-ai/dsh-scope')
    const scopeApi = await import(pathToFileURL(scopeEntry).href)
    const ctx = new cordis.Context()
    ctx.systemPrompt = { tools() {}, section() {} }
    new api.ToolRuntime(ctx)
    return await run(api, ctx, scopeApi)
  } finally {
    process.chdir(previousCwd)
  }
}

test('Cordis loads Browser tools into the published DSH registry and agent view', {
  skip: !apiEntry && 'Set DSH_TOOLS_API_ENTRY to the installed dsh-tools API entry to run this integration contract.'
}, async () => withRealDshTools(async (api, ctx, scopeApi) => {
  assert.equal(typeof api.defineTool, 'function')
  assert.equal(typeof api.assertSupportedJsonSchema, 'function')
  assert.deepEqual(plugin.inject, ['tools'])
  const fiber = ctx.plugin(plugin)
  await fiber.await()
  const globalSchemas = ctx.tools.schemas()
  const registeredNames = globalSchemas.map((schema) => schema.name)
  assert.equal(registeredNames.length, 24)
  assert.deepEqual(registeredNames, [
    'browser_status', 'browser_task', 'browser_open', 'browser_observe', 'browser_wait', 'browser_navigate', 'browser_collect', 'browser_locate',
    'browser_click', 'browser_type',
    'browser_scroll', 'browser_screenshot', 'browser_close', 'browser_debug',
    'browser_network', 'browser_selection', 'browser_page', 'browser_serve', 'browser_file', 'browser_upload', 'browser_event', 'browser_control',
    'browser_rendering', 'browser_ax'
  ])
  const controlSchema = globalSchemas.find((schema) => schema.name === 'browser_control')
  assert.ok(controlSchema, 'browser_control must be registered in the published DSH tool registry')
  assert.match(controlSchema.description, /human approval/i)
  assert.deepEqual(controlSchema.parameters.properties.action.enum, ['request', 'status', 'cancel'])
  assert.equal(controlSchema.parameters.properties.summary.type, 'string')
  assert.match(controlSchema.parameters.properties.summary.description, /required for request/i)
  assert.match(controlSchema.parameters.properties.taskId.description, /correlation/i)
  assert.match(controlSchema.parameters.properties.approvalId.description, /32 lowercase hexadecimal/i)
  assert.deepEqual(globalSchemas.find(schema => schema.name === 'browser_rendering').parameters.properties.operation.enum, ['get', 'set'])
  assert.deepEqual(globalSchemas.find(schema => schema.name === 'browser_ax').parameters.properties.action.enum, ['observe', 'click', 'type', 'scroll'])
  const agent = { id: 'browser-contract-agent' }
  const agentScope = scopeApi.createScope(ctx, agent)
  try {
    const agentSchemas = ctx.tools.schemas(agent)
    assert.deepEqual(agentSchemas.map((schema) => schema.name), registeredNames)
    assert.equal(ctx.tools.get('browser_observe', agent).name, 'browser_observe')
    assert.equal(ctx.tools.resolveExecution('browser_selection', agent).name, 'browser_selection')
    assert.equal(ctx.tools.get('browser_event', agent).name, 'browser_event')
    assert.equal(ctx.tools.get('browser_control', agent).name, 'browser_control')
    assert.equal(ctx.tools.resolveExecution('browser_rendering', agent).name, 'browser_rendering')
    assert.equal(ctx.tools.resolveExecution('browser_ax', agent).name, 'browser_ax')
  } finally {
    await agentScope.dispose()
  }
  const observeTool = ctx.tools.get('browser_observe')
  assert.equal(typeof observeTool.execute, 'function')
  assert.equal(typeof observeTool.output.render, 'function')
  const observeContent = observeTool.output.render({}, {
    text: 'Page observed.', code: 'OK', requestId: 'observe-1', executed: 'false',
    sessionGeneration: 4, controlGeneration: 9,
    sessionGenerationAvailable: true, controlGenerationAvailable: true,
    state: 'RUNNING', dataJson: '{"summary":{"items":[{"nodeId":"n-1","label":"Continue"}]}}', path: ''
  })
  assert.equal(observeContent[0].type, 'text')
  assert.match(observeContent[0].text, /n-1/)
  assert.match(observeContent[0].text, /Continue/)
  const selectionContent = ctx.tools.get('browser_selection').output.render({}, {
    text: 'Selection shared.', code: 'OK', requestId: 'selection-1', executed: 'true',
    sessionGeneration: 4, controlGeneration: 0,
    sessionGenerationAvailable: true, controlGenerationAvailable: false,
    state: 'RUNNING', dataJson: '{"comment":"Check this button","pageUrl":"https://example.test/"}', path: ''
  })
  assert.match(selectionContent[0].text, /Check this button/)
}))

test('Cordis keeps the Browser plugin active and exposes the successful prefix after registration failure', {
  skip: !apiEntry && 'Set DSH_TOOLS_API_ENTRY to the installed dsh-tools API entry to run this integration contract.'
}, async () => withRealDshTools(async (_api, ctx) => {
  const registered = []
  // Keep the original method unbound so Cordis can supply the calling plugin
  // Fiber context to ToolRuntime.register and own each registration effect.
  const register = Object.getPrototypeOf(ctx.tools).register
  let attempts = 0
  ctx.tools.register = function registerWithFailure(definition) {
    attempts += 1
    if (attempts === 4) throw new Error('registry rejected the next definition')
    const disposer = Reflect.apply(register, this, [definition])
    registered.push(definition.name)
    return disposer
  }
  const originalWrite = process.stderr.write
  let diagnostic = ''
  let fiber
  let disposed = false
  process.stderr.write = function (chunk, ...args) {
    diagnostic += String(chunk)
    return originalWrite.call(this, chunk, ...args)
  }
  try {
    fiber = ctx.plugin(plugin)
    await fiber.await()
    assert.deepEqual(plugin.inject, ['tools'])
    assert.equal(registered.length, 3)
    assert.deepEqual(ctx.tools.schemas().map((schema) => schema.name), registered)
    assert.match(diagnostic, /PARTIAL_REGISTRATION/)
    assert.match(diagnostic, /failed=browser_observe/)
    await fiber.dispose()
    disposed = true
    assert.deepEqual(ctx.tools.schemas(), [], 'disposing the Cordis Fiber must remove its successful registration prefix')
  } finally {
    process.stderr.write = originalWrite
    if (fiber && !disposed) await fiber.dispose()
  }
}))

test('Cordis unload and reload removes old registrations before restoring all Browser tools', {
  skip: !apiEntry && 'Set DSH_TOOLS_API_ENTRY to the installed dsh-tools API entry to run this integration contract.'
}, async () => withRealDshTools(async (_api, ctx) => {
  const expectedNames = [
    'browser_status', 'browser_task', 'browser_open', 'browser_observe', 'browser_wait', 'browser_navigate', 'browser_collect', 'browser_locate',
    'browser_click', 'browser_type',
    'browser_scroll', 'browser_screenshot', 'browser_close', 'browser_debug',
    'browser_network', 'browser_selection', 'browser_page', 'browser_serve', 'browser_file', 'browser_upload', 'browser_event', 'browser_control',
    'browser_rendering', 'browser_ax'
  ]
  const first = ctx.plugin(plugin)
  await first.await()
  assert.deepEqual(ctx.tools.schemas().map((schema) => schema.name), expectedNames)

  await first.dispose()
  assert.deepEqual(ctx.tools.schemas(), [], 'unloading the first Fiber must unregister all tools')

  const second = ctx.plugin(plugin)
  try {
    await second.await()
    assert.deepEqual(ctx.tools.schemas().map((schema) => schema.name), expectedNames)
    assert.equal(ctx.tools.schemas().length, 24)
  } finally {
    await second.dispose()
  }
  assert.deepEqual(ctx.tools.schemas(), [], 'unloading the reloaded Fiber must unregister all tools')
}))

test('optional Session Controller is discovered through the real Cordis reflect service', {
  skip: !apiEntry && 'Set DSH_TOOLS_API_ENTRY to the installed dsh-tools API entry to run this integration contract.'
}, async () => withRealDshTools(async (_api, ctx) => {
  const sessionController = { async prompt() { return { accepted: true } } }
  const removeProvider = ctx.reflect.provide('sessionController', sessionController)
  try {
    assert.equal(ctx.reflect.get('sessionController', false), sessionController)
    const integration = createSelectionIntegration(ctx, {
      browserRequest: async () => ({ code: 'NOT_FOUND', executed: 'false' }),
    })
    assert.ok(integration, 'the optional service lookup should enable the integration when the service is available')
    integration.dispose()
  } finally {
    await removeProvider()
  }
}))
