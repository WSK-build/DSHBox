'use strict';

const fs = require('node:fs');
const net = require('node:net');
const crypto = require('node:crypto');
const { runServeCommand, runServeChild, parseServeArguments } = require('./browser-serve.cjs');

const MAX_REQUEST_FRAME_BYTES = 16 * 1024;
const MAX_FRAME_BYTES = 256 * 1024;
const MAX_BUDGET_MS = 60_000;
const MAX_DOWNLOAD_BYTES = 25 * 1024 * 1024;
const CHANNEL_FILE = '/opt/browser/run/channel.json';
const PRECONNECT_FAILURE = Symbol('preconnectFailure');
const METHODS = new Set(['status', 'open', 'observe', 'wait', 'navigate', 'locate', 'click', 'type', 'scroll', 'screenshot', 'close', 'debug', 'network', 'selection', 'page', 'file', 'upload', 'task', 'collect', 'event', 'control']);
const NODE_ID = /^[A-Za-z0-9_.:-]{1,128}$/;
const OBSERVATION_REF = /^b:[1-9][0-9]{0,15}:[1-9][0-9]{0,15}:[0-9]{1,16}:[1-9][0-9]{0,15}:[a-f0-9]{32}:(?:[0-9]|[1-7][0-9])$/;
const SAFE_ID = /^[A-Za-z0-9_.:-]{1,128}$/;

function ownObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value) &&
    (Object.getPrototypeOf(value) === Object.prototype || Object.getPrototypeOf(value) === null);
}

function exactKeys(value, allowed) {
  return Object.keys(value).every((key) => allowed.has(key));
}

function exactObjectKeys(value, keys) {
  return ownObject(value) && Object.keys(value).length === keys.size && exactKeys(value, keys);
}

const SCOPE_COVERAGE_KEYS = new Set([
  'complete', 'scannedElements', 'frameCount', 'shadowRootCount', 'inaccessibleFrameCount',
  'frameLimitReached', 'shadowLimitReached', 'elementLimitReached', 'textNodesVisited', 'textLimitReached',
]);
const FRAME_SCOPE_KEYS = new Set(['ordinal', 'parentOrdinal', 'depth', 'status', 'reason']);
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
});

function validScopeCoverage(value) {
  if (!exactObjectKeys(value, SCOPE_COVERAGE_KEYS) || typeof value.complete !== 'boolean' ||
      !Number.isSafeInteger(value.scannedElements) || value.scannedElements < 0 || value.scannedElements > 5000 ||
      !Number.isSafeInteger(value.frameCount) || value.frameCount < 0 || value.frameCount > 16 ||
      !Number.isSafeInteger(value.shadowRootCount) || value.shadowRootCount < 0 || value.shadowRootCount > 32 ||
      !Number.isSafeInteger(value.inaccessibleFrameCount) || value.inaccessibleFrameCount < 0 || value.inaccessibleFrameCount > 16 ||
      !Number.isSafeInteger(value.textNodesVisited) || value.textNodesVisited < 0 || value.textNodesVisited > 20000 ||
      !['frameLimitReached', 'shadowLimitReached', 'elementLimitReached', 'textLimitReached']
        .every((key) => typeof value[key] === 'boolean')) return false;
  const complete = value.inaccessibleFrameCount === 0 && !value.frameLimitReached &&
    !value.shadowLimitReached && !value.elementLimitReached;
  return value.complete === complete;
}

function validFrameScopes(value, coverage) {
  if (!Array.isArray(value) || value.length !== coverage.frameCount || value.length > 16) return false;
  const byOrdinal = new Map();
  let inaccessible = 0;
  for (let index = 0; index < value.length; index++) {
    const scope = value[index];
    if (!exactObjectKeys(scope, FRAME_SCOPE_KEYS) || scope.ordinal !== index + 1 ||
        !Number.isSafeInteger(scope.parentOrdinal) || scope.parentOrdinal < 0 || scope.parentOrdinal >= scope.ordinal ||
        !Number.isSafeInteger(scope.depth) || scope.depth < 1 || scope.depth > 4 ||
        !['accessible', 'inaccessible'].includes(scope.status)) return false;
    if (scope.status === 'accessible') {
      if (scope.reason !== null) return false;
    } else {
      if (!['CROSS_ORIGIN_OR_OPAQUE', 'FRAME_LIMIT'].includes(scope.reason)) return false;
      inaccessible++;
      if (scope.reason === 'FRAME_LIMIT' && !coverage.frameLimitReached) return false;
    }
    if (scope.parentOrdinal === 0) {
      if (scope.depth !== 1) return false;
    } else {
      const parent = byOrdinal.get(scope.parentOrdinal);
      if (!parent || parent.status !== 'accessible' || parent.depth + 1 !== scope.depth) return false;
    }
    byOrdinal.set(scope.ordinal, scope);
  }
  return inaccessible === coverage.inaccessibleFrameCount;
}

function hasAnyKey(value, keys) {
  return keys.some((key) => Object.prototype.hasOwnProperty.call(value, key));
}

function validLocateScopeMetadata(data, includeTarget) {
  const keys = includeTarget
    ? ['frameOrdinal', 'shadowDepth', 'scopeCoverage', 'frameScopes']
    : ['scopeCoverage', 'frameScopes'];
  const any = hasAnyKey(data, ['frameOrdinal', 'shadowDepth', 'scopeCoverage', 'frameScopes']);
  if (!any) return true;
  if (!keys.every((key) => Object.prototype.hasOwnProperty.call(data, key))) return false;
  if (!validScopeCoverage(data.scopeCoverage) || !validFrameScopes(data.frameScopes, data.scopeCoverage)) return false;
  if (!includeTarget) return true;
  if (!Number.isSafeInteger(data.frameOrdinal) || data.frameOrdinal < 0 || data.frameOrdinal > 16 ||
      !Number.isSafeInteger(data.shadowDepth) || data.shadowDepth < 0 || data.shadowDepth > 32) return false;
  return !data.scopeCoverage.textLimitReached && (data.frameOrdinal === 0 || data.frameScopes.some((scope) =>
    scope.ordinal === data.frameOrdinal && scope.status === 'accessible'));
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

const CANCEL_RECEIPT_STAGES = new Set(['queued', 'transport', 'capture_wait', 'event_wait', 'condition_wait', 'dispatch']);
const CANCEL_RECEIPT_REASONS = new Set([
  'cancel_accepted', 'cancel_before_admission', 'request_in_flight', 'request_not_found',
  'request_finished', 'transport_unavailable', 'invalid_runtime_receipt',
]);

function validCancelReceipt(value, requestId) {
  if (!ownObject(value) || value.version !== 1 || value.requestId !== requestId ||
      typeof value.code !== 'string' || !/^[A-Z][A-Z0-9_]{0,63}$/.test(value.code) ||
      value.executed !== null || !ownObject(value.data)) return false;
  const publicKeys = ['version', 'requestId', 'code', 'executed', 'sessionGeneration',
    'controlGeneration', 'state', 'owner', 'data'];
  if (Object.keys(value).some(key => !publicKeys.includes(key)) ||
      !(value.state === null && value.code === 'UNKNOWN') && !['CLOSED', 'IDLE', 'RUNNING'].includes(value.state)) return false;
  for (const generation of [value.sessionGeneration, value.controlGeneration]) {
    if (generation !== null && (!Number.isSafeInteger(generation) || generation < 0)) return false;
  }
  const data = value.data;
  const fields = ['receiptKind', 'cancelStage', 'reason', 'actionOutcome', 'nextAction', 'automaticRetryAllowed'];
  return Object.keys(data).length === fields.length && fields.every(key => Object.hasOwn(data, key)) &&
    data.receiptKind === 'cancel_request' && CANCEL_RECEIPT_STAGES.has(data.cancelStage) &&
    CANCEL_RECEIPT_REASONS.has(data.reason) && data.actionOutcome === 'unknown' &&
    data.nextAction === 'read_original_request_result' && data.automaticRetryAllowed === false;
}

function legacyCancelReceipt(value, requestId) {
  return ownObject(value) && value.version === 1 && value.requestId === requestId &&
    ['CANCELLED', 'TOO_LATE', 'NOT_FOUND', 'CLOSED'].includes(value.code) &&
    value.executed === false && ownObject(value.data) && Object.keys(value.data).length === 0;
}

function cancellationUnknownData(value) {
  const receipt = value && ownObject(value.data) ? value.data : {};
  return {
    cancelStage: CANCEL_RECEIPT_STAGES.has(receipt.cancelStage) ? receipt.cancelStage : 'transport',
    reason: receipt.reason === 'invalid_runtime_receipt' ? 'invalid_runtime_receipt' : 'transport_unavailable',
    actionOutcome: 'unknown',
    automaticRetryAllowed: false,
  };
}

function validCancellationUnknownData(data) {
  const fields = ['cancelStage', 'reason', 'actionOutcome', 'automaticRetryAllowed'];
  return ownObject(data) && Object.keys(data).length === fields.length &&
    fields.every(key => Object.hasOwn(data, key)) && CANCEL_RECEIPT_STAGES.has(data.cancelStage) &&
    ['transport_unavailable', 'invalid_runtime_receipt'].includes(data.reason) &&
    data.actionOutcome === 'unknown' && data.automaticRetryAllowed === false;
}

function validTransportUnknownData(data, requestId) {
  const base = ['cancelStage', 'reason', 'actionOutcome', 'automaticRetryAllowed'];
  const fields = [...base, 'requestId', 'stage', 'dispatched', 'originalFailure'];
  return ownObject(data) && exactObjectKeys(data, new Set(fields)) &&
    validCancellationUnknownData(Object.fromEntries(base.map(key => [key, data[key]]))) &&
    data.cancelStage === 'transport' && data.requestId === requestId && data.stage === 'transport' &&
    data.dispatched === 'unknown' &&
    ['invalid_response', 'timeout', 'signal', 'buffer_limit', 'transport'].includes(data.originalFailure);
}

function validPreDispatchTerminalData(data) {
  const fields = ['cancelStage', 'reason', 'actionOutcome', 'automaticRetryAllowed'];
  return ownObject(data) && Object.keys(data).length === fields.length &&
    fields.every(key => Object.hasOwn(data, key)) && data.cancelStage === 'queued' &&
    data.reason === 'cancel_before_dispatch' && data.actionOutcome === 'not_dispatched' &&
    data.automaticRetryAllowed === false;
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
    const legacyKeys = new Set(['nodeId', 'tag', 'type', 'role', 'label', 'href',
      'hrefQueryRedacted', 'hrefFragmentRedacted', 'frameOrdinal', 'shadowDepth']);
    const currentKeys = new Set([...legacyKeys, 'accessibleName', 'placeholder']);
    const current = exactObjectKeys(e, currentKeys);
    if (!current && !exactObjectKeys(e, legacyKeys)) return false;
    if (current && (typeof e.accessibleName !== 'string' || e.accessibleName.length > 160 ||
        typeof e.placeholder !== 'string' || e.placeholder.length > 160)) return false;
    return typeof e.nodeId === 'string' && /^b:[1-9][0-9]{0,15}:[1-9][0-9]{0,15}:[0-9]{1,16}:[1-9][0-9]{0,15}:[a-f0-9]{32}:(?:[0-9]|[1-7][0-9])$/.test(e.nodeId) &&
    e.nodeId.split(':')[4] === String(data.observationGeneration) && e.nodeId.endsWith(':' + index) &&
    typeof e.tag === 'string' && e.tag.length > 0 && e.tag.length <= 128 && typeof e.type === 'string' && e.type.length <= 32 &&
    typeof e.role === 'string' && e.role.length <= 48 && typeof e.label === 'string' && e.label.length <= 160 &&
    typeof e.href === 'string' && (e.href === '' || validSanitizedUrl(e.href)) &&
    typeof e.hrefQueryRedacted === 'boolean' && typeof e.hrefFragmentRedacted === 'boolean' &&
    Number.isSafeInteger(e.frameOrdinal) && e.frameOrdinal >= 0 && e.frameOrdinal <= 16 &&
    Number.isSafeInteger(e.shadowDepth) && e.shadowDepth >= 0 && e.shadowDepth <= 32 &&
    (e.frameOrdinal === 0 || accessible.has(e.frameOrdinal));
  });
}
function validPageReference(value) {
  return value === null || (typeof value === 'string' && /^pg-[A-Fa-f0-9]{20}$/.test(value));
}

function validObservationReference(value) {
  return typeof value === 'string' && /^b:[1-9][0-9]{0,15}:[1-9][0-9]{0,15}:[0-9]{1,16}:[1-9][0-9]{0,15}:[a-f0-9]{32}:(?:[0-9]|[1-7][0-9])$/.test(value);
}

function validCollectData(data, args, sessionGeneration, controlGeneration) {
  if (!exactObjectKeys(data, new Set(['action', 'identity', 'containerNodeId', 'container', 'records', 'truncated', 'excludedCount', 'declaredRange'])) ||
      data.action !== 'window' || data.declaredRange !== args.declaredRange || typeof data.truncated !== 'boolean' ||
      !validObservationReference(data.containerNodeId) || !data.containerNodeId.endsWith(':0') ||
      !exactObjectKeys(data.identity, new Set(['pageId', 'documentGeneration', 'navigationId', 'observationId', 'sessionGeneration', 'controlGeneration'])) ||
      !/^pg-[A-Fa-f0-9]{20}$/.test(data.identity.pageId) || !Number.isSafeInteger(data.identity.documentGeneration) || data.identity.documentGeneration < 0 ||
      !Number.isSafeInteger(data.identity.navigationId) || data.identity.navigationId < 0 || !Number.isSafeInteger(data.identity.observationId) || data.identity.observationId < 1 ||
      data.identity.sessionGeneration !== sessionGeneration || data.identity.controlGeneration !== controlGeneration ||
      !exactObjectKeys(data.container, new Set(['scrollTop', 'scrollHeight', 'clientHeight', 'startReached', 'endReached', 'totalCount'])) ||
      !Number.isFinite(data.container.scrollTop) || !Number.isFinite(data.container.scrollHeight) || !Number.isFinite(data.container.clientHeight) ||
      data.container.scrollTop < 0 || data.container.scrollHeight <= data.container.clientHeight || data.container.clientHeight <= 0 ||
      data.container.scrollTop > data.container.scrollHeight || typeof data.container.startReached !== 'boolean' || typeof data.container.endReached !== 'boolean' ||
      !(data.container.totalCount === null || (Number.isSafeInteger(data.container.totalCount) && data.container.totalCount >= 0)) ||
      !Array.isArray(data.records) || data.records.length > args.maxRecords ||
      !Number.isSafeInteger(data.excludedCount) || data.excludedCount < 0 || data.excludedCount > 5000) return false;
  return data.records.every((record) => exactObjectKeys(record, new Set(['key', 'author', 'content', 'order'])) &&
    (record.key === null || (typeof record.key === 'string' && record.key.length <= 256 && !/[\u0000-\u001f\u007f]/.test(record.key))) &&
    (record.author === null || (typeof record.author === 'string' && record.author.length <= 128 && !/[\u0000-\u001f\u007f]/.test(record.author))) &&
    typeof record.content === 'string' && record.content.length <= args.maxContentChars &&
    (record.order === null || (Number.isSafeInteger(record.order) && Math.abs(record.order) <= Number.MAX_SAFE_INTEGER)));
}

function validSanitizedUrl(value) {
  if (value === null || value === 'about:blank') return true;
  if (typeof value !== 'string' || value.length > 2048) return false;
  try {
    const url = new URL(value);
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password && !url.search && !url.hash;
  } catch { return false; }
}

function safeGeneration(value, required, name) {
  if (value === undefined && !required) return;
  if (!Number.isSafeInteger(value) || value < 0) throw new Error(`${name} must be a nonnegative safe integer`);
}

