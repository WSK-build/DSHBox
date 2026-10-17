'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const net = require('node:net');
const path = require('node:path');
const test = require('node:test');
const { spawn, execFileSync } = require('node:child_process');
const { MAX_FRAME_BYTES, MAX_REQUEST_FRAME_BYTES, cancelRequest, execute, normalizeResponse, parseArguments, readChannelConfig, truncateUtf8, validateRequest } = require('../../main/assets/browser/lib/browser-cli.cjs');
const {
  MAX_ACTIVE_SERVICES, MAX_ACTIVE_STREAMS, MAX_START_LOCK_MS, acquireStartLock, makeServer, readLinuxProcessIdentity,
  parseServeArguments, requestControl, runServeCommand, safeRelative,
} = require('../../main/assets/browser/lib/browser-serve.cjs');

const now = Date.now();
test('CLI help exposes task/upload and owned/external service management without reading the channel', () => {
  const cliPath = path.resolve(__dirname, '../../main/assets/browser/lib/browser-cli.cjs');
  const help = execFileSync(process.execPath, ['-e', 'require(process.argv[1]).main(["--help"])', cliPath], { encoding: 'utf8', timeout: 5000 });
  assert.match(help, /\|upload\|task\|/);
  assert.match(help, /serve <start\|register\|status\|list\|logs\|stop\|unregister>/);
  assert.doesNotMatch(help, /BROKER_UNAVAILABLE|UNKNOWN/);
});
const request = (method, args = {}, extra = {}) => ({
  version: 1,
  requestId: 'req_01',
  method,
  deadlineAtMillis: now + 10_000,
  args,
  ...extra,
});

test('fixed methods and bounded argument schema', () => {
  assert.equal(validateRequest('status', JSON.stringify(request('status'))).method, 'status');
  assert.equal(validateRequest('observe', JSON.stringify(request('observe', {}, { sessionGeneration: 4 }))).sessionGeneration, 4);
  assert.equal(validateRequest('open', JSON.stringify(request('open', { url: 'https://example.test' }, { owner: 'ai' }))).method, 'open');
  assert.throws(() => validateRequest('click', JSON.stringify(request('click', { nodeId: '!' }, { owner: 'ai', sessionGeneration: 4, controlGeneration: 7 }))));
  assert.equal(validateRequest('click', JSON.stringify(request('click', { screenshotId: 'a'.repeat(32), x: 10.5, y: 20 }, { owner: 'ai', sessionGeneration: 4, controlGeneration: 7 }))).method, 'click');
  assert.equal(validateRequest('click', JSON.stringify(request('click', { nodeId: 'n1', eventSubscriptionId: 'a'.repeat(32), eventNonce: 'b'.repeat(64) }, { owner: 'ai', sessionGeneration: 4, controlGeneration: 7, taskId: 'task_1' }))).method, 'click');
  assert.throws(() => validateRequest('click', JSON.stringify(request('click', { nodeId: 'n1', screenshotId: 'a'.repeat(32), x: 1, y: 1 }, { owner: 'ai', sessionGeneration: 4, controlGeneration: 7 }))));
  assert.throws(() => validateRequest('click', JSON.stringify(request('click', { screenshotId: 'A'.repeat(32), x: 1, y: 1 }, { owner: 'ai', sessionGeneration: 4, controlGeneration: 7 }))));
  assert.throws(() => validateRequest('click', JSON.stringify(request('click', { screenshotId: 'a'.repeat(32), x: '1', y: 1 }, { owner: 'ai', sessionGeneration: 4, controlGeneration: 7 }))));
  assert.throws(() => validateRequest('click', JSON.stringify(request('click', { nodeId: 'n1', eventSubscriptionId: 'a'.repeat(32) }, { owner: 'ai', sessionGeneration: 4, controlGeneration: 7, taskId: 'task_1' }))));
  assert.throws(() => validateRequest('open', JSON.stringify(request('open', { url: 'javascript:alert(1)' }, { owner: 'ai' }))));
  assert.throws(() => validateRequest('type', JSON.stringify(request('type', { nodeId: 'n1', text: 'x'.repeat(4097) }, { owner: 'ai', sessionGeneration: 4, controlGeneration: 7 }))));
  assert.throws(() => validateRequest('observe', JSON.stringify(request('observe', {}, { sessionGeneration: '4' }))));
  assert.throws(() => validateRequest('observe', JSON.stringify(request('observe', { maxTextChars: 1.5 }, { sessionGeneration: 4 }))));
  assert.throws(() => validateRequest('open', JSON.stringify(request('open', {}, {
    owner: 'ai', sessionGeneration: null, controlGeneration: null,
  }))));
  assert.throws(() => validateRequest('status', JSON.stringify(request('status', {}, {
    deadlineAtMillis: String(now + 10_000),
  }))));
  assert.throws(() => validateRequest('type', JSON.stringify(request('type', { nodeId: 'n1', text: 3 }, { owner: 'ai', sessionGeneration: 4, controlGeneration: 7 }))));
});

test('control requests are bounded and carry no Browser authorization envelope', () => {
  const req = (args, fields = {}) => request('control', args, fields);
  assert.equal(validateRequest('control', JSON.stringify(req({ action: 'request', summary: 'Please let me continue', taskId: 'task_1' }))).args.action, 'request');
  assert.equal(validateRequest('control', JSON.stringify(req({ action: 'status', approvalId: 'a'.repeat(32) }))).args.action, 'status');
  assert.throws(() => validateRequest('control', JSON.stringify(req({ action: 'request', summary: ' ' }))));
  assert.throws(() => validateRequest('control', JSON.stringify(req({ action: 'request', summary: 'x'.repeat(1001) }))));
  assert.throws(() => validateRequest('control', JSON.stringify(req({ action: 'request', summary: 'ok', taskId: '../bad' }))));
  assert.throws(() => validateRequest('control', JSON.stringify(req({ action: 'request', summary: 'ok' }, { owner: 'ai' }))));
  assert.throws(() => validateRequest('control', JSON.stringify(req({ action: 'request', summary: 'ok' }, { sessionGeneration: 3 }))));
  assert.throws(() => validateRequest('control', JSON.stringify(req({ action: 'cancel', approvalId: 'A'.repeat(32) }))));
  assert.throws(() => validateRequest('control', JSON.stringify(req({ action: 'approve', approvalId: 'a'.repeat(32) }))));
});

test('control response requires truthful approval state and fixed diagnostic data', () => {
  const args = { action: 'request', summary: 'continue', taskId: 'task_1' };
  const response = { version: 1, requestId: 'req_01', code: 'OK', executed: false,
    sessionGeneration: null, controlGeneration: null, state: 'RUNNING', owner: 'human',
    data: { approvalId: 'a'.repeat(32), status: 'PENDING', summary: 'continue', taskId: 'task_1',
      createdAtMillis: 10, expiresAtMillis: 120010, coalesced: false } };
  assert.deepEqual(normalizeResponse('control', 'req_01', response, 'request', args), response);
  assert.equal(normalizeResponse('control', 'req_01', { ...response, executed: true }, 'request', args), null);
  assert.equal(normalizeResponse('control', 'req_01', { ...response, data: { ...response.data, approvalId: 'A'.repeat(32) } }, 'request', args), null);
  assert.equal(normalizeResponse('control', 'req_01', { ...response, data: { ...response.data, extra: true } }, 'request', args), null);
  const closed = { ...response, code: 'NO_ACTIVE_SESSION', state: 'CLOSED', owner: 'none', data: {} };
  assert.deepEqual(normalizeResponse('control', 'req_01', closed, 'request', args), closed);
  const surfaceUnavailable = { ...response, code: 'APPROVAL_SURFACE_UNAVAILABLE', data: { ...response.data, status: 'CANCELLED' } };
  assert.deepEqual(normalizeResponse('control', 'req_01', surfaceUnavailable, 'request', args), surfaceUnavailable);
  assert.equal(normalizeResponse('control', 'req_01', { ...surfaceUnavailable,
    data: { ...surfaceUnavailable.data, status: 'PENDING' } }, 'request', args), null);

  for (const [action, code] of [['request', 'CONTROL_TRANSFER_PENDING'], ['status', 'APPROVAL_NOT_FOUND'],
    ['cancel', 'APPROVAL_NOT_PENDING'], ['cancel', 'APPROVAL_EXPIRED'], ['request', 'DEADLINE_EXPIRED']]) {
    const actionArgs = action === 'request' ? { action, summary: 'continue' } : { action, approvalId: 'b'.repeat(32) };
    const failure = { ...closed, requestId: 'req_01', code, state: 'RUNNING' };
    assert.deepEqual(normalizeResponse('control', 'req_01', failure, action, actionArgs), failure);
  }
  assert.equal(normalizeResponse('control', 'req_01', { ...closed, code: 'CONTROL_REQUEST_PENDING' }, 'status',
    { action: 'status', approvalId: 'a'.repeat(32) }), null);
  assert.equal(normalizeResponse('control', 'req_01', { ...closed, code: 'APPROVAL_EXPIRED' }, 'request', args), null);
  const expiredApproval = { ...response, data: { ...response.data, status: 'EXPIRED', coalesced: false } };
  assert.deepEqual(normalizeResponse('control', 'req_01', expiredApproval, 'status', { action: 'status', approvalId: 'a'.repeat(32) }), expiredApproval);
});

test('CONTROL_TRANSFER_PENDING is accepted only for methods that can mutate Browser state', () => {
  const transfer = { version: 1, requestId: 'req_01', code: 'CONTROL_TRANSFER_PENDING', executed: false,
    sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'human', data: {} };
  assert.deepEqual(normalizeResponse('click', 'req_01', transfer, '', {}), transfer);
  assert.equal(normalizeResponse('observe', 'req_01', transfer, '', {}), null);
  assert.equal(normalizeResponse('status', 'req_01', transfer, '', {}), null);
  assert.equal(normalizeResponse('control', 'req_01', transfer, 'status', { action: 'status', approvalId: 'a'.repeat(32) }), null);
  assert.equal(normalizeResponse('click', 'req_01', { ...transfer, data: { dispatched: true } }, '', {}), null);
});

test('normalization preserves authoritative state and permits unknown state only when unavailable', () => {
  const unavailable = { version: 1, requestId: 'req_01', code: 'UNKNOWN', executed: null,
    sessionGeneration: null, controlGeneration: null, state: null, owner: null, data: {} };
  assert.deepEqual(normalizeResponse('screenshot', 'req_01', unavailable, '', {}), unavailable);
  assert.deepEqual(normalizeResponse('screenshot', 'req_01', { ...unavailable, data: { cancelCode: 'CANCELLED' } }, '', {}).data,
    { cancelCode: 'CANCELLED' });
  const authoritative = { ...unavailable, state: 'RUNNING', sessionGeneration: 4, controlGeneration: 9 };
  assert.equal(normalizeResponse('screenshot', 'req_01', authoritative, '', {}).state, 'RUNNING');
  assert.equal(normalizeResponse('screenshot', 'req_01', { ...unavailable, code: 'CANCELLED', executed: false }, '', {}), null);
});

test('cancellation receipts stay distinct from original action terminals', { skip: process.platform === 'win32' }, async () => {
  const socketName = `browser-cancel-receipt-${process.pid}-${Date.now()}`;
  const token = 'e'.repeat(64);
  const receipt = {
    version: 1, requestId: 'cancel-receipt', code: 'CANCELLED', executed: null,
    sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai',
    data: {
      receiptKind: 'cancel_request', cancelStage: 'condition_wait', reason: 'cancel_accepted',
      actionOutcome: 'unknown', nextAction: 'read_original_request_result',
      automaticRetryAllowed: false,
    },
  };
  const peers = new Set();
  const server = net.createServer(peer => {
    peers.add(peer);
    peer.once('close', () => peers.delete(peer));
    let buffer = '';
    peer.setEncoding('utf8');
    peer.on('data', chunk => {
      buffer += chunk;
      const newline = buffer.indexOf('\n');
      if (newline < 0) return;
      const frame = JSON.parse(buffer.slice(0, newline));
      assert.equal(frame.op, 'cancel');
      peer.end(`${JSON.stringify({ ...receipt, requestId: frame.requestId })}\n`);
    });
  });
  await new Promise((resolve, reject) => server.listen(`\0${socketName}`, error => error ? reject(error) : resolve()));
  try {
    const accepted = await cancelRequest('cancel-receipt', { socketName, token });
    assert.equal(accepted.executed, null);
    assert.equal(accepted.data.actionOutcome, 'unknown');
    assert.equal(normalizeResponse('wait', 'cancel-receipt', structuredClone(accepted), 'load', {}), null,
      'a cancel receipt must not be consumed as the wait terminal');

    for (const mutate of [
      value => { value.executed = false },
      value => { delete value.data.nextAction },
      value => { value.data.cancelStage = 'made_up' },
      value => { value.data.actionOutcome = 'not_dispatched' },
      value => { value.data.automaticRetryAllowed = true },
      value => { value.data.extra = true },
    ]) {
      const invalid = structuredClone(receipt);
      mutate(invalid);
      const invalidPeers = new Set();
      const serverInvalid = net.createServer(peer => {
        invalidPeers.add(peer);
        peer.once('close', () => invalidPeers.delete(peer));
        peer.end(`${JSON.stringify(invalid)}\n`);
      });
      const badSocket = `${socketName}-invalid-${Math.random()}`;
      await new Promise((resolve, reject) => serverInvalid.listen(`\0${badSocket}`, error => error ? reject(error) : resolve()));
      try {
        const rejected = await cancelRequest('cancel-receipt', { socketName: badSocket, token });
        assert.equal(rejected.code, 'UNKNOWN');
        assert.equal(rejected.data.reason, 'invalid_runtime_receipt');
        assert.equal(rejected.data.actionOutcome, 'unknown');
      } finally {
        for (const peer of invalidPeers) peer.destroy();
        await new Promise(resolve => serverInvalid.close(resolve));
      }
    }
  } finally {
    for (const peer of peers) peer.destroy();
    await new Promise(resolve => server.close(resolve));
  }

  const queuedTerminal = {
    version: 1, requestId: 'queued-terminal', code: 'CANCELLED', executed: false,
    sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai',
    data: { cancelStage: 'queued', reason: 'cancel_before_dispatch',
      actionOutcome: 'not_dispatched', automaticRetryAllowed: false },
  };
  assert.ok(normalizeResponse('click', queuedTerminal.requestId, queuedTerminal, '', {}),
    'queued terminal must round-trip through the CLI');
});

