#!/usr/bin/env bash
set -euo pipefail

PROFILE_ARG="${1:-}"
if [ -z "$PROFILE_ARG" ] || [ ! -d "$PROFILE_ARG" ]; then
  echo "Browser adapter: profile directory is unavailable" >&2
  exit 1
fi
PROFILE="$(cd -- "$PROFILE_ARG" && pwd -P)"
PKG='@local/browser-use'
SRC="$(cd -- "$(dirname -- "$0")" && pwd -P)/plugin"
NM="$PROFILE/node_modules"
LOCAL="$NM/@local"
PKG_DIR="$LOCAL/browser-use"
LEGACY_PKG='@local/browser'
LEGACY_DIR="$LOCAL/browser"
PKGJSON="$PROFILE/package.json"
STAGE="$LOCAL/.browser-use-stage.$$"
BACKUP="$LOCAL/.browser-use-backup.$$"
LEGACY_BACKUP="$LOCAL/.browser-use-legacy-backup.$$"
JSON_BACKUP="$PROFILE/.browser-use-package-json.$$"
PUBLISHED=0
BACKED_UP=0
LEGACY_MOVED=0

fail() { echo "Browser adapter: $*" >&2; exit 1; }

[ -s "$PKGJSON" ] && [ ! -L "$PKGJSON" ] || fail "profile package.json is missing or not a regular file"
[ -d "$SRC" ] || fail "adapter assets are missing"
NODE="$(command -v node || true)"
[ -n "$NODE" ] || fail "Node.js is unavailable"
for file in package.json cordis.patch.yml browser-use-install.json lib/index.js lib/client.js lib/tools.js; do
  [ -s "$SRC/$file" ] || fail "required adapter asset is missing"
done

"$NODE" --check "$SRC/lib/index.js"
"$NODE" --check "$SRC/lib/client.js"
"$NODE" --check "$SRC/lib/tools.js"

if "$NODE" - "$PKGJSON" "$PKG_DIR" "$PKG" "$LEGACY_DIR" "$LEGACY_PKG" <<'NODE'
const fs = require('node:fs')
const [manifestPath, packageDir, packageName, legacyDir, legacyName] = process.argv.slice(2)
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
if (fs.existsSync(packageDir)) {
  const st = fs.lstatSync(packageDir)
  if (!st.isDirectory() || st.isSymbolicLink()) process.exit(12)
  let owner
  try { owner = readOwnerNoFollow(packageDir + '/.browser-use-install.json') } catch { process.exit(13) }
  if (!owner || owner.package !== packageName) process.exit(14)
}
if (fs.existsSync(legacyDir)) {
  const st = fs.lstatSync(legacyDir)
  if (!st.isDirectory() || st.isSymbolicLink()) process.exit(15)
  let owner
  try { owner = readOwnerNoFollow(legacyDir + '/.browser-install.json') } catch { owner = null }
  if (bundles.includes(legacyName) && (!owner || owner.package !== legacyName)) process.exit(16)
} else if (bundles.includes(legacyName)) {
  process.exit(17)
}
NODE
then PREFLIGHT=0; else PREFLIGHT=$?; fi
case "$PREFLIGHT" in
  0) ;;
  10) fail "profile package.json is invalid" ;;
  11) fail "profile package.json has no dsh.profile.bundles list" ;;
  12) fail "the @local/browser-use target is not an owned directory" ;;
  13|14) fail "the @local/browser-use target has no matching ownership record" ;;
  15) fail "the legacy Browser package path is not a regular directory" ;;
  16) fail "the legacy Browser package is not owned by this adapter" ;;
  17) fail "the legacy Browser bundle has no package directory to verify" ;;
  *) fail "profile validation failed" ;;
esac

MIGRATE_LEGACY=0
if "$NODE" - "$LEGACY_DIR" "$LEGACY_PKG" <<'NODE'
const fs = require('node:fs')
const [legacyDir, legacyName] = process.argv.slice(2)
try {
  const marker = legacyDir + '/.browser-install.json'
  const noFollow = fs.constants.O_NOFOLLOW
  const before = fs.lstatSync(marker)
  if (!before.isFile() || before.isSymbolicLink()) process.exit(1)
  if (!Number.isFinite(before.dev) || !Number.isFinite(before.ino)) process.exit(1)
  const fd = fs.openSync(marker, fs.constants.O_RDONLY | (Number.isInteger(noFollow) ? noFollow : 0))
  let owner
  try {
    const opened = fs.fstatSync(fd)
    if (!opened.isFile() || opened.dev !== before.dev || opened.ino !== before.ino) process.exit(1)
    owner = JSON.parse(fs.readFileSync(fd, 'utf8'))
    const after = fs.lstatSync(marker)
    if (!after.isFile() || after.isSymbolicLink() || after.dev !== opened.dev || after.ino !== opened.ino) process.exit(1)
  } finally { fs.closeSync(fd) }
  if (owner && owner.package === legacyName) process.exit(0)
} catch {}
process.exit(1)
NODE
then MIGRATE_LEGACY=1; fi

