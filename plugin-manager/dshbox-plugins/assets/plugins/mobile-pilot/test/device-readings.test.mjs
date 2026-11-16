/**
 * 设备回包读数对应的回归：数值参数归一、选择框现场的建议、执行面降级单独成键。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { hintFor, describe, numericArgs, normalize, timeoutFor } from '../plugin/lib/pilot.js'
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
    // 审批口径字段：assistantTier / approvalPolicy / requiresApproval / sessionGranted /
    // sessionGrantable 是清单展示的权威列；ceiling 即使出现在旧文件里也不再展示，
    // 不按限制读（档位说了算之后它不是有效上限）。
    { id: 'ui.snapshot', category: 'observe', systemPermission: 'MANUAL_ONLY', assistantTier: 'ASK', approvalPolicy: 'ask_each_call', requiresApproval: true, sessionGranted: true, sessionGrantable: true, usable: true, implemented: true, minSdk: 29, surfaces: ['foreground', 'trusted-display'], guide: 'g' },
    // 带 ceiling 的残留条目：字段还在但不该进表，也不该被读成限制。
    { id: 'notify.post', category: 'data', systemPermission: 'ASK', ceiling: 'ANY', assistantTier: 'ASK', approvalPolicy: 'ask_each_call', requiresApproval: true, sessionGranted: false, sessionGrantable: true, usable: true, implemented: true, minSdk: 29, surfaces: ['foreground'], guide: '' },
    { id: 'cal.read', category: 'calendar', systemPermission: 'CALENDAR', assistantTier: 'ASK', approvalPolicy: 'ask_each_call', requiresApproval: true, sessionGranted: false, sessionGrantable: true, usable: false, implemented: true, minSdk: 29, surfaces: ['foreground'], guide: '缺日历权限' },
    { id: 'sys.shell.full', category: 'system', systemPermission: 'MANUAL_ONLY', assistantTier: 'DENIED', approvalPolicy: 'denied', requiresApproval: false, sessionGranted: false, sessionGrantable: false, usable: false, implemented: true, minSdk: 29, surfaces: [], guide: '特权模式页的独立开关' },
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
  assert.equal(all.capabilities, 4)
  assert.doesNotMatch(all.text, /ui\.snapshot、notify\.post、/, '不可调清单不把 usable=true 的条目算进去')
  assert.doesNotMatch(all.text, /特权模式页的独立开关/, '不重复 guide 那句长文')
  const rows = (await status.execute({ list: true, filter: 'ui.' })).text.split('\n').filter((l) => /^[a-z]+\.[a-z]+ \| /.test(l))
  assert.equal(rows.length, 1, 'filter 要真的收窄')
  // header 与行都要按新审批口径列；ceiling 即使残留也不进表。
  assert.match(all.text, /档位 \| 审批策略 \| 此刻需审批 \| 会话授权在效 \| 可授权会话/)
  assert.match(all.text, /sys\.shell\.full \| .* \| DENIED \| denied \| false \| false \| false \|/)
  assert.match(all.text, /notify\.post \| .* \| ASK \| ask_each_call \| true \| false \| true \|/)
  assert.doesNotMatch(all.text, /ANY \|/, 'ceiling 不再展示，不被当有效上限')
  // 降级原因列：没降级写 "-"，有降级写它。
  assert.match(all.text, / \| 需系统权限 \| 降级原因/)
  assert.match(all.text, /notify\.post \| .* \| -\n/)
  fs.rmSync(dir, { recursive: true, force: true })
})

test('清单字段缺失时按空值兜底：不改判 usable 也不猜测旧语义，ceiling 缺席同样不炸', async () => {
  const fs = await import('node:fs')
  const os = await import('node:os')
  const nodePath = await import('node:path')
  const { buildTools } = await import('../plugin/lib/tools.js')
  const dir = fs.mkdtempSync(nodePath.join(os.tmpdir(), 'mp-sparse-'))
  const manifest = nodePath.join(dir, 'capabilities.json')
  const caps = [
    // 老宿主铺的条目：没有审批口径字段也没有 ceiling。展示层按空值处理，不猜语义。
    { id: 'ui.snapshot', category: 'observe', usable: true, implemented: true, surfaces: ['foreground'] },
  ]
  fs.writeFileSync(manifest, JSON.stringify({ protocol: 1, entry: {}, capabilities: caps }))
  const channel = {
    resolve: () => ({ root: dir, source: 'test', info: { manifest, entry: 'x', entryExecutable: false } }),
    call: async () => ({ ok: true, elapsedMs: 1, data: {} }),
  }
  const status = buildTools({ defineTool: (spec) => spec, channel, outDirOption: dir }).find((t) => t.name === 'phone_status')
  const r = await status.execute({ list: true })
  assert.equal(r.capabilities, 1)
  assert.match(r.text, /ui\.snapshot \| observe \|  \|  \| false \| false \| false \| true \| true \| foreground \| - \| -/)
  fs.rmSync(dir, { recursive: true, force: true })
})

test('ROM 拒建 trusted 屏时给出改前台的处置，且不建议反复重试', () => {
  const hint = hintFor({ ok: false, code: 'E_SURFACE_TRUSTED_DENIED', capability: 'surface.virtual', data: null })
  assert.match(hint, /trusted 虚拟屏/)
  assert.match(hint, /不要反复重试/)
  assert.match(hint, /前台模式/)
  // degradeReason 挂在成功回包上时同样按这条处理。
  const degraded = hintFor({ ok: true, code: null, degradeReason: 'E_SURFACE_TRUSTED_DENIED', capability: 'ui.node', data: null })
  assert.match(degraded, /前台模式/)
})

test('无安全建屏尺寸（两边都超 2560）的建议是"不重试、改前台"，且不按 ROM 拒绝处置', () => {
  // 非正方形场景（短=2561、长=3000）：建议回显必须是"短边在前、长边在后"的读法。
  const hint = hintFor({
    ok: false, code: 'E_SURFACE_UNAVAILABLE', capability: 'surface.virtual',
    data: { sizePolicy: 'none_safe', physicalShortSide: 2561, physicalLongSide: 3000 },
    reason: 'physical display is 2561x3000: no safe trusted-display size fits (each side must be at most 2560 px), so the display is not created; do not retry surface.virtual - run the involved operations in foreground mode',
  })
  // 结构化字段优先：建议里回显 host 给出的物理尺寸（短边×长边），而不是抛空位。
  assert.match(hint, /短边×长边 = 2561×3000/)
  assert.match(hint, /不要反复/)
  assert.match(hint, /前台模式/)
  // 与 ROM 拒绝分开口径：这个失败不写清单的 denied 标记，建议里不得出现"清单已标记 unusable"。
  assert.doesNotMatch(hint, /unusable|拒绝为本应用创建/)
})

test('无安全尺寸识别走结构化字段优先：字段在而 reason 不带英文短语时同样命中；都不在则不误判', () => {
  // data.sizePolicy 在场（新装配）→ 直接命中，不依赖 reason 的英文正则。
  const byField = hintFor({
    ok: false, code: 'E_SURFACE_UNAVAILABLE', capability: 'surface.virtual',
    data: { sizePolicy: 'none_safe', physicalShortSide: 2900, physicalLongSide: 3000 },
    reason: '',
  })
  assert.match(byField, /2900×3000/)
  assert.match(byField, /前台模式/)

  // 字段缺席（旧装配）→ 回退 reason 正则仍可达。
  const byReason = hintFor({
    ok: false, code: 'E_SURFACE_UNAVAILABLE', capability: 'surface.virtual',
    data: null,
    reason: 'physical display is 3000x3000: no safe trusted-display size fits (each side must be at most 2560 px), so the display is not created; do not retry surface.virtual - run the involved operations in foreground mode',
  })
  assert.match(byReason, /不要反复/)
  assert.match(byReason, /前台模式/)

  // 两处都不给 → 按普通 surface 不可达处理，不猜"无安全尺寸"。
  const neither = hintFor({ ok: false, code: 'E_SURFACE_UNAVAILABLE', capability: 'app.launch', data: null, reason: 'some note' })
  assert.doesNotMatch(neither, /重试，改用前台模式执行这些操作|不要反复 surface\.virtual 重试，改用前台模式执行这些操作/)
})

test('phone_surface 的无安全尺寸现场：工具结果文本来自 sizePolicy 字段与 host 的 data（端到端形状）', async () => {
  const fs = await import('node:fs')
  const os = await import('node:os')
  const nodePath = await import('node:path')
  const { buildTools } = await import('../plugin/lib/tools.js')
  const dir = fs.mkdtempSync(nodePath.join(os.tmpdir(), 'mp-nosafe-'))
  const manifest = nodePath.join(dir, 'capabilities.json')
  fs.writeFileSync(manifest, JSON.stringify({}))
  const channel = {
    resolve: () => ({ root: dir, source: 'test', info: { manifest, entry: 'x', entryExecutable: false } }),
    // 宿主生产形状：data 三枚结构化字段（短=2561、长=3000 的非正方形），reason 只是"人读的同义补充"。
    call: async () => ({
      ok: false, capability: 'surface.virtual', code: 'E_SURFACE_UNAVAILABLE', elapsedMs: 3,
      data: { sizePolicy: 'none_safe', physicalShortSide: 2561, physicalLongSide: 3000 },
      reason: 'physical display is 2561x3000: no safe trusted-display size fits (each side must be at most 2560 px), so the display is not created; do not retry surface.virtual - run the involved operations in foreground mode',
      retryable: false,
    }),
  }
  const tool = buildTools({ defineTool: (spec) => spec, channel, outDirOption: dir }).find((t) => t.name === 'phone_surface')
  const r = await tool.execute({ action: 'create' })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'E_SURFACE_UNAVAILABLE')
  // 字段读法优先：结构化字段按短/长命名不按横竖交换；建议文本里短边在前
  // （"短边×长边" = 2561×3000），与宿主字段顺序一字不差。
  assert.match(r.text, /短边×长边 = 2561×3000/)
  assert.match(r.text, /前台模式|改用前台模式/)
  assert.doesNotMatch(r.text, /unusable|拒绝为本应用创建/)
  // 建议只落一处：失败路径由 summary（→ describe）拼 hintFor，不再重复追加——
  // 同一段文本出现两行"处置建议"会把同一条建议摆成两份。
  assert.equal((r.text.match(/处置建议:/g) || []).length, 1)
  fs.rmSync(dir, { recursive: true, force: true })
})

test('release 失败回包 inputStateRestored 布尔 false：类型与语句都按"可确认未恢复"读', () => {
  const help = hintFor({ ok: false, code: 'E_BACKEND_UNAVAILABLE', capability: 'surface.virtual', data: { inputStateRestored: false } })
  assert.match(help, /没有把后台虚拟屏收掉|屏还在/)
  assert.match(help, /确认未恢复/)
  // 字符串 "false" 不触发布尔判据：类型分岔是判据的一部分。
  const wrongType = hintFor({ ok: false, code: 'E_BACKEND_UNAVAILABLE', capability: 'surface.virtual', data: { inputStateRestored: 'false' } })
  assert.doesNotMatch(wrongType, /确认未恢复/)
})

test('release 本地放弃等待（GaveUp）按失败读：不说"已释放"，指向 query 复核', () => {
  const hint = hintFor({
    ok: false, code: 'E_TRANSPORT_TIMEOUT', capability: 'surface.virtual',
    data: { inputStateRestored: 'unknown' },
    reason: 'local wait for the display release transaction gave up after 500ms; the transaction was not cancelled and its outcome is unknown - do not treat this as not executed',
  })
  // 不宣称已释放、不说未执行：这一档只有"等待被放弃、屏可能还在、事务可能稍后完成"。
  assert.match(hint, /等待被放弃/)
  assert.match(hint, /可能还在|可能稍后/)
  assert.doesNotMatch(hint, /已释放/)
  assert.doesNotMatch(hint, /未执行，忽略|按未执行处理/)
  // 下一步是复核，不是立即重试也不是下结论。
  assert.match(hint, /query/)
})

test('release 成功回包 inputStateRestored=unknown 给重启兜底，不宣称已恢复', () => {
  const hint = hintFor({ ok: true, code: null, capability: 'surface.virtual', data: { released: true, inputStateRestored: 'unknown' } })
  assert.match(hint, /不能当作已恢复|无法.*证实/)
  assert.match(hint, /重启/)
  // inputStateRestored 不在场（其它成功回包）不触发这条建议。
  const plain = hintFor({ ok: true, code: null, capability: 'surface.virtual', data: { released: true } })
  assert.doesNotMatch(plain, /重启/)
})

test('phone_surface 成功路径也拼建议：release unknown 的重启兜底在工具结果里可达', async () => {
  const fs = await import('node:fs')
  const os = await import('node:os')
  const nodePath = await import('node:path')
  const { buildTools } = await import('../plugin/lib/tools.js')
  const dir = fs.mkdtempSync(nodePath.join(os.tmpdir(), 'mp-rel-'))
  const manifest = nodePath.join(dir, 'capabilities.json')
  fs.writeFileSync(manifest, JSON.stringify({}))
  const channel = {
    resolve: () => ({ root: dir, source: 'test', info: { manifest, entry: 'x', entryExecutable: false } }),
    call: async () => ({
      ok: true, capability: 'surface.virtual', code: '', elapsedMs: 2, data: {
        released: true, inputStateRestored: 'unknown',
        note: 'the trusted display is released, but whether the system gestures on the user\'s own screen are back cannot be confirmed from this reply',
      },
    }),
  }
  const tool = buildTools({ defineTool: (spec) => spec, channel, outDirOption: dir }).find((t) => t.name === 'phone_surface')
  const r = await tool.execute({ action: 'release' })
  assert.equal(r.ok, true)
  assert.match(r.text, /不能当作已恢复|无法.*证实/)
  assert.match(r.text, /重启/)
  // 无建议字段的建屏/查询成功照常出结果，不强行拼"处置建议"标题。
  const plainChannel = {
    resolve: channel.resolve,
    call: async () => ({ ok: true, capability: 'surface.virtual', code: '', elapsedMs: 2, data: { state: 'create', display: 3 } }),
  }
  const tool2 = buildTools({ defineTool: (spec) => spec, channel: plainChannel, outDirOption: dir }).find((t) => t.name === 'phone_surface')
  const r2 = await tool2.execute({})
  assert.equal(r2.ok, true)
  assert.doesNotMatch(r2.text, /处置建议:/)
  fs.rmSync(dir, { recursive: true, force: true })
})

test('phone_surface：create/query 回传右侧栏可预览，release 不声明可预览', async () => {
  const fs = await import('node:fs')
  const os = await import('node:os')
  const nodePath = await import('node:path')
  const { buildTools } = await import('../plugin/lib/tools.js')
  const dir = fs.mkdtempSync(nodePath.join(os.tmpdir(), 'mp-preview-'))
  const manifest = nodePath.join(dir, 'capabilities.json')
  fs.writeFileSync(manifest, JSON.stringify({}))
  const calls = []
  const channel = {
    resolve: () => ({ root: dir, source: 'test', info: { manifest, entry: 'x', entryExecutable: false } }),
    call: async (capability, payload) => {
      calls.push({ capability, payload })
      if (payload.action === 'release') {
        return { ok: true, capability, code: '', elapsedMs: 1, data: { released: true, displayId: -1 } }
      }
      return { ok: true, capability, code: '', elapsedMs: 1, data: { state: payload.action, displayId: 42 } }
    },
  }
  const tool = buildTools({ defineTool: (spec) => spec, channel, outDirOption: dir }).find((t) => t.name === 'phone_surface')

  const created = await tool.execute({ action: 'create' })
  assert.equal(created.previewAvailable, true)
  assert.equal(created.previewSurface, 'dshbox-right-sidebar')
  assert.equal(created.doNotMoveToMain, true)
  assert.match(created.agentHint, /右侧栏.*预览/)
  assert.match(created.text, /预览提示:/)

  const queried = await tool.execute({ action: 'query' })
  assert.equal(queried.previewAvailable, true, 'query 仍可复读预览状态')
  assert.equal(queried.doNotMoveToMain, true)

  const released = await tool.execute({ action: 'release' })
  assert.equal(released.previewAvailable, false)
  assert.equal(released.previewSurface, '')
  assert.equal(released.doNotMoveToMain, false)
  assert.match(released.agentHint, /已释放/)
  assert.doesNotMatch(released.text, /预览提示:/)
  fs.rmSync(dir, { recursive: true, force: true })
})

test('phone_surface：displayId 字符串/0/失败边界不误报可预览', async () => {
  const fs = await import('node:fs')
  const os = await import('node:os')
  const nodePath = await import('node:path')
  const { buildTools } = await import('../plugin/lib/tools.js')
  const dir = fs.mkdtempSync(nodePath.join(os.tmpdir(), 'mp-preview-edge-'))
  const manifest = nodePath.join(dir, 'capabilities.json')
  fs.writeFileSync(manifest, JSON.stringify({}))
  let reply = { ok: true, code: '', elapsedMs: 1, data: { displayId: '42' } }
  const channel = {
    resolve: () => ({ root: dir, source: 'test', info: { manifest, entry: 'x', entryExecutable: false } }),
    call: async (capability) => ({ capability, ...reply }),
  }
  const tool = buildTools({ defineTool: (spec) => spec, channel, outDirOption: dir }).find((t) => t.name === 'phone_surface')

  assert.equal((await tool.execute({ action: 'query' })).previewAvailable, true, '数字字符串按有效屏号读')

  reply = { ok: true, code: '', elapsedMs: 1, data: { displayId: 0 } }
  const zero = await tool.execute({ action: 'query' })
  assert.equal(zero.previewAvailable, false, '0 是主屏，不是虚拟屏')
  assert.equal(zero.doNotMoveToMain, false)

  reply = { ok: false, code: 'E_SURFACE_UNAVAILABLE', elapsedMs: 1, data: { displayId: 42 } }
  const failed = await tool.execute({ action: 'create' })
  assert.equal(failed.previewAvailable, false, '失败回包即便带屏号也不声明可预览')
  assert.equal(failed.previewSurface, '')

  fs.rmSync(dir, { recursive: true, force: true })
})

test('E_SURFACE_MODE_REQUIRED 的建议与被拒现场互不覆盖：读到 denied 给前台建议', () => {
  const hint = hintFor({ ok: false, code: 'E_SURFACE_MODE_REQUIRED', capability: 'surface.virtual', data: null })
  // 允许重试修正（切偏好/建屏），这与"被拒不能重试"是两种处置，建议里不得混入对方口径。
  assert.match(hint, /优先后台/)
  assert.match(hint, /denied/)
  assert.doesNotMatch(hint, /不要反复重试/)
  // 被拒条目无论从哪个场景读出（失败码 / 成功回包 degradedReason），建议的下一步都是
  // **前台模式**——不能说"切回后台"，也不能只给结论不给方向。
  const denied = hintFor({ ok: false, code: 'E_SURFACE_TRUSTED_DENIED', capability: 'surface.virtual', data: null })
  assert.doesNotMatch(denied, /优先后台/)
  assert.match(denied, /改用前台模式|前台模式（surface=foreground）/)
  const rowDegraded = hintFor({ ok: true, code: null, degradeReason: 'E_SURFACE_TRUSTED_DENIED', capability: 'surface.virtual', data: null })
  assert.match(rowDegraded, /前台模式/)
})

test('混合现场：call 回 modeRequired 而清单 trustedDisplay.denied=true 时，phone_surface 给"改前台"', async () => {
  // 宿主旧装配（调用侧仍走 requiresMode）或调用与清单铺写有时间差时会出现这个组合：
  // 调用回 E_SURFACE_MODE_REQUIRED，清单里 backend.shizuku.trustedDisplay.denied=true。
  // 若照"切优先后台/建屏"办，用户会被送回后台再撞一次拒绝——phone_surface 对账清单，
  // 把这类回包改按拒绝回（理由=改前台）。清单不给字段时不猜，保持原状。
  const fs = await import('node:fs')
  const os = await import('node:os')
  const nodePath = await import('node:path')
  const { buildTools } = await import('../plugin/lib/tools.js')
  const dir = fs.mkdtempSync(nodePath.join(os.tmpdir(), 'mp-mixed-'))
  const manifest = nodePath.join(dir, 'capabilities.json')
  fs.writeFileSync(manifest, JSON.stringify({
    backend: { shizuku: { running: true, authorized: true, bound: true, trustedDisplay: { denied: true } } },
  }))
  const channel = {
    resolve: () => ({ root: dir, source: 'test', info: { manifest, entry: 'x', entryExecutable: false } }),
    call: async () => ({
      ok: false, capability: 'surface.virtual', code: 'E_SURFACE_MODE_REQUIRED',
      degraded: true, degradedFrom: 'trusted-display', exitCode: 4, elapsedMs: 3,
      reason: 'capability surface.virtual only runs on the trusted-display execution mode; switch the surface preference or bring that display up, then retry',
    }),
  }
  const surfaceTool = buildTools({ defineTool: (spec) => spec, channel, outDirOption: dir }).find((t) => t.name === 'phone_surface')
  const r = await surfaceTool.execute({ action: 'create' })
  // 覆盖后的建议按拒绝场景出：**整段 text**（含 reason 与建议两处）都不得再出现
  // "切偏好/切回后台/优先后台"这类反向话——只换码不换 reason 时，文本读法会同时拿到
  // "前台"与"切偏好"两个相反方向。
  assert.equal(r.code, 'E_SURFACE_TRUSTED_DENIED')
  assert.match(r.text, /前台模式|foreground mode/)
  assert.doesNotMatch(r.text, /switch the surface preference/i)
  assert.doesNotMatch(r.text, /切回后台|切到优先后台|优先后台|background-preferred/i)
  assert.equal(r.retryable, false)
  // 失败路径的建议只由 summary 拼一次，不再重复追加第二行"处置建议"。
  assert.equal((r.text.match(/处置建议:/g) || []).length, 1)
  // 清单缺失 / 字段不给时不猜：modeRequired 原样通过。
  const manifestNoField = nodePath.join(nodePath.dirname(manifest), 'no.denied.json')
  fs.writeFileSync(manifestNoField, JSON.stringify({
    backend: { shizuku: { running: true } },
  }))
  const plainChannel = {
    resolve: () => ({ root: dir, source: 'test', info: { manifest: manifestNoField, entry: 'x', entryExecutable: false } }),
    call: channel.call,
  }
  const toolB = buildTools({ defineTool: (spec) => spec, channel: plainChannel, outDirOption: dir }).find((t) => t.name === 'phone_surface')
  const rb = await toolB.execute({ action: 'create' })
  assert.equal(rb.code, 'E_SURFACE_MODE_REQUIRED')
  assert.match(rb.text, /优先后台/)
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

test('完全 Shizuku 被拒时指向特权模式页的开关，而不是让人反复重试', () => {
  const hint = hintFor({ ok: false, code: 'E_GATE_HOST_DENIED', capability: 'sys.shell.full', data: null })
  assert.match(hint, /特权模式页/)
  assert.match(hint, /完全 Shizuku 能力/)
  // 特权通路被拒不会回退到 sys.shell：建议里不能暗示换那条封闭表还能跑表外命令。
  assert.match(hint, /不会回退到 sys\.shell/)
  // 普通能力被拒仍是原判词：不点名 sys.shell.full 时不该把人支去拨特权开关。
  const other = hintFor({ ok: false, code: 'E_GATE_HOST_DENIED', capability: 'sys.shell', data: null })
  assert.doesNotMatch(other, /特权模式页/)
})

test('录音的等待预算留出收尾余量：120 秒内容 + 10 秒余量拿得到产物', () => {
  // 宿主把 audio.capture 的 seconds 夹在 1..120：档位与内容同值时，满时长录音会在
  // 编码落盘、回执回写还没走完时先被这里掐断。130000 = 内容 120 秒 + 收尾余量 10 秒。
  assert.equal(timeoutFor('audio.capture'), 130000)
  assert.ok(timeoutFor('audio.capture') > 120000, '预算必须高于 120 秒内容上限，否则 120 秒内容必然被外层掐断')
  // 覆盖值仍按调用方给的走（夹紧到入口区间由 timeoutPlan 既有规则负责）。
  assert.equal(timeoutFor('audio.capture', 30000), 30000)
  // 入口区间上限同步放宽：130000 的预算原样下发，不再被夹短。
  assert.equal(timeoutFor('audio.capture', 130000), 130000)
  assert.equal(timeoutFor('audio.capture', 140000), 130000)
  // 其他默认档位不受影响。
  assert.equal(timeoutFor('pkg.query'), 30000)
  assert.equal(timeoutFor('screen.capture'), 90000)
})

test('通知发送被系统权限拦下时指向权限/渠道，而不是让人重试', () => {
  const hint = hintFor({ ok: false, code: 'E_GATE_SYSTEM_MISSING', capability: 'notify.post', data: null })
  assert.match(hint, /通知权限或通知渠道/)
  assert.match(hint, /重试仍会被拦下/)
  // 其他能力的系统权限缺口没有这条专项判词：保持原有空判词，不把通知那句套到别的场景。
  const other = hintFor({ ok: false, code: 'E_GATE_SYSTEM_MISSING', capability: 'contact.read', data: null })
  assert.equal(other, '')
})

test('通知结局未知时禁止同 id 重发，指向通知栏确认', () => {
  // 宿主把发送交给了系统、但复查窗口里没看见它：E_TRANSPORT_UNKNOWN 携带
  // id/posted/verified=false。重发同 id 可能造成重复通知，唯一下一步是让人确认。
  const hint = hintFor({ ok: false, code: 'E_TRANSPORT_UNKNOWN', capability: 'notify.post', data: null })
  assert.match(hint, /结局未知/)
  assert.match(hint, /不要用同一 id 原样重发/)
  assert.match(hint, /通知栏/)
  // 其他能力的传输未知仍走通道失联那套通用判词（status/cancel 跟进）。
  const other = hintFor({ ok: false, code: 'E_TRANSPORT_UNKNOWN', capability: 'sys.shell', data: null })
  assert.doesNotMatch(other, /同一 id 原样重发/)
  assert.match(other, /status/)
})
