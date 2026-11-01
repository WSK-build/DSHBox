/* APP-owned worker capability observation. No DSH data or RPC is accessed. */
(function () {
  'use strict';
  if (window.top !== window) return 'not-top';

  var resultKey = Symbol.for('com.dshbox.renderer.worker-capabilities.v1');
  var configKey = Symbol.for('com.dshbox.renderer.worker-capabilities-config.v1');
  var config = globalThis[configKey];
  var probeToken = config && typeof config.probeToken === 'string'
    ? config.probeToken
    : '';
  try { delete globalThis[configKey]; } catch (_) {}
  var startedAt = Date.now();
  var empty = Object.freeze({});

  function publish(status, capabilities, details) {
    try {
      Object.defineProperty(globalThis, resultKey, {
        configurable: true,
        enumerable: false,
        writable: false,
        value: Object.freeze({
          status: status,
          probeToken: probeToken,
          capabilities: Object.freeze(capabilities || empty),
          details: Object.freeze(details || empty),
          startedAt: startedAt,
          updatedAt: Date.now()
        })
      });
    } catch (_) {}
  }

  publish('running', empty, empty);
  if (typeof Worker !== 'function' || typeof Blob !== 'function' ||
      typeof URL !== 'function' || typeof URL.createObjectURL !== 'function') {
    publish('complete', {
      WORKER_CLASSIC: false,
      WORKER_MODULE: false,
      WORKER_TRANSFERABLE_STREAM: false,
      WORKER_FETCH_STREAM_BODY: false,
      PDF_WORKER_API: false
    }, {});
    return 'started';
  }

  var apiProbeSource =
    'function inspectWorkerApis(){' +
      'var details={' +
        'PROMISE_WITH_RESOLVERS:typeof Promise.withResolvers==="function",' +
        'PROMISE_TRY:typeof Promise.try==="function",' +
        'ABORT_SIGNAL_ANY:typeof AbortSignal==="function"&&typeof AbortSignal.any==="function",' +
        'MAP_GET_OR_INSERT:typeof Map.prototype.getOrInsert==="function",' +
        'MAP_GET_OR_INSERT_COMPUTED:typeof Map.prototype.getOrInsertComputed==="function",' +
        'URL_PARSE:typeof URL==="function"&&typeof URL.parse==="function",' +
        'REGEXP_ESCAPE:typeof RegExp.escape==="function",' +
        'UINT8_TO_BASE64:typeof Uint8Array.prototype.toBase64==="function",' +
        'UINT8_FROM_BASE64:typeof Uint8Array.fromBase64==="function",' +
        'UINT8_TO_HEX:typeof Uint8Array.prototype.toHex==="function",' +
        'UINT8_FROM_HEX:typeof Uint8Array.fromHex==="function",' +
        'OFFSCREEN_CANVAS:typeof OffscreenCanvas==="function",' +
        'IMAGE_DECODER:typeof ImageDecoder==="function",' +
        'COMPRESSION_STREAM:typeof CompressionStream==="function",' +
        'DECOMPRESSION_STREAM:typeof DecompressionStream==="function",' +
        'WEBASSEMBLY:typeof WebAssembly==="object"&&typeof WebAssembly.instantiate==="function"' +
      '};' +
      'var pdf=true;for(var key in details){if(details[key]!==true){pdf=false;break}}' +
      'return {details:details,pdfReady:pdf};' +
    '}';

  var classicSource = apiProbeSource +
    ';self.onmessage=function(){var value=inspectWorkerApis();' +
    'value.started=true;self.postMessage(value);};';

  var moduleSource = 'export {};\n' + classicSource;

  var transferSource =
    'self.onmessage=function(event){var value=event.data;' +
    'self.postMessage({started:true,stream:!!value&&' +
    'typeof value.getReader==="function"});};';

  var fetchStreamSource =
    'self.onmessage=function(){var supported=false;try{' +
      'var stream=new ReadableStream({start:function(controller){' +
        'controller.enqueue(new Uint8Array([1]));controller.close();' +
      '}});' +
      'var request=new Request("http://127.0.0.1/",{method:"POST",body:stream,duplex:"half"});' +
      'supported=!!request.body;' +
    '}catch(_){}self.postMessage({started:true,supported:supported});};';

  function probe(source, options, payload, transfer) {
    return new Promise(function (resolve) {
      var worker = null;
      var objectUrl = null;
      var finished = false;
      var timer = setTimeout(function () {
        finish({ started: false, reason: 'timeout' });
      }, 1600);

      function finish(value) {
        if (finished) return;
        finished = true;
        clearTimeout(timer);
        try {
          if (worker) {
            worker.onmessage = null;
            worker.onerror = null;
            worker.terminate();
          }
        } catch (_) {}
        try {
          if (objectUrl) URL.revokeObjectURL(objectUrl);
        } catch (_) {}
        resolve(value || { started: false, reason: 'unknown' });
      }

      try {
        objectUrl = URL.createObjectURL(new Blob([source], { type: 'text/javascript' }));
        worker = options ? new Worker(objectUrl, options) : new Worker(objectUrl);
        worker.onmessage = function (event) {
          finish(event.data && typeof event.data === 'object'
            ? event.data
            : { started: false, reason: 'invalid-message' });
        };
        worker.onerror = function () {
          finish({ started: false, reason: 'worker-error' });
        };
        worker.postMessage(payload, transfer || []);
      } catch (_) {
        finish({ started: false, reason: 'construction-error' });
      }
    });
  }

  var stream = null;
  var transfer = [];
  try {
    stream = new ReadableStream({
      start: function (controller) {
        controller.enqueue(new Uint8Array([1]));
        controller.close();
      }
    });
    transfer = [stream];
  } catch (_) {}

  Promise.all([
    probe(classicSource, null, null, []),
    probe(moduleSource, { type: 'module' }, null, []),
    probe(transferSource, null, stream, transfer),
    probe(fetchStreamSource, null, null, [])
  ]).then(function (values) {
    var classic = values[0];
    var module = values[1];
    var streamResult = values[2];
    var fetchStream = values[3];
    var details = module.details && typeof module.details === 'object'
      ? module.details
      : {};
    var pdfReady = module.started === true && module.pdfReady === true;
    publish('complete', {
      WORKER_CLASSIC: classic.started === true,
      WORKER_MODULE: module.started === true,
      WORKER_TRANSFERABLE_STREAM: streamResult.started === true &&
        streamResult.stream === true,
      WORKER_FETCH_STREAM_BODY: fetchStream.started === true &&
        fetchStream.supported === true,
      PDF_WORKER_API: pdfReady
    }, details);
  }).catch(function () {
    publish('complete', {
      WORKER_CLASSIC: false,
      WORKER_MODULE: false,
      WORKER_TRANSFERABLE_STREAM: false,
      WORKER_FETCH_STREAM_BODY: false,
      PDF_WORKER_API: false
    }, {});
  });

  return 'started';
}());