if [ -L "$NM" ] || [ -L "$LOCAL" ]; then
  fail "the node_modules/@local path contains a symbolic link"
fi
mkdir -p "$LOCAL"
[ ! -e "$STAGE" ] && [ ! -e "$BACKUP" ] && [ ! -e "$LEGACY_BACKUP" ] && [ ! -e "$JSON_BACKUP" ] || fail "a Browser adapter transaction residue needs manual review"

cleanup() {
  local rc=$?
  trap - EXIT INT TERM
  if [ "$rc" -ne 0 ]; then
    if [ "$PUBLISHED" -eq 1 ]; then rm -rf -- "$PKG_DIR" || true; fi
    if [ "$BACKED_UP" -eq 1 ] && [ -e "$BACKUP" ]; then mv -- "$BACKUP" "$PKG_DIR" || echo "Browser adapter: previous package remains at its transaction backup" >&2; fi
    if [ -e "$JSON_BACKUP" ]; then mv -- "$JSON_BACKUP" "$PKGJSON" || echo "Browser adapter: package.json backup remains for recovery" >&2; fi
    if [ "$LEGACY_MOVED" -eq 1 ] && [ -e "$LEGACY_BACKUP" ]; then mv -- "$LEGACY_BACKUP" "$LEGACY_DIR" || echo "Browser adapter: legacy package remains at its transaction backup" >&2; fi
    echo "Browser adapter: installation failed; previous package state was restored where possible" >&2
  else
    [ ! -e "$BACKUP" ] || rm -rf -- "$BACKUP" || echo "Browser adapter: stale owned backup remains" >&2
    [ ! -e "$LEGACY_BACKUP" ] || rm -rf -- "$LEGACY_BACKUP" || echo "Browser adapter: migrated legacy package backup remains" >&2
    [ ! -e "$JSON_BACKUP" ] || rm -f -- "$JSON_BACKUP" || true
  fi
  [ ! -e "$STAGE" ] || rm -rf -- "$STAGE" || true
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

cp -R -- "$SRC" "$STAGE"
cp -- "$STAGE/browser-use-install.json" "$STAGE/.browser-use-install.json"
[ -s "$STAGE/package.json" ] && [ -s "$STAGE/cordis.patch.yml" ] || fail "staged adapter failed its inventory check"
"$NODE" - "$STAGE/package.json" "$PKG" <<'NODE'
const fs = require('node:fs')
const [file, expected] = process.argv.slice(2)
try {
  const p = JSON.parse(fs.readFileSync(file, 'utf8'))
  if (p.name !== expected || p.dsh?.bundle?.patch !== './cordis.patch.yml') process.exit(1)
} catch { process.exit(1) }
NODE

cp -p -- "$PKGJSON" "$JSON_BACKUP"
if [ -e "$PKG_DIR" ]; then
  mv -- "$PKG_DIR" "$BACKUP"
  BACKED_UP=1
fi
mv -- "$STAGE" "$PKG_DIR"
PUBLISHED=1

if [ "$MIGRATE_LEGACY" -eq 1 ]; then
  mv -- "$LEGACY_DIR" "$LEGACY_BACKUP"
  LEGACY_MOVED=1
fi

"$NODE" - "$PKGJSON" "$PKG" "$LEGACY_PKG" <<'NODE'
const fs = require('node:fs')
const path = require('node:path')
const [file, name, legacyName] = process.argv.slice(2)
const st = fs.statSync(file)
const data = JSON.parse(fs.readFileSync(file, 'utf8'))
const bundles = data?.dsh?.profile?.bundles
if (!Array.isArray(bundles)) process.exit(1)
data.dsh.profile.bundles = bundles.filter((bundle) => bundle !== legacyName)
if (!data.dsh.profile.bundles.includes(name)) data.dsh.profile.bundles.push(name)
const tmp = path.join(path.dirname(file), `.browser-use-package-json.${process.pid}.${Date.now()}`)
try {
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2) + '\n', { flag: 'wx', mode: st.mode })
  fs.renameSync(tmp, file)
} finally {
  try { fs.unlinkSync(tmp) } catch {}
}
NODE

rm -f -- "$JSON_BACKUP"
PUBLISHED=0
BACKED_UP=0
LEGACY_MOVED=0
echo "Browser adapter assembled"
