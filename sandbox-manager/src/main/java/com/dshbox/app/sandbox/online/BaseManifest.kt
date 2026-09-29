package com.dshbox.app.sandbox.online

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * 在线 base 组装用的内置 manifest（`tools/gen_base_manifest.py` 生成的纯文本小格式）。
 *
 * 冻结三类内容：①「Essential/required ∪ 种子包闭包」的包名清单；②S8 功能自检用的
 * 关键二进制路径；③S7 裁剪的 locale 白名单。另带 suite/arch 标记做运行时一致性校验。
 *
 * 格式（`#` 注释、`key=value` 标量、`[节名]` 下列表项每行一个）：
 * ```
 * manifest_version=1
 * suite=trixie
 * arch=arm64
 * generated_at=2026-09-13
 * [packages]
 * base-files
 * ...
 * [smoke-binaries]
 * /bin/bash
 * ...
 * [locale-whitelist]
 * C
 * ...
 * ```
 *
 * 刻意不用 JSON：org.json 在 JVM 单测不可用（android.jar 打桩），避免为此新增测试依赖
 * 与第三方登记义务。
 */
data class BaseManifest(
    val manifestVersion: Int,
    val suite: String,
    val arch: String,
    val generatedAt: String,
    val packages: List<String>,
    val smokeBinaries: List<String>,
    val localeWhitelist: Set<String>,
) {
    init {
        require(manifestVersion == SUPPORTED_MANIFEST_VERSION) {
            "unsupported manifest version: $manifestVersion"
        }
        require(suite.isNotBlank()) { "manifest suite is blank" }
        require(arch.isNotBlank()) { "manifest arch is blank" }
        require(packages.isNotEmpty()) { "manifest has no packages" }
    }

    companion object {
        const val SUPPORTED_MANIFEST_VERSION = 1

        fun parse(input: InputStream): BaseManifest {
            var version = -1
            var suite = ""
            var arch = ""
            var generatedAt = ""
            val packages = LinkedHashSet<String>()
            val smoke = LinkedHashSet<String>()
            val locale = LinkedHashSet<String>()
            var section: String? = null

            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                reader.forEachLine { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                    if (line.startsWith("[") && line.endsWith("]")) {
                        section = line.substring(1, line.length - 1).trim()
                        return@forEachLine
                    }
                    if (section == null) {
                        val eq = line.indexOf('=')
                        if (eq <= 0) throw IOException("malformed manifest scalar line: $line")
                        val v = line.substring(eq + 1).trim()
                        when (line.substring(0, eq).trim()) {
                            "manifest_version" -> version = v.toIntOrNull() ?: -1
                            "suite" -> suite = v
                            "arch" -> arch = v
                            "generated_at" -> generatedAt = v
                        }
                    } else {
                        when (section) {
                            "packages" -> packages.add(line)
                            "smoke-binaries" -> smoke.add(line)
                            "locale-whitelist" -> locale.add(line)
                            else -> throw IOException("unknown manifest section: $section")
                        }
                    }
                }
            }
            return BaseManifest(
                manifestVersion = version,
                suite = suite,
                arch = arch,
                generatedAt = generatedAt,
                packages = packages.toList(),
                smokeBinaries = smoke.toList(),
                localeWhitelist = locale.toSet(),
            )
        }
    }
}
