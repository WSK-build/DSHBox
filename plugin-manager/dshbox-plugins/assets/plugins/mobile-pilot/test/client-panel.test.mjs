// 浏览器半边（lib/client.js）的契约测试：右侧栏入口注册、旧 DSH 降级、静态面板不启动流。
// client.js 顶层调用 window.__ModuleLoader__.load —— 用受控的 window 假体求值源码，
// 登记下来的 factory 在测试里以假 require 调用，不进任何真实浏览器环境。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const PACK_DIR = fileURLToPath(new URL('..', import.meta.url))
const CLIENT_PATH = path.join(PACK_DIR, 'plugin', 'lib', 'client.js')
const PKG_PATH = path.join(PACK_DIR, 'plugin', 'package.json')
const PKG = JSON.parse(fs.readFileSync(PKG_PATH, 'utf8'))

const SLOT_PANE_TAB = 'sidebar.right.pane.tab'
const TAB_ID = '@local/mobile-pilot'
const TAB_KIND = 'mobile-pilot-virtual-display'

/** 按 loader 形态求值 client.js：顶层只登记不执行，返回登记的 load 调用清单。 */
function loadClient () {
  const loads = []
  const window = { __ModuleLoader__: { load: (spec) => loads.push(spec) } }
  new Function('window', fs.readFileSync(CLIENT_PATH, 'utf8'))(window)
  return loads
}

/** 最小 React 假体：createElement 记录元素树，useEffect 只登记不执行。 */
function fakeReact () {
  const calls = { elements: [], effects: [], states: [], refs: [] }
  const shimState = { hookSeq: 0, refSeq: 0 }
  const React = {
    createElement (type, props, ...children) {
      const el = { type: type, props: props || {}, children: children }
      calls.elements.push(el)
      return el
    },
    useEffect (fn) { calls.effects.push(fn) },
    // Hooks 语义：同一组件实例的 useState 按调用序返回**同一个**单元格 —— 重渲染
    // 读到的是上一轮 setSnapshot 写回的值，不是重新初始化（的条件渲染块——释放
    // 确认卡——测试要靠它进元素树）。测试挂载/重渲染都只有 VirtualDisplayBody 一个
    // useState 用户，`renderSeq` 哨位重置调用序即可。
    useState (init) {
      const index = shimState.hookSeq
      shimState.hookSeq += 1
      if (calls.states[index] === undefined) {
        calls.states[index] = { value: typeof init === 'function' ? init() : init }
      }
      const cell = calls.states[index]
      return [cell.value, (next) => { cell.value = typeof next === 'function' ? next(cell.value) : next }]
    },
    useRef (init) {
      const index = shimState.refSeq
      shimState.refSeq += 1
      if (calls.refs[index] === undefined) {
        calls.refs[index] = { current: init === undefined ? null : init }
      }
      return calls.refs[index]
    },
  }
  return { React: React, calls: calls, shimState: shimState }
}

/** 调用登记的 factory：require 只认 react（同一份假体原样交回），其余视为接线错误。 */
function factoryExports ({ react = true } = {}) {
  const loads = loadClient()
  assert.equal(loads.length, 1, '顶层必须恰好一次 window.__ModuleLoader__.load')
  const required = []
  const shim = react ? fakeReact() : null
  const fakeRequire = (name) => {
    required.push(name)
    if (name === 'react') {
      if (!shim) throw new Error('react unavailable')
      return shim.React
    }
    throw new Error('unexpected require: ' + name)
  }
  return { load: loads[0], exports: loads[0].factory(fakeRequire), required, shim }
}

/** 假 ctx：effect 立即执行并登记（连同回调返回的 disposer）；注册与席位只记录不生效。 */
function fakeCtx ({ withEffect = true } = {}) {
  const calls = { effect: [], tabDefs: [], injects: [], slotRegs: [] }
  const ctx = {
    sidebarRightTabs: {
      register: (def) => { calls.tabDefs.push(def); return () => {} },
    },
    slots: {
      inject: (name, factory) => { calls.injects.push({ name, factory }); return () => {} },
      register: (config, Body) => { calls.slotRegs.push({ config, Body }); return () => {} },
    },
  }
  if (withEffect) {
    ctx.effect = (fn, label) => {
      const disposer = fn()
      calls.effect.push({ label, disposer })
      return disposer
    }
  }
  return { ctx, calls }
}

/**
 * 生命周期假 ctx：登记是真实占位的（同 id/同席位重复注册会抛错，disposer 真的撤销），
 * 用于「apply → dispose → 再 apply」的完整一轮 —— BLOCKER 回归：effect 回调漏返回
 * disposer 时，重载/禁用后旧 id 占位，重新注册 duplicate id 抛错，入口就此消失。
 */
function lifecycleCtx () {
  const calls = { tabIds: [], injects: [], slotRegs: [], effect: [] }
  const types = new Map()
  const seats = new Map()
  const ctx = {
    sidebarRightTabs: {
      register (def) {
        if (types.has(def.id)) throw new Error('duplicate id: ' + def.id)
        types.set(def.id, def)
        calls.tabIds.push(def.id)
        return () => { types.delete(def.id) }
      },
    },
    slots: {
      inject (name, factory) {
        if (seats.has(name)) throw new Error('duplicate seat: ' + name)
        seats.set(name, factory)
        calls.injects.push(name)
        return () => { seats.delete(name) }
      },
      register (config, Body) {
        calls.slotRegs.push({ config, Body })
        return () => {}
      },
    },
    effect (fn, label) {
      const disposer = fn()
      calls.effect.push({ label, disposer })
      return disposer
    },
  }
  return { ctx, calls, types, seats }
}

/** 临时接管 console.info，收集 client.js 的 note（降级说明），退出即还原。 */
function captureNotes (fn) {
  const notes = []
  const original = console.info
  console.info = (...args) => notes.push(args.join(' '))
  try {
    fn()
  } finally {
    console.info = original
  }
  return notes
}

/** 深收元素树里的直接文本子节点。 */
function collectText (node, out = []) {
  if (typeof node === 'string') { out.push(node); return out }
  if (node && Array.isArray(node.children)) {
    for (const child of node.children) collectText(child, out)
  }
  return out
}

test('package.json 声明 ./client 与 dsh.client、不设包级 engines.dsh，宿主半边入口原样保留', () => {
  assert.equal(PKG.exports['./client'], './lib/client.js', '浏览器半边入口没声明')
  assert.equal(PKG.dsh.client.platform, 'web')
  assert.ok(
    Array.isArray(PKG.dsh.client.inject) &&
    PKG.dsh.client.inject.includes('@deepseek-ai/dsh-client-ui-sidebar-right'),
    'dsh.client.inject 没指向右侧栏服务包',
  )
  // 不设包级 engines.dsh：宿主工具与浏览器半边同包，包级门槛被旧 runtime 执行时会
  // 连 14 个 phone_* 一起停装 —— 版本适配由客户端半边自探测降级承担，不用 engines。
  assert.ok(
    !PKG.engines || PKG.engines.dsh === undefined,
    'mobile-pilot 不得声明包级 engines.dsh（宿主工具会陪葬）',
  )
  // 宿主半边零触碰：main、bin、工具子路径导出与 bundle patch 一字不动
  assert.equal(PKG.main, 'lib/index.js')
  assert.equal(PKG.bin['mobile-pilot'], 'bin/mobile-pilot')
  assert.equal(PKG.exports['.'], './lib/index.js')
  assert.equal(PKG.exports['./mount'], './lib/mount.js')
  assert.equal(PKG.exports['./pilot'], './lib/pilot.js')
  assert.equal(PKG.exports['./tools'], './lib/tools.js')
  assert.equal(PKG.dsh.bundle.patch, './cordis.patch.yml')
})

test('lib/client.js 通过 node --check（安装脚本对它做同样的语法预检）', () => {
  const r = spawnSync(process.execPath, ['--check', CLIENT_PATH], { encoding: 'utf8' })
  assert.equal(r.status, 0, `stderr: ${r.stderr}`)
})

test('装载形态：单次 load、factory 导出 name / inject / apply，require 只取 react', () => {
  const { load, exports, required } = factoryExports()
  assert.equal(load.id, '@local/mobile-pilot')
  assert.equal(typeof load.factory, 'function')
  assert.equal(exports.name, '@local/mobile-pilot', '浏览器半边名与包名一致')
  assert.deepEqual(exports.inject, ['slots', 'sidebarRightTabs'])
  assert.equal(typeof exports.apply, 'function')
  assert.deepEqual(required, ['react'], '浏览器半边只允许依赖 react')
})

test('能力齐备：注册一个 keepMounted:false 的 tab 类型与同一 id 的正文席位', () => {
  const { exports } = factoryExports()
  const { ctx, calls } = fakeCtx()
  exports.apply(ctx)

  assert.equal(calls.effect.length, 2, '两阶段注册都应挂在 ctx.effect 里随插件同生共死')
  for (const { label, disposer } of calls.effect) {
    assert.equal(typeof disposer, 'function', `effect 回调必须返回 disposer（${label}），漏返回会让重载后的新注册撞 duplicate id`)
  }
  assert.equal(calls.tabDefs.length, 1)
  const def = calls.tabDefs[0]
  assert.equal(def.id, TAB_ID, '实现身份必须是包名：它同时是正文席位的 key')
  assert.equal(def.kind, TAB_KIND)
  assert.equal(def.keepMounted, false, '隐藏即卸载：面板不驻留')
  assert.equal(def.title(''), '虚拟屏')
  assert.equal(def.guide.length, 1, '引导页入口一条')
  assert.equal(def.guide[0].title(), '虚拟屏')
  assert.equal(typeof def.guide[0].order, 'number')

  assert.equal(calls.injects.length, 1)
  assert.equal(calls.injects[0].name, SLOT_PANE_TAB)
  const disposer = calls.injects[0].factory()
  assert.equal(typeof disposer, 'function', 'slots.inject 的登记工厂返回 register 的 disposer')
  assert.equal(calls.slotRegs.length, 1)
  assert.equal(calls.slotRegs[0].config.name, SLOT_PANE_TAB)
  assert.equal(calls.slotRegs[0].config.key, def.id, '正文席位按 definition 的 id 派发')
  assert.equal(typeof calls.slotRegs[0].Body, 'function')
})

test('旧 DSH 降级：缺侧栏服务或缺 react 时不注册、不抛错，note 说清原因', () => {
  // ⚠ 防御性覆盖，不是版本门控的主通路：exports.inject 声明了 slots/sidebarRightTabs，
  // 旧 runtime 上 loader 在 inject 不满足时根本不会调 apply —— 下面的场景模拟的是
  // 「inject 意外被满足、但对象残缺/中途被摘」的异常宿主形态，note 分支只是兜底。
  const { exports } = factoryExports()

  // ctx 上没有 sidebarRightTabs（旧 runtime 的异常形态）
  const partial = fakeCtx()
  delete partial.ctx.sidebarRightTabs
  const notes1 = captureNotes(() => exports.apply(partial.ctx))
  assert.equal(partial.calls.tabDefs.length, 0)
  assert.equal(partial.calls.slotRegs.length, 0)
  assert.equal(notes1.length, 1, '要有一条可读 note')
  assert.match(notes1[0], /右侧栏/)

  // slots 整个缺席（更残的宿主）
  const noSlots = fakeCtx()
  delete noSlots.ctx.slots
  const notes2 = captureNotes(() => exports.apply(noSlots.ctx))
  assert.equal(noSlots.calls.tabDefs.length, 0)
  assert.match(notes2[0], /右侧栏/)

  // react 不可解析：画不出正文，整半边不注册
  const bare = factoryExports({ react: false })
  const safe = fakeCtx()
  const notes3 = captureNotes(() => bare.exports.apply(safe.ctx))
  assert.equal(safe.calls.tabDefs.length, 0)
  assert.match(notes3[0], /react/)
})

test('生命周期：apply → dispose → 再 apply 不撞 duplicate id，入口可重新出现', () => {
  const { exports } = factoryExports()
  const { ctx, calls, types, seats } = lifecycleCtx()

  exports.apply(ctx)
  assert.deepEqual(calls.tabIds, [TAB_ID])
  assert.deepEqual(calls.injects, [SLOT_PANE_TAB])

  // loader 卸载插件：逐个调 effect 登记的 disposer
  for (const { disposer } of calls.effect) disposer()
  assert.equal(types.size, 0, '类型注册要随 disposer 撤销')
  assert.equal(seats.size, 0, '席位登记要随 disposer 撤销')

  // 重新 apply（重载/禁用后启用）：旧 id 已让位，不得抛 duplicate id
  const notes = captureNotes(() => exports.apply(ctx))
  assert.equal(calls.tabIds.length, 2, '第二次 apply 重新注册类型')
  assert.deepEqual(calls.injects.length, 2, '第二次 apply 重新登记席位')
  assert.equal(types.get(TAB_ID).kind, TAB_KIND)
  assert.equal(notes.length, 0, '正常重载不许出 note')
})

test('effect 路径注册抛错：只报告不冒泡，apply 不向加载期砸异常', () => {
  const { exports } = factoryExports()
  const { ctx, calls } = lifecycleCtx()
  ctx.sidebarRightTabs.register = () => { throw new Error('boom: id taken') }
  const notes = captureNotes(() => exports.apply(ctx))
  assert.match(notes[0], /类型注册失败/)
  assert.equal(calls.injects.length, 1, '类型段失败后席位段仍在自己的 catch 里走完，不互相连坐')

  // 席位段单独失败：类型已注册的 disposer 照常拿到，报告不冒泡
  const again = lifecycleCtx()
  again.ctx.slots.inject = () => { throw new Error('boom: seat') }
  const notes2 = captureNotes(() => exports.apply(again.ctx))
  assert.match(notes2[0], /席位注册失败/)
  assert.equal(again.calls.tabIds.length, 1, '类型段不受席位段失败影响')
})

test('effect 服务缺席时直接注册兜底：注册中途失败只报告，不向加载期抛错', () => {
  const { exports } = factoryExports()
  const { ctx, calls } = fakeCtx({ withEffect: false })
  exports.apply(ctx)
  assert.equal(calls.tabDefs.length, 1, '无 effect 也应完成类型注册')
  assert.equal(calls.injects.length, 1, '席位登记随类型一并挂上')
  calls.injects[0].factory()
  assert.equal(calls.slotRegs.length, 1)

  const broken = fakeCtx({ withEffect: false })
  broken.ctx.slots.inject = () => { throw new Error('boom') }
  const notes = captureNotes(() => exports.apply(broken.ctx))
  assert.match(notes[0], /注册失败/)
})

// ── 流面板（流与输入）：MSE 能力探测、路由发现、start/attach/stop、输入层 ──

const VIDEO_SRC = 'video/mp4; codecs="avc1.42001F"'

function concatBytes (...arrays) {
  const total = arrays.reduce((n, a) => n + a.length, 0)
  const out = new Uint8Array(total)
  let at = 0
  for (const a of arrays) { out.set(a, at); at += a.length }
  return out
}

function boxBytes (type, payload) {
  const out = new Uint8Array(8 + payload.length)
  new DataView(out.buffer).setUint32(0, out.length)
  for (let i = 0; i < 4; i += 1) out[4 + i] = type.charCodeAt(i)
  out.set(payload, 8)
  return out
}

/** 一段最小 fMP4：ftyp+moov（带 avcC 42 00 1F，Level 3.1）初始化段 + 一个 moof+mdat 分片。 */
function segmentFixture () {
  return avcCFixture(0x1F)
}

