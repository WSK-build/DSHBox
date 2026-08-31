package com.dshbox.app.util

import com.dshbox.app.util.SandboxCleanup.Category
import com.dshbox.app.util.SandboxCleanup.EntryStat
import com.dshbox.app.util.SandboxCleanup.UsageLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.0 (M12/M12.1)：清理分类规则、年龄判定与逐条目归账器。分类规则是清理功能
 * 的安全边界——每条「红线路径必须返回 null」的断言都对应一个绝不能被清理删除的目录。
 */
class SandboxCleanupTest {

    private fun entry(
        relPath: String,
        blocks: Long = 8L,
        isDir: Boolean = false,
        dev: Long = 1,
        ino: Long = 0,
        nlink: Long = 1,
        lastModifiedMs: Long = 0L,
    ): EntryStat {
        val name = relPath.trimStart('/').replace('\\', '/').removePrefix("./").substringAfterLast('/')
        return EntryStat(
            name = name,
            relPath = relPath,
            isDir = isDir,
            isSymlink = false,
            blocks = blocks,
            dev = dev,
            ino = ino,
            nlink = nlink,
            lastModifiedMs = lastModifiedMs,
        )
    }

    @Test
    fun safeItemsAreCategorized() {
        assertEquals(Category.LOGS, SandboxCleanup.categorize("logs"))
        assertEquals(Category.LOGS, SandboxCleanup.categorize("logs/process-dsh.log"))
        assertEquals(Category.CACHE, SandboxCleanup.categorize("runtime-bundle-staging/base.tar.zst"))
        assertEquals(Category.CACHE, SandboxCleanup.categorize("bundled-runtime-staging"))
        assertEquals(Category.CACHE, SandboxCleanup.categorize("bundled-runtime-staging.tar.gz"))
        assertEquals(Category.CACHE, SandboxCleanup.categorize("runtime/dsh-staging/node_modules"))
        // M12.1 P2⑪：proot 临时目录真实位置是 runtime-current/tmp/<role>。
        assertEquals(Category.GUEST_TMP, SandboxCleanup.categorize("runtime/runtime-current/tmp/sandbox"))
        assertEquals(
            Category.GUEST_TMP,
            SandboxCleanup.categorize("runtime/runtime-current/base/tmp/dsh-stage/node_modules/x.js"),
        )
        assertEquals(
            Category.APT,
            SandboxCleanup.categorize("runtime/runtime-current/base/var/cache/apt/archives/vim.deb"),
        )
        assertEquals(Category.ROLLBACK, SandboxCleanup.categorize("runtime/runtime-current/previous/base"))
        assertEquals(Category.ROLLBACK, SandboxCleanup.categorize("runtime/runtime-new"))
        assertEquals(Category.ROLLBACK, SandboxCleanup.categorize("runtime/runtime-previous/debian"))
        assertEquals(Category.ROLLBACK, SandboxCleanup.categorize("runtime/runtime-failed/base/etc"))
    }

    @Test
    fun redLinePathsAreNeverCategorized() {
        assertNull(SandboxCleanup.categorize("user-data/notes.md"))
        assertNull(SandboxCleanup.categorize("user-data/.dsh/profiles/web"))
        assertNull(SandboxCleanup.categorize("user-data/.dsh/mobile-adapt/install.sh"))
        assertNull(SandboxCleanup.categorize("runtime/runtime-current/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js"))
        assertNull(SandboxCleanup.categorize("runtime/runtime-current/node/bin/node"))
        assertNull(SandboxCleanup.categorize("runtime/runtime-current/base/etc/passwd"))
        assertNull(SandboxCleanup.categorize("runtime/runtime-current/base/usr/bin/tar"))
        assertNull(SandboxCleanup.categorize("runtime/runtime-profile.json"))
        assertNull(SandboxCleanup.categorize("runtime/runtime-current/base/var/cache/apt/lists/mirror"))
        assertNull(SandboxCleanup.categorize("updates/bundle.tar.gz"))
        assertNull(SandboxCleanup.categorize("sandbox"))
        assertNull(SandboxCleanup.categorize("backups"))
        assertNull(SandboxCleanup.categorize("runtime/tmp/x"))
        assertNull(SandboxCleanup.categorize("runtime/runtime-current/base/template"))
        // M12.1 P2⑨：最易踩雷的近邻路径（前缀相似但多出字符 / 兄弟目录）。
        assertNull(SandboxCleanup.categorize("runtime/runtime-current/previous2/base"))
        assertNull(SandboxCleanup.categorize("logs_backup/x"))
        assertNull(SandboxCleanup.categorize("runtime/runtime-current/base/var/lib/dpkg/status"))
        assertNull(SandboxCleanup.categorize("sandbox/foo"))
        assertNull(SandboxCleanup.categorize("backups/foo"))
    }

    @Test
    fun windowsSeparatorsAndLeadingSlashesNormalize() {
        assertEquals(Category.GUEST_TMP, SandboxCleanup.categorize("runtime\\runtime-current\\tmp\\dsh"))
        assertEquals(Category.LOGS, SandboxCleanup.categorize("/logs/a.log"))
        assertEquals(Category.ROLLBACK, SandboxCleanup.categorize("./runtime/runtime-current/previous/dsh"))
    }

