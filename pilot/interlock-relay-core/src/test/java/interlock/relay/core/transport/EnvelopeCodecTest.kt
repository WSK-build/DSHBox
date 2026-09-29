package interlock.relay.core.transport

import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.EffectState
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.RelayResponse
import interlock.relay.core.protocol.RetryPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通道解码是请求形状的唯一判据点。响应只会落在 `outbox/<请求文件名>`，也就是客户端真正
 * 去轮询的那个位置，所以「标识白名单」与「拒绝也必须带得出 id」两条要钉在这里：
 * 白名单漏一格，回信就落到没人看的路径上；id 取自正文，一条请求就有了两个回信位置。
 */
class EnvelopeCodecTest {

    @Test
    fun requestIdsAcceptOnlyTheChannelWhitelist() {
        assertTrue(EnvelopeCodec.isValidId("a"))
        assertTrue(EnvelopeCodec.isValidId("req-7_9"))
        assertTrue(EnvelopeCodec.isValidId("x".repeat(64)))
        assertFalse(EnvelopeCodec.isValidId(""))
        assertFalse(EnvelopeCodec.isValidId("x".repeat(65)))
        // 点、斜杠与空格都进不了标识：它同时是响应落点名的一部分。
        assertFalse(EnvelopeCodec.isValidId("req.7"))
        assertFalse(EnvelopeCodec.isValidId("../../etc/passwd"))
        assertFalse(EnvelopeCodec.isValidId("a b"))
        assertFalse(EnvelopeCodec.isValidId("请求"))
    }

    @Test
    fun onlyWholeJsonNamesAreTreatedAsRequests() {
        assertTrue(EnvelopeCodec.isRequestFileName("req-7.json"))
        assertFalse(EnvelopeCodec.isRequestFileName("req-7"))
        assertFalse(EnvelopeCodec.isRequestFileName(".json"))
        assertFalse(EnvelopeCodec.isRequestFileName("req-7.json.json"))
        assertFalse(EnvelopeCodec.isRequestFileName("req-7.JSON"))
        assertFalse(EnvelopeCodec.isRequestFileName("dir/req-7.json"))
        assertFalse(EnvelopeCodec.isRequestFileName("req-7.json.tmp"))
    }

    /** 两条白名单必须咬合：收得下却回不出去，客户端只能干等一个误导自己的超时。 */
    @Test
    fun acceptedFileNamesAlwaysYieldAUsableReplyTarget() {
        listOf("a.json", "req-7.json", "x".repeat(64) + ".json").forEach { name ->
            assertTrue(name, EnvelopeCodec.isRequestFileName(name))
            assertTrue(name, EnvelopeCodec.isValidId(name.removeSuffix(EnvelopeCodec.JSON_SUFFIX)))
        }
    }

    /** v1 正路回归：整条解码链路（id、版本、能力、args、超时夹取）行为不许变。 */
    @Test
    fun v1RequestsStillDecodeExactlyAsBefore() {
        val body = """{"v":1,"id":"req-7","ts":1234,"capability":"ui.click","args":{"x":1},"ttlMs":5}"""
        val result = EnvelopeCodec.decodeRequest(body, "req-7.json")
        assertTrue("expected a valid v1 request, got $result", result is DecodeResult.Valid)
        val request = (result as DecodeResult.Valid).request
        assertEquals("req-7", request.id)
        assertEquals(1234L, request.sentAtMs)
        assertEquals(CapabilityId.UI_CLICK.wire, request.capability)
        assertEquals(1, request.args.optInt("x"))
        // 低于下限的 ttl 夹回下限，这是 v1 的既有语义。
        assertEquals(1000L, request.timeoutMs)
    }

    @Test
    fun oversizedRequestsAreRefusedBeforeTheBodyIsRead() {
        val body = """{"id":"evil","v":1,"capability":"pkg.query","args":{"blob":"${"a".repeat(200_000)}"}}"""
        val result = EnvelopeCodec.decodeRequest(body, "req-7.json")
        assertTrue("expected a rejection, got $result", result is DecodeResult.Rejected)
        val rejected = result as DecodeResult.Rejected
        assertEquals(RelayError.REQUEST_TOO_LARGE, rejected.error)
        assertEquals("oversized", rejected.cause)
        // 拒绝里的 id 来自文件名：正文自称是谁不算数。
        assertEquals("req-7", rejected.id)
    }

