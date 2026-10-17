'use strict';

const assert = require('node:assert/strict');
const {collectVirtualizedWindow} = require('../../main/assets/browser/lib/browser-collect.cjs');

const options = {containerSelector: '#messages', messageSelector: '.message', keyAttribute: 'data-id',
  authorSelector: '.author', contentSelector: '.body', orderAttribute: 'data-order', totalCountSelector: '#total',
  declaredRange:'current conversation message list', fromTop: true};

function record(id, order) { return {key: String(id), author: 'speaker', content: `message ${id}`, order}; }
function data({pageId = 'pg-a', doc = 1, nav = 2, observation = 1, top = 0, end = false, rows = [], total = 4} = {}) {
  return {action: 'window', declaredRange:'current conversation message list', identity: {pageId, documentGeneration: doc, navigationId: nav,
    observationId: observation, sessionGeneration: 8, controlGeneration: 3},
  containerNodeId: 'b:8:3:1:1:nonce:0', container: {scrollTop: top, startReached: top === 0,
      endReached: end, totalCount: total}, records: rows, truncated: false, excludedCount: 0};
}
function response(payload) { return {code: 'OK', data: payload}; }

async function virtualList({reorder = false, changeIdentityAt = -1, cancelOnScroll = false} = {}) {
  let position = 0, collectCount = 0;
  const windows = [
    [record(0,0), record(1,1), record(2,2)],
    reorder ? [record(3,3), record(2,2), record(1,1)] : [record(1,1), record(2,2), record(3,3)],
    [record(2,2), record(3,3)],
  ];
  const invokeBrowser = async (method, args) => {
    if (method === 'collect') {
      collectCount++;
      const changed = collectCount >= changeIdentityAt && changeIdentityAt > 0;
      return response(data({pageId: changed ? 'pg-b' : 'pg-a', observation: collectCount,
        top: position, end: position === 2, rows: windows[position], total: 4}));
    }
    assert.equal(method, 'scroll');
    assert.equal(args.nodeId, 'b:8:3:1:1:nonce:0');
    if (cancelOnScroll) return response({code: 'NO_EFFECT'});
    position = Math.min(2, position + 1);
    return response({code: 'OK'});
  };
  return invokeBrowser;
}

(async () => {
  const result = await collectVirtualizedWindow({invokeBrowser: await virtualList({reorder: true}), options});
  assert.equal(result.complete, true);
  assert.deepEqual(result.records.map(item => item.key), ['0','1','2','3']);
  assert.deepEqual(result.records.map(item => item.order), [0,1,2,3]);

  let position = 1, observation = 0;
  const boundedFromMiddle = async (method, args) => {
    if (method === 'scroll') {
      position = args.direction === 'up' ? Math.max(0, position - 1) : Math.min(2, position + 1);
      return response({code: 'OK'});
    }
    observation++;
    const rows = position === 0 ? [record(0,0),record(1,1),record(2,2)] :
      position === 1 ? [record(1,1),record(2,2),record(3,3)] : [record(2,2),record(3,3)];
    return response(data({observation, top: position, end: position === 2, rows, total: 4}));
  };
  const fromMiddle = await collectVirtualizedWindow({invokeBrowser: boundedFromMiddle, options});
  assert.equal(fromMiddle.complete, true, 'explicit fromTop must establish the start before walking down');
  assert.deepEqual(fromMiddle.records.map(item => item.key), ['0','1','2','3']);

  const noProgress = await collectVirtualizedWindow({invokeBrowser: await virtualList({cancelOnScroll: true}), options});
  assert.equal(noProgress.complete, false);
  assert.match(noProgress.reason, /SCROLL_NO_PROGRESS/);

  const changed = await collectVirtualizedWindow({invokeBrowser: await virtualList({changeIdentityAt: 2}), options});
  assert.equal(changed.complete, false);
  assert.match(changed.reason, /PAGE_CHANGED/);
  assert.equal(changed.records.length, 3, 'records from the changed page must not be merged');

  const controller = new AbortController();
  const cancelTransport = async (method, args, {signal}) => {
    assert.ok(signal instanceof AbortSignal, 'command transport receives an abort signal');
    if (method === 'collect') return response(data({rows: [record(1,0)], total: null}));
    controller.abort();
    return response({code: 'OK'});
  };
  const cancelled = await collectVirtualizedWindow({invokeBrowser: cancelTransport,
    options: {...options, totalCountSelector: ''}, signal: controller.signal});
  assert.equal(cancelled.complete, false);
  assert.match(cancelled.reason, /SCROLL_OUTCOME_UNKNOWN/);

  const noStableKey = async () => response(data({rows: [
    {key: null, author: 'speaker', content: 'same text', order: null},
    {key: null, author: 'speaker', content: 'same text', order: null}], total: null, end: true}));
  const retained = await collectVirtualizedWindow({invokeBrowser: noStableKey,
    options: {...options, fromTop: false}});
  assert.equal(retained.records.length, 2, 'equal content without stable IDs must not be deduplicated');
  assert.equal(retained.complete, false);
  assert.match(retained.reason, /STABLE_KEY_UNAVAILABLE/);

  const missingIdentity = async () => response({...data({rows:[record(1,0)]}),
    identity:{pageId:'pg-a',documentGeneration:1,navigationId:2,observationId:1}});
  const noControl = await collectVirtualizedWindow({invokeBrowser:missingIdentity, options});
  assert.match(noControl.reason, /MISSING_IDENTITY/);

  const calls=[];
  const timedScroll = async (method, args, request) => {
    calls.push(method);
    if (method === 'collect') return response(data({rows:[record(0,0)],top:0,end:false,total:4}));
    assert.ok(request.signal instanceof AbortSignal);
    return new Promise(() => {});
  };
  const timeout = await collectVirtualizedWindow({invokeBrowser:timedScroll, options,
    budgets:{maxDurationMs:25}});
  assert.match(timeout.reason, /SCROLL_OUTCOME_UNKNOWN/);
  assert.deepEqual(calls,['collect','scroll'],'no new command follows an unacknowledged scroll');

  const responseTooLarge = await collectVirtualizedWindow({invokeBrowser:async()=>({code:'LIMIT_EXCEEDED',
    executed:true,data:{action:'window',reason:'RESPONSE_BUDGET_EXCEEDED',truncated:true}}), options});
  assert.match(responseTooLarge.reason,/RESPONSE_BUDGET_EXCEEDED/);
  process.stdout.write('BROWSER_COLLECT_TESTS_OK\n');
})().catch(error => { process.stderr.write(`${error.stack}\n`); process.exitCode = 1; });
