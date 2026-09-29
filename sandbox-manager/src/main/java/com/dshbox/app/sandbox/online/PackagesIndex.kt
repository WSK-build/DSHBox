package com.dshbox.app.sandbox.online

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Debian Packages 索引的流式解析器（在线 base 组装 S2 阶段）。
 *
 * 输入是 Packages 文件解压后的 deb822 段落文本（GZIP 解压由调用方包装，本类只认明文流）：
 * 段落以空行分隔，字段形如 `Key: value`，续行以空白开头。全量索引数万段，为控制内存，
 * 只保留 [wanted] 包名集合命中的段落——即 manifest（[BaseManifest]）清单内的那一百余个。
 *
 * 完整性锚点在编排层：下载到的 Packages.gz 先与 Release 声明的 SHA256 比对，本类不做哈希校验。
 */
class PackagesIndex private constructor(
    /** name -> entry；仅含 [wanted] 中命中索引的包。 */
    val entries: Map<String, Entry>,
    /** 扫过的段落数（诊断用，含未命中的段落）。 */
    val scannedParagraphs: Int,
) {
    data class Entry(
        val name: String,
        /** pool 相对路径，如 `pool/main/b/bash/bash_5.2.37-2+b10_arm64.deb`。 */
        val filename: String,
        val sha256: String,
        val sizeBytes: Long,
        val version: String,
    )

    fun entry(name: String): Entry? = entries[name]

    /** [wanted] 中未在索引出现的包名（编排层据此"缺名即报错"，不静默降级）。 */
    fun missingFrom(wanted: Collection<String>): List<String> =
        wanted.filter { it !in entries }.sorted()

    companion object {
        fun parse(input: InputStream, wanted: Set<String>): PackagesIndex {
            val found = HashMap<String, Entry>()
            var scanned = 0
            val fields = HashMap<String, String>()
            var currentKey: String? = null
            val value = StringBuilder()

            fun commitKey() {
                val key = currentKey ?: return
                fields[key] = value.toString()
                currentKey = null
                value.setLength(0)
            }

            fun flushParagraph() {
                commitKey()
                if (fields.isEmpty()) return
                scanned++
                val name = fields["Package"]
                if (name != null && wanted.contains(name)) {
                    val filename = fields["Filename"]
                    val sha = fields["SHA256"]
                    if (filename.isNullOrBlank() || sha.isNullOrBlank()) {
                        throw IOException("Packages index entry $name is missing Filename/SHA256")
                    }
                    found[name] = Entry(
                        name = name,
                        filename = filename.trim(),
                        sha256 = sha.trim().lowercase(),
                        sizeBytes = fields["Size"]?.trim()?.toLongOrNull() ?: 0L,
                        version = fields["Version"]?.trim().orEmpty(),
                    )
                }
                fields.clear()
            }

            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.isBlank() -> flushParagraph()
                        line[0] == ' ' || line[0] == '\t' ->
                            // 续行（如 Description 多行）：挂到当前键，避免吞掉后续字段边界。
                            if (currentKey != null) value.append('\n').append(line.trim())
                        else -> {
                            val idx = line.indexOf(':')
                            if (idx <= 0) throw IOException("Malformed Packages index line: $line")
                            commitKey()
                            currentKey = line.substring(0, idx)
                            value.append(line.substring(idx + 1).trim())
                        }
                    }
                }
                flushParagraph()
            }
            return PackagesIndex(found, scanned)
        }
    }
}
