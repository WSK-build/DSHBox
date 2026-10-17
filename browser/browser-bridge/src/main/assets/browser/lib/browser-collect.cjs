'use strict';

const DEFAULTS = Object.freeze({maxWindows: 24, maxDurationMs: 20_000, maxBytes: 512 * 1024,
  maxRecords: 80, maxContentChars: 4096, scrollSteps: 1});
const HARD_LIMITS = Object.freeze({maxWindows: 100, maxDurationMs: 60_000, maxBytes: 5 * 1024 * 1024,
  maxRecords: 200, maxContentChars: 16_384, scrollSteps: 10});

function commandData(value) {
  if (!value || typeof value !== 'object' || value.code !== 'OK' || !value.data || typeof value.data !== 'object') return null;
  return value.data;
}

function identityOf(data) {
  const id = data && data.identity;
  if (!id || typeof id.pageId !== 'string' || !id.pageId ||
      !Number.isSafeInteger(id.documentGeneration) || id.documentGeneration < 0 ||
      !Number.isSafeInteger(id.navigationId) || id.navigationId < 0 ||
      !Number.isSafeInteger(id.observationId) || !Number.isSafeInteger(id.sessionGeneration) || id.sessionGeneration < 1 ||
      !Number.isSafeInteger(id.controlGeneration) || id.controlGeneration < 1) return null;
  return `${id.pageId}\u0000${id.documentGeneration}\u0000${id.navigationId}\u0000${id.sessionGeneration ?? ''}\u0000${id.controlGeneration ?? ''}`;
}

function validOptions(options) {
  return options && typeof options.containerSelector === 'string' && options.containerSelector.trim() &&
    typeof options.messageSelector === 'string' && options.messageSelector.trim() &&
    typeof options.keyAttribute === 'string' && options.keyAttribute.trim() &&
    (!options.orderAttribute || typeof options.orderAttribute === 'string') &&
    (!options.authorSelector || typeof options.authorSelector === 'string') &&
    (!options.contentSelector || typeof options.contentSelector === 'string');
}

function declaredScope(options) {
  return typeof options.declaredRange === 'string' && options.declaredRange.trim().length > 0 &&
    options.declaredRange.trim().length <= 128 ? options.declaredRange.trim() : null;
}

function utf8Size(value) {
  return Buffer.byteLength(JSON.stringify(value), 'utf8');
}

function isAbort(signal) { return Boolean(signal && signal.aborted); }

function sequenceCoverage(records, totalCount) {
  if (!Number.isSafeInteger(totalCount) || totalCount < 0 || records.length !== totalCount) return false;
  if (totalCount === 0) return true;
  if (!records.every(record => record.key && Number.isSafeInteger(record.order) && record.author && record.content)) return false;
  const orders = records.map(record => record.order).sort((a, b) => a - b);
  const starts = orders[0] === 0 ? 0 : orders[0] === 1 ? 1 : -1;
  return starts >= 0 && orders.every((order, index) => order === starts + index);
}

/**
 * Collect rendered windows from a page-adapted virtual list. `invokeBrowser` is the
 * existing command transport; this helper deliberately does not infer page identity.
 */
