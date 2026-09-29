// install.sh / uninstall.sh 的契约测试：用 bash 子进程对临时 fixture profile 跑真实的装卸脚本，
// 断言包结构、所有权清单、bundle 注册、升级替换与失败回滚。
// 前提是本机 bash 里有 python3 与 node；任一缺席就整体跳过并说明原因，不静默通过。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const PACK_DIR = fileURLToPath(new URL('..', import.meta.url))

const PKG_DIR_NAME = path.join('node_modules', '@local', 'mobile-pilot')
const MANIFEST_NAME = '.mobile-pilot-install.json'
const REQUIRED_FILES = [
  'package.json', 'cordis.patch.yml', 'lib/index.js', 'lib/mount.js',
  'lib/pilot.js', 'lib/tools.js', 'bin/mobile-pilot',
]

const bashProbe = spawnSync('bash', ['-c', 'command -v python3 >/dev/null 2>&1 && command -v node >/dev/null 2>&1'], { encoding: 'utf8' })
const needsBash = bashProbe.status === 0
  ? {}
  : { skip: `本机 bash 里 python3/node 不齐（exit ${bashProbe.status}），装卸契约测试跑不起来` }

/** Windows 路径交给 bash 前统一成正斜杠，反斜杠在 bash 里是转义符。 */
const shPath = (p) => p.replace(/\\/g, '/')

/**
 * 拼进 PATH 的路径必须是 MSYS 形式（/c/...）：bash 会无视 "C:/..." 形式的 PATH 条目。
 * 非 Windows 上路径本来就是 POSIX 形式，原样返回。
 */
function msysPath (p) {
  if (process.platform !== 'win32') return shPath(p)
  const r = spawnSync('bash', ['-c', `cygpath -u "${shPath(p)}"`], { encoding: 'utf8' })
  assert.equal(r.status, 0, `cygpath 转换失败：${r.stderr}`)
  return r.stdout.trim()
}

/** 在 bash 里执行一段命令，返回 status/stdout/stderr。PATH 已是 bash 的 MSYS 形式。 */
function bashRun (command, env = {}) {
  const r = spawnSync('bash', ['-c', command], { encoding: 'utf8', env: { ...process.env, ...env } })
  return { status: r.status, stdout: r.stdout || '', stderr: r.stderr || '' }
}

/** fixture profile：结构正确的最小 dsh profile，外加一个不该被装卸碰到的邻居包。 */
function makeFixtureProfile (t) {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-fixture-'))
  t.after(() => fs.rmSync(profile, { recursive: true, force: true }))
  fs.writeFileSync(path.join(profile, 'package.json'),
    JSON.stringify({ name: 'fixture-profile', dsh: { profile: { bundles: [] } } }, null, 2))
  fs.mkdirSync(path.join(profile, 'node_modules', '.bin'), { recursive: true })
  fs.mkdirSync(path.join(profile, 'node_modules', '@local', 'other-pkg'), { recursive: true })
  fs.writeFileSync(path.join(profile, 'node_modules', '@local', 'other-pkg', 'keep.txt'), 'not ours')
  return profile
}

/** fixture 安装源：整个插件目录复制一份。契约测试要能改它（升版、损坏），不能碰真源。 */
function makeFixtureSource (t) {
  const src = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-src-'))
  t.after(() => fs.rmSync(src, { recursive: true, force: true }))
  fs.cpSync(PACK_DIR, src, { recursive: true })
  return src
}

/** 升版标记：往 fixture 源的 mount.js 追加一行注释，用内容区分新旧两版。 */
function markSource (src, marker) {
  const file = path.join(src, 'plugin', 'lib', 'mount.js')
  fs.writeFileSync(file, `${fs.readFileSync(file, 'utf8')}\n// fixture marker: ${marker}\n`)
}

function readBundles (profile) {
  return JSON.parse(fs.readFileSync(path.join(profile, 'package.json'), 'utf8')).dsh.profile.bundles
}

function readOwnershipManifest (profile) {
  return JSON.parse(fs.readFileSync(path.join(profile, PKG_DIR_NAME, MANIFEST_NAME), 'utf8'))
}

/** 阶段/备份目录是事务的内部状态：任何正常出口（含失败回滚）之后都不允许留下。 */
function assertNoTransactionLeftovers (profile) {
  const local = path.join(profile, 'node_modules', '@local')
  const leftovers = fs.existsSync(local)
    ? fs.readdirSync(local).filter((n) => n.startsWith('.mobile-pilot-'))
    : []
  assert.deepEqual(leftovers, [], `暂存或备份目录没清干净：${leftovers.join(', ')}`)
}

