package interlock.relay.core.log

import interlock.relay.core.exec.shizuku.ShizukuBackend
import interlock.relay.core.surface.BackendDispatcher
import interlock.relay.core.transport.ControlChannelServer
import interlock.relay.core.transport.MailboxServer
import interlock.relay.core.transport.RequestStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段事件的字典契约：模板的占位符集合必须与**调用点**填的字段集合逐个相等。
 *
 * 这条纪律是给读日志的人立的：RunLog 渲染时若还剩占位符，会把残片落成
 * `unresolved=…`，而那一段看起来像是消息内容。模板是冻结的，调用点是可改的，
 * 于是能自动发现「谁漂了」的地方只有把两者放在一起比 —— 本测试就是那把尺子。
 *
 * 覆盖范围只含本任务自己接的那几条。`REQUEST_APPROVAL_DECIDED`、
 * `APPROVAL_PRESENTER`、`REQUEST_DISPATCH_ARMED` 的调用点归呈现那一路，
 * 它们的字段此刻还没有具名常量可比，等那边接完再一并收进这张表。
 *
 * 为什么能在纯 JVM 上钉住：MailboxServer 依赖 FileObserver、RunLog 依赖
 * android.util.Log，两者在桌面 JVM 上都实例化不了；模板与字段常量都是纯数据，
 * 不引框架，因此字典这一侧仍然钉得死。
 */
class StageEventDictionaryTest {

    /** 事件 → 调用点填的字段集合。 */
    private val wired = listOf(
        "REQUEST_ADMITTED" to (LogEvent.REQUEST_ADMITTED to RequestStateStore.ADMITTED_FIELDS),
        "REQUEST_WAITING_USER" to (LogEvent.REQUEST_WAITING_USER to RequestStateStore.WAITING_FIELDS),
        "REQUEST_COMPLETED" to (LogEvent.REQUEST_COMPLETED to MailboxServer.COMPLETED_FIELDS),
        "RESPONSE_PUBLISHED" to (LogEvent.RESPONSE_PUBLISHED to MailboxServer.PUBLISHED_FIELDS),
        "CONTROL_OP" to (LogEvent.CONTROL_OP to ControlChannelServer.OP_FIELDS),
        "ASK_ANSWERED" to (LogEvent.ASK_ANSWERED to ControlChannelServer.ASK_FIELDS),
        "ASK_UNAVAILABLE" to (LogEvent.ASK_UNAVAILABLE to ControlChannelServer.ASK_FAIL_FIELDS),
        "BACKEND_CALL" to (LogEvent.BACKEND_CALL to BackendDispatcher.CALL_FIELDS),
        "REMOTE_SUBMITTED" to (LogEvent.REMOTE_SUBMITTED to ShizukuBackend.SUBMITTED_FIELDS),
        "REMOTE_GIVE_UP" to (LogEvent.REMOTE_GIVE_UP to ShizukuBackend.GIVE_UP_FIELDS),
    )

    @Test
    fun everyWiredEventFillsExactlyTheTemplatePlaceholders() {
        wired.forEach { (name, spec) ->
            val (event, fields) = spec
            assertEquals(
                "$name 的模板占位符与调用点字段不一致",
                placeholders(event.template),
                fields,
            )
        }
    }

    /**
     * 用与 RunLog 相同的整串替换渲染一遍：正文成形、不留任何占位符残片。
     *
     * 断言的是「一个占位符都不剩」，不是某一句具体措辞 —— 措辞会改，
     * 而「字段名与占位符对不上」这件事不会自己好起来。
     */
    @Test
    fun renderingWithTheCallSiteFieldsLeavesNoPlaceholderBehind() {
        wired.forEach { (name, spec) ->
            val (event, fields) = spec
            val text = fields.fold(event.template) { acc, field ->
                acc.replace("%{$field}", sample(field))
            }
            assertFalse("$name 渲染后仍有占位符残片：$text", text.contains("%{"))
        }
    }

    /**
     * 远端放弃那条的占位符写作 `%{ago}`，而模板正文的字段名是 `submittedAgoMs`。
     * 这不是漂移，是冻结模板本来的样子：调用点必须按占位符填 `ago`，
     * 写成 `submittedAgoMs` 只会得到一段 unresolved。此处把它钉住，防止有人
     * 「按字段名纠正」回去。
     */
    @Test
    fun theGiveUpEventIsKeyedByAgoNotByTheFieldNameInItsBody() {
        assertEquals(setOf("cap", "ago"), ShizukuBackend.GIVE_UP_FIELDS)
        assertTrue(LogEvent.REMOTE_GIVE_UP.template.contains("submittedAgoMs=%{ago}"))
    }

    /** 值一律是短标识、数字或封闭字面量；这里给每种键一个不会触到任何真值的样本。 */
    private fun sample(field: String): String = when (field) {
        "id" -> "req-7"
        "kind" -> RequestStateStore.WAITING_KIND_RELAY
        "state" -> "SUCCEEDED"
        "cap" -> "ui.tap"
        "op" -> "submit"
        "result" -> "ok"
        "backend" -> "shizuku"
        "waitedMs", "ms", "bytes", "waitMs", "ago", "waiting" -> "42"
        else -> field
    }

    private fun placeholders(template: String): Set<String> =
        Regex("%\\{([A-Za-z]+)\\}").findAll(template).map { it.groupValues[1] }.toSet()
}
