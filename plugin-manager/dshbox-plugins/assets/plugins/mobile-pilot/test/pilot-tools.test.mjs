// 通道客户端（argv/解析/退出码/超时/串行）与工具定义层的测试。
// 挂载解析与 CLI 转发仍由 test/mobile-pilot.test.mjs 覆盖。
import { test, after } from 'node:test'

import { normalize } from '../plugin/lib/pilot.js'
import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { EventEmitter } from 'node:events'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const {
  PilotChannel, hintFor, describe, timeoutFor, timeoutPlan, preview,
  ENTRY_TIMEOUT_MAX_MS, ENTRY_TIMEOUT_MIN_MS, ASK_BUDGET_MARGIN_MS
} = require('../plugin/lib/pilot.js')
const { buildTools, renderTree, pruneSnapshots, SNAPSHOT_KEEP, SHOT_PREFIX, SHOT_SUFFIX } = require('../plugin/lib/tools.js')
const plugin = require('../plugin/lib/index.js')

const LIB_DIR = fileURLToPath(new URL('../plugin/lib', import.meta.url))
const PACK_DIR = fileURLToPath(new URL('..', import.meta.url))

/** 按宿主的铺法造一个假挂载点；entryBody 决定入口脚本的行为。 */
function fakePack (t, entryBody, manifest = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-pilot-'))
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }))
  fs.mkdirSync(path.join(dir, 'bin'), { recursive: true })
  fs.mkdirSync(path.join(dir, 'run', 'inbox'), { recursive: true })
  fs.writeFileSync(path.join(dir, 'capabilities.json'), JSON.stringify({
    generatedAt: '2026-09-27T00:00:00Z',
    capabilities: [{ id: 'ui.snapshot', usable: true }, { id: 'sys.shell', usable: false }],
    backend: { shizuku: { running: true, authorized: true, bound: true, trustedDisplay: { alive: false, displayId: -1, nodeTree: 'untested' } } },
    ...manifest,
  }))
  fs.writeFileSync(path.join(dir, 'bin', 'pilot'), entryBody)
  return dir
}

const channelFor = (dir, env = {}, platform = process.platform) => new PilotChannel({
  env: { MOBILE_PILOT_HOME: dir, ...env },
  mountFile: path.join(dir, 'no-mount.json'),
  platform,
})

/**
 * 通道队列行为的测试桩：可注入手动时钟与可控 spawn，不依赖真实入口进程。
 * 排队、背压、取消与预算的交错全部由测试手动推演。
 */
function stubbedChannel (t, dir, { spawnImpl, clock, maxQueued } = {}) {
  return new PilotChannel({
    env: { MOBILE_PILOT_HOME: dir },
    mountFile: path.join(dir, 'no-mount.json'),
    platform: process.platform,
    spawnImpl,
    nowMs: clock ? clock.nowMs : undefined,
    maxQueued,
  })
}

/** 手动时钟：注入 nowMs 用，由测试自行推进。 */
function manualClock (start = 0) {
  let now = start
  return { nowMs: () => now, advance: (ms) => { now += ms } }
}

/**
 * 可控 spawn 桩：记录每次投件的 argv，假子进程由测试手动收场。
 * 不 settle 就是"卡住"—— close 之前不会产生任何回包。
 */
function manualSpawn () {
  const calls = []
  const spawnImpl = (execPath, argv) => {
    const child = new EventEmitter()
    child.stdout = new EventEmitter()
    child.stderr = new EventEmitter()
    child.kill = () => child.emit('close', null, 'SIGKILL')
    calls.push({ execPath, argv, child })
    return child
  }
  return {
    spawnImpl,
    calls,
    settle (i, { code = 0, stdout = JSON.stringify({ ok: true, data: {} }) } = {}) {
      const { child } = calls[i]
      if (stdout) child.stdout.emit('data', stdout)
      child.emit('close', code, null)
    },
  }
}

/** 等到微任务清空：call() 的 spawn 发生在受理后的下一个微任务里。 */
const tick = () => new Promise((resolve) => setImmediate(resolve))

const OK_ENTRY = `#!/usr/bin/env node
process.stdout.write(JSON.stringify({
  ok: true, surface: 'foreground', degraded: true, degradedFrom: 'trusted-display',
  elapsedMs: 7, data: { count: 210, argv: process.argv.slice(2) }, artifacts: []
}) + '\\n')
`

const DENY_ENTRY = `#!/usr/bin/env node
process.stdout.write(JSON.stringify({
  ok: false, reason: 'arg "package" names this app itself, and the assistant cannot read or act on the host app: drop that argument or name another app',
  error: { code: 'E_GATE_HOST_DENIED', retryable: false, exitCode: 2 }
}) + '\\n')
process.exit(2)
`

const JUNK_ENTRY = `#!/usr/bin/env node
process.stdout.write('not json at all\\n')
`

const HANG_ENTRY = `#!/usr/bin/env node
setTimeout(() => process.exit(0), 30000)
`

/** 只捕获入口 argv 的假通道：工具层的渲染与入参拼装由这里核对。 */
function stubChannel (reply) {
  const calls = []
  return {
    calls,
    resolve: () => ({ root: '/fake/pilot', source: 'MOBILE_PILOT_HOME', info: { entry: '/fake/pilot/bin/pilot', manifest: '/fake/pilot/capabilities.json', entryExecutable: true }, tried: [] }),
    call: async (capability, args, options) => {
      calls.push({ capability, args, options })
      return typeof reply === 'function' ? reply(capability, args) : { ok: true, capability, data: {}, ...reply }
    },
    ask: async (payload) => {
      calls.push({ capability: 'ask', args: payload, options: undefined, op: 'ask' })
      return typeof reply === 'function' ? reply('ask', payload) : { ok: true, capability: 'ask', data: {}, ...reply }
    },
  }
}

function toolsWith (reply, outDirOption) {
  const captured = []
  const channel = stubChannel(reply)
  const tools = buildTools({ defineTool: (spec) => spec, channel, outDirOption })
  for (const spec of tools) captured.push(spec)
  return { byName: Object.fromEntries(captured.map((t) => [t.name, t])), channel }
}

test('argv 按数组递过去：--json 是紧凑 JSON，--timeout 用能力对应的默认值', async (t) => {
  const dir = fakePack(t, OK_ENTRY)
  const r = await channelFor(dir).call('pkg.query', { a: 1, s: '带 空格 与"引号"' })
  assert.equal(r.ok, true)
  assert.equal(r.data.count, 210)
  const argv = r.data.argv
  assert.equal(argv[0], 'call')
  assert.equal(argv[1], 'pkg.query')
  assert.equal(argv[2], '--json')
  assert.deepEqual(JSON.parse(argv[3]), { a: 1, s: '带 空格 与"引号"' })
  assert.deepEqual(argv.slice(4, 6), ['--timeout', String(timeoutFor('pkg.query'))])
  // 降级字段原样透出，不被吞掉
  assert.equal(r.surface, 'foreground')
  assert.equal(r.degraded, true)
  assert.equal(r.degradedFrom, 'trusted-display')
})

test('超时按能力分档，且一律夹到入口 --timeout 接受的区间内', () => {
  assert.equal(timeoutFor('ui.snapshot'), 20000)
  assert.equal(timeoutFor('ui.waitFor'), 20000)
  assert.equal(timeoutFor('screen.capture'), 90000)
  assert.equal(timeoutFor('pkg.query'), 30000)
  assert.equal(timeoutFor('ui.snapshot', 1234), 1234)
  // 档位高于入口上限时不能原样下发：入口会静默夹紧，谁掐的这次调用就查不出来了。
  assert.equal(timeoutFor('screen.record'), ENTRY_TIMEOUT_MAX_MS)
  assert.equal(timeoutFor('pkg.query', 999999), ENTRY_TIMEOUT_MAX_MS)
  assert.equal(timeoutFor('pkg.query', 5), ENTRY_TIMEOUT_MIN_MS)
  const plan = timeoutPlan('screen.record')
  assert.deepEqual(
    { ms: plan.ms, requestedMs: plan.requestedMs, clamped: plan.clamped },
    { ms: 120000, requestedMs: 180000, clamped: true },
  )
})

test('夹过的超时要在摘要里说明是哪一层在掐', async (t) => {
  const dir = fakePack(t, `#!/usr/bin/env node
process.stderr.write(JSON.stringify({ error: 'E_TRANSPORT_TIMEOUT' }) + '\\n')
process.exit(3)
`)
  const r = await channelFor(dir).call('screen.record', { seconds: 5 })
  assert.equal(r.code, 'E_TRANSPORT_TIMEOUT')
  assert.equal(r.timeoutMs, 120000)
  assert.equal(r.timeoutClamped, true)
  assert.match(describe(r), /夹紧/)
  assert.match(describe(r), /入口/)
})

test('本层掐断要说成"包装层"，并保留夹紧痕迹', async (t) => {
  const dir = fakePack(t, HANG_ENTRY)
  const r = await channelFor(dir).call('screen.record', {}, { timeoutMs: 100 })
  assert.equal(r.code, 'E_WRAPPER_TIMEOUT')
  assert.equal(r.retryable, true)
  assert.match(r.reason, /包装层/)
  assert.match(describe(r), /120000ms/)
})

test('退出码与 error.code 原样透出，不揉成一个"失败"', async (t) => {
  const dir = fakePack(t, DENY_ENTRY)
  const r = await channelFor(dir).call('app.launch', { package: 'com.dshbox.app' })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'E_GATE_HOST_DENIED')
  assert.equal(r.exitCode, 2)
  assert.equal(r.retryable, false)
  assert.match(hintFor(r), /拒绝/)
})

test('入口没给可解析的回包 → E_WRAPPER_NO_REPLY（不是"被拒"）', async (t) => {
  const dir = fakePack(t, JUNK_ENTRY)
  const r = await channelFor(dir).call('ui.snapshot', {})
  assert.equal(r.ok, false)
  assert.equal(r.code, 'E_WRAPPER_NO_REPLY')
  assert.equal(r.exitCode, 6)
})

test('挂载点不存在 → E_WRAPPER_NO_MOUNT，且报出试过哪些路径', async (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-none-'))
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }))
  // 用 win32 关掉"默认 /opt/pilot"这一候选，才能在没有挂载点的机器上稳定复现。
  const r = await channelFor(path.join(dir, 'nope'), {}, 'win32').call('ui.snapshot', {})
  assert.equal(r.code, 'E_WRAPPER_NO_MOUNT')
  assert.match(r.stderr, /nope/)
  assert.match(hintFor(r), /挂载点/)
})

test('本层超时会掐掉长时间不回的入口', async (t) => {
  const dir = fakePack(t, HANG_ENTRY)
  const r = await channelFor(dir).call('pkg.query', {}, { timeoutMs: 100 })
  assert.equal(r.code, 'E_WRAPPER_TIMEOUT')
  assert.equal(r.retryable, true)
})

test('调用串行：后发的不会越过先发的', async (t) => {
  const order = path.join(os.tmpdir(), `mp-order-${Date.now()}.txt`)
  t.after(() => fs.rmSync(order, { force: true }))
  const entry = `#!/usr/bin/env node
const fs = require('node:fs')
const i = JSON.parse(process.argv[process.argv.indexOf('--json') + 1]).i
setTimeout(() => {
  fs.appendFileSync(process.env.MP_ORDER_FILE, i + '\\n')
  process.stdout.write(JSON.stringify({ ok: true, data: { i } }) + '\\n')
}, (3 - i) * 120)
`
  const dir = fakePack(t, entry)
  const channel = channelFor(dir, { MP_ORDER_FILE: order })
  const [a, b] = await Promise.all([channel.call('x.one', { i: 1 }), channel.call('x.two', { i: 2 })])
  assert.equal(a.data.i, 1)
  assert.equal(b.data.i, 2)
  assert.deepEqual(fs.readFileSync(order, 'utf8').trim().split('\n'), ['1', '2'])
})

test('受理即空闲的调用预算不被排队收紧：--timeout 用档位原值', async (t) => {
  const dir = fakePack(t, OK_ENTRY)
  const spawn = manualSpawn()
  const clock = manualClock(0)
  const channel = stubbedChannel(t, dir, { spawnImpl: spawn.spawnImpl, clock })
  const pending = channel.call('pkg.query', {})
  await tick()
  spawn.settle(0)
  const r = await pending
  const argv = spawn.calls[0].argv
  const at = argv.indexOf('--timeout')
  assert.ok(at !== -1)
  assert.deepEqual(argv.slice(at, at + 2), ['--timeout', String(timeoutFor('pkg.query'))])
  assert.equal(r.ok, true)
  assert.equal(r.timeoutMs, timeoutFor('pkg.query'))
  assert.equal(r.timeoutRequestedMs, timeoutFor('pkg.query'))
  assert.equal(r.queuedMs, 0)
})

test('排队等待计入预算：预算充足时按剩余预算夹紧下发', async (t) => {
  const dir = fakePack(t, OK_ENTRY)
  const spawn = manualSpawn()
  const clock = manualClock(0)
  const channel = stubbedChannel(t, dir, { spawnImpl: spawn.spawnImpl, clock })
  const first = channel.call('x.one', { i: 1 })
  const second = channel.call('x.two', {})
  await tick()
  clock.advance(100) // 第一条卡住 100ms，第二条在排队
  spawn.settle(0)
  await tick() // 第二条的投件发生在队列放行后的下一个微任务
  assert.equal(spawn.calls.length, 2)
  const argv = spawn.calls[1].argv
  const at = argv.indexOf('--timeout')
  spawn.settle(1)
  const r = await second
  assert.equal(argv[at + 1], '29900', `轮到时剩余 29900ms，实际下发 ${argv[at + 1]}`)
  assert.equal(r.ok, true)
  assert.equal(r.timeoutMs, 29900)
  assert.equal(r.timeoutRequestedMs, 30000, 'requestedMs 保留档位原值，不跟随缩短')
  assert.equal(r.queuedMs, 100)
  assert.match(describe(r), /受理后排队 100ms/)
  assert.equal((await first).ok, true)
})