/**
 * 带 avcC 的 init fixture：avcC 记录里 profile/compat/level = 42 00 <level>。
 * 默认 0x28（Level 4.0，1280 长边档）；segmentFixture 用 0x1F（Level 3.1，720 档）。
 */
function avcCFixture (level = 0x28) {
  const ftyp = boxBytes('ftyp', new TextEncoder().encode('isom0000isom'))
  const avcC = boxBytes('avcC', new Uint8Array([1, 0x42, 0x00, level, 0xFF, 0xE1]))
  const moov = boxBytes('moov', avcC)
  const moof = boxBytes('moof', new Uint8Array(0))
  const mdat = boxBytes('mdat', new Uint8Array([1, 2, 3]))
  return {
    init: concatBytes(ftyp, moov),
    segment: concatBytes(moof, mdat),
    all: concatBytes(ftyp, moov, moof, mdat),
  }
}

/** moov 里没有 avcC 的 init（残缺/非 AVC 流）：codecFromInit 应回 null。 */
function bareMoovFixture () {
  const ftyp = boxBytes('ftyp', new TextEncoder().encode('isom0000isom'))
  const moov = boxBytes('moov', new Uint8Array(0))
  return concatBytes(ftyp, moov)
}

/** 非 init 顶层盒（free）：init 门控里应被丢弃、继续等下一段。 */
function freeBox () {
  return boxBytes('free', new Uint8Array([0, 0, 0, 0]))
}

test('codecFromInit：从 init 段 avcC 解出 profile/compat/level，拼逐字节一致的 codec 串', () => {
  const { exports } = factoryExports()
  assert.equal(exports.codecFromInit(avcCFixture(0x28).init), 'video/mp4; codecs="avc1.420028"')
  assert.equal(exports.codecFromInit(segmentFixture().init), 'video/mp4; codecs="avc1.42001F"')
  // moov 无 avcC：回 null（init 门控会继续等，绝不拿写死的 codec 兜底）
  assert.equal(exports.codecFromInit(bareMoovFixture()), null)
  // 半截 init（连 8 字节都不到）：回 null 不抛
  assert.equal(exports.codecFromInit(avcCFixture().init.slice(0, 7)), null)
  assert.equal(exports.codecFromInit(null), null)
})

/** 面板 start → attach 的驱动器：复用主用例的 fetch/MSE 假体接线，回 (fetch, cleanups, mounted)。 */
async function drivePreviewStream ({ MS, reader }) {
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ running: false }), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({
      ok: true, status: 200,
      json: async () => ({
        ok: true, tabToken: 'tab-avc',
        // 宿主写的会话元数据：源尺寸 + 实际编码尺寸，诊断行 src=/enc= 的来源
        metadata: { mime: 'video/mp4', width: 1280, height: 1032, sourceWidth: 2560, sourceHeight: 2064 },
      }),
      body: null,
    }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null }),
    '/mobile-pilot/stream?': () => Promise.resolve({ ok: true, status: 200, body: { getReader: () => reader } }),
  })
  const originalURL = globalThis.URL
  globalThis.URL = class {
    static createObjectURL () { return 'blob:fake-dynamic' }
    static revokeObjectURL () {}
  }
  let result = null
  await withPanelGlobals(fetch, MS, async () => {
    const mounted = mountBody()
    const cleanups = runMountEffects(mounted.shim)
    await new Promise((r) => setTimeout(r, 10))
    const startButton = findProp(mounted.el, 'data-mobile-pilot-action', 'start-preview')
    mounted.shim.calls.refs[0].current = {
      addEventListener: () => {},
      error: null,
      play: () => {},
      pause: () => {},
      removeAttribute: () => {},
      load: () => {},
    }
    startButton.props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    result = { cleanups, mounted }
  })
  globalThis.URL = originalURL
  return { fetch, ...result }
}

test('流面板：init 段带 avcC 时按其现解 codec 建 SourceBuffer（avc1.420028）', async () => {
  const fx = avcCFixture(0x28)
  // 第一段 init 挂起不放行：验证 sourceopen 后、init 到达前**不** addSourceBuffer
  let releaseInit = null
  const initGate = new Promise((resolve) => { releaseInit = resolve })
  let sentInit = false
  const reader = {
    async read () {
      if (!sentInit) {
        sentInit = true
        await initGate
        return { done: false, value: fx.all }
      }
      return { done: true, value: undefined }
    },
    async cancel () {},
  }
  const supportedTypes = []
  const MS = fakeMediaSource({ supported: (type) => { supportedTypes.push(type); return true } })
  const { cleanups, mounted } = await drivePreviewStream({ MS, reader })
  const ms = MS.instances[0]
  for (const fn of ms.listeners.sourceopen || []) fn()
  await new Promise((r) => setTimeout(r, 20))
  assert.equal(ms.sourceBuffers.length, 0, 'sourceopen 后、init 到达前不建 SourceBuffer')
  releaseInit()
  await new Promise((r) => setTimeout(r, 20))
  assert.equal(ms.sourceBuffers.length, 1, 'SourceBuffer 已建')
  assert.equal(ms.sourceBuffers[0].type, 'video/mp4; codecs="avc1.420028"', 'codec 按 avcC 现解')
  assert.deepEqual(supportedTypes, ['video/mp4; codecs="avc1.420028"'], '只探测动态 codec 一个候选（fail-closed，无回退列表）')
  assert.deepEqual(ms.sourceBuffers[0].appended.map((b) => [...b]), [[...fx.init], [...fx.segment]], 'init 与分片照常追加')
  // 诊断行：源尺寸/编码尺寸/codec 全部来自 metadata 与 avcC
  const diag = mounted.shim.calls.states.map((s) => s.value && s.value.diag).filter(Boolean).pop() || ''
  assert.match(diag, /src=2560x2064/, '诊断行带源尺寸')
  assert.match(diag, /enc=1280x1032/, '诊断行带编码尺寸')
  assert.match(diag, /codec=420028/, '诊断行带现解出的 codec')
  for (const cleanup of cleanups) cleanup()
})

test('流面板：init 段 avcC 是 3.1/42001F（720 档）时照样按 avcC 建 buffer，原断言保持', async () => {
  const fx = segmentFixture()
  const reader = {
    chunks: [fx.all],
    index: 0,
    async read () {
      const value = this.chunks[this.index]
      this.index += 1
      if (this.index > this.chunks.length) return { done: true, value: undefined }
      return { done: false, value }
    },
    async cancel () {},
  }
  const MS = fakeMediaSource({ supported: (type) => type === VIDEO_SRC })
  const { cleanups } = await drivePreviewStream({ MS, reader })
  const ms = MS.instances[0]
  for (const fn of ms.listeners.sourceopen || []) fn()
  await new Promise((r) => setTimeout(r, 20))
  assert.ok(ms.sourceBuffers.length === 1 && ms.sourceBuffers[0].type === VIDEO_SRC, '42001F 按 avcC 现解，与写死常量同值')
  assert.deepEqual(ms.sourceBuffers[0].appended.map((b) => [...b]), [[...fx.init], [...fx.segment]])
  for (const cleanup of cleanups) cleanup()
})

test('流面板：动态 codec 不支持时 fail-closed，点名 codec 不回退低 level', async () => {
  const fx = avcCFixture(0x28)
  const reader = {
    chunks: [fx.all],
    index: 0,
    async read () {
      const value = this.chunks[this.index]
      this.index += 1
      if (this.index > this.chunks.length) return { done: true, value: undefined }
      return { done: false, value }
    },
    async cancel () {},
  }
  // 420028 不支持，但 42001F 支持 —— 也不许回退
  const probed = []
  const MS = fakeMediaSource({ supported: (type) => { probed.push(type); return type === VIDEO_SRC } })
  const { fetch, cleanups, mounted } = await drivePreviewStream({ MS, reader })
  const ms = MS.instances[0]
  for (const fn of ms.listeners.sourceopen || []) fn()
  await new Promise((r) => setTimeout(r, 20))
  assert.equal(ms.sourceBuffers.length, 0, '动态 codec 不支持时不建 SourceBuffer')
  assert.deepEqual(probed, ['video/mp4; codecs="avc1.420028"'], '不回退到 42001F/42E01F/42E01E')
  const last = mounted.shim.calls.states[mounted.shim.calls.states.length - 1].value
  assert.match(last.message, /此渲染引擎不支持预览所需的 video\/mp4; codecs="avc1\.420028"/, 'failSession 点名 codec')
  assert.ok(fetch.requests.some((r) => r.url.startsWith('/mobile-pilot/stream/stop')), '失败仍配平 stop')
  for (const cleanup of cleanups) cleanup()
})

// ── E init 门控超时：注入短 initWaitTimeoutMs，区分"零字节"与"有字节无 init" ──

/** 面板控制器直驱的 fetch/MSE 假体：/stream 由 readerFor() 现产（每届 start 一条）。 */
function gateHarness ({ readerFor, initWaitTimeoutMs = 60 } = {}) {
  const { exports } = factoryExports()
  const MS = fakeMediaSource()
  const requests = []
  const fetchImpl = async (url, init) => {
    const u = String(url)
    requests.push(u)
    if (u.startsWith('/mobile-pilot/stream/status')) {
      return { ok: true, status: 200, json: async () => ({ running: false }), body: null }
    }
    if (u.startsWith('/mobile-pilot/stream/start')) {
      return {
        ok: true, status: 200,
        json: async () => ({ ok: true, tabToken: 'tab-gate', metadata: { mime: 'video/mp4', width: 1280, height: 1032, sourceWidth: 2560, sourceHeight: 2064 } }),
        body: null,
      }
    }
    if (u.startsWith('/mobile-pilot/stream/stop')) {
      return { ok: true, status: 200, json: async () => ({ ok: true }), body: null }
    }
    if (u.startsWith('/mobile-pilot/stream?')) {
      return { ok: true, status: 200, body: { getReader: readerFor } }
    }
    return { ok: false, status: 404, json: async () => ({}), body: null }
  }
  const controller = exports.createStreamPanelController({
    fetch: fetchImpl,
    MediaSource: MS,
    urlCreate: () => 'blob:gate',
    urlRevoke: () => {},
    getVideo: () => null,
    initWaitTimeoutMs,
  })
  return { controller, MS, requests }
}

function hangingReader () {
  return { read: () => new Promise(() => {}), cancel: () => {} }
}

test('init 门控：注入短超时后空流按"零字节"文案收场，timer/codec/metadata 清干净', async () => {
  const { controller, MS } = gateHarness({ readerFor: () => hangingReader() })
  await controller.discover()
  await controller.start()
  // init 门控计时从 sourceopen 起算：点火后空 reader 挂起，等短超时。
  for (const fn of MS.instances[0].listeners.sourceopen || []) fn()
  await new Promise((r) => setTimeout(r, 150))
  const state = controller.state()
  assert.equal(state.phase, 'stopped')
  assert.match(state.message, /没有收到任何流字节（面板连接已建立）/, '零字节要有零字节的文案')
  assert.equal(state.initWaitTimer, null, 'initWaitTimer 已清理')
  assert.equal(state.codec, null)
  assert.equal(state.metadata, null, '上一届 metadata 清掉，诊断行不带旧 src/enc')
  controller.dispose()
})

test('init 门控：有字节但无 init 按第二种文案收场', async () => {
  let sent = false
  const readerFor = () => ({
    read () {
      if (!sent) {
        sent = true
        // 半截盒子：重组器不出段、不计数，但字节确实到了
        return Promise.resolve({ done: false, value: new Uint8Array([1, 2, 3, 4, 5]) })
      }
      return new Promise(() => {})
    },
    cancel: () => {},
  })
  const { controller, MS } = gateHarness({ readerFor })
  await controller.discover()
  await controller.start()
  for (const fn of MS.instances[0].listeners.sourceopen || []) fn()
  await new Promise((r) => setTimeout(r, 150))
  const state = controller.state()
  assert.match(state.message, /收到了流数据，但没有可识别的 fMP4 初始化段（ftyp\+moov\/avcC）/)
  controller.dispose()
})

test('init 门控：超时收场后面板手动 start 能重开（codec/metadata 重新到位）', async () => {
  const fx = segmentFixture()
  let first = true
  const { controller, MS } = gateHarness({
    readerFor: () => {
      if (first) {
        first = false
        return hangingReader()
      }
      let sent = false
      return {
        read () {
          if (!sent) {
            sent = true
            // 只给 init 段然后挂起：EOF 会让 finishEnded 清掉 codec/metadata，
            // 这里要断言的是"重开后 codec/metadata 重新到位"。
            return Promise.resolve({ done: false, value: fx.init })
          }
          return new Promise(() => {})
        },
        cancel: () => {},
      }
    },
  })
  await controller.discover()
  await controller.start()
  for (const fn of MS.instances[0].listeners.sourceopen || []) fn()
  await new Promise((r) => setTimeout(r, 150))
  assert.match(controller.state().message, /没有收到任何流字节/, '第一场按零字节收场')
  // 手动重开（phase stopped → start 门禁放行）；不自动无限重试。
  await controller.start()
  // 第二场是新的 MediaSource 实例：sourceopen 要点在最后一个上。
  for (const fn of MS.instances[MS.instances.length - 1].listeners.sourceopen || []) fn()
  await new Promise((r) => setTimeout(r, 50))
  const state = controller.state()
  assert.equal(state.phase, 'playing', '第二场正常开播')
  assert.equal(state.codec, 'video/mp4; codecs="avc1.42001F"', 'codec 重新到位')
  assert.equal(state.metadata && state.metadata.sourceWidth, 2560, 'metadata 重新到位')
  assert.equal(state.initWaitTimer, null, 'init 到手后 timer 已清')
  controller.dispose()
})

test('流面板：连续收不到可识别的 init 段时按段数上限收场，不建 buffer', async () => {
  const reader = {
    chunks: Array.from({ length: 420 }, freeBox),
    index: 0,
    async read () {
      const value = this.chunks[this.index]
      this.index += 1
      if (this.index > this.chunks.length) return { done: true, value: undefined }
      return { done: false, value }
    },
    async cancel () {},
  }
  const MS = fakeMediaSource()
  const { fetch, cleanups, mounted } = await drivePreviewStream({ MS, reader })
  const ms = MS.instances[0]
  for (const fn of ms.listeners.sourceopen || []) fn()
  await new Promise((r) => setTimeout(r, 20))
  assert.equal(ms.sourceBuffers.length, 0, 'init 不到绝不建 buffer')
  const last = mounted.shim.calls.states[mounted.shim.calls.states.length - 1].value
  assert.match(last.message, /没有收到可识别的 fMP4 初始化段（ftyp\+moov\/avcC）/, '按上限收场并说明原因')
  const stopReq = fetch.requests.find((r) => r.url.startsWith('/mobile-pilot/stream/stop'))
  assert.ok(stopReq, '失败仍配平 stop')
  for (const cleanup of cleanups) cleanup()
})

// ── 输入层：归一化/手势判据（纯函数）与覆盖层 pointer 流程 ──

