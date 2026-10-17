'use strict'

const POLL_INTERVAL_MS = 2_000
const LIFETIME_MS = 30 * 60 * 1_000
const SELECTION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const REVOKED_CODES = new Set([
  'AUTOMATION_DISABLED', 'CLOSED', 'SESSION_REVOKED', 'STALE_SESSION', 'GENERATION_UNAVAILABLE',
])

/**
 * Relays only selections created after a successful Browser open. Dependencies
 * are deliberately narrow so the plugin integration supplies real CLI calls,
 * while this module owns the bounded polling and cancellation lifecycle.
 */
function createSelectionRelay({
  bridge,
  readSelection,
  writeReceipt,
  now = monotonicNow,
  schedule = setTimeout,
  cancel = clearTimeout,
  pollIntervalMs = POLL_INTERVAL_MS,
  lifetimeMs = LIFETIME_MS,
} = {}) {
  let active = null
  let disposed = false
  let requestInFlight = false
  let lastStopReason = ''

  function start(exec, openResult) {
    if (disposed) return result('DISPOSED')
    if (!bridge || typeof bridge.bindAfterOpen !== 'function' || typeof bridge.deliver !== 'function' ||
        typeof readSelection !== 'function' || typeof writeReceipt !== 'function') return result('DEPENDENCY_UNAVAILABLE')
    const startedAt = now()
    if (!Number.isFinite(startedAt) || pollIntervalMs < 1 || lifetimeMs < 1) {
      return result('INVALID_CONFIGURATION')
    }
    const bound = bridge.bindAfterOpen(exec, openResult)
    if (!bound || bound.code !== 'BOUND') {
      const previouslyAdmittedMayComplete = requestInFlight && !!active
      let relayStopped = false
      if (active && bound && bound.code === 'SESSION_BINDING_CONFLICT') {
        stopActive(active, 'OPEN_BINDING_REJECTED')
        relayStopped = true
      }
      return result(bound && bound.code || 'OPEN_NOT_CONFIRMED', {
        previouslyAdmittedMayComplete,
        relayStopped,
      })
    }
    if (!Number.isSafeInteger(bound.selectionSequence) || bound.selectionSequence < 0) {
      return result('SELECTION_BASELINE_UNAVAILABLE')
    }
    if (active && active.sessionGeneration === openResult.sessionGeneration &&
        agentId(active.exec) === agentId(exec)) {
      // A repeated open for the same DSH execution and Browser generation is
      // not a new relay epoch. Keep its cursor, timer, and in-flight operation.
      return result('ACTIVE', {
        sessionGeneration: active.sessionGeneration,
        expiresInMillis: Math.max(0, active.deadline - startedAt),
        inFlight: active.inFlight,
      })
    }
    if (requestInFlight) {
      const wasActive = !!active
      if (active) stopActive(active, 'OPEN_BINDING_REPLACED_DURING_OPERATION')
      if (typeof bridge.clearAfterRejectedOpen === 'function') bridge.clearAfterRejectedOpen(exec, openResult)
      return result('PREVIOUS_OPERATION_PENDING', {
        previouslyAdmittedMayComplete: wasActive,
        relayStopped: wasActive,
      })
    }
    if (active) stopActive(active, 'REPLACED_BY_OPEN')
    const controller = new AbortController()
    const state = {
      exec,
      sessionGeneration: openResult.sessionGeneration,
      afterSequence: bound.selectionSequence,
      controller,
      startedAt,
      deadline: startedAt + lifetimeMs,
      inFlight: false,
      expiryTimer: null,
      pollTimer: null,
      stopped: false,
      reason: '',
      baselineReady: true,
      lastReceipt: null,
    }
    active = state
    lastStopReason = ''
    state.expiryTimer = schedule(() => stopActive(state, 'LIFETIME_EXPIRED'), lifetimeMs)
    scheduleNext(state)
    return result('ACTIVE', { sessionGeneration: state.sessionGeneration, expiresInMillis: lifetimeMs })
  }

  function scheduleNext(state) {
    if (!isCurrent(state) || !state.baselineReady) return
    const remaining = state.deadline - now()
    if (!Number.isFinite(remaining) || remaining <= 0) {
      stopActive(state, 'LIFETIME_EXPIRED')
      return
    }
    state.pollTimer = schedule(() => {
      state.pollTimer = null
      launch(state, () => poll(state))
    }, Math.min(pollIntervalMs, remaining))
  }

  function launch(state, operation) {
    if (!isCurrent(state) || requestInFlight) return
    requestInFlight = true
    state.inFlight = true
    void Promise.resolve().then(operation).catch(() => {
      if (isCurrent(state)) stopActive(state, 'RELAY_OPERATION_FAILED')
    }).finally(() => {
      state.inFlight = false
      requestInFlight = false
      if (isCurrent(state) && state.baselineReady) scheduleNext(state)
    })
  }

  async function poll(state) {
    if (!isCurrent(state)) return
    const polled = await readOne(state)
    if (!isCurrent(state)) return
    if (!polled.ok) {
      stopActive(state, polled.reason)
      return
    }
    if (polled.selection) {
      const advance = await deliverAndReceipt(state, polled.selection)
      if (!isCurrent(state)) return
      if (advance !== false) state.afterSequence = polled.selection.selectionSequence
    }
  }

  async function readOne(state) {
    if (!isCurrent(state)) return { ok: false, reason: 'STOPPED' }
    try {
      const response = await readSelection({
        exec: state.exec,
        sessionGeneration: state.sessionGeneration,
        afterSequence: state.afterSequence,
        signal: state.controller.signal,
      })
      if (!isCurrent(state)) return { ok: false, reason: state.reason || 'STOPPED' }
      if (!response || response.sessionGenerationAvailable !== true ||
          !Number.isSafeInteger(response.sessionGeneration) || response.sessionGeneration <= 0) {
        return { ok: false, reason: ['CLI_USAGE', 'CLI_UNAVAILABLE', 'PROTOCOL_ERROR', 'INVALID_ARGUMENT']
          .includes(response && response.code) ? response.code : 'GENERATION_UNAVAILABLE' }
      }
      if (response.sessionGeneration !== state.sessionGeneration) {
        return { ok: false, reason: 'BROWSER_SESSION_CHANGED' }
      }
      if (response.automationEnabled === false) return { ok: false, reason: 'AUTOMATION_DISABLED' }
      if (!['IDLE', 'RUNNING'].includes(response.state)) return { ok: false, reason: 'BROWSER_NOT_ACTIVE' }
      if (REVOKED_CODES.has(response.code)) return { ok: false, reason: response.code }
      if (response.code === 'NOT_FOUND') {
        return response.executed === 'false'
          ? { ok: true, selection: null }
          : { ok: false, reason: 'SELECTION_READ_REJECTED' }
      }
      if (response.code !== 'OK' || response.executed !== 'false') {
        return { ok: false, reason: 'SELECTION_READ_REJECTED' }
      }
      const data = parseData(response.dataJson)
      if (!data || typeof data !== 'object' || Array.isArray(data)) {
        return { ok: false, reason: 'SELECTION_RESPONSE_INVALID' }
      }
      if (data.selectionId === undefined) return { ok: false, reason: 'SELECTION_RESPONSE_INVALID' }
      if (!SELECTION_ID.test(data.selectionId)) return { ok: false, reason: 'SELECTION_ID_INVALID' }
      const resumableClaim = data.deliveryState === 'CLAIMED' &&
        data.destinationSessionKey === agentId(state.exec)
      if (!Number.isSafeInteger(data.selectionSequence) ||
          (data.selectionSequence <= state.afterSequence && !resumableClaim)) {
        return { ok: false, reason: 'SELECTION_SEQUENCE_INVALID' }
      }
      return { ok: true, selection: data }
    } catch (error) {
      return { ok: false, reason: state.controller.signal.aborted ? (state.reason || 'STOPPED') : 'SELECTION_READ_FAILED' }
    }
  }

  async function deliverAndReceipt(state, selection) {
    if (!isCurrent(state) || state.controller.signal.aborted) return
    let receipt
    try {
      receipt = await bridge.deliver({
        exec: state.exec,
        signal: state.controller.signal,
        sessionGeneration: state.sessionGeneration,
        sessionGenerationAvailable: true,
        selection,
      })
    } catch {
      receipt = {
        code: 'UNKNOWN',
        accepted: null,
        doNotRetry: true,
        message: 'Delivery outcome is unknown. Do not resend this selection.',
      }
    }
    if (!isCurrent(state)) return
    if (receipt && receipt.claimRejected === true) {
      state.lastReceipt = { selectionId: selection.selectionId, receipt: boundedReceipt(receipt) }
      if (receipt.doNotRetry === true) stopActive(state, 'SELECTION_CLAIM_UNCONFIRMED')
      return false
    }
    const safeReceipt = boundedReceipt(receipt)
    try {
      const written = await writeReceipt({
        exec: state.exec,
        signal: state.controller.signal,
        sessionGeneration: state.sessionGeneration,
        selectionId: selection.selectionId,
        destinationSessionKey: agentId(state.exec),
        deliveryCode: safeReceipt.code,
        ...(safeReceipt.requestId ? { requestId: safeReceipt.requestId } : {}),
        ...(typeof safeReceipt.imageIncluded === 'boolean' ? { imageIncluded: safeReceipt.imageIncluded } : {}),
        receipt: safeReceipt,
      })
      state.lastReceipt = { selectionId: selection.selectionId, receipt: safeReceipt }
      if (!isCurrent(state)) return
      if (written !== true && !(written && written.code === 'OK' && written.accepted === true)) {
        stopActive(state, 'RECEIPT_WRITE_FAILED')
      }
    } catch {
      state.lastReceipt = { selectionId: selection.selectionId, receipt: safeReceipt }
      stopActive(state, 'RECEIPT_WRITE_FAILED')
    }
  }

  function stop(reason = 'DISPOSED') {
    if (!active) return result(disposed ? 'DISPOSED' : 'INACTIVE', { reason: lastStopReason })
    stopActive(active, reason)
    return result('STOPPED', { reason })
  }

  function dispose() {
    disposed = true
    if (active) stopActive(active, 'DISPOSED')
    return result('DISPOSED')
  }

  function status() {
    if (!active) return result(disposed ? 'DISPOSED' : 'INACTIVE', { reason: lastStopReason })
    return result(active.stopped ? 'STOPPED' : 'ACTIVE', {
      sessionGeneration: active.sessionGeneration,
      expiresInMillis: Math.max(0, active.deadline - now()),
      baselineReady: active.baselineReady,
      inFlight: active.inFlight,
      reason: active.reason,
      lastReceipt: active.lastReceipt,
    })
  }

  function rejectOpen(exec, openResult, code = 'BINDING_UNAVAILABLE') {
    const previouslyAdmittedMayComplete = requestInFlight && !!active
    const confirmed = typeof bridge.isConfirmedOpen === 'function' && bridge.isConfirmedOpen(openResult)
    if (!confirmed) return result(code, { relayStopped: false })
    if (typeof bridge.revokeAfterRejectedOpen === 'function') bridge.revokeAfterRejectedOpen(openResult)
    else if (typeof bridge.clearAfterRejectedOpen === 'function') {
      bridge.clearAfterRejectedOpen(active ? active.exec : exec, openResult)
    }
    const relayStopped = !!active
    if (relayStopped) stopActive(active, 'OPEN_BINDING_REJECTED')
    return result(code, { previouslyAdmittedMayComplete: previouslyAdmittedMayComplete && relayStopped, relayStopped })
  }

  function stopActive(state, reason) {
    if (state.stopped) return
    state.stopped = true
    state.reason = reason
    lastStopReason = reason
    if (state.expiryTimer !== null) cancel(state.expiryTimer)
    if (state.pollTimer !== null) cancel(state.pollTimer)
    state.expiryTimer = null
    state.pollTimer = null
    state.controller.abort()
    if (active === state) active = null
  }

  function isCurrent(state) {
    return active === state && !state.stopped && !state.controller.signal.aborted
  }

  return Object.freeze({ start, stop, dispose, rejectOpen, status })
}

