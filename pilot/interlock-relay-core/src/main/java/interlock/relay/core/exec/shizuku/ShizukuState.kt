package interlock.relay.core.exec.shizuku

import android.content.ComponentName
import android.content.Context
import android.os.IBinder
import interlock.relay.core.log.LogEvent
import interlock.relay.core.log.LogSubsystem
import interlock.relay.core.log.RunLog
import rikka.shizuku.Shizuku

/**
 * Shizuku 通路状态。只读探测与授权请求，不隐式启动服务。
 *
 * 三条来自官方的硬约束：
 * 1. 调用 Shizuku 类前必须确认 binder 存活，否则抛 IllegalStateException；
 * 2. v11 起授权记录在 Shizuku 服务端内，`pm grant` 不能替代用户在 Shizuku 界面里的确认；
 * 3. 服务被杀后需要重新拉起，本类只负责暴露掉线事件，不做自动重连承诺。
 */
class ShizukuState(context: Context, runLog: RunLog) {

    private val appContext = context.applicationContext
    private val log = runLog

    private val binderReceived: Shizuku.OnBinderReceivedListener = Shizuku.OnBinderReceivedListener { sync() }
    private val binderDead: Shizuku.OnBinderDeadListener = Shizuku.OnBinderDeadListener { onDead() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == PERMISSION_REQUEST_CODE) {
            granted = grantResult == PackageManager_GRANTED && ping()
            listeners.forEach { it.onStateChanged(snapshot()) }
        }
    }

    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    var granted: Boolean = false
        private set

    private val listeners = mutableListOf<StateListener>()

    interface StateListener {
        fun onStateChanged(state: Snapshot)
    }

    data class Snapshot(
        /** Shizuku 服务端在跑：binder 存活。与「本应用已获授权」是两件事，界面上也是两行。 */
        val running: Boolean,
        val granted: Boolean,
        val uid: Int,
        val version: Int,
    ) {
        /** 两者同时成立，shell 侧能力才真的可用；缺授权时服务端在跑也没用。 */
        val usable: Boolean get() = running && granted
    }

    fun register(listener: StateListener) {
        // 同一个监听者重复注册要收成一个：通道可以 stop→start 反复，
        // 每次都往列表里加一份的话，一次状态变化就会回调它 N 遍——
        // 重连、刷能力清单各做 N 遍，而界面上看不出任何异常。
        synchronized(listeners) {
            listeners -= listener
            listeners += listener
        }
        if (!registered) startWatching()
        listener.onStateChanged(snapshot())
    }

    fun unregister(listener: StateListener) {
        synchronized(listeners) { listeners -= listener }
        if (listeners.isEmpty()) stopWatching()
    }

    private var registered = false

    private fun startWatching() {
        if (registered) return
        registered = true
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    private fun stopWatching() {
        if (!registered) return
        registered = false
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
    }

    fun snapshot(): Snapshot = Snapshot(
        running = available,
        granted = granted,
        uid = runCatching { if (ping()) Shizuku.getUid() else -1 }.getOrDefault(-1),
        version = runCatching { if (ping()) Shizuku.getVersion() else -1 }.getOrDefault(-1),
    )

    /**
     * 预 v11 的 Shizuku 不再支持，一律视为不可用，避免走到已删除的旧接口上。
     *
     * 异常要落日志：这条链上每一步都是 `runCatching→false`，全吞掉的话「服务端没起」
     * 与「起了但 binder 调用抛了」在界面上长成同一个「未检测到」，用户与我们都无从下手。
     */
    fun ping(): Boolean = runCatching { !Shizuku.isPreV11() && Shizuku.pingBinder() }
        .onFailure { log.warn(LogSubsystem.GATE, LogEvent.SHIZUKU_PROBE_FAILED, "where" to "ping", "cause" to it.brief()) }
        .getOrDefault(false)

    /**
     * 低频重探一次 Shizuku 服务端状态，供特权模式后端与状态页共用。
     *
     * 需要它是因为撤销授权没有回调：授予会走 `OnBinderReceivedListener` 与授权结果监听，
     * 而在 Shizuku 管理器里把授权**撤掉**不发任何通知。不重探的话，能力清单里的
     * `authorized` 会无限期停在 true，而 shell 那几条能力一条都跑不了 ——
     * 「能力清单里的 usable 跟着 Shizuku 状态联动」就只在授予方向成立。
     */
    fun reprobe() = sync()

    private fun sync() {
        if (!ping()) {
            onDead()
            return
        }
        available = true
        granted = runCatching { Shizuku.checkSelfPermission() == PackageManager_GRANTED }
            .onFailure { log.warn(LogSubsystem.GATE, LogEvent.SHIZUKU_PROBE_FAILED, "where" to "checkSelfPermission", "cause" to it.brief()) }
            .getOrDefault(false)
        listeners.forEach { it.onStateChanged(snapshot()) }
    }

    private fun onDead() {
        available = false
        granted = false
        listeners.forEach { it.onStateChanged(snapshot()) }
    }

    /** 已在 Shizuku 内授权时直接返回成功，不重复弹系统框。 */
    fun requestAuthorization() {
        if (!ping()) return
        if (granted) return
        runCatching {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                log.warn(
                    LogSubsystem.GATE,
                    LogEvent.SHIZUKU_PROBE_FAILED,
                    "where" to "requestPermission",
                    "cause" to "user permanently denied; the system will not show the grant dialog again",
                )
                return
            }
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }.onFailure {
            log.warn(LogSubsystem.GATE, LogEvent.SHIZUKU_PROBE_FAILED, "where" to "requestPermission", "cause" to it.brief())
        }
    }

    /**
     * 绑定用户服务（以 shell 身份运行的独立进程）。
     *
     * 返回值必须落日志：这一步失败时 Shizuku 侧的进程会自己退出，本进程只看到一个
     * 永远不回调的 connection——不留一行，事后完全查不到是哪一步断的。
     */
    fun bindUserService(connection: android.content.ServiceConnection): Boolean {
        // 未授权时直接调过去只会抛异常，白留一条失败日志：这一档是「还没到能绑的时候」，
        // 不是「绑失败了」。
        if (!available || !granted) return false
        return runCatching {
            Shizuku.bindUserService(
                Shizuku.UserServiceArgs(ComponentName(appContext, RelayShellService::class.java.name))
                    .daemon(false)
                    .processNameSuffix("shizuku")
                    .tag("relay")
                    .debuggable(false)
                    .version(1),
                connection,
            )
            true
        }.onFailure {
            log.warn(LogSubsystem.GATE, LogEvent.SHIZUKU_BIND_FAILED, "cause" to it.brief())
        }.getOrDefault(false)
    }

    fun unbindUserService(connection: android.content.ServiceConnection) {
        runCatching {
            Shizuku.unbindUserService(
                Shizuku.UserServiceArgs(ComponentName(appContext, RelayShellService::class.java.name))
                    .daemon(false)
                    .processNameSuffix("shizuku")
                    .tag("relay")
                    .debuggable(false)
                    .version(1),
                connection,
                true,
            )
        }
    }

    private companion object {
        const val PERMISSION_REQUEST_CODE = 41
        const val PackageManager_GRANTED = android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

/** 落日志用的单行成因：类型名 + 消息，不带堆栈——运行日志是按行读的。 */
private fun Throwable.brief(): String = javaClass.simpleName + ": " + (message ?: "no message")

/** 用户服务侧的 binder 协议码，客户端与服务端共用同一份常量。 */
object ShellProtocol {
    const val CODE_RUN = IBinder.FIRST_CALL_TRANSACTION

    /** 安装：参数是 (fd, 落点名, 超时毫秒)，见 `RelayShellService` 的说明。 */
    const val CODE_INSTALL = IBinder.FIRST_CALL_TRANSACTION + 1

    /** 建可信虚拟屏：参数 (宽, 高, dpi)；回 (displayId 或 <0, 原因)。 */
    const val CODE_DISPLAY_CREATE = IBinder.FIRST_CALL_TRANSACTION + 2

    /** 收掉那块屏：无参数；回 (1=收了一块 / 0=本来就没有 / <0=失败, 原因)。 */
    const val CODE_DISPLAY_RELEASE = IBinder.FIRST_CALL_TRANSACTION + 3

    /**
     * 取当前帧：参数 (等待上限毫秒 long, JPEG 质量 int[0=无损 PNG], 缩到的宽 int[0=不缩])；
     * 回 (0 或 <0, 原因[, 那一帧的只读 fd])。
     */
    const val CODE_DISPLAY_CAPTURE = IBinder.FIRST_CALL_TRANSACTION + 4

    /** 问那块屏还在不在：无参数；回 (displayId 或 -1, 描述)。 */
    const val CODE_DISPLAY_QUERY = IBinder.FIRST_CALL_TRANSACTION + 5

    /**
     * 取用户眼前这块真屏的一帧：无参数；回 (0 或 <0, 原因[, 那一帧的只读 fd])。
     *
     * 与 [CODE_DISPLAY_CAPTURE] 同一形状，区别只在帧来自 `screencap` 而不是那块可信屏。
     * 走 fd 不走文本：PNG 经 `run()` 那条文本通道会被编码吃掉，而整屏一帧经 binder 回传
     * 会撞事务缓冲区上限。
     */
    const val CODE_SCREENSHOT = IBinder.FIRST_CALL_TRANSACTION + 7

    /**
     * Shizuku 通知用户服务「该退了」的事务码。
     *
     * 事务码不是本模块挑的号：上游称之为 `destroy`，aidl 里写死 16777114。
     * 服务侧不实现它，Shizuku 就杀不掉那个进程 —— 宿主每重启一次就多留下一个用户服务进程。
     */
    const val CODE_DESTROY = 16777115
}
