package developer.access.root

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Root 通道的访问码。
 *
 * ## 为什么是 128 bit
 *
 * TCP 模式的传输是明文的，攻击者抓到一次握手（nonce 与 HMAC 应答）后可以**离线**穷举密钥空间——
 * 这条路完全不需要连服务端，故**服务端的失败限流对它无效**。
 * 密钥长度才是这条通道的真正防线：40 bit 在现代 GPU 上是分钟级穷举，128 bit 则不可行。
 *
 * HMAC 本身不可逆：即使抓包拿到 `HMAC(secret, nonce)`，也无法据此反推 secret；
 * 且 nonce 每次随机，**应答不可重放**。
 *
 * ## 表示形式
 *
 * 26 位 base32 承载 128 bit 熵（16 字节随机数 → base32 编码）。
 * 26 位码**不可能手输**，故只提供复制与二维码两种传递方式。
 */
object RootAuthCode {

    /**
     * Crockford Base32 字母表：32 个字符，去掉易混的 I / L / O / U。
     *
     * 必须恰好 32 项——5 bit 的取值域是 0..31，字母表少于 32 项会在编码时越界。
     * 保留 0 与 1（Crockford 的取舍：删掉 I/L/O/U 后，0/O、1/I 的歧义已不存在）。
     */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** 随机字节数：16 字节 = 128 bit。 */
    private const val RANDOM_BYTES = 16

    /** base32 编码后的字符数：16 字节 → 26 字符。 */
    const val CODE_LENGTH = 26

    /** 分组显示的组宽。 */
    private const val GROUP = 4

    private val random = SecureRandom()

    /**
     * 生成新的访问码。
     *
     * @return 26 位 base32 字符串。
     */
    fun generate(): String {
        val bytes = ByteArray(RANDOM_BYTES)
        random.nextBytes(bytes)
        return encode(bytes)
    }

    /**
     * 把随机字节编码为 base32 串。
     *
     * 自行实现而非用 [java.util.Base64]：标准 base32 保留 `=` 填充且含易混字符，
     * 而此处要的是**无填充、字符集去除易混字符**的形态，便于人工转录与二维码承载。
     */
    private fun encode(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1F
                out.append(ALPHABET[index])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            out.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return out.toString()
    }

    /**
     * 分组显示：便于人工核对与口头传达。
     *
     * @param code 原始访问码。
     */
    fun group(code: String): String =
        code.chunked(GROUP).joinToString("-")

    /**
     * 归一化：去分组符与空白、转大写，并按 Crockford 约定把易混字符映射回字母表内字符
     * （O→0、I/L→1）。用户从二维码或文本框转录时可能带入这些字符。
     */
    fun normalize(code: String): String =
        code.trim()
            .replace("-", "")
            .replace(" ", "")
            .uppercase()
            .replace('O', '0')
            .replace('I', '1')
            .replace('L', '1')

    /**
     * 计算挑战应答。
     *
     * @param secret 访问码。
     * @param nonce 服务端给出的随机串。
     * @return base64 编码的 HMAC-SHA256。
     */
    fun sign(secret: String, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(normalize(secret).toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return java.util.Base64.getEncoder()
            .encodeToString(mac.doFinal(nonce.toByteArray(Charsets.UTF_8)))
    }
}
