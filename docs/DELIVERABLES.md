# DSHapp 运行环境重构 · 交付清单（阶段 A→D + zstd 补）

> 本文档汇总最终交付物：release APK 路径、层版本矩阵、源码备份、CHANGELOG 记录、风险/降级说明。
> 按 `RUNTIME_ARCH_RESTRUCTURE_PLAN.md` 执行；范围仅 `$PROJECT_ROOT\modified_source`；保护 `user-data/.dsh`；bundle 不含 DSH。

---

## 一、release APK（签名 `CN=DSHapp Dev` / SHA-256 `dccfb95c…`，均可覆盖安装）

| 阶段 | 文件 | 大小 | 说明 |
|------|------|------|------|
| A | `$PROJECT_ROOT\apk_output\dshapp-phaseA-layered-runtime-20260822-release.apk` | 12.2 MB | 分层 base/node/android-side（bundle 不含 DSH） |
| B | `$PROJECT_ROOT\apk_output\dshapp-phaseB-dsh-layer-20260822-release.apk` | 12.21 MB | 加 DSH 独立层（DshLayer + 版本仲裁 + Settings UI） |
| C | `$PROJECT_ROOT\apk_output\dshapp-phaseC-integrity-20260822-release.apk` | 12.21 MB | 加完整性校验/受保护目录/进程树清理 |
| D（基线） | `$PROJECT_ROOT\apk_output\dshapp-phaseD-final-release.apk` | 12.21 MB | 阶段 D 脚本/文档落地的基线包 |
| **最终·自包含（修复版）** | `$PROJECT_ROOT\apk_output\dshapp-phaseD-zstd-bundle-20260823-fixed-release.apk` | **204.93 MB** | **内嵌 zstd(19) 运行环境** + zstd-jni 原生；**已通过真机首启**（含 R8 keep 规则修复） |
| **完整版（含 DSH）** | `$PROJECT_ROOT\apk_output\dshapp-full-DSH-0.1.1-rc.2-20260823-release.apk` | **236.17 MB** | **运行环境 + DSH 全内嵌**；DSH `0.1.1-rc.2`（镜像 arm64 拉取）；**当前推荐** |

> ⚠️ `dshapp-phaseD-zstd-bundle-20260822-release.apk`（204.88 MB）为**旧版**，release R8 会重命名 zstd-jni 私有字段导致首启崩 `NoSuchFieldError` —— **请勿使用**，一律用 `20260823-fixed` 或 `full-DSH-0.1.1-rc.2` 版。前面 12MB 的包不内嵌运行环境，需另行导入/按层装配。
> 镜像源与 DSH 层/更新的正确方案见顶层 `RUNTIME_ARCH_RESTRUCTURE_PLAN.md` §7。

---

## 二、层版本矩阵（`runtime-current/`）

| 层 | 内容 | 版本 | 压缩 | 校验（哨兵位于各层 `.dshbox/layer-<name>.sha256`） |
|----|------|------|------|------|
| base (L0) | Debian 裁剪根（debootstrap+qemu，WSL2 构建） | `0.1.0` | zstd(19) | sha256 `ced7fac2…` |
| node (L1) | Node 运行时（绑 `/usr/local`） | **24.19.0** | zstd(19) | sha256 `a476cffa…` |
| android-side (L3) | proot/shmem/loader | `0.1.0` | zstd(19) | sha256 `03a794b4…` |
| **dsh**（L2，独立产品，**不属 bundle**） | DSH 包，由 APK 基线/「更新 DSH」管理 | 已装版本（仲裁决定） | gzip/zstd | 位于 `runtime-current/dsh/.dshbox/version` |

- **bundle 不含 DSH**：`runtime-profile.json` 仅含 base/node/android-side；`assembly = [base, node, android-side]`。
- **profile**：`compression: zstd / zstd_level: 19 / arch: arm64 / bundle version: 0.1.0`。

---

