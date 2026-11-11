'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { URL: NodeURL } = require('node:url');

const systemRoot = path.resolve(__dirname, '../../..');
const rendererRoot = path.resolve(systemRoot, '..');
const sourceRoot = path.resolve(rendererRoot, '..');
const script = fs.readFileSync(
  path.join(rendererRoot, 'api/src/main/assets/dsh-renderer/dsh-page-compat.js'),
  'utf8'
);
assert.doesNotMatch(script, /dshbox\.invalid/);
const CONFIG_KEY = Symbol.for('com.dshbox.renderer.dsh-page-compat-config.v1');
const INSTALL_KEY = Symbol.for('com.dshbox.renderer.dsh-page-compat.v1');
const TRUSTED_ORIGIN = 'http://127.0.0.1:3080';
const OPEN_DOCUMENT_URL = 'dshbox://open-settings-document';
const OPEN_EXTERNAL_URL_PREFIX = 'dshbox://open-external?url=';

class LegacyURL {
  constructor(input, base) {
    this.native = arguments.length > 1
      ? new NodeURL(input, base)
      : new NodeURL(input);
  }

  get protocol() { return this.native.protocol; }
  get origin() { return this.native.origin; }
  get href() { return this.native.href; }
  get host() { return this.broken() ? '' : this.native.host; }
  get hostname() { return this.broken() ? '' : this.native.hostname; }
  get port() { return this.native.port; }
  get username() { return this.native.username; }
  get password() { return this.native.password; }
  get pathname() {
    const userinfo = this.native.username
      ? this.native.username + (this.native.password ? ':' + this.native.password : '') + '@'
      : '';
    return this.broken()
      ? '//' + userinfo + this.native.host + this.native.pathname
      : this.native.pathname;
  }
  get search() { return this.native.search; }
  get hash() { return this.native.hash; }
  get searchParams() { return this.native.searchParams; }
  get [Symbol.toStringTag]() { return 'URL'; }

  broken() {
    return this.native.protocol === 'dsh-resource:' && this.native.host !== '';
  }

  toString() { return this.native.href; }
  toJSON() { return this.native.href; }

  static canParse(input, base) {
    try {
      if (arguments.length > 1) new LegacyURL(input, base);
      else new LegacyURL(input);
      return true;
    } catch (_) {
      return false;
    }
  }

  static parse(input, base) {
    try {
      return arguments.length > 1
        ? new LegacyURL(input, base)
        : new LegacyURL(input);
    } catch (_) {
      return null;
    }
  }

  static createObjectURL() { return 'blob:legacy-test'; }
  static revokeObjectURL() {}
}

class FakeLocation {
  constructor(origin) {
    this.origin = origin;
    this.currentHref = origin + '/';
    this.navigations = [];
  }

  set href(value) {
    this.navigations.push(value);
    this.currentHref = value;
  }

  get href() {
    return this.currentHref || this.origin + '/';
  }
}

class FakeDocument {
  constructor() {
    this.listeners = new Map();
  }

  addEventListener(type, listener, capture) {
    const entries = this.listeners.get(type) || [];
    entries.push({ listener, capture: !!capture });
    this.listeners.set(type, entries);
  }

  emit(type, target, properties = {}) {
    const event = {
      type,
      target,
      button: 0,
      defaultPrevented: false,
      propagationStopped: false,
      immediateStopped: false,
      preventDefault() { this.defaultPrevented = true; },
      stopPropagation() { this.propagationStopped = true; },
      stopImmediatePropagation() { this.immediateStopped = true; },
      ...properties,
    };
    for (const { listener } of (this.listeners.get(type) || []).slice()) {
      listener(event);
      if (event.immediateStopped) break;
    }
    return event;
  }

  count(type) {
    return (this.listeners.get(type) || []).length;
  }
}

class FakeButton {
  constructor(label) {
    this.textContent = label;
  }

  closest(selector) {
    return selector === 'button' ? this : null;
  }
}

function makeWindow({
  url = LegacyURL,
  origin = TRUSTED_ORIGIN,
  topLevel = true,
  pluginEnabled,
} = {}) {
  const location = new FakeLocation(origin);
  const document = new FakeDocument();
  const localStorage = {
    values: pluginEnabled === undefined
      ? {}
      : { 'dsh-mobile-enabled': String(pluginEnabled) },
    getItem(key) {
      return Object.prototype.hasOwnProperty.call(this.values, key)
        ? this.values[key]
        : null;
    },
    setItem(key, value) { this.values[key] = String(value); },
  };
  const nativeOpened = [];
  const nativeOpen = function (...args) {
    nativeOpened.push(args);
    return { native: true };
  };
  const sandbox = {
    URL: url,
    document,
    localStorage,
    location,
    open: nativeOpen,
    console: { warn() {} },
    setTimeout: () => 0,
  };
  sandbox.window = sandbox;
  sandbox.top = topLevel ? sandbox : {};
  const context = vm.createContext(sandbox);
  return { context, document, location, nativeOpen, nativeOpened };
}

function installPageCompat(harness) {
  harness.context[CONFIG_KEY] = Object.freeze({ trustedOrigin: TRUSTED_ORIGIN });
  vm.runInContext(script, harness.context);
}

function urlOf(harness, input, base) {
  const CompatURL = harness.context.window.URL;
  return base === undefined
    ? new CompatURL(input)
    : new CompatURL(input, base);
}

const URL_FIELDS = [
  'href',
  'protocol',
  'username',
  'password',
  'host',
  'hostname',
  'port',
  'pathname',
  'search',
  'hash',
  'origin',
];

function assertNativeViewEquals(harness, input, base) {
  const expected = base === undefined
    ? new NodeURL(input)
    : new NodeURL(input, base);
  const actual = urlOf(harness, input, base);
  for (const field of URL_FIELDS) {
    assert.equal(actual[field], expected[field], `${input} ${field}`);
  }
  assert.equal(String(actual), expected.href);
  assert.equal(Object.prototype.toString.call(actual), '[object URL]');
  return actual;
}

function assertRepaired(harness, input) {
  const value = urlOf(harness, input);
  assert.equal(value.protocol, 'dsh-resource:');
  assert.equal(value.hostname, 'file');
  assert.equal(value.host, 'file');
  assert.equal(value.pathname, '/x');
  assert.equal(value.href, 'dsh-resource://file/x');
  assert.equal(value.origin, 'null');
  assert.equal(value.toString(), value.href);
  assert.equal(value.toJSON(), value.href);
  assert.equal(String(value), value.href);
  assert.equal(Object.prototype.toString.call(value), '[object URL]');
  assert.equal(value.constructor, harness.context.window.URL);
  assert.ok(value instanceof harness.context.window.URL);
  assert.ok(value instanceof LegacyURL);
  return value;
}

