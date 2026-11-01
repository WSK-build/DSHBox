package com.dshbox.pluginmanager.market.data

import com.dshbox.pluginmanager.contract.HostContract
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/**
 * `dsh.profile.bundles`（有序 bundle 层栈）的**外科手术式**编辑。
 *
 * ## 不做"解析 → 改 → 序列化"整份重写的原因
 *
 * bundles 的**顺序就是层优先级**。通用的 JSON 库在反序列化时不保证键序，整份
 * 重写等于让一次库往返去赌栈顺序——顺序一旦被打乱，所有 bundle 层的覆盖关系
 * 都会变。所以编辑只做一件事：在原文里定位 `"bundles"` 数组字面量，原位替换
 * 这一个数组，文件的其余字节一律不动。
 *
 * ## 宁可不写，不可写坏
 *
 * 数组元素必须是字符串字面量（宿主的 schema 就是这样）；任何读不懂的形态
 * （嵌套对象、注释、转义差异拿不准）一律返回 null，调用方按"未写入"处理。
 * 写回前还会用 JSON 解析器复核最终文本——复核不过同样视为未写。
 *
 * ## 与宿主对账的分工
 *
 * 宿主在 pnpm 成功后会做自己的 bundles 对账（保留不在依赖里的条目、追加新
 * 装的 bundle 依赖）。这里只负责市场启停要求的**显式**出栈/回栈，两者不冲突：
 * 对账尊重既有顺序，这里编辑也保持既有顺序。
 */
object ProfileBundlesEditor {

    /** 读当前栈（缺失/损坏返回 null——调用方必须与"空栈"区分）。 */
    fun readBundles(manifestText: String): List<String>? {
        val span = locateArray(manifestText) ?: return null
        return parseElements(manifestText, span)
    }

    /**
     * 把 [name] 移出栈；不在栈里时原样返回输入（幂等）。
     *
     * @return 编辑后的全文；定位失败、元素不可解析或复核失败时返回 null（未写入语义）。
     */
    fun removeBundle(manifestText: String, name: String): String? =
        edit(manifestText) { current -> if (name in current) current.filterNot { it == name } else current }

    /** 把 [name] 追加到栈尾；已在栈里时原样返回输入（幂等）。 */
    fun addBundle(manifestText: String, name: String): String? =
        edit(manifestText) { current -> if (name in current) current else current + name }

    private inline fun edit(manifestText: String, transform: (List<String>) -> List<String>): String? {
        val span = locateArray(manifestText) ?: return null
        val current = parseElements(manifestText, span) ?: return null
        val next = transform(current)
        if (next == current) return manifestText
        val updated = renderArray(manifestText, span, next) ?: return null
        // 复核：改完的全文必须仍是合法 JSON，且 bundles 与期望一致、顺序一致。
        return runCatching {
            val root = JSONObject(updated)
            val readBack = HostContract.readProfileBundles(root) ?: return@runCatching null
            if (readBack != next) return@runCatching null
            updated
        }.getOrNull()
    }

    /** 数组字面量在全文中的区间（含方括号）；找不到或形态读不懂返回 null。 */
    private fun locateArray(text: String): IntRange? {
        // 从每个 `"bundles"` 出现处起找：只有「键 → 仅空白与一个冒号 → `[`」
        // 的形态才算数。清单里可能出现值恰为 "bundles" 的字符串（描述文本、
        // 依赖键名），不校验冒号会把别处的数组误当栈——写路径虽有内容复核
        // 兜底，读路径（readBundles）没有，定位必须自身可靠。
        val key = "\"${HostContract.FIELD_PROFILE_BUNDLES.last()}\""
        var from = 0
        while (true) {
            val keyIndex = text.indexOf(key, from)
            if (keyIndex < 0) return null
            var cursor = keyIndex + key.length
            var sawColon = false
            while (cursor < text.length && (text[cursor].isWhitespace() || text[cursor] == ':')) {
                if (text[cursor] == ':') {
                    if (sawColon) return null
                    sawColon = true
                }
                cursor++
            }
            if (sawColon && cursor < text.length && text[cursor] == '[') {
                val open = cursor
                val span = spanOfArray(text, open)
                if (span != null) return span
            }
            from = keyIndex + 1
        }
    }

