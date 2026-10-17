'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const { createTaskFeedbackRelay } = require('../../assets/plugins/browser-use/plugin/lib/task-feedback')

const guidance = 'Recovery guidance: call browser_task status with this Task id before acting to locate this task and confirm its current revision, title, goal, and status. If the task is missing or any value differs from this draft, stop and do not continue business actions. This draft may be stale; prior results do not prove success. Never replay an UNKNOWN effect. Make fresh Browser observations before deciding what happened.\nTask id: task_1\nRevision: 2\nTitle: Continue work\nCreated wall time: 1700000000000\nTask status: paused\nGoal: Finish the task.'

function openResult(sessionGeneration = 7) {
  return { code: 'OK', executed: 'true', state: 'RUNNING', sessionGeneration,
    dataJson: JSON.stringify({ event: 'opened', selectionSequence: 3 }) }
}

function response(code, executed, data = {}, sessionGeneration = 7) {
  return { code, executed, sessionGeneration, sessionGenerationAvailable: true, state: 'RUNNING', dataJson: JSON.stringify(data) }
}

function item(state = 'pending', revision = 2) {
  return { feedbackId: 'feedback_1', kind: 'task_recovery', taskId: 'task_1', revision,
    title: 'Continue work', text: guidance.replace('Revision: 2', `Revision: ${revision}`), sequence: 1, state, sessionGeneration: 7 }
}

function runPoll(relay, timers) {
  const callback = timers.shift()
  assert.equal(typeof callback, 'function')
  callback()
  return new Promise((resolve) => setTimeout(resolve, 0))
}

test('task feedback is claimed, admitted, and sent once to the bound DSH session', async () => {
  const timers = []
  const calls = []
  const relay = createTaskFeedbackRelay({
    schedule: (callback) => { timers.push(callback); return callback }, cancel: () => {},
    readPending: async ({ destinationSessionKey, afterSequence }) => {
      assert.equal(destinationSessionKey, 'bound-agent')
      assert.equal(afterSequence, 0)
      return response('OK', 'false', { afterSequence: 0, nextSequence: 1, items: [item('pending', 0)] })
    },
    claim: async (args) => { calls.push(['claim', args]); return response('OK', 'true', {
      feedbackId: args.feedbackId, state: 'claimed', requestId: args.requestId, payloadDigest: args.payloadDigest,
    }) },
    admit: async (args) => { calls.push(['admit', args]); return response('OK', 'true', {
      feedbackId: args.feedbackId, state: 'admitted', requestId: args.requestId, payloadDigest: args.payloadDigest,
    }) },
    sessionController: { prompt: async (request) => { calls.push(['prompt', request]); return { accepted: true } } },
    writeReceipt: async (args) => { calls.push(['receipt', args]); return response('OK', 'true', {}) },
  })
  assert.equal(relay.start({ agent: { id: 'bound-agent' } }, openResult()).code, 'ACTIVE')
  await runPoll(relay, timers)
  assert.deepEqual(calls.map(([kind]) => kind), ['claim', 'admit', 'prompt', 'receipt'])
  const prompt = calls.find(([kind]) => kind === 'prompt')[1]
  const zeroGuidance = guidance.replace('Revision: 2', 'Revision: 0')
  assert.equal(prompt.sessionId, 'bound-agent')
  assert.equal(prompt.content[0].text, zeroGuidance)
  assert.match(prompt.requestId, /^[a-f0-9]{32}$/)
  const digest = crypto.createHash('sha256').update(Buffer.from(zeroGuidance, 'utf8')).digest('hex')
  assert.equal(calls[0][1].payloadDigest, digest)
  assert.equal(calls[1][1].payloadDigest, digest)
  assert.equal(calls[3][1].deliveryCode, 'PROMPT_ACCEPTED')
  relay.dispose()
})

