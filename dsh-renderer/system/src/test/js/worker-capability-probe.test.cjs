'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(
  path.join(__dirname, '../../main/assets/dsh-compat/worker-capability-probe.js'),
  'utf8',
);

assert.match(source, /com\.dshbox\.renderer\.worker-capabilities\.v1/);
assert.match(source, /new Worker\(objectUrl, options\)/);
assert.match(source, /type: 'module'/);
assert.match(source, /PDF_WORKER_API/);
assert.match(source, /duplex:"half"/);
assert.doesNotMatch(source, /fetch\s*\(/);
assert.doesNotMatch(source, /launch[_-]?token|authorization|cookie/i);

const objectUrls = new Map();
let sequence = 0;
let createdWorkers = 0;
let revokedWorkers = 0;

class FakeBlob {
  constructor(parts, options) {
    this.source = parts.join('');
    this.type = options?.type || '';
  }
}

function FakeUrl() {}
FakeUrl.createObjectURL = (blob) => {
  const url = `blob:worker-${++sequence}`;
  objectUrls.set(url, blob);
  return url;
};
FakeUrl.revokeObjectURL = (url) => {
  objectUrls.delete(url);
  revokedWorkers += 1;
};

class FakeWorker {
  constructor(url, options) {
    createdWorkers += 1;
    const blob = objectUrls.get(url);
    assert.ok(blob, 'Worker must use a registered Blob URL');
    this.source = blob.source.replace(/^export \{\};\n/, '');
    this.options = options;
  }

  postMessage(payload) {
    const workerContext = vm.createContext({
      AbortSignal,
      CompressionStream: function CompressionStream() {},
      DecompressionStream: function DecompressionStream() {},
      ImageDecoder: function ImageDecoder() {},
      Map,
      OffscreenCanvas: function OffscreenCanvas() {},
      Promise,
      ReadableStream,
      Request,
      Uint8Array,
      URL: FakeUrl,
      WebAssembly,
      self: null,
      postMessage: (value) => queueMicrotask(() => this.onmessage?.({ data: value })),
      onmessage: null,
    });
    vm.runInContext('self = globalThis;', workerContext);
    vm.runInContext(this.source, workerContext);
    queueMicrotask(() => workerContext.self.onmessage?.({ data: payload }));
  }

  terminate() {}
}

const context = vm.createContext({
  AbortSignal,
  Blob: FakeBlob,
  Map,
  Promise,
  ReadableStream,
  Request,
  Uint8Array,
  URL: FakeUrl,
  WebAssembly,
  Worker: FakeWorker,
  clearTimeout,
  setTimeout,
});
vm.runInContext('window = globalThis; window.top = window;', context);
vm.runInContext(`
  Object.defineProperty(
    globalThis,
    Symbol.for('com.dshbox.renderer.worker-capabilities-config.v1'),
    { configurable: true, value: { probeToken: 'test-token' } }
  );
`, context);
assert.equal(vm.runInContext(source, context), 'started');

setTimeout(() => {
  const result = vm.runInContext(
    "globalThis[Symbol.for('com.dshbox.renderer.worker-capabilities.v1')]",
    context,
  );
  assert.equal(result.status, 'complete');
  assert.equal(result.probeToken, 'test-token');
  assert.equal(result.capabilities.WORKER_CLASSIC, true);
  assert.equal(result.capabilities.WORKER_MODULE, true);
  assert.equal(result.capabilities.WORKER_TRANSFERABLE_STREAM, true);
  assert.equal(result.capabilities.WORKER_FETCH_STREAM_BODY, true);
  assert.equal(result.capabilities.PDF_WORKER_API, false);
  assert.equal(result.details.PROMISE_WITH_RESOLVERS, true);
  assert.equal(result.details.IMAGE_DECODER, true);
  assert.equal(createdWorkers, 4);
  assert.equal(revokedWorkers, 4);
  console.log('Worker capability probe checks passed: classic, module, stream, PDF gate');
}, 100);
