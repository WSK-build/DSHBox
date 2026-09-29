import { test } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { resolvePack, inspect } from '../plugin/lib/mount.js'

const BIN = fileURLToPath(new URL('../plugin/bin/mobile-pilot', import.meta.url))

/**
 * "没有挂载点"这一类用例的环境前提：本机未挂载 /opt/pilot。
 * 开发机 / 真机上默认候选必然命中，此时按前提不成立跳过；
 * 同一逻辑另由 test/pilot-tools.test.mjs 通过可注入的 platform 覆盖（不依赖环境）。
 */
const PACK_PRESENT = fs.existsSync('/opt/pilot/bin/pilot')
const noPack = PACK_PRESENT ? { skip: '本机存在 /opt/pilot：默认候选必然命中，环境前提不成立' } : {}

/** A pack directory the way the host lays it out, with an entry script that reports its argv. */
function fakePack (t, { withEntry = true, withManifest = true, exitCode = 0 } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mobile-pilot-'))
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }))
  fs.mkdirSync(path.join(dir, 'bin'), { recursive: true })
  fs.mkdirSync(path.join(dir, 'run', 'inbox'), { recursive: true })
  if (withEntry) {
    fs.writeFileSync(path.join(dir, 'bin', 'pilot'), `#!/usr/bin/env node
process.stdout.write(JSON.stringify({ argv: process.argv.slice(2), status: ${exitCode} }) + '\\n')
process.exit(${exitCode})
`)
  }
  if (withManifest) {
    fs.writeFileSync(path.join(dir, 'capabilities.json'), JSON.stringify({
      generatedAt: '2026-09-26T12:00:00Z',
      capabilities: [{ id: 'ui.snapshot', usable: true }, { id: 'screen.record', usable: false }],
    }))
  }
  return dir
}

function run (args, env = {}) {
  try {
    const stdout = execFileSync(process.execPath, [BIN, ...args], {
      encoding: 'utf8',
      env: { ...process.env, MOBILE_PILOT_HOME: '', ...env },
    })
    return { status: 0, stdout }
  } catch (error) {
    return { status: error.status, stdout: error.stdout || '', stderr: error.stderr || '' }
  }
}

test('resolvePack takes MOBILE_PILOT_HOME and reports the source', (t) => {
  const dir = fakePack(t)
  const r = resolvePack({ env: { MOBILE_PILOT_HOME: dir }, mountFile: path.join(dir, 'missing-mount.json') })
  assert.equal(r.root, fs.realpathSync(dir) === dir ? dir : path.resolve(dir))
  assert.equal(r.source, 'MOBILE_PILOT_HOME')
})

test('a directory without the entry script is not the pack', noPack, (t) => {
  const dir = fakePack(t, { withEntry: false })
  const r = resolvePack({ env: { MOBILE_PILOT_HOME: dir }, mountFile: path.join(dir, 'nope.json') })
  assert.equal(r.root, null)
  assert.equal(r.tried[0].hasEntry, false)
  assert.equal(r.tried[0].hasManifest, true)
})

test('inspect flags a non-executable entry separately from a missing one', (t) => {
  const dir = fakePack(t)
  const info = inspect(path.join(dir, '..'))
  assert.equal(info.hasEntry, false)
  assert.equal(info.ok, false)
})

test('the cached mount.json is used when the variable is unset', (t) => {
  const dir = fakePack(t)
  const mountFile = path.join(dir, 'mount.json')
  fs.writeFileSync(mountFile, JSON.stringify({ pilotHome: dir }))
  const r = resolvePack({ env: {}, mountFile })
  assert.equal(r.root, path.resolve(dir))
  // 来源给**那个文件的完整路径**，不给裸文件名：只写 mount.json 时，照着它去信箱挂载目录下找会一无所获。
  assert.equal(r.source, mountFile)
  assert.ok(r.source.endsWith('mount.json'))
})

test('call forwards argv and the entry exit code unchanged', (t) => {
  const dir = fakePack(t)
  const r = run(['call', 'screen.capture', '--out', 'shot.png'], { MOBILE_PILOT_HOME: dir })
  assert.equal(r.status, 0)
  const echoed = JSON.parse(r.stdout)
  assert.deepEqual(echoed.argv, ['call', 'screen.capture', '--out', 'shot.png'])
})

test('a denied call keeps the entry exit code rather than becoming a wrapper error', (t) => {
  const dir = fakePack(t, { exitCode: 2 })
  const r = run(['call', 'ui.tap', '--json', '{}'], { MOBILE_PILOT_HOME: dir })
  assert.equal(r.status, 2)
})

test('status/cancel are entry subcommands: the wrapper forwards them verbatim', (t) => {
  // v2 控制通道的两个查询命令不经过 wrapper 的任何转义或改写：协议归入口所有，
  // wrapper 只负责找到入口。args 原样回显即证明这一层没有第二份协议。
  const dir = fakePack(t)
  for (const args of [['status', 'req_1'], ['cancel', 'req_1']]) {
    const r = run(args, { MOBILE_PILOT_HOME: dir })
    assert.equal(r.status, 0)
    const echoed = JSON.parse(r.stdout)
    assert.deepEqual(echoed.argv, args)
  }
})

