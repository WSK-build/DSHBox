package interlock.relay.core.spi

/**
 * 运行日志字段脱敏口。写进运行日志的每个字段值都先过这一道；core 默认恒等
 * （字段已按事件字典受限），宿主可注入自己的脱敏规则。
 */
fun interface RelayRedactor {

    /** 返回脱敏后的文本；实现必须能处理任意输入（含控制字符被过滤后的残串）。 */
    fun redact(text: String): String

    companion object {
        /** 缺省实现：恒等。 */
        val IDENTITY: RelayRedactor = RelayRedactor { it }
    }
}
