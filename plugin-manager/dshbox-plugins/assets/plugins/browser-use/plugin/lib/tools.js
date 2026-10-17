'use strict'

const { browserRequest, resolveScreenshotPath, resolveArtifactPath, validateArgs } = require('./client')
const { collectBrowserWindowSet } = require('./browser-collect-client')
const { deliverBrowserImage, imageBlock } = require('./image-delivery')
const { browserServeRequest } = require('./serve-client')

const OUTPUT = {
  type: 'object',
  additionalProperties: false,
  properties: {
    ok: { type: 'boolean', required: true, description: 'True only when the Browser runtime reports code OK.' },
    code: { type: 'string', required: true, description: 'Runtime or adapter error code.' },
    text: { type: 'string', required: true, description: 'Short result; UNKNOWN means do not resubmit the action.' },
    requestId: { type: 'string', required: true, description: 'Unique request id; empty when rejected before dispatch.' },
    executed: { type: 'string', required: true, description: 'true, false, or unknown; unknown must not be treated as not executed.' },
    sessionGeneration: { type: 'integer', required: true, description: 'Positive active session generation from browser_status or browser_open. Zero means unavailable; do not use it.' },
    controlGeneration: { type: 'integer', required: true, description: 'Positive active control generation from browser_status or browser_open. Zero means unavailable; do not use it.' },
    sessionGenerationAvailable: { type: 'boolean', required: true, description: 'Whether sessionGeneration came from an authoritative response.' },
    controlGenerationAvailable: { type: 'boolean', required: true, description: 'Whether controlGeneration came from an authoritative response.' },
    state: { type: 'string', required: true, description: 'Runtime state, or empty if unavailable.' },
    dataJson: { type: 'string', required: true, description: 'Bounded JSON response data.' },
    path: { type: 'string', required: true, description: 'Validated Browser-owned artifact path; empty when the action returns no artifact.' },
    imageReferenceJson: { type: 'string', description: 'Durable reference produced by the DSH attachment service, never an arbitrary image path.' },
    imageDeliveryCode: { type: 'string', description: 'Attachment admission status. OK means image content can be rendered; this is not confirmation that a model has seen it.' }
  }
}

function renderResult(_args, value) {
  const data = typeof value.dataJson === 'string' ? value.dataJson : '{}'
  const text = [
    value.text,
    `code=${value.code} requestId=${value.requestId || '(none)'} executed=${value.executed}`,
    `state=${value.state || '(unknown)'} sessionGeneration=${value.sessionGenerationAvailable ? value.sessionGeneration : '(none)'} controlGeneration=${value.controlGenerationAvailable ? value.controlGeneration : '(none)'}`,
    `data=${data}`,
    value.path ? `path=${value.path} (saved Browser-owned artifact)` : ''
  ].filter(Boolean).join('\n')
  const content = [{ type: 'text', text: text + (value.imageDeliveryCode ? `\nimageDelivery=${value.imageDeliveryCode}` : '') }]
  const image = value.imageDeliveryCode === 'OK' ? imageBlock(value.imageReferenceJson) : null
  if (image) content.push(image)
  return content
}

function localResult(code, text, base = null) {
  return {
    ok: false,
    code,
    text,
    requestId: base && base.requestId || '',
    executed: 'false',
    sessionGeneration: base && Number.isSafeInteger(base.sessionGeneration) ? base.sessionGeneration : 0,
    controlGeneration: base && Number.isSafeInteger(base.controlGeneration) ? base.controlGeneration : 0,
    sessionGenerationAvailable: !!base && Number.isSafeInteger(base.sessionGeneration) && base.sessionGeneration > 0,
    controlGenerationAvailable: !!base && Number.isSafeInteger(base.controlGeneration) && base.controlGeneration > 0,
    state: base && base.state || '',
    dataJson: base && base.dataJson || '{}',
    path: ''
  }
}

function outputResult(value) {
  const output = {}
  for (const key of Object.keys(OUTPUT.properties)) {
    if (Object.hasOwn(value, key)) output[key] = value[key]
  }
  return output
}

function includeBindingStatus(result, integration) {
  let status
  try { status = integration && typeof integration.status === 'function' ? integration.status() : null } catch {}
  const safeCode = (value) => typeof value === 'string' && /^[A-Z0-9_]{1,64}$/.test(value) ? value : 'STATUS_UNAVAILABLE'
  const data = rawData(result)
  return {
    ...result,
    dataJson: JSON.stringify({
      ...data,
      browserBinding: {
        selection: safeCode(status && status.selection),
        taskFeedback: safeCode(status && status.taskFeedback),
        ...(status && status.selectionReason ? { selectionReason: safeCode(status.selectionReason) } : {}),
      },
    }),
  }
}

function rawData(result) {
  try { return JSON.parse(result.dataJson || '{}') } catch { return {} }
}

function generation(value) {
  return Number.isSafeInteger(value) && value > 0 ? value : null
}

function collectScanResult(scan, sessionGeneration, controlGeneration) {
  const complete = scan.coverageComplete === true && scan.complete === true
  const reason = typeof scan.reason === 'string' && scan.reason ? scan.reason : ''
  const unknown = /UNKNOWN|TIME_BUDGET|CANCELLED/.test(reason)
  const code = complete ? 'OK' : unknown ? 'UNKNOWN' : 'PARTIAL'
  return {
    ok: complete,
    code,
    text: complete
      ? 'Virtualized list scan reached a complete, ordered coverage boundary.'
      : `Virtualized list scan is partial (${reason || 'coverage incomplete'}); do not treat it as complete.`,
    requestId: '',
    executed: unknown ? 'unknown' : 'true',
    sessionGeneration,
    controlGeneration,
    sessionGenerationAvailable: true,
    controlGenerationAvailable: true,
    state: '',
    dataJson: JSON.stringify(scan),
    path: ''
  }
}

function generationInputError(args, requireControl = false) {
  if (!Number.isSafeInteger(args.sessionGeneration) || args.sessionGeneration <= 0) {
    return 'The current session generation is unavailable. Call browser_status or browser_open before continuing.'
  }
  if (requireControl && (!Number.isSafeInteger(args.controlGeneration) || args.controlGeneration <= 0)) {
    return 'The current control generation is unavailable. Call browser_status before continuing.'
  }
  return ''
}

async function openBrowser(args, dependencies = {}) {
  const { taskId, ...openArgs } = args
  const argError = validateArgs('open', openArgs)
  if (argError) return localResult('INVALID_ARGUMENT', argError)
  const status = await browserRequest('status', {}, taskEnvelope({ taskId }, {}), dependencies)
  if (!status.ok) return status
  const data = rawData(status)
  const state = String(status.state || data.state || '').toLowerCase()
  const automationEnabled = data.automationEnabled === true || data.automation_enabled === true
  if (!automationEnabled) return localResult('AUTOMATION_DISABLED', 'Browser automation is off. Enable it in Browser settings before continuing.', status)

  const closedStates = new Set(['closed'])
  const activeStates = new Set(['idle', 'running'])
  if (closedStates.has(state)) {
    if (taskId != null) return localResult('TASK_PAGE_MISMATCH', 'A closed Browser open cannot be attached to a task before its page identity exists.', status)
    return browserRequest('open', openArgs, { owner: 'ai' }, dependencies)
  }
  if (!activeStates.has(state)) return localResult('STATE_UNKNOWN', 'Browser status did not identify an open or closed session. No navigation was sent.', status)

  const owner = String(data.owner || data.controlOwner || '').toLowerCase()
  if (owner !== 'ai') return localResult('CONTROL_NOT_GRANTED', 'AI does not own the active Browser control generation. No navigation was sent.', status)
  const sessionGeneration = generation(status.sessionGeneration)
  const controlGeneration = generation(status.controlGeneration)
  if (sessionGeneration == null || controlGeneration == null) return localResult('STATE_UNKNOWN', 'Browser generations are unavailable. No navigation was sent.', status)
  return browserRequest('open', openArgs, taskEnvelope({ taskId }, { sessionGeneration, controlGeneration, owner: 'ai' }), dependencies)
}

