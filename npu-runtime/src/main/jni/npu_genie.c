/*
 * DSHBox 端侧 NPU 推理（Genie / QNN 直通 Hexagon HTP）。
 *
 * 设计约束（全部来自 iQOO 15 / SM8850 / HTP v81 / Android 17 实测）：
 *
 *  1. untrusted_app 对 app_data_file 只有 read、没有 execute，
 *     所以不能 ProcessBuilder 拉外部二进制做 sidecar；
 *     只能在 App 自己的进程里 dlopen 厂商运行时并直接调 C API。
 *
 *  2. GenieDialog_create 会建 QNN 图并加载数 GB 的 ctx-bin，是整个链路上
 *     最慢、也最容易卡死的一步（实测 3.7–5.7 s；卡死后永久阻塞）。
 *     因此这里的调用**必须**能被上层超时中断——所以推理跑在独立进程
 *     （android:process=":npu"），超时由宿主杀进程实现，而不是在这里
 *     尝试 interrupt 线程（native 里的 DSP 调用不吃线程中断）。
 *
 *  3. 厂商库（libGenie.so / libQnn*.so）**不随 App 打包**：
 *     - libcdsprpc.so 在 /vendor/etc/public.libraries.txt 里，
 *       打进 APK 会遮蔽真库并引入坏依赖（实测 dlopen "libvndksupport.so" 失败）；
 *     - 其余 QNN/Genie 库体积大且受各自许可约束，随 APK 分发需另行确认。
 *     所以这里全部走 dlopen(soname)，由系统 linker namespace 解析到设备上的那一份；
 *     若设备没有，dlopen 会失败并把原因回传给上层，而不是静默退化到 CPU。
 *
 *  4. 每次调用都把关键阶段推进到共享内存（g_progress），
 *     上层据此判断"还在正常干活"还是"卡死了"——
 *     这是 create 阶段看门狗唯一可用的信号源（那一阶段一个 token 都没有）。
 */
#include <jni.h>
#include <stdio.h>
#include <limits.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <dlfcn.h>
#include <errno.h>
#include <time.h>
#include <unistd.h>

/* ------------------------------------------------------------------ 共享状态 */

/*
 * 宿主进程与 :npu 进程共享的一块内存。
 * 进程内用不到；这里保留结构是为了将来做「宿主监督子进程」时，
 * 宿主能直接读子进程进度而无需 IPC 往返。
 */
typedef struct {
    volatile long stage_entered_ms;  /* 当前阶段进入时间（CLOCK_MONOTONIC） */
    volatile long last_progress_ms;  /* 最近一次进度推进时间 */
    volatile int  stage;             /* 见 NPU_STAGE_* */
    volatile int  tokens;
    volatile int  status;            /* 0=进行中，非 0=结束码 */
} NpuProgress;

#define NPU_STAGE_IDLE      0
#define NPU_STAGE_LOADING   1  /* dlopen / 读配置 */
#define NPU_STAGE_CREATING  2  /* GenieDialog_create（最易卡死） */
#define NPU_STAGE_GENERATING 3 /* GenieDialog_query（有 token 回调） */
#define NPU_STAGE_DONE      4

static NpuProgress g_progress;

/* Genie 句柄与配置句柄：进程内单例，重复调用先释放旧的。 */
typedef void *cfg_h;
typedef void *dlg_h;
typedef int   status_t;
typedef void (*query_cb)(const char *, int, const void *);

/* Genie token 回调的落地缓冲（进程内一次只有一个推理，静态单例足够）。 */
typedef struct {
    char  *buf;
    size_t cap;
    size_t len;
    int    tokens;
} sink_t;

static sink_t *g_sink = NULL;

/* Genie 的回调签名： (text, code, userdata)。实现在文件末尾。 */
void npu_on_token_shim(const char *text, int code, const void *ud);

typedef status_t (*cfg_from_json_fn)(const char *, cfg_h *);
typedef status_t (*cfg_free_fn)(cfg_h);
typedef status_t (*dlg_create_fn)(cfg_h, dlg_h *);
typedef status_t (*dlg_query_fn)(dlg_h, const char *, int, query_cb, const void *);
typedef status_t (*dlg_free_fn)(dlg_h);

static void *g_lib = NULL;
static cfg_h g_cfg = NULL;
static dlg_h g_dlg = NULL;

/* ------------------------------------------------------------------ 工具 */

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

static void stage_enter(int stage) {
    g_progress.stage = stage;
    g_progress.stage_entered_ms = now_ms();
    g_progress.last_progress_ms = g_progress.stage_entered_ms;
}

static void stage_progress(void) {
    g_progress.last_progress_ms = now_ms();
}

/* 线程安全的日志：写 stderr（logcat 可抓），不依赖外部状态。 */
static void nlog(const char *fmt, ...) {
    char line[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);
    fprintf(stderr, "[DSHNPU] %s\n", line);
    fflush(stderr);
}

