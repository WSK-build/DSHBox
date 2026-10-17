'use strict';

const {collectVirtualizedWindow} = require('./browser-collect.cjs');

const TASK_ID = /^[A-Za-z0-9_-]{1,64}$/;

function normalizedEnvelope(result) {
  if (!result || typeof result !== 'object' || typeof result.code !== 'string') return null;
  let data = {};
  if (typeof result.dataJson === 'string' && result.dataJson) {
    try { data = JSON.parse(result.dataJson); } catch (_) { return null; }
  }
  return {code: result.code, executed: result.executed, state: result.state, data};
}

function referenceMatchesIdentity(reference, identity) {
  if (typeof reference !== 'string' || !identity) return false;
  const match = /^b:([1-9][0-9]{0,15}):([1-9][0-9]{0,15}):([0-9]{1,16}):([1-9][0-9]{0,15}):[a-f0-9]{32}:0$/.exec(reference);
  return Boolean(match && Number(match[1]) === identity.sessionGeneration &&
    Number(match[2]) === identity.controlGeneration && Number(match[3]) === identity.documentGeneration &&
    Number(match[4]) === identity.observationId);
}

/** Runs a bounded multi-window scan through the packaged Browser request client. */
async function collectBrowserWindowSet({browserRequest, taskId, sessionGeneration, controlGeneration,
  options, budgets, signal, dependencies = {}, onProgress} = {}) {
  const partial = (reason) => ({records: [], complete: false, coverageComplete: false,
    coverageScope: 'entire_selected_container', atomicSnapshot: false,
    snapshotConsistency: 'non_atomic', reason, windows: 0, bytes: 0});
  if (typeof browserRequest !== 'function') return partial('TRANSPORT_UNAVAILABLE');
  if (typeof taskId !== 'string' || !TASK_ID.test(taskId)) return partial('TASK_ID_REQUIRED');
  if (!Number.isSafeInteger(sessionGeneration) || sessionGeneration < 1 ||
      !Number.isSafeInteger(controlGeneration) || controlGeneration < 1) return partial('GENERATION_UNAVAILABLE');
  if (options && options.excludeSelector !== undefined && (typeof options.excludeSelector !== 'string' ||
      options.excludeSelector.length < 1 || options.excludeSelector.length > 512 || /[\u0000-\u001f\u007f]/.test(options.excludeSelector)))
    return partial('INVALID_EXCLUDE_SELECTOR');

  const invokeBrowser = async (method, args, request) => {
    const result = await browserRequest(method, args,
      {taskId, sessionGeneration, controlGeneration, owner: 'ai'},
      {...dependencies, signal: request.signal});
    const envelope = normalizedEnvelope(result);
    if (method === 'collect' && envelope && envelope.code === 'OK') {
      const identity = envelope.data && envelope.data.identity;
      if (!identity || identity.sessionGeneration !== sessionGeneration || identity.controlGeneration !== controlGeneration ||
          !referenceMatchesIdentity(envelope.data.containerNodeId, identity)) {
        return {code: 'IDENTITY_MISMATCH', executed: null, state: 'RUNNING', data: {}};
      }
    }
    return envelope;
  };
  const scan = await collectVirtualizedWindow({invokeBrowser, options, budgets, signal, onProgress});
  return {...scan, coverageComplete: scan.complete === true, complete: scan.complete === true,
    atomicSnapshot: false, snapshotConsistency: 'non_atomic', taskId,
    sessionGeneration, controlGeneration};
}

module.exports = {collectBrowserWindowSet};
