'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const background = require(
  '../../main/assets/dsh-status-bridge/background.js',
);
const content = require(
  '../../main/assets/dsh-status-bridge/content.js',
);
const manifest = JSON.parse(fs.readFileSync(
  path.join(__dirname, '../../main/assets/dsh-status-bridge/manifest.json'),
  'utf8',
));

const origin = background.TRUSTED_ORIGIN;
const configPath = process.argv[2] || path.join(
  __dirname,
  '../../build/generated/dshPageCompatConfigAssets/dsh-status-bridge/page-compat-config.js',
);

function mockBrowser() {
  const listeners = {};
  const nativeMessages = [];
  const browser = {
    runtime: {
      sendNativeMessage: (nativeApp, message) => {
        nativeMessages.push({ nativeApp, message });
        return Promise.resolve('ack');
      },
      onMessage: {
        addListener: (listener) => {
          listeners.message = listener;
        },
      },
    },
    webRequest: {
      onBeforeSendHeaders: {
        addListener: (listener, filter, extra) => {
          listeners.beforeSendHeaders = { listener, filter, extra };
        },
      },
      onBeforeRequest: {
        addListener: (listener, filter) => {
          listeners.beforeRequest = { listener, filter };
        },
      },
      onHeadersReceived: {
        addListener: (listener, filter, extra) => {
          listeners.headersReceived = { listener, filter, extra };
        },
      },
    },
  };
  return { browser, listeners, nativeMessages };
}

function request(requestId, pathname, overrides = {}) {
  return {
    requestId,
    tabId: 7,
    frameId: 0,
    type: 'main_frame',
    url: origin + pathname,
    ...overrides,
  };
}

function documentSender(pathname, documentId = 'document-1', overrides = {}) {
  return {
    tab: { id: 7 },
    frameId: 0,
    url: origin + pathname,
    documentId,
    ...overrides,
  };
}

test('manifest and generated config use the exact DSH origin', () => {
  const requiredPermissions = [
    'geckoViewAddons',
    'nativeMessaging',
    'nativeMessagingFromContent',
    'webRequest',
  ];
  for (const permission of requiredPermissions) {
    assert.ok(manifest.permissions.includes(permission), permission);
  }
  for (const permission of manifest.permissions) {
    if (!permission.startsWith('http://')) continue;
    assert.equal(permission, origin + '/*');
  }
  for (const script of manifest.content_scripts) {
    assert.deepEqual(script.matches, [origin + '/*']);
  }

  const generated = fs.readFileSync(configPath, 'utf8');
  assert.match(
    generated,
    new RegExp(`trustedOrigin:\\s*"${origin.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}"`),
  );
});

test('main-frame filter, nonce capture, header release, and document binding', () => {
  const mock = mockBrowser();
  const bridge = background.createStatusBridge(mock.browser);
  bridge.install();

  assert.deepEqual(mock.listeners.beforeRequest.filter, {
    urls: [origin + '/*'],
    types: ['main_frame'],
  });
  bridge.onBeforeRequest(request('ignored', '/image.png', {
    type: 'image',
  }));
  assert.equal(bridge.pendingRequestCount(7), 0);

  bridge.onBeforeRequest(request('request-1', '/page'));
  bridge.onBeforeSendHeaders(request('request-1', '/page', {
    requestHeaders: [
      { name: 'X-DSHBox-Navigation', value: 'nonce-1' },
    ],
  }));
  assert.equal(bridge.pendingNonceCount(), 1);

  bridge.onHeadersReceived(request('request-1', '/page', {
    statusCode: 401,
  }));
  assert.equal(bridge.pendingNonceCount(), 0);
  assert.equal(mock.nativeMessages.length, 1);
  assert.deepEqual(mock.nativeMessages[0].message, {
    kind: 'headers',
    requestId: 'request-1',
    tabId: 7,
    frameId: 0,
    status: 401,
    url: origin + '/page',
    nonce: 'nonce-1',
  });

  const status = bridge.onRuntimeMessage(
    { kind: 'document' },
    documentSender('/page'),
  );
  assert.deepEqual(status, {
    kind: 'document-status',
    requestId: 'request-1',
    tabId: 7,
    frameId: 0,
    status: 401,
    url: origin + '/page',
    nonce: 'nonce-1',
    documentId: 'document-1',
  });
  assert.equal(bridge.pendingRequestCount(7), 0);
});

