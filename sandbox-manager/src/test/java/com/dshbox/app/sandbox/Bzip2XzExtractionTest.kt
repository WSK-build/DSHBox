package com.dshbox.app.sandbox

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

/**
 * 1.1.0 (M12.4): BundleManager.extractTarGz 按魔数支持 bzip2（BZh）与 xz
 * （FD 37 7A 58 5A 00）——此前这两种压缩会误入 gzip 分支得到误导性报错。
 */
class Bzip2XzExtractionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val manager: BundleManager by lazy { BundleManager(SandboxConfig(appFilesDir = tmp.root)) }

    @Test
    fun bzip2TarIsExtractedByMagic() {
        val tarFile = File(tmp.root, "layer.tar.bz2")
        FileOutputStream(tarFile).use { fos ->
            BZip2CompressorOutputStream(fos).use { bz ->
                TarArchiveOutputStream(bz).use { tar ->
                    val entry = TarArchiveEntry("package.json")
                    val bytes = "{\"name\":\"dsh-layer\",\"version\":\"0.1.2\"}".toByteArray()
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
            "{\"name\":\"dsh-layer\",\"version\":\"0.1.2\"}",
            File(dest, "package.json").readText(),
        )
    }

    @Test
    fun xzTarIsExtractedByMagic() {
        val tarFile = File(tmp.root, "layer.tar.xz")
        FileOutputStream(tarFile).use { fos ->
            XZCompressorOutputStream(fos).use { xz ->
                TarArchiveOutputStream(xz).use { tar ->
                    val entry = TarArchiveEntry("node_modules/@deepseek-ai/dsh/lib/bin.js")
                    val bytes = "// dsh entry\n".toByteArray()
                    entry.size = bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        val dest = File(tmp.root, "dsh-xz")
        val result = manager.extractTarGz(tarFile, dest)
        assertTrue(result is com.dshbox.app.common.AppResult.Success)
        assertEquals(
            "// dsh entry\n",
            File(dest, "node_modules/@deepseek-ai/dsh/lib/bin.js").readText(),
        )
    }
}