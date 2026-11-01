package developer.access.adb

/**
 * 配对/连接的结果。
 *
 * 失败必须能区分原因：码过期、地址不通、设备不在同一网络，三种情况的下一步完全不同。
 *
 * ## 为什么配对与连接是两个动作
 *
 * 无线调试的**配对端口**与**连接端口**是两个不同的 mDNS 服务：
 * 配对走 `_adb-tls-pairing._tcp`，连接走 `_adb-tls-connect._tcp`。
 * 配对成功只代表公钥进了设备的 `adb_keys`，**不等于已连接**——
 * 还必须对连接端口执行一次 `adb connect`。故结果类型覆盖两步的失败。
 */
sealed interface PairingResult {
    data object Success : PairingResult

    /** 配对码错误或已过期（配对弹窗关闭或超时后码失效）。 */
    data object CodeRejected : PairingResult

    /** 地址不可达（不在同一网络 / 无线调试已关闭）。 */
    data object Unreachable : PairingResult

    /** 已配对但连接未建立——需要用户从无线调试主界面确认连接端口。 */
    data object ConnectFailed : PairingResult

    /** 其他失败，附原始输出便于排查。 */
    data class Failed(val detail: String) : PairingResult
}

/**
 * 配对码的校验。
 *
 * 只做**形状**校验（6 位数字）：码本身由系统生成，无需也不该在本端验证其"正确性"——
 * 错了由设备拒绝，那才是唯一权威。
 */
object PairingCode {

    const val LENGTH = 6

    /** 是否为完整的 6 位数字（用于"填满即提交"的判定）。 */
    fun isComplete(raw: String): Boolean =
        raw.length == LENGTH && raw.all { it in '0'..'9' }

    /** 只保留数字，并截断到 6 位（用户可能粘入分隔符或多余字符）。 */
    fun normalize(raw: String): String = raw.filter { it in '0'..'9' }.take(LENGTH)
}

/**
 * 把 adb 的输出文本分成 [PairingResult]。
 *
 * adb 没有机器可读的错误码，只能按文案判断；因此这里集中一份匹配规则，并配单测，
 * 避免"码过期被说成泛化失败"这类与"失败必须给出可操作原因"相悖的提示。无法归类时
 * 原样保留输出，面板会把原文附在失败提示里，便于用户与开发者判断。
 */
object AdbOutput {

    /** adb 成功文案：配对是 `Successfully paired to …`，连接是 `connected to …`。 */
    private val CONNECTED = Regex("""connected to\s+\S+""")

    /**
     * 本次 adb 调用是配对还是连接。
     *
     * 同一个失败文案在两种动作下含义不同：`adb pair` 的 `protocol fault` 是设备在
     * 配对协议里拒绝了这次握手（设备上验证：输错配对码时客户端只打出
     * `protocol fault (couldn't read status message): No error`，没有 "incorrect code"），
     * 而 `adb connect` 的同一句话则说明对端不是可用的连接服务。
     * 不区分动作会把配对码错误误判为地址不可达（该行为已在设备上验证）。
     */
    enum class Operation { PAIR, CONNECT }

    /**
     * 把 adb 输出文本分成 [PairingResult]。
     *
     * `adb pair` 的失败文本有区分度（已在设备上确认）：
     * - 地址不可达（端口未监听或端口填写错误）→ `failed to connect to …`；
     * - 已连到配对服务、握手被拒（配对码错误或过期）→ `protocol fault`。
     *
     * 因此配对动作下的 `protocol fault` 判定为配对码被拒；连接动作下同一句话表示
     * 对端不是可用的连接服务，归为不可达。无法归类时原样保留输出，
     * 面板会把原文附在失败提示里，便于用户与开发者判断。
     */
    fun classify(
        text: String,
        operation: Operation = Operation.PAIR,
    ): PairingResult {
        val lower = text.lowercase()
        return when {
            lower.contains("successfully paired") -> PairingResult.Success
            CONNECTED.containsMatchIn(lower) -> PairingResult.Success
            // 配对码问题：过期、错误、认证失败。部分系统直接给 "… incorrect code"。
            lower.contains("incorrect") ||
                lower.contains("wrong") ||
                lower.contains("expired") ||
                lower.contains("failed to authenticate") ||
                (lower.contains("failed to pair") && lower.contains("code")) ->
                PairingResult.CodeRejected
            // 配对动作下的 protocol fault：连接已到达配对服务，握手被拒。
            lower.contains("protocol fault") && operation == Operation.PAIR ->
                PairingResult.CodeRejected
            // 网络不可达：拒绝连接、路由不通、超时；连接动作下 protocol fault 也归此类。
            lower.contains("unable to connect") ||
                lower.contains("cannot connect") ||
                lower.contains("failed to connect") ||
                lower.contains("no route to host") ||
                lower.contains("connection refused") ||
                lower.contains("protocol fault") ||
                lower.contains("timed out") ->
                PairingResult.Unreachable
            else -> PairingResult.Failed(text.trim().take(200))
        }
    }
}
