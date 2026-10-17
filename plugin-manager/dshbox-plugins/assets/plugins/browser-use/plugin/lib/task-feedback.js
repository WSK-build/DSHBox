'use strict'

const crypto = require('node:crypto')
const { confirmedOpenSequence: openSequence } = require('./open-confirmation')

const POLL_INTERVAL_MS = 2_000
const MAX_LIFETIME_MS = 30 * 60 * 1_000
const MAX_PAGE_ITEMS = 64
const FEEDBACK_ID = /^[A-Za-z0-9_-]{1,64}$/

function createTaskFeedbackRelay({
  sessionController,
  readPending,
  claim,
  admit,
  writeReceipt,
  now = monotonicNow,
  schedule = setTimeout,
  cancel = clearTimeout,
  pollIntervalMs = POLL_INTERVAL_MS,
  lifetimeMs = MAX_LIFETIME_MS,
} = {}) {
  let active = null
  let disposed = false
  let inFlight = false
  let conflictedGeneration = 0

  function start(exec, openResult) {
    if (disposed) return result('DISPOSED')
    const sessionKey = agentId(exec)
    const generation = openResult && openResult.sessionGeneration
    const sequence = openSequence(openResult)
    if (!sessionKey || !Number.isSafeInteger(generation) || generation < 1 || sequence === null ||
        !['IDLE', 'RUNNING'].includes(openResult.state) ||
        !['true', 'false'].includes(openResult.executed)) return result('OPEN_NOT_CONFIRMED')
    if (active && generation < active.sessionGeneration) return result('STALE_BROWSER_GENERATION')
    if (generation === conflictedGeneration) return result('SESSION_BINDING_CONFLICT')
    if (typeof readPending !== 'function' || typeof claim !== 'function' || typeof admit !== 'function' ||
        typeof writeReceipt !== 'function' || !sessionController || typeof sessionController.prompt !== 'function') {
      return result('DEPENDENCY_UNAVAILABLE')
    }
    if (active && active.sessionGeneration === generation) {
      if (active.sessionKey === sessionKey) return result('ACTIVE')
      stopActive(active, 'SESSION_BINDING_CONFLICT')
      conflictedGeneration = generation
      return result('SESSION_BINDING_CONFLICT')
    }
    if (generation > conflictedGeneration) conflictedGeneration = 0
    if (active) stopActive(active, 'REPLACED_BY_OPEN')
    const started = now()
    if (!Number.isFinite(started) || pollIntervalMs < 1 || lifetimeMs < 1) return result('INVALID_CONFIGURATION')
    const state = {
      exec, sessionKey, sessionGeneration: generation, afterSequence: 0,
      deadline: started + Math.min(lifetimeMs, MAX_LIFETIME_MS), controller: new AbortController(),
      timer: null, stopped: false, inFlight: false,
    }
    active = state
    scheduleNext(state)
    return result('ACTIVE', { sessionGeneration: generation })
  }

  function scheduleNext(state) {
    if (!current(state)) return
    const remaining = state.deadline - now()
    if (!Number.isFinite(remaining) || remaining <= 0) return stopActive(state, 'LIFETIME_EXPIRED')
    state.timer = schedule(() => {
      state.timer = null
      void poll(state).catch(() => stopActive(state, 'RELAY_OPERATION_FAILED')).finally(() => {
        state.inFlight = false
        inFlight = false
        if (current(state)) scheduleNext(state)
      })
    }, Math.min(pollIntervalMs, remaining))
  }

  async function poll(state) {
    if (!current(state) || inFlight) return
    inFlight = true
    state.inFlight = true
    const response = await readPending({ exec: state.exec, sessionGeneration: state.sessionGeneration,
      afterSequence: state.afterSequence, destinationSessionKey: state.sessionKey, signal: state.controller.signal })
    if (!current(state)) return
    const data = responseData(response)
    if (!response || response.code !== 'OK' || response.executed !== 'false' ||
        response.sessionGenerationAvailable !== true || response.sessionGeneration !== state.sessionGeneration || !data ||
        !Number.isSafeInteger(data.afterSequence) || data.afterSequence !== state.afterSequence ||
        !Number.isSafeInteger(data.nextSequence) || data.nextSequence < state.afterSequence ||
        !Array.isArray(data.items) || data.items.length > MAX_PAGE_ITEMS) return stopActive(state, 'PENDING_RESPONSE_INVALID')
    for (const item of data.items) {
      if (!current(state)) return
      if (!validItem(item, state.sessionGeneration, state.afterSequence)) return stopActive(state, 'PENDING_ITEM_INVALID')
      const outcome = await deliver(state, item)
      if (!current(state)) return
      if (!outcome.processed) return
      state.afterSequence = item.sequence
    }
    state.afterSequence = data.nextSequence
  }

  async function deliver(state, item) {
    const content = [{ type: 'text', text: item.text }]
    const payloadDigest = sha256(Buffer.from(item.text, 'utf8'))
    const requestId = sha256(`browser-task-feedback\0${state.sessionKey}\0${item.feedbackId}\0${item.revision}`).slice(0, 32)
    let claimResult
    try {
      claimResult = await claim({ exec: state.exec, feedbackId: item.feedbackId, destinationSessionKey: state.sessionKey,
        requestId, payloadDigest, sessionGeneration: state.sessionGeneration, signal: state.controller.signal })
    } catch {
      return { processed: false }
    }
    if (!current(state)) return { processed: false }
    if (!acceptedTransition(claimResult, item.feedbackId, requestId, payloadDigest, ['pending', 'claimed'], state.sessionGeneration)) {
      return { processed: false }
    }
    let admitResult
    try {
      admitResult = await admit({ exec: state.exec, feedbackId: item.feedbackId, destinationSessionKey: state.sessionKey,
        requestId, payloadDigest, sessionGeneration: state.sessionGeneration, signal: state.controller.signal })
    } catch {
      return { processed: false }
    }
    if (!current(state)) return { processed: false }
    if (!acceptedTransition(admitResult, item.feedbackId, requestId, payloadDigest, 'admitted', state.sessionGeneration)) {
      if (admitResult && admitResult.code === 'FEEDBACK_ALREADY_ADMITTED' &&
          matchesFeedbackOperation(admitResult, item.feedbackId, requestId, payloadDigest)) {
        await receipt(state, item, requestId, 'UNKNOWN')
        return { processed: true }
      }
      return { processed: false }
    }
    let deliveryCode = 'UNKNOWN'
    try {
      const ack = await sessionController.prompt({ requestId, sessionId: state.sessionKey, mode: 'queue', content }, state.controller.signal)
      if (ack && ack.accepted === true) deliveryCode = 'PROMPT_ACCEPTED'
    } catch {}
    if (current(state)) await receipt(state, item, requestId, deliveryCode)
    return { processed: true }
  }

  async function receipt(state, item, requestId, deliveryCode) {
    try {
      await writeReceipt({ exec: state.exec, feedbackId: item.feedbackId, destinationSessionKey: state.sessionKey,
        requestId, deliveryCode, sessionGeneration: state.sessionGeneration, signal: state.controller.signal })
    } catch {}
  }

  function afterClose(exec, resultValue) {
    if (!active || agentId(exec) !== active.sessionKey || !confirmedClose(resultValue, active.sessionGeneration)) return result('CLOSE_NOT_CONFIRMED')
    stopActive(active, 'BROWSER_CLOSED')
    return result('STOPPED', { reason: 'BROWSER_CLOSED' })
  }

  function rejectOpen(exec, openResult) {
    if (!isConfirmedOpen(openResult) || !active || !agentId(exec)) return
    if (openResult.sessionGeneration > active.sessionGeneration ||
        active.sessionKey === agentId(exec) && active.sessionGeneration === openResult.sessionGeneration) {
      stopActive(active, 'OPEN_REJECTED')
    }
  }

  function stop(reason = 'STOPPED') {
    if (active) stopActive(active, reason)
    return result('STOPPED', { reason })
  }

  function dispose() {
    disposed = true
    if (active) stopActive(active, 'DISPOSED')
    return result('DISPOSED')
  }

  function stopActive(state, reason) {
    if (state.stopped) return
    state.stopped = true
    state.reason = reason
    if (state.timer !== null) cancel(state.timer)
    state.timer = null
    state.controller.abort()
    if (active === state) active = null
  }

  function current(state) { return active === state && !state.stopped && !state.controller.signal.aborted }

  return Object.freeze({ start, afterClose, rejectOpen, stop, dispose, status: () => active
    ? result('ACTIVE', { sessionGeneration: active.sessionGeneration, afterSequence: active.afterSequence,
      inFlight: active.inFlight, reason: active.reason || '' }) : result(disposed ? 'DISPOSED' : 'INACTIVE') })
}

