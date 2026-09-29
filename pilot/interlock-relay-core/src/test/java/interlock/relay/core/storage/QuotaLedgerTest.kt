package interlock.relay.core.storage

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 配额预留的账目纪律：同一份可用余额不许被允诺给两个写入方。
 * 预留只占预算不入账，commit 才把真实字节记进账本，release 原样退还；
 * 失效句柄重复提交或重复释放都不得二次入账。另外，账本落盘失败必须可见——
 * 内存读数失去持久依据时，调用方不能继续把它当准确值。
 */
class QuotaLedgerTest {

    private lateinit var tempDir: File
    private lateinit var ledgerFile: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("quota-ledger").toFile()
        ledgerFile = File.createTempFile("quota-", ".properties", tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun ledger(maxBytes: Long = 1_000_000L) = QuotaLedger(ledgerFile, maxBytes)

    @Test
    fun commitChargesActualBytesAndReleaseLeavesLedgerUntouched() {
        val block = QuotaLedger.BLOCK_BYTES
        val ledger = ledger()

        // 预留只占预算、不入账：totalBytes 不动，usableBytes 把未提交预留算进去。
        val reservation = ledger.reserve(1024)
        assertNotNull(reservation)
        assertEquals(0L, ledger.totalBytes)
        assertEquals(1_000_000L - block, ledger.usableBytes())

        // commit 按实际字节入账，而不是预留时登记的预计值。
        ledger.commit(reservation!!, 4097)
        assertEquals(2 * block, ledger.totalBytes)
        assertEquals(1, ledger.artifactCount)
        assertEquals(1_000_000L - 2 * block, ledger.usableBytes())

        // release 原样退还，账目不动。
        val first = ledger.reserve(1024)!!
        val second = ledger.reserve(1024)!!
        ledger.release(first)
        ledger.release(second)
        assertEquals(2 * block, ledger.totalBytes)
        assertEquals(1, ledger.artifactCount)
        assertEquals(1_000_000L - 2 * block, ledger.usableBytes())

        // 失效句柄（重复释放、重复提交）只生效一次，不得二次入账或重复退还。
        ledger.release(first)
        ledger.commit(second, 1024)
        assertEquals(2 * block, ledger.totalBytes)
        assertEquals(1, ledger.artifactCount)
    }

    @Test
    fun reserveRejectsOnceBudgetFullyPledged() {
        val block = QuotaLedger.BLOCK_BYTES
        val ledger = ledger(maxBytes = 2 * block)
        val first = ledger.reserve(1)!!
        val second = ledger.reserve(1)!!
        assertNull("预算被预留占满后，后续请求必须拿不到份额", ledger.reserve(1))
        ledger.release(first)
        assertNotNull("退还一笔后同额度的请求应当成功", ledger.reserve(1))
        assertNull("按块取整后超预算的请求仍然放不下", ledger.reserve(block + 1))
        ledger.release(second)
    }

    @Test
    fun persistFailureIsVisibleAndRecovers() {
        // 让账本落点本身是一个目录：对它写文件必然失败，且不依赖平台权限位的语义差异。
        // 目录里必须放一个占位文件——Windows 的 rename 顶得掉空目录，空招会假成功。
        val dir = Files.createTempDirectory("quota-unwritable").toFile()
        val path = File(dir, "quota.properties")
        assertTrue(path.mkdir())
        File(path, "occupied").writeText("占位：保证这个目录不可被 rename 替换")
        val ledger = QuotaLedger(path, maxBytes = 1_000_000L)
        assertTrue(ledger.persistHealthy)

        // 记账不得因落盘失败而中断或抛出，读数照常前进；但健康位必须降级。
        ledger.recordWritten(1024)
        assertEquals(QuotaLedger.BLOCK_BYTES, ledger.totalBytes)
        assertFalse("落盘失败必须可见", ledger.persistHealthy)

        // 路径恢复可写后，下一次成功落盘把健康位复位。
        assertTrue(dir.deleteRecursively())
        assertTrue(dir.mkdirs())
        ledger.recordWritten(1024)
        assertTrue(ledger.persistHealthy)
        dir.deleteRecursively()
    }

    @Test
    fun concurrentReserveCommitReleaseLeavesLedgerConsistent() {
        val block = QuotaLedger.BLOCK_BYTES
        val ledger = ledger(maxBytes = 512L * 1024 * 1024)
        val threads = 8
        val rounds = 500
        val commitsPerThread = IntArray(threads)
        val start = CountDownLatch(threads)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            thread {
                start.countDown()
                start.await(5, TimeUnit.SECONDS)
                var committed = 0
                repeat(rounds) { i ->
                    val reservation = ledger.reserve(1024)
                    // 并发在途量远小于预算，reserve 不应失败；即便失败也只能少入账，不能错账。
                    if (reservation != null) {
                        if (i % 2 == 0) {
                            ledger.commit(reservation, 1024)
                            committed++
                        } else {
                            ledger.release(reservation)
                        }
                    }
                }
                commitsPerThread[t] = committed
                done.countDown()
            }
        }
        assertTrue("并发线程应在超时前全部结束", done.await(60, TimeUnit.SECONDS))

        // 每笔 commit 恰好入账一个分配块，release 不动账目，终值必须与手算一致。
        val commits = commitsPerThread.sum()
        assertEquals(commits * block, ledger.totalBytes)
        assertEquals(commits, ledger.artifactCount)
        // 所有预留都已了结，可用字节必须回到「上限 − 已入账」。
        assertEquals(512L * 1024 * 1024 - commits * block, ledger.usableBytes())
    }
}
