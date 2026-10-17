'use strict';

const assert = require('node:assert/strict');
const {collectBrowserWindowSet} = require('../../assets/plugins/browser-use/plugin/lib/browser-collect-client.js');

function row(key, order) { return {key, author: 'author', content: `text-${key}`, order}; }
function payload({observationId, top, endReached, records}) {
  return {action: 'window', declaredRange: 'visible conversation', identity: {
    pageId: 'pg-0123456789abcdefabcd', documentGeneration: 2, navigationId: 3,
    observationId, sessionGeneration: 7, controlGeneration: 4},
    containerNodeId: `b:7:4:2:${observationId}:${String(observationId).padStart(32, 'a')}:0`,
    container: {scrollTop: top, scrollHeight: 600, clientHeight: 100,
      startReached: top === 0, endReached, totalCount: 2}, records, truncated: false, excludedCount: 0};
}
const options = {containerSelector: '#list', messageSelector: '.row', keyAttribute: 'data-id',
  contentSelector: '.text', orderAttribute: 'data-order', declaredRange: 'visible conversation', fromTop: true};

(async () => {
  const calls = [];
  let index = 0;
  const browserRequest = async (method, args, envelope, dependencies) => {
    calls.push({method, args, envelope, signal: dependencies.signal});
    if (method === 'scroll') return {code: 'OK', executed: true, dataJson: JSON.stringify({code: 'OK'})};
    index++;
    const data = index === 1 ? payload({observationId: 1, top: 0, endReached: false, records: [row('a', 0)]})
      : payload({observationId: 2, top: 1, endReached: true, records: [row('b', 1)]});
    return {code: 'OK', executed: true, dataJson: JSON.stringify(data)};
  };
  const scan = await collectBrowserWindowSet({browserRequest, taskId: 'task_123', sessionGeneration: 7,
    controlGeneration: 4, options});
  assert.equal(scan.atomicSnapshot, false);
  assert.equal(scan.snapshotConsistency, 'non_atomic');
  assert.equal(scan.coverageComplete, true, JSON.stringify(scan));
  assert.equal(scan.coverageScope, 'entire_selected_container');
  assert.deepEqual(scan.records.map(r => r.key), ['a', 'b']);
  assert.equal(calls.length, 3);
  for (const call of calls) {
    assert.equal(call.envelope.taskId, 'task_123');
    assert.equal(call.envelope.sessionGeneration, 7);
    assert.equal(call.envelope.controlGeneration, 4);
    assert.equal(call.envelope.owner, 'ai');
    assert.ok(call.signal instanceof AbortSignal);
  }
  assert.equal(calls[1].args.nodeId, payload({observationId: 1, top: 0, endReached: false, records: []}).containerNodeId,
    'scroll uses the latest fresh container reference');

  let changedObservation = 0;
  const changed = await collectBrowserWindowSet({browserRequest: async (method) => {
    if (method === 'scroll') return {code: 'OK', executed: true, dataJson: '{"code":"OK"}'};
    changedObservation++;
    const records = changedObservation === 1 ? [row('a', 0)] : [
      {key: 'a', author: 'author', content: 'changed while scanning', order: 0}, row('b', 1)];
    return {code: 'OK', executed: true, dataJson: JSON.stringify(payload({observationId: changedObservation,
      top: changedObservation - 1, endReached: changedObservation === 2, records}))};
  }, taskId: 'task_123', sessionGeneration: 7, controlGeneration: 4, options});
  assert.equal(changed.coverageComplete, false);
  assert.match(changed.reason, /RECORD_CHANGED/);
  assert.equal(changed.records.find(r => r.key === 'a').content, 'text-a');

  let pageObservation = 0;
  const identityChange = await collectBrowserWindowSet({browserRequest: async (method) => {
    if (method === 'scroll') return {code: 'OK', executed: true, dataJson: '{"code":"OK"}'};
    pageObservation++;
    const data = payload({observationId: pageObservation, top: pageObservation - 1,
      endReached: pageObservation === 2, records: [row(pageObservation === 1 ? 'a' : 'b', pageObservation - 1)]});
    if (pageObservation === 2) data.identity.pageId = 'pg-ffffffffffffffffffff';
    return {code: 'OK', executed: true, dataJson: JSON.stringify(data)};
  }, taskId: 'task_123', sessionGeneration: 7, controlGeneration: 4, options});
  assert.match(identityChange.reason, /PAGE_CHANGED/);
  assert.deepEqual(identityChange.records.map(record => record.key), ['a'], 'records from a changed page are not merged');

  const unknownCalls = [];
  const unknown = await collectBrowserWindowSet({browserRequest: async (method) => {
    unknownCalls.push(method);
    if (method === 'collect') return {code: 'OK', dataJson: JSON.stringify(payload({observationId: 1,
      top: 0, endReached: false, records: [row('a', 0)]}))};
    return {code: 'UNKNOWN', state: 'UNKNOWN', dataJson: '{}'};
  }, taskId: 'task_123', sessionGeneration: 7, controlGeneration: 4, options});
  assert.equal(unknown.coverageComplete, false);
  assert.equal(unknown.atomicSnapshot, false);
  assert.deepEqual(unknownCalls, ['collect', 'scroll'], 'unknown scroll must never be resent');

  const cancelController = new AbortController();
  const cancelCalls = [];
  const cancelled = await collectBrowserWindowSet({browserRequest: async (method) => {
    cancelCalls.push(method);
    if (method === 'collect') return {code: 'OK', dataJson: JSON.stringify(payload({observationId: 1,
      top: 0, endReached: false, records: [row('a', 0)]}))};
    cancelController.abort();
    return new Promise(() => {});
  }, taskId: 'task_123', sessionGeneration: 7, controlGeneration: 4, options, signal: cancelController.signal});
  assert.equal(cancelled.coverageComplete, false);
  assert.match(cancelled.reason, /SCROLL_OUTCOME_UNKNOWN/);
  assert.deepEqual(cancelCalls, ['collect', 'scroll']);

  const excludeCalls = [];
  const excluded = await collectBrowserWindowSet({browserRequest: async (method, args, envelope, dependencies) => {
    excludeCalls.push({method, args, envelope, signal: dependencies.signal});
    if (method === 'scroll') return {code: 'OK', dataJson: '{"code":"OK"}'};
    const data = payload({observationId: 1, top: 0, endReached: true, records: [row('a', 0)]});
    data.container.totalCount = 1;
    data.excludedCount = 1;
    return {code: 'OK', executed: true, dataJson: JSON.stringify(data)};
  }, taskId: 'task_123', sessionGeneration: 7, controlGeneration: 4,
    options: {...options, excludeSelector: '.draft'}});
  assert.equal(excludeCalls.length, 1);
  assert.equal(excludeCalls[0].args.excludeSelector, '.draft');
  assert.equal(excluded.excludedObservations, 1);
  assert.equal(excluded.coverageComplete, false, 'excluded rows do not support a full coverage claim');
  assert.match(excluded.reason, /EXCLUDED_ROWS/);
  const invalidIdentity = await collectBrowserWindowSet({browserRequest: async () => ({code: 'OK', executed: true,
    dataJson: JSON.stringify({...payload({observationId: 1, top: 0, endReached: true, records: []}),
      identity: {...payload({observationId: 1, top: 0, endReached: true, records: []}).identity, controlGeneration: 99}})}),
    taskId: 'task_123', sessionGeneration: 7, controlGeneration: 4, options});
  assert.equal(invalidIdentity.coverageComplete, false);
  assert.match(invalidIdentity.reason, /IDENTITY_MISMATCH|MISSING_IDENTITY/);
  const staleReference = payload({observationId: 1, top: 0, endReached: true, records: []});
  staleReference.containerNodeId = `b:7:4:9:1:${'a'.repeat(32)}:0`;
  const invalidReference = await collectBrowserWindowSet({browserRequest: async () => ({code: 'OK', executed: true,
    dataJson: JSON.stringify(staleReference)}), taskId: 'task_123', sessionGeneration: 7,
    controlGeneration: 4, options});
  assert.equal(invalidReference.coverageComplete, false);
  assert.match(invalidReference.reason, /IDENTITY_MISMATCH/);
  const invalidContainer = payload({observationId: 1, top: 0, endReached: true, records: []});
  invalidContainer.container.scrollTop = NaN;
  const malformed = await collectBrowserWindowSet({browserRequest: async () => ({code: 'OK', executed: true,
    dataJson: JSON.stringify(invalidContainer)}), taskId: 'task_123', sessionGeneration: 7,
    controlGeneration: 4, options});
  assert.equal(malformed.coverageComplete, false);
  assert.match(malformed.reason, /INVALID_RESPONSE/);
  const tooManyBytes = await collectBrowserWindowSet({browserRequest: async () => ({code: 'OK', executed: true,
    dataJson: JSON.stringify(payload({observationId: 1, top: 0, endReached: true, records: [row('a', 0)]}))}),
    taskId: 'task_123', sessionGeneration: 7, controlGeneration: 4, options,
    budgets: {maxBytes: 64}});
  assert.equal(tooManyBytes.coverageComplete, false);
  assert.match(tooManyBytes.reason, /BYTE_BUDGET/);
  let budgetObservation = 0;
  const tooManyRecords = await collectBrowserWindowSet({browserRequest: async (method, args) => {
    if (method === 'scroll') return {code: 'OK', dataJson: '{"code":"OK"}'};
    budgetObservation++;
    const record = budgetObservation === 1 ? row('a', 0) : row('b', 1);
    return {code: 'OK', executed: true, dataJson: JSON.stringify(payload({observationId: budgetObservation,
      top: budgetObservation - 1, endReached: budgetObservation === 2, records: [record]}))};
  }, taskId: 'task_123', sessionGeneration: 7, controlGeneration: 4, options,
    budgets: {maxRecords: 1}});
  assert.equal(tooManyRecords.coverageComplete, false);
  assert.match(tooManyRecords.reason, /RECORD_BUDGET/);
  assert.deepEqual(tooManyRecords.records.map(record => record.key), ['a']);
  const invalidContentBudget = await collectBrowserWindowSet({browserRequest, taskId: 'task_123', sessionGeneration: 7,
    controlGeneration: 4, options, budgets: {maxContentChars: 4097}});
  assert.equal(invalidContentBudget.reason, 'INVALID_BUDGET');
  assert.equal((await collectBrowserWindowSet({browserRequest, sessionGeneration: 7, controlGeneration: 4, options})).reason,
    'TASK_ID_REQUIRED');
  process.stdout.write('BROWSER_COLLECT_CLIENT_TESTS_OK\n');
})().catch(error => { process.stderr.write(`${error.stack}\n`); process.exitCode = 1; });