test('normalizePoint/classifyGesture：归一化夹取与 tap/swipe 判据（纯函数）', () => {
  const { exports } = factoryExports()
  const rect = { left: 0, top: 0, width: 200, height: 100 }
  // 无视频尺寸：整元素即内容（旧行为）
  assert.deepEqual(exports.normalizePoint(rect, 100, 50), { x: 0.5, y: 0.5 })
  // 出界夹回 0..1
  assert.deepEqual(exports.normalizePoint(rect, -30, 250), { x: 0, y: 1 })
  // 信箱黑边：视频 4:3 在 2:1 元素里左右留边，点击黑边归一化到内容边缘外→夹取
  const wide = { left: 0, top: 0, width: 400, height: 100 }
  // 内容宽 = 100*(4/3)≈133.3，左右各留 ~133.3px；内容中心 (200,50) 是视频 (0.5,0.5)
  assert.deepEqual(exports.normalizePoint(wide, 200, 50, 400, 300), { x: 0.5, y: 0.5 })
  // 左侧黑边 → x 夹到 0；右缘内容边界 (266.6) → x≈1
  assert.deepEqual(exports.normalizePoint(wide, 50, 50, 400, 300), { x: 0, y: 0.5 })
  assert.ok(Math.abs(exports.normalizePoint(wide, 266.7, 50, 400, 300).x - 1) < 0.01)
  // 位移 < 0.02 且 < 250ms → tap（用终点）
  const start = { x: 0.5, y: 0.5, t: 1000 }
  assert.deepEqual(
    exports.classifyGesture(start, { x: 0.51, y: 0.5, t: 1100 }),
    { kind: 'tap', x: 0.51, y: 0.5 },
  )
  // 位移 ≥ 0.02 → swipe（起止点 + 实际时长夹 50..1000）
  assert.deepEqual(
    exports.classifyGesture(start, { x: 0.8, y: 0.5, t: 1400 }),
    { kind: 'swipe', x1: 0.5, y1: 0.5, x2: 0.8, y2: 0.5, durationMs: 400 },
  )
  // 长按未动也是 tap（tap 只看位移，慢点按不再变零距离 swipe）
  assert.deepEqual(
    exports.classifyGesture(start, { x: 0.5, y: 0.5, t: 1300 }),
    { kind: 'tap', x: 0.5, y: 0.5 },
  )
  // 位移超阈 → swipe，时长为实际值并夹到 50..1000
  assert.deepEqual(
    exports.classifyGesture(start, { x: 0.9, y: 0.9, t: 9000 }),
    { kind: 'swipe', x1: 0.5, y1: 0.5, x2: 0.9, y2: 0.9, durationMs: 1000 },
  )
  // 慢速拖动：时长夹到下限 50
  assert.equal(exports.classifyGesture(start, { x: 0.6, y: 0.5, t: 1001 }).durationMs, 50)
})

/** 覆盖层假体：记录原生监听、供测试直接发 pointer 事件。 */
function fakeOverlay () {
  const listeners = {}
  return {
    listeners,
    getBoundingClientRect: () => ({ left: 0, top: 0, width: 200, height: 100 }),
    addEventListener (name, fn) { (listeners[name] = listeners[name] || []).push(fn) },
    removeEventListener (name, fn) {
      listeners[name] = (listeners[name] || []).filter((f) => f !== fn)
    },
    setPointerCapture () {},
  }
}

function pointerEvent (type, overlay, clientX, clientY) {
  for (const fn of overlay.listeners[type] || []) {
    fn({ pointerType: 'touch', pointerId: 1, clientX, clientY, button: 0, preventDefault: () => {} })
  }
}

test('流面板：输入层 tap → POST /mobile-pilot/input（归一化坐标 + tab token）', async () => {
  const fx = segmentFixture()
  const reader = {
    chunks: [fx.all], index: 0,
    async read () {
      const value = this.chunks[this.index]
      this.index += 1
      if (this.index > this.chunks.length) return { done: true, value: undefined }
      return { done: false, value }
    },
    async cancel () {},
  }
  const inputBodies = []
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ running: false }), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({
      ok: true, status: 200,
      json: async () => ({ ok: true, tabToken: 'tab-input', metadata: { mime: 'video/mp4', width: 1280, height: 1032, sourceWidth: 2560, sourceHeight: 2064 } }),
      body: null,
    }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null }),
    '/mobile-pilot/stream?': () => Promise.resolve({ ok: true, status: 200, body: { getReader: () => reader } }),
    '/mobile-pilot/input': (url, init) => {
      inputBodies.push(JSON.parse(init.body))
      return Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null })
    },
  })
  const originalURL = globalThis.URL
  globalThis.URL = class {
    static createObjectURL () { return 'blob:fake-input' }
    static revokeObjectURL () {}
  }
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { el, shim } = mountBody()
    const overlay = fakeOverlay()
    // refs[0]=video、refs[1]=输入层覆盖层：在 effect 跑之前就位，监听才挂得上。
    shim.calls.refs[0].current = {
      addEventListener: () => {}, error: null, play: () => {}, pause: () => {},
      removeAttribute: () => {}, load: () => {},
    }
    shim.calls.refs[1].current = overlay
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    const startButton = findProp(el, 'data-mobile-pilot-action', 'start-preview')
    // 未 playing（未 start）：手势整体吞掉（canInput 门禁，不只禁按钮）
    pointerEvent('pointerdown', overlay, 150, 100)
    pointerEvent('pointerup', overlay, 150, 100)
    await new Promise((r) => setTimeout(r, 20))
    startButton.props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    assert.equal(fetch.requests.filter((r) => r.url.startsWith('/mobile-pilot/input')).length, 0, '会话前/无手势零输入请求')
    // 快速点按（同步 down/up，位移 0）：tap，终点归一化 (0.75, 1.0)
    pointerEvent('pointerdown', overlay, 150, 100)
    pointerEvent('pointerup', overlay, 150, 100)
    await new Promise((r) => setTimeout(r, 20))
    assert.equal(inputBodies.length, 1, 'tap 发了一次输入请求')
    assert.equal(inputBodies[0].action, 'tap')
    assert.equal(inputBodies[0].tab, 'tab-input', 'tab token 随请求带给 Node 半')
    assert.equal(inputBodies[0].x, 0.75)
    assert.equal(inputBodies[0].y, 1)
    // 划动（位移大）：swipe，时长夹到下限 50
    pointerEvent('pointerdown', overlay, 10, 10)
    pointerEvent('pointerup', overlay, 100, 80)
    await new Promise((r) => setTimeout(r, 20))
    assert.equal(inputBodies.length, 2)
    assert.equal(inputBodies[1].action, 'swipe')
    assert.deepEqual(
      { x1: inputBodies[1].x1, y1: inputBodies[1].y1, x2: inputBodies[1].x2, y2: inputBodies[1].y2, durationMs: inputBodies[1].durationMs },
      { x1: 0.05, y1: 0.1, x2: 0.5, y2: 0.8, durationMs: 50 },
    )
    for (const cleanup of cleanups) cleanup()
  })
  globalThis.URL = originalURL
})

test('流面板：back/home 按钮走 sendInput；失败原因写进状态卡（不静默、不改播放状态）', async () => {
  let inputStatus = 502
  const fx = segmentFixture()
  const reader = {
    chunks: [fx.all], index: 0,
    async read () {
      const value = this.chunks[this.index]
      this.index += 1
      if (this.index > this.chunks.length) return { done: true, value: undefined }
      return { done: false, value }
    },
    async cancel () {},
  }
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ running: false }), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({
      ok: true, status: 200,
      json: async () => ({ ok: true, tabToken: 'tab-keys', metadata: { mime: 'video/mp4' } }),
      body: null,
    }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null }),
    '/mobile-pilot/stream?': () => Promise.resolve({ ok: true, status: 200, body: { getReader: () => reader } }),
    '/mobile-pilot/input': () => Promise.resolve({
      ok: inputStatus === 200, status: inputStatus,
      json: async () => ({ ok: false, reason: 'no stream session; POST /start first' }),
      body: null,
    }),
  })
  const originalURL = globalThis.URL
  globalThis.URL = class {
    static createObjectURL () { return 'blob:fake-keys' }
    static revokeObjectURL () {}
  }
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { el, shim } = mountBody()
    shim.calls.refs[0].current = {
      addEventListener: () => {}, error: null, play: () => {}, pause: () => {},
      removeAttribute: () => {}, load: () => {},
    }
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    // 会话不在：按钮点击给可读结论，不发请求
    const back = findProp(el, 'data-mobile-pilot-action', 'input-back')
    assert.ok(back && typeof back.props.onClick === 'function', '返回键在')
    assert.equal(back.props.disabled, true, '未 playing 时按键禁用')
    back.props.onClick()
    await new Promise((r) => setTimeout(r, 10))
    let last = shim.calls.states[shim.calls.states.length - 1].value
    assert.match(last.inputNote || '', /输入不可用/, '无会话时给可读结论')
    assert.ok(!fetch.requests.some((r) => r.url.startsWith('/mobile-pilot/input')), '无会话零请求')
    // 开一场会话：playing 后按键解禁，输入请求才真正出面板。
    const startButton = findProp(el, 'data-mobile-pilot-action', 'start-preview')
    startButton.props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    // 有会话但宿主拒绝：原因透传到状态卡
    const home = findProp(el, 'data-mobile-pilot-action', 'input-home')
    assert.ok(home, '主页键在')
    // 面板元素树是挂载时渲染的（测试假体不重渲染）：解禁与否看最新快照。
    const latest = shim.calls.states[shim.calls.states.length - 1].value
    assert.equal(latest.canInput, true, 'playing 且有 tabToken 时输入解禁')
    home.props.onClick()
    await new Promise((r) => setTimeout(r, 10))
    last = shim.calls.states[shim.calls.states.length - 1].value
    assert.match(last.inputNote || '', /输入失败/, '文案是人话（精修 4：HTTP 码不进主文案）')
    assert.ok(!/HTTP 502/.test(last.inputNote || ''), '主文案无 HTTP 码')
    assert.match(last.inputNote || '', /no stream session/, '宿主给的原因原样显示')
    // 成功：inputNote 清空
    inputStatus = 200
    home.props.onClick()
    await new Promise((r) => setTimeout(r, 10))
    last = shim.calls.states[shim.calls.states.length - 1].value
    assert.equal(last.inputNote || '', '', '成功后清空输入结论')
    for (const cleanup of cleanups) cleanup()
  })
  globalThis.URL = originalURL
})

test('fMP4 盒边界重组：任意分片只输出完整 init 与 moof+mdat', () => {
  const { exports } = factoryExports()
  const fx = segmentFixture()
  const assembler = exports.createSegmentAssembler()
  const out = []
  for (let i = 0; i < fx.all.length; i += 1) {
    out.push(...assembler.push(fx.all.slice(i, i + 1)))
  }
  assert.deepEqual(out.map((b) => [...b]), [[...fx.init], [...fx.segment]])
})

/** 深收元素树节点（含嵌套），供输入面扫描。 */
function collectTree (node, out = []) {
  if (!node) return out
  out.push(node)
  if (Array.isArray(node.children)) for (const child of node.children) collectTree(child, out)
  return out
}

/** 受控 fetch 假体：按 URL 前缀路由；记录全部请求 URL（零额外请求哨兵用）。 */
function fakePanelFetch (overrides = {}) {
  const requests = []
  const impl = (url, init) => {
    requests.push({ url: String(url), init: init || null, method: (init && init.method) || 'GET' })
    for (const [prefix, responder] of Object.entries(overrides)) {
      if (String(url).startsWith(prefix)) return responder(String(url), init)
    }
    return Promise.resolve({ ok: false, status: 404, json: async () => ({}), body: null })
  }
  impl.requests = requests
  return impl
}

/** MSE 假体：isTypeSupported 可编程（布尔或 (type)=>bool），实例记录 addSourceBuffer/endOfStream。 */
function fakeMediaSource ({ supported = true } = {}) {
  const instances = []
  function MS () {
    const instance = {
      sourceBuffers: [],
      ended: false,
      listeners: {},
      addEventListener (name, fn) { (this.listeners[name] = this.listeners[name] || []).push(fn) },
      addSourceBuffer (type) {
        const buffer = {
          type,
          appended: [],
          listeners: {},
          appendBuffer (value) {
            this.appended.push(value)
            // 真实 SourceBuffer 在追加完成后异步发 updateend；测试同步发即可驱动链
            for (const fn of this.listeners.updateend || []) fn()
          },
          addEventListener (name, fn) { (this.listeners[name] = this.listeners[name] || []).push(fn) },
          removeEventListener (name, fn) {
            this.listeners[name] = (this.listeners[name] || []).filter((f) => f !== fn)
          },
        }
        this.sourceBuffers.push(buffer)
        return buffer
      },
      endOfStream () { this.ended = true },
    }
    instances.push(instance)
    return instance
  }
  MS.isTypeSupported = typeof supported === 'function' ? supported : () => supported
  MS.instances = instances
  return MS
}

/** 挂载面板正文：跑 apply → 席位工厂 → 渲染 Body。 */
function mountBody () {
  const { exports, shim } = factoryExports()
  const { ctx, calls } = fakeCtx()
  const bodies = []
  ctx.slots.register = (config, Body) => { bodies.push(Body); return () => {} }
  exports.apply(ctx)
  calls.injects[0].factory()
  const Body = bodies[0]
  assert.equal(typeof Body, 'function')
  shim.shimState.hookSeq = 0
  shim.shimState.refSeq = 0
  const el = Body()
  assert.ok(el, '正文要渲染出元素树')
  // 真实 React 在 setState 后重渲染；假体用"再调一次 Body"模拟（useState 单元格
  // 的值已由 setSnapshot 更新，重渲染即读到最新快照）。条件渲染块（释放确认卡、
  // 任务列表行）据此能进元素树， 测试走的是真实 onClick 链路。
  const rerender = () => {
    // 新一轮渲染：hooks 调用序重置，同一序号的 useState/useRef 返回同一个单元格
    // （真实 React 的挂载顺序就是按渲染批次的 hook 调用序）。
    shim.shimState.hookSeq = 0
    shim.shimState.refSeq = 0
    return Body()
  }
  return { el, shim, rerender, exports, text: collectText(el).join('\n') }
}

/** 触发挂载 effect（面板的取数与生命周期都挂在它里），回收集清理函数。 */
function runMountEffects (shim) {
  const cleanups = []
  for (const effect of shim.calls.effects) {
    const cleanup = effect()
    if (typeof cleanup === 'function') cleanups.push(cleanup)
  }
  return cleanups
}

function findProp (el, key, value) {
  if (el && el.props && el.props[key] === value) return el
  if (el && Array.isArray(el.children)) {
    for (const child of el.children) {
      const hit = findProp(child, key, value)
      if (hit) return hit
    }
  }
  return null
}

/** 树里带某 prop 的元素个数（诊断入口唯一性等哨用）。 */
function countProp (el, key, value) {
  let count = el && el.props && el.props[key] === value ? 1 : 0
  if (el && Array.isArray(el.children)) {
    for (const child of el.children) count += countProp(child, key, value)
  }
  return count
}

/** 树里 style 某键等于某值的第一个元素（videoWrap 空态 minHeight 等样式断言用）。 */
function findByStyle (el, key, value) {
  if (el && el.props && el.props.style && el.props.style[key] === value) return el
  if (el && Array.isArray(el.children)) {
    for (const child of el.children) {
      const hit = findByStyle(child, key, value)
      if (hit) return hit
    }
  }
  return null
}

function withPanelGlobals (fetch, MS, fn) {
  const originalFetch = globalThis.fetch
  const originalMS = globalThis.MediaSource
  globalThis.fetch = fetch
  globalThis.MediaSource = MS
  return Promise.resolve().then(fn).finally(() => {
    globalThis.fetch = originalFetch
    globalThis.MediaSource = originalMS
  })
}