test('payload digest hashes exact UTF-8 text bytes including CJK, emoji, NBSP, and U+2028', async () => {
  const timers = []
  const unicodeText = guidance + '\nGoal: 中文 🧪\u00a0line\u2028next'
  let claimed
  const relay = createTaskFeedbackRelay({
    schedule: (callback) => { timers.push(callback); return callback }, cancel: () => {},
    readPending: async () => response('OK', 'false', { afterSequence: 0, nextSequence: 1,
      items: [{ ...item(), text: unicodeText }] }),
    claim: async (args) => { claimed = args; return response('OK', 'true', {
      feedbackId: args.feedbackId, state: 'claimed', requestId: args.requestId, payloadDigest: args.payloadDigest,
    }) },
    admit: async (args) => response('OK', 'true', { feedbackId: args.feedbackId, state: 'admitted',
      requestId: args.requestId, payloadDigest: args.payloadDigest }),
    sessionController: { prompt: async () => ({ accepted: true }) }, writeReceipt: async () => response('OK', 'true'),
  })
  relay.start({ agent: { id: 'bound-agent' } }, openResult())
  await runPoll(relay, timers)
  assert.equal(claimed.payloadDigest, crypto.createHash('sha256').update(Buffer.from(unicodeText, 'utf8')).digest('hex'))
  relay.dispose()
})

test('a transient claim failure retries the same sequence and stable request id', async () => {
  const timers = []
  const reads = []
  const claims = []
  let attempts = 0
  const relay = createTaskFeedbackRelay({
    schedule: (callback) => { timers.push(callback); return callback }, cancel: () => {},
    readPending: async ({ afterSequence }) => {
      reads.push(afterSequence)
      return response('OK', 'false', { afterSequence, nextSequence: 1, items: afterSequence === 0 ? [item()] : [] })
    },
    claim: async (args) => {
      claims.push(args.requestId)
      if (++attempts === 1) return response('FEEDBACK_CONFLICT', 'false')
      return response('OK', 'true', { feedbackId: args.feedbackId, state: 'claimed', requestId: args.requestId,
        payloadDigest: args.payloadDigest })
    },
    admit: async (args) => response('OK', 'true', { feedbackId: args.feedbackId, state: 'admitted',
      requestId: args.requestId, payloadDigest: args.payloadDigest }),
    sessionController: { prompt: async () => ({ accepted: true }) }, writeReceipt: async () => response('OK', 'true'),
  })
  relay.start({ agent: { id: 'bound-agent' } }, openResult())
  await runPoll(relay, timers)
  assert.equal(relay.status().afterSequence, 0)
  await runPoll(relay, timers)
  assert.deepEqual(reads, [0, 0])
  assert.equal(claims.length, 2)
  assert.equal(claims[0], claims[1])
  relay.dispose()
})

test('a later transient failure does not skip an item or resend an already prompted earlier item', async () => {
  const timers = []
  const reads = []
  let secondClaimFailed = false
  let promptCount = 0
  const secondItem = { ...item(), feedbackId: 'feedback_2', sequence: 2, taskId: 'task_2',
    text: guidance.replace('task_1', 'task_2') }
  const relay = createTaskFeedbackRelay({
    schedule: (callback) => { timers.push(callback); return callback }, cancel: () => {},
    readPending: async ({ afterSequence }) => {
      reads.push(afterSequence)
      const items = afterSequence === 0 ? [item(), secondItem] : afterSequence === 1 ? [secondItem] : []
      return response('OK', 'false', { afterSequence, nextSequence: items.at(-1)?.sequence || afterSequence, items })
    },
    claim: async (args) => {
      if (args.feedbackId === 'feedback_2' && !secondClaimFailed) {
        secondClaimFailed = true
        return response('FEEDBACK_CONFLICT', 'false')
      }
      return response('OK', 'true', { feedbackId: args.feedbackId, state: 'claimed', requestId: args.requestId,
        payloadDigest: args.payloadDigest })
    },
    admit: async (args) => response('OK', 'true', { feedbackId: args.feedbackId, state: 'admitted',
      requestId: args.requestId, payloadDigest: args.payloadDigest }),
    sessionController: { prompt: async () => { promptCount++; return { accepted: true } } },
    writeReceipt: async () => response('OK', 'true'),
  })
  relay.start({ agent: { id: 'bound-agent' } }, openResult())
  await runPoll(relay, timers)
  assert.equal(relay.status().afterSequence, 1)
  assert.equal(promptCount, 1)
  await runPoll(relay, timers)
  assert.deepEqual(reads, [0, 1])
  assert.equal(promptCount, 2)
  relay.dispose()
})

