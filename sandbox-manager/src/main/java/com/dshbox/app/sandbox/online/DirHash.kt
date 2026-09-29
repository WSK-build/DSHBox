package com.dshbox.app.sandbox.online

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * 「层目录」的确定性哈希与体积（在线 base 组装定位用）。
 *
 * 离线流程的层 hash 是压缩归档的 hash；在线组装没有归档，只能对目录树现算：
 * 按相对路径排序，逐条喂入「相对路径 + 类型标记 +（内容 / 链接目标）」。同一目录
 * → 同一 hash；任何内容、名字、类型或链接目标的变化 → 不同 hash——与 profile
 * 哨兵（`verifyLayersBroken`）的防篡改语义一致。
 */
object DirHash {

    fun sha256(dir: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val entries = buildList {
            dir.walkTopDown().forEach { f ->
                if (f != dir) add(f.relativeTo(dir).invariantSeparatorsPath to f)
            }
        }.sortedBy { it.first }
        for ((rel, f) in entries) {
            digest.update(rel.toByteArray())
            when {
                f.isDirectory -> digest.update("/".toByteArray())
                Files.isSymbolicLink(f.toPath()) -> {
                    digest.update("->".toByteArray())
                    digest.update(
                        runCatching { Files.readSymbolicLink(f.toPath()).toString() }
                            .getOrDefault("").toByteArray(),
                    )
                }
                f.isFile -> {
                    digest.update("\u0000".toByteArray())
                    f.inputStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                        }
                    }
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 目录内普通文件的字节总量（profile 的 size_bytes 口径）。 */
    fun sizeBytes(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