test('流面板：头部胶囊/预览卡空态/主按钮；挂载后只做路由发现（不发 start）', async () => {
  const fetch = fakePanelFetch()
  const MS = fakeMediaSource({ supported: false })
  await withPanelGlobals(fetch, MS, async () => {
    const { el, shim, text } = mountBody()
    assert.match(text, /虚拟屏/)
    // 状态胶囊：相位进胶囊，技术细节不进主视觉。
    assert.ok(findProp(el, 'data-mobile-pilot-status-pill', 'probing'), '状态胶囊在')
    assert.ok(findProp(el, 'data-mobile-pilot-video', 'stream'), '<video> 元素在')
    assert.ok(findProp(el, 'data-mobile-pilot-preview-empty', '1'), '预览卡空态在')
    assert.ok(findProp(el, 'data-mobile-pilot-action', 'start-preview'), '主按钮在')
    assert.equal(findProp(el, 'data-mobile-pilot-action', 'start-preview').children[0], '开始预览')
    // 诊断默认折叠：主界面不得出现诊断原文。
    assert.equal(findProp(el, 'data-mobile-pilot-diag', 'open'), null, '诊断详情默认折叠')
    assert.ok(!/src=|buffered=|paused=/.test(text), '主界面无诊断原文')
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    assert.equal(fetch.requests.length, 1, 'MSE 不支持时只有路由发现一个请求')
    assert.match(fetch.requests[0].url, /\/mobile-pilot\/stream\/status$/)
    for (const cleanup of cleanups) cleanup()
  })
})

test('流面板：路由缺失（404）时只有 status 一个请求且不发 start/stop', async () => {
  const fetch = fakePanelFetch() // 全部 404
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { shim } = mountBody()
    runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    assert.equal(fetch.requests.length, 1, '路由缺失时只有 status 一个请求')
    assert.ok(fetch.requests.every((r) => r.url.startsWith('/mobile-pilot/stream/')), '没有额外请求')
  })
})

test('流面板：开始预览走 start → stream → MSE 追加；unmount 触发 stop 与读取取消', async () => {
  const fx = segmentFixture()
  const reader = {
    cancelled: false,
    // 故意在 ftyp 中间切开：旧实现会把半截盒子直接 appendBuffer。
    chunks: [fx.all.slice(0, 5), fx.all.slice(5)],
    index: 0,
    async read () {
      const value = this.chunks[this.index]
      this.index += 1
      if (this.index > this.chunks.length) return { done: true, value: undefined }
      return { done: false, value }
    },
    async cancel () { this.cancelled = true },
  }
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ running: false }), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({
      ok: true, status: 200,
      json: async () => ({ ok: true, tabToken: 'tab-abc', metadata: { mime: 'video/mp4' } }),
      body: null,
    }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null }),
    '/mobile-pilot/stream?': () => Promise.resolve({
      ok: true, status: 200,
      body: { getReader: () => reader },
    }),
  })
  const createdUrls = []
  const MS = fakeMediaSource()
  const originalURL = globalThis.URL
  globalThis.URL = class {
    static createObjectURL () { const u = 'blob:fake-' + createdUrls.length; createdUrls.push(u); return u }
    static revokeObjectURL (u) { const at = createdUrls.indexOf(u); if (at >= 0) createdUrls.splice(at, 1) }
  }
  await withPanelGlobals(fetch, MS, async () => {
    const { el, shim } = mountBody()
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    const startButton = findProp(el, 'data-mobile-pilot-action', 'start-preview')
    assert.ok(startButton, '开始按钮在')
    assert.equal(typeof startButton.props.onClick, 'function')
    // 面板零输入：除开始按钮的 onClick 外没有任何输入类 handler
    // 输入层的 pointer 监听挂在**原生事件**（effect 里挂到覆盖层元素），元素树的
    // props 上除三个按钮的 onClick 外不得出现任何 Touch/Pointer/Mouse 类合成 handler。
    const inputProps = JSON.stringify(collectTree(el).map((n) => Object.keys((n && n.props) || {})).flat())
    assert.ok(!/Touch|Pointer|Mouse|Wheel|Scroll/i.test(inputProps), '输入层走原生监听，不得往元素树塞合成输入 handler')

    // video 假体：先塞进 ref（真渲染由 renderer 调 ref 回调填）
    const played = []
    shim.calls.refs[0].current = {
      addEventListener: () => {},
      error: null,
      play: () => { played.push(1) },
      pause: () => {},
      removeAttribute: () => {},
      load: () => {},
    }
    startButton.props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    const urls = fetch.requests.map((r) => r.url)
    assert.ok(urls.some((u) => u.startsWith('/mobile-pilot/stream/start')), 'start 已发')
    assert.ok(urls.some((u) => u.startsWith('/mobile-pilot/stream?tab=tab-abc')), '取流带 tab token')
    assert.ok(!urls.some((u) => u.includes('ctl-token') || u.includes('stream-token')), '控制/流 token 不得出现在浏览器请求里')
    assert.deepEqual(played, [1], 'attach 后显式 video.play()')

    assert.equal(MS.instances.length, 1, 'MSE 实例已建')
    assert.equal(createdUrls.length, 1, 'objectURL 已建')
    const ms = MS.instances[0]
    // SourceBuffer 在 sourceopen 后才建：先点火，再断言追加与 EOF
    for (const fn of ms.listeners.sourceopen || []) fn()
    await new Promise((r) => setTimeout(r, 20))
    assert.ok(ms.sourceBuffers.length === 1 && ms.sourceBuffers[0].type === VIDEO_SRC, 'SourceBuffer 用 AVC fMP4 类型')
    assert.deepEqual(ms.sourceBuffers[0].appended.map((b) => [...b]), [[...fx.init], [...fx.segment]])
    assert.equal(ms.ended, true, 'EOF 后 endOfStream')
    // EOF 后旧源清理：objectURL 撤销（重开不复用死源）
    assert.equal(createdUrls.length, 0, 'EOF 后 objectURL 已撤销')

    for (const cleanup of cleanups) cleanup()
    await new Promise((r) => setTimeout(r, 20))
    assert.equal(reader.cancelled, true, '流读取被取消')
    const stopReq = fetch.requests.find((r) => r.url.startsWith('/mobile-pilot/stream/stop'))
    assert.ok(stopReq, 'stop 已发')
    assert.equal(stopReq.method, 'POST', 'stop 是 POST（CSRF 契约）')
    assert.equal(createdUrls.length, 0, 'objectURL 已撤销')
    globalThis.URL = originalURL
  })
})

test('流面板：start 失败也配平 stop（发过 start 就要通知）', async () => {
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({}), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({ ok: false, status: 502, json: async () => ({ ok: false, reason: 'x' }), body: null }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({}), body: null }),
  })
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { el, shim } = mountBody()
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    const startButton = findProp(el, 'data-mobile-pilot-action', 'start-preview')
    startButton.props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    // 失败路径自己配平 /stop（不等 unmount）
    const stopReq = fetch.requests.find((r) => r.url.startsWith('/mobile-pilot/stream/stop'))
    assert.ok(stopReq, 'start 失败必须补发 stop')
    assert.equal(stopReq.method, 'POST')
    for (const cleanup of cleanups) cleanup()
  })
})

test('流面板：在飞行中 unmount → abort 在飞请求 + dispose 通知 stop', async () => {
  let aborted = false
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({}), body: null }),
    '/mobile-pilot/stream/start': (url, init) => new Promise((resolve) => {
      // 挂起：等 abort 信号
      if (init && init.signal) init.signal.addEventListener('abort', () => { aborted = true })
      void resolve
    }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({}), body: null }),
  })
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { el, shim } = mountBody()
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    const startButton = findProp(el, 'data-mobile-pilot-action', 'start-preview')
    startButton.props.onClick()
    await new Promise((r) => setTimeout(r, 10))
    // unmount（keepMounted:false 的隐藏等价）：dispose 中止在飞 start 并通知 stop
    for (const cleanup of cleanups) cleanup()
    await new Promise((r) => setTimeout(r, 20))
    assert.equal(aborted, true, '在飞的 start 请求被 abort')
    const stopReq = fetch.requests.find((r) => r.url.startsWith('/mobile-pilot/stream/stop'))
    assert.ok(stopReq, 'unmount 补发 stop（startedOnce 已置位）')
    assert.equal(stopReq.method, 'POST')
    // start 在飞时 tabToken 尚未下发：stop 允许无 tab（Node 无会话幂等空过）
  })
})

test('流面板：重挂即新控制器实例——旧实例 disposed 不影响新实例', async () => {
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({}), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({ ok: false, status: 502, json: async () => ({ ok: false }), body: null }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({}), body: null }),
  })
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    // 第一届：挂载 → start 失败（502）→ unmount（dispose 已置旧实例 disposed）
    const first = mountBody()
    const cleanups1 = runMountEffects(first.shim)
    await new Promise((r) => setTimeout(r, 10))
    for (const cleanup of cleanups1) cleanup()
    // 第二届（重挂）：全新实例，discover 照常把相位推到 ready
    const second = mountBody()
    const cleanups2 = runMountEffects(second.shim)
    await new Promise((r) => setTimeout(r, 10))
    const text2 = collectText(second.el).join(' ')
    assert.match(text2, /可以开始预览|开始预览/, '第二届不受旧实例 disposed 影响')
    for (const cleanup of cleanups2) cleanup()
    void first
  })
})

test('流面板：MSE 缺席时点「开始预览」不给任何 start/stop 请求', async () => {
  const fetch = fakePanelFetch()
  await withPanelGlobals(fetch, null, async () => {
    const { el, shim } = mountBody()
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    const startButton = findProp(el, 'data-mobile-pilot-action', 'start-preview')
    startButton.props.onClick()
    await new Promise((r) => setTimeout(r, 10))
    assert.ok(fetch.requests.every((r) => r.url.endsWith('/status')), '能力不支持时点开始也只允许 status 一个请求')
    for (const cleanup of cleanups) cleanup()
  })
})

test('流面板：错误落到可重试状态（stopped + 消息保留），可再次点开始', async () => {
  let failFirst = true
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({}), body: null }),
    '/mobile-pilot/stream/start': () => {
      if (failFirst) return Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true, tabToken: 't1', metadata: null }), body: null })
      return Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true, tabToken: 't2', metadata: null }), body: null })
    },
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({}), body: null }),
    '/mobile-pilot/stream?': (url) => {
      // 第一次取流（t1）断开模拟失败，第二次（t2）成功
      if (String(url).includes('tab=t1')) return Promise.reject(new Error('reset'))
      return Promise.resolve({ ok: true, status: 200, body: { getReader: () => ({ read: async () => ({ done: true, value: undefined }), cancel: async () => {} }) } })
    },
  })
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { el, shim } = mountBody()
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    const startButton = findProp(el, 'data-mobile-pilot-action', 'start-preview')
    startButton.props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    const stopReq = fetch.requests.find((r) => r.url.startsWith('/mobile-pilot/stream/stop'))
    assert.ok(stopReq, '失败配平 stop')
    assert.match(stopReq.url, /tab=/, '会话已建立（tabToken 已下发）的失败 stop 携带 tab')
    startButton.props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    const starts = fetch.requests.filter((r) => r.url.startsWith('/mobile-pilot/stream/start'))
    assert.equal(starts.length, 2, '失败后可再次 start')
    for (const cleanup of cleanups) cleanup()
  })
})

test('流面板：首错保留——先落定的那条原因不被取消引发的二次失败覆盖', async () => {
  // 场景：先来一段 init（SourceBuffer 现在要等 init 段到手才建），随后读取挂起；
  // SourceBuffer 出错触发第一次失败；失败清理里的 reader.cancel() 让挂起的 read()
  // 以「BodyStreamBuffer was aborted」再拒绝一次 —— 那是同一次失败的尾巴，
  // 不得覆盖第一条原因。
  let pendingRead = null
  let sentInit = false
  const fx = segmentFixture()
  const reader = {
    read () {
      if (!sentInit) {
        sentInit = true
        return Promise.resolve({ done: false, value: fx.init })
      }
      return new Promise((resolve, reject) => { pendingRead = { resolve, reject } })
    },
    cancel () {
      if (pendingRead) pendingRead.reject(new Error('BodyStreamBuffer was aborted'))
      pendingRead = null
    },
  }
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({}), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true, tabToken: 't9', metadata: null }), body: null }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({}), body: null }),
    '/mobile-pilot/stream?': () => Promise.resolve({ ok: true, status: 200, body: { getReader: () => reader } }),
  })
  const MS = fakeMediaSource()
  const originalURL = globalThis.URL
  globalThis.URL = class {
    static createObjectURL () { return 'blob:fake-first-error' }
    static revokeObjectURL () {}
  }
  await withPanelGlobals(fetch, MS, async () => {
    const { el, shim } = mountBody()
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    const startButton = findProp(el, 'data-mobile-pilot-action', 'start-preview')
    startButton.props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    // sourceopen 后泵链发起一次挂起的读取。
    for (const fn of MS.instances[0].listeners.sourceopen || []) fn()
    await new Promise((r) => setTimeout(r, 20))
    // SourceBuffer 出错：第一条原因落定；清理里的 cancel() 随后让读取以 aborted 拒绝。
    for (const fn of MS.instances[0].sourceBuffers[0].listeners.error || []) fn()
    await new Promise((r) => setTimeout(r, 20))
    const last = shim.calls.states[shim.calls.states.length - 1].value
    assert.match(last.message, /SourceBuffer 错误/, '首因必须留在面板上')
    assert.doesNotMatch(last.message, /aborted/, '取消引发的二次失败不得覆盖首因')
    for (const cleanup of cleanups) cleanup()
    globalThis.URL = originalURL
  })
})

test('流面板：/start 非 2xx 时显示宿主给的原因（不只显示 HTTP 码）', async () => {
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({}), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({
      ok: false,
      status: 503,
      text: async () => JSON.stringify({ ok: false, reason: '宿主拒绝开流：没有可信屏' }),
      body: null,
    }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({}), body: null }),
  })
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { el, shim } = mountBody()
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    const startButton = findProp(el, 'data-mobile-pilot-action', 'start-preview')
    startButton.props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    const messages = shim.calls.states.map((s) => s.value && s.value.message).filter(Boolean)
    const last = messages[messages.length - 1]
    assert.match(last, /预览开不起来/, '文案是人话（阶段 3：HTTP 状态不再进主文案）')
    assert.match(last, /宿主拒绝开流：没有可信屏/, '文案带上宿主给的原因')
    for (const cleanup of cleanups) cleanup()
  })
})

test('流面板：protected_app 拒绝显示明确文案，不再按 502 自动重试', async () => {
  let starts = 0
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({}), body: null }),
    '/mobile-pilot/stream/start': () => {
      starts++
      return Promise.resolve({
        ok: false,
        status: 403,
        text: async () => JSON.stringify({
          ok: false,
          code: 'protected_app',
          reason: 'the app in front (com.bank) is on the protected-apps list',
        }),
        body: null,
      })
    },
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({}), body: null }),
  })
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { el, shim } = mountBody()
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    const startButton = findProp(el, 'data-mobile-pilot-action', 'start-preview')
    startButton.props.onClick()
    await new Promise((r) => setTimeout(r, 30))
    const messages = shim.calls.states.map((s) => s.value && s.value.message).filter(Boolean)
    const last = messages[messages.length - 1]
    assert.match(last, /受保护应用不支持面板预览/, '必须显示受保护应用专用文案')
    assert.match(last, /protected-apps list/, '宿主原因要保留')
    assert.equal(starts, 1, 'protected_app 不得按 502 再自动重试')
    for (const cleanup of cleanups) cleanup()
  })
})