test('task metadata edits use revision guards while preserving safe legacy actions', () => {
  const active = { owner: 'ai', sessionGeneration: 4, controlGeneration: 9 };
  const read = { action: 'status', taskId: 'task_01' };
  assert.equal(validateRequest('task', JSON.stringify(request('task', read))).args.action, 'status');
  assert.equal(validateRequest('task', JSON.stringify(request('task', { action: 'begin', title: 'T', goal: 'G', notes: 'N' }, active))).args.goal, 'G');
  assert.equal(validateRequest('task', JSON.stringify(request('task', { action: 'update', taskId: 'task_01', expectedRevision: 2, notes: '' }))).args.notes, '');
  assert.equal(validateRequest('task', JSON.stringify(request('task', { action: 'update', taskId: 'task_01', expectedRevision: 0, goal: 'Initial revision' }))).args.expectedRevision, 0);
  assert.equal(validateRequest('task', JSON.stringify(request('task', { action: 'delete', taskId: 'task_01', expectedRevision: 2 }))).args.action, 'delete');
  assert.equal(validateRequest('task', JSON.stringify(request('task', { action: 'delete', taskId: 'task_01', expectedRevision: 0 }))).args.expectedRevision, 0);
  assert.equal(validateRequest('task', JSON.stringify(request('task', { action: 'fail', taskId: 'task_01', reason: 'network failure' }))).args.reason, 'network failure');
  assert.equal(validateRequest('task', JSON.stringify(request('task', { action: 'fail', taskId: 'task_01' }))).args.action, 'fail');
  assert.throws(() => validateRequest('task', JSON.stringify(request('task', { action: 'fail', taskId: 'task_01', reason: '   ' }))));
  assert.throws(() => validateRequest('task', JSON.stringify(request('task', { action: 'fail', taskId: 'task_01', reason: 'x'.repeat(1001) }))));
  assert.throws(() => validateRequest('task', JSON.stringify(request('task', { action: 'wait', taskId: 'task_01', reason: 'free text' }))));
  assert.throws(() => validateRequest('task', JSON.stringify(request('task', { action: 'reconcile', taskId: 'task_01', expectedRevision: 0 }, active))));
  for (const args of [
    { action: 'begin', title: 'x'.repeat(121) },
    { action: 'begin', goal: 'x'.repeat(4001) },
    { action: 'begin', notes: 'x'.repeat(2001) },
    { action: 'update', taskId: 'task_01', expectedRevision: 1 },
    { action: 'update', taskId: 'task_01', expectedRevision: -1, title: 'x' },
    { action: 'delete', taskId: 'task_01', expectedRevision: -1 },
    { action: 'update', taskId: 'task_01', expectedRevision: 1, title: 'x', url: 'https://example.test' },
    { action: 'delete', taskId: 'task_01' },
  ]) assert.throws(() => validateRequest('task', JSON.stringify(request('task', args, args.action === 'begin' ? active : {}))));
});

test('task list pagination and private feedback actions use bounded exact schemas', () => {
  assert.equal(MAX_REQUEST_FRAME_BYTES, 16 * 1024)
  assert.equal(MAX_FRAME_BYTES, 256 * 1024)
  assert.equal(validateRequest('task', JSON.stringify(request('task', { action: 'list', offset: 20, limit: 40 }))).args.limit, 40)
  assert.throws(() => validateRequest('task', JSON.stringify(request('task', { action: 'list', offset: -1 }))))
  assert.throws(() => validateRequest('task', JSON.stringify(request('task', { action: 'list', limit: 101 }))))
  for (const args of [
    { action: 'task_pending', afterSequence: 0, destinationSessionKey: 'agent-id' },
    { action: 'task_claim', feedbackId: 'feedback_1', destinationSessionKey: 'agent-id', requestId: 'a'.repeat(32), payloadDigest: 'b'.repeat(64) },
    { action: 'task_admit', feedbackId: 'feedback_1', destinationSessionKey: 'agent-id', requestId: 'a'.repeat(32), payloadDigest: 'b'.repeat(64) },
    { action: 'task_receipt', feedbackId: 'feedback_1', destinationSessionKey: 'agent-id', requestId: 'a'.repeat(32), deliveryCode: 'PROMPT_ACCEPTED' },
  ]) assert.equal(validateRequest('selection', JSON.stringify(request('selection', args, { sessionGeneration: 7 }))).args.action, args.action)
  assert.throws(() => validateRequest('selection', JSON.stringify(request('selection', {
    action: 'task_claim', feedbackId: 'feedback_1', destinationSessionKey: 'agent-id', requestId: 'a'.repeat(32),
    payloadDigest: 'b'.repeat(64), enqueue: true,
  }, { sessionGeneration: 7 }))))
  assert.throws(() => validateRequest('status', JSON.stringify(request('status', { padding: 'x'.repeat(MAX_REQUEST_FRAME_BYTES) }))))
})

test('CLI validates 64 KiB feedback text within the 256 KiB response frame', () => {
  const text = `Recovery guidance: ${'x'.repeat(64 * 1024 - 19)}`
  const args = { action: 'task_pending', afterSequence: 0, destinationSessionKey: 'agent-id' }
  const feedback = { feedbackId: 'feedback_1', kind: 'task_recovery', taskId: 'task_1', revision: 2,
    title: 'Continue', text, sequence: 1, state: 'pending', sessionGeneration: 7 }
  const responseValue = { version: 1, requestId: 'req_01', code: 'OK', executed: false, sessionGeneration: 7,
    controlGeneration: null, state: 'RUNNING', owner: null,
    data: { afterSequence: 0, nextSequence: 1, items: [feedback] } }
  assert.equal(normalizeResponse('selection', 'req_01', responseValue, args.action, args).data.items[0].text, text)
  responseValue.data.items[0] = { ...feedback, revision: 0 }
  assert.equal(normalizeResponse('selection', 'req_01', responseValue, args.action, args).data.items[0].revision, 0)
  responseValue.data.items[0].revision = -1
  assert.equal(normalizeResponse('selection', 'req_01', responseValue, args.action, args), null)
  responseValue.data.items[0] = feedback
  responseValue.data.items[0].text = 'x'.repeat(64 * 1024 + 1)
  assert.equal(normalizeResponse('selection', 'req_01', responseValue, args.action, args), null)
  responseValue.data.items = Array.from({ length: 5 }, (_, index) => ({ ...feedback, feedbackId: `feedback_${index + 1}`,
    sequence: index + 1, text: `Recovery guidance: ${'x'.repeat(60 * 1024)}` }))
  responseValue.data.nextSequence = 5
  assert.equal(normalizeResponse('selection', 'req_01', responseValue, args.action, args), null)
})

test('upload requests require scoped AI credentials and only safe project-relative paths', () => {
  const control = { owner: 'ai', sessionGeneration: 4, controlGeneration: 9, taskId: 'task_01' };
  const valid = request('upload', { action: 'request', paths: ['reports/report.pdf'], acceptTypes: ['application/pdf'], multiple: false }, control);
  assert.equal(validateRequest('upload', JSON.stringify(valid)).method, 'upload');
  for (const path of ['/etc/passwd', '../secret', 'reports/../x', 'user-data/private.txt', '.dsh/config', '.env.local', 'id_rsa', 'api_token.txt']) {
    assert.throws(() => validateRequest('upload', JSON.stringify(request('upload', { action: 'request', paths: [path], multiple: false }, control))));
  }
  assert.throws(() => validateRequest('upload', JSON.stringify(request('upload', { action: 'request', paths: ['a.txt', 'a.txt'], multiple: false }, control))));
  assert.throws(() => validateRequest('upload', JSON.stringify(request('upload', { action: 'request', paths: ['a.txt'] }, { ...control, taskId: undefined }))));
  assert.throws(() => validateRequest('upload', JSON.stringify(request('upload', { action: 'request', paths: ['a.txt'], multiple: false }, { ...control, owner: 'human' }))));
  assert.throws(() => validateRequest('upload', JSON.stringify(request('upload', { action: 'submit', approvalId: 'a'.repeat(32), fileIds: ['../bad'] }, control))));
  assert.equal(validateRequest('upload', JSON.stringify(request('upload', { action: 'activate', approvalId: 'a'.repeat(32), nodeId: `b:4:7:3:1:${'b'.repeat(32)}:0` }, control))).args.action, 'activate');
  assert.throws(() => validateRequest('upload', JSON.stringify(request('upload', { action: 'activate', approvalId: 'a'.repeat(32), nodeId: 'stale' }, control))));
});

test('upload response contract preserves pending, one-shot, and non-verified delivery states', () => {
  const base = { version: 1, requestId: 'req_01', code: 'OK', executed: true, sessionGeneration: 4,
    controlGeneration: 9, state: 'RUNNING', owner: 'ai', data: {} };
  const requestData = { state: 'awaiting_approval', approvalId: 'a'.repeat(32), pageId: 'pg-0123456789abcdefabcd',
    documentGeneration: 2, fileCount: 1, bytesTotal: 4, expiresInMs: 120000 };
  assert.ok(normalizeResponse('upload', 'req_01', { ...base, data: requestData }, 'request', { action: 'request' }));
  const pending = { ...base, executed: false, data: { approvalId: 'a'.repeat(32), state: 'pending', expiresInMs: 120000 } };
  assert.ok(normalizeResponse('upload', 'req_01', pending, 'list', { action: 'list', approvalId: 'a'.repeat(32) }));
  assert.equal(normalizeResponse('upload', 'req_01', { ...pending, executed: true }, 'list', { action: 'list', approvalId: 'a'.repeat(32) }), null);
  const submitted = { ...base, data: { approvalId: 'a'.repeat(32), state: 'chooser_delivered', fileCount: 1, websiteUploadVerified: false } };
  assert.ok(normalizeResponse('upload', 'req_01', submitted, 'submit', { action: 'submit', approvalId: 'a'.repeat(32), fileIds: ['b'.repeat(32)] }));
  const activated = { ...base, code: 'OK', executed: true, data: { approvalId: 'a'.repeat(32), state: 'chooser_pending' } };
  assert.ok(normalizeResponse('upload', 'req_01', activated, 'activate', { action: 'activate', approvalId: 'a'.repeat(32) }));
  assert.ok(normalizeResponse('upload', 'req_01', { ...base, code: 'UPLOAD_CHOOSER_NOT_OPENED', executed: true, data: {} }, 'activate', { action: 'activate', approvalId: 'a'.repeat(32) }));
  assert.ok(normalizeResponse('upload', 'req_01', { ...base, code: 'UPLOAD_ACTIVATION_UNRESOLVED', executed: true, data: {} }, 'activate', { action: 'activate', approvalId: 'a'.repeat(32) }));
  assert.equal(normalizeResponse('upload', 'req_01', { ...submitted, data: { ...submitted.data, websiteUploadVerified: true } }, 'submit', { action: 'submit', approvalId: 'a'.repeat(32), fileIds: ['b'.repeat(32)] }), null);
});

test('event request schema is exact and carries task-scoped AI credentials', () => {
  const control = { owner: 'ai', sessionGeneration: 4, controlGeneration: 7, taskId: 'task_1' };
  assert.equal(validateRequest('event', JSON.stringify(request('event', { action: 'arm', eventKinds: ['navigation_started', 'javascript_dialog'], ttlMs: 15000 }, control))).method, 'event');
  assert.equal(validateRequest('event', JSON.stringify(request('event', { action: 'wait', subscriptionId: 'a'.repeat(32), timeoutMs: 15000 }, control))).method, 'event');
  assert.equal(validateRequest('event', JSON.stringify(request('event', { action: 'cancel', subscriptionId: 'a'.repeat(32) }, control))).method, 'event');
  assert.equal(validateRequest('event', JSON.stringify(request('event', { action: 'resolve_dialog', eventId: 'a'.repeat(32), decision: 'prompt', promptText: 'hello' }, control))).method, 'event');
  for (const args of [
    { action: 'arm', eventKinds: [] },
    { action: 'arm', eventKinds: ['download', 'download'] },
    { action: 'arm', eventKinds: ['unknown'] },
    { action: 'wait', subscriptionId: 'A'.repeat(32) },
    { action: 'resolve_dialog', eventId: 'a'.repeat(32), decision: 'dismiss', promptText: 'unexpected' },
    { action: 'resolve_dialog', eventId: 'a'.repeat(32), decision: 'prompt', promptText: 'x'.repeat(4097) },
  ]) assert.throws(() => validateRequest('event', JSON.stringify(request('event', args, control))));
  assert.throws(() => validateRequest('event', JSON.stringify(request('event', { action: 'cancel', subscriptionId: 'a'.repeat(32) }, { ...control, taskId: undefined }))));
  assert.throws(() => validateRequest('event', JSON.stringify(request('event', { action: 'cancel', subscriptionId: 'a'.repeat(32) }, { ...control, owner: 'human' }))));
});