async function collectVirtualizedWindow({invokeBrowser, options, budgets = {}, signal, onProgress} = {}) {
  const limits = {...DEFAULTS, ...budgets};
  const result = (records, complete, reason, extra = {}) => ({records, complete, reason, ...extra});
  if (typeof invokeBrowser !== 'function' || !validOptions(options))
    return result([], false, 'INVALID_CONFIGURATION');
  const bounds = ['maxWindows', 'maxDurationMs', 'maxBytes', 'maxRecords', 'maxContentChars', 'scrollSteps'];
  if (bounds.some(k => !Number.isSafeInteger(limits[k]) || limits[k] < 1 || limits[k] > HARD_LIMITS[k]))
    return result([], false, 'INVALID_BUDGET');
  const started = Date.now();
  const deadlineAt = started + limits.maxDurationMs;
  const recordsByKey = new Map();
  const unkeyed = [];
  let bytes = 0, expectedIdentity = null, current = null, windows = 0;
  let totalCount = null, sawTruncation = false, orderConflict = false, startWasProven = false;
  const partial = new Set();
  const scope = declaredScope(options);
  if (!scope) partial.add('COMPLETION_SCOPE_UNDECLARED');
  if (!options.fromTop) partial.add('STARTED_AT_CURRENT_POSITION');

  const call = async (method, args) => {
    if (isAbort(signal)) return {reason: 'CANCELLED'};
    const remaining = deadlineAt - Date.now();
    if (remaining <= 0) return {reason: method === 'scroll' ? 'SCROLL_OUTCOME_UNKNOWN' : 'TIME_BUDGET'};
    const requestController = new AbortController();
    let timer, abortListener;
    const interrupted = new Promise(resolve => {
      timer = setTimeout(() => {
        requestController.abort();
        resolve({reason:method === 'scroll' ? 'SCROLL_OUTCOME_UNKNOWN' : 'TIME_BUDGET'});
      }, remaining);
      if (signal) {
        abortListener = () => {
          requestController.abort();
          resolve({reason:method === 'scroll' ? 'SCROLL_OUTCOME_UNKNOWN' : 'CANCELLED'});
        };
        signal.addEventListener('abort', abortListener, {once:true});
      }
    });
    let response;
    try {
      const pending = Promise.resolve().then(() => invokeBrowser(method, args,
        {signal:requestController.signal,deadlineAt})).then(value => ({response:value}), () => ({reason:'COMMAND_FAILED'}));
      const outcome = await Promise.race([pending, interrupted]);
      if (outcome.reason) return outcome;
      response = outcome.response;
    } finally {
      clearTimeout(timer);
      if (signal && abortListener) signal.removeEventListener('abort', abortListener);
    }
    try { bytes += utf8Size(response); } catch (_) { return {reason:'INVALID_RESPONSE'}; }
    if (bytes > limits.maxBytes) return {reason: 'BYTE_BUDGET'};
    return {response};
  };
  const scrollFailure = response => {
    if (response && response.data && response.data.code === 'NO_EFFECT') return 'SCROLL_NO_PROGRESS';
    const code = response && response.data && typeof response.data.code === 'string' ? response.data.code :
      response && typeof response.code === 'string' ? response.code : 'UNKNOWN';
    return code === 'OK' ? null : `SCROLL_${code}`;
  };

  const collect = async () => {
    if (windows >= limits.maxWindows) return {reason: 'WINDOW_BUDGET'};
    const callResult = await call('collect', {action: 'window', containerSelector: options.containerSelector,
      messageSelector: options.messageSelector, keyAttribute: options.keyAttribute,
      authorSelector: options.authorSelector || '', authorAttribute: options.authorAttribute || '',
      contentSelector: options.contentSelector || '', orderAttribute: options.orderAttribute || '',
      totalCountSelector: options.totalCountSelector || '', totalCountAttribute: options.totalCountAttribute || '',
      declaredRange: scope || '',
      maxRecords: limits.maxRecords, maxContentChars: limits.maxContentChars});
    if (callResult.reason) return callResult;
    const data = commandData(callResult.response);
    if (!data) {
      const code = callResult.response && typeof callResult.response.code === 'string' ? callResult.response.code : '';
      return {reason:code === 'LIMIT_EXCEEDED' ? 'RESPONSE_BUDGET_EXCEEDED' :
        code === 'STALE_REFERENCE' ? 'STALE_REFERENCE' : 'INVALID_RESPONSE'};
    }
    if (data.action !== 'window' || data.declaredRange !== scope || !Array.isArray(data.records) || !data.container ||
        typeof data.containerNodeId !== 'string' || !data.containerNodeId ||
        typeof data.container.startReached !== 'boolean' || typeof data.container.endReached !== 'boolean' ||
        typeof data.truncated !== 'boolean') return {reason: 'INVALID_RESPONSE'};
    const identity = identityOf(data);
    if (!identity) return {reason: 'MISSING_IDENTITY'};
    if (expectedIdentity && identity !== expectedIdentity) {
      const oldId = data.identity;
      const reason = oldId.pageId !== current.identity.pageId ? 'PAGE_CHANGED' :
        oldId.documentGeneration !== current.identity.documentGeneration ? 'DOCUMENT_CHANGED' :
        oldId.navigationId !== current.identity.navigationId ? 'NAVIGATION_CHANGED' :
        oldId.sessionGeneration !== current.identity.sessionGeneration ? 'SESSION_CHANGED' :
        oldId.controlGeneration !== current.identity.controlGeneration ? 'CONTROL_CHANGED' : 'OBSERVATION_CHANGED';
      return {reason};
    }
    expectedIdentity = identity;
    current = data;
    windows++;
    if (Number.isSafeInteger(data.container.totalCount)) {
      if (totalCount !== null && totalCount !== data.container.totalCount) partial.add('TOTAL_COUNT_CHANGED');
      totalCount = data.container.totalCount;
    } else partial.add('TOTAL_COUNT_UNAVAILABLE');
    sawTruncation ||= data.truncated;
    if (data.truncated) partial.add('WINDOW_TRUNCATED');
    for (const candidate of data.records) {
      if (!candidate || typeof candidate.content !== 'string') { partial.add('INVALID_RECORD'); continue; }
      const record = {key: typeof candidate.key === 'string' && candidate.key ? candidate.key : null,
        author: typeof candidate.author === 'string' && candidate.author ? candidate.author : null,
        content: candidate.content, order: Number.isSafeInteger(candidate.order) ? candidate.order : null};
      if (!record.key) { unkeyed.push(record); partial.add('STABLE_KEY_UNAVAILABLE'); continue; }
      if (record.order === null) partial.add('ORDER_UNAVAILABLE');
      const previous = recordsByKey.get(record.key);
      if (previous && previous.order !== null && record.order !== null && previous.order !== record.order)
        orderConflict = true;
      recordsByKey.set(record.key, record);
    }
    if (typeof onProgress === 'function') onProgress({windows, records: recordsByKey.size + unkeyed.length,
      bytes, startReached: data.container.startReached, endReached: data.container.endReached});
    return {data};
  };

  let fetched = await collect();
  if (fetched.reason) { partial.add(fetched.reason); return result([], false, fetched.reason, {windows, bytes}); }
  startWasProven = current.container.startReached;
  if (options.fromTop) {
    while (!current.container.startReached) {
      const scrolled = await call('scroll', {nodeId: current.containerNodeId, direction: 'up', steps: limits.scrollSteps});
      if (scrolled.reason) { partial.add(scrolled.reason); break; }
      const failure = scrollFailure(scrolled.response);
      if (failure) { partial.add(failure === 'SCROLL_NO_PROGRESS' ? 'TOP_NOT_REACHED' : failure); break; }
      const before = current.container.scrollTop;
      fetched = await collect();
      if (fetched.reason) { partial.add(fetched.reason); break; }
      if (current.container.scrollTop >= before && !current.container.startReached) { partial.add('TOP_NOT_REACHED'); break; }
    }
    startWasProven = current.container.startReached;
  }
  const stopAfterTopFailure = options.fromTop && !startWasProven;
  while (!stopAfterTopFailure && !current.container.endReached && !partial.has('CANCELLED') &&
      !partial.has('SCROLL_OUTCOME_UNKNOWN') && !partial.has('TIME_BUDGET')) {
    if (isAbort(signal)) { partial.add('CANCELLED'); break; }
    const before = current.container.scrollTop;
    const nodeId = current.containerNodeId;
    const scrolled = await call('scroll', {nodeId, direction: 'down', steps: limits.scrollSteps});
    if (scrolled.reason) { partial.add(scrolled.reason); break; }
    const failure = scrollFailure(scrolled.response);
    if (failure) { partial.add(failure); break; }
    fetched = await collect();
    if (fetched.reason) { partial.add(fetched.reason); break; }
    if (current.container.scrollTop <= before && !current.container.endReached) {
      partial.add('SCROLL_NO_PROGRESS'); break;
    }
  }
  if (!current.container.endReached) partial.add('END_NOT_REACHED');
  if (!options.fromTop || !startWasProven) partial.add('START_NOT_PROVEN');
  if (orderConflict) partial.add('ORDER_CONFLICT');
  const records = [...recordsByKey.values(), ...unkeyed].sort((a, b) =>
    a.order === null ? (b.order === null ? 0 : 1) : b.order === null ? -1 : a.order - b.order);
  const complete = partial.size === 0 && !sawTruncation && startWasProven && current.container.endReached &&
    Boolean(scope) && sequenceCoverage(records, totalCount);
  if (!complete && partial.size === 0) partial.add('ORDER_COVERAGE_UNPROVEN');
  return result(records, complete, complete ? null : [...partial].join(','), {windows, bytes, totalCount, scope});
}

module.exports = {collectVirtualizedWindow};