{
  // A compliant engine must be left completely alone.
  const harness = makeWindow({ url: NodeURL });
  installPageCompat(harness);
  assert.equal(harness.context.window.URL, NodeURL);
  assert.equal(harness.context[INSTALL_KEY].trustedOrigin, TRUSTED_ORIGIN);
  // Directly compare the public URL surface with Node's WHATWG implementation.
  for (const reference of [
    '../other?x=2#z',
    '%2e%2e/other',
    '/a//b',
    '/../x',
    '//other/a/../b',
  ]) {
    assertNativeViewEquals(
      harness,
      reference,
      'dsh-resource://file/session/id/path?q=1#h'
    );
  }
}

{
  // Only the observed broken dsh-resource authority view is repaired.
  const harness = makeWindow();
  installPageCompat(harness);
  assertRepaired(harness, 'dsh-resource://file/x');

  const query = urlOf(harness, 'dsh-resource://file/x?download=1#preview');
  assert.equal(query.hostname, 'file');
  assert.equal(query.host, 'file');
  assert.equal(query.pathname, '/x');
  assert.equal(query.href, 'dsh-resource://file/x?download=1#preview');
  assert.equal(query.origin, 'null');
  assert.equal(query.search, '?download=1');
  assert.equal(query.hash, '#preview');
  assert.equal(query.searchParams.get('download'), '1');

  for (const authorityUrl of [
    'dsh-resource://file:8080/x',
    'dsh-resource://user@file/x',
    'dsh-resource://user:pass@file:8080/x',
    'dsh-resource://[::1]/x',
    'dsh-resource://[::1]:8080/x',
  ]) {
    assertNativeViewEquals(harness, authorityUrl);
  }
  const portOnly = urlOf(harness, 'dsh-resource://file:8080/x');
  assert.equal(portOnly.host, 'file:8080');
  assert.equal(portOnly.hostname, 'file');
  assert.equal(portOnly.port, '8080');
  const userinfo = urlOf(harness, 'dsh-resource://user@file/x');
  assert.equal(userinfo.host, 'file');
  assert.equal(userinfo.hostname, 'file');
  assert.equal(userinfo.port, '');
  assert.equal(userinfo.username, 'user');
  const ipv6 = urlOf(harness, 'dsh-resource://[::1]:8080/x');
  assert.equal(ipv6.host, '[::1]:8080');
  assert.equal(ipv6.hostname, '[::1]');
  assert.equal(ipv6.port, '8080');
  const noPort = urlOf(harness, 'dsh-resource://file/x');
  assert.equal(noPort.host, 'file');
  assert.equal(noPort.hostname, 'file');
  assert.equal(noPort.port, '');

  const base = urlOf(harness, 'dsh-resource://file/session/id/path?q=1#h');
  assert.equal(
    urlOf(harness, '../other?x=2#z', base).href,
    'dsh-resource://file/session/other?x=2#z'
  );
  assert.equal(
    urlOf(harness, '/root/file', base).href,
    'dsh-resource://file/root/file'
  );
  assert.equal(
    urlOf(harness, '?only=query', base).href,
    'dsh-resource://file/session/id/path?only=query'
  );
  assert.equal(
    urlOf(harness, '#only-hash', base).href,
    'dsh-resource://file/session/id/path?q=1#only-hash'
  );
  assert.equal(
    urlOf(harness, '', base).href,
    'dsh-resource://file/session/id/path?q=1'
  );
  assert.equal(
    urlOf(harness, '//other/path', base).href,
    'dsh-resource://other/path'
  );

  const rfcBases = [
    'dsh-resource://file/session/id/path?q=1#h',
    'dsh-resource:/path?q=1#h',
    'dsh-resource:///path?q=1#h',
    'dsh-resource://file',
  ];
  const rfcReferences = [
    '../other',
    '%2e%2e/other',
    '.%2e/other',
    '%2E%2E/other',
    '/a//b',
    '/a/./b',
    '/a/%2e%2e/b',
    '/../x',
    '/..',
    '/.',
    './x',
    '//other/a/../b',
    '?q=1',
    '#h',
  ];
  for (const rfcBase of rfcBases) {
    const compatBase = urlOf(harness, rfcBase);
    for (const reference of rfcReferences) {
      assertNativeViewEquals(harness, reference, compatBase);
    }
  }
  // A no-authority base stays no-authority; an empty authority stays doubled.
  assert.equal(
    urlOf(harness, '../other', 'dsh-resource:/path?q=1#h').href,
    'dsh-resource:/other'
  );
  assert.equal(
    urlOf(harness, '../other', 'dsh-resource:///path?q=1#h').href,
    'dsh-resource:///other'
  );

  // Ordinary URLs, non-authority forms, and real double-slash paths are untouched.
  assert.equal(urlOf(harness, 'http://example.com//path').pathname, '//path');
  assert.equal(urlOf(harness, 'http://example.com//path').hostname, 'example.com');
  assert.equal(urlOf(harness, 'mailto:user@example.com').hostname, '');
  assert.equal(urlOf(harness, 'dsh-resource:///path').hostname, '');
  assert.equal(urlOf(harness, 'dsh-resource:/path').hostname, '');
  assert.equal(
    urlOf(harness, 'https://example.com/a', base).href,
    'https://example.com/a'
  );

  assert.equal(
    harness.context.window.URL.createObjectURL({}),
    'blob:legacy-test'
  );
  assert.equal(
    harness.context.window.URL.parse('dsh-resource://file/x').hostname,
    'file'
  );
  assert.equal(harness.context.window.URL.parse('::not-a-url'), null);
  assert.equal(
    harness.context.window.URL.canParse('dsh-resource://file/x'),
    true
  );
  assert.equal(harness.context.window.URL.prototype, LegacyURL.prototype);
}

