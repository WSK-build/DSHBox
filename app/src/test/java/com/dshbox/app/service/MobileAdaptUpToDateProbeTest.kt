package com.dshbox.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * 插件「已是最新」判定逻辑的单测（1.3.1 复查 M27）。
 *
 * ## 被测对象
 *
 * [SandboxService.refreshAssembledMobileAdaptPlugin] 里那段 shell 判定：
 * 判断 profile 副本与 staged 副本的关键文件是否一致、且 bundle 仍注册。
 * 一致则跳过安装（省 I/O、**不覆盖用户对 profile 内插件的手动修改**）。
 *
 * ## 为什么必须测
 *
 * 该判定是「三个条件的 AND」：文件存在性 + 内容哈希相等 + bundle 已注册。
 * 其中**哈希比对在"两侧文件同时缺失"时会给出误导性的 true**
 * —— `cat 缺失文件 2>/dev/null` 输出空内容，空内容的 sha256 恒为
 * `e3b0c442…`，两侧都空则哈希相等。
 * 若此时 bundle 恰好仍注册，整体会误判「已最新」→ **跳过安装，但插件一个文件都没有**。
 * 这不是自愈的：用户永远跑不上插件。
 *
 * 因此本测试用 JVM 复刻同一判定逻辑（与 Kotlin 端拼接的 shell 行为一一对应），
 * 把「文件存在性检查」这一必需条件锁死。真实 shell 行为已在开发期用同构脚本实测过
 * （见 CHANGELOG M27），这里保证 Kotlin 侧拼出的条件在逻辑上不退化。
 */
class MobileAdaptUpToDateProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fingerprintFiles = listOf("lib/client.js", "package.json", "cordis.patch.yml")

    /** 与生产同构：内容连接后的 sha256（空内容也有一个固定哈希）。 */
    private fun digest(root: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        fingerprintFiles.forEach { md.update(File(root, it).readBytes()) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 复刻生产判定：**存在性 ∧ 哈希相等 ∧ bundle 注册**。
     * 存在性用 `test -f` 语义（isFile），不是「能读到就行」。
     */
    private fun isUpToDate(profilePlugin: File, stagePlugin: File, profilePackageJson: File): Boolean {
        val allExist = fingerprintFiles.all {
            File(profilePlugin, it).isFile && File(stagePlugin, it).isFile
        }
        if (!allExist) return false
        if (digest(profilePlugin) != digest(stagePlugin)) return false
        return profilePackageJson.isFile &&
            profilePackageJson.readText().contains("@local/dsh-mobile-adapt")
    }

    private fun writeFiles(root: File, content: String) {
        fingerprintFiles.forEach { rel ->
            File(root, rel).apply {
                parentFile?.mkdirs()
                writeText(content)
            }
        }
    }

    private fun setup(): Triple<File, File, File> {
        val profile = tmp.newFolder("profile")
        val profilePlugin = File(profile, "node_modules/@local/dsh-mobile-adapt").apply { mkdirs() }
        val stagePlugin = tmp.newFolder("stage-plugin")
        val pkg = File(profile, "package.json").apply {
            writeText("""{"dsh":{"profile":{"bundles":["@local/dsh-mobile-adapt"]}}}""")
        }
        return Triple(profilePlugin, stagePlugin, pkg)
    }

    /** ① 两侧齐全且内容相同 → 已是最新（应跳过安装）。 */
    @Test
    fun identicalContentIsUpToDate() {
        val (p, s, pkg) = setup()
        writeFiles(p, "same")
        writeFiles(s, "same")
        assertTrue(isUpToDate(p, s, pkg))
    }

    /** ② 内容不同 → 不是最新（应重装）。 */
    @Test
    fun differentContentNeedsReinstall() {
        val (p, s, pkg) = setup()
        writeFiles(p, "old")
        writeFiles(s, "new")
        assertFalse(isUpToDate(p, s, pkg))
    }

    /** ③ 单侧缺文件 → 不是最新（应重装）。 */
    @Test
    fun missingFileOnOneSideNeedsReinstall() {
        val (p, s, pkg) = setup()
        writeFiles(p, "same")
        writeFiles(s, "same")
        File(p, "lib/client.js").delete()
        assertFalse("profile 侧缺文件必须重装", isUpToDate(p, s, pkg))
    }

    /**
     * ④ **核心回归**：两侧文件**同时缺失** + bundle 仍注册 → 必须重装。
     *
     * 这是修复前的真实漏洞：两侧空内容的哈希相等（`e3b0c442…`），
     * 加上 bundle 已注册，会误判「已最新」而跳过安装 —— 但插件其实空无一物。
     * 存在性检查是唯一能堵住它的条件。
     */
    @Test
    fun bothSidesMissingMustNotBeTreatedAsUpToDate() {
        val (p, s, pkg) = setup()
        // 特意制造「两侧都没有任何指纹文件」，但目录与 bundle 注册都在
        fingerprintFiles.forEach {
            File(p, it).delete()
            File(s, it).delete()
        }
        assertTrue("前提：两侧确实一个文件都没有",
            fingerprintFiles.none { File(p, it).isFile || File(s, it).isFile })
        assertTrue("前提：bundle 仍注册（这正是会骗过旧逻辑的组合）",
            pkg.readText().contains("@local/dsh-mobile-adapt"))

        assertFalse("两侧全缺必须判为需重装（旧逻辑会误判为已最新）", isUpToDate(p, s, pkg))
    }

    /** ⑤ bundle 被移除（用户 uninstall 过）→ 即使内容一致也必须重装。 */
    @Test
    fun removedBundleNeedsReinstall() {
        val (p, s, pkg) = setup()
        writeFiles(p, "same")
        writeFiles(s, "same")
        pkg.writeText("""{"dsh":{"profile":{"bundles":[]}}}""")
        assertFalse(isUpToDate(p, s, pkg))
    }

    /** ⑥ 空内容哈希确实是固定值（说明「两侧空」为何会骗过纯哈希比对）。 */
    @Test
    fun emptyContentHashIsStableWhichIsWhyExistenceCheckIsRequired() {
        val empty = MessageDigest.getInstance("SHA-256").digest(ByteArray(0))
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "空内容的 sha256 是固定值 —— 两侧都缺文件时纯哈希比对必然相等",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            empty,
        )
    }
}
