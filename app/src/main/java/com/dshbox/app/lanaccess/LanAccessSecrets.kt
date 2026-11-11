package com.dshbox.app.lanaccess

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 局域网访问的密钥与门禁 cookie 签名（纯函数，JVM 单测锁定）。
 *
 * 访问密钥：URL-safe 随机串（[KEY_BYTES] 字节熵，base64url 编码），生成后
 * 持久化；重置即换成新值，**旧 cookie 立即失效**（cookie 值是对旧密钥的
 * HMAC，对不上新密钥的 HMAC）。
 *
 * 门禁 cookie（`dshbox_lan`）：值 = `HMAC-SHA256(key=密钥, msg=固定标签)`。
 * 只有持有密钥的网关能算出该值——不持有密钥的局域网访客无法伪造成
 * 功；密钥轮换后旧值校验不过，全部旧会话由这一致性灭失。比较走
 * [MessageDigest.isEqual] 常量时间路径（长度不等同样安全）。
 */
object LanAccessSecrets {

    /** 门禁 cookie 名：局域网浏览器凭此值免钥匙续访。 */
    const val COOKIE_NAME = "dshbox_lan"

    /** URL 中携带密钥的 query 参数名（`?k=<key>` 首跳兑换）。 */
    const val KEY_PARAM = "k"

    /** 新密钥的熵字节数（128 位；base64url 后 22 字符）。 */
    const val KEY_BYTES = 16

    private val SIGN_LABEL: ByteArray =
        "dshbox-lan-access-gate/v1".toByteArray(Charsets.UTF_8)

    /** base64url 字符集（ Religious + '-' '_'，等号去掉，URL/query 安全）。 */
    private val B64_URL = java.util.Base64.getUrlEncoder().withoutPadding()

    /** base64url 解码（容错：非法形态返回 null；不接受 padding）。 */
    private fun decode64(value: String): ByteArray? = runCatching {
        java.util.Base64.getUrlDecoder().decode(value)
    }.getOrNull()

    /** 生成新访问密钥：URL-safe、无 padding。 */
    fun generateKey(): String {
        val bytes = ByteArray(KEY_BYTES)
        SecureRandom().nextBytes(bytes)
        return B64_URL.encodeToString(bytes)
    }

    /**
     * 密钥合法性（接受用户输入/导入的 key 前先核验形态）：非空、合法
     * base64url、解码后长度不低于 [KEY_BYTES]。
     */
    fun isWellFormedKey(key: String): Boolean {
        if (key.isEmpty() || key.length < 20) return false
        val bytes = decode64(key) ?: return false
        return bytes.size >= KEY_BYTES
    }

    /** 密钥对应的有效门禁 cookie 值（`HMAC-SHA256(key, 标签)` base64url）。 */
    fun cookieValueFor(key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return B64_URL.encodeToString(mac.doFinal(SIGN_LABEL))
    }

    /**
     * 常量时间校验一枚门禁 cookie 值（any-match 语义：请求可能携带多头
     * 或同名 cookie 串，任一与期望签名相等即通过）。非法/不等一律 false。
     */
    fun verifyCookie(key: String, presentedValues: List<String>): Boolean {
        if (presentedValues.isEmpty()) return false
        val expected = cookieValueFor(key).toByteArray(Charsets.UTF_8)
        return presentedValues.any { candidate ->
            MessageDigest.isEqual(
                candidate.toByteArray(Charsets.UTF_8),
                expected,
            )
        }
    }
}
