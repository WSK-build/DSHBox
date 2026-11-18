# jniLibs/arm64-v8a/

本目录**不入库**实际的原生库。运行：

```bash
bash npu-runtime/scripts/build-llama-htp.sh
```

会把下面这些放进 `arm64-v8a/`：

| 文件 | 作用 | 体积 |
|---|---|---|
| `libllama.so` | llama.cpp 主库 | ~42 MB |
| `libggml.so` / `libggml-base.so` / `libggml-cpu.so` | ggml 核心 + CPU 后端 | ~13 MB |
| `libggml-hexagon.so` | HTP host 侧后端 | ~9.5 MB |
| `libggml-htp-v8x.so` | **DSP 侧 skel**（按设备 HTP 版本，可只留一个） | ~1 MB/个 |

体积原因（合计 100 MB+）与 Qualcomm 二进制许可，是这个目录不进版本控制的两个理由。
没有这些库时 `NpuLlama.isAvailable()` 返回 false，App 正常降级，不会启动失败。
