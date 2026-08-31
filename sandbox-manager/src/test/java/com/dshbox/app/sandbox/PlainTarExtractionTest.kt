package com.dshbox.app.sandbox

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

/**
 * 1.1.0 (M5): BundleManager.extractTarGz must accept UNCOMPRESSED tar by the
 * "ustar" magic at offset 257, in addition to gzip/zstd — a user-supplied DSH
 * layer packed as plain .tar installs without renaming.
 */
class PlainTarExtractionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // lazy: TemporaryFolder only creates its root inside @Before, so the manager
    // must not touch tmp.root during test-class construction.
    private val manager: BundleManager by lazy { BundleManager(SandboxConfig(appFilesDir = tmp.root)) }

    @Test
    fun plainTarIsExtractedByMagic() {
        val tarFile = File(tmp.root, "layer.tar")
        FileOutputStream(tarFile).use { fos ->
            TarArchiveOutputStream(fos).use { tar ->
                for ((path, content) in mapOf(
                    "package.json" to "{\"name\":\"dsh-layer\",\"version\":\"0.1.2\"}",
                    "node_modules/@deepseek-ai/dsh/lib/bin.js" to "// dsh entry\n",
                )) {
                    val entry = TarArchiveEntry(path)
                    val bytes = content.toByteArray()
                    entry.size = bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        val dest = File(tmp.root, "dsh")
        val result = manager.extractTarGz(tarFile, dest)
        assertTrue(result is com.dshbox.app.common.AppResult.Success)
        assertEquals(
            "// dsh entry\n",
            File(dest, "node_modules/@deepseek-ai/dsh/lib/bin.js").readText(),
        )
    }
}
