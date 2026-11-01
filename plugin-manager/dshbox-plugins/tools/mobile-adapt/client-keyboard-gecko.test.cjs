'use strict'

// Standalone Node test for the Gecko-only top-bar pinning in dsh-mobile-adapt.
// Runs the real plugin client.js in a minimal browser stub (no jsdom dep):
//   - UA switching (Gecko vs System WebView Chrome/128)
//   - fake visualViewport with adjustable height/offsetTop
//   - tiny DOM enough for findTopBar() + installGeckoTopBarPin()
//
// Verify: node tools/mobile-adapt/client-keyboard-gecko.test.cjs

const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const CLIENT_JS = path.resolve(__dirname, '..', '..', 'assets', 'plugins', 'dsh-mobile-adapt', 'plugin', 'lib', 'client.js')

// ---- minimal element ----
function makeEl(tag) {
  const el = {
    tagName: (tag || 'div').toUpperCase(),
    style: {},
    children: [],
    parentNode: null,
    _attrs: {},
    _listeners: {},
    classList: {
      _s: new Set(),
      add(c) { this._s.add(c) },
      remove(c) { this._s.delete(c) },
      contains(c) { return this._s.has(c) },
    },
    setAttribute(k, v) { this._attrs[k] = String(v) },
    getAttribute(k) { return Object.prototype.hasOwnProperty.call(this._attrs, k) ? this._attrs[k] : null },
    addEventListener(t, f) { (this._listeners[t] = this._listeners[t] || []).push(f) },
    removeEventListener() {},
    closest(selector) {
      let node = this
      while (node) {
        if (selector === '[data-composer-input], [data-composer-card]') {
          if (node.getAttribute('data-composer-input') !== null ||
              node.getAttribute('data-composer-card') !== null) return node
        }
        node = node.parentNode
      }
      return null
    },
    matches() { return false },
    contains() { return false },
    querySelector(sel) { return this.__q ? this.__q(sel) : null },
    querySelectorAll() { return [] },
    appendChild(c) { c.parentNode = this; this.children.push(c); return c },
    removeChild(c) {
      const i = this.children.indexOf(c)
      if (i >= 0) this.children.splice(i, 1)
      if (c) c.parentNode = null
      return c
    },
    getBoundingClientRect() {
      // 真实浏览器 rect 含 transform 位移；顶栏自然位置由 __naturalTop 模拟
      // （键盘弹起后为负值 = 被滚出可视区）。
      const m = /translateY\((-?[\d.]+)px\)/.exec(this.style.transform || '')
      const ty = m ? parseFloat(m[1]) : 0
      const naturalTop = typeof this.__naturalTop === 'number' ? this.__naturalTop : 0
      return { top: naturalTop + ty, bottom: naturalTop + ty + 100, left: 0, right: 100, height: 100 }
    },
    get firstElementChild() { return this.children[0] || null },
    get isConnected() { return true },
    get offsetTop() { return 0 },
  }
  return el
}

// ---- shared browser globals (configured per test) ----
let win, doc, nav, store, vv

function freshVisualViewport(height) {
  return {
    height: height,
    offsetTop: 0,
    _listeners: {},
    addEventListener(t, f) { (this._listeners[t] = this._listeners[t] || []).push(f) },
    removeEventListener() {},
    _emit(t) { (this._listeners[t] || []).forEach((f) => f()) },
  }
}