test('排队等待计入预算：轮到时剩余不足入口下限就不投件，报排队耗尽', async (t) => {
  const dir = fakePack(t, OK_ENTRY)
  const spawn = manualSpawn()
  const clock = manualClock(0)
  const channel = stubbedChannel(t, dir, { spawnImpl: spawn.spawnImpl, clock })
  const first = channel.call('x.one', { i: 1 })
  // 预算 100ms 被夹到入口下限 1000ms：排队 150ms 之后剩余已垫不出一次投件。
  const second = channel.call('x.two', {}, { timeoutMs: 100 })
  await tick()
  clock.advance(150)
  spawn.settle(0)
  const r = await second
  assert.equal(spawn.calls.length, 1, '预算不足的调用不得投件')
  assert.equal(r.code, 'E_WRAPPER_TIMEOUT')
  assert.equal(r.retryable, true)
  assert.match(r.reason, /排队等待 150ms/)
  assert.match(r.reason, /未把请求投给入口/)
  assert.equal(r.timeoutRequestedMs, 100)
  assert.equal(r.elapsedMs, 150)
  assert.equal((await first).ok, true)
})

test('满额背压：在途+排队到达上限后，新调用当场被拒且不再投件', async (t) => {
  const dir = fakePack(t, OK_ENTRY)
  const spawn = manualSpawn()
  const channel = stubbedChannel(t, dir, { spawnImpl: spawn.spawnImpl, maxQueued: 2 })
  const first = channel.call('x.one', { i: 1 })
  const second = channel.call('x.two', { i: 2 })
  const third = await channel.call('x.three', { i: 3 })
  const fourth = await channel.call('x.four', { i: 4 })
  await tick()
  assert.equal(spawn.calls.length, 1, '满额后不得再投件')
  assert.equal(third.code, 'E_WRAPPER_QUEUE_FULL')
  assert.equal(third.retryable, true)
  assert.equal(third.queueDepth, 2)
  assert.match(third.reason, /未受理/)
  assert.match(third.reason, /降低并发|退避/)
  assert.equal(fourth.code, 'E_WRAPPER_QUEUE_FULL')
  assert.match(hintFor(third), /降低并发或退避/)
  // 前两条不受背压影响，照常收场，计数归零
  spawn.settle(0)
  assert.equal((await first).ok, true)
  await tick() // 第二条的投件发生在队列放行后的下一个微任务
  assert.equal(spawn.calls.length, 2, '第一条收场后第二条才投件')
  spawn.settle(1)
  assert.equal((await second).ok, true)
  assert.equal(channel.pending, 0)
})

test('有界之后仍保 FIFO 串行：先受理的先投件，同时只有一条在途', async (t) => {
  const dir = fakePack(t, OK_ENTRY)
  const spawn = manualSpawn()
  const channel = stubbedChannel(t, dir, { spawnImpl: spawn.spawnImpl })
  const calls = [channel.call('x.one'), channel.call('x.two'), channel.call('x.three')]
  await tick()
  assert.deepEqual(spawn.calls.map((c) => c.argv[2]), ['x.one'], '同一时刻只有一条在途')
  spawn.settle(0)
  await tick()
  assert.deepEqual(spawn.calls.map((c) => c.argv[2]), ['x.one', 'x.two'])
  spawn.settle(1)
  await tick()
  assert.deepEqual(spawn.calls.map((c) => c.argv[2]), ['x.one', 'x.two', 'x.three'])
  spawn.settle(2)
  const results = await Promise.all(calls)
  assert.deepEqual(results.map((r) => r.capability), ['x.one', 'x.two', 'x.three'])
})

test('cancelPending 撤下排队中的调用：轮到时不投件，报 E_WRAPPER_CANCELLED', async (t) => {
  const dir = fakePack(t, OK_ENTRY)
  const spawn = manualSpawn()
  const channel = stubbedChannel(t, dir, { spawnImpl: spawn.spawnImpl })
  const first = channel.call('x.one', { i: 1 })
  const second = channel.call('x.two', {})
  await tick()
  assert.equal(spawn.calls.length, 1, '第一条已在途')
  assert.equal(channel.cancelPending('x.two'), 1, '返回被标记的条数')
  assert.equal(channel.cancelPending('x.two'), 0, '重复取消不再计数')
  assert.equal(channel.cancelPending('x.three'), 0, '没有排队的同能力调用时不误标')
  spawn.settle(0)
  const r = await second
  assert.equal(spawn.calls.length, 1, '被撤下的调用不得投件')
  assert.equal(r.code, 'E_WRAPPER_CANCELLED')
  assert.equal(r.retryable, false, '取消是调用方自己发起的，不算可重试失败')
  assert.match(r.reason, /未投件未执行/)
  assert.match(hintFor(r), /重新发起/)
  assert.equal((await first).ok, true, '在途的第一条不受取消影响')
})

test('cancelPending 不指定能力时撤下全部排队，在途的不算', async (t) => {
  const dir = fakePack(t, OK_ENTRY)
  const spawn = manualSpawn()
  const channel = stubbedChannel(t, dir, { spawnImpl: spawn.spawnImpl })
  const first = channel.call('x.one', { i: 1 })
  const rest = [channel.call('x.two'), channel.call('x.three')]
  await tick()
  assert.equal(channel.cancelPending(), 2, '只标记还在排队的两条')
  spawn.settle(0)
  const results = await Promise.all(rest)
  assert.deepEqual(results.map((r) => r.code), ['E_WRAPPER_CANCELLED', 'E_WRAPPER_CANCELLED'])
  assert.equal(spawn.calls.length, 1, '在途的一条照常执行，不被误撤')
  assert.equal((await first).ok, true)
})

test('截断标记要写清还剩多少没显示', () => {
  const long = 'x'.repeat(700)
  const cut = preview(long)
  assert.match(cut, /余 100 字符 \/ 100 字节/)
  // 中文字符一个占三个字节：只报字符数会低估该去读多大的文件。
  const wide = '啊'.repeat(700)
  assert.match(preview(wide), /余 100 字符 \/ 300 字节/)
  assert.equal(preview('short'), 'short')
})

test('确认框的三处答复入口与"相同参数重试"要写进处置建议', () => {
  const hint = hintFor({ code: 'E_AWAITING_CONSENT' })
  assert.match(hint, /通知栏/)
  assert.match(hint, /助手页/)
  assert.match(hint, /完全相同的参数/)
  // 旧说法把浮层说成"运行期间不会出现"，会让人以为这次必死；实际只是宿主界面在前面时不出现。
  assert.doesNotMatch(hint, /后台虚拟屏运行时助手浮层不会出现/)
})

test('启动未落地的建议要同时说清两条通路，且不许把人支去查后端', () => {
  const hint = hintFor({ code: 'E_LAUNCH_NOT_LANDED' })
  assert.match(hint, /前台屏/)
  assert.match(hint, /虚拟屏/)
  assert.match(hint, /再以同样参数重试/)
  // 这一码原先在前台那一路回的是 E_BACKEND_UNAVAILABLE，处置建议是去查 Shizuku；
  // 启动没落地时后端好好的，照那句做等于白查一遍。
  assert.doesNotMatch(hint, /^后端不可用/)
})

test('设备没有那条二进制要说成设备边界，不能挂在"后端不可用"下让人去查 Shizuku', () => {
  const boundary = hintFor({ code: 'E_CAPABILITY_UNAVAILABLE_ON_DEVICE' })
  assert.match(boundary, /这台设备给不出/)
  assert.match(boundary, /重试与开任何开关都不会变/)
  // 那句设备边界原先写在 E_BACKEND_UNAVAILABLE 的建议里，而这条 case 现在回的是
  // E_CAPABILITY_UNAVAILABLE_ON_DEVICE：留在旧码下就等于两份说法各自漂移。
  assert.doesNotMatch(hintFor({ code: 'E_BACKEND_UNAVAILABLE' }), /no such command/)
})

test('phone_node 按宿主实际字段渲染：平铺坐标 + offscreen，不读 bounds', async () => {
  const { byName } = toolsWith({
    data: {
      matchMode: 'exact',
      nodeId: 41,
      node: {
        class: 'android.widget.NumberPicker',
        package: 'com.example.clock',
        id: 'com.example.clock:id/spinner',
        text: '07',
        desc: '小时',
        enabled: true, checkable: false, checked: false,
        clickable: true, longClickable: false, scrollable: true,
        selected: false, visible: true, focused: false, editable: false,
        left: 100, top: 200, right: 300, bottom: 260, display: 12,
        actions: ['scroll-forward', 'scroll-backward'],
        range: { min: 0, max: 23, current: 7, type: 'int' },
      },
    },
  })
  const out = await byName.phone_node.execute({ nodeId: 41 })
  assert.equal(out.ok, true)
  assert.match(out.text, /\(100,200\)-\(300,260\)/)
  assert.doesNotMatch(out.text, /bounds=/)
  // 节点属于哪块屏要照原样交出去：nodeId 是同一棵树里的前序下标，
  // 跨屏复用旧编号会静默指到另一颗节点上，这一格是唯一的预警信号。
  assert.match(out.text, /display=12/)
  assert.equal(out.actions, 'scroll-forward,scroll-backward')
  // 真实字段名逐个保住，调用方才不用猜键名
  for (const key of ['checkable', 'checked', 'longClickable', 'scrollable', 'selected', 'visible', 'focused', 'editable', 'package', 'desc']) {
    assert.ok(out.text.includes(key), `缺少字段 ${key}`)
  }
  assert.match(out.text, /range=\{/)
  // clickable=true 但 actions 里没有 click：渲染必须把这条差异说出来
  assert.match(out.text, /actions 里没有 click/)
})

test('phone_node 遇到整颗裁空的节点只报 offscreen', async () => {
  const { byName } = toolsWith({ data: { nodeId: 9, node: { class: 'android.view.View', offscreen: true, actions: [], enabled: true } } })
  const out = await byName.phone_node.execute({ nodeId: 9 })
  assert.match(out.text, /offscreen=true/)
  assert.doesNotMatch(out.text, /\(null/)
})

test('phone_scroll 分列 dispatched / advanced，并把 reached、target、stepLimit 交回去', async () => {
  const { byName, channel } = toolsWith({
    data: {
      dispatched: 20, requested: 20, stepLimit: 20, direction: 'forward',
      advanced: 3, movementVerified: false, reached: true, target: 7, value: 7,
      note: 'advanced counts observed value changes',
    },
  })
  const out = await byName.phone_scroll.execute({ nodeId: 41, direction: 'forward', times: 20, until: 7 })
  assert.deepEqual(channel.calls[0].args, { nodeId: 41, direction: 'forward', times: 20, until: 7 })
  assert.match(out.text, /dispatched=20/)
  assert.match(out.text, /advanced=3/)
  assert.match(out.text, /movementVerified=false/)
  assert.match(out.text, /reached=true/)
  assert.match(out.text, /target=7/)
  assert.match(out.text, /stepLimit=20/)
  assert.match(out.text, /派发次数与位移次数不一致/)
})

test('phone_scroll 在量不出位移的控件上说清"未提供"而不是 0', async () => {
  const { byName } = toolsWith({ data: { dispatched: 2, requested: 2, stepLimit: 2, direction: 'forward', movementVerified: false } })
  const out = await byName.phone_scroll.execute({ nodeId: 1, direction: 'forward', times: 2 })
  assert.match(out.text, /advanced=未提供（该控件不自报量程/)
  assert.match(out.text, /reached=未要求/)
})

test('phone_scroll 的 until 在无量程控件上的失败给出量程指引', async () => {
  const { byName } = toolsWith((capability) => ({
    ok: false,
    code: 'E_NODE_NOT_ACTIONABLE',
    capability,
    reason: 'until needs a node that reports a range; read ui.node {range}',
  }))
  const out = await byName.phone_scroll.execute({ nodeId: 1, direction: 'forward', until: 23 })
  assert.equal(out.ok, false)
  assert.equal(out.code, 'E_NODE_NOT_ACTIONABLE')
  assert.match(out.text, /range/)
  assert.match(byName.phone_scroll.description, /自报量程/)
})

test('renderTree 的 clickable 标注以 actions 为准', () => {
  const text = renderTree({
    package: 'com.example',
    screen: { width: 1080, height: 1920, display: 0, densityDpi: 420 },
    nodes: [
      { nodeId: 0, class: 'android.widget.Button', text: '删除', clickable: true, left: 8, top: 16, right: 120, bottom: 60 },
      { nodeId: 1, class: 'android.view.View', desc: '已移出屏幕', clickable: true, offscreen: true },
      { nodeId: 2, class: 'android.widget.ScrollView', scrollable: true, left: 0, top: 0, right: 1080, bottom: 1920 },
    ],
  }, '/tmp/pilot-snapshot-x.json')
  assert.match(text, /\(8,16\)-\(120,60\)/)
  assert.match(text, /offscreen=true/)
  assert.match(text, /full=\/tmp\/pilot-snapshot-x\.json/)
  assert.match(text, /densityDpi=420/)
  assert.match(text, /以 phone_node 的 actions 为准/)
})

test('快照落盘只保留最近若干份，且不碰目录里的其他文件', async (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-out-'))
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }))
  const foreign = path.join(dir, 'keep-me.txt')
  fs.writeFileSync(foreign, 'not ours')
  for (let i = 0; i < SNAPSHOT_KEEP + 5; i++) {
    const file = path.join(dir, `pilot-snapshot-old-${String(i).padStart(3, '0')}.json`)
    fs.writeFileSync(file, '{}')
    fs.utimesSync(file, new Date(i * 1000), new Date(i * 1000))
  }
  const { byName } = toolsWith({ data: { nodes: [], package: 'com.example' } }, dir)
  const out = await byName.phone_observe.execute({})
  assert.ok(out.path && fs.existsSync(out.path), '快照路径要写进工具输出')
  assert.match(out.text, new RegExp(`full=${out.path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`))
  const left = fs.readdirSync(dir).filter((n) => n.startsWith('pilot-snapshot-'))
  assert.equal(left.length, SNAPSHOT_KEEP)
  assert.equal(fs.readFileSync(foreign, 'utf8'), 'not ours')
  // 只按自己写出的文件名删，目录外的东西一概不碰。
  pruneSnapshots(path.join(dir, 'no-such-dir'))
})

