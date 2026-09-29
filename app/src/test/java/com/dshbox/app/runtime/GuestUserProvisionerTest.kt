package com.dshbox.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `GuestUserProvisioner` 单测。
 *
 * 锁住三件事：**缺失时补条目**、**重复调用不累积**、**uid 变化时不留旧条目**；
 * 外加两条安全边界：**不改动 root 与系统用户**、**层损坏（无 root 条目）时不介入**。
 *
 * 只测纯函数 [GuestUserProvisioner.withEntry] 与可注入路径的 `provision`，
 * 不依赖 Android 运行时（uid/gid 由 `Os` 提供，故 `provision` 的 uid 断言按"存在一行自己的条目"判定）。
 */
class GuestUserProvisionerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sample = """
        root:*:0:0:root:/root:/bin/bash
        daemon:*:1:1:daemon:/usr/sbin:/usr/sbin/nologin
        nobody:*:65534:65534:nobody:/nonexistent:/usr/sbin/nologin
    """.trimIndent() + "\n"

    /** 缺失时追加自己的条目，且不动既有条目。 */
    @Test
    fun appendsEntryKeepingExistingOnes() {
        val out = GuestUserProvisioner.withEntry(sample, 10495, 10495)

        assertTrue("应含 root 条目", out.contains("root:*:0:0:root:/root:/bin/bash"))
        assertTrue("应含 nobody 条目", out.contains("nobody:*:65534:65534:"))
        assertTrue(
            "应含自己的条目",
            out.contains("${GuestUserProvisioner.USER_NAME}:x:10495:10495::/root:/bin/bash"),
        )
        assertEquals("条目数应为 4", 4, out.trim().lines().size)
    }

    /** 重复调用不累积：同名旧行被替换而不是叠加。 */
    @Test
    fun repeatedCallsDoNotAccumulate() {
        val once = GuestUserProvisioner.withEntry(sample, 10495, 10495)
        val twice = GuestUserProvisioner.withEntry(once, 10495, 10495)

        assertEquals("重复调用应幂等", once, twice)
        assertEquals(
            "同名条目只应有一条",
            1,
            twice.lines().count { it.startsWith("${GuestUserProvisioner.USER_NAME}:") },
        )
    }

    /** uid 变化（换设备/换安装）时旧条目被替换，不留两个自己。 */
    @Test
    fun uidChangeReplacesOldEntry() {
        val before = GuestUserProvisioner.withEntry(sample, 10495, 10495)
        val after = GuestUserProvisioner.withEntry(before, 10123, 10123)

        assertFalse("不应残留旧 uid", after.contains(":10495:10495:"))
        assertTrue("应换成新 uid", after.contains(":10123:10123:"))
        assertEquals(
            "同名条目只应有一条",
            1,
            after.lines().count { it.startsWith("${GuestUserProvisioner.USER_NAME}:") },
        )
    }

    /** 正文以换行收尾：passwd 末行缺换行会被部分工具误读。 */
    @Test
    fun outputEndsWithNewline() {
        assertTrue(GuestUserProvisioner.withEntry(sample, 1, 1).endsWith("\n"))
    }

    /** base 层不存在时不做任何事。 */
    @Test
    fun missingBaseLayerIsNoop() {
        assertEquals(
            GuestUserProvisioner.Outcome.SKIPPED,
            GuestUserProvisioner.provision(File(tmp.root, "nope")),
        )
    }

    /** passwd 不可读时不做任何事（不抛）。 */
    @Test
    fun missingPasswdIsNoop() {
        val base = tmp.newFolder("base")
        assertEquals(
            GuestUserProvisioner.Outcome.SKIPPED,
            GuestUserProvisioner.provision(base),
        )
    }

    /** 层损坏（连 root 都没有）时不介入，避免把系统账号一并抹掉。 */
    @Test
    fun brokenLayerWithoutRootIsLeftAlone() {
        val base = tmp.newFolder("base")
        File(base, "etc").mkdirs()
        val passwd = File(base, "etc/passwd")
        passwd.writeText("daemon:*:1:1:daemon:/usr/sbin:/usr/sbin/nologin\n")

        assertEquals(
            "不应改写损坏的层",
            GuestUserProvisioner.Outcome.SKIPPED,
            GuestUserProvisioner.provision(base),
        )
        assertEquals(
            "内容必须原样保留",
            "daemon:*:1:1:daemon:/usr/sbin:/usr/sbin/nologin\n",
            passwd.readText(),
        )
    }

    /**
     * 取不到 uid/gid 时以 SKIPPED 返回且**不抛**。
     *
     * JVM 单测栈里没有 Android 运行时（`android.system.Os` 是 stub），因此这条路径
     * 恰好在此被真实走到；在真机上同样的容错保证"读 uid 失败不连累 bootstrap"。
     */
    @Test
    fun uidUnavailableSkipsWithoutThrowing() {
        val base = tmp.newFolder("base")
        File(base, "etc").mkdirs()
        val passwd = File(base, "etc/passwd")
        passwd.writeText("root:*:0:0:root:/root:/bin/bash\n")

        assertEquals(
            GuestUserProvisioner.Outcome.SKIPPED,
            GuestUserProvisioner.provision(base),
        )
        assertEquals("文件必须原样保留", "root:*:0:0:root:/root:/bin/bash\n", passwd.readText())
    }
}
