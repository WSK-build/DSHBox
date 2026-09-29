package com.dshbox.pluginmanager.safemode

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.pluginmanager.core.GuestCommandRunner
import com.dshbox.pluginmanager.core.GuestCommands
import com.dshbox.pluginmanager.core.PluginPaths
import com.dshbox.pluginmanager.layer.HostInfrastructure
import com.dshbox.pluginmanager.layer.LayerPatchEntry
import com.dshbox.pluginmanager.layer.PluginLayer

/** 合成配置里的一个条目。 */
data class ComposedEntry(val id: String, val name: String)

/**
 * 合成配置的解析结果。
 *
 * @property unnamedIds 有 `- id:` 但没有同级 `name:` 的条目。它们**不能**被判成
 *   第三方（我们不知道它是谁），但必须报出来——静默丢弃会让"不加载任何第三方
 *   插件"这个承诺变得无法核实。
 */
data class ComposedParse(
    val entries: List<ComposedEntry>,
    val unnamedIds: List<String>,
)

/**
 * 绝对安全模式：**不加载任何第三方插件**也能把 DSH 拉起来。
 *
 * 实现方式：让 DSH 自己把合成后的配置树打印出来（`--dump-config`，静态合成、
 * 不启动服务），挑出第三方条目，再往**本模式独占的那一层**里整层写 `disabled: true`；
 * 关闭时删掉那一层。选这条路的理由：
 *
 * - 不碰用户的 `cordis.patch.yml`，也不需要临时改 profile 的 `package.json`；
 * - 我们的层在 profile 层之后应用，能覆盖下层已有的配置；
 * - `disabled: true` 的跨层生效已在真机上验证过（用同一条 `--dump-config` 复核）；
 * - **独占一层**：关掉本模式只是删文件，绝不会把守卫/市场写在另一层里的停用行翻回去
 *   （共享一份文件时真机出过事故：隔离好的坏插件被回滚成启用，DSH 再也起不来）。
 *
 * 未验证的部分：**bundle 类条目**是否仅靠 patch 行的 `disabled` 就能完全停掉
 * （上游遇到"停用载体"时会额外把 bundle 从 `dsh.profile.bundles` 摘掉）。
 * 这里先只写层，并在返回值里如实反映实际写入的条目，不假装一定生效。
 */
