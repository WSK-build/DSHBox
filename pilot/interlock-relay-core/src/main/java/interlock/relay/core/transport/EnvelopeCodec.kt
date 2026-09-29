package interlock.relay.core.transport

import interlock.relay.core.protocol.EffectState
import interlock.relay.core.protocol.RELAY_PROTOCOL_VERSION
import interlock.relay.core.protocol.RELAY_PROTOCOL_VERSION_V2
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.RelayRequest
import interlock.relay.core.protocol.RelayResponse
import interlock.relay.core.protocol.RetryPolicy
import org.json.JSONArray
import org.json.JSONObject

/** 解析结果。拒绝也必须带得出 id，否则助手只能干等一个误导自己的超时。 */
sealed class DecodeResult {
    data class Valid(val request: RelayRequest) : DecodeResult()
    data class Rejected(val id: String, val error: RelayError, val cause: String) : DecodeResult()
}

/**
 * v2 控制请求。四类操作各占一个子类：字段形状由 op 决定，
 * 合成一个带可空字段的类会让「status 必须带 targetId」这类约束无处安放。
 */
sealed class ControlRequest {
    abstract val id: String

    /** 提交一条能力请求。[payloadDigest] 是助手侧对规范化参数算出的摘要，宿主据此认同「同一件事」。 */
    data class Submit(
        override val id: String,
        val capability: CapabilityId,
        val args: JSONObject,
        val hostTtlMs: Long,
        val payloadDigest: String,
    ) : ControlRequest()

    /** 查询一条已提交请求的本地快照，不等待审批与后端。 */
    data class Status(override val id: String, val targetId: String) : ControlRequest()

    /** 请求取消一条已提交请求；能否给出肯定结论由宿主按持久化边界判定。 */
    data class Cancel(override val id: String, val targetId: String) : ControlRequest()

    /** 探活：控制通道是否活着、积压多少。 */
    data class Health(override val id: String) : ControlRequest()

    /**
     * 助手向用户当面提问：一句问题 + 2..3 个选项，外加「全部驳回」。
     *
     * 它不是能力动作、不进闸门也绝不触碰后端——只经自制悬浮卡把问题摆到用户眼前，
     * 把答复原样交回。选项由助手撰写并原样展示（有长度上限），
     * 卡片另带固定的「全部驳回」出口：用户驳回即代表选项都不合适，助手据此重新整理。
     */
    data class Ask(
        override val id: String,
        val question: String,
        val options: List<String>,
        val timeoutMs: Long,
    ) : ControlRequest()
}

/** v2 控制请求的解析结果，语义与 v1 的 [DecodeResult] 一致：拒绝也必须带得出 id。 */
sealed class ControlDecodeResult {
    data class Valid(val control: ControlRequest) : ControlDecodeResult()
    data class Rejected(val id: String, val error: RelayError, val cause: String) : ControlDecodeResult()
}

/**
 * 信封编解码。请求内容完全来自沙盒侧，不可信：
 * 长度、嵌套深度、标识符、能力名、执行模式、超时上限全部在此校验，
 * 任何一项不合形状就整条拒绝，绝不带着半截数据进入后续裁决。
 */
object EnvelopeCodec {

    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    private val FILE_PATTERN = Regex("[A-Za-z0-9_-]{1,64}\\.json")

    fun isValidId(value: String): Boolean = ID_PATTERN.matches(value)

    /** 请求文件名白名单，跳过写入中的分片与沙盒侧自建的其它文件。 */
    fun isRequestFileName(name: String): Boolean = FILE_PATTERN.matches(name)

