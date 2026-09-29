package interlock.relay.core.storage

import interlock.relay.core.log.RunLog
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `out/` 交付目录的回收纪律：份数、龄期、总量三道闸，宽限期，以及"只删常规文件"。
 *
 * 为什么单独钉：这棵树在绑定子树内（沙盒可写），而清理动作是**宿主**在删——
 * 判错一条就会删掉用户正在看的产物，或者顺着链接删到子树之外。
 */
class StorageReaperOutTest {

    private lateinit var root: File
    private lateinit var paths: RelayPaths
    private lateinit var reaper: StorageReaper
    private var wallClock = 1_000_000_000L

    @Before
    fun setUp() {
        root = Files.createTempDirectory("relay-out").toFile()
        paths = RelayPaths(root)
        assertTrue(paths.ensureWritableDirs())
        reaper = StorageReaper(
            paths,
            QuotaLedger(File(root, "quota.properties")),
            RunLog(paths.logsDir),
            now = { 0L },
            wallNow = { wallClock },
        )
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** 造一个产物文件，mtime 由 agesMs 指定（越新越靠近 wallClock）。 */
    private fun outFile(kind: String, name: String, bytes: Int, ageMs: Long = 0L): File {
        val dir = File(paths.outDir, kind)
        assertTrue(dir.isDirectory)
        val file = File(dir, name)
        file.writeBytes(ByteArray(bytes))
        file.setLastModified(wallClock - ageMs)
        return file
    }

    @Test
    fun usageCountsBytesFilesAndKinds() {
        outFile(ArtifactOut.SHOT, "a.png", 100, ageMs = 0)
        outFile(ArtifactOut.SHOT, "b.png", 200, ageMs = 0)
        outFile(ArtifactOut.AUDIO, "c.m4a", 50, ageMs = 0)

        val usage = reaper.outUsage()
        assertEquals(350L, usage.bytes)
        assertEquals(3, usage.files)
        assertEquals(300L, usage.byKind[ArtifactOut.SHOT])
        assertEquals(0L, usage.byKind[ArtifactOut.VIDEO])
        assertEquals(50L, usage.byKind[ArtifactOut.AUDIO])
    }

    @Test
    fun sweepKeepsTheNewestPerKindAndDropsTheRest() {
        // 每类造 KEEP_PER_KIND + 3 份，全部超过宽限期。
        val total = ArtifactOut.KEEP_PER_KIND + 3
        repeat(total) { index ->
            // ageMs 越小越新：序号越大越新，因此被删的应当是最小的那三个。
            outFile(
                ArtifactOut.SHOT,
                "shot-$index.png",
                10,
                ageMs = ArtifactOut.RECENT_GRACE_MS + 60_000L + (total - index),
            )
        }
        sweepIgnoringLog()

        val remaining = File(paths.outDir, ArtifactOut.SHOT).listFiles()?.sortedBy { it.name } ?: emptyList()
        assertEquals(ArtifactOut.KEEP_PER_KIND, remaining.size)
        // 留下的是最新的那批：按名字里的序号比（字符串序会把 shot-10 排到 shot-3 前面）。
        val stale = remaining.map { it.name }.filter {
            it.removePrefix("shot-").removeSuffix(".png").toInt() < 3
        }
        assertTrue("保留了旧件 $stale / 全部 ${remaining.map { it.name }}", stale.isEmpty())
    }

    /** 宽限期：刚写下的文件即使超出份数也不动——录制中的视频正在被写入。 */
    @Test
    fun recentFilesAreNeverCollected() {
        val total = ArtifactOut.KEEP_PER_KIND + 5
        repeat(total) { index ->
            // 全部在宽限期内（ageMs < RECENT_GRACE）：一份都不该被删。
            outFile(ArtifactOut.SHOT, "fresh-$index.png", 10, ageMs = 1_000L)
        }
        sweepIgnoringLog()
        assertEquals(total, File(paths.outDir, ArtifactOut.SHOT).listFiles()?.size)
    }

    /** 龄期：份数没超，但太老的一样回收（只是兜底，宽限期之外才生效）。 */
    @Test
    fun oldFilesAreCollectedEvenUnderTheCountLimit() {
        val fresh = outFile(ArtifactOut.AUDIO, "new.m4a", 10, ageMs = ArtifactOut.RECENT_GRACE_MS + 1_000L)
        val old = outFile(ArtifactOut.AUDIO, "old.m4a", 10, ageMs = ArtifactOut.MAX_AGE_MS + 60_000L)
        sweepIgnoringLog()
        assertTrue("新件留下", fresh.isFile)
        assertFalse("过龄的回收", old.isFile)
    }

    /** 总量闸：跨类合计超限时从最旧开始删，但每类至少保留最新一份。 */
    @Test
    fun totalBudgetTrimsOldestAcrossKindsButKeepsOnePerKind() {
        // 用真实上限装不下的小文件模拟：把上限压低不现实（常量是编译期），
        // 因此这里造"总量刚好超过上限"的稀疏文件（setLength 不占实际磁盘）。
        val chunk = ArtifactOut.MAX_TOTAL_BYTES / 4 + 1
        val kinds = listOf(ArtifactOut.SHOT, ArtifactOut.VIDEO, ArtifactOut.AUDIO, ArtifactOut.FILE)
        kinds.forEachIndexed { index, kind ->
            val file = outFile(kind, "big-$index.bin", 1, ageMs = ArtifactOut.RECENT_GRACE_MS + 1_000L + index * 1_000L)
            java.io.RandomAccessFile(file, "rw").use { it.setLength(chunk) }
            file.setLastModified(wallClock - (ArtifactOut.RECENT_GRACE_MS + 1_000L + index * 1_000L))
        }
        val before = kinds.sumOf { File(paths.outDir, it).listFiles()?.size ?: 0 }
        assertEquals(4, before)

        sweepIgnoringLog()

        // 总量 4×(上限/4+1) > 上限，但每类只有一件且它就是最新 → 受"每类至少留一份"保护，
        // 一件都不删（宁可超限，也不删用户最后的那份）。这条断言把这个取舍钉住。
        var survivors = 0
        for (kind in kinds) {
            val files = File(paths.outDir, kind).listFiles() ?: emptyArray()
            assertTrue("$kind 至少保留最新一份", files.size >= 1)
            survivors += files.size
        }
        assertEquals("每类最后一份受保护，一件不删", 4, survivors)
        assertTrue("总量确实超过上限", kinds.sumOf { kind ->
            File(paths.outDir, kind).listFiles()?.sumOf { it.length() } ?: 0L
        } > ArtifactOut.MAX_TOTAL_BYTES)
    }

    /**
     * 总量闸真正删件的场景：某类有多件、且都不是"该类最新"时，从最旧开始删。
     *
     * 与上一条的区别：上一条每类只有一件（受保护），这一条给 shot 造三件——
     * 前两件可以删，最后一件必须留下。上限用稀疏文件凑（`setLength` 不占实际磁盘）。
     */
    @Test
    fun totalBudgetDropsTheOldestWhenAKindHasSpares() {
        val chunk = ArtifactOut.MAX_TOTAL_BYTES / 3 + 1
        val ages = listOf(3_000L, 2_000L, 1_000L)
        var i = 0
        for (age in ages) {
            val file = outFile(ArtifactOut.SHOT, "spare-$i.png", 1, ageMs = ArtifactOut.RECENT_GRACE_MS + age)
            java.io.RandomAccessFile(file, "rw").use { it.setLength(chunk) }
            file.setLastModified(wallClock - (ArtifactOut.RECENT_GRACE_MS + age))
            i++
        }
        sweepIgnoringLog()

        val remaining = File(paths.outDir, ArtifactOut.SHOT).listFiles()?.sortedBy { it.name } ?: emptyList()
        assertTrue("至少删掉一件最旧的", remaining.size < ages.size)
        assertTrue("最新一份必须还在", remaining.any { it.name == "spare-2.png" })
    }

    /** 清理前先复位：JVM 里 RunLog 落盘会抛（SystemClock 未 mock），清理本身已完成。 */
    private fun sweepIgnoringLog() {
        runCatching { reaper.sweep() }
    }

    /** 「立即清理」：清空各类，但每类留最新一份（用户可能正看着刚截的那张）。 */
    @Test
    fun clearKeepsTheNewestFileOfEachKind() {
        outFile(ArtifactOut.SHOT, "old.png", 10, ageMs = 500_000L)
        val newest = outFile(ArtifactOut.SHOT, "new.png", 10, ageMs = 1_000L)
        val only = outFile(ArtifactOut.FILE, "only.txt", 10, ageMs = 900_000L)

        // 不看返回值：JVM 里落盘那一步会抛（RunLog 依赖 SystemClock），
        // 而清理本身早在写日志之前就完成了——断言落在文件效果上。
        runCatching { reaper.clearAllArtifacts() }
        assertTrue("最新一份留下", newest.isFile)
        assertFalse("旧的清掉", File(paths.outDir, "${ArtifactOut.SHOT}/old.png").isFile)
        assertTrue("每类都留一份（哪怕它很旧）", only.isFile)
    }

    /** 只删常规文件：子目录（含沙盒放进去的）与符号链接都不动。 */
    @Test
    fun directoriesAndLinksAreLeftAlone() {
        val kindDir = File(paths.outDir, ArtifactOut.FILE)
        val sub = File(kindDir, "subdir").apply { mkdirs() }
        val payload = File(sub, "keep.txt").apply { writeText("keep") }
        val link = File(kindDir, "link.txt")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), payload.toPath()) }.isSuccess

        outFile(ArtifactOut.FILE, "victim.txt", 10, ageMs = ArtifactOut.MAX_AGE_MS + 60_000L)
        sweepIgnoringLog()

        assertTrue("子目录不动", sub.isDirectory)
        assertTrue("子目录里的文件不动", payload.isFile)
        if (created) assertTrue("链接不动", Files.isSymbolicLink(link.toPath()))
        assertFalse("常规文件按龄期回收", File(kindDir, "victim.txt").isFile)
    }
}