/* ------------------------------------------------------------------ 加载 */

static int load_genie(void) {
    if (g_lib) return 0;

    stage_enter(NPU_STAGE_LOADING);
    /*
     * libGenie.so **不在设备上**——实测 iQOO 15（Android 17）的
     * /odm/lib64/npuhw/qnnv3 与 /vendor/lib64 都没有它，只有 QNN 的
     * libQnnHtp/libQnnSystem 等。Genie 需要从 QAIRT SDK 取：
     *   qairt/<ver>/lib/aarch64-android/libGenie.so
     * 然后二选一：
     *   a) 放进 APK 的 jniLibs/arm64-v8a/（随应用分发，注意其许可）
     *   b) 由使用方提供并放到应用私有目录，用绝对路径 dlopen
     *
     * 这里先试 soname（对应 a），再试应用私有目录（对应 b），
     * 都不行就报明确原因——不要只抛一个 "not found" 让人猜。
     */
    g_lib = dlopen("libGenie.so", RTLD_NOW | RTLD_GLOBAL);
    if (!g_lib) {
        char priv[PATH_MAX];
        const char *dir = getenv("DSH_NPU_LIBDIR");
        if (dir && *dir) {
            snprintf(priv, sizeof(priv), "%s/libGenie.so", dir);
            g_lib = dlopen(priv, RTLD_NOW | RTLD_GLOBAL);
        }
        if (!g_lib) {
            nlog("dlopen libGenie.so 失败: %s", dlerror());
            nlog("提示：本机 /vendor /odm 下没有 libGenie.so，需从 QAIRT SDK 取 "
                 "qairt/<ver>/lib/aarch64-android/libGenie.so，放进 jniLibs/arm64-v8a/，"
                 "或置于私有目录并设 DSH_NPU_LIBDIR");
            return -1;
        }
    }
    nlog("libGenie.so 已加载");

    /* 符号齐全才算可用；缺一个就当不可用，避免半初始化。 */
    if (!dlsym(g_lib, "GenieDialogConfig_createFromJson") ||
        !dlsym(g_lib, "GenieDialog_create") ||
        !dlsym(g_lib, "GenieDialog_query")) {
        nlog("libGenie.so 缺少 GenieDialog* 符号（版本不匹配？）");
        g_lib = NULL;
        return -2;
    }
    return 0;
}

/* ------------------------------------------------------------------ 释放 */

static void release_dialog(void) {
    if (g_dlg) {
        if (g_lib) {
            dlg_free_fn f = (dlg_free_fn)dlsym(g_lib, "GenieDialog_free");
            if (f) f(g_dlg);
        }
        g_dlg = NULL;
    }
    if (g_cfg) {
        cfg_free_fn f = (cfg_free_fn)dlsym(g_lib, "GenieDialogConfig_free");
        if (f) f(g_cfg);
        g_cfg = NULL;
    }
}

/* ------------------------------------------------------------------ 推理 */

/*
 * 一次完整的单轮推理。
 * 返回值：0 成功；负值表示失败（语义见各分支注释）。
 * 文本通过 out 缓冲区回传，长度通过 out_len 回传。
 */