function validItem(item, generation, afterSequence) {
  return item && typeof item === 'object' && !Array.isArray(item) &&
    Object.keys(item).sort().join(',') === 'feedbackId,kind,revision,sequence,sessionGeneration,state,taskId,text,title'.split(',').sort().join(',') &&
    FEEDBACK_ID.test(item.feedbackId) && item.kind === 'task_recovery' && typeof item.taskId === 'string' &&
    /^[A-Za-z0-9_-]{1,64}$/.test(item.taskId) && Number.isSafeInteger(item.revision) && item.revision >= 0 &&
    Number.isSafeInteger(item.sequence) && item.sequence > afterSequence && Number.isSafeInteger(item.sessionGeneration) &&
    item.sessionGeneration === generation && typeof item.title === 'string' && item.title.length <= 120 &&
    typeof item.text === 'string' && Buffer.byteLength(item.text, 'utf8') <= 64 * 1024 && validRecoveryText(item) &&
    ['pending', 'claimed'].includes(item.state)
}

function validRecoveryText(item) {
  const guidance = 'Recovery guidance: call browser_task status with this Task id before acting to locate this task and confirm its current revision, title, goal, and status. If the task is missing or any value differs from this draft, stop and do not continue business actions. This draft may be stale; prior results do not prove success. Never replay an UNKNOWN effect. Make fresh Browser observations before deciding what happened.'
  const pattern = /^(Recovery guidance: [^\n]+)\nTask id: ([A-Za-z0-9_-]{1,64})\nRevision: (0|[1-9][0-9]*)\nTitle: ([^\r\n]{0,120})\nCreated wall time: ([0-9]{1,16})\nTask status: (running|waiting_page|waiting_user|paused|needs_reconciliation|budget_exhausted|completed|failed|expired)\nGoal: ([\s\S]*)$/
  const match = pattern.exec(item.text)
  if (!match || match[1] !== guidance || match[2] !== item.taskId || Number(match[3]) !== item.revision ||
      match[4] !== item.title ||
      !Number.isSafeInteger(Number(match[5])) || Number(match[5]) < 0) return false
  const goalAndFollowingNotes = match[7]
  return goalAndFollowingNotes.length > 0 && !goalAndFollowingNotes.startsWith('\nNotes: ')
}