test('截图产物同样只保留最近若干份：旧帧被清，调用方指定落点时不清理', async (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-shot-'))
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }))
  for (let i = 0; i < SNAPSHOT_KEEP + 5; i++) {
    const file = path.join(dir, `${SHOT_PREFIX}old-${String(i).padStart(3, '0')}${SHOT_SUFFIX}`)
    fs.writeFileSync(file, 'png')
    fs.utimesSync(file, new Date(i * 1000), new Date(i * 1000))
  }
  // 截图像素由宿主按 --out 落盘：桩通道照此把文件写出来，工具层才有产物可指。
  const channel = {
    resolve: () => ({ root: '/fake/pilot', source: 'MOBILE_PILOT_HOME', info: { entry: '/fake/pilot/bin/pilot', manifest: '/fake/pilot/capabilities.json', entryExecutable: true }, tried: [] }),
    call: async (capability, args, options) => {
      if (options && options.out) fs.writeFileSync(options.out, 'png')
      return { ok: true, capability, surface: 'foreground', data: { display: 0, bytes: 8 } }
    },
  }
  const tools = buildTools({ defineTool: (spec) => spec, channel, outDirOption: dir })
  const byName = Object.fromEntries(tools.map((tool) => [tool.name, tool]))
  const out = await byName.phone_capture.execute({})
  assert.equal(out.ok, true)
  assert.ok(fs.existsSync(out.path), '本次的截图必须在')
  // 截图是 .png：旧实现只清 pilot-snapshot- 前缀的 .json，这里钉住 .png 那一份策略。
  const frames = () => fs.readdirSync(dir).filter((n) => n.startsWith(SHOT_PREFIX) && n.endsWith(SHOT_SUFFIX))
  assert.equal(frames().length, SNAPSHOT_KEEP)
  assert.ok(!fs.existsSync(path.join(dir, `${SHOT_PREFIX}old-000${SHOT_SUFFIX}`)), '最旧的帧要被清掉')
  // 调用方指定落点时本次没有自己的产物：目录里剩下的旧帧不被这趟顺手清掉。
  const before = frames()
  await byName.phone_capture.execute({ out: path.join(dir, 'named.png') })
  assert.deepEqual(frames(), before, '指定落点时旧帧不被清理')
})

test('宿主复制产物失败：phone_capture 不假成功，也不指回从未写出的路径', async (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-cap-fail-'))
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }))
  // 宿主/入口在把产物复制到落点失败时：回包仍是 ok:true，artifacts 槽位是空串并标 artifactDelivery:'failed'。
  const { byName } = toolsWith({
    ok: true, surface: 'foreground', data: { display: 0, bytes: 128 },
    artifacts: [''], artifactDelivery: 'failed',
  }, dir)
  const out = await byName.phone_capture.execute({})
  assert.equal(out.ok, false, '能力成功而产物没落地，不能报成功')
  assert.equal(out.code, 'E_ARTIFACT_WRITE')
  assert.equal(out.path, '', '兜底回预设文件名等于指向一个从未写出的文件')
  assert.equal(out.exitCode, 6, '交付失败属包装层故障，按 6 报')
  assert.match(out.text, /产物交付失败/)
  assert.match(out.text, /文件未落地/)
  assert.match(out.text, /out 参数/)
  assert.doesNotMatch(out.text, /截图已保存/)
})

test('phone_call 带 --out 时同样受产物交付失败判定约束', async () => {
  const { byName } = toolsWith({
    ok: true, surface: 'foreground', data: {},
    artifacts: [''], artifactDelivery: 'failed',
  })
  const out = await byName.phone_call.execute({ capability: 'screen.record', out: '/tmp/clip.mp4' })
  assert.equal(out.ok, false)
  assert.equal(out.code, 'E_ARTIFACT_WRITE')
  assert.equal(out.path, '')
  assert.match(out.text, /文件未落地/)
})

test('宿主不给产物清单也不给交付标记时，phone_capture 的兜底路径照旧可用', async (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-cap-ok-'))
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }))
  const { byName } = toolsWith({ ok: true, surface: 'foreground', data: { display: 0, bytes: 1 } }, dir)
  const out = await byName.phone_capture.execute({})
  assert.equal(out.ok, true, '交付失败判定不得误伤旧宿主的回包形状')
  assert.ok(out.path, '回包不带 artifacts 时仍按预设路径兜底')
  assert.match(out.text, /截图已保存/)
})

test('buildTools 产出固定的 14 个工具，且每个都符合 schema 规矩', () => {
  const captured = []
  const defineTool = (spec) => { captured.push(spec); return spec }
  const channel = { resolve: () => ({ root: null, tried: [] }), call: async () => ({ ok: false }) }
  const tools = buildTools({ defineTool, channel, outDirOption: os.tmpdir() })

  assert.equal(tools.length, 14)
  const names = tools.map((t) => t.name)
  assert.deepEqual([...new Set(names)].length, names.length, '工具名必须唯一')
  for (const want of [
    'phone_status', 'phone_call', 'phone_observe', 'phone_click', 'phone_setValue', 'phone_scroll',
    'phone_node', 'phone_waitFor', 'phone_key', 'phone_launch', 'phone_capture', 'phone_intent', 'phone_surface',
    'phone_ask',
  ]) assert.ok(names.includes(want), `缺少工具 ${want}`)
  // 危险通路不做一键工具：shell 与坐标注入只能经 phone_call 点名。
  for (const banned of ['phone_shell', 'phone_tap', 'phone_swipe', 'phone_input', 'phone_appops']) {
    assert.ok(!names.includes(banned), `${banned} 不该存在`)
  }

  for (const tool of tools) {
    assert.ok(tool.description && tool.description.length > 20, `${tool.name} 的 description 太短`)
    assert.equal(typeof tool.execute, 'function', `${tool.name} 缺 execute`)
    assert.equal(typeof tool.output.render, 'function', `${tool.name} 缺 render`)
    // defineTool 会即时校验：additionalProperties 必须显式给出
    assert.equal(tool.output.schema.additionalProperties, false, `${tool.name} 的输出 schema 必须 additionalProperties:false`)
    assert.ok(tool.output.schema.properties.ok, `${tool.name} 的输出必须含 ok`)
    assert.ok(tool.output.schema.properties.text, `${tool.name} 的输出必须含 text`)
    for (const [key, spec] of Object.entries(tool.parameters || {})) {
      assert.equal(typeof spec.type, 'string', `${tool.name}.${key} 缺 type`)
      if (spec.type === 'object') {
        assert.ok('additionalProperties' in spec, `${tool.name}.${key} 是对象，必须显式 additionalProperties`)
      }
    }
  }
})

/**
 * 模拟宿主 defineTool 的即时校验：schema 声明的字段一个不能少，回包也不许多一键，
 * 类型按声明核对。工具层承诺「schema 与返回逐字段一致」，就靠它在三类回包路径上兑现。
 */
function validateAgainstSchema (spec, value) {
  const props = spec.output.schema.properties
  assert.ok(value && typeof value === 'object', `${spec.name} 的返回不是对象`)
  for (const [key, meta] of Object.entries(props)) {
    assert.ok(Object.prototype.hasOwnProperty.call(value, key), `${spec.name} 的返回缺 schema 字段 ${key}`)
    const v = value[key]
    const what = `${spec.name}.${key}`
    if (meta.type === 'integer') assert.ok(Number.isInteger(v), `${what} 应为 integer，实为 ${JSON.stringify(v)}`)
    else if (meta.type === 'boolean') assert.equal(typeof v, 'boolean', `${what} 应为 boolean，实为 ${JSON.stringify(v)}`)
    else if (meta.type === 'string') assert.equal(typeof v, 'string', `${what} 应为 string，实为 ${JSON.stringify(v)}`)
    else if (meta.type === 'number') assert.equal(typeof v, 'number', `${what} 应为 number，实为 ${JSON.stringify(v)}`)
    else if (meta.type === 'object') assert.ok(v !== null && typeof v === 'object', `${what} 应为 object，实为 ${JSON.stringify(v)}`)
  }
  for (const key of Object.keys(value)) {
    assert.ok(Object.prototype.hasOwnProperty.call(props, key), `${spec.name} 的返回多出 schema 之外的键 ${key}`)
  }
}

/** 三类回包路径下各工具的最小入参：必填参数给足，其余留空走兜底。 */
const TOOL_ARGS = {
  phone_status: { probe: true },
  phone_call: { capability: 'pkg.query' },
  phone_key: { key: 'back' },
  phone_launch: { package: 'com.example.app' },
  phone_intent: { template: 'alarm.show' },
  phone_surface: { action: 'query' },
  phone_ask: { question: '这两个里面选哪个', options: ['A', 'B'] },
}

test('14 个工具在成功/宿主失败/包装层错误三类回包上，schema 与返回逐字段对齐', async (t) => {
  const out = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-schema-'))
  t.after(() => fs.rmSync(out, { recursive: true, force: true }))
  // phone_status 要真的读清单文件：resolve 必须指向一个可解析的挂载点，假路径会走坏清单分支。
  const pack = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-pack-'))
  t.after(() => fs.rmSync(pack, { recursive: true, force: true }))
  fs.mkdirSync(path.join(pack, 'bin'), { recursive: true })
  fs.writeFileSync(path.join(pack, 'capabilities.json'), JSON.stringify({
    capabilities: [{ id: 'ui.snapshot', usable: true }],
    backend: { shizuku: { running: true } },
  }))
  const replyByClass = {
    成功: { ok: true, code: null, surface: 'foreground', degraded: true, degradedFrom: 'trusted-display', retryable: null, exitCode: 0, elapsedMs: 7, reason: null, note: null, requestId: 'req-1', state: 'SUCCEEDED', data: { nodes: [{ nodeId: 0, text: 'x' }], count: 3 }, artifacts: ['/tmp/stub-artifact.png'], choice: { kind: 'option', index: 1, label: 'B' } },
    宿主失败: { ok: false, code: 'E_GATE_HOST_DENIED', reason: 'denied by gate', retryable: false, exitCode: 2, surface: null, degraded: null, degradedFrom: null, elapsedMs: 12, data: null, artifacts: [] },
    包装层错误: { ok: false, code: 'E_WRAPPER_TIMEOUT', reason: '包装层超时', retryable: true, exitCode: 6, surface: null, degraded: null, degradedFrom: null, elapsedMs: null, data: null, artifacts: [] },
  }
  const expectedByClass = {
    成功: { ok: true, code: '', exitCode: 0, retryable: false, surface: 'foreground', degraded: true, degradedFrom: 'trusted-display', elapsedMs: 7, requestId: 'req-1', state: 'SUCCEEDED' },
    宿主失败: { ok: false, code: 'E_GATE_HOST_DENIED', exitCode: 2, retryable: false, surface: '', degraded: false, degradedFrom: '', elapsedMs: 12, requestId: '', state: '' },
    包装层错误: { ok: false, code: 'E_WRAPPER_TIMEOUT', exitCode: 6, retryable: true, surface: '', degraded: false, degradedFrom: '', elapsedMs: 0, requestId: '', state: '' },
  }
  for (const [label, reply] of Object.entries(replyByClass)) {
    const captured = []
    const channel = {
      resolve: () => ({ root: pack, source: 'MOBILE_PILOT_HOME', info: { entry: path.join(pack, 'bin', 'pilot'), manifest: path.join(pack, 'capabilities.json'), entryExecutable: true }, tried: [] }),
      call: async (capability) => ({ capability, data: {}, ...reply }),
      ask: async () => ({ capability: 'ask', data: {}, ...reply }),
    }
    buildTools({
      defineTool: (spec) => {
        captured.push(spec)
        return {
          ...spec,
          execute: async (args) => {
            const value = await spec.execute(args)
            validateAgainstSchema(spec, value)
            return value
          },
        }
      },
      channel,
      outDirOption: out,
    })
    assert.equal(captured.length, 14, `${label}：应为 14 个工具`)
    for (const spec of captured) {
      const value = await spec.execute(TOOL_ARGS[spec.name] || {})
      for (const [key, want] of Object.entries(expectedByClass[label])) {
        assert.equal(value[key], want, `${spec.name}（${label}）的 ${key} 应为 ${JSON.stringify(want)}，实为 ${JSON.stringify(value[key])}`)
      }
    }
  }
})

/** 指向真实临时挂载点的工具组：phone_status 要真的读清单文件，stubChannel 的假路径不够用。 */
function toolsOnPack (t, reply, manifestBody) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-status2-'))
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }))
  fs.mkdirSync(path.join(dir, 'bin'), { recursive: true })
  fs.writeFileSync(path.join(dir, 'bin', 'pilot'), OK_ENTRY)
  fs.writeFileSync(path.join(dir, 'capabilities.json'), manifestBody === undefined
    ? JSON.stringify({
        generatedAt: '2026-09-27T00:00:00Z',
        capabilities: [{ id: 'ui.snapshot', usable: true }, { id: 'sys.shell', usable: false }],
        backend: { shizuku: { running: true, authorized: true, bound: true } },
      })
    : manifestBody)
  const calls = []
  const channel = {
    resolve: () => ({ root: dir, source: 'MOBILE_PILOT_HOME', info: { entry: path.join(dir, 'bin', 'pilot'), manifest: path.join(dir, 'capabilities.json'), entryExecutable: true }, tried: [] }),
    call: async (capability, args, options) => {
      calls.push({ capability, args, options })
      return typeof reply === 'function' ? reply(capability, args) : { ok: true, capability, data: {}, ...reply }
    },
  }
  const tools = buildTools({ defineTool: (spec) => spec, channel, outDirOption: dir })
  return { byName: Object.fromEntries(tools.map((tool) => [tool.name, tool])), calls }
}

test('phone_status：清单缺失或损坏 → E_WRAPPER_BAD_MANIFEST，不再以空清单报成功', async (t) => {
  // 两种损坏各验一遍：整个文件解析不动 / 解析得动但没有 capabilities 数组。
  for (const broken of ['{not json', JSON.stringify({ backend: {} })]) {
    const { byName } = toolsOnPack(t, { ok: true }, broken)
    const out = await byName.phone_status.execute({})
    assert.equal(out.ok, false)
    assert.equal(out.code, 'E_WRAPPER_BAD_MANIFEST')
    assert.equal(out.exitCode, 6, '清单读不出属包装层故障，按 6 报')
    assert.equal(out.capabilities, 0)
    assert.equal(out.probeOk, true, 'probe 未请求时 probeOk 固定为 true')
    assert.match(out.text, /清单缺失或无法解析/)
    assert.match(out.text, /重铺资产/)
    // 请求了 probe 却没跑成：probeOk 不得停在 true 装作验证过
    const probed = await byName.phone_status.execute({ probe: true })
    assert.equal(probed.ok, false)
    assert.equal(probed.code, 'E_WRAPPER_BAD_MANIFEST')
    assert.equal(probed.probeOk, false)
  }
})

