package interlock.relay.core.storage

import android.system.Os
import android.system.OsConstants
import interlock.relay.core.log.LogEvent
import interlock.relay.core.log.LogSubsystem
import interlock.relay.core.log.RunLog
import interlock.relay.core.runtime.monotonicNow
import interlock.relay.core.runtime.wallNow
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 通道与产物的生命周期管理：孤儿请求回收、响应超时清理、产物按寿命与配额淘汰。
 *
 * 三条硬规则：
 * 1. 配额判定只有 [newArtifactFile] 一个入口，不接受调用方自行拼路径后直接写盘。
 * 2. 产物文件由宿主独占创建，名字含随机后缀——可预测的名字会让沙盒侧抢先把同名
 *    路径做成指向别处的符号链接，宿主写盘时便替它改了别处。
 * 3. 淘汰只碰未被在途响应引用的产物。响应已投递而助手尚未取走时，产物是它的正文。
 *
 * 时钟分工：文件 mtime 由内核按墙钟给出，与之比较一律用 [wallNow]；
 * 在途与滞留时长一律用 [now]（单调），两者不得互换。
 */
class StorageReaper(
    private val paths: RelayPaths,
    private val ledger: QuotaLedger,
    private val runLog: RunLog,
    private val now: () -> Long = ::monotonicNow,
    private val wallNow: () -> Long = ::wallNow,
) {

    /**
     * 一轮周期回收的读数。[staleRequests] 合计请求侧目录（inbox、processing 孤儿、
     * control-inbox 滞留件），[staleResponses] 合计响应侧目录（outbox、control-outbox）。
     * [skipped] 为真表示本轮被并发的清扫或清空让路，什么都没做——不能当成「配额已满」。
     */
    data class SweepResult(
        val staleRequests: Int = 0,
        val staleResponses: Int = 0,
        val deletedArtifacts: Int = 0,
        val freedBytes: Long = 0L,
        val skipped: Boolean = false,
    )

    /** `out/` 交付目录的用量读数：总量、文件数与四类分解（分解键为 [ArtifactOut.SUBDIRS]）。 */
    data class OutUsage(
        val bytes: Long = 0L,
        val files: Int = 0,
        val byKind: Map<String, Long> = emptyMap(),
    )

    /**
     * 准入短锁：清扫中 / 清空中 / 在途数三个准入状态必须在同一把锁内判定并翻转。
     * 「先读标志、再置标志」分两步走时，清空与开始调用会在两步之间互相插入，
     * 清理便会与在途的产物写入、读取并发。锁只护这几个标志位本身；
     * 清扫体与清空体都在锁外执行，长耗时操作不得持锁。
     */
    private val stateLock = Any()

    /** 清扫进行中：周期回收允许与在途调用并存，只与另一次清扫、「立即清理」互斥。 */
    private var sweeping = false

    /** 「立即清理」进行中：与清扫、在途调用都互斥。 */
    private var clearing = false

    /** 在途调用数，[beginCall] / [endCall] 配对增减。 */
    private var inFlight = 0

    /** 记录条目入 processing 的单调时刻，避免依赖可被系统时间影响的 mtime。 */
    private val processingSince = ConcurrentHashMap<String, Long>()

    /** 产物绝对路径 → 持有它的请求标识；请求标识 → 入钉时刻（单调）。 */
    private val pins = ConcurrentHashMap<String, String>()
    private val pinnedAt = ConcurrentHashMap<String, Long>()

    /** 暂存件绝对路径 → 它创建时占下的配额预留；产物入账或写入放弃时退还。 */
    private val pendingReservations = ConcurrentHashMap<String, QuotaLedger.Reservation>()

    private val random = SecureRandom()

    init {
        if (!paths.ensureWritableDirs()) {
            runLog.event(LogSubsystem.STORAGE, LogEvent.DIRS_UNAVAILABLE)
        }
    }

    /**
     * 登记一次在途调用。返回 false 表示清扫或「立即清理」正在进行、本次不该开始；
     * 此路径不留下计数，调用方重试即可，无需配对 [endCall]。
     */
    fun beginCall(): Boolean {
        synchronized(stateLock) {
            if (sweeping || clearing) return false
            inFlight++
        }
        return true
    }

    fun endCall() {
        synchronized(stateLock) { inFlight-- }
    }

    fun canAccept(bytes: Long): Boolean =
        !ledger.overQuota() && ledger.deviceHasRoom(paths.runDir, bytes) && ledger.usableBytes() >= bytes

    /**
     * 产物文件唯一入口：在**未绑定**的暂存目录里独占创建，返回可直接写流的文件。
     *
     * 不直接在产物目录建文件的原因：那个目录整体绑定给沙盒，宿主写完后改名前
     * 沙盒有机会把同名路径换成指向别处的符号链接，而宿主内核解析路径不受 PRoot 翻译，
     * 于是替它覆写了授权记录。写完后 [publishArtifact] 用 rename 落到产物目录，
     * rename 不跟随末级符号链接，窗口便不存在了。
     *
     * 配额以「预留—占住」走账：创建前先 [QuotaLedger.reserve] 占住预计字节，
     * 预留在产物入账（[publishArtifact]）前一直计入占用，杜绝两条并发产物
     * 看到同一份可用余额；产物未能落地时预留必须如数退还，不得虚占。
     *
     * 返回 null 表示配额确实放不下，调用方必须回「存储已满」，不得静默少写或写一张不完整的图。
     */
    fun newArtifactFile(requestId: String, extension: String, expectedBytes: Long): File? {
        require(extension in ALLOWED_EXTENSIONS) { "unsupported artifact extension: $extension" }
        val reservation = acquireQuotaRoom(expectedBytes)
        if (reservation == null) {
            runLog.event(LogSubsystem.STORAGE, LogEvent.QUOTA_EXHAUSTED, "want" to expectedBytes.toString())
            return null
        }
        val base = requestId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(48)
            .ifBlank { "artifact" }
        repeat(CREATE_ATTEMPTS) {
            val candidate = File(paths.stagingDir, "$base-${hex()}.$extension")
            if (createExclusive(candidate)) {
                // 预留继续占用到产物入账为止：publishArtifact 按实际字节入账后退还，
                // 写入中途放弃则由 discard 或清扫兜底退还。
                pendingReservations[candidate.absolutePath] = reservation
                return candidate
            }
        }
        ledger.release(reservation)
        runLog.event(LogSubsystem.STORAGE, LogEvent.ARTIFACT_CREATE_FAILED, "cap" to base)
        return null
    }

    /**
     * 把写好的暂存文件落进产物目录并记账、入钉。失败时清掉暂存件并返回 null，
     * 调用方必须把这看成一次失败，而不是「已经写了但没登记」。
     * 无论成败，暂存件占下的配额预留都随其终局一并退还。
     */
    fun publishArtifact(staged: File, requestId: String): File? {
        val target = File(paths.artifactsDir, staged.name)
        // 比较 canonical 挡不住中间级链接：`artifacts` 整个被换成指向 shared_prefs 的
        // 符号链接时，target 与 root 会一起解析到那里，startsWith 照样成立。
        if (!RelayPaths.symlinkFreeFrom(paths.entryDir, target)) {
            staged.delete()
            releasePendingReservation(staged)
            runLog.event(LogSubsystem.STORAGE, LogEvent.ATOMIC_WRITE_FAILED, "file" to target.name)
            return null
        }
        val moved = runCatching { staged.renameTo(target) }.getOrDefault(false)
        if (!moved) {
            runCatching { target.delete() }
            staged.delete()
            releasePendingReservation(staged)
            runLog.event(LogSubsystem.STORAGE, LogEvent.ATOMIC_WRITE_FAILED, "file" to target.name)
            return null
        }
        recordArtifact(target, requestId)
        releasePendingReservation(staged)
        return target
    }

    /** 先试预留配额，不足则清扫后重试；清扫被让路时不记作失败。设备分区余量与账本配额是两道独立的闸。 */
    private fun acquireQuotaRoom(expectedBytes: Long): QuotaLedger.Reservation? {
        if (!ledger.deviceHasRoom(paths.runDir, expectedBytes)) return null
        ledger.reserve(expectedBytes)?.let { return it }
        repeat(QUOTA_ATTEMPTS) {
            val result = sweep()
            if (result.skipped) pauseBriefly()
            if (!ledger.deviceHasRoom(paths.runDir, expectedBytes)) return null
            ledger.reserve(expectedBytes)?.let { return it }
        }
        return null
    }

    /** 记账并把产物钉在对应请求的响应上，直到响应被取走或超过在钉上限。 */
    fun recordArtifact(file: File, requestId: String) {
        val id = requestId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifBlank { "unknown" }
        pins[file.absolutePath] = id
        pinnedAt[id] = now()
        ledger.recordWritten(file.length())
    }

    /** 临时文件名由系统生成，避免固定后缀被预测或被沙盒侧预先占位。 */
    fun writeTextAtomic(target: File, text: String): Boolean {
        val parent = target.parentFile ?: return false
        // 响应落点 outbox 也在绑定子树内，与产物同一类风险：guest 把 run/outbox
        // 换成指向宿主私有目录的符号链接，宿主就会替它把响应写进去。
        if (target.absolutePath.startsWith(paths.entryDir.absolutePath.trimEnd('/') + File.separator) &&
            !RelayPaths.symlinkFreeFrom(paths.entryDir, target)
        ) {
            runLog.event(LogSubsystem.STORAGE, LogEvent.ATOMIC_WRITE_FAILED, "file" to target.name)
            return false
        }
        return runCatching {
            parent.mkdirs()
            val temp = File.createTempFile(target.name + "-", ".tmp", parent)
            temp.writeText(text)
            if (temp.renameTo(target)) true else {
                temp.delete()
                false
            }
        }.getOrElse {
            runLog.event(LogSubsystem.STORAGE, LogEvent.ATOMIC_WRITE_FAILED, "file" to target.name)
            false
        }
    }

    fun noteProcessing(name: String) {
        processingSince[name] = now()
    }

    fun clearProcessing(name: String) {
        processingSince.remove(name)
    }

    /** `out/` 交付目录此刻的用量。只读目录，不做任何判断，供界面与探活读数使用。 */
    fun outUsage(): OutUsage {
        var total = 0L
        var count = 0
        val byKind = LinkedHashMap<String, Long>()
        for (kind in ArtifactOut.SUBDIRS) {
            val dir = outDirFor(kind)
            val bytes = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
            byKind[kind] = bytes
            total += bytes
            count += dir.listFiles()?.count { it.isFile } ?: 0
        }
        return OutUsage(total, count, byKind)
    }

    /**
     * `out/` 的周期回收：份数、龄期、总量三道闸各自独立可触发。
     *
     * 纪律（与产物目录同一套）：
     * - 只处理四个已知子目录里的**常规文件**，不递归、不跟随符号链接（逐级检查见
     *   [RelayPaths.symlinkFreeFrom]）：`out/` 在绑定子树内，沙盒可以把子目录换成指向别处的链接；
     * - 最近 [ArtifactOut.RECENT_GRACE_MS] 内改动过的文件一律不动（录制中的视频正在写）；
     * - 每类至少保留最新一份：总量超限时也守着这一条，避免把用户"最后一张图"删掉。
     *
     * @return 删除的文件数与释放的字节数
     */
    private fun reapOutTree(): Pair<Int, Long> {
        var deleted = 0
        var freed = 0L
        val nowWall = wallNow()
        val ageCutoff = nowWall - ArtifactOut.MAX_AGE_MS
        val graceCutoff = nowWall - ArtifactOut.RECENT_GRACE_MS

        val live = ArrayList<Pair<File, Long>>() // 各子目录里"还在"的文件，供总量闸使用
        for (kind in ArtifactOut.SUBDIRS) {
            val dir = outDirFor(kind)
            if (!RelayPaths.symlinkFreeFrom(paths.entryDir, dir)) {
                runLog.error(LogSubsystem.STORAGE, LogEvent.UNSAFE_PATH_REJECTED, "name" to dir.name)
                continue
            }
            // 新 → 旧；前 KEEP_PER_KIND 个受份数闸保护。
            val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                ?: continue
            files.forEachIndexed { index, file ->
                val mtime = file.lastModified()
                if (mtime >= graceCutoff) {
                    live.add(file to mtime)
                    return@forEachIndexed
                }
                val tooMany = index >= ArtifactOut.KEEP_PER_KIND
                val tooOld = mtime < ageCutoff
                if (tooMany || tooOld) {
                    val bytes = deleteOutsideLedger(file)
                    if (bytes > 0L) {
                        freed += bytes
                        deleted++
                    }
                } else {
                    live.add(file to mtime)
                }
            }
        }

        // 总量闸：跨类合计超限时从最旧的一个开始删，仍守宽限与"每类至少一份"的底线。
        var total = live.sumOf { it.first.length() }
        if (total > ArtifactOut.MAX_TOTAL_BYTES) {
            val newestPerKind = HashMap<String, File>()
            for ((file, _) in live) {
                val kind = file.parentFile?.name ?: continue
                val current = newestPerKind[kind]
                if (current == null || file.lastModified() > current.lastModified()) newestPerKind[kind] = file
            }
            val protectedPaths = newestPerKind.values.map { it.absolutePath }.toHashSet()
            for ((file, _) in live.sortedBy { it.second }) {
                if (total <= ArtifactOut.MAX_TOTAL_BYTES) break
                if (file.absolutePath in protectedPaths) continue
                val bytes = deleteOutsideLedger(file)
                if (bytes > 0L) {
                    total -= bytes
                    freed += bytes
                    deleted++
                }
            }
        }
        return deleted to freed
    }

    /**
     * 「立即清理」对 `out/` 的那一半：清空四个子目录，每类默认保留**最新一份**
     * （用户可能正看着刚截图的那张；连它一起删掉是最容易被骂的"清理"）。
     * 与周期回收同一套链接判据；不递归、只删常规文件。
     */
    private fun clearOutTree(keepLatestPerKind: Boolean = true): Long {
        var freed = 0L
        for (kind in ArtifactOut.SUBDIRS) {
            val dir = outDirFor(kind)
            if (!RelayPaths.symlinkFreeFrom(paths.entryDir, dir)) {
                runLog.error(LogSubsystem.STORAGE, LogEvent.UNSAFE_PATH_REJECTED, "name" to dir.name)
                continue
            }
            val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                ?: continue
            val keep = if (keepLatestPerKind) files.take(1).map { it.absolutePath }.toHashSet() else emptySet()
            for (file in files) {
                if (file.absolutePath in keep) continue
                freed += deleteOutsideLedger(file)
            }
        }
        return freed
    }

    private fun outDirFor(kind: String): File = when (kind) {
        ArtifactOut.SHOT -> paths.outShotDir
        ArtifactOut.VIDEO -> paths.outVideoDir
        ArtifactOut.AUDIO -> paths.outAudioDir
        else -> paths.outFileDir
    }

    fun sweep(): SweepResult {
        synchronized(stateLock) {
            if (sweeping || clearing) return SweepResult(skipped = true)
            sweeping = true
        }
        // 清扫体耗时长，必须放在锁外：锁只裁决「能不能扫」，不覆盖「扫」本身。
        try {
            return doSweep()
        } finally {
            synchronized(stateLock) { sweeping = false }
        }
    }

    private fun doSweep(): SweepResult {
        var staleRequests = 0
        var staleResponses = 0
        var deletedArtifacts = 0
        var freed = 0L

        val cutoff = now() - QuotaLedger.ORPHAN_REQUEST_TTL_MS
        staleRequests += deleteOlderFiles(paths.inboxDir, wallNow() - QuotaLedger.ORPHAN_REQUEST_TTL_MS)
        staleRequests += deleteStaleProcessing(cutoff)
        // 控制收件箱滞留的未消费控制请求按孤儿请求同一寿命回收（没人消费它们就永远
        // 占着目录，也与普通请求一样按龄自愈）。
        staleRequests += deleteOlderFiles(paths.controlInboxDir, wallNow() - QuotaLedger.ORPHAN_REQUEST_TTL_MS)
        staleResponses += deleteOlderFiles(paths.outboxDir, wallNow() - QuotaLedger.RESPONSE_TTL_MS)
        // 控制回包与普通响应同一寿命：终态帧被取走（或过了可重取窗）之后留在原地
        // 只占磁盘。两个控制目录都在绑定子树内，deleteOlderFiles 的逐级链接判据照常把关。
        staleResponses += deleteOlderFiles(paths.controlOutboxDir, wallNow() - QuotaLedger.RESPONSE_TTL_MS)
        // 暂存件只在「写完—落地」这几毫秒里存在；留下来就是写失败或被中途取消的残骸。
        deleteOlderFiles(paths.stagingDir, wallNow() - STAGING_MAX_AGE_MS)
        releaseReservationsForVanishedStaging()
        // 入站中转件：递交了却没有任何调用消费（调用失败、助手放弃、只放了没发）
        // 也要按龄回收；再配一道总量上限，否则沙盒可以只往 uploads 里灌文件占满分区。
        deleteOlderFiles(paths.uploadsDir, wallNow() - QuotaLedger.ORPHAN_REQUEST_TTL_MS)
        trimUploadBudget()
        releaseExpiredPins()

        val ttlCutoff = wallNow() - QuotaLedger.ARTIFACT_TTL_MS
        val protectedPaths = pins.keys.toSet()
        val artifacts = paths.artifactsDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }
            ?: emptyList()
        for (file in artifacts.takeWhile { it.lastModified() < ttlCutoff }) {
            if (file.absolutePath in protectedPaths) continue
            val bytes = delete(file)
            if (bytes > 0L) pins.remove(file.absolutePath)
            freed += bytes
            deletedArtifacts++
        }

        // 配额淘汰：按需要释放的字节数递减预算，达标即停；删除后立即同步账本。
        var budget = ledger.totalBytes - ledger.maxBytes
        if (budget > 0L) {
            val remaining = paths.artifactsDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }
                ?: emptyList()
            for (file in remaining) {
                if (budget <= 0L) break
                if (file.absolutePath in protectedPaths) continue
                val bytes = delete(file)
                if (bytes > 0L) pins.remove(file.absolutePath)
                budget -= bytes
                freed += bytes
                deletedArtifacts++
            }
        }

        // 交付目录（out/）：份数/龄期/总量三道闸。它的字节不属于产物账本，
        // 因此单独记一条读数（freed 里含它，方便"清了到底少了多少"对账）。
        val (outDeleted, outFreed) = reapOutTree()
        deletedArtifacts += outDeleted
        freed += outFreed

        if (deletedArtifacts > 0 || staleRequests > 0 || staleResponses > 0) {
            runLog.event(
                LogSubsystem.STORAGE,
                LogEvent.SWEEP_DONE,
                "requests" to staleRequests.toString(),
                "responses" to staleResponses.toString(),
                "artifacts" to deletedArtifacts.toString(),
                "freed" to freed.toString(),
            )
        }
        return SweepResult(staleRequests, staleResponses, deletedArtifacts, freed)
    }

    /**
     * 界面「立即清理」入口。有在途调用、清扫或清空正在进行时返回 null，表示「现在不行」，
     * 而不是「清掉了 0 字节」——调用方要据此提示用户，不能显示成功。
     */
    fun clearAllArtifacts(): Long? {
        synchronized(stateLock) {
            if (inFlight > 0 || sweeping || clearing) return null
            clearing = true
        }
        try {
            val uploadsFreed = clearUploads()
            // 交付目录一并清（每类留最新一份）：用户看到的"占地方的东西"大半在这里，
            // 只清产物目录的话，「立即清理」就还是那个"点了没反应"的按钮。
            val outFreed = clearOutTree()
            val files = paths.artifactsDir.listFiles()?.filter { it.isFile } ?: emptyList()
            val freed = files.sumOf { delete(it) } + uploadsFreed + outFreed
            pins.clear()
            pinnedAt.clear()
            ledger.reconcileFromDisk(paths.artifactsDir)
            runLog.event(LogSubsystem.STORAGE, LogEvent.MANUAL_CLEAR, "freed" to freed.toString())
            return freed
        } finally {
            synchronized(stateLock) { clearing = false }
        }
    }

    /**
     * 用户按「立即清理」时一并回收沙盒递交的件。不计入账本，所以不走 [delete] 的冲账路径
     * —— 那会把从未入账的字节冲成负数。子目录逐个确认不是链接后才递归删除，
     * 否则一次删除会顺着链接删到绑定子树之外。
     */
    private fun clearUploads(): Long {
        if (!RelayPaths.symlinkFreeFrom(paths.entryDir, paths.uploadsDir)) return 0L
        return deleteUploadTree(paths.uploadsDir, 1)
    }

    /**
     * 安全删除一棵目录树，返回删掉的常规文件字节。
     *
     * 下潜前必须用 `lstat` 确认是真目录：`File.isDirectory()` 与 stdlib 的
     * `deleteRecursively()`（实现就是 `walkBottomUp().forEach { delete() }`，下潜判据
     * 跟随链接）都会走进符号链接。中转区整个躺在对手可写的子树里，
     * `mkdir uploads/d; ln -s ../../../<private-dir> uploads/d/s` 之后无脑递归，
     * 就是宿主亲手删掉自己的配额账本与授权记录。
     * 删掉一个链接本身只去掉那条目录项，是安全的；危险的是把链接当目录走进去。
     */
    private fun deleteUploadTree(root: File, depth: Int): Long {
        var freed = 0L
        // 先判递交目录这一层自身是不是符号链接：`File.listFiles()` 跟随链接，
        // 若不做这一眼，`ln -s ../../../<private-dir> uploads/x` 会让下面的遍历落在宿主
        // 私有目录里，其中的常规文件在 `lstat` 看来完全正常，于是被逐个删掉。
        val rootMode = runCatching { Os.lstat(root.absolutePath).st_mode }.getOrNull() ?: return 0L
        when (rootMode and OsConstants.S_IFMT) {
            OsConstants.S_IFREG -> {
                val bytes = root.length()
                return if (runCatching { root.delete() }.getOrDefault(false)) bytes else 0L
            }

            OsConstants.S_IFDIR -> Unit

            // 符号链接、FIFO、设备文件等：只删这条目录项本身，`unlink` 不触碰其目标。
            else -> {
                runCatching { root.delete() }
                return 0L
            }
        }
        for (entry in runCatching { root.listFiles() }.getOrNull() ?: return 0L) {
            val mode = runCatching { Os.lstat(entry.absolutePath).st_mode }.getOrNull() ?: continue
            when (mode and OsConstants.S_IFMT) {
                OsConstants.S_IFDIR -> {
                    if (depth < UPLOAD_MAX_DEPTH) freed += deleteUploadTree(entry, depth + 1)
                    runCatching { entry.delete() }
                }

                OsConstants.S_IFREG -> {
                    val bytes = entry.length()
                    if (runCatching { entry.delete() }.getOrDefault(false)) freed += bytes
                }

                else -> runCatching { entry.delete() }
            }
        }
        return freed
    }

    /** 中转区占用字节。同样按 `lstat` 分类，免得把链接目标的字节算成自己占的。 */
    private fun uploadBytes(dir: File = paths.uploadsDir, depth: Int = 0): Long {
        if (depth > UPLOAD_MAX_DEPTH) return 0L
        var total = 0L
        for (entry in runCatching { dir.listFiles() }.getOrNull() ?: return 0L) {
            val mode = runCatching { Os.lstat(entry.absolutePath).st_mode }.getOrNull() ?: continue
            when (mode and OsConstants.S_IFMT) {
                OsConstants.S_IFDIR -> total += uploadBytes(entry, depth + 1)
                OsConstants.S_IFREG -> total += entry.length()
            }
        }
        return total
    }

    /** 中转区当前占用字节。界面要如实显示"这部分也算助手占的"，故对外可读。 */
    fun uploadsBytes(): Long = uploadBytes()

    /**
     * 信箱三处的在途字节（请求 / 处理中 / 响应）。它们按 TTL 收敛，但同样占着磁盘，
     * 只报"产物"会让读数与用户直觉对不上。
     */
    fun mailboxBytes(): Long {
        var total = 0L
        for (dir in listOf(paths.inboxDir, paths.processingDir, paths.outboxDir)) {
            if (!RelayPaths.symlinkFreeFrom(paths.entryDir, dir)) continue
            for (entry in runCatching { dir.listFiles() }.getOrNull() ?: emptyArray()) {
                val mode = runCatching { Os.lstat(entry.absolutePath).st_mode }.getOrNull() ?: continue
                if (mode and OsConstants.S_IFREG != 0) total += entry.length()
            }
        }
        return total
    }

    /**
     * 配额口径 = 宿主产出的产物字节。
     *
     * 入站中转件**故意不计入**：这一格的界面标题是"产物"，把沙盒自己递交进来的文件
     * 加进去会让标题与数字不符；它们由 64 MiB 总量 + 128 条目 + 10 分钟按龄回收
     * 三重兜住，并且「立即清理」会一并回收（见 [clearUploads]），不靠这个读数来暴露。
     * 不记配额不等于不显示——[uploadsBytes] 与 [mailboxBytes] 就是给界面分开报数用的。
     */
    fun usageBytes(): Long = ledger.totalBytes

    /** 账本最近一次落盘是否成功。为 false 时占用读数暂时没有持久依据，重启对账前可能与磁盘不符。 */
    fun ledgerPersistHealthy(): Boolean = ledger.persistHealthy

    /** 放弃一个产物：撤钉与冲账，并退还它还占着的预留，不留只存在于内存里的孤儿引用。 */
    fun discard(file: File): Long {
        releasePendingReservation(file)
        return delete(file)
    }

    /** 预留随暂存件的终局退还：入账成功、写入放弃、文件消失，三条路都必须走到。 */
    private fun releasePendingReservation(file: File) {
        pendingReservations.remove(file.absolutePath)?.let { ledger.release(it) }
    }

    /** 独占创建并确认落在暂存目录内。符号链接、已存在、越界一律拒绝。 */
    private fun createExclusive(candidate: File): Boolean {
        val parent = candidate.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        val created = runCatching { candidate.createNewFile() }.getOrDefault(false)
        if (!created) {
            runCatching { candidate.delete() }
            return false
        }
        val canonical = runCatching { candidate.canonicalPath }.getOrNull()
        val root = runCatching { paths.stagingDir.canonicalPath }.getOrNull()
        if (canonical == null || root == null || !canonical.startsWith("$root${File.separatorChar}")) {
            candidate.delete()
            return false
        }
        return true
    }

    /** 请求的响应文件消失即视为已被取走；再配一个在钉上限，防止响应写失败留下永久引用。 */
    private fun releaseExpiredPins() {
        val stamp = now()
        val iterator = pins.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val owner = entry.value
            val responsePending = File(paths.outboxDir, "$owner.json").exists()
            val age = pinnedAt[owner]?.let { stamp - it } ?: Long.MAX_VALUE
            if (!responsePending || age > PIN_MAX_AGE_MS) {
                iterator.remove()
                if (pins.values.none { it == owner }) pinnedAt.remove(owner)
            }
        }
    }

    /**
     * 暂存件已消失而预留还在的兜底退还。后端可能绕过 [discard] 直接删暂存件，
     * 文件不在了，占住的预算若不退还，配额就被永久虚占。还在写的暂存件文件存在，
     * 不会被误退。
     */
    private fun releaseReservationsForVanishedStaging() {
        val holds = pendingReservations.entries.iterator()
        while (holds.hasNext()) {
            val entry = holds.next()
            if (!File(entry.key).exists()) {
                holds.remove()
                ledger.release(entry.value)
            }
        }
    }

    private fun deleteStaleProcessing(cutoff: Long): Int {
        val files = paths.processingDir.listFiles()?.filter { it.isFile } ?: return 0
        var deleted = 0
        for (file in files) {
            // 先判保留、后摘时间戳：摘了再判会让存留下来的文件丢掉单调记账，
            // 下一轮它就成了"无伴生时间戳的残留"，改由可被同 uid 改写的 mtime 说话。
            val since = processingSince[file.name]
            if (since != null && since > cutoff) continue
            // 无伴生时间戳的条目说明是重启前的残留，按文件时间兜底判定。
            if (since == null && file.lastModified() > wallNow() - QuotaLedger.ORPHAN_REQUEST_TTL_MS) {
                continue
            }
            // 删除失败时时间戳留在原地：文件还在，下一轮照旧按记账判定。
            if (file.delete()) {
                processingSince.remove(file.name)
                deleted++
            }
        }
        return deleted
    }

    /**
     * 中转区回收：字节总量与条目数两道闸，子目录一起算。
     *
     * 只算顶层常规文件，则 `mkdir uploads/a` 再往里灌就完全不计不删；只按 mtime 排序，
     * 同 uid 把时间戳调到未来即可让文件永不被删。因此另设条目硬上限，超出时按列表顺序
     * 驱逐，不看 mtime。
     *
     * 子目录用 `deleteRecursively` 前必须先确认它不是符号链接 —— 递归删除跟随链接，
     * 那会把链接目标整棵删掉。
     */
    private fun trimUploadBudget() {
        if (!RelayPaths.symlinkFreeFrom(paths.entryDir, paths.uploadsDir)) {
            runLog.error(LogSubsystem.STORAGE, LogEvent.UNSAFE_PATH_REJECTED, "name" to paths.uploadsDir.name)
            return
        }
        val entries = paths.uploadsDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var remaining = entries.size
        var total = uploadBytes()
        for (entry in entries) {
            if (total <= UPLOAD_TOTAL_BYTES && remaining <= UPLOAD_MAX_ENTRIES) break
            total -= deleteUploadTree(entry, 1)
            runCatching { entry.delete() }
            if (!entry.exists()) remaining--
        }
    }

    private fun deleteOlderFiles(dir: File, cutoff: Long): Int {
        // 删除是最危险的写操作：`dir` 在绑定子树内而它本身被沙盒换成指向宿主私有目录
        // 的符号链接时，按 mtime 删掉的就是别人的文件 —— 而 `isDirectory` 对指向目录的
        // 链接返回 true，这种状态永不自愈。暂存目录不在子树内，不受此约束。
        val entryRoot = paths.entryDir.absolutePath.trimEnd('/')
        val insideEntry = dir.absolutePath == entryRoot || dir.absolutePath.startsWith("$entryRoot/")
        if (insideEntry && !RelayPaths.symlinkFreeFrom(paths.entryDir, dir)) {
            runLog.error(LogSubsystem.STORAGE, LogEvent.UNSAFE_PATH_REJECTED, "name" to dir.name)
            return 0
        }
        val victims = dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff } ?: return 0
        return victims.count { it.delete() }
    }

    private fun delete(file: File): Long {
        val bytes = file.length()
        if (!file.delete()) return 0L
        pins.remove(file.absolutePath)
        ledger.recordFreed(bytes)
        return bytes
    }

    /**
     * 删除一个**从未进过产物账本**的文件：只删盘、不冲账。
     *
     * 走 [delete] 会调 `ledger.recordFreed`，把 [QuotaLedger] 冲成负数——`out/` 交付目录
     * 与入站中转区的字节从来没被 `reserve/commit` 记过（[clearUploads] 的同款理由）。
     */
    private fun deleteOutsideLedger(file: File): Long {
        val bytes = file.length()
        if (!file.delete()) return 0L
        pins.remove(file.absolutePath)
        return bytes
    }

    private fun hex(): String = ByteArray(PIN_SUFFIX_BYTES).also { random.nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private fun pauseBriefly() {
        runCatching { Thread.sleep(SWEEP_RETRY_PAUSE_MS) }
    }

    companion object {
        val ALLOWED_EXTENSIONS = setOf("png", "jpg", "webp", "mp4", "m4a", "webm", "txt", "json")

        /** 产物名里的随机后缀字节数。与请求标识拼在一起，沙盒侧无法预先占位。 */
        private const val PIN_SUFFIX_BYTES = 4
        private const val CREATE_ATTEMPTS = 4
        private const val QUOTA_ATTEMPTS = 3
        private const val SWEEP_RETRY_PAUSE_MS = 60L

        /** 暂存件超过这个年龄还没落地即为残骸；与响应寿命无关。 */
        private const val STAGING_MAX_AGE_MS = 10L * 60L * 1000L

        /** 入站中转区的总量上限，与产物配额分开设：中转件是沙盒自己放的，不该挤占产物预算。 */
        private const val UPLOAD_TOTAL_BYTES = 64L * 1024L * 1024L

        /** 条目数硬上限：mtime 由同 uid 说了算，只按龄回收能被"未来时间戳"永久绕开。 */
        private const val UPLOAD_MAX_ENTRIES = 128

        /** 递归深度上限。中转区没有正当理由放嵌套目录，加深只是给遍历添负担。 */
        private const val UPLOAD_MAX_DEPTH = 4

        /**
         * 在钉上限。必须不大于响应寿命：比它大的话永远由响应文件先消失来解锁，
         * 这一条兜底就成了摆设，而它存在的理由是「响应压根没写成」。
         */
        val PIN_MAX_AGE_MS = QuotaLedger.RESPONSE_TTL_MS
    }
}
