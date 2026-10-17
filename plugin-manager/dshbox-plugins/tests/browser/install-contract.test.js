'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { spawnSync } = require('node:child_process')

const adapterRoot = path.resolve(__dirname, '../../assets/plugins/browser-use')
const bash = process.env.BROWSER_TEST_BASH || 'bash'

function profile(t, manifest = { name: 'fixture', dsh: { profile: { bundles: ['@local/keep'] }, metadata: { keep: true } } }) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-browser-adapter-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  fs.writeFileSync(path.join(root, 'package.json'), `${JSON.stringify(manifest, null, 2)}\n`)
  return root
}

function runScript(name, profilePath, env = process.env) {
  return spawnSync(bash, [path.join(adapterRoot, name), profilePath], { encoding: 'utf8', windowsHide: true, env })
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'))
}

test('explicit disable before profile creation is successful without creating profile data', (t) => {
  const parent = fs.mkdtempSync(path.join(os.tmpdir(), 'browser-empty-profile-'))
  t.after(() => fs.rmSync(parent, { recursive: true, force: true }))
  const missing = path.join(parent, 'not-created')
  const result = runScript('uninstall.sh', missing)
  assert.equal(result.status, 0, result.stderr)
  assert.equal(fs.existsSync(missing), false)
  const empty = path.join(parent, 'empty')
  fs.mkdirSync(empty)
  const emptyResult = runScript('uninstall.sh', empty)
  assert.equal(emptyResult.status, 0, emptyResult.stderr)
  assert.deepEqual(fs.readdirSync(empty), [])
})

function linkMarkerOrSkip(t, markerPath, targetPath) {
  try { fs.symlinkSync(targetPath, markerPath) } catch (error) {
    if (['EPERM', 'EACCES', 'ENOTSUP'].includes(error.code)) {
      t.skip(`symlink creation is unavailable: ${error.code}`)
      return false
    }
    throw error
  }
  return true
}

test('install assembles only @local/browser-use and retains unrelated package state', (t) => {
  const root = profile(t)
  const result = runScript('install.sh', root)
  assert.equal(result.status, 0, result.stderr)
  const manifest = readJson(path.join(root, 'package.json'))
  assert.deepEqual(manifest.dsh.profile.bundles, ['@local/keep', '@local/browser-use'])
  assert.deepEqual(manifest.dsh.metadata, { keep: true })
  assert.equal(readJson(path.join(root, 'node_modules/@local/browser-use/package.json')).name, '@local/browser-use')
  assert.equal(readJson(path.join(root, 'node_modules/@local/browser-use/.browser-use-install.json')).package, '@local/browser-use')
})

test('upgrade refreshes the owned package without duplicating registration or changing siblings', (t) => {
  const root = profile(t)
  assert.equal(runScript('install.sh', root).status, 0)
  const sibling = path.join(root, 'node_modules/@local/keep')
  fs.mkdirSync(sibling, { recursive: true })
  fs.writeFileSync(path.join(sibling, 'sentinel'), 'preserve')
  const manifestBefore = readJson(path.join(root, 'package.json'))
  const result = runScript('install.sh', root)
  assert.equal(result.status, 0, result.stderr)
  assert.deepEqual(readJson(path.join(root, 'package.json')).dsh.profile.bundles, ['@local/keep', '@local/browser-use'])
  assert.deepEqual(readJson(path.join(root, 'package.json')).dsh.metadata, manifestBefore.dsh.metadata)
  assert.equal(fs.readFileSync(path.join(sibling, 'sentinel'), 'utf8'), 'preserve')
})

test('install migrates only an owned legacy Browser package and its bundle entry', (t) => {
  const root = profile(t, { name: 'fixture', dsh: { profile: { bundles: ['@local/keep', '@local/browser'] }, metadata: { keep: true } } })
  const legacy = path.join(root, 'node_modules/@local/browser')
  fs.mkdirSync(legacy, { recursive: true })
  fs.writeFileSync(path.join(legacy, '.browser-install.json'), JSON.stringify({ package: '@local/browser', format: 1 }))
  fs.writeFileSync(path.join(legacy, 'owned-file'), 'legacy adapter')
  const result = runScript('install.sh', root)
  assert.equal(result.status, 0, result.stderr)
  const manifest = readJson(path.join(root, 'package.json'))
  assert.deepEqual(manifest.dsh.profile.bundles, ['@local/keep', '@local/browser-use'])
  assert.deepEqual(manifest.dsh.metadata, { keep: true })
  assert.equal(fs.existsSync(legacy), false)
  assert.equal(readJson(path.join(root, 'node_modules/@local/browser-use/.browser-use-install.json')).package, '@local/browser-use')
})

