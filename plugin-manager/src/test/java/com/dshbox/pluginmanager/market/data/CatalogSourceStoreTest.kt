package com.dshbox.pluginmanager.market.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「当前数据源」的落盘与回落。
 *
 * 钉两条规则：
 * 1. **读不出来就用默认源** —— 文件缺失、内容空白、id 是旧版本残留（认不出），
 *    一律回落默认。一个读坏的选择不该让市场打不开。
 * 2. **写失败只如实返回 false，不抛** —— 调用方据此提示一句，切换本身照做。
 */
class CatalogSourceStoreTest {

    private val roots = mutableListOf<File>()

    private fun tempDir(): File =
        Files.createTempDirectory("catalog-source-test").toFile().also { roots.add(it) }

    private fun storeIn(dir: File) = CatalogSourceStore(File(dir, "catalog-source"))

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun absentFileFallsBackToDefault() = runBlocking {
        assertEquals(CatalogSource.DEFAULT, storeIn(tempDir()).read())
    }

    @Test
    fun roundTripsTheSelection() = runBlocking {
        val store = storeIn(tempDir())
        assertTrue(store.write(CatalogSource.OFFICIAL))
        assertEquals(CatalogSource.OFFICIAL, store.read())
        assertTrue(store.write(CatalogSource.MOBILE))
        assertEquals(CatalogSource.MOBILE, store.read())
    }

    @Test
    fun unknownIdFallsBackToDefault() = runBlocking {
        val dir = tempDir()
        File(dir, "catalog-source").writeText("a-source-we-removed\n")
        assertEquals(CatalogSource.DEFAULT, storeIn(dir).read())
    }

    @Test
    fun blankContentFallsBackToDefault() = runBlocking {
        val dir = tempDir()
        File(dir, "catalog-source").writeText("  \n\n")
        assertEquals(CatalogSource.DEFAULT, storeIn(dir).read())
    }

    @Test
    fun writeCreatesTheMarketDirectoryWhenMissing() = runBlocking {
        // 真实路径是 <profile>/.dsh-market/catalog-source，那个目录可能还不存在。
        val nested = File(tempDir(), "profile/.dsh-market")
        val store = CatalogSourceStore(File(nested, "catalog-source"))
        assertTrue(store.write(CatalogSource.OFFICIAL))
        assertEquals(CatalogSource.OFFICIAL, store.read())
    }

    @Test
    fun writeFailureIsReportedNotThrown() = runBlocking {
        // 父路径是个文件 —— 写入必然失败：不能抛，只能返回 false。
        val blocker = File(tempDir(), "blocker").apply { writeText("x") }
        val store = CatalogSourceStore(File(blocker, "catalog-source"))
        assertFalse(store.write(CatalogSource.OFFICIAL))
        assertEquals(CatalogSource.DEFAULT, store.read())
    }
}
