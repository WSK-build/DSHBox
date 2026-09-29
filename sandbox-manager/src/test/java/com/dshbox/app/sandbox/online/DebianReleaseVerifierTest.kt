package com.dshbox.app.sandbox.online

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * DebianReleaseVerifier 真实数据测试：fixture 为 deb.debian.org 的 trixie
 * `Release` + `Release.gpg`（官方归档钥签名）。预埋公钥
 * 与验签逻辑对真实签名链路生效即为本测试通过的前提。
 */
class DebianReleaseVerifierTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            DebianReleaseVerifier.ensureProvider()
        }

        private val release: ByteArray by lazy {
            DebianReleaseVerifierTest::class.java
                .getResourceAsStream("/release-trixie.bin")!!.readBytes()
        }
        private val signature: ByteArray by lazy {
            DebianReleaseVerifierTest::class.java
                .getResourceAsStream("/release-trixie.gpg")!!.readBytes()
        }
    }

    /**
     * **平台已有名为 "BC" 的裁剪版 provider 时，验签仍须成立**（Android 实况）。
     *
     * 这是回归锁：若只在 `getProvider("BC") == null` 时才注册我们那份，
     * 于是 Android 平台自带的 "BC"（没有 SHA256withRSA）被直接使用，所有镜像都报
     * `cannot create signature: no such algorithm: SHA256withRSA for provider BC`。
     * JVM 不带 "BC"，所以这条用例必须**自己造一个空壳 "BC"** 才能复现。
     */
    @Test
    fun survivesPlatformBcNameCollision() {
        // 先摘掉可能已注册的同名 provider：`Security.addProvider` 对同名是**拒绝**的，
        // 不先摘掉的话假 provider 装不上，这条用例就会变成"永远通过"的假保险。
        val original = java.security.Security.getProvider("BC")
        java.security.Security.removeProvider("BC")
        // 只占用名字、不提供任何算法 —— 等价于平台上那个裁剪版
        val decoy = object : java.security.Provider("BC", 1.0, "decoy platform BC") {}
        java.security.Security.addProvider(decoy)
        try {
            // 自证：假 provider 必须真的成为当前 "BC"，否则下面的断言没有意义
            org.junit.Assert.assertSame(
                "用例前提：假 BC 必须已占用该名字",
                decoy,
                java.security.Security.getProvider("BC"),
            )
            // 重放一次注册（真实启动顺序：先 ensureProvider，再验签）
            DebianReleaseVerifier.ensureProvider()
            assertTrue(
                "平台占用 BC 名字时，验签仍必须走我们打包的那份 bcprov",
                DebianReleaseVerifier.verify(release, signature),
            )
        } finally {
            java.security.Security.removeProvider("BC")
            if (original != null) java.security.Security.addProvider(original)
        }
    }

    @Test
    fun trustedKeysAreEmbedded() {
        assertTrue(
            "预埋公钥必须非空（resources/debian-archive-key-13*.asc）",
            DebianReleaseVerifier.trustedKeyCount() > 0,
        )
    }

    @Test
    fun verifiesGenuineSignature() {
        assertTrue(DebianReleaseVerifier.verify(release, signature))
    }

    @Test
    fun rejectsTamperedBody() {
        val tampered = release.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(DebianReleaseVerifier.verify(tampered, signature))
    }

    @Test
    fun rejectsGarbageSignature() {
        assertFalse(DebianReleaseVerifier.verify(release, "not a signature".toByteArray()))
        assertFalse(DebianReleaseVerifier.verify(release, ByteArray(0)))
    }

    @Test
    fun rejectsCrossPairing() {
        // 同一钥签名的 Release A 配 Release B 的 body（body 篡改的变体，本质同 rejectTamperedBody，
        // 但从"错配"角度再钉一次）。
        val swapped = release.copyOfRange(1, release.size)
        assertFalse(DebianReleaseVerifier.verify(swapped, signature))
    }
}