test('event receipts are bounded, identity checked, and retain scoped attribution', () => {
  const base = { version: 1, requestId: 'req_01', code: 'OK', executed: true, sessionGeneration: 4,
    controlGeneration: 7, state: 'RUNNING', owner: 'ai', data: {} };
  const identity = { sessionGeneration: 4, controlGeneration: 7, pageId: 'pg-0123456789abcdefabcd', documentGeneration: 2, navigationId: 3 };
  const armed = { ...base, data: { subscriptionId: 'a'.repeat(32), nonce: 'b'.repeat(64), expiresAtElapsedMillis: 20000, identity } };
  assert.equal(normalizeResponse('event', 'req_01', armed, 'arm', {}).code, 'OK');
  assert.equal(normalizeResponse('event', 'req_01', { ...armed, data: { ...armed.data, nonce: 'B'.repeat(64) } }, 'arm', {}), null);
  const args = { subscriptionId: 'a'.repeat(32) };
  const receipt = { eventId: 'c'.repeat(32), subscriptionId: args.subscriptionId, actionRequestId: 'req_02',
    eventKind: 'navigation_finished', attribution: 'action_scoped_observation', pageId: identity.pageId,
    documentGeneration: 2, navigationId: 4, url: 'https://example.test/' };
  assert.equal(normalizeResponse('event', 'req_01', { ...base, data: receipt }, 'wait', args).data.attribution, 'action_scoped_observation');
  const newWindowReceipt = { ...receipt, eventKind: 'new_window', newPageId: 'pg-0123456789abcdefabcd' };
  assert.equal(normalizeResponse('event', 'req_01', { ...base, data: newWindowReceipt }, 'wait', args).data.newPageId, newWindowReceipt.newPageId);
  assert.equal(normalizeResponse('event', 'req_01', { ...base, data: { ...newWindowReceipt, newPageId: 'pg-ABCDEF0123456789abcd' } }, 'wait', args), null);
  assert.equal(normalizeResponse('event', 'req_01', { ...base, data: { ...receipt, newPageId: 'pg-0123456789abcdefabcd' } }, 'wait', args), null);
  const downloadReceipt = { ...receipt, eventKind: 'download', downloadId: 'd'.repeat(32) };
  assert.equal(normalizeResponse('event', 'req_01', { ...base, data: downloadReceipt }, 'wait', args).data.downloadId, downloadReceipt.downloadId);
  assert.equal(normalizeResponse('event', 'req_01', { ...base, data: { ...downloadReceipt, downloadId: 'D'.repeat(32) } }, 'wait', args), null);
  assert.equal(normalizeResponse('event', 'req_01', { ...base, data: { ...receipt, downloadId: 'd'.repeat(32) } }, 'wait', args), null);
  assert.equal(normalizeResponse('event', 'req_01', { ...base, data: { ...receipt, url: 'https://example.test/?secret=x' } }, 'wait', args), null);
  for (const code of ['TASK_PAGE_MISMATCH', 'JOURNAL_UNAVAILABLE', 'BUSY', 'TASK_NOT_FOUND',
    'TASK_UNKNOWN_EFFECT_UNVERIFIED', 'TASK_EXPIRED', 'TASK_NOT_RUNNING', 'TASK_BUDGET_EXHAUSTED']) {
    const denied = normalizeResponse('event', 'req_01', { ...base, code, executed: false, data: {} }, 'arm', {});
    assert.equal(denied?.code, code, `${code} must remain a known nonexecuted rejection`);
    assert.equal(denied?.executed, false);
  }
  assert.equal(normalizeResponse('event', 'req_01', { ...base, code: 'TASK_INVENTED', executed: false, data: {} }, 'arm', {}), null);
  assert.equal(normalizeResponse('event', 'req_01', { ...base, code: 'EVENT_TIMEOUT', executed: false,
    data: { subscriptionId: args.subscriptionId, matched: false } }, 'wait', args).code, 'EVENT_TIMEOUT');
  const unknown = { ...base, code: 'UNKNOWN', executed: null, data: { cancelCode: 'CANCELLED' } };
  assert.equal(normalizeResponse('event', 'req_01', unknown, 'arm', {}).executed, null);
  assert.equal(normalizeResponse('event', 'req_01', { ...unknown, executed: false }, 'arm', {}), null);
});

test('collect window requires exact bounded selectors and AI generations', () => {
  const args = { action: 'window', containerSelector: '#list', messageSelector: 'article.item', keyAttribute: 'data-id',
    declaredRange: 'latest 20', maxRecords: 20, maxContentChars: 1024 };
  const valid = request('collect', args, { owner: 'ai', sessionGeneration: 4, controlGeneration: 7 });
  assert.equal(validateRequest('collect', JSON.stringify(valid)).method, 'collect');
  assert.equal(validateRequest('collect', JSON.stringify({ ...valid, args: { ...args, excludeSelector: '.draft' } })).method, 'collect');
  assert.throws(() => validateRequest('collect', JSON.stringify({ ...valid, args: { ...args, excludeSelector: '' } })));
  assert.throws(() => validateRequest('collect', JSON.stringify({ ...valid, args: { ...args, excludeSelector: 'x'.repeat(513) } })));
  assert.throws(() => validateRequest('collect', JSON.stringify({ ...valid, args: { ...args, maxRecords: 201 } })));
  assert.throws(() => validateRequest('collect', JSON.stringify({ ...valid, args: { ...args, extra: true } })));
  assert.throws(() => validateRequest('collect', JSON.stringify({ ...valid, owner: 'human' })));
  const data = { action: 'window', declaredRange: 'latest 20', truncated: false, excludedCount: 2,
    identity: { pageId: 'pg-0123456789abcdefabcd', documentGeneration: 3, navigationId: 4, observationId: 5,
      sessionGeneration: 4, controlGeneration: 7 },
    containerNodeId: `b:4:7:3:5:${'a'.repeat(32)}:0`,
    container: { scrollTop: 0, scrollHeight: 500, clientHeight: 100, startReached: true, endReached: false, totalCount: 4 },
    records: [], };
  const response = { version: 1, requestId: 'req_01', code: 'OK', executed: true, sessionGeneration: 4,
    controlGeneration: 7, state: 'RUNNING', owner: 'ai', data };
  assert.equal(normalizeResponse('collect', 'req_01', structuredClone(response), 'window', args).data.excludedCount, 2);
  assert.equal(normalizeResponse('collect', 'req_01', { ...structuredClone(response), data: { ...data, excludedRow: { key: 'private' } } }, 'window', args), null);
  assert.equal(normalizeResponse('collect', 'req_01', { ...structuredClone(response), data: { ...data, excludedCount: -1 } }, 'window', args), null);
});

test('SIGTERM keeps the request until one receipt is followed by the original wait terminal', { skip: process.platform !== 'linux' }, async () => {
  const socketName = `browser-cli-sigterm-${process.pid}-${Date.now()}`;
  const token = 'f'.repeat(64);
  let cancelFrames = 0;
  const peers = new Set();
  let requestPeer;
  const server = net.createServer((peer) => {
    peers.add(peer);
    peer.once('close', () => peers.delete(peer));
    let buffer = '';
    peer.setEncoding('utf8');
    peer.on('data', (chunk) => {
      buffer += chunk;
      const newline = buffer.indexOf('\n');
      if (newline < 0) return;
      const frame = JSON.parse(buffer.slice(0, newline));
      assert.equal(frame.token, token);
      if (frame.op === 'request') {
        requestPeer = peer;
        return;
      }
      assert.equal(frame.op, 'cancel');
      cancelFrames += 1;
      peer.end(`${JSON.stringify({ version: 1, requestId: frame.requestId, code: 'CANCELLED', executed: null,
        sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai',
        data: { receiptKind: 'cancel_request', cancelStage: 'condition_wait',
          reason: 'cancel_accepted', actionOutcome: 'unknown',
          nextAction: 'read_original_request_result', automaticRetryAllowed: false } })}\n`);
      setImmediate(() => requestPeer?.end(`${JSON.stringify({ version: 1, requestId: frame.requestId,
        code: 'CANCELLED', executed: false, sessionGeneration: 4, controlGeneration: 9,
        state: 'RUNNING', owner: 'ai', data: { matched: false, condition: 'load', pageId: null,
          navigationId: null, documentGeneration: null, url: null, loadState: 'unknown',
          reason: 'cancelled', stage: 'condition_wait', cancellationReason: 'user_cancelled',
          nextAction: 'read_status_and_observe', automaticRetryAllowed: false } })}\n`));
    });
  });
  await new Promise((resolve, reject) => server.listen(`\0${socketName}`, (error) => error ? reject(error) : resolve()));
  const modulePath = require.resolve('../../main/assets/browser/lib/browser-cli.cjs');
  const script = [
    'const cli=require(process.argv[1]);',
    'const [socketName,token]=process.argv.slice(2);',
    'const request={version:1,requestId:"sigterm-wait",method:"wait",deadlineAtMillis:Date.now()+20000,args:{condition:"load"},sessionGeneration:4};',
    'cli.execute("wait",request,{socketName,token}).then((value)=>process.stdout.write(JSON.stringify(value)+"\\n"));'
  ].join('');
  const child = spawn(process.execPath, ['-e', script, modulePath, socketName, token], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  let stdout = '';
  let stderr = '';
  child.stdout.setEncoding('utf8').on('data', (chunk) => { stdout += chunk; });
  child.stderr.setEncoding('utf8').on('data', (chunk) => { stderr += chunk; });
  try {
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('wait CLI did not send request frame')), 3000);
      server.once('connection', () => { clearTimeout(timer); resolve(); });
      child.once('error', reject);
    });
    child.kill('SIGTERM');
    const exit = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('wait CLI did not finish after cancellation acknowledgement')), 3000);
      child.once('exit', (code, signal) => { clearTimeout(timer); resolve({ code, signal }); });
    });
    assert.equal(exit.code, 0, stderr);
    const response = JSON.parse(stdout.trim());
    assert.equal(response.code, 'CANCELLED', stdout);
    assert.equal(response.executed, false);
    assert.equal(response.data.stage, 'condition_wait');
    assert.equal(response.data.receiptKind, undefined);
    assert.equal(response.data.actionOutcome, undefined);
    assert.equal(cancelFrames, 1);
  } finally {
    if (child.exitCode === null) child.kill('SIGKILL');
    for (const peer of peers) peer.destroy();
    await new Promise((resolve) => server.close(resolve));
  }
});

test('wait is session-scoped and navigation requires current AI ownership', () => {
  const readContext = { sessionGeneration: 4 };
  const actionContext = { owner: 'ai', sessionGeneration: 4, controlGeneration: 9 };
  assert.equal(validateRequest('wait', JSON.stringify(request('wait', {
    condition: 'selector', selector: '#ready', state: 'visible', timeoutMs: 30_000, pollIntervalMs: 100,
  }, readContext))).method, 'wait');
  assert.equal(validateRequest('navigate', JSON.stringify(request('navigate', { action: 'back' }, actionContext))).method, 'navigate');
  for (const args of [
    { condition: 'load', selector: '#unexpected' },
    { condition: 'selector', selector: '#ready', state: 'visible', controlGeneration: 9 },
    { condition: 'url', url: 'https://example.test', timeoutMs: 30_001 },
    { condition: 'text', text: 'ready', pollIntervalMs: 99 },
  ]) assert.throws(() => validateRequest('wait', JSON.stringify(request('wait', args, readContext))));
  assert.throws(() => validateRequest('navigate', JSON.stringify(request('navigate', { action: 'back' }, readContext))));
  assert.throws(() => validateRequest('navigate', JSON.stringify(request('navigate', { action: 'goto' }, actionContext))));
});

test('locate requires AI control and validates bounded semantic strategies and current scope references', () => {
  const control = { owner: 'ai', sessionGeneration: 4, controlGeneration: 9 };
  const ref = 'b:4:9:3:7:0123456789abcdef0123456789abcdef:0';
  assert.equal(validateRequest('locate', JSON.stringify(request('locate', {
    strategy: 'role', value: 'button', exact: true, name: 'Continue', scopeRef: ref,
  }, control))).method, 'locate');
  assert.equal(validateRequest('locate', JSON.stringify(request('locate', {
    strategy: 'css', value: '#submit', exact: false,
  }, control))).method, 'locate');
  for (const args of [
    { strategy: 'role', value: 'button', exact: true, name: '' },
    { strategy: 'label', value: 'Email', exact: false, name: 'forbidden' },
    { strategy: 'css', value: 'x'.repeat(2049), exact: true },
    { strategy: 'text', value: 'Submit', exact: 1 },
    { strategy: 'role', value: 'button', exact: true, scopeRef: 'n1' },
    { strategy: 'xpath', value: '//button', exact: true },
  ]) assert.throws(() => validateRequest('locate', JSON.stringify(request('locate', args, control))));
  assert.throws(() => validateRequest('locate', JSON.stringify(request('locate', {
    strategy: 'role', value: 'button', exact: true,
  }, { owner: 'ai', sessionGeneration: 4 }))));
});

