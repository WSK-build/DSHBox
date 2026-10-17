const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname,
  '../../main/java/com/dshbox/browser/runtime/BrowserSameDocumentHistoryScript.kt'), 'utf8');

function template(name, endMarker) {
  const start = source.indexOf(`val ${name} = """`);
  assert.notEqual(start, -1, `missing template: ${name}`);
  const quote = source.indexOf('"""', start) + 3;
  const end = source.indexOf(endMarker, quote);
  assert.notEqual(end, -1, `missing template end: ${name}`);
  return source.slice(quote, end).replace(/^\r?\n/, '').replace(/\r?\n\s*$/, '')
    .split(/\r?\n/).map(line => line.replace(/^ {8}/, '')).join('\n');
}

function methodTemplate(name) {
  const start = source.indexOf(`fun ${name}`);
  assert.notEqual(start, -1, `missing method: ${name}`);
  const quote = source.indexOf('"""', start) + 3;
  const end = source.indexOf('""".trimIndent()', quote);
  assert.notEqual(end, -1, `missing method template end: ${name}`);
  return source.slice(quote, end).replace(/^\r?\n/, '').replace(/\r?\n\s*$/, '')
    .split(/\r?\n/).map(line => line.replace(/^ {8}/, '')).join('\n');
}

function browser() {
  const location = { href: 'https://fixture.test/start' };
  const listeners = new Map();
  const history = {
    stack: [location.href], index: 0,
    pushState(_state, _title, url) {
      this.stack.splice(this.index + 1);
      this.stack.push(new URL(url, location.href).href);
      this.index = this.stack.length - 1;
      location.href = this.stack[this.index];
    },
    replaceState(_state, _title, url) {
      this.stack[this.index] = new URL(url, location.href).href;
      location.href = this.stack[this.index];
    },
    back() {
      if (this.index > 0) location.href = this.stack[--this.index];
      if (!this.suppressPopstate) listeners.get('popstate')?.();
    },
  };
  const context = { location, history, addEventListener: (type, listener) => listeners.set(type, listener) };
  context.window = context;
  vm.createContext(context);
  vm.runInContext(template('install', '""".trimIndent()'), context);
  const target = () => JSON.parse(vm.runInContext(template('target', '""".trimIndent()'), context));
  const dispatch = (url, token, authorize = true) => vm.runInContext(
    methodTemplate('dispatchBack')
      .replaceAll('__EXPECTED_URL__', JSON.stringify(url))
      .replaceAll('__TOKEN__', JSON.stringify(token))
      .replaceAll('__AUTHORIZATION_NONCE__', JSON.stringify('nonce-1')), context);
  const verify = (url, token) => vm.runInContext(
    methodTemplate('verifyBack')
      .replaceAll('__EXPECTED_URL__', JSON.stringify(url))
      .replaceAll('__TOKEN__', JSON.stringify(token)), context);
  context.__dshBrowserActionGate = { authorize: () => authorizeNext };
  let authorizeNext = true;
  return { context, history, target, dispatch, verify, emitPopstate: () => listeners.get('popstate')?.(),
    setAuthorize: value => { authorizeNext = value; } };
}

test('tracked pushState gives a directional back target and confirms the traversal', () => {
  const browserPage = browser();
  browserPage.context.history.pushState({ route: 'next' }, '', '?route=next');
  assert.deepEqual(browserPage.target(), {
    known: true, state: 'target', index: 0, url: 'https://fixture.test/start',
  });
  assert.equal(browserPage.dispatch('https://fixture.test/start', 'back-1'), true);
  assert.equal(browserPage.context.location.href, 'https://fixture.test/start');
  assert.equal(browserPage.verify('https://fixture.test/start', 'back-1'), true);
});

test('replaceState changes the current entry without manufacturing back history', () => {
  const browserPage = browser();
  browserPage.context.history.replaceState({ route: 'replace' }, '', '?route=replaced');
  assert.deepEqual(browserPage.target(), { known: true, state: 'no_history' });
});

test('repeated identical URLs remain separate entries and a wrong target is rejected', () => {
  const browserPage = browser();
  browserPage.context.history.pushState({}, '', '?route=same');
  browserPage.context.history.pushState({}, '', '?route=same');
  assert.equal(browserPage.target().index, 1);
  assert.equal(browserPage.dispatch('https://fixture.test/wrong', 'bad'), false);
  assert.equal(browserPage.dispatch('https://fixture.test/start?route=same', 'back-2'), true);
  assert.equal(browserPage.verify('https://fixture.test/start?route=same', 'back-2'), true);
});

test('unknown ledger, including unhooked history mutation, fails closed', () => {
  const page = browser();
  page.context.history.pushState({}, '', '?known=1');
  page.context.history.pushState = function(...args) { return page.history.pushState.apply(this, args); };
  assert.equal(page.target().known, false);
  assert.equal(page.dispatch('https://fixture.test/start?known=1', 'unknown'), false);
});

test('no back target stays fail-closed even when the browser has forward history', () => {
  const page = browser();
  page.history.pushState({}, '', '?forward');
  page.history.back();
  assert.equal(page.target().state, 'no_history');
  assert.equal(page.history.index, 0);
});

test('dispatch acceptance is not traversal success until matching popstate arrives', () => {
  const page = browser();
  page.context.history.pushState({}, '', '?route=next');
  page.history.suppressPopstate = true;
  assert.equal(page.dispatch('https://fixture.test/start', 'await-pop'), true);
  assert.equal(page.verify('https://fixture.test/start', 'await-pop'), false);
  page.history.suppressPopstate = false;
  page.emitPopstate();
  assert.equal(page.verify('https://fixture.test/start', 'await-pop'), true);
});

test('revoked action gate denies dispatch without causing back side effects', () => {
  const page = browser();
  page.context.history.pushState({}, '', '?route=next');
  page.setAuthorize(false);
  assert.equal(page.dispatch('https://fixture.test/start', 'revoked'), false);
  assert.equal(page.history.index, 1);
  assert.equal(page.verify('https://fixture.test/start', 'revoked'), false);
});