test('overlapping requests bind by their own URL instead of the current tab request', () => {
  const mock = mockBrowser();
  const bridge = background.createStatusBridge(mock.browser);

  bridge.onBeforeRequest(request('old-request', '/old'));
  bridge.onBeforeSendHeaders(request('old-request', '/old', {
    requestHeaders: [
      { name: 'X-DSHBox-Navigation', value: 'old-nonce' },
    ],
  }));
  bridge.onHeadersReceived(request('old-request', '/old', {
    statusCode: 401,
  }));

  bridge.onBeforeRequest(request('new-request', '/new'));
  bridge.onBeforeSendHeaders(request('new-request', '/new', {
    requestHeaders: [
      { name: 'X-DSHBox-Navigation', value: 'new-nonce' },
    ],
  }));
  bridge.onHeadersReceived(request('new-request', '/new', {
    statusCode: 200,
  }));

  const oldDocument = bridge.onRuntimeMessage(
    { kind: 'document' },
    documentSender('/old', 'old-document'),
  );
  assert.equal(oldDocument.requestId, 'old-request');
  assert.equal(oldDocument.status, 401);
  assert.equal(oldDocument.nonce, 'old-nonce');

  const newDocument = bridge.onRuntimeMessage(
    { kind: 'document' },
    documentSender('/new', 'new-document'),
  );
  assert.equal(newDocument.requestId, 'new-request');
  assert.equal(newDocument.status, 200);
  assert.equal(newDocument.nonce, 'new-nonce');
});

test('ambiguous same-URL requests are rejected instead of misattributed', () => {
  const mock = mockBrowser();
  const bridge = background.createStatusBridge(mock.browser);

  for (const requestId of ['ambiguous-1', 'ambiguous-2']) {
    bridge.onBeforeRequest(request(requestId, '/same'));
    bridge.onBeforeSendHeaders(request(requestId, '/same', {
      requestHeaders: [
        { name: 'X-DSHBox-Navigation', value: requestId + '-nonce' },
      ],
    }));
    bridge.onHeadersReceived(request(requestId, '/same', {
      statusCode: requestId.endsWith('1') ? 401 : 200,
    }));
  }

  assert.equal(bridge.onRuntimeMessage(
    { kind: 'document' },
    documentSender('/same', 'ambiguous-document'),
  ), undefined);
});

test('content script only forwards a top-level exact-origin document binding', async () => {
  let sendMessageCount = 0;
  const nativeMessages = [];
  const browser = {
    runtime: {
      sendMessage: () => {
        sendMessageCount += 1;
        return Promise.resolve({
          kind: 'document-status',
          requestId: 'request-1',
          documentId: 'document-1',
        });
      },
      sendNativeMessage: (nativeApp, message) => {
        nativeMessages.push({ nativeApp, message });
        return Promise.resolve('ack');
      },
    },
  };
  const topWindow = {};
  topWindow.top = topWindow;
  assert.equal(await content.startContentBridge(browser, {
    window: topWindow,
    location: { origin },
  }), true);
  assert.equal(sendMessageCount, 1);
  assert.equal(nativeMessages.length, 1);
  assert.equal(nativeMessages[0].nativeApp, background.NATIVE_APPLICATION);

  const childWindow = {};
  assert.equal(await content.startContentBridge(browser, {
    window: childWindow,
    location: { origin },
  }), false);
  assert.equal(sendMessageCount, 1);
});