    /**
     * 解码一条请求。[fileName] 是宿主看到的那个请求文件名，调用方已用
     * [isRequestFileName] 白名单校验过，因此它去掉后缀就是本条请求 id 的**唯一可信来源**：
     * 响应只会落在 `outbox/<fileName>`，也就是客户端真正去轮询的那个位置。
     * 正文里的 `id` 与之不符即整条拒绝——否则一条请求会产生两个可能的响应位置，
     * 而通道没有任何来源认证，先落地的一个就能顶掉另一个。
     *
     * 每条拒绝都带得出 id：形状类拒绝若回 null 而不写响应，客户端只能按自身超时拿到
     * exit 3，而正确结论是 exit 1（请求本身写坏了，要改的是脚本）。
     */
    fun decodeRequest(text: String, fileName: String): DecodeResult {
        val expectedId = fileName.removeSuffix(JSON_SUFFIX)
        if (text.length > MAX_JSON_CHARS) {
            return DecodeResult.Rejected(expectedId, RelayError.REQUEST_TOO_LARGE, "oversized")
        }
        if (nestingDepth(text) > MAX_DEPTH) {
            return DecodeResult.Rejected(expectedId, RelayError.TRANSPORT_MALFORMED, "too deep")
        }
        val json = runCatching { JSONObject(text) }.getOrElse {
            return DecodeResult.Rejected(expectedId, RelayError.TRANSPORT_MALFORMED, "not an object")
        }
        val id = json.optString("id")
        if (!isValidId(id)) {
            return DecodeResult.Rejected(expectedId, RelayError.TRANSPORT_MALFORMED, "bad id")
        }
        if (id != expectedId) {
            return DecodeResult.Rejected(expectedId, RelayError.TRANSPORT_MALFORMED, "id mismatch")
        }
        if (json.optInt("v") != RELAY_PROTOCOL_VERSION) {
            return DecodeResult.Rejected(expectedId, RelayError.TRANSPORT_MALFORMED, "version")
        }
        val capability = CapabilityId.fromWire(json.optString("capability"))
            ?: return DecodeResult.Rejected(expectedId, RelayError.CAPABILITY_NOT_IMPLEMENTED, "unknown capability")
        val args = json.optJSONObject("args")
            ?: return DecodeResult.Rejected(expectedId, RelayError.TRANSPORT_MALFORMED, "args missing")
        return DecodeResult.Valid(
            RelayRequest(
                id = id,
                sentAtMs = json.optLong("ts").coerceAtLeast(0L),
                capability = capability.wire,
                args = args,
                timeoutMs = json.optLong("ttlMs").takeIf { it > 0 }?.coerceIn(MIN_TTL_MS, MAX_TTL_MS) ?: DEFAULT_TTL_MS,
            ),
        )
    }

    /** 执行模式的字面量只在此处出现，响应结构里存的是枚举。 */
    fun encode(response: RelayResponse): String = JSONObject().apply {
        put("v", RELAY_PROTOCOL_VERSION)
        put("id", response.id)
        put("ok", response.ok)
        put("elapsedMs", response.elapsedMs)
        response.surface?.let { put("surface", it.wire) }
        response.degradedFrom?.let { put("degradedFrom", it.wire) }
        // 降级必须是一个能直接判的正误值，不能只靠"有没有 degradedFrom 这个键"：
        // 一次落到真屏的执行看起来与后台执行完全相同（ok:true），而它打扰了用户。
        // 只在有执行面时写：数据读取类能力与模式无关，写 degraded:false 会被读成
        // 对后台通路的一种承诺。
        if (response.surface != null) put("degraded", response.degradedFrom != null)
        // `reason` 只说"为什么失败"。成功回包里的补充说明走 `note`：同一个键在两种语义下
        // 各说一件事，读数的人必须先看 `ok`，否则会把一次降级读成一次报错。
        response.reason?.let { if (response.ok) put("note", it) else put("reason", it) }
        // 降级单独一个键：`degraded:true` 只说"这次没走成后台面"，这一条说"为什么没走成"。
        response.degradeReason?.let { put("degradeReason", it) }
        if (response.data.length() > 0) put("data", response.data)
        if (response.artifacts.isNotEmpty()) {
            put("artifacts", JSONArray().apply { response.artifacts.forEach { put(it) } })
        }
        response.error?.let {
            put("error", JSONObject().apply {
                put("code", it.code)
                put("retryable", it.retryable)
                put("exitCode", it.exitCode)
            })
        }
        // 重放语义两键仅在能给出判断时写出。不写「非空默认值」：空值本身是一句
        // 「这一趟给不出建议」，补成 SAFE_RETRY 会把没论证过的动作放行成可重放。
        // v1 读取方不认识这两个键就忽略，属于附加键，不构成版本断裂。
        response.retryPolicy?.let { put("retryPolicy", it.name.lowercase()) }
        response.effectState?.let { put("effectState", it.name.lowercase()) }
        // 许可种类仅在能明确归类时写出（本模块审批 / 系统采集同意）：空值表示这一趟
        // 不涉及许可，补默认值会把「尚未归类」读成某一种许可。同样是附加键。
        response.consentKind?.let { put("consentKind", it) }
    }.toString()