test('phone_status：probe 失败则整体失败并沿用 probe 的错误码，probe 成功才 ok', async (t) => {
  const { byName } = toolsOnPack(t, { ok: false, code: 'E_TRANSPORT_TIMEOUT', retryable: null, exitCode: 3, elapsedMs: 1200, reason: 'no reply in budget' })
  const failed = await byName.phone_status.execute({ probe: true })
  assert.equal(failed.ok, false)
  assert.equal(failed.code, 'E_TRANSPORT_TIMEOUT')
  assert.equal(failed.probeOk, false)
  assert.equal(failed.exitCode, 3)
  assert.equal(failed.elapsedMs, 1200)
  assert.equal(failed.retryable, false)
  // 失败也要带上已读到的清单读数，不能把状态一并丢掉
  assert.match(failed.text, /能力 2 条/)
  assert.match(failed.text, /probe 失败，通道未证实可用/)

  const good = toolsOnPack(t, { ok: true, surface: 'virtual', degraded: false, degradedFrom: null, exitCode: 0, elapsedMs: 88, data: { count: 42 } })
  const probed = await good.byName.phone_status.execute({ probe: true })
  assert.equal(probed.ok, true)
  assert.equal(probed.code, '')
  assert.equal(probed.probeOk, true)
  assert.equal(probed.surface, 'virtual')
  assert.equal(probed.exitCode, 0)
  assert.equal(probed.elapsedMs, 88)
  assert.match(probed.text, /probe pkg\.query: ok 88ms/)

  // 不请求 probe：纯本地读数，没有执行面与耗时可言，按空值口径给
  const quiet = await good.byName.phone_status.execute({})
  assert.equal(quiet.ok, true)
  assert.equal(quiet.probeOk, true)
  assert.equal(quiet.surface, '')
  assert.equal(quiet.degraded, false)
  assert.equal(quiet.exitCode, 0)
  assert.equal(quiet.elapsedMs, 0)
})

test('phone_observe：快照拿到但落盘失败 → E_ARTIFACT_WRITE，不假成功也不指空路径', async (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-out-fail-'))
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }))
  const { byName } = toolsWith({ ok: true, surface: 'foreground', data: { nodes: [{ nodeId: 0, text: 'a', clickable: true, left: 0, top: 0, right: 10, bottom: 10 }], package: 'com.example' } }, dir)
  const realWrite = fs.writeFileSync
  fs.writeFileSync = () => { throw new Error('no space left') }
  try {
    const out = await byName.phone_observe.execute({})
    assert.equal(out.ok, false)
    assert.equal(out.code, 'E_ARTIFACT_WRITE')
    assert.equal(out.artifactOk, false)
    assert.equal(out.path, '')
    assert.equal(out.exitCode, 6)
    assert.ok(out.nodes >= 1, '节点数照实报：数据读到过就是读到过')
    assert.match(out.text, /落盘失败，本层未保存完整副本/)
    assert.doesNotMatch(out.text, /data 预览/)
    assert.doesNotMatch(out.text, /完整内容/)
    assert.match(out.text, /MOBILE_PILOT_OUT_DIR/)
  } finally {
    fs.writeFileSync = realWrite
  }
})

test('renderTree 落盘失败的分支不得声称「完整内容」或指向 data 预览', () => {
  const text = renderTree({ package: 'com.example', nodes: [{ nodeId: 0, text: 'a', clickable: true, left: 0, top: 0, right: 10, bottom: 10 }] }, '')
  assert.match(text, /落盘失败，本层未保存完整副本/)
  assert.doesNotMatch(text, /data 预览/)
  assert.doesNotMatch(text, /完整内容/)
})

test('清单损坏与落盘失败两条错误码有各自的处置建议', () => {
  const manifest = hintFor({ code: 'E_WRAPPER_BAD_MANIFEST' })
  assert.match(manifest, /重铺资产/)
  assert.match(manifest, /phone_status/)
  const artifact = hintFor({ code: 'E_ARTIFACT_WRITE' })
  assert.match(artifact, /MOBILE_PILOT_OUT_DIR/)
  assert.match(artifact, /没有另存副本/)
})

test('许可口径分开说：E_AWAITING_CONSENT 只指 Pilot 审批，系统采集同意按执行路线讲', () => {
  const { byName } = toolsWith({ ok: true, data: {} })
  for (const name of ['phone_capture', 'phone_call']) {
    const d = byName[name].description
    assert.match(d, /Pilot 审批在等答复/)
    assert.match(d, /MediaProjection/)
    // 旧口径把 Pilot 审批与系统确认框混成一句，且一口咬定前台每帧都要系统框
    assert.doesNotMatch(d, /每帧要用户答复一次系统确认框/)
  }
  const capture = byName.phone_capture.description
  assert.match(capture, /取决于执行路线/)
  assert.match(capture, /无障碍截图路线没有系统框/)
  assert.match(capture, /以回包的 surface 与错误码为准/)
})

test('交付文本里的屏幕叫法统一：只有虚拟屏与主屏/前台屏', () => {
  const banned = ['虚拟面', '真屏', '那块屏', '新屏', '用户那块屏']
  const targets = [
    path.join(LIB_DIR, 'pilot.js'),
    path.join(LIB_DIR, 'tools.js'),
    path.join(LIB_DIR, 'index.js'),
    path.join(LIB_DIR, 'mount.js'),
    path.join(PACK_DIR, 'plugin', 'package.json'),
    path.join(PACK_DIR, 'plugin', 'bin', 'mobile-pilot'),
    path.join(PACK_DIR, 'README.md'),
    path.join(PACK_DIR, 'install.sh'),
  ]
  for (const file of targets) {
    if (!fs.existsSync(file)) continue
    const text = fs.readFileSync(file, 'utf8')
    for (const word of banned) {
      assert.ok(!text.includes(word), `${path.basename(file)} 里出现了「${word}」`)
    }
  }
})

test('注释不复述内部文档编号', () => {
  for (const file of ['pilot.js', 'tools.js', 'index.js']) {
    const text = fs.readFileSync(path.join(LIB_DIR, file), 'utf8')
    assert.doesNotMatch(text, /LESSONS|经验库|L-\d/, `${file} 的注释指向了内部文档或编号`)
  }
})

test('插件导出面：注入 tools，并保留原有包 API', () => {
  assert.equal(plugin.name, '@local/mobile-pilot')
  assert.ok(Array.isArray(plugin.inject) && plugin.inject.includes('tools'))
  assert.equal(typeof plugin.apply, 'function')
  assert.equal(typeof plugin.uiCapabilities, 'function')
  assert.equal(typeof plugin.PilotChannel, 'function')
  assert.ok(plugin.ENTRY_REL && plugin.MANIFEST_REL && plugin.GUIDE_REL)
})

test('拿不到 defineTool 时只报告不抛异常，工具一个也不注册', async () => {
  const written = []
  const original = process.stderr.write
  process.stderr.write = (chunk) => { written.push(String(chunk)); return true }
  try {
    let registered = 0
    const ctx = { tools: { register: () => { registered++ } } }
    const returned = plugin.apply(ctx, {})
    const value = await returned
    assert.equal(value, undefined)

    let defineToolAvailable = false
    try { defineToolAvailable = typeof require('@deepseek-ai/dsh-tools').defineTool === 'function' } catch { defineToolAvailable = false }

    if (defineToolAvailable) {
      assert.equal(registered, 14)
    } else {
      assert.equal(registered, 0)
      assert.match(written.join(''), /未找到 dsh-tools，工具未注册/)
    }
    // 宿主没给 tools 服务时同样只是报告，不抛；报告的内容要说清缺的是哪一边。
    written.length = 0
    assert.equal(await plugin.apply({}, {}), undefined)
    assert.equal(await plugin.apply(undefined, undefined), undefined)
    assert.match(written.join(''), /未提供 tools 服务/)
  } finally {
    process.stderr.write = original
  }
})

test('注册全部成功：返回工具数，不留任何 PARTIAL_REGISTRATION 输出', () => {
  const registered = []
  const written = []
  const original = process.stderr.write
  process.stderr.write = (chunk) => { written.push(String(chunk)); return true }
  try {
    const n = plugin.registerTools((spec) => spec, { tools: { register: (tool) => { registered.push(tool.name) } } }, {})
    assert.equal(n, 14)
    assert.doesNotMatch(written.join(''), /PARTIAL_REGISTRATION/)
  } finally {
    process.stderr.write = original
  }
})

test('注册第 N 项失败：停下注册并点名 PARTIAL_REGISTRATION 与已注册清单', () => {
  // 注册接口没有批次回滚：第 4 项（phone_click）抛错时，前 3 项撤不掉，
  // 能做的是停下、把已注册清单和失败项如实例数报出来，并明示重启后重试或按部分工具继续。
  const registered = []
  const register = (tool) => {
    if (tool.name === 'phone_click') throw new Error('boom')
    registered.push(tool.name)
  }
  const written = []
  const original = process.stderr.write
  process.stderr.write = (chunk) => { written.push(String(chunk)); return true }
  try {
    const n = plugin.registerTools((spec) => spec, { tools: { register } }, {})
    assert.equal(n, 3, '失败项之前注册了几项就报几项')
    assert.deepEqual(registered, ['phone_status', 'phone_call', 'phone_observe'])
    const out = written.join('')
    assert.match(
      out,
      /PARTIAL_REGISTRATION: registered=\[phone_status, phone_call, phone_observe\] failed=phone_click \(boom\)/,
      `要点名清单与失败项，实为：${out}`,
    )
    assert.match(out, /没有批次回滚/)
    assert.match(out, /重启 dsh/)
  } finally {
    process.stderr.write = original
  }
})

test('动态 import 兜底按已解析路径的 file URL 导入（临时纯 ESM fixture）', async (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-esm-'))
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }))
  fs.writeFileSync(path.join(dir, 'index.mjs'), 'export const defineTool = (spec) => spec\n')
  const loaded = await plugin.importDefineTool([path.join(dir, 'index.mjs')])
  assert.equal(typeof loaded.defineTool, 'function', '解析到的纯 ESM 应经 file URL 导入成功')
  assert.equal(loaded.via, path.join(dir, 'index.mjs'))
})

test('解析路径与裸包名全部落空：note 带回每一步卡在哪', async () => {
  let resolvable = false
  try { resolvable = typeof require('@deepseek-ai/dsh-tools').defineTool === 'function' } catch { resolvable = false }
  const loaded = await plugin.importDefineTool([path.join(os.tmpdir(), 'no-such-tools-fixture.mjs')])
  if (resolvable) {
    assert.equal(typeof loaded.defineTool, 'function', '裸包名兜底在装了 dsh-tools 的机器上仍可用')
    return
  }
  assert.equal(loaded.defineTool, null)
  assert.match(loaded.note, /no-such-tools-fixture\.mjs/, 'file URL 那一步的原因要带回来')
  assert.match(loaded.note, /dsh-tools/, '裸包名那一步的原因也要带回来')
})

test('工具真的能调通通道（在有挂载点的机器上跑，离开设备则跳过）', async (t) => {
  const probe = new PilotChannel()
  if (!probe.resolve().root) { t.skip('本机没有 /opt/pilot 挂载点'); return }
  const r = await probe.call('pkg.query', {})
  assert.equal(r.ok, true)
  assert.ok(r.data && r.data.count > 0)
})

test('phone_intent 的模板表跟住宿主的封闭表：八条，且不含删除类', () => {
  const { byName } = toolsWith({ data: { resolvedPackage: 'com.android.deskclock' } })
  const spec = byName.phone_intent
  assert.deepEqual(spec.parameters.template.enum, [
    'alarm.set', 'timer.set', 'alarm.show', 'timer.show', 'settings.open', 'app.info', 'dial', 'web.open',
  ])
  assert.deepEqual(spec.parameters.page.enum,
    ['wifi', 'bluetooth', 'display', 'location', 'sound', 'apn', 'developer', 'nfc'])
  // 表里没有、也不给加的：删除与取消闹钟一类。这条断言防的是"顺手加一条方便的"。
  for (const banned of ['delete', 'remove', 'dismiss', 'snooze', 'uninstall', 'clear']) {
    assert.ok(!spec.parameters.template.enum.some((name) => name.includes(banned)), `模板表里出现了 ${banned}`)
  }
  // SKIP_UI 只对那两条设值入口有意义，别让它变成"每条都不上屏"的假承诺
  assert.match(spec.description, /SKIP_UI 只对 alarm\.set\/timer\.set 有意义/)
  assert.match(spec.description, /com\.android\.intentresolver/)
})

test('启动未落地：reason 点名了占着前面的包时，建议不许再指 release/重建虚拟屏', () => {
  const hint = hintFor({
    code: 'E_LAUNCH_NOT_LANDED',
    reason: 'the launch was accepted but another app is confirmed to hold the front: asked for ' +
      'com.android.deskclock, foreground is com.vivo.doubleinstance.',
  })
  assert.match(hint, /com\.vivo\.doubleinstance/, '要把占着前面的那个包原样点出来')
  assert.match(hint, /screen\.capture/, '系统选择框只有像素看得见，得指向截帧')
  assert.match(hint, /不要 release\/重建虚拟屏/, '给 release/重建是反方向的建议')
  // 另一档（没有包占前面）仍应保留原来的重试与重建说法
  const other = hintFor({ code: 'E_LAUNCH_NOT_LANDED', reason: 'no foreign window seen yet' })
  assert.match(other, /再以同样参数重试/)
})

