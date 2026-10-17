'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { validateArgs } = require('../../assets/plugins/browser-use/plugin/lib/client')
const cli = require('../../../../browser/browser-bridge/src/main/assets/browser/lib/browser-cli.cjs')

test('receipt client and CLI reject malformed or foreign-session fields', () => {
  const args = { action: 'receipt', selectionId: '12345678-1234-4234-8234-123456789abc',
    destinationSessionKey: 'session-a', deliveryCode: 'DELIVERED',
    requestId: '0123456789abcdef0123456789abcdef', imageIncluded: true }
  const request = value => ({ version: 1, method: 'selection', requestId: 'receipt-test',
    deadlineAtMillis: Date.now() + 5000, args: value, sessionGeneration: 4 })
  assert.equal(validateArgs('selection', args), '')
  assert.doesNotThrow(() => cli.validateRequest('selection', JSON.stringify(request(args))))
  for (const bad of [
    { ...args, selectionId: '-'.repeat(36) }, { ...args, imageIncluded: 1 },
    { ...args, requestId: '../session' }, { ...args, deliveryCode: 'delivered' },
    { ...args, sessionId: 'foreign' },
  ]) {
    assert.notEqual(validateArgs('selection', bad), '')
    assert.throws(() => cli.validateRequest('selection', JSON.stringify(request(bad))))
  }
})

test('claim and admission validators require an exact destination and stable payload identity', () => {
  const base = {
    selectionId: '12345678-1234-4234-8234-123456789abc',
    destinationSessionKey: 'session-a',
    requestId: '0123456789abcdef0123456789abcdef',
    payloadDigest: 'a'.repeat(64),
  }
  const request = value => ({ version: 1, method: 'selection', requestId: 'claim-test',
    deadlineAtMillis: Date.now() + 5000, args: value, sessionGeneration: 4 })
  for (const action of ['claim', 'admit']) {
    const args = { action, ...base }
    assert.equal(validateArgs('selection', args), '')
    assert.doesNotThrow(() => cli.validateRequest('selection', JSON.stringify(request(args))))
    for (const bad of [
      { ...args, destinationSessionKey: '' },
      { ...args, payloadDigest: 'x'.repeat(64) },
      { ...args, callerSessionId: 'session-b' },
    ]) {
      assert.notEqual(validateArgs('selection', bad), '')
      assert.throws(() => cli.validateRequest('selection', JSON.stringify(request(bad))))
    }
  }
})
