#!/usr/bin/env bash
set -euo pipefail

PROFILE_ARG="${1:-}"
if [ -z "$PROFILE_ARG" ]; then
  echo "Browser adapter: profile directory is unavailable" >&2
  exit 1
fi
if [ ! -e "$PROFILE_ARG" ] && [ ! -L "$PROFILE_ARG" ]; then
  echo "Browser adapter: no profile exists; nothing to remove"
  exit 0
fi
if [ ! -d "$PROFILE_ARG" ] || [ -L "$PROFILE_ARG" ]; then
  echo "Browser adapter: profile directory is unavailable or is a symbolic link" >&2
  exit 1
fi
PROFILE="$(cd -- "$PROFILE_ARG" && pwd -P)"
PKG='@local/browser-use'
PKG_DIR="$PROFILE/node_modules/@local/browser-use"
PKGJSON="$PROFILE/package.json"
BACKUP="$PROFILE/node_modules/@local/.browser-use-uninstall-backup.$$"
JSON_BACKUP="$PROFILE/.browser-use-package-json.$$"
MOVED=0
COMMITTED=0

fail() { echo "Browser adapter: $*" >&2; exit 1; }
if [ ! -e "$PKGJSON" ] && [ ! -L "$PKGJSON" ] && [ ! -e "$PKG_DIR" ] && [ ! -L "$PKG_DIR" ]; then
  echo "Browser adapter: profile has no registration or adapter; nothing to remove"
  exit 0
fi
[ -s "$PKGJSON" ] && [ ! -L "$PKGJSON" ] || fail "profile package.json is missing or not a regular file"
[ ! -L "$PROFILE/node_modules" ] && [ ! -L "$PROFILE/node_modules/@local" ] || fail "the node_modules/@local path contains a symbolic link"

NODE="$(command -v node || true)"
[ -n "$NODE" ] || fail "Node.js is unavailable"
if "$NODE" - "$PKGJSON" "$PKG_DIR" "$PKG" <<'NODE'
const fs = require('node:fs')
const [manifestPath, packageDir, packageName] = process.argv.slice(2)
function readOwnerNoFollow(file) {
  const noFollow = fs.constants.O_NOFOLLOW
  const before = fs.lstatSync(file)
  if (!before.isFile() || before.isSymbolicLink()) throw new Error('ownership marker is not a regular file')
  if (!Number.isFinite(before.dev) || !Number.isFinite(before.ino)) throw new Error('file identity is unavailable')
  const fd = fs.openSync(file, fs.constants.O_RDONLY | (Number.isInteger(noFollow) ? noFollow : 0))
  try {
    const opened = fs.fstatSync(fd)
    if (!opened.isFile() || opened.dev !== before.dev || opened.ino !== before.ino) throw new Error('ownership marker changed')
    const owner = JSON.parse(fs.readFileSync(fd, 'utf8'))
    const after = fs.lstatSync(file)
    if (!after.isFile() || after.isSymbolicLink() || after.dev !== opened.dev || after.ino !== opened.ino) throw new Error('ownership marker changed')
    return owner
  } finally { fs.closeSync(fd) }
}
let manifest
try { manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8')) } catch { process.exit(10) }
const bundles = manifest && manifest.dsh && manifest.dsh.profile && manifest.dsh.profile.bundles
if (!Array.isArray(bundles)) process.exit(11)
if (!fs.existsSync(packageDir)) process.exit(bundles.includes(packageName) ? 12 : 0)
const st = fs.lstatSync(packageDir)
if (!st.isDirectory() || st.isSymbolicLink()) process.exit(13)
let owner
try { owner = readOwnerNoFollow(packageDir + '/.browser-use-install.json') } catch { process.exit(14) }
if (!owner || owner.package !== packageName) process.exit(14)
NODE
then PREFLIGHT=0; else PREFLIGHT=$?; fi
case "$PREFLIGHT" in
  0) if [ ! -e "$PKG_DIR" ]; then echo "Browser adapter is not assembled"; exit 0; fi ;;
  10) fail "profile package.json is invalid" ;;
  11) fail "profile package.json has no dsh.profile.bundles list" ;;
  12) fail "@local/browser-use is registered but its ownership cannot be verified" ;;
  13) fail "the @local/browser-use target is not an owned directory" ;;
  14) fail "the @local/browser-use target has no matching ownership record" ;;
  *) fail "profile validation failed" ;;
esac

cleanup() {
  local rc=$?
  trap - EXIT INT TERM
  if [ "$rc" -ne 0 ] && [ "$COMMITTED" -eq 0 ]; then
    if [ -e "$JSON_BACKUP" ]; then mv -- "$JSON_BACKUP" "$PKGJSON" || echo "Browser adapter: package.json backup remains for recovery" >&2; fi
    if [ "$MOVED" -eq 1 ] && [ -e "$BACKUP" ]; then mv -- "$BACKUP" "$PKG_DIR" || echo "Browser adapter: package backup remains for recovery" >&2; fi
    echo "Browser adapter: uninstall failed; previous package state was restored where possible" >&2
  fi
  [ ! -e "$JSON_BACKUP" ] || rm -f -- "$JSON_BACKUP" || true
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

[ ! -e "$BACKUP" ] && [ ! -e "$JSON_BACKUP" ] || fail "a Browser adapter transaction residue needs manual review"
cp -p -- "$PKGJSON" "$JSON_BACKUP"
mv -- "$PKG_DIR" "$BACKUP"
MOVED=1
"$NODE" - "$PKGJSON" "$PKG" <<'NODE'
const fs = require('node:fs')
const path = require('node:path')
const [file, name] = process.argv.slice(2)
const st = fs.statSync(file)
const data = JSON.parse(fs.readFileSync(file, 'utf8'))
const bundles = data?.dsh?.profile?.bundles
if (!Array.isArray(bundles)) process.exit(1)
data.dsh.profile.bundles = bundles.filter((bundle) => bundle !== name)
const tmp = path.join(path.dirname(file), `.browser-use-package-json.${process.pid}.${Date.now()}`)
try {
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2) + '\n', { flag: 'wx', mode: st.mode })
  fs.renameSync(tmp, file)
} finally {
  try { fs.unlinkSync(tmp) } catch {}
}
NODE
COMMITTED=1
rm -rf -- "$BACKUP" || echo "Browser adapter: unregistered owned backup remains" >&2
MOVED=0
echo "Browser adapter removed"