test('install refuses an unowned legacy Browser registration without changing it', (t) => {
  const root = profile(t, { name: 'fixture', dsh: { profile: { bundles: ['@local/keep', '@local/browser'] } } })
  const legacy = path.join(root, 'node_modules/@local/browser')
  fs.mkdirSync(legacy, { recursive: true })
  fs.writeFileSync(path.join(legacy, '.browser-install.json'), JSON.stringify({ package: '@local/foreign', format: 1 }))
  fs.writeFileSync(path.join(legacy, 'keep'), 'untouched')
  const before = fs.readFileSync(path.join(root, 'package.json'), 'utf8')
  const result = runScript('install.sh', root)
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /legacy Browser package is not owned/i)
  assert.equal(fs.readFileSync(path.join(root, 'package.json'), 'utf8'), before)
  assert.equal(fs.readFileSync(path.join(legacy, 'keep'), 'utf8'), 'untouched')
  assert.equal(fs.existsSync(path.join(root, 'node_modules/@local/browser-use')), false)
})

test('install refuses a registered legacy Browser entry whose package is missing', (t) => {
  const root = profile(t, { name: 'fixture', dsh: { profile: { bundles: ['@local/keep', '@local/browser'] } } })
  const before = fs.readFileSync(path.join(root, 'package.json'), 'utf8')
  const result = runScript('install.sh', root)
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /legacy Browser bundle has no package directory/i)
  assert.equal(fs.readFileSync(path.join(root, 'package.json'), 'utf8'), before)
  assert.equal(fs.existsSync(path.join(root, 'node_modules/@local/browser-use')), false)
})

test('failed new package publication restores the owned legacy package and profile', (t) => {
  const root = profile(t, { name: 'fixture', dsh: { profile: { bundles: ['@local/keep', '@local/browser'] }, metadata: { keep: true } } })
  const legacy = path.join(root, 'node_modules/@local/browser')
  fs.mkdirSync(legacy, { recursive: true })
  fs.writeFileSync(path.join(legacy, '.browser-install.json'), JSON.stringify({ package: '@local/browser', format: 1 }))
  fs.writeFileSync(path.join(legacy, 'owned-file'), 'legacy adapter')
  const before = fs.readFileSync(path.join(root, 'package.json'), 'utf8')
  const shimDir = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-browser-node-shim-'))
  t.after(() => fs.rmSync(shimDir, { recursive: true, force: true }))
  const countFile = path.join(shimDir, 'node-count')
  const realNode = process.execPath.replace(/^([A-Za-z]):/, (_, drive) => `/${drive.toLowerCase()}`).replaceAll('\\', '/')
  const nodeShim = path.join(shimDir, 'node')
  fs.writeFileSync(nodeShim, `#!/usr/bin/env bash\ncount=0\n[ ! -f '${countFile}' ] || count=$(cat '${countFile}')\ncount=$((count + 1))\nprintf '%s' "$count" > '${countFile}'\n[ "$count" -ne 7 ] || exit 91\nexec '${realNode}' "$@"\n`)
  fs.chmodSync(nodeShim, 0o755)
  const env = { ...process.env, PATH: `${shimDir}${path.delimiter}${process.env.PATH || ''}` }
  const result = runScript('install.sh', root, env)
  assert.notEqual(result.status, 0)
  assert.equal(fs.readFileSync(path.join(root, 'package.json'), 'utf8'), before)
  assert.equal(fs.readFileSync(path.join(legacy, 'owned-file'), 'utf8'), 'legacy adapter')
  assert.deepEqual(fs.readdirSync(path.join(root, 'node_modules/@local')), ['browser'])
})

test('uninstall removes only its owned bundle and keeps unrelated profile values', (t) => {
  const root = profile(t)
  assert.equal(runScript('install.sh', root).status, 0)
  const result = runScript('uninstall.sh', root)
  assert.equal(result.status, 0, result.stderr)
  const manifest = readJson(path.join(root, 'package.json'))
  assert.deepEqual(manifest.dsh.profile.bundles, ['@local/keep'])
  assert.deepEqual(manifest.dsh.metadata, { keep: true })
  assert.equal(fs.existsSync(path.join(root, 'node_modules/@local/browser-use')), false)
})

