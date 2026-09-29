package interlock.relay.core.storage

import android.os.StatFs
import interlock.relay.core.runtime.wallNow
import java.io.File

/**
 * 存储配额账本。
 *
 * 四条纪律：
 * 1. 一切变更在锁内完成并同步落盘，避免并发丢更新与半新半旧的持久化内容；
 * 2. 记账按分配块取整，与「应用存储」口径一致，避免长期低估；
 * 3. 探测失败按「无空间」处理（拒绝写入），不放行——目录不可用的场景正是需要拦的时候；
 * 4. 写入方先以 [reserve] 占住预算、成功后以 [commit] 按实际字节入账、失败以 [release] 退还。
 *    只判不占的余额检查下，两个并发写入方会看到同一份可用空间；预留把先判后写变成先到先得。
 */
class QuotaLedger(
    private val file: File,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    /**
     * 未提交预留的句柄。对调用方不透明：只原样交回 [commit] 或 [release]。
     * 序号用于识别句柄是否仍有效，重复提交与重复释放都只生效一次。
     */
    data class Reservation(internal val bytes: Long, internal val seq: Long)

    private val lock = Any()

    var totalBytes: Long = 0L
        private set

    var artifactCount: Int = 0
        private set

    /** 已预留未提交的字节数（按块取整口径）。只活在内存里，进程重启后随磁盘对账一并消失。 */
    private var reservedBytes = 0L
    private val reservations = HashMap<Long, Long>()
    private var nextReservationSeq = 0L

    /**
     * 账本最近一次落盘是否成功。失败不抛出、只翻此标记：记账主路径不能因持久化故障中断，
     * 但「内存读数还有持久依据」必须如实上报——落盘退化期间继续把配额读数当准确值承诺并不成立。
     */
    @Volatile
    var persistHealthy: Boolean = true
        private set

    /** 启动时必须先对账：账本丢失或半截时以磁盘为准，不静默归零。 */
    fun load(onCorrupted: (String) -> Unit = {}): Boolean {
        if (!file.isFile) return true
        val text = runCatching { file.readText() }.getOrElse {
            onCorrupted("quota ledger unreadable")
            return false
        }
        val map = text.lineSequence().mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
        }.toMap()
        val total = map[KEY_TOTAL]?.toLongOrNull()
        val count = map[KEY_COUNT]?.toIntOrNull()
        if (total == null || count == null || total < 0) {
            onCorrupted("quota ledger malformed, reconcile from disk required")
            return false
        }
        synchronized(lock) {
            totalBytes = total
            artifactCount = count
        }
        return true
    }

    fun recordWritten(bytes: Long) = mutate(charged(bytes)) { artifactCount += 1 }

    fun recordFreed(bytes: Long) = mutate(-charged(bytes)) { artifactCount = (artifactCount - 1).coerceAtLeast(0) }

    /**
     * 占住 [bytes] 的预算，返回句柄；余额不足返回 null。
     * 只动内存、不落盘：预留是短暂状态，落盘账本只记录已成事实的入账，
     * 进程重启后未了结的预留由磁盘对账兜底。
     */
    fun reserve(bytes: Long): Reservation? {
        synchronized(lock) {
            val need = charged(bytes)
            if (usableLocked() < need) return null
            val seq = nextReservationSeq++
            reservations[seq] = need
            reservedBytes += need
            return Reservation(need, seq)
        }
    }

    /** 撤销预留并按实际字节入账（与 [recordWritten] 同一语义）。句柄已失效时忽略，避免重复入账。 */
    fun commit(reservation: Reservation, actualBytes: Long) {
        synchronized(lock) {
            val pledged = reservations.remove(reservation.seq) ?: return
            reservedBytes -= pledged
            mutate(charged(actualBytes)) { artifactCount += 1 }
        }
    }

    /** 撤销预留且不入账：写入没能发生，占住的预算必须如数退还。句柄已失效时忽略。 */
    fun release(reservation: Reservation) {
        synchronized(lock) {
            val pledged = reservations.remove(reservation.seq) ?: return
            reservedBytes -= pledged
        }
    }

    private fun mutate(delta: Long, extra: () -> Unit) {
        synchronized(lock) {
            totalBytes = (totalBytes + delta).coerceAtLeast(0L)
            extra()
            persistLocked()
        }
    }

    /** 以磁盘为准重算，用于启动对账与手动清理之后。 */
    fun reconcileFromDisk(artifactsDir: File) {
        val files = artifactsDir.listFiles()?.filter { it.isFile } ?: emptyList()
        synchronized(lock) {
            totalBytes = files.sumOf { charged(it.length()) }
            artifactCount = files.size
            persistLocked()
        }
    }

    fun overQuota(): Boolean = synchronized(lock) { totalBytes + reservedBytes > maxBytes }

    fun usableBytes(): Long = synchronized(lock) { usableLocked() }

    /** 未提交预留同样占着预算：漏算会把同一份余额允诺给两个并发写入方。 */
    private fun usableLocked(): Long = (maxBytes - totalBytes - reservedBytes).coerceAtLeast(0L)

    /** 预留底线也暴露给界面，否则「占用 X / 上限 Y」与实际判定不是同一来源。 */
    fun deviceHasRoom(dir: File, bytes: Long): Boolean = runCatching {
        StatFs(dir.absolutePath).availableBytes > charged(bytes) + RESERVED_FLOOR_BYTES
    }.getOrDefault(false)

    /**
     * 落盘失败不抛出，只把 [persistHealthy] 翻成退化，下一次成功落盘再复位；
     * rename 失败退到直接覆写的兜底保持原样。
     */
    private fun persistLocked() {
        val snapshot = "$KEY_TOTAL=$totalBytes\n$KEY_COUNT=$artifactCount\n$KEY_UPDATED=${wallNow()}\n"
        persistHealthy = runCatching {
            file.parentFile?.mkdirs()
            val temp = File.createTempFile("quota-", ".tmp", file.parentFile)
            temp.writeText(snapshot)
            if (!temp.renameTo(file)) {
                file.writeText(snapshot)
            }
            temp.delete()
        }.isSuccess
    }

    private fun charged(bytes: Long): Long = if (bytes <= 0L) 0L else {
        ((bytes + BLOCK_BYTES - 1) / BLOCK_BYTES) * BLOCK_BYTES
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 200L * 1024 * 1024
        const val ARTIFACT_TTL_MS = 24L * 60L * 60L * 1000L
        const val ORPHAN_REQUEST_TTL_MS = 10L * 60L * 1000L
        const val RESPONSE_TTL_MS = 30L * 60L * 1000L
        const val RESERVED_FLOOR_BYTES = 64L * 1024 * 1024
        const val BLOCK_BYTES = 4096L
        private const val KEY_TOTAL = "totalBytes"
        private const val KEY_COUNT = "artifactCount"
        private const val KEY_UPDATED = "updatedAtWallClock"
    }
}
