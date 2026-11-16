package interlock.relay.core.interlock

import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.spi.RelayPrefs

/**
 * 「自定义配置」的存储：用户在编辑器里保存下来的那份 40 条普通能力档位表。
 *
 * 与档位本身（[TierStore]）分开存：保存动作**只入库、不套用**——套用永远走显式的
 * 「使用」。没保存过时 [load] 返回 null，「使用」条目据此置灰。
 *
 * 与档位同一份宿主私有偏好文件，绝不落共享目录（理由见 TierStore 的类注）。
 */
class CustomPresetStore(private val prefs: RelayPrefs) {

    private val file = RelayPrefs.FILE_MAIN

    /** 已保存的自定义配置；没保存过或内容不完整（不是全范围 40 条）时为 null。 */
    fun load(): Map<CapabilityId, AccessTier>? =
        prefs.getString(file, KEY, null)?.let(::decode)

    fun hasSaved(): Boolean = load() != null

    /**
     * 保存一份自定义配置。只接受**恰好覆盖 [TierPresets.GLOBAL_SCOPE]** 的表：
     * 半份配置存进去，「使用」就会把没编辑过的条目留在旧档上而界面却报「自定义已套用」。
     * 范围不符时拒绝写入并返回 false。
     */
    fun save(tiers: Map<CapabilityId, AccessTier>): Boolean {
        if (tiers.keys != TierPresets.GLOBAL_SCOPE) return false
        prefs.putString(file, KEY, encode(tiers))
        return true
    }

    private fun encode(tiers: Map<CapabilityId, AccessTier>): String =
        tiers.entries.joinToString(SEPARATOR) { "${it.key.wire}${ASSIGN}${it.value.name}" }

    /** 任一条解不出来或范围不齐一律作 null：半份配置宁可当没存过，也不猜缺的那几条。 */
    private fun decode(raw: String): Map<CapabilityId, AccessTier>? {
        val decoded = raw.split(SEPARATOR).mapNotNull { entry ->
            val parts = entry.split(ASSIGN)
            if (parts.size != 2) return@mapNotNull null
            val id = CapabilityId.fromWire(parts[0]) ?: return@mapNotNull null
            val tier = runCatching { AccessTier.valueOf(parts[1]) }.getOrNull() ?: return@mapNotNull null
            id to tier
        }.toMap()
        return if (decoded.keys == TierPresets.GLOBAL_SCOPE) decoded else null
    }

    private companion object {
        const val KEY = "custom_tier_preset"
        const val SEPARATOR = ";"
        const val ASSIGN = "="
    }
}