class AbsoluteSafeMode(
    private val guest: GuestCommandRunner,
    private val layer: PluginLayer,
    private val profile: String,
    /** 安装层实际自带的包名（[com.dshbox.pluginmanager.core.HostPackages] 推导）。 */
    private val installedPackages: Set<String> = emptySet(),
    /** 条目集合缓存（`--dump-config` 约 10~30 秒，profile 未变时无需重跑）。 */
    private val targetsCache: AbsoluteTargetsCache? = null,
    /** profile 指纹来源（清单与 node_modules 的 mtime）；未提供则缓存不生效。 */
    private val profileFingerprint: (() -> AbsoluteTargetsCache.Fingerprint)? = null,
) {

    init {
        // profile 会进 shell 命令，非法值必须在这里就被挡住（构造即失败，
        // 而不是等到某次调用才把一串命令注进去）。
        require(PluginPaths.isValidProfileName(profile)) {
            "非法的 profile 名：$profile"
        }
    }

    /**
     * 读取 DSH 合成后的条目清单。
     *
     * 命令带 `timeout`，避免 DSH 因环境问题卡住时把界面拖死。
     */
    suspend fun composedEntries(): AppResult<ComposedParse> {
        val result = guest.run(GuestCommands.dshDumpConfig(profile))
        if (!result.ok) {
            return AppResult.Failure(
                AppError(
                    code = "DUMP_CONFIG_FAILED",
                    message = result.text.takeLast(600),
                ),
            )
        }
        val parsed = parseComposedEntries(result.output)
        if (parsed.entries.isEmpty() && parsed.unnamedIds.isEmpty()) {
            return AppResult.Failure(
                AppError(
                    code = "DUMP_CONFIG_EMPTY",
                    message = "未能从合成配置里解析出任何条目",
                ),
            )
        }
        return AppResult.Success(parsed)
    }

    /** 只取第三方条目（宿主自带的与 `cordis:` 合成条目不碰，拿不准的也不碰）。 */
    fun thirdPartyIds(entries: List<ComposedEntry>): List<String> =
        entries
            // 双保险：不可写形态的 id 一律不进候选。解析层已经过滤过，
            // 但这里再挡一次——写层失败会让整次操作白做，代价比漏一个条目大。
            .filter { PluginLayer.isValidId(it.id) }
            .filter { HostInfrastructure.isThirdParty(it.name, installedPackages) }
            .filterNot { HostInfrastructure.isProtected(it.id, it.name, installedPackages) }
            .map { it.id }
            .distinct()

    /**
     * 停用层是否已经在位（"现在不加载任何第三方插件"这件事已经写进磁盘）。
     *
     * 守卫每次启动都会问一次：层在就不必再跑一遍 10~30 秒的 `--dump-config`。
     */
    fun isApplied(): Boolean = layer.exists()

    /**
     * 缓存当前条目集合对应的第三方 id；profile 未变时下次可跳过 `--dump-config`。
     */
    fun rememberTargets(ids: List<String>): Boolean {
        val cache = targetsCache ?: return false
        val current = profileFingerprint?.invoke() ?: return false
        return cache.write(current, ids)
    }

    /**
     * 读取缓存里的第三方 id；profile 指纹不一致（或缓存不可用）时返回 null，
     * 调用方必须重跑 `--dump-config`。
     */
    fun cachedTargets(): List<String>? {
        val cache = targetsCache ?: return null
        val current = profileFingerprint?.invoke() ?: return null
        return cache.read(current)
    }

    /**
     * 写入停用行：**整层重写成这批 id**（该层由本模式独占，见 [PluginPaths.absoluteOverlayFile]）。
     *
     * 为什么不做"记下改动前状态、关闭时回滚"：那份记录只要过期一次，回滚就会覆盖
     * 别人写的行。历史故障——守卫隔离了 `bad-b`（写 `disabled: true`），随后关闭绝对安全
     * 模式时按过期快照把它翻回 `disabled: false`，界面还显示"已隔离"，而 DSH 每次启动
     * 都加载这个崩溃插件起不来。独占一层之后不存在这种交互：开 = 写这一层，关 = 删这一层。
     *
     * 层解析不完整或落盘失败时返回失败，调用方必须把它当失败处理。
     */
    fun apply(ids: List<String>): AppResult<Unit> {
        val rejected = ids.filterNot { PluginLayer.isValidId(it) }
        if (rejected.isNotEmpty()) {
            return AppResult.Failure(
                AppError(
                    code = "LAYER_ID_INVALID",
                    message = "条目 id 不是可写的裸标量：${rejected.joinToString(", ")}",
                ),
            )
        }
        if (ids.isEmpty()) {
            // 没有第三方条目 = 这一层不需要存在；删掉它，别留一份空层让启动多传一个参数。
            return clear()
        }
        // 这一层是我们独占的，别人不会写它；所以读不出来（半截写入、权限问题）时
        // 可以直接备份后重置，而不是像共享层那样只能放弃——否则绝对安全模式会
        // 因为一个坏文件彻底用不了，那正是它要保护的场景。
        if (!layer.read().parseOk) {
            if (!layer.backupAndReset()) {
                return AppResult.Failure(
                    AppError(
                        code = "LAYER_UNPARSABLE",
                        message = "停用层内容异常且无法备份重置，未做改动",
                        recoverable = false,
                    ),
                )
            }
        }
        // 读-改-写整体走层的写锁（与其它写者串行）；这里是**整层替换**，
        // 因此结果只可能是这批 id。
        val ok = layer.withPatches { _ ->
            ids.distinct().map { LayerPatchEntry(id = it, disabled = true) } to true
        } != null
        return if (ok) AppResult.Success(Unit) else AppResult.Failure(
            AppError(
                code = "LAYER_UNPARSABLE",
                message = "层文件无法解析或写入失败，已放弃改动以免破坏它",
                recoverable = false,
            ),
        )
    }

    /** 解除绝对安全模式：删掉我们独占的那一层。 */
    fun clear(): AppResult<Unit> =
        if (layer.erase()) {
            AppResult.Success(Unit)
        } else {
            AppResult.Failure(AppError(code = "LAYER_WRITE_FAILED", message = "层文件删除失败"))
        }

    /**
     * 解析 `--dump-config` 的输出。
     *
     * 只认「`- id:` 与**同级缩进 +2** 的 `name:`」这一对，并且**只把缩进 0 或 4 的
     * `- id:` 当作 loader 条目**：合成树里 `config:` 块内也有大量 `- id:`（例如
     * `llm-pi-ai` 的模型清单是 `inclusionai/ling-3.0-flash-fin:free` 这样的模型 id），
     * 把它们当成插件条目会得到一堆不可写的 id，进而让整次操作失败（真机上就踩过）。
     *
     * 同时跳过 id 不是可写形态的候选：它们本来也进不了我们的层。
     */
    fun parseComposedEntries(lines: List<String>): ComposedParse {
        val entries = mutableListOf<ComposedEntry>()
        val unnamed = mutableListOf<String>()
        var pendingId: String? = null
        var pendingIndent = -1

        lines.forEach { raw ->
            val line = raw.trimEnd('\r')
            val idMatch = ID_RE.find(line)
            if (idMatch != null) {
                pendingId?.let { unnamed.add(it) }
                pendingId = null
                val indent = indentOf(line)
                val id = PluginLayer.unquote(idMatch.groupValues[1])
                // 只有顶层（0）与插入块内（4）的条目才是 loader 条目；
                // 更深的 `- id:` 属于配置数据，且其 id 往往含冒号、写不进层。
                if (indent == 0 || indent == INSERT_ITEM_INDENT) {
                    if (PluginLayer.isValidId(id)) {
                        pendingId = id
                        pendingIndent = indent
                    }
                }
                return@forEach
            }
            val nameMatch = NAME_RE.find(line)
            // 字段行比条目行多缩进 2 格：顶层条目是 `- id:` + 2 空格字段，
            // 插入块内是 4 空格条目 + 6 空格字段。按"恰好 +2"比对才能把
            // `config:` 里的同名键（缩进更深）排除掉。
            if (nameMatch != null && pendingId != null && indentOf(line) == pendingIndent + 2) {
                val name = PluginLayer.unquote(nameMatch.groupValues[1])
                if (name.isNotBlank()) {
                    entries.add(ComposedEntry(pendingId!!, name))
                    pendingId = null
                }
            }
        }
        pendingId?.let { unnamed.add(it) }
        return ComposedParse(entries, unnamed)
    }

    /**
     * 行的缩进宽度。
     *
     * 必须自己数前导空白：正则的 `\s*` 会把缩进吃进匹配里，`match.range.first`
     * 永远是 0——用它比同级缩进等于没比，`config:` 块里的 `name:` 会被误当成包名。
     */
    private fun indentOf(line: String): Int {
        var count = 0
        while (count < line.length && (line[count] == ' ' || line[count] == '\t')) count++
        return count
    }
    private companion object {
        /** 顶层 `- id: X` 与插入块 `    - id: X` 都接受；缩进由调用处比对。 */
        val ID_RE = Regex("""^\s*- id: *(.+?) *$""")
        val NAME_RE = Regex("""^\s*name: *(.+?) *$""")

        /** 插入块内条目的缩进（分层 patch 层里是 4 空格）。 */
        const val INSERT_ITEM_INDENT = 4
    }
}
