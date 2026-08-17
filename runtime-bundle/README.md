# Runtime Bundle

本目录承载 Linux Sandbox 的构建、离线打包、初始化和启动脚本。目标：

- 首次启动不依赖国际网络。
- Runtime 与 APK 解耦，可独立更新/回滚。
- Sandbox 内保持完整 Debian RootFS。

## 目录

```text
runtime-bundle/
├── scripts/
│   ├── init_sandbox.sh   # 在 Android 侧准备目录、校验 rootfs、解包
│   ├── start_sandbox.sh  # PRoot 启动入口（Debian init/NS 入口）
│   ├── start_dsh.sh      # 在 Debian 内启动 DSH Web
│   ├── install_dsh.sh    # 在 Debian 内精确安装 DSH（离线/在线 bundle 准备）
│   └── healthcheck.sh    # DSH 健康检查
├── build_rootfs.sh       # rootfs 构建入口（debootstrap/docker/proot-distro）
├── Dockerfile            # 可复现的 Debian ARM64 rootfs 镜像定义
├── bundle.yaml           # Bundle 清单（版本/架构/校验和）
└── README.md
```

## 设计约束

- 双 Runtime：`runtime-current/` 与 `runtime-new/`，更新成功才切换。
- 每个 Bundle 必须有 SHA-256、版本、架构、构建日期、来源。
- 永不删除 `user-data/` 下的 Workspace。
- DSH 只监听 `127.0.0.1`。
- PRoot 不是安全容器，最终边界是 Android App Sandbox。

## 使用

1. 将构建好的 `debian-arm64-rootfs.tar.gz` 放入本目录或 App assets。
2. 填写 `bundle.yaml` 的 `sha256`、`version`、`built_at`、`source`。
3. Android 侧调用 `SandboxManager` 解包到 `runtime-new/` 并校验。
4. 启动顺序：`init_sandbox.sh` → `start_sandbox.sh` → Debian 内 `start_dsh.sh` → `healthcheck.sh`。
