'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const adapter = fs.readFileSync(
  path.join(__dirname, '../../main/java/com/dshbox/dsh/renderer/system/SystemDshRendererAdapter.kt'),
  'utf8',
);
const workerProbe = fs.readFileSync(
  path.join(__dirname, '../../main/assets/dsh-compat/worker-capability-probe.js'),
  'utf8',
);

assert.match(adapter, /WebViewFeature\.DOCUMENT_START_SCRIPT/);
assert.match(adapter, /COMPATIBILITY_UNAVAILABLE/);
assert.match(adapter, /WORKER_PROBE_ASSET/);
assert.match(adapter, /WorkerCapabilitiesObserved/);
assert.match(workerProbe, /WORKER_CLASSIC/);
assert.match(workerProbe, /WORKER_MODULE/);
assert.match(workerProbe, /WORKER_TRANSFERABLE_STREAM/);
assert.match(workerProbe, /WORKER_FETCH_STREAM_BODY/);
assert.match(workerProbe, /PDF_WORKER_API/);
assert.doesNotMatch(workerProbe, /fetch\s*\(/);
assert.equal(adapter.includes('deferredCompatibilitySource'), false);
assert.equal(adapter.includes('installDeferredCompatibility'), false);
assert.equal(
  /onPageFinished[\s\S]{0,800}evaluateJavascript\(\s*source/.test(adapter),
  false,
  'DSH startup compatibility must not be injected after page load',
);

console.log('System renderer contract checks passed: document-start and worker diagnostics');