{
  // The bridge is installed before DOM content exists.
  const enabled = makeWindow({ url: NodeURL, pluginEnabled: true });
  installPageCompat(enabled);
  assert.equal(enabled.document.count('pointerdown'), 1);
  assert.equal(enabled.document.count('click'), 1);
  assert.equal(enabled.document.listeners.size, 2);

  const disabled = makeWindow({ url: NodeURL, pluginEnabled: false });
  installPageCompat(disabled);
  assert.equal(disabled.document.count('pointerdown'), 1);
  assert.equal(disabled.document.count('click'), 1);
  const disabledButton = new FakeButton('\u6253\u5f00\u914d\u7f6e\u6587\u4ef6');
  disabled.document.emit('pointerdown', disabledButton);
  disabled.document.emit('click', disabledButton);
  assert.deepEqual(disabled.location.navigations, [OPEN_DOCUMENT_URL]);

  const settingAbsent = makeWindow({ url: NodeURL });
  installPageCompat(settingAbsent);
  settingAbsent.document.emit('click', new FakeButton('Open configuration file'));
  assert.deepEqual(settingAbsent.location.navigations, [OPEN_DOCUMENT_URL]);

  const chinese = new FakeButton('  \u6253\u5f00\u914d\u7f6e\u6587\u4ef6  ');
  const pointer = enabled.document.emit('pointerdown', chinese);
  const click = enabled.document.emit('click', chinese);
  assert.equal(pointer.defaultPrevented, true);
  assert.equal(pointer.immediateStopped, true);
  assert.equal(click.defaultPrevented, true);
  assert.equal(click.immediateStopped, true);
  assert.deepEqual(enabled.location.navigations, [OPEN_DOCUMENT_URL]);

  const english = new FakeButton('Open configuration file');
  enabled.document.emit('click', english);
  assert.deepEqual(enabled.location.navigations, [
    OPEN_DOCUMENT_URL,
    OPEN_DOCUMENT_URL,
  ]);

  enabled.document.emit('pointerdown', new FakeButton('Open configuration file'));
  enabled.document.emit('pointerdown', new FakeButton('Other action'));
  enabled.document.emit('click', new FakeButton('Open configuration file'), {
    button: 2,
  });
  assert.equal(enabled.location.navigations.length, 3);

  // Re-running both content-script files in one document is idempotent.
  installPageCompat(enabled);
  assert.equal(enabled.document.count('pointerdown'), 1);
  assert.equal(enabled.document.count('click'), 1);
  enabled.document.emit('click', new FakeButton('Open configuration file'));
  assert.equal(enabled.location.navigations.length, 4);
}

{
  // Exact-origin and top-frame checks reject every other document.
  const wrongOrigin = makeWindow({ origin: 'http://127.0.0.1:3081' });
  installPageCompat(wrongOrigin);
  assert.equal(wrongOrigin.context[INSTALL_KEY], undefined);
  assert.equal(wrongOrigin.document.listeners.size, 0);
  assert.equal(wrongOrigin.context.window.URL, LegacyURL);

  const child = makeWindow({ topLevel: false });
  installPageCompat(child);
  assert.equal(child.context[INSTALL_KEY], undefined);
  assert.equal(child.document.listeners.size, 0);
  assert.equal(child.context.window.URL, LegacyURL);
}

{
  // A failed page-world installation must not consume the one-shot gate.
  let failWrapperRead = true;
  const failingURL = new Proxy(LegacyURL, {
    ownKeys(target) {
      if (failWrapperRead) {
        failWrapperRead = false;
        throw new Error('injected wrapper failure');
      }
      return Reflect.ownKeys(target);
    },
  });
  const retry = makeWindow({ url: failingURL });

  installPageCompat(retry);
  assert.equal(retry.context[INSTALL_KEY], undefined);
  assert.equal(retry.document.listeners.size, 0);

  installPageCompat(retry);
  assert.equal(retry.context[INSTALL_KEY].trustedOrigin, TRUSTED_ORIGIN);
  assert.equal(retry.document.count('pointerdown'), 1);
  assert.equal(retry.document.count('click'), 1);
  assert.equal(urlOf(retry, 'dsh-resource://file/x').hostname, 'file');
}

{
  const external = makeWindow();
  installPageCompat(external);

  const result = external.context.window.open(
    'https://example.com/path?q=1#section',
    '_blank',
    'noopener,noreferrer'
  );
  assert.equal(result, null);
  assert.equal(external.nativeOpened.length, 0);
  assert.equal(external.location.navigations.length, 1);
  assert.ok(external.location.navigations[0].startsWith(OPEN_EXTERNAL_URL_PREFIX));
  assert.equal(
    decodeURIComponent(
      external.location.navigations[0].slice(OPEN_EXTERNAL_URL_PREFIX.length)
    ),
    'https://example.com/path?q=1#section'
  );
}

{
  const login = makeWindow();
  installPageCompat(login);

  const popup = login.context.window.open('about:blank', '_blank');
  assert.ok(popup);
  assert.equal(popup.closed, false);
  popup.opener = null;
  popup.location.replace('https://account.example.com/authorize?state=safe');
  assert.equal(login.nativeOpened.length, 0);
  assert.equal(login.location.navigations.length, 1);
  assert.equal(
    decodeURIComponent(
      login.location.navigations[0].slice(OPEN_EXTERNAL_URL_PREFIX.length)
    ),
    'https://account.example.com/authorize?state=safe'
  );
  popup.close();
  assert.equal(popup.closed, true);
}


{
  // Host message channel: when the injected `dshboxHost` object is present,
  // cross-origin and same-origin `_blank` destinations are handed to it as a
  // JSON frame; the document never navigates and no scheme path runs.
  const external = makeWindow();
  const hostMessages = [];
  external.context.dshboxHost = {
    postMessage(raw) { hostMessages.push(raw); },
  };
  installPageCompat(external);

  const result = external.context.window.open(
    'https://example.com/path?q=1#section',
    '_blank',
    'noopener,noreferrer'
  );
  assert.equal(result, null);
  assert.equal(external.nativeOpened.length, 0);
  assert.equal(external.location.navigations.length, 0);
  assert.deepEqual(JSON.parse(hostMessages[0]), {
    type: 'open-external',
    url: 'https://example.com/path?q=1#section',
  });

  // Same-origin `_blank` also goes through the channel: the native popup
  // cannot open inside the embedded application.
  external.context.window.open(TRUSTED_ORIGIN + '/session', '_blank');
  assert.deepEqual(JSON.parse(hostMessages[1]), {
    type: 'open-external',
    url: TRUSTED_ORIGIN + '/session',
  });

  // A same-origin unsupported scheme still goes to the native path: only
  // http(s) destinations are handed to the channel.
  external.context.window.open('data:text/plain,blocked', '_blank');
  assert.equal(hostMessages.length, 2);
  assert.equal(external.nativeOpened.length, 1);

  // The blank popup hands its eventual destination to the channel too.
  const popup = external.context.window.open('about:blank', '_blank');
  popup.location.replace('https://account.example.com/authorize?state=safe');
  assert.deepEqual(JSON.parse(hostMessages[2]), {
    type: 'open-external',
    url: 'https://account.example.com/authorize?state=safe',
  });
  assert.equal(external.location.navigations.length, 0);
}

