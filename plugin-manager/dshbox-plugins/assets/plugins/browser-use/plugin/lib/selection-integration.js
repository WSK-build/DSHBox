'use strict'

const { browserRequest } = require('./client')
const { createSelectionFeedbackBridge } = require('./selection-feedback')
const { createSelectionRelay } = require('./selection-relay')
const { createProjectContextResolver } = require('./project-context')
const { createTaskFeedbackRelay } = require('./task-feedback')
const { confirmedOpenSequence: openSequence } = require('./open-confirmation')

function createSelectionIntegration(ctx, dependencies = {}) {
  if (!ctx || typeof ctx.effect !== 'function') return null
  const request = dependencies.browserRequest || browserRequest
  let sessionController
  let runtime = null
  let lastOpen = null
  let disposed = false
  let highestGeneration = 0
  let boundAgentId = ''
  let generationConflicted = false
  let selectionStatus = 'SESSION_CONTROLLER_UNAVAILABLE'
  let taskFeedbackStatus = 'SESSION_CONTROLLER_UNAVAILABLE'
  let serviceListenerRegistered = false

  function disposeRuntime() {
    if (!runtime) return
    try { runtime.relay.dispose() } catch {}
    try { runtime.taskFeedback.dispose() } catch {}
    runtime = null
  }

  function makeRuntime(controller) {
    const bridge = createSelectionFeedbackBridge({
      sessionController: controller,
      resolveProjectContext: dependencies.resolveProjectContext || createProjectContextResolver({
        dependencies,
        ...(dependencies.browserServeRequest ? { request: dependencies.browserServeRequest } : {}),
      }),
      claimSelection: async ({ selectionId, destinationSessionKey, requestId, payloadDigest, sessionGeneration, signal }) => {
        const response = await request('selection', {
          action: 'claim', selectionId, destinationSessionKey, requestId, payloadDigest,
        }, { sessionGeneration }, { ...dependencies, signal })
        return { code: response.code, executed: response.executed, accepted: response.code === 'CLAIMED' }
      },
      beginAdmission: async ({ selectionId, destinationSessionKey, requestId, payloadDigest, sessionGeneration, signal }) => {
        const response = await request('selection', {
          action: 'admit', selectionId, destinationSessionKey, requestId, payloadDigest,
        }, { sessionGeneration }, { ...dependencies, signal })
        return { code: response.code, accepted: response.code === 'ADMITTING' && response.executed === 'true' }
      },
    })
    const relay = createSelectionRelay({
      bridge,
      readSelection: ({ exec, sessionGeneration, afterSequence, signal }) => request('selection', {
        action: 'pending', afterSequence, destinationSessionKey: agentId(exec),
      }, { sessionGeneration }, { ...dependencies, signal }),
      writeReceipt: async ({ exec, sessionGeneration, signal, selectionId, deliveryCode, requestId, imageIncluded }) => {
        const response = await request('selection', {
          action: 'receipt', selectionId, destinationSessionKey: agentId(exec), deliveryCode,
          ...(requestId ? { requestId } : {}),
          ...(typeof imageIncluded === 'boolean' ? { imageIncluded } : {}),
        }, { sessionGeneration }, { ...dependencies, signal })
        return response.code === 'OK' && response.executed === 'true'
      },
      ...(dependencies.relayOptions || {}),
    })
    const taskFeedback = createTaskFeedbackRelay({
      sessionController: controller,
      readPending: ({ sessionGeneration, afterSequence, destinationSessionKey, signal }) => request('selection', {
        action: 'task_pending', afterSequence, destinationSessionKey,
      }, { sessionGeneration }, { ...dependencies, signal }),
      claim: ({ feedbackId, destinationSessionKey, requestId, payloadDigest, sessionGeneration, signal }) => request('selection', {
        action: 'task_claim', feedbackId, destinationSessionKey, requestId, payloadDigest,
      }, { sessionGeneration }, { ...dependencies, signal }),
      admit: ({ feedbackId, destinationSessionKey, requestId, payloadDigest, sessionGeneration, signal }) => request('selection', {
        action: 'task_admit', feedbackId, destinationSessionKey, requestId, payloadDigest,
      }, { sessionGeneration }, { ...dependencies, signal }),
      writeReceipt: ({ feedbackId, destinationSessionKey, requestId, deliveryCode, sessionGeneration, signal }) => request('selection', {
        action: 'task_receipt', feedbackId, destinationSessionKey, requestId, deliveryCode,
      }, { sessionGeneration }, { ...dependencies, signal }),
      ...(dependencies.taskFeedbackOptions || {}),
    })
    return { bridge, relay, taskFeedback }
  }

  function isController(value) { return !!value && typeof value.prompt === 'function' }

  function refreshController(value) {
    if (disposed) return
    const next = isController(value) ? value : undefined
    if (next === sessionController) return
    disposeRuntime()
    sessionController = next
    if (!sessionController) {
      const unavailable = generationConflicted ? 'SESSION_BINDING_CONFLICT' : 'SESSION_CONTROLLER_UNAVAILABLE'
      selectionStatus = unavailable
      taskFeedbackStatus = unavailable
      return
    }
    runtime = makeRuntime(sessionController)
    selectionStatus = 'UNBOUND'
    taskFeedbackStatus = 'UNBOUND'
    if (generationConflicted) {
      selectionStatus = 'SESSION_BINDING_CONFLICT'
      taskFeedbackStatus = 'SESSION_BINDING_CONFLICT'
      return
    }
    if (lastOpen && !generationConflicted && lastOpen.result.sessionGeneration === highestGeneration && agentId(lastOpen.exec) === boundAgentId) {
      startRelays(lastOpen.exec, lastOpen.result)
    }
  }

  function startRelays(exec, openResult) {
    if (disposed) return { code: 'DISPOSED' }
    if (!runtime) {
      selectionStatus = 'SESSION_CONTROLLER_UNAVAILABLE'
      taskFeedbackStatus = 'SESSION_CONTROLLER_UNAVAILABLE'
      return { code: selectionStatus }
    }
    let selected
    let task
    try { selected = runtime.relay.start(exec, openResult) } catch { selected = { code: 'START_FAILED' } }
    try { task = runtime.taskFeedback.start(exec, openResult) } catch { task = { code: 'START_FAILED' } }
    selectionStatus = resultCode(selected)
    taskFeedbackStatus = resultCode(task)
    return selected
  }

  function acceptOpen(exec, openResult) {
    if (disposed) return { code: 'DISPOSED' }
    const owner = agentId(exec)
    const generation = openResult && openResult.sessionGeneration
    if (!owner || !Number.isSafeInteger(generation) || generation < 1 ||
        openResult.code !== 'OK' || !['true', 'false'].includes(openResult.executed) ||
        !['IDLE', 'RUNNING'].includes(openResult.state) || openResult.sessionGenerationAvailable !== true ||
        openSequence(openResult) === null) {
      lastOpen = null
      return { code: 'OPEN_NOT_CONFIRMED' }
    }
    if (generation < highestGeneration) return { code: 'STALE_BROWSER_GENERATION' }
    if (generation === highestGeneration && boundAgentId && boundAgentId !== owner) {
      generationConflicted = true
      lastOpen = null
      selectionStatus = 'SESSION_BINDING_CONFLICT'
      taskFeedbackStatus = 'SESSION_BINDING_CONFLICT'
      let previouslyAdmittedMayComplete = false
      if (runtime) {
        try { previouslyAdmittedMayComplete = runtime.relay.status().inFlight === true } catch {}
      }
      disposeRuntime()
      return { code: 'SESSION_BINDING_CONFLICT', relayStopped: true, previouslyAdmittedMayComplete }
    }
    if (generation > highestGeneration) {
      highestGeneration = generation
      boundAgentId = owner
      generationConflicted = false
    }
    if (generationConflicted) return { code: 'SESSION_BINDING_CONFLICT' }
    if (!runtime && sessionController) runtime = makeRuntime(sessionController)
    lastOpen = { exec, result: openResult }
    return startRelays(exec, openResult)
  }

  const onServiceChange = (name, value) => {
    if (name === 'sessionController') refreshController(value)
  }
  if (typeof ctx.on === 'function') {
    ctx.on('internal/service', onServiceChange)
    serviceListenerRegistered = true
  }
  try {
    refreshController(dependencies.sessionController !== undefined
      ? dependencies.sessionController
      : ctx.reflect && typeof ctx.reflect.get === 'function'
        ? ctx.reflect.get('sessionController', false)
        : ctx.sessionController)
  } catch {
    refreshController(undefined)
  }
  ctx.effect(() => () => {
    disposed = true
    if (serviceListenerRegistered && typeof ctx.off === 'function') ctx.off('internal/service', onServiceChange)
    disposeRuntime()
    lastOpen = null
  })

  return Object.freeze({
    afterOpen: (exec, result) => {
      return acceptOpen(exec, result)
    },
    rejectOpen: (exec, result) => {
      if (disposed) return { code: 'DISPOSED', relayStopped: false }
      const owner = agentId(exec)
      const generation = result && result.sessionGeneration
      const confirmed = owner && Number.isSafeInteger(generation) && generation > 0 && isConfirmedOpenResult(result)
      if (confirmed) {
        if (generation > highestGeneration) {
          highestGeneration = generation
          boundAgentId = owner
          generationConflicted = false
        } else if (generation === highestGeneration && boundAgentId && boundAgentId !== owner) {
          generationConflicted = true
        }
        lastOpen = null
      }
      if (confirmed && generation === highestGeneration && boundAgentId !== owner) {
        let previouslyAdmittedMayComplete = false
        if (runtime) {
          try { previouslyAdmittedMayComplete = runtime.relay.status().inFlight === true } catch {}
        }
        disposeRuntime()
        selectionStatus = 'SESSION_BINDING_CONFLICT'
        taskFeedbackStatus = 'SESSION_BINDING_CONFLICT'
        return { code: 'SESSION_BINDING_CONFLICT', relayStopped: true, previouslyAdmittedMayComplete }
      }
      let rejected = { code: runtime ? 'BINDING_UNAVAILABLE' : 'SESSION_CONTROLLER_UNAVAILABLE', relayStopped: false }
      if (runtime) {
        try { rejected = runtime.relay.rejectOpen(exec, result) || rejected } catch {}
        try { runtime.taskFeedback.rejectOpen(exec, result) } catch {}
      }
      selectionStatus = resultCode(rejected)
      return rejected
    },
    afterClose: (exec, result) => {
      if (disposed) return { code: 'DISPOSED' }
      if (!runtime) {
        if (confirmedCloseResult(exec, result, boundAgentId, highestGeneration)) lastOpen = null
        return { code: 'SESSION_CONTROLLER_UNAVAILABLE' }
      }
      let cleared
      try { cleared = runtime.bridge.clearAfterClose(exec, result) } catch { cleared = { code: 'CLOSE_NOT_CONFIRMED' } }
      if (cleared.code === 'UNBOUND') {
        try { runtime.relay.stop('BROWSER_CLOSED') } catch {}
        try { runtime.taskFeedback.afterClose(exec, result) } catch {}
        lastOpen = null
      }
      return cleared
    },
    status: () => {
      if (disposed) return Object.freeze({ selection: 'DISPOSED', taskFeedback: 'DISPOSED' })
      let selection = selectionStatus
      let taskFeedback = taskFeedbackStatus
      let selectionReason = ''
      if (runtime) {
        try {
          const current = runtime.relay.status()
          selectionReason = current.reason || ''
          if (selection === 'ACTIVE' && current.code !== 'ACTIVE') selection = current.code
        } catch {}
        try {
          const current = runtime.taskFeedback.status()
          if (taskFeedback === 'ACTIVE' && current.code !== 'ACTIVE') taskFeedback = current.code
        } catch {}
      }
      return Object.freeze({ selection, taskFeedback, ...(selectionReason ? { selectionReason } : {}) })
    },
    dispose: () => {
      if (!disposed) {
        disposed = true
        if (serviceListenerRegistered && typeof ctx.off === 'function') ctx.off('internal/service', onServiceChange)
        disposeRuntime()
        lastOpen = null
      }
      return { code: 'DISPOSED' }
    },
  })
}

module.exports = { createSelectionIntegration }

function agentId(exec) {
  const value = exec && exec.agent && exec.agent.id
  return typeof value === 'string' && value.length > 0 && value.length <= 256 && !/[\u0000-\u001f\u007f]/.test(value)
    ? value
    : ''
}

function resultCode(value) { return value && typeof value.code === 'string' ? value.code : 'START_FAILED' }

function isConfirmedOpenResult(value) {
  return value && value.code === 'OK' && ['true', 'false'].includes(value.executed) &&
    ['IDLE', 'RUNNING'].includes(value.state) && value.sessionGenerationAvailable === true && openSequence(value) !== null
}

function confirmedCloseResult(exec, result, owner, generation) {
  if (!owner || agentId(exec) !== owner || !Number.isSafeInteger(generation) || generation < 1 ||
      !result || result.code !== 'OK' || result.executed !== 'true' || result.state !== 'CLOSED' ||
      result.sessionGenerationAvailable !== true || result.sessionGeneration !== generation + 1) return false
  let data
  try { data = JSON.parse(result.dataJson || '{}') } catch { return false }
  return !!data && data.event === 'closed'
}