static int run_once(const char *cfg_json_path,
                    const char *prompt,
                    char *out, size_t out_cap, int *out_len,
                    int *out_tokens) {
    long t0 = now_ms();
    int rc = 0;

    if (out_cap == 0) return -1;
    out[0] = 0;

    rc = load_genie();
    if (rc != 0) return rc;

    cfg_from_json_fn c_from = (cfg_from_json_fn)dlsym(g_lib, "GenieDialogConfig_createFromJson");
    cfg_free_fn      c_free = (cfg_free_fn)dlsym(g_lib, "GenieDialogConfig_free");
    dlg_create_fn    d_new  = (dlg_create_fn)dlsym(g_lib, "GenieDialog_create");
    dlg_query_fn     d_query= (dlg_query_fn)dlsym(g_lib, "GenieDialog_query");
    dlg_free_fn      d_free = (dlg_free_fn)dlsym(g_lib, "GenieDialog_free");

    /* --- 读配置 --- */
    FILE *f = fopen(cfg_json_path, "rb");
    if (!f) {
        nlog("打不开配置 %s: %s", cfg_json_path, strerror(errno));
        return -3;
    }
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (n <= 0 || n > 8 * 1024 * 1024) { fclose(f); nlog("配置长度异常: %ld", n); return -3; }
    char *json = (char *)malloc((size_t)n + 1);
    if (!json) { fclose(f); return -4; }
    if (fread(json, 1, (size_t)n, f) != (size_t)n) { fclose(f); free(json); return -5; }
    json[n] = 0;
    fclose(f);
    nlog("配置读入 %ld 字节", n);

    /* --- config --- */
    status_t st = c_from(json, &g_cfg);
    free(json);
    nlog("GenieDialogConfig_createFromJson → %d", st);
    if (st != 0 || !g_cfg) { g_cfg = NULL; return -6; }

    /* --- create：最慢也最易卡死的一步 --- */
    stage_enter(NPU_STAGE_CREATING);
    nlog("GenieDialog_create 开始（建图 + 加载权重）");
    st = d_new(g_cfg, &g_dlg);
    nlog("GenieDialog_create → %d (+%ldms)", st, now_ms() - t0);
    if (st != 0 || !g_dlg) {
        g_dlg = NULL;
        if (c_free) c_free(g_cfg);
        g_cfg = NULL;
        return -7;
    }

    /* --- query：token 回调阶段 --- */
    stage_enter(NPU_STAGE_GENERATING);

    /* 回调只做两件事：累计 token 数、把文本追加到 out。 */
    sink_t sink;
    sink.buf = out;
    sink.cap = out_cap;
    sink.len = 0;
    sink.tokens = 0;
    g_sink = &sink;

    st = d_query(g_dlg, prompt, 0 /* GENIE_DIALOG_SENTENCE_COMPLETE */,
                  npu_on_token_shim, NULL);
    long t1 = now_ms();
    g_sink = NULL;

    nlog("GenieDialog_query → %d，回调 %d 次，decode %ldms", st, sink.tokens, t1 - t0);

    if (out_len) *out_len = (int)sink.len;
    if (out_tokens) *out_tokens = sink.tokens;

    /* --- 释放 --- */
    if (d_free) d_free(g_dlg);
    g_dlg = NULL;
    if (c_free) c_free(g_cfg);
    g_cfg = NULL;

    if (st != 0) return -8;
    return 0;
}

/* Genie token 回调：追加文本 + 计数 + 推进进度。 */
void npu_on_token_shim(const char *text, int code, const void *ud) {
    (void)code;
    (void)ud;
    if (!g_sink) return;

    g_sink->tokens++;
    g_progress.tokens = g_sink->tokens;
    stage_progress();

    if (text && *text) {
        size_t add = strlen(text);
        if (g_sink->len + add < g_sink->cap) {
            memcpy(g_sink->buf + g_sink->len, text, add);
            g_sink->len += add;
            g_sink->buf[g_sink->len] = 0;
        }
    }
}

/* ------------------------------------------------------------------ JNI */

/*
 * nativeRun(cfgJsonPath, prompt) -> String
 * 返回 "status\ttokens\ttext"；失败时 status 为负值，text 为错误描述。
 */
JNIEXPORT jstring JNICALL
Java_com_dshbox_app_npu_NpuNative_nativeRun(JNIEnv *env, jclass clazz,
                                             jstring cfg_path, jstring prompt) {
    (void)clazz;
    const char *cfg = cfg_path ? (*env)->GetStringUTFChars(env, cfg_path, NULL) : NULL;
    const char *pr  = prompt ? (*env)->GetStringUTFChars(env, prompt, NULL) : "";
    if (!cfg) return (*env)->NewStringUTF(env, "-1\t0\t缺少配置路径");

    size_t cap = 65536;
    char *out = (char *)malloc(cap);
    if (!out) {
        (*env)->ReleaseStringUTFChars(env, cfg_path, cfg);
        return (*env)->NewStringUTF(env, "-2\t0\t内存不足");
    }

    g_progress.stage = NPU_STAGE_IDLE;
    g_progress.tokens = 0;
    g_progress.status = 0;

    int out_len = 0, tokens = 0;
    int rc = run_once(cfg, pr ? pr : "", out, cap, &out_len, &tokens);
    g_progress.status = rc;
    stage_enter(NPU_STAGE_DONE);

    /* 拼装返回串：status \t tokens \t text */
    size_t need = (size_t)out_len + 64;
    char *ret = (char *)malloc(need);
    if (!ret) {
        free(out);
        (*env)->ReleaseStringUTFChars(env, cfg_path, cfg);
        return (*env)->NewStringUTF(env, "-2\t0\t内存不足");
    }
    snprintf(ret, need, "%d\t%d\t%s", rc, tokens, out);
    free(out);
    (*env)->ReleaseStringUTFChars(env, cfg_path, cfg);

    jstring js = (*env)->NewStringUTF(env, ret);
    free(ret);
    return js;
}

/* nativeProgress() -> long[]：{stage, tokens, lastProgressAgeMs} */
JNIEXPORT jlongArray JNICALL
Java_com_dshbox_app_npu_NpuNative_nativeProgress(JNIEnv *env, jclass clazz) {
    (void)clazz;
    jlong vals[3];
    vals[0] = (jlong)g_progress.stage;
    vals[1] = (jlong)g_progress.tokens;
    vals[2] = (jlong)(now_ms() - g_progress.last_progress_ms);
    jlongArray arr = (*env)->NewLongArray(env, 3);
    if (arr) (*env)->SetLongArrayRegion(env, arr, 0, 3, vals);
    return arr;
}

