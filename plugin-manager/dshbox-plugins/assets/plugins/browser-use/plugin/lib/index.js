'use strict'

const path = require('node:path')
const { pathToFileURL } = require('node:url')
const { buildTools } = require('./tools')
const { createSelectionIntegration } = require('./selection-integration')

exports.name = '@local/browser-use'
// Cordis treats an object inject value as a map of service names to intercept
// configs. Use the service-name list form so the loader waits for `tools`.
exports.inject = ['tools']

const TOOLS_MODULE = '@deepseek-ai/dsh-tools'

function report(message) {
  try { process.stderr.write(`${exports.name}: ${message}\n`) } catch {}
}

function optionalService(ctx, name) {
  try {
    if (ctx && ctx.reflect && typeof ctx.reflect.get === 'function') return ctx.reflect.get(name, false)
  } catch {}
  try { return ctx && ctx[name] } catch {}
  return undefined
}

function resolveDefineTool() {
  const bases = new Set([__dirname, process.cwd()])
  if (require.main && require.main.filename) bases.add(path.dirname(require.main.filename))
  if (process.execPath) bases.add(path.dirname(process.execPath))
  const attempts = []
  for (const base of bases) {
    let resolved
    try { resolved = require.resolve(TOOLS_MODULE, { paths: [base] }) } catch (error) {
      attempts.push({ base, error })
      continue
    }
    try {
      const api = require(resolved)
      if (api && typeof api.defineTool === 'function') return { defineTool: api.defineTool, resolved }
      attempts.push({ base, error: new Error('defineTool export missing') })
    } catch (error) {
      attempts.push({ base, error, resolved })
    }
  }
  return { defineTool: null, attempts }
}

async function importDefineTool(attempts) {
  const tried = new Set()
  for (const attempt of attempts || []) {
    if (!attempt.resolved || tried.has(attempt.resolved)) continue
    tried.add(attempt.resolved)
    try {
      const api = await import(pathToFileURL(attempt.resolved).href)
      if (api && typeof api.defineTool === 'function') return api.defineTool
    } catch {}
  }
  try {
    const api = await import(TOOLS_MODULE)
    if (api && typeof api.defineTool === 'function') return api.defineTool
  } catch {}
  return null
}

function applyWithDefineTool(ctx, defineTool) {
  if (!ctx || !ctx.tools || typeof ctx.tools.register !== 'function') {
    report('TOOLS_UNAVAILABLE: no Browser tools registered')
    return { code: 'TOOLS_UNAVAILABLE', registered: [] }
  }
  if (typeof defineTool !== 'function') {
    report('DEFINE_TOOL_UNAVAILABLE: no Browser tools registered')
    return { code: 'DEFINE_TOOL_UNAVAILABLE', registered: [] }
  }
  let tools
  try {
    const selectionIntegration = createSelectionIntegration(ctx)
    tools = buildTools({ defineTool, dependencies: { attachments: optionalService(ctx, 'attachments'), selectionIntegration } })
  } catch (error) {
    report(`DEFINITION_FAILED: no Browser tools registered (${error && error.message || error})`)
    return { code: 'DEFINITION_FAILED', registered: [] }
  }
  const registered = []
  for (const tool of tools) {
    try {
      ctx.tools.register(tool)
      registered.push(tool.name)
    } catch (error) {
      report(`PARTIAL_REGISTRATION: registered=${registered.length} failed=${tool.name} (${error && error.message || error})`)
      return { code: 'PARTIAL_REGISTRATION', registered, failedTool: tool.name }
    }
  }
  return { code: 'OK', registered }
}

exports.apply = async function apply(ctx) {
  if (!ctx || !ctx.tools || typeof ctx.tools.register !== 'function') {
    report('TOOLS_UNAVAILABLE: no Browser tools registered')
    return
  }
  const acquired = resolveDefineTool()
  const defineTool = acquired.defineTool || await importDefineTool(acquired.attempts)
  if (typeof defineTool !== 'function') {
    report('DEFINE_TOOL_UNAVAILABLE: dsh-tools defineTool missing; no Browser tools registered')
    return
  }
  // Cordis apply results are effect cleanups, not plugin status values. Tool
  // registration is owned by the DSH tools service; resolve to void here.
  applyWithDefineTool(ctx, defineTool)
}

module.exports.buildTools = buildTools
module.exports.resolveDefineTool = resolveDefineTool
module.exports.importDefineTool = importDefineTool
module.exports.applyWithDefineTool = applyWithDefineTool
