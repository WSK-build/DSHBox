package developer.access.root

import org.json.JSONObject
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/**
 * Root 通道的帧协议。
 *
 * ## 帧格式
 *
 * 每帧 = **4 字节大端长度** + JSON 载荷。长度前缀使消息可分帧——
 * 命令输出可能很大且含任意字节，不能靠分隔符切分。
 *
 * ```
 * 请求帧   {"cmd":"<命令串>"}
 * 输出帧   {"stream":"stdout"|"stderr","data":"<base64 片段>"}
 * 结束帧   {"code":<退出码>}
 * ```
 *
 * 输出可分多帧，`code` 只在结束帧出现一次。
 *
 * ## 为什么 `data` 用 base64
 *
 * 命令输出可能是任意二进制（例如 `cat` 一张图片）。直接塞进 JSON 字符串会产生
 * 非法 UTF-8 导致解析失败；base64 保证任意字节无损承载。`cmd` 本身是文本，不做编码。
 *
 * ## 认证握手（TCP 模式）
 *
 * 明文传输下抓包可见握手内容，故：
 *
 * 1. 服务端发 `{"nonce":"<随机串>"}`
 * 2. 客户端发 `{"auth":"<base64(HMAC-SHA256(secret, nonce))>"}`
 * 3. 服务端比对后回 `{"ok":true}`，否则关闭连接
 *
 * **secret 永不上网**，nonce 每次随机故**应答不可重放**。
 * unix socket 模式跳过握手——对端 uid 已由内核经 SO_PEERCRED 给出，
 * 同 uid 即本 App 与沙箱，无需再验。
 */
object RootProtocol {

    /** 单帧载荷上限。防止对端声明超长帧耗尽内存。 */
    const val MAX_FRAME_BYTES = 4 * 1024 * 1024

    /** 帧类型标识（JSON 内的字段名）。 */
    const val KEY_CMD = "cmd"
    const val KEY_STREAM = "stream"
    const val KEY_DATA = "data"
    const val KEY_CODE = "code"
    const val KEY_NONCE = "nonce"
    const val KEY_AUTH = "auth"
    const val KEY_OK = "ok"
    const val KEY_ERROR = "error"

    /** 输出流标识。 */
    const val STREAM_STDOUT = "stdout"
    const val STREAM_STDERR = "stderr"

    /** 读取一帧；流结束返回 null。 */
    fun readFrame(input: InputStream): JSONObject? {
        val header = ByteArray(4)
        val read = readFully(input, header, 0, 4)
        if (read == 0) return null
        if (read != 4) throw EOFException("帧头不完整：$read/4 字节")
        val length = decodeLength(header)
        require(length in 1..MAX_FRAME_BYTES) { "帧长度非法：$length" }
        val payload = ByteArray(length)
        if (readFully(input, payload, 0, length) != length) throw EOFException("帧载荷不完整")
        return JSONObject(String(payload, Charsets.UTF_8))
    }

    /** 写一帧。 */
    fun writeFrame(output: OutputStream, json: JSONObject) {
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        require(payload.size in 1..MAX_FRAME_BYTES) { "帧载荷超出上限：${payload.size}" }
        output.write(encodeLength(payload.size))
        output.write(payload)
        output.flush()
    }

    /**
     * 把帧长度编码为 4 字节大端序。
     *
     * 与 [decodeLength] 成对，二者是帧协议的字节层核心：不依赖 JSON，
     * 因而不受单元测试环境对 `org.json` 的桩实现影响。
     */
    internal fun encodeLength(length: Int): ByteArray = byteArrayOf(
        (length ushr 24).toByte(),
        (length ushr 16).toByte(),
        (length ushr 8).toByte(),
        length.toByte(),
    )

    /** 从 4 字节大端序解出帧长度。 */
    internal fun decodeLength(header: ByteArray): Int {
        require(header.size >= 4) { "帧头至少 4 字节" }
        return ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
    }

    /** 构造请求帧。 */
    fun requestFrame(cmd: String): JSONObject = JSONObject().put(KEY_CMD, cmd)

    /** 构造输出帧。 */
    fun outputFrame(stream: String, bytes: ByteArray): JSONObject = JSONObject()
        .put(KEY_STREAM, stream)
        .put(KEY_DATA, Base64.getEncoder().encodeToString(bytes))

    /** 构造结束帧。 */
    fun endFrame(code: Int): JSONObject = JSONObject().put(KEY_CODE, code)

    /** 构造错误帧（协议层错误，非命令退出码）。 */
    fun errorFrame(message: String): JSONObject = JSONObject().put(KEY_ERROR, message)

    /** 构造认证挑战帧。 */
    fun nonceFrame(nonce: String): JSONObject = JSONObject().put(KEY_NONCE, nonce)

    /** 构造认证应答帧（客户端侧）。 */
    fun authFrame(signature: String): JSONObject = JSONObject().put(KEY_AUTH, signature)

    /** 构造认证结果帧。 */
    fun authResultFrame(ok: Boolean): JSONObject = JSONObject().put(KEY_OK, ok)

    /** 从输出帧取出字节；字段缺失返回 null。 */
    fun decodeData(frame: JSONObject): ByteArray? {
        val encoded = frame.optString(KEY_DATA, "")
        if (encoded.isEmpty()) return null
        return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        while (total < length) {
            val n = input.read(buffer, offset + total, length - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