function acceptedTransition(response, feedbackId, requestId, payloadDigest, state, sessionGeneration) {
  const data = responseData(response)
  const allowedStates = Array.isArray(state) ? state : [state]
  return response && response.code === 'OK' && response.executed === 'true' && data &&
    response.sessionGenerationAvailable === true && response.sessionGeneration === sessionGeneration &&
    data.feedbackId === feedbackId && data.requestId === requestId && data.payloadDigest === payloadDigest && allowedStates.includes(data.state)
}

function matchesFeedbackOperation(response, feedbackId, requestId, payloadDigest) {
  const data = responseData(response)
  return !!data && data.feedbackId === feedbackId && data.requestId === requestId && data.payloadDigest === payloadDigest
}

function responseData(response) {
  if (!response || typeof response.dataJson !== 'string' || response.dataJson.length > 256 * 1024) return null
  try { const data = JSON.parse(response.dataJson); return data && typeof data === 'object' && !Array.isArray(data) ? data : null } catch { return null }
}

function isConfirmedOpen(value) {
  return !!value && value.code === 'OK' && ['true', 'false'].includes(value.executed) &&
    ['IDLE', 'RUNNING'].includes(value.state) && Number.isSafeInteger(value.sessionGeneration) &&
    value.sessionGeneration > 0 && openSequence(value) !== null
}

function confirmedClose(value, generation) {
  if (!value || value.code !== 'OK' || value.executed !== 'true' || value.state !== 'CLOSED' ||
      value.sessionGeneration !== generation + 1) return false
  const data = responseData(value)
  return !!data && data.event === 'closed'
}

function agentId(exec) {
  const value = exec && exec.agent && exec.agent.id
  return typeof value === 'string' && value.length > 0 && value.length <= 256 && !/[\u0000-\u001f\u007f]/.test(value) ? value : ''
}

function sha256(value) { return crypto.createHash('sha256').update(value).digest('hex') }
function monotonicNow() { return Number(process.hrtime.bigint() / 1_000_000n) }
function result(code, extras = {}) { return { code, ...extras } }

module.exports = { createTaskFeedbackRelay }