test('advanced wire families require positive authorization context and bounded schemas', () => {
  const control = { owner: 'ai', sessionGeneration: 2, controlGeneration: 3 };
  assert.equal(validateRequest('debug', JSON.stringify(request('debug', { action: 'evaluate', expression: '1+1' }, control))).method, 'debug');
  assert.equal(validateRequest('network', JSON.stringify(request('network', { action: 'rules', rules: [
    { urlPattern: 'https://example.test/*', stage: 'response', action: 'fulfill', body: 'safe' },
  ] }, control))).method, 'network');
  assert.equal(validateRequest('network', JSON.stringify(request('network', { action: 'frames', cursor: 0, limit: 20 }, control))).method, 'network');
  for (const args of [
    { action: 'start', includeRequestBodies: true }, { action: 'export_frames' },
    { action: 'replay_prepare', entryId: 'entry-1' }, { action: 'replay_status', requestId: 'a'.repeat(32) },
    { action: 'replay', entryId: 'entry-1' },
    { action: 'replay', entryId: 'entry-1', riskToken: 'a'.repeat(32), captureId: `cap-${'b'.repeat(20)}` },
  ]) assert.equal(validateRequest('network', JSON.stringify(request('network', args, control))).method, 'network');
  assert.equal(validateRequest('selection', JSON.stringify(request('selection', { action: 'latest' }, { sessionGeneration: 2 }))).method, 'selection');
  for (const [method, args, context] of [
    ['debug', { action: 'evaluate', expression: '1' }, { ...control, controlGeneration: 0 }],
    ['debug', { action: 'interact', kind: 'tap', x: Infinity, y: 2 }, control],
    ['network', { action: 'rules', rules: [{ urlPattern: '*', stage: 'response', action: 'modify', postData: 'x' }] }, control],
    ['network', { action: 'frames', cursor: -1 }, control],
    ['network', { action: 'frames', limit: 21 }, control],
    ['network', { action: 'frames', webSocketId: 'ws-01234567890123456789' }, control],
    ['network', { action: 'resolve', pausedId: 'p1', decision: 'continue', body: 'x' }, control],
    ['network', { action: 'export_frames', arbitrary: true }, control],
    ['network', { action: 'replay_prepare', entryId: 'entry-1', sessionGeneration: 2 }, control],
    ['network', { action: 'replay_status', requestId: 'A'.repeat(32) }, control],
    ['network', { action: 'replay', entryId: 'entry-1', riskToken: 'a'.repeat(32) }, control],
    ['selection', { action: 'latest' }, { sessionGeneration: 0 }],
  ]) assert.throws(() => validateRequest(method, JSON.stringify(request(method, args, context))));
  assert.throws(() => validateRequest('observe', JSON.stringify(request('observe', {}, { sessionGeneration: 0 }))));
  assert.throws(() => validateRequest('click', JSON.stringify(request('click', { nodeId: 'n1' }, { ...control, sessionGeneration: 0 }))));
});

test('network status and WebSocket export expose bounded, truthful capabilities and artifact references', () => {
  const cap = (value) => ({
    version: 1, requestId: 'network-capability-test', code: 'OK', executed: false,
    sessionGeneration: 2, controlGeneration: 3, state: 'RUNNING', owner: 'ai',
    data: { capabilities: Object.fromEntries([
      'Target.setAutoAttach', 'Network.enable', 'Network.getResponseBody', 'Network.getRequestPostData',
      'Network.streamResourceContent', 'Fetch.enable', 'Runtime.evaluate', 'Network.webSocketEvents', 'page.fetchReplay',
    ].map((key) => [key, 'supported'])), coverage: { mainPage: 'connected', workerTargets: 'attached_network_enabled',
      serviceWorkerTargets: 'unsupported_shared_target_unverified', otherTargets: 'excluded' }, ...value },
  });
  const encoded = (value) => `${JSON.stringify(value)}\n`;
  const status = cap({});
  assert.ok(normalizeResponse('debug', status.requestId, structuredClone(status), 'status', { action: 'status' }));
  for (const mutate of [
    (value) => { value.data.targetId = 'raw-target'; },
    (value) => { value.data.capabilities['Network.enable'] = 'maybe'; },
    (value) => { value.data.coverage.workerTargets = 'all'; },
  ]) {
    const bad = structuredClone(status); mutate(bad);
    assert.equal(normalizeResponse('debug', bad.requestId, bad, 'status', { action: 'status' }), null);
  }
  const exported = { ...status, requestId: 'network-export-frames', executed: true,
    data: { artifactPath: `/opt/browser/out/network/net-${'a'.repeat(24)}.ws.json`, mimeType: 'application/json',
      bytes: 128, captureId: `cap-${'b'.repeat(20)}`, frameCount: 2, payloadsIncluded: true,
      payloadUnredacted: true, headersRedacted: true, redacted: false, truncated: false } };
  assert.ok(normalizeResponse('network', exported.requestId, structuredClone(exported), 'export_frames', { action: 'export_frames' }));
  for (const mutate of [
    (value) => { value.data.artifactPath = '/opt/browser/out/network/arbitrary.json'; },
    (value) => { value.data.redacted = true; },
    (value) => { value.data.payloadsIncluded = false; },
    (value) => { value.data.artifactPath = `/opt/browser/out/network/net-${'A'.repeat(24)}.ws.json`; },
  ]) {
    const bad = structuredClone(exported); mutate(bad);
    assert.equal(normalizeResponse('network', bad.requestId, bad, 'export_frames', { action: 'export_frames' }), null);
  }
});

test('network frames responses are bounded, strictly shaped, and exclude CDP identifiers', () => {
  const base = {
    version: 1, requestId: 'ws-frame-read', code: 'OK', executed: false,
    sessionGeneration: 2, controlGeneration: 3, state: 'RUNNING', owner: 'ai',
    data: {
      captureId: 'cap-0123456789abcdef0123',
      frames: [
        { sequence: 1, webSocketId: 'ws-0123456789abcdef0123', kind: 'created', url: 'wss://example.test/chat' },
        { sequence: 2, webSocketId: 'ws-0123456789abcdef0123', kind: 'sent', timestamp: 5, opcode: 1,
          direction: 'outgoing', masked: false, payloadEncoding: 'text', payloadBytes: 5, payload: 'hello' },
      ],
      cursor: 0, nextCursor: null, total: 2, droppedFrames: 0, eventDrops: 0,
      recording: false, includeBodies: true, payloadUnredacted: true, headersRedacted: true, redacted: false,
      payloadBytes: 5, maxFramePayloadBytes: 8192,
      maxCapturePayloadBytes: 1024 * 1024, maxFrames: 1000, maxPageBytes: 14 * 1024,
      loss: false, truncated: false,
    },
  };
  assert.equal(normalizeResponse('network', 'ws-frame-read', structuredClone(base), 'frames', { action: 'frames' }).code, 'OK');
  const incomplete = structuredClone(base);
  incomplete.data.frames = [
    { sequence: 1, webSocketId: 'ws-0123456789abcdef0123', kind: 'created', metadataIncomplete: true, urlTruncated: true },
    { sequence: 2, webSocketId: 'ws-0123456789abcdef0123', kind: 'sent', metadataIncomplete: true, payloadUnavailable: true },
  ];
  incomplete.data.loss = true;
  incomplete.data.truncated = true;
  assert.ok(normalizeResponse('network', incomplete.requestId, structuredClone(incomplete), 'frames', { action: 'frames' }));
  incomplete.data.frames[1].opcode = 99;
  assert.equal(normalizeResponse('network', incomplete.requestId, incomplete, 'frames', { action: 'frames' }), null);
  for (const mutate of [
    (value) => { value.executed = true; },
    (value) => { value.data.extra = 'forbidden'; },
    (value) => { value.data.frames[1].requestId = 'raw-cdp-id'; },
    (value) => { value.data.frames[1].payload = 'x'.repeat(8193); },
    (value) => { value.data.frames[0].url = 'wss://example.test/chat?token=secret'; },
    (value) => { value.data.frames[1].kind = 'rawCDP'; },
    (value) => { value.data.loss = true; },
    (value) => { value.data.frames[1].payloadEncoding = 'json'; },
    (value) => { value.data.redacted = true; },
    (value) => { value.data.payloadUnredacted = false; },
  ]) {
    const malformed = structuredClone(base);
    mutate(malformed);
    assert.equal(normalizeResponse('network', malformed.requestId, malformed, 'frames', { action: 'frames' }), null);
  }
});

test('debug emulate accepts exclusive clear and preserves bounded dimension validation', () => {
  const control = { owner: 'ai', sessionGeneration: 2, controlGeneration: 3 };
  assert.equal(validateRequest('debug', JSON.stringify(request('debug', { action: 'emulate', clear: true }, control))).method, 'debug');
  assert.equal(validateRequest('debug', JSON.stringify(request('debug', {
    action: 'emulate', width: 240, height: 2560, mobile: false,
  }, control))).method, 'debug');
  assert.equal(validateRequest('debug', JSON.stringify(request('debug', {
    action: 'emulate', width: 800, height: 600, mobile: true, clear: false,
  }, control))).method, 'debug');

  for (const args of [
    { action: 'emulate', clear: true, width: 800 },
    { action: 'emulate', clear: true, height: 600 },
    { action: 'emulate', clear: true, mobile: true },
    { action: 'emulate', clear: true, unknown: false },
    { action: 'emulate', clear: 1 },
    { action: 'emulate', width: 239, height: 600, mobile: true },
    { action: 'emulate', width: 800, height: 2561, mobile: true },
    { action: 'emulate', width: 800.5, height: 600, mobile: true },
    { action: 'emulate', width: 800, height: 600, mobile: true, unknown: false },
    { action: 'emulate', clear: false },
  ]) assert.throws(() => validateRequest('debug', JSON.stringify(request('debug', args, control))));
});

test('page list is session-scoped and mutations require AI owner plus current generations', () => {
  const pageId = 'pg-0123456789abcdefabcd'
  const list = request('page', { action: 'list' }, { sessionGeneration: 4 })
  assert.equal(validateRequest('page', JSON.stringify(list)).method, 'page')
  for (const action of ['new', 'switch', 'close']) {
    const args = action === 'new' ? { action } : { action, pageId }
    const mutation = request('page', args, { owner: 'ai', sessionGeneration: 4, controlGeneration: 9 })
    assert.equal(validateRequest('page', JSON.stringify(mutation)).method, 'page')
  }
  assert.throws(() => validateRequest('page', JSON.stringify(request('page', { action: 'list' }, { owner: 'ai', sessionGeneration: 4 }))))
  assert.throws(() => validateRequest('page', JSON.stringify(request('page', { action: 'new' }, { sessionGeneration: 4, controlGeneration: 9 }))))
  assert.throws(() => validateRequest('page', JSON.stringify(request('page', { action: 'close', pageId: '../bad' }, { owner: 'ai', sessionGeneration: 4, controlGeneration: 9 }))))
  assert.throws(() => validateRequest('page', JSON.stringify(request('page', { action: 'switch', pageId }, { owner: 'ai', sessionGeneration: 4, controlGeneration: 0 }))))
});

test('file listing/status are session-scoped reads while start/cancel require AI control generations', () => {
  const read = { sessionGeneration: 4 };
  const control = { owner: 'ai', sessionGeneration: 4, controlGeneration: 9 };
  const id = '0123456789abcdef0123456789abcdef';
  assert.equal(validateRequest('file', JSON.stringify(request('file', { action: 'list', limit: 20, beforeId: id }, read))).method, 'file');
  assert.equal(validateRequest('file', JSON.stringify(request('file', { action: 'status', id }, read))).method, 'file');
  assert.equal(validateRequest('file', JSON.stringify(request('file', {
    action: 'start', url: 'https://example.test/file.zip', suggestedFileName: 'archive.zip',
  }, control))).method, 'file');
  assert.equal(validateRequest('file', JSON.stringify(request('file', { action: 'cancel', id }, control))).method, 'file');

  for (const invalid of [
    request('file', { action: 'list', limit: 0 }, read),
    request('file', { action: 'list', beforeId: '../bad' }, read),
    request('file', { action: 'status' }, read),
    request('file', { action: 'cancel', id }, read),
    request('file', { action: 'start', url: 'file:///private/item' }, control),
    request('file', { action: 'start', url: 'https://user:pass@example.test/file' }, control),
    request('file', { action: 'start', url: 'https://example.test/file', headers: { Authorization: 'secret' } }, control),
    request('file', { action: 'start', url: 'https://example.test/file', suggestedFileName: '../secret' }, control),
    request('file', { action: 'start', url: 'https://example.test/file' }, { ...control, owner: undefined }),
    request('file', { action: 'cancel', id }, { ...control, controlGeneration: 0 }),
  ]) assert.throws(() => validateRequest('file', JSON.stringify(invalid)));
  assert.equal(validateRequest('file', JSON.stringify(request('file', {
    action: 'start', url: 'https://example.test/file', suggestedFileName: '报告.zip',
  }, control))).method, 'file');
});

