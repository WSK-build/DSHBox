package com.dshbox.browser.runtime

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BrowserUploadProviderOrphanCleanupAndroidTest {
    @Test
    fun cleanupDeletesOnlyStrictlyNamedRegularSnapshotFiles() {
        val cacheFixture = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile,
            "upload-cleanup-${UUID.randomUUID().toString().replace("-", "")}")
        val snapshotDirectory = File(cacheFixture, "browser-upload-provider")
        val orphan = File(snapshotDirectory, "verified-orphan123.bin")
        val unrelated = File(snapshotDirectory, "keep.bin")
        val directoryWithSnapshotName = File(snapshotDirectory, "verified-directory.bin")
        val symlinkTarget = File(snapshotDirectory, "symlink-target.bin")
        val symlink = File(snapshotDirectory, "verified-link.bin")

        try {
            assertTrue(snapshotDirectory.mkdirs())
            orphan.writeText("orphan")
            unrelated.writeText("keep")
            assertTrue(directoryWithSnapshotName.mkdir())
            symlinkTarget.writeText("target")
            Os.symlink(symlinkTarget.absolutePath, symlink.absolutePath)

            assertTrue(BrowserUploadProvider.cleanOrphanSnapshotsForTest(cacheFixture))
            assertFalse("orphan regular snapshot removed", orphan.exists())
            assertTrue("unrelated file preserved", unrelated.isFile)
            assertTrue("matching directory preserved", directoryWithSnapshotName.isDirectory)
            assertTrue("symlink itself preserved without following it", Files.isSymbolicLink(symlink.toPath()))
            assertTrue("symlink target preserved", symlinkTarget.readText() == "target")
        } finally {
            runCatching { cacheFixture.deleteRecursively() }
        }
    }
}
