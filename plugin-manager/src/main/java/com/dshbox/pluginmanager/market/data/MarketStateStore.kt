package com.dshbox.pluginmanager.market.data

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * `.dsh-market/state.json` 中由本模块掌握的部分，加上**必须原样带回**的其余字段。
 *
 * @property disabled 用户关掉的插件；每次启动都要重放，否则开关会在重启后失效。
 * @property notes 用户自己给插件写的一行备注（见 [MarketStateCodec.MAX_NOTE]）。
 * @property preserved 我们不解释、但必须逐字写回的顶层字段（键 → 原始 JSON 片段）。
 *   它是桌面端与未来版本写进去的东西（分组、收藏、下载区域、更新通道……）。
 *   丢掉它们的后果不是"少了个设置"，而是**用户的选择被静默抹掉**——桌面端
 *   就因为这个出过一次问题。
 */
data class MarketState(
    val disabled: List<String>,
    val notes: Map<String, String>,
    val preserved: Map<String, String>,
) {
    companion object {
        val EMPTY = MarketState(emptyList(), emptyMap(), emptyMap())
    }
}

/**
 * state.json 的读-改-写纯逻辑。
 *
 * 与文件 IO 分开，是因为这里承载的是**最容易出错也最值得验证**的一条规则：
 * 只改自己拥有的字段，其余字段原样带回。把它做成纯函数，单测就能直接构造
 * 一份"盘上已有桌面端写的字段"的状态，验证它们不会在写回时消失。
 */
object MarketStateCodec {

    /** 备注是一行标签而不是文档：限长，避免一次粘贴让状态文件无限增长。 */
    const val MAX_NOTE = 200

    /**
     * 本模块拥有的顶层键。
     *
     * `disabledSkins` 是旧版只关主题时的键，读的时候仍要认它（老 profile 里
     * 可能只有它），但写回一律用 `disabled`——这是上游迁移后的统一写法。
     * 其余一切键都进 [MarketState.preserved]。
     */
    val OWNED_KEYS: Set<String> = setOf("disabled", "disabledSkins", "notes")

    /** 从顶层键集合里分出需要原样保留的那些（保持原有顺序）。 */
    fun preservedKeys(allKeys: List<String>): List<String> = allKeys.filterNot { it in OWNED_KEYS }

    /** 备注归一：空白等于"没有备注"，其余截到上限。 */
    fun sanitizeNotes(notes: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((name, text) in notes) {
            if (text.trim().isEmpty()) continue
            out[name] = text.take(MAX_NOTE)
        }
        return out
    }

    /**
     * 读盘态 + 本次改动 → 写盘态。
     *
     * [MarketState.preserved] 一律取自 [onDisk]：调用方只知道自己改的那一项，
     * 拿不到、也不该猜其余字段的新值。
     */
    fun merge(
        onDisk: MarketState,
        disabled: Collection<String>,
        notes: Map<String, String>,
    ): MarketState = MarketState(
        disabled = disabled.filter { it.isNotBlank() }.distinct(),
        notes = sanitizeNotes(notes),
        preserved = onDisk.preserved,
    )

    /**
     * 渲染成 state.json 正文。
     *
     * 手工拼装而不是复用 JSON 库，是因为保留字段必须**逐字**写回：再走一遍
     * 解析/序列化，等于让库去猜我们本来就不理解的字段，多一层改写就多一次
     * 丢信息的机会。
     */
    fun encode(state: MarketState): String {
        val lines = mutableListOf<String>()
        lines.add("  \"disabled\": " + encodeStringArray(state.disabled))
        if (state.notes.isNotEmpty()) {
            lines.add("  \"notes\": " + encodeStringMap(state.notes))
        }
        for ((key, raw) in state.preserved) {
            lines.add("  " + encodeString(key) + ": " + raw)
        }
        return "{\n" + lines.joinToString(",\n") + "\n}\n"
    }

    private fun encodeStringArray(values: Collection<String>): String =
        values.joinToString(prefix = "[", postfix = "]", separator = ",") { encodeString(it) }

