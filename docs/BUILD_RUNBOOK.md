# 运行环境 bundle 构建 + 真机安装 · 执行手册（阶段 A→D）

> 本文档是**你（用户）在 WSL2/Linux 与真机上执行**的全部步骤；我在沙箱内无法执行 WSL2（E_ACCESS_DENIED）或真机测试，故只产出脚本+清单，由你实际运行并反馈结果。
>
> 目标：在 WSL2 构建「base/node/android-side」三层 bundle（无 DSH），在 Windows 用 `build_apk.sh`/Gradle 内嵌 → 产出自包含 APK → 真机覆盖安装验证。

---

## 0. 材料核对
- 项目源码在 Windows：`$PROJECT_ROOT\modified_source`。
- 我构建的代码已全绿（阶段 A→D），关键脚本：
  - `tools/pipeline_dryrun.sh`（预检）
  - `tools/build_arm64_runtime_bundle.sh`（调度器）
  - `runtime-bundle/build_base.sh / build_node.sh / build_android_side.sh`
  - `tools/pack_runtime.sh`、`runtime-bundle/scripts/gen_profile.sh`
- 最终 release APK：`$PROJECT_ROOT\apk_output\dshapp-phaseD-final-release.apk`（**尚未内嵌运行环境**——需先构建 bundle 再做第 ② 步内嵌）。

---

## ① WSL2 内构建分层 bundle

### 1.1 拷贝源码到 Linux 文件系统（建议，避免 /mnt/d 挂载性能/权限问题）
在 WSL2 内：
```bash
mkdir -p ~/dshbuild
cp -r /mnt/d/PROJECT/opencode/modified_source ~/dshbuild/   # 若项目挂在 /mnt/d
cd ~/dshbuild/modified_source
```
> 若你已把源码放在 Linux 侧，跳到 1.2 并 `cd` 到对应目录。

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
export NODE_VERSION=24.19.0
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
# 在 WSL2 内：
mkdir -p /mnt/d/PROJECT/opencode/modified_source/dist
cp build/dist/* /mnt/d/PROJECT/opencode/modified_source/dist/
```
> 这样 `build_apk.sh`（Windows）能检测到 `dist/runtime-profile.json + base.tar.*` 并**按分层嵌入**。

---

## ② Windows 内嵌 bundle（选择一种方式）

### 方式 A：`build_apk.sh`（推荐，自动检测分层/单体）
在 Windows PowerShell：
```powershell
$env:JAVA_HOME="D:\Software\STM32CubeMX\jre"
# build_apk.sh 会读取 modified_source\dist；有 runtime-profile.json 则分层嵌入，否则回退单体
$PROJECT_ROOT\modified_source\tools\build_apk.sh
```

### 方式 B：直接 `assembleRelease`（分层 assets 会被 Gradle 打包进 APK 的 assets/runtime）
把 `dist/` 里的 `runtime-profile.json + base/node/android-side.tar.gz + .sha256` 放到
`modified_source/app/src/main/assets/runtime/`，然后：
```powershell
$env:JAVA_HOME="D:\Software\STM32CubeMX\jre"
cmd /c "set JAVA_HOME=D:\Software\STM32CubeMX\jre&& $PROJECT_ROOT\modified_source\gradlew.bat -p $PROJECT_ROOT\modified_source --offline -Dorg.gradle.vfs.watch=false -Dorg.gradle.workers.max=4 -Pkotlin.compiler.execution.strategy=in-process :app:assembleRelease"
```
产出 `app\build\outputs\apk\release\app-release.apk`。
> 若你要的 DSH 也随包走，把 `assets/dsh/<version>.tar.gz(+.sha256)` 一并放入 `app/src/main/assets/dsh/`，`SandboxService.provisionBundledDsh()` 会在首启自动装配。

验证签名：
```powershell
$env:JAVA_HOME="D:\Software\STM32CubeMX\jre"
& "$HOME\AppData\Local\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs "app\build\outputs\apk\release\app-release.apk"
# 应显示 CN=DSHapp Dev
```

---

## ③ （可选）DSH 独立编排
- 方式 1：构建时把 DSH 层 `.tar.gz` 放 `app/src/main/assets/dsh/<version>.tar.gz`，首启自动装配。
- 方式 2：真机上在「设置 → DSH → 更新 DSH」离线导入 DSH 包 `.tar.gz`（版本仲裁：已装更新→保留，入站更新→替换）。
- npx 在线升级链路依赖网络，按文档在恢复网络后接入。

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
