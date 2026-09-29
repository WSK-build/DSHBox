package com.dshbox.app.sandbox.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class Shasums256Test {
    @Test
    fun parsesOfficialFormat() {
        val h1 = "8a6e1e0d8a6e1e0d8a6e1e0d8a6e1e0d8a6e1e0d8a6e1e0d8a6e1e0d8a6e1e0d"
        val h2 = "b47d8f2ab47d8f2ab47d8f2ab47d8f2ab47d8f2ab47d8f2ab47d8f2ab47d8f2a"
        val h3 = "c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1"
        val text = """
            $h1  node-v24.19.0-linux-arm64.tar.xz
            $h2 *node-v24.19.0-linux-x64.tar.xz
            $h3  node-v24.19.0-win-x64.zip
            not-a-hash-line
        """.trimIndent()
        val map = Shasums256.parse(text)
        assertEquals(3, map.size)
        assertEquals(h1, map["node-v24.19.0-linux-arm64.tar.xz"])
        assertEquals(h2, map["node-v24.19.0-linux-x64.tar.xz"])
    }

    @Test
    fun emptyTextYieldsEmptyMap() {
        assertTrue(Shasums256.parse("").isEmpty())
    }
}

class DirHashTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun stableForSameContentAndSensitiveToChange() {
        val dir = tmp.newFolder("layer")
        File(dir, "bin/hello").apply { parentFile.mkdirs(); writeText("hi") }
        File(dir, "etc/a.conf").apply { parentFile.mkdirs(); writeText("x=1") }
        val h1 = DirHash.sha256(dir)

        // 同内容重建 → 同 hash（确定性）
        val dir2 = tmp.newFolder("layer2")
        File(dir2, "etc/a.conf").apply { parentFile.mkdirs(); writeText("x=1") }
        File(dir2, "bin/hello").apply { parentFile.mkdirs(); writeText("hi") }
        assertEquals(h1, DirHash.sha256(dir2))

        // 内容变化 → hash 变化
        File(dir, "etc/a.conf").writeText("x=2")
        assertNotEquals(h1, DirHash.sha256(dir))
    }

    @Test
    fun symlinkTargetChangeIsDetected() {
        val dir = tmp.newFolder("layer3")
        val target = File(dir, "gawk"); target.writeText("x")
        val link = File(dir, "awk").toPath()
        try {
            Files.createSymbolicLink(link, target.toPath())
        } catch (e: UnsupportedOperationException) {
            return // 桌面平台无符号链接能力（Windows 需开发者模式）：跳过，真机验证
        } catch (e: java.io.IOException) {
            return // Windows 无权限建链接：跳过
        }
        val h1 = DirHash.sha256(dir)
        Files.delete(link)
        Files.createSymbolicLink(link, File(dir, "other").toPath())
        assertNotEquals(h1, DirHash.sha256(dir))
    }

    @Test
    fun sizeCountsRegularFilesOnly() {
        val dir = tmp.newFolder("layer4")
        File(dir, "a.bin").apply { parentFile.mkdirs(); writeText("12345") }
        File(dir, "sub/b.bin").apply { parentFile.mkdirs(); writeText("123") }
        assertEquals(8L, DirHash.sizeBytes(dir))
    }
}

class RuntimeProfileWriterTest {
    @Test
    fun buildsSchemaCompatibleJson() {
        val json = RuntimeProfileWriter.build(
            bundleVersion = "0.1.0", arch = "arm64",
            baseVersion = "0.1.0", baseSha256 = "a".repeat(64), baseSizeBytes = 123L,
            nodeVersion = "24.19.0", nodeSha256 = "b".repeat(64), nodeSizeBytes = 456L,
            asideVersion = "0.1.0", asideSha256 = "c".repeat(64), asideSizeBytes = 789L,
            builtAtIsoUtc = "2026-09-13T00:00:00Z",
        )
        assertTrue(json.contains("\"name\": \"dshapp-runtime-debian-arm64\""))
        assertTrue(json.contains("\"sha256\": \"${"a".repeat(64)}\""))
        assertTrue(json.contains("\"size_bytes\": 123"))
        assertTrue(json.contains("\"file\": \"base.tar.zst\""))
        assertTrue(json.contains("\"env_file\": \".dshbox/env.d/node.sh\""))
        assertTrue(json.contains("\"assembly\": [\"base\", \"node\", \"android-side\"]"))
        assertFalse(json.contains("gzip"))
    }

    @Test
    fun buildsJsonWithoutNodeLayer() {
        val json = RuntimeProfileWriter.build(
            bundleVersion = "0.1.0", arch = "arm64",
            baseVersion = "0.1.0", baseSha256 = "a".repeat(64), baseSizeBytes = 123L,
            nodeVersion = null, nodeSha256 = null, nodeSizeBytes = null,
            asideVersion = "0.1.0", asideSha256 = "c".repeat(64), asideSizeBytes = 789L,
            builtAtIsoUtc = "2026-09-13T00:00:00Z",
        )
        assertFalse(json.contains("\"name\": \"node\""))
        assertTrue(json.contains("\"assembly\": [\"base\", \"android-side\"]"))
        assertTrue(json.contains("\"name\": \"base\""))
        assertTrue(json.contains("\"name\": \"android-side\""))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsBadHash() {
        RuntimeProfileWriter.build(
            bundleVersion = "0.1.0", arch = "arm64",
            baseVersion = "0.1.0", baseSha256 = "zz", baseSizeBytes = 1L,
            nodeVersion = "24.19.0", nodeSha256 = "b".repeat(64), nodeSizeBytes = 1L,
            asideVersion = "0.1.0", asideSha256 = "c".repeat(64), asideSizeBytes = 1L,
            builtAtIsoUtc = "2026-09-13T00:00:00Z",
        )
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsUnsafeField() {
        RuntimeProfileWriter.build(
            bundleVersion = "0.1.0\nINJECTED", arch = "arm64",
            baseVersion = "0.1.0", baseSha256 = "a".repeat(64), baseSizeBytes = 1L,
            nodeVersion = "24.19.0", nodeSha256 = "b".repeat(64), nodeSizeBytes = 1L,
            asideVersion = "0.1.0", asideSha256 = "c".repeat(64), asideSizeBytes = 1L,
            builtAtIsoUtc = "2026-09-13T00:00:00Z",
        )
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsHalfNullNode() {
        RuntimeProfileWriter.build(
            bundleVersion = "0.1.0", arch = "arm64",
            baseVersion = "0.1.0", baseSha256 = "a".repeat(64), baseSizeBytes = 1L,
            nodeVersion = "24.19.0", nodeSha256 = null, nodeSizeBytes = 1L,
            asideVersion = "0.1.0", asideSha256 = "c".repeat(64), asideSizeBytes = 1L,
            builtAtIsoUtc = "2026-09-13T00:00:00Z",
        )
    }
}