function assertNeighborUntouched (profile) {
  const keep = path.join(profile, 'node_modules', '@local', 'other-pkg', 'keep.txt')
  assert.equal(fs.readFileSync(keep, 'utf8'), 'not ours', '邻居包被装卸误伤')
}

test('全新安装：包结构、mount.json、bundle 注册、命令入口与所有权清单一次到位', needsBash, (t) => {
  const profile = makeFixtureProfile(t)
  const src = makeFixtureSource(t)
  const r = bashRun(`bash "${shPath(path.join(src, 'install.sh'))}" "${shPath(profile)}"`)
  assert.equal(r.status, 0, `stdout: ${r.stdout}\nstderr: ${r.stderr}`)

  const pkg = path.join(profile, PKG_DIR_NAME)
  for (const f of [...REQUIRED_FILES, 'mount.json', MANIFEST_NAME]) {
    assert.ok(fs.existsSync(path.join(pkg, f)), `安装后缺 ${f}`)
  }
  assert.ok(readBundles(profile).includes('@local/mobile-pilot'), 'bundle 没注册上')
  assert.ok(fs.existsSync(path.join(profile, 'node_modules', '.bin', 'mobile-pilot')), '命令入口不在')

  const manifest = readOwnershipManifest(profile)
  assert.equal(manifest.package, '@local/mobile-pilot')
  assert.equal(manifest.binLink, 'node_modules/.bin/mobile-pilot')
  assert.deepEqual(manifest.files, REQUIRED_FILES)
  assert.ok(Number.isInteger(manifest.installedAtWall), 'installedAtWall 应为 epoch 秒')
  assert.ok(Math.abs(manifest.installedAtWall - Date.now() / 1000) < 300, 'installedAtWall 偏离当前时间过远')

  assertNoTransactionLeftovers(profile)
  assertNeighborUntouched(profile)
})

test('升级：旧包被新包替换、所有权清单更新，bundle 不重复登记', needsBash, (t) => {
  const profile = makeFixtureProfile(t)
  const src = makeFixtureSource(t)
  const install = `bash "${shPath(path.join(src, 'install.sh'))}" "${shPath(profile)}"`
  assert.equal(bashRun(install).status, 0)
  const firstInstalledAt = readOwnershipManifest(profile).installedAtWall

  markSource(src, 'fixture-v2')
  const r = bashRun(install)
  assert.equal(r.status, 0, `stdout: ${r.stdout}\nstderr: ${r.stderr}`)

  const pkg = path.join(profile, PKG_DIR_NAME)
  assert.match(fs.readFileSync(path.join(pkg, 'lib', 'mount.js'), 'utf8'), /fixture-v2/, '旧包没有被新包替换')
  assert.ok(readOwnershipManifest(profile).installedAtWall >= firstInstalledAt, '所有权清单没有随升级更新')
  const entries = readBundles(profile).filter((b) => b === '@local/mobile-pilot')
  assert.equal(entries.length, 1, 'bundle 重复登记')
  assertNoTransactionLeftovers(profile)
})

test('安装源缺文件：预检挡下，安装源的版本改动不上位，旧包完好', needsBash, (t) => {
  const profile = makeFixtureProfile(t)
  const src = makeFixtureSource(t)
  const install = `bash "${shPath(path.join(src, 'install.sh'))}" "${shPath(profile)}"`
  assert.equal(bashRun(install).status, 0)

  markSource(src, 'fixture-v2')
  fs.rmSync(path.join(src, 'plugin', 'lib', 'tools.js'))
  const r = bashRun(install)
  assert.notEqual(r.status, 0, '缺文件的安装源不应装成功')
  assert.match(r.stdout, /插件内容不完整/)

  // 旧包（v1）还在原位：mount.js 里没有 v2 标记，bundle 与命令入口都保持可用
  const pkg = path.join(profile, PKG_DIR_NAME)
  assert.doesNotMatch(fs.readFileSync(path.join(pkg, 'lib', 'mount.js'), 'utf8'), /fixture-v2/)
  assert.ok(fs.existsSync(path.join(pkg, 'lib', 'tools.js')), '旧包的文件被动了')
  assert.ok(readBundles(profile).includes('@local/mobile-pilot'))
  assert.ok(fs.existsSync(path.join(profile, 'node_modules', '.bin', 'mobile-pilot')))
  assertNoTransactionLeftovers(profile)
})