function validateRequest(method, serialized, now = Date.now()) {
  if (typeof serialized !== 'string' || Buffer.byteLength(serialized, 'utf8') > MAX_REQUEST_FRAME_BYTES) {
    throw new Error('request exceeds the 16 KiB limit');
  }
  let request;
  try { request = JSON.parse(serialized); } catch { throw new Error('request must be valid JSON'); }
  if (!ownObject(request) || request.version !== 1 || request.method !== method) {
    throw new Error('request version or method does not match');
  }
  if (!exactKeys(request, new Set([
    'version', 'requestId', 'method', 'sessionGeneration', 'controlGeneration',
    'owner', 'deadlineAtMillis', 'args', 'taskId',
  ]))) throw new Error('request contains unsupported fields');
  if (request.taskId !== undefined && (typeof request.taskId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(request.taskId) || method === 'task')) {
    throw new Error('taskId is invalid');
  }
  if (method === 'event' && request.taskId === undefined) throw new Error('event actions require an explicit taskId');
  if (['click', 'navigate'].includes(method) && request.args && request.args.eventSubscriptionId !== undefined && request.taskId === undefined) {
    throw new Error('event-correlated actions require an explicit taskId');
  }
  if (method === 'open' && request.sessionGeneration === undefined && request.taskId !== undefined) {
    throw new Error('a cold open cannot be attached to a task before its page identity exists');
  }
  if (typeof request.requestId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(request.requestId)) {
    throw new Error('requestId is invalid');
  }
  if (!Number.isSafeInteger(request.deadlineAtMillis) || request.deadlineAtMillis <= now ||
      request.deadlineAtMillis - now > MAX_BUDGET_MS) {
    throw new Error('deadlineAtMillis must be within the next 60 seconds');
  }
  if (!ownObject(request.args)) throw new Error('args must be a JSON object');

  if (method === 'control') {
    const args = request.args;
    if (request.sessionGeneration !== undefined || request.controlGeneration !== undefined || request.owner !== undefined || request.taskId !== undefined) {
      throw new Error('control requests do not accept Browser generation, owner, or top-level taskId fields');
    }
    if (args.action === 'request') {
      if (!exactKeys(args, new Set(['action', 'summary', 'taskId'])) || typeof args.summary !== 'string' ||
          args.summary.trim().length < 1 || args.summary.length > 1000 || /[\u0000-\u001f\u007f]/.test(args.summary) ||
          (args.taskId !== undefined && (typeof args.taskId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(args.taskId)))) {
        throw new Error('control request requires a 1..1000 character summary and optional correlation taskId');
      }
    } else if (['status', 'cancel'].includes(args.action)) {
      if (!exactKeys(args, new Set(['action', 'approvalId'])) || typeof args.approvalId !== 'string' || !/^[a-f0-9]{32}$/.test(args.approvalId)) {
        throw new Error(`control ${args.action} requires a 32-character lowercase hexadecimal approvalId`);
      }
    } else throw new Error('control action must be request, status, or cancel');
    return request;
  }

  if (method === 'task') {
    if (request.taskId !== undefined) throw new Error('task management does not accept a taskId field');
    const args = request.args;
    const needsAiControl = ['begin', 'complete', 'reconcile', 'resume'].includes(args.action);
    if (needsAiControl) {
      if (request.owner !== 'ai' || !Number.isSafeInteger(request.sessionGeneration) || request.sessionGeneration < 1 ||
          !Number.isSafeInteger(request.controlGeneration) || request.controlGeneration < 1) {
        throw new Error(`task ${args.action} requires current AI generations`);
      }
    } else if (request.sessionGeneration !== undefined || request.controlGeneration !== undefined || request.owner !== undefined) {
      throw new Error('task read or safe transition does not accept Browser authorization fields');
    }
    if (args.action === 'begin') {
      if (!exactKeys(args, new Set(['action', 'durationMs', 'operationLimit', 'title', 'goal', 'notes'])) ||
          (args.durationMs !== undefined && (!Number.isSafeInteger(args.durationMs) || args.durationMs < 1 || args.durationMs > 14_400_000)) ||
          (args.operationLimit !== undefined && (!Number.isSafeInteger(args.operationLimit) || args.operationLimit < 1 || args.operationLimit > 2000)) ||
          (args.title !== undefined && (typeof args.title !== 'string' || args.title.length > 120)) ||
          (args.goal !== undefined && (typeof args.goal !== 'string' || args.goal.length > 4000)) ||
          (args.notes !== undefined && (typeof args.notes !== 'string' || args.notes.length > 2000))) {
        throw new Error('task begin accepts bounded durationMs, operationLimit, title, goal, and notes');
      }
    } else if (args.action === 'list') {
      if (!exactKeys(args, new Set(['action', 'offset', 'limit'])) ||
          (args.offset !== undefined && (!Number.isSafeInteger(args.offset) || args.offset < 0)) ||
          (args.limit !== undefined && (!Number.isSafeInteger(args.limit) || args.limit < 1 || args.limit > 100))) {
        throw new Error('task list accepts optional offset >= 0 and limit 1..100');
      }
    } else if (args.action === 'update') {
      if (!exactKeys(args, new Set(['action', 'taskId', 'expectedRevision', 'title', 'goal', 'notes'])) ||
          typeof args.taskId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(args.taskId) ||
          !Number.isSafeInteger(args.expectedRevision) || args.expectedRevision < 0 ||
          !['title', 'goal', 'notes'].some((key) => args[key] !== undefined) ||
          (args.title !== undefined && (typeof args.title !== 'string' || args.title.length > 120)) ||
          (args.goal !== undefined && (typeof args.goal !== 'string' || args.goal.length > 4000)) ||
          (args.notes !== undefined && (typeof args.notes !== 'string' || args.notes.length > 2000))) {
        throw new Error('task update requires taskId, expectedRevision, and bounded task fields');
      }
    } else if (args.action === 'delete') {
      if (!exactKeys(args, new Set(['action', 'taskId', 'expectedRevision'])) ||
          typeof args.taskId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(args.taskId) ||
          !Number.isSafeInteger(args.expectedRevision) || args.expectedRevision < 0) {
        throw new Error('task delete requires taskId and expectedRevision');
      }
    } else if (args.action === 'fail') {
      if (!exactKeys(args, new Set(['action', 'taskId', 'reason'])) || typeof args.taskId !== 'string' ||
          !/^[A-Za-z0-9_-]{1,64}$/.test(args.taskId) || (args.reason !== undefined &&
          (typeof args.reason !== 'string' || args.reason.trim().length < 1 || args.reason.length > 1000))) {
        throw new Error('task fail reason, when provided, must contain 1..1000 non-whitespace characters');
      }
    } else if (['status', 'pause', 'wait', 'complete', 'reconcile', 'resume'].includes(args.action)) {
      if (!exactKeys(args, new Set(['action', 'taskId', 'reason'])) || typeof args.taskId !== 'string' ||
          !/^[A-Za-z0-9_-]{1,64}$/.test(args.taskId) ||
          (args.action === 'wait' ? !['page_load', 'page_condition', 'user_action', 'user_authentication', 'recovery_check'].includes(args.reason) : args.reason !== undefined)) {
        throw new Error(`task ${args.action} requires a valid taskId and action fields`);
      }
    } else throw new Error('task action is invalid');
    return request;
  }

  const pageList = method === 'page' && request.args.action === 'list';
  const pageMutation = method === 'page' && ['new', 'switch', 'close'].includes(request.args.action);
  const fileRead = method === 'file' && ['list', 'status'].includes(request.args.action);
  const fileMutation = method === 'file' && ['start', 'cancel'].includes(request.args.action);
  const hasOwner = !['status', 'observe', 'wait', 'selection'].includes(method) && !pageList && !fileRead;
  if (hasOwner) {
    if (request.owner !== 'ai') throw new Error('owner must be ai for control methods');
    const openWithoutSession = method === 'open' && request.sessionGeneration === undefined &&
      request.controlGeneration === undefined;
    safeGeneration(request.sessionGeneration, !openWithoutSession, 'sessionGeneration');
    safeGeneration(request.controlGeneration, !openWithoutSession, 'controlGeneration');
  } else {
    safeGeneration(request.sessionGeneration, ['observe', 'wait', 'selection'].includes(method) || pageList, 'sessionGeneration');
    if (request.owner !== undefined || request.controlGeneration !== undefined) {
      throw new Error(`${method} must not include owner or controlGeneration`);
    }
  }
  if (!['observe', 'wait', 'selection'].includes(method) && !pageList && !fileRead &&
      ((request.sessionGeneration === undefined) !== (request.controlGeneration === undefined))) {
    throw new Error('sessionGeneration and controlGeneration must be provided together');
  }
  if (['observe', 'wait', 'navigate', 'locate', 'click', 'type', 'scroll', 'screenshot', 'close', 'page', 'file', 'upload'].includes(method) && request.sessionGeneration < 1) {
    throw new Error('active sessionGeneration must be positive');
  }
  if (['navigate', 'locate', 'click', 'type', 'scroll', 'screenshot', 'close'].includes(method) && request.controlGeneration < 1) {
    throw new Error('active controlGeneration must be positive');
  }
  if (['collect', 'event'].includes(method) && request.controlGeneration < 1) throw new Error(`${method} controlGeneration must be positive`);
  if (method === 'open' && request.sessionGeneration !== undefined &&
      (request.sessionGeneration < 1 || request.controlGeneration < 1)) {
    throw new Error('active open generations must be positive');
  }
  if (['debug', 'network'].includes(method) &&
      (request.sessionGeneration < 1 || request.controlGeneration < 1)) {
    throw new Error('advanced control generations must be positive');
  }
  if (method === 'selection' && request.sessionGeneration < 1) {
    throw new Error('selection sessionGeneration must be positive');
  }
  if (pageMutation && request.controlGeneration < 1) {
    throw new Error('page mutation generations must be positive');
  }
  if (fileMutation && request.controlGeneration < 1) {
    throw new Error('file mutation generations must be positive');
  }
  if (method === 'upload' && request.controlGeneration < 1) throw new Error('upload generations must be positive');
  if (method === 'upload' && (typeof request.taskId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(request.taskId))) throw new Error('upload requires taskId');

  const args = request.args;
  if (['debug', 'network', 'selection'].includes(method)) {
    const error = validateAdvancedArgs(method, args);
    if (error) throw new Error(error);
    return request;
  }
  if (method === 'collect') {
    const allowed = new Set(['action', 'containerSelector', 'messageSelector', 'keyAttribute', 'authorSelector', 'authorAttribute', 'contentSelector', 'orderAttribute', 'totalCountSelector', 'totalCountAttribute', 'declaredRange', 'maxRecords', 'maxContentChars', 'excludeSelector']);
    const selector = (v, required) => typeof v === 'string' && v.length >= (required ? 1 : 0) && v.length <= 512 && !/[\u0000-\u001f\u007f]/.test(v);
    const attr = (v) => typeof v === 'string' && /^[A-Za-z_:][A-Za-z0-9_.:-]{0,63}$/.test(v);
    if (!exactKeys(args, allowed) || args.action !== 'window' || !selector(args.containerSelector, true) || !selector(args.messageSelector, true) ||
        ['authorSelector', 'contentSelector', 'totalCountSelector', 'excludeSelector'].some((key) => args[key] !== undefined && !selector(args[key], true)) ||
        ['keyAttribute', 'authorAttribute', 'orderAttribute', 'totalCountAttribute'].some((key) => args[key] !== undefined && !attr(args[key])) ||
        typeof args.declaredRange !== 'string' || args.declaredRange.length < 1 || args.declaredRange.length > 128 || /[\u0000-\u001f\u007f]/.test(args.declaredRange) ||
        !Number.isInteger(args.maxRecords) || args.maxRecords < 1 || args.maxRecords > 200 ||
        !Number.isInteger(args.maxContentChars) || args.maxContentChars < 1 || args.maxContentChars > 4096) {
      throw new Error('collect window fields are invalid');
    }
    return request;
  }
  if (method === 'event') {
    const args = request.args;
    const id = (value) => typeof value === 'string' && /^[a-f0-9]{32}$/.test(value);
    if (args.action === 'arm') {
      if (!exactKeys(args, new Set(['action', 'eventKinds', 'ttlMs'])) || !Array.isArray(args.eventKinds) ||
          args.eventKinds.length < 1 || args.eventKinds.length > 6 || new Set(args.eventKinds).size !== args.eventKinds.length ||
          !args.eventKinds.every((kind) => ['navigation_started', 'navigation_finished', 'navigation_failed', 'new_window', 'download', 'javascript_dialog'].includes(kind)) ||
          (args.ttlMs !== undefined && (!Number.isSafeInteger(args.ttlMs) || args.ttlMs < 1 || args.ttlMs > 30000))) {
        throw new Error('event arm requires 1..6 unique eventKinds and bounded ttlMs');
      }
    } else if (args.action === 'wait') {
      if (!exactKeys(args, new Set(['action', 'subscriptionId', 'timeoutMs'])) || !id(args.subscriptionId) ||
          (args.timeoutMs !== undefined && (!Number.isSafeInteger(args.timeoutMs) || args.timeoutMs < 1 || args.timeoutMs > 30000))) {
        throw new Error('event wait requires a subscriptionId and bounded timeoutMs');
      }
    } else if (args.action === 'cancel') {
      if (!exactKeys(args, new Set(['action', 'subscriptionId'])) || !id(args.subscriptionId)) throw new Error('event cancel requires a subscriptionId');
    } else if (args.action === 'resolve_dialog') {
      if (!exactKeys(args, new Set(['action', 'eventId', 'decision', 'promptText'])) || !id(args.eventId) ||
          !['accept', 'dismiss', 'prompt'].includes(args.decision) ||
          (args.decision === 'prompt' ? typeof args.promptText !== 'string' || Array.from(args.promptText).length > 4096 : args.promptText !== undefined)) {
        throw new Error('event resolve_dialog fields are invalid');
      }
    } else throw new Error('event action is invalid');
    return request;
  }
  if (method === 'page') {
    if (args.action === 'list' || args.action === 'new') {
      if (!exactKeys(args, new Set(['action']))) throw new Error('page list/new accepts only action');
    } else if (args.action === 'switch' || args.action === 'close') {
      if (!exactKeys(args, new Set(['action', 'pageId'])) || typeof args.pageId !== 'string' ||
          !/^pg-[A-Fa-f0-9]{20}$/.test(args.pageId)) {
        throw new Error('page switch/close requires a valid pageId');
      }
    } else {
      throw new Error('page action must be list, new, switch, or close');
    }
    return request;
  }
  if (method === 'file') {
    switch (args.action) {
      case 'list':
        if (!exactKeys(args, new Set(['action', 'limit', 'beforeId'])) ||
            (args.limit !== undefined && (!Number.isInteger(args.limit) || args.limit < 1 || args.limit > 20)) ||
            (args.beforeId !== undefined && (typeof args.beforeId !== 'string' || !/^[a-f0-9]{32}$/.test(args.beforeId)))) {
          throw new Error('file list accepts bounded limit and optional 32-hex beforeId');
        }
        break;
      case 'status':
      case 'cancel':
        if (!exactKeys(args, new Set(['action', 'id'])) || typeof args.id !== 'string' || !/^[a-f0-9]{32}$/.test(args.id)) {
          throw new Error(`file ${args.action} requires a valid 32-hex id`);
        }
        break;
      case 'start':
        if (!exactKeys(args, new Set(['action', 'url', 'suggestedFileName'])) || !validHttpUrl(args.url) ||
            (args.suggestedFileName !== undefined && (typeof args.suggestedFileName !== 'string' ||
              args.suggestedFileName.length > 180 || /[\u0000-\u001f\u007f-\u009f/\\:]/.test(args.suggestedFileName)))) {
          throw new Error('file start requires an http(s) url and optional safe suggestedFileName');
        }
        break;
      default:
        throw new Error('file action must be list, status, start, or cancel');
    }
    return request;
  }
  if (method === 'upload') {
    const id = (value) => typeof value === 'string' && /^[a-f0-9]{32}$/.test(value);
    const safePath = (value) => {
      if (typeof value !== 'string' || value.length < 1 || value.length > 1024 || /[\\\u0000-\u001f\u007f]/.test(value) || value.startsWith('/') || /^[A-Za-z]:/.test(value)) return false;
      const parts = value.split('/');
      if (parts.some((part) => !part || part === '.' || part === '..')) return false;
      const denied = new Set(['.dsh', '.git', '.ssh', '.config', '.android', '.gradle', '.npm', '.aws', '.kube', '.npmrc', '.pypirc', '.netrc', 'user-data', 'node_modules', 'credentials', 'credential', 'secrets', 'secret', 'id_rsa', 'id_ed25519', 'id_ecdsa', 'authorized_keys']);
      return !parts.some((part) => denied.has(part.toLowerCase()) || /^\.env(?:\.|$)/i.test(part) || /(?:^|[._-])(?:credentials?|secrets?|tokens?)(?:[._-]|$)/i.test(part) || /\.(?:pem|key|p12|pfx|jks|keystore|mobileprovision|asc)$/i.test(part));
    };
    switch (args.action) {
      case 'request':
        if (!exactKeys(args, new Set(['action', 'paths', 'acceptTypes', 'multiple', 'summary'])) || !Array.isArray(args.paths) || args.paths.length < 1 || args.paths.length > 10 || !args.paths.every(safePath) || new Set(args.paths).size !== args.paths.length ||
            (args.acceptTypes !== undefined && (!Array.isArray(args.acceptTypes) || args.acceptTypes.length > 20 || !args.acceptTypes.every((v) => typeof v === 'string' && v.length <= 127 && !/[\\\u0000-\u001f\u007f]/.test(v)))) ||
            (args.multiple !== undefined && typeof args.multiple !== 'boolean') || (args.summary !== undefined && (typeof args.summary !== 'string' || args.summary.length > 160 || /[\u0000-\u001f\u007f]/.test(args.summary)))) throw new Error('upload request paths and options are invalid');
        break;
      case 'list': case 'status': case 'cancel':
        if (!exactKeys(args, new Set(['action', 'approvalId'])) || !id(args.approvalId)) throw new Error(`upload ${args.action} requires approvalId`);
        break;
      case 'activate':
        if (!exactKeys(args, new Set(['action', 'approvalId', 'nodeId'])) || !id(args.approvalId) ||
            typeof args.nodeId !== 'string' || !OBSERVATION_REF.test(args.nodeId)) throw new Error('upload activate requires approvalId and a current observed nodeId');
        break;
      case 'submit':
        if (!exactKeys(args, new Set(['action', 'approvalId', 'fileIds'])) || !id(args.approvalId) || !Array.isArray(args.fileIds) || args.fileIds.length < 1 || args.fileIds.length > 10 || !args.fileIds.every(id) || new Set(args.fileIds).size !== args.fileIds.length) throw new Error('upload submit requires approvalId and unique fileIds');
        break;
      default: throw new Error('upload action is invalid');
    }
    return request;
  }
  const keys = (list) => exactKeys(args, new Set(list));
  switch (method) {
    case 'status':
    case 'screenshot':
    case 'close':
      if (!keys([])) throw new Error(`${method} takes no arguments`);
      break;
    case 'open': {
      if (!keys(['url', 'thumbnail']) || (args.thumbnail !== undefined && typeof args.thumbnail !== 'boolean') || args.thumbnail === true && args.url === undefined) throw new Error('open thumbnail requires a URL and a boolean flag');
      if (args.url !== undefined) {
        if (typeof args.url !== 'string' || args.url.length > 2048) throw new Error('url is invalid');
        let url;
        try { url = new URL(args.url); } catch { throw new Error('url is invalid'); }
        if (!['http:', 'https:'].includes(url.protocol) || !url.hostname || url.username || url.password) {
          throw new Error('url must be an http or https URL without credentials');
        }
      }
      break;
    }
    case 'observe':
      if (!keys(['maxTextChars']) || (args.maxTextChars !== undefined &&
          (!Number.isInteger(args.maxTextChars) || args.maxTextChars < 1 || args.maxTextChars > 4096))) {
        throw new Error('observe maxTextChars must be between 1 and 4096');
      }
      break;
    case 'wait': {
      const conditions = ['load', 'url', 'text', 'selector'];
      if (!keys(['condition', 'timeoutMs', 'pollIntervalMs', 'url', 'text', 'selector', 'state']) ||
          !conditions.includes(args.condition) ||
          (args.timeoutMs !== undefined && (!Number.isInteger(args.timeoutMs) || args.timeoutMs < 1 || args.timeoutMs > 30000)) ||
          (args.pollIntervalMs !== undefined && (!Number.isInteger(args.pollIntervalMs) || args.pollIntervalMs < 100 || args.pollIntervalMs > 1000)) ||
          (args.state !== undefined && !['attached', 'visible', 'hidden'].includes(args.state))) {
        throw new Error('wait requires a supported condition and bounded timeout, poll interval, and selector state');
      }
      const conditionField = { url: 'url', text: 'text', selector: 'selector' }[args.condition];
      if (conditionField && (typeof args[conditionField] !== 'string' || args[conditionField].length < 1 || args[conditionField].length > 2048)) {
        throw new Error(`wait ${conditionField} must contain 1..2048 characters`);
      }
      if (conditionField && ['url', 'text', 'selector'].some((field) => field !== conditionField && args[field] !== undefined)) {
        throw new Error(`wait ${args.condition} accepts only its matching condition value`);
      }
      if (args.condition === 'url' && !validHttpUrl(args.url)) throw new Error('wait url must be an HTTP or HTTPS URL without credentials');
      if (!conditionField && ['url', 'text', 'selector', 'state'].some((field) => args[field] !== undefined)) {
        throw new Error('wait load accepts only condition, timeoutMs, and pollIntervalMs');
      }
      if (args.condition !== 'selector' && args.state !== undefined) throw new Error('wait state is supported only for selector conditions');
      break;
    }
    case 'navigate':
      if (!keys(['action', 'eventSubscriptionId', 'eventNonce', 'thumbnail']) || (args.thumbnail !== undefined && typeof args.thumbnail !== 'boolean') || !['back', 'forward', 'reload'].includes(args.action) ||
          ((args.eventSubscriptionId === undefined) !== (args.eventNonce === undefined)) ||
          (args.eventSubscriptionId !== undefined && (!/^[a-f0-9]{32}$/.test(args.eventSubscriptionId) || !/^[a-f0-9]{64}$/.test(args.eventNonce)))) {
        throw new Error('navigate action must be back, forward, or reload');
      }
      break;
    case 'locate': {
      if (!keys(['strategy', 'value', 'exact', 'name', 'scopeRef', 'partial']) ||
          (args.partial !== undefined && typeof args.partial !== 'boolean') ||
          !['role', 'label', 'placeholder', 'text', 'css'].includes(args.strategy) ||
          typeof args.value !== 'string' || args.value.length < 1 || args.value.length > 2048 ||
          typeof args.exact !== 'boolean' ||
          (args.strategy === 'role' && args.name !== undefined &&
            (typeof args.name !== 'string' || args.name.length < 1 || args.name.length > 2048)) ||
          (args.strategy !== 'role' && args.name !== undefined) ||
          (args.scopeRef !== undefined && (typeof args.scopeRef !== 'string' || !OBSERVATION_REF.test(args.scopeRef)))) {
        throw new Error('locate requires strategy role|label|placeholder|text|css, value, exact, optional role name, and a current scopeRef');
      }
      break;
    }
    case 'click': {
      const credentials = (args.eventSubscriptionId === undefined) === (args.eventNonce === undefined) &&
        (args.eventSubscriptionId === undefined || (/^[a-f0-9]{32}$/.test(args.eventSubscriptionId) && /^[a-f0-9]{64}$/.test(args.eventNonce)));
      const nodeClick = (keys(['nodeId']) || keys(['nodeId', 'eventSubscriptionId', 'eventNonce'])) && typeof args.nodeId === 'string' && NODE_ID.test(args.nodeId);
      const visualClick = (keys(['screenshotId', 'x', 'y']) || keys(['screenshotId', 'x', 'y', 'eventSubscriptionId', 'eventNonce'])) && typeof args.screenshotId === 'string' &&
        /^[a-f0-9]{32}$/.test(args.screenshotId) && Number.isFinite(args.x) && Number.isFinite(args.y);
      if ((!nodeClick && !visualClick) || !credentials) throw new Error('click requires nodeId or screenshotId with finite bitmap x/y and optional paired event credentials');
      break;
    }
    case 'type':
      if (!keys(['nodeId', 'text', 'replace']) || typeof args.nodeId !== 'string' ||
          !NODE_ID.test(args.nodeId) || typeof args.text !== 'string' || args.text.length > 4096 ||
          (args.replace !== undefined && typeof args.replace !== 'boolean')) {
        throw new Error('type requires nodeId and text (maximum 4096 characters)');
      }
      break;
    case 'scroll':
      if (!keys(['nodeId', 'direction', 'steps']) ||
          (args.nodeId !== undefined && (typeof args.nodeId !== 'string' || !NODE_ID.test(args.nodeId))) ||
          !['up', 'down'].includes(args.direction) ||
          (args.steps !== undefined && (!Number.isInteger(args.steps) || args.steps < 1 || args.steps > 10))) {
        throw new Error('scroll requires direction up|down and optional bounded nodeId/steps');
      }
      break;
    default:
      throw new Error('unsupported browser method');
  }
  return request;
}