## 三、源码备份（`$PROJECT_ROOT\source_backup`）
每次改动后备份（排除 build/.gradle/.gradle-home/keystore/local.properties）：
`phaseA_layered_runtime_*`、`phaseB_dsh_*`、`phaseC_integrity_*`、`phaseD_layered_*`、`phaseD_zstd_bundle_*`、`phaseD_zstd_verify_*`（含内嵌 `assets/runtime`，约 203 MB）。

---

## 四、CHANGELOG 记录（`modified_source/CHANGELOG.md` 顶部，倒序）
- `2026-08-23 阶段D补 · zstd 全链路启用 + 自包含 release APK`
- `2026-08-22 阶段D · 宿主侧原生工具链与构建管线`
- `2026-08-22 阶段C · 运行环境完整性检测与保护`
- `2026-08-22 阶段B · DSH 独立产物 + 版本仲裁`
- `2026-08-22 阶段A · 运行环境包架构重构（分层 base/node/android-side，DSH 拆出）`

---

## 五、构建管线（WSL2/Linux + Windows）
- **WSL2/Linux**：`tools/pipeline_dryrun.sh`（预检）→ `tools/build_arm64_runtime_bundle.sh` → 产出 `build/dist/{base,node,android-side}*.tar.zst + .sha256 + runtime-profile.json`。
- **Windows 内嵌**：把上述文件解包到 `app/src/main/assets/runtime/`（已由本机 zip 解包完成），`assembleRelease` 打包。
- 构建与内嵌脚本：`tools/pack_runtime.sh`、`runtime-bundle/scripts/gen_profile.sh`、`tools/build_apk.sh`。详见 `docs/BUILD_RUNBOOK.md`。
- **DSH 独立**：`docs/L4-tools.md`（node-pty/arm64 预编译）；`runtime-bundle/scripts/patch_dsh_android.js`（按版本条件化）。

---

## 六、风险 / 降级 / 未完成
1. **真机回归（完整版通过）**：`dshapp-full-DSH-0.1.1-rc.2`（2026-08-23）全量验证**通过**——zstd 三层解压、DSH 层装配+版本仲裁、沙箱 online、DSH 进程+Web(127.0.0.1:3080) 就绪、node 24.19.0、user-data 未触碰、无崩溃/孤儿（见 CHANGELOG「阶段D补4」）。**待补的可选项**：`node -v` 明确输出复核、`forceStop` 无孤儿二次确认、DSH「更新 DSH」在线导入。
2. **DSH 平台正确性**：镜像 `--os=linux --cpu=arm64` 拉到 arm64 预编译（koffi/ripgrep ✓、sharp=wasm32、node-pty=arm64 prebuild ✓）；若个别原生在设备不兼容，按 §7.4 在 linux-arm64 环境重编。
3. **旧包勿用**：`20260822` 版 R8 重命名 zstd-jni 字段崩 `NoSuchFieldError`；请用 `full-DSH-0.1.1-rc.2`（当前正确版）。`20260823-fixed`（无 DSH）为旧基线。
4. **SIZE**：完整版 236.17 MB（运行环境+DSH 全内嵌）。可选瘦身：简化版 APK（只 DSH）或按 §7 镜像拉取不内嵌。
5. **npx 在线升级**：依赖网络；按 §7.6 可在 app「更新 DSH」接入多镜像择优下载预编译层。
5. 脚本未在沙箱 `bash -n`（沙箱禁 bash 信号管道），已人工复核；以 WSL2 内 `pipeline_dryrun.sh` 首跑为准。

---

## 七、待你反馈（验收）
```
真机:
  1) 分层解出+无DSH: 通过/失败(附现象)
  2) node -v:       通过/失败(输出)
  3) 沙箱启停+无孤儿: 通过/失败(附现象)
  4) user-data/.dsh 完好: 通过/失败(附现象)
  5) DSH版本/仲裁:   通过/失败(附现象)
```
收到通过结果后，我将把本目标标记完成，并定稿最终验收结论。
