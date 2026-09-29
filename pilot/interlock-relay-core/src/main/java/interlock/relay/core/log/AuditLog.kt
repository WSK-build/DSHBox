package interlock.relay.core.log

import interlock.relay.core.runtime.monotonicNow
import interlock.relay.core.runtime.wallNow
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一次能力调用的授权记录。面向用户可问责，与运行日志分开存储。 */
data class AuditEntry(
    val requestId: String,
    val capability: String,
    /**
     * 本次操作的对象（包名、联系人名、日历项）。参数原文一律不落盘；对象这一项是用户在
     * 确认框里已经看过并据以表态的内容，授权记录里没有它就无法事后问责。
     */
    val target: String?,
    val systemState: String,
    val tier: String,
    val decision: String,
    val grantSource: String,
    val uidRole: String,
    val outcome: String,
    val surface: String?,
    val degradedFrom: String?,
    val reason: String?,
    val latencyMs: Long,
    val artifactBytes: Long,
    /** 参数原文一律不落盘，只落加盐摘要。 */
    val canonicalArgs: String?,
)

/**
 * 授权记录存储。目录不得位于任何 bind 共享子树内：沙盒与宿主同 uid，
 * 放进助手可读可写的目录等于让被记录的一方改写记录。
 *
 * 本模块自己承担保留期回收：`logs` 目录之外没有任何外部清理逻辑认得这些文件，
 * [purgeExpired] 必须被周期性调用，否则记录会无限增长到写满分区。
 */