test('file responses enforce item schema, matching ids, and the fixed download artifact namespace', () => {
  const base = { version: 1, requestId: 'f1', code: 'OK', executed: false, sessionGeneration: 4, controlGeneration: null, state: 'RUNNING', owner: 'human' };
  const id = '0123456789abcdef0123456789abcdef';
  const item = {
    id, state: 'COMPLETED', fileName: 'report.zip', bytesReceived: 12, totalBytes: 12,
    mimeType: 'application/zip', artifactPath: `/opt/browser/out/downloads/${id}_report.zip`,
    errorCode: null, updatedAtMillis: 123456,
  };
  assert.equal(normalizeResponse('file', 'f1', { ...base, data: { item } }, 'status', { id }).code, 'OK');
  assert.equal(normalizeResponse('file', 'f1', { ...base, data: { item } }, 'status', { id: 'f'.repeat(32) }), null);
  assert.equal(normalizeResponse('file', 'f1', { ...base, data: { item: { ...item, artifactPath: '/tmp/secret.zip' } } }, 'status', { id }), null);
  assert.equal(normalizeResponse('file', 'f1', { ...base, data: { item: { ...item, artifactPath: `/opt/browser/out/downloads/${'f'.repeat(32)}_report.zip` } } }, 'status', { id }), null);
  assert.equal(normalizeResponse('file', 'f1', { ...base, data: { item: { ...item, fileName: `${id}_report.zip` } } }, 'status', { id }), null);
  assert.equal(normalizeResponse('file', 'f1', { ...base, data: { item: { ...item, artifactPath: null } } }, 'status', { id }), null);
  assert.equal(normalizeResponse('file', 'f1', { ...base, data: { item: { ...item, bytesReceived: 25 * 1024 * 1024 + 1 } } }, 'status', { id }), null);
  const list = { ...base, data: { items: [item], nextCursor: null, truncated: false } };
  assert.equal(normalizeResponse('file', 'f1', list, 'list').code, 'OK');
  assert.equal(normalizeResponse('file', 'f1', { ...list, data: { ...list.data, extra: true } }, 'list'), null);
  assert.equal(normalizeResponse('file', 'f1', { ...list, data: { ...list.data, items: Array(21).fill(item) } }, 'list'), null);
  const accepted = { ...base, executed: true, data: { item: { ...item, state: 'QUEUED', artifactPath: null, bytesReceived: 0, totalBytes: null, mimeType: null } } };
  assert.equal(normalizeResponse('file', 'f1', accepted, 'start', {}).code, 'OK');
  assert.equal(normalizeResponse('file', 'f1', { ...accepted, executed: false }, 'start', {}), null);
  for (const prefix of ['_', '-']) {
    const longName = `${prefix}${'a'.repeat(179)}`;
    const longItem = { ...item, fileName: longName, artifactPath: `/opt/browser/out/downloads/${id}_${longName}` };
    assert.equal(normalizeResponse('file', 'f1', { ...base, data: { item: longItem } }, 'status', { id }).code, 'OK');
  }
  const tooLongName = `${'a'.repeat(181)}`;
  assert.equal(normalizeResponse('file', 'f1', {
    ...base, data: { item: { ...item, fileName: tooLongName, artifactPath: `/opt/browser/out/downloads/${id}_${tooLongName}` } },
  }, 'status', { id }), null);
});

