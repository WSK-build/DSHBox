package interlock.relay.core.transport

import interlock.relay.core.log.LogEvent
import interlock.relay.core.log.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 受理/了结两条阶段事件的字典契约。MailboxServer 依赖 FileObserver，无法在纯 JVM 单测里
 * 实例化，能在不引 Android 框架的前提下钉住的只有字典这一侧：级别、占位符集合与替换后的
 * 正文形状。占位符与调用点字段名一旦漂移，RunLog 会把残留的 %{…} 落成 unresolved，
 * 读日志的人会把模板残片当成消息内容；这里用同一规则在测试里提前拦住。
 */
class MailboxPhaseEventsTest {

    /** 受理/了结是可对账的进展点而非故障，级别必须留在 INFO：降成 WARN 会污染错误面。 */
    @Test
    fun phaseEventsAreInfoLevel() {
        assertEquals(LogLevel.INFO, LogEvent.CALL_ACCEPTED.level)
        assertEquals(LogLevel.INFO, LogEvent.CALL_SETTLED.level)
    }

    /** 占位符集合就是 MailboxServer 调用点填的字段集合，多一个少一个都会落 unresolved。 */
    @Test
    fun templatesDeclareExactlyTheFieldsTheServerFills() {
        assertEquals(setOf("id"), placeholders(LogEvent.CALL_ACCEPTED.template))
        assertEquals(setOf("ms"), placeholders(LogEvent.CALL_SETTLED.template))
    }

    /** 用与 RunLog 相同的整串替换渲染一遍：正文成形、不留任何占位符残片。 */
    @Test
    fun renderingWithTheServerFieldsLeavesNoPlaceholderBehind() {
        val accepted = LogEvent.CALL_ACCEPTED.template.replace("%{id}", "req-7")
        val settled = LogEvent.CALL_SETTLED.template.replace("%{ms}", "1234")
        assertEquals("request accepted id=req-7", accepted)
        assertEquals("request settled ms=1234", settled)
    }

    private fun placeholders(template: String): Set<String> =
        Regex("%\\{([A-Za-z]+)\\}").findAll(template).map { it.groupValues[1] }.toSet()
}
