package interlock.relay.core.log

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 授权记录的回收判据。两条各守一个洞：
 *  - 淘汰线只按单调时钟推进，改系统时间不能一次清空记录链；
 *  - 整库有字节上限，但越界时丢的是最旧那一整日的环，最近一日永远留着。
 */
class AuditLogPurgeTest {

    private lateinit var dir: File
    private var wall = 1_760_000_000_000L
    private var mono = 100_000L
    private val day = 24L * 60L * 60L * 1000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("relay-audit-purge").toFile()
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun log(cap: Long = Long.MAX_VALUE) = AuditLog(
        auditDir = dir,
        digestSalt = "salt",
        now = { wall },
        mono = { mono },
        storeCapBytes = cap,
    )

    private fun stampFile(offsetDays: Long, bytes: Int = 40): File {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            .format(java.util.Date(wall + offsetDays * day))
        return File(dir, "audit-$stamp.jsonl").apply {
            writeText("x".repeat(bytes))
            setLastModified(wall + offsetDays * day)
        }
    }

    @Test
    fun firstTickOnlyAnchorsAndDeletesNothing() {
        val old = stampFile(-400)
        assertEquals(0, log().purgeExpired())
        assertTrue("对表那一轮不该按时间删任何东西", old.exists())
    }

    @Test
    fun wallClockJumpForwardDoesNotWipeTheTrail() {
        // 未过期的一份：只在保留期内五天。要是拿"早就过期"的文件来测，删得掉反而说明判据没错，
        // 测不出改表这件事。
        val recent = stampFile(-5)
        val store = log()
        store.purgeExpired()          // 对表
        wall += 400L * day             // 用户或校时把系统时间往前拨一年多
        assertEquals(0, store.purgeExpired())
        assertTrue("改表就清空记录链，正是这条改动要挡住的", recent.exists())
    }

    @Test
    fun monotonicAdvancePastRetentionDoesDelete() {
        val old = stampFile(-31)
        val fresh = stampFile(31)      // 真实走过 31 天之后写的那一份
        val store = log()
        store.purgeExpired()           // 对表，锚在当下
        mono += 31L * day              // 真实走过 31 天
        assertTrue(store.purgeExpired() >= 1)
        assertTrue("超过保留期的旧环要能被清掉", !old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun rebootReanchorsAndSkipsTimeDeletion() {
        val old = stampFile(-400)
        val store = log()
        store.purgeExpired()
        mono = 5_000L                  // elapsedRealtime 归零 = 重启过
        assertEquals(0, store.purgeExpired())
        assertTrue("重启后无从知道过了多久，这一轮不按时删", old.exists())
    }

    @Test
    fun byteCapEvictsTheOldestDayRingOnly() {
        val yesterday = File(dir, "audit-20260925.jsonl").apply {
            writeText("y".repeat(500))
            setLastModified(wall - day)
        }
        File(dir, "audit-20260925.2.jsonl").apply {
            writeText("y".repeat(500))
            setLastModified(wall - day)
        }
        val today = File(dir, "audit-20260926.jsonl").apply {
            writeText("t".repeat(500))
            setLastModified(wall)
        }
        // 上限 600 < 两日合计 2000，但只剩一日时不再删。
        log(cap = 600L).purgeExpired()
        assertTrue("最旧那一整日（含它的分段）应被淘汰", !yesterday.exists())
        assertTrue("最近一日必须留下", today.exists())
    }

    @Test
    fun watermarkIsNotTreatedAsARecordFile() {
        stampFile(0)
        val store = log()
        store.purgeExpired()
        assertTrue(File(dir, ".watermark").exists())
        assertEquals("水位线不能被当成记录读出来", 1, store.recent(limit = 50).count { it.isNotBlank() })
    }
}
