'use strict'

const { execFile: nodeExecFile } = require('node:child_process')
const { randomUUID } = require('node:crypto')
const fs = require('node:fs')
const path = require('node:path')

const CLI = '/opt/browser/bin/browser'
const REQUEST_LIMIT = 16 * 1024
const RESPONSE_LIMIT = 256 * 1024
const MAX_DEADLINE_MS = 60000
const CONTROL_METHODS = new Set(['open', 'navigate', 'locate', 'click', 'type', 'scroll', 'screenshot', 'close', 'debug', 'network', 'page', 'file', 'upload', 'collect', 'event'])
const METHODS = new Set(['status', 'open', 'observe', 'wait', 'navigate', 'locate', 'click', 'type', 'scroll', 'screenshot', 'close', 'debug', 'network', 'selection', 'page', 'file', 'upload', 'task', 'collect', 'event', 'control'])
const NODE_ID_PATTERN = /^[A-Za-z0-9_.:-]{1,128}$/
const DOWNLOAD_ID_PATTERN = /^[a-f0-9]{32}$/
const DOWNLOAD_STATES = new Set(['QUEUED', 'DOWNLOADING', 'CANCELLING', 'COMPLETED', 'FAILED', 'CANCELLED'])
const SCREENSHOT_FAILURE_STAGES = Object.freeze({
  TARGET_UNAVAILABLE: 'target_attach',
  AMBIGUOUS_TARGET: 'target_attach',
  CDP_UNSUPPORTED: 'pixel_read',
  CDP_TIMEOUT: 'pixel_read',
  BACKGROUND_UNAVAILABLE: 'surface_attach',
  CLOSED: 'surface_attach',
  WINDOW_NOT_ACTIVE: 'surface_attach',
  VIEWPORT_UNAVAILABLE: 'surface_attach',
  CLIPPED_VIEWPORT: 'surface_attach',
  NO_FRAME: 'pixel_read',
  PIXEL_READ_FAILED: 'pixel_read',
  CAPTURE_EMPTY_RESULT: 'pixel_read',
  CANCELLED: 'pixel_read',
  DEADLINE_EXPIRED: 'pixel_read',
  IDENTITY_CHANGED: 'identity_match',
  PNG_INVALID: 'decode',
  PNG_TOO_LARGE: 'decode',
  ENCODE_FAILED: 'encode',
  STORAGE_FAILED: 'storage',
  SCREENSHOT_QUOTA_EXCEEDED: 'storage',
  JOURNAL_UNAVAILABLE: 'journal',
  SCREENSHOT_DEBUG_REQUIRED: 'surface_attach',
  CAPTURE_BUSY: 'surface_attach',
  VIRTUAL_DISPLAY_UNAVAILABLE: 'surface_attach',
  RESTORE_FAILED: 'surface_attach',
})
const MAX_DOWNLOAD_BYTES = 25 * 1024 * 1024
const METHOD_BUDGET_MS = {
  status: 5000,
  open: 20000,
  observe: 10000,
  wait: 15000,
  navigate: 12000,
  locate: 12000,
  click: 12000,
  type: 12000,
  scroll: 12000,
  screenshot: 30000,
  close: 10000,
  debug: 20000,
  network: 20000,
  selection: 10000,
  page: 10000,
  file: 15000,
  upload: 15000,
  task: 10000,
  event: 30000,
  control: 10000
}

function ownKeysOnly(value, allowed, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return `${label} must be an object`
  for (const key of Object.keys(value)) if (!allowed.includes(key)) return `${label} contains an unsupported field`
  return ''
}

function validateGeneration(value, name, required) {
  if (value == null) return required ? `${name} is required` : ''
  if (!Number.isSafeInteger(value) || value < 0) return `${name} must be a non-negative safe integer`
  if (required && value === 0) return `${name} must be positive for an active Browser session`
  return ''
}

function validateArgs(method, args) {
  const keys = {
    status: [],
    open: ['url', 'thumbnail'],
    observe: ['maxTextChars'],
    wait: ['condition', 'timeoutMs', 'pollIntervalMs', 'url', 'text', 'selector', 'state'],
    navigate: ['action', 'eventSubscriptionId', 'eventNonce', 'thumbnail'],
    locate: ['strategy', 'value', 'exact', 'name', 'scopeRef', 'partial'],
    click: ['nodeId', 'screenshotId', 'x', 'y', 'eventSubscriptionId', 'eventNonce'],
    type: ['nodeId', 'text', 'replace'],
    scroll: ['nodeId', 'direction', 'steps'],
    screenshot: [],
    close: [],
    debug: null,
    network: null,
    selection: ['action'],
    page: null,
    file: null,
    upload: null,
    task: null,
    collect: null,
    event: null,
    control: null
  }[method]
  if (!METHODS.has(method)) return 'unsupported browser method'
  if (method === 'control') {
    if (!args || typeof args !== 'object' || Array.isArray(args)) return 'control args must be an object'
    if (args.action === 'request') return !ownKeysOnly(args, ['action', 'summary', 'taskId'], 'args') &&
      typeof args.summary === 'string' && args.summary.trim().length >= 1 && args.summary.length <= 1000 &&
      !/[\u0000-\u001f\u007f]/.test(args.summary) &&
      (args.taskId === undefined || (typeof args.taskId === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(args.taskId)))
      ? '' : 'control request requires a 1..1000 character summary and optional correlation taskId'
    if (['status', 'cancel'].includes(args.action)) return !ownKeysOnly(args, ['action', 'approvalId'], 'args') &&
      typeof args.approvalId === 'string' && /^[a-f0-9]{32}$/.test(args.approvalId)
      ? '' : `control ${args.action} requires a 32-character lowercase hexadecimal approvalId`
    return 'control action must be request, status, or cancel'
  }
  if (method === 'task') {
    if (!args || typeof args !== 'object' || Array.isArray(args)) return 'task args must be an object'
    if (args.action === 'reconcile' && args.expectedRevision !== undefined) {
      return 'task reconcile does not accept expectedRevision; use it only with update or delete'
    }
    const needsAiControl = ['begin', 'complete', 'reconcile', 'resume'].includes(args.action)
    if (needsAiControl) {
      // Generations are carried in the request envelope and checked natively.
      if (args.action === 'begin') {
      return !ownKeysOnly(args, ['action', 'durationMs', 'operationLimit', 'title', 'goal', 'notes'], 'args') &&
        (args.durationMs === undefined || (Number.isSafeInteger(args.durationMs) && args.durationMs >= 1 && args.durationMs <= 14400000)) &&
        (args.operationLimit === undefined || (Number.isSafeInteger(args.operationLimit) && args.operationLimit >= 1 && args.operationLimit <= 2000)) &&
        (args.title === undefined || (typeof args.title === 'string' && args.title.length <= 120)) &&
        (args.goal === undefined || (typeof args.goal === 'string' && args.goal.length <= 4000)) &&
        (args.notes === undefined || (typeof args.notes === 'string' && args.notes.length <= 2000))
        ? '' : 'task begin budget is invalid'
      }
    }
    if (args.action === 'list') return !ownKeysOnly(args, ['action', 'offset', 'limit'], 'args') &&
      (args.offset === undefined || (Number.isSafeInteger(args.offset) && args.offset >= 0)) &&
      (args.limit === undefined || (Number.isSafeInteger(args.limit) && args.limit >= 1 && args.limit <= 100))
      ? '' : 'task list fields are invalid'
    if (args.action === 'update') return !ownKeysOnly(args, ['action', 'taskId', 'expectedRevision', 'title', 'goal', 'notes'], 'args') &&
      typeof args.taskId === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(args.taskId) &&
      Number.isSafeInteger(args.expectedRevision) && args.expectedRevision >= 0 &&
      ['title', 'goal', 'notes'].some((key) => args[key] !== undefined) &&
      (args.title === undefined || (typeof args.title === 'string' && args.title.length <= 120)) &&
      (args.goal === undefined || (typeof args.goal === 'string' && args.goal.length <= 4000)) &&
      (args.notes === undefined || (typeof args.notes === 'string' && args.notes.length <= 2000))
      ? '' : 'task update fields are invalid'
    if (args.action === 'delete') return !ownKeysOnly(args, ['action', 'taskId', 'expectedRevision'], 'args') &&
      typeof args.taskId === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(args.taskId) &&
      Number.isSafeInteger(args.expectedRevision) && args.expectedRevision >= 0
      ? '' : 'task delete fields are invalid'
    if (args.action === 'fail') return !ownKeysOnly(args, ['action', 'taskId', 'reason'], 'args') &&
      typeof args.taskId === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(args.taskId) &&
      (args.reason === undefined || (typeof args.reason === 'string' && args.reason.trim().length >= 1 && args.reason.length <= 1000))
      ? '' : 'task fail reason, when provided, must contain 1..1000 non-whitespace characters'
    if (['status', 'pause', 'wait', 'complete', 'reconcile', 'resume'].includes(args.action)) {
      return !ownKeysOnly(args, ['action', 'taskId', 'reason'], 'args') &&
        typeof args.taskId === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(args.taskId) &&
        (args.action === 'wait'
          ? ['page_load', 'page_condition', 'user_action', 'user_authentication', 'recovery_check'].includes(args.reason)
          : args.reason === undefined)
        ? '' : `task ${args.action} fields are invalid`
    }
    return 'task action is invalid'
  }
  if (method === 'selection' && args && args.action === 'pending') {
    return !ownKeysOnly(args, ['action', 'afterSequence', 'destinationSessionKey'], 'args') &&
      (args.afterSequence === undefined || (Number.isSafeInteger(args.afterSequence) && args.afterSequence >= 0)) &&
      (args.destinationSessionKey === undefined || (typeof args.destinationSessionKey === 'string' &&
        args.destinationSessionKey.length <= 256 && !/[\u0000-\u001f\u007f]/.test(args.destinationSessionKey)))
      ? '' : 'selection cursor or session key is invalid'
  }
  if (method === 'selection' && args && ['claim', 'admit'].includes(args.action)) {
    return !ownKeysOnly(args, ['action', 'selectionId', 'destinationSessionKey', 'requestId', 'payloadDigest'], 'args') &&
      typeof args.selectionId === 'string' && /^[a-fA-F0-9-]{36}$/.test(args.selectionId) &&
      typeof args.destinationSessionKey === 'string' && args.destinationSessionKey.length > 0 && args.destinationSessionKey.length <= 256 && !/[\u0000-\u001f\u007f]/.test(args.destinationSessionKey) &&
      typeof args.requestId === 'string' && /^[a-f0-9]{32}$/.test(args.requestId) &&
      typeof args.payloadDigest === 'string' && /^[a-f0-9]{64}$/.test(args.payloadDigest)
      ? '' : 'selection claim fields are invalid'
  }
  if (method === 'selection' && args && args.action === 'receipt') {
    return !ownKeysOnly(args, ['action', 'selectionId', 'destinationSessionKey', 'deliveryCode', 'requestId', 'imageIncluded'], 'args') &&
      typeof args.selectionId === 'string' && /^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$/.test(args.selectionId) &&
      typeof args.destinationSessionKey === 'string' && args.destinationSessionKey.length > 0 && args.destinationSessionKey.length <= 256 && !/[\u0000-\u001f\u007f]/.test(args.destinationSessionKey) &&
      typeof args.deliveryCode === 'string' && /^[A-Z_]{1,64}$/.test(args.deliveryCode) &&
      (args.requestId === undefined || (typeof args.requestId === 'string' && /^[a-f0-9]{32}$/.test(args.requestId))) &&
      (args.imageIncluded === undefined || typeof args.imageIncluded === 'boolean')
      ? '' : 'selection receipt fields are invalid'
  }
  if (method === 'selection' && args && ['task_pending', 'task_claim', 'task_admit', 'task_receipt'].includes(args.action)) {
    const validDestination = typeof args.destinationSessionKey === 'string' && args.destinationSessionKey.length > 0 &&
      args.destinationSessionKey.length <= 256 && !/[\u0000-\u001f\u007f]/.test(args.destinationSessionKey)
    if (args.action === 'task_pending') return !ownKeysOnly(args, ['action', 'afterSequence', 'destinationSessionKey'], 'args') &&
      Number.isSafeInteger(args.afterSequence) && args.afterSequence >= 0 && validDestination
      ? '' : 'task feedback cursor or destination is invalid'
    if (args.action === 'task_claim' || args.action === 'task_admit') return !ownKeysOnly(args,
      ['action', 'feedbackId', 'destinationSessionKey', 'requestId', 'payloadDigest'], 'args') &&
      typeof args.feedbackId === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(args.feedbackId) && validDestination &&
      typeof args.requestId === 'string' && /^[a-f0-9]{32}$/.test(args.requestId) &&
      typeof args.payloadDigest === 'string' && /^[a-f0-9]{64}$/.test(args.payloadDigest)
      ? '' : 'task feedback claim fields are invalid'
    return !ownKeysOnly(args, ['action', 'feedbackId', 'destinationSessionKey', 'requestId', 'deliveryCode'], 'args') &&
      typeof args.feedbackId === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(args.feedbackId) && validDestination &&
      typeof args.requestId === 'string' && /^[a-f0-9]{32}$/.test(args.requestId) &&
      ['PROMPT_ACCEPTED', 'UNKNOWN', 'BLOCKED'].includes(args.deliveryCode)
      ? '' : 'task feedback receipt fields are invalid'
  }
  if (method === 'file') return validateFileArgs(args)
  if (method === 'upload') return validateUploadArgs(args)
  if (method === 'page') {
    if (!args || typeof args !== 'object' || Array.isArray(args)) return 'page args must be an object'
    if (args.action === 'list' || args.action === 'new') {
      return ownKeysOnly(args, ['action'], 'args')
    }
    if (args.action === 'switch' || args.action === 'close') {
      if (ownKeysOnly(args, ['action', 'pageId'], 'args')) return 'page args contain unsupported fields'
      if (typeof args.pageId !== 'string' || !/^pg-[A-Fa-f0-9]{20}$/.test(args.pageId)) return 'pageId is invalid'
      return ''
    }
    return 'page action must be list, new, switch, or close'
  }
  if (method === 'debug' || method === 'network') return validateAdvancedArgs(method, args)
  if (method === 'collect') {
    const only = (...fields) => ownKeysOnly(args, ['action', ...fields], 'args') === ''
    const selector = (value, required) => typeof value === 'string' && value.length >= (required ? 1 : 0) && value.length <= 512 && !/[\u0000-\u001f\u007f]/.test(value)
    const attr = (value) => typeof value === 'string' && /^[A-Za-z_:][A-Za-z0-9_.:-]{0,63}$/.test(value)
    if (args.action !== 'window' || !only('containerSelector', 'messageSelector', 'keyAttribute', 'authorSelector', 'authorAttribute', 'contentSelector', 'orderAttribute', 'totalCountSelector', 'totalCountAttribute', 'declaredRange', 'maxRecords', 'maxContentChars', 'excludeSelector') ||
        !selector(args.containerSelector, true) || !selector(args.messageSelector, true) || !attr(args.keyAttribute) ||
        ['authorSelector', 'contentSelector', 'totalCountSelector', 'excludeSelector'].some((key) => args[key] !== undefined && !selector(args[key], true)) ||
        ['authorAttribute', 'orderAttribute', 'totalCountAttribute'].some((key) => args[key] !== undefined && !attr(args[key])) ||
        typeof args.declaredRange !== 'string' || args.declaredRange.length < 1 || args.declaredRange.length > 128 || /[\u0000-\u001f\u007f]/.test(args.declaredRange) ||
        !Number.isSafeInteger(args.maxRecords) || args.maxRecords < 1 || args.maxRecords > 200 ||
        !Number.isSafeInteger(args.maxContentChars) || args.maxContentChars < 1 || args.maxContentChars > 4096) return 'collect window fields are invalid'
    return ''
  }
  if (method === 'event') {
    if (!args || typeof args !== 'object' || Array.isArray(args)) return 'event args must be an object'
    const id = (value) => typeof value === 'string' && /^[a-f0-9]{32}$/.test(value)
    if (args.action === 'arm') return !ownKeysOnly(args, ['action', 'eventKinds', 'ttlMs'], 'args') &&
      Array.isArray(args.eventKinds) && args.eventKinds.length >= 1 && args.eventKinds.length <= 6 &&
      new Set(args.eventKinds).size === args.eventKinds.length &&
      args.eventKinds.every((kind) => ['navigation_started', 'navigation_finished', 'navigation_failed', 'new_window', 'download', 'javascript_dialog'].includes(kind)) &&
      (args.ttlMs === undefined || (Number.isSafeInteger(args.ttlMs) && args.ttlMs >= 1 && args.ttlMs <= 30000))
      ? '' : 'event arm fields are invalid'
    if (args.action === 'wait') return !ownKeysOnly(args, ['action', 'subscriptionId', 'timeoutMs'], 'args') &&
      id(args.subscriptionId) && (args.timeoutMs === undefined || (Number.isSafeInteger(args.timeoutMs) && args.timeoutMs >= 1 && args.timeoutMs <= 30000))
      ? '' : 'event wait fields are invalid'
    if (args.action === 'cancel') return !ownKeysOnly(args, ['action', 'subscriptionId'], 'args') && id(args.subscriptionId)
      ? '' : 'event cancel fields are invalid'
    if (args.action === 'resolve_dialog') return !ownKeysOnly(args, ['action', 'eventId', 'decision', 'promptText'], 'args') &&
      id(args.eventId) && ['accept', 'dismiss', 'prompt'].includes(args.decision) &&
      (args.decision === 'prompt'
        ? typeof args.promptText === 'string' && Array.from(args.promptText).length <= 4096
        : args.promptText === undefined)
      ? '' : 'event resolve_dialog fields are invalid'
    return 'event action is invalid'
  }
  const extra = ownKeysOnly(args || {}, keys, 'args')
  if (extra) return extra
  if (['open', 'navigate'].includes(method) && (args.thumbnail !== undefined && typeof args.thumbnail !== 'boolean' || method === 'open' && args.thumbnail === true && typeof args.url !== 'string')) return 'thumbnail requires a boolean flag and an open URL'
  if (method === 'open' && args.url != null) {
    if (typeof args.url !== 'string' || args.url.length < 1 || args.url.length > 2048) return 'url must contain 1..2048 characters'
    let url
    try { url = new URL(args.url) } catch { return 'url must be a valid HTTP or HTTPS address' }
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return 'url must be a credential-free HTTP or HTTPS address'
  }
  if (method === 'observe' && args.maxTextChars != null &&
      (!Number.isSafeInteger(args.maxTextChars) || args.maxTextChars < 1 || args.maxTextChars > 4096)) {
    return 'maxTextChars must be between 1 and 4096'
  }
  if (method === 'wait') {
    if (!['load', 'url', 'text', 'selector'].includes(args.condition)) return 'condition must be load, url, text, or selector'
    if (args.timeoutMs != null && (!Number.isSafeInteger(args.timeoutMs) || args.timeoutMs < 1 || args.timeoutMs > 30000)) return 'timeoutMs must be between 1 and 30000'
    if (args.pollIntervalMs != null && (!Number.isSafeInteger(args.pollIntervalMs) || args.pollIntervalMs < 100 || args.pollIntervalMs > 1000)) return 'pollIntervalMs must be between 100 and 1000'
    const field = { url: 'url', text: 'text', selector: 'selector' }[args.condition]
    if (field && (typeof args[field] !== 'string' || args[field].length < 1 || args[field].length > 2048)) return `${field} must contain 1..2048 characters`
    if (field && ['url', 'text', 'selector'].some((key) => key !== field && args[key] != null)) return 'Only the value for the selected condition is accepted'
    if (args.condition === 'url') {
      let url
      try { url = new URL(args.url) } catch { return 'url must be an HTTP or HTTPS address' }
      if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return 'url must be an HTTP or HTTPS address without credentials'
    }
    if (!field && ['url', 'text', 'selector', 'state'].some((key) => args[key] != null)) return 'load accepts only condition, timeoutMs, and pollIntervalMs'
    if (args.state != null && (args.condition !== 'selector' || !['attached', 'visible', 'hidden'].includes(args.state))) return 'state is valid only for selector conditions'
  }
  if (method === 'navigate' && !['back', 'forward', 'reload'].includes(args.action)) return 'action must be back, forward, or reload'
  if (['click', 'navigate'].includes(method)) {
    const hasSubscription = args.eventSubscriptionId !== undefined
    const hasNonce = args.eventNonce !== undefined
    if (hasSubscription !== hasNonce || (hasSubscription &&
        (typeof args.eventSubscriptionId !== 'string' || !/^[a-f0-9]{32}$/.test(args.eventSubscriptionId) ||
         typeof args.eventNonce !== 'string' || !/^[a-f0-9]{64}$/.test(args.eventNonce)))) {
      return 'eventSubscriptionId and eventNonce must be supplied together with valid credentials'
    }
  }
  if (method === 'locate') {
    if (args.partial !== undefined && typeof args.partial !== 'boolean') return 'partial must be a boolean'
    if (!['role', 'label', 'placeholder', 'text', 'css'].includes(args.strategy)) return 'strategy must be role, label, placeholder, text, or css'
    if (typeof args.value !== 'string' || args.value.length < 1 || args.value.length > 2048) return 'value must contain 1..2048 characters'
    if (typeof args.exact !== 'boolean') return 'exact must be a boolean'
    if (args.strategy === 'role') {
      if (args.name !== undefined && (typeof args.name !== 'string' || args.name.length < 1 || args.name.length > 2048)) return 'name must contain 1..2048 characters'
    } else if (args.name !== undefined) return 'name is supported only for role strategy'
    if (args.scopeRef !== undefined && (typeof args.scopeRef !== 'string' ||
        !/^b:[1-9][0-9]{0,15}:[1-9][0-9]{0,15}:[0-9]{1,16}:[1-9][0-9]{0,15}:[a-f0-9]{32}:(?:[0-9]|[1-7][0-9])$/.test(args.scopeRef))) {
      return 'scopeRef must be a current Browser observation reference'
    }
  }
  if (['click', 'type', 'scroll'].includes(method) && args.nodeId != null &&
      (typeof args.nodeId !== 'string' || !NODE_ID_PATTERN.test(args.nodeId))) {
    return 'nodeId must be a non-empty identifier of at most 128 characters'
  }
  if (method === 'click') {
    const nodeClick = typeof args.nodeId === 'string' && args.screenshotId === undefined && args.x === undefined && args.y === undefined
    const visualClick = args.nodeId === undefined && typeof args.screenshotId === 'string' &&
      /^[a-f0-9]{32}$/.test(args.screenshotId) && Number.isFinite(args.x) && Number.isFinite(args.y)
    if (!nodeClick && !visualClick) return 'click requires nodeId or screenshotId with finite bitmap x/y'
  }
  if (method === 'type') {
    if (typeof args.nodeId !== 'string') return 'nodeId is required'
    if (typeof args.text !== 'string') return 'text is required'
    if (Array.from(args.text).length > 4096 || Buffer.byteLength(args.text, 'utf8') > 8192) return 'text exceeds the input limit'
    if (args.replace != null && typeof args.replace !== 'boolean') return 'replace must be a boolean'
  }
  if (method === 'scroll') {
    if (args.nodeId != null && typeof args.nodeId !== 'string') return 'nodeId must be a string'
    if (!['up', 'down'].includes(args.direction)) return 'direction must be up or down'
    if (args.steps != null && (!Number.isSafeInteger(args.steps) || args.steps < 1 || args.steps > 10)) return 'steps must be between 1 and 10'
  }
  if (method === 'selection' && !['latest', 'consume', 'clear', 'list', 'pending', 'claim', 'admit', 'receipt',
    'task_pending', 'task_claim', 'task_admit', 'task_receipt'].includes(args.action)) return 'selection action is invalid'
  return ''
}