test('预览截断时：有落盘副本就给路径，没有就不许说"见落盘文件"', () => {
  const big = { packages: Array.from({ length: 300 }, (_, i) => ({ pkg: `com.example.app${i}` })) }
  const withFile = describe({ capability: 'pkg.query', ok: true, data: big, exitCode: 0 },
    () => '/tmp/pilot-full-1.json')
  assert.match(withFile, /完整内容见 \/tmp\/pilot-full-1\.json/)
  const noFile = describe({ capability: 'pkg.query', ok: true, data: big, exitCode: 0 })
  assert.match(noFile, /本层没有另存完整副本/)
  assert.doesNotMatch(noFile, /完整内容见落盘文件/, '不许指向一个不存在的地方')
  assert.ok(noFile.length < 4000, '截断仍然生效（摘要不会被撑爆）')
})

test('成功回包的解释走 note，失败原因才走 reason：一个键不能两种语义', () => {
  const okDegrade = normalize({
    capability: 'ui.node', exitCode: 0,
    stdout: JSON.stringify({ ok: true, capability: 'ui.node', note: 'ran on the user screen', data: { nodeId: 0 } }),
  })
  assert.equal(okDegrade.ok, true)
  assert.equal(okDegrade.note, 'ran on the user screen')
  assert.equal(okDegrade.reason, null, '成功回包不该再往 reason 里塞降级说明')
  const failure = normalize({
    capability: 'ui.node', exitCode: 3,
    stdout: JSON.stringify({ ok: false, capability: 'ui.node', reason: 'no node with nodeId=7', error: { code: 'E_NODE_NOT_FOUND' } }),
  })
  assert.equal(failure.reason, 'no node with nodeId=7')
  assert.equal(failure.note, null)
})

// ---------------------------------------------------------------------------
// 宿主入口 CLI（core 资产名 relay-client.cjs，物化后是无扩展名的 bin/pilot）：
// v2 控制通道的纯函数与严格参数边界。参数/用法错误都在触碰文件系统之前退出。
// 对账以核模块资产 relay-client.cjs 为准——真机物化的就是它；
// source/pilot 的 pilot-client.cjs 是已冻结的回退基线，只作最后兜底。
// core 资产的通道目录与命令名都从脚本自身位置/基名推导（不再写死 /opt/pilot），
// 所以 spawn 前一律先把它按真机形状物化到固定路径 /opt/pilot/bin/pilot：
// 通道落在 <入口>/run、命令名是基名 pilot，与 mock 宿主的固定通道对上；
// 进程收尾时拆除。物化不成（固定路径不可写）则退回原地直跑。
// ---------------------------------------------------------------------------
const CLI_PATH = [
  // 工程根 pilot/interlock-relay-core/src/main/assets/relay/relay-client.cjs（首选，新位置）
  new URL('../../../../../../../../pilot/interlock-relay-core/src/main/assets/relay/relay-client.cjs', import.meta.url),
  // source/pilot/src/main/assets/pilot/pilot-client.cjs（冻结基线，最后回退）
  new URL('../../../../../../pilot/src/main/assets/pilot/pilot-client.cjs', import.meta.url),
]
  .map(fileURLToPath)
  .find((p) => fs.existsSync(p))
if (!CLI_PATH) throw new Error('找不到宿主入口脚本：core 资产 relay-client.cjs 与基线 pilot-client.cjs 均不存在')
const cli = require(CLI_PATH)

const CONTROL_PRESENT = fs.existsSync('/opt/pilot/run/control-inbox') && fs.existsSync('/opt/pilot/run/control-outbox')
const noControl = CONTROL_PRESENT ? { skip: '本机存在 v2 控制目录：环境前提不成立' } : {}

// 真机物化形状的固定挂载点（与 mount.js 的设备端路径一致，测完即拆）。
const CLI_FIXED_PACK = path.join('/opt/pilot', 'bin', 'pilot')

function runCli (args) {
  let target = CLI_PATH
  try {
    fs.mkdirSync(path.dirname(CLI_FIXED_PACK), { recursive: true })
    fs.copyFileSync(CLI_PATH, CLI_FIXED_PACK)
    target = CLI_FIXED_PACK
  } catch { /* 固定路径不可写：退回原地直跑，只有依赖固定通道/落点的用例会失败 */ }
  const r = spawnSync(process.execPath, [target, ...args], { encoding: 'utf8' })
  return { code: r.status, stdout: r.stdout || '', stderr: r.stderr || '' }
}

// 收尾拆除物化出来的固定挂载点（rmdir 对非空目录会失败并被忽略，不会误删他物）。
after(() => {
  try { fs.rmSync(CLI_FIXED_PACK, { force: true }) } catch { /* 已清理 */ }
  for (const dir of [path.dirname(CLI_FIXED_PACK), '/opt/pilot', '/opt']) {
    try { fs.rmdirSync(dir) } catch { /* 非空或缺席就不动 */ }
  }
})

test('stableStringify/digestOf：对象键序无关、数组保序、中文与转义稳定，摘要是 64 位小写十六进制', () => {
  assert.equal(
    cli.stableStringify({ b: 2, a: { d: 1, c: [1, 2] } }),
    cli.stableStringify({ a: { c: [1, 2], d: 1 }, b: 2 }),
    '「同一件事」不能因键序不同而拆成两件事',
  )
  assert.notEqual(cli.stableStringify({ list: [1, 2] }), cli.stableStringify({ list: [2, 1] }), '数组的序是内容的一部分')
  assert.equal(cli.stableStringify({ s: '中文"引"\n换行\\反斜杠' }), '{"s":"中文\\"引\\"\\n换行\\\\反斜杠"}')
  assert.equal(cli.stableStringify(null), 'null')
  assert.equal(cli.stableStringify([true, 1.5]), '[true,1.5]')
  const digest = cli.digestOf('ui.tap', { x: 1, y: '中文' })
  assert.match(digest, /^[0-9a-f]{64}$/)
  assert.equal(digest, cli.digestOf('ui.tap', { y: '中文', x: 1 }), '键序不影响摘要')
  assert.notEqual(cli.digestOf('ui.tap', { x: 1 }), cli.digestOf('ui.tap', { x: 2 }))
  assert.notEqual(cli.digestOf('ui.tap', { x: 1 }), cli.digestOf('ui.click', { x: 1 }), '能力名参与摘要')
})

test('终态帧判定与宿主写法对齐：受理回执与中间阶段不算终态，ok:false 与终态阶段算', () => {
  assert.equal(cli.isTerminalSubmitFrame({ v: 2, op: 'submit', ok: true, state: 'QUEUED', targetId: 'r' }), false)
  assert.equal(cli.isTerminalSubmitFrame({ ok: true, state: 'ADMITTED' }), false)
  assert.equal(cli.isTerminalSubmitFrame({ ok: true, state: 'EXECUTING' }), false)
  assert.equal(cli.isTerminalSubmitFrame({ ok: true, state: 'SUCCEEDED', result: { ok: true } }), true)
  assert.equal(cli.isTerminalSubmitFrame({ ok: false, cause: 'E_TRANSPORT_MALFORMED/too deep' }), true)
  assert.equal(cli.isTerminalSubmitFrame({ ok: true, state: 'EXPIRED' }), true, '幂等命中的过期终态不会再有回包')
  assert.equal(cli.isTerminalSubmitFrame({ ok: true, state: 'UNKNOWN' }), true)
  assert.equal(cli.isTerminalSubmitFrame(null), false)
})

test('终态帧输出：v1 回包原样铺开、外层补 v2 阶段；拒绝帧合成 v1 形状失败并沿用宿主退出码表', () => {
  const merged = cli.submitOutcome({
    v: 2, id: 'r1', op: 'submit', ok: true, state: 'SUCCEEDED', targetId: 'r1',
    result: { v: 1, id: 'r1', ok: true, elapsedMs: 5, data: { a: 1 } },
  })
  assert.equal(merged.v, 2, '外层协议版本盖过 v1 回包自带的 v')
  assert.equal(merged.state, 'SUCCEEDED')
  assert.equal(merged.ok, true)
  assert.deepEqual(merged.data, { a: 1 })
  const refused = cli.submitOutcome({ v: 2, id: 'r1', op: 'submit', ok: false, cause: 'E_TRANSPORT_MALFORMED/too deep' })
  assert.equal(refused.ok, false)
  assert.equal(refused.state, null)
  assert.equal(refused.error.code, 'E_TRANSPORT_MALFORMED')
  assert.equal(refused.error.exitCode, 1)
  assert.match(refused.reason, /too deep/)
  // 裸文本 cause（digest conflict）是调用方自己的 id/载荷配对站不住：
  // 按用法类报 E_TRANSPORT_MALFORMED，让调用方换 id 重投，而不是按宿主故障停下。
  const refusedBare = cli.submitOutcome({ v: 2, id: 'r1', op: 'submit', ok: false, cause: 'digest conflict' })
  assert.equal(refusedBare.error.code, 'E_TRANSPORT_MALFORMED')
  assert.equal(refusedBare.error.exitCode, 1)
  // 宿主故障（入队/状态落盘失败）带 E_INTERNAL 前缀：不是调用方能改对的错，按 exit 6 归类。
  const internal = cli.submitOutcome({ v: 2, id: 'r1', op: 'submit', ok: false, cause: 'E_INTERNAL/enqueue failed (mailbox write failed)' })
  assert.equal(internal.error.code, 'E_INTERNAL')
  assert.equal(internal.error.exitCode, 6, '宿主故障按内部失败报，cause 原文留在 reason')
  assert.match(internal.reason, /enqueue failed/)
  // 连 cause 都没有的（终态没落住）才是宿主侧故障：照旧报内部失败。
  const noCause = cli.submitOutcome({ v: 2, id: 'r1', op: 'submit', ok: false, state: 'UNKNOWN' })
  assert.equal(noCause.error.code, 'E_INTERNAL')
  assert.equal(noCause.error.exitCode, 6)
})

test('fallbackAllowed：只有确定没投进控制信箱的失败才允许退回 v1 重走', () => {
  // 写件失败与通道从未在位都证明请求没有落进信箱：换一条通道重走是安全的。
  assert.equal(cli.fallbackAllowed(false, 'submit-write-failed'), true)
  assert.equal(cli.fallbackAllowed(false, 'channel-never-present'), true)
  // submit 文件成功改名进 control-inbox 之后，宿主随时可能已把它取走执行：
  // 此后任何通道异常（目录消失、回包消失、读失败）都不许回退——
  // 回退重投对非幂等动作就是一次无人授权的重复执行。
  for (const cause of ['channel-disappeared', 'reply-file-lost', 'reply-read-failed', 'submit-write-failed']) {
    assert.equal(cli.fallbackAllowed(true, cause), false, `submitted=true 时 ${cause} 不得回退`)
  }
  // 未收录的原因保守处理：宁可报 UNKNOWN，也不自动换通道重发。
  assert.equal(cli.fallbackAllowed(false, 'something-unexpected'), false)
})

test('CLI 严格参数：未知/重复/缺值 option、多余位置参数、option 用错命令，都点名拒绝并退出 1', () => {
  assert.equal(runCli(['call', 'cap', '--jsoen', 'x']).code, 1)
  assert.match(runCli(['call', 'cap', '--jsoen', 'x']).stderr, /unknown option --jsoen/)
  assert.match(runCli(['call', 'cap', '--timeout', '1', '--timeout', '2']).stderr, /duplicate option --timeout/)
  assert.match(runCli(['call', 'cap', '--json']).stderr, /option --json requires a value/)
  assert.match(runCli(['call', 'cap', 'junk']).stderr, /unexpected argument 'junk'/)
  assert.match(runCli(['doctor', '--json', '{}']).stderr, /--json is not valid for doctor/)
  assert.equal(runCli(['frobnicate']).code, 1)
  assert.match(runCli(['frobnicate']).stderr, /usage:/)
  assert.equal(runCli(['-h']).code, 0)
})

test('CLI status/cancel 的参数边界在触碰文件系统之前拒绝', () => {
  assert.match(runCli(['status']).stderr, /status requires a request id/)
  assert.match(runCli(['cancel']).stderr, /cancel requires a request id/)
  assert.match(runCli(['status', 'a', 'b']).stderr, /unexpected argument 'b'/)
  assert.match(runCli(['cancel', 'a', 'b']).stderr, /unexpected argument 'b'/)
  const badId = runCli(['status', 'bad id!'])
  assert.equal(badId.code, 1)
  assert.match(badId.stderr, /must match/)
})

test('CLI status/cancel：控制通道不在位时如实报不可用（退出码 6），不假装查过', noControl, () => {
  for (const command of ['status', 'cancel']) {
    const r = runCli([command, 'req-1'])
    assert.equal(r.code, 6)
    assert.match(r.stderr, /control channel unavailable/)
    assert.equal(r.stdout, '')
  }
})

test('CLI home：只认 --timeout，选项用错命令与多余位置参数都在触碰文件系统之前拒绝', () => {
  const badOption = runCli(['home', '--json', '{}'])
  assert.equal(badOption.code, 1)
  assert.match(badOption.stderr, /--json is not valid for home/)
  assert.equal(runCli(['home', 'junk']).code, 1)
  assert.match(runCli(['home', 'junk']).stderr, /unexpected argument 'junk'/)
})

test('CLI home：解析不出宿主包名时不猜、不硬编码，给出去改走 call 的替代命令', (t) => {
  const manifest = path.join('/opt/pilot', 'capabilities.json')
  if (fs.existsSync(manifest)) {
    t.skip('本机存在宿主清单（无清单的前提不成立）；有清单的路径由 mock 段端到端用例覆盖')
    return
  }
  const r = runCli(['home'])
  assert.equal(r.code, 1, `stderr: ${r.stderr}`)
  assert.match(r.stderr, /cannot resolve the host package/)
  assert.match(r.stderr, /pilot call app\.launch/, '回不去就说清怎么手动回去')
  assert.equal(r.stdout, '', '没有可走的路径时不给任何伪造输出')
})

test('CLI --out：相对路径当场拒绝，并把两条正确写法写进错误里', () => {
  const r = runCli(['call', 'screen.capture', '--out', 'shot.png'])
  assert.equal(r.code, 1, `stderr: ${r.stderr}`)
  assert.match(r.stderr, /--out must be an absolute path/)
  assert.match(r.stderr, /omit --out/, '要直接告诉调用方"省略即可"')
  // 标准落点由入口自身位置推导（<入口>/out）：设备上是 /opt/pilot/out，
  // 在宿主上跑物化副本时分隔符随平台（如 D:\opt\pilot\out），只断言落点本身。
  assert.match(r.stderr, /[\\/]opt[\\/]pilot[\\/]out/, '并给出标准落点')
  // 绝对路径不在参数阶段被拒（能否成功由通道决定）：
  assert.doesNotMatch(runCli(['call', 'screen.capture', '--out', '/tmp/abs.png', '--timeout', '1000']).stderr,
    /must be an absolute path/)
})

