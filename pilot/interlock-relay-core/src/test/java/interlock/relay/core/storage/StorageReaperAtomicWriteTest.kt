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
 * 原子写落点与逐级链接判据的纯 JVM 测试。这两条是信箱出站写入（畸形名回包、控制回执、
 * v1 响应）的安全边界：写入永远先落同目录暂存件、再用不跟随末级符号链接的 rename 落地，
 * 落点在绑定子树内时还要逐级确认没有中间链接。目标为符号链接时拒绝落盘——这一支需要
 * 造真链接（Windows 上要特权），留给设备侧验证；这里钉住的是桌面 JVM 上确定的行为面。
 */
class StorageReaperAtomicWriteTest {

    private lateinit var tempDir: File
    private lateinit var paths: RelayPaths
    private lateinit var reaper: StorageReaper

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("relay-atomic").toFile()
        paths = RelayPaths(tempDir)
        reaper = StorageReaper(paths, QuotaLedger(File(tempDir, "quota.properties")), RunLog(paths.logsDir))
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /** 中间目录不存在时先补齐再落盘：信箱目录被外部整层删掉后，下一次写入要能自愈。 */
    @Test
    fun atomicWriteCreatesMissingParentDirsAndLandsContent() {
        // StorageReaper 装配时已建齐全部目录：删掉 outbox 模拟“被外部整层删掉”。
        assertTrue(paths.outboxDir.delete())
        assertFalse(paths.outboxDir.exists())
        val target = File(paths.outboxDir, "resp-1.json")
        assertTrue(reaper.writeTextAtomic(target, "{\"ok\":true}"))
        assertEquals("{\"ok\":true}", target.readText())
        // 暂存件用完必须消失：留下的只能有目标文件本身。
        assertTrue(paths.outboxDir.listFiles()!!.all { it == target })
    }

    /** 绑定子树之外的落点不经链接门照常可写：暂存目录一类的宿主私有路径不受影响。 */
    @Test
    fun atomicWriteServesTargetsOutsideTheBoundEntryTree() {
        val target = File(paths.stagingDir, "plain.txt")
        assertTrue(reaper.writeTextAtomic(target, "outside"))
        assertEquals("outside", target.readText())
    }

    /**
     * 逐级链接判据的分隔符归一与边界：root 之内的多层路径判真（Windows 反斜杠路径
     * 必须先归一成 '/'，否则这道门在桌面 JVM 上对任何路径都判假），root 之外与
     * 仅共享前缀的同级目录（artifactsX 对 artifacts）一律判假。
     */
    @Test
    fun symlinkFreeFromJudgesInsideOutsideAndPrefixBoundary() {
        val root = File(tempDir, "relay")
        val deep = File(root, "run/outbox/resp-1.json")
        assertTrue("root 之内的多层路径判真", RelayPaths.symlinkFreeFrom(root, deep))

        val outside = File(tempDir, "elsewhere/secret.txt")
        assertFalse("root 之外判假", RelayPaths.symlinkFreeFrom(root, outside))

        val base = File(tempDir, "prefix").apply { mkdirs() }
        val artifacts = File(base, "run/artifacts")
        val sibling = File(base, "run/artifactsX/f.txt")
        assertTrue(RelayPaths.symlinkFreeFrom(artifacts, File(artifacts, "a.png")))
        assertFalse("仅共享字符串前缀的同级目录不算在内", RelayPaths.symlinkFreeFrom(artifacts, sibling))
    }
}
