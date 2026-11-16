// 录音等待预算的跨层契约（可加载测试文件）：入口脚本的 call TTL 上限、插件本层的
// 入口区间、宿主 EnvelopeCodec 的有效期上限与 USAGE 文案必须同表——120 秒内容之外
// 各侧都要留收尾余量，任何一侧仍按内容上限夹紧，满时长录音就会被那一侧掐断。
// 直接读四处源文件逐项比对，不各自维护一份"应该是多少"的副本。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { ENTRY_TIMEOUT_MIN_MS, ENTRY_TIMEOUT_MAX_MS, timeoutFor } from '../plugin/lib/pilot.js'

const CONTENT_CAP_MS = 120000
const WAITING_CAP_MS = 130000

/** 从本测试文件向上找仓库布局里的源文件：找不到就当场失败，不静默跳过。 */
function sourceOf (relative) {
  const testDir = fileURLToPath(new URL('.', import.meta.url))
  // test → mobile-pilot → plugins → assets → dshbox-plugins → plugin-manager → 工作树根
  return readFileSync(new URL(`../../../../../../${relative}`, `file://${testDir}`), 'utf8')
}

function constantOf (source, name) {
  // JS 形如 `const NAME = 130000;`，Kotlin 形如 `const val NAME = 130_000L`：两种都认。
  const match = source.match(new RegExp(`const\\s+(?:val\\s+)?${name}\\s*=\\s*([\\d_]+)L?;?`))
  assert.ok(match, `源文件里找不到常量 ${name}`)
  return Number(match[1].replace(/_/g, ''))
}

test('入口脚本的 call TTL 上限已放宽到 130000，与插件本层同值', () => {
  const script = sourceOf('pilot/interlock-relay-core/src/main/assets/relay/relay-client.cjs')
  assert.equal(constantOf(script, 'CALL_MAX_TTL_MS'), WAITING_CAP_MS)
  assert.equal(constantOf(script, 'CALL_MIN_TTL_MS'), ENTRY_TIMEOUT_MIN_MS)
  // help 文案与夹紧实现出自同一对常量：文案写 120000 而实现放 130000 的错位由这里挡住。
  assert.match(script, /how long this process waits, 1000\.\.130000/)
  // ask 是另一条通路（等用户答复），上限独立在 120000：契约只锁 call，不锁 ask。
  assert.equal(constantOf(script, 'ASK_MAX_TIMEOUT_MS'), 120000)
  // 插件把 130000 的预算传给入口，入口不再静默夹回 120000。
  assert.equal(ENTRY_TIMEOUT_MAX_MS, WAITING_CAP_MS)
})

test('宿主有效期与 audio 档位同表，全部严格高于 120 秒内容上限', () => {
  const codec = sourceOf('pilot/interlock-relay-core/src/main/java/interlock/relay/core/transport/EnvelopeCodec.kt')
  assert.equal(constantOf(codec, 'MAX_TTL_MS'), WAITING_CAP_MS)
  assert.equal(timeoutFor('audio.capture'), WAITING_CAP_MS)
  assert.ok(WAITING_CAP_MS > CONTENT_CAP_MS)
  assert.equal(WAITING_CAP_MS, CONTENT_CAP_MS + 10000)
})

test('USAGE 文案的入口区间与录音预算已同步', () => {
  const usage = sourceOf('pilot/interlock-relay-core/src/main/java/interlock/relay/core/transport/GuestAssets.kt')
    .replace(/\s+/g, ' ')
  assert.match(usage, /clamped into 1000\.\.130000/)
  assert.match(usage, /calling budget at 130000ms/)
})