function validText(value, maximum, minimum = 0) {
  return typeof value === 'string' && value.length >= minimum && value.length <= maximum;
}

function validCoordinate(value) {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 10000;
}

function validHeaders(value) {
  if (!ownObject(value)) return false;
  const entries = Object.entries(value);
  return entries.length <= 16 && entries.every(([name, content]) =>
    /^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}$/.test(name) &&
    typeof content === 'string' && content.length <= 1024 && !/[\r\n]/.test(content));
}

function validHttpUrl(value, maximum = 2048) {
  if (!validText(value, maximum, 1)) return false;
  let url;
  try { url = new URL(value); } catch { return false; }
  return ['http:', 'https:'].includes(url.protocol) && !!url.hostname && !url.username && !url.password;
}

function validateAdvancedArgs(method, args) {
  if (!ownObject(args) || typeof args.action !== 'string') return 'advanced args require an action';
  if (method === 'selection') {
    if (args.action === 'pending') return exactKeys(args, new Set(['action', 'afterSequence', 'destinationSessionKey'])) &&
      Number.isSafeInteger(args.afterSequence) && args.afterSequence >= 0 &&
      typeof args.destinationSessionKey === 'string' && args.destinationSessionKey.length >= 1 &&
      args.destinationSessionKey.length <= 256 && !/[\u0000-\u001f\u007f]/.test(args.destinationSessionKey)
      ? '' : 'selection cursor or session key is invalid';
    if (args.action === 'claim' || args.action === 'admit') return exactKeys(args,
      new Set(['action', 'selectionId', 'destinationSessionKey', 'requestId', 'payloadDigest'])) &&
      typeof args.selectionId === 'string' && /^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$/.test(args.selectionId) &&
      typeof args.destinationSessionKey === 'string' && args.destinationSessionKey.length >= 1 && args.destinationSessionKey.length <= 256 &&
      !/[\u0000-\u001f\u007f]/.test(args.destinationSessionKey) &&
      typeof args.requestId === 'string' && /^[a-f0-9]{32}$/.test(args.requestId) &&
      typeof args.payloadDigest === 'string' && /^[a-f0-9]{64}$/.test(args.payloadDigest)
      ? '' : 'selection claim fields are invalid';
    if (['task_pending', 'task_claim', 'task_admit', 'task_receipt'].includes(args.action)) {
      const validDestination = typeof args.destinationSessionKey === 'string' && args.destinationSessionKey.length >= 1 &&
        args.destinationSessionKey.length <= 256 && !/[\u0000-\u001f\u007f]/.test(args.destinationSessionKey);
      if (args.action === 'task_pending') return exactKeys(args, new Set(['action', 'afterSequence', 'destinationSessionKey'])) &&
        Number.isSafeInteger(args.afterSequence) && args.afterSequence >= 0 && validDestination
        ? '' : 'task feedback cursor or destination is invalid';
      if (args.action === 'task_claim' || args.action === 'task_admit') return exactKeys(args,
        new Set(['action', 'feedbackId', 'destinationSessionKey', 'requestId', 'payloadDigest'])) &&
        typeof args.feedbackId === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(args.feedbackId) && validDestination &&
        typeof args.requestId === 'string' && /^[a-f0-9]{32}$/.test(args.requestId) &&
        typeof args.payloadDigest === 'string' && /^[a-f0-9]{64}$/.test(args.payloadDigest)
        ? '' : 'task feedback claim fields are invalid';
      return exactKeys(args, new Set(['action', 'feedbackId', 'destinationSessionKey', 'requestId', 'deliveryCode'])) &&
        typeof args.feedbackId === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(args.feedbackId) && validDestination &&
        typeof args.requestId === 'string' && /^[a-f0-9]{32}$/.test(args.requestId) &&
        ['PROMPT_ACCEPTED', 'UNKNOWN', 'BLOCKED'].includes(args.deliveryCode)
        ? '' : 'task feedback receipt fields are invalid';
    }
    if (args.action === 'receipt') {
      return exactKeys(args, new Set(['action', 'selectionId', 'destinationSessionKey', 'deliveryCode', 'requestId', 'imageIncluded'])) &&
        typeof args.selectionId === 'string' && /^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$/.test(args.selectionId) &&
        typeof args.destinationSessionKey === 'string' && args.destinationSessionKey.length >= 1 && args.destinationSessionKey.length <= 256 && !/[\u0000-\u001f\u007f]/.test(args.destinationSessionKey) &&
        typeof args.deliveryCode === 'string' && /^[A-Z_]{1,64}$/.test(args.deliveryCode) &&
        (args.requestId === undefined || (typeof args.requestId === 'string' && /^[a-f0-9]{32}$/.test(args.requestId))) &&
        (args.imageIncluded === undefined || typeof args.imageIncluded === 'boolean')
        ? '' : 'selection receipt fields are invalid';
    }
    return exactKeys(args, new Set(['action'])) && ['latest', 'consume', 'clear', 'list'].includes(args.action)
      ? '' : 'selection action or fields are invalid';
  }
  if (method === 'debug') {
    switch (args.action) {
      case 'status': return exactKeys(args, new Set(['action'])) ? '' : 'debug status takes no arguments';
      case 'rendering': return validRenderingArgs(args) ? '' : 'rendering fields are invalid';
      case 'ax_click': return exactKeys(args, new Set(['action', 'actionRef'])) && validAxActionRef(args.actionRef) ? '' : 'AX click reference is invalid';
      case 'ax_type': return exactKeys(args, new Set(['action', 'actionRef', 'text', 'replace'])) && validAxActionRef(args.actionRef) && typeof args.text === 'string' && args.text.length <= 4096 && (args.replace === undefined || typeof args.replace === 'boolean') ? '' : 'AX type fields are invalid';
      case 'ax_scroll': return exactKeys(args, new Set(['action', 'actionRef', 'direction', 'steps'])) && validAxActionRef(args.actionRef) && ['up', 'down'].includes(args.direction) && (args.steps === undefined || Number.isInteger(args.steps) && args.steps >= 1 && args.steps <= 10) ? '' : 'AX scroll fields are invalid';
      case 'ax_observe':
      case 'ax_tree': return exactKeys(args, new Set(['action', 'maxNodes', 'maxDepth', 'includeIgnored'])) &&
        (args.maxNodes === undefined || Number.isInteger(args.maxNodes) && args.maxNodes >= 1 && args.maxNodes <= 256) &&
        (args.maxDepth === undefined || Number.isInteger(args.maxDepth) && args.maxDepth >= 1 && args.maxDepth <= 8) &&
        (args.includeIgnored === undefined || typeof args.includeIgnored === 'boolean') ? '' : 'AX tree bounds are invalid';
      case 'console':
        return exactKeys(args, new Set(['action', 'limit'])) && (args.limit === undefined ||
          (Number.isInteger(args.limit) && args.limit >= 1 && args.limit <= 50)) ? '' : 'console limit must be between 1 and 50';
      case 'evaluate':
        return exactKeys(args, new Set(['action', 'expression'])) && validText(args.expression, 8192, 1) ? '' : 'evaluate expression is invalid';
      case 'dom':
        return exactKeys(args, new Set(['action', 'selector'])) && (args.selector === undefined || validText(args.selector, 512, 1)) ? '' : 'dom selector is invalid';
      case 'emulate':
        if (!exactKeys(args, new Set(['action', 'width', 'height', 'mobile', 'clear'])) ||
            (args.clear !== undefined && typeof args.clear !== 'boolean')) return 'emulate dimensions or flags are invalid';
        if (args.clear === true) {
          return exactKeys(args, new Set(['action', 'clear'])) ? '' : 'emulate clear cannot include dimensions';
        }
        return Number.isInteger(args.width) && args.width >= 240 && args.width <= 2560 &&
          Number.isInteger(args.height) && args.height >= 240 && args.height <= 2560 &&
          typeof args.mobile === 'boolean' ? '' : 'emulate dimensions or flags are invalid';
      case 'interact': {
        if (typeof args.kind !== 'string') return 'interact kind is invalid';
        if (args.kind === 'key') return exactKeys(args, new Set(['action', 'kind', 'key'])) && validText(args.key, 32, 1) ? '' : 'key interaction is invalid';
        if (args.kind === 'tap') {
          const selector = args.selector !== undefined;
          const coordinates = args.x !== undefined || args.y !== undefined;
          if (selector && !coordinates) return exactKeys(args, new Set(['action', 'kind', 'selector'])) && validText(args.selector, 512, 1) ? '' : 'tap selector is invalid';
          return !selector && coordinates && exactKeys(args, new Set(['action', 'kind', 'x', 'y'])) && validCoordinate(args.x) && validCoordinate(args.y)
            ? '' : 'tap requires a selector or finite x/y coordinates';
        }
        if (args.kind === 'drag') return exactKeys(args, new Set(['action', 'kind', 'x', 'y', 'toX', 'toY'])) &&
          validCoordinate(args.x) && validCoordinate(args.y) && validCoordinate(args.toX) && validCoordinate(args.toY) ? '' : 'drag coordinates are invalid';
        if (args.kind === 'select') return exactKeys(args, new Set(['action', 'kind', 'selector', 'value'])) &&
          validText(args.selector, 512, 1) && validText(args.value, 4096) ? '' : 'select interaction is invalid';
        return 'interact kind is unsupported';
      }
      default: return 'debug action is unsupported';
    }
  }
  switch (args.action) {
    case 'start':
      return exactKeys(args, new Set(['action', 'includeBodies', 'includeRequestBodies'])) &&
        (args.includeBodies === undefined || typeof args.includeBodies === 'boolean') &&
        (args.includeRequestBodies === undefined || typeof args.includeRequestBodies === 'boolean')
        ? '' : 'capture body opt-ins must be booleans';
    case 'har':
    case 'export_frames':
      return exactKeys(args, new Set(['action', 'includeBodies'])) && (args.includeBodies === undefined || typeof args.includeBodies === 'boolean')
        ? '' : 'includeBodies must be a boolean';
    case 'stop':
    case 'clear_rules':
    case 'paused':
      return exactKeys(args, new Set(['action'])) ? '' : `${args.action} takes no arguments`;
    case 'list':
      return exactKeys(args, new Set(['action', 'cursor', 'limit'])) &&
        (args.cursor === undefined || (Number.isSafeInteger(args.cursor) && args.cursor >= 0)) &&
        (args.limit === undefined || (Number.isInteger(args.limit) && args.limit >= 1 && args.limit <= 20)) ? '' : 'network list bounds are invalid';
    case 'frames':
      return exactKeys(args, new Set(['action', 'cursor', 'limit'])) &&
        (args.cursor === undefined || (Number.isSafeInteger(args.cursor) && args.cursor >= 0)) &&
        (args.limit === undefined || (Number.isInteger(args.limit) && args.limit >= 1 && args.limit <= 20)) ? '' : 'network frames bounds are invalid';
    case 'body':
      return exactKeys(args, new Set(['action', 'entryId'])) && typeof args.entryId === 'string' && SAFE_ID.test(args.entryId) ? '' : 'entryId is invalid';
    case 'replay_prepare':
      return exactKeys(args, new Set(['action', 'entryId'])) && typeof args.entryId === 'string' && SAFE_ID.test(args.entryId) ? '' : 'entryId is invalid';
    case 'replay_status':
      return exactKeys(args, new Set(['action', 'requestId'])) && typeof args.requestId === 'string' && /^[a-f0-9]{32}$/.test(args.requestId)
        ? '' : 'replay requestId is invalid';
    case 'replay': {
      const hasToken = args.riskToken !== undefined || args.captureId !== undefined;
      return (hasToken
        ? exactKeys(args, new Set(['action', 'entryId', 'riskToken', 'captureId'])) && typeof args.riskToken === 'string' &&
          /^[a-f0-9]{32}$/.test(args.riskToken) && typeof args.captureId === 'string' && /^cap-[a-f0-9]{20}$/.test(args.captureId)
        : exactKeys(args, new Set(['action', 'entryId']))) && typeof args.entryId === 'string' && SAFE_ID.test(args.entryId)
        ? '' : 'replay requires an entry id and, for write replay, a confirmed one-use ticket';
    }
    case 'rules': {
      if (!exactKeys(args, new Set(['action', 'rules'])) || !Array.isArray(args.rules) || args.rules.length > 8) return 'network rules must contain at most eight entries';
      for (const rule of args.rules) {
        if (!ownObject(rule) || !validText(rule.urlPattern, 256, 1) ||
            !['request', 'response'].includes(rule.stage) || !['continue', 'block', 'modify', 'fulfill'].includes(rule.action) ||
            (rule.method !== undefined && (typeof rule.method !== 'string' || !/^[A-Za-z][A-Za-z0-9_-]{0,15}$/.test(rule.method))) ||
            !validNetworkRuleFields(rule, rule.stage, rule.action)) return 'network rule is invalid';
      }
      return '';
    }
    case 'resolve': {
      if (!exactKeys(args, new Set(['action', 'pausedId', 'decision', 'headers', 'url', 'method', 'postData', 'body', 'responseCode'])) ||
          typeof args.pausedId !== 'string' || !SAFE_ID.test(args.pausedId) ||
          !['continue', 'block', 'modify', 'fulfill'].includes(args.decision)) return 'network resolve request is invalid';
      const fields = ['headers', 'url', 'method', 'postData', 'body', 'responseCode'];
      if (['continue', 'block'].includes(args.decision) && fields.some((key) => args[key] !== undefined)) return 'continue/block resolve cannot modify a request';
      if (args.decision === 'fulfill' && ['url', 'method', 'postData'].some((key) => args[key] !== undefined)) return 'fulfill accepts only response headers, body, and status';
      if (['modify', 'fulfill'].includes(args.decision) && !fields.some((key) => args[key] !== undefined)) return 'modify/fulfill resolve requires bounded change fields';
      if (args.headers !== undefined && !validHeaders(args.headers)) return 'network headers are invalid';
      if (args.url !== undefined && !validHttpUrl(args.url)) return 'network url is invalid';
      if (args.method !== undefined && (typeof args.method !== 'string' || !/^[A-Za-z][A-Za-z0-9_-]{0,15}$/.test(args.method))) return 'network method is invalid';
      if (args.postData !== undefined && !validText(args.postData, 8192)) return 'postData is too long';
      if (args.body !== undefined && !validText(args.body, 8192)) return 'body is too long';
      if (args.responseCode !== undefined && (!Number.isInteger(args.responseCode) || args.responseCode < 200 || args.responseCode > 599)) return 'responseCode is invalid';
      return '';
    }
    default: return 'network action is unsupported';
  }
}