    /** 25 层 = 深度上限之上的第一层。这条扫描是手写线性扫描，不是解析器的自我保护。 */
    @Test
    fun overDeepRequestsAreRefusedWithoutTheJsonParser() {
        val body = "{".repeat(25) + "\"a\":1" + "}".repeat(25)
        val result = EnvelopeCodec.decodeRequest(body, "req-7.json")
        assertTrue("expected a rejection, got $result", result is DecodeResult.Rejected)
        val rejected = result as DecodeResult.Rejected
        assertEquals(RelayError.TRANSPORT_MALFORMED, rejected.error)
        assertEquals("too deep", rejected.cause)
        assertEquals("req-7", rejected.id)
    }

    // ---- v2 控制请求 ----

    private val digest = "ab".repeat(32)

    private fun rejectedCause(result: ControlDecodeResult): Pair<RelayError, String> {
        assertTrue("expected a rejection, got $result", result is ControlDecodeResult.Rejected)
        val rejected = result as ControlDecodeResult.Rejected
        return rejected.error to rejected.cause
    }

    @Test
    fun allFourControlOpsDecode() {
        val submit = EnvelopeCodec.decodeControl(
            """{"v":2,"op":"submit","id":"c1","capability":"ui.click","args":{"x":1},"hostTtlMs":90000,"payloadDigest":"$digest"}""",
            "c1.json",
        )
        val submitRequest = (submit as ControlDecodeResult.Valid).control
        assertTrue(submitRequest is ControlRequest.Submit)
        submitRequest as ControlRequest.Submit
        assertEquals("c1", submitRequest.id)
        assertEquals(CapabilityId.UI_CLICK, submitRequest.capability)
        assertEquals(1, submitRequest.args.optInt("x"))
        assertEquals(90_000L, submitRequest.hostTtlMs)
        assertEquals(digest, submitRequest.payloadDigest)

        val status = EnvelopeCodec.decodeControl(
            """{"v":2,"op":"status","id":"c2","targetId":"req-9"}""",
            "c2.json",
        )
        assertEquals(ControlRequest.Status("c2", "req-9"), (status as ControlDecodeResult.Valid).control)

        val cancel = EnvelopeCodec.decodeControl(
            """{"v":2,"op":"cancel","id":"c3","targetId":"req-9"}""",
            "c3.json",
        )
        assertEquals(ControlRequest.Cancel("c3", "req-9"), (cancel as ControlDecodeResult.Valid).control)

        val health = EnvelopeCodec.decodeControl("""{"v":2,"op":"health","id":"c4"}""", "c4.json")
        assertEquals(ControlRequest.Health("c4"), (health as ControlDecodeResult.Valid).control)

        val ask = EnvelopeCodec.decodeControl(
            """{"v":2,"op":"ask","id":"c5","question":"去哪里?","options":["A","B","C"],"timeoutMs":30000}""",
            "c5.json",
        )
        val askRequest = (ask as ControlDecodeResult.Valid).control
        assertTrue(askRequest is ControlRequest.Ask)
        askRequest as ControlRequest.Ask
        assertEquals("c5", askRequest.id)
        assertEquals("去哪里?", askRequest.question)
        assertEquals(listOf("A", "B", "C"), askRequest.options)
        assertEquals(30_000L, askRequest.timeoutMs)
    }