function taskEnvelope(args, envelope) {
  return !args || args.taskId == null ? envelope : { ...envelope, taskId: args.taskId }
}

function screenshotResult(result) {
  if (!result.ok) return result
  const resolved = resolveScreenshotPath(result.artifactPath)
  if (!resolved) return localResult('ARTIFACT_UNAVAILABLE', 'The Browser runtime reported success but its screenshot could not be verified in the Browser output directory. Do not repeat automatically.', result)
    return { ...result, path: resolved, text: 'Screenshot saved as a Browser-owned artifact. See imageDelivery for attachment admission status.' }
}

function advancedRequest(method, args, dependencies) {
  const needsControl = method !== 'selection'
  if (!needsControl && args.controlGeneration != null) {
    return Promise.resolve(localResult('INVALID_ARGUMENT', 'selection does not accept a control generation or AI owner.'))
  }
  const error = generationInputError(args, needsControl)
  if (error) return Promise.resolve(localResult('GENERATION_UNAVAILABLE', error))
  const { sessionGeneration, controlGeneration, taskId, ...actionArgs } = args
  return browserRequest(method, actionArgs, {
    sessionGeneration,
    ...(needsControl ? { controlGeneration, owner: 'ai' } : {}),
    ...(taskId == null ? {} : { taskId })
  }, dependencies).then((result) => {
    if (!result.ok || !result.artifactPath) return result
    const resolved = resolveArtifactPath(result.artifactPath, method, actionArgs.action)
    if (!resolved) return localResult('ARTIFACT_UNAVAILABLE', 'The Browser artifact could not be verified under its fixed output directory. Do not repeat automatically.', result)
    return { ...result, path: resolved }
  })
}

function inputProps(generationRequired, controlRequired = false, taskScoped = true) {
  const props = {}
  if (generationRequired) props.sessionGeneration = {
    type: 'integer', required: true, description: 'Positive active session generation from browser_status or browser_open. Zero means unavailable; do not use it.'
  }
  if (controlRequired) props.controlGeneration = {
    type: 'integer', required: true, description: 'Positive active control generation from browser_status or browser_open. Zero means unavailable; do not use it.'
  }
  if (taskScoped) props.taskId = {
    type: 'string',
    description: 'Optional task checkpoint id returned by browser_task. It is sent in the request envelope and is not a Browser action argument.'
  }
  return props
}