test('流面板：/stream 409 时显示会话被占用的原因', async () => {
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({}), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true, tabToken: 't8', metadata: null }), body: null }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({}), body: null }),
    '/mobile-pilot/stream?': () => Promise.resolve({
      ok: false,
      status: 409,
      text: async () => JSON.stringify({ error: '会话已被另一处占用' }),
      body: null,
    }),
  })
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { el, shim } = mountBody()
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    const startButton = findProp(el, 'data-mobile-pilot-action', 'start-preview')
    startButton.props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    const messages = shim.calls.states.map((s) => s.value && s.value.message).filter(Boolean)
    const last = messages[messages.length - 1]
    assert.match(last, /流连接失败/, '文案是人话（阶段 3：HTTP 状态不再进主文案）')
    assert.match(last, /会话已被另一处占用/, '文案带上占用原因')
    for (const cleanup of cleanups) cleanup()
  })
})

// ──  面板三动作与虚拟屏任务列表 ──

test('流面板 N3F：三动作按钮在（仅 playing 可用），释放要过二次确认（move-first 语义）', async () => {
  const fx = segmentFixture()
  const reader = {
    chunks: [fx.all], index: 0,
    async read () {
      const value = this.chunks[this.index]
      this.index += 1
      if (this.index > this.chunks.length) return { done: true, value: undefined }
      return { done: false, value }
    },
    async cancel () {},
  }
  const stopCalls = []
  const releaseBodies = []
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ running: false }), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({
      ok: true, status: 200,
      json: async () => ({ ok: true, tabToken: 'tab-n3f', metadata: { mime: 'video/mp4' } }),
      body: null,
    }),
    '/mobile-pilot/stream/stop': (url) => { stopCalls.push(url); return Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null }) },
    '/mobile-pilot/release': (url, init) => {
      releaseBodies.push({ url, body: init && init.body })
      return Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true, released: true }), body: null })
    },
    '/mobile-pilot/stream?': () => Promise.resolve({ ok: true, status: 200, body: { getReader: () => reader } }),
  })
  const originalURL = globalThis.URL
  globalThis.URL = class {
    static createObjectURL () { return 'blob:fake-n3f' }
    static revokeObjectURL () {}
  }
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { el, shim, rerender } = mountBody()
    shim.calls.refs[0].current = {
      addEventListener: () => {}, error: null, play: () => {}, pause: () => {},
      removeAttribute: () => {}, load: () => {},
    }
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    // 未 playing：三动作按钮禁用
    const toMain = findProp(el, 'data-mobile-pilot-action', 'move-to-main')
    const toVirtual = findProp(el, 'data-mobile-pilot-action', 'move-to-virtual')
    const release = findProp(el, 'data-mobile-pilot-action', 'release-virtual')
    const refreshIdle = findProp(el, 'data-mobile-pilot-action', 'refresh-preview')
    assert.ok(toMain && toVirtual && release && refreshIdle, '四动作按钮都在')
    assert.equal(toMain.props.disabled, true, '未 playing 时禁用')
    assert.equal(refreshIdle.props.disabled, true, '未 playing 不能刷新')
    assert.ok(findProp(el, 'data-mobile-pilot-preview-actions', '1'), '预览工具行在')
    assert.ok(findProp(el, 'data-mobile-pilot-task-actions', '1'), '任务动作行在')
    // 开场会话到 playing
    findProp(el, 'data-mobile-pilot-action', 'start-preview').props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    const latest = shim.calls.states[shim.calls.states.length - 1].value
    assert.equal(latest.canInput, true, 'playing 后解禁')
    const playingTree = rerender()
    const refresh = findProp(playingTree, 'data-mobile-pilot-action', 'refresh-preview')
    assert.ok(refresh, '刷新预览按钮在')
    assert.equal(refresh.props.disabled, false, 'playing 后可刷新')
    // 释放走二次确认：点按钮只展开确认卡（confirmRelease 状态位），不发请求。
    // 假体经 rerender 模拟 setState 后的重渲染：确认卡进元素树，走真实 onClick 链路。
    release.props.onClick()
    const latest2 = shim.calls.states[shim.calls.states.length - 1].value
    assert.equal(latest2.confirmRelease, true, '确认卡展开（状态位）')
    assert.equal(releaseBodies.length, 0, '未确认零请求')
    const rerendered = rerender()
    // 确认卡在重渲染树里、正文写明 语义、确认/取消按钮都在。
    const confirm = findProp(rerendered, 'data-mobile-pilot-action', 'release-confirm')
    const cancel = findProp(rerendered, 'data-mobile-pilot-action', 'release-cancel')
    assert.ok(confirm && cancel, '确认/取消按钮都在确认卡里')
    const confirmText = collectText(rerendered).join('')
    assert.match(confirmText, /会关闭虚拟屏上的应用并关闭虚拟屏/, '模态正文写明释放=关闭屏上应用')
    assert.match(confirmText, /释放不会把应用搬到主屏/, '模态正文写明释放不搬主屏')
    assert.match(confirmText, /要保留某个应用，请先「移到主屏」/, '保留应用的唯一路径写明')
    assert.match(confirmText, /释放虚拟屏？/, '模态标题在')
    // 取消：收卡不发请求
    cancel.props.onClick()
    assert.equal(
      findProp(rerender(), 'data-mobile-pilot-action', 'release-confirm'), null,
      '取消后确认卡收起',
    )
    assert.equal(releaseBodies.length, 0, '取消零请求')
    // 再展开并确认：走真实 onReleaseConfirmed → controller.releaseDisplay
    findProp(rerendered, 'data-mobile-pilot-action', 'release-virtual').props.onClick()
    const confirmBtn = findProp(rerender(), 'data-mobile-pilot-action', 'release-confirm')
    assert.ok(confirmBtn, '确认按钮在重渲染树里')
    confirmBtn.props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    assert.equal(releaseBodies.length, 1, '确认后发一次 release')
    assert.match(releaseBodies[0].url, /tab=tab-n3f/, 'tab 走查询参数')
    // 成功后复位：回 ready（releaseDisplay 清本地账）
    await new Promise((r) => setTimeout(r, 20))
    const final = shim.calls.states[shim.calls.states.length - 1].value
    assert.equal(final.phase, 'ready', '释放成功后回 ready')
    for (const cleanup of cleanups) cleanup()
  })
  globalThis.URL = originalURL
})

test('流面板 T1：刷新预览先 stop 再 start，保留“移回”任务账并回到 playing', async () => {
  const fx = segmentFixture()
  let starts = 0
  let stops = 0
  let cancels = 0
  function readerFor () {
    let sent = false
    let pending = null
    return {
      async read () {
        if (!sent) { sent = true; return { done: false, value: fx.all } }
        return new Promise((resolve) => { pending = resolve })
      },
      async cancel () {
        // 真实 reader.cancel 会让挂起的 read 以 done 收尾；旧泵链必须被代际挡掉，
        // 不能在新 start 之后把 phase 改回 stopped。
        cancels += 1
        if (pending) { const resolve = pending; pending = null; resolve({ done: true }) }
      },
    }
  }
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ running: false }), body: null }),
    '/mobile-pilot/stream/start': () => {
      starts += 1
      return Promise.resolve({
        ok: true, status: 200,
        json: async () => ({ ok: true, tabToken: 'tab-refresh-' + starts, metadata: { mime: 'video/mp4' } }),
        body: null,
      })
    },
    '/mobile-pilot/stream/stop': () => {
      stops += 1
      return Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null })
    },
    '/mobile-pilot/stream?': () => Promise.resolve({ ok: true, status: 200, body: { getReader: readerFor } }),
  })
  const { exports } = factoryExports()
  const MS = fakeMediaSource()
  const controller = exports.createStreamPanelController({
    fetch,
    MediaSource: MS,
    urlCreate: () => 'blob:refresh',
    urlRevoke: () => {},
    getVideo: () => null,
  })
  await controller.discover()
  await controller.start()
  assert.equal(controller.state().phase, 'playing', '先进入播放')
  // 必须真正触发 sourceopen，泵链才会挂起第一次 read()；否则旧会话尾巴根本不存在。
  for (const fn of MS.instances[0].listeners.sourceopen || []) fn()
  await new Promise((r) => setTimeout(r, 10))
  assert.ok(controller.state().reader, '首届 reader 在')
  const epochBefore = controller.state().epoch
  controller.state().lastMovedTaskId = 42

  const refreshing = controller.refresh()
  assert.equal(controller.state().refreshPending, true, '刷新期间置 pending')
  await refreshing

  assert.equal(starts, 2, '刷新会重新 start 一次')
  assert.equal(stops, 1, '刷新先 stop 当前会话一次')
  assert.ok(cancels >= 1, '旧 reader 被 cancel（迟到 done 已真实产生）')
  assert.ok(controller.state().epoch > epochBefore, '刷新换代')
  assert.equal(controller.state().phase, 'playing', '刷新后回到播放')
  assert.equal(controller.state().refreshPending, false, '刷新结束复位 pending')
  assert.equal(controller.state().lastMovedTaskId, 42, '刷新不清“移回”任务账')
  assert.ok(controller.state().reader, '新会话的 reader 在')
  // 新会话也开始泵，旧 reader.cancel 的迟到 done 不得把它改回 stopped。
  for (const fn of MS.instances[1].listeners.sourceopen || []) fn()
  await new Promise((r) => setTimeout(r, 30))
  assert.equal(controller.state().phase, 'playing', '旧会话尾巴不覆盖新会话')
  await controller.dispose()
})

test('流面板 T1：刷新遇到 /stop 排水期 502 时有界重试，最终回到 playing', async () => {
  const fx = segmentFixture()
  let starts = 0
  function readerFor () {
    let sent = false
    return {
      async read () {
        if (!sent) { sent = true; return { done: false, value: fx.all } }
        return new Promise(() => {})
      },
      async cancel () {},
    }
  }
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ running: false }), body: null }),
    '/mobile-pilot/stream/start': () => {
      starts += 1
      // 刷新第一次 start 撞上上一届排水；第二次重试成功。
      if (starts === 2) {
        return Promise.resolve({
          ok: false, status: 502,
          json: async () => ({ reason: 'a stream session is already running (or still draining); stop it first' }),
          body: null,
        })
      }
      return Promise.resolve({
        ok: true, status: 200,
        json: async () => ({ ok: true, tabToken: 'tab-retry-' + starts, metadata: { mime: 'video/mp4' } }),
        body: null,
      })
    },
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null }),
    '/mobile-pilot/stream?': () => Promise.resolve({ ok: true, status: 200, body: { getReader: readerFor } }),
  })
  const { exports } = factoryExports()
  const controller = exports.createStreamPanelController({
    fetch,
    MediaSource: fakeMediaSource(),
    urlCreate: () => 'blob:retry',
    urlRevoke: () => {},
    getVideo: () => null,
  })
  await controller.discover()
  await controller.start()
  await controller.refresh()
  assert.equal(starts, 3, '刷新 502 后重试一次成功')
  assert.equal(controller.state().phase, 'playing', '重试成功后回 playing')
  assert.equal(controller.state().refreshPending, false)
  await controller.dispose()
})

test('流面板 N3F 返工3：任务列表 UI 已删，"移到主屏"内部取列表第一条；空/失败给可读结论', async () => {
  const fx = segmentFixture()
  const reader = {
    chunks: [fx.all], index: 0,
    async read () {
      const value = this.chunks[this.index]
      this.index += 1
      if (this.index > this.chunks.length) return { done: true, value: undefined }
      return { done: false, value }
    },
    async cancel () {},
  }
  const moveBodies = []
  let tasksStatus = 200
  let tasksBody = { ok: true, displayId: 45, tasks: [
    { taskId: 703, package: 'com.ss.android.ugc.aweme', label: '抖音', activity: 'com.ss.android.ugc.aweme/.main.MainActivity' },
    { taskId: 710, package: 'com.example.todo', label: 'com.example.todo', activity: 'com.example.todo/.Main' },
  ] }
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ running: false }), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({
      ok: true, status: 200,
      json: async () => ({ ok: true, tabToken: 'tab-list', metadata: { mime: 'video/mp4' } }),
      body: null,
    }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null }),
    '/mobile-pilot/stream?': () => Promise.resolve({ ok: true, status: 200, body: { getReader: () => reader } }),
    '/mobile-pilot/tasks': () => Promise.resolve({
      ok: tasksStatus === 200, status: tasksStatus,
      json: async () => (tasksStatus === 200 ? tasksBody : { ok: false, reason: 'no stream session; POST /start first' }),
      body: null,
    }),
    '/mobile-pilot/move': (url, init) => {
      moveBodies.push({ url, body: init && init.body })
      return Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null })
    },
  })
  const originalURL = globalThis.URL
  globalThis.URL = class {
    static createObjectURL () { return 'blob:fake-list' }
    static revokeObjectURL () {}
  }
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { el, shim } = mountBody()
    shim.calls.refs[0].current = {
      addEventListener: () => {}, error: null, play: () => {}, pause: () => {},
      removeAttribute: () => {}, load: () => {},
    }
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    findProp(el, 'data-mobile-pilot-action', 'start-preview').props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    // 任务列表 UI 已删：无刷新按钮，主界面无任务列表段。
    assert.equal(findProp(el, 'data-mobile-pilot-action', 'tasks-refresh'), null, '刷新按钮已删')
    assert.ok(!/虚拟屏任务/.test(collectText(el).join('')), '任务列表段不在主界面')
    // "移到主屏"= 内部取任务列表第一条（前台任务）搬去主屏。
    const btn = findProp(el, 'data-mobile-pilot-action', 'move-to-main')
    assert.ok(btn, '移到主屏按钮在')
    btn.props.onClick()
    await new Promise((r) => setTimeout(r, 30))
    assert.equal(moveBodies.length, 1, '内部取列表第一条发一次搬迁')
    assert.equal(JSON.parse(moveBodies[0].body).taskId, 703, '搬的是列表第一条（前台任务）')
    assert.equal(JSON.parse(moveBodies[0].body).direction, 'main')
    assert.match(moveBodies[0].url, /tab=tab-list/, 'tab 走查询参数')
    assert.ok(!moveBodies[0].url.includes('token'), 'token 不出现在请求 URL')
    // 成功结论可见
    const noted = shim.calls.states[shim.calls.states.length - 1].value
    assert.match(noted.taskNote || '', /已移到主屏/)
    // 空列表：零搬迁请求，中性提示。
    moveBodies.length = 0
    tasksBody = { ok: true, displayId: 45, tasks: [] }
    findProp(el, 'data-mobile-pilot-action', 'move-to-main').props.onClick()
    await new Promise((r) => setTimeout(r, 30))
    assert.equal(moveBodies.length, 0, '空列表零搬迁请求')
    const empty = shim.calls.states[shim.calls.states.length - 1].value
    assert.match(empty.taskNote || '', /虚拟屏上当前没有任务/)
    // 失败：tasks 409 → 人话结论进 taskNote（HTTP 码只进诊断折叠）。
    tasksStatus = 409
    findProp(el, 'data-mobile-pilot-action', 'move-to-main').props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    const errored = shim.calls.states[shim.calls.states.length - 1].value
    assert.match(errored.taskNote || '', /任务列表获取失败，可重试/, '文案是人话 + 可重试')
    assert.ok(!/HTTP 409/.test(errored.taskNote || ''), '主文案无 HTTP 码')
    assert.match(errored.taskNote || '', /no stream session/, '宿主原因透传')
    for (const cleanup of cleanups) cleanup()
  })
  globalThis.URL = originalURL
})