    /**
     * ask 的形状闸：问题/选项非空且有长度上限、选项严格 2..3 个、超时夹进 5s..120s。
     * 卡面一屏放不下超长文案，与其截成半句话不如当场拒绝；这里逐支钉住。
     */
    @Test
    fun askShapeIsValidatedAndTimeoutClamped() {
        val ok = """{"v":2,"op":"ask","id":"a1","question":"Q","options":["x","y"]}"""
        val clamped = EnvelopeCodec.decodeControl(ok, "a1.json")
        assertEquals(60_000L, ((clamped as ControlDecodeResult.Valid).control as ControlRequest.Ask).timeoutMs)

        fun reject(body: String): String =
            (EnvelopeCodec.decodeControl(body, "a1.json") as ControlDecodeResult.Rejected).cause

        assertEquals("question", reject("""{"v":2,"op":"ask","id":"a1","question":"","options":["x","y"]}"""))
        assertEquals(
            "question",
            reject("""{"v":2,"op":"ask","id":"a1","question":"${"q".repeat(201)}","options":["x","y"]}"""),
        )
        assertEquals("options", reject("""{"v":2,"op":"ask","id":"a1","question":"Q"}"""))
        assertEquals("options count", reject("""{"v":2,"op":"ask","id":"a1","question":"Q","options":["x"]}"""))
        assertEquals(
            "options count",
            reject("""{"v":2,"op":"ask","id":"a1","question":"Q","options":["a","b","c","d"]}"""),
        )
        assertEquals("option text", reject("""{"v":2,"op":"ask","id":"a1","question":"Q","options":["x","  "]}"""))
        assertEquals(
            "option text",
            reject("""{"v":2,"op":"ask","id":"a1","question":"Q","options":["x","${"o".repeat(61)}"]}"""),
        )
        // 非字符串元素按形状拒绝，而不是被 org.json 的 getString 转成 "1"/"true" 混过去：
        // 宿主比入口宽松时，手写请求文件的人看不出自己写错了类型。
        assertEquals("options shape", reject("""{"v":2,"op":"ask","id":"a1","question":"Q","options":[1,2]}"""))
        assertEquals("options shape", reject("""{"v":2,"op":"ask","id":"a1","question":"Q","options":["x",true]}"""))
        val low = EnvelopeCodec.decodeControl(
            """{"v":2,"op":"ask","id":"a1","question":"Q","options":["x","y"],"timeoutMs":1000}""",
            "a1.json",
        )
        assertEquals(5_000L, ((low as ControlDecodeResult.Valid).control as ControlRequest.Ask).timeoutMs)
        val high = EnvelopeCodec.decodeControl(
            """{"v":2,"op":"ask","id":"a1","question":"Q","options":["x","y"],"timeoutMs":999999}""",
            "a1.json",
        )
        assertEquals(120_000L, ((high as ControlDecodeResult.Valid).control as ControlRequest.Ask).timeoutMs)
    }

    /** 宿主有效期只影响宿主自己排多久队：缺省 90 秒，越界夹进 [1000, 120000]。 */
    @Test
    fun hostTtlIsClampedOrDefaulted() {
        fun ttlOf(body: String): Long {
            val result = EnvelopeCodec.decodeControl(body, "c1.json")
            return (result as ControlDecodeResult.Valid).control.let {
                (it as ControlRequest.Submit).hostTtlMs
            }
        }
        assertEquals(90_000L, ttlOf("""{"v":2,"op":"submit","id":"c1","capability":"ui.tap","args":{},"payloadDigest":"$digest"}"""))
        assertEquals(1_000L, ttlOf("""{"v":2,"op":"submit","id":"c1","capability":"ui.tap","args":{},"hostTtlMs":5,"payloadDigest":"$digest"}"""))
        assertEquals(120_000L, ttlOf("""{"v":2,"op":"submit","id":"c1","capability":"ui.tap","args":{},"hostTtlMs":999999,"payloadDigest":"$digest"}"""))
    }

    @Test
    fun malformedControlRequestsAreRejectedWithTheirId() {
        val base = """{"v":2,"op":"submit","id":"c1","capability":"ui.click","args":{},"payloadDigest":"$digest"}"""

        // 摘要必须 64 位小写十六进制：大小写与缩写都会让「同一件事」的比对失真。
        assertEquals(
            RelayError.TRANSPORT_MALFORMED to "digest",
            rejectedCause(EnvelopeCodec.decodeControl(base.replace(digest, digest.uppercase()), "c1.json")),
        )
        assertEquals(
            RelayError.TRANSPORT_MALFORMED to "digest",
            rejectedCause(EnvelopeCodec.decodeControl(base.replace(digest, "abcd"), "c1.json")),
        )
        // op 白名单之外整条拒。
        assertEquals(
            RelayError.TRANSPORT_MALFORMED to "op",
            rejectedCause(EnvelopeCodec.decodeControl("""{"v":2,"op":"exec","id":"c1"}""", "c1.json")),
        )
        // 未知能力在这里就拒：控制通道绝不能执行能力动作。
        assertEquals(
            RelayError.CAPABILITY_NOT_IMPLEMENTED to "unknown capability",
            rejectedCause(
                EnvelopeCodec.decodeControl(
                    """{"v":2,"op":"submit","id":"c1","capability":"no.such","args":{},"payloadDigest":"$digest"}""",
                    "c1.json",
                ),
            ),
        )
        // args 必须是对象。
        assertEquals(
            RelayError.TRANSPORT_MALFORMED to "args missing",
            rejectedCause(
                EnvelopeCodec.decodeControl(
                    """{"v":2,"op":"submit","id":"c1","capability":"ui.click","payloadDigest":"$digest"}""",
                    "c1.json",
                ),
            ),
        )
        // status/cancel 的目标 id 也要过白名单。
        assertEquals(
            RelayError.TRANSPORT_MALFORMED to "target",
            rejectedCause(EnvelopeCodec.decodeControl("""{"v":2,"op":"status","id":"c1","targetId":"../x"}""", "c1.json")),
        )
        // 版本、id 与文件名的咬合规则与 v1 同源。
        assertEquals(
            RelayError.TRANSPORT_MALFORMED to "version",
            rejectedCause(EnvelopeCodec.decodeControl("""{"v":1,"op":"health","id":"c1"}""", "c1.json")),
        )
        assertEquals(
            RelayError.TRANSPORT_MALFORMED to "id mismatch",
            rejectedCause(EnvelopeCodec.decodeControl("""{"v":2,"op":"health","id":"other"}""", "c1.json")),
        )
        assertEquals(
            RelayError.REQUEST_TOO_LARGE to "oversized",
            rejectedCause(EnvelopeCodec.decodeControl("""{"pad":"${"a".repeat(64_001)}"}""", "c1.json")),
        )
        // 拒绝也要带得出 id：来自文件名，正文自称是谁不算数。
        val result = EnvelopeCodec.decodeControl("""{"v":2,"op":"exec","id":"c1"}""", "c1.json")
        assertEquals("c1", (result as ControlDecodeResult.Rejected).id)
    }