    private fun encodeStringMap(values: Map<String, String>): String =
        values.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
            encodeString(key) + ":" + encodeString(value)
        }

    /** JSON 字符串转义；控制字符一律走 `\uXXXX`，避免写出非法 JSON。 */
    fun encodeString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') {
                    sb.append("\\u").append(String.format("%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

/**
 * 读盘结果。
 *
 * 三种情况必须分开，因为**只有一种可以写**：文件不存在说明这是首次写入，
 * 可以写；文件存在但读不出来说明盘上有我们看不懂的内容（桌面端写的字段、
 * 被截断的 JSON），此时按"空状态"合并再写回，等于把用户的选择静默抹掉。
 */
sealed interface MarketStateRead {
    data class Ok(val state: MarketState) : MarketStateRead

    /** 文件不存在（或路径上什么都没有）：可以写。 */
    data object Missing : MarketStateRead

    /** 文件存在但读/解析失败：**拒绝写**，绝不覆盖原文件。 */
    data class Broken(val reason: String) : MarketStateRead
}

/** 写盘结果。 */
sealed interface MarketStateWrite {
    data object Ok : MarketStateWrite

    /** 盘上的文件存在但读不出来，为避免抹掉别人的字段而拒绝写入。 */
    data class Refused(val reason: String) : MarketStateWrite

    /** IO 失败（目录建不出来、临时文件写不进、move 失败）；原文件保持不动。 */
    data class IoFailed(val reason: String) : MarketStateWrite
}

/**
 * `.dsh-market/state.json` 的读写。
 *
 * 目录与文件都只属于本 app 的私有数据区，但仍然按 0700 收紧目录权限：
 * 状态里含用户自己写的备注，属于"不该被同设备其它应用读到"的内容。
 *
 * ## 写入用「同目录临时文件 + 原子 move」
 *
 * DSH 或桌面端可能在任意时刻读这个文件，半截文件被读到会解析失败，而解析
 * 失败在对方那里通常表现为"设置全部丢失"。所以：先写一个**带随机后缀**的
 * 临时文件（同名 `.tmp` 会在并发写时互相踩），再用 `ATOMIC_MOVE` 覆盖目标。
 *
 * **不再先删原文件**：`delete` 之后 `rename` 之前的那一瞬间，文件是不存在的，
 * 此时对方读到"没有文件"就等于读到"设置全空"；而且 rename 一旦失败，原文件
 * 已经没了。原子 move 没有这个窗口，失败时原文件也仍在原处。
 *
 * ## 文案语言
 *
 * 这里的 reason 串是"中文 / English"双语，`localizeBilingual` 按界面语言择半；
 * **同一时刻只支持 zh / en**，ar / es / fr / ru 回退英文。
 */
class MarketStateStore(private val file: File) {

    private companion object {
        /** 临时文件多久算"残留"（进程被杀留下的）。10 分钟。 */
        const val STALE_TMP_MS = 10 * 60 * 1000L
    }

    /**
     * 读盘（带"能不能写"的判断）。
     *
     * 文件缺失或损坏都退化成空状态——状态读不出来不该让市场不可用。
     */
    fun readState(): MarketStateRead {
        if (!file.isFile) return MarketStateRead.Missing
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse { t ->
            return MarketStateRead.Broken(
                "状态文件存在但读不出来（${t.message ?: t::class.java.simpleName}），已拒绝写入以免覆盖它 / " +
                    "the state file exists but could not be read, so writing was refused to avoid overwriting it",
            )
        }
        if (text.isBlank()) {
            return MarketStateRead.Broken(
                "状态文件存在但是空的（可能是一次被截断的写入），已拒绝写入以免覆盖它 / " +
                    "the state file exists but is empty (possibly a truncated write), so writing was refused",
            )
        }
        return runCatching { MarketStateRead.Ok(decode(text)) }.getOrElse { t ->
            MarketStateRead.Broken(
                "状态文件存在但不是合法 JSON（${t.message ?: t::class.java.simpleName}），已拒绝写入以免覆盖它 / " +
                    "the state file exists but is not valid JSON, so writing was refused to avoid overwriting it",
            )
        }
    }

    /** 读盘（只关心状态本身）。任何读失败都退化成空状态。 */
    fun read(): MarketState = when (val result = readState()) {
        is MarketStateRead.Ok -> result.state
        is MarketStateRead.Missing -> MarketState.EMPTY
        is MarketStateRead.Broken -> MarketState.EMPTY
    }

    fun disabled(): Set<String> = read().disabled.toSet()

    fun notes(): Map<String, String> = read().notes

    /**
     * 只更新 [disabled] 与 [notes]，其余字段从盘上原样带回。
     *
     * 返回 false 表示**没有落盘**（盘上文件读不出来而拒绝写入、目录建不出来、
     * 临时文件写不进、move 失败），调用方必须把它当失败处理：状态没写成功而
     * 界面显示成功，下一次启动开关就会"自己变回去"。
     */
    fun write(disabled: Collection<String>, notes: Map<String, String>): Boolean =
        writeOutcome(disabled, notes) is MarketStateWrite.Ok

    /**
     * 同 [write]，但把失败原因带出来。
     *
     * 调用方需要区分"盘上的文件坏了，我们不敢写"与"磁盘写不进去"：前者要
     * 让用户知道**原文件没被动过**，后者是一次可重试的 IO 故障。
     */
    fun writeOutcome(disabled: Collection<String>, notes: Map<String, String>): MarketStateWrite {
        val current = readState()
        if (current is MarketStateRead.Broken) {
            // 读不出来就不写：按空状态合并会把桌面端的字段整块抹掉。
            return MarketStateWrite.Refused(current.reason)
        }
        val base = if (current is MarketStateRead.Ok) current.state else MarketState.EMPTY
        val next = MarketStateCodec.merge(base, disabled, notes)
        return writeRaw(MarketStateCodec.encode(next))
    }

    /**
     * 备份损坏的状态文件并重建一份只含我们字段的新文件。
     *
     * 盘上的 `state.json` 读不出来时，[writeOutcome] 一律**拒绝写入**（避免把
     * 桌面端写进去的字段整块抹掉），于是开关与备注永久无法保存，而应用内没有
     * 恢复入口。这个方法给用户第二条出路：把原文件改名成
     * `state.json.broken-<时间戳>` 留档，再写一份只含 `disabled` / `notes`
     * 的新文件。
     *
     * 文件没坏（缺失或可读）时退化为普通写入。
     */
    fun rebuild(disabled: Collection<String>, notes: Map<String, String>): MarketStateWrite {
        val current = readState()
        if (current !is MarketStateRead.Broken) {
            return writeOutcome(disabled, notes)
        }
        val dir = file.parentFile
            ?: return MarketStateWrite.IoFailed(
                "状态文件没有父目录，无法写入 / the state file has no parent directory",
            )
        if (!dir.isDirectory && !dir.mkdirs()) {
            return MarketStateWrite.IoFailed(
                "建不出状态目录 / the state directory could not be created",
            )
        }
        restrictToOwner(dir)
        val backup = File(dir, file.name + ".broken-" + brokenStamp())
        val moved = runCatching {
            java.nio.file.Files.move(file.toPath(), backup.toPath())
        }.isSuccess || file.renameTo(backup)
        if (!moved) {
            return MarketStateWrite.IoFailed(
                "备份损坏的状态文件失败，原文件未被改动 / backing up the broken state file failed; the original was left untouched",
            )
        }
        // 新文件只含我们拥有的字段：原文件里我们不理解的内容无法读出来，
        // 但已完整留档在备份里。
        val next = MarketStateCodec.merge(MarketState.EMPTY, disabled, notes)
        val outcome = writeRaw(MarketStateCodec.encode(next))
        if (outcome !is MarketStateWrite.Ok) {
            // 重建失败：把备份还原回原路径，别让用户既没有旧文件也没有新文件。
            runCatching { java.nio.file.Files.move(backup.toPath(), file.toPath()) }
                .onFailure { runCatching { backup.renameTo(file) } }
        }
        return outcome
    }

    private fun brokenStamp(): String =
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())

    private fun writeRaw(text: String): MarketStateWrite {
        val dir = file.parentFile
            ?: return MarketStateWrite.IoFailed(
                "状态文件没有父目录，无法写入 / the state file has no parent directory",
            )
        if (!dir.isDirectory && !dir.mkdirs()) {
            return MarketStateWrite.IoFailed(
                "建不出状态目录 / the state directory could not be created",
            )
        }
        restrictToOwner(dir)
        // 进程在"写完临时文件、还没 move"之间被杀会永久留下 .tmp，先清掉旧的。
        cleanStaleTempFiles(dir)
        // 随机后缀：固定的 `.tmp` 在两处并发写时会互相踩，写出半截文件。
        val tmp = File(dir, file.name + "." + randomSuffix() + ".tmp")
        return try {
            tmp.writeText(text, Charsets.UTF_8)
            moveOver(tmp, file)
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            MarketStateWrite.IoFailed(
                "写状态文件失败：${t.message ?: t::class.java.simpleName} / writing the state file failed",
            )
        }
    }

    /**
     * 清掉同目录下**残留的**临时文件。
     *
     * 只清 mtime 超过 [STALE_TMP_MS] 的：正在并发写的那一份的临时文件是新的，
     * 误删会让那次写入失败。
     */
    private fun cleanStaleTempFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - STALE_TMP_MS
        runCatching {
            dir.listFiles { candidate ->
                candidate.isFile &&
                    candidate.name.startsWith(file.name + ".") &&
                    candidate.name.endsWith(".tmp")
            }?.forEach { stale ->
                if (stale.lastModified() < cutoff) runCatching { stale.delete() }
            }
        }
    }

    /**
     * 把 [tmp] 覆盖到 [target]。失败时 [target] 必须仍在原处（**不先删**）。
     *
     * 首选 `ATOMIC_MOVE`（POSIX rename，不存在"文件短暂消失"的窗口）；部分
     * 文件系统不支持，退化为 `REPLACE_EXISTING`，最后才是 `renameTo`。
     */
    private fun moveOver(tmp: File, target: File): MarketStateWrite {
        val path = runCatching {
            java.nio.file.Files.move(
                tmp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }
        if (path.isSuccess) return MarketStateWrite.Ok
        val replaced = runCatching {
            java.nio.file.Files.move(
                tmp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
        if (replaced.isSuccess) return MarketStateWrite.Ok
        if (tmp.renameTo(target)) return MarketStateWrite.Ok
        runCatching { tmp.delete() }
        return MarketStateWrite.IoFailed(
            "状态文件替换失败（原文件未被改动）：${path.exceptionOrNull()?.message ?: "unknown"} / " +
                "replacing the state file failed (the original file was left untouched)",
        )
    }

    private fun randomSuffix(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(8)

    /** 目录收紧到仅属主可读写执行；在部分文件系统上无效果，因此失败不影响写入。 */
    private fun restrictToOwner(dir: File) {
        runCatching {
            dir.setReadable(true, true)
            dir.setWritable(true, true)
            dir.setExecutable(true, true)
        }
    }

    private fun decode(text: String): MarketState {
        val root = JSONObject(text)
        val disabled = when {
            root.has("disabled") && !root.isNull("disabled") ->
                root.optJSONArray("disabled").toStringList()

            root.has("disabledSkins") && !root.isNull("disabledSkins") ->
                root.optJSONArray("disabledSkins").toStringList()

            else -> emptyList()
        }
        val notes = LinkedHashMap<String, String>()
        root.optJSONObject("notes")?.let { obj ->
            for (key in obj.keys()) {
                val value = obj.optString(key)
                if (value.trim().isNotEmpty()) notes[key] = value.take(MarketStateCodec.MAX_NOTE)
            }
        }
        val allKeys = root.keys().asSequence().toList()
        val preserved = LinkedHashMap<String, String>()
        for (key in MarketStateCodec.preservedKeys(allKeys)) {
            // 原始片段逐字留存；不认识的字段一律不解释、不改写。
            preserved[key] = rawJsonOf(root.get(key))
        }
        return MarketState(disabled = disabled, notes = notes, preserved = preserved)
    }

    /**
     * 把已解出的 JSON 值还原成**合法**的 JSON 片段。
     *
     * 不能直接 `toString()`：`JSONObject.get` 对字符串字段返回的是裸 `String`，
     * `toString()` 会把它写成不带引号的 `beta`——于是写回的 state.json 整份
     * 变成非法 JSON，桌面端读它会得到"设置全部丢失"。而桌面端写进去的
     * `channel` / `region` / `githubProxy` 恰好都是字符串字段。
     */
    private fun rawJsonOf(value: Any?): String = when {
        value == null -> "null"
        // `JSONObject.NULL` 是 JSON null 的哨兵对象，不是 Java null；它必须
        // 写成裸 `null`，写成字符串 "null" 会改变字段语义。
        value === JSONObject.NULL -> "null"
        value is String -> MarketStateCodec.encodeString(value)
        value is Boolean || value is Number || value is JSONObject || value is JSONArray -> value.toString()
        // 未知类型（第三方 JSON 实现的值对象）按字符串处理：加引号最多多一层，
        // 不加引号会让整份文件不可读。
        else -> MarketStateCodec.encodeString(value.toString())
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = mutableListOf<String>()
        for (index in 0 until length()) {
            val value = optString(index)
            if (value.isNotBlank()) out.add(value)
        }
        return out
    }
}
