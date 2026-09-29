package com.dshbox.app.sandbox

import android.content.Context
import android.util.Log
import com.dshbox.app.common.Constants
import java.io.File

/**
 * 「DSH官方插件」开关层（overlay）—— 插件面板里那个同名子页的落盘层。
 *
 * 上游把部分官方能力做成"默认关闭"的条目（web profile 下 `disabled: true`，
 * 或仅 desktop 启用）。宿主不改 DSH 源码、也不动 profile 里用户的 `cordis.patch.yml`，
 * 而是用 dsh 官方的 `--patch` 入口（应用在 profile 层之后，按 id 逐键覆盖、后层胜）
 * 覆盖这些条目 —— 这正是上游在 `tool-ralph` 注释里文档化的开启方式。
 *
 * 正文由 [DshOfficialPlugins.ENTRIES] 与用户偏好共同决定：每条写一行 `- id: …` +
 * `name` + **显式的** `disabled: false|true`。显式写出关闭态（而不是省略该行）是有意的：
 * 省略等于"回到上游默认"，一旦上游将来翻转默认值，用户的选择就会被静默推翻。
 *
 * 落点：`.dsh/dshbox/dsh-official-plugin/dsh-official-plugin-overlay.yml`。
 * 消费端 `DefaultSandboxManager.dshOverlayGuestPaths()` 只在该文件存在且非空时把
 * [Constants.DSH_OFFICIAL_PLUGIN_OVERLAY_GUEST_PATH] 作为 `--patch` 传给 dsh。
 *
 * 本层只写「改已有条目的键」这一类行（`- id: …`），不写 `- insert:`（新建条目），
 * 也不碰市场层（`plugin-market/`）与绝对安全模式层（`safe-mode/`）。
 */
object OfficialPluginOverlay {

    private const val TAG = "OfficialPluginOverlay"

    /** 空层正文：本层无内容时的合法写法（**空文件会让 dsh 启动失败**）。 */
    private const val EMPTY_LAYER = "[]\n"

    /** 宿主侧落点（相对 `filesDir/user-data`）。 */
    private fun overlayFile(context: Context): File =
        File(File(context.filesDir, "user-data"), Constants.DSH_OFFICIAL_PLUGIN_OVERLAY_RELATIVE_PATH)

    /** overlay 文件是否在位。 */
    fun overlayFileExists(context: Context): Boolean = overlayFile(context).isFile

    /**
     * 按当前偏好重写覆盖层。应用启动时、以及用户在面板里拨动任一开关后各调一次。
     *
     * 永不抛：写不进去最多是这些能力保持上游默认，不该连累 App 启动或界面操作。
     *
     * @return 写入成功与否
     */
    fun refresh(context: Context): Boolean = runCatching {
        val file = overlayFile(context)
        file.parentFile?.mkdirs()
        file.writeText(render(DshOfficialPlugins.enabledIds(context)), Charsets.UTF_8)
        true
    }.onFailure { Log.w(TAG, "write overlay failed: ${it.message}") }.getOrDefault(false)

    /**
     * 渲染本层正文。
     *
     * 整个文件恒为**单个顶层 YAML 序列**：先出现 `[]` 再追加条目会形成第二个顶层
     * 节点，DSH 解析时抛 `YAMLException` 并静默丢弃整层，所以永远是整体重写。
     */
    internal fun render(enabledIds: Set<String>): String {
        if (DshOfficialPlugins.ENTRIES.isEmpty()) return EMPTY_LAYER
        val sb = StringBuilder()
        for (entry in DshOfficialPlugins.ENTRIES) {
            sb.append("- id: ").append(entry.id).append('\n')
            sb.append("  name: ").append(quoted(entry.name)).append('\n')
            sb.append("  disabled: ").append(entry.id !in enabledIds).append('\n')
        }
        return sb.toString()
    }

    /** YAML 单引号标量；值内的单引号按 YAML 规则写成两个。 */
    private fun quoted(value: String): String = "'" + value.replace("'", "''") + "'"
}