function setupGlobals(opts) {
  opts = opts || {}
  const ua = opts.ua || 'Mozilla/5.0 (Android 14; Mobile) Gecko/158.0 Firefox/158.0'
  const innerHeight = opts.innerHeight || 800
  const vvHeight = typeof opts.vvHeight === 'number' ? opts.vvHeight : innerHeight

  store = {
    _m: {},
    getItem(k) { return Object.prototype.hasOwnProperty.call(this._m, k) ? this._m[k] : null },
    setItem(k, v) { this._m[k] = String(v) },
    removeItem(k) { delete this._m[k] },
  }
  nav = { userAgent: ua, language: 'zh-CN' }
  vv = freshVisualViewport(vvHeight)

  const documentElement = makeEl('html')
  const body = makeEl('body')
  const documentListeners = {}
  doc = {
    readyState: 'complete',
    documentElement,
    body,
    _q: opts.documentQuery || null,
    querySelector(sel) { return this._q ? this._q(sel) : null },
    querySelectorAll() { return [] },
    getElementById() { return null },
    activeElement: body,
    addEventListener(t, f) { (documentListeners[t] = documentListeners[t] || []).push(f) },
    removeEventListener() {},
    emit(t, event) { (documentListeners[t] || []).forEach((f) => f(event || {})) },
    createElement(tag) { return makeEl(tag) },
    dispatchEvent() {},
  }

  const windowListeners = {}
  win = {
    innerHeight,
    visualViewport: vv,
    navigator: nav,
    addEventListener(t, f) { (windowListeners[t] = windowListeners[t] || []).push(f) },
    removeEventListener() {},
    emit(t, event) { (windowListeners[t] || []).forEach((f) => f(event || {})) },
    requestAnimationFrame(f) { f(); return 1 },
    __dshGeckoTopBarPin: undefined,
    __dsh_kb_topbar_restore: undefined,
    __dsh_kb_topbar: undefined,
  }

  // expose as globals so the plain-script client.js resolves them
  global.window = win
  global.document = doc
  // navigator is a read-only getter on globalThis in Node 24; define it explicitly.
  Object.defineProperty(global, 'navigator', { value: nav, configurable: true, writable: true })
  global.localStorage = store
  global.HTMLElement = function HTMLElement() {}
  global.HTMLElement.prototype = { focus() {} }
  global.MutationObserver = function MutationObserver() {
    return { observe() {}, disconnect() {} }
  }
  win.MutationObserver = global.MutationObserver
  global.KeyboardEvent = function KeyboardEvent() {}
  global.getComputedStyle = function getComputedStyle() {
    return { overflowY: 'visible' }
  }
}

// Load client.js and return its exports (resets module cache each call).
function loadClient() {
  let captured = null
  win.__ModuleLoader__ = {
    load(spec) {
      captured = spec.factory(function () {})
    },
  }
  delete require.cache[require.resolve(CLIENT_JS)]
  require(CLIENT_JS)
  assert.ok(captured, 'client.js should register via __ModuleLoader__.load')
  return captured
}

// Build a centerCol + header DOM and a composer card; returns the registry.
function buildConversationDom() {
  const centerCol = makeEl('div')
  centerCol.setAttribute('class', 'FDk7aW_centerCol')
  const header = makeEl('header') // 对话/轨迹 横幅
  header.style.cssText = 'position:sticky;top:0px'
  header.__naturalTop = -120 // 键盘弹起后顶栏被 reflow 滚出 120px
  const messages = makeEl('div')
  messages.setAttribute('class', 'FDk7aW_scrollBody')
  const composer = makeEl('div')
  composer.setAttribute('data-composer-card', '')
  composer.setAttribute('class', 'XyZ_composerCard')
  centerCol.appendChild(header)
  centerCol.appendChild(messages)
  centerCol.appendChild(composer)
  // centerCol.querySelector('header') -> header；findTopBar 依赖此
  centerCol.__q = (sel) => (sel === 'header' ? header : null)
  return { centerCol, header, messages, composer, scrollBody: messages }
}

function setCenterColQuery(dom) {
  doc._q = (sel) => {
    if (sel.indexOf('centerCol') >= 0) return dom.centerCol
    if (sel.indexOf('scrollBody') >= 0) return dom.scrollBody
    return null
  }
}

