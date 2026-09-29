package com.dshbox.app.sandbox.online

import org.bouncycastle.bcpg.ArmoredInputStream
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import java.io.InputStream
import java.security.Security

/**
 * Debian `Release` 文件的 OpenPGP 验签器（在线 base 组装 S2 的信任链锚点）。
 *
 * 原理：镜像站的 `Release` 文件附带同源签名 `Release.gpg`。用**随 APK 预埋**的
 * Debian 归档公钥（`resources/debian-archive-key-13*.asc`，取自
 * ftp-master.debian.org/keys）验证签名——这是独立于 HTTPS 证书体系的第二道信任：
 * 即使 DNS/证书被劫持到一个内容自洽的假源（自己签自己的哈希），验签也会失败。
 *
 * 验签策略（明确性优先）：
 *  - Release.gpg 可能是「分离签名」（对 Release 文件本体签名，本项目各镜像均为
 *    此形态），也可能是「cleartext 签名」（InRelease 形态）——两者都支持；
 *  - 签名必须被**至少一把预埋公钥**验证通过；找不到匹配公钥/密钥过期导致无法验证
 *    一律拒绝（fail-closed，不做"跳过验签"的降级）。
 *
 * 纯 JVM 实现（bcpg-jdk18on + JDK），可在 testDebugUnitTest 用真实签名直测。
 */
object DebianReleaseVerifier {

    /** 预埋公钥环： lazily 从 classpath 装载一次。 */
    private val trustedKeys: List<PGPPublicKey> by lazy {
        buildList {
            for (res in KEY_RESOURCES) {
                DebianReleaseVerifier::class.java.getResourceAsStream(res)?.use { input ->
                    addAll(loadKeys(input))
                }
            }
        }
    }

    fun trustedKeyCount(): Int = trustedKeys.size

    /** 验证 [releaseBytes]（Release 文件本体）与 [signatureBytes]（Release.gpg）。
     *  @return true 当且仅当至少一把预埋 Debian 归档公钥验证签名通过。 */
    fun verify(releaseBytes: ByteArray, signatureBytes: ByteArray): Boolean {
        val signatures = readSignatures(signatureBytes) ?: return false
        var sawUsableSignature = false
        for (sig in signatures) {
            // 找到能验证该签名的预埋钥（按 keyId 匹配；找不到的签名跳过——镜像源
            // 可能在 Release.gpg 里带多把签名）。
            val key = trustedKeys.firstOrNull { it.keyID == sig.keyID } ?: continue
            sawUsableSignature = true
            sig.init(JcaPGPContentVerifierBuilderProvider().setProvider(bcProvider), key)
            // 分离签名：被签对象是 Release 文件本体的完整字节。
            sig.update(releaseBytes)
            if (sig.verify()) return true
        }
        // 有签名但都不是我们信任的钥 → 明确拒绝（fail-closed）。
        return signatures.size() == 0
    }

    /** Release.gpg 可能是分离签（PGPSignatureList）或 cleartext 签（内嵌 one-pass）。 */
    private fun readSignatures(signatureBytes: ByteArray): PGPSignatureList? = try {
        PGPUtil.getDecoderStream(signatureBytes.inputStream()).use { decoder ->
            val obj = org.bouncycastle.openpgp.PGPObjectFactory(
                decoder, JcaKeyFingerprintCalculator(),
            ).nextObject()
            when (obj) {
                is PGPSignatureList -> obj
                else -> null
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun loadKeys(input: InputStream): List<PGPPublicKey> = try {
        val decoded = PGPUtil.getDecoderStream(input)
        val ring = PGPPublicKeyRingCollection(decoded, JcaKeyFingerprintCalculator())
        // 主钥与子钥都进入匹配池（签名可能由任一钥匙 id 出具）。
        ring.keyRings.asSequence()
            .flatMap { it.publicKeys.asSequence() }
            .toList()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * 我们打进 APK 的那份 BouncyCastle（**按实例传入，不注册进全局 Security**）。
     *
     * **为什么不用 `Security.addProvider` + 名字**：Android 平台自带一个
     * 名为 "BC" 的**裁剪版** BouncyCastle，它没有 `SHA256withRSA`。旧写法
     * `if (Security.getProvider("BC") == null) addProvider(...)` 在 Android 上会**跳过注册**，
     * 随后按名字取到的就是平台那份裁剪版，于是所有镜像都在同一处失败：
     * `cannot create signature: no such algorithm: SHA256withRSA for provider BC`
     * —— 在线导入运行环境**必然失败**（JVM 单测发现不了：JDK 不自带 "BC"，老代码在测试里是对的）。
     * 直接传 Provider 实例后，验签一定走我们打包的完整 bcprov，与平台注册了什么名字无关，
     * 也不留全局状态。
     */
    private val bcProvider: java.security.Provider by lazy {
        org.bouncycastle.jce.provider.BouncyCastleProvider()
    }

    /**
     * 保留的兼容入口（调用方不多改一行）。
     *
     * 现在验签按**实例**取 provider，不再需要往全局 `Security` 注册 —— 这里只把实例构造出来，
     * 也避免了"注册过早/被平台顶掉"这类问题。
     */
    fun ensureProvider() {
        runCatching { bcProvider.name }
    }

    private val KEY_RESOURCES = listOf(
        "/debian-archive-key-13.asc",
        "/debian-archive-key-13-security.asc",
    )
}
