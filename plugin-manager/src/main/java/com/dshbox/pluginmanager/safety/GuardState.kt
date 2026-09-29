package com.dshbox.pluginmanager.safety

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** 被隔离（启动时加载失败 → 已跳过）的一条记录。 */
data class IsolationRecord(
    /** 我们写进层的条目 id。 */
    val id: String,
    /** 失败行里点到的包名（可能为空：有的失败只给了 id）。 */
    val name: String?,
    /** 失败原因原文（不做翻译，保留现场）。 */
    val reason: String,
    val isolatedAtMs: Long,
)

/**
 * 安全模式的持久状态。
 *
 * @property safetyMode 安全模式开关。默认开启：用户装到坏插件时默认能被救回来，
 *   这是本模块存在的首要理由。
 * @property absoluteMode 绝对安全模式开关（它的停用行在**独占的那一层**里，
 *   状态本身不需要记录回滚数据——关闭 = 删那一层）。
 * @property isolated 当前被隔离的插件。
 * @property roundsUsed 本次开机已经用掉的降级轮次（跨进程重启保留，避免无限重启）。
 *   只有守卫自己在一轮成功后才清零——观察者清零会让"启动成功→随后崩溃"无限循环。
 * @property bootStartedAtMs 本次 DSH 启动的时间戳，用于把日志切成「本次/上次」两段。
 */
data class GuardState(
    val safetyMode: Boolean = true,
    val absoluteMode: Boolean = false,
    val isolated: List<IsolationRecord> = emptyList(),
    val roundsUsed: Int = 0,
    val bootStartedAtMs: Long = 0L,
) {
    companion object {
        /**
         * 一次开机允许的自动隔离轮次上限。
         *
         * 语义是"**跳过出错的插件，把整个 DSH 加载完**"，所以预算按"能跳几个坏插件"来给：
         * 一轮约 20 秒（写层 + 重启 + 判定），4 轮约 80 秒——坏插件通常是同一个失败里的
         * 多条（一次全认出），级联暴露的第二个也能覆盖到。再往上加，用户等不起，
         * 而且每轮都要求"至少新隔离一个条目"（无进展即转绝对安全模式），不会空转。
         */
        const val MAX_ROUNDS = 4
    }
}

/** 一次读-改-写的结果。 */
data class GuardMutation(
    val state: GuardState,
    /** 是否真的落盘成功。为 false 时调用方**不得**把它当成已生效。 */
    val saved: Boolean,
)

/**
 * 安全模式状态的落盘。
 *
 * 文件是我们自己的资产（`.dsh/dshbox/plugin-guard.json`），不进 profile，
 * 所以不会和用户的 DSH 配置混在一起。
 *
 * 两条纪律：
 *
 * 1. **读失败 ≠ 空状态**。文件存在但读不出来（权限、半截写入、磁盘错误）时
 *    [mutate] 一律拒绝写入：否则我们会用一份空状态覆盖掉隔离清单，
 *    下次启动所有坏插件复活——这正好是本模块要防的事。
 * 2. **读-改-写要互斥**。观察者与守卫会同时改状态（清轮次 vs 加轮次），
 *    没有锁就会丢更新。
 */
class GuardStore(private val file: File) {

    private val lock = Any()

    /** 读取状态。文件缺失返回默认值；读失败返回默认值但 [loadOk] 为 false。 */
    fun load(): GuardState = loadInternal().first

    /** 上次 [load] 是否可信。 */
    fun loadOk(): Boolean = loadInternal().second

    private fun loadInternal(): Pair<GuardState, Boolean> {
        if (!file.exists()) return GuardState() to true
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            return GuardState() to false
        }
        if (text.isBlank()) return GuardState() to true
        return try {
            val json = JSONObject(text)
            GuardState(
                safetyMode = json.optBoolean("safetyMode", true),
                absoluteMode = json.optBoolean("absoluteMode", false),
                isolated = json.optJSONArray("isolated").toRecords(),
                roundsUsed = json.optInt("roundsUsed", 0),
                bootStartedAtMs = json.optLong("bootStartedAtMs", 0L),
            ) to true
        } catch (t: Throwable) {
            GuardState() to false
        }
    }

    /** 直接落盘（用于明确的整体覆盖，如重置）。返回是否成功。 */
    fun save(state: GuardState): Boolean = synchronized(lock) { saveLocked(state) }

    /**
     * 读-改-写。
     *
     * 原状态读不出来时**不写**：返回的 `saved = false`，调用方必须按失败处理。
     */
    fun mutate(block: (GuardState) -> GuardState): GuardMutation = synchronized(lock) {
        val (current, ok) = loadInternal()
        val next = block(current)
        if (!ok) return@synchronized GuardMutation(next, saved = false)
        GuardMutation(next, saveLocked(next))
    }

    private fun saveLocked(state: GuardState): Boolean = runCatching {
        val json = JSONObject().apply {
            put("safetyMode", state.safetyMode)
            put("absoluteMode", state.absoluteMode)
            put("isolated", JSONArray().apply {
                state.isolated.forEach { record ->
                    put(JSONObject().apply {
                        put("id", record.id)
                        put("name", record.name ?: JSONObject.NULL)
                        put("reason", record.reason)
                        put("isolatedAtMs", record.isolatedAtMs)
                    })
                }
            })
            put("absoluteSnapshots", JSONArray())
            put("roundsUsed", state.roundsUsed)
            put("bootStartedAtMs", state.bootStartedAtMs)
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp-" + System.nanoTime())
        tmp.writeText(json.toString(2), Charsets.UTF_8)
        val target = file.toPath()
        val source = tmp.toPath()
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (t: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }.isSuccess

    private fun JSONArray?.toRecords(): List<IsolationRecord> {
        if (this == null) return emptyList()
        val out = mutableListOf<IsolationRecord>()
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            out.add(
                IsolationRecord(
                    id = id,
                    name = item.optString("name").ifBlank { null },
                    reason = item.optString("reason"),
                    isolatedAtMs = item.optLong("isolatedAtMs"),
                ),
            )
        }
        return out
    }
}