function validNetworkRuleFields(rule, stage, action) {
  const base = ['urlPattern', 'stage', 'method', 'action'];
  const mutationFields = action === 'continue' || action === 'block' ? [] : action === 'fulfill'
    ? ['headers', 'body', 'responseCode']
    : (stage === 'request' ? ['headers', 'url', 'postData'] : ['headers', 'body', 'responseCode']);
  if (!exactKeys(rule, new Set([...base, ...mutationFields]))) return false;
  if ((action === 'modify' || action === 'fulfill') && !mutationFields.some((key) => rule[key] !== undefined)) return false;
  if (action === 'fulfill' && stage !== 'response') return false;
  if (rule.headers !== undefined && !validHeaders(rule.headers)) return false;
  if (rule.url !== undefined && !validHttpUrl(rule.url)) return false;
  if (rule.postData !== undefined && !validText(rule.postData, 8192)) return false;
  if (rule.body !== undefined && !validText(rule.body, 8192)) return false;
  if (rule.responseCode !== undefined && (!Number.isInteger(rule.responseCode) || rule.responseCode < 200 || rule.responseCode > 599 || stage !== 'response')) return false;
  return true;
}

function readChannelConfig(filePath = CHANNEL_FILE) {
  const stat = fs.lstatSync(filePath);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 4096) throw new Error('channel configuration is unavailable');
  const config = JSON.parse(fs.readFileSync(filePath, 'utf8'));
  if (!ownObject(config) || config.version !== 1 ||
      typeof config.socketName !== 'string' || !/^[A-Za-z0-9_.-]{1,90}$/.test(config.socketName) ||
      typeof config.token !== 'string' || !/^[a-f0-9]{64}$/.test(config.token)) {
    throw new Error('channel configuration is invalid');
  }
  return config;
}

function response(requestId, code, state = 'CLOSED', executed = null, data = {}) {
  return { version: 1, requestId, code, executed, sessionGeneration: null, controlGeneration: null, state, owner: null, data };
}

function truncateUtf8(text, maxBytes) {
  let output = '';
  for (const character of text) {
    const next = output + character;
    if (Buffer.byteLength(next, 'utf8') > maxBytes) return { text: output, truncated: true };
    output = next;
  }
  return { text: output, truncated: false };
}

function validArtifactReference(method, action, artifactPath) {
  if (typeof artifactPath !== 'string' || artifactPath.includes('..')) return false;
  if (method === 'screenshot') return /^\/opt\/browser\/out\/shot\/[A-Za-z0-9_.-]+\.png$/.test(artifactPath);
  if (method === 'network' && ['body', 'har'].includes(action)) {
    return /^\/opt\/browser\/out\/network\/[A-Za-z0-9_.-]{1,160}$/.test(artifactPath);
  }
  if (method === 'network' && action === 'export_frames') {
    return /^\/opt\/browser\/out\/network\/net-[a-f0-9]{24}\.ws\.json$/.test(artifactPath);
  }
  if (method === 'selection' && ['latest', 'consume', 'pending', 'claim'].includes(action)) {
    return /^\/opt\/browser\/out\/selection\/[A-Za-z0-9_.-]+\.png$/.test(artifactPath);
  }
  if (method === 'file') {
    return /^\/opt\/browser\/out\/downloads\/[a-f0-9]{32}_[A-Za-z0-9_.-]{1,180}$/.test(artifactPath);
  }
  return false;
}