test('receipt failure after prompt admission never causes another prompt', async () => {
  const timers = []
  let promptCount = 0
  let readCount = 0
  const relay = createTaskFeedbackRelay({
    schedule: (callback) => { timers.push(callback); return callback }, cancel: () => {},
    readPending: async ({ afterSequence }) => {
      readCount++
      return response('OK', 'false', { afterSequence, nextSequence: readCount === 1 ? 1 : afterSequence,
        items: readCount === 1 ? [item()] : [] })
    },
    claim: async (args) => response('OK', 'true', { feedbackId: args.feedbackId, state: 'claimed',
      requestId: args.requestId, payloadDigest: args.payloadDigest }),
    admit: async (args) => response('OK', 'true', { feedbackId: args.feedbackId, state: 'admitted',
      requestId: args.requestId, payloadDigest: args.payloadDigest }),
    sessionController: { prompt: async () => { promptCount++; return { accepted: true } } },
    writeReceipt: async () => { throw new Error('receipt transport failed') },
  })
  relay.start({ agent: { id: 'bound-agent' } }, openResult())
  await runPoll(relay, timers)
  await runPoll(relay, timers)
  assert.equal(promptCount, 1)
  relay.dispose()
})

test('an already-admitted task feedback is marked unknown and never prompted again', async () => {
  const timers = []
  let prompts = 0
  let receipt
  const relay = createTaskFeedbackRelay({
    schedule: (callback) => { timers.push(callback); return callback }, cancel: () => {},
    readPending: async () => response('OK', 'false', { afterSequence: 0, nextSequence: 1, items: [item('claimed')] }),
    claim: async (args) => response('OK', 'true', { feedbackId: args.feedbackId, state: 'claimed',
      requestId: args.requestId, payloadDigest: args.payloadDigest }),
    admit: async (args) => response('FEEDBACK_ALREADY_ADMITTED', 'false', { feedbackId: args.feedbackId,
      requestId: args.requestId, payloadDigest: args.payloadDigest }),
    sessionController: { prompt: async () => { prompts++; return { accepted: true } } },
    writeReceipt: async (args) => { receipt = args; return response('OK', 'true') },
  })
  relay.start({ agent: { id: 'bound-agent' } }, openResult())
  await runPoll(relay, timers)
  assert.equal(prompts, 0)
  assert.equal(receipt.deliveryCode, 'UNKNOWN')
  relay.dispose()
})

test('an ambiguous admit that was not committed retries the still-claimed item', async () => {
  const timers = []
  const claims = []
  let admitCalls = 0
  let prompts = 0
  const relay = createTaskFeedbackRelay({
    schedule: (callback) => { timers.push(callback); return callback }, cancel: () => {},
    readPending: async ({ afterSequence }) => response('OK', 'false', {
      afterSequence, nextSequence: 1, items: afterSequence === 0 ? [item('claimed')] : [],
    }),
    claim: async (args) => {
      claims.push(args.requestId)
      return response('OK', 'true', { feedbackId: args.feedbackId, state: 'claimed', requestId: args.requestId,
        payloadDigest: args.payloadDigest })
    },
    admit: async (args) => {
      if (++admitCalls === 1) throw new Error('admit failed before commit')
      return response('OK', 'true', { feedbackId: args.feedbackId, state: 'admitted', requestId: args.requestId,
        payloadDigest: args.payloadDigest })
    },
    sessionController: { prompt: async () => { prompts++; return { accepted: true } } },
    writeReceipt: async () => response('OK', 'true'),
  })
  relay.start({ agent: { id: 'bound-agent' } }, openResult())
  await runPoll(relay, timers)
  assert.equal(relay.status().afterSequence, 0)
  await runPoll(relay, timers)
  assert.deepEqual(claims, [claims[0], claims[0]])
  assert.equal(prompts, 1)
  assert.equal(relay.status().afterSequence, 1)
  relay.dispose()
})