function validateFileArgs(args) {
  if (!args || typeof args !== 'object' || Array.isArray(args) || typeof args.action !== 'string') return 'file action is required'
  const only = (...keys) => ownKeysOnly(args, ['action', ...keys], 'args') === ''
  switch (args.action) {
    case 'start': {
      if (!only('url', 'suggestedFileName')) return 'file start has unsupported fields'
      if (typeof args.url !== 'string' || args.url.length < 1 || args.url.length > 2048 || /[\u0000-\u001f\u007f]/.test(args.url)) return 'url must contain 1..2048 characters'
      let url
      try { url = new URL(args.url) } catch { return 'url must be a valid HTTP or HTTPS address' }
      if (!['http:', 'https:'].includes(url.protocol) || !url.hostname || url.username || url.password) return 'url must be a credential-free HTTP or HTTPS address'
      if (args.suggestedFileName !== undefined && (typeof args.suggestedFileName !== 'string' || args.suggestedFileName.length < 1 || args.suggestedFileName.length > 180 || /[\u0000-\u001f\u007f-\u009f/\\:]/.test(args.suggestedFileName))) return 'suggestedFileName must contain 1..180 characters without controls or path separators'
      return ''
    }
    case 'list':
      if (!only('limit', 'beforeId')) return 'file list has unsupported fields'
      if (args.limit !== undefined && (!Number.isSafeInteger(args.limit) || args.limit < 1 || args.limit > 20)) return 'limit must be an integer from 1 to 20'
      if (args.beforeId !== undefined && (typeof args.beforeId !== 'string' || !DOWNLOAD_ID_PATTERN.test(args.beforeId))) return 'beforeId must be a download id'
      return ''
    case 'status': case 'cancel':
      if (!only('id')) return `file ${args.action} has unsupported fields`
      return typeof args.id === 'string' && DOWNLOAD_ID_PATTERN.test(args.id) ? '' : 'id must be a download id'
    default:
      return 'file action must be start, list, status, or cancel'
  }
}