test('发布后注册失败：半包被撤、旧版回滚上位，命令入口继续指向可用旧包', needsBash, (t) => {
  const profile = makeFixtureProfile(t)
  const src = makeFixtureSource(t)
  const installCmd = `bash "${shPath(path.join(src, 'install.sh'))}" "${shPath(profile)}"`
  assert.equal(bashRun(installCmd).status, 0)
  const firstInstalledAt = readOwnershipManifest(profile).installedAtWall

  // python3 替身：bundle 注册那一次调用（argv 里带着包名）直接失败，其余调用原样转交。
  // 失败点刻意选在发布之后：这时旧包已进备份位，考验的是回滚本身。
  const realPy = spawnSync('bash', ['-c', 'command -v python3'], { encoding: 'utf8' }).stdout.trim()
  const shimDir = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-shim-'))
  t.after(() => fs.rmSync(shimDir, { recursive: true, force: true }))
  const shim = path.join(shimDir, 'python3')
  fs.writeFileSync(shim, `#!/bin/sh
if [ "$3" = "@local/mobile-pilot" ]; then
  echo "shim: simulated bundle-registration failure" >&2
  exit 70
fi
exec "$REAL_PYTHON3" "$@"
`)
  assert.equal(spawnSync('bash', ['-c', `chmod +x "${shPath(shim)}"`]).status, 0)

  markSource(src, 'fixture-v2')
  const r = bashRun(
    `export PATH="${msysPath(shimDir)}:$PATH"; ${installCmd}`,
    { REAL_PYTHON3: realPy },
  )
  assert.notEqual(r.status, 0, '注册失败必须以非零退出')
  assert.match(r.stdout + r.stderr, /回滚/, '失败要说明已经回滚')

  // 旧版（v1，没有 v2 标记）回到包位，连它自己的所有权清单一起回来
  const pkg = path.join(profile, PKG_DIR_NAME)
  assert.doesNotMatch(fs.readFileSync(path.join(pkg, 'lib', 'mount.js'), 'utf8'), /fixture-v2/)
  assert.equal(readOwnershipManifest(profile).installedAtWall, firstInstalledAt, '所有权清单应随旧包一起回滚')
  assert.ok(readBundles(profile).includes('@local/mobile-pilot'), 'bundle 保持注册状态')
  assert.ok(fs.existsSync(path.join(profile, 'node_modules', '.bin', 'mobile-pilot')), '命令入口还在')
  assertNoTransactionLeftovers(profile)
  assertNeighborUntouched(profile)
})

test('卸载：按所有权清单撤包与命令入口，bundle 摘除，邻居不受伤', needsBash, (t) => {
  const profile = makeFixtureProfile(t)
  const src = makeFixtureSource(t)
  assert.equal(bashRun(`bash "${shPath(path.join(src, 'install.sh'))}" "${shPath(profile)}"`).status, 0)

  const r = bashRun(`bash "${shPath(path.join(src, 'uninstall.sh'))}" "${shPath(profile)}"`)
  assert.equal(r.status, 0, `stdout: ${r.stdout}\nstderr: ${r.stderr}`)

  assert.ok(!fs.existsSync(path.join(profile, PKG_DIR_NAME)), '包目录没删掉')
  assert.ok(!fs.existsSync(path.join(profile, 'node_modules', '.bin', 'mobile-pilot')), '命令入口没删掉')
  assert.ok(!readBundles(profile).includes('@local/mobile-pilot'), 'bundle 没摘除')
  // profile 的 package.json 必须仍可解析：卸载最忌讳把宿主的配置文件写坏
  assert.doesNotThrow(() => JSON.parse(fs.readFileSync(path.join(profile, 'package.json'), 'utf8')))
  assertNeighborUntouched(profile)
})

test('卸载：清单登记的命令入口认不出是我们的文件时，留着不动', needsBash, (t) => {
  const profile = makeFixtureProfile(t)
  const src = makeFixtureSource(t)
  assert.equal(bashRun(`bash "${shPath(path.join(src, 'install.sh'))}" "${shPath(profile)}"`).status, 0)

  // 命令入口被换成了与包无关的用户文件：清单虽然记着这个路径，内容对不上就不许删
  const bin = path.join(profile, 'node_modules', '.bin', 'mobile-pilot')
  fs.writeFileSync(bin, 'user data that merely lives at the same path\n')
  const r = bashRun(`bash "${shPath(path.join(src, 'uninstall.sh'))}" "${shPath(profile)}"`)
  assert.equal(r.status, 0, `stdout: ${r.stdout}\nstderr: ${r.stderr}`)
  assert.equal(fs.readFileSync(bin, 'utf8'), 'user data that merely lives at the same path\n', '用户文件被误删')
  assert.ok(!fs.existsSync(path.join(profile, PKG_DIR_NAME)), '包目录照常移除')
})