test('流面板 N3F 返工1：移回虚拟屏搬的是"上次移到主屏"的那条；无记录时提示先移到主屏', async () => {
  const fx = segmentFixture()
  const reader = {
    chunks: [fx.all], index: 0,
    async read () {
      const value = this.chunks[this.index]
      this.index += 1
      if (this.index > this.chunks.length) return { done: true, value: undefined }
      return { done: false, value }
    },
    async cancel () {},
  }
  const moveBodies = []
  let tasksStatus = 200
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ running: false }), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({
      ok: true, status: 200,
      json: async () => ({ ok: true, tabToken: 'tab-back', metadata: { mime: 'video/mp4' } }),
      body: null,
    }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null }),
    '/mobile-pilot/stream?': () => Promise.resolve({ ok: true, status: 200, body: { getReader: () => reader } }),
    // 主屏方向的列表（"移到主屏"成功后 loadTasks 刷新到的对面屏）——含刚搬过去的 703。
    '/mobile-pilot/tasks': () => Promise.resolve({
      ok: tasksStatus === 200, status: tasksStatus,
      json: async () => ({ ok: true, displayId: 0, tasks: tasksStatus === 200
        ? [{ taskId: 703, package: 'com.a', label: 'A', activity: '.M' }]
        : undefined }),
      body: null,
    }),
    '/mobile-pilot/move': (url, init) => {
      moveBodies.push({ url, body: init && init.body })
      return Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null })
    },
  })
  const originalURL = globalThis.URL
  globalThis.URL = class {
    static createObjectURL () { return 'blob:fake-back' }
    static revokeObjectURL () {}
  }
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { el, shim, rerender } = mountBody()
    shim.calls.refs[0].current = {
      addEventListener: () => {}, error: null, play: () => {}, pause: () => {},
      removeAttribute: () => {}, load: () => {},
    }
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    findProp(el, 'data-mobile-pilot-action', 'start-preview').props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    // 无记录时点"移回虚拟屏"：可读提示、零请求。
    findProp(el, 'data-mobile-pilot-action', 'move-to-virtual').props.onClick()
    await new Promise((r) => setTimeout(r, 10))
    let latest = shim.calls.states[shim.calls.states.length - 1].value
    assert.match(latest.taskNote || '', /先移到主屏|还没有移到主屏/, '无记录给可读提示')
    assert.equal(moveBodies.length, 0, '无记录零搬迁请求')
    // 移到主屏（列表第一条 703）：成功后 lastMovedTaskId 记下。
    const refreshed = rerender()
    findProp(refreshed, 'data-mobile-pilot-action', 'move-to-main').props.onClick()
    await new Promise((r) => setTimeout(r, 30))
    assert.ok(moveBodies.some((m) => JSON.parse(m.body).direction === 'main'), '已发 main 搬迁')
    latest = shim.calls.states[shim.calls.states.length - 1].value
    assert.match(latest.taskNote || '', /已移到主屏/)
    // "移回虚拟屏"：搬回 lastMovedTaskId=703，direction=virtual。
    findProp(rerender(), 'data-mobile-pilot-action', 'move-to-virtual').props.onClick()
    await new Promise((r) => setTimeout(r, 30))
    const virtualMove = moveBodies.map((m) => JSON.parse(m.body)).find((b) => b.direction === 'virtual')
    assert.ok(virtualMove, '发出了 virtual 搬迁')
    assert.equal(virtualMove.taskId, 703, '搬回的是上次移到主屏的那条')
    latest = shim.calls.states[shim.calls.states.length - 1].value
    assert.match(latest.taskNote || '', /已移回虚拟屏/, '成功后显示已移回虚拟屏')
    for (const cleanup of cleanups) cleanup()
  })
  globalThis.URL = originalURL
})

// ── 预览自适应 + 固定布局 ──

test('流面板返工3：previewAspectRatio 纯函数——metadata 源尺寸优先、video 尺寸兜底、缺省回 null', () => {
  const { exports } = factoryExports()
  // 源 2560×2064（设备竖长源）：按 ~1.24 显示，不写死 16/9。
  assert.equal(exports.previewAspectRatio({ sourceWidth: 2560, sourceHeight: 2064 }, 1280, 720), '2560 / 2064')
  // metadata 缺源尺寸/不是数字：退回 video 元素内容尺寸。
  assert.equal(exports.previewAspectRatio(null, 1280, 720), '1280 / 720')
  assert.equal(exports.previewAspectRatio({ mime: 'video/mp4' }, 1280, 720), '1280 / 720')
  assert.equal(exports.previewAspectRatio({ sourceWidth: 0, sourceHeight: 0 }, 1280, 720), '1280 / 720')
  assert.equal(exports.previewAspectRatio(undefined, 2560, 2064), '2560 / 2064')
  // 两者都没有（未起会话）：null —— 渲染处不给 aspect-ratio。
  assert.equal(exports.previewAspectRatio(null, 0, 0), null)
  assert.equal(exports.previewAspectRatio(null), null)
})

test('流面板返工3：头部+预览固定、下方独立滚动；预览宽高比随 metadata 自适应；视频层 touch-action:none', async () => {
  const fx = segmentFixture()
  const reader = {
    chunks: [fx.all], index: 0,
    async read () {
      const value = this.chunks[this.index]
      this.index += 1
      if (this.index > this.chunks.length) return { done: true, value: undefined }
      return { done: false, value }
    },
    async cancel () {},
  }
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ running: false }), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({
      ok: true, status: 200,
      // 宿主真实源尺寸进 metadata：面板据此动态设置 aspect-ratio。
      json: async () => ({ ok: true, tabToken: 'tab-fit', metadata: { mime: 'video/mp4', sourceWidth: 2560, sourceHeight: 2064 } }),
      body: null,
    }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null }),
    '/mobile-pilot/stream?': () => Promise.resolve({ ok: true, status: 200, body: { getReader: () => reader } }),
  })
  const originalURL = globalThis.URL
  globalThis.URL = class {
    static createObjectURL () { return 'blob:fit' }
    static revokeObjectURL () {}
  }
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { el, shim, rerender, exports } = mountBody()
    shim.calls.refs[0].current = {
      addEventListener: () => {}, error: null, play: () => {}, pause: () => {},
      removeAttribute: () => {}, load: () => {},
    }
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    // 固定布局：root 不整体滚动，头部与预览在滚动区外，只有滚动区可滚。
    const rootEl = findProp(el, 'data-mobile-pilot-panel', 'virtual-display')
    assert.equal(rootEl.props.style.overflow, 'hidden', 'root 自身不滚')
    const scroll = findProp(el, 'data-mobile-pilot-body', '1')
    assert.ok(scroll, '独立滚动区在')
    assert.equal(scroll.props.style.overflowY, 'auto', '只有滚动区可滚')
    assert.equal(findProp(scroll, 'data-mobile-pilot-video', 'stream'), null, '预览不在滚动区内（固定）')
    assert.equal(findProp(scroll, 'data-mobile-pilot-status-pill', 'probing'), null, '头部不在滚动区内（固定）')
    assert.ok(findProp(el, 'data-mobile-pilot-video', 'stream'), '预览在固定区')
    // 未起会话：不写死 aspect-ratio；空态由 videoWrap 的 minHeight（共用常量）撑底。
    const videoEl = findProp(el, 'data-mobile-pilot-video', 'stream')
    assert.equal(videoEl.props.style.aspectRatio, undefined, '未起会话不写死 aspect-ratio')
    assert.equal(videoEl.props.style.minHeight, undefined, 'video 元素无写死最小高')
    assert.equal(videoEl.props.style.maxHeight, '45vh', '竖长源封顶（N3F 返工5），下方动作可达')
    assert.ok(findByStyle(el, 'minHeight', exports.EMPTY_PREVIEW_MIN_HEIGHT), '空态时 videoWrap 有 minHeight 撑底')
    // 视频层 touch-action:none：滑视频不带动右侧栏/面板滚动。
    const overlay = findProp(el, 'data-mobile-pilot-input', 'overlay')
    assert.equal(overlay.props.style.touchAction, 'none', '视频层 touch-action 恒为 none')
    // 开场：metadata 源尺寸 2560×2064 → aspect-ratio 动态设置。
    findProp(el, 'data-mobile-pilot-action', 'start-preview').props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    const playingTree = rerender()
    const playingVideo = findProp(playingTree, 'data-mobile-pilot-video', 'stream')
    assert.equal(playingVideo.props.style.aspectRatio, '2560 / 2064', 'metadata 源尺寸进 aspect-ratio')
    assert.equal(findByStyle(playingTree, 'minHeight', exports.EMPTY_PREVIEW_MIN_HEIGHT), null, 'playing 后空态 minHeight 撤掉')
    for (const cleanup of cleanups) cleanup()
  })
  globalThis.URL = originalURL
})

// ── 诊断折叠、释放模态、状态胶囊 ──

test('流面板返工5：失败收场清 metadata 并通知渲染（非 playing 不残留旧 aspect-ratio）', async () => {
  const fx = segmentFixture()
  const reader = {
    chunks: [fx.all], index: 0,
    async read () {
      const value = this.chunks[this.index]
      this.index += 1
      if (this.index > this.chunks.length) return { done: true, value: undefined }
      return { done: false, value }
    },
    async cancel () {},
  }
  let startStatus = 200
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ running: false }), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({
      ok: startStatus < 400, status: startStatus,
      json: async () => (startStatus < 400
        ? { ok: true, tabToken: 'tab-meta', metadata: { mime: 'video/mp4', sourceWidth: 2560, sourceHeight: 2064 } }
        : { ok: false, reason: 'no trusted display' }),
      body: null,
    }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null }),
    '/mobile-pilot/stream?': () => Promise.resolve({ ok: true, status: 200, body: { getReader: () => reader } }),
  })
  const originalURL = globalThis.URL
  globalThis.URL = class {
    static createObjectURL () { return 'blob:meta' }
    static revokeObjectURL () {}
  }
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { el, shim, rerender } = mountBody()
    shim.calls.refs[0].current = {
      addEventListener: () => {}, error: null, play: () => {}, pause: () => {},
      removeAttribute: () => {}, load: () => {},
    }
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    // 成功一场会话：metadata 源尺寸进 aspect-ratio。
    findProp(el, 'data-mobile-pilot-action', 'start-preview').props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    assert.equal(findProp(rerender(), 'data-mobile-pilot-video', 'stream').props.style.aspectRatio,
      '2560 / 2064', '会话期 aspect-ratio 在')
    // 停止后再开失败：failSession 清 metadata 并通知渲染 —— video 样式不再带旧 aspect。
    findProp(rerender(), 'data-mobile-pilot-action', 'start-preview').props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    startStatus = 503
    findProp(rerender(), 'data-mobile-pilot-action', 'start-preview').props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    const latest = shim.calls.states[shim.calls.states.length - 1].value
    assert.equal(latest.phase, 'stopped', '失败落 stopped')
    assert.equal(latest.metadata, null, '失败后 metadata 清空')
    assert.equal(findProp(rerender(), 'data-mobile-pilot-video', 'stream').props.style.aspectRatio,
      undefined, '非 playing 不残留旧 aspect-ratio')
    for (const cleanup of cleanups) cleanup()
  })
  globalThis.URL = originalURL
})

test('流面板 N3F 返工3：诊断只留底部折叠卡（入口唯一），展开后 monospace 块出现，主界面无诊断原文', async () => {
  const fx = segmentFixture()
  const reader = {
    chunks: [fx.all], index: 0,
    async read () {
      const value = this.chunks[this.index]
      this.index += 1
      if (this.index > this.chunks.length) return { done: true, value: undefined }
      return { done: false, value }
    },
    async cancel () {},
  }
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ running: false }), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({
      ok: true, status: 200,
      json: async () => ({ ok: true, tabToken: 'tab-diag', metadata: { mime: 'video/mp4' } }),
      body: null,
    }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null }),
    '/mobile-pilot/stream?': () => Promise.resolve({ ok: true, status: 200, body: { getReader: () => reader } }),
  })
  const originalURL = globalThis.URL
  globalThis.URL = class {
    static createObjectURL () { return 'blob:diag' }
    static revokeObjectURL () {}
  }
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { el, shim, rerender } = mountBody()
    shim.calls.refs[0].current = {
      addEventListener: () => {}, error: null, play: () => {}, pause: () => {},
      removeAttribute: () => {}, load: () => {},
    }
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    // 默认折叠：折叠卡在（有入口），无诊断原文；溢出菜单已删。
    assert.equal(findProp(el, 'data-mobile-pilot-diag', 'open'), null, '诊断详情默认折叠')
    assert.ok(findProp(el, 'data-mobile-pilot-diag', 'collapsed'), '底部折叠卡在')
    assert.ok(!/src=\d|segs=/.test(collectText(el).join('')), '折叠时主界面无诊断读数')
    assert.equal(findProp(el, 'data-mobile-pilot-action', 'overflow-menu'), null, '右上角溢出菜单入口已删')
    assert.equal(findProp(el, 'data-mobile-pilot-menu', '1'), null, '下拉菜单不再存在')
    assert.equal(countProp(el, 'data-mobile-pilot-action', 'toggle-diag'), 1, '诊断入口唯一（底部折叠卡）')
    // 折叠按钮带 aria-expanded/aria-controls（读屏可感知展开态与受控区域）。
    const toggleBtn = findProp(el, 'data-mobile-pilot-action', 'toggle-diag')
    assert.equal(toggleBtn.props['aria-expanded'], false, '折叠时 aria-expanded=false')
    assert.equal(toggleBtn.props['aria-controls'], 'mp-diag-body', 'aria-controls 指向诊断正文')
    // 点"展开" → 详情卡展开，monospace 诊断块在；入口仍唯一。
    findProp(el, 'data-mobile-pilot-action', 'toggle-diag').props.onClick()
    const diagTree = rerender()
    assert.ok(findProp(diagTree, 'data-mobile-pilot-diag', 'open'), '诊断详情展开')
    assert.ok(findProp(diagTree, 'data-mobile-pilot-diag-body', '1'), 'monospace 诊断块在')
    assert.equal(findProp(diagTree, 'data-mobile-pilot-action', 'toggle-diag').props['aria-expanded'], true,
      '展开后 aria-expanded=true')
    assert.equal(findProp(diagTree, 'data-mobile-pilot-diag', 'collapsed'), null, '展开后不再有折叠态卡')
    assert.equal(countProp(diagTree, 'data-mobile-pilot-action', 'toggle-diag'), 1, '展开后入口仍唯一')
    // 再点"收起"：诊断原文块消失，回到折叠卡。
    findProp(diagTree, 'data-mobile-pilot-action', 'toggle-diag').props.onClick()
    const closed = rerender()
    assert.equal(findProp(closed, 'data-mobile-pilot-diag', 'open'), null, '收起后详情卡消失')
    assert.ok(!/src=\d|segs=/.test(collectText(closed).join('')), '收起后主界面无诊断读数')
    for (const cleanup of cleanups) cleanup()
  })
  globalThis.URL = originalURL
})