    /**
     * 解码一条 v2 控制请求。[fileName] 与 v1 同规：`<id>.json`，文件名去后缀即
     * 本条请求 id 的唯一可信来源，正文自称的 id 与之不符整条拒绝。
     *
     * 控制请求与能力请求同样来自沙盒、同样不可信，长度、深度、id 白名单、op 白名单
     * 一项不少；另加两条控制通道特有的校验：
     * - `payloadDigest` 必须是 64 位小写十六进制。摘要参与「同一件事」的判定，
     *   形状不收紧，摘要比对就会被大小写与缩写绕开；
     * - `submit` 的能力名必须落在封闭集合内。控制通道**绝不能执行能力动作**，
     *   未知能力在这里就拒，不让它进入后续任何裁决。
     */
    fun decodeControl(text: String, fileName: String): ControlDecodeResult {
        val expectedId = fileName.removeSuffix(JSON_SUFFIX)
        if (text.length > MAX_CONTROL_CHARS) {
            return ControlDecodeResult.Rejected(expectedId, RelayError.REQUEST_TOO_LARGE, "oversized")
        }
        if (nestingDepth(text) > MAX_DEPTH) {
            return ControlDecodeResult.Rejected(expectedId, RelayError.TRANSPORT_MALFORMED, "too deep")
        }
        val json = runCatching { JSONObject(text) }.getOrElse {
            return ControlDecodeResult.Rejected(expectedId, RelayError.TRANSPORT_MALFORMED, "not an object")
        }
        val id = json.optString("id")
        if (!isValidId(id)) {
            return ControlDecodeResult.Rejected(expectedId, RelayError.TRANSPORT_MALFORMED, "bad id")
        }
        if (id != expectedId) {
            return ControlDecodeResult.Rejected(expectedId, RelayError.TRANSPORT_MALFORMED, "id mismatch")
        }
        if (json.optInt("v") != RELAY_PROTOCOL_VERSION_V2) {
            return ControlDecodeResult.Rejected(expectedId, RelayError.TRANSPORT_MALFORMED, "version")
        }
        return when (json.optString("op")) {
            "submit" -> decodeSubmit(id, json)
            "status" -> decodeTargeted(id, json, cancel = false)
            "cancel" -> decodeTargeted(id, json, cancel = true)
            "health" -> ControlDecodeResult.Valid(ControlRequest.Health(id))
            "ask" -> decodeAsk(id, json)
            else -> ControlDecodeResult.Rejected(expectedId, RelayError.TRANSPORT_MALFORMED, "op")
        }
    }

