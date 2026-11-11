'use strict'

// Standalone Node test for the dsh-mobile-adapt **settings switch row** and
// **back-key layering** (DSH 0.2.2 native narrow layout refactor).
//
// Runs the real plugin client.js in a tiny DOM stub (no jsdom dep):
//   - a settings panel shaped like DSH 0.2.2's SettingsRoot
//     (dialog[role=dialog][aria-modal=true] > nav + header + options)
//   - a session-log row shaped like dsh-client-ui-settings-session-log's
//     UploadRow (row > div > title + description, plus button[role=switch] > span)
//   - the native sidebar drawer state (frame[data-sidebar-drawer] /
//     [data-sidebar-collapsed])
//
// Verify: node tools/mobile-adapt/client-settings-switch-back.test.cjs

const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const fs = require('node:fs')

const PLUGIN_DIR = path.resolve(__dirname, '..', '..', 'assets', 'plugins', 'dsh-mobile-adapt', 'plugin')
const CLIENT_JS = path.join(PLUGIN_DIR, 'lib', 'client.js')
const PKG_JSON = path.join(PLUGIN_DIR, 'package.json')

// =====================================================================
// Tiny selector engine (tag / #id / .class / [attr] / [attr=v] / [attr*=v]
// / :not(simple) / :scope ; combinators: descendant and child).
// =====================================================================
function tokenizeComplex(sel) {
  const steps = []
  let buf = ''
  let comb = null
  let i = 0
  const flush = () => {
    if (buf) { steps.push({ comb, compound: parseCompound(buf) }); buf = '' }
  }
  while (i < sel.length) {
    const ch = sel[i]
    if (ch === '[') {
      let depth = 0
      let j = i
      for (; j < sel.length; j++) {
        if (sel[j] === '[') depth++
        else if (sel[j] === ']') { depth--; if (depth === 0) break }
        buf += sel[j]
      }
      buf += sel[j]
      i = j + 1
      continue
    }
    if (ch === ':' && sel.slice(i, i + 5) === ':not(') {
      let depth = 0
      let j = i + 4
      for (; j < sel.length; j++) {
        if (sel[j] === '(') depth++
        else if (sel[j] === ')') { depth--; if (depth === 0) break }
        buf += sel[j]
      }
      buf += sel[j]
      i = j + 1
      continue
    }
    if (ch === ' ') {
      flush()
      comb = 'descendant'
      while (i < sel.length && sel[i] === ' ') i++
      continue
    }
    if (ch === '>') {
      flush()
      comb = 'child'
      i++
      while (i < sel.length && sel[i] === ' ') i++
      continue
    }
    buf += ch
    i++
  }
  flush()
  return steps
}

