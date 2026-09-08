package com.dshbox.app.util.viewer

import java.io.File

/**
 * 文件类型识别器（1.2.0 §6.2，纯 JVM，无 Android 依赖）。
 *
 * 三级识别，**内容优先、不信扩展名**：
 * 1. **魔数签名**（前 16–512 字节）：图片/PDF/ZIP 族/gzip/zst/7z/rar/tar/ELF/class/dex/SQLite/
 *    音视频容器；ZIP 族（PK）命中后按扩展名二级区分 docx/xlsx/pptx/jar/apk 等；
 * 2. **文本嗅探**：前 8KB 含 NUL 或控制字节比例超阈值 → 二进制；否则按文本处理
 *    （覆盖无扩展名脚本、Dockerfile、Makefile 等）。UTF BOM 直接定编码线索；
 * 3. **扩展名兜底归类**：代码语言（高亮选择）、Markdown/标记、Office、配置文件等。
 *
 * 输出 [FileType]：[FileKind] + 子类型（语言/具体格式）+ 置信度。
 * 分类失败/未知 → [FileKind.HEX] 或 [FileKind.UNKNOWN]，UI 落 HexViewer + 信息卡——
 * **任何文件都有界面打开，不存在「不支持预览」死路（§6.1.4）**。
 */
object FileTypeClassifier {

    /** 文本嗅探的探测窗口（字节）。 */
    const val SNIFF_LIMIT = 8 * 1024

    /** 文件大类（§6.1 分发目标）。UNKNOWN 为 §6.2 预留输出：M2 路由不产出——
     *  无法识别的二进制一律 HEX（§6.3「非文本即 Hex」兜底行），M3+ 子类型标注再启用。 */
    enum class FileKind { TEXT, IMAGE, PDF, MARKUP, ARCHIVE, OFFICE, HEX, AUDIO_VIDEO, UNKNOWN }

    /** 识别置信度：魔数 HIGH；嗅探 MEDIUM；扩展名兜底 LOW。 */
    enum class Confidence { HIGH, MEDIUM, LOW }

    data class FileType(
        val kind: FileKind,
        /** 子类型：语言名（python/json/…）、具体格式（png/zip/elf/…）或 null。 */
        val subType: String?,
        val confidence: Confidence,
        /** 小写扩展名（无扩展名为空串），信息卡与二级判断用。 */
        val extension: String,
    )

    /**
     * 纯函数分类入口。[head] 为文件头部采样（建议 ≥512 字节，tar 需 262+），
     * [fileSize] 为文件真实大小（0 表示空文件）。
     */
    fun classify(name: String, head: ByteArray, fileSize: Long): FileType {
        // M3 返工 B：复合扩展名还原（a.tar.gz 的 extensionOf 只得 "gz"，tar 容器语义丢失
        // 导致 formatOf 全部落 null → 信息卡）。分类一律用容器感知扩展名。
        val ext = effectiveExtensionOf(name)
        magicOf(head, ext, fileSize)?.let { (kind, sub, conf) ->
            return FileType(kind, sub, conf, ext)
        }
        // 文本嗅探（二级）：仅对非空样本判断。
        // §6.2：UTF BOM（EF BB BF / FF FE / FE FF）直接定文本——UTF-16 文本含大量 NUL，
        // 若不加此分支会被嗅探误判二进制（编码判定由 TextEncoding 承接）。
        if (hasBom(head)) {
            return textResult(ext, Confidence.MEDIUM)
        }
        if (fileSize > 0L && head.isNotEmpty()) {
            if (looksBinary(head)) {
                // 无魔数的二进制：HEX（子类型按扩展名提示，如 so/dat/bin）
                return FileType(FileKind.HEX, ext.ifEmpty { null }, Confidence.MEDIUM, ext)
            }
            return textResult(ext, Confidence.MEDIUM)
        }
        // 空文件：按扩展名归类（空脚本/空配置可编辑），无扩展名归 TEXT（可编辑）
        return textResult(ext, Confidence.LOW)
    }

    /** [classify] 的文件入口：读头部采样后复用纯函数（IO 在调用方线程执行）。 */
    fun classify(file: File): FileType {
        val size = runCatching { file.length() }.getOrDefault(0L)
        val head = readHead(file, maxOf(SNIFF_LIMIT, 512))
        return classify(file.name, head, size)
    }

    /** 读文件头 [limit] 字节（不足则全量）。 */
    fun readHead(file: File, limit: Int): ByteArray {
        if (!file.isFile || file.length() <= 0L) return ByteArray(0)
        return runCatching {
            file.inputStream().buffered().use { ins ->
                val buf = ByteArray(limit)
                var off = 0
                while (off < limit) {
                    val n = ins.read(buf, off, limit - off)
                    if (n < 0) break
                    off += n
                }
                if (off == limit) buf else buf.copyOf(off)
            }
        }.getOrDefault(ByteArray(0))
    }

