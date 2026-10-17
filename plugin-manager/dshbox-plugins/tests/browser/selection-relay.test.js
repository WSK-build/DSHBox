'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { LIFETIME_MS, POLL_INTERVAL_MS, createSelectionRelay } = require('../../assets/plugins/browser-use/plugin/lib/selection-relay')

const id = (suffix) => `00000000-0000-4000-8000-${String(suffix).padStart(12, '0')}`

function fakeTimers() {
  let time = 100
  let nextId = 0
  const tasks = new Map()
  return {
    now: () => time,
    schedule(fn, delay) {
      const key = ++nextId
      tasks.set(key, { fn, at: time + delay })
      return key
    },
    cancel(key) { tasks.delete(key) },
    async advance(delay) {
      const end = time + delay
      while (true) {
        const due = [...tasks.entries()].filter(([, task]) => task.at <= end).sort((a, b) => a[1].at - b[1].at)[0]
        if (!due) break
        tasks.delete(due[0])
        time = due[1].at
        due[1].fn()
        await flush()
      }
      time = end
      await flush()
    },
    pending: () => tasks.size,
  }
}

async function flush() {
  for (let i = 0; i < 8; i++) await Promise.resolve()
}

function response(code, generation, data = null, extra = {}) {
  return {
    code,
    executed: code === 'OK' ? 'false' : 'false',
    sessionGeneration: generation,
    sessionGenerationAvailable: true,
    state: 'RUNNING',
    ...(data ? { dataJson: JSON.stringify(data) } : {}),
    ...extra,
  }
}

function setup({ readSelection, deliver, writeReceipt, lifetimeMs = LIFETIME_MS } = {}) {
  const timers = fakeTimers()
  const exec = { agent: { id: 'real-session-id' }, signal: new AbortController().signal }
  const bridgeCalls = { binds: [], deliveries: [] }
  const bridge = {
    bindAfterOpen(actualExec, openResult) {
      bridgeCalls.binds.push({ actualExec, openResult })
      let openData = {}
      try { openData = JSON.parse(openResult.dataJson) } catch {}
      return openResult && openResult.code === 'OK' && ['true', 'false'].includes(openResult.executed) &&
        openResult.sessionGenerationAvailable && openResult.sessionGeneration > 0 &&
        ['IDLE', 'RUNNING'].includes(openResult.state) && openData.event === 'opened' &&
        Number.isSafeInteger(openData.selectionSequence) && openData.selectionSequence >= 0
        ? { code: 'BOUND', selectionSequence: openData.selectionSequence }
        : { code: 'OPEN_NOT_CONFIRMED' }
    },
    async deliver(value) {
      bridgeCalls.deliveries.push(value)
      return deliver ? deliver(value) : { code: 'DELIVERED', accepted: true, delivery: 'queued' }
    },
  }
  const relay = createSelectionRelay({
    bridge,
    readSelection,
    writeReceipt,
    now: timers.now,
    schedule: timers.schedule,
    cancel: timers.cancel,
    lifetimeMs,
  })
  const openResult = {
    code: 'OK', executed: 'true', sessionGeneration: 7, sessionGenerationAvailable: true, state: 'RUNNING',
    dataJson: JSON.stringify({ event: 'opened', selectionSequence: 1 }),
  }
  return { relay, timers, exec, openResult, bridgeCalls }
}

test('a stopped relay retains its failure reason until a new binding starts', async () => {
  const ctx = setup({ readSelection: async () => ({ code: 'CLI_USAGE', sessionGenerationAvailable: false }),
    writeReceipt: async () => true })
  assert.equal(ctx.relay.start(ctx.exec, ctx.openResult).code, 'ACTIVE')
  await ctx.timers.advance(POLL_INTERVAL_MS)
  assert.equal(ctx.relay.status().code, 'INACTIVE')
  assert.equal(ctx.relay.status().reason, 'CLI_USAGE')
  assert.equal(ctx.relay.start(ctx.exec, ctx.openResult).code, 'ACTIVE')
  assert.equal(ctx.relay.status().reason, undefined)
  ctx.relay.dispose()
})

