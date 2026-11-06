# 运行环境 bundle 构建 + 真机安装 · 执行手册（阶段 A→D）

> 本文档描述**在 WSL2/Linux 与真机上**构建运行环境并验证的步骤。
>
> 目标：在 WSL2 构建「base/node/android-side」三层 bundle（无 DSH），再通过 Gradle 生成 System WebView 与 Gecko 两个自包含 APK → 真机覆盖安装验证。
>
> 路径一律用 `<项目根>` 表示源码所在目录；请按自己的实际位置替换。

---

## 0. 材料核对
- 项目源码在 Windows：`<项目根>`。
- 关键脚本：
  - `tools/pipeline_dryrun.sh`（预检）
  - `tools/build_arm64_runtime_bundle.sh`（调度器）
  - `runtime-bundle/build_base.sh / build_node.sh / build_android_side.sh`
  - `tools/pack_runtime.sh`、`runtime-bundle/scripts/gen_profile.sh`
- Release APK 产出在 `app/build/outputs/apk/{systemWebView,gecko}/release/`（构建阶段见第 ② 步）。

---

## ① WSL2 内构建分层 bundle

### 1.1 拷贝源码到 Linux 文件系统（建议，避免 /mnt 挂载性能/权限问题）
在 WSL2 内：
```bash
mkdir -p ~/dshbuild
cp -r <项目根> ~/dshbuild/          # 若项目挂在 /mnt/<盘符> 下
cd ~/dshbuild
```
> 若源码已在 Linux 侧，跳到 1.2 并 `cd` 到对应目录。

### 1.2 安装构建前置
```bash
sudo apt-get update
sudo apt-get install -y debootstrap qemu-user-static zstd tar
```
（`zstd` 缺装会自动降级 gzip，见第 ④ 节风险。）

### 1.3 预检（先跑这个，提前暴露缺依赖）
```bash
bash tools/pipeline_dryrun.sh
```
应显示 `[ok] debootstrap`、`[ok] qemu-user-static`、`[ok] zstd` 且无 `[FAIL]`。

### 1.4 构建
```bash
export NODE_VERSION=24.22.2
export BUNDLE_VERSION=0.1.0
export ZSTD_LEVEL=19            # zstd 存在时用；不存在则 pack 脚本自动 gzip
bash tools/build_arm64_runtime_bundle.sh   # 默认 trixie arm64
```
产物在 `build/dist/`：
```
base.tar.gz  node.tar.gz  android-side.tar.gz   （各自携带 .sha256）
runtime-profile.json
```
> 注意：此 bundle **不含 DSH**（DSH 是独立产物，见第 ③ 步）。

### 1.5 把产物回拷到 Windows
```bash
# 在 WSL2 内（把 <项目根> 换成 Windows 侧的源码目录在 WSL 下的挂载路径）：
mkdir -p <项目根>/dist
cp build/dist/* <项目根>/dist/
```
> 这样 `build_apk.sh`（Windows）能检测到 `dist/runtime-profile.json + base.tar.*` 并**按分层嵌入**。

---

## ② 构建两个 APK 变体

源码构建需要 JDK 21、Android SDK 37.2，以及源码目录上一级 `runtime/android-assets/`
中的运行层：

```text
runtime/android-assets/runtime/android-side.tar.zst(+.sha256)
runtime/android-assets/dsh/<dsh-version>.tar.zst(+.sha256)
```

`android-side` 从 Release 发布的环境整包中提取。`base`、`node` 不进入 APK；缺失的
运行层在 App 内通过在线获取或离线导入安装。

在源码根目录执行：

```powershell
$env:JAVA_HOME="<JDK21 路径>"
$env:ANDROID_HOME="<Android SDK 路径>"
.\gradlew.bat :app:assembleSystemWebViewRelease :app:assembleGeckoRelease
pwsh tools/assemble_release_apks.ps1
```

归档脚本默认在源码根目录的 `apk/` 下生成：

```text
apk/dshbox-v<version>-system-release.apk
apk/dshbox-v<version>-gecko-release.apk
```

Linux/WSL 可执行 `tools/build_linux.sh` 完成两个 Debug、两个 Release、全量 JVM
测试和两个变体的 Lint。已有运行层位于 `dist/` 时，`tools/build_apk.sh` 也可先
内嵌运行层，再构建两个变体。

