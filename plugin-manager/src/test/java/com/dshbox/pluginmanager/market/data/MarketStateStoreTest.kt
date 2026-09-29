package com.dshbox.pluginmanager.market.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * state.json 的读-改-写（含文件 IO）。
 *
 * 这里钉两条最容易造成**用户数据静默丢失**的规则：
 * 1. 盘上的文件存在但读不出来时**拒绝写入**——按空状态合并会把桌面端写的
 *    字段（分组、收藏、下载区域……）整块抹掉；
 * 2. 写入用"临时文件 + 原子 move"，**不先删原文件**——先删后 rename 的窗口里
 *    对方读到"没有文件"，等于读到"设置全空"，而且 rename 失败时原文件已经没了。
 */
class MarketStateStoreTest {

    private val roots = mutableListOf<File>()

    private fun tempDir(): File =
        Files.createTempDirectory("market-state-test").toFile().also { roots.add(it) }

    @After
    fun cleanUp() {
        roots.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun stateFile(dir: File) = File(File(dir, ".dsh-market"), "state.json")

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    @Test
    fun missingFileCanBeWritten() {
        val file = stateFile(tempDir())
        val store = MarketStateStore(file)
        assertTrue(store.readState() is MarketStateRead.Missing)
        assertTrue(store.writeOutcome(listOf("a"), mapOf("a" to "note")) is MarketStateWrite.Ok)
        assertTrue(file.isFile)
        assertEquals(setOf("a"), store.disabled())
        assertEquals(mapOf("a" to "note"), store.notes())
    }

    @Test
    fun brokenFileRefusesTheWriteAndKeepsTheOriginalBytes() {
        val file = stateFile(tempDir())
        val original = """{"disabled":["keep"],"favorites":["x"]"""
        write(file, original)
        val store = MarketStateStore(file)
        assertTrue(store.readState() is MarketStateRead.Broken)
        val outcome = store.writeOutcome(listOf("a"), emptyMap())
        assertTrue("读不出来就必须拒绝写", outcome is MarketStateWrite.Refused)
        assertEquals("原文件必须逐字节保持不变", original, file.readText(Charsets.UTF_8))
        assertFalse("拒绝写入时不该落盘", file.readText(Charsets.UTF_8).contains("\"a\""))
    }

    @Test
    fun emptyFileIsTreatedAsBrokenNotAsEmptyState() {
        val file = stateFile(tempDir())
        write(file, "")
        val store = MarketStateStore(file)
        assertTrue(store.readState() is MarketStateRead.Broken)
        assertTrue(store.writeOutcome(listOf("a"), emptyMap()) is MarketStateWrite.Refused)
        assertEquals("", file.readText(Charsets.UTF_8))
    }

    @Test
    fun validFileIsWrittenWithPreservedFieldsIntact() {
        val file = stateFile(tempDir())
        write(
            file,
            """
            {
              "disabled": ["old"],
              "favorites": ["https://example.com/p"],
              "groups": {"g":["x"]},
              "channel": "beta"
            }
            """.trimIndent(),
        )
        val store = MarketStateStore(file)
        assertTrue(store.readState() is MarketStateRead.Ok)
        assertTrue(store.writeOutcome(listOf("new"), emptyMap()) is MarketStateWrite.Ok)
        val text = file.readText(Charsets.UTF_8)
        assertTrue("桌面端的字段不能被抹掉", text.contains(""""favorites": ["https://example.com/p"]"""))
        assertTrue(text.contains(""""groups": {"g":["x"]}"""))
        assertTrue(text.contains(""""channel": "beta"""))
        assertTrue(text.contains(""""disabled": ["new"]"""))
    }

    @Test
    fun preservedScalarFieldsAreWrittenBackAsValidJson() {
        // 桌面端写进去的 channel / region / githubProxy 都是**字符串**字段。
        // 直接 `get(key).toString()` 会把它们写成不带引号的 `beta`，整份
        // state.json 随之变成非法 JSON —— 对方读到的就是"设置全部丢失"。
        val file = stateFile(tempDir())
        write(
            file,
            """{"channel":"beta","regionAuto":true,"count":3,"note":null,"nested":{"a":[1,2]}}""",
        )
        val store = MarketStateStore(file)
        assertTrue(store.writeOutcome(listOf("a"), emptyMap()) is MarketStateWrite.Ok)
        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.contains(""""channel": "beta""""))
        assertTrue(text.contains(""""regionAuto": true"""))
        assertTrue(text.contains(""""count": 3"""))
        assertTrue(text.contains(""""note": null"""))
        assertTrue(text.contains(""""nested": {"a":[1,2]}"""))
        // 写回的文件必须仍然可被解析（这是"字段被保留"的前提）。
        assertTrue(MarketStateStore(file).readState() is MarketStateRead.Ok)
    }

    @Test
    fun successfulWriteLeavesNoTempFileBehind() {
        val file = stateFile(tempDir())
        val store = MarketStateStore(file)
        store.writeOutcome(listOf("a"), emptyMap())
        store.writeOutcome(listOf("b"), emptyMap())
        val leftovers = file.parentFile!!.listFiles()!!.filter { it.name.endsWith(".tmp") }
        assertTrue("临时文件必须被搬走，残留=$leftovers", leftovers.isEmpty())
    }

    @Test
    fun failedMoveKeepsTheOriginalAndCleansUpTheTempFile() {
        // 用一个**非空目录**占住目标路径：move 会失败。此时原目标必须还在，
        // 且不能留下临时文件（"先删再 rename"的写法会在这里把目录内容删掉）。
        val dir = tempDir()
        val target = stateFile(dir)
        target.mkdirs()
        File(target, "keep.txt").writeText("keep", Charsets.UTF_8)

        val outcome = MarketStateStore(target).writeOutcome(listOf("a"), emptyMap())
        assertTrue(outcome is MarketStateWrite.IoFailed)
        assertTrue("原路径必须仍在", target.isDirectory)
        assertEquals("keep", File(target, "keep.txt").readText(Charsets.UTF_8))
        val leftovers = target.parentFile!!.listFiles()!!.filter { it.name.endsWith(".tmp") }
        assertTrue("失败时必须清掉临时文件，残留=$leftovers", leftovers.isEmpty())
    }

    @Test
    fun writeBooleanWrapperMatchesTheOutcome() {
        val file = stateFile(tempDir())
        val store = MarketStateStore(file)
        assertTrue(store.write(listOf("a"), emptyMap()))
        write(file, "{ broken")
        assertFalse("盘上文件坏了就不能再报成功", store.write(listOf("b"), emptyMap()))
    }

    @Test
    fun writeKeepsDisabledWhenOnlyTheNoteChanges() {
        val file = stateFile(tempDir())
        val store = MarketStateStore(file)
        store.writeOutcome(listOf("a"), mapOf("a" to "n1"))
        // 模拟 setNote 的调用形态：读出来 → 只改 notes → 写回。
        val state = store.read()
        store.writeOutcome(state.disabled, state.notes + ("b" to "n2"))
        val after = store.read()
        assertEquals(listOf("a"), after.disabled)
        assertEquals(mapOf("a" to "n1", "b" to "n2"), after.notes)
    }

    @Test
    fun rebuildBacksUpTheBrokenFileAndThenWritesSucceed() {
        // 状态文件坏了时所有写入都被拒，用户无从下手。rebuild 把原文件改名留档，
        // 再写一份只含我们字段的新文件——之后普通写入必须重新可用。
        val file = stateFile(tempDir())
        val broken = """{"disabled":["keep"],"favorites":["x"]"""
        write(file, broken)
        val store = MarketStateStore(file)
        assertTrue(store.readState() is MarketStateRead.Broken)

        val outcome = store.rebuild(listOf("a"), mapOf("a" to "n"))
        assertTrue("重建必须成功，实际=$outcome", outcome is MarketStateWrite.Ok)
        assertTrue(store.readState() is MarketStateRead.Ok)
        assertEquals(setOf("a"), store.disabled())
        assertEquals(mapOf("a" to "n"), store.notes())

        val backups = file.parentFile!!.listFiles()!!
            .filter { it.name.startsWith("state.json.broken-") }
        assertEquals("原文件必须留档一份", 1, backups.size)
        assertEquals("留档必须是原文件逐字节", broken, backups.single().readText(Charsets.UTF_8))

        // 重建之后，普通写入不再被拒。
        assertTrue(store.writeOutcome(listOf("b"), emptyMap()) is MarketStateWrite.Ok)
        assertEquals(setOf("b"), store.disabled())
    }

    @Test
    fun rebuildOnAHealthyFileIsJustAWrite() {
        val file = stateFile(tempDir())
        write(file, """{"disabled":["old"],"favorites":["keep"]}""")
        val store = MarketStateStore(file)
        assertTrue(store.rebuild(listOf("new"), emptyMap()) is MarketStateWrite.Ok)
        // 没坏就不该留档，桌面端的字段也要照旧保留。
        assertTrue(
            file.parentFile!!.listFiles()!!.none { it.name.startsWith("state.json.broken-") },
        )
        assertTrue(file.readText(Charsets.UTF_8).contains(""""favorites": ["keep"]"""))
    }

    @Test
    fun staleTempFilesAreCleanedUpOnWrite() {
        // 进程在"写完临时文件、还没 move"之间被杀会永久留下 .tmp；写入前清掉旧的。
        val file = stateFile(tempDir())
        file.parentFile!!.mkdirs()
        val stale = File(file.parentFile, file.name + ".deadbeef.tmp")
        stale.writeText("junk", Charsets.UTF_8)
        stale.setLastModified(System.currentTimeMillis() - 11 * 60 * 1000L)
        val inFlight = File(file.parentFile, file.name + ".cafebabe.tmp")
        inFlight.writeText("in flight", Charsets.UTF_8)

        MarketStateStore(file).writeOutcome(listOf("a"), emptyMap())

        assertFalse("超过 10 分钟的残留必须清掉", stale.exists())
        assertTrue("正在并发写的那份不能被误删", inFlight.exists())
    }
}
