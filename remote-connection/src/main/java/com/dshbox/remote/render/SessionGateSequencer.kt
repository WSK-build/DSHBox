package com.dshbox.remote.render

/**
 * 门禁 cookie 下发通道：显示槽据此把门禁令牌种进渲染器的 cookie 罐。
 *
 * systemWebView 变体经进程级 CookieManager 异步下发；gecko 变体的注入
 * 路径暂不可用，会话为无门禁（bypass）模式，显示槽不构造本通道。
 */
fun interface GateCookieWriter {

    /**
     * 异步下发门禁 cookie（host 级，覆盖该设备全部端点端口）；完成后在
     * 主线程回调 [onResult]。失败不重试——由自愈路径兜底。
     */
    fun write(host: String, value: String, onResult: (Boolean) -> Unit)
}

/**
 * 会话门禁时序器：守护渲染器加载与门禁下发的先后关系。
 *
 * 职责一（时序约束）：门禁下发完成回调之后才放行渲染器加载——否则旧值/
 * 空值会导致首跳 404。下发失败同样放行：加载会命中代理 404，由职责二的
 * 自愈路径兜底。
 *
 * 职责二（自愈路径）：主文档 404 或主框架加载失败时，重新下发门禁并延迟
 * 重载一次，对用户无感；连续失败转失败态。每个会话仅自愈一次；自愈重载
 * 尚未执行期间到达的重复失败事件不消耗机会也不重复排程（双事件引擎的
 * 404 观测会先后到达）。
 *
 * cookie 通道与延迟调度入口均可注入，逻辑可在 JVM 单测中全路径驱动。
 */
class SessionGateSequencer(
    /** 门禁 cookie 通道；null = 无门禁模式（bypass），加载直接放行。 */
    private val cookieWriter: GateCookieWriter?,
    /** 延迟调度入口（自愈重载经它延后执行；可注入假调度器驱动单测）。 */
    private val scheduler: Scheduler = Scheduler.DIRECT,
) {

    /** 延迟调度入口（可注入假调度器驱动单测）。 */
    fun interface Scheduler {

        /** 延迟 [delayMillis] 后执行 [action]。 */
        fun post(delayMillis: Long, action: Runnable)

        companion object {
            /** 即时调度：单测同步驱动用。 */
            val DIRECT = Scheduler { _, action -> action.run() }
        }
    }

    /** 自愈重载前的延迟：给 cookie 写入与代理状态留出稳定窗口。 */
    private val healDelayMillis: Long = GATE_HEAL_DELAY_MILLIS

    /** 本会话是否已用过自愈机会（每会话一次，重复失败不再重试）。 */
    private var healUsed = false

    /** 自愈重载已排程尚未执行：期间到达的重复失败事件直接忽略。 */
    private var healPending = false

    /**
     * 放行首次加载：无门禁模式直接加载；有门禁则等待下发完成回调后加载
     * （回调成功与否都放行，失败交由自愈路径兜底）。
     */
    fun dispatchFirstLoad(host: String, token: String?, load: () -> Unit) {
        val writer = cookieWriter
        if (writer == null || token == null) {
            load()
            return
        }
        writer.write(host, token) { load() }
    }

    /**
     * 主文档 404 / 主框架加载失败的观测入口：未用过自愈机会且无在途自愈时
     * 重发门禁并延迟重载一次（返回 true，会话层保持加载态）；自愈机会已
     * 用尽则回调 [onGiveUp] 转失败态（返回 false）；自愈重载在途则忽略本
     * 次事件（返回 true），由排定的重载收敛。
     */
    fun onMainDocumentFailure(
        host: String,
        token: String?,
        retryLoad: () -> Unit,
        onGiveUp: () -> Unit,
    ): Boolean {
        if (healUsed) {
            onGiveUp()
            return false
        }
        if (healPending) return true
        healUsed = true
        healPending = true
        val writer = cookieWriter
        if (writer == null || token == null) {
            scheduler.post(healDelayMillis) {
                healPending = false
                retryLoad()
            }
            return true
        }
        writer.write(host, token) {
            scheduler.post(healDelayMillis) {
                healPending = false
                retryLoad()
            }
        }
        return true
    }

    private companion object {
        const val GATE_HEAL_DELAY_MILLIS = 300L
    }
}