    // ---------------- 魔数（一级） ----------------

    /** 返回 (kind, subType, confidence)；无魔数命中返回 null。 */
    private fun magicOf(head: ByteArray, ext: String, fileSize: Long): Triple<FileKind, String, Confidence>? {
        fun starts(vararg sig: Int): Boolean = head.size >= sig.size && sig.indices.all { head[it] == sig[it].toByte() }
        fun startsAt(offset: Int, s: String): Boolean {
            if (head.size < offset + s.length) return false
            for (i in s.indices) if (head[offset + i] != s[i].code.toByte()) return false
            return true
        }

        return when {
            // ---- 图片 ----
            starts(0x89, 0x50, 0x4E, 0x47) -> Triple(FileKind.IMAGE, "png", Confidence.HIGH)
            starts(0xFF, 0xD8, 0xFF) -> Triple(FileKind.IMAGE, "jpeg", Confidence.HIGH)
            startsAt(0, "GIF8") -> Triple(FileKind.IMAGE, "gif", Confidence.HIGH)
            starts(0x52, 0x49, 0x46, 0x46) && startsAt(8, "WEBP") -> Triple(FileKind.IMAGE, "webp", Confidence.HIGH)
            starts(0x42, 0x4D) -> Triple(FileKind.IMAGE, "bmp", Confidence.HIGH)
            startsAt(4, "ftyp") -> isoBmffBrand(head)?.let { Triple(it.first, it.second, Confidence.HIGH) }
            // ---- 文档 ----
            startsAt(0, "%PDF") -> Triple(FileKind.PDF, "pdf", Confidence.HIGH)
            // ---- ZIP 族（二级按扩展名区分，§6.2）：OOXML 文档 → OFFICE（M3 抽文本），
            //      jar/apk/aar/war/epub 应用容器与普通 zip → ARCHIVE（M3 ArchiveViewer 条目浏览）
            starts(0x50, 0x4B) -> Triple(
                if (ext in OOXML_EXT) FileKind.OFFICE else FileKind.ARCHIVE,
                ext.ifEmpty { "zip" },
                Confidence.HIGH,
            )
            // ---- 压缩容器 ----
            starts(0x1F, 0x8B) -> Triple(FileKind.ARCHIVE, if (ext in TAR_GZ_EXT) "tar.gz" else "gzip", Confidence.HIGH)
            starts(0x28, 0xB5, 0x2F, 0xFD) -> Triple(FileKind.ARCHIVE, if (ext in TAR_ZST_EXT) "tar.zst" else "zstd", Confidence.HIGH)
            startsAt(0, "BZh") -> Triple(FileKind.ARCHIVE, if (ext in TAR_BZ2_EXT) "tar.bz2" else "bzip2", Confidence.HIGH)
            starts(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> Triple(FileKind.ARCHIVE, if (ext == "tar") "tar.xz" else "xz", Confidence.HIGH)
            starts(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> Triple(FileKind.ARCHIVE, "7z", Confidence.HIGH)
            startsAt(0, "Rar!\u001A\u0007") -> Triple(FileKind.ARCHIVE, "rar", Confidence.HIGH)
            startsAt(257, "ustar") && fileSize >= 512 -> Triple(FileKind.ARCHIVE, "tar", Confidence.HIGH)
            // ---- 可执行 / 结构化二进制 ----
            starts(0x7F, 0x45, 0x4C, 0x46) -> Triple(FileKind.HEX, elfSubType(head), Confidence.HIGH)
            starts(0xCA, 0xFE, 0xBA, 0xBE) -> Triple(FileKind.HEX, "java-class", Confidence.HIGH)
            startsAt(0, "dex\n") -> Triple(FileKind.HEX, "dex", Confidence.HIGH)
            startsAt(0, "SQLite format 3\u0000") -> Triple(FileKind.HEX, "sqlite", Confidence.HIGH)
            startsAt(0, "wOF2") -> Triple(FileKind.HEX, "font", Confidence.HIGH)
            startsAt(0, "wOFF") -> Triple(FileKind.HEX, "font", Confidence.HIGH)
            startsAt(0, "OTTO") -> Triple(FileKind.HEX, "font", Confidence.HIGH)
            // ---- 音视频容器 ----
            starts(0xFF, 0xF1) || starts(0xFF, 0xF2) || starts(0xFF, 0xF3) || starts(0xFF, 0xFB) ->
                Triple(FileKind.AUDIO_VIDEO, "mp3", Confidence.HIGH)
            startsAt(0, "ID3") -> Triple(FileKind.AUDIO_VIDEO, "mp3", Confidence.HIGH)
            starts(0x1A, 0x45, 0xDF, 0xA3) -> Triple(FileKind.AUDIO_VIDEO, "mkv/webm", Confidence.HIGH)
            startsAt(0, "OggS") -> Triple(FileKind.AUDIO_VIDEO, "ogg", Confidence.HIGH)
            startsAt(0, "fLaC") -> Triple(FileKind.AUDIO_VIDEO, "flac", Confidence.HIGH)
            starts(0x52, 0x49, 0x46, 0x46) && startsAt(8, "WAVE") -> Triple(FileKind.AUDIO_VIDEO, "wav", Confidence.HIGH)
            starts(0x52, 0x49, 0x46, 0x46) && startsAt(8, "AVI ") -> Triple(FileKind.AUDIO_VIDEO, "avi", Confidence.HIGH)
            else -> null
        }
    }

    /**
     * ELF 子类型（返工二批 #11：落实 §6.3「ELF 架构信息卡」）——读 e_machine（偏移 18，
     * 2 字节，端序按 EI_DATA）映射常见架构；未识别返回 "elf"。
     */
    private fun elfSubType(head: ByteArray): String {
        if (head.size < 20) return "elf"
        val little = (head[5].toInt() and 0xFF) == 1 // EI_DATA: 1=LE, 2=BE
        val machine = if (little) {
            (head[18].toInt() and 0xFF) or ((head[19].toInt() and 0xFF) shl 8)
        } else {
            ((head[18].toInt() and 0xFF) shl 8) or (head[19].toInt() and 0xFF)
        }
        return when (machine) {
            0xB7 -> "elf-aarch64"
            0x3E -> "elf-x86_64"
            0x28 -> "elf-arm"
            0x03 -> "elf-x86"
            0xF3 -> "elf-riscv"
            else -> "elf"
        }
    }

    /**
     * ISO-BMFF「ftyp」box 品牌分流（返工修正）：图片品牌 → IMAGE（heif/avif）；
     * 音视频容器品牌（mp4/mov/m4a 等）→ AUDIO_VIDEO——否则大视频文件会落
     * 嗅探（含 NUL）→ HEX，几 GB 的视频点开进 HexViewer（返工清单 #9）。
     * 未识别品牌返回 null 落后续嗅探。
     */
    private fun isoBmffBrand(head: ByteArray): Pair<FileKind, String>? {
        if (head.size < 12) return null
        val brand = String(head, 8, 4, Charsets.US_ASCII)
        return when (brand) {
            "avif", "avis" -> FileKind.IMAGE to "avif"
            "heic", "heix", "hevc", "hevx", "mif1", "msf1" -> FileKind.IMAGE to "heif"
            "m4a", "M4A " -> FileKind.AUDIO_VIDEO to "m4a"
            "qt  " -> FileKind.AUDIO_VIDEO to "mov"
            "f4v ", "isom", "iso2", "mp41", "mp42", "mmp4", "NDSC", "NSDC", "NDSH", "NSMH",
            "NDSP", "MSNV", "avc1", "avc3", "M4V ", "M4VP", "dash", "msdh", "msix",
            -> FileKind.AUDIO_VIDEO to "mp4"
            else -> null
        }
    }

    // ---------------- 文本嗅探（二级） ----------------

    /**
     * 是否二进制样本：含 NUL 即否决；控制字节（除 \t \n \r \f \v 与 ESC）比例超 5% 否决。
     * 高位字节（≥0x80）不算二进制特征——UTF-8/GBK 等多字节文本依赖它们。
     */
    private fun looksBinary(head: ByteArray): Boolean {
        val n = minOf(head.size, SNIFF_LIMIT)
        if (n == 0) return false
        var control = 0
        for (i in 0 until n) {
            val b = head[i].toInt() and 0xFF
            if (b == 0x00) return true
            if (b < 0x20 && b !in ALLOWED_CONTROL) control++
        }
        return control * 20 > n // 比例 > 5%
    }

    private val ALLOWED_CONTROL = intArrayOf(0x09, 0x0A, 0x0D, 0x0C, 0x0B, 0x1B)

    /** UTF BOM 检查（UTF-8 / UTF-16LE / UTF-16BE）。 */
    private fun hasBom(head: ByteArray): Boolean {
        if (head.size < 2) return false
        val b0 = head[0].toInt() and 0xFF
        val b1 = head[1].toInt() and 0xFF
        if (b0 == 0xEF && b1 == 0xBB && head.size >= 3 && (head[2].toInt() and 0xFF) == 0xBF) return true
        return (b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)
    }

    // ---------------- 扩展名兜底（三级） ----------------

    /** 按扩展名归类的文本结果：代码语言 / Markdown / 标记语言归 MARKUP，其余 TEXT。 */
    private fun textResult(ext: String, confidence: Confidence): FileType = when {
        ext in MARKUP_EXT -> FileType(FileKind.MARKUP, ext, confidence, ext)
        else -> FileType(FileKind.TEXT, LANG_BY_EXT[ext], confidence, ext)
    }

    fun extensionOf(name: String): String {
        val lower = name.lowercase()
        val dot = lower.lastIndexOf('.')
        return if (dot > 0) lower.substring(dot + 1) else ""
    }

    /**
     * 容器感知扩展名：在 [extensionOf] 之上还原复合扩展名——`a.tar.gz` → "tar.gz"、
     * `a.tar.zst` → "tar.zst"、`a.tar.bz2` → "tar.bz2"、`a.tzst` → "tar.zst"（惯例命名）。
     * 其余名称与 [extensionOf] 等价。纯函数，M3 返工 B 的端到端命名用例覆盖。
     */
    internal fun effectiveExtensionOf(name: String): String {
        val ext = extensionOf(name)
        val lower = name.lowercase()
        return when {
            ext == "gz" && lower.endsWith(".tar.gz") -> "tar.gz"
            ext == "zst" && lower.endsWith(".tar.zst") -> "tar.zst"
            ext == "bz2" && lower.endsWith(".tar.bz2") -> "tar.bz2"
            ext == "tzst" -> "tar.zst"
            else -> ext
        }
    }

    /** 扩展名 → 高亮语言映射（§6.4 首发覆盖 6 种：json/yaml/sh/python/js/java+kotlin）。
     *  无映射的文本扩展落纯文本编辑（不参与高亮）。 */
    val LANG_BY_EXT: Map<String, String> = mapOf(
        // json
        "json" to "json", "jsonc" to "json", "json5" to "json", "map" to "json",
        // yaml
        "yaml" to "yaml", "yml" to "yaml",
        // shell
        "sh" to "shell", "bash" to "shell", "zsh" to "shell", "fish" to "shell", "command" to "shell",
        // python
        "py" to "python", "pyw" to "python", "pyi" to "python",
        // javascript / typescript
        "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript", "jsx" to "javascript",
        "ts" to "javascript", "tsx" to "javascript",
        // java / kotlin（共用一套 C 系规则）
        "java" to "java", "kt" to "java", "kts" to "java",
        // C 系其余文本（M2 纯文本编辑，高亮 1.2.x 评估扩展）
        "c" to "java", "h" to "java", "cpp" to "java", "hpp" to "java", "cc" to "java",
    )

    /** 扩展名 → 高亮语言（无映射返回 null，落纯文本）。 */
    fun highlightLanguageOf(ext: String): String? = LANG_BY_EXT[ext]

    private val MARKUP_EXT = setOf("md", "markdown", "html", "htm", "svg", "xml", "css", "vue")

    private val OOXML_EXT = setOf("docx", "xlsx", "pptx")

    // tar 容器扩展名集合（含 M3 返工 B 的复合扩展名与 tzst 惯例）
    private val TAR_GZ_EXT = setOf("tgz", "tar", "tar.gz")
    private val TAR_ZST_EXT = setOf("tar", "tar.zst")
    private val TAR_BZ2_EXT = setOf("tar", "tar.bz2")

    // ---------------- GlobalSearch 文本候选判定（§6.1.5 统一） ----------------

    /** 已知二进制扩展名：搜索跳过内容嗅探（免读盘），但文件名匹配照常。 */
    private val KNOWN_BINARY_EXT = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heif", "heic", "avif", "ico",
        "pdf", "zip", "jar", "apk", "aar", "war", "epub", "docx", "xlsx", "pptx",
        "gz", "tgz", "zst", "bz2", "xz", "7z", "rar", "tar", "deb", "rpm",
        "so", "o", "a", "class", "dex", "exe", "dll", "dylib", "bin", "dat",
        "ttf", "otf", "woff", "woff2", "eot",
        "mp3", "mp4", "mkv", "webm", "ogg", "flac", "wav", "avi", "mov", "m4a", "m4v",
        "sqlite", "db", "db3", "iso", "img", "vdi", "qcow2",
    )

    /**
     * 搜索内容命中候选（§6.1.5：取代 GlobalSearch 私有清单，消除「Makefile 搜索可命中、
     * 预览打不开」的分叉）。[head] 为调用方采样的前若干字节（可为空 = 不嗅探）。
     */
    fun isTextCandidate(name: String, head: ByteArray): Boolean {
        val ext = extensionOf(name)
        if (ext in KNOWN_BINARY_EXT) return false
        if (head.isEmpty()) return true // 无样本（空文件/未读）按候选处理
        return !looksBinary(head)
    }

    /** 已知二进制扩展名快捷判定（供搜索在采样前跳过读盘）。 */
    fun isKnownBinaryByExtension(name: String): Boolean = extensionOf(name) in KNOWN_BINARY_EXT
}