class AuditLog(
    private val auditDir: File,
    private val digestSalt: String,
    private val reportFailure: (String) -> Unit = {},
    private val now: () -> Long = ::wallNow,
    /** 淘汰线只认这条单调时钟；`elapsedRealtime` 跨重启归零，所以归零就当"重新对表"。 */
    private val mono: () -> Long = ::monotonicNow,
    /** 分段的上限与段数做成入参：滚动淘汰的判据要在单测里跑到，不能要求测试写满 40MB。 */
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    private val maxSegments: Int = MAX_SEGMENTS,
    /** 整库上限。按日的环各自有界不等于整库有界：30 天 × 40MB 是 1.2GB。 */
    private val storeCapBytes: Long = STORE_CAP_BYTES,
) {

    private val lock = Any()

    fun record(entry: AuditEntry) {
        val fields = buildList<Pair<String, Any?>> {
            add("ts" to timestampFormat().format(Date(now())))
            add("req" to safeId(entry.requestId))
            add("cap" to entry.capability)
            add("target" to (entry.target ?: NULL_VALUE))
            add("sys" to entry.systemState)
            add("tier" to entry.tier)
            add("decision" to entry.decision)
            add("source" to entry.grantSource)
            add("uid" to entry.uidRole)
            add("result" to entry.outcome)
            add("surface" to (entry.surface ?: NULL_VALUE))
            add("from" to (entry.degradedFrom ?: NULL_VALUE))
            add("reason" to (entry.reason ?: NULL_VALUE))
            add("ms" to entry.latencyMs)
            add("bytes" to entry.artifactBytes)
            add("argsDigest" to (entry.canonicalArgs?.let { digest(it) } ?: NULL_VALUE))
        }
        val line = JsonLine.of(fields)
        val written = runCatching {
            synchronized(lock) {
                auditDir.mkdirs()
                targetFile().appendText(line + "\n")
            }
            true
        }.getOrDefault(false)
        if (!written) {
            // 磁盘满或目录不可写时记录会静默停止增长，必须留下别处可见的痕迹；
            // 走 [reportFailure] 而不是直写运行日志对象，避免两个存储互相触发。
            reportFailure("append rejected")
        }
    }

    /**
     * 参数摘要，与落进授权记录的是同一个值。
     * 供详细运行日志复用：运行日志也不得出现参数原文，两处必须是同一套加盐摘要，
     * 否则「一个地方只有摘要、另一个地方能反推出原文」会绕过整个不落盘纪律。
     */
    fun argsDigest(canonical: String?): String? = canonical?.let { digest(it) }

    /**
     * 参数原文已知存在时用的那条：同一个加盐摘要，只是不把 null 传给调用方。
     * 审批侧拿它当「是不是同一件事」的身份，那里给不出一个「摘要取不到」的替代值 ——
     * 任何常量都会把两件不同的事并成一件。
     */
    fun argsDigestOf(canonical: String): String = digest(canonical)

    /**
     * 只读尾部若干字节，避免把整个记录文件读进内存。
     *
     * 列文件与读文件必须在同一把锁里：滚动淘汰会在锁内 rename 这些文件，两步分开时
     * 一次读取可以正好被挪动插进去 —— 同一条记录被两个名字各读一遍，或者一整段被跳过。
     * 诊断页读的是有界的尾部（每段 [TAIL_READ_BYTES]），把读放进锁里的代价可以接受。
     */
    fun recent(limit: Int, tailBytes: Int = TAIL_READ_BYTES): List<String> = synchronized(lock) {
        val files = auditDir.listFiles { file -> file.isFile && file.name.startsWith(PREFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: return emptyList()
        val out = ArrayList<String>(limit)
        for (file in files) {
            if (out.size >= limit) break
            val lines = readTail(file, tailBytes)
            for (line in lines.asReversed()) {
                if (line.isBlank()) continue
                out += line
                if (out.size >= limit) break
            }
        }
        out
    }

    /** 只算记录文件：水位线不是记录，不该出现在界面的"授权记录占用"里。 */
    fun usageBytes(): Long = runCatching {
        synchronized(lock) {
            auditDir.listFiles()?.sumOf { if (it.isFile && it.name.startsWith(PREFIX)) it.length() else 0L } ?: 0L
        }
    }.getOrDefault(0L)

    /**
     * 保留期清理 + 整库字节上限，由存储清扫流程按周期调用。
     *
     * 两件事各治一个洞，缺一不可：
     *
     *  - **保留期不能拿挂钟做差**。`now()` 是墙钟，把系统时间往前拨 `retentionDays`
     *    就会让下一次巡检一次删光整条记录链 —— 而授权记录正是出事时要看的东西，
     *    本模块的 `RelayTime` 也明令不许拿挂钟做差。所以淘汰线只按**单调时钟实际走过的
     *    时间**推进（水位线落在 `.watermark`），墙钟跳变不被采信。设备重启后
     *    `elapsedRealtime` 归零，那一刻无从判断过了多久，只能重新对表且**本 tick 不按
     *    时间删任何东西** —— 宁可晚一轮清理，也不能让一次改表清空记录。
     *  - **保留期是按日建的环**，30 天各 40MB 意味着整库最坏 1.2GB。字节上限兜住这一头：
     *    到顶先淘汰最旧那一整日的环，最近一日永远完整。
     */
    fun purgeExpired(retentionDays: Int = DEFAULT_RETENTION_DAYS): Int {
        var removed = 0
        synchronized(lock) {
            val trusted = advanceTrustedClock()
            val files = auditDir.listFiles { file -> file.isFile && file.name.startsWith(PREFIX) }
                ?.toList() ?: return 0
            if (trusted > 0L) {
                val cutoff = trusted - retentionDays.toLong() * DAY_MS
                files.filter { it.lastModified() < cutoff }.forEach { if (it.delete()) removed++ }
            }
            removed += enforceStoreCap(files.filter { it.exists() })
        }
        return removed
    }

    /**
     * 推进并返回"可信的现在"。返回 0 表示刚对过表（首次或重启后），调用方这一轮不该
     * 按时间删任何东西。
     */
    private fun advanceTrustedClock(): Long {
        val mark = File(auditDir, WATERMARK)
        val parts = runCatching { mark.readText().trim().split(" ") }.getOrNull()
        if (parts?.size != 2) {
            writeWatermark(now(), mono())
            return 0L
        }
        val anchorWall = parts[0].toLongOrNull() ?: return 0L.also { writeWatermark(now(), mono()) }
        val anchorMono = parts[1].toLongOrNull() ?: return 0L.also { writeWatermark(now(), mono()) }
        val elapsed = mono() - anchorMono
        if (elapsed < 0L) {
            // elapsedRealtime 只会单调增，倒退只能是重启过。重启前到底过了多久无从得知，
            // 重新对表并跳过这一轮的时间判据。
            writeWatermark(now(), mono())
            return 0L
        }
        val trusted = anchorWall + elapsed
        writeWatermark(trusted, mono())
        return trusted
    }

    private fun writeWatermark(wall: Long, monoAt: Long) {
        runCatching {
            auditDir.mkdirs()
            markFile().writeText("$wall $monoAt")
        }
    }

    private fun markFile(): File = File(auditDir, WATERMARK)

    /** 整库超上限就按日淘汰最旧那一整环；只剩一日时不再删，宁可越界也不清空记录。 */
    private fun enforceStoreCap(files: List<File>): Int {
        var total = files.sumOf { it.length() }
        if (total <= storeCapBytes) return 0
        val byDay = files.groupBy { dayStamp(it.name) }
        var removed = 0
        for (day in byDay.keys.sortedBy { stamp -> byDay.getValue(stamp).minOf { it.lastModified() } }
                .dropLast(1)) {
            for (file in byDay.getValue(day)) {
                // 长度要在删之前取：删完再 `length()` 只会拿到 0，越界也就永远算不出来。
                val size = file.length()
                if (file.delete()) {
                    removed++
                    total -= size
                }
                if (total <= storeCapBytes) return removed
            }
        }
        return removed
    }

    /** `audit-20260926.jsonl` 与 `audit-20260926.7.jsonl` 都归到 `20260926` 这一日。 */
    private fun dayStamp(name: String): String =
        name.removePrefix(PREFIX).takeWhile { it.isDigit() }

    /**
     * 单文件超限后另起分段；当天的分段全部到顶时**滚动淘汰最旧那一份**，新记录照样有地方落。
     *
     * 不写成"到顶即停"：授权记录在被灌循环的那一刻正好最需要留痕，停写等于让安全记录在
     * 压力下自己闭嘴，而那正是灌循环的一方想要的结果。丢的因此是最旧的记录 ——
     * 事后问责要看的是刚刚发生过什么，最近这一段始终完整。
     */
    private fun targetFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now()))
        val files = (1..maxSegments).map { segmentFile(stamp, it) }
        for (file in files) if (file.length() <= maxFileBytes) return file
        files.first().delete()
        // 一格一格往前挪，并且**认 rename 的结果**：不看返回值时，第 k 格挪不动会让第 k+1 格
        // 盖到第 k 格上，那一段的字节就此丢掉。挪不动就停在刚腾空的这一格 —— 总占用最多
        // 超出一段，而一段记录都不会消失。
        var free = 0
        while (free + 1 < files.size && files[free + 1].renameTo(files[free])) free++
        return files[free]
    }

    /** 段号 1 是当天主文件，2..[maxSegments] 是超限后的分段。 */
    private fun segmentFile(stamp: String, segment: Int): File =
        if (segment <= 1) File(auditDir, "$PREFIX$stamp$SUFFIX")
        else File(auditDir, "$PREFIX$stamp.$segment$SUFFIX")

    private fun readTail(file: File, bytes: Int): List<String> = runCatching {
        RandomAccessFile(file, "r").use { access ->
            val length = access.length()
            val start = (length - bytes).coerceAtLeast(0L)
            access.seek(start)
            val buffer = ByteArray((length - start).toInt())
            access.readFully(buffer)
            // 不能用 RandomAccessFile.readLine：它按 ISO-8859-1 解行，
            // 一旦记录里出现非 ASCII 字段（如中文目标名）就会整行乱码。
            val lines = String(buffer, Charsets.UTF_8).split('\n')
            if (start > 0L) lines.drop(1) else lines
        }
    }.getOrDefault(emptyList())

    private fun safeId(value: String): String = value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)

    /** 加本机盐再截断：低熵参数（姓名、通知文本）否则可通过字典枚举反推。 */
    private fun digest(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest((digestSalt + "\n" + text).toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PREFIX = "audit-"
        const val SUFFIX = ".jsonl"
        const val NULL_VALUE = "null"
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
        const val MAX_SEGMENTS = 20
        const val TAIL_READ_BYTES = 64 * 1024
        const val DEFAULT_RETENTION_DAYS = 30
        const val DAY_MS = 24L * 60L * 60L * 1000L

        /** 整库上限：与产物配额同一量级，到顶先丢最旧那一整日的环。 */
        const val STORE_CAP_BYTES = 200L * 1024 * 1024

        /** 淘汰水位线：`<可信墙钟> <对表时的单调时钟>`。带点号，不会被当成记录文件。 */
        const val WATERMARK = ".watermark"
    }
}
