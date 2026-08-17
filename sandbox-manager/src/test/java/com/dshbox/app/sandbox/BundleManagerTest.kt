package com.dshbox.app.sandbox

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class BundleManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var manager: BundleManager

    @Before
    fun setUp() {
        manager = BundleManager(SandboxConfig(appFilesDir = tmp.root))
    }

    private fun makeTarGz(target: File, files: Map<String, String>) {
        FileOutputStream(target).use { fos ->
            GzipCompressorOutputStream(fos).use { gzip ->
                TarArchiveOutputStream(gzip).use { tar ->
                    for ((path, content) in files) {
                        val entry = TarArchiveEntry(path)
                        val bytes = content.toByteArray()
                        entry.size = bytes.size.toLong()
                        tar.putArchiveEntry(entry)
                        tar.write(bytes)
                        tar.closeArchiveEntry()
                    }
                }
            }
        }
    }

    @Test
    fun sha256IsStableAndVerifies() {
        val f = tmp.newFile("bundle.txt")
        f.writeText("hello")
        val hash = manager.sha256(f)
        assertEquals(64, hash.length)
        assertTrue(manager.verifySha256(f, hash))
        assertFalse(manager.verifySha256(f, "0".repeat(64)))
    }

    @Test
    fun extractTarGzKeepsFilesAndDirs() {
        val tarFile = tmp.newFile("bundle.tar.gz")
        makeTarGz(tarFile, mapOf(
            "debian/etc/hostname" to "dshapp\n",
            "debian/opt/dshapp/start_dsh.sh" to "#!/bin/bash\necho ok\n",
        ))
        val dest = File(tmp.root, "dest")
        val result = manager.extractTarGz(tarFile, dest)
        assertTrue(result is com.dshbox.app.common.AppResult.Success)
        assertTrue(File(dest, "debian/etc/hostname").isFile)
        assertEquals("dshapp\n", File(dest, "debian/etc/hostname").readText())
        assertTrue(File(dest, "debian/opt/dshapp/start_dsh.sh").isFile)
    }

    @Test
    fun extractTarGzPreservesExecutableMode() {
        val tarFile = tmp.newFile("bundle-mode.tar.gz")
        FileOutputStream(tarFile).use { fos ->
            GzipCompressorOutputStream(fos).use { gzip ->
                TarArchiveOutputStream(gzip).use { tar ->
                    val entry = TarArchiveEntry("debian/usr/local/bin/node")
                    entry.mode = 0x1ED // 0755 octal
                    val bytes = "#!/bin/sh\necho ok\n".toByteArray()
                    entry.size = bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        val dest = File(tmp.root, "dest-mode")
        assertTrue(manager.extractTarGz(tarFile, dest) is com.dshbox.app.common.AppResult.Success)
        val node = File(dest, "debian/usr/local/bin/node")
        assertTrue(node.exists())
        assertTrue(node.canExecute())
    }

    @Test
    fun installToNewSlotRejectsBadShaAndAcceptsGoodSha() {
        val tarFile = tmp.newFile("bundle.tar.gz")
        makeTarGz(tarFile, mapOf("etc/hostname" to "dshapp\n"))
        val bad = manager.installToNewSlot(tarFile, "0".repeat(64))
        assertTrue(bad is com.dshbox.app.common.AppResult.Failure)

        val hash = manager.sha256(tarFile)
        val good = manager.installToNewSlot(tarFile, hash)
        assertTrue(good is com.dshbox.app.common.AppResult.Success)
        assertTrue(File(manager.newSlotDir(), "debian/etc/hostname").isFile)
    }

    @Test
    fun promoteNewSlotAndRollback() {
        val v1 = tmp.newFile("bundle-v1.tar.gz")
        makeTarGz(v1, mapOf("etc/hostname" to "dshapp-v1\n"))
        val hash1 = manager.sha256(v1)
        assertTrue(manager.installToNewSlot(v1, hash1) is com.dshbox.app.common.AppResult.Success)
        assertTrue(manager.promoteNewSlotToCurrent() is com.dshbox.app.common.AppResult.Success)
        assertTrue(File(manager.currentSlotDir(), "debian/etc/hostname").isFile)

        val v2 = tmp.newFile("bundle-v2.tar.gz")
        makeTarGz(v2, mapOf("etc/hostname" to "dshapp-v2\n"))
        val hash2 = manager.sha256(v2)
        assertTrue(manager.installToNewSlot(v2, hash2) is com.dshbox.app.common.AppResult.Success)
        assertTrue(manager.promoteNewSlotToCurrent() is com.dshbox.app.common.AppResult.Success)
        assertEquals("dshapp-v2\n", File(manager.currentSlotDir(), "debian/etc/hostname").readText())
        assertTrue(File(manager.backupSlotDir(), "debian/etc/hostname").isFile)

        assertTrue(manager.rollback() is com.dshbox.app.common.AppResult.Success)
        assertEquals("dshapp-v1\n", File(manager.currentSlotDir(), "debian/etc/hostname").readText())
    }
}
