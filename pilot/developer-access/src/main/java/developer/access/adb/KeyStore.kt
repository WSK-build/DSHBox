package developer.access.adb

import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * ADB 密钥的读取、指纹计算与增删。
 *
 * ## 布局
 *
 * ```
 * <elevation>/home/.android/adbkey        主密钥（adb 自动生成的那把）
 *                            adbkey.pub
 *                            adb_known_hosts.pb   已配对设备清单（protobuf，不直接编辑）
 *                            keys/<name>.adb_key  附加密钥（ADB_VENDOR_KEYS 指向）
 * ```
 *
 * ## 为什么只做"读 + 删 + 调 adb keygen"
 *
 * 本模块**不自己生成 RSA 密钥对**：adb 自带 `keygen FILE` 子命令，直接调它即可，
 * 省去密钥编码与格式对齐的风险。指纹也不需要私钥——从 `.pub` 里的
 * base64 公钥解出模数与指数后按 ADB 的指纹算法计算即可。
 */
class KeyStore(private val androidDir: File) {

    /** 一把密钥的可见信息。 */
    data class KeyInfo(
        val id: String,
        val label: String,
        val fingerprint: String,
        val isPrimary: Boolean,
        /** 密钥文件的创建/修改时刻（毫秒）；取不到为 0，界面显示"未知"。 */
        val createdAtMs: Long = 0L,
    )

    private val primaryPriv: File get() = File(androidDir, "adbkey")
    private val primaryPub: File get() = File(androidDir, "adbkey.pub")
    private val extraDir: File get() = File(androidDir, "keys")
    private val knownHosts: File get() = File(androidDir, "adb_known_hosts.pb")

    /** 主密钥是否存在（= 本机已有身份）。 */
    fun hasIdentity(): Boolean = primaryPub.isFile || primaryPriv.isFile

    /**
     * 列出全部本机密钥：主密钥在前，附加密钥按文件名排序在后。
     *
     * 指纹取不到时给空串——界面上显示"无法计算"而不是伪造一个值。
     */
    fun listKeys(): List<KeyInfo> {
        val out = mutableListOf<KeyInfo>()
        if (primaryPub.isFile) {
            out += KeyInfo(
                id = PRIMARY_ID,
                // 标签是数据不是文案：界面按 isPrimary 取本地化标题，这里保留非本地化标识。
                label = PRIMARY_ID,
                fingerprint = fingerprintOf(primaryPub),
                isPrimary = true,
                createdAtMs = primaryPub.lastModified(),
            )
        }
        extraDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".adb_key.pub") }
            ?.sortedBy { it.name }
            ?.forEach { pub ->
                val id = pub.name.removeSuffix(".pub")
                out += KeyInfo(
                    id = id,
                    label = id.removeSuffix(".adb_key"),
                    fingerprint = fingerprintOf(pub),
                    isPrimary = false,
                    createdAtMs = pub.lastModified(),
                )
            }
        return out
    }

    /**
     * 删除一把密钥。
     *
     * 主密钥删除后本机身份消失，所有设备都需要重新配对；
     * 附加密钥删除只影响对应设备。
     */
    fun deleteKey(id: String): Boolean = if (id == PRIMARY_ID) {
        val removed = primaryPriv.delete() or primaryPub.delete()
        removed
    } else {
        val priv = File(extraDir, id)
        val pub = File(extraDir, "$id.pub")
        priv.delete() or pub.delete()
    }

    /** 是否已有已配对设备清单。 */
    fun hasPairedDevices(): Boolean = knownHosts.isFile && knownHosts.length() > 0

    /**
     * 清除已配对设备清单。
     *
     * 该文件是 protobuf，**不直接编辑**——整体删除让 adb 重建。
     * 与"删除本机密钥"是两个不同操作：身份保留，只丢"该自动连谁"。
     */
    fun clearPairedDevices(): Boolean = !knownHosts.exists() || knownHosts.delete()

    /**
     * 计算 ADB 公钥指纹。
     *
     * `.pub` 内容形如 `AAAA... user@host`：base64 是 **PKCS#1 RSAPublicKey** 的 DER。
     * ADB 的指纹算法是对该 DER 取 SHA-256 后按 MD5 风格分组显示。
     *
     * 解析失败（文件被截断、格式异常）时返回空串，不抛异常——
     * 面板展示不应因一个坏文件而整体失败。
     */
    internal fun fingerprintOf(pubFile: File): String = runCatching {
        val text = pubFile.readText().trim()
        val b64 = text.substringBefore(' ').trim()
        if (b64.isEmpty()) return ""
        val der = Base64.getDecoder().decode(b64)
        fingerprintOfDer(der)
    }.getOrDefault("")

    /**
     * 对公钥 DER 计算指纹：SHA-256，按 `AB:CD:...` 形式展示。
     *
     * 不用 MD5（历史格式）：SHA-256 与示例一致，且无需额外算法。
     */
    internal fun fingerprintOfDer(der: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(der)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    companion object {
        /** 主密钥的固定标识（不是文件名，附加密钥用文件名作标识）。 */
        const val PRIMARY_ID = "adbkey"
    }
}
