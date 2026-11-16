package interlock.relay.core.log

import interlock.relay.core.exec.shizuku.ShizukuBackend
import interlock.relay.core.exec.stream.RunLogStreamSessionLog
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
 * JVM 测试范围：MailboxServer 依赖 FileObserver、RunLog 依赖
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
        // 的五条显示事件：字段集与模板占位符同表对账（字段集在 ShizukuBackend 伴生）。
        "DISPLAY_CREATE_FLAGS" to (LogEvent.DISPLAY_CREATE_FLAGS to ShizukuBackend.DISPLAY_FLAGS_FIELDS),
        "DISPLAY_MAIN_STATE" to (LogEvent.DISPLAY_MAIN_STATE to ShizukuBackend.DISPLAY_MAIN_STATE_FIELDS),
        "DISPLAY_SELF_CHECK_FAILED" to (LogEvent.DISPLAY_SELF_CHECK_FAILED to ShizukuBackend.DISPLAY_SELF_CHECK_FIELDS),
        "DISPLAY_SELF_CHECK_TRANSIENT" to (LogEvent.DISPLAY_SELF_CHECK_TRANSIENT to ShizukuBackend.DISPLAY_TRANSIENT_FIELDS),
        "DISPLAY_RELEASE_STATE" to (LogEvent.DISPLAY_RELEASE_STATE to ShizukuBackend.DISPLAY_RELEASE_FIELDS),
        // 流会话的读数（诊断埋点）：两条事件共用同一组字段
        // （会话序号、阶段、结论、短原因、字节数、时长），字段集在
        // RunLogStreamSessionLog 伴生。
        "STREAM_SESSION" to (LogEvent.STREAM_SESSION to RunLogStreamSessionLog.FIELDS),
        "STREAM_SESSION_FAILED" to (LogEvent.STREAM_SESSION_FAILED to RunLogStreamSessionLog.FIELDS),
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
     * 流会话那两条事件必须**真的接进这张表**：字典对账只查「接进来的那些」，
     * 事件没接进来时它一声不响 —— 而运行日志渲染会把没被替换的占位符落成
     * `unresolved=…`，正文看起来像消息内容。本测试让「忘了接」这件事变红。
     */
    @Test
    fun theStreamSessionEventsAreWiredIntoTheDictionary() {
        val wiredEvents = wired.map { it.second.first }.toSet()
        assertTrue(
            "STREAM_SESSION 必须接进字典对账（否则模板占位符会被落成 unresolved）",
            wiredEvents.contains(LogEvent.STREAM_SESSION),
        )
        assertTrue(
            "STREAM_SESSION_FAILED 必须接进字典对账（否则模板占位符会被落成 unresolved）",
            wiredEvents.contains(LogEvent.STREAM_SESSION_FAILED),
        )
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
     * 使用 `submittedAgoMs` 会产生 unresolved；本用例验证占位符名为 `ago`。
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
        "sdk" -> "34"
        "trusted", "windowingOk", "imePolicyOk", "released" -> "true"
        "flags" -> "0x7f2d"
        "display" -> "5"
        "violations", "recovered" -> "TOP_TASK_CHANGED"
        "coverage" -> "0.98"
        "beforeTop", "afterTop", "launcher" -> "com.example.launcher"
        "beforeTask", "afterTask", "displayGroup" -> "7"
        "beforeFocus", "afterFocus" -> "5adfb70 u0 com.example.launcher/...@0"
        "beforeIme", "afterIme" -> "0/false"
        else -> field
    }

    private fun placeholders(template: String): Set<String> =
        Regex("%\\{([A-Za-z]+)\\}").findAll(template).map { it.groupValues[1] }.toSet()
}