test('CLI ask：--json 必需、形状严格，问题与选项的边界都在触碰通道之前拒绝', () => {
  assert.equal(runCli(['ask']).code, 1)
  assert.match(runCli(['ask']).stderr, /ask requires --json/)
  assert.match(runCli(['ask', '--json', '{oops']).stderr, /not valid JSON/)
  assert.match(runCli(['ask', '--json', '{"question":"   ","options":["a","b"]}']).stderr, /non-empty question/)
  for (const bad of [
    '{"question":"Q"}',
    '{"question":"Q","options":["only-one"]}',
    '{"question":"Q","options":["a","b","c","d"]}',
    '{"question":"Q","options":["a","   "]}',
    '{"question":"Q","options":["a",42]}',
  ]) {
    const r = runCli(['ask', '--json', bad])
    assert.equal(r.code, 1, `${bad} 应被拒绝`)
    assert.match(r.stderr, /2\.\.3 non-empty option strings/)
  }
  // ask 不接受 --timeout：等待窗口只由载荷里的 timeoutMs 决定，两个来源会互相打架。
  assert.match(
    runCli(['ask', '--json', '{"question":"Q","options":["a","b"]}', '--timeout', '5000']).stderr,
    /--timeout is not valid for ask/,
  )
  // 长度上限与宿主同一组数（200 / 60），本进程先拒：交给宿主拒会走成 exit 6 的"内部故障"。
  const longQuestion = runCli(['ask', '--json', JSON.stringify({ question: 'q'.repeat(201), options: ['a', 'b'] })])
  assert.equal(longQuestion.code, 1)
  assert.match(longQuestion.stderr, /longer than 200 characters/)
  const longOption = runCli(['ask', '--json', JSON.stringify({ question: 'Q', options: ['a', 'o'.repeat(61)] })])
  assert.equal(longOption.code, 1)
  assert.match(longOption.stderr, /at most 60 characters/)
  // 刚好压线的两个都放过（校验是 > 而不是 >=）：能过 CLI 就一定不会撞宿主的形状闸。
  assert.doesNotMatch(
    runCli(['ask', '--json', JSON.stringify({ question: 'q'.repeat(200), options: ['a', 'o'.repeat(60)] })]).stderr,
    /longer than|at most/,
  )
})

test('CLI ask：v2 控制通道不在位时如实回不可用（退出码 6），不留请求文件、不进 v1 信箱', noControl, () => {
  const inboxBefore = listV1Inbox()
  const r = runCli(['ask', '--json', '{"question":"Q","options":["a","b"],"timeoutMs":5000}'])
  assert.equal(r.code, 6, `stderr: ${r.stderr}`)
  assert.match(r.stderr, /ask needs the v2 control channel/)
  assert.equal(r.stdout, '', '问都没问出口，不给任何答复形状')
  assert.deepEqual(listV1Inbox(), inboxBefore, 'v1 信箱没有新增请求：ask 没有 v1 等价物')
})

test('normalize 透传 v2 终态帧字段；v1 回包没有 state 就按 null 给', () => {
  const v1 = normalize({
    capability: 'ui.tap', exitCode: 0,
    stdout: `${JSON.stringify({ v: 1, id: 'req-1', ok: true, elapsedMs: 9, data: {}, artifacts: ['/tmp/a.png'] })}\n`,
  })
  assert.equal(v1.ok, true)
  assert.equal(v1.state, null)
  assert.equal(v1.requestId, 'req-1')
  assert.equal(v1.artifactDelivery, null)

  const v2 = normalize({
    capability: 'ui.tap', exitCode: 0,
    stdout: JSON.stringify({ id: 'req-2', ok: true, elapsedMs: 9, data: {}, v: 2, state: 'SUCCEEDED' }),
  })
  assert.equal(v2.ok, true)
  assert.equal(v2.state, 'SUCCEEDED')
  assert.equal(v2.requestId, 'req-2')

  const undelivered = normalize({
    capability: 'screen.capture', exitCode: 0,
    stdout: JSON.stringify({ id: 'req-3', ok: true, artifacts: [''], artifactDelivery: 'failed', artifactDeliveryError: 'EACCES' }),
  })
  assert.equal(undelivered.ok, true, '能力成功的事实不因本进程复制失败改写')
  assert.equal(undelivered.artifactDelivery, 'failed')
  assert.deepEqual(undelivered.artifacts, [''])
})

test('v2 超时回包（stderr）带出 requestId 与 UNKNOWN，建议指向 status/cancel 跟进', () => {
  const r = normalize({
    capability: 'app.install', exitCode: 3,
    stderr: `${JSON.stringify({ error: 'E_TRANSPORT_TIMEOUT', capability: 'app.install', id: 'req-9', v: 2, state: 'UNKNOWN' })}\n`,
  })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'E_TRANSPORT_TIMEOUT')
  assert.equal(r.state, 'UNKNOWN')
  assert.equal(r.requestId, 'req-9')
  assert.equal(r.exitCode, 3)
  const hint = hintFor(r)
  assert.match(hint, /status/)
  assert.match(hint, /cancel/)
  assert.match(hint, /requestId/)
})

test('state=UNKNOWN 的建议先查/取消再谈重试；E_TRANSPORT_TIMEOUT 不再教"以相同参数重试"', () => {
  const unknown = hintFor({ code: 'E_TRANSPORT_TIMEOUT', state: 'UNKNOWN', requestId: 'req-9' })
  assert.match(unknown, /无法证实该请求是否已执行/)
  assert.match(unknown, /禁止按原参数自动重试/)
  const v1Timeout = hintFor({ code: 'E_TRANSPORT_TIMEOUT', timeoutMs: 30000 })
  assert.match(v1Timeout, /status/)
  assert.match(v1Timeout, /cancel/)
  assert.match(v1Timeout, /宿主可能在你的等待超时后/)
  assert.doesNotMatch(v1Timeout, /完全相同的参数重试/, '那句旧建议对写动作就是自动重放')
  const wrapper = hintFor({ code: 'E_WRAPPER_TIMEOUT', requestId: 'req-8' })
  assert.match(wrapper, /status/, '投件后的执行超时同样指向跟进通路')
})

test('E_TRANSPORT_UNKNOWN（投件后失联）的建议指向 status/cancel，且不许按原参数重新发起', () => {
  const hint = hintFor({ code: 'E_TRANSPORT_UNKNOWN', requestId: 'req-7' })
  assert.match(hint, /status/)
  assert.match(hint, /cancel/)
  assert.match(hint, /requestId/)
  assert.match(hint, /禁止按原参数重新发起/)
  assert.doesNotMatch(hint, /以相同参数重试|按原参数自动重试/)
})

test('state=CANCELLED（宿主证实执行前已撤销）说"未执行"，不再沿用"可能已执行"的旧口径', () => {
  const hint = hintFor({ code: 'E_TRANSPORT_TIMEOUT', state: 'CANCELLED', requestId: 'req-5' })
  assert.match(hint, /执行前被撤销/)
  assert.match(hint, /未执行/)
  assert.match(hint, /不需要再用 status\/cancel 跟进/)
  // 旧文案会让人以为动作可能已经完成，与"已证实未执行"的结论自相矛盾。
  assert.doesNotMatch(hint, /可能.*完成执行/)
})

test('cancel 只记下意愿（CANCEL_REQUESTED）时，建议以 status 跟进而非当作已撤销', () => {
  const hint = hintFor({ outcome: 'CANCEL_REQUESTED' })
  assert.match(hint, /未证实已撤销/)
  assert.match(hint, /status/)
  assert.match(hintFor({ code: 'CANCEL_REQUESTED' }), /status/, '按 code 挂靠的回包同样命中')
})

test('describe 在 v2 结果上给出 requestId/阶段行，v1 回包不加这一行', () => {
  const v2 = describe({ capability: 'ui.tap', ok: true, exitCode: 0, requestId: 'req-2', state: 'SUCCEEDED' })
  assert.match(v2, /request: id=req-2 state=SUCCEEDED/)
  const v1 = describe({ capability: 'ui.tap', ok: true, exitCode: 0, requestId: 'req-1' })
  assert.doesNotMatch(v1, /request:/)
})

// ---------------------------------------------------------------------------
// v2 通道行为的端到端覆盖：mock 宿主 + 真实入口进程。
// 入口的通道目录是固定路径，只有在它缺席的机器上才能临时搭出这对目录来跑 mock，
// 真实通道在位的设备一律跳过（mock 不能占用真实通道）。测完即拆，不占地方。
// ---------------------------------------------------------------------------
const CONTROL_ROOT = '/opt/pilot'
const CONTROL_BASE = '/opt'
const CONTROL_RUN_DIR = path.join(CONTROL_ROOT, 'run')
const CONTROL_IN = path.join(CONTROL_RUN_DIR, 'control-inbox')
const CONTROL_OUT = path.join(CONTROL_RUN_DIR, 'control-outbox')
const V1_INBOX_DIR = path.join(CONTROL_RUN_DIR, 'inbox')
const mockGuard = CONTROL_PRESENT ? { skip: '本机存在 v2 控制目录：mock 宿主不能占用真实通道' } : {}

/**
 * mock 宿主：轮询 control-inbox，把出现的请求消费掉并记进 trace 文件。
 * mode=confirm 对 cancel 回 CANCELLED；mode=silent 消费后不应答；
 * mode=vanish 在收到 submit 后把两个控制目录整体拆掉，模拟通道中途消失；
 * mode=launch 对 submit 回终态成功（供 home 改写后的 app.launch 走通）；
 * mode=answer/reject/busy/blind 对 ask 分别回：选中某项 / 全部驳回 / 忙 / 无呈现面。
 */
const mockHostScript = `
const fs = require('node:fs')
const path = require('node:path')
// node -e 的 process.argv 从下标 1 开始就是跟随参数（没有脚本路径那一项）。
const [inDir, outDir, mode, traceFile] = process.argv.slice(1)
const trace = (line) => { try { fs.appendFileSync(traceFile, line + '\\n') } catch {} }
const reply = (name, frame) => {
  const part = path.join(outDir, '.' + name + '.part')
  fs.writeFileSync(part, JSON.stringify(frame) + '\\n')
  fs.renameSync(part, path.join(outDir, name))
}
const tick = () => {
  let names = []
  try { names = fs.readdirSync(inDir).filter((n) => n.endsWith('.json')) } catch { return }
  for (const name of names) {
    const file = path.join(inDir, name)
    let frame = null
    try { frame = JSON.parse(fs.readFileSync(file, 'utf8')) } catch { continue }
    try { fs.rmSync(file, { force: true }) } catch {}
    // submit 的行带上整帧：调用方要能核对投进去的到底是什么（能力、参数、宿主有效期）。
    trace((frame.op || '?') + ':' + (frame.id || '?') + (frame.op === 'submit' ? ' ' + JSON.stringify(frame) : ''))
    if (frame.op === 'cancel' && mode === 'confirm') {
      reply(name, { v: 2, op: 'cancel', id: frame.id, targetId: frame.targetId, ok: true, outcome: 'CANCELLED' })
    }
    // 交付链路专用：回包带一份真实存在的产物（路径由 MP_ARTIFACT_SRC 指定），
    // 入口据此把它复制到自己的交付落点——用于验证"不写 --out 时落哪里"。
    if (frame.op === 'submit' && mode === 'artifact') {
      reply(name, {
        v: 2, op: 'submit', id: frame.id, targetId: frame.id, ok: true, state: 'SUCCEEDED',
        result: {
          v: 1, id: frame.id, ok: true, elapsedMs: 1,
          data: { bytes: 7 }, artifacts: [process.env.MP_ARTIFACT_SRC || '/nonexistent'],
        },
      })
    }
    if (frame.op === 'submit' && mode === 'launch') {
      reply(name, {
        v: 2, op: 'submit', id: frame.id, targetId: frame.id, ok: true, state: 'SUCCEEDED',
        result: { v: 1, id: frame.id, ok: true, elapsedMs: 1, data: { launched: true } },
      })
    }
    // home 的真实失败面：档位要求的确认框没人答（回 E_AWAITING_CONSENT / exit 5）。
    // mock 不能只回成功——那会让"home 总能成功"变成一个测试制造的假象。
    if (frame.op === 'submit' && mode === 'gate' && frame.capability === 'app.launch') {
      reply(name, {
        v: 2, op: 'submit', id: frame.id, targetId: frame.id, ok: true, state: 'FAILED',
        result: {
          v: 1, id: frame.id, ok: false, elapsedMs: 5,
          reason: 'E_AWAITING_CONSENT/pending',
          error: { code: 'E_AWAITING_CONSENT', retryable: true, exitCode: 5 },
        },
      })
    }
    if (frame.op === 'ask') {
      if (mode === 'answer') reply(name, { v: 2, op: 'ask', id: frame.id, ok: true, choice: { kind: 'option', index: 1, label: 'B' } })
      if (mode === 'reject') reply(name, { v: 2, op: 'ask', id: frame.id, ok: true, choice: { kind: 'reject_all' } })
      if (mode === 'reask') reply(name, { v: 2, op: 'ask', id: frame.id, ok: true, choice: { kind: 'reask' } })
      // 失败帧按宿主真实形状给：cause + error{code,retryable,exitCode}（单源在宿主 AskCodes）。
      if (mode === 'busy') {
        reply(name, {
          v: 2, op: 'ask', id: frame.id, ok: false,
          cause: 'E_ASK_BUSY/an approval card is on screen',
          error: { code: 'E_ASK_BUSY', retryable: true, exitCode: 5 },
        })
      }
      if (mode === 'blind') {
        reply(name, {
          v: 2, op: 'ask', id: frame.id, ok: false,
          cause: 'E_ASK_NO_SURFACE/overlay permission is not granted',
          error: { code: 'E_ASK_NO_SURFACE', retryable: false, exitCode: 4 },
        })
      }
      // 前缀与宿主声明的退出码**故意不一致**：入口必须按宿主给的数走，不许按前缀猜。
      // （若用一致的一组，把入口那段 error.exitCode 删掉这条用例照样绿——测不到东西。）
      if (mode === 'declared') {
        reply(name, {
          v: 2, op: 'ask', id: frame.id, ok: false,
          cause: 'E_ASK_TIMEOUT/renamed by a newer host',
          error: { code: 'E_ASK_TIMEOUT', retryable: false, exitCode: 4 },
        })
      }
      // mode=silent：消费掉但不应答，模拟宿主收下了问题却给不出回包。
    }
    if (frame.op === 'submit' && mode === 'vanish') {
      try { fs.rmSync(inDir, { recursive: true, force: true }) } catch {}
      try { fs.rmSync(outDir, { recursive: true, force: true }) } catch {}
      trace('dirs-removed')
      return
    }
  }
}
setInterval(tick, 10)
setTimeout(() => process.exit(0), 20000)
`