    /** 从 `[` 起找配对 `]`（字符串感知）；找不到返回 null。 */
    private fun spanOfArray(text: String, open: Int): IntRange? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in open until text.length) {
            val ch = text[index]
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                inString -> Unit
                ch == '[' -> depth++
                ch == ']' -> {
                    depth--
                    if (depth == 0) return open..index
                }
            }
        }
        return null
    }

    /** 解析数组字面量里的字符串元素；任何非字符串元素或转义读不懂都返回 null。 */
    private fun parseElements(text: String, span: IntRange): List<String>? {
        val body = text.substring(span.first + 1, span.last).trim()
        if (body.isEmpty()) return emptyList()
        val elements = mutableListOf<String>()
        var index = 0
        while (index < body.length) {
            while (index < body.length && body[index].isWhitespace()) index++
            if (index >= body.length) break
            if (body[index] == ',') {
                index++
                continue
            }
            if (body[index] != '"') return null
            val sb = StringBuilder()
            index++
            var closed = false
            var escaped = false
            while (index < body.length) {
                val ch = body[index]
                if (escaped) {
                    // 只接受常见转义，其余视为读不懂（宁可拒绝编辑）。
                    when (ch) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (index + 4 >= body.length) return null
                            val hex = body.substring(index + 1, index + 5)
                            val code = hex.toIntOrNull(16) ?: return null
                            sb.append(code.toChar())
                            index += 4
                        }
                        else -> return null
                    }
                    escaped = false
                } else when (ch) {
                    '\\' -> escaped = true
                    '"' -> {
                        closed = true
                        index++
                        break
                    }
                    else -> sb.append(ch)
                }
                index++
            }
            if (!closed) return null
            elements.add(sb.toString())
        }
        return elements
    }

    /**
     * 用新元素重渲染数组并回填全文。
     *
     * 多行数组保持逐行格式（缩进取原数组第一个元素或键缩进 +2）；单行数组
     * （含空数组）渲染成单行。包名转义复用 [MarketStateCodec.encodeString]，
     * 保证与既有 JSON 字符串编码口径一致。
     *
     * **绝不产生尾逗号**：宿主（Node 的 JSON.parse）严格拒绝尾逗号，而
     * Android 的 org.json 宽松解析会放行——只靠进程内复核兜不住这一差异，
     * 渲染本身必须保证严格 JSON。
     */
    private fun renderArray(text: String, span: IntRange, elements: List<String>): String? {
        val original = text.substring(span.first, span.last + 1)
        // 跟随文件自身的行尾风格：CRLF 文件被编辑的区域不能混入 LF。
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val rendered = if (original.lines().size <= 1) {
            elements.joinToString(prefix = "[", postfix = "]", separator = ",") {
                MarketStateCodec.encodeString(it)
            }
        } else {
            val elementIndent = original.lines().getOrNull(1)
                ?.takeWhile { it == ' ' }
                ?.ifBlank { null }
                ?: indentOf(text, span.first).let { " ".repeat(it + 2) }
            // 收括号回到键的缩进位（元素缩进 -2），与宿主的 JSON.stringify 格式一致。
            val closingIndent = " ".repeat((elementIndent.length - 2).coerceAtLeast(0))
            elements.joinToString(
                prefix = "[$newline",
                postfix = "$newline$closingIndent]",
                separator = ",$newline",
            ) { name -> "$elementIndent${MarketStateCodec.encodeString(name)}" }
                .let { rendered ->
                    if (elements.isEmpty()) "[]" else rendered
                }
        }
        return text.replaceRange(span.first, span.last + 1, rendered)
    }

    private fun indentOf(text: String, index: Int): Int {
        var lineStart = text.lastIndexOf('\n', index).coerceAtLeast(-1)
        lineStart += 1
        var count = 0
        while (lineStart + count < index && text[lineStart + count] == ' ') count++
        return count
    }
}

/**
 * profile 清单的读-改-写入口（原子落盘 + 进程内串行）。
 *
 * 编辑是**低频**操作（市场启停），但两个写者（这里与宿主对账/随包装配脚本）
 * 共用同一份文件，所以落盘用「同目录临时文件 + 原子 move」：任意时刻读到的是
 * 完整旧文或完整新文，不存在半截清单。
 */
class ProfileBundlesStore(private val manifest: File) {

    private val lock = Any()

    /** 当前栈；文件缺失或解析失败返回 null。 */
    fun readBundles(): List<String>? = synchronized(lock) {
        val text = readText() ?: return@synchronized null
        ProfileBundlesEditor.readBundles(text)
    }

    /**
     * 从栈中移除 [name]；[isPresent] 为 false 时不动文件（幂等）。
     *
     * @return 是否确认落盘。false 表示"没有写入"（文件缺失/编辑被拒/IO 失败），
     *   调用方必须按失败处理，不能假设已生效。
     */
    fun remove(name: String, isPresent: Boolean): Boolean =
        edit({ ProfileBundlesEditor.removeBundle(it, name) }, guard = isPresent)

    /** 把 [name] 追加到栈尾；[isPresent] 为 true 时不动文件（幂等）。 */
    fun add(name: String, isPresent: Boolean): Boolean =
        edit({ ProfileBundlesEditor.addBundle(it, name) }, guard = !isPresent)

    private fun edit(transform: (String) -> String?, guard: Boolean): Boolean = synchronized(lock) {
        if (!guard) return@synchronized true
        val text = readText() ?: return@synchronized false
        val updated = transform(text) ?: return@synchronized false
        if (updated == text) return@synchronized true
        writeAtomic(updated)
    }

    private fun readText(): String? =
        runCatching { manifest.takeIf { it.isFile }?.readText(Charsets.UTF_8) }.getOrNull()

    private fun writeAtomic(text: String): Boolean {
        var tmp: File? = null
        return runCatching {
            manifest.parentFile?.mkdirs()
            cleanStaleTempFiles()
            val staged = File(manifest.parentFile, manifest.name + ".tmp-" + System.nanoTime())
            tmp = staged
            staged.writeText(text, Charsets.UTF_8)
            try {
                Files.move(
                    staged.toPath(),
                    manifest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            tmp = null
            true
        }.getOrElse {
            false
        }.also { done ->
            // move 已成功时临时名不复存在；失败或异常时清掉残留，避免半截清单。
            if (!done) tmp?.let { runCatching { it.delete() } }
        }
    }

    private companion object {
        /** 临时文件多久算"残留"（进程被杀留下的）。与 MarketStateStore 同口径：10 分钟。 */
        const val STALE_TMP_MS = 10 * 60 * 1000L
    }

    /**
     * 清掉本清单留下的**陈旧**临时文件（进程被杀时 `.tmp-` 会永久残留）。
     *
     * 只清 mtime 超过阈值的：正在并发写的那一份的临时文件是新的，误删会让
     * 那次写入失败。
     */
    private fun cleanStaleTempFiles() {
        val parent = manifest.parentFile ?: return
        val cutoff = System.currentTimeMillis() - STALE_TMP_MS
        runCatching {
            parent.listFiles { candidate ->
                candidate.isFile && candidate.name.startsWith(manifest.name + ".tmp-")
            }?.forEach { stale ->
                if (stale.lastModified() < cutoff) runCatching { stale.delete() }
            }
        }
    }
}