test('a known rejected claim keeps its cursor so it can complete after control settles', async () => {
  const cursors = []
  let attempts = 0
  const ctx = setup({
    readSelection: async ({ afterSequence }) => {
      cursors.push(afterSequence)
      return afterSequence < 2 ? response('OK', 7, { selectionId: id(2), selectionSequence: 2, mode: 'area' })
        : response('NOT_FOUND', 7)
    },
    deliver: async () => ++attempts === 1
      ? { code: 'STALE_SESSION', accepted: false, claimRejected: true, doNotRetry: false }
      : { code: 'DELIVERED', accepted: true },
    writeReceipt: async () => true,
  })
  ctx.relay.start(ctx.exec, ctx.openResult)
  await ctx.timers.advance(POLL_INTERVAL_MS)
  await ctx.timers.advance(POLL_INTERVAL_MS)
  await ctx.timers.advance(POLL_INTERVAL_MS)
  assert.equal(attempts, 2)
  assert.deepEqual(cursors, [1, 1, 2])
  ctx.relay.dispose()
})

test('an uncertain claim stops without attempting to deliver the same selection again', async () => {
  let attempts = 0
  const ctx = setup({
    readSelection: async () => response('OK', 7, { selectionId: id(2), selectionSequence: 2, mode: 'area' }),
    deliver: async () => { attempts++; return { code: 'UNKNOWN', claimRejected: true, doNotRetry: true } },
    writeReceipt: async () => true,
  })
  ctx.relay.start(ctx.exec, ctx.openResult)
  await ctx.timers.advance(POLL_INTERVAL_MS * 3)
  assert.equal(attempts, 1)
  assert.equal(ctx.relay.status().reason, 'SELECTION_CLAIM_UNCONFIRMED')
  ctx.relay.dispose()
})

test('open sequence remains an eligibility baseline while durable older items stay manageable', async () => {
  let pending = null
  let reads = 0
  const receipts = []
  const ctx = setup({
    readSelection: async ({ exec, sessionGeneration, afterSequence, signal }) => {
      assert.equal(exec.agent.id, 'real-session-id')
      assert.equal(sessionGeneration, 7)
      assert.equal(signal.aborted, false)
      assert.equal(afterSequence, 1)
      reads++
      return pending ? response('OK', 7, pending) : response('NOT_FOUND', 7)
    },
    writeReceipt: async (value) => { receipts.push(value); return true },
  })

  assert.equal(ctx.relay.start(ctx.exec, { ...ctx.openResult, dataJson: '{}' }).code, 'OPEN_NOT_CONFIRMED')
  assert.equal(ctx.relay.status().code, 'INACTIVE')
  assert.equal(ctx.relay.start(ctx.exec, ctx.openResult).code, 'ACTIVE')
  await flush()
  assert.equal(ctx.bridgeCalls.binds[0].actualExec, ctx.exec)
  assert.equal(ctx.relay.status().baselineReady, true)
  assert.equal(reads, 0)

  pending = { selectionId: id(1), selectionSequence: 2, mode: 'area', comment: 'explicit save' }
  await ctx.timers.advance(POLL_INTERVAL_MS)
  assert.equal(ctx.bridgeCalls.deliveries.length, 1)
  assert.equal(ctx.bridgeCalls.deliveries[0].exec, ctx.exec)
  assert.equal(ctx.bridgeCalls.deliveries[0].signal.aborted, false)
  assert.equal(receipts.length, 1)
  assert.equal(receipts[0].selectionId, id(1))
  assert.equal(receipts[0].receipt.accepted, true)
  assert.equal(ctx.bridgeCalls.binds.length, 2) // one rejected attempt plus the single successful open; polling never rebinds
  assert.equal(ctx.timers.pending(), 2) // one finite-lifetime timer and one next poll
  ctx.relay.dispose()
  assert.equal(ctx.timers.pending(), 0)
})

test('queued selection sequence advances only after its receipt and does not redeliver prior item', async () => {
  let pending = null
  let deliveryCount = 0
  const ctx = setup({
    readSelection: async ({ afterSequence }) => {
      assert.equal(afterSequence, deliveryCount === 0 ? 1 : 2)
      return pending ? response('OK', 7, pending) : response('NOT_FOUND', 7)
    },
    deliver: async () => { deliveryCount++; return { code: 'DELIVERED', accepted: true } },
    writeReceipt: async () => true,
  })
  ctx.relay.start(ctx.exec, ctx.openResult)
  await flush()
  await ctx.timers.advance(POLL_INTERVAL_MS)
  assert.equal(deliveryCount, 0)

  pending = { selectionId: id(3), selectionSequence: 2, mode: 'element' }
  await ctx.timers.advance(POLL_INTERVAL_MS)
  pending = null
  await ctx.timers.advance(POLL_INTERVAL_MS)
  assert.equal(deliveryCount, 1)
  ctx.relay.dispose()
})