/**
 * 端到端现场的记账。这批用例要起真实入口进程、并在固定路径上搭出 v2 控制目录；
 * 真实通道在位的机器上只能跳过（mock 不能占用真通道）。跳过本身是诚实的，
 * 但**整批跳过仍以绿收场**不是 —— 那等于说这次运行没有任何端到端证据。
 * 末尾的现场自检把实跑与跳过都报出来，并在一条都没真跑时以失败收场。
 */
const e2eRan = []
const e2eSkipped = []

/** 临时搭出 v2 控制目录并注册拆除；搭不出来（固定路径不可写）时跳过本用例。 */
function setupControlDirs (t) {
  let ok = false
  try {
    // 先清掉上一次异常退出可能留下的残目录，再按入口的固定路径搭出这对目录。
    for (const dir of [CONTROL_IN, CONTROL_OUT]) {
      try { fs.rmSync(dir, { recursive: true, force: true }) } catch { /* 稍后再试 */ }
    }
    fs.mkdirSync(CONTROL_IN, { recursive: true })
    fs.mkdirSync(CONTROL_OUT, { recursive: true })
    ok = fs.statSync(CONTROL_IN).isDirectory() && fs.statSync(CONTROL_OUT).isDirectory()
  } catch { ok = false }
  if (!ok) {
    e2eSkipped.push(t.name)
    t.skip('本机无法在固定路径创建 v2 控制目录，端到端现场搭不起来')
    return false
  }
  t.after(() => {
    for (const dir of [CONTROL_IN, CONTROL_OUT]) {
      for (let attempt = 0; attempt < 3; attempt += 1) {
        try {
          fs.rmSync(dir, { recursive: true, force: true })
          break
        } catch { /* Windows 上句柄释放有延迟，稍候重试 */ }
        Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 50)
      }
    }
    // 只顺带拆掉这次腾出来的空目录：rmdir 对非空目录会失败并被忽略。
    for (const dir of [V1_INBOX_DIR, CONTROL_RUN_DIR, CONTROL_ROOT, CONTROL_BASE]) {
      try { fs.rmdirSync(dir) } catch { /* 非空或缺席就不动 */ }
    }
  })
  e2eRan.push(t.name)
  return true
}

function startMockHost (t, mode, traceFile, extraEnv = {}) {
  const child = spawn(process.execPath, ['-e', mockHostScript, CONTROL_IN, CONTROL_OUT, mode, traceFile], {
    stdio: 'ignore',
    env: { ...process.env, ...extraEnv },
  })
  t.after(() => { try { child.kill() } catch { /* 已退出 */ } })
  return child
}

const readTrace = (traceFile) => {
  try { return fs.readFileSync(traceFile, 'utf8') } catch { return '' }
}
const traceCount = (trace, prefix) => trace.split('\n').filter((l) => l.startsWith(prefix)).length
const listV1Inbox = () => {
  try { return fs.readdirSync(V1_INBOX_DIR).filter((n) => n.endsWith('.json')).sort() } catch { return [] }
}
const lastLine = (text) => text.trim().split('\n').pop()
const newTraceFile = (t) => {
  const traceFile = path.join(os.tmpdir(), `mp-trace-${process.pid}-${Date.now()}-${Math.random().toString(36).slice(2)}.txt`)
  t.after(() => { try { fs.rmSync(traceFile, { force: true }) } catch { /* 已清理 */ } })
  return traceFile
}

test('v2 投件后通道消失：按 UNKNOWN 收场（退出码 6），不回退 v1 重投', mockGuard, (t) => {
  if (!setupControlDirs(t)) return
  const traceFile = newTraceFile(t)
  startMockHost(t, 'vanish', traceFile)
  const inboxBefore = listV1Inbox()
  const r = runCli(['call', 'pkg.query', '--json', '{"i":1}', '--timeout', '5000'])
  assert.equal(r.code, 6, `stderr: ${r.stderr}`)
  assert.equal(r.stdout, '', '结局未证实，stdout 不给伪造结果')
  const detail = JSON.parse(lastLine(r.stderr))
  assert.equal(detail.error, 'E_TRANSPORT_UNKNOWN')
  assert.equal(detail.capability, 'pkg.query')
  assert.match(detail.id, /^[A-Za-z0-9_-]{1,64}$/)
  assert.match(detail.note, /its outcome could not be confirmed/)
  assert.match(detail.note, /do not re-send/)
  const trace = readTrace(traceFile)
  assert.equal(traceCount(trace, 'submit:'), 1, 'mock 宿主恰好收到一次投件')
  assert.equal(traceCount(trace, 'cancel:'), 0, '通道已亡，不会再有 cancel 往来')
  assert.ok(trace.includes('dirs-removed'), '现场确实是通道整体消失')
  // 回退 v1 会把同一能力重投进 v1 信箱：信箱里不得新增任何请求文件。
  assert.deepEqual(listV1Inbox(), inboxBefore, 'v1 信箱没有新增请求：没有回退重投')
})

test('等待到点的有界取消：宿主证实执行前已撤销 → state=CANCELLED（退出码 3），不回退 v1', mockGuard, (t) => {
  if (!setupControlDirs(t)) return
  const traceFile = newTraceFile(t)
  startMockHost(t, 'confirm', traceFile)
  const inboxBefore = listV1Inbox()
  const r = runCli(['call', 'ui.tap', '--json', '{"x":1,"y":2}', '--timeout', '1000'])
  assert.equal(r.code, 3, `stderr: ${r.stderr}`)
  assert.equal(r.stdout, '', '未执行的动作不给伪造结果')
  const detail = JSON.parse(lastLine(r.stderr))
  assert.equal(detail.error, 'E_TRANSPORT_TIMEOUT', '等待预算到点的事实不变，仍按超时报')
  assert.equal(detail.state, 'CANCELLED')
  assert.match(detail.id, /^[A-Za-z0-9_-]{1,64}$/)
  assert.equal(detail.note, 'host confirmed the request was cancelled before execution')
  const trace = readTrace(traceFile)
  assert.equal(traceCount(trace, 'submit:'), 1)
  assert.equal(traceCount(trace, 'cancel:'), 1, '到点后恰好补发一次 cancel')
  assert.deepEqual(listV1Inbox(), inboxBefore, 'v1 信箱没有新增请求：没有回退重投')
})

test('等待到点的有界取消：cancel 无回包 → 仍按 UNKNOWN 报告（退出码 3）', mockGuard, (t) => {
  if (!setupControlDirs(t)) return
  const traceFile = newTraceFile(t)
  startMockHost(t, 'silent', traceFile)
  const inboxBefore = listV1Inbox()
  const r = runCli(['call', 'ui.tap', '--json', '{"x":1,"y":2}', '--timeout', '1000'])
  assert.equal(r.code, 3, `stderr: ${r.stderr}`)
  assert.equal(r.stdout, '')
  const detail = JSON.parse(lastLine(r.stderr))
  assert.equal(detail.error, 'E_TRANSPORT_TIMEOUT')
  assert.equal(detail.state, 'UNKNOWN', '证实不了撤销时结论保持未知')
  assert.equal(detail.v, 2)
  assert.match(detail.id, /^[A-Za-z0-9_-]{1,64}$/)
  const trace = readTrace(traceFile)
  assert.equal(traceCount(trace, 'submit:'), 1)
  assert.equal(traceCount(trace, 'cancel:'), 1, 'cancel 已经发出，只是没有回包')
  assert.deepEqual(listV1Inbox(), inboxBefore, 'v1 信箱没有新增请求：没有回退重投')
})

test('ask 端到端：用户选了某一项 → 原样交回（退出 0）', mockGuard, (t) => {
  if (!setupControlDirs(t)) return
  const traceFile = newTraceFile(t)
  startMockHost(t, 'answer', traceFile)
  const r = runCli(['ask', '--json', '{"question":"走哪条路?","options":["A","B"],"timeoutMs":5000}'])
  assert.equal(r.code, 0, `stderr: ${r.stderr}`)
  const frame = JSON.parse(lastLine(r.stdout))
  assert.equal(frame.ok, true)
  assert.deepEqual(frame.choice, { kind: 'option', index: 1, label: 'B' }, '索引与文案原样带回，不加解释')
  assert.equal(traceCount(readTrace(traceFile), 'ask:'), 1, '宿主恰好收到一次提问')
})

test('ask 端到端：全部驳回是合法答案 → 退出 0，交回 reject_all 由助手重整选项', mockGuard, (t) => {
  if (!setupControlDirs(t)) return
  const traceFile = newTraceFile(t)
  startMockHost(t, 'reject', traceFile)
  const r = runCli(['ask', '--json', '{"question":"Q","options":["A","B","C"],"timeoutMs":5000}'])
  assert.equal(r.code, 0, '驳回不是失败：只是这一组选项都不合适')
  const frame = JSON.parse(lastLine(r.stdout))
  assert.equal(frame.ok, true)
  assert.deepEqual(frame.choice, { kind: 'reject_all' })
})

test('ask 端到端：「重新提问」是合法答案 → 退出 0，交回 reask 由助手换问法重问', mockGuard, (t) => {
  if (!setupControlDirs(t)) return
  const traceFile = newTraceFile(t)
  startMockHost(t, 'reask', traceFile)
  const r = runCli(['ask', '--json', '{"question":"Q","options":["A","B"],"timeoutMs":5000}'])
  assert.equal(r.code, 0, '否的是问题本身，不是失败')
  const frame = JSON.parse(lastLine(r.stdout))
  assert.equal(frame.ok, true)
  assert.deepEqual(frame.choice, { kind: 'reask' })
})

