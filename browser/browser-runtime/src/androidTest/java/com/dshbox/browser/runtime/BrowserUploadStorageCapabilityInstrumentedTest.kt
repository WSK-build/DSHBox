package com.dshbox.browser.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BrowserUploadStorageCapabilityInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun appPrivateFixtureSupportsProductionUploadInspection() {
        val filesRoot = File(context.filesDir.canonicalFile, "browser-upload-capability-${UUID.randomUUID()}")
        val userData = File(filesRoot, "user-data")
        val qaDirectory = File(userData, "qa")
        val fixture = File(qaDirectory, "qa-report.txt")
        val expected = "browser upload test" // exactly 19 bytes

        try {
            val fixtureCreated = runCatching {
                check(filesRoot.mkdirs())
                check(qaDirectory.mkdirs())
                fixture.writeBytes(expected.toByteArray(Charsets.UTF_8))
                fixture.length() == 19L
            }.getOrDefault(false)
            requireStage(fixtureCreated, "fixture_created")

            val root = filesRoot.toPath()
            val absoluteRoot = filesRoot.absoluteFile.toPath().normalize().toFile()
            val canonicalRoot = filesRoot.canonicalFile
            requireStage(absoluteRoot == canonicalRoot && canonicalRoot.isDirectory, "canonical_root")

            val rootStream = runCatching { java.nio.file.Files.newDirectoryStream(root) }.getOrNull()
            if (rootStream !is SecureDirectoryStream<*>) runCatching { rootStream?.close() }
            requireStage(rootStream is SecureDirectoryStream<*>, "sds_root")
            @Suppress("UNCHECKED_CAST")
            val secureRoot = rootStream as SecureDirectoryStream<java.nio.file.Path>
            try {
                val userDataStream = runCatching {
                    secureRoot.newDirectoryStream(Paths.get("user-data"), LinkOption.NOFOLLOW_LINKS)
                }.getOrNull()
                if (userDataStream !is SecureDirectoryStream<*>) runCatching { userDataStream?.close() }
                requireStage(userDataStream is SecureDirectoryStream<*>, "sds_user_data")
                @Suppress("UNCHECKED_CAST")
                val secureUserData = userDataStream as SecureDirectoryStream<java.nio.file.Path>
                try {
                    val qaStream = runCatching {
                        secureUserData.newDirectoryStream(Paths.get("qa"), LinkOption.NOFOLLOW_LINKS)
                    }.getOrNull()
                    if (qaStream !is SecureDirectoryStream<*>) runCatching { qaStream?.close() }
                    requireStage(qaStream is SecureDirectoryStream<*>, "sds_child")
                    @Suppress("UNCHECKED_CAST")
                    val secureQa = qaStream as SecureDirectoryStream<java.nio.file.Path>
                    try {
                        val attrs = runCatching {
                            secureQa.getFileAttributeView(
                                Paths.get("qa-report.txt"), BasicFileAttributeView::class.java,
                                LinkOption.NOFOLLOW_LINKS,
                            ).readAttributes()
                        }.getOrNull()
                        requireStage(attrs != null && attrs.isRegularFile && !attrs.isSymbolicLink && attrs.size() == 19L,
                            "source_attributes")
                        requireStage(attrs!!.fileKey() != null, "source_file_key")

                        val digest = runCatching {
                            secureQa.newByteChannel(
                                Paths.get("qa-report.txt"),
                                setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                            ).use(::sha256)
                        }.getOrNull()
                        requireStage(digest != null && digest.first == 19L && digest.second == expectedHash(expected),
                            "nofollow_read_hash")
                    } finally {
                        runCatching { secureQa.close() }
                    }
                } finally {
                    runCatching { secureUserData.close() }
                }
            } finally {
                runCatching { secureRoot.close() }
            }

            val inspected = runCatching { BrowserUploadPolicy.inspect(userData, "qa/qa-report.txt") }.getOrNull()
            requireStage(inspected != null, "production_inspect")
            assertEquals("production_inspect_size", 19L, inspected!!.size)
            assertEquals("production_inspect_hash", expectedHash(expected), inspected.sha256)
        } finally {
            runCatching { filesRoot.deleteRecursively() }
        }
    }

    @Test
    fun unicodeProjectDirectoriesPassProductionUploadInspection() {
        val filesRoot = File(context.filesDir.canonicalFile, "browser-upload-unicode-${UUID.randomUUID()}")
        val userData = File(filesRoot, "user-data")
        val qaDirectory = File(userData, "多agent测试/browser-qa")
        val fixture = File(qaDirectory, "qa-report.txt")
        val expected = "browser upload test" // exactly 19 bytes
        val relativePath = "多agent测试/browser-qa/qa-report.txt"

        try {
            val created = runCatching {
                check(qaDirectory.mkdirs())
                fixture.writeBytes(expected.toByteArray(Charsets.UTF_8))
                fixture.length() == 19L
            }.getOrDefault(false)
            requireStage(created, "unicode_fixture_created")
            requireStage(BrowserUploadPolicy.validateRelativePath(relativePath) != null, "unicode_relative_validation")

            val inspected = runCatching { BrowserUploadPolicy.inspect(userData, relativePath) }.getOrNull()
            requireStage(inspected != null, "unicode_production_inspect")
            assertEquals("unicode_inspect_size", 19L, inspected!!.size)
            assertEquals("unicode_inspect_hash", expectedHash(expected), inspected.sha256)
        } finally {
            runCatching { filesRoot.deleteRecursively() }
        }
    }

    private fun sha256(channel: SeekableByteChannel): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(64)
        var total = 0L
        while (true) {
            buffer.clear()
            val count = channel.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            buffer.flip()
            digest.update(buffer)
        }
        return total to digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun expectedHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun requireStage(passed: Boolean, stage: String) {
        assertTrue("stage=$stage", passed)
    }
}
