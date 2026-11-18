# npu-runtime — 端侧 NPU 推理（llama.cpp + Qualcomm Hexagon HTP）

在 App **进程内**跑本地大模型，把计算卸载到 Hexagon DSP（NPU）。
不依赖任何第三方 App，也不需要 root / shell / 无线调试。

实测设备：iQOO 15（SM8850，Hexagon **HTP v81**，Android 17）。

## 为什么这个方案可行（而 QNN/Genie 不行）

同机对照过两条路，差别在**依赖链**：

| | llama.cpp hexagon 后端 | QNN + Genie |
|---|---|---|
| 静态依赖 | `liblog/libdl/libggml-base/libm/libc` | 22 个库 |
| 运行时 `dlopen` | **只有 `libcdsprpc.so`** | `libQnnHtp` → `libhidlbase` → … → linker |
| App 域 | ✅ 可行 | ❌ 需打包 20 个 vendor 库，且仍失败 |

关键机制是 Android 12+ 的 **`<uses-native-library>`**（见
`src/main/AndroidManifest.xml`）：声明后系统把那些非 public 的系统库放进 App 的
linker namespace。**不用把 vendor 库打进 APK** —— 打包会遮蔽真库、多 38 MB、
并在 Android 15+ 上撞 16 KB 对齐检查。

实测对照（同一份 `libcdsprpc.so`）：

```
不声明 → dlopen 失败:
  library "libvndksupport.so" not found:
  needed by .../libhidlbase.so in namespace clns-9

声明后 → dev[0]=HTP0 / dev[1]=CPU，模型加载 + decode 正常，
        logcat 出现:
  Successfully opened file .../lib/arm64/libggml-htp-v81.so
  dspqueue_create: created Queue 0 ... for domain 3
```

## 另一个必须做对的地方：ADSP_LIBRARY_PATH

DSP 侧 skel **不是被 Android linker 加载的**，而是 adsprpc 按
`ADSP_LIBRARY_PATH` 去 `nativeLibraryDir` 找。不设时表现为「一切成功、推理时挂住、
logcat 一条 fastrpc 日志都没有」。`nativeInit()` 里已经自动设置，不需要调用方操心。

## 构建

原生库（100 MB+）**不进仓库**，用脚本产出：

```bash
bash npu-runtime/scripts/build-llama-htp.sh
```

脚本用 llama.cpp 官方 CI 的配方：在
`ghcr.io/snapdragon-toolchain/arm64-android:v0.8` 容器里 `cmake --preset
arm64-android-snapdragon-release`。**不要自己拼 `HEXAGON_SDK_ROOT`** ——
SDK 的 `hexagon_fun.cmake` 会因变量缺失直接 FATAL_ERROR。

体积大 + skel 是 Qualcomm 专有二进制，是不入库的两个理由。

## 用法

```kotlin
if (!NpuLlama.isAvailable()) return useCpuOrCloud()      // 无库/无 DSP 时优雅降级

val init = NpuLlama.init(context.applicationInfo.nativeLibraryDir)
if (!init.htpPresent) return useCpuOrCloud()             // 设备没有 HTP

val r = NpuLlama.generate(
    modelPath = "$filesDir/models/qwen2.5-0.5b-q4km.gguf",
    prompt = "用一句话说明什么是端侧大模型",
    nGpuLayers = 99,      // 全部层卸载到 DSP
    nPredict = 32,
)
if (r.ok) Log.i(TAG, "${r.tokensPerSecond} tok/s")
```

## 已知限制

- **只测过一台设备**（SM8850 / HTP v81）。其它 HTP 版本需要对应的
  `libggml-htp-v8x.so`；脚本会产出 v68–v85 全套，可按设备裁剪。
- **模型不随仓库分发**，需要自备 GGUF（Q4_K_M 及以上量化）。
- DSP 会话可能卡死：`NpuInferenceService` 跑在独立进程（`:npu`），
  杀进程即可恢复（内核关闭 fastrpc fd → DSP 回收 User PD）。
- App 域的实际 tok/s 与宿主 CPU 负载有关；后台 cgroup 只给 0–5 号核，
  前台 `top-app` 才有全 8 核。