    private fun decodeSubmit(id: String, json: JSONObject): ControlDecodeResult {
        val capability = CapabilityId.fromWire(json.optString("capability"))
            ?: return ControlDecodeResult.Rejected(id, RelayError.CAPABILITY_NOT_IMPLEMENTED, "unknown capability")
        val args = json.optJSONObject("args")
            ?: return ControlDecodeResult.Rejected(id, RelayError.TRANSPORT_MALFORMED, "args missing")
        val digest = json.optString("payloadDigest")
        if (!DIGEST_PATTERN.matches(digest)) {
            return ControlDecodeResult.Rejected(id, RelayError.TRANSPORT_MALFORMED, "digest")
        }
        // 宿主有效期只影响宿主自己排多久队：夹进上限防止一次提交把资源钉到天荒地老，
        // 夹进下限防止 0 值把请求在入队瞬间判成过期。
        val ttl = json.optLong("hostTtlMs").takeIf { it > 0 }
            ?.coerceIn(MIN_TTL_MS, MAX_TTL_MS) ?: DEFAULT_TTL_MS
        return ControlDecodeResult.Valid(
            ControlRequest.Submit(id = id, capability = capability, args = args, hostTtlMs = ttl, payloadDigest = digest),
        )
    }

    private fun decodeTargeted(id: String, json: JSONObject, cancel: Boolean): ControlDecodeResult {
        val targetId = json.optString("targetId")
        if (!isValidId(targetId)) {
            return ControlDecodeResult.Rejected(id, RelayError.TRANSPORT_MALFORMED, "target")
        }
        val request = if (cancel) ControlRequest.Cancel(id, targetId) else ControlRequest.Status(id, targetId)
        return ControlDecodeResult.Valid(request)
    }

    /**
     * ask 的形状校验：问题与每个选项都非空且有长度上限（卡面只放得下这么多，
     * 超长文案在悬浮卡上会被截成半句话，不如当场拒绝让助手改短）；选项个数严格 2..3
     * ——少于两个没有可选择性，多于三个按钮挤不下一屏；超时夹进宿主侧认可区间。
     */
    private fun decodeAsk(id: String, json: JSONObject): ControlDecodeResult {
        val question = json.optString("question").trim()
        if (question.isEmpty() || question.length > MAX_ASK_QUESTION_CHARS) {
            return ControlDecodeResult.Rejected(id, RelayError.TRANSPORT_MALFORMED, "question")
        }
        val array = json.optJSONArray("options")
            ?: return ControlDecodeResult.Rejected(id, RelayError.TRANSPORT_MALFORMED, "options")
        if (array.length() < MIN_ASK_OPTIONS || array.length() > MAX_ASK_OPTIONS) {
            return ControlDecodeResult.Rejected(id, RelayError.TRANSPORT_MALFORMED, "options count")
        }
        // 逐个元素判类型：org.json 的 getString 会把非字符串元素 toString 成 "1" 之类，
        // 于是 [1,2] 也能混过"字符串数组"这道闸——宿主比入口更宽松时，手写请求文件的人
        // 看不出自己写错了类型，直到某个选项以数字形状出现在用户眼前。
        val options = (0 until array.length()).map { index ->
            val item = array.opt(index)
            if (item !is String) {
                return ControlDecodeResult.Rejected(id, RelayError.TRANSPORT_MALFORMED, "options shape")
            }
            item.trim()
        }
        if (options.any { it.isEmpty() || it.length > MAX_ASK_OPTION_CHARS }) {
            return ControlDecodeResult.Rejected(id, RelayError.TRANSPORT_MALFORMED, "option text")
        }
        val timeout = json.optLong("timeoutMs").takeIf { it > 0 }
            ?.coerceIn(MIN_ASK_TIMEOUT_MS, MAX_ASK_TIMEOUT_MS) ?: DEFAULT_ASK_TIMEOUT_MS
        return ControlDecodeResult.Valid(
            ControlRequest.Ask(id = id, question = question, options = options, timeoutMs = timeout),
        )
    }