    @Test
    fun ageGuardAppliesOnlyToGuestTmpWhileRunning() {
        val now = 1_000_000_000L
        val fresh = now - 60_000L                      // 1 分钟前
        val boundary = now - SandboxCleanup.AGE_GUARD_MS  // 恰好 24h
        val justUnder = boundary + 1                   // 差 1ms 不到 24h

        assertTrue(SandboxCleanup.passesAgeGuard(Category.GUEST_TMP, boundary, now, guardActive = true))
        assertFalse(SandboxCleanup.passesAgeGuard(Category.GUEST_TMP, justUnder, now, guardActive = true))
        assertFalse(SandboxCleanup.passesAgeGuard(Category.GUEST_TMP, fresh, now, guardActive = true))
        // 沙箱/DSH 全停时不做年龄过滤。
        assertTrue(SandboxCleanup.passesAgeGuard(Category.GUEST_TMP, fresh, now, guardActive = false))
        // 其余类别不受年龄限制（日志可随时截断、缓存可随时清）。
        assertTrue(SandboxCleanup.passesAgeGuard(Category.LOGS, fresh, now, guardActive = true))
        assertTrue(SandboxCleanup.passesAgeGuard(Category.CACHE, fresh, now, guardActive = true))
        assertTrue(SandboxCleanup.isAgeGuarded(Category.GUEST_TMP))
        assertFalse(SandboxCleanup.isAgeGuarded(Category.APT))
    }

    @Test
    fun ledgerCountsAllocatedBytesAndFiltersByExtension() {
        val now = 100_000L
        val ledger = UsageLedger(now, guardActive = false)
        // add() 返回计入「总量」的字节数；扩展名过滤只影响 reclaimable。
        assertEquals(2048L, ledger.add(entry("logs/process-dsh.log", blocks = 4)))
        // LOGS 只认 .log（M12.1 P2⑦）：README 计总量但不进 reclaimable。
        assertEquals(2048L, ledger.add(entry("logs/README.txt", blocks = 4)))
        // APT 只认 .deb（M12.1 P2⑥）：lock 文件不进 reclaimable（真实 apt clean 只清包文件）。
        assertEquals(2048L, ledger.add(entry("runtime/runtime-current/base/var/cache/apt/archives/lock", blocks = 4)))
        // partial/ 内的 .deb 与 apt clean 行为一致（partial 也被清空）。
        assertEquals(
            2048L,
            ledger.add(entry("runtime/runtime-current/base/var/cache/apt/archives/partial/vim.deb", blocks = 4)),
        )
        assertEquals(
            4096L,
            ledger.add(entry("runtime/runtime-current/base/var/cache/apt/archives/vim.deb", blocks = 8)),
        )
        // 目录计总量、不进 reclaimable。
        assertEquals(2048L, ledger.add(entry("runtime/runtime-current/previous/base", blocks = 4, isDir = true)))
        val reclaimable = ledger.reclaimable()
        assertEquals(2048L, reclaimable[Category.LOGS])
        assertEquals(6144L, reclaimable[Category.APT])
    }

    @Test
    fun ledgerDeduplicatesHardlinksByDevAndIno() {
        val now = 100_000L
        val ledger = UsageLedger(now, guardActive = false)
        // 同一 inode 的两个名字（st_nlink=2）：块只在首次遇到时计入一次（du 口径）。
        // 首遇路径在 base（无类别）——删除 previous 侧的该名字并不释放块（inode 仍被
        // base 持有），因此 ROLLBACK 不应包含这部分字节，这正是去重的正确语义。
        val a = entry("runtime/runtime-current/base/usr/bin/tar", blocks = 100, dev = 7, ino = 42, nlink = 2)
        val b = entry("runtime/runtime-current/previous/base/usr/bin/tar", blocks = 100, dev = 7, ino = 42, nlink = 2)
        assertEquals(51200L, ledger.add(a))
        assertEquals(0L, ledger.add(b))
        val reclaimable = ledger.reclaimable()
        assertNull(reclaimable[Category.ROLLBACK])
        assertEquals(0L, reclaimable.values.sum())
    }

    @Test
    fun ledgerAppliesAgeGuardWhenActive() {
        val now = 1_000_000L
        val ledger = UsageLedger(now, guardActive = true)
        val fresh = now - 60_000L
        val stale = now - SandboxCleanup.AGE_GUARD_MS
        // add() 返回计入总量的字节（fresh 文件也计总量）；年龄过滤只影响 reclaimable。
        ledger.add(entry("runtime/runtime-current/base/tmp/live-spill/x", blocks = 4, lastModifiedMs = fresh))
        ledger.add(entry("runtime/runtime-current/base/tmp/old-spill/x", blocks = 4, lastModifiedMs = stale))
        // 非年龄受限类别不受影响。
        ledger.add(entry("logs/process-sandbox.log", blocks = 4, lastModifiedMs = fresh))
        val reclaimable = ledger.reclaimable()
        assertEquals(2048L, reclaimable[Category.GUEST_TMP])
        assertEquals(2048L, reclaimable[Category.LOGS])
    }

    @Test
    fun symlinkEntriesAreSkipped() {
        val ledger = UsageLedger(100_000L, guardActive = false)
        val link = entry("runtime/runtime-current/base/bin/sh", blocks = 4).copy(isSymlink = true)
        assertEquals(0L, ledger.add(link))
        assertTrue(ledger.reclaimable().isEmpty())
    }
}