{
  const popupTest = makeWindow();
  const hostMessages = [];
  popupTest.context.dshboxHost = {
    postMessage(raw) { hostMessages.push(raw); },
  };
  installPageCompat(popupTest);

  const popup = popupTest.context.window.open('about:blank', '_blank');
  assert.ok(popup);
  assert.equal(popup.closed, false);
  // `location.href` assignment on the pseudo popup follows the channel too.
  popup.location.href = 'https://account.example.com/next?step=2';
  assert.deepEqual(JSON.parse(hostMessages[0]), {
    type: 'open-external',
    url: 'https://account.example.com/next?step=2',
  });
  popup.close();
  assert.equal(popup.closed, true);
}

{
  const local = makeWindow();
  installPageCompat(local);

  const nativeResult = local.context.window.open(
    TRUSTED_ORIGIN + '/session',
    '_blank'
  );
  assert.equal(nativeResult.native, true);
  assert.equal(local.nativeOpened.length, 1);
  assert.equal(local.location.navigations.length, 0);

  const unsupported = local.context.window.open('data:text/plain,blocked', '_blank');
  assert.equal(unsupported.native, true);
  assert.equal(local.nativeOpened.length, 2);
}

{
  const manifestPath = path.join(
    rendererRoot,
    'gecko/src/main/assets/dsh-status-bridge/manifest.json'
  );
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const pageScript = manifest.content_scripts.find(
    (entry) => entry.world === 'MAIN'
  );
  assert.ok(pageScript);
  assert.equal(pageScript.run_at, 'document_start');
  assert.equal(pageScript.all_frames, false);
  assert.deepEqual(pageScript.js, [
    'page-compat-config.js',
    'dsh-page-compat.js',
  ]);

  const geckoBuild = fs.readFileSync(
    path.join(rendererRoot, 'gecko/build.gradle.kts'),
    'utf8'
  );
  assert.match(geckoBuild, /copyPageCompatAsset/);
  assert.match(geckoBuild, /addGeneratedSourceDirectory/);
  assert.match(
    geckoBuild,
    /dsh-renderer\/api\/src\/main\/assets\/dsh-renderer\/dsh-page-compat\.js/
  );
  assert.match(geckoBuild, /GeneratePageCompatConfig/);
  assert.match(geckoBuild, /DSH_DEFAULT_HOST/);
  assert.match(geckoBuild, /DSH_DEFAULT_PORT/);

  const constants = fs.readFileSync(
    path.join(sourceRoot, 'common/src/main/java/com/dshbox/app/common/Constants.kt'),
    'utf8'
  );
  const host = /DSH_DEFAULT_HOST\s*=\s*"([^"]+)"/.exec(constants)[1];
  const port = /DSH_DEFAULT_PORT\s*=\s*(\d+)/.exec(constants)[1];
  assert.deepEqual(pageScript.matches, [`http://${host}:${port}/*`]);

  const screen = fs.readFileSync(
    path.join(
      sourceRoot,
      'app/src/main/java/com/dshbox/app/ui/webview/DshWebViewScreen.kt'
    ),
    'utf8'
  );
  const action = /DSHBOX_ACTION_SETTINGS_DOCUMENT\s*=\s*"([^"]+)"/.exec(screen)[1];
  assert.match(script, new RegExp(
    `OPEN_SETTINGS_DOCUMENT_URL\\s*=\\s*'dshbox://${action}'`
  ));
  const externalAction =
    /DSHBOX_ACTION_OPEN_EXTERNAL\s*=\s*"([^"]+)"/.exec(screen)[1];
  assert.match(script, new RegExp(
    `OPEN_EXTERNAL_URL_PREFIX\\s*=\\s*'dshbox://${externalAction}\\?url='`
  ));

  const systemAdapter = fs.readFileSync(
    path.join(
      rendererRoot,
      'system/src/main/java/com/dshbox/dsh/renderer/system/SystemDshRendererAdapter.kt'
    ),
    'utf8'
  );
  assert.equal(
    (systemAdapter.match(/addDocumentStartJavaScript\s*\(/g) || []).length,
    1
  );
  assert.match(systemAdapter, /COMPAT_BOOTSTRAP_ASSET/);
  assert.match(systemAdapter, /PAGE_COMPAT_ASSET/);
  assert.equal(systemAdapter.includes('deferredCompatibilitySource'), false);
  assert.equal(systemAdapter.includes('installDeferredCompatibility'), false);

  const mobileClient = fs.readFileSync(
    path.join(
      sourceRoot,
      'plugin-manager/dshbox-plugins/assets/plugins/dsh-mobile-adapt/plugin/lib/client.js'
    ),
    'utf8'
  );
  for (const removed of [
    'installUrlHostCompat',
    'installOpenDocumentShim',
    'OPEN_DOC_LABELS',
    'isOpenDocButton',
  ]) {
    assert.doesNotMatch(mobileClient, new RegExp(removed));
  }
  assert.doesNotMatch(mobileClient, /dsh-resource:\/\//);
  assert.doesNotMatch(mobileClient, /dshbox:\/\/open-settings-document/);
  assert.match(mobileClient, /function bootLog\(msg\)/);

  const diagnosticSources = [
    fs.readFileSync(
      path.join(sourceRoot, 'app/src/main/java/com/dshbox/app/ui/webview/DshRendererReportCard.kt'),
      'utf8'
    ),
    fs.readFileSync(
      path.join(sourceRoot, 'app/src/main/java/com/dshbox/app/dsh/DshDiagnosticLogs.kt'),
      'utf8'
    ),
  ];
  const resourceRoot = path.join(sourceRoot, 'app/src/main/res');
  for (const name of fs.readdirSync(resourceRoot)) {
    if (!name.startsWith('values')) continue;
    const strings = path.join(resourceRoot, name, 'strings.xml');
    if (fs.existsSync(strings)) diagnosticSources.push(fs.readFileSync(strings, 'utf8'));
  }
  for (const source of diagnosticSources) {
    assert.doesNotMatch(source, /\b0\.2\.1(?:-[A-Za-z0-9]+)*\b/i);
    assert.doesNotMatch(source, /\brc[.-]?3\b/i);
  }
}

{
  // The DSH Enter key switch: "new line" mode installs a capture-phase
  // keydown interceptor that stops a bare Enter on the DSH composer
  // editable (`data-composer-input="true"`, the field-proven marker) or —
  // as a fallback — on a Lexical-flagged contenteditable, and replays a
  // shift-keyed Enter on the same target; editable inner layers keep
  // climbing to the flagged root; textarea and non-composer editable
  // targets pass through untouched; visible menus (slash/@ mention
  // confirm) passthrough the key while hidden/collapsed menus do not;
  // modifier combos and IME composition pass through; a failed replay
  // falls back to ONE imperative insertLineBreak, whose own structural
  // effect decides whether the single insertText "\n" retry fires.
  //
  // The switch reads the mutable `__dshboxEnterNewline` global on every
  // key press (the host flips it via evaluateJavascript to push settings
  // changes without a reload); "send" mode at install time (or a config
  // without the field plus no global) installs nothing.
  class FakeKeyboardEvent {
    constructor(type, init = {}) {
      this.type = type;
      this.key = init.key || '';
      this.code = init.code || '';
      this.shiftKey = !!init.shiftKey;
      this.bubbles = !!init.bubbles;
      this.cancelable = !!init.cancelable;
    }
  }

  /*
   * Lexical editor: contenteditable [contenteditable="true"] with the
   * data-lexical-editor="true" flag (self or ancestor).
   *
   * The synthetic tree mimics a real contenteditable where the
   * interceptor's structure fingerprint reads three surfaces:
   * `childElementCount` (direct element children), `querySelectorAll('br')`,
   * and `innerHTML.length`. `textContent` deliberately does NOT change when
   * a linebreak arrives: Lexical materializes its newline as a dedicated
   * `<br>` node that carries no text — exactly the behaviour the old
   * text-only fallback gate missed (it re-issued the insert on every
   * Enter). The fake reuses that real-world shape so the new fingerprint
   * gate is verified against the actual failure mode, not an easier one.
   *
   * Children are node descriptors: a text run is `{ text }`; a linebreak is
   * `{ br: true }` — it contributes `<br/>` (5 chars) to innerHTML, +1 to
   * the br tally, and NOTHING to textContent. `applyCommands` arms /
   * disarms whether execCommand invocations actually mutate the tree
   * (mirroring an engine that accepted but did not apply).
   */
  class FakeLexicalEditor {
    constructor() {
      this.dispatched = [];
      this.isContentEditable = true;
      this.parentElement = null;
      this.attributes = { 'data-lexical-editor': 'true' };
      this.children = [{ text: 'queued message' }];
      this.applyCommands = true;
    }

    getAttribute(name) {
      return Object.prototype.hasOwnProperty.call(this.attributes, name)
        ? this.attributes[name]
        : null;
    }

    closest(selector) {
      if (String(selector) === '[contenteditable="true"]') {
        return this.isContentEditable ? this : null;
      }
      return null;
    }

    containsSelf(node) {
      let cursor = node;
      while (cursor) {
        if (cursor === this) return true;
        cursor = cursor.parentElement;
      }
      return false;
    }

    get textContent() {
      return this.children.map((child) => child.text || '').join('');
    }

    get childElementCount() {
      // Each child models an inline layer (span wrapper or br); the
      // fingerprint only needs its surfaces to move together.
      return this.children.length;
    }

    get innerHTML() {
      return this.children
        .map((child) => (child.br ? '<br/>' : '<span>' + child.text + '</span>'))
        .join('');
    }

    querySelectorAll(selector) {
      if (String(selector) === 'br') {
        return this.children.filter((child) => child.br);
      }
      return [];
    }

    dispatchEvent(event) {
      this.dispatched.push(event);
      // Real interceptor semantics: when Lexical handles the replayed
      // shift-keyed Enter it inserts a linebreak — a `<br>` element whose
      // text is empty, so textContent stays the same while structure
      // grows. Settable to false per-case to exercise the fallback path.
      if (this.replayHandled) {
        this.insertLinebreak();
      }
      return true;
    }

    // Mirrors execCommand('insertLineBreak') landing: a <br> child appears.
    insertLinebreak() {
      this.children.push({ br: true });
    }

    // Mirrors execCommand('insertText', '\n') landing: a newline text node
    // appears (innerHTML grows by the escaped run, no <br>).
    insertNewlineText() {
      this.children.push({ text: '\n' });
    }
  }

  /*
   * An inner node (Lexical paragraph wrapper / span) inside the editor.
   * Contenteditability inherits down from the root in real browsers, so
   * this layer is ALSO `isContentEditable: true` — the interceptor must
   * keep climbing past it to the flagged root. The previous gate returned
   * false at the first editable-but-unflagged ancestor, dropping keys
   * aimed at caret positions inside a Lexical paragraph; the test locks
   * the corrected climb.
   */
  function innerNode(editor) {
    return {
      isContentEditable: true,
      closest: (selector) => (
        String(selector) === '[contenteditable="true"]' ? editor : null
      ),
      parentElement: editor,
    };
  }

  // A `<textarea rows=1>` (DSH queue-message InlineEditor legacy shape):
  // no JS-level Shift+Enter handler, so intercepting would swallow Enter.
  class FakeTextarea {
    constructor() {
      this.dispatched = [];
      this.isContentEditable = false;
      this.parentElement = null;
    }

    closest(selector) {
      return String(selector) === 'textarea' ? this : null;
    }

    dispatchEvent(event) { this.dispatched.push(event); return true; }
  }

  // A plain (non-Lexical) contenteditable: untouched by the interceptor.
  class FakePlainEditable {
    constructor() {
      this.dispatched = [];
      this.isContentEditable = true;
      this.parentElement = null;
      this.attributes = {};
    }

    getAttribute(name) {
      return Object.prototype.hasOwnProperty.call(this.attributes, name)
        ? this.attributes[name]
        : null;
    }

    closest(selector) {
      return String(selector) === '[contenteditable="true"]' ? this : null;
    }

    dispatchEvent(event) { this.dispatched.push(event); return true; }
  }

  // A plain single-line input matches neither editable selector, so it is
  // never intercepted.
  class FakeInput {
    closest(selector) {
      return String(selector).split(',').map((part) => part.trim())
        .includes('input') ? this : null;
    }
  }

  /*
   * A menu candidate node: only surfaceable ones passthrough Enter. The
   * interceptor gates on (in order): inline `hidden`,
   * `aria-hidden="true"`, Radix `data-state="closed"`, then computed
   * `display:none` / `visibility:hidden` on the node or ANY ancestor.
   * `displayValue` feeds the display style read; `visibilityValue`
   * overrides the visibility style read; `collapsedAncestor` chains a
   * parent object (with its own `displayValue`) so the ancestor-walk
   * branch is exercised.
   */
  class FakeMenu {
    constructor(displayValue, attrs = {}) {
      this.nodeType = 1;
      this.hidden = false;
      this.displayValue = displayValue;
      this.visibilityValue = attrs.visibilityValue;
      this.parentElement = attrs.collapsedAncestor || null;
      this.attributes = { role: attrs.role, 'data-radix-wrapper': attrs['data-radix-wrapper'] };
      if (attrs.hidden !== undefined) this.hidden = attrs.hidden;
      if (attrs['aria-hidden'] !== undefined) {
        this.attributes['aria-hidden'] = attrs['aria-hidden'];
      }
      if (attrs['data-state'] !== undefined) {
        this.attributes['data-state'] = attrs['data-state'];
      }
    }

    // The interceptor reads collapse state through getAttribute; without
    // this the attribute gates (aria-hidden / data-state) cannot see the
    // menu's own state and the variants collapse only via style reads.
    getAttribute(name) {
      return Object.prototype.hasOwnProperty.call(this.attributes, name)
        ? this.attributes[name]
        : null;
    }
  }

  class EnterDocument extends FakeDocument {
    constructor() {
      super();
      this.menuNodes = [];
      this.execCommands = [];
      this.focusEditor = null;
      this.documentElement = { contains() { return true; } };
    }

    setMenus(menus) {
      this.menuNodes = menus;
    }

    getComputedStyle(node) {
      return {
        get display() {
          if (!node) return 'block';
          if (node.displayValue !== undefined) return node.displayValue;
          return 'block';
        },
        get visibility() {
          if (node && node.visibilityValue !== undefined) {
            return node.visibilityValue;
          }
          return 'visible';
        },
      };
    }

    execCommand(command, showDefault, value) {
      this.execCommands.push({ command, value });
      // Apply the imperative edit to the focused editor (the last target
      // the interceptor replayed against) when the command lands on the
      // synthetic tree: insertLineBreak pushes a `<br>` child,
      // insertText pushes a newline text node. Cases modelling an
      // ineffective engine disarm `applyCommands` so nothing mutates —
      // the interceptor then sees an unchanged fingerprint past each
      // command and proceeds down its ladder exactly once.
      const target = this.focusTarget;
      if (target && target.applyCommands) {
        if (command === 'insertLineBreak' && target.insertLinebreak) {
          target.insertLinebreak();
        } else if (command === 'insertText' && target.insertNewlineText) {
          target.insertNewlineText();
        }
      }
      return true;
    }

    set focusTarget(editor) {
      this.focusEditor = editor;
    }

    get focusTarget() {
      return this.focusEditor || null;
    }

    querySelectorAll(selector) {
      const menus = this.menuNodes;
      if (selector === '[role="menu"]') {
        return menus.filter((node) => node.attributes.role === 'menu');
      }
      if (selector === '[role="listbox"]') {
        return menus.filter((node) => node.attributes.role === 'listbox');
      }
      if (selector === '[data-radix-popper-content-wrapper]') {
        return menus.filter((node) => node.attributes['data-radix-wrapper'] === true);
      }
      return [];
    }
  }

  function makeEnterHarness(enterKeyNewline, documentOverride) {
    const harness = makeWindow({ url: NodeURL });
    const sandbox = harness.context;
    if (documentOverride) sandbox.document = documentOverride;
    sandbox.KeyboardEvent = FakeKeyboardEvent;
    sandbox.setTimeout = (fn) => 0;
    sandbox[CONFIG_KEY] = enterKeyNewline === undefined
      ? Object.freeze({ trustedOrigin: TRUSTED_ORIGIN })
      : Object.freeze({
        trustedOrigin: TRUSTED_ORIGIN,
        enterKeyNewline,
      });
    vm.runInContext(script, harness.context);
    // The override replaces the context's `document` binding; report the
    // document the installed handlers actually target, not the base one.
    if (documentOverride) harness.document = documentOverride;
    return harness;
  }

  function keyEvent(target, properties = {}) {
    return {
      target,
      key: 'Enter',
      keyCode: 13,
      shiftKey: false,
      ctrlKey: false,
      metaKey: false,
      altKey: false,
      isComposing: false,
      defaultPrevented: false,
      immediateStopped: false,
      preventDefault() { this.defaultPrevented = true; },
      stopImmediatePropagation() { this.immediateStopped = true; },
      ...properties,
    };
  }

  // ── Lexical editor target is intercepted and replayed ──
  const enabled = makeEnterHarness(true, new EnterDocument());
  assert.equal(enabled.document.count('keydown'), 1);

  const editor = new FakeLexicalEditor();
  editor.replayHandled = true;
  const inner = innerNode(editor);
  // The emitter returns the event object actually walked through the
  // listeners; assertions go against it, not the helper-built input.
  const bare = enabled.document.emit('keydown', inner, keyEvent(inner));
  assert.equal(bare.defaultPrevented, true);
  assert.equal(bare.immediateStopped, true);
  assert.equal(editor.dispatched.length, 1);
  const replay = editor.dispatched[0];
  assert.equal(replay.type, 'keydown');
  assert.equal(replay.key, 'Enter');
  assert.equal(replay.shiftKey, true);
  assert.equal(replay.bubbles, true);
  assert.equal(replay.cancelable, true);
  // A handled replay materializes a <br>: structure grows, text does not —
  // the exact real-world shape this interceptor must read.
  assert.equal(editor.textContent, 'queued message');
  assert.equal(editor.querySelectorAll('br').length, 1);

  // A contenteditable target that is itself the Lexical root also replays.
  const direct = new FakeLexicalEditor();
  direct.replayHandled = true;
  enabled.document.emit('keydown', direct, keyEvent(direct));
  assert.equal(direct.dispatched.length, 1);

  // ── Textarea and non-Lexical contenteditable are not intercepted ──
  const area = new FakeTextarea();
  const bareArea = enabled.document.emit('keydown', area, keyEvent(area));
  assert.equal(bareArea.defaultPrevented, false);
  assert.equal(area.dispatched.length, 0);

  const plain = new FakePlainEditable();
  const barePlain = enabled.document.emit('keydown', plain, keyEvent(plain));
  assert.equal(barePlain.defaultPrevented, false);
  assert.equal(plain.dispatched.length, 0);

  // ── Grace-window driver: fallback timers are captured here so each case
  //    fast-forwards synchronously and deterministically. ──
  enabled.context.setTimeout = (fn) => {
    enabled.context.__pendingFallbacks.push(fn);
    return 0;
  };
  enabled.context.__pendingFallbacks = [];

  // ── Fallback case A: replay succeeded and produced a <br>. The fallback
  //    is still scheduled (nothing about intercept order changed), but
  //    running it must NOT mutate: the <br> moved the structure
  //    fingerprint off the baseline, which the previous text-only gate
  //    mis-read as "nothing happened" and then duplicated on every Enter. ──
  const lucky = new FakeLexicalEditor();
  lucky.replayHandled = true;
  enabled.document.focusTarget = lucky;
  const luckyEvent = enabled.document.emit('keydown', lucky, keyEvent(lucky));
  assert.equal(luckyEvent.defaultPrevented, true);
  assert.equal(lucky.dispatched.length, 1);
  assert.equal(enabled.context.__pendingFallbacks.length, 1);
  const luckyExecBefore = enabled.document.execCommands.length;
  enabled.context.__pendingFallbacks.shift()();
  assert.equal(enabled.document.execCommands.length, luckyExecBefore);
  assert.equal(lucky.querySelectorAll('br').length, 1);
  assert.equal(lucky.textContent, 'queued message');

  // ── Fallback case B: replay ineffective; fallback issues exactly one
  //    insertLineBreak, and because that imperative insert lands (a <br>
  //    appears) the second-chance insertText gate stays closed. Net: one
  //    insertion, newline guaranteed, no duplicates. ──
  const stuck = new FakeLexicalEditor();
  stuck.replayHandled = false;
  enabled.document.focusTarget = stuck;
  const stuckEvent = enabled.document.emit('keydown', stuck, keyEvent(stuck));
  assert.equal(stuckEvent.defaultPrevented, true);
  assert.equal(stuck.dispatched.length, 1);
  assert.equal(enabled.context.__pendingFallbacks.length, 1);
  // Before the grace window runs out, nothing has mutated yet.
  assert.deepEqual(enabled.document.execCommands, []);
  // Fast-forward: fingerprint unchanged → insertLineBreak; a <br> lands →
  // fingerprint drifts → insertText never runs.
  enabled.context.__pendingFallbacks.shift()();
  assert.deepEqual(enabled.document.execCommands, [
    { command: 'insertLineBreak', value: null },
  ]);
  assert.equal(stuck.querySelectorAll('br').length, 1);
  assert.equal(stuck.textContent, 'queued message');

  // ── Fallback case C: replay ineffective AND insertLineBreak ineffective
  //    (engine accepts but does not apply). Only then does the single
  //    insertText "\n" retry fire; both commands appear exactly once —
  //    the "at most one extra insertion" contract locked in. ──
  const stubborn = new FakeLexicalEditor();
  stubborn.replayHandled = false;
  stubborn.applyCommands = false;
  // Per-case ledger: each fallback case checks the commands that ONE
  // grace-window run issued, so the previous case's commands are cleared
  // here rather than asserted against.
  enabled.document.execCommands = [];
  enabled.document.focusTarget = stubborn;
  enabled.document.emit('keydown', stubborn, keyEvent(stubborn));
  assert.equal(enabled.context.__pendingFallbacks.length, 1);
  enabled.context.__pendingFallbacks.shift()();
  assert.deepEqual(enabled.document.execCommands, [
    { command: 'insertLineBreak', value: null },
    { command: 'insertText', value: '\n' },
  ]);

  // ── Modifier combos and IME composition states pass through untouched. ──
  for (const properties of [
    { shiftKey: true },
    { ctrlKey: true },
    { metaKey: true },
    { altKey: true },
    { isComposing: true },
    { keyCode: 229 },
  ]) {
    const dispatches = editor.dispatched.length;
    const passed = enabled.document.emit('keydown', inner, keyEvent(inner, properties));
    assert.equal(editor.dispatched.length, dispatches);
    assert.equal(passed.defaultPrevented, false);
  }

  // ── A single-line input target is not intercepted. ──
  const input = new FakeInput();
  const untouched = enabled.document.emit('keydown', input, keyEvent(input));
  assert.equal(untouched.defaultPrevented, false);
  assert.equal(input.dispatched, undefined);

  // ── Visible open menus passthrough Enter so the slash/@ mention confirm
  //    keeps its native meaning. ──
  const menuHarness = makeEnterHarness(true, new EnterDocument());
  const menuEditor = new FakeLexicalEditor();
  menuEditor.replayHandled = true;
  menuHarness.document.setMenus([
    new FakeMenu('block', { role: 'menu', 'data-radix-wrapper': true }),
  ]);
  const menuBare = menuHarness.document.emit(
    'keydown', innerNode(menuEditor), keyEvent(menuEditor),
  );
  assert.equal(menuBare.defaultPrevented, false);
  assert.equal(menuEditor.dispatched.length, 0);

  const listboxHarness = makeEnterHarness(true, new EnterDocument());
  const listboxEditor = new FakeLexicalEditor();
  listboxEditor.replayHandled = true;
  listboxHarness.document.setMenus([
    new FakeMenu('block', { role: 'listbox' }),
  ]);
  const listboxBare = listboxHarness.document.emit(
    'keydown', innerNode(listboxEditor), keyEvent(listboxEditor),
  );
  assert.equal(listboxBare.defaultPrevented, false);
  assert.equal(listboxEditor.dispatched.length, 0);

  // ── Collapsed menus do NOT gate the interceptor: display:none, the
  //    inline `hidden` attribute, aria-hidden="true", visibility:hidden,
  //    Radix data-state="closed", and a display:none ANCESTOR all leave
  //    Enter inside the interceptor (Radix keeps its closed wrapper
  //    mounted in the tree — the case the ancestor walk covers). ──
  const hiddenMenuHarness = makeEnterHarness(true, new EnterDocument());
  const hiddenMenuEditor = new FakeLexicalEditor();
  hiddenMenuEditor.replayHandled = true;
  hiddenMenuHarness.document.setMenus([
    new FakeMenu('none', { role: 'menu' }),
  ]);
  const hiddenMenuBare = hiddenMenuHarness.document.emit(
    'keydown', innerNode(hiddenMenuEditor), keyEvent(hiddenMenuEditor),
  );
  assert.equal(hiddenMenuBare.defaultPrevented, true);
  assert.equal(hiddenMenuEditor.dispatched.length, 1);
  assert.equal(hiddenMenuEditor.dispatched[0].shiftKey, true);

  const collapsedHarness = makeEnterHarness(true, new EnterDocument());
  const collapsedEditor = new FakeLexicalEditor();
  collapsedEditor.replayHandled = true;
  collapsedHarness.document.setMenus([
    // hidden attribute
    new FakeMenu('block', { role: 'menu', hidden: true }),
    // aria-hidden
    new FakeMenu('block', { role: 'menu', 'aria-hidden': 'true' }),
    // Radix closed state
    new FakeMenu('block', { role: 'menu', 'data-state': 'closed' }),
    // computed visibility:hidden
    new FakeMenu('block', { role: 'menu', visibilityValue: 'hidden' }),
    // an ancestor with display:none hides the whole subtree
    new FakeMenu('block', {
      role: 'menu',
      collapsedAncestor: { nodeType: 1, displayValue: 'none' },
    }),
  ]);
  for (const collapsed of collapsedHarness.document.menuNodes) {
    const passed = collapsedHarness.document.emit(
      'keydown', innerNode(collapsedEditor), keyEvent(collapsedEditor),
    );
    assert.equal(passed.defaultPrevented, true,
      'a collapsed menu variant must not gate the interceptor');
  }
  // Five collapsed shapes, each intercepted (replayed) once.
  assert.equal(collapsedEditor.dispatched.length, 5);

  // ── Composer-attribute gate (field-proven marker): a target inside
  //    [data-composer-input="true"] is intercepted even when no Lexical
  //    flag exists on the chain — the exact live-DOM mismatch that the
  //    previous Lexical-only gate silently missed. ──
  function composerInnerNode(editor) {
    return {
      isContentEditable: true,
      // The caret rests on an inner layer INSIDE the composer element:
      // the climb must reach the flagged composer root.
      closest: (selector) => (
        String(selector) === '[contenteditable="true"]' ? editor : null
      ),
      parentElement: editor,
    };
  }

  class FakeComposerEditor {
    constructor() {
      this.dispatched = [];
      this.isContentEditable = true;
      this.parentElement = null;
      this.attributes = { 'data-composer-input': 'true' };
    }

    getAttribute(name) {
      return Object.prototype.hasOwnProperty.call(this.attributes, name)
        ? this.attributes[name]
        : null;
    }

    closest(selector) {
      return String(selector) === '[contenteditable="true"]' ? this : null;
    }

    dispatchEvent(event) { this.dispatched.push(event); return true; }
  }

  // The composer editor carries ONLY the composer flag (no data-lexical-
  // editor) and still gets intercepted — the regression the selector fix
  // targets.
  const composerHarness = makeEnterHarness(true, new EnterDocument());
  const composerEditor = new FakeComposerEditor();
  composerEditor.replayHandled = true;
  const composerInner = composerInnerNode(composerEditor);
  const composerBare = composerHarness.document.emit('keydown', composerInner, keyEvent(composerInner));
  assert.equal(composerBare.defaultPrevented, true,
    'a composer-flagged editable must be intercepted (field-proven gate)');
  assert.equal(composerEditor.dispatched.length, 1);
  assert.equal(composerEditor.dispatched[0].shiftKey, true);

  // An editor that carries BOTH flags (composer primary, lexical
  // fallback) still intercepts exactly once — no double replay.
  class FakeDualEditor extends FakeComposerEditor {
    constructor() {
      super();
      this.attributes['data-lexical-editor'] = 'true';
    }
  }
  const dualEditor = new FakeDualEditor();
  dualEditor.replayHandled = true;
  const dualBare = composerHarness.document.emit(
    'keydown', composerInnerNode(dualEditor), keyEvent(dualEditor),
  );
  assert.equal(dualBare.defaultPrevented, true);
  assert.equal(dualEditor.dispatched.length, 1);

  // ── A non-composer editable (neither composer nor Lexical markers)
  //    passes through, even though it is contenteditable. ──
  const plainEditableAgain = new FakePlainEditable();
  const plainPass = composerHarness.document.emit(
    'keydown', plainEditableAgain, keyEvent(plainEditableAgain),
  );
  assert.equal(plainPass.defaultPrevented, false,
    'an editable without composer/lexical markers must not be intercepted');
  assert.equal(plainEditableAgain.dispatched.length, 0);

  // ── Runtime switch: the mutable `__dshboxEnterNewline` global decides
  //    per key press, so a host can flip it between keys without a
  //    reload. The interceptor is installed once and reads the global on
  //    every key; "send" install states keep it dormant (no interception)
  //    until the host pushes the global. Both directions verified on the
  //    SAME harness. ──
  const runtimeHarness = makeEnterHarness(false, new EnterDocument());
  // Seeded from the install config when the host never pushes: config
  // false seeds the global to false.
  assert.strictEqual(runtimeHarness.context.__dshboxEnterNewline, false);
  // A composer key while dormant passes through untouched.
  const passiveEditor = new FakeComposerEditor();
  const passiveBare = runtimeHarness.document.emit(
    'keydown', composerInnerNode(passiveEditor), keyEvent(passiveEditor),
  );
  assert.equal(passiveBare.defaultPrevented, false,
    'dormant switch must leave Enter untouched');
  assert.equal(passiveEditor.dispatched.length, 0);

  // Host flips the global (evaluateJavascript writes
  // `window.__dshboxEnterNewline=true`) — interception turns ON for the
  // next key on the SAME page, no reload.
  runtimeHarness.context.__dshboxEnterNewline = true;
  const awakeEditor = new FakeComposerEditor();
  awakeEditor.replayHandled = true;
  const awakeBare = runtimeHarness.document.emit(
    'keydown', composerInnerNode(awakeEditor), keyEvent(awakeEditor),
  );
  assert.equal(awakeBare.defaultPrevented, true,
    'global flip to true must arm interception on the next key');
  assert.equal(awakeEditor.dispatched.length, 1);
  assert.equal(awakeEditor.dispatched[0].shiftKey, true);

  // And back off: flipping to false returns to native send behaviour.
  runtimeHarness.context.__dshboxEnterNewline = false;
  const offEditor = new FakeComposerEditor();
  const offBare = runtimeHarness.document.emit(
    'keydown', composerInnerNode(offEditor), keyEvent(offEditor),
  );
  assert.equal(offBare.defaultPrevented, false,
    'global=false must turn the interception off on the next key press');
  assert.equal(offEditor.dispatched.length, 0);

  // Field-absent config (gecko build-time config) seeds the switch to
  // false: dormant unless the host pushes the global.
  const absentHarness = makeEnterHarness(undefined);
  assert.strictEqual(absentHarness.context.__dshboxEnterNewline, false);

  // A host-seeded global (`window.__dshboxEnterNewline=true` before the
  // script runs) decides even when the config field is absent — the gecko
  // push path.
  const hostSeededHarness = makeEnterHarness(undefined, new EnterDocument());
  hostSeededHarness.context.__dshboxEnterNewline = true;
  const hostSeededEditor = new FakeComposerEditor();
  hostSeededEditor.replayHandled = true;
  const hostSeededBare = hostSeededHarness.document.emit(
    'keydown',
    composerInnerNode(hostSeededEditor),
    keyEvent(hostSeededEditor),
  );
  assert.equal(hostSeededBare.defaultPrevented, true,
    'a host-seeded global decides even without a config field');
  assert.equal(hostSeededEditor.dispatched.length, 1);
}

console.log(
  'dsh-page-compat checks passed: URL repair, bridge, host channel, manifest, single injection'
);