    /**
     * 编码一条 v2 控制响应。整体形状 `{"v":2,"id":<控制请求id>,"op":<回显的op>,"ok":bool,...payload}`；
     * [payload] 的键按 op 约定，控制服务按下表产出（字段名与 [ControlChannelServer] 的实现一致）：
     *
     * - submit 回执：`targetId`（被受理的请求 id）、`state`（受理时的阶段；终态帧同键覆盖）、
     *   `digest`（回显摘要，调用方据此核对没被偷换参数）；拒绝时 `cause` 说原因。
     * - status 读数：`targetId`、`found`、`state`、`result`（终态完整回包 JSON，仅终态且未过期时有）、
     *   `mayHaveDispatched`、`cancelRequested`；保留期届满 `state` 为 `EXPIRED`、无 `result`，
     *   过期不当未执行。
     * - cancel 结论：`targetId`、`outcome`（`CANCELLED`／`CANCEL_REQUESTED`／`COMPLETED`／
     *   `UNKNOWN`／`NOT_FOUND`，部分结论另带 `state` 或 `provablyUndispatched`）。
     *   只有宿主证实尚未提交动作才给 `CANCELLED`，其余一律不说「已撤销」。
     * - health 快照：`channelLive`、`queueDepth`、`oldestQueuedAgeMs`。
     */
    fun encodeControl(id: String, op: String, ok: Boolean, payload: JSONObject): String = JSONObject().apply {
        put("v", RELAY_PROTOCOL_VERSION_V2)
        put("id", id)
        put("op", op)
        put("ok", ok)
        payload.keys().asSequence().forEach { key -> put(key, payload.opt(key)) }
    }.toString()

    /** 线性扫描括号嵌套，不依赖 org.json 自我保护——它没有深度上限。 */
    private fun nestingDepth(text: String): Int {
        var depth = 0
        var max = 0
        var inString = false
        var escaped = false
        for (ch in text) {
            when {
                inString -> when {
                    escaped -> escaped = false
                    ch == '\\' && inString -> escaped = true
                    ch == '"' -> inString = false
                }

                ch == '"' -> inString = true
                ch == '{' || ch == '[' -> {
                    depth++
                    if (depth > max) max = depth
                }

                ch == '}' || ch == ']' -> depth--
            }
        }
        return max
    }

    const val MAX_REQUEST_BYTES = 256L * 1024L

    /** 响应体上限（字符数）。请求侧有封顶而响应侧没有，宿主就能被一次读取撑到 OOM。 */
    const val MAX_RESPONSE_CHARS = 512_000

    /**
     * 控制请求正文上限（字符数）。控制通道要在主循环之前优先服务，封顶必须比能力
     * 请求紧得多：一条探活请求与一条参数齐全的提交没有理由共享同一个上限。
     */
    const val MAX_CONTROL_CHARS = 64_000

    const val JSON_SUFFIX = ".json"
    private const val MAX_JSON_CHARS = 200_000
    private const val MAX_DEPTH = 24
    private const val MIN_TTL_MS = 1_000L
    private const val MAX_TTL_MS = 120_000L
    private val DEFAULT_TTL_MS = RelayRequest.DEFAULT_TIMEOUT_MS.coerceIn(MIN_TTL_MS, MAX_TTL_MS)

    /**
     * ask 的卡面容量：问题 200 字、单选项 60 字（卡上一屏读得完），2..3 个选项。
     *
     * 这四个数在入口 CLI 里各有一份（`ASK_MAX_QUESTION_CHARS` 等），由
     * `AskContractTest.askLengthLimitsMatchBetweenHostAndEntry` 逐条比对——两侧不一致时，
     * 入口放过的请求会被宿主整条拒掉，而入口只能把它报成 exit 6 的"宿主内部故障"。
     */
    internal const val MAX_ASK_QUESTION_CHARS = 200
    internal const val MAX_ASK_OPTION_CHARS = 60
    internal const val MIN_ASK_OPTIONS = 2
    internal const val MAX_ASK_OPTIONS = 3
    internal const val MIN_ASK_TIMEOUT_MS = 5_000L
    internal const val MAX_ASK_TIMEOUT_MS = 120_000L
    internal const val DEFAULT_ASK_TIMEOUT_MS = 60_000L

    /** 参数摘要形状：64 位小写十六进制。大小写与缩写都会让「同一件事」的比对失真。 */
    private val DIGEST_PATTERN = Regex("[0-9a-f]{64}")
}
