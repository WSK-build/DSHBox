package com.dshbox.app.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 1.2.0 §10.1：moveWithin 移动引擎 JVM 单测（临时目录构造树）。
 *
 * 覆盖：rename 路径与复制兜底路径（rwx 权限位含可执行位、lastModified 保留）、
 * OVERWRITE「先就位后替换」、目录递归合并不删目标、目标并发占用按失败、
 * 取消点行为（单文件为最小不可中断单元）、`.dsh-moving-*` finally 清理与启动扫描清理。
 *
 * 复制兜底路径通过 forceCopyFallback 测试钩子强制触发（模拟跨挂载点 renameTo 失败），
 * 避免依赖跨卷环境。可执行位断言仅在 POSIX 文件系统上有意义，Windows 跳过。
 */
class MoveWithinTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")

    // ---------- rename 路径 ----------

    @Test
    fun moveFileKeepsContentAndTimestamp() = runBlocking {
        val src = tmp.newFile("a.txt").apply {
            writeText("hello move")
            setLastModified(1_700_000_000_000L)
        }
        val target = tmp.newFolder("t1")
        val result = MoveEngine.moveWithin(listOf(MoveTask(src, File(target, "a.txt"))))
        assertEquals(1, result.moved)
        assertTrue(result.failed.isEmpty())
        assertFalse(src.exists())
        val dest = File(target, "a.txt")
        assertEquals("hello move", dest.readText())
        assertEquals(1_700_000_000_000L, dest.lastModified())
    }

    @Test
    fun moveDirRenamesAtomically() = runBlocking {
        val srcDir = tmp.newFolder("d1").also { dir ->
            File(dir, "x.txt").writeText("x")
            File(dir, "sub").mkdirs().also { File(dir, "sub/y.txt").writeText("y") }
        }
        val target = tmp.newFolder("t2")
        val result = MoveEngine.moveWithin(listOf(MoveTask(srcDir, File(target, "d1"))))
        assertEquals(1, result.moved)
        assertFalse(srcDir.exists())
        assertEquals("x", File(target, "d1/x.txt").readText())
        assertEquals("y", File(target, "d1/sub/y.txt").readText())
    }

    // ---------- 复制兜底路径：rwx / lastModified 同步 ----------

    @Test
    fun copyFallbackSyncsExecutableBitAndTimestamp() = runBlocking {
        lateinit var srcRun: File
        lateinit var srcSetup: File
        val srcDir = tmp.newFolder("script-src").also { dir ->
            srcRun = File(dir, "run.sh").apply {
                writeText("#!/bin/sh\necho ok\n")
                setLastModified(1_700_000_100_000L)
                // Windows 上 setExecutable(false) 不生效，仅 POSIX 断言不可执行位
                if (!isWindows) setExecutable(false)
            }
            srcSetup = File(dir, "setup.sh").apply {
                writeText("#!/bin/sh\nexit 0\n")
                setExecutable(true)
                setLastModified(1_700_000_200_000L)
            }
        }
        val expectedRunMtime = srcRun.lastModified()
        val expectedSetupMtime = srcSetup.lastModified()
        val target = tmp.newFolder("t3")
        val result = MoveEngine.moveWithin(
            listOf(MoveTask(srcDir, File(target, "script-src"))),
            forceCopyFallback = true,
        )
        assertEquals(1, result.moved)
        assertTrue(result.failed.isEmpty())
        assertFalse(srcDir.exists())
        val runSh = File(target, "script-src/run.sh")
        val setupSh = File(target, "script-src/setup.sh")
        assertEquals("#!/bin/sh\necho ok\n", runSh.readText())
        if (!isWindows) {
            assertFalse("不可执行位应同步（POSIX）", runSh.canExecute())
        }
        assertTrue("可执行位应保留", setupSh.canExecute())
        assertEquals(expectedRunMtime, runSh.lastModified())
        assertEquals(expectedSetupMtime, setupSh.lastModified())
        // 目录本体可进入
        if (!isWindows) assertTrue(File(target, "script-src").canExecute())
    }

    @Test
    fun copyFallbackSingleFile() = runBlocking {
        val src = tmp.newFile("big.bin").apply {
            writeBytes(ByteArray(300_000) { (it % 251).toByte() })
            setExecutable(true)
            setLastModified(1_700_000_300_000L)
        }
        val target = tmp.newFolder("t4")
        val result = MoveEngine.moveWithin(
            listOf(MoveTask(src, File(target, "big.bin"))),
            forceCopyFallback = true,
        )
        assertEquals(1, result.moved)
        assertFalse(src.exists())
        val dest = File(target, "big.bin")
        assertEquals(300_000L, dest.length())
        assertEquals(300_000L, FileOps.totalSize(dest))
        assertEquals(1_700_000_300_000L, dest.lastModified())
        if (!isWindows) assertTrue(dest.canExecute())
    }

    // ---------- OVERWRITE：先就位后替换 ----------

    @Test
    fun overwriteReplacesExistingFile() = runBlocking {
        val src = tmp.newFile("o.txt").apply { writeText("new") }
        val target = tmp.newFolder("t5")
        File(target, "o.txt").writeText("old")
        val result = MoveEngine.moveWithin(
            listOf(MoveTask(src, File(target, "o.txt"), existing = MoveExisting.OVERWRITE)),
        )
        assertEquals(1, result.moved)
        assertEquals("new", File(target, "o.txt").readText())
        assertFalse(src.exists())
    }

    @Test
    fun mergeDirKeepsTargetFilesAndOverwritesClashes() = runBlocking {
        val srcDir = tmp.newFolder("m-src").also { dir ->
            File(dir, "clash.txt").writeText("src")
            File(dir, "only-src.txt").writeText("s-only")
        }
        val target = tmp.newFolder("t6")
        val destDir = File(target, "m-src").also { dir ->
            dir.mkdirs()
            File(dir, "clash.txt").writeText("dst")
            File(dir, "only-dst.txt").writeText("d-only")
        }
        val result = MoveEngine.moveWithin(
            listOf(MoveTask(srcDir, destDir, existing = MoveExisting.MERGE_DIR)),
        )
        assertEquals(1, result.moved)
        // 同名文件被源覆盖
        assertEquals("src", File(destDir, "clash.txt").readText())
        // 目标独有文件绝不丢失（§5.3.4）
        assertEquals("d-only", File(destDir, "only-dst.txt").readText())
        assertEquals("s-only", File(destDir, "only-src.txt").readText())
        assertFalse(srcDir.exists())
    }

    // ---------- 并发 / 失败路径 ----------

    @Test
    fun occupiedTargetWithoutPolicyFailsThatItemAndContinues() = runBlocking {
        val s1 = tmp.newFile("f1.txt").apply { writeText("1") }
        val s2 = tmp.newFile("f2.txt").apply { writeText("2") }
        val target = tmp.newFolder("t7")
        File(target, "f1.txt").writeText("occupied")
        val result = MoveEngine.moveWithin(listOf(MoveTask(s1, File(target, "f1.txt")), MoveTask(s2, File(target, "f2.txt"))))
        assertEquals(1, result.moved)
        assertEquals(1, result.failed.size)
        assertEquals(s1, result.failed.single().source)
        assertEquals("occupied", File(target, "f1.txt").readText())
        assertEquals("2", File(target, "f2.txt").readText())
    }

    @Test
    fun missingSourceIsReportedAsFailure() = runBlocking {
        val ghost = File(tmp.root, "ghost.txt")
        val target = tmp.newFolder("t8")
        val result = MoveEngine.moveWithin(listOf(MoveTask(ghost, File(target, "ghost.txt"))))
        assertEquals(0, result.moved)
        assertEquals(1, result.failed.size)
        assertTrue(result.failed.single().message.contains("源不存在"))
    }

    // ---------- 取消（§5.3.5） ----------

    @Test
    fun cancellationBetweenItemsKeepsCompletedAndCleansTemps() = runBlocking {
        val dir1 = tmp.newFolder("c1").also { dir ->
            File(dir, "a.txt").writeText("A")
        }
        val dir2 = tmp.newFolder("c2").also { dir ->
            File(dir, "b.txt").writeText("B")
        }
        val target = tmp.newFolder("t9")
        // 复制兜底路径的回调序列：①"正在移动 c1"(done=0) ②a.txt 复制完成(done=1)
        // ③"正在移动 c2"——任务 2 的开始汇报时取消，此时任务 1 已完整落位
        val result = MoveEngine.moveWithin(
            tasks = listOf(MoveTask(dir1, File(target, "c1")), MoveTask(dir2, File(target, "c2"))),
            listener = ProgressListener { _, _, stage ->
                if (stage.endsWith("c2")) throw CancellationException("user cancelled")
            },
            forceCopyFallback = true,
        )
        assertTrue("应报告取消", result.cancelled)
        assertEquals("取消前的项应已完成", 1, result.moved)
        assertEquals(0, result.failed.size)
        assertTrue("已完成项落位", File(target, "c1/a.txt").exists())
        assertFalse("后续项不应执行", File(target, "c2").exists())
        assertTrue("源保留（未处理项）", File(dir2, "b.txt").exists())
    }

    // ---------- .dsh-moving 残留清理（§5.3.6） ----------

    @Test
    fun finallyCleansTempOnCancelledCopy() = runBlocking {
        // 目录含多个文件：取消发生在文件之间时，stageCopy 产生的中转目录由 finally 清理
        val srcDir = tmp.newFolder("cancel-src").also { dir ->
            File(dir, "f1.bin").writeBytes(ByteArray(120_000))
            File(dir, "f2.bin").writeBytes(ByteArray(120_000))
            File(dir, "f3.bin").writeBytes(ByteArray(120_000))
        }
        val target = tmp.newFolder("t10")
        val result = MoveEngine.moveWithin(
            tasks = listOf(MoveTask(srcDir, File(target, "cancel-src"))),
            listener = ProgressListener { done, _, _ ->
                if (done > 0) throw CancellationException("cancel mid-copy")
            },
            forceCopyFallback = true,
        )
        assertTrue(result.cancelled)
        // finally 清理：目标目录旁不允许残留 .dsh-moving-*
        val residuals = target.listFiles()!!.filter { it.name.startsWith(".dsh-moving-") }
        assertTrue("不应残留中转临时名: $residuals", residuals.isEmpty())
    }

    @Test
    fun startupScanRemovesOnlyEngineTempNames() {
        val root = tmp.newFolder("scan-root")
        val validDirResidual = File(root, "user-data/.dsh-moving-123456789").apply { mkdirs() }
        File(validDirResidual, "partial.bin").writeBytes(ByteArray(10))
        File(root, "user-data/.dsh-moving-987654321").apply { mkdirs() }
        val validFileResidual = File(root, ".dsh-moving-42").apply { writeText("x") }
        // 用户自建文件不匹配引擎命名（非纯数字后缀），不得误删
        val userFile = File(root, "user-data/.dsh-moving-mydata").apply { writeText("keep me") }
        val normalFile = File(root, "user-data/normal.txt").apply { writeText("data") }

        val removed = MoveEngine.cleanupMovingResiduals(root)

        assertEquals(3, removed)
        assertFalse(validDirResidual.exists())
        assertFalse(File(root, "user-data/.dsh-moving-987654321").exists())
        assertFalse(validFileResidual.exists())
        assertTrue(userFile.exists())
        assertEquals("keep me", userFile.readText())
        assertEquals("data", normalFile.readText())
    }

    // ---------- 审查修正回归：OVERWRITE 失败路径数据零丢失（注入失败，全平台确定性执行） ----------

    /**
     * 「删除旧目标失败」用注入的 deleteTarget = { false } 驱动真实失败分支——
     * 本机实测：Windows（JDK 21）上打开句柄、只读属性均已不能阻止删除（环境实测
     * File.delete on readonly -> true），无法用文件系统手段可靠构造，故走注入。
     * 断言（stagedByRename 路径）：源唯一副本被还原回原路径、内容完整、无 `.dsh-moving-*` 残留。
     */
    @Test
    fun overwriteFailureOnUndeletableTargetPreservesSource() = runBlocking {
        val src = tmp.newFile("keep.txt").apply { writeText("PRECIOUS") }
        val target = tmp.newFolder("t11")
        val dest = File(target, "keep.txt").apply {
            mkdirs()
            File(this, "child").mkdirs()
            File(this, "child/x.txt").writeText("x")
        }
        val result = MoveEngine.moveWithin(
            listOf(MoveTask(src, dest, existing = MoveExisting.OVERWRITE)),
            deleteTarget = { false },
        )
        assertEquals(1, result.failed.size)
        assertTrue("源必须完好（数据零丢失）", src.exists())
        assertEquals("PRECIOUS", src.readText())
        assertTrue(
            target.listFiles()!!.filter { it.name.startsWith(".dsh-moving-") }.isEmpty(),
        )
    }

    /** 复制兜底进入中转位的变体（源本体一直在）：删除旧目标失败 → 副本清理、源必须完好。 */
    @Test
    fun overwriteFailureWhenStagedByCopyKeepsSourceIntact() = runBlocking {
        val src = tmp.newFile("keep.txt").apply { writeText("PRECIOUS") }
        val target = tmp.newFolder("t12")
        val dest = File(target, "keep.txt").apply {
            mkdirs()
            File(this, "child").mkdirs()
            File(this, "child/x.txt").writeText("x")
        }
        val result = MoveEngine.moveWithin(
            listOf(MoveTask(src, dest, existing = MoveExisting.OVERWRITE)),
            forceCopyFallback = true,
            deleteTarget = { false },
        )
        assertEquals(1, result.failed.size)
        assertTrue("源必须完好（数据零丢失）", src.exists())
        assertEquals("PRECIOUS", src.readText())
        assertTrue(
            target.listFiles()!!.filter { it.name.startsWith(".dsh-moving-") }.isEmpty(),
        )
    }

    // ---------- 合并失败可追溯性（R20 回归：子项失败继续 + 源保留 + 聚合消息） ----------

    /** 平铺层：一个子项删除失败 → 兄弟子项照常合并、源保留失败子项、异常含已合并计数与失败路径。 */
    @Test
    fun mergeFailureContinuesSiblingsAndKeepsSource() = runBlocking {
        val srcDir = tmp.newFolder("mf-src").also { dir ->
            File(dir, "good.txt").writeText("good")
            File(dir, "clash.txt").writeText("src-clash")
        }
        val target = tmp.newFolder("t13")
        val destDir = File(target, "mf-src").also { dir ->
            dir.mkdirs()
            // clash.txt 的旧目标为目录 + 不可删内容（注入 deleteTarget={false} 使删除必败）
            File(dir, "clash.txt").mkdirs().also { File(dir, "clash.txt/old.txt").writeText("old") }
        }
        val result = MoveEngine.moveWithin(
            listOf(MoveTask(srcDir, destDir, existing = MoveExisting.MERGE_DIR)),
            deleteTarget = { false },
        )
        assertEquals(1, result.failed.size)
        val message = result.failed.single().message
        assertTrue("异常应含已合并计数", message.contains("已合并 1 项"))
        assertTrue("异常应含首个失败子项路径", message.contains("clash.txt"))
        // 兄弟子项已合并到目标
        assertEquals("good", File(destDir, "good.txt").readText())
        // 失败子项：目标保留旧内容、源保留原文件（可补移）
        assertEquals("old", File(destDir, "clash.txt/old.txt").readText())
        assertEquals("src-clash", File(srcDir, "clash.txt").readText())
        assertTrue("本轮无中转残留", target.listFiles()!!.none { it.name.startsWith(".dsh-moving-") })
    }

    /** 嵌套层：子目录内失败 → 内层消息并入外层、聚合计数跨层级精确（MergeStats 共享）。 */
    @Test
    fun mergeFailureInNestedSubdirAggregatesPrecisely() = runBlocking {
        val srcDir = tmp.newFolder("mn-src").also { dir ->
            File(dir, "top.txt").writeText("top")
            File(dir, "sub").also { sub ->
                sub.mkdirs()
                File(sub, "deep.txt").writeText("deep")
                File(sub, "clash2.txt").writeText("src-clash2")
            }
        }
        val target = tmp.newFolder("t14")
        val destDir = File(target, "mn-src").also { dir ->
            dir.mkdirs()
            File(dir, "sub").mkdirs().also {
                File(dir, "sub/clash2.txt").mkdirs().also { File(dir, "sub/clash2.txt/old.txt").writeText("old") }
            }
        }
        val result = MoveEngine.moveWithin(
            listOf(MoveTask(srcDir, destDir, existing = MoveExisting.MERGE_DIR)),
            deleteTarget = { false },
        )
        assertEquals(1, result.failed.size)
        val message = result.failed.single().message
        // 精确聚合计数：top.txt + sub/deep.txt = 2（MergeStats 跨层级共享）
        assertTrue("聚合计数应跨层级精确：$message", message.contains("已合并 2 项"))
        assertTrue("应指明失败发生在 sub 内：$message", message.contains("sub"))
        assertTrue(message.contains("clash2.txt"))
        assertEquals("top", File(destDir, "top.txt").readText())
        assertEquals("deep", File(destDir, "sub/deep.txt").readText())
        assertEquals("src-clash2", File(srcDir, "sub/clash2.txt").readText())
        assertTrue("本轮无中转残留", target.listFiles()!!.none { it.name.startsWith(".dsh-moving-") })
    }

    @Test
    fun startupScanIsLimitedToMoveCapableLayers() {
        val fakeFiles = tmp.newFolder("filesDir")
        val current = File(fakeFiles, "runtime/runtime-current")
        val base = File(current, "base").apply { mkdirs() }
        File(current, "android-side").apply { mkdirs() }
        val userData = File(fakeFiles, "user-data").apply { mkdirs() }
        // 应清理：base 与 user-data 内的引擎残留
        File(base, ".dsh-moving-111").apply {
            mkdirs()
            File(this, "p.bin").writeBytes(ByteArray(4))
        }
        File(userData, ".dsh-moving-222").writeText("x")
        // 不应触碰：android-side（移动不可达层）、用户文件、非引擎命名
        File(current, "android-side/.dsh-moving-333").writeText("outside")
        File(userData, "keep.txt").writeText("data")
        File(base, ".dsh-moving-abc").writeText("user")

        val removed = MoveEngine.cleanupMovingResidualsInAppDirs(fakeFiles)

        assertEquals(2, removed)
        assertFalse(File(base, ".dsh-moving-111").exists())
        assertFalse(File(userData, ".dsh-moving-222").exists())
        assertTrue("android-side 不在扫描范围", File(current, "android-side/.dsh-moving-333").exists())
        assertEquals("data", File(userData, "keep.txt").readText())
        assertEquals("user", File(base, ".dsh-moving-abc").readText())
    }
}