test('an ambiguous admit that committed is absent from pending and is never prompted again', async () => {
  const timers = []
  let admitted = false
  let prompts = 0
  const relay = createTaskFeedbackRelay({
    schedule: (callback) => { timers.push(callback); return callback }, cancel: () => {},
    readPending: async ({ afterSequence }) => response('OK', 'false', {
      afterSequence, nextSequence: afterSequence, items: admitted ? [] : [item('claimed')],
    }),
    claim: async (args) => response('OK', 'true', { feedbackId: args.feedbackId, state: 'claimed',
      requestId: args.requestId, payloadDigest: args.payloadDigest }),
    admit: async () => { admitted = true; throw new Error('response lost after commit') },
    sessionController: { prompt: async () => { prompts++; return { accepted: true } } },
    writeReceipt: async () => response('OK', 'true'),
  })
  relay.start({ agent: { id: 'bound-agent' } }, openResult())
  await runPoll(relay, timers)
  assert.equal(prompts, 0)
  assert.equal(relay.status().afterSequence, 0)
  await runPoll(relay, timers)
  assert.equal(prompts, 0)
  assert.equal(relay.status().afterSequence, 0)
  relay.dispose()
})

test('a same-generation agent change blocks the relay until a new Browser generation opens', () => {
  const timers = []
  const cancelled = []
  const relay = createTaskFeedbackRelay({
    schedule: (callback) => { timers.push(callback); return callback },
    cancel: (timer) => cancelled.push(timer),
    readPending: async () => response('OK', 'false', { afterSequence: 0, nextSequence: 0, items: [] }),
    claim: async () => response('OK', 'true'), admit: async () => response('OK', 'true'),
    sessionController: { prompt: async () => ({ accepted: true }) }, writeReceipt: async () => response('OK', 'true'),
  })
  const firstOpen = openResult(7)
  assert.equal(relay.start({ agent: { id: 'agent-a' } }, firstOpen).code, 'ACTIVE')
  assert.equal(relay.start({ agent: { id: 'agent-b' } }, firstOpen).code, 'SESSION_BINDING_CONFLICT')
  assert.equal(relay.status().code, 'INACTIVE')
  assert.equal(relay.start({ agent: { id: 'agent-b' } }, firstOpen).code, 'SESSION_BINDING_CONFLICT')
  assert.equal(cancelled.length, 1)
  assert.equal(relay.start({ agent: { id: 'agent-b' } }, openResult(8)).code, 'ACTIVE')
  assert.equal(relay.start({ agent: { id: 'agent-a' } }, openResult(7)).code, 'STALE_BROWSER_GENERATION')
  assert.equal(relay.status().sessionGeneration, 8)
  relay.dispose()
})

test('wrong-generation pending responses and stale closes cannot send or clear a current relay', async () => {
  const timers = []
  let claims = 0
  const relay = createTaskFeedbackRelay({
    schedule: (callback) => { timers.push(callback); return callback }, cancel: () => {},
    readPending: async () => response('OK', 'false', { afterSequence: 0, nextSequence: 0, items: [] }, 8),
    claim: async () => { claims++; return response('OK', 'true') },
    admit: async () => response('OK', 'true'),
    sessionController: { prompt: async () => ({ accepted: true }) }, writeReceipt: async () => response('OK', 'true'),
  })
  const exec = { agent: { id: 'agent-a' } }
  relay.start(exec, openResult(7))
  const staleClose = { code: 'OK', executed: 'true', state: 'CLOSED', sessionGeneration: 9,
    dataJson: JSON.stringify({ event: 'closed' }) }
  assert.equal(relay.afterClose(exec, staleClose).code, 'CLOSE_NOT_CONFIRMED')
  await runPoll(relay, timers)
  assert.equal(claims, 0)
  assert.equal(relay.status().code, 'INACTIVE')
  assert.equal(relay.start(exec, openResult(7)).code, 'ACTIVE')
  const correctClose = { code: 'OK', executed: 'true', state: 'CLOSED', sessionGeneration: 8,
    dataJson: JSON.stringify({ event: 'closed' }) }
  assert.equal(relay.afterClose(exec, correctClose).code, 'STOPPED')
  assert.equal(relay.status().code, 'INACTIVE')
})

