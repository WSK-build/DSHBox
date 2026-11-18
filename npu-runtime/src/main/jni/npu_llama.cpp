//=============================================================================
// NPU 推理桥（llama.cpp + Qualcomm HTP）
//
// 三个关键点，都有实测依据：
//
// 1) ★ 必须先设 ADSP_LIBRARY_PATH = nativeLibraryDir。
//    DSP 侧 skel（libggml-htp-v8x.so）不是被 Android linker 加载，而是由
//    adsprpc 按这个变量去找。不设时表现为「dlopen 成功、配置解析成功、
//    推理时挂住且 logcat 一条 fastrpc 日志都没有」。设上之后：
//      Successfully opened file .../lib/arm64/libggml-htp-v81.so
//      dspqueue_create: created Queue 0 ... for domain 3
//
// 2) 用**真实头文件**（llama.h / ggml-backend.h），不手写函数指针或结构体。
//    手写过两次都错：llama_model_load 真名是 llama_model_load_from_file、
//    而 llama_model_params 的字段偏移不是猜得出来的。
//
// 3) 不把 libllama.so 做成硬链接依赖（全部 dlsym）。
//    没构建原生库时本模块优雅降级，App 仍能启动。
//
// 构建前置：scripts/build-llama-htp.sh 产出 libllama.so / libggml*.so /
// libggml-htp-v8x.so 与 include/*.h。
//=============================================================================
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "llama.h"
#include "ggml-backend.h"

#define TAG "NpuLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

