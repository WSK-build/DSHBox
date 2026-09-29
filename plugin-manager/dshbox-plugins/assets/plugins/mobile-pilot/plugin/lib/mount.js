'use strict'

/**
 * Resolves where the phone-assistant tool pack is mounted.
 *
 * The pack is the directory the host app bind-mounts into the sandbox at
 * /opt/pilot: `bin/pilot` (call entry), `capabilities.json` (capability list
 * and gate state), `USAGE.md` (the guide written for the agent) and `run/`
 * (the mailbox). It is produced by the app at runtime, so it is not part of
 * this package and may move between releases - hence the ordered lookup below
 * instead of a hardcoded path. The result is cached in `mount.json` at install
 * time so a call does not have to probe the filesystem again.
 */

const fs = require('node:fs')
const path = require('node:path')

const ENTRY_REL = ['bin', 'pilot']
const MANIFEST_REL = 'capabilities.json'
const GUIDE_REL = 'USAGE.md'
const RUN_REL = ['run', 'inbox']

/** Candidate roots, most explicit first. `env` and `mountFile` are injectable for tests. */
function candidates (env, mountFile, platform = process.platform) {
  const list = []
  if (env.MOBILE_PILOT_HOME) list.push({ source: 'MOBILE_PILOT_HOME', dir: env.MOBILE_PILOT_HOME })
  const cached = readMountFile(mountFile)
  // 来源写**真正读过的那个文件的路径**：只写文件名时，按这条线索去信箱挂载目录下找会一无所获。
  if (cached) list.push({ source: mountFile, dir: cached })
  if (platform !== 'win32') list.push({ source: 'default', dir: '/opt/pilot' })
  return list
}

function readMountFile (file) {
  try {
    const raw = JSON.parse(fs.readFileSync(file, 'utf8'))
    return typeof raw.pilotHome === 'string' && raw.pilotHome ? raw.pilotHome : null
  } catch {
    return null
  }
}

/** A directory counts as the pack only if the call entry and the manifest are both there. */
function inspect (dir) {
  const entry = path.join(dir, ...ENTRY_REL)
  const manifest = path.join(dir, MANIFEST_REL)
  const guide = path.join(dir, GUIDE_REL)
  const run = path.join(dir, ...RUN_REL)
  const has = (p, mode = fs.constants.F_OK) => {
    try {
      fs.accessSync(p, mode)
      return true
    } catch {
      return false
    }
  }
  const isDir = (p) => {
    try {
      return fs.statSync(p).isDirectory()
    } catch {
      return false
    }
  }
  return {
    dir,
    entry,
    manifest,
    guide,
    run,
    hasEntry: has(entry),
    hasManifest: has(manifest),
    hasGuide: has(guide),
    hasRun: isDir(run),
    /** Present-but-not-executable is reported separately: it is a different fix. */
    entryExecutable: has(entry, fs.constants.X_OK),
    ok: has(entry) && has(manifest),
  }
}

/**
 * Returns `{ root, source, tried }`. `root` is null when nothing resolved, in
 * which case `tried` lists what was probed and why each was rejected - the
 * caller's message is only useful if it names the paths that were looked at.
 */
function resolvePack ({ env = process.env, mountFile = path.join(__dirname, '..', 'mount.json'), platform = process.platform } = {}) {
  const tried = []
  for (const { source, dir } of candidates(env, mountFile, platform)) {
    const info = inspect(path.resolve(dir))
    tried.push({ source, dir: info.dir, ok: info.ok, hasEntry: info.hasEntry, hasManifest: info.hasManifest })
    if (info.ok) return { root: info.dir, source, tried, info }
  }
  return { root: null, source: null, tried }
}

function readManifest (file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'))
  } catch {
    return null
  }
}

module.exports = { resolvePack, inspect, readManifest, ENTRY_REL, MANIFEST_REL, GUIDE_REL }