    // ---- 响应编码 ----

    private fun response(retryPolicy: RetryPolicy?, effectState: EffectState?) = RelayResponse(
        id = "r1",
        ok = true,
        data = JSONObject(),
        artifacts = emptyList(),
        surface = null,
        degradedFrom = null,
        reason = null,
        error = null,
        elapsedMs = 5L,
        retryPolicy = retryPolicy,
        effectState = effectState,
    )

    /** 重放语义两键是附加键：能给出判断才写出，v1 读取方不认识就忽略。 */
    @Test
    fun replaySemanticsKeysAppearOnlyWhenKnown() {
        val bare = JSONObject(EnvelopeCodec.encode(response(retryPolicy = null, effectState = null)))
        assertFalse(bare.has("retryPolicy"))
        assertFalse(bare.has("effectState"))

        val annotated = JSONObject(EnvelopeCodec.encode(response(RetryPolicy.SAFE_RETRY, EffectState.VERIFIED)))
        assertEquals("safe_retry", annotated.getString("retryPolicy"))
        assertEquals("verified", annotated.getString("effectState"))
    }

    /**
     * 许可种类是附加键：只在能明确归类时写出。两种许可（本模块审批 / 系统采集同意）
     * 的字面量钉在这里——回包里出现的值必须是两者之一，不能有第二种拼写。
     */
    @Test
    fun consentKindAppearsOnlyWhenClassified() {
        val bare = JSONObject(EnvelopeCodec.encode(response(retryPolicy = null, effectState = null)))
        assertFalse(bare.has("consentKind"))

        val allowedKinds = setOf(
            RelayResponse.CONSENT_KIND_RELAY_POLICY,
            RelayResponse.CONSENT_KIND_ANDROID_PROJECTION,
        )
        assertEquals(setOf("relay_policy", "android_projection"), allowedKinds)
        allowedKinds.forEach { kind ->
            val encoded = JSONObject(
                EnvelopeCodec.encode(response(retryPolicy = null, effectState = null).copy(consentKind = kind)),
            )
            assertEquals(kind, encoded.getString("consentKind"))
        }
    }

    /** v2 控制响应是固定骨架加按 op 约定的 payload 键。 */
    @Test
    fun controlResponsesCarryTheFrameAndThePayload() {
        val encoded = JSONObject(
            EnvelopeCodec.encodeControl(
                id = "c1",
                op = "status",
                ok = true,
                payload = JSONObject().apply {
                    put("targetId", "req-9")
                    put("found", true)
                    put("phase", "SUCCEEDED")
                },
            ),
        )
        assertEquals(2, encoded.optInt("v"))
        assertEquals("c1", encoded.getString("id"))
        assertEquals("status", encoded.getString("op"))
        assertEquals(true, encoded.getBoolean("ok"))
        assertEquals("req-9", encoded.getString("targetId"))
        assertTrue(encoded.getBoolean("found"))
        assertEquals("SUCCEEDED", encoded.getString("phase"))
        // payload 里没放的键就不该出现，编不出来路数据。
        assertFalse(encoded.has("resultJson"))
    }
}
