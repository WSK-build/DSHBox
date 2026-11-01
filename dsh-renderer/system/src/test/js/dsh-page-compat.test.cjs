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

console.log(
  'dsh-page-compat checks passed: URL repair, bridge, manifest, single injection'
);