// =====================================================================
test('isGecko(): Gecko UA true, System WebView (Chrome/128) UA false', () => {
  setupGlobals({ ua: 'Mozilla/5.0 (Android 14; Mobile) Gecko/158.0 Firefox/158.0' })
  const exp = loadClient()
  assert.equal(exp.__test.isGecko(), true)

  // 换 UA 后重新载入插件（isGecko 在调用时读 navigator.userAgent；此处隔离状态）。
  setupGlobals({ ua: 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36' })
  const exp2 = loadClient()
  assert.equal(exp2.__test.isGecko(), false)
})

test('findTopBar(): resolves <header> inside centerCol', () => {
  setupGlobals({})
  const exp = loadClient()
  const dom = buildConversationDom()
  setCenterColQuery(dom)
  assert.equal(exp.__test.findTopBar(), dom.header)
})

test('findTopBar(): returns null when centerCol has no <header> (no risky fallback)', () => {
  setupGlobals({})
  const exp = loadClient()
  const centerCol = makeEl('div')
  centerCol.setAttribute('class', 'FDk7aW_centerCol')
  const banner = makeEl('div') // 无 header 标签：不得退化为 firstElementChild
  const other = makeEl('div')
  centerCol.appendChild(banner)
  centerCol.appendChild(other)
  centerCol.__q = () => null
  doc._q = (sel) => (sel.indexOf('centerCol') >= 0 ? centerCol : null)
  assert.equal(exp.__test.findTopBar(), null)
})

test('findTopBar(): returns null when centerCol missing', () => {
  setupGlobals({})
  const exp = loadClient()
  doc._q = () => null
  assert.equal(exp.__test.findTopBar(), null)
})

test('keyboardReservedPadding(): accounts for Gecko visual viewport pan', () => {
  setupGlobals({})
  const exp = loadClient()
  assert.equal(
    exp.__test.keyboardReservedPadding(800, 400, 0),
    400,
    'layout-only shrink needs bottom padding'
  )
  assert.equal(
    exp.__test.keyboardReservedPadding(800, 400, 400),
    0,
    'fully panned visual viewport must not reserve keyboard space twice'
  )
  assert.equal(
    exp.__test.keyboardReservedPadding(800, 400, 100),
    300,
    'mixed shrink/pan reserves only the remaining layout area'
  )
})

// ---- scenario: full pin + restore cycle under Gecko ----
function geckoScenario(opts) {
  setupGlobals(Object.assign({ vvHeight: 800 }, opts))
  const exp = loadClient()
  const dom = buildConversationDom()
  setCenterColQuery(dom)
  exp.__test.installGeckoTopBarPin(() => true) // enabled
  // install() schedules apply via setTimeout(0); flush it.
  return { exp, dom }
}

function flushTimers() {
  return new Promise((r) => setTimeout(r, 0))
}

test('Gecko: top bar pinned when keyboard up, restored when keyboard down', async () => {
  const { dom } = geckoScenario()
  await flushTimers()
  // initial: keyboard down (vvHeight == innerHeight) -> not pinned
  assert.ok(!dom.header.style.transform, 'should not pin before keyboard up')

  // keyboard up: shrink visual viewport height
  vv.height = 400
  vv._emit('resize')
  assert.equal(dom.header.style.transform, 'translateY(120px)', 'top bar visually lifted back by transform')
  assert.equal(dom.header.style.position, undefined, 'must stay in normal flow (no fixed)')
  assert.equal(dom.header.style.zIndex, undefined, 'must not raise stacking level')
  assert.equal(win.__dsh_kb_topbar, dom.header)

  // keyboard down again
  vv.height = 800
  vv._emit('resize')
  assert.equal(dom.header.style.transform, '', 'transform cleared when keyboard down')
  assert.equal(win.__dsh_kb_topbar, null)
})

test('Gecko: original inline style preserved exactly on restore (no hard-coded overwrite)', async () => {
  const { dom } = geckoScenario()
  dom.header.style.position = 'sticky'
  dom.header.style.top = '0px'
  dom.header.style.zIndex = 'auto'
  await flushTimers()
  vv.height = 300
  vv._emit('resize')
  assert.equal(dom.header.style.transform, 'translateY(120px)')
  vv.height = 800
  vv._emit('resize')
  assert.equal(dom.header.style.transform, '', 'transform restored')
  assert.equal(dom.header.style.position, 'sticky', 'DSH original position untouched')
  assert.equal(dom.header.style.top, '0px', 'restored to DSH original top (sticky/0px)')
  assert.equal(dom.header.style.zIndex, 'auto', 'restored to DSH original zIndex')
})

test('Gecko: displacement recomputed when natural position changes', async () => {
  const { dom } = geckoScenario()
  await flushTimers()
  vv.height = 400
  vv._emit('resize')
  assert.equal(dom.header.style.transform, 'translateY(120px)')
  dom.header.__naturalTop = -60
  vv._emit('resize')
  assert.equal(dom.header.style.transform, 'translateY(60px)', 'offset recomputed when natural position changes')
})

test('Gecko: visual viewport offset is included when layout rect remains at zero', async () => {
  const { dom } = geckoScenario()
  dom.header.__naturalTop = 0
  await flushTimers()
  vv.height = 400
  vv.offsetTop = 180
  vv._emit('resize')
  assert.equal(
    dom.header.style.transform,
    'translateY(180px)',
    'panned visual viewport must not leave the header above the visible area'
  )
  assert.equal(
    dom.scrollBody.style.paddingBottom,
    '220px',
    'mixed shrink/pan reserves only the unrepresented layout area'
  )
  vv.offsetTop = 0
  vv._emit('resize')
  assert.equal(dom.header.style.transform, '')
})

test('Gecko: fully panned keyboard does not create trailing blank space', async () => {
  const { dom } = geckoScenario()
  dom.header.__naturalTop = 0
  await flushTimers()
  vv.height = 400
  vv.offsetTop = 400
  vv._emit('resize')
  assert.equal(dom.header.style.transform, 'translateY(400px)')
  assert.equal(
    dom.scrollBody.style.paddingBottom,
    '',
    'pan already represents the keyboard area and must not add a second reservation'
  )

  vv.height = 800
  vv.offsetTop = 0
  vv._emit('resize')
  assert.equal(dom.scrollBody.style.paddingBottom, '')
  assert.equal(dom.header.style.transform, '')
})

test('Gecko: composer focus pins even when visual and layout viewport stay equal', async () => {
  const { dom } = geckoScenario()
  const input = makeEl('div')
  input.setAttribute('data-composer-input', '')
  dom.composer.appendChild(input)
  await flushTimers()

  assert.equal(vv.height, 800)
  assert.equal(win.innerHeight, 800)
  doc.activeElement = input
  doc.emit('focusin', { target: input })
  assert.equal(
    dom.header.style.transform,
    'translateY(120px)',
    'composer focus must not depend on visualViewport height changing'
  )

  doc.activeElement = doc.body
  doc.emit('focusout', { target: input })
  await flushTimers()
  assert.equal(dom.header.style.transform, '', 'blur restores the original header')
})

test('Gecko: composer card untouched (coexists with composer fix)', async () => {
  const { dom } = geckoScenario()
  await flushTimers()
  vv.height = 400
  vv._emit('resize')
  assert.deepEqual(dom.composer.style, {}, 'composer card must not be styled by top-bar logic')
  assert.equal(dom.messages.style.position, undefined)
})

test('System WebView UA: zero DOM writes, zero listeners', () => {
  setupGlobals({ ua: 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36' })
  const exp = loadClient()
  const dom = buildConversationDom()
  setCenterColQuery(dom)
  exp.__test.installGeckoTopBarPin(() => true)
  // not Gecko -> install returns before attaching vv listeners / pinning DOM.
  // 仅 Gecko 才进入安装路径（含还原钩子）；System 分支严格零动作、零钩子。
  assert.equal(win.__dshGeckoTopBarPin, true, 'idempotency flag set')
  assert.equal(typeof win.__dsh_kb_topbar_restore, 'undefined', 'System: no restore hook installed')
  assert.ok(!dom.header.style.transform, 'System: top bar never transformed')
  assert.equal(Object.keys(vv._listeners).length, 0, 'System: no visualViewport listeners')
})

test('Plugin switch off: pin disabled and restored', async () => {
  setupGlobals({ vvHeight: 800 })
  const exp = loadClient()
  const dom = buildConversationDom()
  setCenterColQuery(dom)
  let enabled = true
  exp.__test.installGeckoTopBarPin(() => enabled)
  await flushTimers()
  vv.height = 400
  vv._emit('resize')
  assert.equal(dom.header.style.transform, 'translateY(120px)', 'pinned while enabled')

  // toggle off
  enabled = false
  vv._emit('resize')
  assert.equal(dom.header.style.transform, '', 'restored on switch off')
})

test('Idempotent: repeated install does not stack listeners', () => {
  setupGlobals({})
  const exp = loadClient()
  const dom = buildConversationDom()
  setCenterColQuery(dom)
  exp.__test.installGeckoTopBarPin(() => true)
  const before = (vv._listeners.resize || []).length
  exp.__test.installGeckoTopBarPin(() => true) // second call must no-op
  const after = (vv._listeners.resize || []).length
  assert.equal(after, before, 'second install adds no extra visualViewport listener')
})

test('React replaces top bar: old restored, new pinned (no leak)', async () => {
  setupGlobals({})
  const exp = loadClient()
  const dom = buildConversationDom()
  setCenterColQuery(dom)
  exp.__test.installGeckoTopBarPin(() => true)
  await flushTimers()
  vv.height = 400
  vv._emit('resize')
  assert.equal(dom.header.style.transform, 'translateY(120px)')

  // React swaps the header element for a new one (same centerCol)
  const newHeader = makeEl('header')
  newHeader.style.cssText = 'position:sticky;top:0px'
  newHeader.__naturalTop = -120
  dom.centerCol.children[0] = newHeader
  dom.centerCol.__q = (sel) => (sel === 'header' ? newHeader : null)
  vv._emit('resize')

  assert.equal(dom.header.style.transform, '', 'old header restored')
  assert.equal(newHeader.style.transform, 'translateY(120px)', 'new header pinned')
})