function parseData(value) {
  if (typeof value !== 'string' || value.length > 12 * 1024) return null
  try { return JSON.parse(value) } catch { return null }
}

function boundedReceipt(receipt) {
  if (!receipt || typeof receipt !== 'object' || Array.isArray(receipt)) {
    return { code: 'UNKNOWN', accepted: null, doNotRetry: true }
  }
  const copy = {
    code: typeof receipt.code === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(receipt.code) ? receipt.code : 'UNKNOWN',
    accepted: receipt.accepted === true ? true : receipt.accepted === false ? false : null,
    doNotRetry: receipt.doNotRetry === true || receipt.code === 'UNKNOWN',
  }
  for (const key of ['requestId', 'delivery', 'receiptMeaning', 'message', 'imageStatus']) {
    if (typeof receipt[key] === 'string') copy[key] = receipt[key].slice(0, 512)
  }
  if (typeof receipt.imageIncluded === 'boolean') copy.imageIncluded = receipt.imageIncluded
  if (Buffer.byteLength(JSON.stringify(copy), 'utf8') > 2 * 1024) {
    return { code: copy.code, accepted: copy.accepted, doNotRetry: true }
  }
  return copy
}

function result(code, extras = {}) {
  return {
    code,
    ...(Number.isSafeInteger(extras.sessionGeneration) ? { sessionGeneration: extras.sessionGeneration } : {}),
    ...(Number.isFinite(extras.expiresInMillis) ? { expiresInMillis: extras.expiresInMillis } : {}),
    ...(typeof extras.baselineReady === 'boolean' ? { baselineReady: extras.baselineReady } : {}),
    ...(typeof extras.inFlight === 'boolean' ? { inFlight: extras.inFlight } : {}),
    ...(typeof extras.previouslyAdmittedMayComplete === 'boolean'
      ? { previouslyAdmittedMayComplete: extras.previouslyAdmittedMayComplete }
      : {}),
    ...(typeof extras.relayStopped === 'boolean' ? { relayStopped: extras.relayStopped } : {}),
    ...(extras.reason ? { reason: extras.reason } : {}),
    ...(extras.lastReceipt ? { lastReceipt: extras.lastReceipt } : {}),
  }
}

function monotonicNow() {
  return Number(process.hrtime.bigint()) / 1e6
}

function agentId(exec) {
  const value = exec && exec.agent && exec.agent.id
  return typeof value === 'string' ? value : ''
}

module.exports = { LIFETIME_MS, POLL_INTERVAL_MS, createSelectionRelay }
