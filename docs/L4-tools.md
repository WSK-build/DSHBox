# L4 · 运行环境宿主侧原生工具链（glibc / node-pty / 预编译清单）

> 阶段 D 交付物。L4 指在 **PRoot 侧（Debian guest）** 编译/运行的原生二进制工具链。
> 这些内容**只**在 `base` 层的裁剪范围内按需引入，`base` 层本身**不含 DSH 与 Node 业务**。

## 1. 作用
- DSH 的 `node-pty`（终端伪终端）等原生模块需要 **glibc + 编译工具链**（make/gcc/g++/cmake/pkg-config）。
- Node 24.19.0 的预编译二进制是 **arm64 Linux** 而非 pty 用；`node-pty` 的 `.node` 原生产物必须用与目标机一致的 **WSL2 arm64 glibc** 预先编译，再打进 DSH 层/或 base 层。

## 2. 分层归属
| 层 | 归属 | 是否含 L4 工具链 |
|----|------|------------------|
| base (L0) | Debian 裁剪根 | **不含** make/gcc/cmake（仅 glibc + 基础二进制 + 终端依赖） |
| node (L1) | Node 24.19.0 | 仅 Node 运行时 + npm；不含二次编译工具 |
| android-side (L3) | proot/shmem/loader | 仅宿主侧 so；不含 guest 工具链 |
| dsh (独立产物) | DSH 包 + **预编译 node-pty** | **含** 编译好的 arm64 .node |

> 因此 L4 工具链**不进入 bundle**；它只在 `build_node.sh`/DSh 打包**侧**（WSL2）使用，最终产物是预编译好的 `node-pty` `.node`，随 DSH 层部署。

## 3. 构建侧前置（WSL2/Linux）
```bash
sudo apt-get update && sudo apt-get install -y \
  debootstrap qemu-user-static zstd \
  build-essential python3 make g++ gcc cmake pkg-config
node --version   # 应等于 24.19.0（即 NODE_VERSION）
```

## 4. node-pty 预编译（一次性，WSL2 arm64）
在 WSL2 内以目标 arm64 环境编译 `node-pty`：
```bash
cd <dsh-src>/node_modules/node-pty
npm rebuild --build-from-source
```
产物：`build/Release/pty.node`（arm64 Linux glibc）。打进 DSH 层后，运行时的 `@deepseek-ai/dsh/...` 直接加载该 `.node`，无需在设备上编译。

## 5. Node 24.19.0 + arm64 预编译清单
- `build_node.sh` 默认 `NODE_VERSION=24.19.0`（可在 `bundle.yaml`/`build_arm64_runtime_bundle.sh` 覆盖）。
- Node 官方 arm64 预编译：`node-v24.19.0-linux-arm64.tar.xz`（构建机为 arm64 时直接用官方产物）。
- **一次性 arm64 native 预编译清单**（写入 `runtime-bundle/native-manifest.json`，供 DSH 层打包复用）：
```json
{
  "node": { "arch": "arm64", "version": "24.19.0", "binary": "node-v24.19.0-linux-arm64.tar.xz", "sha256": "<sha256>" },
  "node-pty": { "arch": "arm64", "abi": 127, "artifact": "node_modules/node-pty/build/Release/pty.node", "built_in": "wsl2-arm64-debian" },
  "glibc": "2.36+",
  "notes": "across arm64 linux glibc; compiled once in WSL2, deployed inside the DSH layer"
}
```

## 6. 风险 / 说明
- `node-pty` 必须与设备 `libc`/内核兼容：WSL2 的 glibc 版本需 ≥ 设备（Android 16 / arm64），否则运行时报 `GLIBC_x.y not found`。
- 若目标设备 glibc 更新，需重新在对应 glibc 环境预编译并更新清单（`built_in` 记录来源环境）。
- `base` 层不携带 gcc/make，可显著减小体积；代价是设备内**不能**二次编译原生模块（只消费预编译产物）。