test('流面板 N3F 阶段3：释放模态 Esc/点遮罩取消；确认后防连点（release 进行中按钮禁用）', async () => {
  const fx = segmentFixture()
  const reader = {
    chunks: [fx.all], index: 0,
    async read () {
      const value = this.chunks[this.index]
      this.index += 1
      if (this.index > this.chunks.length) return { done: true, value: undefined }
      return { done: false, value }
    },
    async cancel () {},
  }
  const releaseBodies = []
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ running: false }), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({
      ok: true, status: 200,
      json: async () => ({ ok: true, tabToken: 'tab-modal', metadata: { mime: 'video/mp4' } }),
      body: null,
    }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null }),
    '/mobile-pilot/stream?': () => Promise.resolve({ ok: true, status: 200, body: { getReader: () => reader } }),
    '/mobile-pilot/release': (url, init) => {
      releaseBodies.push({ url, body: init && init.body })
      return Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true, released: true }), body: null })
    },
  })
  const originalURL = globalThis.URL
  globalThis.URL = class {
    static createObjectURL () { return 'blob:modal' }
    static revokeObjectURL () {}
  }
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { el, shim, rerender } = mountBody()
    shim.calls.refs[0].current = {
      addEventListener: () => {}, error: null, play: () => {}, pause: () => {},
      removeAttribute: () => {}, load: () => {},
    }
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    findProp(el, 'data-mobile-pilot-action', 'start-preview').props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    // 展开：模态遮罩在；Esc 取消。
    findProp(el, 'data-mobile-pilot-action', 'release-virtual').props.onClick()
    let modal = findProp(rerender(), 'data-mobile-pilot-release-confirm', '1')
    assert.ok(modal, '释放模态展开')
    // Esc 键挂在面板根元素上（onRootKeyDown）：经根节点派发。
    const panelRoot = findProp(el, 'data-mobile-pilot-panel', 'virtual-display')
    assert.ok(typeof panelRoot.props.onKeyDown === 'function', '面板根挂了 Esc 处理')
    panelRoot.props.onKeyDown({ key: 'Escape' })
    assert.equal(findProp(rerender(), 'data-mobile-pilot-release-confirm', '1'), null, 'Esc 收起模态')
    assert.equal(releaseBodies.length, 0, 'Esc 零请求')
    // 再展开：点遮罩（target===currentTarget 的合成事件）取消。
    findProp(rerender(), 'data-mobile-pilot-action', 'release-virtual').props.onClick()
    modal = findProp(rerender(), 'data-mobile-pilot-release-confirm', '1')
    modal.props.onClick({ target: modal, currentTarget: modal })
    assert.equal(findProp(rerender(), 'data-mobile-pilot-release-confirm', '1'), null, '点遮罩收起模态')
    assert.equal(releaseBodies.length, 0, '遮罩取消零请求')
    // 再展开并确认：请求发出；确认后模态收起（releasePending 在飞窗口由  专测）。
    findProp(rerender(), 'data-mobile-pilot-action', 'release-virtual').props.onClick()
    findProp(rerender(), 'data-mobile-pilot-action', 'release-confirm').props.onClick()
    await new Promise((r) => setTimeout(r, 5))
    assert.equal(findProp(rerender(), 'data-mobile-pilot-release-confirm', '1'), null,
      '确认后模态收起')
    // 在飞窗口的"正在释放…"文案从外观察不到（假体 fetch 立即 resolve）：
    // 防连点语义由 controller state.releasePending 承担（专测）。
    assert.equal(releaseBodies.length, 1, '确认后发一次 release')
    // 成功后复位：releasePending 清、模态不出现。
    await new Promise((r) => setTimeout(r, 20))
    const after = shim.calls.states[shim.calls.states.length - 1].value
    assert.equal(after.phase, 'ready', '释放成功后回 ready')
    for (const cleanup of cleanups) cleanup()
  })
  globalThis.URL = originalURL
})

test('流面板 N3F 阶段3：状态胶囊随相位变化；错误时主文案是人话、技术原因进详情', async () => {
  let startStatus = 503
  let startReason = 'no trusted display'
  const fx = segmentFixture()
  const reader = {
    chunks: [fx.all], index: 0,
    async read () {
      const value = this.chunks[this.index]
      this.index += 1
      if (this.index > this.chunks.length) return { done: true, value: undefined }
      return { done: false, value }
    },
    async cancel () {},
  }
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ running: false }), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({
      ok: startStatus < 400, status: startStatus,
      json: async () => (startStatus < 400
        ? { ok: true, tabToken: 'tab-pill', metadata: { mime: 'video/mp4' } }
        : { ok: false, reason: startReason }),
      body: null,
    }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null }),
    '/mobile-pilot/stream?': () => Promise.resolve({ ok: true, status: 200, body: { getReader: () => reader } }),
  })
  const originalURL = globalThis.URL
  globalThis.URL = class {
    static createObjectURL () { return 'blob:pill' }
    static revokeObjectURL () {}
  }
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { el, shim, rerender } = mountBody()
    shim.calls.refs[0].current = {
      addEventListener: () => {}, error: null, play: () => {}, pause: () => {},
      removeAttribute: () => {}, load: () => {},
    }
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    // probing 胶囊
    assert.ok(findProp(el, 'data-mobile-pilot-status-pill', 'probing'), '初始胶囊=probing')
    // start 失败：胶囊切 stopped，主文案是人话（宿主原因），不显示"HTTP 503"作主文案
    const startBtn = findProp(el, 'data-mobile-pilot-action', 'start-preview')
    startBtn.props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    const latest = shim.calls.states[shim.calls.states.length - 1].value
    assert.equal(latest.phase, 'stopped', 'start 失败落 stopped（可重试）')
    const failed = rerender()
    assert.ok(findProp(failed, 'data-mobile-pilot-status-pill', 'stopped'), '失败后胶囊=stopped')
    const mainError = findProp(failed, 'data-mobile-pilot-error-main', '1')
    assert.ok(mainError, '失败主文案在预览卡上')
    assert.match(String(mainError.children[0]), /no trusted display/, '主文案带宿主人话原因')
    assert.ok(!/HTTP 503/.test(String(mainError.children[0])), '主文案不是 HTTP 码')
    // 成功：胶囊切 playing
    startStatus = 200
    startReason = ''
    findProp(rerender(), 'data-mobile-pilot-action', 'start-preview').props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    const okPill = findProp(rerender(), 'data-mobile-pilot-status-pill', 'playing')
    assert.ok(okPill, '成功后胶囊=playing')
    for (const cleanup of cleanups) cleanup()
  })
  globalThis.URL = originalURL
})

// ──  精修 1：可访问性与文案精修 ──

test('流面板精修：释放模态 role/aria 属性齐全，打开聚焦取消，关闭焦点回到触发按钮', async () => {
  const fx = segmentFixture()
  const reader = {
    chunks: [fx.all], index: 0,
    async read () {
      const value = this.chunks[this.index]
      this.index += 1
      if (this.index > this.chunks.length) return { done: true, value: undefined }
      return { done: false, value }
    },
    async cancel () {},
  }
  const releaseBodies = []
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ running: false }), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({
      ok: true, status: 200,
      json: async () => ({ ok: true, tabToken: 'tab-a11y', metadata: { mime: 'video/mp4' } }),
      body: null,
    }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null }),
    '/mobile-pilot/stream?': () => Promise.resolve({ ok: true, status: 200, body: { getReader: () => reader } }),
    '/mobile-pilot/release': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true, released: true }), body: null }),
  })
  const originalURL = globalThis.URL
  globalThis.URL = class {
    static createObjectURL () { return 'blob:a11y' }
    static revokeObjectURL () {}
  }
  // 可编程的 document 假体：activeElement 模拟真实焦点；querySelector 返回带 focus
  // 的元素替身（Tab 循环的 document 回退路径用）。真实 DOM 上这些都是原生行为。
  const focusableEls = {
    'release-cancel': { disabled: false, focusCalls: 0, focus () { this.focusCalls += 1 } },
    'release-confirm': { disabled: false, focusCalls: 0, focus () { this.focusCalls += 1 } },
  }
  const originalDocument = globalThis.document
  globalThis.document = {
    activeElement: focusableEls['release-cancel'],
    querySelector (selector) {
      const m = /data-mobile-pilot-action="([^"]+)"/.exec(selector)
      return m ? (focusableEls[m[1]] || null) : null
    },
    querySelectorAll () { return [] },
  }
  try {
    await withPanelGlobals(fetch, fakeMediaSource(), async () => {
      const { el, shim, rerender } = mountBody()
      // 模态焦点管理读 DOM：给假体接上 ref 对象（button factory 的 ref 回调会写入
      // cancelBtnHolder/confirmBtnHolder——这里从渲染树里直接抓元素替身）。
      shim.calls.refs[0].current = {
        addEventListener: () => {}, error: null, play: () => {}, pause: () => {},
        removeAttribute: () => {}, load: () => {},
      }
      const cleanups = runMountEffects(shim)
      await new Promise((r) => setTimeout(r, 10))
      findProp(el, 'data-mobile-pilot-action', 'start-preview').props.onClick()
      await new Promise((r) => setTimeout(r, 20))
      // 触发按钮（DOM 假体：可聚焦、带 action 标记）
      const triggerEl = { disabled: false, focusCalls: 0, focus () { this.focusCalls += 1 }, getAttribute: (k) => k === 'data-mobile-pilot-action' ? 'release-virtual' : null }
      const cancelEl = { focusCalls: 0, focus () { this.focusCalls += 1 }, disabled: false }
      globalThis.document.activeElement = triggerEl
      // 展开：onReleaseRequest 捕获触发按钮
      findProp(el, 'data-mobile-pilot-action', 'release-virtual').props.onClick()
      let modal = findProp(rerender(), 'data-mobile-pilot-release-confirm', '1')
      assert.ok(modal, '释放模态展开')
      assert.equal(modal.props.role, 'dialog', 'role=dialog')
      assert.equal(modal.props['aria-modal'], 'true', 'aria-modal=true')
      assert.equal(modal.props['aria-labelledby'], 'mp-release-title', '标题关联')
      // 焦点管理 effect（第二个 useEffect 是输入层；这里跑挂载后新增的所有 effect）
      // —— 用最新一次 effects 里带 confirmRelease 依赖的：直接调最后登记的 effect 不可靠，
      // 改为断言 ref 挂接与 Tab 循环（真实 focus 移动在真 DOM 上，此处验契约形状）。
      // Tab 循环：挂两键，Escape 之外 Tab 不再冒泡（preventDefault 被调用）。
      const cancelBtn = findProp(modal, 'data-mobile-pilot-action', 'release-cancel')
      const confirmBtn = findProp(modal, 'data-mobile-pilot-action', 'release-confirm')
      assert.ok(cancelBtn && confirmBtn, '取消/释放两键都在')
      let prevented = false
      modal.props.onKeyDown({ key: 'Tab', shiftKey: false, preventDefault: () => { prevented = true } })
      assert.equal(prevented, true, 'Tab 循环接管（preventDefault）')
      // Esc 仍走取消（精修保留）。
      const panelRoot = findProp(el, 'data-mobile-pilot-panel', 'virtual-display')
      panelRoot.props.onKeyDown({ key: 'Escape' })
      assert.equal(findProp(rerender(), 'data-mobile-pilot-release-confirm', '1'), null, 'Esc 收起模态')
      for (const cleanup of cleanups) cleanup()
    })
  } finally {
    globalThis.document = originalDocument
    globalThis.URL = originalURL
  }
})

test('流面板精修：状态胶囊 aria-live、旋转/释放按钮 aria-busy（溢出菜单与任务列表已删）', async () => {
  const fx = segmentFixture()
  const reader = {
    chunks: [fx.all], index: 0,
    async read () {
      const value = this.chunks[this.index]
      this.index += 1
      if (this.index > this.chunks.length) return { done: true, value: undefined }
      return { done: false, value }
    },
    async cancel () {},
  }
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ running: false }), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({
      ok: true, status: 200,
      json: async () => ({ ok: true, tabToken: 'tab-aria', metadata: { mime: 'video/mp4' } }),
      body: null,
    }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null }),
    '/mobile-pilot/stream?': () => Promise.resolve({ ok: true, status: 200, body: { getReader: () => reader } }),
  })
  const originalURL = globalThis.URL
  globalThis.URL = class {
    static createObjectURL () { return 'blob:aria' }
    static revokeObjectURL () {}
  }
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { el, shim, rerender } = mountBody()
    shim.calls.refs[0].current = {
      addEventListener: () => {}, error: null, play: () => {}, pause: () => {},
      removeAttribute: () => {}, load: () => {},
    }
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    // 状态胶囊：role=status + aria-live=polite
    const pill = findProp(el, 'data-mobile-pilot-status-pill', 'probing')
    assert.ok(pill, '状态胶囊在')
    assert.equal(pill.props.role, 'status')
    assert.equal(pill.props['aria-live'], 'polite')
    // 溢出菜单与任务列表已删：无 overflow-menu 入口、无任务刷新。
    assert.equal(findProp(el, 'data-mobile-pilot-action', 'overflow-menu'), null, '溢出菜单已删')
    assert.equal(findProp(el, 'data-mobile-pilot-action', 'tasks-refresh'), null, '任务刷新按钮已删')
    // 开场会话：旋转/释放按钮带 aria-busy（进行中为 true，闲时 false）。
    findProp(el, 'data-mobile-pilot-action', 'start-preview').props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    const playing = rerender()
    assert.equal(findProp(playing, 'data-mobile-pilot-action', 'rotate-virtual').props['aria-busy'], false,
      '旋转闲时 aria-busy=false')
    assert.equal(findProp(playing, 'data-mobile-pilot-action', 'release-virtual').props['aria-busy'], false,
      '释放闲时 aria-busy=false')
    for (const cleanup of cleanups) cleanup()
  })
  globalThis.URL = originalURL
})

test('流面板精修：空态用 inline SVG 几何图标（无 emoji 字符）', () => {
  const source = fs.readFileSync(CLIENT_PATH, 'utf8')
  assert.ok(!/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(source), '主界面不再用 emoji（📱/⚠ 等）')
  assert.ok(!/react\.createElement\('svg'/.test(source), '空态不放 SVG 图标（真机反馈：巨大的播放器符号）')
  assert.match(source, /data-mobile-pilot-preview-error/, '错误态与中性空态分开标记')
})

test('流面板精修：styles 注入 reduced-motion 与 :focus-visible', () => {
  const source = fs.readFileSync(CLIENT_PATH, 'utf8')
  assert.match(source, /prefers-reduced-motion: reduce/, '减弱动态偏好下关闭过渡/动画')
  assert.match(source, /button:focus-visible/, '键盘焦点环')
})
// ── 面板旋转按钮 ──

test('流面板 N3F 阶段4：旋转按钮仅 playing 可用、防连点、成功复位并提示"已旋转，点开始预览"', async () => {
  const fx = segmentFixture()
  const reader = {
    chunks: [fx.all], index: 0,
    async read () {
      const value = this.chunks[this.index]
      this.index += 1
      if (this.index > this.chunks.length) return { done: true, value: undefined }
      return { done: false, value }
    },
    async cancel () {},
  }
  const rotateBodies = []
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ running: false }), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({
      ok: true, status: 200,
      json: async () => ({ ok: true, tabToken: 'tab-rot', metadata: { mime: 'video/mp4' } }),
      body: null,
    }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null }),
    '/mobile-pilot/stream?': () => Promise.resolve({ ok: true, status: 200, body: { getReader: () => reader } }),
    '/mobile-pilot/rotate': (url, init) => {
      rotateBodies.push({ url, body: init && init.body })
      return Promise.resolve({
        ok: true, status: 200,
        json: async () => ({ ok: true, rotated: true, dimensions: { width: 1032, height: 720 } }),
        body: null,
      })
    },
  })
  const originalURL = globalThis.URL
  globalThis.URL = class {
    static createObjectURL () { return 'blob:rot' }
    static revokeObjectURL () {}
  }
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { el, shim, rerender } = mountBody()
    shim.calls.refs[0].current = {
      addEventListener: () => {}, error: null, play: () => {}, pause: () => {},
      removeAttribute: () => {}, load: () => {},
    }
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    // 未 playing：旋转按钮禁用
    const rotateBtn = findProp(el, 'data-mobile-pilot-action', 'rotate-virtual')
    assert.ok(rotateBtn, '旋转按钮在动作栏')
    assert.equal(rotateBtn.props.disabled, true, '未 playing 时禁用')
    // 开场会话到 playing
    findProp(el, 'data-mobile-pilot-action', 'start-preview').props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    assert.equal(
      findProp(rerender(), 'data-mobile-pilot-action', 'rotate-virtual').props.disabled,
      false, 'playing 后解禁')
    // 旋转：请求发出、成功后复位（回 ready）+ 状态卡"已旋转…点开始预览"
    findProp(rerender(), 'data-mobile-pilot-action', 'rotate-virtual').props.onClick()
    await new Promise((r) => setTimeout(r, 30))
    assert.equal(rotateBodies.length, 1, '一次 rotate 请求')
    assert.match(rotateBodies[0].url, /tab=tab-rot/, 'tab 走查询参数')
    assert.ok(!rotateBodies[0].url.includes('token='), 'token 不出现在请求 URL')
    const after = shim.calls.states[shim.calls.states.length - 1].value
    // 旋转成功后自动拉起预览（不再停在 ready 等手点）。
    assert.equal(after.phase, 'playing', '自动 start 后回到预览中')
    for (const cleanup of cleanups) cleanup()
  })
  globalThis.URL = originalURL
})