test('three queued selections are delivered oldest-first without losing later sequence ids', async () => {
  const selections = [2, 3, 4].map((sequence) => ({
    selectionId: id(sequence), selectionSequence: sequence, mode: 'element', comment: `item-${sequence}`,
  }))
  const cursors = []
  const delivered = []
  const written = []
  const ctx = setup({
    readSelection: async ({ afterSequence }) => {
      cursors.push(afterSequence)
      const next = selections.find((selection) => selection.selectionSequence > afterSequence)
      return next ? response('OK', 7, next) : response('NOT_FOUND', 7)
    },
    deliver: async ({ selection }) => {
      delivered.push(selection.selectionId)
      return { code: 'DELIVERED', accepted: true }
    },
    writeReceipt: async ({ selectionId }) => { written.push(selectionId); return true },
  })
  ctx.relay.start(ctx.exec, ctx.openResult)
  await flush()
  await ctx.timers.advance(POLL_INTERVAL_MS)
  await ctx.timers.advance(POLL_INTERVAL_MS)
  await ctx.timers.advance(POLL_INTERVAL_MS)

  assert.deepEqual(delivered, [id(2), id(3), id(4)])
  assert.deepEqual(written, delivered)
  assert.deepEqual(cursors.slice(0, 3), [1, 2, 3])
  ctx.relay.dispose()
})

test('same-agent same-generation reopen preserves the active relay cursor and queued items', async () => {
  const selections = [2, 3].map((sequence) => ({
    selectionId: id(sequence), selectionSequence: sequence, mode: 'element', comment: `item-${sequence}`,
  }))
  const cursors = []
  const delivered = []
  const ctx = setup({
    readSelection: async ({ afterSequence }) => {
      cursors.push(afterSequence)
      const next = selections.find((selection) => selection.selectionSequence > afterSequence)
      return next ? response('OK', 7, next) : response('NOT_FOUND', 7)
    },
    deliver: async ({ selection }) => {
      delivered.push(selection.selectionId)
      return { code: 'DELIVERED', accepted: true }
    },
    writeReceipt: async () => true,
  })
  assert.equal(ctx.relay.start(ctx.exec, ctx.openResult).code, 'ACTIVE')
  await flush()
  const reopened = {
    ...ctx.openResult,
    dataJson: JSON.stringify({ event: 'opened', selectionSequence: 99 }),
  }
  assert.equal(ctx.relay.start(ctx.exec, reopened).code, 'ACTIVE')
  assert.equal(ctx.relay.status().sessionGeneration, 7)
  await ctx.timers.advance(POLL_INTERVAL_MS)
  await ctx.timers.advance(POLL_INTERVAL_MS)
  assert.deepEqual(delivered, [id(2), id(3)])
  assert.deepEqual(cursors.slice(0, 2), [1, 2])
  ctx.relay.dispose()
})

test('same-agent reopen during an in-flight operation preserves the relay binding', async () => {
  let releaseDelivery
  const ctx = setup({
    readSelection: async () => response('OK', 7, {
      selectionId: id(7), selectionSequence: 2, mode: 'area',
    }),
    deliver: () => new Promise((resolve) => { releaseDelivery = resolve }),
    writeReceipt: async () => true,
  })
  assert.equal(ctx.relay.start(ctx.exec, ctx.openResult).code, 'ACTIVE')
  await flush()
  await ctx.timers.advance(POLL_INTERVAL_MS)
  assert.equal(ctx.relay.status().inFlight, true)
  const bindCount = ctx.bridgeCalls.binds.length
  const reopen = {
    ...ctx.openResult,
    dataJson: JSON.stringify({ event: 'opened', selectionSequence: 20 }),
  }
  assert.equal(ctx.relay.start(ctx.exec, reopen).code, 'ACTIVE')
  assert.equal(ctx.bridgeCalls.binds.length, bindCount + 1)
  releaseDelivery({ code: 'DELIVERED', accepted: true })
  await flush()
  assert.equal(ctx.relay.status().code, 'ACTIVE')
  assert.equal(ctx.relay.status().inFlight, false)
  assert.equal(ctx.timers.pending(), 2)
  ctx.relay.dispose()
})