test('ask 端到端：E_ASK_BUSY → 退出 5（先答屏上那张卡，别叠加第二问）', mockGuard, (t) => {
  if (!setupControlDirs(t)) return
  const traceFile = newTraceFile(t)
  startMockHost(t, 'busy', traceFile)
  const r = runCli(['ask', '--json', '{"question":"Q","options":["A","B"],"timeoutMs":5000}'])
  assert.equal(r.code, 5, `stderr: ${r.stderr}`)
  const frame = JSON.parse(lastLine(r.stdout))
  assert.equal(frame.ok, false)
  assert.match(frame.cause, /^E_ASK_BUSY\//)
})

test('ask 端到端：E_ASK_NO_SURFACE → 退出 4（需要用户开权限或回到助手页）', mockGuard, (t) => {
  if (!setupControlDirs(t)) return
  const traceFile = newTraceFile(t)
  startMockHost(t, 'blind', traceFile)
  const r = runCli(['ask', '--json', '{"question":"Q","options":["A","B"],"timeoutMs":5000}'])
  assert.equal(r.code, 4, `stderr: ${r.stderr}`)
  const frame = JSON.parse(lastLine(r.stdout))
  assert.equal(frame.ok, false)
  assert.match(frame.cause, /^E_ASK_NO_SURFACE\//)
  assert.equal(frame.error.exitCode, 4, '宿主把退出码写在回包里，入口照抄')
})

test('ask 端到端：宿主声明的退出码压过前缀（前缀说 3、宿主说 4 → 走 4）', mockGuard, (t) => {
  if (!setupControlDirs(t)) return
  const traceFile = newTraceFile(t)
  startMockHost(t, 'declared', traceFile)
  const r = runCli(['ask', '--json', '{"question":"Q","options":["A","B"],"timeoutMs":5000}'])
  assert.equal(r.code, 4, `stderr: ${r.stderr}`)
  const frame = JSON.parse(lastLine(r.stdout))
  assert.match(frame.cause, /^E_ASK_TIMEOUT\//, '前缀是 TIMEOUT（按前缀兜底会得 3）')
  assert.equal(frame.error.exitCode, 4, '入口取的是宿主声明的 4')
})

test('ask 端到端：宿主收下却给不出回包 → E_ASK_NO_REPLY（退出 3），不谎报"通道不存在"', mockGuard, (t) => {
  if (!setupControlDirs(t)) return
  const traceFile = newTraceFile(t)
  startMockHost(t, 'silent', traceFile)
  const r = runCli(['ask', '--json', '{"question":"Q","options":["A","B"],"timeoutMs":5000}'])
  assert.equal(r.code, 3, `stderr: ${r.stderr}`)
  assert.equal(r.stdout, '')
  const detail = JSON.parse(lastLine(r.stderr))
  assert.equal(detail.error, 'E_ASK_NO_REPLY')
  assert.match(detail.note, /asking again is safe/)
  assert.doesNotMatch(r.stderr, /no v1 equivalent/, '通道在位，别把「没有回包」说成「没有通道」')
  assert.equal(traceCount(readTrace(traceFile), 'ask:'), 1, '请求确实落进了控制信箱，这正是不能报「通道不存在」的理由')
})

test('CLI 交付：不写 --out 时产物落到 /opt/pilot/out/<类>/，回包的 artifacts 给出真实路径', mockGuard, (t) => {
  if (!setupControlDirs(t)) return
  const traceFile = newTraceFile(t)
  const srcDir = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-src-'))
  const src = path.join(srcDir, 'host-shot.png')
  fs.writeFileSync(src, 'PNGDATA')
  t.after(() => fs.rmSync(srcDir, { recursive: true, force: true }))
  startMockHost(t, 'artifact', traceFile, { MP_ARTIFACT_SRC: src })
  const r = runCli(['call', 'screen.capture', '--timeout', '5000'])
  assert.equal(r.code, 0, `stderr: ${r.stderr}`)
  const delivered = JSON.parse(lastLine(r.stdout)).artifacts[0]
  t.after(() => {
    try { fs.rmSync(delivered, { force: true }) } catch { /* 已清理 */ }
    // 顺带收掉空目录链：只对空目录成功，非空时 rmdir 会失败并被忽略。
    for (const dir of [path.dirname(delivered), path.dirname(path.dirname(delivered))]) {
      try { fs.rmdirSync(dir) } catch { /* 非空或不在 */ }
    }
  })
  assert.match(
    delivered.replace(/\\/g, '/'),
    /opt\/pilot\/out\/shot\/pilot-shot-/,
    '缺省落点必须在 out 树的 shot 子目录里，且名字带 pilot-shot- 前缀',
  )
  assert.equal(fs.readFileSync(delivered, 'utf8'), 'PNGDATA', '内容与宿主产物一致')
})

test('CLI 交付：给了绝对 --out 就按它落（不改写）', mockGuard, (t) => {
  if (!setupControlDirs(t)) return
  const traceFile = newTraceFile(t)
  const srcDir = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-src-'))
  const src = path.join(srcDir, 'host-shot.png')
  fs.writeFileSync(src, 'PNGDATA')
  const target = path.join(srcDir, 'named.png')
  t.after(() => fs.rmSync(srcDir, { recursive: true, force: true }))
  startMockHost(t, 'artifact', traceFile, { MP_ARTIFACT_SRC: src })
  const r = runCli(['call', 'screen.capture', '--out', target, '--timeout', '5000'])
  assert.equal(r.code, 0, `stderr: ${r.stderr}`)
  assert.equal(JSON.parse(lastLine(r.stdout)).artifacts[0], target)
  assert.equal(fs.readFileSync(target, 'utf8'), 'PNGDATA')
})

test('home 端到端（改写形状）：按清单里的 host.package 投出 app.launch，参数只带那个包名', mockGuard, (t) => {
  if (!setupControlDirs(t)) return
  const HOST_PACKAGE = 'com.dshbox.testhost'
  const manifest = path.join(CONTROL_ROOT, 'capabilities.json')
  fs.mkdirSync(CONTROL_ROOT, { recursive: true })
  fs.writeFileSync(manifest, JSON.stringify({ protocol: 2, host: { package: HOST_PACKAGE }, capabilities: [] }))
  t.after(() => { try { fs.rmSync(manifest, { force: true }) } catch { /* 已清理 */ } })
  const traceFile = newTraceFile(t)
  startMockHost(t, 'launch', traceFile)
  const r = runCli(['home', '--timeout', '5000'])
  assert.equal(r.code, 0, `stderr: ${r.stderr}`)
  const submit = readTrace(traceFile).split('\n').find((line) => line.startsWith('submit:'))
  assert.ok(submit, '宿主应收到一次投件')
  const request = JSON.parse(submit.slice(submit.indexOf('{')))
  assert.equal(request.capability, 'app.launch', 'home 不走专用能力，走的就是 app.launch 的宿主自指豁免')
  assert.deepEqual(request.args, { package: HOST_PACKAGE }, '参数只有清单里的宿主包名，不多带一个字段')
})

test('home 端到端（失败面）：确认框没人答 → 按宿主的 E_AWAITING_CONSENT 退 5，不谎报成功', mockGuard, (t) => {
  if (!setupControlDirs(t)) return
  const HOST_PACKAGE = 'com.dshbox.testhost'
  const manifest = path.join(CONTROL_ROOT, 'capabilities.json')
  fs.mkdirSync(CONTROL_ROOT, { recursive: true })
  fs.writeFileSync(manifest, JSON.stringify({ protocol: 2, host: { package: HOST_PACKAGE }, capabilities: [] }))
  t.after(() => { try { fs.rmSync(manifest, { force: true }) } catch { /* 已清理 */ } })
  const traceFile = newTraceFile(t)
  startMockHost(t, 'gate', traceFile)
  const r = runCli(['home', '--timeout', '5000'])
  assert.equal(r.code, 5, `stderr: ${r.stderr}`)
  const frame = JSON.parse(lastLine(r.stdout))
  assert.equal(frame.ok, false)
  assert.equal(frame.error.code, 'E_AWAITING_CONSENT')
  assert.match(frame.reason, /E_AWAITING_CONSENT/)
})

// ── phone_ask：提问通路接进工具层 ─────────────────────────────────────
// ask 不走 `call <能力>`，而是 `bin/pilot ask --json`。窗口写在载荷里，本层的等待预算在窗口
// 之上再加一截余量（宿主认领 + 上屏判定 + 回包落盘），且**不下发 --timeout** ——
// 入口对这个子命令明确拒绝它，两个来源会互相打架。

test('通道 ask：argv 是 ask --json 且不带 --timeout，预算是窗口加余量', async (t) => {
  const dir = fakePack(t, OK_ENTRY)
  const { spawnImpl, calls, settle } = manualSpawn()
  const channel = stubbedChannel(t, dir, { spawnImpl })

  const pending = channel.ask({ question: ' 去哪里 ', options: [' 甲 ', '乙'] })
  await tick()
  assert.equal(calls.length, 1)
  const argv = calls[0].argv
  assert.equal(argv[0], path.join(dir, 'bin', 'pilot'))
  assert.deepEqual(argv.slice(1, 3), ['ask', '--json'])
  assert.deepEqual(JSON.parse(argv[3]), { question: '去哪里', options: ['甲', '乙'], timeoutMs: 60000 })
  assert.ok(!argv.includes('--timeout'), '入口对 ask 拒绝 --timeout：窗口只由载荷里的 timeoutMs 决定')

  settle(0, { code: 0, stdout: JSON.stringify({ v: 2, op: 'ask', id: 'ask-1', ok: true, choice: { kind: 'option', index: 0, label: '甲' } }) })
  const r = await pending
  assert.equal(r.ok, true)
  assert.equal(r.choice.kind, 'option')
  assert.equal(r.choice.label, '甲')
  assert.equal(r.requestId, 'ask-1')
  assert.equal(r.timeoutMs, 60000 + ASK_BUDGET_MARGIN_MS, '本层预算是窗口加余量')
})

test('通道 ask：载荷不合规在投件之前拒绝（退出码 1），一个请求都不发', async (t) => {
  const dir = fakePack(t, OK_ENTRY)
  const { spawnImpl, calls } = manualSpawn()
  const channel = stubbedChannel(t, dir, { spawnImpl })
  const cases = [
    [{ question: '   ', options: ['a', 'b'] }, /question/],
    [{ question: 'q'.repeat(201), options: ['a', 'b'] }, /200/],
    [{ question: 'q', options: ['a'] }, /2\.\.3/],
    [{ question: 'q', options: ['a', 'b', 'c', 'd'] }, /2\.\.3/],
    [{ question: 'q', options: ['a', '   '] }, /非空/],
    [{ question: 'q', options: ['a', 'o'.repeat(61)] }, /60/],
  ]
  for (const [payload, pattern] of cases) {
    const r = await channel.ask(payload)
    assert.equal(r.ok, false, JSON.stringify(payload))
    assert.equal(r.code, 'E_WRAPPER_USAGE')
    assert.equal(r.exitCode, 1, '用法错误按 1 报：那不是宿主故障')
    assert.match(r.reason, pattern)
  }
  assert.equal(calls.length, 0, '用法错误不该触碰通道')
})

test('通道 ask：窗口夹在 5000..120000，本层预算跟着窗口走', async (t) => {
  const dir = fakePack(t, OK_ENTRY)
  const { spawnImpl, calls, settle } = manualSpawn()
  const channel = stubbedChannel(t, dir, { spawnImpl })
  for (const [given, want] of [[1000, 5000], [999999, 120000], [30000, 30000], [undefined, 60000]]) {
    const pending = channel.ask({ question: 'q', options: ['a', 'b'], timeoutMs: given })
    await tick()
    const payload = JSON.parse(calls[calls.length - 1].argv[3])
    assert.equal(payload.timeoutMs, want, `timeoutMs=${given} 应夹到 ${want}`)
    assert.equal(payload.question, 'q')
    settle(calls.length - 1, { code: 0, stdout: JSON.stringify({ ok: true, choice: { kind: 'reask' } }) })
    const r = await pending
    assert.equal(r.timeoutMs, want + ASK_BUDGET_MARGIN_MS)
  }
})

test('通道 ask 与 call 共用同一条串行队列：提问在途时后续调用排队，不并行投件', async (t) => {
  const dir = fakePack(t, OK_ENTRY)
  const { spawnImpl, calls, settle } = manualSpawn()
  const channel = stubbedChannel(t, dir, { spawnImpl })

  const asking = channel.ask({ question: 'q', options: ['a', 'b'] })
  const calling = channel.call('pkg.query', {})
  await tick()
  assert.equal(calls.length, 1, '同一时刻只允许一条在途')
  assert.deepEqual(calls[0].argv.slice(1, 3), ['ask', '--json'])

  settle(0, { code: 0, stdout: JSON.stringify({ ok: true, choice: { kind: 'reask' } }) })
  await asking
  await tick()
  assert.equal(calls.length, 2)
  assert.deepEqual(calls[1].argv.slice(1, 3), ['call', 'pkg.query'])
  settle(1, { code: 0, stdout: JSON.stringify({ ok: true, data: {} }) })
  await calling
})

test('通道 ask：排队吃掉的预算不足以覆盖一次最小提问时，当场退回不投件', async (t) => {
  const dir = fakePack(t, OK_ENTRY)
  const { spawnImpl, calls, settle } = manualSpawn()
  const clock = manualClock(0)
  const channel = stubbedChannel(t, dir, { spawnImpl, clock })

  const first = channel.ask({ question: 'q', options: ['a', 'b'] })
  await tick()
  const second = channel.ask({ question: 'q2', options: ['a', 'b'] })
  clock.advance(60000)
  settle(0, { code: 0, stdout: JSON.stringify({ ok: true, choice: { kind: 'reask' } }) })
  await first

  const r = await second
  assert.equal(r.code, 'E_WRAPPER_TIMEOUT')
  assert.match(r.reason, /未把请求投给入口/)
  assert.equal(calls.length, 1, '预算不足时第二件没有投件')
})

test('工具 phone_ask：三支答复各自给下一步，且走的是提问通路而不是 call', async () => {
  const cases = [
    [{ ok: true, choice: { kind: 'option', index: 1, label: '乙' } }, { choice: 'option', choiceIndex: 1, choiceLabel: '乙' }, /用户选择：乙（第 2 项/],
    [{ ok: true, choice: { kind: 'reject_all' } }, { choice: 'reject_all', choiceIndex: -1, choiceLabel: '' }, /重新组织选项/],
    [{ ok: true, choice: { kind: 'reask' } }, { choice: 'reask', choiceIndex: -1, choiceLabel: '' }, /改问法/],
  ]
  for (const [reply, want, pattern] of cases) {
    const { byName, channel } = toolsWith(reply)
    const out = await byName.phone_ask.execute({ question: '二选一', options: ['甲', '乙'] })
    assert.equal(out.ok, true)
    for (const [key, value] of Object.entries(want)) assert.equal(out[key], value, `${key} 应为 ${JSON.stringify(value)}`)
    assert.match(out.text, pattern)
    assert.equal(channel.calls.length, 1)
    assert.equal(channel.calls[0].op, 'ask', 'phone_ask 必须走 ask 子命令')
    assert.equal(channel.calls[0].args.question, '二选一')
  }
})

test('工具 phone_ask：失败照常透出宿主码与退出码，答复字段给空值而不是缺键', async () => {
  const { byName } = toolsWith({ ok: false, code: 'E_ASK_BUSY', retryable: true, exitCode: 5, reason: 'another question is already waiting on screen' })
  const out = await byName.phone_ask.execute({ question: 'q', options: ['a', 'b'] })
  assert.equal(out.ok, false)
  assert.equal(out.code, 'E_ASK_BUSY')
  assert.equal(out.exitCode, 5)
  assert.equal(out.retryable, true)
  assert.equal(out.choice, '')
  assert.equal(out.choiceIndex, -1)
  assert.equal(out.choiceLabel, '')
  assert.match(out.text, /已经有一张卡/, '处置建议要点明"先答掉屏上那张"')
})

test('ask 的错误码都有处置建议，且不教人无脑重发', () => {
  for (const code of ['E_ASK_TIMEOUT', 'E_ASK_BUSY', 'E_ASK_NO_SURFACE', 'E_ASK_NO_REPLY', 'E_WRAPPER_USAGE']) {
    const hint = hintFor({ code })
    assert.ok(hint.length > 20, `${code} 缺处置建议`)
  }
  assert.match(hintFor({ code: 'E_ASK_NO_SURFACE' }), /悬浮窗|权限/)
  assert.match(hintFor({ code: 'E_ASK_TIMEOUT' }), /重问|重新问/)
})

// ── 现场自检：端到端证据不许缺而不报 ─────────────────────────────────
test('端到端现场自检：整批跳过就是没有证据，按失败收场而不是绿', () => {
  const declared = e2eRan.length + e2eSkipped.length
  assert.ok(declared > 0, '一条端到端用例都没被调度到：这批用例整体缺席')
  process.stdout.write(`\n端到端现场：实跑 ${e2eRan.length} 条，本次跳过 ${e2eSkipped.length} 条\n`)
  if (CONTROL_PRESENT) {
    // 真机上这批只能跳过：mock 宿主不能占用真实通道。这句话必须重到让人停下，
    // 否则一次「全绿」的运行会被读成「端到端验过了」。
    assert.fail(
      '本机存在真实 v2 控制目录，mock 端到端用例整批跳过：本次运行没有端到端证据。' +
      '要在设备上取证，请用真实通道直接跑一次调用（例如 mobile-pilot call pkg.query --json \'{}\'），' +
      '不要把这次结果当成端到端已验证。',
    )
  }
  assert.ok(e2eRan.length > 0, `实跑 ${e2eRan.length} 条、跳过 ${e2eSkipped.length} 条：一条端到端用例都没能真跑（跳过原因见各条的 skip 说明）`)
})