function parseCompound(s) {
  const c = { tag: null, id: null, classes: [], attrs: [], not: [], scope: false }
  let i = 0
  while (i < s.length) {
    const ch = s[i]
    if (ch === '*') { i++; continue }
    if (/[a-zA-Z]/.test(ch)) {
      let j = i
      while (j < s.length && /[a-zA-Z0-9_-]/.test(s[j])) j++
      c.tag = s.slice(i, j).toUpperCase()
      i = j
      continue
    }
    if (ch === '[') {
      let depth = 0
      let j = i
      for (; j < sel_len(s); j++) {
        if (s[j] === '[') depth++
        else if (s[j] === ']') { depth--; if (depth === 0) break }
      }
      const body = s.slice(i + 1, j)
      const m = /^([^~^$*|=\s]+)\s*(?:([~^$*|]?=)\s*("?)([^\]]*?)\3)?$/.exec(body)
      if (m) {
        c.attrs.push({ name: m[1], op: m[2] || null, value: m[4] })
      }
      i = j + 1
      continue
    }
    if (ch === '.') {
      let j = i + 1
      while (j < s.length && /[a-zA-Z0-9_-]/.test(s[j])) j++
      c.classes.push(s.slice(i + 1, j))
      i = j
      continue
    }
    if (ch === '#') {
      let j = i + 1
      while (j < s.length && /[a-zA-Z0-9_-]/.test(s[j])) j++
      c.id = s.slice(i + 1, j)
      i = j
      continue
    }
    if (ch === ':') {
      if (s.slice(i, i + 6) === ':scope') { c.scope = true; i += 6; continue }
      if (s.slice(i, i + 5) === ':not(') {
        let depth = 0
        let j = i + 4
        for (; j < s.length; j++) {
          if (s[j] === '(') depth++
          else if (s[j] === ')') { depth--; if (depth === 0) break }
        }
        c.not.push(parseCompound(s.slice(i + 5, j)))
        i = j + 1
        continue
      }
      i++
      continue
    }
    i++
  }
  return c
}

function sel_len(s) { return s.length }

function matchCompound(el, c, scopeEl) {
  if (c.scope && el !== scopeEl) return false
  if (c.tag && el.tagName !== c.tag) return false
  if (c.id && el.getAttribute('id') !== c.id) return false
  for (const cls of c.classes) if (!el.classList.contains(cls)) return false
  for (const a of c.attrs) {
    const v = el.getAttribute(a.name)
    if (v === null) return false
    if (a.op === '=' && v !== a.value) return false
    if (a.op === '*=' && v.indexOf(a.value) < 0) return false
    if (a.op === '^=' && v.indexOf(a.value) !== 0) return false
  }
  for (const n of c.not) if (matchCompound(el, n, scopeEl)) return false
  return true
}

function matchComplex(el, steps, scopeEl) {
  const n = steps.length - 1
  if (!matchCompound(el, steps[n].compound, scopeEl)) return false
  let node = el
  for (let i = n - 1; i >= 0; i--) {
    const rel = steps[i + 1].comb || 'descendant'
    if (rel === 'child') {
      const p = node.parentNode
      if (!p || !matchCompound(p, steps[i].compound, scopeEl)) return false
      node = p
    } else {
      let p = node.parentNode
      let found = null
      while (p) {
        if (matchCompound(p, steps[i].compound, scopeEl)) { found = p; break }
        p = p.parentNode
      }
      if (!found) return false
      node = found
    }
  }
  return true
}

const selectorCache = new Map()
function compile(sel) {
  let parts = selectorCache.get(sel)
  if (!parts) {
    parts = sel.split(',').map((s) => s.trim()).filter(Boolean).map(tokenizeComplex)
    selectorCache.set(sel, parts)
  }
  return parts
}

// =====================================================================
// Tiny DOM
// =====================================================================
class El {
  constructor(tag, doc) {
    this.tagName = String(tag || 'div').toUpperCase()
    this.ownerDocument = doc || null
    this.attrs = Object.create(null)
    this.childNodes = []
    this.parentNode = null
    this.style = {}
    this.dataset = {}
    this._text = ''
    this._listeners = Object.create(null)
    this._classes = new Set()
    this._root = false
  }

  get children() { return this.childNodes.filter((n) => n instanceof El) }
  get firstElementChild() { return this.children[0] || null }
  get lastElementChild() { const c = this.children; return c[c.length - 1] || null }
  get isConnected() {
    let node = this
    while (node) {
      if (node._root) return true
      node = node.parentNode
    }
    return false
  }

  get classList() {
    const self = this
    return {
      add(c) { self._classes.add(c) },
      remove(c) { self._classes.delete(c) },
      contains(c) { return self._classes.has(c) },
      toggle(c, on) { if (on === undefined) { self._classes.has(c) ? self._classes.delete(c) : self._classes.add(c) } else if (on) self._classes.add(c); else self._classes.delete(c) },
    }
  }

  setAttribute(name, value) {
    this.attrs[name] = String(value)
    if (name === 'class') {
      this._classes = new Set(String(value).split(/\s+/).filter(Boolean))
    }
    if (name.startsWith('data-')) {
      const key = name.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase())
      this.dataset[key] = String(value)
    }
  }
  getAttribute(name) {
    return Object.prototype.hasOwnProperty.call(this.attrs, name) ? this.attrs[name] : null
  }
  hasAttribute(name) { return Object.prototype.hasOwnProperty.call(this.attrs, name) }
  removeAttribute(name) { delete this.attrs[name] }

  get className() { return this.getAttribute('class') || '' }
  set className(v) { this.setAttribute('class', v) }

  get textContent() {
    if (this.childNodes.length === 0) return this._text
    return this.childNodes.map((n) => (n instanceof El ? n.textContent : n._text || '')).join('')
  }
  set textContent(v) {
    this.childNodes = []
    this._text = String(v)
  }

  appendChild(child) {
    if (child.parentNode) child.parentNode.removeChild(child)
    child.parentNode = this
    this.childNodes.push(child)
    return child
  }
  insertBefore(child, ref) {
    if (child.parentNode) child.parentNode.removeChild(child)
    child.parentNode = this
    if (!ref) { this.childNodes.push(child); return child }
    const i = this.childNodes.indexOf(ref)
    if (i < 0) this.childNodes.push(child)
    else this.childNodes.splice(i, 0, child)
    return child
  }
  removeChild(child) {
    const i = this.childNodes.indexOf(child)
    if (i >= 0) this.childNodes.splice(i, 1)
    child.parentNode = null
    return child
  }
  contains(node) {
    let n = node
    while (n) { if (n === this) return true; n = n.parentNode }
    return false
  }
  cloneNode(deep) {
    const copy = new El(this.tagName, this.ownerDocument)
    for (const k of Object.keys(this.attrs)) copy.attrs[k] = this.attrs[k]
    copy._classes = new Set(this._classes)
    copy._text = this._text
    if (deep) for (const child of this.childNodes) copy.appendChild(child.cloneNode(true))
    return copy
  }

  descendants() {
    const out = []
    const walk = (node) => {
      for (const child of node.childNodes) {
        if (child instanceof El) { out.push(child); walk(child) }
      }
    }
    walk(this)
    return out
  }

  matches(sel) {
    return compile(sel).some((steps) => matchComplex(this, steps, null))
  }
  closest(sel) {
    let node = this
    while (node) {
      if (node.matches(sel)) return node
      node = node.parentNode
    }
    return null
  }
  querySelectorAll(sel) {
    const parts = compile(sel)
    return this.descendants().filter((el) => parts.some((steps) => matchComplex(el, steps, this)))
  }
  querySelector(sel) {
    const parts = compile(sel)
    for (const el of this.descendants()) {
      if (parts.some((steps) => matchComplex(el, steps, this))) return el
    }
    return null
  }

  addEventListener(type, fn) {
    (this._listeners[type] = this._listeners[type] || []).push(fn)
  }
  removeEventListener(type, fn) {
    const list = this._listeners[type]
    if (!list) return
    const i = list.indexOf(fn)
    if (i >= 0) list.splice(i, 1)
  }
  dispatchEvent(ev) {
    ev.target = ev.target || this
    let node = this
    while (node) {
      const list = node._listeners[ev.type]
      if (list) for (const fn of list.slice()) fn.call(node, ev)
      if (ev._stopped) break
      node = node.parentNode
    }
    return true
  }
  click() {
    this.dispatchEvent({
      type: 'click',
      target: this,
      defaultPrevented: false,
      preventDefault() { this.defaultPrevented = true },
      stopPropagation() { this._stopped = true },
      stopImmediatePropagation() { this._stopped = true },
    })
  }
}

// =====================================================================
// Browser globals
// =====================================================================
let win, doc

function setupGlobals(opts) {
  opts = opts || {}
  const store = {
    _m: {},
    getItem(k) { return Object.prototype.hasOwnProperty.call(this._m, k) ? this._m[k] : null },
    setItem(k, v) { this._m[k] = String(v) },
    removeItem(k) { delete this._m[k] },
  }

  const documentElement = new El('html')
  const head = new El('head')
  const body = new El('body')
  documentElement._root = true
  head._root = true
  body._root = true
  documentElement.appendChild(head)
  documentElement.appendChild(body)

  const docListeners = Object.create(null)
  doc = {
    readyState: 'complete',
    documentElement,
    head,
    body,
    activeElement: body,
    createElement(tag) { return new El(tag, doc) },
    querySelector(sel) { return documentElement.querySelector(sel) },
    querySelectorAll(sel) { return documentElement.querySelectorAll(sel) },
    getElementById(id) { return documentElement.querySelector('#' + id) },
    addEventListener(t, f) { (docListeners[t] = docListeners[t] || []).push(f) },
    removeEventListener() {},
    dispatchEvent(ev) {
      const list = docListeners[ev.type] || []
      for (const fn of list.slice()) fn(ev)
      return true
    },
    emit(t, event) { doc.dispatchEvent(Object.assign({ type: t }, event || {})) },
  }

  const windowListeners = Object.create(null)
  win = {
    innerHeight: 800,
    visualViewport: { height: 800, offsetTop: 0, addEventListener() {}, removeEventListener() {} },
    navigator: { userAgent: 'Mozilla/5.0 (Linux; Android 14) Chrome/128.0.0.0 Mobile', language: opts.lang || 'zh-CN' },
    addEventListener(t, f) { (windowListeners[t] = windowListeners[t] || []).push(f) },
    removeEventListener() {},
    emit(t, event) { (windowListeners[t] || []).forEach((f) => f(event || {})) },
    requestAnimationFrame(f) { f(); return 1 },
    history: {
      _stack: [{ dshboxBack: 0 }],
      pushState(state) { this._stack.push(state) },
      back() { if (this._stack.length > 1) this._stack.pop() },
    },
  }

  global.window = win
  global.document = doc
  Object.defineProperty(global, 'navigator', { value: win.navigator, configurable: true, writable: true })
  global.localStorage = store
  global.HTMLElement = function HTMLElement() {}
  global.HTMLElement.prototype = { focus() {} }
  global.MutationObserver = function MutationObserver() {
    return { observe() {}, disconnect() {} }
  }
  win.MutationObserver = global.MutationObserver
  global.KeyboardEvent = function KeyboardEvent() {}
  global.getComputedStyle = function getComputedStyle() { return { overflowY: 'visible' } }
  global.history = win.history
  return { store }
}

function loadClient() {
  let captured = null
  win.__ModuleLoader__ = {
    load(spec) { captured = spec.factory(function () {}) },
  }
  delete require.cache[require.resolve(CLIENT_JS)]
  require(CLIENT_JS)
  assert.ok(captured, 'client.js should register via __ModuleLoader__.load')
  return captured
}

// =====================================================================
// DOM fixtures shaped like DSH 0.2.2
// =====================================================================
/** 一个原生 switch（primitives.Switch）：button[role=switch] > span。 */
function makeSwitch(label) {
  const btn = new El('button')
  btn.setAttribute('type', 'button')
  btn.setAttribute('role', 'switch')
  btn.setAttribute('aria-checked', 'false')
  btn.setAttribute('aria-label', label)
  const thumb = new El('span')
  thumb.setAttribute('class', 'Pp_thumb')
  btn.appendChild(thumb)
  return btn
}

/** 一行原生设置行（UploadRow 形状）：div[class*=_row] > div > title + description。 */
function makeRow(titleText, descText, withSwitch) {
  const row = new El('div')
  row.setAttribute('class', 'gi5gtW_row')
  const left = new El('div')
  const title = new El('div')
  title.setAttribute('class', 'gi5gtW_title')
  title.textContent = titleText
  const desc = new El('div')
  desc.setAttribute('class', 'gi5gtW_description')
  desc.textContent = descText || ''
  left.appendChild(title)
  left.appendChild(desc)
  row.appendChild(left)
  if (withSwitch !== false) row.appendChild(makeSwitch(titleText))
  return row
}

/** 设置面板（SettingsRoot 形状）：dialog > navTitle + nav + header + options。 */
function makeSettingsDialog(options) {
  options = options || {}
  const dlg = new El('div')
  dlg.setAttribute('role', 'dialog')
  dlg.setAttribute('aria-modal', 'true')
  // 0.2.2 的设置面板带这个官方标记（见 dsh-client-ui-settings-general）。
  dlg.setAttribute('data-shortcut-modal', 'settings')
  dlg.setAttribute('class', 'VOzbGW_panel')

  const navTitle = new El('div')
  navTitle.setAttribute('class', 'VOzbGW_navTitle')
  const nav = new El('nav')
  nav.setAttribute('class', 'VOzbGW_nav')
  const header = new El('div')
  header.setAttribute('class', 'VOzbGW_header')
  const closeBtn = new El('button')
  closeBtn.setAttribute('type', 'button')
  closeBtn.setAttribute('class', 'VOzbGW_close')
  closeBtn.setAttribute('aria-label', 'Close')
  let closeCount = 0
  closeBtn.addEventListener('click', () => { closeCount++ })
  header.appendChild(closeBtn)
  const content = new El('div')
  content.setAttribute('class', 'VOzbGW_options')

  dlg.appendChild(navTitle)
  dlg.appendChild(nav)
  dlg.appendChild(header)
  dlg.appendChild(content)

  const rows = options.rows || []
  for (const row of rows) content.appendChild(row)

  return { dlg, content, nav, header, closeBtn, closeCount: () => closeCount }
}

/** 原生侧栏抽屉容器：frame[data-sidebar-drawer] (+ [data-sidebar-collapsed])。 */
function makeFrame(opts) {
  opts = opts || {}
  const frame = new El('div')
  frame.setAttribute('class', 'pI_x6G_frame')
  if (opts.drawer !== false) frame.setAttribute('data-sidebar-drawer', 'true')
  if (opts.collapsed) frame.setAttribute('data-sidebar-collapsed', 'true')
  return frame
}

/**
 * 原生抽屉侧栏（0.2.2 的 SidebarRoot）真实形状：
 *   role="dialog" + aria-modal="true"（drawer 展开时）
 *   + data-shortcut-modal="sidebar"
 *   + **直接子节点** <nav class="panelList">（随包 profile 的插件/定时任务面板
 *     必渲染，见 dsh-client-ui-sidebar 的 SidebarRoot）
 *   + foot 里的设置触发键
 * 抽屉若被误判成设置面板：返回键会兜底点 foot 的设置触发键（反而打开设置），
 * 开关行也会去抽屉里找锚点（手机窄屏下永远找不到 → 开关行不注入）。
 */
function makeSidebarDrawer() {
  const root = new El('div')
  root.setAttribute('role', 'dialog')
  root.setAttribute('aria-modal', 'true')
  root.setAttribute('data-shortcut-modal', 'sidebar')
  root.setAttribute('class', 'IW6AQa_root')

  const nav = new El('nav')
  nav.setAttribute('class', 'IW6AQa_panelList')
  nav.setAttribute('aria-label', 'Panels')
  const panelRow = new El('button')
  panelRow.setAttribute('class', 'IW6AQa_panelRow')
  panelRow.textContent = 'Plugins'
  nav.appendChild(panelRow)
  root.appendChild(nav)

  const foot = new El('div')
  foot.setAttribute('class', 'IW6AQa_footArea')
  const settingsTrigger = new El('button')
  settingsTrigger.setAttribute('class', 'IW6AQa_settingsTrigger')
  settingsTrigger.setAttribute('aria-label', 'Settings')
  let triggerClicks = 0
  settingsTrigger.addEventListener('click', () => { triggerClicks++ })
  foot.appendChild(settingsTrigger)
  root.appendChild(foot)

  return { root, nav, foot, settingsTrigger, triggerClicks: () => triggerClicks }
}

/** 抽屉所在的 frame：frame[data-sidebar-drawer] > sidebarCol > 抽屉根节点。 */
function makeDrawerFrame(drawer, opts) {
  const frame = makeFrame(opts)
  const col = new El('div')
  col.setAttribute('class', 'pI_x6G_sidebarCol')
  col.appendChild(drawer.root)
  frame.appendChild(col)
  return frame
}

// =====================================================================
// Tests
// =====================================================================
test('锚点：命中已知标题文案（会话日志行）', () => {
  setupGlobals()
  const exp = loadClient()
  const sessionLog = makeRow('Upload Session Log when using the official model API', 'Help improve DeepSeek models and products.')
  const other = makeRow('Some other switch', 'x')
  const s = makeSettingsDialog({ rows: [other, sessionLog] })
  doc.body.appendChild(s.dlg)
  assert.equal(exp.__test.findSessionLogRow(s.dlg), sessionLog, '应命中会话日志行而不是最后一个 switch 行')
})

test('锚点：中文标题同样命中', () => {
  setupGlobals()
  const exp = loadClient()
  const sessionLog = makeRow('在使用官方模型 API 时上传 Session Log', '帮助改进 DeepSeek 模型与产品')
  const other = makeRow('别的开关', 'x')
  const s = makeSettingsDialog({ rows: [sessionLog, other] })
  doc.body.appendChild(s.dlg)
  assert.equal(exp.__test.findSessionLogRow(s.dlg), sessionLog)
})

test('锚点回退：标题失配时取该面板最后一个带 switch 的行', () => {
  setupGlobals()
  const exp = loadClient()
  const first = makeRow('完全不相干的标题', 'x')
  const last = makeRow('另一个开关', 'y')
  const s = makeSettingsDialog({ rows: [first, last] })
  doc.body.appendChild(s.dlg)
  assert.equal(exp.__test.findSessionLogRow(s.dlg), last, '失配时回退到最后一个 switch 行')
})

test('锚点回退：没有带 switch 的行时不注入', () => {
  setupGlobals()
  const exp = loadClient()
  const plain = makeRow('只有文字的行', 'x', false)
  const s = makeSettingsDialog({ rows: [plain] })
  doc.body.appendChild(s.dlg)
  assert.equal(exp.__test.findSessionLogRow(s.dlg), null)
  assert.equal(exp.__test.ensureMobileRow({ isEnabled: () => true, onToggle: () => {} }), null)
  assert.equal(doc.querySelectorAll('[data-dsh-mobile-row]').length, 0, '不应注入任何行')
})

test('注入：克隆原生行插在锚点之后，文案与开关态被替换', () => {
  setupGlobals()
  const exp = loadClient()
  const sessionLog = makeRow('Upload Session Log when using the official model API', 'Help improve DeepSeek models and products.')
  const s = makeSettingsDialog({ rows: [sessionLog] })
  doc.body.appendChild(s.dlg)

  const row = exp.__test.ensureMobileRow({ isEnabled: () => true, onToggle: () => {} })
  assert.ok(row, '应注入一行')
  assert.equal(row.getAttribute('data-dsh-mobile-row'), '1', '注入节点带稳定标记')
  assert.equal(s.content.children.length, 2, '锚点行之后多出一行')
  assert.equal(s.content.children[1], row, '新行紧跟锚点行')
  assert.equal(row.querySelector('div[class*="_title"]').textContent, 'DSH 页面交互适配')
  assert.equal(
    row.querySelector('div[class*="_description"]').textContent,
    '关闭后：切换对话或打开菜单时键盘可能自动弹出，输入区可能被键盘遮挡；Gecko 版打字时会话顶栏会被顶出屏幕；返回键不再优先收起抽屉/关闭设置。',
  )
  const sw = row.querySelector('button[role="switch"]')
  assert.ok(sw, '克隆行保留原生 switch 按钮')
  assert.equal(sw.getAttribute('aria-checked'), 'true', 'aria-checked 反映本地开关态')
  assert.equal(sw.getAttribute('aria-label'), 'DSH 页面交互适配')
  assert.equal(sw.hasAttribute('disabled'), false, '克隆的 switch 必须可点')
  assert.ok(sw.querySelector('span'), 'switch 保留原生 thumb 结构')
})

test('注入幂等：重复注入不产生第二行，且已在位时零 DOM 变更', () => {
  setupGlobals()
  const exp = loadClient()
  const sessionLog = makeRow('Upload Session Log when using the official model API', 'x')
  const s = makeSettingsDialog({ rows: [sessionLog] })
  doc.body.appendChild(s.dlg)

  const first = exp.__test.ensureMobileRow({ isEnabled: () => true, onToggle: () => {} })
  const second = exp.__test.ensureMobileRow({ isEnabled: () => true, onToggle: () => {} })
  assert.equal(second, first, '第二次应复用同一个节点')
  assert.equal(doc.querySelectorAll('[data-dsh-mobile-row]').length, 1, '只有一行')
  assert.equal(s.content.children.length, 2)
})

test('开关：点击克隆的 switch 翻转开关态并回调', () => {
  setupGlobals()
  const exp = loadClient()
  const sessionLog = makeRow('Upload Session Log when using the official model API', 'x')
  const s = makeSettingsDialog({ rows: [sessionLog] })
  doc.body.appendChild(s.dlg)

  let state = true
  const toggles = []
  const row = exp.__test.ensureMobileRow({
    isEnabled: () => state,
    onToggle: (next) => { toggles.push(next); state = next },
  })
  const sw = row.querySelector('button[role="switch"]')
  sw.click()
  assert.deepEqual(toggles, [false], '开 → 关')
  // 状态翻转后再次注入应把 aria-checked 同步过去
  exp.__test.ensureMobileRow({ isEnabled: () => state, onToggle: () => {} })
  assert.equal(sw.getAttribute('aria-checked'), 'false', 'aria-checked 跟随开关态')
  sw.click()
  assert.deepEqual(toggles, [false, true], '关 → 开')
})

test('面板关闭：注入行被清理', () => {
  setupGlobals()
  const exp = loadClient()
  const sessionLog = makeRow('Upload Session Log when using the official model API', 'x')
  const s = makeSettingsDialog({ rows: [sessionLog] })
  doc.body.appendChild(s.dlg)
  exp.__test.ensureMobileRow({ isEnabled: () => true, onToggle: () => {} })
  assert.equal(doc.querySelectorAll('[data-dsh-mobile-row]').length, 1)

  s.dlg.parentNode.removeChild(s.dlg)
  assert.equal(exp.__test.ensureMobileRow({ isEnabled: () => true, onToggle: () => {} }), null)
  assert.equal(doc.querySelectorAll('[data-dsh-mobile-row]').length, 0, '面板关闭后不留残留')
})

test('多语言：跟随 DSH 当前语言（zh / en / 其它回退英文）', () => {
  setupGlobals()
  const exp = loadClient()
  doc.documentElement.setAttribute('lang', 'zh')
  assert.equal(exp.__test.mobileCopy().title, 'DSH 页面交互适配')
  doc.documentElement.setAttribute('lang', 'en')
  assert.equal(exp.__test.mobileCopy().title, 'DSH page interaction adaptation')
  doc.documentElement.setAttribute('lang', 'ja')
  assert.equal(exp.__test.mobileCopy().title, 'DSH page interaction adaptation', '未覆盖语言回退英文')
})

test('说明文字：必须保留「关掉后会怎样」的三条关键警告（Gecko / 返回键 / 键盘遮挡）', () => {
  setupGlobals()
  const exp = loadClient()

  doc.documentElement.setAttribute('lang', 'zh')
  const zh = exp.__test.mobileCopy().desc
  assert.ok(zh.includes('Gecko'), '中文说明必须点名 Gecko 版顶栏被顶出屏幕')
  assert.ok(zh.includes('返回键'), '中文说明必须说明返回键不再优先收抽屉/关设置')
  assert.ok(zh.includes('键盘'), '中文说明必须说明键盘自动弹出可能遮挡输入区')

  doc.documentElement.setAttribute('lang', 'en')
  const en = exp.__test.mobileCopy().desc
  assert.ok(en.includes('Gecko'), '英文说明必须点名 Gecko 版顶栏被顶出屏幕')
  assert.ok(en.includes('back key'), '英文说明必须说明返回键不再优先收抽屉/关设置')
  assert.ok(en.includes('keyboard'), '英文说明必须说明键盘自动弹出可能遮挡输入区')

  // 长文案必须整段换行显示：注入行的说明不继承原生「单行 + 省略号」样式，
  // 否则用户只能看到半句警告。
  const src = fs.readFileSync(CLIENT_JS, 'utf8')
  assert.ok(
    src.includes('[data-dsh-mobile-row] div[class*="_description"]'),
    '必须对注入行的说明单独放开换行（不依赖原生行样式）',
  )
  assert.ok(src.includes('white-space: normal !important;'), '说明必须允许换行')
  assert.ok(src.includes('text-overflow: clip !important;'), '说明不得被截断成省略号')
})

test('返回键分层：抽屉读原生 frame 属性', () => {
  setupGlobals()
  const exp = loadClient()
  const frame = makeFrame({ drawer: true })
  doc.body.appendChild(frame)
  assert.equal(exp.__test.drawerOpen(), true, '抽屉形态且未折叠 = 展开')
  frame.setAttribute('data-sidebar-collapsed', 'true')
  assert.equal(exp.__test.drawerOpen(), false, '折叠后不是展开态')
  frame.removeAttribute('data-sidebar-drawer')
  assert.equal(exp.__test.drawerOpen(), false, '宽屏轨道形态不算抽屉层')
})

test('返回键分层：最上层判定（设置子页 > 设置弹窗 > 抽屉 > 无）', () => {
  setupGlobals()
  const exp = loadClient()
  assert.equal(exp.__test.topLayerKind(), null, '无可退层时放行')

  const frame = makeFrame({ drawer: true })
  doc.body.appendChild(frame)
  assert.equal(exp.__test.topLayerKind(), 'drawer', '抽屉展开')

  const s = makeSettingsDialog({ rows: [] })
  doc.body.appendChild(s.dlg)
  assert.equal(exp.__test.topLayerKind(), 'settings', '设置弹窗盖在抽屉之上')

  const back = new El('button')
  back.setAttribute('class', 'VOzbGW_back')
  s.content.appendChild(back)
  assert.equal(exp.__test.topLayerKind(), 'settings-sub', '设置内存在原生返回控件 = 二级页')
})

test('返回键分层：设置二级页的返回控件被识别（类名与文案两种）', () => {
  setupGlobals()
  const exp = loadClient()
  const s = makeSettingsDialog({ rows: [] })
  doc.body.appendChild(s.dlg)

  const byClass = new El('button')
  byClass.setAttribute('class', 'VOzbGW_back')
  s.content.appendChild(byClass)
  assert.equal(exp.__test.settingsBackButton(s.dlg), byClass, 'CSS Modules 类名含 back')

  byClass.parentNode.removeChild(byClass)
  const byLabel = new El('button')
  byLabel.setAttribute('aria-label', 'Back to DeepSeek Harness')
  s.content.appendChild(byLabel)
  assert.equal(exp.__test.settingsBackButton(s.dlg), byLabel, 'aria-label 含 Back')

  byLabel.parentNode.removeChild(byLabel)
  assert.equal(exp.__test.settingsBackButton(s.dlg), null, '没有返回控件 = 设置一级')
})

test('返回键分层：设置面板用原生关闭按钮关闭', () => {
  setupGlobals()
  const exp = loadClient()
  const s = makeSettingsDialog({ rows: [] })
  doc.body.appendChild(s.dlg)
  assert.equal(exp.__test.nativeCloseButton(s.dlg), s.closeBtn, '定位到原生关闭按钮')
  assert.equal(exp.__test.closeSettings(s.dlg), true)
  assert.equal(s.closeCount(), 1, '走的是 DSH 原生 onClose')
})

test('返回键分层：真实形状的原生抽屉（带 nav 子节点）不会被误判成设置面板', () => {
  setupGlobals()
  const exp = loadClient()
  const drawer = makeSidebarDrawer()
  doc.body.appendChild(makeDrawerFrame(drawer))
  assert.equal(exp.__test.settingsDialog(), null, '抽屉 dialog 不是设置面板')
  assert.equal(exp.__test.topLayerKind(), 'drawer', '抽屉层判定为 drawer')
  assert.equal(exp.__test.findSessionLogRow(drawer.root), null, '抽屉里没有会话日志行锚点')
  assert.equal(exp.__test.settingsBackButton(drawer.root), null, '抽屉不是设置子页')
})

test('返回键分层：抽屉展开时返回只收抽屉，不会去点抽屉里的设置触发键', () => {
  setupGlobals()
  const exp = loadClient()
  const drawer = makeSidebarDrawer()
  doc.body.appendChild(makeDrawerFrame(drawer))
  const calls = []
  exp.apply({
    layout: {
      toggleSidebar: () => calls.push('toggleSidebar'),
      selectPanel: () => calls.push('selectPanel'),
      panelInfo: { getSnapshot: () => ({ activePanelId: null }) },
    },
    get: () => null,
  })
  try {
    assert.equal(typeof win.__dshboxCloseTopLayer, 'function', '返回键关闭入口已挂上')
    assert.equal(win.__dshboxCloseTopLayer(), true, '抽屉层被收起')
    assert.deepEqual(calls, ['toggleSidebar'], '只收起抽屉，不碰别的层')
    assert.equal(drawer.triggerClicks(), 0, '抽屉里的设置触发键没有被点（否则按返回反而打开设置）')
  } finally {
    if (typeof doc.__dshboxAttachTeardown === 'function') doc.__dshboxAttachTeardown()
  }
})

test('窄屏：抽屉已开时开关行仍注入到设置面板（不落到抽屉里）', () => {
  setupGlobals()
  const exp = loadClient()
  const drawer = makeSidebarDrawer()
  doc.body.appendChild(makeDrawerFrame(drawer))
  const sessionLog = makeRow('Upload Session Log when using the official model API', 'x')
  const s = makeSettingsDialog({ rows: [sessionLog] })
  doc.body.appendChild(s.dlg)

  const row = exp.__test.ensureMobileRow({ isEnabled: () => true, onToggle: () => {} })
  assert.ok(row, '设置面板打开时应注入开关行')
  assert.equal(row.parentNode, s.content, '注入到设置面板的内容区')
  assert.equal(s.content.children.length, 2, '锚点行之后多出一行')
  assert.equal(drawer.nav.querySelectorAll('[data-dsh-mobile-row]').length, 0, '抽屉里不注入')
})

test('兼容：无 data-shortcut-modal 标记、但有 NAV 子节点的 dialog 仍认作设置面板', () => {
  setupGlobals()
  const exp = loadClient()
  const dlg = new El('div')
  dlg.setAttribute('role', 'dialog')
  dlg.setAttribute('aria-modal', 'true')
  const nav = new El('nav')
  dlg.appendChild(nav)
  doc.body.appendChild(dlg)
  assert.equal(exp.__test.settingsDialog(), dlg, '旧版本（无官方标记）仍按 NAV 子节点判定')
})

test('删除项：布局干预相关的构造已不在插件里', () => {
  const src = fs.readFileSync(CLIENT_JS, 'utf8')
  const gone = [
    'dsh-mobile-menu',
    'dsh-mobile-backdrop',
    'dsh-mobile-chip',
    'dsh-mobile-back-inline',
    'dsh-settings-content-open',
    'dsh-mobile-drawer-open',
    'grid-template-columns',
    '100dvh',
    '[class*="handle"]',
    '[class*="detailsCol"]',
  ]
  for (const marker of gone) {
    assert.ok(!src.includes(marker), '不应再出现：' + marker)
  }
  assert.ok(src.includes('data-dsh-mobile-row'), '开关行标记仍在')
})

test('版本：package.json 为 1.5.2 且描述标明适配 DSH 0.2.2', () => {
  const pkg = JSON.parse(fs.readFileSync(PKG_JSON, 'utf8'))
  assert.equal(pkg.version, '1.5.2')
  assert.match(pkg.description, /0\.2\.2/)
})
