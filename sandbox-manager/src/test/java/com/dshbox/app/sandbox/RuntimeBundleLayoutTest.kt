package com.dshbox.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the 1.1.0 runtime-bundle ZIP layout analysis (M1/M2).
 *
 * The M1 case reproduces the 1.0.0 fatal bug exactly: the official zip lists
 * `base.tar.zst` BEFORE `base.tar.zst.sha256`, and the old startsWith() matching
 * let the sidecar overwrite the archive — every official import failed with
 * "Not in GZIP format".
 */
class RuntimeBundleLayoutTest {

    @Test
    fun officialFlatZipMatchesArchivesNotSidecars() {
        val result = RuntimeBundleLayout.analyze(
            listOf(
                "base.tar.zst",
                "base.tar.zst.sha256",
                "node.tar.zst",
                "node.tar.zst.sha256",
                "android-side.tar.zst",
                "android-side.tar.zst.sha256",
                "runtime-profile.json",
            ),
        )
        assertTrue(result is RuntimeBundleLayout.Result.Ok)
        val ok = result as RuntimeBundleLayout.Result.Ok
        assertEquals(
            mapOf(
                "base" to "base.tar.zst",
                "node" to "node.tar.zst",
                "android-side" to "android-side.tar.zst",
            ),
            ok.archives,
        )
        assertEquals(
            mapOf(
                "base" to "base.tar.zst.sha256",
                "node" to "node.tar.zst.sha256",
                "android-side" to "android-side.tar.zst.sha256",
            ),
            ok.sidecars,
        )
        assertEquals("runtime-profile.json", ok.profilePath)
    }

    @Test
    fun commonTopLevelFolderIsStripped() {
        val result = RuntimeBundleLayout.analyze(
            listOf(
                "runtime/base.tar.zst",
                "runtime/base.tar.zst.sha256",
                "runtime/node.tar.zst",
                "runtime/android-side.tar.zst",
                "runtime/runtime-profile.json",
            ),
        )
        assertTrue(result is RuntimeBundleLayout.Result.Ok)
        val ok = result as RuntimeBundleLayout.Result.Ok
        assertEquals("base.tar.zst", ok.archives["base"])
        assertEquals("runtime-profile.json", ok.profilePath)
        assertEquals("base.tar.zst", ok.targets["runtime/base.tar.zst"])
    }

    @Test
    fun mixedRootAndFolderEntriesDoNotStrip() {
        // A top-level README next to the folder: the prefix is NOT shared by all
        // entries, so nothing may be stripped (README stays at root, layers too).
        val result = RuntimeBundleLayout.analyze(
            listOf("README.txt", "runtime/base.tar.zst", "runtime/node.tar.zst"),
        )
        assertTrue(result is RuntimeBundleLayout.Result.Ok)
        val ok = result as RuntimeBundleLayout.Result.Ok
        assertEquals("runtime/base.tar.zst", ok.targets["runtime/base.tar.zst"])
        assertNull(ok.profilePath)
    }

    @Test
    fun traversalEntryIsRejected() {
        val result = RuntimeBundleLayout.analyze(
            listOf("base.tar.zst", "../../evil.txt"),
        )
        assertTrue(result is RuntimeBundleLayout.Result.Unsafe)
        assertEquals("../../evil.txt", (result as RuntimeBundleLayout.Result.Unsafe).entryName)
    }

    @Test
    fun sidecarOnlyArchiveNameDoesNotMatchAsLayer() {
        // A zip containing ONLY sidecars must not produce phantom layer archives.
        val result = RuntimeBundleLayout.analyze(
            listOf("base.tar.zst.sha256", "runtime-profile.json"),
        )
        assertTrue(result is RuntimeBundleLayout.Result.Ok)
        val ok = result as RuntimeBundleLayout.Result.Ok
        assertTrue(ok.archives.isEmpty())
        assertTrue(ok.sidecars.isEmpty())
    }

    @Test
    fun bareTarWithoutExtensionIsAValidLayerArchive() {
        // 1.1.0 (M12.4): 裸 base.tar（无压缩扩展）也是合法层归档——压缩格式按魔数识别。
        val result = RuntimeBundleLayout.analyze(
            listOf("base.tar", "base.tar.sha256", "node.tar", "android-side.tar", "runtime-profile.json"),
        )
        assertTrue(result is RuntimeBundleLayout.Result.Ok)
        val ok = result as RuntimeBundleLayout.Result.Ok
        assertEquals(
            mapOf("base" to "base.tar", "node" to "node.tar", "android-side" to "android-side.tar"),
            ok.archives,
        )
    }

    @Test
    fun layerOfArchiveNameAcceptsAllShapesAndRejectsNeighbours() {
        assertEquals("base", RuntimeBundleLayout.layerOfArchiveName("base.tar"))
        assertEquals("base", RuntimeBundleLayout.layerOfArchiveName("base.tar.zst"))
        assertEquals("base", RuntimeBundleLayout.layerOfArchiveName("base.tar.bz2"))
        assertEquals("base", RuntimeBundleLayout.layerOfArchiveName("base.tar.xz"))
        assertEquals("node", RuntimeBundleLayout.layerOfArchiveName("nested/dir/node.tar.gz"))
        assertNull(RuntimeBundleLayout.layerOfArchiveName("base.tar.zst.sha256"))
        assertNull(RuntimeBundleLayout.layerOfArchiveName("base.tarball"))
        assertNull(RuntimeBundleLayout.layerOfArchiveName("base.tar2"))
        assertNull(RuntimeBundleLayout.layerOfArchiveName("runtime-profile.json"))
        // 任意字母数字扩展名均可（如 .tar.v），与「压缩格式仅按魔数判断」的口径一致。
        assertEquals("base", RuntimeBundleLayout.layerOfArchiveName("base.tar.v"))
    }

    @Test
    fun windowsSeparatorsAndDotSlashNormalize() {
        val result = RuntimeBundleLayout.analyze(
            listOf(".\\base.tar.zst", "base.tar.zst.sha256"),
        )
        assertTrue(result is RuntimeBundleLayout.Result.Ok)
        val ok = result as RuntimeBundleLayout.Result.Ok
        assertEquals("base.tar.zst", ok.archives["base"])
    }
}