function validateUploadArgs(args) {
  if (!args || typeof args !== 'object' || Array.isArray(args) || typeof args.action !== 'string') return 'upload action is required'
  const only = (...keys) => ownKeysOnly(args, ['action', ...keys], 'args') === ''
  const id = (value) => typeof value === 'string' && /^[a-f0-9]{32}$/.test(value)
  const safePath = (value) => {
    if (typeof value !== 'string' || value.length < 1 || value.length > 1024 || /[\\\u0000-\u001f\u007f]/.test(value) || value.startsWith('/') || /^[A-Za-z]:/.test(value)) return false
    const parts = value.split('/')
    const denied = new Set(['.dsh', '.git', '.ssh', '.config', '.android', '.gradle', '.npm', '.aws', '.kube', '.npmrc', '.pypirc', '.netrc', 'user-data', 'node_modules', 'credentials', 'credential', 'secrets', 'secret', 'id_rsa', 'id_ed25519', 'id_ecdsa', 'authorized_keys'])
    return parts.every((part) => part && part !== '.' && part !== '..' && !denied.has(part.toLowerCase()) && !/^\.env(?:\.|$)/i.test(part) && !/(?:^|[._-])(?:credentials?|secrets?|tokens?)(?:[._-]|$)/i.test(part) && !/\.(?:pem|key|p12|pfx|jks|keystore|mobileprovision|asc)$/i.test(part))
  }
  switch (args.action) {
    case 'request':
      if (!only('paths', 'acceptTypes', 'multiple', 'summary') || !Array.isArray(args.paths) || args.paths.length < 1 || args.paths.length > 10 || !args.paths.every(safePath) || new Set(args.paths).size !== args.paths.length) return 'upload request requires 1..10 unique safe project-relative paths'
      if (args.acceptTypes !== undefined && (!Array.isArray(args.acceptTypes) || args.acceptTypes.length > 20 || !args.acceptTypes.every((value) => typeof value === 'string' && value.length >= 1 && value.length <= 128 && !/[\\\u0000-\u001f\u007f]/.test(value) && (value.startsWith('.') ? /^\.[A-Za-z0-9]{1,16}$/.test(value) : value === '*/*' || /^[A-Za-z0-9!#$&^_.+-]+\/(?:[A-Za-z0-9!#$&^_.+-]+|\*)$/.test(value))))) return 'upload acceptTypes are invalid'
      if (args.multiple !== undefined && typeof args.multiple !== 'boolean') return 'upload multiple must be boolean'
      if (args.summary !== undefined && (typeof args.summary !== 'string' || args.summary.length > 160 || /[\u0000-\u001f\u007f]/.test(args.summary))) return 'upload summary is invalid'
      return ''
    case 'list': case 'status': case 'cancel':
      return only('approvalId') && id(args.approvalId) ? '' : `upload ${args.action} requires a valid approvalId`
    case 'activate':
      return only('approvalId', 'nodeId') && id(args.approvalId) && typeof args.nodeId === 'string' &&
        /^b:[1-9][0-9]{0,15}:[1-9][0-9]{0,15}:[0-9]{1,16}:[1-9][0-9]{0,15}:[a-f0-9]{32}:(?:[0-9]|[1-7][0-9])$/.test(args.nodeId)
        ? '' : 'upload activate requires approvalId and a current observed nodeId'
    case 'submit':
      return only('approvalId', 'fileIds') && id(args.approvalId) && Array.isArray(args.fileIds) && args.fileIds.length >= 1 && args.fileIds.length <= 10 && args.fileIds.every(id) && new Set(args.fileIds).size === args.fileIds.length
        ? '' : 'upload submit requires approvalId and unique fileIds'
    default: return 'upload action must be request, list, status, activate, submit, or cancel'
  }
}

function validateAdvancedArgs(method, args) {
  if (!args || typeof args !== 'object' || Array.isArray(args) || typeof args.action !== 'string') return 'action is required'
  const only = (...keys) => ownKeysOnly(args, ['action', ...keys], 'args') === ''
  const text = (value, max, min = 0) => typeof value === 'string' && value.length >= min && value.length <= max && Buffer.byteLength(value, 'utf8') <= max * 4
  const coord = (value) => typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 10000
  const headers = (value) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length <= 16 &&
    Object.entries(value).every(([key, content]) => /^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}$/.test(key) && typeof content === 'string' && content.length <= 1024 && !/[\r\n]/.test(content))
  const url = (value) => {
    if (!text(value, 2048, 1)) return false
    try { const parsed = new URL(value); return ['http:', 'https:'].includes(parsed.protocol) && !parsed.username && !parsed.password } catch { return false }
  }
  if (method === 'debug') {
    switch (args.action) {
      case 'status': return only() ? '' : 'debug status takes no arguments'
      case 'console': return only('limit') && (args.limit === undefined || (Number.isInteger(args.limit) && args.limit >= 1 && args.limit <= 50)) ? '' : 'console limit is invalid'
      case 'evaluate': return only('expression') && text(args.expression, 8192, 1) ? '' : 'evaluate expression is invalid'
      case 'dom': return only('selector') && (args.selector === undefined || text(args.selector, 512, 1)) ? '' : 'DOM selector is invalid'
      case 'rendering': return validRenderingArgs(args) ? '' : 'rendering fields are invalid'
      case 'ax_click': return only('actionRef') && validAxActionRef(args.actionRef) ? '' : 'AX click reference is invalid'
      case 'ax_type': return only('actionRef', 'text', 'replace') && validAxActionRef(args.actionRef) && typeof args.text === 'string' && args.text.length <= 4096 && (args.replace === undefined || typeof args.replace === 'boolean') ? '' : 'AX type fields are invalid'
      case 'ax_scroll': return only('actionRef', 'direction', 'steps') && validAxActionRef(args.actionRef) && ['up', 'down'].includes(args.direction) && (args.steps === undefined || Number.isInteger(args.steps) && args.steps >= 1 && args.steps <= 10) ? '' : 'AX scroll fields are invalid'
      case 'ax_observe':
      case 'ax_tree': return only('maxNodes', 'maxDepth', 'includeIgnored') &&
        (args.maxNodes === undefined || Number.isInteger(args.maxNodes) && args.maxNodes >= 1 && args.maxNodes <= 256) &&
        (args.maxDepth === undefined || Number.isInteger(args.maxDepth) && args.maxDepth >= 1 && args.maxDepth <= 8) &&
        (args.includeIgnored === undefined || typeof args.includeIgnored === 'boolean') ? '' : 'AX tree bounds are invalid'
      case 'emulate':
        if (!only('width', 'height', 'mobile', 'clear') ||
            (args.clear !== undefined && typeof args.clear !== 'boolean')) return 'emulation fields are invalid'
        if (args.clear === true) return only('clear') ? '' : 'clear cannot be combined with emulation dimensions'
        return Number.isInteger(args.width) && args.width >= 240 && args.width <= 2560 &&
          Number.isInteger(args.height) && args.height >= 240 && args.height <= 2560 &&
          typeof args.mobile === 'boolean' ? '' : 'emulation fields are invalid'
      case 'interact':
        if (args.kind === 'key') return only('kind', 'key') && text(args.key, 32, 1) ? '' : 'key interaction is invalid'
        if (args.kind === 'tap') return args.selector !== undefined
          ? only('kind', 'selector') && text(args.selector, 512, 1) ? '' : 'tap selector is invalid'
          : only('kind', 'x', 'y') && coord(args.x) && coord(args.y) ? '' : 'tap coordinates are invalid'
        if (args.kind === 'drag') return only('kind', 'x', 'y', 'toX', 'toY') && [args.x, args.y, args.toX, args.toY].every(coord) ? '' : 'drag coordinates are invalid'
        if (args.kind === 'select') return only('kind', 'selector', 'value') && text(args.selector, 512, 1) && text(args.value, 4096) ? '' : 'select fields are invalid'
        return 'interaction kind is unsupported'
      default: return 'debug action is unsupported'
    }
  }
  switch (args.action) {
    case 'start': return only('includeBodies', 'includeRequestBodies') &&
      (args.includeBodies === undefined || typeof args.includeBodies === 'boolean') &&
      (args.includeRequestBodies === undefined || typeof args.includeRequestBodies === 'boolean') ? '' : 'capture body opt-ins must be booleans'
    case 'har': case 'export_frames': return only('includeBodies') && (args.includeBodies === undefined || typeof args.includeBodies === 'boolean') ? '' : 'includeBodies is invalid'
    case 'stop': case 'clear_rules': case 'paused': return only() ? '' : 'network action takes no arguments'
    case 'list': return only('cursor', 'limit') && (args.cursor === undefined || (Number.isSafeInteger(args.cursor) && args.cursor >= 0)) && (args.limit === undefined || (Number.isInteger(args.limit) && args.limit >= 1 && args.limit <= 20)) ? '' : 'network list bounds are invalid'
    case 'frames': return only('cursor', 'limit') && (args.cursor === undefined || (Number.isSafeInteger(args.cursor) && args.cursor >= 0)) && (args.limit === undefined || (Number.isInteger(args.limit) && args.limit >= 1 && args.limit <= 20)) ? '' : 'network frames bounds are invalid'
    case 'body': case 'replay_prepare': return only('entryId') && typeof args.entryId === 'string' && /^[A-Za-z0-9_.:-]{1,128}$/.test(args.entryId) ? '' : 'entryId is invalid'
    case 'replay_status': return only('requestId') && typeof args.requestId === 'string' && /^[a-f0-9]{32}$/.test(args.requestId) ? '' : 'replay requestId is invalid'
    case 'replay': {
      const hasTicket = args.riskToken !== undefined || args.captureId !== undefined
      return (hasTicket ? only('entryId', 'riskToken', 'captureId') && typeof args.riskToken === 'string' &&
        /^[a-f0-9]{32}$/.test(args.riskToken) && typeof args.captureId === 'string' && /^cap-[a-f0-9]{20}$/.test(args.captureId)
        : only('entryId')) && typeof args.entryId === 'string' && /^[A-Za-z0-9_.:-]{1,128}$/.test(args.entryId)
        ? '' : 'replay requires an entry id and, for write replay, a confirmed one-use ticket'
    }
    case 'rules': {
      if (!only('rules') || !Array.isArray(args.rules) || args.rules.length > 8) return 'network rules are invalid'
      for (const rule of args.rules) {
        if (!rule || typeof rule !== 'object' || Array.isArray(rule) || !text(rule.urlPattern, 256, 1) || !['request', 'response'].includes(rule.stage) || !['continue', 'block', 'modify', 'fulfill'].includes(rule.action)) return 'network rule is invalid'
        const base = ['urlPattern', 'stage', 'method', 'action']
        const mutations = rule.action === 'continue' || rule.action === 'block' ? [] : rule.action === 'fulfill' ? ['headers', 'body', 'responseCode'] : (rule.stage === 'request' ? ['headers', 'url', 'postData'] : ['headers', 'url', 'body', 'responseCode'])
        if (Object.keys(rule).some((key) => !base.includes(key) && !mutations.includes(key)) || (rule.action === 'fulfill' && rule.stage !== 'response') || (['modify', 'fulfill'].includes(rule.action) && !mutations.some((key) => rule[key] !== undefined)) || (rule.headers !== undefined && !headers(rule.headers)) || (rule.url !== undefined && !url(rule.url)) || (rule.postData !== undefined && !text(rule.postData, 8192)) || (rule.body !== undefined && !text(rule.body, 8192)) || (rule.responseCode !== undefined && (!Number.isInteger(rule.responseCode) || rule.responseCode < 200 || rule.responseCode > 599 || rule.stage !== 'response'))) return 'network rule fields are invalid'
      }
      return ''
    }
    case 'resolve': {
      const fields = ['headers', 'url', 'method', 'postData', 'body', 'responseCode']
      if (!only('pausedId', 'decision', ...fields) || typeof args.pausedId !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(args.pausedId) || !['continue', 'block', 'modify', 'fulfill'].includes(args.decision)) return 'network resolve fields are invalid'
      if (['continue', 'block'].includes(args.decision) && fields.some((key) => args[key] !== undefined)) return 'continue/block cannot include modifications'
      if (args.decision === 'fulfill' && ['url', 'method', 'postData'].some((key) => args[key] !== undefined)) return 'fulfill accepts only headers, body, and responseCode'
      if (['modify', 'fulfill'].includes(args.decision) && !fields.some((key) => args[key] !== undefined)) return 'modification fields are required'
      if (args.headers !== undefined && !headers(args.headers) || args.url !== undefined && !url(args.url) || args.method !== undefined && (typeof args.method !== 'string' || !/^[A-Za-z][A-Za-z0-9_-]{0,15}$/.test(args.method)) || args.postData !== undefined && !text(args.postData, 8192) || args.body !== undefined && !text(args.body, 8192) || args.responseCode !== undefined && (!Number.isInteger(args.responseCode) || args.responseCode < 200 || args.responseCode > 599)) return 'network resolve modification is invalid'
      if (args.decision === 'fulfill' && args.body === undefined && args.responseCode === undefined) return 'fulfill requires body or responseCode'
      return ''
    }
    default: return 'network action is unsupported'
  }
}

function validArtifactReference(artifactPath, method, action) {
  if (typeof artifactPath !== 'string' || artifactPath.includes('..')) return false
  if (method === 'screenshot') return /^\/opt\/browser\/out\/shot\/[A-Za-z0-9_.-]+\.png$/.test(artifactPath)
  if (method === 'network' && ['body', 'har'].includes(action)) return /^\/opt\/browser\/out\/network\/[A-Za-z0-9_.-]{1,160}$/.test(artifactPath)
  if (method === 'network' && action === 'export_frames') return /^\/opt\/browser\/out\/network\/net-[a-f0-9]{24}\.ws\.json$/.test(artifactPath)
  if (method === 'selection' && ['latest', 'consume', 'pending', 'claim'].includes(action)) return /^\/opt\/browser\/out\/selection\/[A-Za-z0-9_.-]+\.png$/.test(artifactPath)
  if (method === 'file' && ['start', 'status', 'cancel', 'list'].includes(action)) return /^\/opt\/browser\/out\/downloads\/[a-f0-9]{32}_[A-Za-z0-9_.-]{1,180}$/.test(artifactPath)
  return false
}

function validDownloadItem(item) {
  const allowed = ['id', 'state', 'fileName', 'bytesReceived', 'totalBytes', 'mimeType', 'artifactPath', 'errorCode', 'updatedAtMillis']
  if (!item || typeof item !== 'object' || Array.isArray(item) || Object.keys(item).some((key) => !allowed.includes(key))) return false
  if (!Object.hasOwn(item, 'id') || !Object.hasOwn(item, 'state') || !Object.hasOwn(item, 'fileName') ||
      !Object.hasOwn(item, 'bytesReceived') || !Object.hasOwn(item, 'updatedAtMillis')) return false
  if (typeof item.id !== 'string' || !DOWNLOAD_ID_PATTERN.test(item.id) || !DOWNLOAD_STATES.has(item.state) ||
      !(item.fileName === null || (typeof item.fileName === 'string' && /^[A-Za-z0-9_.-]{1,180}$/.test(item.fileName) && item.fileName !== '.' && item.fileName !== '..')) ||
      !Number.isSafeInteger(item.bytesReceived) || item.bytesReceived < 0 || item.bytesReceived > MAX_DOWNLOAD_BYTES ||
      !Number.isSafeInteger(item.updatedAtMillis) || item.updatedAtMillis < 0) return false
  if (item.totalBytes !== undefined && item.totalBytes !== null &&
      (!Number.isSafeInteger(item.totalBytes) || item.totalBytes < 0 || item.totalBytes > MAX_DOWNLOAD_BYTES || item.bytesReceived > item.totalBytes)) return false
  if (item.mimeType !== undefined && item.mimeType !== null &&
      (typeof item.mimeType !== 'string' || item.mimeType.length > 127 || !/^[A-Za-z0-9!#$&^_.+-]+\/[A-Za-z0-9!#$&^_.+-]+$/.test(item.mimeType))) return false
  if (item.errorCode !== undefined && item.errorCode !== null &&
      (typeof item.errorCode !== 'string' || !/^[A-Z][A-Z0-9_]{0,63}$/.test(item.errorCode))) return false
  if (item.artifactPath !== undefined && item.artifactPath !== null) {
    if (item.state !== 'COMPLETED' || typeof item.fileName !== 'string' ||
        item.artifactPath !== `/opt/browser/out/downloads/${item.id}_${item.fileName}` ||
        !validArtifactReference(item.artifactPath, 'file', 'status')) return false
  } else if (item.state === 'COMPLETED') return false
  return true
}

function validFileData(action, code, data) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return false
  if (code !== 'OK') {
    if (Object.keys(data).length === 0) return true
    return Object.keys(data).length === 1 && Object.hasOwn(data, 'item') && validDownloadItem(data.item)
  }
  if (action === 'list') {
    return Object.keys(data).sort().join(',') === 'items,nextCursor,truncated' && Array.isArray(data.items) && data.items.length <= 20 &&
      (data.nextCursor === null || (typeof data.nextCursor === 'string' && DOWNLOAD_ID_PATTERN.test(data.nextCursor))) &&
      typeof data.truncated === 'boolean' && data.items.every(validDownloadItem)
  }
  return Object.keys(data).length === 1 && Object.hasOwn(data, 'item') && validDownloadItem(data.item)
}

function validUploadData(action, code, executed, data, args) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return false
  const id = (value) => typeof value === 'string' && /^[a-f0-9]{32}$/.test(value)
  if (action === 'activate') {
    if (code === 'UNKNOWN') return executed === null && Object.keys(data).length === 0
    if (code === 'OK') return executed === true && Object.keys(data).sort().join(',') === 'approvalId,state' &&
      data.approvalId === args.approvalId && data.state === 'chooser_pending'
    if (code === 'UPLOAD_CHOOSER_NOT_OPENED') return executed === true && Object.keys(data).length === 0
    if (code === 'UPLOAD_ACTIVATION_UNRESOLVED') return [true, false].includes(executed) && Object.keys(data).length === 0
    return executed === false && Object.keys(data).length === 0
  }
  if (code !== 'OK') return executed === false && Object.keys(data).length === 0
  if (action === 'request') return executed === true && Object.keys(data).sort().join(',') === 'approvalId,bytesTotal,documentGeneration,expiresInMs,fileCount,pageId,state' &&
    data.state === 'awaiting_approval' && id(data.approvalId) && /^pg-[A-Fa-f0-9]{20}$/.test(data.pageId) && Number.isSafeInteger(data.documentGeneration) && data.documentGeneration >= 0 &&
    Number.isSafeInteger(data.fileCount) && data.fileCount >= 1 && data.fileCount <= 10 && Number.isSafeInteger(data.bytesTotal) && data.bytesTotal >= 0 && data.bytesTotal <= 33554432 &&
    Number.isSafeInteger(data.expiresInMs) && data.expiresInMs >= 1 && data.expiresInMs <= 120000
  if (action === 'submit') return executed === true && Object.keys(data).sort().join(',') === 'approvalId,fileCount,state,websiteUploadVerified' &&
    data.approvalId === args.approvalId && data.state === 'chooser_delivered' && data.fileCount === args.fileIds.length && data.websiteUploadVerified === false
  if (action === 'cancel') return executed === true && Object.keys(data).sort().join(',') === 'approvalId,state' && data.approvalId === args.approvalId && data.state === 'cancelled'
  if (action === 'list' || action === 'status') {
    const hasFiles = Object.hasOwn(data, 'files')
    const expected = action === 'list' && hasFiles ? 'approvalId,expiresInMs,files,state' : 'approvalId,expiresInMs,state'
    if (Object.keys(data).sort().join(',') !== expected || data.approvalId !== args.approvalId || !['pending', 'ready', 'chooser_pending', 'chooser_delivered', 'cancelled', 'denied', 'expired', 'failed'].includes(data.state) ||
        !Number.isSafeInteger(data.expiresInMs) || data.expiresInMs < 0 || data.expiresInMs > 120000) return false
    if (data.state === 'pending' && (executed !== false || hasFiles)) return false
    if (hasFiles) {
      if (executed !== false || !['ready', 'chooser_pending'].includes(data.state) || !Array.isArray(data.files) || data.files.length < 1 || data.files.length > 10) return false
      let total = 0
      for (const file of data.files) {
        if (!file || typeof file !== 'object' || Array.isArray(file) || Object.keys(file).sort().join(',') !== 'fileId,mimeType,name,size' ||
            !id(file.fileId) || typeof file.name !== 'string' || file.name.length < 1 || file.name.length > 255 || /[/\\\u0000-\u001f\u007f]/.test(file.name) ||
            typeof file.mimeType !== 'string' || file.mimeType.length > 127 || !/^[A-Za-z0-9!#$&^_.+-]+\/[A-Za-z0-9!#$&^_.+-]+$/.test(file.mimeType) ||
            !Number.isSafeInteger(file.size) || file.size < 0 || file.size > 16777216) return false
        total += file.size
      }
      if (total > 33554432 || new Set(data.files.map((file) => file.fileId)).size !== data.files.length) return false
    }
    return executed === false && !(action === 'status' && hasFiles)
  }
  return false
}

function validDevToolsCapabilityCoverage(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data) ||
      ['targetId', 'socketPath', 'socketUrl', 'webSocketDebuggerUrl', 'debuggerUrl'].some((key) => Object.hasOwn(data, key))) return false
  const hasCapabilities = Object.hasOwn(data, 'capabilities')
  const hasCoverage = Object.hasOwn(data, 'coverage')
  if (hasCapabilities !== hasCoverage) return false
  if (!hasCapabilities) return true
  const methods = ['Target.setAutoAttach', 'Network.enable', 'Network.getResponseBody', 'Network.getRequestPostData',
    'Network.streamResourceContent', 'Fetch.enable', 'Runtime.evaluate', 'Network.webSocketEvents', 'page.fetchReplay']
  if (!exactObjectKeys(data.capabilities, methods) || Object.values(data.capabilities).some((state) => !['unknown', 'supported', 'unsupported'].includes(state))) return false
  const coverage = data.coverage
  return exactObjectKeys(coverage, ['mainPage', 'workerTargets', 'serviceWorkerTargets', 'otherTargets']) &&
    ['connected', 'disconnected'].includes(coverage.mainPage) &&
    ['unsupported', 'waiting_for_attached_target', 'attached_network_enabled', 'detached'].includes(coverage.workerTargets) &&
    coverage.serviceWorkerTargets === 'unsupported_shared_target_unverified' && coverage.otherTargets === 'excluded'
}

function validNetworkFrameExport(data) {
  return exactObjectKeys(data, ['artifactPath', 'mimeType', 'bytes', 'captureId', 'frameCount', 'payloadsIncluded',
    'payloadUnredacted', 'headersRedacted', 'redacted', 'truncated']) &&
    validArtifactReference(data.artifactPath, 'network', 'export_frames') && data.mimeType === 'application/json' &&
    Number.isSafeInteger(data.bytes) && data.bytes > 0 && data.bytes <= 2 * 1024 * 1024 &&
    typeof data.captureId === 'string' && /^cap-[a-f0-9]{20}$/.test(data.captureId) &&
    Number.isSafeInteger(data.frameCount) && data.frameCount >= 0 && data.frameCount <= 1000 &&
    typeof data.payloadsIncluded === 'boolean' && data.payloadUnredacted === data.payloadsIncluded &&
    typeof data.headersRedacted === 'boolean' && data.redacted === !data.payloadsIncluded && typeof data.truncated === 'boolean'
}

function validReplayPreview(preview) {
  return exactObjectKeys(preview, ['captureId', 'entryId', 'method', 'origin', 'path', 'queryOmitted', 'querySha256',
    'requestBodyBytes', 'requestBodySha256', 'contentType', 'credentials', 'sensitiveHeadersCopied', 'possibleDuplicateSideEffects']) &&
    typeof preview.captureId === 'string' && /^cap-[a-f0-9]{20}$/.test(preview.captureId) &&
    typeof preview.entryId === 'string' && /^[A-Za-z0-9_.:-]{1,128}$/.test(preview.entryId) &&
    ['POST', 'PUT', 'PATCH', 'DELETE'].includes(preview.method) && typeof preview.origin === 'string' && preview.origin.length <= 512 &&
    typeof preview.path === 'string' && preview.path.startsWith('/') && preview.path.length <= 1024 && !/[?#]/.test(preview.path) &&
    typeof preview.queryOmitted === 'boolean' && (preview.querySha256 === null || /^[a-f0-9]{64}$/.test(preview.querySha256)) &&
    Number.isSafeInteger(preview.requestBodyBytes) && preview.requestBodyBytes >= 0 && preview.requestBodyBytes <= 8192 &&
    typeof preview.requestBodySha256 === 'string' && /^[a-f0-9]{64}$/.test(preview.requestBodySha256) &&
    typeof preview.contentType === 'string' && preview.contentType.length <= 256 && preview.credentials === 'same-origin' &&
    preview.sensitiveHeadersCopied === false && preview.possibleDuplicateSideEffects === true && (() => {
      try {
        const url = new URL(preview.path, preview.origin)
        return url.origin === preview.origin && url.pathname === preview.path && !url.search && !url.hash &&
          (preview.queryOmitted ? preview.querySha256 !== null : preview.querySha256 === null)
      } catch { return false }
    })()
}

function validReplayPrepare(data) {
  return exactObjectKeys(data, ['requestId', 'status', 'expiresInMillis', 'preview']) &&
    typeof data.requestId === 'string' && /^[a-f0-9]{32}$/.test(data.requestId) && data.status === 'awaiting_user' &&
    Number.isSafeInteger(data.expiresInMillis) && data.expiresInMillis >= 1 && data.expiresInMillis <= 120000 && validReplayPreview(data.preview)
}

function validReplayStatus(data, response, args) {
  if (!data || typeof data !== 'object' || Array.isArray(data) || data.requestId !== args.requestId ||
      !/^[a-f0-9]{32}$/.test(data.requestId) || !Number.isSafeInteger(data.expiresInMillis) || data.expiresInMillis < 0 || data.expiresInMillis > 120000) return false
  if (data.status === 'awaiting_user') return exactObjectKeys(data, ['requestId', 'status', 'expiresInMillis'])
  if (data.status === 'denied') return exactObjectKeys(data, ['requestId', 'status', 'expiresInMillis'])
  return data.status === 'approved' && exactObjectKeys(data, ['requestId', 'status', 'riskToken', 'entryId', 'captureId',
    'sessionGeneration', 'controlGeneration', 'expiresInMillis']) && /^[a-f0-9]{32}$/.test(data.riskToken) &&
    typeof data.entryId === 'string' && /^[A-Za-z0-9_.:-]{1,128}$/.test(data.entryId) &&
    typeof data.captureId === 'string' && /^cap-[a-f0-9]{20}$/.test(data.captureId) &&
    Number.isSafeInteger(response.sessionGeneration) && data.sessionGeneration === response.sessionGeneration &&
    Number.isSafeInteger(response.controlGeneration) && data.controlGeneration === response.controlGeneration
}

function validNetworkReplayResult(data) {
  return exactObjectKeys(data, ['event', 'method', 'status', 'requestBodyBytes', 'requestBodySha256', 'resourceType', 'redirected']) &&
    data.event === 'replayCompleted' && ['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE'].includes(data.method) &&
    Number.isInteger(data.status) && data.status >= 0 && data.status <= 599 &&
    Number.isSafeInteger(data.requestBodyBytes) && data.requestBodyBytes >= 0 && data.requestBodyBytes <= 8192 &&
    typeof data.requestBodySha256 === 'string' && (data.requestBodySha256 === '' || /^[a-f0-9]{64}$/.test(data.requestBodySha256)) &&
    data.resourceType === 'XHR' && typeof data.redirected === 'boolean'
}

function validNetworkFramesData(data) {
  const keys = ['captureId', 'frames', 'cursor', 'nextCursor', 'total', 'droppedFrames', 'eventDrops', 'recording', 'includeBodies', 'payloadUnredacted', 'headersRedacted', 'redacted', 'payloadBytes', 'maxFramePayloadBytes', 'maxCapturePayloadBytes', 'maxFrames', 'maxPageBytes', 'loss', 'truncated']
  if (!data || typeof data !== 'object' || Array.isArray(data) || Object.keys(data).sort().join(',') !== keys.slice().sort().join(',') ||
      !(data.captureId === null || (typeof data.captureId === 'string' && /^cap-[a-f0-9]{20}$/.test(data.captureId))) ||
      !Array.isArray(data.frames) || data.frames.length > 20 || !Number.isSafeInteger(data.cursor) || data.cursor < 0 ||
      !(data.nextCursor === null || (Number.isSafeInteger(data.nextCursor) && data.nextCursor >= 0 && data.nextCursor <= 1000)) ||
      !Number.isSafeInteger(data.total) || data.total < 0 || data.total > 1000 || data.cursor > data.total ||
      !Number.isSafeInteger(data.droppedFrames) || data.droppedFrames < 0 || !Number.isSafeInteger(data.eventDrops) || data.eventDrops < 0 ||
      typeof data.recording !== 'boolean' || typeof data.includeBodies !== 'boolean' || data.payloadUnredacted !== data.includeBodies ||
      typeof data.headersRedacted !== 'boolean' || data.redacted !== !data.includeBodies ||
      !Number.isSafeInteger(data.payloadBytes) || data.payloadBytes < 0 || data.payloadBytes > 1048576 ||
      data.maxFramePayloadBytes !== 8192 || data.maxCapturePayloadBytes !== 1048576 || data.maxFrames !== 1000 || data.maxPageBytes !== 14336 ||
      typeof data.loss !== 'boolean' || data.truncated !== data.loss || ((data.nextCursor === null) !== (data.cursor + data.frames.length >= data.total)) ||
      data.frames.some((frame) => !validNetworkFrame(frame, data.includeBodies)) || Buffer.byteLength(JSON.stringify(data), 'utf8') > data.maxPageBytes) return false
  if ((data.droppedFrames > 0 || data.eventDrops > 0 || data.frames.some((frame) => frame.metadataIncomplete || frame.frameTruncated || frame.urlTruncated || frame.detailTruncated)) && !data.loss) return false
  return true
}

function validNetworkFrame(frame, includeBodies) {
  if (!frame || typeof frame !== 'object' || Array.isArray(frame) || !Number.isSafeInteger(frame.sequence) || frame.sequence < 1 ||
      typeof frame.webSocketId !== 'string' || !/^ws-[a-f0-9]{20}$/.test(frame.webSocketId) ||
      !['created', 'handshakeRequest', 'handshakeResponse', 'sent', 'received', 'error', 'closed'].includes(frame.kind)) return false
  const common = ['sequence', 'webSocketId', 'kind', 'timestamp', 'metadataIncomplete']
  if (frame.timestamp !== undefined && (typeof frame.timestamp !== 'number' || !Number.isFinite(frame.timestamp) || frame.timestamp < 0)) return false
  if (frame.kind !== 'created' && frame.timestamp === undefined && frame.metadataIncomplete !== true) return false
  if (frame.metadataIncomplete !== undefined && frame.metadataIncomplete !== true) return false
  let fields = common
  if (frame.kind === 'created') {
    fields = [...common, 'url', 'urlTruncated']
    if (frame.url !== undefined) {
      try { const url = new URL(frame.url); if (typeof frame.url !== 'string' || !['ws:', 'wss:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || frame.url.length > 2048) return false } catch { return false }
    } else if (frame.metadataIncomplete !== true) return false
    if (frame.urlTruncated !== undefined && frame.urlTruncated !== true) return false
  } else if (frame.kind === 'handshakeRequest') {
    fields = [...common, 'requestHeaders', 'wallTime', 'requestHeadersRedacted', 'requestHeadersTruncated']
    if (frame.wallTime !== undefined && (typeof frame.wallTime !== 'number' || !Number.isFinite(frame.wallTime) || frame.wallTime < 0)) return false
    if ((frame.requestHeaders === undefined || frame.wallTime === undefined) && frame.metadataIncomplete !== true) return false
    if (frame.requestHeaders !== undefined && !validWsHeaders(frame.requestHeaders)) return false
    if (frame.requestHeadersRedacted !== undefined && frame.requestHeadersRedacted !== true || frame.requestHeadersTruncated !== undefined && frame.requestHeadersTruncated !== true) return false
  } else if (frame.kind === 'handshakeResponse') {
    fields = [...common, 'status', 'statusText', 'responseHeaders', 'responseHeadersRedacted', 'responseHeadersTruncated', 'statusTextTruncated']
    if (frame.status !== undefined && (!Number.isInteger(frame.status) || (frame.status !== -1 && (frame.status < 100 || frame.status > 599)))) return false
    if (frame.statusText !== undefined && (typeof frame.statusText !== 'string' || frame.statusText.length > 128)) return false
    if ((frame.status === undefined || frame.status === -1 || frame.statusText === undefined || frame.responseHeaders === undefined) && frame.metadataIncomplete !== true) return false
    if (frame.responseHeaders !== undefined && !validWsHeaders(frame.responseHeaders)) return false
    if (frame.responseHeadersRedacted !== undefined && frame.responseHeadersRedacted !== true || frame.responseHeadersTruncated !== undefined && frame.responseHeadersTruncated !== true ||
        frame.statusTextTruncated !== undefined && frame.statusTextTruncated !== true) return false
  } else if (frame.kind === 'sent' || frame.kind === 'received') {
    fields = [...common, 'opcode', 'direction', 'masked', 'payloadEncoding', 'payloadBytes', 'payloadOmitted', 'payloadUnavailable', 'payload', 'frameTruncated']
    if (frame.metadataIncomplete !== true && ['opcode', 'direction', 'masked', 'payloadEncoding', 'payloadBytes'].some(key => frame[key] === undefined)) return false
    if (frame.opcode !== undefined && (!Number.isInteger(frame.opcode) || frame.opcode < 0 || frame.opcode > 15) ||
        frame.direction !== undefined && frame.direction !== (frame.kind === 'sent' ? 'outgoing' : 'incoming') ||
        frame.masked !== undefined && typeof frame.masked !== 'boolean' ||
        frame.payloadEncoding !== undefined && !['text', 'base64'].includes(frame.payloadEncoding) ||
        frame.opcode !== undefined && frame.payloadEncoding !== undefined && (frame.opcode === 1) !== (frame.payloadEncoding === 'text') ||
        frame.payloadBytes !== undefined && (!Number.isSafeInteger(frame.payloadBytes) || frame.payloadBytes < 0 || frame.payloadBytes > 2097152)) return false
    if (frame.payloadOmitted !== undefined && frame.payloadOmitted !== true || frame.payloadUnavailable !== undefined && frame.payloadUnavailable !== true || frame.frameTruncated !== undefined && frame.frameTruncated !== true) return false
    if (frame.payload !== undefined) {
      if (typeof frame.payload !== 'string' || !includeBodies || frame.payloadOmitted || frame.payloadUnavailable || frame.opcode === undefined || frame.payloadEncoding === undefined || frame.payloadBytes === undefined) return false
      if (frame.payloadEncoding === 'text' && Buffer.byteLength(frame.payload, 'utf8') > 8192) return false
      if (frame.payloadEncoding === 'base64' && (!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(frame.payload) || Buffer.from(frame.payload, 'base64').length > 8192)) return false
    } else if (includeBodies && !frame.payloadUnavailable && !frame.frameTruncated && frame.payloadBytes !== 0 && frame.metadataIncomplete !== true) return false
    if (!includeBodies && (frame.payload !== undefined || frame.payloadOmitted !== true && frame.metadataIncomplete !== true) || includeBodies && frame.payloadOmitted === true) return false
    if (frame.payloadUnavailable === true && frame.metadataIncomplete !== true) return false
  } else if (frame.kind === 'error') {
    fields = [...common, 'detail', 'detailRedacted', 'detailTruncated']
    if (frame.detail !== undefined && (typeof frame.detail !== 'string' || frame.detail.length > 256)) return false
    if (frame.detail === undefined && frame.metadataIncomplete !== true) return false
    if (frame.detailRedacted !== undefined && frame.detailRedacted !== true || frame.detailTruncated !== undefined && frame.detailTruncated !== true) return false
  }
  return Object.keys(frame).every((key) => fields.includes(key))
}

function validWsHeaders(headers) {
  const sensitive = /(?:token|secret|password|csrf|auth|api[-_]?key|cookie)/i
  return headers && typeof headers === 'object' && !Array.isArray(headers) && Object.keys(headers).length <= 16 &&
    Object.entries(headers).every(([key, value]) => key.length <= 64 && !sensitive.test(key) && typeof value === 'string' && value.length <= 256 && !/[\r\n]/.test(value))
}

function validPageReference(value) {
  return value === null || (typeof value === 'string' && /^pg-[A-Fa-f0-9]{20}$/.test(value))
}

const SCOPE_COVERAGE_KEYS = ['complete', 'scannedElements', 'frameCount', 'shadowRootCount', 'inaccessibleFrameCount',
  'frameLimitReached', 'shadowLimitReached', 'elementLimitReached', 'textNodesVisited', 'textLimitReached']
const FRAME_SCOPE_KEYS = ['ordinal', 'parentOrdinal', 'depth', 'status', 'reason']

function exactObjectKeys(value, keys) {
  return value !== null && typeof value === 'object' && !Array.isArray(value) &&
    Object.keys(value).length === keys.length && keys.every((key) => Object.prototype.hasOwnProperty.call(value, key))
}

function validScopeCoverage(value) {
  if (!exactObjectKeys(value, SCOPE_COVERAGE_KEYS) || typeof value.complete !== 'boolean' ||
      !Number.isSafeInteger(value.scannedElements) || value.scannedElements < 0 || value.scannedElements > 5000 ||
      !Number.isSafeInteger(value.frameCount) || value.frameCount < 0 || value.frameCount > 16 ||
      !Number.isSafeInteger(value.shadowRootCount) || value.shadowRootCount < 0 || value.shadowRootCount > 32 ||
      !Number.isSafeInteger(value.inaccessibleFrameCount) || value.inaccessibleFrameCount < 0 || value.inaccessibleFrameCount > 16 ||
      !Number.isSafeInteger(value.textNodesVisited) || value.textNodesVisited < 0 || value.textNodesVisited > 20000 ||
      !['frameLimitReached', 'shadowLimitReached', 'elementLimitReached', 'textLimitReached']
        .every((key) => typeof value[key] === 'boolean')) return false
  const complete = value.inaccessibleFrameCount === 0 && !value.frameLimitReached &&
    !value.shadowLimitReached && !value.elementLimitReached
  return value.complete === complete
}

function validFrameScopes(value, coverage) {
  if (!Array.isArray(value) || value.length !== coverage.frameCount || value.length > 16) return false
  const byOrdinal = new Map()
  let inaccessible = 0
  for (let index = 0; index < value.length; index++) {
    const scope = value[index]
    if (!exactObjectKeys(scope, FRAME_SCOPE_KEYS) || scope.ordinal !== index + 1 ||
        !Number.isSafeInteger(scope.parentOrdinal) || scope.parentOrdinal < 0 || scope.parentOrdinal >= scope.ordinal ||
        !Number.isSafeInteger(scope.depth) || scope.depth < 1 || scope.depth > 4 ||
        !['accessible', 'inaccessible'].includes(scope.status)) return false
    if (scope.status === 'accessible') {
      if (scope.reason !== null) return false
    } else {
      if (!['CROSS_ORIGIN_OR_OPAQUE', 'FRAME_LIMIT'].includes(scope.reason)) return false
      inaccessible++
      if (scope.reason === 'FRAME_LIMIT' && !coverage.frameLimitReached) return false
    }
    if (scope.parentOrdinal === 0) {
      if (scope.depth !== 1) return false
    } else {
      const parent = byOrdinal.get(scope.parentOrdinal)
      if (!parent || parent.status !== 'accessible' || parent.depth + 1 !== scope.depth) return false
    }
    byOrdinal.set(scope.ordinal, scope)
  }
  return inaccessible === coverage.inaccessibleFrameCount
}

function hasAnyKey(value, keys) {
  return keys.some((key) => Object.prototype.hasOwnProperty.call(value, key))
}

function validLocateScopeMetadata(data, includeTarget) {
  const keys = includeTarget ? ['frameOrdinal', 'shadowDepth', 'scopeCoverage', 'frameScopes'] : ['scopeCoverage', 'frameScopes']
  const any = hasAnyKey(data, ['frameOrdinal', 'shadowDepth', 'scopeCoverage', 'frameScopes'])
  if (!any) return true
  if (!keys.every((key) => Object.prototype.hasOwnProperty.call(data, key)) ||
      !validScopeCoverage(data.scopeCoverage) || !validFrameScopes(data.frameScopes, data.scopeCoverage)) return false
  if (!includeTarget) return true
  if (!Number.isSafeInteger(data.frameOrdinal) || data.frameOrdinal < 0 || data.frameOrdinal > 16 ||
      !Number.isSafeInteger(data.shadowDepth) || data.shadowDepth < 0 || data.shadowDepth > 32) return false
  return !data.scopeCoverage.textLimitReached && (data.frameOrdinal === 0 || data.frameScopes.some((scope) =>
    scope.ordinal === data.frameOrdinal && scope.status === 'accessible'))
}

const NAVIGATION_METADATA_KEYS = ['urlBefore', 'urlBeforeQueryRedacted', 'urlBeforeFragmentRedacted',
  'requestedUrl', 'requestedUrlQueryRedacted', 'requestedUrlFragmentRedacted',
  'urlAfter', 'urlAfterQueryRedacted', 'urlAfterFragmentRedacted', 'committed'];

function validNavigationThumbnail(response, args = {}) {
  const data = response.data;
  const thumbnail = data.thumbnail;
  const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
  if (thumbnail === undefined) return !(args.thumbnail === true && response.code === 'OK' && response.executed === true);
  if (args.thumbnail !== true || response.code !== 'OK' || response.executed !== true || !object(thumbnail) ||
      typeof thumbnail.code !== 'string' || !/^[A-Z][A-Z0-9_]{0,63}$/.test(thumbnail.code) || typeof thumbnail.captured !== 'boolean') return false;
  if (!thumbnail.captured) return thumbnail.code !== 'OK' && Object.keys(thumbnail).sort().join(',') === 'captured,code';
  return Object.keys(thumbnail).sort().join(',') === 'artifactPath,bytes,captured,code,documentGeneration,format,height,navigationId,pageId,width' &&
    thumbnail.code === 'OK' && thumbnail.format === 'png' && typeof thumbnail.artifactPath === 'string' &&
    /^\/opt\/browser\/out\/shot\/[A-Za-z0-9_.-]{1,160}\.png$/.test(thumbnail.artifactPath) && !thumbnail.artifactPath.includes('..') &&
    Number.isSafeInteger(thumbnail.width) && thumbnail.width >= 1 && thumbnail.width <= 384 &&
    Number.isSafeInteger(thumbnail.height) && thumbnail.height >= 1 && thumbnail.height <= 384 &&
    Number.isSafeInteger(thumbnail.bytes) && thumbnail.bytes >= 1 && thumbnail.bytes <= 819200 &&
    typeof thumbnail.pageId === 'string' && /^pg-[a-f0-9]{20}$/.test(thumbnail.pageId) &&
    Number.isSafeInteger(thumbnail.documentGeneration) && thumbnail.documentGeneration >= 0 &&
    Number.isSafeInteger(thumbnail.navigationId) && thumbnail.navigationId >= 0 && data.navigationId === thumbnail.navigationId &&
    (data.pageId === undefined || data.pageId === thumbnail.pageId);
}
function validWaitScope(data, condition) {
  if (data.waitScope === undefined) return true;
  const scope = data.waitScope;
  const dom = condition === 'text' || condition === 'selector';
  return scope !== null && typeof scope === 'object' && !Array.isArray(scope) &&
    Object.keys(scope).sort().join(',') === 'conditionCoverage,frameTraversal,kind,topFrameOnly,wholePageCoverage' &&
    scope.kind === (dom ? 'top_frame' : 'page_navigation') && scope.topFrameOnly === true &&
    scope.frameTraversal === 'not_attempted' && scope.conditionCoverage === 'declared_scope' &&
    scope.wholePageCoverage === (dom ? 'partial' : 'not_assessed');
}
function validBoundUnknownDiagnostics(response, method, action, args) {
  const data = response.data;
  const exact = fields => Object.keys(data).sort().join(',') === [...new Set(fields)].sort().join(',');
  if (response.executed !== null || Object.keys(response).some(key =>
      !['version', 'requestId', 'code', 'executed', 'sessionGeneration', 'controlGeneration', 'state', 'owner', 'data'].includes(key))) return false;
  if (method === 'task') return ['begin', 'complete', 'reconcile', 'resume'].includes(action) &&
    exact(['taskOutcomeUnconfirmed']) && data.taskOutcomeUnconfirmed === true;
  if (method === 'click') {
    const media = data.targetKind === 'NATIVE_MEDIA';
    const fields = ['screenshotId', 'bitmapX', 'bitmapY', 'cssX', 'cssY', 'targetKind',
      ...(media ? ['pixelMatchBeforeDispatch', 'atomicSnapshot'] : [])];
    return exact(fields) && typeof args.screenshotId === 'string' && /^[a-f0-9]{32}$/.test(args.screenshotId) &&
      data.screenshotId === args.screenshotId && Number.isFinite(data.bitmapX) && data.bitmapX >= 0 &&
      Number.isFinite(data.bitmapY) && data.bitmapY >= 0 && data.bitmapX === args.x && data.bitmapY === args.y &&
      Number.isFinite(data.cssX) && data.cssX >= 0 && Number.isFinite(data.cssY) && data.cssY >= 0 &&
      (media ? data.pixelMatchBeforeDispatch === true && data.atomicSnapshot === false : data.targetKind === 'DOM_HTML');
  }
  if (method === 'open') {
    if (data.event === 'opened') return exact(['event', 'selectionSequence']) &&
      Number.isSafeInteger(data.selectionSequence) && data.selectionSequence >= 0;
    const fields = ['event', 'selectionSequence', 'navigationId', 'loadState', 'requestedUrl',
      'requestedUrlQueryRedacted', 'requestedUrlFragmentRedacted', ...(data.committed === undefined ? [] : NAVIGATION_METADATA_KEYS)];
    return data.event === 'navigation_accepted' && exact(fields) && validNavigationMetadata(data) &&
      Number.isSafeInteger(data.selectionSequence) && data.selectionSequence >= 0 &&
      (data.navigationId === null || Number.isSafeInteger(data.navigationId) && data.navigationId >= 0) &&
      ['accepted', 'loading', 'finished', 'failed'].includes(data.loadState) && validSanitizedUrl(data.requestedUrl) &&
      typeof data.requestedUrlQueryRedacted === 'boolean' && typeof data.requestedUrlFragmentRedacted === 'boolean';
  }
  if (method !== 'navigate' || !['back', 'forward', 'reload'].includes(action) ||
      data.action !== action || data.accepted !== true || !validNavigationMetadata(data)) return false;
  const metadata = data.committed === undefined ? [] : NAVIGATION_METADATA_KEYS;
  const fallback = data.sameDocumentFallback === undefined ? [] : ['sameDocumentFallback'];
  if (fallback.length && (action !== 'back' || data.sameDocumentFallback !== true)) return false;
  if (exact(['action', 'accepted'])) return true;
  if (fallback.length && exact(['action', 'accepted', 'sameDocumentFallback', 'url'])) return validSanitizedUrl(data.url);
  return exact(['action', 'accepted', 'pageId', 'navigationId', 'url', ...metadata, ...fallback]) &&
    validPageReference(data.pageId) && (data.navigationId === null || Number.isSafeInteger(data.navigationId) && data.navigationId >= 0) &&
    validSanitizedUrl(data.url);
}
function validConditionWaitUnknown(response, condition) {
  const data = response.data;
  const required = ['condition', 'matched', 'pageId', 'documentGeneration', 'navigationId', 'url', 'loadState', 'waitScope'];
  const allowed = new Set([...required, ...NAVIGATION_METADATA_KEYS]);
  return response.executed === null && required.every(key => Object.hasOwn(data, key)) &&
    Object.keys(data).every(key => allowed.has(key)) && ['selector', 'text', 'url', 'load'].includes(condition) &&
    data.condition === condition && data.matched === false && validPageReference(data.pageId) &&
    (data.documentGeneration === null || Number.isSafeInteger(data.documentGeneration) && data.documentGeneration >= 0) &&
    (data.navigationId === null || Number.isSafeInteger(data.navigationId) && data.navigationId >= 0) &&
    validSanitizedUrl(data.url) && ['ACCEPTED', 'LOADING', 'FINISHED', 'FAILED', 'unknown'].includes(data.loadState) &&
    validWaitScope(data, condition) && validNavigationMetadata(data);
}
function validConditionWaitCancellation(response) {
  const data = response.data;
  const keys = ['stage', 'cancellationReason', 'nextAction', 'automaticRetryAllowed'];
  if (!keys.some(key => Object.hasOwn(data, key))) return true;
  return keys.every(key => Object.hasOwn(data, key)) && response.executed === false &&
    data.matched === false && ['CANCELLED', 'SESSION_REVOKED'].includes(response.code) &&
    data.stage === 'condition_wait' && data.nextAction === 'read_status_and_observe' &&
    data.automaticRetryAllowed === false &&
    data.cancellationReason === (response.code === 'CANCELLED' ? 'user_cancelled' : 'context_changed');
}

const CANCEL_RECEIPT_STAGES = new Set(['queued', 'transport', 'capture_wait', 'event_wait', 'condition_wait', 'dispatch'])
const CANCEL_RECEIPT_REASONS = new Set([
  'cancel_accepted', 'cancel_before_admission', 'request_in_flight', 'request_not_found',
  'request_finished', 'transport_unavailable', 'invalid_runtime_receipt',
])

function validCancelReceipt(value, requestId) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || value.version !== 1 ||
      value.requestId !== requestId || typeof value.code !== 'string' ||
      !/^[A-Z][A-Z0-9_]{0,63}$/.test(value.code) || value.executed !== null ||
      !value.data || typeof value.data !== 'object' || Array.isArray(value.data)) return false
  const publicKeys = ['version', 'requestId', 'code', 'executed', 'sessionGeneration',
    'controlGeneration', 'state', 'owner', 'data']
  if (Object.keys(value).some(key => !publicKeys.includes(key)) ||
      !(value.state === null && value.code === 'UNKNOWN') && !['CLOSED', 'IDLE', 'RUNNING'].includes(value.state)) return false
  for (const generation of [value.sessionGeneration, value.controlGeneration]) {
    if (generation !== null && (!Number.isSafeInteger(generation) || generation < 0)) return false
  }
  const data = value.data
  const fields = ['receiptKind', 'cancelStage', 'reason', 'actionOutcome', 'nextAction', 'automaticRetryAllowed']
  return Object.keys(data).length === fields.length && fields.every(key => Object.hasOwn(data, key)) &&
    data.receiptKind === 'cancel_request' && CANCEL_RECEIPT_STAGES.has(data.cancelStage) &&
    CANCEL_RECEIPT_REASONS.has(data.reason) && data.actionOutcome === 'unknown' &&
    data.nextAction === 'read_original_request_result' && data.automaticRetryAllowed === false
}

function legacyCancelReceipt(value, requestId) {
  return value && typeof value === 'object' && !Array.isArray(value) && value.version === 1 &&
    value.requestId === requestId && ['CANCELLED', 'TOO_LATE', 'NOT_FOUND', 'CLOSED'].includes(value.code) &&
    value.executed === false && value.data && typeof value.data === 'object' &&
    !Array.isArray(value.data) && Object.keys(value.data).length === 0
}

function cancellationUnknownData(value) {
  const receipt = value && value.data && typeof value.data === 'object' ? value.data : {}
  return {
    cancelStage: CANCEL_RECEIPT_STAGES.has(receipt.cancelStage) ? receipt.cancelStage : 'transport',
    reason: receipt.reason === 'invalid_runtime_receipt' ? 'invalid_runtime_receipt' : 'transport_unavailable',
    actionOutcome: 'unknown',
    automaticRetryAllowed: false,
  }
}

function validCancellationUnknownData(data) {
  const fields = ['cancelStage', 'reason', 'actionOutcome', 'automaticRetryAllowed']
  return data && typeof data === 'object' && !Array.isArray(data) &&
    Object.keys(data).length === fields.length && fields.every(key => Object.hasOwn(data, key)) &&
    CANCEL_RECEIPT_STAGES.has(data.cancelStage) &&
    ['transport_unavailable', 'invalid_runtime_receipt'].includes(data.reason) &&
    data.actionOutcome === 'unknown' && data.automaticRetryAllowed === false
}

function validTransportUnknownData(data, requestId) {
  const base = ['cancelStage', 'reason', 'actionOutcome', 'automaticRetryAllowed']
  const fields = [...base, 'requestId', 'stage', 'dispatched', 'originalFailure']
  return data && typeof data === 'object' && !Array.isArray(data) &&
    Object.keys(data).length === fields.length && fields.every(key => Object.hasOwn(data, key)) &&
    validCancellationUnknownData(Object.fromEntries(base.map(key => [key, data[key]]))) &&
    data.cancelStage === 'transport' && data.requestId === requestId && data.stage === 'transport' &&
    data.dispatched === 'unknown' &&
    ['invalid_response', 'timeout', 'signal', 'buffer_limit', 'transport'].includes(data.originalFailure)
}

function validPreDispatchTerminalData(data) {
  const fields = ['cancelStage', 'reason', 'actionOutcome', 'automaticRetryAllowed']
  return data && typeof data === 'object' && !Array.isArray(data) &&
    Object.keys(data).length === fields.length && fields.every(key => Object.hasOwn(data, key)) &&
    data.cancelStage === 'queued' && data.reason === 'cancel_before_dispatch' &&
    data.actionOutcome === 'not_dispatched' && data.automaticRetryAllowed === false
}

function validEventWaitCancellation(response, args) {
  const data = response.data;
  const fields = ['subscriptionId', 'matched', 'stage', 'reason', 'nextAction', 'automaticRetryAllowed'];
  return response.executed === false && data && Object.keys(data).length === fields.length &&
    Object.keys(data).every(key => fields.includes(key)) && data.subscriptionId === args.subscriptionId &&
    data.matched === false && data.stage === 'event_wait' &&
    ['user_cancelled', 'session_changed', 'page_changed', 'task_changed', 'action_invalidated', 'subscription_removed'].includes(data.reason) &&
    data.nextAction === 'read_status_and_rearm' && data.automaticRetryAllowed === false;
}

function validRenderingArgs(args) {
  const fields = args.operation === 'get' ? ['action', 'operation'] :
    ['action', 'operation', 'key', 'value', 'expectedRevision'];
  if (Object.keys(args).length !== fields.length || Object.keys(args).some(key => !fields.includes(key))) return false;
  if (args.operation === 'get') return true;
  if (args.operation !== 'set' || !Number.isSafeInteger(args.expectedRevision) || args.expectedRevision < 0) return false;
  switch (args.key) {
    case 'userAgentMode': return ['default', 'mobile', 'desktop'].includes(args.value);
    case 'layoutWidthMode': return ['device', 'wide'].includes(args.value);
    case 'overviewMode': return typeof args.value === 'boolean';
    case 'textScalePercent': return Number.isInteger(args.value) && args.value >= 50 && args.value <= 200;
    default: return false;
  }
}

function validRenderingResponse(response, args) {
  const data = response.data;
  const fields = ['settings', 'changed', 'reloadRequired', 'invalidatesVisualReferences', 'persisted'];
  if (!data || Object.keys(data).length !== fields.length || Object.keys(data).some(key => !fields.includes(key))) return false;
  const settings = data.settings;
  const keys = ['userAgentMode', 'layoutWidthMode', 'overviewMode', 'textScalePercent', 'revision'];
  if (!settings || typeof settings !== 'object' || Array.isArray(settings) ||
      Object.keys(settings).length !== keys.length || Object.keys(settings).some(key => !keys.includes(key))) return false;
  if (!['default', 'mobile', 'desktop'].includes(settings.userAgentMode) ||
      !['device', 'wide'].includes(settings.layoutWidthMode) || typeof settings.overviewMode !== 'boolean' ||
      !Number.isInteger(settings.textScalePercent) || settings.textScalePercent < 50 || settings.textScalePercent > 200 ||
      !Number.isSafeInteger(settings.revision) || settings.revision < 0) return false;
  if (typeof data.changed !== 'boolean' || typeof data.persisted !== 'boolean' ||
      data.reloadRequired !== data.changed || data.invalidatesVisualReferences !== data.changed) return false;
  if (response.code === 'SETTINGS_PERSISTENCE_FAILED') {
    return args.operation === 'set' && response.executed === null && data.persisted === false;
  }
  if (response.code === 'STALE_REVISION') {
    return args.operation === 'set' && response.executed === false && !data.changed && !data.persisted &&
      settings.revision !== args.expectedRevision;
  }
  if (response.code !== 'OK') return false;
  if (args.operation === 'get') return response.executed === false && !data.changed && !data.persisted;
  return args.operation === 'set' && response.executed === data.changed &&
    data.persisted === data.changed && settings[args.key] === args.value &&
    settings.revision === args.expectedRevision + (data.changed ? 1 : 0);
}

function validAxActionRef(value) {
  return typeof value === 'string' && /^axa:[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}:[0-9]{1,3}$/.test(value) && Number(value.slice(value.lastIndexOf(':') + 1)) <= 255;
}
function validAxActionResponse(response, action, args = {}) {
  const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
  const keys = (value, allowed) => object(value) && Object.keys(value).every(key => allowed.includes(key));
  const data = response.data;
  if (!keys(response, ['version', 'requestId', 'code', 'executed', 'sessionGeneration', 'controlGeneration', 'state', 'owner', 'data'])) return false;
  const failureData = () => keys(data, ['reason', 'stage', 'cancelStage', 'objectGroupReleased', 'nextAction', 'automaticRetryAllowed']) &&
    ['reason', 'stage', 'cancelStage', 'nextAction'].every(key => data[key] === undefined || typeof data[key] === 'string' && /^[A-Za-z][A-Za-z0-9_]{0,127}$/.test(data[key])) &&
    (data.objectGroupReleased === undefined || typeof data.objectGroupReleased === 'boolean') &&
    (data.automaticRetryAllowed === undefined || data.automaticRetryAllowed === false);
  if (action === 'ax_observe') {
    if (response.executed !== false) return false;
    if (response.code !== 'OK') return response.code !== 'UNKNOWN' && failureData();
    if (!keys(data, ['tree', 'actionRefs', 'actionRefTtlMs', 'actionRefsTruncated']) || Object.keys(data).length !== 4 ||
        !validAxTreeData(data.tree, args) || data.actionRefTtlMs !== 30000 || typeof data.actionRefsTruncated !== 'boolean' ||
        !Array.isArray(data.actionRefs) || data.actionRefs.length > 256 || Buffer.byteLength(JSON.stringify(data), 'utf8') > 14336) return false;
    const treeRefs = new Set(data.tree.nodes.map(node => node.axRef));
    const axRefs = new Set(), actionRefs = new Set();
    for (const row of data.actionRefs) {
      if (!keys(row, ['axRef', 'actionRef']) || Object.keys(row).length !== 2 || !treeRefs.has(row.axRef) ||
          !validAxActionRef(row.actionRef) || axRefs.has(row.axRef) || actionRefs.has(row.actionRef)) return false;
      axRefs.add(row.axRef); actionRefs.add(row.actionRef);
    }
    return true;
  }
  const kind = action.slice(3);
  if (response.code === 'OK' || response.code === 'NO_EFFECT') {
    return response.executed === true && (response.code !== 'NO_EFFECT' || kind === 'scroll') &&
      keys(data, ['action', 'scrollTop', 'objectGroupReleased']) && data.action === kind &&
      (data.scrollTop === undefined || kind === 'scroll' && typeof data.scrollTop === 'number' && Number.isFinite(data.scrollTop)) &&
      (data.objectGroupReleased === undefined || typeof data.objectGroupReleased === 'boolean');
  }
  if (response.code === 'UNKNOWN') {
    return response.executed === null && (Object.keys(data).length === 0 ||
      keys(data, ['reason', 'stage', 'nonRetryable', 'sideEffectsMayHaveOccurred', 'objectGroupReleased']) &&
      typeof data.reason === 'string' && /^[a-z][a-z0-9_]{0,127}$/.test(data.reason) &&
      data.nonRetryable === true && data.sideEffectsMayHaveOccurred === true &&
      (data.stage === undefined || typeof data.stage === 'string' && /^[a-z][a-z0-9_]{0,63}$/.test(data.stage)) &&
      (data.objectGroupReleased === undefined || typeof data.objectGroupReleased === 'boolean'));
  }
  return response.executed === false && failureData();
}
function validAxTreeData(data, args = {}) {
  const fields = ['coverage', 'coverageScope', 'fullPageAxCovered', 'frames', 'nodes', 'uniqueAxRefs',
    'requestedNodes', 'maxDepth', 'truncated', 'inventoryTruncated', 'inventoryTruncatedReason',
    'droppedNodes', 'duplicateNodes', 'unattributedNodes', 'childReferencesTruncated',
    'unresolvedChildReferences', 'depthLimited', 'droppedFrames', 'commandCount', 'budgets'];
  const object = value => value && typeof value === 'object' && !Array.isArray(value);
  const exact = (value, keys) => object(value) && Object.keys(value).sort().join(',') === keys.slice().sort().join(',');
  const integer = value => Number.isSafeInteger(value) && value >= 0;
  const identifier = value => typeof value === 'string' && /^[A-Za-z0-9_.:-]{1,128}$/.test(value);
  const ref = value => typeof value === 'string' && /^ax:[A-Za-z0-9_.:-]{1,260}$/.test(value);
  if (!exact(data, fields) || !['complete', 'partial'].includes(data.coverage) ||
      data.coverageScope !== 'inventoried_frames_only' || data.fullPageAxCovered !== false ||
      data.requestedNodes !== (args.maxNodes ?? 64) || data.maxDepth !== (args.maxDepth ?? 4) ||
      !Number.isInteger(data.requestedNodes) || data.requestedNodes < 1 || data.requestedNodes > 256 ||
      !Number.isInteger(data.maxDepth) || data.maxDepth < 1 || data.maxDepth > 8 ||
      !Array.isArray(data.frames) || data.frames.length < 1 || data.frames.length > 32 ||
      !Array.isArray(data.nodes) || data.nodes.length > data.requestedNodes ||
      data.uniqueAxRefs !== data.nodes.length || Buffer.byteLength(JSON.stringify(data), 'utf8') > 12288) return false;
  if (!['truncated', 'inventoryTruncated', 'childReferencesTruncated', 'depthLimited'].every(key => typeof data[key] === 'boolean') ||
      !['droppedNodes', 'duplicateNodes', 'unattributedNodes', 'unresolvedChildReferences', 'droppedFrames', 'commandCount'].every(key => integer(data[key])) ||
      data.commandCount > 12 || data.depthLimited !== (data.maxDepth < 8) ||
      (data.inventoryTruncated ? data.inventoryTruncatedReason !== 'frame_limit_or_depth' : data.inventoryTruncatedReason !== null)) return false;
  const budget = data.budgets;
  if (!exact(budget, ['maxCommands', 'usedCommands', 'maxOutputBytes', 'maxTotalNodes']) ||
      budget.maxCommands !== 12 || budget.usedCommands !== data.commandCount ||
      budget.maxOutputBytes !== 12288 || budget.maxTotalNodes !== 256) return false;
  const frames = new Set();
  for (const frame of data.frames) {
    if (!exact(frame, ['frameId', 'relation', 'origin', 'coverage', 'reason', 'nodeCount']) ||
        !identifier(frame.frameId) || frames.has(frame.frameId) || !['main', 'child'].includes(frame.relation) ||
        !['same', 'cross', 'opaque'].includes(frame.origin) ||
        !['observed', 'not_covered', 'not_attempted', 'unsupported'].includes(frame.coverage) ||
        !['ok', 'not_read', 'command_budget', 'frame_id_unsupported', 'no_matching_nodes', 'cdp_failed'].includes(frame.reason) ||
        !integer(frame.nodeCount) || frame.nodeCount > data.nodes.length) return false;
    frames.add(frame.frameId);
  }
  const refs = new Set();
  const states = new Set(['focused', 'disabled', 'required', 'readonly', 'busy', 'modal',
    'multiselectable', 'expanded', 'selected', 'checked', 'mixed']);
  for (const node of data.nodes) {
    if (!exact(node, ['axRef', 'parentAxRef', 'frameId', 'frameAttribution', 'role', 'name', 'states', 'childAxRefs', 'childRefsTruncated']) ||
        !ref(node.axRef) || refs.has(node.axRef) || !(node.parentAxRef === null || ref(node.parentAxRef)) ||
        !(node.frameId === null || identifier(node.frameId) && frames.has(node.frameId)) ||
        node.frameAttribution !== (node.frameId === null ? 'unknown' : 'known') ||
        !(node.role === null || typeof node.role === 'string' && node.role.length <= 64) ||
        typeof node.name !== 'string' || node.name.length > 512 ||
        !Array.isArray(node.states) || node.states.length > 12 || !node.states.every(state => states.has(state)) ||
        !Array.isArray(node.childAxRefs) || node.childAxRefs.length > 16 || !node.childAxRefs.every(ref) ||
        typeof node.childRefsTruncated !== 'boolean') return false;
    refs.add(node.axRef);
  }
  const missing = data.nodes.reduce((total, node) => total + node.childAxRefs.filter(value => !refs.has(value)).length, 0);
  if (data.unattributedNodes !== data.nodes.filter(node => node.frameId === null).length ||
      data.unresolvedChildReferences !== missing ||
      data.childReferencesTruncated !== data.nodes.some(node => node.childRefsTruncated) ||
      data.frames.some(frame => frame.nodeCount !== data.nodes.filter(node => node.frameId === frame.frameId).length)) return false;
  const limited = data.inventoryTruncated || data.droppedNodes > 0 || data.droppedFrames > 0 ||
    data.unattributedNodes > 0 || data.childReferencesTruncated || data.depthLimited || missing > 0;
  if (limited && !data.truncated) return false;
  return data.coverage !== 'complete' || !data.truncated && data.frames.every(frame => frame.coverage === 'observed');
}

function validNavigationMetadata(data) {
  if (!['urlBefore', 'urlAfter', 'committed'].some((key) => Object.prototype.hasOwnProperty.call(data, key))) return true;
  if (!NAVIGATION_METADATA_KEYS.every((key) => Object.prototype.hasOwnProperty.call(data, key)) ||
      typeof data.committed !== 'boolean' || (data.committed ? data.urlAfter === null : data.urlAfter !== null)) return false;
  return ['urlBefore', 'requestedUrl', 'urlAfter'].every((key) => validSanitizedUrl(data[key]) &&
    typeof data[key + 'QueryRedacted'] === 'boolean' && typeof data[key + 'FragmentRedacted'] === 'boolean');
}

function validBlockInfo(data) {
  if (!Object.prototype.hasOwnProperty.call(data, 'blockInfo')) return true;
  const b = data.blockInfo;
  if (!b || typeof b !== 'object' || Array.isArray(b) || Object.keys(b).sort().join(',') !==
      'assessment,automaticResolutionAllowed,nextAction,signals' || !Array.isArray(b.signals) || b.signals.length > 8 ||
      b.automaticResolutionAllowed !== false || b.assessment !== (b.signals.length ? 'possible' : 'not_detected') ||
      b.nextAction !== (b.signals.length ? 'inspect_and_request_user_if_needed' : 'continue_observing')) return false;
  const seen = new Set();
  return b.signals.every(s => {
    if (!s || typeof s !== 'object' || Array.isArray(s) || Object.keys(s).sort().join(',') !== 'evidence,kind,source') return false;
    const key = s.kind + ':' + s.source;
    if (seen.has(key)) return false;
    seen.add(key);
    return s.kind === 'authentication' ? s.source === 'elements' && s.evidence === 'password_field_present' :
      ['verification','access_restricted'].includes(s.kind) && ['title','text','overlay'].includes(s.source) && s.evidence === 'keyword_match';
  });
}

function validOverlayMetadata(data) {
  const keys = ['overlays', 'overlayScope', 'overlayCoverage'];
  if (!keys.some(k => Object.prototype.hasOwnProperty.call(data, k))) return true;
  const exact = (o, fields) => o && typeof o === 'object' && !Array.isArray(o) &&
    Object.keys(o).length === fields.length && fields.every(k => Object.prototype.hasOwnProperty.call(o, k));
  const int = (n, max) => Number.isSafeInteger(n) && n >= 0 && n <= max;
  const str = (s, max) => typeof s === 'string' && s.length <= max;
  const number = n => typeof n === 'number' && Number.isFinite(n) && Math.abs(n) <= 1e9;
  const box = b => exact(b, ['x','y','width','height']) && Object.values(b).every(number) && b.width > 0 && b.height > 0;
  const scope = data.overlayScope, c = data.overlayCoverage;
  if (!keys.every(k => Object.prototype.hasOwnProperty.call(data, k)) ||
      !Array.isArray(data.overlays) || data.overlays.length > 8 ||
      !exact(scope, ['topFrameOnly','includesCrossOriginFrames','frameCoverage','complete']) ||
      scope.topFrameOnly !== true || scope.includesCrossOriginFrames !== false || scope.frameCoverage !== 'not_inspected' || scope.complete !== false ||
      !exact(c, ['partial','candidateScan','candidateScanLimit','candidateLimitReached','viewportSampleCount','viewportSampleLimit',
        'overlayOutputCount','overlayOutputLimit','outputTruncated','textNodesVisited','textChars','textLimitReached','errors']) ||
      !['partial','candidateLimitReached','outputTruncated','textLimitReached'].every(k => typeof c[k] === 'boolean') ||
      c.candidateScanLimit !== 256 || c.viewportSampleLimit !== 25 || c.overlayOutputLimit !== 8 ||
      !int(c.candidateScan,256) || !int(c.viewportSampleCount,25) || c.overlayOutputCount !== data.overlays.length ||
      !int(c.textNodesVisited,512) || !int(c.textChars,8192) || !Array.isArray(c.errors) || c.errors.length > 4 ||
      !c.errors.every(e => e === 'bounded_observation_error') || c.errors.length > 0 && !c.partial) return false;
  const refs = new Set((Array.isArray(data.elements) ? data.elements : [])
    .filter(e => e && typeof e === 'object' && e.frameOrdinal === 0).map(e => e.nodeId));
  let sampled = 0, dismissCount = 0;
  for (const o of data.overlays) {
    if (!exact(o, ['kind','tag','role','name','box','viewportBox','viewportCoverageRatio','evidence','occlusion','pseudo','dismiss']) ||
        !['dialog','modal','fixed','sticky','layer'].includes(o.kind) || !str(o.tag,32) || !str(o.role,48) || !str(o.name,160) ||
        !box(o.box) || !box(o.viewportBox) || !number(o.viewportCoverageRatio) || o.viewportCoverageRatio < 0 || o.viewportCoverageRatio > 1 ||
        !exact(o.evidence, ['fixed','sticky','dialog','modal','zIndex','semanticHint']) ||
        !['fixed','sticky','dialog','modal'].every(k => typeof o.evidence[k] === 'boolean') ||
        !(o.evidence.zIndex === null || number(o.evidence.zIndex)) || !str(o.evidence.semanticHint,160) ||
        !exact(o.occlusion, ['sampled','covered','ratio']) || !int(o.occlusion.sampled,25) || !int(o.occlusion.covered,o.occlusion.sampled) ||
        (o.occlusion.sampled === 0 ? o.occlusion.ratio !== null : !number(o.occlusion.ratio) ||
          Math.abs(o.occlusion.ratio - o.occlusion.covered/o.occlusion.sampled) > 0.000051) ||
        !Array.isArray(o.pseudo) || o.pseudo.length > 2 ||
        !o.pseudo.every(p => exact(p,['source','boundedContent']) && ['before','after'].includes(p.source) && str(p.boundedContent,160)) ||
        !exact(o.dismiss,['status','candidates']) || !Array.isArray(o.dismiss.candidates) || o.dismiss.candidates.length > 4 ||
        o.dismiss.status !== (o.dismiss.candidates.length ? 'available' : 'none')) return false;
    sampled += o.occlusion.sampled;
    dismissCount += o.dismiss.candidates.length;
    if (!o.dismiss.candidates.every(d => exact(d,['nodeId','tag','role','name','box']) && refs.has(d.nodeId) &&
      str(d.tag,32) && str(d.role,48) && str(d.name,160) && box(d.box))) return false;
  }
  return sampled <= c.viewportSampleCount && dismissCount <= 7;
}

function validObserveScopeMetadata(data) {
  if (!validBlockInfo(data)) return false;
  if (!validOverlayMetadata(data)) return false;
  const metadataKeys = ['scopeCoverage', 'frameScopes', 'topFrameOnly', 'openShadowRootsSupported',
    'closedShadowRootsSupported', 'crossOriginFramesSupported'];
  const present = hasAnyKey(data, metadataKeys) || (Array.isArray(data.elements) && data.elements.some((e) =>
    e && typeof e === 'object' && hasAnyKey(e, ['frameOrdinal', 'shadowDepth', 'accessibleName', 'placeholder'])));
  if (!present) return true;
  const required = ['readyState', 'text', 'textTruncated', 'elements', 'truncated', 'elementsTruncated',
    'urlQueryRedacted', 'urlFragmentRedacted', 'loadState', 'observationGeneration', ...metadataKeys];
  const allowed = new Set([...required, 'title', 'url', 'httpStatus', 'mainFrameError', ...NAVIGATION_METADATA_KEYS,
    'overlays', 'overlayScope', 'overlayCoverage', 'blockInfo']);
  if (required.some((k) => !Object.prototype.hasOwnProperty.call(data, k)) ||
      Object.keys(data).some((k) => !allowed.has(k)) || !validScopeCoverage(data.scopeCoverage) ||
      !validFrameScopes(data.frameScopes, data.scopeCoverage) || data.topFrameOnly !== false ||
      data.openShadowRootsSupported !== true || data.closedShadowRootsSupported !== false || data.crossOriginFramesSupported !== false ||
      (data.title !== undefined && (typeof data.title !== 'string' || data.title.length > 256)) ||
      (data.url !== undefined && !validSanitizedUrl(data.url)) ||
      typeof data.urlQueryRedacted !== 'boolean' || typeof data.urlFragmentRedacted !== 'boolean' ||
      data.loadState !== 'finished' || data.readyState !== 'complete' ||
      !Number.isSafeInteger(data.observationGeneration) || data.observationGeneration < 1 ||
      typeof data.text !== 'string' || typeof data.textTruncated !== 'boolean' || typeof data.truncated !== 'boolean' ||
      typeof data.elementsTruncated !== 'boolean' || !Array.isArray(data.elements) || data.elements.length > 80) return false;
  if (data.httpStatus !== undefined && (!Number.isInteger(data.httpStatus) || data.httpStatus < 100 || data.httpStatus > 599)) return false;
  if (data.mainFrameError !== undefined && (!Number.isInteger(data.mainFrameError) || data.mainFrameError < -1000 || data.mainFrameError > 0)) return false;
  const accessible = new Set(data.frameScopes.filter((s) => s.status === 'accessible').map((s) => s.ordinal));
  return data.elements.every((e, index) => {
    const legacyKeys = ['nodeId', 'tag', 'type', 'role', 'label', 'href',
      'hrefQueryRedacted', 'hrefFragmentRedacted', 'frameOrdinal', 'shadowDepth']
    const currentKeys = [...legacyKeys, 'accessibleName', 'placeholder']
    const current = exactObjectKeys(e, currentKeys)
    if (!current && !exactObjectKeys(e, legacyKeys)) return false
    if (current && (typeof e.accessibleName !== 'string' || e.accessibleName.length > 160 ||
        typeof e.placeholder !== 'string' || e.placeholder.length > 160)) return false
    return typeof e.nodeId === 'string' && /^b:[1-9][0-9]{0,15}:[1-9][0-9]{0,15}:[0-9]{1,16}:[1-9][0-9]{0,15}:[a-f0-9]{32}:(?:[0-9]|[1-7][0-9])$/.test(e.nodeId) &&
    e.nodeId.split(':')[4] === String(data.observationGeneration) && e.nodeId.endsWith(':' + index) &&
    typeof e.tag === 'string' && e.tag.length > 0 && e.tag.length <= 128 && typeof e.type === 'string' && e.type.length <= 32 &&
    typeof e.role === 'string' && e.role.length <= 48 && typeof e.label === 'string' && e.label.length <= 160 &&
    typeof e.href === 'string' && (e.href === '' || validSanitizedUrl(e.href)) &&
    typeof e.hrefQueryRedacted === 'boolean' && typeof e.hrefFragmentRedacted === 'boolean' &&
    Number.isSafeInteger(e.frameOrdinal) && e.frameOrdinal >= 0 && e.frameOrdinal <= 16 &&
    Number.isSafeInteger(e.shadowDepth) && e.shadowDepth >= 0 && e.shadowDepth <= 32 &&
    (e.frameOrdinal === 0 || accessible.has(e.frameOrdinal))
  })
}
function validSanitizedUrl(value) {
  if (value === null || value === 'about:blank') return true
  if (typeof value !== 'string' || value.length > 2048) return false
  try {
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password && !url.search && !url.hash
  } catch { return false }
}

function validCollectData(data, args, sessionGeneration, controlGeneration) {
  const exact = (value, fields) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).sort().join(',') === fields.slice().sort().join(',')
  if (!exact(data, ['action', 'identity', 'containerNodeId', 'container', 'records', 'truncated', 'excludedCount', 'declaredRange']) || data.action !== 'window' ||
      data.declaredRange !== args.declaredRange || typeof data.truncated !== 'boolean' ||
      !Number.isSafeInteger(data.excludedCount) || data.excludedCount < 0 || data.excludedCount > 5000 ||
      typeof data.containerNodeId !== 'string' || !/^b:[1-9][0-9]{0,15}:[1-9][0-9]{0,15}:[0-9]{1,16}:[1-9][0-9]{0,15}:[a-f0-9]{32}:0$/.test(data.containerNodeId) ||
      !exact(data.identity, ['pageId', 'documentGeneration', 'navigationId', 'observationId', 'sessionGeneration', 'controlGeneration']) ||
      !/^pg-[A-Fa-f0-9]{20}$/.test(data.identity.pageId) || !Number.isSafeInteger(data.identity.documentGeneration) || data.identity.documentGeneration < 0 ||
      !Number.isSafeInteger(data.identity.navigationId) || data.identity.navigationId < 0 || !Number.isSafeInteger(data.identity.observationId) || data.identity.observationId < 1 ||
      data.identity.sessionGeneration !== sessionGeneration || data.identity.controlGeneration !== controlGeneration ||
      !exact(data.container, ['scrollTop', 'scrollHeight', 'clientHeight', 'startReached', 'endReached', 'totalCount']) ||
      !Number.isFinite(data.container.scrollTop) || !Number.isFinite(data.container.scrollHeight) || !Number.isFinite(data.container.clientHeight) ||
      data.container.scrollTop < 0 || data.container.scrollHeight <= data.container.clientHeight || data.container.clientHeight <= 0 ||
      data.container.scrollTop > data.container.scrollHeight || typeof data.container.startReached !== 'boolean' || typeof data.container.endReached !== 'boolean' ||
      !(data.container.totalCount === null || (Number.isSafeInteger(data.container.totalCount) && data.container.totalCount >= 0)) ||
      !Array.isArray(data.records) || data.records.length > args.maxRecords) return false
  return data.records.every((record) => exact(record, ['key', 'author', 'content', 'order']) &&
    (record.key === null || (typeof record.key === 'string' && record.key.length <= 256 && !/[\u0000-\u001f\u007f]/.test(record.key))) &&
    (record.author === null || (typeof record.author === 'string' && record.author.length <= 128 && !/[\u0000-\u001f\u007f]/.test(record.author))) &&
    typeof record.content === 'string' && record.content.length <= args.maxContentChars &&
    (record.order === null || Number.isSafeInteger(record.order)))
}

function validEventResponse(response, action, args) {
  const exact = (value, fields) => value && typeof value === 'object' && !Array.isArray(value) &&
    Object.keys(value).sort().join(',') === fields.slice().sort().join(',')
  const id = (value) => typeof value === 'string' && /^[a-f0-9]{32}$/.test(value)
  const identity = (value) => exact(value, ['sessionGeneration', 'controlGeneration', 'pageId', 'documentGeneration', 'navigationId']) &&
    Number.isSafeInteger(value.sessionGeneration) && value.sessionGeneration > 0 &&
    Number.isSafeInteger(value.controlGeneration) && value.controlGeneration > 0 &&
    /^pg-[A-Fa-f0-9]{20}$/.test(value.pageId) && Number.isSafeInteger(value.documentGeneration) && value.documentGeneration >= 0 &&
    Number.isSafeInteger(value.navigationId) && value.navigationId >= 0
  // Keep ambiguous delivery visible as UNKNOWN; its data is diagnostic only and never success.
  if (response.code === 'UNKNOWN') return response.executed === null
  if (response.code === 'OK' && response.executed === true) {
    const data = response.data
    if (action === 'arm') return exact(data, ['subscriptionId', 'nonce', 'expiresAtElapsedMillis', 'identity']) &&
      id(data.subscriptionId) && typeof data.nonce === 'string' && /^[a-f0-9]{64}$/.test(data.nonce) &&
      Number.isSafeInteger(data.expiresAtElapsedMillis) && data.expiresAtElapsedMillis > 0 && identity(data.identity) &&
      data.identity.sessionGeneration === response.sessionGeneration && data.identity.controlGeneration === response.controlGeneration
    if (action === 'wait') {
      const base = ['eventId', 'subscriptionId', 'actionRequestId', 'eventKind', 'attribution', 'pageId', 'documentGeneration', 'navigationId']
      const allowed = new Set([...base, 'url', 'dialog', 'newPageId', 'downloadId', ...NAVIGATION_METADATA_KEYS])
      if (!validNavigationMetadata(data) || (NAVIGATION_METADATA_KEYS.some((key) => Object.hasOwn(data, key)) &&
          !data.eventKind?.startsWith('navigation_'))) return false
      if (!base.every((key) => Object.prototype.hasOwnProperty.call(data, key)) || Object.keys(data).some((key) => !allowed.has(key))) return false
      if (!id(data.eventId) || data.subscriptionId !== args.subscriptionId ||
          typeof data.actionRequestId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(data.actionRequestId) ||
          !['navigation_started', 'navigation_finished', 'navigation_failed', 'new_window', 'download', 'javascript_dialog'].includes(data.eventKind) ||
          !['native_correlated', 'action_scoped_observation'].includes(data.attribution) ||
          !/^pg-[A-Fa-f0-9]{20}$/.test(data.pageId) || !Number.isSafeInteger(data.documentGeneration) || data.documentGeneration < 0 ||
          !Number.isSafeInteger(data.navigationId) || data.navigationId < 0 ||
          (data.newPageId !== undefined && (data.eventKind !== 'new_window' || !/^pg-[a-f0-9]{20}$/.test(data.newPageId))) ||
          (data.downloadId !== undefined && (data.eventKind !== 'download' || !/^[a-f0-9]{32}$/.test(data.downloadId))) ||
          (data.url !== undefined && !validSanitizedUrl(data.url))) return false
      if (data.eventKind === 'javascript_dialog') {
        const dialog = data.dialog
        if (!dialog || typeof dialog !== 'object' || Array.isArray(dialog) ||
            !['alert', 'confirm', 'prompt', 'beforeunload'].includes(dialog.dialogType) ||
            typeof dialog.message !== 'string' || dialog.message.length > 4096 ||
            Object.keys(dialog).some((key) => !['dialogType', 'message', 'promptDefault'].includes(key)) ||
            (dialog.promptDefault !== undefined && (typeof dialog.promptDefault !== 'string' || dialog.promptDefault.length > 4096))) return false
      } else if (data.dialog !== undefined) return false
      return true
    }
    if (action === 'cancel') return exact(data, ['cancelled']) && data.cancelled === true
    if (action === 'resolve_dialog') return exact(data, ['resolved']) && data.resolved === true
  }
  if (response.code === 'EVENT_TIMEOUT') return action === 'wait' && response.executed === false &&
    exact(response.data, ['subscriptionId', 'matched']) && response.data.subscriptionId === args.subscriptionId && response.data.matched === false
  if (response.code === 'CANCELLED') return response.executed === false && Object.keys(response.data).length === 0 ||
    action === 'wait' && validEventWaitCancellation(response, args)
  if (['EVENT_SUBSCRIPTION_INVALID', 'INVALID_ARGUMENT', 'CLOSED', 'AUTOMATION_DISABLED', 'USER_CONTROLLED', 'STALE_SESSION',
    'SESSION_REQUIRED', 'DEADLINE_EXPIRED', 'TASK_PAGE_MISMATCH', 'JOURNAL_UNAVAILABLE', 'BUSY', 'TASK_NOT_FOUND',
    'TASK_UNKNOWN_EFFECT_UNVERIFIED', 'TASK_EXPIRED', 'TASK_NOT_RUNNING', 'TASK_BUDGET_EXHAUSTED'].includes(response.code)) {
    return response.executed === false && Object.keys(response.data).length === 0
  }
  return false
}

function validTaskFeedbackResponse(response, action, args) {
  const data = response.data
  const exact = (value, fields) => value && typeof value === 'object' && !Array.isArray(value) &&
    Object.keys(value).sort().join(',') === fields.slice().sort().join(',')
  if (Object.keys(response).some((key) => !['version', 'requestId', 'code', 'executed', 'sessionGeneration',
      'controlGeneration', 'state', 'owner', 'data'].includes(key))) return false
  if (response.code === 'UNKNOWN') return response.executed === null
  if (response.code !== 'OK') return response.executed === false && Object.keys(data).length === 0 &&
    ['INVALID_ARGUMENT', 'FEEDBACK_NOT_FOUND', 'DESTINATION_MISMATCH', 'FEEDBACK_CONFLICT',
      'FEEDBACK_ALREADY_ADMITTED', 'FEEDBACK_NOT_ADMITTED', 'LIMIT_EXCEEDED', 'CLOSED', 'STALE_SESSION',
      'SESSION_REQUIRED', 'DEADLINE_EXPIRED'].includes(response.code)
  if (action === 'task_pending') {
    if (response.executed !== false || !exact(data, ['afterSequence', 'nextSequence', 'items']) ||
        data.afterSequence !== args.afterSequence || !Number.isSafeInteger(data.nextSequence) ||
        data.nextSequence < data.afterSequence || !Array.isArray(data.items) || data.items.length > 64) return false
    let priorSequence = data.afterSequence
    for (const item of data.items) {
      const keys = ['feedbackId', 'kind', 'taskId', 'revision', 'title', 'text', 'sequence', 'state', 'sessionGeneration']
      if (!item || !exact(item, keys) || typeof item.feedbackId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(item.feedbackId) ||
          item.kind !== 'task_recovery' || typeof item.taskId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(item.taskId) ||
          !Number.isSafeInteger(item.revision) || item.revision < 0 || typeof item.title !== 'string' || item.title.length > 120 ||
          typeof item.text !== 'string' || Buffer.byteLength(item.text, 'utf8') > 64 * 1024 ||
          !Number.isSafeInteger(item.sequence) || item.sequence <= priorSequence || item.sequence > data.nextSequence ||
          !['pending', 'claimed'].includes(item.state) || item.sessionGeneration !== response.sessionGeneration) return false
      priorSequence = item.sequence
    }
    return data.nextSequence === (data.items.length ? priorSequence : data.afterSequence)
  }
  if (action === 'task_claim' || action === 'task_admit') {
    return response.executed === true && exact(data, ['feedbackId', 'state', 'requestId', 'payloadDigest']) &&
      data.feedbackId === args.feedbackId && (action === 'task_claim' ? ['pending', 'claimed'].includes(data.state) : data.state === 'admitted') &&
      data.requestId === args.requestId && data.payloadDigest === args.payloadDigest
  }
  return response.executed === true && exact(data, ['feedbackId', 'state', 'deliveryCode']) &&
    data.feedbackId === args.feedbackId && data.deliveryCode === args.deliveryCode &&
    data.state === ({ PROMPT_ACCEPTED: 'prompt_accepted', UNKNOWN: 'unknown', BLOCKED: 'blocked' })[args.deliveryCode]
}

function validTaskListResponse(response, args) {
  if (response.code === 'TASK_RECORD_TOO_LARGE') {
    const data = response.data
    return response.executed === false && exactTaskKeys(data,
      ['taskId', 'offset', 'nextOffset', 'requiredBytes', 'maxResponseBytes', 'hardFrameLimitBytes']) &&
      typeof data.taskId === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(data.taskId) &&
      data.offset === (args.offset ?? 0) && data.nextOffset === data.offset && Number.isSafeInteger(data.requiredBytes) &&
      data.requiredBytes > 12 * 1024 && data.maxResponseBytes === 12 * 1024 && data.hardFrameLimitBytes === 256 * 1024
  }
  if (response.code !== 'OK') return response.executed === false && Object.keys(response.data).length === 0
  const data = response.data
  if (response.executed !== false || !exactTaskKeys(data, ['tasks', 'totalCount', 'nextOffset']) ||
      !Array.isArray(data.tasks) || data.tasks.length > (args.limit ?? 100) || !Number.isSafeInteger(data.totalCount) ||
      data.totalCount < 0 || data.totalCount > 512 ||
      !(data.nextOffset === null || (Number.isSafeInteger(data.nextOffset) && data.nextOffset >= (args.offset ?? 0) && data.nextOffset <= data.totalCount))) return false
  const taskFields = ['taskId', 'revision', 'status', 'deadlineElapsedMillis', 'operationLimit', 'operationsUsed',
    'remainingOperations', 'waitReason', 'title', 'goal', 'notes', 'createdAtWallMillis', 'updatedAtWallMillis', 'canResume', 'canDelete']
  for (const task of data.tasks) {
    const fields = Object.prototype.hasOwnProperty.call(task, 'failureReason') ? [...taskFields, 'failureReason'] : taskFields
    if (!exactTaskKeys(task, fields) ||
        (Object.prototype.hasOwnProperty.call(task, 'failureReason') && task.failureReason !== null &&
          (task.status !== 'failed' || typeof task.failureReason !== 'string' || !task.failureReason.trim() || task.failureReason.length > 1000)) ||
        typeof task.taskId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(task.taskId) ||
        !Number.isSafeInteger(task.revision) || task.revision < 0 ||
        !['running', 'waiting_page', 'waiting_user', 'paused', 'needs_reconciliation', 'budget_exhausted', 'completed', 'failed', 'expired'].includes(task.status) ||
        !Number.isSafeInteger(task.deadlineElapsedMillis) || task.deadlineElapsedMillis < 0 ||
        !Number.isSafeInteger(task.operationLimit) || task.operationLimit < 1 || task.operationLimit > 2000 ||
        !Number.isSafeInteger(task.operationsUsed) || task.operationsUsed < 0 || task.operationsUsed > task.operationLimit ||
        task.remainingOperations !== task.operationLimit - task.operationsUsed ||
        !(task.waitReason === null || ['page_load', 'page_condition', 'user_action', 'user_authentication', 'recovery_check'].includes(task.waitReason)) ||
        typeof task.title !== 'string' || task.title.length > 120 || typeof task.goal !== 'string' || task.goal.length > 4000 ||
        typeof task.notes !== 'string' || task.notes.length > 2000 || !Number.isSafeInteger(task.createdAtWallMillis) || task.createdAtWallMillis < 0 ||
        !Number.isSafeInteger(task.updatedAtWallMillis) || task.updatedAtWallMillis < task.createdAtWallMillis ||
        typeof task.canResume !== 'boolean' || typeof task.canDelete !== 'boolean') return false
  }
  const expectedNext = (args.offset ?? 0) + data.tasks.length < data.totalCount ? (args.offset ?? 0) + data.tasks.length : null
  return data.nextOffset === expectedNext
}

function exactTaskKeys(value, fields) {
  return value && typeof value === 'object' && !Array.isArray(value) &&
    Object.keys(value).sort().join(',') === fields.slice().sort().join(',')
}

function validControlApprovalData(data, args, expectedStatus = null, expectedCoalesced) {
  if (Object.keys(data).sort().join(',') !== 'approvalId,coalesced,createdAtMillis,expiresAtMillis,status,summary,taskId' ||
      typeof data.approvalId !== 'string' || !/^[a-f0-9]{32}$/.test(data.approvalId) ||
      !['PENDING', 'APPROVED', 'DENIED', 'CANCELLED', 'EXPIRED'].includes(data.status) ||
      (expectedStatus !== null && data.status !== expectedStatus) ||
      typeof data.summary !== 'string' || data.summary.length < 1 || data.summary.length > 1000 ||
      !(data.taskId === null || (typeof data.taskId === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(data.taskId))) ||
      !Number.isSafeInteger(data.createdAtMillis) || data.createdAtMillis < 0 ||
      !Number.isSafeInteger(data.expiresAtMillis) || data.expiresAtMillis - data.createdAtMillis !== 120000 ||
      typeof data.coalesced !== 'boolean' || (expectedCoalesced !== undefined && data.coalesced !== expectedCoalesced)) return false
  if (args.action === 'request' && (data.summary !== args.summary || data.taskId !== (args.taskId ?? null))) return false
  if (args.action !== 'request' && (data.approvalId !== args.approvalId || data.coalesced)) return false
  return true
}

function canReceiveControlTransfer(method, action) {
  if (['open', 'click', 'type', 'scroll', 'screenshot', 'close', 'navigate'].includes(method)) return true
  if (method === 'debug') return ['evaluate', 'emulate', 'interact'].includes(action)
  if (method === 'network') return ['start', 'har', 'export_frames', 'stop', 'clear_rules', 'frames', 'body', 'replay_prepare',
    'replay_status', 'replay', 'rules', 'resolve'].includes(action)
  if (method === 'selection') return ['consume', 'clear', 'claim', 'admit', 'receipt', 'task_claim', 'task_admit', 'task_receipt'].includes(action)
  if (method === 'page') return ['new', 'switch', 'close'].includes(action)
  if (method === 'file') return ['start', 'cancel'].includes(action)
  if (method === 'upload') return ['request', 'activate', 'submit', 'cancel'].includes(action)
  return method === 'event' && action === 'arm'
}

function safeResponse(stdout, requestId, method = '', action = '', args = {}) {
  if (typeof stdout !== 'string' || Buffer.byteLength(stdout, 'utf8') > RESPONSE_LIMIT) return null
  const lines = stdout.trim().split(/\r?\n/)
  if (lines.length !== 1 || !lines[0]) return null
  let response
  try { response = JSON.parse(lines[0]) } catch { return null }
  if (!response || typeof response !== 'object' || Array.isArray(response) || response.version !== 1 || response.requestId !== requestId || typeof response.code !== 'string') return null
  if (!(response.executed === true || response.executed === false || response.executed === null)) return null
  if (!(response.sessionGeneration == null || (Number.isSafeInteger(response.sessionGeneration) && response.sessionGeneration >= 0))) return null
  if (!(response.controlGeneration == null || (Number.isSafeInteger(response.controlGeneration) && response.controlGeneration >= 0))) return null
  if (!(response.state === null && response.code === 'UNKNOWN') && !['CLOSED', 'IDLE', 'RUNNING'].includes(response.state)) return null
  if (response.owner !== undefined && response.owner !== null && !['none', 'human', 'ai'].includes(response.owner)) return null
  if (!(response.data && typeof response.data === 'object' && !Array.isArray(response.data))) return null
  if (method === 'cancel') return validCancelReceipt(response, requestId) || legacyCancelReceipt(response, requestId) ? response : null
  if (method === 'debug' && ['ax_observe', 'ax_click', 'ax_type', 'ax_scroll'].includes(action) && !validAxActionResponse(response, action, args)) return null
  if (method === 'debug' && action === 'ax_tree' && response.code === 'OK' &&
      (response.executed !== false || !validAxTreeData(response.data, args))) return null
  if (method === 'debug' && action === 'rendering' &&
      ['OK', 'STALE_REVISION', 'SETTINGS_PERSISTENCE_FAILED'].includes(response.code) &&
      !validRenderingResponse(response, args)) return null
  if (['open', 'navigate'].includes(method) && !validNavigationThumbnail(response, args)) return null
  if (['open', 'observe', 'navigate', 'wait'].includes(method) && !validNavigationMetadata(response.data)) return null
  if (response.code === 'CONTROL_TRANSFER_PENDING' && method !== 'control') {
    const allowed = ['version', 'requestId', 'code', 'executed', 'sessionGeneration', 'controlGeneration', 'state', 'owner', 'data']
    if (Object.keys(response).some((key) => !allowed.includes(key)) || response.executed !== false || Object.keys(response.data).length !== 0 ||
        !canReceiveControlTransfer(method, action)) return null
    return response
  }
  if (method === 'control' && response.code === 'UNKNOWN') {
    const allowed = ['version', 'requestId', 'code', 'executed', 'sessionGeneration', 'controlGeneration', 'state', 'owner', 'data']
    return !Object.keys(response).some(key => !allowed.includes(key)) && response.executed === null &&
      (Object.keys(response.data).length === 0 || validCancellationUnknownData(response.data) ||
        validTransportUnknownData(response.data, requestId)) ? response : null
  }
  if (method === 'control') {
    if (Object.keys(response).some((key) => !['version', 'requestId', 'code', 'executed', 'sessionGeneration', 'controlGeneration', 'state', 'owner', 'data'].includes(key)) || response.executed !== false) return null
    if (response.code === 'CONTROL_TRANSFER_PENDING') return action === 'request' && Object.keys(response.data).length === 0 ? response : null
    const empty = {
      request: ['NO_ACTIVE_SESSION', 'AI_CONTROL_ALREADY_ENABLED', 'CONTROL_REQUEST_PENDING', 'DEADLINE_EXPIRED', 'SERVICE_CLOSED', 'INVALID_ARGUMENT'],
      status: ['APPROVAL_NOT_FOUND', 'DEADLINE_EXPIRED', 'SERVICE_CLOSED', 'INVALID_ARGUMENT'],
      cancel: ['APPROVAL_NOT_FOUND', 'APPROVAL_NOT_PENDING', 'APPROVAL_EXPIRED', 'DEADLINE_EXPIRED', 'SERVICE_CLOSED', 'INVALID_ARGUMENT'],
    }
    if (empty[action]?.includes(response.code)) return Object.keys(response.data).length === 0 ? response : null
    if (response.code === 'APPROVAL_SURFACE_UNAVAILABLE') {
      if (action !== 'request') return null
      if (Object.keys(response.data).length === 0) return response
      return validControlApprovalData(response.data, args, 'CANCELLED', false) ? response : null
    }
    if (response.code !== 'OK') return null
    return validControlApprovalData(response.data, args, action === 'request' ? 'PENDING' : null,
      action === 'request' ? undefined : false) ? response : null
  }
  if (response.code === 'UNKNOWN' && method !== 'event') {
    if (Object.keys(response.data).length && !(Object.keys(response.data).length === 1 && response.data.cancelCode === 'CANCELLED') &&
        ['task', 'navigate', 'open', 'click'].includes(method)) return validBoundUnknownDiagnostics(response, method, action, args) ? response : null;
    if (method === 'wait' && Object.hasOwn(response.data, 'waitScope')) return validConditionWaitUnknown(response, action) ? response : null;
    if (method === 'debug' && ['ax_click', 'ax_type', 'ax_scroll'].includes(action)) return response;
    const unknownData = Object.keys(response.data).length === 0 ||
      Object.keys(response.data).length === 1 && response.data.cancelCode === 'CANCELLED'
    return response.executed === null && (unknownData || validCancellationUnknownData(response.data) ||
      validTransportUnknownData(response.data, response.requestId)) ? response : null
  }
  if (method === 'screenshot' && response.code === 'INVALID_REQUEST' && response.executed === false &&
      exactObjectKeys(response.data, ['cancelStage', 'reason', 'validationStage']) &&
      response.data.cancelStage === 'admission' && response.data.reason === 'invalid_request' &&
      response.data.validationStage === 'request_schema') return response
  if (method === 'debug' && action === 'status' &&
      (response.executed !== false || !validDevToolsCapabilityCoverage(response.data))) return null
  if (response.code === 'CANCELLED' && response.executed === false &&
      validPreDispatchTerminalData(response.data)) return response
  if (method === 'event' && (Object.keys(response).some((key) => !['version', 'requestId', 'code', 'executed', 'sessionGeneration',
      'controlGeneration', 'state', 'owner', 'data'].includes(key)) || !validEventResponse(response, action, args))) return null
  if (method === 'selection' && action.startsWith('task_') && !validTaskFeedbackResponse(response, action, args)) return null
  if (method === 'task' && action === 'list' && !validTaskListResponse(response, args)) return null
  if (method === 'observe' && !validObserveScopeMetadata(response.data)) return null
  if (method === 'type' && response.code === 'OBSCURED') {
    const obscuredBy = response.data.obscuredBy
    const publicKeys = ['version', 'requestId', 'code', 'executed', 'sessionGeneration', 'controlGeneration', 'state', 'owner', 'data']
    if (Object.keys(response).some((key) => !publicKeys.includes(key)) || response.executed !== false ||
        Object.keys(response.data).join(',') !== 'obscuredBy' ||
        !obscuredBy || typeof obscuredBy !== 'object' || Array.isArray(obscuredBy) ||
        Object.keys(obscuredBy).sort().join(',') !== 'className,id,tag' ||
        typeof obscuredBy.tag !== 'string' || obscuredBy.tag.length > 64 ||
        typeof obscuredBy.id !== 'string' || obscuredBy.id.length > 128 ||
        typeof obscuredBy.className !== 'string' || obscuredBy.className.length > 256) return null
  }
  if (method === 'collect' && response.code === 'OK' && (response.executed !== true ||
      !validCollectData(response.data, args, response.sessionGeneration, response.controlGeneration) ||
      Buffer.byteLength(JSON.stringify(response), 'utf8') > RESPONSE_LIMIT)) return null
  if (method === 'screenshot') {
    if (response.code === 'UNKNOWN') {
      const unknownData = Object.keys(response.data).length === 0 ||
        validCancellationUnknownData(response.data) || validTransportUnknownData(response.data, response.requestId)
      return response.executed === null && unknownData ? response : null
    }
    const expectedStage = SCREENSHOT_FAILURE_STAGES[response.code]
    if (expectedStage) {
      const publicKeys = ['version', 'requestId', 'code', 'executed', 'sessionGeneration', 'controlGeneration', 'state', 'owner', 'data']
      if (Object.keys(response).some((key) => !publicKeys.includes(key)) || response.executed !== false) return null
      if (response.code === 'DEADLINE_EXPIRED' && Object.keys(response.data).length === 0) return response
      const baseKeys = ['cancelStage', 'reason']
      const diagnosticKeys = ['cancelStage', 'reason', 'primaryBackend', 'primaryFailure',
        'fallbackBackend', 'fallbackFailure', 'debugEnabled', 'userActionRequired']
      const detailed = response.data !== null && typeof response.data === 'object' && !Array.isArray(response.data) &&
        Object.keys(response.data).every(key => diagnosticKeys.includes(key)) &&
        ['cancelStage', 'reason', 'primaryBackend', 'primaryFailure', 'debugEnabled', 'userActionRequired']
          .every(key => Object.hasOwn(response.data, key)) &&
        ['virtual_display', 'cdp'].includes(response.data.primaryBackend) &&
        typeof response.data.primaryFailure === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(response.data.primaryFailure) &&
        typeof response.data.debugEnabled === 'boolean' && typeof response.data.userActionRequired === 'boolean' &&
        (response.data.fallbackBackend === undefined && response.data.fallbackFailure === undefined ||
          response.data.fallbackBackend === 'cdp' && typeof response.data.fallbackFailure === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(response.data.fallbackFailure))
      if ((!exactObjectKeys(response.data, baseKeys) && !detailed) ||
          response.data.cancelStage !== expectedStage || response.data.reason !== response.code) return null
      return response
    }
    if (response.code !== 'OK') return response.executed === false && Object.keys(response.data).length === 0 ? response : null
    if (response.executed !== true) return null
    const keys = Object.keys(response.data).sort().join(',')
    const base = 'artifactPath,bytes,format,height,width'
    const screenshotKeys = new Set(['artifactPath', 'bytes', 'format', 'height', 'screenshotId', 'width', 'backend',
      'primaryBackend', 'primaryFailure', 'fallbackBackend', 'fallbackFailure'])
    if (!Object.keys(response.data).every(key => screenshotKeys.has(key)) ||
        (keys !== base && keys !== 'artifactPath,bytes,format,height,screenshotId,width' && response.data.backend === undefined) ||
        (response.data.backend !== undefined && !['virtual_display', 'cdp'].includes(response.data.backend)) ||
        ((response.data.primaryBackend === undefined) !== (response.data.primaryFailure === undefined)) ||
        (response.data.primaryBackend !== undefined && (response.data.primaryBackend !== 'virtual_display' || typeof response.data.primaryFailure !== 'string' || !/^[A-Z][A-Z0-9_]{0,63}$/.test(response.data.primaryFailure))) ||
        ((response.data.fallbackBackend === undefined) !== (response.data.fallbackFailure === undefined)) ||
        (response.data.fallbackBackend !== undefined && (response.data.backend !== 'cdp' || response.data.fallbackBackend !== 'virtual_display' || typeof response.data.fallbackFailure !== 'string' || !/^[A-Z][A-Z0-9_]{0,63}$/.test(response.data.fallbackFailure))) ||
        (response.data.screenshotId !== undefined &&
          (typeof response.data.screenshotId !== 'string' || !/^[a-f0-9]{32}$/.test(response.data.screenshotId))) ||
        !Number.isSafeInteger(response.data.width) || response.data.width < 1 ||
        !Number.isSafeInteger(response.data.height) || response.data.height < 1 ||
        !Number.isSafeInteger(response.data.bytes) || response.data.bytes < 1 || response.data.format !== 'png') return null
  }
  if (method === 'click' && response.code === 'OK' && response.executed === true && args.screenshotId !== undefined) {
    const data = response.data
    if (Object.keys(data).sort().join(',') !== 'bitmapX,bitmapY,cssX,cssY,screenshotId,targetKind' ||
        data.screenshotId !== args.screenshotId || data.bitmapX !== args.x || data.bitmapY !== args.y ||
        !Number.isFinite(data.cssX) || !Number.isFinite(data.cssY) || data.targetKind !== 'DOM_HTML') return null
  }
  if (method === 'observe' && (hasAnyKey(response.data, ['scopeCoverage', 'frameScopes', 'topFrameOnly', 'openShadowRootsSupported',
      'closedShadowRootsSupported', 'crossOriginFramesSupported']) ||
      (Array.isArray(response.data.elements) && response.data.elements.some((element) =>
        element && typeof element === 'object' && !Array.isArray(element) && hasAnyKey(element, ['frameOrdinal', 'shadowDepth']))))) {
    const allowed = ['version', 'requestId', 'code', 'executed', 'sessionGeneration', 'controlGeneration', 'state', 'owner', 'data']
    if (Object.keys(response).some((key) => !allowed.includes(key))) return null
  }
  if (method === 'network' && action === 'frames' && response.code === 'OK') {
    const allowed = ['version', 'requestId', 'code', 'executed', 'sessionGeneration', 'controlGeneration', 'state', 'owner', 'data']
    if (Object.keys(response).some((key) => !allowed.includes(key)) || response.executed !== false ||
        !validNetworkFramesData(response.data) || Buffer.byteLength(JSON.stringify(response), 'utf8') > RESPONSE_LIMIT) return null
  }
  if (method === 'network' && action === 'export_frames' && response.code === 'OK' &&
      (response.executed !== true || !validNetworkFrameExport(response.data))) return null
  if (method === 'network' && action === 'replay_prepare' && response.code === 'OK' &&
      (response.executed !== true || !validReplayPrepare(response.data))) return null
  if (method === 'network' && action === 'replay_status' && response.code === 'OK' &&
      (response.executed !== false || !validReplayStatus(response.data, response, args))) return null
  if (method === 'network' && action === 'replay' && response.code === 'OK' &&
      (response.executed !== true || !validNetworkReplayResult(response.data))) return null
  if (method === 'file') {
    const allowed = ['version', 'requestId', 'code', 'executed', 'sessionGeneration', 'controlGeneration', 'state', 'owner', 'data']
    if (Object.keys(response).some((key) => !allowed.includes(key)) || !validFileData(action, response.code, response.data)) return null
    if (response.code === 'OK' && response.executed !== (action === 'start' || action === 'cancel')) return null
  }
  if (method === 'upload') {
    const allowed = ['version', 'requestId', 'code', 'executed', 'sessionGeneration', 'controlGeneration', 'state', 'owner', 'data']
    if (Object.keys(response).some((key) => !allowed.includes(key)) || !validUploadData(action, response.code, response.executed, response.data, args)) return null
  }
  if (method === 'page' && response.code === 'OK' && !validPageData(response.data)) return null
  if (method === 'wait' && ['OK', 'WAIT_TIMEOUT', 'CANCELLED', 'SESSION_REVOKED', 'STALE_SESSION', 'NO_ACTIVE_SESSION', 'AUTOMATION_DISABLED', 'INVALID_REQUEST', 'SESSION_REQUIRED', 'NAVIGATION_CHANGED', 'NAVIGATION_FAILED', 'INVALID_SELECTOR', 'WAIT_PROBE_LIMIT'].includes(response.code)) {
    const data = response.data
    if (!validConditionWaitCancellation(response) || !validWaitScope(data, action)) return null
    const scopeKeys = data.waitScope === undefined ? [] : ['waitScope']
    const diagnosticKeys = data.stage !== undefined ? ['stage', 'cancellationReason', 'nextAction', 'automaticRetryAllowed'] : []
    const keys = Object.keys(data).sort().join(',')
    if (response.code === 'CANCELLED' && keys === '' && response.executed === false) return response
    if (['SESSION_REVOKED', 'STALE_SESSION', 'NO_ACTIVE_SESSION', 'AUTOMATION_DISABLED', 'INVALID_REQUEST', 'SESSION_REQUIRED'].includes(response.code) &&
        keys === '' && response.executed === false) return response
    const common = ['condition', 'matched', 'pageId']
    const navigationKeys = data.committed !== undefined ? NAVIGATION_METADATA_KEYS : []
    const snapshotKeys = [...common, 'documentGeneration', 'loadState', 'navigationId', 'url', ...navigationKeys, ...diagnosticKeys, ...scopeKeys].sort().join(',')
    const cancelledKeys = ['condition', 'matched', 'reason'].sort().join(',')
    const fullCancelledKeys = [...common, 'documentGeneration', 'loadState', 'navigationId', 'url', 'reason', ...navigationKeys, ...diagnosticKeys, ...scopeKeys].sort().join(',')
    if (response.code === 'CANCELLED' && (keys === cancelledKeys || keys === fullCancelledKeys)) {
      const snapshotValid = keys === cancelledKeys || (
        validPageReference(data.pageId) && (data.navigationId === null || (Number.isSafeInteger(data.navigationId) && data.navigationId >= 0)) &&
        (data.documentGeneration === null || (Number.isSafeInteger(data.documentGeneration) && data.documentGeneration >= 0)) &&
        validSanitizedUrl(data.url) && ['ACCEPTED', 'LOADING', 'FINISHED', 'FAILED', 'unknown'].includes(data.loadState)
      )
      if (!snapshotValid || data.matched !== false || data.condition !== action || data.reason !== 'cancelled' || response.executed !== false) return null
    } else if (keys !== snapshotKeys || typeof data.matched !== 'boolean' || data.condition !== action || !validPageReference(data.pageId) ||
        !(data.navigationId === null || (Number.isSafeInteger(data.navigationId) && data.navigationId >= 0)) ||
        !(data.documentGeneration === null || (Number.isSafeInteger(data.documentGeneration) && data.documentGeneration >= 0)) ||
        !validSanitizedUrl(data.url) || ['ACCEPTED', 'LOADING', 'FINISHED', 'FAILED', 'unknown'].includes(data.loadState) === false ||
        ((response.code === 'OK') !== data.matched) || response.executed !== (response.code === 'OK') ||
        (['NAVIGATION_CHANGED', 'NAVIGATION_FAILED', 'INVALID_SELECTOR', 'SESSION_REVOKED', 'STALE_SESSION'].includes(response.code) && data.matched)) return null
  }
  if (method === 'navigate' && ['OK', 'NO_HISTORY'].includes(response.code)) {
    if (response.code === 'NO_HISTORY') {
      const data = response.data
      if (Object.keys(data).sort().join(',') !== 'accepted,action,canGoBack,canGoForward' || data.action !== action ||
          data.accepted !== false || typeof data.canGoBack !== 'boolean' || typeof data.canGoForward !== 'boolean' || response.executed !== false) return null
    } else {
      const data = response.data
      const expectedKeys = ['accepted', 'action', 'navigationId', 'pageId', 'url',
        ...(data.committed !== undefined ? NAVIGATION_METADATA_KEYS : []), ...(data.thumbnail !== undefined ? ['thumbnail'] : [])].sort().join(',')
      if (Object.keys(data).sort().join(',') !== expectedKeys || data.action !== action ||
          data.accepted !== true || !validPageReference(data.pageId) ||
          !(data.navigationId === null || (Number.isSafeInteger(data.navigationId) && data.navigationId >= 0)) ||
          !validSanitizedUrl(data.url) || response.executed !== true) return null
    }
  }
  if (method === 'locate') {
    const data = response.data
    const partial = Object.prototype.hasOwnProperty.call(data, 'partial')
    if (partial && (args.partial !== true || data.partial !== true ||
        !['OK', 'NOT_FOUND', 'AMBIGUOUS', 'LOCATE_LIMIT'].includes(response.code) || data.scopeCoverage?.complete !== false ||
        !validLocateScopeMetadata(data, response.code === 'OK'))) return null
    if (!partial && ['OK', 'NOT_FOUND', 'AMBIGUOUS'].includes(response.code) && data.scopeCoverage?.complete === false) return null
    const keys = Object.keys(data).filter(key => key !== 'partial').sort().join(',')
    const envelopeKeys = ['version', 'requestId', 'code', 'executed', 'sessionGeneration', 'controlGeneration', 'state', 'owner', 'data']
    if (Object.keys(response).some((key) => !envelopeKeys.includes(key))) return null
    if (response.code === 'OK') {
      const hasMetadata = hasAnyKey(data, ['frameOrdinal', 'shadowDepth', 'scopeCoverage', 'frameScopes'])
      const expectedKeys = hasMetadata
        ? 'count,documentGeneration,frameOrdinal,frameScopes,observationId,pageId,ref,scopeCoverage,shadowDepth,strategy'
        : 'count,documentGeneration,observationId,pageId,ref,strategy'
      if (keys !== expectedKeys || response.executed !== true ||
          data.count !== 1 || data.strategy !== action ||
          typeof data.ref !== 'string' || !/^b:[1-9][0-9]{0,15}:[1-9][0-9]{0,15}:[0-9]{1,16}:[1-9][0-9]{0,15}:[a-f0-9]{32}:(?:[0-9]|[1-7][0-9])$/.test(data.ref) ||
          typeof data.pageId !== 'string' || !/^pg-[A-Fa-f0-9]{20}$/.test(data.pageId) ||
          !Number.isSafeInteger(data.documentGeneration) || data.documentGeneration < 0 ||
          !Number.isSafeInteger(data.observationId) || data.observationId < 1 || !validLocateScopeMetadata(data, true)) return null
    } else if (['NOT_FOUND', 'AMBIGUOUS', 'LOCATE_LIMIT', 'INCOMPLETE_SCOPE', 'INVALID_SELECTOR', 'STALE_REFERENCE', 'EXECUTION_FAILED'].includes(response.code)) {
      if (response.executed !== false) return null
      const hasMetadata = hasAnyKey(data, ['frameOrdinal', 'shadowDepth', 'scopeCoverage', 'frameScopes'])
      if (response.code === 'INCOMPLETE_SCOPE') {
        if (keys !== 'count,frameScopes,scopeCoverage' || !Number.isSafeInteger(data.count) || data.count < 0 || data.count > 5000 ||
            !validLocateScopeMetadata(data, false) || data.scopeCoverage.complete !== false) return null
        return response
      }
      if (hasMetadata && (Object.prototype.hasOwnProperty.call(data, 'frameOrdinal') ||
          Object.prototype.hasOwnProperty.call(data, 'shadowDepth') || !validLocateScopeMetadata(data, false))) return null
      if (hasMetadata && ['NOT_FOUND', 'AMBIGUOUS'].includes(response.code) && !data.scopeCoverage.complete && !partial) return null
      if (keys === '' && response.code === 'STALE_REFERENCE') return response
      if (response.code === 'AMBIGUOUS') {
        const expectedKeys = hasMetadata ? 'count,frameScopes,matches,scopeCoverage' : 'count,matches'
        if (keys !== expectedKeys || !Number.isSafeInteger(data.count) || data.count < 2 || data.count > 5000 ||
            !Array.isArray(data.matches) || data.matches.length < 1 || data.matches.length > 5 ||
            !data.matches.every((match) => match && typeof match === 'object' && !Array.isArray(match) &&
              Object.keys(match).sort().join(',') === (hasMetadata ? 'frameOrdinal,name,role,shadowDepth,tag' : 'name,role,tag') &&
              typeof match.tag === 'string' && match.tag.length >= 1 && match.tag.length <= 128 &&
              typeof match.role === 'string' && match.role.length <= 128 &&
              typeof match.name === 'string' && match.name.length <= 100 && (!hasMetadata ||
                (Number.isSafeInteger(match.frameOrdinal) && match.frameOrdinal >= 0 && match.frameOrdinal <= 16 &&
                 Number.isSafeInteger(match.shadowDepth) && match.shadowDepth >= 0 && match.shadowDepth <= 32 &&
                 (match.frameOrdinal === 0 || data.frameScopes.some((scope) =>
                   scope.ordinal === match.frameOrdinal && scope.status === 'accessible')))))) return null
        return response
      }
      const expectedKeys = hasMetadata ? 'count,frameScopes,scopeCoverage' : 'count'
      if (keys !== expectedKeys || !Number.isSafeInteger(data.count) || data.count < 0 || data.count > 5001 ||
          (response.code === 'NOT_FOUND' && data.count !== 0) ||
          (response.code === 'INVALID_SELECTOR' && data.count !== 0) ||
          (response.code === 'STALE_REFERENCE' && data.count !== 0)) return null
    } else if (['CLOSED', 'AUTOMATION_DISABLED', 'USER_CONTROLLED', 'STALE_SESSION', 'DEADLINE_EXPIRED',
      'PAGE_NOT_READY', 'BUSY_OR_STALE', 'INVALID_ARGUMENT', 'SESSION_REQUIRED'].includes(response.code)) {
      if (response.executed !== false || keys !== '') return null
    }
  }
  if (response.data.artifactPath !== undefined && !validArtifactReference(response.data.artifactPath, method, action)) return null
  if (response.data.base64 !== undefined) return null
  if (response.code === 'OK' && method === 'network' && action === 'export_frames' && !validNetworkFrameExport(response.data)) return null
  if (response.code === 'OK' && ((method === 'network' && ['body', 'har', 'export_frames'].includes(action)) ||
      (method === 'selection' && ['latest', 'consume', 'pending'].includes(action))) &&
      !validArtifactReference(response.data.artifactPath, method, action)) return null
  return response
}

function validPageData(data) {
  if (!data || Object.keys(data).sort().join(',') !== 'activePageId,limit,pages' ||
      !Array.isArray(data.pages) || data.pages.length < 1 || data.pages.length > 4 || data.limit !== 4 ||
      typeof data.activePageId !== 'string' || !/^pg-[A-Fa-f0-9]{20}$/.test(data.activePageId)) return false
  const ids = new Set()
  let activeCount = 0
  for (const page of data.pages) {
    if (!page || typeof page !== 'object' || Array.isArray(page) ||
        Object.keys(page).sort().join(',') !== 'active,pageId,title,url' ||
        typeof page.pageId !== 'string' || !/^pg-[A-Fa-f0-9]{20}$/.test(page.pageId) || ids.has(page.pageId) ||
        typeof page.title !== 'string' || page.title.length > 256 || typeof page.url !== 'string' ||
        page.url.length > 2048 || typeof page.active !== 'boolean') return false
    ids.add(page.pageId)
    if (page.active) activeCount += 1
    if (page.url === 'about:blank') continue
    let url
    try { url = new URL(page.url) } catch { return false }
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || url.pathname !== '/') return false
  }
  return ids.has(data.activePageId) && activeCount === 1 && data.pages.some((page) => page.pageId === data.activePageId && page.active)
}

function resultFrom(response, localCode = '', localText = '', resolvedPath = '') {
  const data = response && response.data && typeof response.data === 'object' ? response.data : {}
  const definitelyNotExecuted = new Set(['INVALID_ARGUMENT', 'LIMIT_EXCEEDED', 'INVALID_DEADLINE', 'CLI_UNAVAILABLE', 'CLI_USAGE', 'CANCELLED'])
  const executed = response
    ? (response.executed === true ? 'true' : response.executed === false ? 'false' : 'unknown')
    : (definitelyNotExecuted.has(localCode) ? 'false' : 'unknown')
  const code = localCode || (response && response.code) || 'UNKNOWN'
  const artifactPath = typeof data.artifactPath === 'string' ? data.artifactPath : ''
  const ok = !localCode && code === 'OK'
  const state = response && typeof response.state === 'string' ? response.state : ''
  const text = localText || (state === 'UNKNOWN' || code === 'UNKNOWN'
    ? 'The result is unknown; the action may have run. Do not submit it again. Check browser_status first.'
    : (ok ? 'Browser request completed.' : `Browser request was not completed (${code}).`))
  return {
    ok,
    code,
    text,
    requestId: response && typeof response.requestId === 'string' ? response.requestId : '',
    executed,
    sessionGeneration: response && Number.isSafeInteger(response.sessionGeneration) ? response.sessionGeneration : 0,
    controlGeneration: response && Number.isSafeInteger(response.controlGeneration) ? response.controlGeneration : 0,
    sessionGenerationAvailable: !!response && Number.isSafeInteger(response.sessionGeneration) && response.sessionGeneration > 0,
    controlGenerationAvailable: !!response && Number.isSafeInteger(response.controlGeneration) && response.controlGeneration > 0,
    state,
    dataJson: JSON.stringify(data),
    artifactPath,
    path: resolvedPath
  }
}

function browserRequest(method, args = {}, envelope = {}, dependencies = {}) {
  if (dependencies.signal && dependencies.signal.aborted) return Promise.resolve(resultFrom(null, 'CANCELLED', 'Browser operation was cancelled before dispatch.'))
  if (!METHODS.has(method)) return Promise.resolve(resultFrom(null, 'INVALID_ARGUMENT', 'Unsupported Browser method.'))
  const argError = validateArgs(method, args)
  if (argError) return Promise.resolve(resultFrom(null, 'INVALID_ARGUMENT', argError))
  const controlRequest = method === 'control'
  if (controlRequest && (envelope.sessionGeneration != null || envelope.controlGeneration != null || envelope.owner != null || envelope.taskId != null)) {
    return Promise.resolve(resultFrom(null, 'INVALID_ARGUMENT', 'control requests do not accept Browser generations, owner, or top-level taskId.'))
  }
  const eventCorrelatedAction = ['click', 'navigate'].includes(method) && args.eventSubscriptionId !== undefined
  if ((method === 'event' || method === 'upload' || eventCorrelatedAction) &&
      (typeof envelope.taskId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(envelope.taskId))) {
    return Promise.resolve(resultFrom(null, 'INVALID_ARGUMENT', 'This event action requires an explicit taskId envelope.'))
  }
  if (method === 'open' && ((envelope.sessionGeneration == null) !== (envelope.controlGeneration == null))) {
    return Promise.resolve(resultFrom(null, 'INVALID_ARGUMENT', 'open requires both generations for an active session, or neither for a closed session'))
  }
  if (method === 'open' && envelope.sessionGeneration != null &&
      (envelope.sessionGeneration === 0 || envelope.controlGeneration === 0)) {
    return Promise.resolve(resultFrom(null, 'INVALID_ARGUMENT', 'active Browser generations must be positive'))
  }
  const taskRequiresControl = method === 'task' && ['begin', 'complete', 'reconcile', 'resume'].includes(args.action)
  const sessionError = controlRequest ? '' : validateGeneration(envelope.sessionGeneration, 'sessionGeneration', !['status', 'open', 'task'].includes(method) || taskRequiresControl)
  if (sessionError) return Promise.resolve(resultFrom(null, 'INVALID_ARGUMENT', sessionError))
  const pageList = method === 'page' && args.action === 'list'
  const fileReadOnly = method === 'file' && ['list', 'status'].includes(args.action)
  const controlMethod = CONTROL_METHODS.has(method) && !fileReadOnly
  const controlRequired = !controlRequest && ((controlMethod && !pageList && !(method === 'open' && envelope.sessionGeneration == null && envelope.controlGeneration == null)) || taskRequiresControl)
  const controlError = validateGeneration(envelope.controlGeneration, 'controlGeneration', controlRequired)
  if (controlError) return Promise.resolve(resultFrom(null, 'INVALID_ARGUMENT', controlError))
  if (!controlRequest && ((controlMethod && !pageList) || taskRequiresControl) && envelope.owner != null && envelope.owner !== 'ai') {
    return Promise.resolve(resultFrom(null, 'INVALID_ARGUMENT', 'owner must be ai'))
  }
  if (!controlRequest && ((controlMethod && !pageList) || taskRequiresControl) && method !== 'open' && envelope.owner !== 'ai') {
    return Promise.resolve(resultFrom(null, 'INVALID_ARGUMENT', 'AI ownership is required'))
  }
  if (!controlRequest && (method === 'selection' || pageList) && (envelope.controlGeneration != null || envelope.owner != null)) {
    return Promise.resolve(resultFrom(null, 'INVALID_ARGUMENT', 'selection accepts only a session generation'))
  }

  const now = typeof dependencies.now === 'function' ? dependencies.now() : Date.now()
  const budget = method === 'wait'
    ? Math.min(MAX_DEADLINE_MS, (args.timeoutMs ?? 10000) + 5000)
    : method === 'event' && args.action === 'wait'
      ? Math.min(MAX_DEADLINE_MS, (args.timeoutMs ?? 15000) + 1000)
    : (METHOD_BUDGET_MS[method] || 10000)
  const deadlineAtMillis = now + budget
  const requestId = typeof dependencies.requestId === 'function' ? dependencies.requestId() : randomUUID()
  const request = {
    version: 1,
    requestId,
    method,
    deadlineAtMillis,
    args: { ...args }
  }
  if (envelope.taskId != null) {
    if (method === 'task' || typeof envelope.taskId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(envelope.taskId)) {
      return Promise.resolve(resultFrom(null, 'INVALID_ARGUMENT', 'taskId is invalid for this Browser request.'))
    }
    if (method === 'open' && envelope.sessionGeneration == null) {
      return Promise.resolve(resultFrom(null, 'TASK_PAGE_MISMATCH', 'A closed Browser open cannot be attached to a task before its page identity exists.'))
    }
    request.taskId = envelope.taskId
  }
  if (envelope.sessionGeneration != null) request.sessionGeneration = envelope.sessionGeneration
  if (controlRequired || (method === 'open' && envelope.controlGeneration != null)) request.controlGeneration = envelope.controlGeneration
  if ((controlMethod && !pageList) || taskRequiresControl) request.owner = 'ai'
  if (Buffer.byteLength(JSON.stringify(request), 'utf8') > REQUEST_LIMIT) {
    return Promise.resolve(resultFrom(null, 'LIMIT_EXCEEDED', 'Browser request exceeds the 16 KiB limit.'))
  }

  const execute = dependencies.execFile || nodeExecFile
  const remaining = deadlineAtMillis - (typeof dependencies.now === 'function' ? dependencies.now() : Date.now())
  if (remaining <= 0 || remaining > MAX_DEADLINE_MS) return Promise.resolve(resultFrom(null, 'INVALID_DEADLINE', 'Browser request deadline is invalid.'))
  return new Promise((resolve) => {
    let settled = false
    let requestChild
    const finish = (value) => {
      if (settled) return
      settled = true
      if (dependencies.signal) dependencies.signal.removeEventListener('abort', onAbort)
      resolve(value)
    }
    const onAbort = () => {
      if (method !== 'wait' && !(method === 'event' && args.action === 'wait')) {
        finish(resultFrom({
          code: 'UNKNOWN', executed: null, state: 'RUNNING',
          data: cancellationUnknownData(null),
        }, '', 'The Browser result is unknown after cancellation. Do not submit the action again.'))
        return
      }
      execute(dependencies.cli || CLI, ['cancel', '--request-id', requestId], {
        encoding: 'utf8', timeout: 2_500, maxBuffer: RESPONSE_LIMIT + 1, windowsHide: true
      }, (cancelError, cancelStdout) => {
        const cancellation = safeResponse(cancelStdout, requestId, 'cancel')
        if (cancellation) return
        if (requestChild && requestChild.exitCode == null && requestChild.signalCode == null) {
          try { requestChild.kill('SIGKILL') } catch {}
        }
        finish(resultFrom({
          code: 'UNKNOWN', executed: null, state: 'RUNNING',
          data: cancellationUnknownData(cancelError ? null : { data: { reason: 'invalid_runtime_receipt' } }),
        }, '', 'The Browser wait cancellation receipt was invalid. Do not repeat automatically.'))
      })
    }
    if (dependencies.signal) {
      if (dependencies.signal.aborted) {
        finish(resultFrom(null, 'CANCELLED', 'Browser wait was cancelled before dispatch.'))
        return
      }
      dependencies.signal.addEventListener('abort', onAbort, { once: true })
    }
    requestChild = execute(dependencies.cli || CLI, [method, '--request-json', JSON.stringify(request)], {
      encoding: 'utf8', timeout: remaining + 1500, maxBuffer: RESPONSE_LIMIT + 1, windowsHide: true
    }, (error, stdout) => {
      if (settled) return
      const response = safeResponse(stdout, requestId, method, args.condition || args.action || args.strategy, args)
      if (response) {
        const data = response.data || {}
        const reportedArtifactPath = method === 'file' ? data.item && data.item.artifactPath : data.artifactPath
        if (response.code === 'OK' && typeof reportedArtifactPath === 'string') {
          const artifactPath = resolveArtifactPath(reportedArtifactPath, method, request.args.action)
          if (!artifactPath) return finish(resultFrom(response, 'ARTIFACT_UNAVAILABLE', 'The Browser artifact could not be verified under its fixed output directory. Do not repeat automatically.'))
          return finish(resultFrom(response, '', '', artifactPath))
        }
        return finish(resultFrom(response))
      }
      if (error && (error.code === 'ENOENT' || error.code === 'EACCES')) {
        return finish(resultFrom(null, 'CLI_UNAVAILABLE', 'The Browser command is unavailable.'))
      }
      const isUnknown = !!error && (error.code === 'ETIMEDOUT' || error.killed === true || error.code === 4 ||
        error.code === 'ERR_CHILD_PROCESS_STDIO_MAXBUFFER' || (controlMethod && error.code !== 2 && error.code !== 3))
      if (isUnknown) {
        const originalFailure = error.code === 'ETIMEDOUT' || error.killed === true || error.code === 4
          ? 'timeout'
          : error.code === 'ERR_CHILD_PROCESS_STDIO_MAXBUFFER' ? 'buffer_limit'
            : error.signal ? 'signal' : 'transport'
        return finish(resultFrom({
          code: 'UNKNOWN', executed: null, state: 'UNKNOWN',
          data: {
            cancelStage: 'transport', reason: 'transport_unavailable', actionOutcome: 'unknown',
            automaticRetryAllowed: false, requestId, stage: 'transport', dispatched: 'unknown', originalFailure,
          },
        }, '', 'The result is unknown; do not submit the action again. Check browser_status first.'))
      }
      if (error && error.code === 3) return finish(resultFrom(null, 'CLI_UNAVAILABLE', 'The Browser command could not reach its runtime.'))
      if (error && error.code === 2) return finish(resultFrom(null, 'CLI_USAGE', 'The Browser command rejected the request format.'))
      finish(resultFrom(null, 'PROTOCOL_ERROR', 'The Browser command returned an invalid response.'))
    })
  })
}

function resolveArtifactPath(artifactPath, method, action) {
  const rules = {
    screenshot: ['/opt/browser/out/shot', /^[A-Za-z0-9_.-]+\.png$/],
    network: ['/opt/browser/out/network', /^[A-Za-z0-9_.-]{1,160}$/],
    selection: ['/opt/browser/out/selection', /^[A-Za-z0-9_.-]+\.png$/],
    file: ['/opt/browser/out/downloads', /^[a-f0-9]{32}_[A-Za-z0-9_.-]{1,180}$/]
  }
  const rule = rules[method]
  const allowed = method === 'screenshot' || (method === 'network' && ['body', 'har'].includes(action)) ||
    (method === 'file' && ['start', 'status', 'cancel', 'list'].includes(action)) ||
    (method === 'selection' && ['latest', 'consume', 'pending'].includes(action))
  if (!allowed || typeof artifactPath !== 'string' || !artifactPath.startsWith(rule[0] + '/')) return ''
  const name = artifactPath.slice((rule[0] + '/').length)
  if (!rule[1].test(name) || name.includes('..') || name.includes('/')) return ''
  const base = path.posix.resolve(rule[0])
  const candidate = path.posix.resolve(artifactPath)
  try {
    const baseStat = fs.lstatSync(base)
    const candidateStat = fs.lstatSync(candidate)
    if (!baseStat.isDirectory() || baseStat.isSymbolicLink() || !candidateStat.isFile() || candidateStat.isSymbolicLink()) return ''
    const realBase = fs.realpathSync(base)
    const realCandidate = fs.realpathSync(candidate)
    const relative = path.posix.relative(realBase, realCandidate)
    if (!relative || relative === '..' || relative.startsWith('../') || path.posix.isAbsolute(relative)) return ''
    return realCandidate
  } catch {
    return ''
  }
}

function resolveScreenshotPath(artifactPath) {
  return resolveArtifactPath(artifactPath, 'screenshot', '')
}

module.exports = {
  CLI,
  REQUEST_LIMIT,
  RESPONSE_LIMIT,
  browserRequest,
  resolveArtifactPath,
  resolveScreenshotPath,
  safeResponse,
  validDownloadItem,
  validFileData,
  validateArgs,
  validateGeneration
}
