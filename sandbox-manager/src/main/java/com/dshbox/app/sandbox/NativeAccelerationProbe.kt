package com.dshbox.app.sandbox

import android.util.Log
import java.io.File

/**
 * 在 **proot 之外**（App 自身的 SELinux 上下文）记录一次本地加速可达性。
 *
 * DSH 工具与插件都跑在 proot 里，看到的是 guest 的挂载视图。当 guest 内
 * `/vendor` 缺失、或 `/dev/fastrpc-cdsp` 打不开时，仅凭 guest 视角无法区分：
 *
 *  1. 硬件/驱动本来就不存在；
 *  2. proot 没有把对应路径绑定进来；
 *  3. 厂商 SELinux 策略挡住了本应用。
 *
 * 三者的修法完全不同，而只有宿主侧的一手数据能把它们分开。
 *
 * 本探针只做只读探测（`exists/canRead/canWrite` 与 `/proc/self/attr/current`），
 * 不上传、不落盘、不参与启动流程。
 */
internal object NativeAccelerationProbe {

    private const val TAG = "NativeAccelProbe"

    private const val FAST_RPC_NODE = "/dev/fastrpc-cdsp"
    private const val OPENCL_LIB = "/vendor/lib64/libOpenCL.so"
    private const val VULKAN_ICD_DIR = "/vendor/etc/vulkan/icd.d"
    private const val QNN_LIB_ODM = "/odm/lib64/npuhw/qnnv3/libQnnHtp.so"
    private const val QNN_LIB_VENDOR = "/vendor/lib64/hw/libQnnHtp.so"
    private const val SELINUX_CONTEXT = "/proc/self/attr/current"

    internal data class Snapshot(
        val fastRpcExists: Boolean,
        val fastRpcReadable: Boolean,
        val fastRpcWritable: Boolean,
        val openClVisible: Boolean,
        val vulkanIcdVisible: Boolean,
        val qnnVisible: Boolean,
        val selinuxContext: String,
    )

    /**
     * 输出一行日志。调用点在 [DefaultSandboxManager.startSandbox]，每次启动记一次。
     *
     * 判读方式：
     * - `fastrpc(exists=false)` → 节点不存在（机型/驱动问题）；
     * - `exists=true r=false w=false` 且设备本身有 NPU → 被策略挡住（本应用上下文）；
     * - `opencl=true vulkanIcd=true` 但 guest 内仍看不到 → 属于 proot 绑定问题，
     *   见 PR「把 /vendor 绑定进 proot」。
     */
    fun log(tag: String = TAG) {
        val s = snapshot()
        Log.i(
            tag,
            "native accel: fastrpc(exists=${s.fastRpcExists} r=${s.fastRpcReadable} w=${s.fastRpcWritable}) " +
                "opencl=${s.openClVisible} vulkanIcd=${s.vulkanIcdVisible} qnn=${s.qnnVisible} " +
                "ctx=${s.selinuxContext}",
        )
    }

    internal fun snapshot(): Snapshot {
        val fastRpc = File(FAST_RPC_NODE)
        return Snapshot(
            fastRpcExists = fastRpc.exists(),
            fastRpcReadable = fastRpc.canRead(),
            fastRpcWritable = fastRpc.canWrite(),
            openClVisible = File(OPENCL_LIB).exists(),
            vulkanIcdVisible = File(VULKAN_ICD_DIR).isDirectory,
            qnnVisible = File(QNN_LIB_ODM).exists() || File(QNN_LIB_VENDOR).exists(),
            selinuxContext = runCatching { File(SELINUX_CONTEXT).readText().trim() }
                .getOrDefault("?"),
        )
    }
}
