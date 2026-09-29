package com.dshbox.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * dpkg 账本守卫：干净时留快照、未闭合时用快照恢复、有工具在跑时跳过。
 */
class DpkgLedgerGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun baseWithDpkg(
        status: String? = "Package: x\nStatus: install ok installed\n",
        journal: List<String> = listOf("tmp.i"),
    ): File {
        val base = tmp.newFolder("base")
        val dpkg = File(base, "var/lib/dpkg")
        File(dpkg, "updates").mkdirs()
        if (status != null) File(dpkg, "status").writeText(status)
        journal.forEach { File(dpkg, "updates/$it").writeText("") }
        return base
    }

    private fun snapshotDir() = File(tmp.root, "snap")

    @Test
    fun `clean ledger gets snapshotted`() {
        val base = baseWithDpkg()
        val snap = snapshotDir()
        assertEquals(DpkgLedgerGuard.Outcome.SNAPSHOTTED, DpkgLedgerGuard.guard(base, snap) { false })
        assertTrue(File(snap, "status").isFile)
    }

    @Test
    fun `interrupted ledger is restored from snapshot`() {
        val base = baseWithDpkg()
        val snap = snapshotDir()
        assertEquals(DpkgLedgerGuard.Outcome.SNAPSHOTTED, DpkgLedgerGuard.guard(base, snap) { false })

        // 模拟一次被打断的事务：status 被改坏 + journal 残留
        val dpkg = File(base, "var/lib/dpkg")
        File(dpkg, "status").writeText("Package: x\nStatus: half-configured\n")
        File(dpkg, "updates/0000").writeText("journal")
        assertTrue(DpkgLedgerGuard.isLedgerDirty(dpkg))

        assertEquals(DpkgLedgerGuard.Outcome.RESTORED, DpkgLedgerGuard.guard(base, snap) { false })
        assertTrue(File(dpkg, "status").readText().contains("install ok installed"))
        assertFalse(File(dpkg, "updates/0000").exists())
    }

    @Test
    fun `unchanged ledger is not snapshotted twice`() {
        val base = baseWithDpkg()
        val snap = snapshotDir()
        assertEquals(DpkgLedgerGuard.Outcome.SNAPSHOTTED, DpkgLedgerGuard.guard(base, snap) { false })
        assertEquals(DpkgLedgerGuard.Outcome.UP_TO_DATE, DpkgLedgerGuard.guard(base, snap) { false })
    }

    @Test
    fun `skips while a package tool is running`() {
        val base = baseWithDpkg()
        val snap = snapshotDir()
        assertEquals(DpkgLedgerGuard.Outcome.SKIPPED, DpkgLedgerGuard.guard(base, snap) { true })
        assertFalse(snap.exists())
    }

    @Test
    fun `dirty ledger without snapshot is left untouched`() {
        val base = baseWithDpkg(status = "Package: x\nStatus: half-installed\n")
        assertEquals(DpkgLedgerGuard.Outcome.UNAVAILABLE, DpkgLedgerGuard.guard(base, snapshotDir()) { false })
    }

    @Test
    fun `missing base layer does nothing`() {
        assertEquals(
            DpkgLedgerGuard.Outcome.UNAVAILABLE,
            DpkgLedgerGuard.guard(File(tmp.root, "missing"), snapshotDir()) { false },
        )
    }

    @Test
    fun `dirty detection covers journal and status`() {
        val base = baseWithDpkg()
        val dpkg = File(base, "var/lib/dpkg")
        assertFalse(DpkgLedgerGuard.isLedgerDirty(dpkg))

        File(dpkg, "updates/0001").writeText("")
        assertTrue(DpkgLedgerGuard.isLedgerDirty(dpkg))
        File(dpkg, "updates/0001").delete()

        File(dpkg, "status").writeText("Package: x\nStatus: install ok installed\ntriggers-pending: 1\n")
        assertTrue(DpkgLedgerGuard.isLedgerDirty(dpkg))
    }
}
