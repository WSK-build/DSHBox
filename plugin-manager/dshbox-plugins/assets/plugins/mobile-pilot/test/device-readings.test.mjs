/**
 * 设备回包读数对应的回归：数值参数归一、选择框现场的建议、执行面降级单独成键。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { hintFor, describe, numericArgs, normalize } from '../plugin/lib/pilot.js'
import index from '../plugin/lib/index.js'

test('numericArgs 把数值参数的数字字符串转回数字', () => {
  assert.equal(numericArgs({ x: '306', y: '275.5' }).x, 306)
  assert.equal(numericArgs({ x: '306', y: '275.5' }).y, 275.5)
  assert.equal(numericArgs({ fromX: ' 306 ', fromY: '900' }).fromX, 306)
  assert.equal(numericArgs({ nodeId: '11' }).nodeId, 11)
  // 落点不是参数：由用户的执行模式偏好决定，转成数字也照样被拒
  assert.equal(numericArgs({ display: '40' }).display, '40')
  assert.equal(numericArgs({ percent: '62.5' }).percent, 62.5)
  assert.equal(numericArgs({ value: '3' }, 'ui.setProgress').value, 3)
  assert.equal(numericArgs({ value: '3' }, 'sys.settings.write').value, '3')
  assert.equal(numericArgs({ selector: { index: '2' } }).selector.index, 2)
})

test('numericArgs 不动非数值参数，也不动名单外的键', () => {
  const kept = numericArgs({ text: '123', keyword: '2024', package: 'com.ss.android.ugc.aweme', key: '66' })
  assert.equal(kept.text, '123')
  assert.equal(kept.keyword, '2024')
  assert.equal(kept.package, 'com.ss.android.ugc.aweme')
  // ui.key 的取值是字符串枚举，'66' 不能变成一个键码
  assert.equal(kept.key, '66')
  assert.equal(numericArgs({ until: 'not-a-number' }).until, 'not-a-number')
  assert.deepEqual(numericArgs(null), null)
})

test('data.observed 点名中介包时给出选择框处置路径，且按执行面区分能否让用户自己点', () => {
  const onVirtual = hintFor({
    capability: 'app.launch',
    code: null,
    surface: 'trusted-display',
    data: { observed: 'com.vivo.doubleinstance', verified: false },
  })
  assert.match(onVirtual, /screen\.capture 看一眼/)
  assert.match(onVirtual, /ui\.tap/)
  assert.match(onVirtual, /先 ui\.snapshot 读一次树/)
  assert.match(onVirtual, /不接受 display 参数/)
  assert.doesNotMatch(onVirtual, /原参数重发即可/)
  assert.doesNotMatch(onVirtual, /release 或重建虚拟屏收不掉一个选择框。答掉/, '不能同时给出相反方向')

  const onUserScreen = hintFor({
    capability: 'app.launch',
    code: 'E_LAUNCH_NOT_LANDED',
    surface: 'foreground',
    data: { observed: 'com.android.intentresolver' },
  })
  assert.match(onUserScreen, /认不出它里面装着哪个应用/)
})

test('E_SURFACE_UNAVAILABLE 有兜底建议，不再落到无文案的 default', () => {
  const hint = hintFor({ capability: 'app.launch', code: 'E_SURFACE_UNAVAILABLE', data: null })
  assert.ok(hint.length > 0)
  assert.match(hint, /backend\.shizuku/)
})

test('degradeReason 单独成行，reason 与 note 各自保留自己的语义', () => {
  const parsed = normalize({
    capability: 'app.launch',
    stdout: JSON.stringify({
      v: 1, id: 'r1', ok: true, surface: 'foreground', degradedFrom: 'trusted-display',
      degradeReason: 'E_SURFACE_UNAVAILABLE', note: 'foreground is clone container com.vivo.doubleinstance',
      data: { package: 'com.ss.android.ugc.aweme', verified: false, observed: 'com.vivo.doubleinstance' },
    }),
    stderr: '',
    exitCode: 0,
  })
  assert.equal(parsed.ok, true)
  assert.equal(parsed.degradeReason, 'E_SURFACE_UNAVAILABLE')
  assert.equal(parsed.note, 'foreground is clone container com.vivo.doubleinstance')
  const text = describe(parsed, null)
  assert.match(text, /degrade: E_SURFACE_UNAVAILABLE/)
  assert.match(text, /note: foreground is clone container/)
  assert.match(text, /处置建议: .*screen\.capture/)
})

test('toolsApi 解析不到时给出可读的边界说明，不把假阴性说成插件坏了', () => {
  const probe = index.probeToolsApi()
  if (probe.ok) {
    // 本机若恰好能解析到该模块，只要求成功形状齐备。
    assert.equal(probe.via, 'require')
    return
  }
  assert.ok(Array.isArray(probe.bases) && probe.bases.length >= 2, '应列出尝试过的基准目录')
  assert.match(probe.meaning, /不表示插件坏了/)
  assert.match(probe.meaning, /phone_\*/)
})
test('phone_status 的 list 与 filter 是入参，逐条清单取自那份 capabilities.json', async () => {
  const fs = await import('node:fs')
  const os = await import('node:os')
  const nodePath = await import('node:path')
  const { buildTools } = await import('../plugin/lib/tools.js')
  const dir = fs.mkdtempSync(nodePath.join(os.tmpdir(), 'mp-status-'))
  const manifest = nodePath.join(dir, 'capabilities.json')
  const caps = [
    { id: 'ui.snapshot', category: 'observe', systemPermission: 'MANUAL_ONLY', assistantTier: 'ALWAYS', ceiling: 'ANY', usable: true, implemented: true, minSdk: 29, surfaces: ['foreground', 'trusted-display'], guide: 'g' },
    { id: 'ui.tap', category: 'control', systemPermission: 'MANUAL_ONLY', assistantTier: 'ASK', ceiling: 'ANY', usable: false, implemented: true, minSdk: 29, surfaces: ['foreground'], guide: '要开危险开关' },
    { id: 'cal.read', category: 'calendar', systemPermission: 'CALENDAR', assistantTier: 'ASK', ceiling: 'ANY', usable: false, implemented: true, minSdk: 29, surfaces: ['foreground'], guide: '缺日历权限' },
  ]
  fs.writeFileSync(manifest, JSON.stringify({ protocol: 1, entry: {}, backend: { shizuku: { running: false } }, capabilities: caps }))
  const channel = {
    resolve: () => ({ root: dir, source: 'test', info: { manifest, entry: 'x', entryExecutable: false } }),
    call: async () => ({ ok: true, elapsedMs: 1, data: { count: 1 } }),
  }
  const tools = buildTools({ defineTool: (spec) => spec, channel, outDirOption: dir })
  const status = tools.find((t) => t.name === 'phone_status')
  assert.deepEqual(Object.keys(status.parameters).sort(), ['filter', 'list', 'probe'])
  assert.ok(!('list' in ((status.output && status.output.schema) || {})), 'list 不该出现在出参 schema 里')
  const all = await status.execute({ list: true })
  assert.equal(all.capabilities, 3)
  assert.match(all.text, /不可调的已实现能力 2 条：ui\.tap、cal\.read/)
  assert.doesNotMatch(all.text, /要开危险开关/, '不重复 guide 那句长文')
  const rows = (await status.execute({ list: true, filter: 'ui.' })).text.split('\n').filter((l) => /^[a-z]+\.[a-z]+ \| /.test(l))
  assert.equal(rows.length, 2, 'filter 要真的收窄')
  fs.rmSync(dir, { recursive: true, force: true })
})