test('page responses reject stale active ids, query-bearing URLs, and oversized page lists', () => {
  const pageId = 'pg-0123456789abcdefabcd'
  const base = { version: 1, requestId: 'p1', code: 'OK', executed: false, sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai' }
  const data = { pages: [{ pageId, title: 'Example', url: 'https://example.test/', active: true }], activePageId: pageId, limit: 4 }
  assert.equal(normalizeResponse('page', 'p1', { ...base, data }).code, 'OK')
  assert.equal(normalizeResponse('page', 'p1', { ...base, data: { ...data, activePageId: 'pg-aaaaaaaaaaaaaaaaaaaa' } }), null)
  assert.equal(normalizeResponse('page', 'p1', { ...base, data: { ...data, pages: [{ ...data.pages[0], url: 'https://example.test/?token=secret' }] } }), null)
  assert.equal(normalizeResponse('page', 'p1', { ...base, data: { ...data, pages: Array(5).fill(data.pages[0]) } }), null)
});

test('advanced response artifacts are restricted to their action-specific Browser directories', () => {
  const base = { version: 1, requestId: 'r1', code: 'OK', executed: true, sessionGeneration: 2, controlGeneration: 3, state: 'RUNNING', owner: 'ai' };
  assert.equal(normalizeResponse('network', 'r1', { ...base, data: { artifactPath: '/opt/browser/out/network/body-1.bin', bytes: 10 } }, 'body').code, 'OK');
  assert.equal(normalizeResponse('network', 'r1', { ...base, data: { artifactPath: '/tmp/body-1.bin' } }, 'body'), null);
  assert.equal(normalizeResponse('selection', 'r1', { ...base, data: { artifactPath: '/opt/browser/out/selection/item.png' } }, 'latest').code, 'OK');
  assert.equal(normalizeResponse('selection', 'r1', { ...base, data: { artifactPath: '/opt/browser/out/shot/item.png' } }, 'latest'), null);
});

test('response validation bounds observe text and fences screenshot artifacts', () => {
  const result = normalizeResponse('observe', 'r1', {
    version: 1, requestId: 'r1', code: 'OK', executed: false, sessionGeneration: 4,
    controlGeneration: null, state: 'RUNNING', owner: 'ai', data: { text: '汉'.repeat(2000) },
  });
  assert.ok(Buffer.byteLength(result.data.text, 'utf8') <= 4096);
  assert.equal(result.data.truncated, true);
  assert.equal(truncateUtf8('ab汉', 4).text, 'ab');
  assert.equal(normalizeResponse('screenshot', 'r2', {
    version: 1, requestId: 'r2', code: 'OK', executed: true, sessionGeneration: 4,
    controlGeneration: 7, state: 'RUNNING', owner: 'ai',
    data: { artifactPath: '/opt/browser/out/shot/shot-1.png', width: 540, height: 960, format: 'png', bytes: 123, base64: 'hidden' },
  }).data.base64, undefined);
  const screenshot = normalizeResponse('screenshot', 'r2', {
    version: 1, requestId: 'r2', code: 'OK', executed: true, sessionGeneration: 4,
    controlGeneration: 7, state: 'RUNNING', owner: 'ai',
    data: { artifactPath: '/opt/browser/out/shot/shot-1.png', width: 540, height: 960, format: 'png', bytes: 123, screenshotId: 'a'.repeat(32) },
  });
  assert.equal(screenshot.data.screenshotId, 'a'.repeat(32));
  assert.equal(normalizeResponse('screenshot', 'r2', {
    version: 1, requestId: 'r2', code: 'OK', executed: true, sessionGeneration: 4,
    controlGeneration: 7, state: 'RUNNING', owner: 'ai',
    data: { artifactPath: '/opt/browser/out/shot/shot-1.png', width: 540, height: 960, format: 'png', bytes: 123, screenshotId: 'bad' },
  }), null);
  assert.equal(normalizeResponse('screenshot', 'r2', {
    version: 1, requestId: 'r2', code: 'OK', executed: true, sessionGeneration: 4,
    controlGeneration: 7, state: 'RUNNING', owner: 'ai', data: { artifactPath: '/opt/browser/out/shot/../../user-data/.dsh/x.png' },
  }), null);
});

test('locate and observe validate complete frame scope metadata while retaining metadata-free v1 fixtures', () => {
  const coverage = (overrides = {}) => ({
    complete: true, scannedElements: 4, frameCount: 1, shadowRootCount: 1, inaccessibleFrameCount: 0,
    frameLimitReached: false, shadowLimitReached: false, elementLimitReached: false,
    textNodesVisited: 3, textLimitReached: false, ...overrides,
  });
  const frameScopes = [{ ordinal: 1, parentOrdinal: 0, depth: 1, status: 'accessible', reason: null }];
  const locateBase = {
    version: 1, requestId: 'scope-locate', code: 'OK', executed: true, sessionGeneration: 4,
    controlGeneration: 9, state: 'RUNNING', owner: 'ai',
    data: { ref: 'b:4:9:3:7:0123456789abcdef0123456789abcdef:1', count: 1, strategy: 'role',
      pageId: 'pg-0123456789abcdefabcd', documentGeneration: 3, observationId: 7,
      frameOrdinal: 1, shadowDepth: 2, scopeCoverage: coverage(), frameScopes },
  };
  assert.equal(normalizeResponse('locate', 'scope-locate', structuredClone(locateBase), 'role').code, 'OK');
  const legacyLocate = structuredClone(locateBase);
  for (const key of ['frameOrdinal', 'shadowDepth', 'scopeCoverage', 'frameScopes']) delete legacyLocate.data[key];
  assert.equal(normalizeResponse('locate', 'scope-locate', legacyLocate, 'role').code, 'OK');
  const partial = structuredClone(locateBase);
  delete partial.data.scopeCoverage;
  assert.equal(normalizeResponse('locate', 'scope-locate', partial, 'role'), null);
  for (const mutate of [
    (value) => { value.data.scopeCoverage.complete = false; },
    (value) => { value.data.scopeCoverage.unrecognized = 1; },
    (value) => { value.data.frameScopes[0].depth = 2; },
    (value) => { value.data.frameScopes[0].extra = true; },
    (value) => { value.data.frameOrdinal = 2; },
  ]) {
    const malformed = structuredClone(locateBase);
    mutate(malformed);
    assert.equal(normalizeResponse('locate', 'scope-locate', malformed, 'role'), null);
  }
  const textLimitedSuccess = structuredClone(locateBase);
  textLimitedSuccess.data.scopeCoverage.textLimitReached = true;
  assert.equal(normalizeResponse('locate', 'scope-locate', textLimitedSuccess, 'role'), null);

  const incomplete = {
    ...locateBase, code: 'INCOMPLETE_SCOPE', executed: false,
    data: { count: 4, scopeCoverage: coverage({ complete: false, frameCount: 1, inaccessibleFrameCount: 1 }),
      frameScopes: [{ ordinal: 1, parentOrdinal: 0, depth: 1, status: 'inaccessible', reason: 'CROSS_ORIGIN_OR_OPAQUE' }] },
  };
  assert.equal(normalizeResponse('locate', 'scope-locate', incomplete, 'role').code, 'INCOMPLETE_SCOPE');
  const incompleteWithRef = structuredClone(incomplete);
  incompleteWithRef.data.ref = locateBase.data.ref;
  assert.equal(normalizeResponse('locate', 'scope-locate', incompleteWithRef, 'role'), null);
  const scopedNotFound = { ...locateBase, code: 'NOT_FOUND', executed: false,
    data: { count: 0, scopeCoverage: coverage(), frameScopes } };
  assert.equal(normalizeResponse('locate', 'scope-locate', scopedNotFound, 'role').code, 'NOT_FOUND');
  const falseNotFound = structuredClone(scopedNotFound);
  falseNotFound.data.scopeCoverage.complete = false;
  assert.equal(normalizeResponse('locate', 'scope-locate', falseNotFound, 'role'), null);
  const scopedAmbiguous = { ...locateBase, code: 'AMBIGUOUS', executed: false,
    data: { count: 2, scopeCoverage: coverage(), frameScopes, matches: [
      { tag: 'button', role: 'button', name: 'Go', frameOrdinal: 1, shadowDepth: 2 },
    ] } };
  assert.equal(normalizeResponse('locate', 'scope-locate', scopedAmbiguous, 'role').code, 'AMBIGUOUS');
  const scopedLimit = { ...locateBase, code: 'LOCATE_LIMIT', executed: false,
    data: { count: 5000, scopeCoverage: coverage(), frameScopes } };
  assert.equal(normalizeResponse('locate', 'scope-locate', scopedLimit, 'role').code, 'LOCATE_LIMIT');

  const observe = {
    version: 1, requestId: 'scope-observe', code: 'OK', executed: false, sessionGeneration: 4,
    controlGeneration: null, state: 'RUNNING', owner: 'ai',
    data: { title: 'Fixture', url: 'https://example.test/', readyState: 'complete', text: 'ready',
      textTruncated: true, truncated: true, elementsTruncated: false,
      urlQueryRedacted: false, urlFragmentRedacted: false, loadState: 'finished', observationGeneration: 7,
      elements: [{ nodeId: 'b:4:9:3:7:0123456789abcdef0123456789abcdef:0', hrefQueryRedacted: false, hrefFragmentRedacted: false, tag: 'button', type: '', role: 'button', label: 'Go', accessibleName: 'Go', placeholder: '', href: '', frameOrdinal: 1, shadowDepth: 2 }],
      scopeCoverage: coverage({ textLimitReached: true }), frameScopes,
      topFrameOnly: false, openShadowRootsSupported: true, closedShadowRootsSupported: false, crossOriginFramesSupported: false },
  };
  assert.equal(normalizeResponse('observe', 'scope-observe', structuredClone(observe)).code, 'OK');
  const oldElementFixture = structuredClone(observe);
  delete oldElementFixture.data.elements[0].accessibleName;
  delete oldElementFixture.data.elements[0].placeholder;
  assert.equal(normalizeResponse('observe', 'scope-observe', oldElementFixture).code, 'OK');
  const partialElement = structuredClone(observe);
  delete partialElement.data.elements[0].shadowDepth;
  assert.equal(normalizeResponse('observe', 'scope-observe', partialElement), null);
  const missingAccessibleField = structuredClone(observe);
  delete missingAccessibleField.data.elements[0].placeholder;
  assert.equal(normalizeResponse('observe', 'scope-observe', missingAccessibleField), null);
  const oversizedAccessibleName = structuredClone(observe);
  oversizedAccessibleName.data.elements[0].accessibleName = 'x'.repeat(161);
  assert.equal(normalizeResponse('observe', 'scope-observe', oversizedAccessibleName), null);
  const extraElementField = structuredClone(observe);
  extraElementField.data.elements[0].arbitrary = 'value';
  assert.equal(normalizeResponse('observe', 'scope-observe', extraElementField), null);
  const capabilityMismatch = structuredClone(observe);
  capabilityMismatch.data.crossOriginFramesSupported = true;
  assert.equal(normalizeResponse('observe', 'scope-observe', capabilityMismatch), null);
  const topFrameOnly = structuredClone(observe);
  topFrameOnly.data.topFrameOnly = true;
  assert.equal(normalizeResponse('observe', 'scope-observe', topFrameOnly), null);
  const legacyObserve = { ...observe, data: { text: 'old v1 fixture' } };
  assert.equal(normalizeResponse('observe', 'scope-observe', legacyObserve).data.text, 'old v1 fixture');
});

test('screenshot backend metadata and new failure diagnostics are strict and legacy-compatible', () => {
  const base = {
    version: 1, requestId: 'shot-contract', code: 'OK', executed: true,
    sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai',
  }
  const oldSuccess = { ...base, data: {
    artifactPath: '/opt/browser/out/shot/shot-1.png', width: 10, height: 20, format: 'png', bytes: 80,
  } }
  assert.ok(normalizeResponse('screenshot', base.requestId, oldSuccess))

  const fallbackSuccess = { ...base, data: {
    ...oldSuccess.data, backend: 'cdp', primaryBackend: 'virtual_display', primaryFailure: 'NO_FRAME',
  } }
  assert.equal(normalizeResponse('screenshot', base.requestId, fallbackSuccess).data.backend, 'cdp')
  assert.equal(normalizeResponse('screenshot', base.requestId, {
    ...fallbackSuccess, data: { ...fallbackSuccess.data, fallbackFailure: 'NO_FRAME' },
  }), null)
  assert.equal(normalizeResponse('screenshot', base.requestId, {
    ...fallbackSuccess, data: { ...fallbackSuccess.data, backend: 'remote' },
  }), null)

  const failure = { ...base, code: 'SCREENSHOT_DEBUG_REQUIRED', executed: false, data: {
    cancelStage: 'surface_attach', reason: 'SCREENSHOT_DEBUG_REQUIRED',
    primaryBackend: 'virtual_display', primaryFailure: 'NO_FRAME', debugEnabled: false, userActionRequired: true,
  } }
  assert.ok(normalizeResponse('screenshot', base.requestId, failure))
  assert.equal(normalizeResponse('screenshot', base.requestId, {
    ...failure, data: { ...failure.data, debugEnabled: 'false' },
  }), null)
})

test('screenshot failure diagnostics use only their code-specific bounded stage', () => {
  const stages = {
    TARGET_UNAVAILABLE: 'target_attach', AMBIGUOUS_TARGET: 'target_attach',
    CDP_UNSUPPORTED: 'pixel_read', CDP_TIMEOUT: 'pixel_read',
    BACKGROUND_UNAVAILABLE: 'surface_attach', WINDOW_NOT_ACTIVE: 'surface_attach', CLIPPED_VIEWPORT: 'surface_attach',
    VIEWPORT_UNAVAILABLE: 'surface_attach',
    NO_FRAME: 'pixel_read', PIXEL_READ_FAILED: 'pixel_read', DEADLINE_EXPIRED: 'pixel_read',
    IDENTITY_CHANGED: 'identity_match', PNG_INVALID: 'decode', PNG_TOO_LARGE: 'decode',
    ENCODE_FAILED: 'encode', STORAGE_FAILED: 'storage', SCREENSHOT_QUOTA_EXCEEDED: 'storage', JOURNAL_UNAVAILABLE: 'journal',
  };
  for (const [code, cancelStage] of Object.entries(stages)) {
    const value = { version: 1, requestId: 'screenshot-diagnostic', code, executed: false,
      sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai',
      data: { cancelStage, reason: code } };
    assert.equal(normalizeResponse('screenshot', value.requestId, value).code, code);
    assert.equal(normalizeResponse('screenshot', value.requestId, { ...value, data: { cancelStage, reason: 'other' } }), null);
    assert.equal(normalizeResponse('screenshot', value.requestId, { ...value, data: { cancelStage, reason: code, detail: 'unbounded' } }), null);
    assert.equal(normalizeResponse('screenshot', value.requestId, { ...value, transportHint: 'extra' }), null);
  }
  const badStage = { version: 1, requestId: 'screenshot-diagnostic', code: 'NO_FRAME', executed: false,
    sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai', data: { cancelStage: 'encode', reason: 'NO_FRAME' } };
  assert.equal(normalizeResponse('screenshot', badStage.requestId, badStage), null);
  const preDispatchDeadline = { version: 1, requestId: 'screenshot-deadline', code: 'DEADLINE_EXPIRED', executed: false,
    sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai', data: {} };
  assert.deepEqual(normalizeResponse('screenshot', preDispatchDeadline.requestId, preDispatchDeadline), preDispatchDeadline);
  assert.deepEqual(normalizeResponse('screenshot', preDispatchDeadline.requestId, {
    ...preDispatchDeadline, data: { cancelStage: 'pixel_read', reason: 'DEADLINE_EXPIRED' }
  }).data, { cancelStage: 'pixel_read', reason: 'DEADLINE_EXPIRED' });
  assert.equal(normalizeResponse('screenshot', preDispatchDeadline.requestId, {
    ...preDispatchDeadline, data: { cancelStage: 'journal', reason: 'DEADLINE_EXPIRED' }
  }), null);
});

test('production screenshot failure fixture with primary diagnostics is accepted', () => {
  const runtimeTest = path.resolve(__dirname,
    '../../../../browser-runtime/src/test/java/com/dshbox/browser/runtime/BrowserScreenshotCaptureCoordinatorTest.kt');
  const source = fs.readFileSync(runtimeTest, 'utf8');
  const match = source.match(/PRODUCTION_FAILURE_SAMPLE_JSON = """([\s\S]*?)"""/);
  assert.ok(match, 'runtime producer fixture is present');
  const response = JSON.parse(match[1]);
  assert.deepEqual(normalizeResponse('screenshot', response.requestId, response, '', {}), response);
  assert.equal(normalizeResponse('screenshot', response.requestId, {
    ...response, data: { ...response.data, unexpected: true },
  }, '', {}), null);
  for (const [code, cancelStage, primaryFailure] of [
    ['IDENTITY_CHANGED', 'identity_match', 'OK'],
    ['DEADLINE_EXPIRED', 'pixel_read', 'DEADLINE_EXPIRED'],
    ['CAPTURE_EMPTY_RESULT', 'pixel_read', 'CAPTURE_EMPTY_RESULT'],
  ]) {
    const value = { ...response, code, data: { cancelStage, reason: code,
      primaryBackend: 'virtual_display', primaryFailure, debugEnabled: true, userActionRequired: false } };
    assert.ok(normalizeResponse('screenshot', value.requestId, value, '', {}), code);
  }
  const fallback = { ...response, data: { ...response.data, debugEnabled: true,
    fallbackBackend: 'cdp', fallbackFailure: 'PIXEL_READ_FAILED' } };
  assert.ok(normalizeResponse('screenshot', fallback.requestId, fallback, '', {}));
  assert.equal(normalizeResponse('screenshot', fallback.requestId, { ...fallback,
    data: { ...fallback.data, fallbackFailure: undefined } }, '', {}), null);
  const busy = { ...response, code: 'CAPTURE_BUSY', data: { cancelStage: 'surface_attach', reason: 'CAPTURE_BUSY' } };
  assert.ok(normalizeResponse('screenshot', busy.requestId, busy, '', {}));
});

test('type obstruction diagnostics retain only bounded obscurer metadata', () => {
  const value = { version: 1, requestId: 'type-obscured', code: 'OBSCURED', executed: false,
    sessionGeneration: 4, controlGeneration: 9, state: 'RUNNING', owner: 'ai',
    data: { obscuredBy: { tag: 'div', id: 'shade', className: 'overlay' } } };
  assert.deepEqual(normalizeResponse('type', value.requestId, value).data, value.data);
  assert.equal(normalizeResponse('type', value.requestId, { ...value, actionHint: 'extra' }), null);
  assert.equal(normalizeResponse('type', value.requestId, { ...value,
    data: { obscuredBy: { ...value.data.obscuredBy, nodeId: 'made-up' } } }), null);
});

test('adapter envelope requires version 1 and a public Browser state', () => {
  const validSyntheticError = {
    version: 1, requestId: 'err1', code: 'AUTOMATION_DISABLED', executed: false,
    sessionGeneration: null, controlGeneration: null, state: 'CLOSED', owner: 'none', data: {},
  };
  assert.equal(normalizeResponse('open', 'err1', validSyntheticError).code, 'AUTOMATION_DISABLED');
  assert.equal(normalizeResponse('open', 'err1', { ...validSyntheticError, version: undefined }), null);
  assert.equal(normalizeResponse('open', 'err1', { ...validSyntheticError, state: 'REJECTED' }), null);
  assert.equal(normalizeResponse('open', 'err1', { ...validSyntheticError, state: 'UNKNOWN' }), null);
  assert.equal(normalizeResponse('open', 'err1', { ...validSyntheticError, owner: 'root' }), null);
});

test('rejects mismatched command, extra wire fields, and overlong frames', () => {
  assert.throws(() => parseArguments(['exec', '--request-json', '{}']));
  assert.throws(() => validateRequest('open', JSON.stringify({ ...request('open', {}, { owner: 'ai' }), script: 'no' })));
  assert.throws(() => validateRequest('observe', ' '.repeat(16 * 1024 + 1)));
});

test('reads only a regular bounded rendezvous file', () => {
  const root = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'browser-cli-test-'));
  try {
    const file = path.join(root, 'channel.json');
    fs.writeFileSync(file, JSON.stringify({ version: 1, socketName: 'dshbox.browser.test', token: 'a'.repeat(64) }));
    assert.equal(readChannelConfig(file).socketName, 'dshbox.browser.test');
    fs.writeFileSync(file, JSON.stringify({ version: 1, socketName: 'x/../bad', token: 'a'.repeat(64) }));
    assert.throws(() => readChannelConfig(file));
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('Node abstract UDS carries one request and returns its matching response', { skip: process.platform === 'win32' }, async () => {
  const socketName = `browser-cli-test-${process.pid}-${Date.now()}`;
  const server = net.createServer((peer) => {
    let data = '';
    peer.setEncoding('utf8');
    peer.on('data', (part) => {
      data += part;
      if (!data.includes('\n')) return;
      const frame = JSON.parse(data.slice(0, data.indexOf('\n')));
      assert.equal(frame.op, 'request');
      assert.equal(frame.request.method, 'status');
      assert.equal(frame.token, 'b'.repeat(64));
      peer.end(`${JSON.stringify({ version: 1, requestId: frame.request.requestId, code: 'OK', executed: false, sessionGeneration: 0, controlGeneration: 0, state: 'CLOSED', owner: 'none', data: {} })}\n`);
    });
  });
  await new Promise((resolve, reject) => server.listen(`\0${socketName}`, (error) => error ? reject(error) : resolve()));
  try {
    const result = await execute('status', request('status'), { socketName, token: 'b'.repeat(64) });
    assert.equal(result.code, 'OK');
    assert.equal(result.state, 'CLOSED');
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test('refused connection is nonexecuted and distinct from uncertain delivery', { skip: process.platform === 'win32' }, async () => {
  const result = await execute('open', request('open', {}, { owner: 'ai' }), {
    socketName: `browser-cli-absent-${process.pid}-${Date.now()}`, token: 'b'.repeat(64),
  });
  assert.equal(result.code, 'CHANNEL_UNAVAILABLE');
  assert.equal(result.executed, false);
  assert.equal(result.data.transportError, 'ECONNREFUSED');
});

test('disconnect after receiving an action remains unknown and never resends action', { skip: process.platform === 'win32' }, async () => {
  const socketName = `browser-cli-lost-${process.pid}-${Date.now()}`;
  let actions = 0;
  const server = net.createServer(peer => {
    let bytes = '';
    peer.on('data', part => {
      bytes += part;
      if (!bytes.includes('\n')) return;
      const frame = JSON.parse(bytes.slice(0, bytes.indexOf('\n')));
      if (frame.op === 'request') actions++;
      peer.destroy();
    });
  });
  await new Promise(resolve => server.listen(`\0${socketName}`, resolve));
  try {
    const result = await execute('open', request('open', {}, { owner: 'ai' }), {socketName, token: 'b'.repeat(64)});
    assert.equal(result.code, 'UNKNOWN');
    assert.equal(result.executed, null);
    assert.equal(actions, 1);
  } finally {await new Promise(resolve => server.close(resolve));}
});

test('verified same-document back uses the canonical navigation receipt', () => {
  const receipt = {version: 1, requestId: 'spa-back', code: 'OK', executed: true,
    sessionGeneration: 2, controlGeneration: 3, state: 'RUNNING', owner: 'ai',
    data: {action: 'back', accepted: true, pageId: 'pg-'+ 'a'.repeat(20), navigationId: 4,
      url: 'http://127.0.0.1:8899/'}};
  assert.equal(normalizeResponse('navigate', 'spa-back', receipt, 'back').code, 'OK');
  assert.equal(normalizeResponse('navigate', 'spa-back', {...receipt,
    data: {action: 'back', accepted: true, sameDocumentFallback: true, url: receipt.data.url}}, 'back'), null);
});

test('connected peer cannot forge a preconnect failure using JSON fields', { skip: process.platform === 'win32' }, async () => {
  const socketName = `browser-cli-forged-${process.pid}-${Date.now()}`;
  const server = net.createServer(peer => {
    peer.once('data', () => peer.end(JSON.stringify({connected: false, transportFailure: 'ECONNREFUSED'})+'\n'));
  });
  await new Promise(resolve => server.listen(`\0${socketName}`, resolve));
  try {
    const result = await execute('open', request('open', {}, {owner: 'ai'}), {socketName, token: 'b'.repeat(64)});
    assert.equal(result.code, 'UNKNOWN');
    assert.equal(result.executed, null);
  } finally {await new Promise(resolve => server.close(resolve));}
});

test('serve CLI schema is fixed and rejects traversal, sensitive roots, and unbounded leases', () => {
  assert.deepEqual(parseServeArguments(['start', '--dir', 'site', '--port', '0', '--lease-ms', '60000']), {
    action: 'start', dir: 'site', port: 0, leaseMs: 60_000,
  });
  assert.equal(parseServeArguments(['logs', 'a'.repeat(32), '--limit', '10']).limit, 10);
  assert.equal(parseServeArguments(['register', '--dir', 'site', '--url', 'http://127.0.0.1:4173/', '--health-path', '/health']).healthPath, '/health');
  assert.throws(() => parseServeArguments(['register', '--dir', 'site', '--url', 'http://0.0.0.0:4173/']));
  assert.throws(() => parseServeArguments(['register', '--dir', 'site', '--url', 'http://127.0.0.1:4173/', '--health-path', '/%2e%2e%2fprivate']));
  assert.throws(() => parseServeArguments(['start', '--dir', 'site', '--lease-ms', '59999']));
  assert.throws(() => parseServeArguments(['start', '--dir', 'site', '--lease-ms', '28800001']));
  assert.throws(() => parseServeArguments(['start', '--dir', 'site', '--unknown', 'x']));
  assert.throws(() => parseServeArguments(['stop', '..']));
  assert.equal(safeRelative('%2e%2e/.env'), null);
  assert.equal(safeRelative('assets/ok.js'), path.join('assets', 'ok.js'));
});

function spawnServeChild(configFile, runDir) {
  return new Promise((resolve, reject) => {
    const modulePath = path.resolve(__dirname, '../../main/assets/browser/lib/browser-serve.cjs');
    const childCode = 'require(process.argv[1]).runServeChild(process.argv[2], process.argv[3]).catch(error => { process.stdout.write(JSON.stringify({ready:false,code:"CHILD_ERROR",message:String(error.stack||error)+JSON.stringify(process.argv)})+"\\n"); process.exit(2) })';
    const child = spawn(process.execPath, ['-e', childCode, modulePath, configFile, runDir], { detached: true, windowsHide: true, stdio: ['ignore', 'pipe', 'ignore'] });
    child.unref();
    let output = '';
    const timer = setTimeout(() => { child.kill(); reject(new Error('serve child did not become ready')); }, 5000);
    child.stdout.on('data', (chunk) => {
      output += chunk.toString('utf8');
      const lineEnd = output.indexOf('\n');
      if (lineEnd < 0) return;
      clearTimeout(timer);
      let result;
      try { result = JSON.parse(output.slice(0, lineEnd)); } catch (error) { child.kill(); reject(error); return; }
      child.stdout.destroy();
      if (!result.ready) { const error = new Error(result.message || 'serve child failed'); error.code = result.code; reject(error); }
      else resolve({ ...result, child });
    });
    child.once('error', (error) => { clearTimeout(timer); reject(error); });
  });
}

function httpRead(port, requestPath, accept = '*/*', extraHeaders = {}) {
  return new Promise((resolve, reject) => {
    const request = require('node:http').get({ host: '127.0.0.1', port, path: requestPath, headers: { accept, ...extraHeaders } }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => resolve({ status: response.statusCode, type: response.headers['content-type'], body: Buffer.concat(chunks).toString('utf8') }));
    });
    request.on('error', reject);
  });
}

function httpOpenPaused(port, requestPath) {
  return new Promise((resolve, reject) => {
    const request = require('node:http').get({ host: '127.0.0.1', port, path: requestPath }, (response) => {
      resolve({ request, response });
    });
    request.on('error', reject);
  });
}

function httpHead(port, requestPath) {
  return new Promise((resolve, reject) => {
    const request = require('node:http').request({ host: '127.0.0.1', port, path: requestPath, method: 'HEAD' }, (response) => {
      response.resume();
      response.on('end', () => resolve({ status: response.statusCode, length: response.headers['content-length'] }));
    });
    request.on('error', reject);
    request.end();
  });
}

function httpPostJson(port, requestPath, value, extraHeaders = {}) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify(value);
    const request = require('node:http').request({
      host: '127.0.0.1', port, path: requestPath, method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body), ...extraHeaders },
    }, (response) => {
      response.resume();
      response.on('end', () => resolve(response.statusCode));
    });
    request.on('error', reject);
    request.end(body);
  });
}