function validDownloadItem(item) {
  if (!ownObject(item) || !exactKeys(item, new Set([
    'id', 'state', 'fileName', 'bytesReceived', 'totalBytes', 'mimeType', 'artifactPath', 'errorCode', 'updatedAtMillis',
  ]))) return false;
  if (typeof item.id !== 'string' || !/^[a-f0-9]{32}$/.test(item.id) ||
      !['QUEUED', 'DOWNLOADING', 'CANCELLING', 'COMPLETED', 'FAILED', 'CANCELLED'].includes(item.state) ||
      !(item.fileName === null || (typeof item.fileName === 'string' && /^[A-Za-z0-9_.-]{1,180}$/.test(item.fileName) && item.fileName !== '.' && item.fileName !== '..')) ||
      !Number.isSafeInteger(item.bytesReceived) || item.bytesReceived < 0 || item.bytesReceived > MAX_DOWNLOAD_BYTES ||
      !(item.totalBytes === null || (Number.isSafeInteger(item.totalBytes) && item.totalBytes >= 0 && item.totalBytes <= MAX_DOWNLOAD_BYTES && item.bytesReceived <= item.totalBytes)) ||
      !(item.mimeType === null || (typeof item.mimeType === 'string' && item.mimeType.length <= 127 && /^[A-Za-z0-9!#$&^_.+-]+\/[A-Za-z0-9!#$&^_.+-]+$/.test(item.mimeType))) ||
      !(item.artifactPath === null || (typeof item.artifactPath === 'string' && validArtifactReference('file', '', item.artifactPath))) ||
      !(item.errorCode === null || (typeof item.errorCode === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(item.errorCode))) ||
      !Number.isSafeInteger(item.updatedAtMillis) || item.updatedAtMillis < 0) return false;
  if (item.artifactPath !== null) {
    const match = /^\/opt\/browser\/out\/downloads\/([a-f0-9]{32})_/.exec(item.artifactPath);
    if (!match || match[1] !== item.id || item.state !== 'COMPLETED' || item.fileName === null ||
        item.artifactPath !== `/opt/browser/out/downloads/${item.id}_${item.fileName}`) return false;
  } else if (item.state === 'COMPLETED') return false;
  return true;
}

function validDevToolsCapabilityCoverage(data) {
  if (!ownObject(data) || ['targetId', 'socketPath', 'socketUrl', 'webSocketDebuggerUrl', 'debuggerUrl']
    .some((key) => Object.hasOwn(data, key))) return false;
  const hasCapabilities = Object.hasOwn(data, 'capabilities');
  const hasCoverage = Object.hasOwn(data, 'coverage');
  if (hasCapabilities !== hasCoverage) return false;
  if (!hasCapabilities) return true;
  const methods = new Set(['Target.setAutoAttach', 'Network.enable', 'Network.getResponseBody',
    'Network.getRequestPostData', 'Network.streamResourceContent', 'Fetch.enable', 'Runtime.evaluate',
    'Network.webSocketEvents', 'page.fetchReplay']);
  const capabilities = data.capabilities;
  if (!exactObjectKeys(capabilities, methods) ||
      Object.values(capabilities).some((state) => !['unknown', 'supported', 'unsupported'].includes(state))) return false;
  const coverage = data.coverage;
  return exactKeys(coverage, new Set(['mainPage', 'workerTargets', 'serviceWorkerTargets', 'otherTargets'])) &&
    ['connected', 'disconnected'].includes(coverage.mainPage) &&
    ['unsupported', 'waiting_for_attached_target', 'attached_network_enabled', 'detached'].includes(coverage.workerTargets) &&
    coverage.serviceWorkerTargets === 'unsupported_shared_target_unverified' && coverage.otherTargets === 'excluded';
}

function validNetworkFrameExport(data) {
  return exactKeys(data, new Set(['artifactPath', 'mimeType', 'bytes', 'captureId', 'frameCount', 'payloadsIncluded',
    'payloadUnredacted', 'headersRedacted', 'redacted', 'truncated'])) &&
    validArtifactReference('network', 'export_frames', data.artifactPath) && data.mimeType === 'application/json' &&
    Number.isSafeInteger(data.bytes) && data.bytes > 0 && data.bytes <= 2 * 1024 * 1024 &&
    typeof data.captureId === 'string' && /^cap-[a-f0-9]{20}$/.test(data.captureId) &&
    Number.isSafeInteger(data.frameCount) && data.frameCount >= 0 && data.frameCount <= 1000 &&
    typeof data.payloadsIncluded === 'boolean' && data.payloadUnredacted === data.payloadsIncluded &&
    typeof data.headersRedacted === 'boolean' && data.redacted === !data.payloadsIncluded && typeof data.truncated === 'boolean';
}

function validReplayPreview(preview) {
  const fields = new Set(['captureId', 'entryId', 'method', 'origin', 'path', 'queryOmitted', 'querySha256',
    'requestBodyBytes', 'requestBodySha256', 'contentType', 'credentials', 'sensitiveHeadersCopied', 'possibleDuplicateSideEffects']);
  if (!exactKeys(preview, fields) || typeof preview.captureId !== 'string' || !/^cap-[a-f0-9]{20}$/.test(preview.captureId) ||
      typeof preview.entryId !== 'string' || !SAFE_ID.test(preview.entryId) || !['POST', 'PUT', 'PATCH', 'DELETE'].includes(preview.method) ||
      typeof preview.origin !== 'string' || preview.origin.length > 512 || typeof preview.path !== 'string' ||
      !preview.path.startsWith('/') || preview.path.length > 1024 || /[?#]/.test(preview.path) || typeof preview.queryOmitted !== 'boolean' ||
      !(preview.querySha256 === null || /^[a-f0-9]{64}$/.test(preview.querySha256)) ||
      !Number.isSafeInteger(preview.requestBodyBytes) || preview.requestBodyBytes < 0 || preview.requestBodyBytes > 8192 ||
      typeof preview.requestBodySha256 !== 'string' || !/^[a-f0-9]{64}$/.test(preview.requestBodySha256) ||
      typeof preview.contentType !== 'string' || preview.contentType.length > 256 || preview.credentials !== 'same-origin' ||
      preview.sensitiveHeadersCopied !== false || preview.possibleDuplicateSideEffects !== true) return false;
  try {
    const url = new URL(preview.path, preview.origin);
    return url.origin === preview.origin && url.pathname === preview.path && !url.search && !url.hash &&
      ((preview.queryOmitted && preview.querySha256 !== null) || (!preview.queryOmitted && preview.querySha256 === null));
  } catch { return false; }
}

function validReplayPrepare(data) {
  return exactKeys(data, new Set(['requestId', 'status', 'expiresInMillis', 'preview'])) &&
    typeof data.requestId === 'string' && /^[a-f0-9]{32}$/.test(data.requestId) && data.status === 'awaiting_user' &&
    Number.isSafeInteger(data.expiresInMillis) && data.expiresInMillis >= 1 && data.expiresInMillis <= 120000 &&
    validReplayPreview(data.preview);
}

function validReplayStatus(data, sessionGeneration, controlGeneration, args) {
  if (!ownObject(data) || data.requestId !== args?.requestId || !/^[a-f0-9]{32}$/.test(data.requestId) ||
      !Number.isSafeInteger(data.expiresInMillis) || data.expiresInMillis < 0 || data.expiresInMillis > 120000) return false;
  if (data.status === 'awaiting_user') return exactKeys(data, new Set(['requestId', 'status', 'expiresInMillis']));
  if (data.status === 'denied') return exactKeys(data, new Set(['requestId', 'status', 'expiresInMillis']));
  return data.status === 'approved' && exactKeys(data, new Set(['requestId', 'status', 'riskToken', 'entryId', 'captureId',
    'sessionGeneration', 'controlGeneration', 'expiresInMillis'])) && /^[a-f0-9]{32}$/.test(data.riskToken) &&
    typeof data.entryId === 'string' && SAFE_ID.test(data.entryId) && typeof data.captureId === 'string' && /^cap-[a-f0-9]{20}$/.test(data.captureId) &&
    Number.isSafeInteger(sessionGeneration) && data.sessionGeneration === sessionGeneration &&
    Number.isSafeInteger(controlGeneration) && data.controlGeneration === controlGeneration;
}

function validNetworkReplayResult(data) {
  return exactKeys(data, new Set(['event', 'method', 'status', 'requestBodyBytes', 'requestBodySha256', 'resourceType', 'redirected'])) &&
    data.event === 'replayCompleted' && ['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE'].includes(data.method) &&
    Number.isInteger(data.status) && data.status >= 0 && data.status <= 599 &&
    Number.isSafeInteger(data.requestBodyBytes) && data.requestBodyBytes >= 0 && data.requestBodyBytes <= 8192 &&
    typeof data.requestBodySha256 === 'string' && (data.requestBodySha256 === '' || /^[a-f0-9]{64}$/.test(data.requestBodySha256)) &&
    data.resourceType === 'XHR' && typeof data.redirected === 'boolean';
}

function validUploadResult(value, action, args) {
  const data = value.data;
  const id = (candidate) => typeof candidate === 'string' && /^[a-f0-9]{32}$/.test(candidate);
  if (action === 'activate') {
    if (value.code === 'UNKNOWN') return value.executed === null;
    if (value.code === 'OK') return value.executed === true &&
      exactKeys(data, new Set(['approvalId', 'state'])) && data.approvalId === args.approvalId && data.state === 'chooser_pending';
    if (value.code === 'UPLOAD_CHOOSER_NOT_OPENED') return value.executed === true && Object.keys(data).length === 0;
    if (value.code === 'UPLOAD_ACTIVATION_UNRESOLVED') return [true, false].includes(value.executed) && Object.keys(data).length === 0;
    return value.executed === false && Object.keys(data).length === 0;
  }
  if (value.code !== 'OK') return value.code === 'UNKNOWN' ? value.executed === null : value.executed === false && Object.keys(data).length === 0;
  if (action === 'request') return value.executed === true && exactKeys(data, new Set(['state', 'approvalId', 'pageId', 'documentGeneration', 'fileCount', 'bytesTotal', 'expiresInMs'])) &&
    data.state === 'awaiting_approval' && id(data.approvalId) && validPageReference(data.pageId) && Number.isSafeInteger(data.documentGeneration) && data.documentGeneration >= 0 &&
    Number.isSafeInteger(data.fileCount) && data.fileCount >= 1 && data.fileCount <= 10 && Number.isSafeInteger(data.bytesTotal) && data.bytesTotal >= 0 && data.bytesTotal <= 32 * 1024 * 1024 &&
    Number.isSafeInteger(data.expiresInMs) && data.expiresInMs >= 1 && data.expiresInMs <= 120000;
  if (action === 'submit') return value.executed === true && exactKeys(data, new Set(['approvalId', 'state', 'fileCount', 'websiteUploadVerified'])) &&
    data.approvalId === args.approvalId && data.state === 'chooser_delivered' && Number.isSafeInteger(data.fileCount) && data.fileCount === args.fileIds.length && data.fileCount >= 1 && data.fileCount <= 10 && data.websiteUploadVerified === false;
  if (action === 'cancel') return value.executed === true && exactKeys(data, new Set(['approvalId', 'state'])) && data.approvalId === args.approvalId && data.state === 'cancelled';
  if (action !== 'list' && action !== 'status') return false;
  const hasFiles = Object.hasOwn(data, 'files');
  if (!exactKeys(data, action === 'list' && hasFiles ? new Set(['approvalId', 'state', 'expiresInMs', 'files']) : new Set(['approvalId', 'state', 'expiresInMs'])) ||
      data.approvalId !== args.approvalId || !['pending', 'ready', 'chooser_pending', 'chooser_delivered', 'cancelled', 'denied', 'expired', 'failed'].includes(data.state) ||
      !Number.isSafeInteger(data.expiresInMs) || data.expiresInMs < 0 || data.expiresInMs > 120000 || value.executed !== false || (action === 'status' && hasFiles)) return false;
  if (data.state === 'pending') return !hasFiles;
  if (!hasFiles) return true;
  const files = data.files;
  return ['ready', 'chooser_pending'].includes(data.state) && Array.isArray(files) && files.length >= 1 && files.length <= 10 &&
    files.every((file) => ownObject(file) && exactKeys(file, new Set(['fileId', 'name', 'mimeType', 'size'])) && id(file.fileId) &&
      typeof file.name === 'string' && /^[^/\\\u0000-\u001f\u007f]{1,255}$/.test(file.name) && typeof file.mimeType === 'string' && file.mimeType.length <= 127 &&
      /^[A-Za-z0-9!#$&^_.+-]+\/[A-Za-z0-9!#$&^_.+-]+$/.test(file.mimeType) && Number.isSafeInteger(file.size) && file.size >= 0 && file.size <= 16 * 1024 * 1024) &&
    new Set(files.map((file) => file.fileId)).size === files.length && files.reduce((total, file) => total + file.size, 0) <= 32 * 1024 * 1024;
}

function validControlApprovalData(data, args, expectedStatus = null, expectedCoalesced) {
  if (!exactObjectKeys(data, new Set(['approvalId', 'status', 'summary', 'taskId', 'createdAtMillis', 'expiresAtMillis', 'coalesced'])) ||
      typeof data.approvalId !== 'string' || !/^[a-f0-9]{32}$/.test(data.approvalId) ||
      !['PENDING', 'APPROVED', 'DENIED', 'CANCELLED', 'EXPIRED'].includes(data.status) ||
      (expectedStatus !== null && data.status !== expectedStatus) ||
      typeof data.summary !== 'string' || data.summary.length < 1 || data.summary.length > 1000 ||
      !(data.taskId === null || (typeof data.taskId === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(data.taskId))) ||
      !Number.isSafeInteger(data.createdAtMillis) || data.createdAtMillis < 0 ||
      !Number.isSafeInteger(data.expiresAtMillis) || data.expiresAtMillis - data.createdAtMillis !== 120_000 ||
      typeof data.coalesced !== 'boolean' || (expectedCoalesced !== undefined && data.coalesced !== expectedCoalesced)) return false;
  if (args.action === 'request' && (data.summary !== args.summary || data.taskId !== (args.taskId ?? null))) return false;
  if (args.action !== 'request' && (data.approvalId !== args.approvalId || data.coalesced)) return false;
  return true;
}

function canReceiveControlTransfer(method, action) {
  if (['open', 'click', 'type', 'scroll', 'screenshot', 'close', 'navigate'].includes(method)) return true;
  if (method === 'debug') return ['evaluate', 'emulate', 'interact'].includes(action);
  if (method === 'network') return ['start', 'har', 'export_frames', 'stop', 'clear_rules', 'frames', 'body', 'replay_prepare',
    'replay_status', 'replay', 'rules', 'resolve'].includes(action);
  if (method === 'selection') return ['consume', 'clear', 'claim', 'admit', 'receipt', 'task_claim', 'task_admit', 'task_receipt'].includes(action);
  if (method === 'page') return ['new', 'switch', 'close'].includes(action);
  if (method === 'file') return ['start', 'cancel'].includes(action);
  if (method === 'upload') return ['request', 'activate', 'submit', 'cancel'].includes(action);
  return method === 'event' && action === 'arm';
}

function normalizeResponse(method, requestId, value, action, args) {
  if (['open', 'observe', 'navigate', 'wait'].includes(method) && ownObject(value) && ownObject(value.data) &&
      !validNavigationMetadata(value.data)) return null;
  if (!ownObject(value) || value.version !== 1 || value.requestId !== requestId || typeof value.code !== 'string' ||
      !/^[A-Z][A-Z0-9_]{0,63}$/.test(value.code) ||
      ![true, false, null].includes(value.executed) ||
      !(value.state === null && value.code === 'UNKNOWN') && !['CLOSED', 'IDLE', 'RUNNING'].includes(value.state) ||
      (value.owner !== undefined && value.owner !== null && !['none', 'human', 'ai'].includes(value.owner)) ||
      !ownObject(value.data)) return null;
  for (const generation of [value.sessionGeneration, value.controlGeneration]) {
    if (generation !== null && (!Number.isSafeInteger(generation) || generation < 0)) return null;
  }
  if (value.code === 'CONTROL_TRANSFER_PENDING') {
    const publicKeys = new Set(['version', 'requestId', 'code', 'executed', 'sessionGeneration', 'controlGeneration', 'state', 'owner', 'data']);
    if (value.executed !== false || Object.keys(value.data).length !== 0 || Object.keys(value).some((key) => !publicKeys.has(key))) return null;
    if (method === 'control') return action === 'request' ? value : null;
    return canReceiveControlTransfer(method, action) ? value : null;
  }
  if (['open', 'navigate'].includes(method) && !validNavigationThumbnail(value, args)) return null;
  if (method === 'debug' && action === 'status' && !validDevToolsCapabilityCoverage(value.data)) return null;
  if (method === 'debug' && action === 'rendering' &&
      ['OK', 'STALE_REVISION', 'SETTINGS_PERSISTENCE_FAILED'].includes(value.code) &&
      !validRenderingResponse(value, args)) return null;
  if (method === 'debug' && ['ax_observe', 'ax_click', 'ax_type', 'ax_scroll'].includes(action) && !validAxActionResponse(value, action, args)) return null;
  if (method === 'debug' && action === 'ax_tree' && value.code === 'OK' &&
      (value.executed !== false || !validAxTreeData(value.data, args))) return null;
  if (method === 'control' && value.code === 'UNKNOWN') {
    const allowed = ['version', 'requestId', 'code', 'executed', 'sessionGeneration', 'controlGeneration', 'state', 'owner', 'data'];
    return !Object.keys(value).some(key => !allowed.includes(key)) && value.executed === null &&
      (Object.keys(value.data).length === 0 || validCancellationUnknownData(value.data) ||
        validTransportUnknownData(value.data, requestId)) ? value : null;
  }
  if (method === 'control') {
    const allowed = ['version', 'requestId', 'code', 'executed', 'sessionGeneration', 'controlGeneration', 'state', 'owner', 'data'];
    if (Object.keys(value).some((key) => !allowed.includes(key)) || value.executed !== false) return null;
    const emptyCodes = {
      request: ['NO_ACTIVE_SESSION', 'AI_CONTROL_ALREADY_ENABLED', 'CONTROL_REQUEST_PENDING', 'DEADLINE_EXPIRED', 'SERVICE_CLOSED', 'INVALID_ARGUMENT'],
      status: ['APPROVAL_NOT_FOUND', 'DEADLINE_EXPIRED', 'SERVICE_CLOSED', 'INVALID_ARGUMENT'],
      cancel: ['APPROVAL_NOT_FOUND', 'APPROVAL_NOT_PENDING', 'APPROVAL_EXPIRED', 'DEADLINE_EXPIRED', 'SERVICE_CLOSED', 'INVALID_ARGUMENT'],
    };
    if (emptyCodes[action]?.includes(value.code)) return Object.keys(value.data).length === 0 ? value : null;
    if (value.code === 'APPROVAL_SURFACE_UNAVAILABLE') {
      if (action !== 'request') return null;
      if (Object.keys(value.data).length === 0) return value;
      return validControlApprovalData(value.data, args, 'CANCELLED', false) ? value : null;
    }
    if (value.code !== 'OK') return null;
    return validControlApprovalData(value.data, args, args.action === 'request' ? 'PENDING' : null,
      args.action === 'request' ? undefined : false) ? value : null;
  }
  if (method === 'selection' && typeof action === 'string' && action.startsWith('task_') &&
      !validTaskFeedbackResponse(value, action, args)) return null;
  if (method === 'event') {
    const data = value.data;
    const id = (candidate) => typeof candidate === 'string' && /^[a-f0-9]{32}$/.test(candidate);
    const identity = (candidate) => exactKeys(candidate, new Set(['sessionGeneration', 'controlGeneration', 'pageId', 'documentGeneration', 'navigationId'])) &&
      Number.isSafeInteger(candidate.sessionGeneration) && candidate.sessionGeneration > 0 &&
      Number.isSafeInteger(candidate.controlGeneration) && candidate.controlGeneration > 0 &&
      validPageReference(candidate.pageId) && Number.isSafeInteger(candidate.documentGeneration) && candidate.documentGeneration >= 0 &&
      Number.isSafeInteger(candidate.navigationId) && candidate.navigationId >= 0;
    const exactData = (...fields) => exactKeys(data, new Set(fields));
    const emptyFailure = ['EVENT_SUBSCRIPTION_INVALID', 'INVALID_ARGUMENT', 'CLOSED', 'AUTOMATION_DISABLED',
      'USER_CONTROLLED', 'STALE_SESSION', 'SESSION_REQUIRED', 'DEADLINE_EXPIRED', 'TASK_PAGE_MISMATCH',
      'JOURNAL_UNAVAILABLE', 'BUSY', 'TASK_NOT_FOUND', 'TASK_UNKNOWN_EFFECT_UNVERIFIED', 'TASK_EXPIRED',
      'TASK_NOT_RUNNING', 'TASK_BUDGET_EXHAUSTED'];
    if (Object.keys(value).some((key) => !['version', 'requestId', 'code', 'executed', 'sessionGeneration',
      'controlGeneration', 'state', 'owner', 'data'].includes(key))) return null;
    // Preserve uncertain delivery exactly; never reinterpret it as an ordinary action result.
    if (value.code === 'UNKNOWN') return value.executed === null ? value : null;
    if (value.code === 'OK' && value.executed === true) {
      if (action === 'arm') {
        if (!exactData('subscriptionId', 'nonce', 'expiresAtElapsedMillis', 'identity') || !id(data.subscriptionId) ||
            typeof data.nonce !== 'string' || !/^[a-f0-9]{64}$/.test(data.nonce) ||
            !Number.isSafeInteger(data.expiresAtElapsedMillis) || data.expiresAtElapsedMillis <= 0 || !identity(data.identity) ||
            data.identity.sessionGeneration !== value.sessionGeneration || data.identity.controlGeneration !== value.controlGeneration) return null;
      } else if (action === 'wait') {
        const base = ['eventId', 'subscriptionId', 'actionRequestId', 'eventKind', 'attribution', 'pageId', 'documentGeneration', 'navigationId'];
        const allowed = new Set([...base, 'url', 'dialog', 'newPageId', 'downloadId', ...NAVIGATION_METADATA_KEYS]);
        if (!validNavigationMetadata(data) || (NAVIGATION_METADATA_KEYS.some((key) => Object.hasOwn(data, key)) &&
            !data.eventKind?.startsWith('navigation_'))) return null;
        if (!base.every((key) => Object.hasOwn(data, key)) || Object.keys(data).some((key) => !allowed.has(key)) ||
            !id(data.eventId) || data.subscriptionId !== args.subscriptionId ||
            typeof data.actionRequestId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(data.actionRequestId) ||
            !['navigation_started', 'navigation_finished', 'navigation_failed', 'new_window', 'download', 'javascript_dialog'].includes(data.eventKind) ||
            !['native_correlated', 'action_scoped_observation'].includes(data.attribution) || !validPageReference(data.pageId) ||
            !Number.isSafeInteger(data.documentGeneration) || data.documentGeneration < 0 ||
            !Number.isSafeInteger(data.navigationId) || data.navigationId < 0 ||
            (data.newPageId !== undefined && (data.eventKind !== 'new_window' || !/^pg-[a-f0-9]{20}$/.test(data.newPageId))) ||
            (data.downloadId !== undefined && (data.eventKind !== 'download' || !/^[a-f0-9]{32}$/.test(data.downloadId))) ||
            (data.url !== undefined && !validSanitizedUrl(data.url))) return null;
        if (data.eventKind === 'javascript_dialog') {
          const dialog = data.dialog;
          if (!ownObject(dialog) || !['alert', 'confirm', 'prompt', 'beforeunload'].includes(dialog.dialogType) ||
              typeof dialog.message !== 'string' || dialog.message.length > 4096 ||
              Object.keys(dialog).some((key) => !['dialogType', 'message', 'promptDefault'].includes(key)) ||
              (dialog.promptDefault !== undefined && (typeof dialog.promptDefault !== 'string' || dialog.promptDefault.length > 4096))) return null;
        } else if (data.dialog !== undefined) return null;
      } else if (action === 'cancel') {
        if (!exactData('cancelled') || data.cancelled !== true) return null;
      } else if (action === 'resolve_dialog') {
        if (!exactData('resolved') || data.resolved !== true) return null;
      } else return null;
    } else if (value.code === 'EVENT_TIMEOUT') {
      if (action !== 'wait' || value.executed !== false || !exactData('subscriptionId', 'matched') ||
          data.subscriptionId !== args.subscriptionId || data.matched !== false) return null;
    } else if (value.code === 'CANCELLED') {
      if (!(value.executed === false && Object.keys(data).length === 0) &&
          !(action === 'wait' && validEventWaitCancellation(value, args))) return null;
    } else if (emptyFailure.includes(value.code)) {
      if (value.executed !== false || Object.keys(data).length !== 0) return null;
    } else return null;
  }
  if (value.code === 'UNKNOWN' && method !== 'event') {
    if (Object.keys(value.data).length && !(Object.keys(value.data).length === 1 && value.data.cancelCode === 'CANCELLED') &&
        ['task', 'navigate', 'open', 'click'].includes(method)) return validBoundUnknownDiagnostics(value, method, action, args) ? value : null;
    if (method === 'wait' && Object.hasOwn(value.data, 'waitScope')) return validConditionWaitUnknown(value, action) ? value : null;
    if (method === 'debug' && ['ax_click', 'ax_type', 'ax_scroll'].includes(action)) return value;
    const validUnknownData = Object.keys(value.data).length === 0 ||
      exactKeys(value.data, new Set(['cancelCode'])) && value.data.cancelCode === 'CANCELLED' ||
      validCancellationUnknownData(value.data) || validTransportUnknownData(value.data, value.requestId);
    return value.executed === null && validUnknownData ? value : null;
  }
  if (method === 'screenshot' && value.code === 'INVALID_REQUEST' && value.executed === false &&
      exactObjectKeys(value.data, new Set(['cancelStage', 'reason', 'validationStage'])) &&
      value.data.cancelStage === 'admission' && value.data.reason === 'invalid_request' &&
      value.data.validationStage === 'request_schema') return value;
  if (value.code === 'CANCELLED' && value.executed === false && validPreDispatchTerminalData(value.data)) return value;
  if (method === 'observe' && typeof value.data.text === 'string') {
    const bounded = truncateUtf8(value.data.text, 4096);
    value.data.text = bounded.text;
    value.data.truncated = Boolean(value.data.truncated) || bounded.truncated;
  }
  if (method === 'observe' && !validObserveScopeMetadata(value.data)) return null;
  if (method === 'collect' && value.code === 'OK' && (value.executed !== true ||
      !validCollectData(value.data, args, value.sessionGeneration, value.controlGeneration) ||
      Buffer.byteLength(JSON.stringify(value), 'utf8') > MAX_FRAME_BYTES)) return null;
  if (method === 'observe' && (hasAnyKey(value.data, ['scopeCoverage', 'frameScopes', 'topFrameOnly', 'openShadowRootsSupported',
      'closedShadowRootsSupported', 'crossOriginFramesSupported']) ||
      (Array.isArray(value.data.elements) && value.data.elements.some((element) =>
        ownObject(element) && hasAnyKey(element, ['frameOrdinal', 'shadowDepth'])))) &&
      Object.keys(value).some((key) => !['version', 'requestId', 'code', 'executed', 'sessionGeneration',
        'controlGeneration', 'state', 'owner', 'data'].includes(key))) return null;
  if (method === 'wait' && ['OK', 'WAIT_TIMEOUT', 'CANCELLED', 'SESSION_REVOKED', 'STALE_SESSION', 'NO_ACTIVE_SESSION', 'AUTOMATION_DISABLED', 'INVALID_REQUEST', 'SESSION_REQUIRED', 'NAVIGATION_CHANGED', 'NAVIGATION_FAILED', 'INVALID_SELECTOR', 'WAIT_PROBE_LIMIT'].includes(value.code)) {
    const data = value.data;
    if (!validConditionWaitCancellation(value) || !validWaitScope(data, action)) return null;
    const scopeKeys = data.waitScope === undefined ? [] : ['waitScope'];
    const diagnosticKeys = data.stage !== undefined ? ['stage', 'cancellationReason', 'nextAction', 'automaticRetryAllowed'] : [];
    const navigationKeys = data.committed !== undefined ? NAVIGATION_METADATA_KEYS : [];
    if (value.code === 'CANCELLED' && Object.keys(data).length === 0 && value.executed === false) return value;
    if (['SESSION_REVOKED', 'STALE_SESSION', 'NO_ACTIVE_SESSION', 'AUTOMATION_DISABLED', 'INVALID_REQUEST', 'SESSION_REQUIRED'].includes(value.code) &&
        Object.keys(data).length === 0 && value.executed === false) return value;
    if (value.code === 'CANCELLED') {
      const shortCancel = exactKeys(data, new Set(['matched', 'condition', 'reason'])) &&
        data.matched === false && data.condition === action && data.reason === 'cancelled';
      const fullCancel = exactKeys(data, new Set(['matched', 'condition', 'pageId', 'navigationId', 'documentGeneration', 'url', 'loadState', 'reason', ...navigationKeys, ...diagnosticKeys, ...scopeKeys])) &&
        data.matched === false && data.condition === action && validPageReference(data.pageId) &&
        (data.navigationId === null || (Number.isSafeInteger(data.navigationId) && data.navigationId >= 0)) &&
        (data.documentGeneration === null || (Number.isSafeInteger(data.documentGeneration) && data.documentGeneration >= 0)) &&
        validSanitizedUrl(data.url) &&
        ['ACCEPTED', 'LOADING', 'FINISHED', 'FAILED', 'unknown'].includes(data.loadState) && data.reason === 'cancelled';
      if ((!shortCancel && !fullCancel) || value.executed !== false) return null;
    } else {
      if (!exactKeys(data, new Set(['matched', 'condition', 'pageId', 'navigationId', 'documentGeneration', 'url', 'loadState', ...navigationKeys, ...diagnosticKeys, ...scopeKeys])) ||
          typeof data.matched !== 'boolean' || data.condition !== action || !validPageReference(data.pageId) ||
          !(data.navigationId === null || (Number.isSafeInteger(data.navigationId) && data.navigationId >= 0)) ||
          !(data.documentGeneration === null || (Number.isSafeInteger(data.documentGeneration) && data.documentGeneration >= 0)) ||
          !validSanitizedUrl(data.url) ||
          !['ACCEPTED', 'LOADING', 'FINISHED', 'FAILED', 'unknown'].includes(data.loadState) ||
          ((value.code === 'OK') !== data.matched) || value.executed !== (value.code === 'OK')) return null;
      if (['NAVIGATION_FAILED', 'INVALID_SELECTOR', 'SESSION_REVOKED', 'STALE_SESSION'].includes(value.code) && data.matched) return null;
    }
  }
  if (method === 'navigate' && ['OK', 'NO_HISTORY'].includes(value.code)) {
    if (value.code === 'OK') {
      const data = value.data;
      if (!exactKeys(data, new Set(['action', 'accepted', 'pageId', 'navigationId', 'url',
          ...(data.committed !== undefined ? NAVIGATION_METADATA_KEYS : []), ...(data.thumbnail !== undefined ? ['thumbnail'] : [])])) ||
          data.action !== action || data.accepted !== true || !validPageReference(data.pageId) ||
          !(data.navigationId === null || (Number.isSafeInteger(data.navigationId) && data.navigationId >= 0)) ||
          !validSanitizedUrl(data.url) || value.executed !== true) return null;
    } else {
      const data = value.data;
      if (!exactKeys(data, new Set(['action', 'accepted', 'canGoBack', 'canGoForward'])) || data.action !== action ||
          data.accepted !== false || typeof data.canGoBack !== 'boolean' || typeof data.canGoForward !== 'boolean' || value.executed !== false) return null;
    }
  }
  if (method === 'locate') {
    const data = value.data;
    const partial = Object.prototype.hasOwnProperty.call(data, 'partial');
    if (partial && (args?.partial !== true || data.partial !== true ||
        !['OK', 'NOT_FOUND', 'AMBIGUOUS', 'LOCATE_LIMIT'].includes(value.code) || data.scopeCoverage?.complete !== false ||
        !validLocateScopeMetadata(data, value.code === 'OK'))) return null;
    if (!partial && ['OK', 'NOT_FOUND', 'AMBIGUOUS'].includes(value.code) && data.scopeCoverage?.complete === false) return null;
    const keys = Object.keys(data).filter(key => key !== 'partial').sort().join(',');
    if (Object.keys(value).some((key) => !['version', 'requestId', 'code', 'executed', 'sessionGeneration',
        'controlGeneration', 'state', 'owner', 'data'].includes(key))) return null;
    if (value.code === 'OK') {
      const hasMetadata = hasAnyKey(data, ['frameOrdinal', 'shadowDepth', 'scopeCoverage', 'frameScopes']);
      const expectedKeys = hasMetadata
        ? 'count,documentGeneration,frameOrdinal,frameScopes,observationId,pageId,ref,scopeCoverage,shadowDepth,strategy'
        : 'count,documentGeneration,observationId,pageId,ref,strategy';
      if (keys !== expectedKeys || value.executed !== true ||
          data.count !== 1 || data.strategy !== action || !validObservationReference(data.ref) ||
          typeof data.pageId !== 'string' || !/^pg-[A-Fa-f0-9]{20}$/.test(data.pageId) ||
          !Number.isSafeInteger(data.documentGeneration) || data.documentGeneration < 0 ||
          !Number.isSafeInteger(data.observationId) || data.observationId < 1 ||
          !validLocateScopeMetadata(data, true)) return null;
    } else if (['NOT_FOUND', 'AMBIGUOUS', 'LOCATE_LIMIT', 'INCOMPLETE_SCOPE', 'INVALID_SELECTOR', 'STALE_REFERENCE', 'EXECUTION_FAILED'].includes(value.code)) {
      if (value.executed !== false) return null;
      const hasMetadata = hasAnyKey(data, ['frameOrdinal', 'shadowDepth', 'scopeCoverage', 'frameScopes']);
      if (value.code === 'INCOMPLETE_SCOPE') {
        if (keys !== 'count,frameScopes,scopeCoverage' || !Number.isSafeInteger(data.count) || data.count < 0 || data.count > 5000 ||
            !validLocateScopeMetadata(data, false) || data.scopeCoverage.complete !== false) return null;
        return value;
      }
      if (hasMetadata && (Object.prototype.hasOwnProperty.call(data, 'frameOrdinal') ||
          Object.prototype.hasOwnProperty.call(data, 'shadowDepth') || !validLocateScopeMetadata(data, false))) return null;
      if (hasMetadata && ['NOT_FOUND', 'AMBIGUOUS'].includes(value.code) && !data.scopeCoverage.complete && !partial) return null;
      if (keys === '' && value.code === 'STALE_REFERENCE') return value;
      if (value.code === 'AMBIGUOUS') {
        const expectedKeys = hasMetadata ? 'count,frameScopes,matches,scopeCoverage' : 'count,matches';
        if (keys !== expectedKeys || !Number.isSafeInteger(data.count) || data.count < 2 || data.count > 5000 ||
            !Array.isArray(data.matches) || data.matches.length < 1 || data.matches.length > 5 ||
            !data.matches.every((match) => ownObject(match) && Object.keys(match).sort().join(',') ===
              (hasMetadata ? 'frameOrdinal,name,role,shadowDepth,tag' : 'name,role,tag') &&
              typeof match.tag === 'string' && match.tag.length >= 1 && match.tag.length <= 128 &&
              typeof match.role === 'string' && match.role.length <= 128 &&
              typeof match.name === 'string' && match.name.length <= 100 && (!hasMetadata ||
                (Number.isSafeInteger(match.frameOrdinal) && match.frameOrdinal >= 0 && match.frameOrdinal <= 16 &&
                 Number.isSafeInteger(match.shadowDepth) && match.shadowDepth >= 0 && match.shadowDepth <= 32 &&
                 (match.frameOrdinal === 0 || data.frameScopes.some((scope) =>
                   scope.ordinal === match.frameOrdinal && scope.status === 'accessible')))))) return null;
        return value;
      }
      const expectedKeys = hasMetadata ? 'count,frameScopes,scopeCoverage' : 'count';
      if (keys !== expectedKeys || !Number.isSafeInteger(data.count) || data.count < 0 || data.count > 5001) return null;
      if (value.code === 'NOT_FOUND' && data.count !== 0) return null;
      if (value.code === 'INVALID_SELECTOR' && data.count !== 0) return null;
      if (value.code === 'STALE_REFERENCE' && data.count !== 0) return null;
      return value;
    } else if (['CLOSED', 'AUTOMATION_DISABLED', 'USER_CONTROLLED', 'STALE_SESSION', 'DEADLINE_EXPIRED',
      'PAGE_NOT_READY', 'BUSY_OR_STALE', 'INVALID_ARGUMENT', 'SESSION_REQUIRED'].includes(value.code)) {
      if (value.executed !== false || keys !== '') return null;
    }
  }
  if (method === 'network' && action === 'frames' && value.code === 'OK') {
    if (value.executed !== false || !validNetworkFramesData(value.data) ||
        Buffer.byteLength(JSON.stringify(value), 'utf8') > MAX_FRAME_BYTES) return null;
  }
  if (method === 'network' && action === 'export_frames' && value.code === 'OK' &&
      (value.executed !== true || !validNetworkFrameExport(value.data))) return null;
  if (method === 'network' && action === 'replay_prepare' && value.code === 'OK' &&
      (value.executed !== true || !validReplayPrepare(value.data))) return null;
  if (method === 'network' && action === 'replay_status' && value.code === 'OK' &&
      (value.executed !== false || !validReplayStatus(value.data, value.sessionGeneration, value.controlGeneration, args))) return null;
  if (method === 'network' && action === 'replay' && value.code === 'OK' &&
      (value.executed !== true || !validNetworkReplayResult(value.data))) return null;
  if (method === 'page' && value.code === 'OK') {
    const pages = value.data.pages;
    if (!Array.isArray(pages) || pages.length < 1 || pages.length > 4 ||
        typeof value.data.activePageId !== 'string' || !/^pg-[A-Fa-f0-9]{20}$/.test(value.data.activePageId) ||
        value.data.limit !== 4) return null;
    const ids = new Set();
    for (const page of pages) {
      if (!ownObject(page) || !exactKeys(page, new Set(['pageId', 'title', 'url', 'active'])) ||
          typeof page.pageId !== 'string' || !/^pg-[A-Fa-f0-9]{20}$/.test(page.pageId) || ids.has(page.pageId) ||
          typeof page.title !== 'string' || page.title.length > 256 || typeof page.url !== 'string' ||
          page.url.length > 2048 || typeof page.active !== 'boolean') return null;
      ids.add(page.pageId);
      if (page.url !== 'about:blank') {
        let url;
        try { url = new URL(page.url); } catch { return null; }
        if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password ||
            url.search || url.hash || url.pathname !== '/') return null;
      }
    }
    if (!ids.has(value.data.activePageId) || pages.filter((page) => page.active).length !== 1 ||
        !pages.some((page) => page.pageId === value.data.activePageId && page.active)) return null;
  }
  if (method === 'file') {
    if (value.data.artifactPath !== undefined) return null;
    if (action === 'list') {
      if (value.code === 'OK') {
        const { items, nextCursor, truncated } = value.data;
        if (!exactKeys(value.data, new Set(['items', 'nextCursor', 'truncated'])) || !Array.isArray(items) || items.length > 20 ||
            !(nextCursor === null || (typeof nextCursor === 'string' && /^[a-f0-9]{32}$/.test(nextCursor))) ||
            typeof truncated !== 'boolean' || !items.every(validDownloadItem) ||
            new Set(items.map((item) => item.id)).size !== items.length) return null;
        if (value.executed !== false) return null;
      }
    } else if (['start', 'status', 'cancel'].includes(action)) {
      if (value.data.item !== undefined) {
        if (!exactKeys(value.data, new Set(['item'])) || !validDownloadItem(value.data.item)) return null;
        if (['status', 'cancel'].includes(action) && args?.id !== undefined && value.data.item.id !== args.id) return null;
        if (action === 'start' && value.code === 'OK' && value.data.item.state === 'FAILED') return null;
        if (action === 'cancel' && value.code === 'OK' && !['CANCELLING', 'CANCELLED'].includes(value.data.item.state)) return null;
        if (action === 'start' && value.code === 'OK' && value.executed !== true) return null;
        if (action === 'start' && value.code === 'DOWNLOAD_FAILED' &&
            (value.executed !== false || value.data.item.state !== 'FAILED')) return null;
        if (action === 'cancel' && value.code === 'OK' && value.executed !== true) return null;
        if (action === 'status' && value.code === 'OK' && value.executed !== false) return null;
      } else if (value.code === 'OK' || value.code === 'DOWNLOAD_FAILED') {
        return null;
      }
    }
  }
  if (method === 'upload' && !validUploadResult(value, action, args || {})) return null;
  if (method === 'screenshot') {
    const expectedStage = SCREENSHOT_FAILURE_STAGES[value.code];
    if (expectedStage) {
      const publicKeys = new Set(['version', 'requestId', 'code', 'executed', 'sessionGeneration',
        'controlGeneration', 'state', 'owner', 'data']);
      if (Object.keys(value).some((key) => !publicKeys.has(key)) || value.executed !== false) return null;
      if (value.code === 'DEADLINE_EXPIRED' && Object.keys(value.data).length === 0) return value;
      const baseKeys = new Set(['cancelStage', 'reason']);
      const diagnosticKeys = new Set(['cancelStage', 'reason', 'primaryBackend', 'primaryFailure',
        'fallbackBackend', 'fallbackFailure', 'debugEnabled', 'userActionRequired']);
      const detailed = exactKeys(value.data, diagnosticKeys) &&
        ['cancelStage', 'reason', 'primaryBackend', 'primaryFailure', 'debugEnabled', 'userActionRequired']
          .every((key) => Object.hasOwn(value.data, key)) &&
        ['virtual_display', 'cdp'].includes(value.data.primaryBackend) &&
        typeof value.data.primaryFailure === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(value.data.primaryFailure) &&
        typeof value.data.debugEnabled === 'boolean' && typeof value.data.userActionRequired === 'boolean' &&
        (value.data.fallbackBackend === undefined && value.data.fallbackFailure === undefined ||
          value.data.fallbackBackend === 'cdp' && typeof value.data.fallbackFailure === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(value.data.fallbackFailure));
      if ((!exactObjectKeys(value.data, baseKeys) && !detailed) ||
          value.data.cancelStage !== expectedStage || value.data.reason !== value.code) return null;
      return value;
    }
    if (value.code !== 'OK') {
      return value.executed === false && Object.keys(value.data).length === 0 ? value : null;
    }
    const artifactPath = value.data.artifactPath;
    if (!validArtifactReference(method, action, artifactPath)) return null;
    if (value.data.screenshotId !== undefined &&
        (typeof value.data.screenshotId !== 'string' || !/^[a-f0-9]{32}$/.test(value.data.screenshotId))) return null;
    if (value.code === 'OK' && (value.executed !== true ||
        !Number.isSafeInteger(value.data.width) || value.data.width < 1 ||
        !Number.isSafeInteger(value.data.height) || value.data.height < 1 ||
        !Number.isSafeInteger(value.data.bytes) || value.data.bytes < 1 || value.data.format !== 'png')) return null;
    const screenshotKeys = new Set(['artifactPath', 'width', 'height', 'format', 'bytes', 'screenshotId',
      'backend', 'primaryBackend', 'primaryFailure', 'fallbackBackend', 'fallbackFailure']);
    value.data = Object.fromEntries(Object.entries(value.data).filter(([key]) => screenshotKeys.has(key)));
    if ((value.data.backend !== undefined && !['virtual_display', 'cdp'].includes(value.data.backend)) ||
        ((value.data.primaryBackend === undefined) !== (value.data.primaryFailure === undefined)) ||
        (value.data.primaryBackend !== undefined && (value.data.primaryBackend !== 'virtual_display' || typeof value.data.primaryFailure !== 'string')) ||
        ((value.data.fallbackBackend === undefined) !== (value.data.fallbackFailure === undefined)) ||
        (value.data.fallbackBackend !== undefined && (value.data.backend !== 'cdp' || value.data.fallbackBackend !== 'virtual_display' || typeof value.data.fallbackFailure !== 'string'))) return null;
  } else if (method === 'type' && value.code === 'OBSCURED') {
    const obscuredBy = value.data.obscuredBy;
    const publicKeys = new Set(['version', 'requestId', 'code', 'executed', 'sessionGeneration',
      'controlGeneration', 'state', 'owner', 'data']);
    if (Object.keys(value).some((key) => !publicKeys.has(key)) || value.executed !== false ||
        !exactObjectKeys(value.data, new Set(['obscuredBy'])) ||
        !exactObjectKeys(obscuredBy, new Set(['tag', 'id', 'className'])) ||
        typeof obscuredBy.tag !== 'string' || obscuredBy.tag.length > 64 ||
        typeof obscuredBy.id !== 'string' || obscuredBy.id.length > 128 ||
        typeof obscuredBy.className !== 'string' || obscuredBy.className.length > 256) return null;
  } else if (method === 'click' && args?.screenshotId !== undefined && value.code === 'OK') {
    const data = value.data;
    if (value.executed !== true || !exactKeys(data, new Set(['screenshotId', 'bitmapX', 'bitmapY', 'cssX', 'cssY', 'targetKind'])) ||
        data.screenshotId !== args.screenshotId || data.bitmapX !== args.x || data.bitmapY !== args.y ||
        !Number.isFinite(data.cssX) || !Number.isFinite(data.cssY) || data.targetKind !== 'DOM_HTML') return null;
  } else if (method === 'network' && ['body', 'har'].includes(action) && value.code === 'OK') {
    if (!validArtifactReference(method, action, value.data.artifactPath)) return null;
  } else if (method === 'network' && action === 'export_frames' && value.code === 'OK') {
    if (!validNetworkFrameExport(value.data)) return null;
  } else if (method === 'selection') {
    if (value.data.artifactPath !== undefined && !validArtifactReference(method, action, value.data.artifactPath)) return null;
  } else if (method === 'file') {
    // File artifacts are validated inside each action-specific item above.
  } else if (method === 'upload') {
    // Upload grants carry opaque ids and fixed /opt/browser paths, validated above.
  } else if (value.data.artifactPath !== undefined) {
    return null;
  }
  if (Buffer.byteLength(JSON.stringify(value), 'utf8') > MAX_FRAME_BYTES) return null;
  return value;
}

function validTaskFeedbackResponse(value, action, args) {
  const data = value && value.data;
  const exact = (object, keys) => ownObject(object) && Object.keys(object).sort().join(',') === [...keys].sort().join(',');
  const publicKeys = new Set(['version', 'requestId', 'code', 'executed', 'sessionGeneration', 'controlGeneration', 'state', 'owner', 'data']);
  if (!ownObject(value) || Object.keys(value).some((key) => !publicKeys.has(key)) || !ownObject(data)) return false;
  if (value.code === 'UNKNOWN') return value.executed === null;
  if (value.code !== 'OK') return value.executed === false && Object.keys(data).length === 0 &&
    ['INVALID_ARGUMENT', 'FEEDBACK_NOT_FOUND', 'DESTINATION_MISMATCH', 'FEEDBACK_CONFLICT',
      'FEEDBACK_ALREADY_ADMITTED', 'FEEDBACK_NOT_ADMITTED', 'LIMIT_EXCEEDED', 'CLOSED', 'STALE_SESSION',
      'SESSION_REQUIRED', 'DEADLINE_EXPIRED'].includes(value.code);
  if (action === 'task_pending') {
    if (value.executed !== false || !exact(data, ['afterSequence', 'nextSequence', 'items']) ||
        data.afterSequence !== args.afterSequence || !Number.isSafeInteger(data.nextSequence) ||
        data.nextSequence < data.afterSequence || !Array.isArray(data.items) || data.items.length > 64) return false;
    let last = data.afterSequence;
    for (const item of data.items) {
      if (!exact(item, ['feedbackId', 'kind', 'taskId', 'revision', 'title', 'text', 'sequence', 'state', 'sessionGeneration']) ||
          typeof item.feedbackId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(item.feedbackId) || item.kind !== 'task_recovery' ||
          typeof item.taskId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(item.taskId) ||
          !Number.isSafeInteger(item.revision) || item.revision < 0 || typeof item.title !== 'string' || item.title.length > 120 ||
          typeof item.text !== 'string' || Buffer.byteLength(item.text, 'utf8') > 64 * 1024 ||
          !Number.isSafeInteger(item.sequence) || item.sequence <= last || item.sequence > data.nextSequence ||
          !['pending', 'claimed'].includes(item.state) || item.sessionGeneration !== value.sessionGeneration) return false;
      last = item.sequence;
    }
    return data.nextSequence === (data.items.length ? last : data.afterSequence);
  }
  if (action === 'task_claim' || action === 'task_admit') {
    return value.executed === true && exact(data, ['feedbackId', 'state', 'requestId', 'payloadDigest']) &&
      data.feedbackId === args.feedbackId && data.requestId === args.requestId && data.payloadDigest === args.payloadDigest &&
      (action === 'task_claim' ? ['pending', 'claimed'].includes(data.state) : data.state === 'admitted');
  }
  return value.executed === true && exact(data, ['feedbackId', 'state', 'deliveryCode']) &&
    data.feedbackId === args.feedbackId && data.deliveryCode === args.deliveryCode &&
    data.state === ({ PROMPT_ACCEPTED: 'prompt_accepted', UNKNOWN: 'unknown', BLOCKED: 'blocked' })[args.deliveryCode];
}

function validNetworkFramesData(data) {
  const allowed = new Set([
    'captureId', 'frames', 'cursor', 'nextCursor', 'total', 'droppedFrames', 'eventDrops', 'recording',
    'includeBodies', 'payloadUnredacted', 'headersRedacted', 'redacted', 'payloadBytes', 'maxFramePayloadBytes', 'maxCapturePayloadBytes', 'maxFrames',
    'maxPageBytes', 'loss', 'truncated',
  ]);
  if (!ownObject(data) || !exactKeys(data, allowed) ||
      !(data.captureId === null || (typeof data.captureId === 'string' && /^cap-[a-f0-9]{20}$/.test(data.captureId))) ||
      !Array.isArray(data.frames) || data.frames.length > 20 ||
      !Number.isSafeInteger(data.cursor) || data.cursor < 0 ||
      !(data.nextCursor === null || (Number.isSafeInteger(data.nextCursor) && data.nextCursor >= 0 && data.nextCursor <= 1000)) ||
      !Number.isSafeInteger(data.total) || data.total < 0 || data.total > 1000 || data.cursor > data.total ||
      !Number.isSafeInteger(data.droppedFrames) || data.droppedFrames < 0 ||
      !Number.isSafeInteger(data.eventDrops) || data.eventDrops < 0 ||
      typeof data.recording !== 'boolean' || typeof data.includeBodies !== 'boolean' || data.payloadUnredacted !== data.includeBodies ||
      typeof data.headersRedacted !== 'boolean' || data.redacted !== !data.includeBodies ||
      !Number.isSafeInteger(data.payloadBytes) || data.payloadBytes < 0 || data.payloadBytes > 1024 * 1024 ||
      data.maxFramePayloadBytes !== 8192 || data.maxCapturePayloadBytes !== 1024 * 1024 ||
      data.maxFrames !== 1000 || data.maxPageBytes !== 14 * 1024 ||
      typeof data.loss !== 'boolean' || data.truncated !== data.loss ||
      ((data.nextCursor === null) !== (data.cursor + data.frames.length >= data.total)) ||
      data.frames.some((frame) => !validNetworkWebSocketEvent(frame, data.includeBodies)) ||
      Buffer.byteLength(JSON.stringify(data), 'utf8') > data.maxPageBytes) return false;
  if ((data.droppedFrames > 0 || data.eventDrops > 0 || data.frames.some((frame) =>
    frame.metadataIncomplete === true || frame.frameTruncated === true || frame.urlTruncated === true ||
    frame.statusTextTruncated === true || frame.detailTruncated === true || frame.requestHeadersTruncated === true ||
    frame.responseHeadersTruncated === true)) && !data.loss) return false;
  return true;
}

function validNetworkWebSocketEvent(frame, includeBodies) {
  if (!ownObject(frame) || typeof frame.webSocketId !== 'string' || !/^ws-[a-f0-9]{20}$/.test(frame.webSocketId) ||
      !Number.isSafeInteger(frame.sequence) || frame.sequence < 1 || typeof frame.kind !== 'string') return false;
  const allowedCommon = ['sequence', 'webSocketId', 'kind', 'timestamp', 'metadataIncomplete'];
  const knownKinds = new Set(['created', 'handshakeRequest', 'handshakeResponse', 'sent', 'received', 'error', 'closed']);
  if (!knownKinds.has(frame.kind) ||
      (frame.timestamp !== undefined && (typeof frame.timestamp !== 'number' || !Number.isFinite(frame.timestamp) || frame.timestamp < 0)) ||
      (frame.kind !== 'created' && frame.timestamp === undefined && frame.metadataIncomplete !== true) ||
      (frame.metadataIncomplete !== undefined && frame.metadataIncomplete !== true)) return false;
  let keys = [...allowedCommon];
  if (frame.kind === 'created') {
    keys.push('url', 'urlTruncated');
    if (frame.url !== undefined) {
      if (typeof frame.url !== 'string' || frame.url.length > 2048) return false;
      try {
        const url = new URL(frame.url);
        if (!['ws:', 'wss:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) return false;
      } catch { return false; }
    } else if (frame.metadataIncomplete !== true) return false;
    if (frame.urlTruncated !== undefined && frame.urlTruncated !== true) return false;
  } else if (frame.kind === 'handshakeRequest') {
    keys.push('requestHeaders', 'wallTime', 'requestHeadersRedacted', 'requestHeadersTruncated');
    if (frame.requestHeaders !== undefined && !validNetworkWebSocketHeaders(frame.requestHeaders)) return false;
    if (frame.wallTime !== undefined && (typeof frame.wallTime !== 'number' || !Number.isFinite(frame.wallTime) || frame.wallTime < 0)) return false;
    if ((frame.requestHeaders === undefined || frame.wallTime === undefined) && frame.metadataIncomplete !== true) return false;
  } else if (frame.kind === 'handshakeResponse') {
    keys.push('status', 'statusText', 'responseHeaders', 'responseHeadersRedacted', 'responseHeadersTruncated', 'statusTextTruncated');
    if (frame.status !== undefined && (!Number.isInteger(frame.status) || (frame.status !== -1 && (frame.status < 100 || frame.status > 599)))) return false;
    if (frame.statusText !== undefined && (typeof frame.statusText !== 'string' || frame.statusText.length > 128)) return false;
    if (frame.responseHeaders !== undefined && !validNetworkWebSocketHeaders(frame.responseHeaders)) return false;
    if ((frame.status === undefined || frame.status === -1 || frame.statusText === undefined || frame.responseHeaders === undefined) && frame.metadataIncomplete !== true) return false;
  } else if (frame.kind === 'sent' || frame.kind === 'received') {
    keys.push('opcode', 'direction', 'masked', 'payloadEncoding', 'payloadBytes', 'payloadOmitted', 'payloadUnavailable', 'payload', 'frameTruncated');
    if (frame.metadataIncomplete !== true && ['opcode', 'direction', 'masked', 'payloadEncoding', 'payloadBytes'].some(key => frame[key] === undefined)) return false;
    if (frame.opcode !== undefined && (!Number.isInteger(frame.opcode) || frame.opcode < 0 || frame.opcode > 15) ||
        frame.direction !== undefined && frame.direction !== (frame.kind === 'sent' ? 'outgoing' : 'incoming') ||
        frame.masked !== undefined && typeof frame.masked !== 'boolean' ||
        frame.payloadEncoding !== undefined && !['text', 'base64'].includes(frame.payloadEncoding) ||
        frame.opcode !== undefined && frame.payloadEncoding !== undefined && (frame.opcode === 1) !== (frame.payloadEncoding === 'text') ||
        frame.payloadBytes !== undefined && (!Number.isSafeInteger(frame.payloadBytes) || frame.payloadBytes < 0 || frame.payloadBytes > 2 * 1024 * 1024)) return false;
    if (frame.payloadOmitted !== undefined && frame.payloadOmitted !== true) return false;
    if (frame.payloadUnavailable !== undefined && frame.payloadUnavailable !== true) return false;
    if (frame.frameTruncated !== undefined && frame.frameTruncated !== true) return false;
    if (frame.payload !== undefined) {
      if (typeof frame.payload !== 'string' || !includeBodies || frame.payloadOmitted || frame.payloadUnavailable || frame.opcode === undefined || frame.payloadEncoding === undefined || frame.payloadBytes === undefined) return false;
      if (frame.payloadEncoding === 'text' && Buffer.byteLength(frame.payload, 'utf8') > 8192) return false;
      if (frame.payloadEncoding === 'base64' && (!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(frame.payload) || Buffer.from(frame.payload, 'base64').length > 8192)) return false;
    } else if (includeBodies && !frame.payloadUnavailable && !frame.frameTruncated && frame.payloadBytes !== 0 && frame.metadataIncomplete !== true) return false;
    if (!includeBodies && (frame.payload !== undefined || frame.payloadOmitted !== true && frame.metadataIncomplete !== true)) return false;
    if (includeBodies && frame.payloadOmitted === true) return false;
    if (frame.payloadUnavailable === true && frame.metadataIncomplete !== true) return false;
  } else if (frame.kind === 'error') {
    keys.push('detail', 'detailRedacted', 'detailTruncated');
    if (frame.detail !== undefined && (typeof frame.detail !== 'string' || frame.detail.length > 256)) return false;
    if (frame.detailRedacted !== undefined && frame.detailRedacted !== true) return false;
    if (frame.detailTruncated !== undefined && frame.detailTruncated !== true) return false;
    if (frame.detail === undefined && frame.metadataIncomplete !== true) return false;
  }
  if (!exactKeys(frame, new Set(keys))) return false;
  for (const flag of ['requestHeadersRedacted', 'responseHeadersRedacted']) if (frame[flag] !== undefined && frame[flag] !== true) return false;
  for (const flag of ['requestHeadersTruncated', 'responseHeadersTruncated', 'statusTextTruncated']) if (frame[flag] !== undefined && frame[flag] !== true) return false;
  return true;
}

function validNetworkWebSocketHeaders(headers) {
  const sensitive = /(?:token|secret|password|csrf|auth|api[-_]?key|cookie)/i;
  return ownObject(headers) && Object.keys(headers).length <= 16 && Object.entries(headers).every(([key, value]) =>
    key.length <= 64 && !sensitive.test(key) && typeof value === 'string' && value.length <= 256 && !/[\r\n]/.test(value));
}

function connectFrame(frame, config, timeoutMs, signal) {
  return new Promise((resolve) => {
    let settled = false;
    let connected = false;
    let bytes = Buffer.alloc(0);
    const socket = net.createConnection({ path: `\0${config.socketName}` });
    const finish = (value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (signal && abort) signal.removeEventListener('abort', abort);
      socket.destroy();
      resolve(value);
    };
    const abort = signal ? () => finish(null) : null;
    const timer = setTimeout(() => finish(null), Math.max(1, timeoutMs));
    if (signal) {
      if (signal.aborted) return finish(null);
      signal.addEventListener('abort', abort, { once: true });
    }
    socket.on('connect', () => {
      connected = true;
      socket.write(`${JSON.stringify({ version: 1, token: config.token, ...frame })}\n`);
    });
    socket.on('data', (chunk) => {
      bytes = Buffer.concat([bytes, chunk]);
      if (bytes.length > MAX_FRAME_BYTES) return finish(null);
      const newline = bytes.indexOf(0x0a);
      if (newline < 0) return;
      try {
        const parsed = JSON.parse(bytes.subarray(0, newline).toString('utf8'));
        finish(ownObject(parsed) ? parsed : null);
      } catch { finish(null); }
    });
    socket.on('error', (error) => finish(!connected &&
      ['ECONNREFUSED', 'ENOENT', 'EACCES', 'EPERM'].includes(error.code)
      ? { [PRECONNECT_FAILURE]: error.code } : null));
    socket.on('end', () => finish(null));
  });
}

async function execute(method, request, channelConfig, now = Date.now()) {
  const budget = request.deadlineAtMillis - now;
  const requestAbort = new AbortController();
  let signalHandler;
  let cancelResolve;
  const interrupted = new Promise((resolve) => { cancelResolve = resolve; });
  let cancellationStarted = false;
  let confirmedCancellation = null;
  signalHandler = async () => {
    if (cancellationStarted) return;
    cancellationStarted = true;
    if (method !== 'wait' && !(method === 'event' && request.args.action === 'wait')) {
      cancelResolve(response(request.requestId, 'UNKNOWN', null, null,
        cancellationUnknownData(null)));
      requestAbort.abort();
      return;
    }
    const ack = await connectFrame({ op: 'cancel', requestId: request.requestId }, channelConfig, 1_500);
    if (validCancelReceipt(ack, request.requestId) || legacyCancelReceipt(ack, request.requestId)) {
      confirmedCancellation = ack;
      return;
    }
    const unknown = response(request.requestId, 'UNKNOWN', 'RUNNING', null,
      cancellationUnknownData(ack || null));
    requestAbort.abort();
    cancelResolve(unknown);
  };
  process.once('SIGTERM', signalHandler);
  let result;
  try {
    result = await Promise.race([
      connectFrame({ op: 'request', request }, channelConfig, budget, requestAbort.signal),
      interrupted,
    ]);
  } finally {
    process.removeListener('SIGTERM', signalHandler);
  }
  if (result?.[PRECONNECT_FAILURE]) {
    const unavailable = response(request.requestId, 'CHANNEL_UNAVAILABLE', 'CLOSED', false);
    unavailable.data.transportError = result[PRECONNECT_FAILURE];
    return unavailable;
  }
  const normalized = normalizeResponse(method, request.requestId, result,
    request.args.condition || request.args.action || request.args.strategy, request.args);
  if (normalized && Buffer.byteLength(JSON.stringify(normalized)) <= MAX_FRAME_BYTES) {
    return normalized;
  }
  if (confirmedCancellation) {
    return response(request.requestId, 'UNKNOWN', null, null,
      cancellationUnknownData(confirmedCancellation));
  }
  const cancelBudget = Math.min(1_500, Math.max(1, request.deadlineAtMillis - Date.now() + 1_500));
  const cancelAck = await connectFrame({ op: 'cancel', requestId: request.requestId }, channelConfig, cancelBudget);
  const unknown = response(request.requestId, 'UNKNOWN', null);
  if (validCancelReceipt(cancelAck, request.requestId) || legacyCancelReceipt(cancelAck, request.requestId)) {
    Object.assign(unknown.data, cancellationUnknownData(cancelAck));
  } else {
    unknown.data = cancellationUnknownData(null);
  }
  return unknown;
}

function parseArguments(argv) {
  if (argv.length === 1 && ['--help', '-h'].includes(argv[0])) return { help: true };
  if (argv.length === 1 && argv[0] === '--version') return { version: true };
  if (argv[0] === 'cancel' && argv.length === 3 && argv[1] === '--request-id' && /^[A-Za-z0-9_-]{1,64}$/.test(argv[2])) {
    return { cancelRequestId: argv[2] };
  }
  const method = argv[0];
  if (!METHODS.has(method)) throw new Error('expected a fixed browser command');
  if (argv.length !== 3 || argv[1] !== '--request-json') {
    throw new Error(`usage: browser ${method} --request-json <JSON>`);
  }
  return { method, raw: argv[2] };
}

async function cancelRequest(requestId, channelConfig, timeoutMs = 2_000) {
  const result = await connectFrame({ op: 'cancel', requestId }, channelConfig, timeoutMs);
  if (validCancelReceipt(result, requestId) || legacyCancelReceipt(result, requestId)) return result;
  if (!ownObject(result)) {
    return response(requestId, 'UNKNOWN', 'RUNNING', null, cancellationUnknownData(null));
  }
  return response(requestId, 'UNKNOWN', 'RUNNING', null, {
    ...cancellationUnknownData(null), reason: 'invalid_runtime_receipt',
  });
}

async function main(argv) {
  if (argv[0] === '--serve-child') {
    await runServeChild(argv[1]);
    return;
  }
  if (argv[0] === 'serve') {
    let parsed;
    try { parsed = parseServeArguments(argv.slice(1)); } catch (error) {
      process.stdout.write(`${JSON.stringify({
        version: 1, requestId: crypto.randomBytes(12).toString('hex'), code: 'CLI_USAGE',
        executed: false, state: 'CLOSED', data: { message: error.message },
      })}\n`);
      process.exitCode = 2;
      return;
    }
    const result = await runServeCommand(parsed);
    process.stdout.write(`${JSON.stringify(result)}\n`);
    if (result.code !== 'OK') process.exitCode = 3;
    return;
  }
  if (argv[0] === 'cancel') {
    try {
      const parsed = parseArguments(argv);
      if (!parsed.cancelRequestId) throw new Error('usage: browser cancel --request-id <requestId>');
      const result = await cancelRequest(parsed.cancelRequestId, readChannelConfig());
      process.stdout.write(`${JSON.stringify(result)}\n`);
    } catch {
      process.stdout.write(`${JSON.stringify(response(crypto.randomBytes(12).toString('hex'), 'CLI_USAGE', 'CLOSED', false))}\n`);
      process.exitCode = 2;
    }
    return;
  }
  const parsed = parseArguments(argv);
  if (parsed.help) {
    process.stdout.write(`usage: browser <${[...METHODS].join('|')}> --request-json <JSON>\n       browser cancel --request-id <id>\n       browser serve <start|register|status|list|logs|stop|unregister> [options]\n`);
    return;
  }
  if (parsed.version) {
    process.stdout.write('browser-cli 1\n');
    return;
  }
  const request = validateRequest(parsed.method, parsed.raw);
  let result;
  try {
    const channelConfig = readChannelConfig();
    result = await execute(parsed.method, request, channelConfig);
  } catch {
    result = response(request.requestId, 'BROKER_UNAVAILABLE');
  }
  const output = JSON.stringify(result);
  if (Buffer.byteLength(output, 'utf8') > MAX_FRAME_BYTES) throw new Error('broker response exceeds the 256 KiB limit');
  process.stdout.write(`${output}\n`);
}

module.exports = { MAX_FRAME_BYTES, MAX_REQUEST_FRAME_BYTES, cancelRequest, execute, main, normalizeResponse, parseArguments, readChannelConfig, truncateUtf8, validDownloadItem, validateRequest };