test('降级码挂在成功回包上时也要给建议（code 空、degradeReason 有值）', () => {
  const degraded = hintFor({ ok: true, code: null, degradeReason: 'E_SURFACE_NO_SHELL', capability: 'ui.node', data: null })
  assert.match(degraded, /Shizuku/)
  assert.match(degraded, /surface=foreground/)
  const tooLow = hintFor({ ok: true, code: null, degradeReason: 'E_SURFACE_API_LEVEL', capability: 'ui.node', data: null })
  assert.match(tooLow, /Android 13/)
  assert.doesNotMatch(tooLow, /Shizuku 用户服务没在跑/)
})

test('虚拟屏那次没有 data.observed：从 reason 点名到中介包也要走选择框那段', () => {
  const reason = 'accepted but a system chooser may hold the front (top on display 45 is ' +
    'com.vivo.doubleinstance, the user\'s own screen shows com.dshbox.app): the target is waiting ' +
    'for someone to pick an icon.'
  const hint = hintFor({ ok: false, code: 'E_LAUNCH_NOT_LANDED', surface: 'trusted-display', reason, data: {} })
  assert.match(hint, /停在最前面的是 com\.vivo\.doubleinstance/)
  assert.match(hint, /回的是失败（E_LAUNCH_NOT_LANDED）/)
  assert.doesNotMatch(hint, /有另一个应用确实停在最前面/)
  assert.doesNotMatch(hint, /才按"选一个图标"的框处理/)
})

test('清单没有 generatedAt 时，phone_status 报文件时间而不是「未知」', async () => {
  const fs = await import('node:fs')
  const os = await import('node:os')
  const nodePath = await import('node:path')
  const { buildTools } = await import('../plugin/lib/tools.js')
  const dir = fs.mkdtempSync(nodePath.join(os.tmpdir(), 'mp-time-'))
  const manifest = nodePath.join(dir, 'capabilities.json')
  fs.writeFileSync(manifest, JSON.stringify({ protocol: 1, entry: {}, capabilities: [] }))
  const channel = {
    resolve: () => ({ root: dir, source: 'test', info: { manifest, entry: 'x', entryExecutable: false } }),
    call: async () => ({ ok: true, elapsedMs: 1, data: {} }),
  }
  const status = buildTools({ defineTool: (spec) => spec, channel, outDirOption: dir }).find((t) => t.name === 'phone_status')
  const r = await status.execute({})
  assert.doesNotMatch(r.text, /清单生成时间: 未知/)
  assert.match(r.text, /清单未带时间字段，按文件时间 \d{4}-\d{2}-\d{2}T/)
  fs.rmSync(dir, { recursive: true, force: true })
})

test('选择框那句按 ok 分形状说：失败那次不能写成「不是失败」', () => {
  const failed = hintFor({ ok: false, code: 'E_LAUNCH_NOT_LANDED', surface: 'trusted-display', data: {}, reason: 'top on display 45 is com.vivo.doubleinstance' })
  assert.match(failed, /回的是失败（E_LAUNCH_NOT_LANDED）/)
  assert.doesNotMatch(failed, /不是失败/)
  const okShape = hintFor({ ok: true, code: null, surface: 'foreground', data: { observed: 'com.vivo.doubleinstance' } })
  assert.match(okShape, /verified:false，不是失败/)
})
