package interlock.relay.core.interlock

import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.spi.RelayPrefs

/**
 * 当前生效预设的**身份**：全局选择器此刻显示的是哪个条目。
 *
 * 与 40 条档位（[TierStore]）、已保存自定义（[CustomPresetStore]）分开存：档位值本身
 * 反推不出身份——「常用自定义预设」保存的内容完全可能与内置默认配置相同，「使用」
 * 之后标签必须仍是常用自定义预设，而不是按值被改判成「默认配置」。
 *
 * 身份只在写入路径上维护（装配根的 setTier / setAllTiers / applyDefaultPreset /
 * applyCustomPreset / saveCustomPreset 各自落自己的身份），读取侧绝不按值反推：
 * 老升级场景没有身份时做**一次**迁移（按  的旧判据从现值推导并持久化），此后
 * 该判据不再参与。
 *
 * 与档位同一份宿主私有偏好文件，绝不落共享目录（理由见 TierStore 的类注）。
 */
enum class ActivePreset {
    DEFAULT,
    ALL_DENIED,
    ALL_ASK,
    ALL_ALWAYS,
    CUSTOM,

    /** 单独改过某一条、不再与任何预设身份对应的场景。 */
    MIXED,
    ;

    /** 选择器显示用的状态：同一套身份，两套名字各归各的读者。 */
    fun toGlobalConfigState(): GlobalConfigState = when (this) {
        DEFAULT -> GlobalConfigState.DEFAULT
        ALL_DENIED -> GlobalConfigState.ALL_DENIED
        ALL_ASK -> GlobalConfigState.ALL_ASK
        ALL_ALWAYS -> GlobalConfigState.ALL_ALWAYS
        CUSTOM -> GlobalConfigState.CUSTOM
        MIXED -> GlobalConfigState.MIXED
    }

    companion object {

        /** 迁移判据： 旧状态机给出的条目就是迁移后的身份。 */
        fun fromGlobalState(state: GlobalConfigState): ActivePreset = when (state) {
            GlobalConfigState.DEFAULT -> DEFAULT
            GlobalConfigState.ALL_DENIED -> ALL_DENIED
            GlobalConfigState.ALL_ASK -> ALL_ASK
            GlobalConfigState.ALL_ALWAYS -> ALL_ALWAYS
            GlobalConfigState.CUSTOM -> CUSTOM
            GlobalConfigState.MIXED -> MIXED
        }

        /** 单独改一条：全局范围内的能力一律落混合；范围外（特权开关等）不动身份。 */
        fun afterTierChange(current: ActivePreset, id: CapabilityId): ActivePreset =
            if (id in TierPresets.GLOBAL_SCOPE) MIXED else current

        /** 三个「全部×」直接动作的身份就是那个均匀档。 */
        fun afterBulkTier(tier: AccessTier): ActivePreset = when (tier) {
            AccessTier.DENIED -> ALL_DENIED
            AccessTier.ASK -> ALL_ASK
            AccessTier.ALWAYS -> ALL_ALWAYS
        }

        /** 「默认配置」直接动作：即使写出的 40 条与之前完全相同，身份也落回默认配置。 */
        fun afterDefaultApply(): ActivePreset = DEFAULT

        /**
         * 「常用自定义预设 → 使用」：身份落 CUSTOM——哪怕保存的那份与默认配置全等，
         * 用户点的是「使用常用自定义预设」，标签就锁在常用自定义预设上。
         */
        fun afterCustomApply(): ActivePreset = CUSTOM

        /**
         * 保存不套用：保存后的集合与当前 40 条全等时当前身份原样保留（保存的动作
         * 没有改变任何生效档位，不该把「默认配置」说成别的）；否则生效档位与任何
         * 已知身份都对不上——保存进来的这份毕竟没有套用——落混合。
         */
        fun afterCustomSave(current: ActivePreset, savedEqualsApplied: Boolean): ActivePreset =
            if (savedEqualsApplied) current else MIXED
    }
}

/** 当前生效预设身份的持久化。键与档位键、自定义预设键互不相干。 */
class ActivePresetStore(private val prefs: RelayPrefs) {

    private val file = RelayPrefs.FILE_MAIN

    /** 持久化的身份；没存过（老升级场景）或值解不出来时为 null。 */
    fun load(): ActivePreset? =
        prefs.getString(file, KEY, null)?.let { runCatching { ActivePreset.valueOf(it) }.getOrNull() }

    fun save(preset: ActivePreset) {
        prefs.putString(file, KEY, preset.name)
    }

    /**
     * 读取侧的唯一入口：有身份直接用（**此后不再按 40 条值反推**）；没有身份时按
     * 的旧判据从现值推导一次并持久化——迁移只发生这一次，判据此后不再参与。
     */
    fun resolve(
        current: Map<CapabilityId, AccessTier>,
        custom: Map<CapabilityId, AccessTier>?,
    ): ActivePreset {
        load()?.let { return it }
        val migrated = ActivePreset.fromGlobalState(TierPresets.globalState(current, custom))
        save(migrated)
        return migrated
    }

    private companion object {
        const val KEY = "active_preset_v1"
    }
}