test('卸载：没有所有权清单时只动固定规范路径', needsBash, (t) => {
  const profile = makeFixtureProfile(t)
  const src = makeFixtureSource(t)
  assert.equal(bashRun(`bash "${shPath(path.join(src, 'install.sh'))}" "${shPath(profile)}"`).status, 0)
  fs.rmSync(path.join(profile, PKG_DIR_NAME, MANIFEST_NAME))

  const r = bashRun(`bash "${shPath(path.join(src, 'uninstall.sh'))}" "${shPath(profile)}"`)
  assert.equal(r.status, 0, `stdout: ${r.stdout}\nstderr: ${r.stderr}`)
  assert.ok(!fs.existsSync(path.join(profile, PKG_DIR_NAME)), '规范包目录要移除')
  assert.ok(!fs.existsSync(path.join(profile, 'node_modules', '.bin', 'mobile-pilot')), '规范命令入口要移除')
  assert.ok(!readBundles(profile).includes('@local/mobile-pilot'))
  assertNeighborUntouched(profile)
})

test('首装发布后失败：半包撤除之外，bundle 条目与命令链接一并摘除，profile 回到安装前', needsBash, (t) => {
  const profile = makeFixtureProfile(t)
  const src = makeFixtureSource(t)

  // python3 替身：只让所有权清单那一次写入失败（argv 第二段是清单路径）。
  // 失败点刻意选在 bundle 注册与命令链接都成功之后：首装没有旧版可回，
  // 考验的是回滚把已扩散到 profile 上的副作用一并收干净。
  const realPy = spawnSync('bash', ['-c', 'command -v python3'], { encoding: 'utf8' }).stdout.trim()
  const shimDir = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-shim-'))
  t.after(() => fs.rmSync(shimDir, { recursive: true, force: true }))
  const shim = path.join(shimDir, 'python3')
  fs.writeFileSync(shim, `#!/bin/sh
case "$2" in
  *.mobile-pilot-install.json)
    echo "shim: simulated ownership-manifest failure" >&2
    exit 70 ;;
esac
exec "$REAL_PYTHON3" "$@"
`)
  assert.equal(spawnSync('bash', ['-c', `chmod +x "${shPath(shim)}"`]).status, 0)

  const r = bashRun(
    `export PATH="${msysPath(shimDir)}:$PATH"; bash "${shPath(path.join(src, 'install.sh'))}" "${shPath(profile)}"`,
    { REAL_PYTHON3: realPy },
  )
  assert.notEqual(r.status, 0, '所有权清单写失败必须以非零退出')
  assert.match(r.stdout + r.stderr, /回滚/)

  assert.ok(!fs.existsSync(path.join(profile, PKG_DIR_NAME)), '半包要撤掉')
  assert.deepEqual(readBundles(profile), [], '首装回滚必须把 bundle 条目一并摘除')
  assert.ok(!fs.existsSync(path.join(profile, 'node_modules', '.bin', 'mobile-pilot')), '命令链接要撤掉')
  // profile 的 package.json 必须仍可解析：回滚最忌讳把宿主的配置文件写坏
  assert.doesNotThrow(() => JSON.parse(fs.readFileSync(path.join(profile, 'package.json'), 'utf8')))
  assertNoTransactionLeftovers(profile)
  assertNeighborUntouched(profile)
})

test('卸载：清单里的 binLink 越出 profile 时按不可信处理，固定规范路径照常清理', needsBash, (t) => {
  const profile = makeFixtureProfile(t)
  const src = makeFixtureSource(t)
  assert.equal(bashRun(`bash "${shPath(path.join(src, 'install.sh'))}" "${shPath(profile)}"`).status, 0)

  // 篡改所有权清单：binLink 指向 profile 之外的一个无辜文件，卸载不得顺着它删出去。
  const outside = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-outside-'))
  t.after(() => fs.rmSync(outside, { recursive: true, force: true }))
  const victim = path.join(outside, 'keep.txt')
  fs.writeFileSync(victim, 'not ours to delete')
  const manifestPath = path.join(profile, PKG_DIR_NAME, MANIFEST_NAME)
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
  manifest.binLink = path.relative(profile, victim).replace(/\\/g, '/')
  fs.writeFileSync(manifestPath, JSON.stringify(manifest))

  const r = bashRun(`bash "${shPath(path.join(src, 'uninstall.sh'))}" "${shPath(profile)}"`)
  assert.equal(r.status, 0, `stdout: ${r.stdout}\nstderr: ${r.stderr}`)
  assert.match(r.stderr, /越出了 profile/, '越界条目要说明为什么不按它删')
  assert.equal(fs.readFileSync(victim, 'utf8'), 'not ours to delete', '越界条目不得成为删除依据')
  assert.ok(!fs.existsSync(path.join(profile, 'node_modules', '.bin', 'mobile-pilot')), '固定规范路径的命令入口照常移除')
  assert.ok(!fs.existsSync(path.join(profile, PKG_DIR_NAME)), '包目录照常移除')
  assert.ok(!readBundles(profile).includes('@local/mobile-pilot'))
  assertNeighborUntouched(profile)
})
