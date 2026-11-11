package com.dshbox.remote.model

/**
 * 会话内存保留策略：切换到其它显示源或离开 DSH 页签后，代理监听与渲染器
 * 表面（页面热池）的保留时机。
 *
 * 登录态不受任何一档影响（由代理侧存档持久）：重建代理后免登录、免重建
 * 通道；页面自身的客户端状态只在会话保留期内保留（热池保活），释放后
 * 重连即重载页面。
 */
sealed interface RetentionPolicy {
    /** 失活即关闭代理监听并移出页面热池（默认档）；槽位与端口保留，可重建。 */
    data object IMMEDIATE : RetentionPolicy

    /**
     * 失活后保留代理监听与热池保活指定分钟数再释放；期内切回不重载页面
     * （滚动位置、表单与 WS 连接原样保留，回环 origin 不变，storage 与
     * 登录态原样可用）。
     */
    data class Retain(val minutes: Int) : RetentionPolicy {
        init {
            require(minutes > 0) { "Retention minutes must be positive" }
        }
    }

    /** 保留代理与热池保活直至进程存活期结束，永不自动释放（可显式释放）。 */
    data object FOREVER : RetentionPolicy
}

/** 策略 → 档案字段值（设备存档与全局设置的持久化编码，非界面文案）。 */
fun RetentionPolicy.toArchiveValue(): String = when (this) {
    RetentionPolicy.IMMEDIATE -> "IMMEDIATE"
    RetentionPolicy.FOREVER -> "FOREVER"
    is RetentionPolicy.Retain -> "RETAIN:$minutes"
}

/** 档案字段值 → 策略；未知或损坏值返回 null（调用方按继承默认处理）。 */
fun retentionPolicyFromArchive(value: String): RetentionPolicy? = when {
    value == "IMMEDIATE" -> RetentionPolicy.IMMEDIATE
    value == "FOREVER" -> RetentionPolicy.FOREVER
    value.startsWith("RETAIN:") ->
        value.substringAfter(':').toIntOrNull()?.takeIf { it > 0 }?.let { RetentionPolicy.Retain(it) }
    else -> null
}
