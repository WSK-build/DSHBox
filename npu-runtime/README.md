# npu-runtime — 端侧 NPU 推理（Hexagon HTP / QNN / Genie）

在 DSHBox 内直接调用高通 NPU 跑 LLM 推理的模块。所有结论都来自
**iQOO 15（V2505A）/ SM8850 / HTP v81 / Android 17** 的实测，不是文档转述。

## 现状：能跑，但慢

| 模型 | ctx-bin | 生成速度 |
|---|---|---|
| Qwen3-4B w4a16 | 4 段 / 3.06 GB | **≈5.1 tok/s** |
| Llama-3.2-3B w4a16 | 3 段 / 2.64 GB | **≈7.2 tok/s** |

瓶颈是**带宽**：实测有效带宽 15.6–19.0 GB/s，而 LPDDR5X 峰值约 76 GB/s
（利用率仅 20–25%）。这说明还有优化空间，但不在运行时配置层——
需要用更好的 ctx-bin 编译（QAIRT 生成质量）来提升，运行时参数已探到底。

## 为什么需要这个模块（与其他方案对比）

同机同模型的实测（详见 DSHBox#99 讨论与 `docs/`）：

| 方案 | 3B decode | 需要装第三方 App | 占用主 CPU | 备注 |
|---|---|---|---|---|
| **本模块（QNN/Genie）** | **7.2 tok/s** | ❌ 不需要 | 极低 | 直接调设备上的 QNN/Genie |
| llama.cpp + `libggml-htp-v81.so` | 22.4 tok/s | ✅ 需要（或需自编 llama.cpp） | 极低 | 需要 Hexagon SDK 编译 hexagon 后端 |
| MNN CPU 0.8B | 134.5 tok/s | ❌（可内置） | 高（吃满多核） | 能力弱，适合窄任务 |
| MNN CPU 3B | 未验证 | ❌ | 高 | 模型导出缺元数据 |

**所以本模块的定位不是「最快」，而是「不依赖第三方 App、不占主 CPU」**：

- 设备上没有任何现成 NPU 运行时（PocketOrca 之类）时，这是唯一的内置路径；
- 主进程在做重活（UI、沙箱、Node）时，NPU 只占 0.1–0.2% CPU，比 CPU 推理省得多；
- 代价是吞吐只有 llama.cpp HTP 的 1/3 —— 这个取舍写在代码注释里，不藏着。

如果你只追求最快且能接受多装一个 App，llama.cpp HTP 更合适；
如果你要求零外部依赖，这条路是当前唯一可用的。

## 如何获取模型

本模块**不分发模型**（3 GB 级 ctx-bin 不适合进仓库）。需要：

1. 找一个与设备 QAIRT 版本匹配、且按本机 SoC 编译的 ctx-bin 包。
   实测可用的一例：`Kyudan/Qwen3-Instruct-QAIRT-2.51-v81`（Qwen3-4B w4a16，4 段）
   与 `Llama-3.2-3B w4a16 v81`（3 段）。
2. 放进 `<filesDir>/npu/models/<modelId>/`，并写 `manifest.json`（见下方「目录约定」）。
   **`ctxBinFiles` 必须写精确字节数**，否则预检会拒绝启动。
3. 首次调用 `NpuEngine.generate()` 会自动生成 `cfg32.json` / `htp_backend_ext_config.json`。

**版本硬约束**：ctx-bin 的编译版本不能高于设备运行时版本（实测 QAIRT 2.42 的包在 2.40
运行时上 `GenieDialog_create` 直接返回 -1）。`NpuPreflight` 会提前拦，但前提是
`manifest.json` 里填了 `qairtVersion`。

## 用法

```kotlin
val engine = NpuEngine(context, modelId = "qwen3-4b")
if (!engine.isAvailable()) {
    // 设备没有 Qualcomm 运行时 → 回退 CPU 路径，不要报错
    return
}
val outcome = engine.generate("写一首关于秋天的短诗", maxTokens = 64)
if (outcome.status == 0) {
    println(outcome.text)   // outcome.tokens / outcome.elapsedMs
}
```

`generate()` 会阻塞，请在工作线程调用。

## 目录约定

```
<filesDir>/npu/models/<modelId>/
  ├─ manifest.json                    模型清单
  │    {"ctxBinFiles": {"part1_of_4.bin": 123456, ...},
  │     "qairtVersion": "2.40.0",
  │     "socModel": 87, "dspArch": "v81"}
  ├─ part1_of_4.bin …                 ctx-bin 各段
  ├─ cfg32.json                       自动生成
  └─ htp_backend_ext_config.json      自动生成
```

`manifest.json` 的 `ctxBinFiles` 必须是**精确字节数**，不能只写文件名。
只校验「文件存在」无法区分「完整的 4B」与「下载了一半的 3B」——
历史上正因此把 4B 的运行结果误记成了 1B。

## 三条硬约束（都是实测踩出来的）

### 1. ctx-bin 编译版本不能高于运行时