test('doctor reports what the manifest says', (t) => {
  const dir = fakePack(t)
  const r = run(['doctor'], { MOBILE_PILOT_HOME: dir })
  assert.equal(r.status, 0)
  const out = JSON.parse(r.stdout)
  assert.equal(out.root, path.resolve(dir))
  assert.equal(out.ok, true)
  assert.equal(out.manifest.capabilities, 2)
  assert.equal(out.manifest.usable, 1)
  assert.equal(out.runInbox, true)
})

test('doctor fails with the paths it looked at when nothing is mounted', noPack, () => {
  const r = run(['doctor'], { MOBILE_PILOT_HOME: path.join(os.tmpdir(), 'definitely-not-a-pack') })
  assert.equal(r.status, 6)
  const out = JSON.parse(r.stdout)
  assert.equal(out.ok, false)
  assert.equal(out.tried.length, 1)
})

test('forwarding without a pack explains which paths were tried', noPack, () => {
  const r = run(['call', 'ui.snapshot', '--json', '{}'], { MOBILE_PILOT_HOME: path.join(os.tmpdir(), 'definitely-not-a-pack') })
  assert.equal(r.status, 6)
  assert.match(r.stderr, /cannot find the phone-assistant pack/)
  assert.match(r.stderr, /definitely-not-a-pack/)
})

test('a wrapper-side failure is never reported as a usage error', noPack, () => {
  // 1 在那张表里是"用法错"，会把助手支去改命令行；挂载点没找到属本包自己的失败。
  const r = run(['call', 'ui.snapshot', '--json', '{}'], { MOBILE_PILOT_HOME: '' })
  assert.notEqual(r.status, 1)
  assert.equal(r.status, 6)
})

test('ui lists the screen-control surface straight from the manifest', (t) => {
  const dir = fakePack(t)
  const r = run(['ui'], { MOBILE_PILOT_HOME: dir })
  assert.equal(r.status, 0)
  assert.match(r.stdout, /UI control surface: 1\/1/)
  assert.match(r.stdout, /ui\.snapshot/)
  // 采集那条不属于 ui.*，混进来就说明名单不是按清单字段筛的。
  assert.doesNotMatch(r.stdout, /screen\.record/)
})

test('ui --json gives the same set with the per-capability gate fields', (t) => {
  const dir = fakePack(t)
  const r = run(['ui', '--json'], { MOBILE_PILOT_HOME: dir })
  assert.equal(r.status, 0)
  const out = JSON.parse(r.stdout)
  assert.equal(out.ui.length, 1)
  assert.equal(out.ui[0].id, 'ui.snapshot')
  assert.equal(out.ui[0].usable, true)
})

/**
 * `doctor` 必须回答"phone_* 为什么不在工具列表里"：工具面接没接上、卡在哪一步。
 * 三种卡法各有各的处置（包根本不在可解析路径 / 解析到了但 require 不动 / 导出形状不对），
 * 合成一句"未找到 dsh-tools"就会让人去查根本不相关的挂载点。
 */
test('doctor 报出工具面接没接上，并带上卡在哪一步', () => {
  const r = spawnSync(process.execPath, [BIN, 'doctor'], { encoding: 'utf8' })
  const out = JSON.parse(r.stdout)
  assert.ok(out.toolsApi, 'doctor 少了 toolsApi 字段')
  assert.equal(typeof out.toolsApi.ok, 'boolean')
  if (out.toolsApi.ok) {
    assert.equal(typeof out.toolsApi.entry, 'string', '接上了就要报解析到的路径')
  } else {
    assert.ok(
      ['resolve', 'shape', 'require', 'probe'].includes(out.toolsApi.stage),
      `没报出卡在哪一步：${JSON.stringify(out.toolsApi)}`,
    )
  }
})

/**
 * 现场自检：本机挂载了 /opt/pilot 时，上面四条「无挂载点」用例因前提不成立而整批跳过
 * （同一逻辑另由 test/pilot-tools.test.mjs 用可注入的 platform 覆盖，不依赖环境）。
 * 跳过要报出来：一次全是跳过的运行不能读成「这四条验过了」。
 */
test('现场自检：无挂载点前提的用例，本机是实跑还是整批跳过', () => {
  process.stdout.write(PACK_PRESENT
    ? '\n现场自检：本机存在 /opt/pilot，无挂载点前提的用例整批跳过（等价覆盖见 pilot-tools 的注入 platform 用例）\n'
    : '\n现场自检：本机没有 /opt/pilot，无挂载点前提的用例整批实跑\n')
  assert.equal(PACK_PRESENT, fs.existsSync('/opt/pilot/bin/pilot'), '前提判据必须与 mount.js 默认候选是同一个事实')
})
