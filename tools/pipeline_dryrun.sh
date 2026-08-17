#!/usr/bin/env bash
# Non-root pipeline dry run: validates the build-script changes that can be
# validated without root (npm install + arm64 rg swap + DSH patches + syntax).
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$ROOT_DIR/build/pipeline-test"
NPM_CACHE="$ROOT_DIR/.npm-cache"
rm -rf "$TMP"; mkdir -p "$TMP/native"
echo "==> npm install DSH (x64 host, like build Stage 5)"
npm install --prefix "$TMP/runtime" "npm:@deepseek-ai/dsh@${DSH_VERSION:-0.1.0-rc.6}" \
  --registry=https://registry.npmjs.org --no-audit --no-fund --cache "$NPM_CACHE"
echo "==> rg arm64 swap (build Stage 5 logic)"
RG_VERSION="$(node -p "require('$TMP/runtime/node_modules/@vscode/ripgrep/package.json').version")"
echo "installed @vscode/ripgrep version: $RG_VERSION"
(cd "$TMP/native" && npm pack "@vscode/ripgrep-linux-arm64@${RG_VERSION}" --registry=https://registry.npmjs.org --cache "$NPM_CACHE" >/dev/null)
mkdir -p "$TMP/native/pkg"
tar -xzf "$TMP/native"/*.tgz -C "$TMP/native/pkg"
rm -rf "$TMP/runtime/node_modules/@vscode/ripgrep-linux-arm64"
cp -r "$TMP/native/pkg/package" "$TMP/runtime/node_modules/@vscode/ripgrep-linux-arm64"
rm -rf "$TMP/runtime/node_modules/@vscode/ripgrep-linux-x64"
ls "$TMP/runtime/node_modules/@vscode/ripgrep-linux-arm64/bin/"
echo "==> DSH android patches (build Stage 6)"
node "$ROOT_DIR/runtime-bundle/scripts/patch_dsh_android.js" "$TMP/runtime/node_modules/@deepseek-ai"
echo "==> re-run patches (idempotence)"
node "$ROOT_DIR/runtime-bundle/scripts/patch_dsh_android.js" "$TMP/runtime/node_modules/@deepseek-ai"
echo "==> start_dsh.sh syntax"
bash -n "$ROOT_DIR/runtime-bundle/scripts/start_dsh.sh" && echo "start_dsh.sh OK"
echo "==> markers"
grep -c "DSH_PERMISSION_MODE" "$ROOT_DIR/runtime-bundle/scripts/start_dsh.sh"
grep -c "fs-local" "$TMP/runtime/node_modules/@deepseek-ai/dsh-fs-local/lib/index.js"
echo "PIPELINE-DRYRUN-OK"