double now_ms() {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

// libllama.so 里我们真正用到的入口，dlsym 后填进来。
// 类型直接用 llama.h 里的原型，编译器会核对签名。
struct Api {
    void *h = nullptr;
    decltype(&llama_backend_init)          backend_init = nullptr;
    decltype(&llama_model_load_from_file)  model_load   = nullptr;
    decltype(&llama_model_free)            model_free   = nullptr;
    decltype(&llama_init_from_model)       ctx_new      = nullptr;
    decltype(&llama_free)                  ctx_free     = nullptr;
    decltype(&llama_decode)                decode       = nullptr;
    decltype(&llama_tokenize)              tokenize     = nullptr;
    decltype(&llama_model_get_vocab)       get_vocab    = nullptr;
    decltype(&llama_vocab_n_tokens)        vocab_size   = nullptr;
    decltype(&llama_get_logits_ith)        logits       = nullptr;
    decltype(&llama_model_default_params)  model_params = nullptr;
    decltype(&llama_context_default_params) ctx_params  = nullptr;
    decltype(&llama_batch_get_one)         batch_one    = nullptr;
};

Api  g;
bool g_probed = false;

bool ensure_api() {
    if (g_probed) return g.h != nullptr;
    g_probed = true;

    g.h = dlopen("libllama.so", RTLD_NOW | RTLD_GLOBAL);
    if (!g.h) {
        LOGE("dlopen libllama.so 失败: %s", dlerror());
        LOGE("提示：原生库需先跑 scripts/build-llama-htp.sh，产物会放进 jniLibs");
        return false;
    }
#define LOAD(field, sym)                                         \
    g.field = (decltype(g.field))dlsym(g.h, #sym);               \
    if (!g.field) { LOGE("缺少符号 %s", #sym); return false; }
    LOAD(backend_init, llama_backend_init)
    LOAD(model_load,   llama_model_load_from_file)
    LOAD(model_free,   llama_model_free)
    LOAD(ctx_new,      llama_init_from_model)
    LOAD(ctx_free,     llama_free)
    LOAD(decode,       llama_decode)
    LOAD(tokenize,     llama_tokenize)
    LOAD(get_vocab,    llama_model_get_vocab)
    LOAD(vocab_size,   llama_vocab_n_tokens)
    LOAD(logits,       llama_get_logits_ith)
    LOAD(model_params, llama_model_default_params)
    LOAD(ctx_params,   llama_context_default_params)
    LOAD(batch_one,    llama_batch_get_one)
#undef LOAD
    LOGI("libllama.so 已加载");
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_dshbox_app_npu_NpuLlama_nativeAvailable(JNIEnv *, jclass) {
    return ensure_api() ? JNI_TRUE : JNI_FALSE;
}

/**
 * 初始化：设 ADSP_LIBRARY_PATH，加载后端，枚举设备。
 * @return 打包的 long[]: { ok, dev_count, htp_present }
 */
JNIEXPORT jlongArray JNICALL
Java_com_dshbox_app_npu_NpuLlama_nativeInit(JNIEnv *env, jclass, jstring jLibDir) {
    jlong r[3] = {0, 0, 0};
    const char *dir = env->GetStringUTFChars(jLibDir, nullptr);
    if (dir && *dir) {
        setenv("ADSP_LIBRARY_PATH", dir, 1);   // ★ 见文件头 1)
        LOGI("ADSP_LIBRARY_PATH = %s", dir);
    }
    if (ensure_api()) {
        g.backend_init();
        ggml_backend_load_all_from_path(dir);
        size_t n = ggml_backend_dev_count();
        r[0] = 1; r[1] = (jlong)n;
        for (size_t i = 0; i < n; i++) {
            ggml_backend_dev_t d = ggml_backend_dev_get(i);
            const char *nm = d ? ggml_backend_dev_name(d) : nullptr;
            LOGI("dev[%zu] = %s", i, nm ? nm : "(null)");
            if (nm && strstr(nm, "HTP")) r[2] = 1;
        }
    }
    if (dir) env->ReleaseStringUTFChars(jLibDir, dir);
    jlongArray a = env->NewLongArray(3);
    env->SetLongArrayRegion(a, 0, 3, r);
    return a;
}

/**
 * 生成。返回 long[]: { ok, n_tokens, gen_ms, tok_s_x100, load_ms }
 * 用整数回传，避免在 JNI 里构造 Java 对象。
 */
JNIEXPORT jlongArray JNICALL
Java_com_dshbox_app_npu_NpuLlama_nativeGenerate(JNIEnv *env, jclass,
                                                jstring jModel, jstring jPrompt,
                                                jint nGpuLayers, jint nPredict) {
    jlong r[5] = {0, 0, 0, 0, 0};
    if (!ensure_api()) { jlongArray a = env->NewLongArray(5); env->SetLongArrayRegion(a,0,5,r); return a; }

    const char *model  = env->GetStringUTFChars(jModel, nullptr);
    const char *prompt = env->GetStringUTFChars(jPrompt, nullptr);

    struct llama_model_params mp = g.model_params();
    mp.n_gpu_layers = nGpuLayers;

    double t0 = now_ms();
    struct llama_model *m = g.model_load(model, mp);
    r[4] = (jlong)(now_ms() - t0);
    LOGI("model_load(ngl=%d) → %p (+%lld ms)", nGpuLayers, (void*)m, (long long)r[4]);
    if (!m) goto done;

    {
        struct llama_context_params cp = g.ctx_params();
        cp.n_ctx = 512;
        cp.n_batch = 512;
        struct llama_context *ctx = g.ctx_new(m, cp);
        if (!ctx) { LOGE("ctx 创建失败"); g.model_free(m); goto done; }

        const struct llama_vocab *v = g.get_vocab(m);
        llama_token toks[64];
        int32_t n = g.tokenize(v, prompt, (int32_t)strlen(prompt), toks, 64, true, false);
        LOGI("tokenize → %d", n);

        if (n > 0) {
            struct llama_batch b = g.batch_one(toks, n);
            if (g.decode(ctx, b) == 0) {
                double g0 = now_ms();
                int ok = 0;
                const int want = nPredict > 0 ? nPredict : 16;
                for (int i = 0; i < want; i++) {
                    float *lg = g.logits(ctx, -1);
                    if (!lg) break;
                    const int nv = g.vocab_size(v);
                    int best = 0; float bv = lg[0];
                    for (int k = 1; k < nv; k++) if (lg[k] > bv) { bv = lg[k]; best = k; }
                    llama_token next = best;
                    struct llama_batch nb = g.batch_one(&next, 1);
                    if (g.decode(ctx, nb) != 0) break;
                    ok++;
                }
                double dt = now_ms() - g0;
                r[0] = 1; r[1] = ok; r[2] = (jlong)dt;
                r[3] = dt > 0 ? (jlong)(ok * 100000.0 / dt) : 0;
                LOGI("decode %d tok (+%.0f ms) = %.1f tok/s", ok, dt, ok * 1000.0 / dt);
            }
        }
        g.ctx_free(ctx);
        g.model_free(m);
    }

done:
    if (model)  env->ReleaseStringUTFChars(jModel, model);
    if (prompt) env->ReleaseStringUTFChars(jPrompt, prompt);
    jlongArray a = env->NewLongArray(5);
    env->SetLongArrayRegion(a, 0, 5, r);
    return a;
}

}  // extern "C"