test('a successful claim acknowledgement from the wrong Browser generation cannot admit or prompt', async () => {
  const timers = []
  let admits = 0
  let prompts = 0
  const relay = createTaskFeedbackRelay({
    schedule: (callback) => { timers.push(callback); return callback }, cancel: () => {},
    readPending: async () => response('OK', 'false', { afterSequence: 0, nextSequence: 1, items: [item()] }),
    claim: async (args) => response('OK', 'true', { feedbackId: args.feedbackId, state: 'claimed',
      requestId: args.requestId, payloadDigest: args.payloadDigest }, 8),
    admit: async () => { admits++; return response('OK', 'true') },
    sessionController: { prompt: async () => { prompts++; return { accepted: true } } },
    writeReceipt: async () => response('OK', 'true'),
  })
  relay.start({ agent: { id: 'agent-a' } }, openResult(7))
  await runPoll(relay, timers)
  assert.equal(admits, 0)
  assert.equal(prompts, 0)
  relay.dispose()
})

test('recovery text with mismatched task revision or without stop guidance is never delivered', async () => {
  const timers = []
  let claims = 0
  const relay = createTaskFeedbackRelay({
    schedule: (callback) => { timers.push(callback); return callback }, cancel: () => {},
    readPending: async () => response('OK', 'false', { afterSequence: 0, nextSequence: 1,
      items: [{ ...item(), text: guidance.replace('Revision: 2', 'Revision: 3') }] }),
    claim: async () => { claims++; return response('OK', 'true') }, admit: async () => response('OK', 'true'),
    sessionController: { prompt: async () => ({ accepted: true }) }, writeReceipt: async () => response('OK', 'true'),
  })
  relay.start({ agent: { id: 'agent-a' } }, openResult())
  await runPoll(relay, timers)
  assert.equal(claims, 0)
  relay.dispose()
})

test('native recovery text with notes is accepted when its task title matches the item', async () => {
  const timers = []
  let promptedText = null
  const recoveryText = `${guidance}\nNotes: Keep the work on the local fixture.`
  const relay = createTaskFeedbackRelay({
    schedule: (callback) => { timers.push(callback); return callback }, cancel: () => {},
    readPending: async () => response('OK', 'false', { afterSequence: 0, nextSequence: 1,
      items: [{ ...item(), text: recoveryText }] }),
    claim: async (args) => response('OK', 'true', { feedbackId: args.feedbackId, state: 'claimed',
      requestId: args.requestId, payloadDigest: args.payloadDigest }),
    admit: async (args) => response('OK', 'true', { feedbackId: args.feedbackId, state: 'admitted',
      requestId: args.requestId, payloadDigest: args.payloadDigest }),
    sessionController: { prompt: async (request) => { promptedText = request.content[0].text; return { accepted: true } } },
    writeReceipt: async () => response('OK', 'true'),
  })
  relay.start({ agent: { id: 'agent-a' } }, openResult())
  await runPoll(relay, timers)
  assert.equal(promptedText, recoveryText)
  relay.dispose()
})

test('recovery item title must match the title embedded in its payload', async () => {
  const timers = []
  let claims = 0
  const relay = createTaskFeedbackRelay({
    schedule: (callback) => { timers.push(callback); return callback }, cancel: () => {},
    readPending: async () => response('OK', 'false', { afterSequence: 0, nextSequence: 1,
      items: [{ ...item(), title: 'Different title' }] }),
    claim: async () => { claims++; return response('OK', 'true') },
    admit: async () => response('OK', 'true'),
    sessionController: { prompt: async () => ({ accepted: true }) },
    writeReceipt: async () => response('OK', 'true'),
  })
  relay.start({ agent: { id: 'agent-a' } }, openResult())
  await runPoll(relay, timers)
  assert.equal(claims, 0)
  assert.equal(relay.status().code, 'INACTIVE')
  relay.dispose()
})