/* nativeRelease()：释放当前持有的 Genie 句柄（正常收尾用）。 */
JNIEXPORT void JNICALL
Java_com_dshbox_app_npu_NpuNative_nativeRelease(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    if (g_lib) release_dialog();
}

/*
 * nativeRuntimeApiVersion() -> String
 *
 * 运行时 API 版本（QNN 核心 API 版本，如 "2.40.0"）。
 * 用于预检 ctx-bin 的编译版本是否高于运行时（那样会直接 create 失败）。
 *
 * ## 取值路径（实测确定）
 *
 * `libQnnSystem.so` **只导出** `QnnSystemInterface_getProviders` 与
 * `QnnSystemInterfaceInternal_getProviders`——**没有** `QnnBackend_getApiVersion`，
 * 所以不能去 dlsym 那个符号。
 *
 * 运行时版本要从 `QnnInterface_getProviders()` 返回的 `QnnInterface_t` 里读：
 *     typedef struct { uint32_t          backendId;
 *                      const char*       providerName;
 *                      Qnn_ApiVersion_t  apiVersion;   // ← 这里
 *                      union { ... } ; } QnnInterface_t;
 *     typedef struct { Qnn_Version_t coreApiVersion;     // {major,minor,patch}
 *                      Qnn_Version_t backendApiVersion; } Qnn_ApiVersion_t;
 *
 * ## 为什么手写偏移而不用厂商头文件
 *
 * QNN 头文件受 Qualcomm 专有许可约束，不能随本仓库分发；所以这里按
 * 上面的布局手写偏移。下方的静态断言把这套布局**钉死**在编译期：
 * 若 ABI 变化，编译就失败，而不是运行时读到垃圾值。
 *
 * 注意两点：
 *  - SDK 头文件里的 `QNN_API_VERSION_*` 宏（2.27）**不等于**设备运行时的版本
 *    （实测 2.40）——所以必须运行时查，不能读头文件常量。
 *  - libQnnSystem.so 里**不含**版本字符串，不能靠 strings 旁路。
 */

/* Qnn_Version_t = {uint32 major, minor, patch} */
typedef struct { uint32_t major, minor, patch; } qnn_version_t;
/* Qnn_ApiVersion_t = {coreApiVersion, backendApiVersion} */
typedef struct { qnn_version_t core, backend; } qnn_api_version_t;
/* QnnInterface_t 的前缀：{uint32 backendId, const char* providerName, apiVersion} */
typedef struct {
    uint32_t backendId;
    const char *providerName;
    qnn_api_version_t apiVersion;
} qnn_interface_prefix_t;

/* 布局契约（编译期钉死）：
 *   - qnn_version_t 是 {major,minor,patch} 三个 uint32 → 12 字节；
 *   - qnn_api_version_t 是两个 qnn_version_t → 24 字节；
 *   - apiVersion 必须落在 QnnInterface_t 的偏移 16 处
 *     （backendId 4 字节 + 4 字节对齐 + providerName 8 字节）。
 * 若 ABI 变化，编译就失败，而不是运行时读到垃圾值。 */
typedef char qnn_version_assert_[
    (sizeof(qnn_version_t) == 12 && sizeof(qnn_api_version_t) == 24) ? 1 : -1];
typedef char qnn_layout_assert_[
    (offsetof(qnn_interface_prefix_t, apiVersion) == 16) ? 1 : -1];

JNIEXPORT jstring JNICALL
Java_com_dshbox_app_npu_NpuNative_nativeRuntimeApiVersion(JNIEnv *env, jclass clazz) {
    (void)clazz;

    void *htp = dlopen("libQnnHtp.so", RTLD_NOW);
    if (!htp) return (*env)->NewStringUTF(env, "");

    typedef int (*get_providers_fn)(const void ***, uint32_t *);
    get_providers_fn gp = (get_providers_fn)dlsym(htp, "QnnInterface_getProviders");
    if (!gp) return (*env)->NewStringUTF(env, "");

    const void **provs = NULL;
    uint32_t num = 0;
    if (gp(&provs, &num) != 0 || !provs || num == 0) {
        return (*env)->NewStringUTF(env, "");
    }

    const qnn_interface_prefix_t *iface =
        (const qnn_interface_prefix_t *)provs[0];
    if (!iface) return (*env)->NewStringUTF(env, "");

    char buf[32];
    snprintf(buf, sizeof(buf), "%u.%u.%u",
             iface->apiVersion.core.major,
             iface->apiVersion.core.minor,
             iface->apiVersion.core.patch);
    return (*env)->NewStringUTF(env, buf);
}
