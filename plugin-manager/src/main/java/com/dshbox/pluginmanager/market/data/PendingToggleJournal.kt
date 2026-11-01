package com.dshbox.pluginmanager.market.data

import android.util.Log
import com.dshbox.pluginmanager.contract.HostSignals
import java.io.File
import java.util.Locale
import org.json.JSONObject

/**
 * 市场自己拨过、但要等 DSH 下次启动才生效的包。
 *
 * 市场写的是启动参数里的 `--patch` 覆盖层，宿主只在组合时读；安装与 DSH 自身页面
 * 的开关走 profile 与用户补丁层（HMR 即时生效）。这张表把前者单独记下来，避免把
 * "刚在市场上拨的开关"说成已生效。
 *
 * 记录在 DSH 下一次启动后自动过期：读取时与进程日志里最后一条启动标记比对，
 * 早于该标记的记录即视为已兑现。写入失败不影响启停本身（只记一条警告），
 * 代价是该包下次显示为"已停用/已生效"而不是"待重启"。
 */
class PendingToggleJournal(private val file: File) {

    private val lock = Any()

    /** 尚未兑现的包名；读不出或损坏时返回空集（当作"没有待生效的开关"）。 */
    fun pending(): Set<String> = synchronized(lock) { read().keys }

    /** 记一次市场侧的拨动。 */
    fun record(name: String, atMillis: Long = System.currentTimeMillis()): Unit = synchronized(lock) {
        if (name.isBlank()) return
        write(read() + (name to atMillis))
    }

    /** 丢掉某个包的记录（卸载后没有可待生效的开关）。 */
    fun forget(name: String): Unit = synchronized(lock) {
        val current = read()
        if (current.containsKey(name)) write(current - name)
    }

    /** 丢弃早于给定启动时刻的记录；启动时刻读不到时保持不动（宁可多显示一次"待重启"）。 */
    fun pruneStartedBefore(bootMillis: Long?): Unit = synchronized(lock) {
        if (bootMillis == null) return
        val current = read()
        if (current.isEmpty()) return
        val next = current.filterValues { it > bootMillis }
        if (next.size != current.size) write(next)
    }

    private fun read(): Map<String, Long> = runCatching {
        if (!file.isFile) return emptyMap()
        val root = JSONObject(file.readText())
        if (root.optInt("version") != STATE_VERSION) return emptyMap()
        val toggles = root.optJSONObject("toggles") ?: return emptyMap()
        toggles.keys().asSequence()
            .associateWith { toggles.optLong(it, 0L) }
            .filterKeys { it.isNotBlank() }
    }.getOrDefault(emptyMap())

    private fun write(toggles: Map<String, Long>) {
        runCatching {
            val root = JSONObject().put("version", STATE_VERSION)
            val body = JSONObject()
            toggles.forEach { (name, at) -> body.put(name, at) }
            root.put("toggles", body)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp-" + System.nanoTime())
            tmp.writeText(root.toString(), Charsets.UTF_8)
            java.nio.file.Files.move(
                tmp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }.onFailure { Log.w(TAG, "pending toggles could not be written", it) }
    }

    private companion object {
        const val STATE_VERSION = 1
        const val TAG = "PendingToggleJournal"
    }
}

/**
 * DSH 进程日志里最后一次启动的时刻。
 *
 * 只回答"宿主最近一次由本应用启动是何时"，不推断任何插件是否加载成功。
 * 没有启动标记、时间戳读不出时返回 null（调用方按保守一侧处理）。
 */
object BootStamp {

    fun lastBootMillis(text: String): Long? = text.lineSequence()
        .filter { HostSignals.BOOT_MARKER.matches(it.trim()) }
        .lastOrNull()
        ?.let { line -> STAMP.find(line)?.groupValues?.get(1) }
        ?.let { stamp -> runCatching { FORMAT.get().parse(stamp)?.time }.getOrNull() }

    private val STAMP = Regex("""(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})""")

    /** 写入侧用系统默认时区生成该文本，解析也必须用同一时区。 */
    private val FORMAT = object : ThreadLocal<java.text.SimpleDateFormat>() {
        override fun initialValue(): java.text.SimpleDateFormat =
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}
