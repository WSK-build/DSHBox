'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { confirmedOpenSequence } = require('../../assets/plugins/browser-use/plugin/lib/open-confirmation')
const warm = { event: 'navigation_accepted', selectionSequence: 3, navigationId: 2,
  loadState: 'accepted', requestedUrl: 'https://example.com/' }
const result = (data, executed = 'true') => ({ dataJson: JSON.stringify(data), executed })

test('cold open and accepted navigation share the same selection baseline contract', () => {
  assert.equal(confirmedOpenSequence(result({ event: 'opened', selectionSequence: 0 }, 'false')), 0)
  assert.equal(confirmedOpenSequence(result(warm)), 3)
})

test('unknown, incomplete or unexecuted navigation cannot authorize feedback binding', () => {
  for (const data of [null, [], { ...warm, event: 'closed' }, { ...warm, selectionSequence: -1 },
    { ...warm, navigationId: 0 }, { ...warm, loadState: 'failed' },
    { ...warm, requestedUrl: 'javascript:alert(1)' }, { ...warm, requestedUrl: null }]) {
    assert.equal(confirmedOpenSequence(result(data)), null, JSON.stringify(data))
  }
  assert.equal(confirmedOpenSequence(result(warm, 'false')), null)
  assert.equal(confirmedOpenSequence({ dataJson: '{' }), null)
})