test('real Node static service serves bounded MIME, fences sensitive paths, reports readiness, and stops only its authenticated instance', async () => {
  const temporary = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'browser-serve-test-'));
  const project = path.join(temporary, 'project');
  const runDir = path.join(temporary, 'run', 'serve');
  fs.mkdirSync(path.join(project, 'assets'), { recursive: true });
  fs.writeFileSync(path.join(project, 'index.html'), '<!doctype html><html><body>preview</body></html>');
    fs.writeFileSync(path.join(project, 'assets', 'app.js'), 'window.preview = true;');
    fs.writeFileSync(path.join(project, 'assets', 'large.bin'), Buffer.alloc(4 * 1024 * 1024, 7));
  fs.writeFileSync(path.join(project, '.env'), 'SECRET_SHOULD_NOT_SERVE');
  fs.mkdirSync(path.join(project, '.dsh'));
  fs.writeFileSync(path.join(project, '.dsh', 'private.txt'), 'PRIVATE');
  let childPid = null;
  const spawnChild = async (configFile) => {
    const ready = await spawnServeChild(configFile, runDir);
    childPid = ready.child.pid;
    return ready;
  };
  try {
    const started = await runServeCommand(parseServeArguments(['start', '--dir', project, '--port', '0', '--lease-ms', '60000', '--spa-fallback', 'index.html']), { runDir, projectsRoot: temporary, spawnChild });
    assert.equal(started.code, 'OK');
    assert.equal(started.executed, true);
    assert.equal(started.data.ready, true);
    const ownedRecord = JSON.parse(fs.readFileSync(path.join(runDir, `${started.data.serviceId}.json`), 'utf8'));
    assert.equal(ownedRecord.kind, 'owned-static');
    if (process.platform === 'linux') {
      assert.equal(ownedRecord.pid, childPid);
      assert.match(ownedRecord.processStartTime, /^\d+$/);
      assert.equal(typeof ownedRecord.bootId, 'string');
      assert.equal(ownedRecord.leaseExpiresAtUptimeMillis > 0, true);
    } else {
      assert.equal(ownedRecord.pid, null);
    }
    assert.match(started.data.url, /^http:\/\/127\.0\.0\.1:\d+\/$/);
    assert.equal(started.data.leaseExpiresAtMillis > Date.now(), true);
    const port = started.data.port;
    const page = await httpRead(port, '/');
    assert.equal(page.status, 200);
    assert.match(page.type, /^text\/html/);
    assert.match(page.body, /preview/);
    assert.equal((await httpRead(port, '/', '*/*', { Host: `attacker.test:${port}` })).status, 421);
    const script = await httpRead(port, '/assets/app.js');
    assert.equal(script.status, 200);
    assert.match(script.type, /^text\/javascript/);
    assert.deepEqual(await httpHead(port, '/assets/app.js'), { status: 200, length: String(Buffer.byteLength('window.preview = true;')) });
    const spaRoute = await httpRead(port, '/app/settings', 'text/html');
    assert.equal(spaRoute.status, 200);
    assert.match(spaRoute.type, /^text\/html/);
    assert.match(spaRoute.body, /preview/);
    const missingAsset = await httpRead(port, '/assets/missing.js', 'text/html');
    assert.equal(missingAsset.status, 404);
    assert.doesNotMatch(missingAsset.body, /preview/);
    assert.equal((await httpRead(port, '/.env')).status, 403);
    assert.equal((await httpRead(port, '/.dsh/private.txt')).status, 403);
    assert.equal((await httpRead(port, '/.npmrc')).status, 403);
    assert.equal((await httpRead(port, '/app-token.txt')).status, 403);
    assert.equal(await httpPostJson(port, '/__browser_serve_control', null), 400);
    assert.equal((await httpRead(port, '/')).status, 200);
    assert.equal(await httpPostJson(port, '/__browser_serve_control', { version: 1, serviceId: started.data.serviceId, token: 'f'.repeat(64), op: 'status' }), 404);
    assert.equal(await httpPostJson(port, '/__browser_serve_control', { version: 1, serviceId: started.data.serviceId, token: 'f'.repeat(64), op: 'status' }, { Origin: 'https://attacker.test' }), 404);
    const external = path.join(temporary, 'private.txt');
    fs.writeFileSync(external, 'OUTSIDE');
    try {
      fs.symlinkSync(external, path.join(project, 'assets', 'linked.txt'));
      assert.equal((await httpRead(port, '/assets/linked.txt')).status, 403);
    } catch (error) {
      if (!['EPERM', 'EACCES', 'UNKNOWN'].includes(error.code)) throw error;
    }
    const held = await Promise.all(Array.from({ length: MAX_ACTIVE_STREAMS }, () => httpOpenPaused(port, '/assets/large.bin')));
    try {
      assert.ok(held.every(({ response }) => response.statusCode === 200));
      assert.equal((await httpRead(port, '/assets/app.js')).status, 503);
    } finally {
      for (const { request, response } of held) { response.destroy(); request.destroy(); }
    }
    const status = await runServeCommand(parseServeArguments(['status', started.data.serviceId]), { runDir });
    assert.equal(status.code, 'OK');
    assert.equal(status.data.serviceId, started.data.serviceId);
    const list = await runServeCommand(parseServeArguments(['list']), { runDir });
    assert.equal(list.data.services.length, 1);
    const logs = await runServeCommand(parseServeArguments(['logs', started.data.serviceId, '--limit', '5']), { runDir });
    assert.equal(logs.code, 'OK');
    assert.ok(logs.data.lines.length <= 5);
    assert.ok(logs.data.lines.every((line) => !line.includes(started.data.url) && !line.includes('SECRET')));
    const stopped = await runServeCommand(parseServeArguments(['stop', started.data.serviceId]), { runDir });
    if (process.platform === 'linux') {
      assert.equal(stopped.code, 'OK');
      assert.equal(stopped.executed, true);
      assert.equal(stopped.state, 'CLOSED');
      assert.equal((await runServeCommand(parseServeArguments(['status', started.data.serviceId]), { runDir })).code, 'NOT_RUNNING');
    } else {
      assert.equal(stopped.code, 'STOP_UNCONFIRMED');
      assert.equal(stopped.state, 'UNKNOWN');
      assert.equal(fs.existsSync(path.join(runDir, `${started.data.serviceId}.json`)), true);
    }
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});

test('HTTP open failures return an error before headers and leave the service responsive', async () => {
  const temporary = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'browser-serve-open-failure-'));
  const project = path.join(temporary, 'project');
  fs.mkdirSync(project);
  fs.writeFileSync(path.join(project, 'index.html'), '<p>still alive</p>');
  fs.writeFileSync(path.join(project, 'unreadable.txt'), 'restricted');
  const config = {
    version: 1,
    serviceId: 'a'.repeat(32),
    token: 'b'.repeat(64),
    root: project,
    port: 0,
    leaseExpiresAtMillis: Date.now() + 60_000,
    spaFallback: null,
    logFile: path.join(temporary, 'service.log'),
  };
  const service = await makeServer(config, {
    openFile: async (root, found) => {
      if (path.basename(found.path) === 'unreadable.txt') throw Object.assign(new Error('denied'), { code: 'EACCES' });
      return fs.promises.open(found.path, 'r');
    },
    exitProcess: () => {},
  });
  await new Promise((resolve, reject) => service.server.listen(0, '127.0.0.1', (error) => error ? reject(error) : resolve()));
  const port = service.server.address().port;
  try {
    assert.equal((await httpRead(port, '/unreadable.txt')).status, 403);
    assert.deepEqual(await httpHead(port, '/unreadable.txt'), { status: 403, length: undefined });
    assert.equal((await httpRead(port, '/')).status, 200);
    assert.equal(await httpPostJson(port, '/__browser_serve_control', {
      version: 1, serviceId: config.serviceId, token: config.token, op: 'status',
    }), 200);
  } finally {
    await new Promise((resolve) => service.server.close(resolve));
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});

