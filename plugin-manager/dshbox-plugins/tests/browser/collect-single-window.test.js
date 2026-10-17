'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { collectVirtualizedWindow } = require('../../assets/plugins/browser-use/plugin/lib/browser-collect.cjs')
const options = { containerSelector: '#list', messageSelector: '.message', keyAttribute: 'data-id',
  declaredRange: 'selected list', fromTop: true }

for (const count of [0, 1]) test(`single-window list of ${count} messages reports bounded coverage without scrolling`, async () => {
  let calls = 0
  const result = await collectVirtualizedWindow({ options, invokeBrowser: async method => {
    calls++
    assert.equal(method, 'collect')
    return { code: 'OK', data: { action: 'window', declaredRange: 'selected list',
      identity: { pageId: 'pg-0123456789abcdefabcd', documentGeneration: 1, navigationId: 2,
        observationId: 1, sessionGeneration: 3, controlGeneration: 4 },
      containerNodeId: 'fresh-reference', container: { scrollTop: 0, scrollHeight: 100, clientHeight: 100,
        startReached: true, endReached: true, totalCount: count },
      records: count ? [{ key: 'a', order: 0, author: 'person', content: 'hello' }] : [],
      truncated: false, excludedCount: 0 } }
  } })
  assert.equal(calls, 1)
  assert.equal(result.coverageComplete, true)
  assert.equal(result.atomicSnapshot, false)
  assert.equal(result.records.length, count)
})