function buildTools({ defineTool, dependencies = {} }) {
  if (typeof defineTool !== 'function') throw new TypeError('defineTool is required')
  const tools = []
  const add = (name, description, parameters, execute) => tools.push(defineTool({
    name,
    description,
    parameters,
    output: { schema: OUTPUT, render: renderResult },
    execute: async (args, exec) => {
      let result = await execute(args, exec)
      if (dependencies.selectionIntegration && result.ok) {
        if (name === 'browser_open') {
          let relay
          try {
            relay = dependencies.selectionIntegration.afterOpen(exec, result)
          } catch {
            try {
              relay = typeof dependencies.selectionIntegration.rejectOpen === 'function'
                ? dependencies.selectionIntegration.rejectOpen(exec, result)
                : { code: 'BINDING_UNAVAILABLE', relayStopped: false }
            } catch {
              relay = { code: 'BINDING_UNAVAILABLE', relayStopped: false }
            }
          }
          if (!relay || !['ACTIVE'].includes(relay.code)) {
            const code = relay && typeof relay.code === 'string' ? relay.code : 'BINDING_UNAVAILABLE'
            const admitted = relay && relay.previouslyAdmittedMayComplete === true
              ? ' A prompt already submitted to DSH may still arrive in the previously bound session and cannot be retracted.'
              : ''
            const stopped = relay && relay.relayStopped === true
              ? ' The prior relay was stopped.'
              : ' No new relay binding was confirmed; the existing relay state could not be changed safely.'
            result = {
              ...result,
              text: `Browser open succeeded according to the runtime (code=${result.code}, executed=${result.executed}). Selection relay was not bound to this DSH session (${code}).${stopped}${admitted}`,
            }
          }
          result = includeBindingStatus(result, dependencies.selectionIntegration)
        }
        if (name === 'browser_close') dependencies.selectionIntegration.afterClose(exec, result)
        if (name === 'browser_status') result = includeBindingStatus(result, dependencies.selectionIntegration)
      }
      if (result.ok && ['browser_open', 'browser_navigate'].includes(name)) {
        const preview = rawData(result).thumbnail
        if (preview) {
          const previewPath = preview.captured ? resolveScreenshotPath(preview.artifactPath) : ''
          result = { ...result, path: previewPath,
            text: previewPath ? 'Navigation accepted. Optional preview captured; verify the task with fresh observe. This image is not a coordinate-action screenshot.'
              : `Navigation accepted; optional preview unavailable (${preview.captured ? 'ARTIFACT_UNAVAILABLE' : preview.code}). Do not repeat the navigation to obtain a preview.`,
            ...(preview.captured && !previewPath ? { imageDeliveryCode: 'ARTIFACT_UNAVAILABLE' } : {}) }
        }
      }
      if (!result.ok || !result.path || !['browser_screenshot', 'browser_selection', 'browser_open', 'browser_navigate'].includes(name)) return outputResult(result)
      const image = await deliverBrowserImage(result.path, dependencies.attachments, dependencies.image || {})
      return outputResult({ ...result, imageReferenceJson: image.referenceJson, imageDeliveryCode: image.code })
    }
  }))

  add(
    'browser_status',
    'Read Browser session, automation, ownership, and generation state. This does not start the Browser runtime or WebView and does not renew control. Use before opening or acting.',
    inputProps(false),
    async (args) => browserRequest('status', {}, taskEnvelope(args, {}), dependencies)
  )
  add(
    'browser_task',
    'Create, inspect, edit, resume, and delete bounded Browser task checkpoints. Task IDs correlate state but do not grant control. Begin, resume, reconcile, and completion require a live matching AI-owned page; status, list, edit, and eligible delete work while Browser is closed. Reconcile accepts only action and taskId; expectedRevision is used only by update and delete. wait reason must be one of page_load, page_condition, user_action, user_authentication, recovery_check; fail reason is optional and, when provided, must be 1..1000 non-whitespace characters. List pages use offset and limit. Begin metadata has a combined 16 KiB request-frame limit; if large fields exceed it, create the task then update fields separately. A task never replays an operation after UNKNOWN. Defaults are 30 minutes and 500 operations; maximums are 4 hours and 2000 operations.',
    {
      action: { type: 'string', required: true, enum: ['begin', 'status', 'list', 'pause', 'wait', 'complete', 'fail', 'reconcile', 'resume', 'update', 'delete'] },
      taskId: { type: 'string', description: 'Required except for begin/list; opaque task id returned by begin.' },
      durationMs: { type: 'integer', description: 'Optional begin budget, 1..14400000 ms.' },
      operationLimit: { type: 'integer', description: 'Optional begin budget, 1..2000 operations.' },
      title: { type: 'string', description: 'Optional task title, at most 120 characters.' },
      goal: { type: 'string', description: 'Optional task goal, at most 4000 characters.' },
      notes: { type: 'string', description: 'Optional task notes, at most 2000 characters.' },
      expectedRevision: { type: 'integer', description: 'Required current task revision for update and delete only; reconcile does not accept it.' },
      offset: { type: 'integer', description: 'Optional task list offset, at least 0; defaults to 0.' },
      limit: { type: 'integer', description: 'Optional task list page size, 1..100; defaults to 100.' },
      reason: { type: 'string', description: 'Required for wait (one of page_load, page_condition, user_action, user_authentication, recovery_check). Optional for fail; if provided, use a non-whitespace explanation up to 1000 characters.' }
    },
    async (args) => {
      const argError = validateArgs('task', args)
      if (argError) return localResult('INVALID_ARGUMENT', argError)
      if (!['begin', 'complete', 'reconcile', 'resume'].includes(args.action)) {
        return browserRequest('task', args, {}, dependencies)
      }
      const status = await browserRequest('status', {}, {}, dependencies)
      if (!status.ok) return status
      const data = rawData(status)
      const state = String(status.state || data.state || '').toLowerCase()
      if (!['idle', 'running'].includes(state)) return localResult('CLOSED', 'This task action requires the active Browser page.', status)
      if (!(data.automationEnabled === true || data.automation_enabled === true)) {
        return localResult('AUTOMATION_DISABLED', 'Browser automation is off. Enable it in Browser settings before continuing.', status)
      }
      if (String(data.owner || '').toLowerCase() !== 'ai') {
        return localResult('CONTROL_NOT_GRANTED', 'AI does not own the active Browser page.', status)
      }
      const sessionGeneration = generation(status.sessionGeneration)
      const controlGeneration = generation(status.controlGeneration)
      if (sessionGeneration == null || controlGeneration == null) return localResult('STATE_UNKNOWN', 'Browser generations are unavailable.', status)
      return browserRequest('task', args, { sessionGeneration, controlGeneration, owner: 'ai' }, dependencies)
    }
  )
  add(
    'browser_open',
    'Open or navigate the Browser when Browser automation is enabled and AI owns the current control generation. It checks browser_status first and never claims control. An accepted navigation is not proof that the page finished loading; use browser_wait and browser_observe to verify. URL, when supplied, must be HTTP or HTTPS. Use an address supplied by the user, read from a verified page link, or established by reliable search evidence; never guess a brand domain. A valid URL or successful load does not prove an official site or the user goal. Verify the final address and site identity before account, message, purchase or payment actions.',
    { ...inputProps(false, false), url: { type: 'string', description: 'Optional HTTP or HTTPS address, at most 2048 characters.' },
      thumbnail: { type: 'boolean', description: 'Opt in to a bounded preview of this committed navigation, requires URL. Default does not capture. Preview failure does not mean navigation failed.' } },
    async (args) => openBrowser({ ...(args.url == null ? {} : { url: args.url }),
      ...(args.thumbnail === undefined ? {} : { thumbnail: args.thumbnail }), ...(args.taskId == null ? {} : { taskId: args.taskId }) }, dependencies)
  )
  add(
    'browser_observe',
    'Read bounded information from the current Browser page, including top-document overlays and current dismiss candidates. blockInfo contains possible verification, authentication, or access-restriction hints; not_detected does not prove the page is unrestricted. Inspect the evidence. If human help is needed, explicitly place the task in wait with user_authentication or user_action and ask the user; never solve a challenge or transfer control automatically. Obtain fresh status/observe and reconcile before resuming. Automation must be enabled and this session-only read does not require AI control. It renews the idle lease only if AI owns the session before and after the read, the control and document generations still match, and the DOM read succeeds. Use the current sessionGeneration.',
    {
      ...inputProps(true),
      maxTextChars: { type: 'integer', description: 'Maximum page text, 1..4096 characters; the runtime also enforces a UTF-8 response-byte cap.' }
    },
    async (args) => {
      const error = generationInputError(args)
      if (error) return localResult('GENERATION_UNAVAILABLE', error)
      return browserRequest('observe', { ...(args.maxTextChars == null ? {} : { maxTextChars: args.maxTextChars }) },
        taskEnvelope(args, { sessionGeneration: args.sessionGeneration }), dependencies)
    }
  )
  add(
    'browser_wait',
    'Wait up to 30 seconds for the current page load state, exact URL, text, or selector condition. Text and selector inspect the top document only; waitScope reports partial whole-page coverage and no frame traversal. URL and load monitor the current main page navigation, not DOM completeness. This uses the current session and does not require or renew AI control. A timeout returns the latest observed page state; cancellation or session revocation means the condition was not confirmed. If cancellation cannot be confirmed, the result is UNKNOWN and must not be repeated automatically. Use browser_observe after a match when you need fresh page content.',
    {
      ...inputProps(true),
      condition: { type: 'string', required: true, enum: ['load', 'url', 'text', 'selector'], description: 'Condition to await. URL matching is exact.' },
      timeoutMs: { type: 'integer', description: 'Bounded wait duration, 1..30000 milliseconds; defaults to 10000.' },
      pollIntervalMs: { type: 'integer', description: 'Condition polling interval, 100..1000 milliseconds.' },
      url: { type: 'string', description: 'Required for url; exact HTTP(S) address to match.' },
      text: { type: 'string', description: 'Required for text; text that must appear in the page.' },
      selector: { type: 'string', description: 'Required for selector; CSS selector to check.' },
      state: { type: 'string', enum: ['attached', 'visible', 'hidden'], description: 'Selector condition state; defaults to visible.' }
    },
    async (args, exec) => {
      const error = generationInputError(args)
      if (error) return localResult('GENERATION_UNAVAILABLE', error)
      const { sessionGeneration, taskId, ...waitArgs } = args
      if (waitArgs.condition === 'selector' && waitArgs.state == null) waitArgs.state = 'visible'
      return browserRequest('wait', waitArgs, { sessionGeneration, ...(taskId == null ? {} : { taskId }) }, {
        ...dependencies,
        ...(exec && exec.signal ? { signal: exec.signal } : {})
      })
    }
  )
  add(
    'browser_navigate',
    'Use back, forward, or reload on the current Browser page. History actions can resubmit a prior POST or form submission, especially with back or reload. Requires Browser automation enabled and current AI control. An accepted navigation only confirms dispatch; use browser_wait to confirm loading. Optional eventSubscriptionId and eventNonce must come from a prior browser_event arm and are consumed by this action; they produce a separate correlated observation, not proof of causality or business success. Do not repeat after UNKNOWN.',
    {
      ...inputProps(true, true),
      action: { type: 'string', required: true, enum: ['back', 'forward', 'reload'], description: 'History navigation or reload action.' },
      thumbnail: { type: 'boolean', description: 'Opt in to a bounded preview after this navigation commits. Default does not capture. Never replay navigation after a preview failure.' },
      eventSubscriptionId: { type: 'string', description: 'Optional one-use event subscription ID from browser_event arm; pass with eventNonce and taskId.' },
      eventNonce: { type: 'string', description: 'Optional paired expectation credential from browser_event arm; pass with eventSubscriptionId and taskId.' }
    },
    async (args) => {
      const error = generationInputError(args, true)
      if (error) return localResult('GENERATION_UNAVAILABLE', error)
      if ((args.eventSubscriptionId !== undefined || args.eventNonce !== undefined) && !args.taskId) {
        return localResult('INVALID_ARGUMENT', 'Event-correlated navigation requires the taskId used to arm the subscription.')
      }
      return browserRequest('navigate', { action: args.action,
        ...(args.thumbnail === undefined ? {} : { thumbnail: args.thumbnail }),
        ...(args.eventSubscriptionId === undefined ? {} : { eventSubscriptionId: args.eventSubscriptionId, eventNonce: args.eventNonce }) }, taskEnvelope(args, {
        sessionGeneration: args.sessionGeneration, controlGeneration: args.controlGeneration, owner: 'ai'
      }), dependencies)
    }
  )
  add(
    'browser_collect',
    'Read one bounded window or scan the entire selected visible virtualized container. Scan may scroll the current list, uses a task checkpoint, and reports partial coverage explicitly; it does not click or submit page content. declaredRange is a descriptive label only; it is not interpreted as a row range or filter.',
    {
      ...inputProps(true, true),
      action: { type: 'string', required: true, enum: ['window', 'scan'] },
      taskId: { type: 'string', description: 'Required for scan; optional for one window. Use 1..64 letters, digits, underscores, or hyphens; returned by browser_task begin.' },
      containerSelector: { type: 'string', required: true, description: 'CSS selector for the scrollable list container; at most 512 characters.' },
      messageSelector: { type: 'string', required: true, description: 'CSS selector for visible record elements; at most 512 characters.' },
      keyAttribute: { type: 'string', required: true, description: 'Stable record key attribute name.' },
      authorSelector: { type: 'string', description: 'Optional author selector.' },
      authorAttribute: { type: 'string', description: 'Optional author attribute name.' },
      contentSelector: { type: 'string', description: 'Optional record content selector.' },
      orderAttribute: { type: 'string', description: 'Optional ordering attribute name.' },
      totalCountSelector: { type: 'string', description: 'Optional total-count selector.' },
      totalCountAttribute: { type: 'string', description: 'Optional total-count attribute name.' },
      declaredRange: { type: 'string', required: true, description: 'Short descriptive label for the selected container, up to 128 characters. It does not limit rows or express a scan predicate.' },
      excludeSelector: { type: 'string', description: 'Optional selector for draft or otherwise excluded rows; at most 512 characters.' },
      fromTop: { type: 'boolean', description: 'For scan, first scroll to a proven top boundary; defaults to true.' },
      maxWindows: { type: 'integer', description: 'For scan, maximum rendered windows, 1..100; defaults to 24.' },
      maxDurationMs: { type: 'integer', description: 'For scan, bounded total duration, 1..60000 ms; defaults to 20000.' },
      maxTotalBytes: { type: 'integer', description: 'For scan, combined response budget, 1..5242880 bytes; defaults to 524288.' },
      maxRecords: { type: 'integer', required: true, description: 'Maximum records per native window, 1..200.' },
      maxContentChars: { type: 'integer', required: true, description: 'Maximum content characters per record, 1..4096.' }
    },
    async (args, exec) => {
      const error = generationInputError(args, true)
      if (error) return localResult('GENERATION_UNAVAILABLE', error)
      const { sessionGeneration, controlGeneration, taskId, action, fromTop, maxWindows,
        maxDurationMs, maxTotalBytes, maxRecords, maxContentChars, ...collectArgs } = args
      if (action === 'window') {
        if (fromTop !== undefined || maxWindows !== undefined || maxDurationMs !== undefined || maxTotalBytes !== undefined) {
          return localResult('INVALID_ARGUMENT', 'Scan budgets and fromTop are valid only for the scan action.')
        }
        const windowArgs = { action, ...collectArgs, maxRecords, maxContentChars }
        const invalid = validateArgs('collect', windowArgs)
        if (invalid) return localResult('INVALID_ARGUMENT', invalid)
        return browserRequest('collect', windowArgs, {
          sessionGeneration, controlGeneration, owner: 'ai', ...(taskId == null ? {} : { taskId })
        }, dependencies)
      }
      if (action !== 'scan') return localResult('INVALID_ARGUMENT', 'Collect action must be window or scan.')
      if (typeof taskId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(taskId)) {
        return localResult('INVALID_ARGUMENT', 'The scan action requires a taskId containing 1..64 letters, digits, underscores, or hyphens.')
      }
      if (fromTop !== undefined && typeof fromTop !== 'boolean') return localResult('INVALID_ARGUMENT', 'fromTop must be a boolean.')
      const budgetFields = [
        ['maxWindows', maxWindows, 1, 100],
        ['maxDurationMs', maxDurationMs, 1, 60000],
        ['maxTotalBytes', maxTotalBytes, 1, 5 * 1024 * 1024]
      ]
      for (const [name, value, minimum, maximum] of budgetFields) {
        if (value !== undefined && (!Number.isSafeInteger(value) || value < minimum || value > maximum)) {
          return localResult('INVALID_ARGUMENT', `${name} must be an integer from ${minimum} to ${maximum}.`)
        }
      }
      const windowArgs = { action: 'window', ...collectArgs,
        maxRecords: maxRecords == null ? 80 : maxRecords,
        maxContentChars: maxContentChars == null ? 4096 : maxContentChars }
      const invalid = validateArgs('collect', windowArgs)
      if (invalid) return localResult('INVALID_ARGUMENT', invalid)
      const budgets = {
        ...(maxWindows == null ? {} : { maxWindows }),
        ...(maxDurationMs == null ? {} : { maxDurationMs }),
        ...(maxTotalBytes == null ? {} : { maxBytes: maxTotalBytes }),
        ...(maxRecords == null ? {} : { maxRecords }),
        ...(maxContentChars == null ? {} : { maxContentChars })
      }
      const scan = await collectBrowserWindowSet({
        browserRequest,
        taskId,
        sessionGeneration,
        controlGeneration,
        options: { ...collectArgs, fromTop: fromTop !== false },
        budgets,
        signal: exec && exec.signal,
        dependencies
      })
      return collectScanResult(scan, sessionGeneration, controlGeneration)
    }
  )
  add(
    'browser_locate',
    'Find one unique visible element in the current page and return its current observation reference for browser_click or browser_type. Requires current AI control and does not automatically take control. A successful locate creates a new observation and invalidates references from earlier observations. No match returns NOT_FOUND; multiple matches return descriptions without executable references. Locating does not click or confirm a business outcome. Frame and shadow-root support is limited; do not assume arbitrary cross-frame coverage.',
    {
      ...inputProps(true, true),
      strategy: { type: 'string', required: true, enum: ['role', 'label', 'placeholder', 'text', 'css'], description: 'Matching strategy.' },
      value: { type: 'string', required: true, description: 'Role name, label, placeholder, visible text, or CSS selector; 1..2048 characters.' },
      exact: { type: 'boolean', required: true, description: 'Match the requested value exactly when true; otherwise use the strategy’s partial-match behavior.' },
      name: { type: 'string', description: 'Optional accessible name filter, 1..2048 characters; only valid with strategy=role.' },
      scopeRef: { type: 'string', description: 'Optional current observation reference that limits the search to that element’s descendants.' },
      partial: { type: 'boolean', description: 'Explicitly allow a result only within inventoried accessible frames. partial=true with incomplete coverage means local uniqueness, never whole-page uniqueness. Default rejects incomplete coverage.' }
    },
    async (args) => {
      const error = generationInputError(args, true)
      if (error) return localResult('GENERATION_UNAVAILABLE', error)
      const { sessionGeneration, controlGeneration, taskId, ...locateArgs } = args
      return browserRequest('locate', locateArgs, {
        sessionGeneration, controlGeneration, owner: 'ai', ...(taskId == null ? {} : { taskId })
      }, dependencies)
    }
  )
  add(
    'browser_click',
    'Click either a current observed node id or a recent one-use browser_screenshot token. Screenshot clicks expire after 15 seconds and require full-frame pixel plus page/document/viewport identity to match. The runtime conservatively targets an observed DOM anchor/button or uses a native coordinate fallback only for static canvas/video; dynamic media and stale identity are rejected. Editable or sensitive controls and frame/shadow targets remain unsupported. The native gesture is non-atomic: immediately observe the page and verify the business result; UNKNOWN may already have clicked, so do not resubmit.',
    { ...inputProps(true, true),
      nodeId: { type: 'string', description: 'Current observed node id, at most 128 characters; mutually exclusive with screenshotId/x/y.' },
      screenshotId: { type: 'string', description: 'One-use screenshot token valid for 15 seconds; full-frame pixels and page/document/viewport identity must still match. Requires bitmap x and y.' },
      x: { type: 'number', description: 'Horizontal screenshot bitmap coordinate; targets a conservative DOM anchor/button or static canvas/video fallback. Requires screenshotId and y.' },
      y: { type: 'number', description: 'Vertical screenshot bitmap coordinate; dynamic media or stale snapshots are rejected. Requires screenshotId and x.' },
      eventSubscriptionId: { type: 'string', description: 'Optional one-use event subscription id from browser_event arm; pass with eventNonce and taskId.' },
      eventNonce: { type: 'string', description: 'Optional paired expectation credential from browser_event arm; pass with eventSubscriptionId and taskId.' } },
    async (args) => {
      const error = generationInputError(args, true)
      if (error) return localResult('GENERATION_UNAVAILABLE', error)
      const nodeClick = typeof args.nodeId === 'string' && args.screenshotId === undefined && args.x === undefined && args.y === undefined
      const visualClick = args.nodeId === undefined && typeof args.screenshotId === 'string' &&
        /^[a-f0-9]{32}$/.test(args.screenshotId) && Number.isFinite(args.x) && Number.isFinite(args.y)
      if (!nodeClick && !visualClick) return localResult('INVALID_ARGUMENT', 'Provide either nodeId or screenshotId with finite bitmap x and y.')
      if ((args.eventSubscriptionId !== undefined || args.eventNonce !== undefined) && (!args.taskId ||
          typeof args.eventSubscriptionId !== 'string' || typeof args.eventNonce !== 'string')) {
        return localResult('INVALID_ARGUMENT', 'Event-correlated click requires paired credentials and the taskId used to arm the subscription.')
      }
      const clickArgs = { ...(nodeClick ? { nodeId: args.nodeId } : { screenshotId: args.screenshotId, x: args.x, y: args.y }),
        ...(args.eventSubscriptionId === undefined ? {} : { eventSubscriptionId: args.eventSubscriptionId, eventNonce: args.eventNonce }) }
      return browserRequest('click', clickArgs, taskEnvelope(args, {
        sessionGeneration: args.sessionGeneration, controlGeneration: args.controlGeneration, owner: 'ai'
      }), dependencies)
    }
  )
  add(
    'browser_type',
    'Type into a current node id returned by browser_observe or a unique element reference returned by browser_locate. Requires Browser automation enabled and current AI control. Text is limited to 4096 characters and 8192 UTF-8 bytes. Stale document or observation references are rejected. Do not repeat after UNKNOWN.',
    {
      ...inputProps(true, true),
      nodeId: { type: 'string', required: true, description: 'Current observed editable node id.' },
      text: { type: 'string', required: true, description: 'Text to type; at most 4096 characters and 8192 UTF-8 bytes.' },
      replace: { type: 'boolean', description: 'Replace the current value when true; otherwise append/type according to the Browser runtime.' }
    },
    async (args) => {
      const error = generationInputError(args, true)
      if (error) return localResult('GENERATION_UNAVAILABLE', error)
      return browserRequest('type', {
        nodeId: args.nodeId, text: args.text, ...(args.replace == null ? {} : { replace: args.replace })
      }, taskEnvelope(args, { sessionGeneration: args.sessionGeneration, controlGeneration: args.controlGeneration, owner: 'ai' }), dependencies)
    }
  )
  add(
    'browser_scroll',
    'Scroll the current Browser page or a node returned by browser_observe. Requires Browser automation enabled and current AI control. Do not repeat after UNKNOWN.',
    {
      ...inputProps(true, true),
      nodeId: { type: 'string', description: 'Optional current observed scrollable node id.' },
      direction: { type: 'string', required: true, enum: ['up', 'down'], description: 'Scroll direction.' },
      steps: { type: 'integer', description: 'Bounded scroll steps, 1..10; defaults to 1.' }
    },
    async (args) => {
      const error = generationInputError(args, true)
      if (error) return localResult('GENERATION_UNAVAILABLE', error)
      return browserRequest('scroll', {
        ...(args.nodeId == null ? {} : { nodeId: args.nodeId }),
        direction: args.direction,
        ...(args.steps == null ? {} : { steps: args.steps })
      }, taskEnvelope(args, { sessionGeneration: args.sessionGeneration, controlGeneration: args.controlGeneration, owner: 'ai' }), dependencies)
    }
  )
  add(
    'browser_screenshot',
    'Capture the active Browser session. Requires Browser automation enabled and current AI control. Saves a Browser-owned PNG and may return a one-use screenshotId valid for 15 seconds. Screenshot-coordinate clicks require a full-frame pixel match and current page/document/viewport identity; stale snapshots are rejected. Only a conservative DOM anchor/button target or static canvas/video native fallback is supported; editable/sensitive, frame, and shadow targets remain unsupported. The gesture is non-atomic: observe the page afterward and verify the business result. Image attachment availability is reported separately. Do not repeat the capture after UNKNOWN.',
    inputProps(true, true),
    async (args) => {
      const error = generationInputError(args, true)
      if (error) return localResult('GENERATION_UNAVAILABLE', error)
      return screenshotResult(await browserRequest('screenshot', {}, taskEnvelope(args, {
        sessionGeneration: args.sessionGeneration, controlGeneration: args.controlGeneration, owner: 'ai'
      }), dependencies))
    }
  )
  add(
    'browser_close',
    'Close the current Browser session. Requires Browser automation enabled and current AI control. Do not repeat after UNKNOWN; check browser_status first.',
    inputProps(true, true),
    async (args) => {
      const error = generationInputError(args, true)
      if (error) return localResult('GENERATION_UNAVAILABLE', error)
      return browserRequest('close', {}, taskEnvelope(args, {
        sessionGeneration: args.sessionGeneration, controlGeneration: args.controlGeneration, owner: 'ai'
      }), dependencies)
    }
  )
  add(
    'browser_debug',
    'Inspect or control only the current Browser WebView using bounded debug actions. Requires Browser automation enabled and current AI control. Page JavaScript is limited to the current target; no raw CDP or file access is exposed. Do not repeat after UNKNOWN.',
    {
      ...inputProps(true, true),
      action: { type: 'string', required: true, enum: ['status', 'console', 'evaluate', 'dom', 'ax_tree', 'emulate', 'interact'] },
      maxNodes: { type: 'integer', description: 'AX read-only observation limit, 1..256; default 64. AX refs are not DOM action refs.' },
      maxDepth: { type: 'integer', description: 'AX depth limit, 1..8; default 4. Partial frame coverage must be checked.' },
      includeIgnored: { type: 'boolean', description: 'Include ignored AX nodes; default false. Input values are never included.' },
      limit: { type: 'integer', description: 'Console entry limit, 1..50.' },
      expression: { type: 'string', description: 'Page expression, 1..8192 characters.' },
      selector: { type: 'string', description: 'Optional DOM selector, at most 512 characters.' },
      width: { type: 'integer', description: 'Emulated width, 240..2560; required when clear is not true.' },
      height: { type: 'integer', description: 'Emulated height, 240..2560; required when clear is not true.' },
      mobile: { type: 'boolean', description: 'Enable mobile viewport emulation; required when clear is not true.' },
      clear: { type: 'boolean', description: 'Set true by itself to clear emulation settings; do not combine it with width, height, or mobile.' },
      kind: { type: 'string', enum: ['key', 'tap', 'drag', 'select'] },
      key: { type: 'string', description: 'Key name, at most 32 characters.' },
      x: { type: 'number', description: 'Absolute viewport coordinate in CSS pixels, finite and within 0..10000.' },
      y: { type: 'number', description: 'Absolute viewport coordinate in CSS pixels, finite and within 0..10000.' },
      toX: { type: 'number', description: 'Absolute drag destination coordinate in viewport CSS pixels, finite and within 0..10000.' },
      toY: { type: 'number', description: 'Absolute drag destination coordinate in viewport CSS pixels, finite and within 0..10000.' },
      value: { type: 'string', description: 'Select value, at most 4096 characters.' }
    },
    async (args) => advancedRequest('debug', args, dependencies)
  )
  add(
    'browser_network',
    'Record and inspect HTTP requests or bounded WebSocket lifecycle/frame metadata for the current Browser page. start may opt in to bounded HTTP response bodies with includeBodies and write-request bodies with includeRequestBodies; request bodies are memory-only, limited to supported text/JSON/form content and 8 KiB, and appear in a visible native risk prompt before write replay. WebSocket frames use frames with cursor/limit 1..20 or export_frames for a separate .ws.json artifact; payload capture requires includeBodies and exports state payloadUnredacted, headersRedacted, and redacted accurately. HTTP HAR excludes WebSocket frames. Worker coverage is limited to verifiably attached same-origin dedicated workers; shared ServiceWorker targets are reported unsupported. Read-only GET/HEAD replay is bounded to the current origin. Write replay is a two-step replay_prepare, native user confirmation, replay_status, then replay with its one-use riskToken/captureId; never retry UNKNOWN because the request may already have been sent. Network mutation requires its separate Browser setting. This does not capture device-wide traffic.',
    {
      ...inputProps(true, true),
      action: { type: 'string', required: true, enum: ['start', 'stop', 'clear_rules', 'paused', 'list', 'frames', 'export_frames', 'body', 'replay_prepare', 'replay_status', 'replay', 'har', 'rules', 'resolve'] },
      includeBodies: { type: 'boolean', description: 'Opt in to bounded HTTP response bodies or WebSocket frame payloads when starting capture.' },
      includeRequestBodies: { type: 'boolean', description: 'Opt in to memory-only, supported text/JSON/form write-request body capture (8 KiB each and bounded total); visible in the native replay confirmation prompt.' },
      cursor: { type: 'integer', description: 'Non-negative cursor for list or frames.' }, limit: { type: 'integer', description: 'Page size for list or frames, 1..20.' },
      entryId: { type: 'string', description: 'Existing safe network record id.' },
      requestId: { type: 'string', description: '32-character requestId returned by replay_prepare; use replay_status to read the native confirmation result.' },
      riskToken: { type: 'string', description: 'One-use 32-character riskToken returned only after native confirmation; required with captureId for write replay.' },
      captureId: { type: 'string', description: 'Capture id returned by replay_status; required with riskToken for write replay.' },
      rules: {
        type: 'array', description: 'At most eight bounded request/response rules.',
        items: { type: 'object', additionalProperties: false, properties: {
          urlPattern: { type: 'string', required: true, description: 'Pattern, at most 256 characters.' },
          stage: { type: 'string', required: true, enum: ['request', 'response'] },
          method: { type: 'string', description: 'Optional HTTP method token, at most 16 characters.' },
          action: { type: 'string', required: true, enum: ['continue', 'block', 'modify', 'fulfill'] },
          headers: { type: 'object', additionalProperties: true, description: 'At most 16 string headers, values at most 1024 characters.' },
          url: { type: 'string', description: 'Credential-free HTTP(S) URL.' },
          postData: { type: 'string', description: 'Request body, at most 8192 characters.' },
          body: { type: 'string', description: 'Response body, at most 8192 characters.' },
          responseCode: { type: 'integer', description: 'HTTP response code, 200..599.' }
        } }
      },
      pausedId: { type: 'string', description: 'Existing paused request identifier.' },
      decision: { type: 'string', enum: ['continue', 'block', 'modify', 'fulfill'] },
      headers: { type: 'object', additionalProperties: true, description: 'At most 16 bounded header values.' }, url: { type: 'string', description: 'Credential-free HTTP(S) URL, at most 2048 characters.' },
      method: { type: 'string', description: 'HTTP method token, at most 16 characters.' }, postData: { type: 'string', description: 'Request body, at most 8192 characters.' },
      body: { type: 'string', description: 'Response body, at most 8192 characters.' }, responseCode: { type: 'integer', description: 'HTTP response code, 200..599.' }
    },
    async (args) => advancedRequest('network', args, dependencies)
  )
  add(
    'browser_selection',
    'List, read, consume, or clear context explicitly submitted by a person in Browser. Unbound or uncertain deliveries remain available here for management. This does not open the selection UI, claim AI control, or renew the control lease. Requires the current session and Browser automation enabled.',
    { ...inputProps(true), action: { type: 'string', required: true, enum: ['list', 'latest', 'consume', 'clear'] } },
    async (args) => advancedRequest('selection', args, dependencies)
  )
  add(
    'browser_page',
    'List or manage up to four Browser pages. Listing is read-only and only requires the current session generation. Creating, switching, or closing a page requires current AI control; each change advances control generation and invalidates prior node ids. Close cannot remove the final page.',
    {
      action: { type: 'string', required: true, enum: ['list', 'new', 'switch', 'close'] },
      ...inputProps(true),
      controlGeneration: { type: 'integer', description: 'Required for new, switch, and close; positive current generation from browser_status.' },
      pageId: { type: 'string', description: 'Required for switch and close; opaque id from browser_page list.' }
    },
    async (args) => {
      if (!args || typeof args !== 'object' || Array.isArray(args) ||
          Object.keys(args).some((key) => !['action', 'sessionGeneration', 'controlGeneration', 'pageId', 'taskId'].includes(key))) {
        return localResult('INVALID_ARGUMENT', 'Page request contains unsupported fields.')
      }
      if (!Number.isSafeInteger(args.sessionGeneration) || args.sessionGeneration <= 0) {
        return localResult('GENERATION_UNAVAILABLE', 'Call browser_status to get the active session generation first.')
      }
      if (args.action === 'list') {
        if (args.controlGeneration != null || args.pageId != null) return localResult('INVALID_ARGUMENT', 'Page list accepts only action and sessionGeneration.')
        return browserRequest('page', { action: 'list' }, taskEnvelope(args, { sessionGeneration: args.sessionGeneration }), dependencies)
      }
      if (!['new', 'switch', 'close'].includes(args.action)) return localResult('INVALID_ARGUMENT', 'Page action is invalid.')
      if (!Number.isSafeInteger(args.controlGeneration) || args.controlGeneration <= 0) {
        return localResult('GENERATION_UNAVAILABLE', 'Call browser_status to get the current control generation first.')
      }
      if (args.action === 'new') {
        if (args.pageId != null) return localResult('INVALID_ARGUMENT', 'Page new does not accept pageId.')
        return browserRequest('page', { action: 'new' }, taskEnvelope(args, {
          sessionGeneration: args.sessionGeneration,
          controlGeneration: args.controlGeneration,
          owner: 'ai'
        }), dependencies)
      }
      if (typeof args.pageId !== 'string' || !/^pg-[A-Fa-f0-9]{20}$/.test(args.pageId)) {
        return localResult('INVALID_ARGUMENT', 'Switch and close require a pageId returned by browser_page list.')
      }
      return browserRequest('page', { action: args.action, pageId: args.pageId }, taskEnvelope(args, {
        sessionGeneration: args.sessionGeneration,
        controlGeneration: args.controlGeneration,
        owner: 'ai'
      }), dependencies)
    }
  )
  add(
    'browser_serve',
    'Start an owned static HTTP service or register an existing loopback development service, inspect/list registrations, read bounded logs, stop only an owned service, or unregister external metadata without stopping its process. Project roots must be within the user projects directory. Owned static services use 127.0.0.1 and finite leases. This tool does not control a Browser page or use AI ownership generations. A timeout is UNKNOWN; check status/list and do not repeat automatically.',
    {
      action: { type: 'string', required: true, enum: ['start', 'register', 'status', 'list', 'logs', 'stop', 'unregister'] },
      dir: { type: 'string', description: 'Required for start: explicit static root directory.' },
      port: { type: 'integer', description: 'Optional start port, 0 for an available port, otherwise 1..65535.' },
      leaseMs: { type: 'integer', description: 'Optional finite lease, 60000..28800000 milliseconds.' },
      spaFallback: { type: 'string', description: 'Optional safe relative SPA document path; missing static assets still return 404.' },
      url: { type: 'string', description: 'Required for register: existing HTTP service on 127.0.0.1 with an explicit port.' },
      healthPath: { type: 'string', description: 'Optional safe local health-check path for register; defaults to /.' },
      serviceId: { type: 'string', description: 'Service id for status/stop/unregister; required for logs.' },
      limit: { type: 'integer', description: 'Optional bounded log count, 1..100.' }
    },
    async (args) => browserServeRequest(args, dependencies.serve || {})
  )
  add(
    'browser_file',
    'Start an HTTP(S) download, list Browser-owned downloads, read one download status, or cancel a transfer. Downloads remain in Browser output and are distinct from temporary screenshots. Start and cancel require current AI control; list and status are read-only. Queued success means accepted, not completed. The user must select upload files in the system picker; this tool cannot silently select device files. blob/data downloads are unsupported.',
    {
      ...inputProps(true),
      action: { type: 'string', required: true, enum: ['start', 'list', 'status', 'cancel'] },
      controlGeneration: { type: 'integer', description: 'Required only for start/cancel; forbidden for list/status.' },
      url: { type: 'string', description: 'Required for start: HTTP(S) URL without embedded credentials, at most 2048 characters.' },
      suggestedFileName: { type: 'string', description: 'Optional start display filename, at most 180 characters, without path separators or controls.' },
      id: { type: 'string', description: 'Required for status/cancel: download id returned by the Browser.' },
      limit: { type: 'integer', description: 'Optional list page size, 1..20.' },
      beforeId: { type: 'string', description: 'Optional list cursor returned by the previous page.' },
    },
    async (args) => {
      const { sessionGeneration, controlGeneration, taskId, ...fileArgs } = args
      const invalid = validateArgs('file', fileArgs)
      if (invalid) return localResult('INVALID_ARGUMENT', invalid)
      const readOnly = ['list', 'status'].includes(fileArgs.action)
      if (readOnly && controlGeneration != null) return localResult('INVALID_ARGUMENT', 'Read-only downloads do not accept a control generation.')
      const generationError = generationInputError(args, !readOnly)
      if (generationError) return localResult('GENERATION_UNAVAILABLE', generationError)
      return browserRequest('file', fileArgs, {
        sessionGeneration, ...(!readOnly ? { controlGeneration, owner: 'ai' } : {}),
        ...(taskId == null ? {} : { taskId })
      }, dependencies)
    },
  )
  add(
    'browser_upload',
    'Request a one-time upload grant for explicit files inside the current DSH project. Browser displays the exact filenames, sizes, and site origin for user approval. After approval, use action=activate with the freshly observed file input nodeId to open only its matching website chooser; do not use browser_click (which is synthetic for file inputs). Then list the returned opaque fileIds and submit selected ids only while that matching chooser is pending. UPLOAD_ACTIVATION_UNRESOLVED means the native gesture may still produce a late chooser: do not retry on that WebView; consume/cancel the late chooser or use a new WebView. Grants expire and are bound to this task, Browser session, page, and chooser. Submission delivers read-only temporary file URIs to the website; it does not confirm that the website accepted or uploaded the files. Never use this for arbitrary device files or protected project data.',
    {
      ...inputProps(true, true),
      taskId: { type: 'string', required: true, description: 'Active task checkpoint id that binds approval and submission to the current Browser page.' },
      action: { type: 'string', required: true, enum: ['request', 'list', 'status', 'activate', 'submit', 'cancel'] },
      paths: { type: 'array', description: 'Required for request: 1..10 unique paths relative to /root/projects; each at most 16 MiB and all together at most 32 MiB. Protected and secret paths are denied.', items: { type: 'string' } },
      acceptTypes: { type: 'array', description: 'Optional request file type filters, such as application/pdf, image/*, or .csv.', items: { type: 'string' } },
      multiple: { type: 'boolean', description: 'Optional approval for selecting multiple files; defaults to false. Submission is limited to one file if either this approval or the website chooser is single-file. Only file types accepted by both can be submitted.' },
      summary: { type: 'string', description: 'Optional short user-facing reason for the upload request.' },
      approvalId: { type: 'string', description: 'Required for list/status/activate/submit/cancel: short-lived approval id returned by request.' },
      nodeId: { type: 'string', description: 'Required for activate: fresh nodeId from browser_observe for the visible input[type=file], or its visible associated label. Activation uses a Browser-owned native WebView tap and succeeds only when the matching native chooser callback is observed.' },
      fileIds: { type: 'array', description: 'Required for submit: selected opaque fileIds returned by an approved list; never pass paths here.', items: { type: 'string' } },
      controlGeneration: { type: 'integer', required: true, description: 'Current AI control generation; required for every upload action.' }
    },
    async (args) => {
      const error = generationInputError(args, true)
      if (error) return localResult('GENERATION_UNAVAILABLE', error)
      if (typeof args.taskId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(args.taskId)) return localResult('INVALID_ARGUMENT', 'browser_upload requires an explicit valid taskId.')
      const uploadArgs = Object.fromEntries(Object.entries(args).filter(([key]) => ['action', 'paths', 'acceptTypes', 'multiple', 'summary', 'approvalId', 'fileIds', 'nodeId'].includes(key)))
      if (uploadArgs.action === 'request' && uploadArgs.multiple === undefined) uploadArgs.multiple = false
      const invalid = validateArgs('upload', uploadArgs)
      if (invalid) return localResult('INVALID_ARGUMENT', invalid)
      return browserRequest('upload', uploadArgs, taskEnvelope(args, {
        sessionGeneration: args.sessionGeneration, controlGeneration: args.controlGeneration, owner: 'ai'
      }), dependencies)
    }
  )
  add(
    'browser_event',
    'Arm, wait for, cancel, or resolve a bounded Browser event subscription. Every action requires the current AI-owned session and an explicit taskId envelope. Arm before the click/navigation you want to observe. Event observations are bounded and correlated to the action scope when credentials match; they do not prove causality or business success. Wait supports cancellation; UNKNOWN means the wait/action outcome is unknown and must not be retried automatically.',
    {
      ...inputProps(true, true),
      taskId: { type: 'string', required: true, description: 'Explicit task checkpoint id. It binds the event subscription and must be reused for the correlated click/navigation.' },
      action: { type: 'string', required: true, enum: ['arm', 'wait', 'cancel', 'resolve_dialog'] },
      eventKinds: { type: 'array', description: 'Required for arm: 1..6 unique kinds from navigation_started, navigation_finished, navigation_failed, new_window, download, javascript_dialog.', items: { type: 'string', enum: ['navigation_started', 'navigation_finished', 'navigation_failed', 'new_window', 'download', 'javascript_dialog'] } },
      ttlMs: { type: 'integer', description: 'Optional arm lifetime, 1..30000 ms; defaults to 15000 and is bounded by the request deadline.' },
      subscriptionId: { type: 'string', description: 'Required for wait/cancel; 32 lowercase hexadecimal characters returned by arm.' },
      timeoutMs: { type: 'integer', description: 'Optional wait duration, 1..30000 ms; defaults to 15000 and is bounded by the request deadline.' },
      eventId: { type: 'string', description: 'Required for resolve_dialog; 32 lowercase hexadecimal event id from a javascript_dialog event.' },
      decision: { type: 'string', enum: ['accept', 'dismiss', 'prompt'], description: 'Required for resolve_dialog.' },
      promptText: { type: 'string', description: 'Only for decision=prompt; at most 4096 characters.' }
    },
    async (args, exec) => {
      const error = generationInputError(args, true)
      if (error) return localResult('GENERATION_UNAVAILABLE', error)
      if (typeof args.taskId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(args.taskId)) {
        return localResult('INVALID_ARGUMENT', 'browser_event requires an explicit valid taskId.')
      }
      const eventArgs = Object.fromEntries(Object.entries(args).filter(([key]) =>
        ['action', 'eventKinds', 'ttlMs', 'subscriptionId', 'timeoutMs', 'eventId', 'decision', 'promptText'].includes(key)))
      const invalid = validateArgs('event', eventArgs)
      if (invalid) return localResult('INVALID_ARGUMENT', invalid)
      return browserRequest('event', eventArgs, taskEnvelope(args, {
        sessionGeneration: args.sessionGeneration, controlGeneration: args.controlGeneration, owner: 'ai'
      }), {
        ...dependencies,
        ...(args.action === 'wait' && exec && exec.signal ? { signal: exec.signal } : {})
      })
    }
  )
  add(
    'browser_control',
    'Request, inspect, or cancel a Browser control approval. A request only asks a human to approve Browser control; it cannot approve itself, execute page actions, or grant control. Requests require an already open Browser session. After human approval, obtain fresh browser_status and browser_observe data, then reconcile or resume the task explicitly; old actions are never replayed automatically.',
    {
      action: { type: 'string', required: true, enum: ['request', 'status', 'cancel'] },
      summary: { type: 'string', description: 'Required for request: concise reason for taking control, 1..1000 characters.' },
      taskId: { type: 'string', description: 'Optional request-only correlation id; it does not bind or grant control.' },
      approvalId: { type: 'string', description: 'Required for status/cancel: 32 lowercase hexadecimal id returned by request.' }
    },
    async (args) => {
      const controlArgs = Object.fromEntries(Object.entries(args).filter(([key]) => ['action', 'summary', 'taskId', 'approvalId'].includes(key)))
      const invalid = validateArgs('control', controlArgs)
      if (invalid) return localResult('INVALID_ARGUMENT', invalid)
      return browserRequest('control', controlArgs, {}, dependencies)
    }
  )
  add(
    'browser_rendering',
    'Read or update Browser page display policy using a revision check. Requires current AI control. Changes invalidate page references but do not reload the page; explicitly navigate reload only after considering unsent forms. persisted is true only when this command committed and verified a changed policy, not a claim about earlier reads. After SETTINGS_PERSISTENCE_FAILED or UNKNOWN, read settings and inspect the page before deciding what to do; never blindly repeat.',
    {
      ...inputProps(true, true),
      operation: { type: 'string', required: true, enum: ['get', 'set'] },
      key: { type: 'string', enum: ['userAgentMode', 'layoutWidthMode', 'overviewMode', 'textScalePercent'] },
      userAgentMode: { type: 'string', enum: ['default', 'mobile', 'desktop'] },
      layoutWidthMode: { type: 'string', enum: ['device', 'wide'] },
      overviewMode: { type: 'boolean' },
      textScalePercent: { type: 'integer', description: 'Text size percent, 50..200; separate from page zoom.' },
      expectedRevision: { type: 'integer', description: 'Current settings revision from get; required for set.' }
    },
    async args => {
      const error = generationInputError(args, true)
      if (error) return localResult('GENERATION_UNAVAILABLE', error)
      const policyKeys = ['userAgentMode', 'layoutWidthMode', 'overviewMode', 'textScalePercent']
      const supplied = policyKeys.filter(key => Object.hasOwn(args, key))
      const allowed = ['sessionGeneration', 'controlGeneration', 'taskId', 'operation', 'key', 'expectedRevision', ...policyKeys]
      if (Object.keys(args).some(key => !allowed.includes(key)) ||
          args.operation === 'get' && (supplied.length || args.key !== undefined || args.expectedRevision !== undefined) ||
          args.operation === 'set' && (supplied.length !== 1 || supplied[0] !== args.key)) {
        return localResult('INVALID_ARGUMENT', 'Use get without policy fields, or set with one matching key and value field.')
      }
      const requestArgs = { action: 'rendering', operation: args.operation }
      if (args.operation === 'set') Object.assign(requestArgs, {
        key: args.key, value: args[args.key], expectedRevision: args.expectedRevision
      })
      const invalid = validateArgs('debug', requestArgs)
      if (invalid) return localResult('INVALID_ARGUMENT', invalid)
      return browserRequest('debug', requestArgs, taskEnvelope(args, {
        sessionGeneration: args.sessionGeneration, controlGeneration: args.controlGeneration, owner: 'ai'
      }), dependencies)
    }
  )
  add(
    'browser_ax',
    'Observe bounded accessibility nodes and act using only the opaque actionRef returned by this observation. Coverage is limited to reported frames. References expire 30 seconds after native creation, and become invalid after navigation, page or control changes, reconnect, or a new AX observation. Refresh status and observe after rejection. Actions require AI control and Browser debug permission. Never replay UNKNOWN automatically: effects may already have occurred. type supports text-like inputs and editable content, not passwords. Legacy browser_debug ax_tree remains read-only.',
    {
      ...inputProps(true, true),
      action: { type: 'string', required: true, enum: ['observe', 'click', 'type', 'scroll'] },
      maxNodes: { type: 'integer', description: 'Observation node budget, 1..256.' },
      maxDepth: { type: 'integer', description: 'Observation depth budget, 1..8.' },
      includeIgnored: { type: 'boolean' },
      actionRef: { type: 'string', description: 'Opaque reference from the latest AX observation; never a raw CDP node id.' },
      text: { type: 'string', description: 'Type text, at most 4096 UTF-16 code units; empty is allowed.' },
      replace: { type: 'boolean' },
      direction: { type: 'string', enum: ['up', 'down'] },
      steps: { type: 'integer', description: 'Scroll steps, 1..10.' }
    },
    async (args) => {
      if (!['observe', 'click', 'type', 'scroll'].includes(args.action)) return localResult('INVALID_ARGUMENT', 'Unsupported AX action')
      return advancedRequest('debug', { ...args, action: `ax_${args.action}` }, dependencies)
    }
  )
  return tools
}

module.exports = { OUTPUT, advancedRequest, buildTools, openBrowser, outputResult, renderResult, screenshotResult }