test('start lock refuses concurrent ownership and an old releaser cannot remove a replacement lock', async () => {
  const temporary = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'browser-serve-lock-'));
  const runDir = path.join(temporary, 'run');
  fs.mkdirSync(runDir);
  const lockPath = path.join(runDir, 'start.lock');
  try {
    const oldLock = await acquireStartLock(runDir);
    await assert.rejects(acquireStartLock(runDir), { code: 'START_BUSY' });
    fs.unlinkSync(lockPath);
    const newLock = await acquireStartLock(runDir);
    await oldLock.release();
    await assert.rejects(oldLock.assertOwned(), { code: 'START_LOCK_LOST' });
    assert.equal(JSON.parse(fs.readFileSync(lockPath, 'utf8')).nonce, newLock.nonce);
    await newLock.assertOwned();
    await newLock.release();
    assert.equal(fs.existsSync(lockPath), false);
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});

test('lock recovery requires confirmed dead or mismatched owner identity and fails closed when unknown', async () => {
  const temporary = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'browser-serve-lock-probe-'));
  const runDir = path.join(temporary, 'run');
  fs.mkdirSync(runDir);
  const lockPath = path.join(runDir, 'start.lock');
  const originalOwner = { pid: 9137, processStartTime: '111', bootId: 'boot-id-original' };
  const seed = () => fs.writeFileSync(lockPath, JSON.stringify({
    version: 1, nonce: 'd'.repeat(48), acquiredAtMillis: Date.now() - MAX_START_LOCK_MS - 5_000, owner: originalOwner,
  }), { mode: 0o600 });
  try {
    for (const [label, probe, stale] of [
      ['live owner past age limit', async () => ({ state: 'live', ...originalOwner }), false],
      ['unverifiable owner', async () => ({ state: 'unknown' }), false],
      ['dead owner', async () => ({ state: 'dead', bootId: originalOwner.bootId }), true],
      ['reused pid', async () => ({ state: 'live', pid: originalOwner.pid, processStartTime: '222', bootId: originalOwner.bootId }), true],
      ['changed boot', async () => ({ state: 'live', pid: originalOwner.pid, processStartTime: originalOwner.processStartTime, bootId: 'boot-id-new' }), true],
    ]) {
      seed();
      const options = {
        ownerProbe: probe,
        ownerIdentity: { state: 'live', pid: 8123, processStartTime: '333', bootId: 'boot-id-current' },
      };
      if (!stale) {
        await assert.rejects(acquireStartLock(runDir, options), { code: 'START_BUSY' }, label);
        assert.equal(JSON.parse(fs.readFileSync(lockPath, 'utf8')).nonce, 'd'.repeat(48), label);
      } else {
        const recovered = await acquireStartLock(runDir, options);
        assert.notEqual(recovered.nonce, 'd'.repeat(48), label);
        await recovered.assertOwned();
        await recovered.release();
      }
    }
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});

test('Linux start lock recovers after exact owner exits, without reaping a paused live owner by age', { skip: process.platform !== 'linux' }, async () => {
  const temporary = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'browser-serve-lock-linux-'));
  const project = path.join(temporary, 'project');
  const runDir = path.join(temporary, 'run', 'serve');
  fs.mkdirSync(project);
  fs.writeFileSync(path.join(project, 'index.html'), '<p>recovered</p>');
  await fs.promises.mkdir(runDir, { recursive: true });
  const lockPath = path.join(runDir, 'start.lock');
  const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { windowsHide: true, stdio: 'ignore' });
  let started;
  try {
    await new Promise((resolve, reject) => { child.once('spawn', resolve); child.once('error', reject); });
    const identity = await readLinuxProcessIdentity(child.pid);
    assert.equal(identity.state, 'live');
    fs.writeFileSync(lockPath, JSON.stringify({
      version: 1, nonce: 'e'.repeat(48), acquiredAtMillis: Date.now() - MAX_START_LOCK_MS - 5_000,
      owner: { pid: identity.pid, processStartTime: identity.processStartTime, bootId: identity.bootId },
    }), { mode: 0o600 });
    await assert.rejects(acquireStartLock(runDir), { code: 'START_BUSY' });
    child.kill('SIGTERM');
    await new Promise((resolve) => child.once('exit', resolve));
    started = await runServeCommand(parseServeArguments(['start', '--dir', project, '--lease-ms', '60000']), {
      runDir, projectsRoot: temporary, spawnChild: (configFile) => spawnServeChild(configFile, runDir),
    });
    assert.equal(started.code, 'OK');
    assert.equal(started.executed, true);
  } finally {
    if (child.exitCode === null && !child.killed) child.kill('SIGTERM');
    if (started?.data?.serviceId) await runServeCommand(parseServeArguments(['stop', started.data.serviceId]), { runDir });
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});

test('serve start rejects a sensitive root and reports port collisions without claiming readiness', async () => {
  const temporary = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'browser-serve-negative-'));
  const project = path.join(temporary, 'project');
  const sensitive = path.join(temporary, '.dsh');
  const runDir = path.join(temporary, 'run', 'serve');
  fs.mkdirSync(project);
  fs.mkdirSync(sensitive);
  const occupied = net.createServer();
  await new Promise((resolve, reject) => occupied.listen(0, '127.0.0.1', (error) => error ? reject(error) : resolve()));
  const port = occupied.address().port;
  try {
    const blocked = await runServeCommand(parseServeArguments(['start', '--dir', sensitive]), { runDir, projectsRoot: temporary });
    assert.equal(blocked.code, 'PROJECT_ROOT_DENIED');
    const collision = await runServeCommand(parseServeArguments(['start', '--dir', project, '--port', String(port)]), { runDir, projectsRoot: temporary, spawnChild: (file) => spawnServeChild(file, runDir) });
    assert.equal(collision.code, 'PORT_IN_USE');
    assert.notEqual(collision.data.ready, true);
  } finally {
    await new Promise((resolve) => occupied.close(resolve));
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});

test('external registrations retain UNKNOWN through health timeouts and never stop the external process', async () => {
  const temporary = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'browser-serve-external-'));
  const project = path.join(temporary, 'project');
  const runDir = path.join(temporary, 'run', 'serve');
  fs.mkdirSync(project);
  let unhealthy = false;
  const server = http.createServer((request, response) => {
    if (unhealthy) return;
    if (request.url !== '/dev') { response.writeHead(404).end(); return; }
    response.writeHead(200, { 'Content-Type': 'text/plain' }).end('dev server alive');
  });
  await new Promise((resolve, reject) => server.listen(0, '127.0.0.1', (error) => error ? reject(error) : resolve()));
  const port = server.address().port;
  try {
    const registered = await runServeCommand(parseServeArguments(['register', '--dir', project, '--url', `http://127.0.0.1:${port}/dev`, '--lease-ms', '60000']), { runDir, projectsRoot: temporary });
    assert.equal(registered.code, 'OK');
    assert.equal(registered.data.kind, 'external');
    const registry = path.join(runDir, `${registered.data.serviceId}.json`);
    assert.equal(JSON.parse(fs.readFileSync(registry, 'utf8')).kind, 'external');
    assert.equal(JSON.parse(fs.readFileSync(registry, 'utf8')).healthPath, '/dev');
    assert.equal(registered.data.url, `http://127.0.0.1:${port}/dev`);
    unhealthy = true;
    const status = await runServeCommand(parseServeArguments(['status', registered.data.serviceId]), { runDir });
    assert.equal(status.code, 'OK');
    assert.equal(status.state, 'UNKNOWN');
    assert.equal(fs.existsSync(registry), true);
    const list = await runServeCommand(parseServeArguments(['list']), { runDir });
    assert.equal(list.state, 'UNKNOWN');
    assert.equal(list.data.services[0].kind, 'external');
    const stop = await runServeCommand(parseServeArguments(['stop', registered.data.serviceId]), { runDir });
    assert.equal(stop.code, 'EXTERNAL_NOT_OWNED');
    assert.equal(fs.existsSync(registry), true);
    unhealthy = false;
    const recovered = await runServeCommand(parseServeArguments(['status', registered.data.serviceId]), { runDir });
    assert.equal(recovered.state, 'RUNNING');
    assert.equal((await httpRead(port, '/dev')).status, 200);
    const unregistered = await runServeCommand(parseServeArguments(['unregister', registered.data.serviceId]), { runDir });
    assert.equal(unregistered.code, 'OK');
    assert.equal(unregistered.data.unregistered, true);
    assert.equal(fs.existsSync(registry), false);
    assert.equal((await httpRead(port, '/dev')).status, 200);
  } finally {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});

test('a lost authenticated stop response returns UNKNOWN and retains the owner record until exact child exit', async () => {
  const temporary = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'browser-serve-stop-unknown-'));
  const project = path.join(temporary, 'project');
  const runDir = path.join(temporary, 'run', 'serve');
  fs.mkdirSync(project);
  fs.writeFileSync(path.join(project, 'index.html'), '<p>stop recovery</p>');
  fs.writeFileSync(path.join(project, 'large.bin'), Buffer.alloc(4 * 1024 * 1024, 3));
  let started;
  let held;
  try {
    started = await runServeCommand(parseServeArguments(['start', '--dir', project, '--lease-ms', '60000']), {
      runDir, projectsRoot: temporary, spawnChild: (file) => spawnServeChild(file, runDir),
    });
    assert.equal(started.code, 'OK');
    const recordPath = path.join(runDir, `${started.data.serviceId}.json`);
    const record = JSON.parse(fs.readFileSync(recordPath, 'utf8'));
    held = await httpOpenPaused(started.data.port, '/large.bin');
    let stopDeliveries = 0;
    const loseStopReply = async (port, id, token, op) => {
      const result = await requestControl(port, id, token, op);
      if (op === 'stop') { stopDeliveries += 1; return null; }
      return result;
    };
    const uncertain = await runServeCommand(parseServeArguments(['stop', started.data.serviceId]), { runDir, requestControl: loseStopReply });
    assert.equal(uncertain.code, 'STOP_UNCONFIRMED');
    assert.equal(uncertain.executed, null);
    assert.equal(uncertain.state, 'UNKNOWN');
    assert.equal(stopDeliveries, 1);
    assert.equal(fs.existsSync(recordPath), true);
    const stopDeadline = Date.now() + 750;
    let whileStopping;
    do {
      whileStopping = await runServeCommand(parseServeArguments(['status', started.data.serviceId]), { runDir });
      if (whileStopping.state === 'UNKNOWN') break;
      await new Promise((resolve) => setTimeout(resolve, 25));
    } while (Date.now() < stopDeadline);
    assert.equal(whileStopping.code, 'OK');
    assert.equal(whileStopping.state, 'UNKNOWN');
    assert.equal(fs.existsSync(recordPath), true);
    held.response.destroy();
    held.request.destroy();
    held = null;
    if (process.platform === 'linux') {
      const deadline = Date.now() + 3500;
      let status;
      do {
        status = await runServeCommand(parseServeArguments(['status', started.data.serviceId]), { runDir });
        if (status.code === 'NOT_RUNNING') break;
        await new Promise((resolve) => setTimeout(resolve, 100));
      } while (Date.now() < deadline);
      assert.equal(status.code, 'NOT_RUNNING');
      assert.equal(fs.existsSync(recordPath), false);
    } else {
      assert.equal(record.kind, 'owned-static');
    }
  } finally {
    if (held) { held.response.destroy(); held.request.destroy(); }
    if (started?.data?.serviceId) await runServeCommand(parseServeArguments(['stop', started.data.serviceId]), { runDir });
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});

test('serve manager caps active services and uses finite leases without PID-based termination', async () => {
  const temporary = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'browser-serve-cap-'));
  const project = path.join(temporary, 'project');
  const runDir = path.join(temporary, 'run', 'serve');
  fs.mkdirSync(project);
  fs.writeFileSync(path.join(project, 'index.html'), '<p>managed</p>');
  const spawnChild = (configFile) => spawnServeChild(configFile, runDir);
  const serviceIds = [];
  try {
    for (let index = 0; index < MAX_ACTIVE_SERVICES; index += 1) {
      const result = await runServeCommand(parseServeArguments(['start', '--dir', project, '--lease-ms', '60000']), { runDir, projectsRoot: temporary, spawnChild });
      assert.equal(result.code, 'OK');
      assert.equal(Number.isSafeInteger(result.data.leaseExpiresAtMillis), true);
      serviceIds.push(result.data.serviceId);
    }
    const capped = await runServeCommand(parseServeArguments(['start', '--dir', project]), { runDir, projectsRoot: temporary, spawnChild });
    assert.equal(capped.code, 'SERVICE_LIMIT');
    assert.equal((await runServeCommand(parseServeArguments(['list']), { runDir })).data.services.length, MAX_ACTIVE_SERVICES);
  } finally {
    for (const serviceId of serviceIds) await runServeCommand(parseServeArguments(['stop', serviceId]), { runDir });
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});
