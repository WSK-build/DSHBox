package interlock.relay.core.log

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 授权记录到顶之后的滚动淘汰。判据只有一条：**记录不能因为到顶就不长了** ——
 * 被灌循环的那一刻正是最需要留痕的时刻，停写等于让安全记录自己闭嘴。
 * 因此丢的必须是最旧那一份，而最近这一段要完整可读。
 */
class AuditLogRollingTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("relay-audit").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** 一段要装得下好几条：单条记录约 230 字节，所以 240 那个量级一段只剩一两条，测不出"这一段完整可读"。 */
    private fun log(maxFileBytes: Long = 1_200, maxSegments: Int = 3) = AuditLog(
        auditDir = dir,
        digestSalt = "salt",
        now = { 1_760_000_000_000L },
        maxFileBytes = maxFileBytes,
        maxSegments = maxSegments,
    )

    private fun entry(capability: String) = AuditEntry(
        requestId = "r-$capability",
        capability = capability,
        target = null,
        systemState = "GRANTED",
        tier = "FULL",
        decision = "approved",
        grantSource = "tier",
        uidRole = "app",
        outcome = "ok",
        surface = "foreground",
        degradedFrom = null,
        reason = null,
        latencyMs = 5L,
        artifactBytes = 0L,
        canonicalArgs = null,
    )

    @Test
    fun segmentCountStaysBoundedWhileRecordsKeepBeingAccepted() {
        val store = log()
        repeat(60) { store.record(entry("cap${it.toString().padStart(3, '0')}")) }

        val files = dir.listFiles { file: File -> file.isFile }?.toList().orEmpty()
        assertEquals("分段数必须停在 maxSegments 以内", 3, files.size)
        assertTrue(
            "总占用不得越过 maxSegments × maxFileBytes 的上界（最后一段可多一条越界）",
            files.sumOf { it.length() } <= 3L * 1_200L + 1_200L,
        )
    }

    @Test
    fun newestRecordSurvivesTheRoll() {
        val store = log()
        repeat(60) { store.record(entry("cap${it.toString().padStart(3, '0')}")) }
        val lines = store.recent(limit = 200, tailBytes = 64 * 1024)
        assertTrue("到顶之后新记录必须还在写进来", lines.any { it.contains("cap059") })
        assertTrue("最近这一段要能读回多条", lines.size >= 5)
    }

    @Test
    fun oldestRecordIsTheOneThatGetsDropped() {
        val store = log()
        repeat(60) { store.record(entry("cap${it.toString().padStart(3, '0')}")) }
        val lines = store.recent(limit = 500, tailBytes = 64 * 1024)
        assertTrue("最旧那几条应已被淘汰", lines.none { it.contains("cap000") })
    }

    @Test
    fun singleSegmentStillRotatesWithoutOverflowingTheCap() {
        val store = log(maxFileBytes = 240, maxSegments = 1)
        repeat(20) { store.record(entry("one${it}")) }
        val files = dir.listFiles { file: File -> file.isFile }?.toList().orEmpty()
        assertEquals(1, files.size)
        assertTrue(store.recent(limit = 50).any { it.contains("one19") })
    }
}