test('installer refuses unowned collision without modifying profile or target', (t) => {
  const root = profile(t)
  const target = path.join(root, 'node_modules/@local/browser-use')
  fs.mkdirSync(target, { recursive: true })
  fs.writeFileSync(path.join(target, 'keep'), 'untouched')
  const before = fs.readFileSync(path.join(root, 'package.json'), 'utf8')
  const result = runScript('install.sh', root)
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /ownership/i)
  assert.equal(fs.readFileSync(path.join(root, 'package.json'), 'utf8'), before)
  assert.equal(fs.readFileSync(path.join(target, 'keep'), 'utf8'), 'untouched')
})

test('installer refuses a symlink ownership marker even when its target claims ownership', (t) => {
  const root = profile(t)
  const target = path.join(root, 'node_modules/@local/browser-use')
  const outside = path.join(root, 'outside-owner.json')
  fs.mkdirSync(target, { recursive: true })
  fs.writeFileSync(outside, JSON.stringify({ package: '@local/browser-use' }))
  if (!linkMarkerOrSkip(t, path.join(target, '.browser-use-install.json'), outside)) return
  fs.writeFileSync(path.join(target, 'keep'), 'untouched')
  const before = fs.readFileSync(path.join(root, 'package.json'), 'utf8')
  const result = runScript('install.sh', root)
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /ownership record/i)
  assert.equal(fs.readFileSync(path.join(root, 'package.json'), 'utf8'), before)
  assert.equal(fs.readFileSync(path.join(target, 'keep'), 'utf8'), 'untouched')
  assert.equal(fs.lstatSync(path.join(target, '.browser-use-install.json')).isSymbolicLink(), true)
})

test('installer will not migrate a registered legacy package through a symlink marker', (t) => {
  const root = profile(t, { name: 'fixture', dsh: { profile: { bundles: ['@local/browser'] } } })
  const legacy = path.join(root, 'node_modules/@local/browser')
  const outside = path.join(root, 'outside-legacy-owner.json')
  fs.mkdirSync(legacy, { recursive: true })
  fs.writeFileSync(outside, JSON.stringify({ package: '@local/browser' }))
  if (!linkMarkerOrSkip(t, path.join(legacy, '.browser-install.json'), outside)) return
  fs.writeFileSync(path.join(legacy, 'keep'), 'untouched')
  const before = fs.readFileSync(path.join(root, 'package.json'), 'utf8')
  const result = runScript('install.sh', root)
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /legacy Browser package is not owned/i)
  assert.equal(fs.readFileSync(path.join(root, 'package.json'), 'utf8'), before)
  assert.equal(fs.readFileSync(path.join(legacy, 'keep'), 'utf8'), 'untouched')
  assert.equal(fs.existsSync(path.join(root, 'node_modules/@local/browser-use')), false)
})

test('installer refuses malformed bundle registration without creating package', (t) => {
  const root = profile(t, { dsh: { profile: { bundles: '@local/keep' } } })
  const result = runScript('install.sh', root)
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /bundles/i)
  assert.equal(fs.existsSync(path.join(root, 'node_modules/@local/browser-use')), false)
})

test('uninstaller refuses an unowned package and preserves both it and the profile', (t) => {
  const root = profile(t)
  const target = path.join(root, 'node_modules/@local/browser-use')
  fs.mkdirSync(target, { recursive: true })
  fs.writeFileSync(path.join(target, 'keep'), 'untouched')
  const before = fs.readFileSync(path.join(root, 'package.json'), 'utf8')
  const result = runScript('uninstall.sh', root)
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /ownership/i)
  assert.equal(fs.readFileSync(path.join(root, 'package.json'), 'utf8'), before)
  assert.equal(fs.readFileSync(path.join(target, 'keep'), 'utf8'), 'untouched')
})

test('uninstaller refuses a symlink ownership marker without removing the package', (t) => {
  const root = profile(t)
  assert.equal(runScript('install.sh', root).status, 0)
  const target = path.join(root, 'node_modules/@local/browser-use')
  const outside = path.join(root, 'outside-owner.json')
  fs.writeFileSync(outside, JSON.stringify({ package: '@local/browser-use' }))
  fs.unlinkSync(path.join(target, '.browser-use-install.json'))
  if (!linkMarkerOrSkip(t, path.join(target, '.browser-use-install.json'), outside)) return
  const before = fs.readFileSync(path.join(root, 'package.json'), 'utf8')
  const result = runScript('uninstall.sh', root)
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /ownership record/i)
  assert.equal(fs.readFileSync(path.join(root, 'package.json'), 'utf8'), before)
  assert.equal(fs.existsSync(path.join(target, 'package.json')), true)
  assert.equal(fs.lstatSync(path.join(target, '.browser-use-install.json')).isSymbolicLink(), true)
})