test('UNKNOWN delivery stays hidden after relay restart and cannot prompt twice', async () => {
  const timers = []
  let admitted = false
  let promptCount = 0
  const sessionController = { prompt: async () => { promptCount++; throw new Error('ambiguous prompt transport') } }
  const deps = {
    schedule: (callback) => { timers.push(callback); return callback }, cancel: () => {},
    readPending: async ({ afterSequence }) => response('OK', 'false', {
      afterSequence, nextSequence: admitted ? afterSequence : 1, items: admitted ? [] : [item()],
    }),
    claim: async (args) => response('OK', 'true', { feedbackId: args.feedbackId, state: 'claimed',
      requestId: args.requestId, payloadDigest: args.payloadDigest }),
    admit: async (args) => {
      admitted = true
      return response('OK', 'true', { feedbackId: args.feedbackId, state: 'admitted', requestId: args.requestId, payloadDigest: args.payloadDigest })
    },
    sessionController,
    writeReceipt: async (args) => { assert.equal(args.deliveryCode, 'UNKNOWN'); return response('OK', 'true') },
  }
  const first = createTaskFeedbackRelay(deps)
  first.start({ agent: { id: 'agent-a' } }, openResult())
  await runPoll(first, timers)
  assert.equal(promptCount, 1)
  first.dispose()

  const restartTimers = []
  const restarted = createTaskFeedbackRelay({ ...deps,
    schedule: (callback) => { restartTimers.push(callback); return callback },
  })
  restarted.start({ agent: { id: 'agent-a' } }, openResult())
  await runPoll(restarted, restartTimers)
  assert.equal(promptCount, 1)
  restarted.dispose()
})

test('dispose cancels the active poll timer and rejects future opens', () => {
  const timers = []
  const cancelled = []
  const relay = createTaskFeedbackRelay({
    schedule: (callback) => { timers.push(callback); return callback }, cancel: (timer) => cancelled.push(timer),
    readPending: async () => response('OK', 'false', { afterSequence: 0, nextSequence: 0, items: [] }),
    claim: async () => response('OK', 'true'), admit: async () => response('OK', 'true'),
    sessionController: { prompt: async () => ({ accepted: true }) }, writeReceipt: async () => response('OK', 'true'),
  })
  relay.start({ agent: { id: 'agent-a' } }, openResult())
  assert.equal(relay.dispose().code, 'DISPOSED')
  assert.equal(cancelled.length, 1)
  assert.equal(relay.start({ agent: { id: 'agent-a' } }, openResult()).code, 'DISPOSED')
})

test('confirmed rejected newer open stops the old task relay even for another agent', () => {
  const cancelled = []
  const relay = createTaskFeedbackRelay({
    schedule: callback => callback, cancel: timer => cancelled.push(timer),
    readPending: async () => response('OK', 'false', { afterSequence: 3, nextSequence: 3, items: [] }),
    claim: async () => response('OK', 'true'), admit: async () => response('OK', 'true'),
    sessionController: { prompt: async () => ({ accepted: true }) }, writeReceipt: async () => response('OK', 'true'),
  })
  const a = { agent: { id: 'agent-a' } }
  const b = { agent: { id: 'agent-b' } }
  assert.equal(relay.start(a, openResult(7)).code, 'ACTIVE')
  relay.rejectOpen(b, { ...openResult(8), code: 'UNKNOWN' })
  assert.equal(relay.status().code, 'ACTIVE')
  relay.rejectOpen(b, openResult(6))
  assert.equal(relay.status().code, 'ACTIVE')
  relay.rejectOpen(b, openResult(8))
  assert.equal(relay.status().code, 'INACTIVE')
  assert.equal(cancelled.length, 1)
  relay.dispose()
})