`GenieDialog_create` 对**前向不兼容**的 ctx-bin 直接返回 `-1`（~0.6 s 快速失败）。
实测：QAIRT 2.42 编译的包在 2.40 运行时上失败；同版本即可跑。

预检在 [NpuPreflight.checkVersionCompatibility] 里做，不通过就不进 DSP。

注意：**SDK 头文件里的 `QNN_API_VERSION_*` 宏（2.27）≠ 设备运行时（实测 2.40）**，
所以版本必须运行时查。查询路径是 `QnnInterface_getProviders()` 返回的
`QnnInterface_t.apiVersion.coreApiVersion`——`libQnnSystem.so` 并没有导出
`QnnBackend_getApiVersion`，别去 dlsym 那个符号。

### 2. 独立进程 + 看门狗

Genie 在 DSP 上的会话是独占资源，且 `GenieDialog_create` **会卡死**
（建图 + 加载数 GB ctx-bin 过程中偶发永久阻塞，无自愈路径，
`stop/start cdsprpcd` 需要 root）。

唯一恢复手段是**杀掉持有会话的进程**（内核关闭 fastrpc fd →
DSP 侧回收 User PD → 立刻恢复）。因此推理跑在 `:npu` 独立进程：

- 杀它只损失一次推理，主 App 不受影响；
- 看门狗靠 `:npu` 进程**回传心跳**（每 500 ms）判断卡死；
- create 阶段一个 token 都没有，**只能靠心跳**区分「慢」与「死」；
- 杀进程后需 **8 秒冷却期**再重启，否则新进程会撞上旧 PD 的清理窗口。

### 3. `poll` 必须是 false

`"poll": true` → **0.3 char/s** 且 `query` 返回 `-6`（22× 差距）。
`"poll": false` → 正常，`query` 返回 0。

其它已固化在 [NpuConfigBuilder] 里的实测结论：

| 字段 | 值 | 依据 |
|---|---|---|
| `rpc_control_latency` | 100 | 5 时可复现挂死；20/50/100 无差异，取无风险值 |
| `use-mmap` | true | false 无收益（4.76–5.01 vs 4.90–5.23 tok/s） |
| `n-threads` | 3 | 4 无收益 |
| `cpu-mask` | 不设 | 0xf0/0xc0/0x80 交错 A/B 无增益（单次 12.49 是噪声） |
| `perf_profile` | burst | 官方最高功率档 |
| `kv-dim` / `rope-theta` | 128 / 5000000 | 实测值 |

## 为什么不打包厂商二进制

本模块默认**不**随 APK 分发厂商二进制，但这里有一个必须说明的例外：

| 库 | 设备上有吗 | 本模块怎么处理 |
|---|---|---|
| `libcdsprpc.so` | ✅ 在 `/vendor/etc/public.libraries.txt` | **绝不打包**——打了会遮蔽真库（实测 `dlopen "libvndksupport.so"` 失败） |
| `libQnnHtp.so` / `libQnnSystem.so` | ✅ `/odm/lib64/npuhw/qnnv3/` | 走 `dlopen(soname)` 用设备那份 |
| **`libGenie.so`** | ❌ **没有**（实测 `/vendor`、`/odm` 全无） | **必须由使用方自备**，见下 |

### libGenie.so 怎么来（重要）

Genie 不在 AOSP 也不在任何 vendor 分区里，只存在于 **QAIRT SDK**：

```
qairt/<version>/lib/aarch64-android/libGenie.so      # 约 11 MB
```

两条路，二选一：

1. **打进 APK**：放到 `npu-runtime/src/main/jniLibs/arm64-v8a/libGenie.so`。
   注意其许可（Qualcomm 专有），自行确认分发合规性。
2. **运行时提供**：把库放到应用私有目录，并设环境变量
   `DSH_NPU_LIBDIR=<那个目录>`。native 层会先试 soname，失败再试
   `<DSH_NPU_LIBDIR>/libGenie.so`。

两者都没有时，`nativeRun` 会返回明确错误（而不是只抛一个 "not found"）：

```
dlopen libGenie.so 失败: ...
提示：本机 /vendor /odm 下没有 libGenie.so，需从 QAIRT SDK 取 ...
```

**版本要匹配设备运行时**：实测设备 QNN 运行时 API 2.40，ctx-bin 若用 QAIRT 2.42
编译则 `GenieDialog_create` 直接 -1。Genie 库本身也应与 ctx-bin 的 QAIRT 版本同代。

## 内存门槛

`MemAvailable` 低于 **4 GiB** 直接拒绝进入 DSP；低于 6 GiB 告警。
实测 3 GB ballast 下 4B 仍能跑（5.34 tok/s），但加载数 GB 权重时
LMKD 回收 mmap 页 → DSP 侧 SMMU 缺页 → 永久挂起。

## 许可

本模块只含自有代码与配置生成逻辑，不含 Qualcomm / 其它第三方二进制。
QNN/Genie 运行时由设备提供，或经 DSHBox 既有的在线运行时资源通道下发。
