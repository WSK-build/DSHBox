package interlock.relay.core.exec.stream

/**
 * 面板输入的**纯判据**：动作白名单、归一化坐标、时长与限速。JVM 可测，不碰
 * socket / 设备。
 *
 * 语义边界：这是用户自己面板（虚拟屏预览）的远程操作，**不走  受保护名单、不走
 * gate/档位/审批** —— 鉴权在控制 token（宿主）+ tab token（Node 半）+ 会话存活，
 * 动作面刻意收窄到 tap/swipe/back/home 四种，没有文字、组合键与复杂手势。
 */
object PanelInputPolicy {
    val ACTIONS = setOf("tap", "swipe", "back", "home")
    const val MIN_DURATION_MS = 50
    const val MAX_DURATION_MS = 1000
    const val DEFAULT_DURATION_MS = 300

    /** 每会话限速：滑动窗口内最多这么多次输入，超出 429。 */
    const val RATE_LIMIT_PER_SECOND = 20  // 每会话最多 20 次/秒；超限拒绝，执行并发进池，排队不占 accept

    /** 一次面板输入的解析读数（坐标归一化 0..1；back/home 无坐标）。 */
    data class Request(
        val action: String,
        /** Node 半抓住的会话 id：端点按 /stop 同口径对账。 */
        val sessionId: String? = null,
        val x: Double? = null,
        val y: Double? = null,
        val x1: Double? = null,
        val y1: Double? = null,
        val x2: Double? = null,
        val y2: Double? = null,
        val durationMs: Int? = null,
    )

    /**
     * 校验：回错误短句（人读，原样进 400 响应），合法回 null。
     *
     * - 动作必须在白名单内；
     * - tap 必须带 x/y，swipe 必须带 x1/y1/x2/y2（back/home 不带坐标）；
     * - 出现的坐标都必须在 0..1（NaN/无穷按越界拒）；
     * - swipe 的 durationMs 必须在 [MIN_DURATION_MS]..[MAX_DURATION_MS]；
     * tap/back/home 不看 duration（客户端也不会发）。
     */
    fun validate(request: Request): String? {
        if (request.action !in ACTIONS) {
            return "unknown action \"${request.action}\"; accepted: ${ACTIONS.joinToString(", ")}"
        }
        val coords = when (request.action) {
            "tap" -> listOf("x" to request.x, "y" to request.y)
            "swipe" -> listOf("x1" to request.x1, "y1" to request.y1, "x2" to request.x2, "y2" to request.y2)
            else -> emptyList()
        }
        for ((name, value) in coords) {
            if (value == null || !value.isFinite() || value < 0.0 || value > 1.0) {
                return "$name must be a number in 0..1 (normalized)"
            }
        }
        if (request.action == "swipe") {
            val duration = request.durationMs
            if (duration == null || duration < MIN_DURATION_MS || duration > MAX_DURATION_MS) {
                return "durationMs must be in $MIN_DURATION_MS..$MAX_DURATION_MS"
            }
        }
        return null
    }

    /** 归一化坐标 → 像素（夹到 0..size-1）。尺寸未知（≤0）回 null，调用方拒发。 */
    fun pixel(normalized: Double, size: Int): Int? {
        if (size <= 0) return null
        if (!normalized.isFinite() || normalized < 0.0 || normalized > 1.0) return null
        return (normalized * size).toInt().coerceIn(0, size - 1)
    }

    /** 映射到像素后的输入（执行面拿到的就是它，直接拼 `input -d …` 命令）。 */
    data class Command(
        val action: String,
        val x: Int? = null,
        val y: Int? = null,
        val x1: Int? = null,
        val y1: Int? = null,
        val x2: Int? = null,
        val y2: Int? = null,
        val durationMs: Int? = null,
    )

    /**
     * 归一化请求 → 像素命令。尺寸按**虚拟屏源尺寸**（可信屏的 TrustedDisplay.size()，
     * 宿主侧经会话 metadata 的 sourceWidth/sourceHeight 带过来）；任一需要的维度未知
     * （≤0）回 null，调用方按 400 拒。
     */
    fun map(request: Request, sourceWidth: Int, sourceHeight: Int): Command? {
        when (request.action) {
            "tap" -> {
                val nx = request.x ?: return null
                val ny = request.y ?: return null
                val x = pixel(nx, sourceWidth) ?: return null
                val y = pixel(ny, sourceHeight) ?: return null
                return Command("tap", x = x, y = y)
            }
            "swipe" -> {
                val x1 = pixel(request.x1 ?: return null, sourceWidth) ?: return null
                val y1 = pixel(request.y1 ?: return null, sourceHeight) ?: return null
                val x2 = pixel(request.x2 ?: return null, sourceWidth) ?: return null
                val y2 = pixel(request.y2 ?: return null, sourceHeight) ?: return null
                return Command("swipe", x1 = x1, y1 = y1, x2 = x2, y2 = y2, durationMs = request.durationMs)
            }
            else -> return Command(request.action)
        }
    }

    /**
     * 从 POST body 提取请求。body 由本仓库的 Node 半自己写出（扁平 JSON、键名固定），
     * 这里手动扫键值 —— org.json 在 JVM 单测里是平台桩，解析判据要可测就得不依赖它。
     * 解析不出 action 回 null（调用方按 400 拒）。
     */
    fun parse(body: String): Request? {
        fun text(key: String): String? =
            Regex("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
        fun num(key: String): Double? =
            Regex("\"" + key + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)").find(body)?.groupValues?.get(1)?.toDoubleOrNull()
        val action = text("action") ?: return null
        val duration = Regex("\"durationMs\"\\s*:\\s*(-?\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
        return Request(
            action,
            text("sessionId"),
            num("x"), num("y"),
            num("x1"), num("y1"), num("x2"), num("y2"),
            duration,
        )
    }

    /**
     * 每会话限速器（滑动窗口，JVM 可测）。执行并发进 serve 池，
     * tryAcquire 可能被多工人同时调：内部同步；换届用 [reset] 清账（新会话从零计数）。
     */
    class RateLimiter(private val limit: Int = RATE_LIMIT_PER_SECOND, private val windowMs: Long = 1000L) {
        private val hits = ArrayDeque<Long>()

        /** 回 true = 放行；false = 本窗口已满（调用方回 429）。 */
        @Synchronized
        fun tryAcquire(nowMs: Long): Boolean {
            while (hits.isNotEmpty() && nowMs - hits.first() >= windowMs) hits.removeFirst()
            if (hits.size >= limit) return false
            hits.addLast(nowMs)
            return true
        }

        @Synchronized
        fun reset() = hits.clear()
    }
}
