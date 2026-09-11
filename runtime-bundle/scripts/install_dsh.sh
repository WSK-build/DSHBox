#!/bin/bash
# install_dsh.sh - runs inside Debian sandbox. Installs the exact DSH version
# for offline/online bundle preparation. Do NOT rely on latest tags of
# @deepseek-ai/dsh-* subpackages; always pin the exact DSH version.

set -euo pipefail

DSH_VERSION="${DSH_VERSION:-0.1.0-rc.6}"
DSH_NPM_REGISTRY="${DSH_NPM_REGISTRY:-https://registry.npmjs.org}"
PNPM_DIR="${PNPM_DIR:-/opt/dshapp/pnpm-cache}"

echo "Installing DeepSeek Harness ${DSH_VERSION} (pin exact version; do not use latest tag)"
npm install --prefix /opt/dshapp/runtime "npm:@deepseek-ai/dsh@${DSH_VERSION}" --registry "$DSH_NPM_REGISTRY"
npm install --prefix /opt/dshapp/runtime "pnpm@latest" --registry "$DSH_NPM_REGISTRY" || true

# Android 硬链接兼容：此前在此调用 patch_dsh_android.js 就地改写 DSH 的 JS 文件。
# 1.3.1 起改为**运行期垫片**（app 启动 DSH 时以 --import 预加载 link-shim.mjs），
# 因此这里不再改动 DSH 源码，安装出的层保持上游原样。

echo "Verifying install:"
ls -la /opt/dshapp/runtime/node_modules/@deepseek-ai/dsh || true
echo "Run with: /opt/dshapp/runtime/node_modules/.bin/dsh web --host 127.0.0.1 --port 3080"