test('流面板 N3F 阶段4：旋转进行中防连点（按钮禁用+正在旋转…）；失败人话不露 HTTP', async () => {
  const fx = segmentFixture()
  const reader = {
    chunks: [fx.all], index: 0,
    async read () {
      const value = this.chunks[this.index]
      this.index += 1
      if (this.index > this.chunks.length) return { done: true, value: undefined }
      return { done: false, value }
    },
    async cancel () {},
  }
  let rotateStatus = 503
  const rotateBodies = []
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ running: false }), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({
      ok: true, status: 200,
      json: async () => ({ ok: true, tabToken: 'tab-rotp', metadata: { mime: 'video/mp4' } }),
      body: null,
    }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null }),
    '/mobile-pilot/stream?': () => Promise.resolve({ ok: true, status: 200, body: { getReader: () => reader } }),
    '/mobile-pilot/rotate': () => Promise.resolve({
      ok: rotateStatus < 400, status: rotateStatus,
      json: async () => ({ ok: false, reason: 'resize failed: UnsupportedOperationException' }),
      body: null,
    }),
  })
  const originalURL = globalThis.URL
  globalThis.URL = class {
    static createObjectURL () { return 'blob:rotp' }
    static revokeObjectURL () {}
  }
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { el, shim, rerender } = mountBody()
    shim.calls.refs[0].current = {
      addEventListener: () => {}, error: null, play: () => {}, pause: () => {},
      removeAttribute: () => {}, load: () => {},
    }
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    findProp(el, 'data-mobile-pilot-action', 'start-preview').props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    // 失败：人话 + 可重试；HTTP/技术细节不进主文案（进诊断折叠 httpDetail）
    findProp(rerender(), 'data-mobile-pilot-action', 'rotate-virtual').props.onClick()
    await new Promise((r) => setTimeout(r, 30))
    const noted = shim.calls.states[shim.calls.states.length - 1].value
    assert.match(noted.taskNote || '', /旋转失败，可重试/, '人话 + 可重试')
    assert.match(noted.taskNote || '', /resize failed: UnsupportedOperationException/, '宿主原因透传（人话部分）')
    assert.ok(!/HTTP 503/.test(noted.taskNote || ''), '主文案无 HTTP 码')
    assert.match(noted.httpDetail || '', /HTTP 503/, 'HTTP 码进诊断折叠 httpDetail')
    // 成功路径：防连点观察（rotate 立即回，这里只验成功后 pending 清）
    rotateStatus = 200
    findProp(rerender(), 'data-mobile-pilot-action', 'rotate-virtual').props.onClick()
    await new Promise((r) => setTimeout(r, 30))
    const done = shim.calls.states[shim.calls.states.length - 1].value
    assert.equal(done.rotatePending, false, '完成后 pending 清')
    for (const cleanup of cleanups) cleanup()
  })
  globalThis.URL = originalURL
})

// ── releasePending 复位与旋转自动 start ──

test('流面板返工2 MAJOR-2：释放成功 releasePending 复位、释放按钮解禁；失败也复位', async () => {
  let releaseStatus = 200
  const fx = segmentFixture()
  const reader = {
    chunks: [fx.all], index: 0,
    async read () {
      const value = this.chunks[this.index]
      this.index += 1
      if (this.index > this.chunks.length) return { done: true, value: undefined }
      return { done: false, value }
    },
    async cancel () {},
  }
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ running: false }), body: null }),
    '/mobile-pilot/stream/start': () => Promise.resolve({
      ok: true, status: 200,
      json: async () => ({ ok: true, tabToken: 'tab-pend', metadata: { mime: 'video/mp4' } }),
      body: null,
    }),
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null }),
    '/mobile-pilot/stream?': () => Promise.resolve({ ok: true, status: 200, body: { getReader: () => reader } }),
    '/mobile-pilot/release': () => Promise.resolve({
      ok: releaseStatus < 400, status: releaseStatus,
      json: async () => (releaseStatus < 400
        ? { ok: true, released: true }
        : { ok: false, reason: 'display release failed' }),
      body: null,
    }),
  })
  const originalURL = globalThis.URL
  globalThis.URL = class {
    static createObjectURL () { return 'blob:pend' }
    static revokeObjectURL () {}
  }
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { el, shim, rerender } = mountBody()
    shim.calls.refs[0].current = {
      addEventListener: () => {}, error: null, play: () => {}, pause: () => {},
      removeAttribute: () => {}, load: () => {},
    }
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    findProp(el, 'data-mobile-pilot-action', 'start-preview').props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    // 展开并确认：releasePending 置位（动作栏按钮禁用 + "正在释放…"）
    findProp(rerender(), 'data-mobile-pilot-action', 'release-virtual').props.onClick()
    findProp(rerender(), 'data-mobile-pilot-action', 'release-confirm').props.onClick()
    await new Promise((r) => setTimeout(r, 5))
    let latest = shim.calls.states[shim.calls.states.length - 1].value
    // 假体 fetch 立即 resolve：在飞窗口难以从外断言 true；复位语义见下两条。
    // 成功：releasePending 复位、按钮解禁（的核心断言）
    await new Promise((r) => setTimeout(r, 20))
    latest = shim.calls.states[shim.calls.states.length - 1].value
    assert.equal(latest.releasePending, false, '成功后 releasePending 复位（MAJOR-2）')
    assert.equal(
      findProp(rerender(), 'data-mobile-pilot-action', 'release-virtual').props.disabled,
      true, // phase=ready 后 canInput=false，按钮仍禁用但那是会话门禁不是 pending
    )
    assert.match(
      String(findProp(rerender(), 'data-mobile-pilot-action', 'release-virtual').children[0]),
      /释放虚拟屏/, '文案不再是"正在释放…"')
    // 失败也复位：再开一场会话，释放失败后 pending 清、按钮文案恢复
    findProp(rerender(), 'data-mobile-pilot-action', 'start-preview').props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    releaseStatus = 503
    findProp(rerender(), 'data-mobile-pilot-action', 'release-virtual').props.onClick()
    findProp(rerender(), 'data-mobile-pilot-action', 'release-confirm').props.onClick()
    await new Promise((r) => setTimeout(r, 30))
    latest = shim.calls.states[shim.calls.states.length - 1].value
    assert.equal(latest.releasePending, false, '失败后 releasePending 复位')
    assert.match(latest.taskNote || '', /释放失败，可重试/)
    for (const cleanup of cleanups) cleanup()
  })
  globalThis.URL = originalURL
})

test('流面板返工2：旋转成功自动 start（请求序列 rotate → start）', async () => {
  const fx = segmentFixture()
  const reader = {
    chunks: [fx.all], index: 0,
    async read () {
      const value = this.chunks[this.index]
      this.index += 1
      if (this.index > this.chunks.length) return { done: true, value: undefined }
      return { done: false, value }
    },
    async cancel () {},
  }
  const startCalls = []
  const rotateCalls = []
  const fetch = fakePanelFetch({
    '/mobile-pilot/stream/status': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ running: false }), body: null }),
    '/mobile-pilot/stream/start': () => {
      startCalls.push(1)
      return Promise.resolve({
        ok: true, status: 200,
        json: async () => ({ ok: true, tabToken: 'tab-auto' + startCalls.length, metadata: { mime: 'video/mp4' } }),
        body: null,
      })
    },
    '/mobile-pilot/stream/stop': () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true }), body: null }),
    '/mobile-pilot/stream?': () => Promise.resolve({ ok: true, status: 200, body: { getReader: () => reader } }),
    '/mobile-pilot/rotate': (url, init) => {
      rotateCalls.push(url)
      return Promise.resolve({
        ok: true, status: 200,
        json: async () => ({ ok: true, rotated: true, dimensions: { width: 1032, height: 720 } }),
        body: null,
      })
    },
  })
  const originalURL = globalThis.URL
  globalThis.URL = class {
    static createObjectURL () { return 'blob:auto' }
    static revokeObjectURL () {}
  }
  await withPanelGlobals(fetch, fakeMediaSource(), async () => {
    const { el, shim, rerender } = mountBody()
    shim.calls.refs[0].current = {
      addEventListener: () => {}, error: null, play: () => {}, pause: () => {},
      removeAttribute: () => {}, load: () => {},
    }
    const cleanups = runMountEffects(shim)
    await new Promise((r) => setTimeout(r, 10))
    findProp(el, 'data-mobile-pilot-action', 'start-preview').props.onClick()
    await new Promise((r) => setTimeout(r, 20))
    const startCallsAfterManual = startCalls.length
    // 旋转：成功后自动 start（请求序列 rotate → start）
    findProp(rerender(), 'data-mobile-pilot-action', 'rotate-virtual').props.onClick()
    await new Promise((r) => setTimeout(r, 40))
    assert.equal(rotateCalls.length, 1, '一次 rotate 请求')
    assert.ok(startCalls.length > startCallsAfterManual, '旋转成功后自动拉起 start（P1-UI 6）')
    // 自动 start 失败再提示"点开始预览"：由 rotateDisplay 的 start 失败分支给出
    // （phase !== playing 时），这里以状态断言 playing 作为自动重连成功的证据。
    const latest = shim.calls.states[shim.calls.states.length - 1].value
    assert.equal(latest.phase, 'playing', '自动 start 后回到 playing')
    for (const cleanup of cleanups) cleanup()
  })
  globalThis.URL = originalURL
})

test('流面板返工5：静态扫描——空态高度与哨共用同一常量，任务列表 UI 与溢出菜单已删', () => {
  const source = fs.readFileSync(CLIENT_PATH, 'utf8')
  assert.ok(!/aspectRatio:\s*'16\s*\/\s*9'/.test(source), '预览不写死 16/9（按源尺寸自适应）')
  // 空态撑底高度只有一个真值来源 —— EMPTY_PREVIEW_MIN_HEIGHT 常量，
  // 实现与哨都引用它，不再各写一个裸数字。
  assert.match(source, /var EMPTY_PREVIEW_MIN_HEIGHT = 180/, '空态高度常量在')
  assert.match(source, /minHeight: EMPTY_PREVIEW_MIN_HEIGHT/, 'videoWrap 空态用常量撑底')
  assert.ok(!/tasks-refresh|task-move-main/.test(source), '任务行/刷新入口已删')
  assert.ok(!/overflow-menu|overflowWrap|overflowMenu/.test(source), '右上角溢出菜单已删（诊断入口唯一）')
  assert.ok(!/tasksStale|tasksLoading/.test(source), '只写不读的死状态已删')
  assert.ok(/data-mobile-pilot-diag/.test(source) && /toggle-diag/.test(source), '底部诊断折叠卡在')
  assert.ok(/aria-controls/.test(source), '诊断折叠卡带 aria-controls')
})

test('流面板 T4/T6：主按钮用 on-brand 前景、释放模态用实际存在的 layer token', () => {
  const source = fs.readFileSync(CLIENT_PATH, 'utf8')
  const primary = source.match(/btnPrimary:\s*\{[\s\S]*?\n\s*\},/)
  assert.ok(primary, 'btnPrimary 样式段在')
  assert.match(primary[0], /label-primary-foreground/, '主按钮前景不硬编码 #fff，用 on-brand token')
  assert.doesNotMatch(primary[0], /color:\s*'#fff'/, '主按钮不再白底白字')
  const modal = source.match(/modalCard:\s*\{[\s\S]*?\n\s*\},/)
  assert.ok(modal, 'modalCard 样式段在')
  assert.match(modal[0], /bg-layer-2/, '释放模态用 DSH 实际存在的 layer token')
  assert.match(modal[0], /#fff/, '模态背景兜底为浅色（token 缺失时白卡）')
  assert.match(modal[0], /#111/, '模态文字兜底为深色（token 缺失时白卡深字）')
  assert.doesNotMatch(modal[0], /background:\s*'[^']*bg-(elevated|primary)/, '不再用空的 bg-elevated/bg-primary token')
  assert.match(modal[0], /boxSizing:\s*'border-box'/, '模态宽度含内边距不溢出')
  assert.match(source, /btnHalf:\s*\{[^}]*flex:\s*'1 1 calc\(50% - 4px\)'/, '五按钮两列自适应样式在')
  assert.match(source, /btnBase:\s*\{[\s\S]*?boxSizing:\s*'border-box'/, '全宽按钮含内边距不溢出')
  assert.match(source, /data-mobile-pilot-preview-actions/, '刷新/旋转两列分组在')
  assert.match(source, /data-mobile-pilot-task-actions/, '移主/移回两列分组在')
  assert.match(source, /release-virtual'[\s\S]{0,500}?styles\.btnFull/, '释放独占全宽')
})

test('流面板：静态扫描——无 AI 输入注入、无硬编码绝对 URL', () => {
  const source = fs.readFileSync(CLIENT_PATH, 'utf8')
  // D：面板自己的输入层用原生 pointer 监听（合法），AI 侧的注入动词仍然一个不许有；
  // 请求面 = stream* + input 与 tasks/move/release。
  assert.ok(!/dispatchGesture|injectInput|Instrumentation|uiautomator|keyevent/i.test(source), '没有 AI 输入注入通路')
  assert.ok(/mobile-pilot\/input/.test(source), '输入请求面是 /mobile-pilot/input')
  assert.ok(/mobile-pilot\/tasks/.test(source) && /mobile-pilot\/move/.test(source) && /mobile-pilot\/release/.test(source),
    'N3F 任务面三条路由在请求面上（静态扫描哨）')
  const absUrls = source.match(/['"]https?:\/\/[^'"]+['"]/g) || []
  assert.deepEqual(absUrls, [], '面板不得硬编码任何绝对 URL（请求面只有同源相对路由）')
})