test('generation change or authorization revocation stops polling before delivery', async (t) => {
  for (const changed of [
    response('OK', 8, { selectionId: id(4), selectionSequence: 2, mode: 'area' }),
    response('SESSION_REVOKED', 7),
    response('OK', 7, null, { automationEnabled: false }),
  ]) {
    await t.test(changed.code + '-' + changed.sessionGeneration + '-' + String(changed.automationEnabled), async () => {
      let call = 0
      const ctx = setup({
      readSelection: async () => { call++; return changed },
        writeReceipt: async () => true,
      })
      ctx.relay.start(ctx.exec, ctx.openResult)
      await flush()
      await ctx.timers.advance(POLL_INTERVAL_MS)
      assert.equal(ctx.bridgeCalls.deliveries.length, 0)
      assert.equal(ctx.relay.status().code, 'INACTIVE')
      assert.equal(ctx.timers.pending(), 0)
    })
  }
})

test('relay permits at most one read/delivery and stops on failed receipt write', async () => {
  let releaseDelivery
  let readCount = 0
  const receiptAttempts = []
  const ctx = setup({
    readSelection: async () => {
      readCount++
      return response('OK', 7, { selectionId: id(5), selectionSequence: 2, mode: 'area' })
    },
    deliver: () => new Promise((resolve) => { releaseDelivery = resolve }),
    writeReceipt: async (value) => { receiptAttempts.push(value); return false },
  })
  ctx.relay.start(ctx.exec, ctx.openResult)
  await flush()
  await ctx.timers.advance(POLL_INTERVAL_MS)
  assert.equal(ctx.relay.status().inFlight, true)
  assert.equal(readCount, 1)
  releaseDelivery({ code: 'UNKNOWN', accepted: null, doNotRetry: true })
  await flush()
  assert.equal(ctx.relay.status().code, 'INACTIVE')
  assert.equal(receiptAttempts.length, 1)
  assert.equal(receiptAttempts[0].deliveryCode, 'UNKNOWN')
  assert.equal(receiptAttempts[0].receipt.doNotRetry, true)
  assert.equal(ctx.timers.pending(), 0)
})

test('dispose aborts relay-owned signal during delivery and never writes a late receipt', async () => {
  let latest = null
  let releaseDelivery
  let receiptCount = 0
  const ctx = setup({
    readSelection: async () => latest ? response('OK', 7, latest) : response('NOT_FOUND', 7),
    deliver: ({ signal }) => new Promise((resolve) => {
      assert.equal(signal.aborted, false)
      releaseDelivery = resolve
    }),
    writeReceipt: async () => { receiptCount++; return true },
  })
  ctx.relay.start(ctx.exec, ctx.openResult)
  await flush()
  latest = { selectionId: id(6), selectionSequence: 2, mode: 'area' }
  await ctx.timers.advance(POLL_INTERVAL_MS)
  const signal = ctx.bridgeCalls.deliveries[0].signal
  ctx.relay.dispose()
  assert.equal(signal.aborted, true)
  releaseDelivery({ code: 'DELIVERED', accepted: true })
  await flush()
  assert.equal(receiptCount, 0)
  assert.equal(ctx.relay.start(ctx.exec, ctx.openResult).code, 'DISPOSED')
})

test('30-minute lifetime aborts pending work and does not poll afterward', async () => {
  let readSignal
  const ctx = setup({
    readSelection: async ({ signal }) => {
      readSignal = signal
      return new Promise(() => {})
    },
    writeReceipt: async () => true,
    lifetimeMs: 30 * 60_000,
  })
  ctx.relay.start(ctx.exec, ctx.openResult)
  await flush()
  assert.equal(ctx.relay.status().inFlight, false)
  await ctx.timers.advance(LIFETIME_MS)
  assert.equal(readSignal.aborted, true)
  assert.equal(ctx.relay.status().code, 'INACTIVE')
  assert.equal(ctx.timers.pending(), 0)
  assert.equal(ctx.relay.start(ctx.exec, ctx.openResult).code, 'PREVIOUS_OPERATION_PENDING')
})