验证两个 APK 的包名、版本号、共同签名与内嵌资产：

```powershell
pwsh tools/verify_release_apk_contract.ps1 `
  -SystemApk apk/dshbox-v<version>-system-release.apk `
  -GeckoApk apk/dshbox-v<version>-gecko-release.apk
```

---

## ③ DSH 层装配
- 开发构建可把 DSH 层 `.tar.zst` 与 `.sha256` 放到 `runtime/android-assets/dsh/`，首启自动装配。
- 真机可在「设置 → DSH → 更新 DSH」离线导入同格式的 DSH 层；版本仲裁为已装更新时保留，入站更新时替换。
- 在线更新链路依赖网络与上游发布状态。

---

## ④ 真机回归清单（Android 16 / arm64，覆盖安装）
1. 首启：`runtime-current/{base,node,android-side}` + `runtime-profile.json` 解出；bundle 内**无 DSH**（若无 DSH 基线则 DSH 未启动，属预期）。
2. 终端 `root@` 提示符；`node -v`/`npm -v` 可用（验证 node 层经 `/usr/local` 挂载）。
3. 沙箱启停正常；`forceStop()` 后无 proot 孤儿（验证 killAll）。
4. 重启后 `user-data/`、`user-data/.dsh` **完好**（验证受保护目录/未触碰）。
5. DSH（若装配）版本在「设置 → DSH → DSH 版本」显示；导入更旧包应被保留、更新被替换、previous 只有一份。
6. 逐项反馈：`[通过/失败] <条目>`；失败附现象与 logcat 片段。

---

## ⑤ 风险 / 降级（务必知晓）
- **zstd**：**已启用**——构建侧 `zstd`(19)，Android 端经 `zstd-jni`（本地 `classes.jar` + `arm64-v8a/libzstd-jni-1.5.7-15.so`，`BundleManager` 用 `ZstdInputStream` 解压 `.tar.zst`）。若某层用 gzip 制作，同样可解（gzip→commons-compress）。
- **bundle 不含 DSH**：首启 DSH 是否可用取决于第 ③ 步是否装配 DSH 层。
- 脚本未在沙箱 `bash -n`（bash 无法创建信号管道），已人工复核；以 1.3 `pipeline_dryrun.sh` 首跑为准。

---

## ⑥ 反馈格式（直接回我）
```
WSL2 dry-run:  [通过/失败] <缺依赖可附>
WSL2 build:    [通过/失败] <产物列表或报错>
bundle 内嵌:   [通过/失败]
真机:
  1) 分层解出+无DSH: 通过/失败(<现象>)
  2) node -v:       通过/失败(<输出>)
  3) 沙箱启停+无孤儿: 通过/失败(<现象>)
  4) user-data完好: 通过/失败(<现象>)
  5) DSH版本/仲裁:  通过/失败(<现象>)
```

---

## ⑦ 真机 adb 快速核对（可选，加快回归）
包名 `com.dshbox.app`；应用文件目录 `files/` = `/data/data/com.dshbox.app/files`。
```bash
# 1) 确认分层运行环境已解出 + 每层哨兵（zstd 解压成功的判据）
adb shell ls -la /data/data/com.dshbox.app/files/runtime/runtime-current/
adb shell cat  /data/data/com.dshbox.app/files/runtime/runtime-current/runtime-profile.json
adb shell find /data/data/com.dshbox.app/files/runtime/runtime-current \
  -name "layer-*.sha256" -exec sh -c 'echo "$1:"; cat "$1"' _ {} \;
# 期望：base/node/android-side 目录 + runtime-profile.json + 三个 layer-*.sha256（profile 值）
# 且 profile 内 compression 为 zstd / zstd_level 19

# 2) 保护目录未被破坏
adb shell ls -la /data/data/com.dshbox.app/files/user-data/          # 应完好
adb shell ls -la /data/data/com.dshbox.app/files/user-data/.dsh/     # 若存在应完好

# 3) 版本矩阵（node 层）
adb shell cat /data/data/com.dshbox.app/files/runtime/runtime-current/node/usr/local/bin/node --version 2>/dev/null || echo "见终端 node -v"
```
> 以上 `adb` 需 root/可读 app 私有目录；若无权限，用「设置 → 诊断」或终端内 `node -v` 替代。
