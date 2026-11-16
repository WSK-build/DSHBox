package dshbox.adapter.ui

import androidx.annotation.StringRes
import dshbox.adapter.R
import interlock.relay.core.interlock.GlobalConfigState
import interlock.relay.core.protocol.AccessTier

/**
 * 全局配置状态 → 词条。状态来自内核单独持久化的预设身份（RelayContainer.globalConfigState），
 * 这里只按结果取词；抽成纯函数供 JVM 单测钉住六档各有各的词、不与单条档位的措辞混用。
 */
@StringRes
fun globalConfigLabelRes(state: GlobalConfigState): Int = when (state) {
    GlobalConfigState.ALL_DENIED -> R.string.pilot_tier_all_denied
    GlobalConfigState.ALL_ASK -> R.string.pilot_tier_all_ask
    GlobalConfigState.ALL_ALWAYS -> R.string.pilot_tier_all_always
    GlobalConfigState.DEFAULT -> R.string.pilot_config_default
    GlobalConfigState.CUSTOM -> R.string.pilot_config_custom
    GlobalConfigState.MIXED -> R.string.pilot_config_unsaved
}

/**
 * 全局选择器下拉里的**五个条目动作**：前四个是直接动作（默认配置 + 三个「全部×」，
 * 点选即整体写档位；[tier] 非空者为「全部×」，由界面转发批量拨档），第五个是常用
 * 自定义预设（「配置」「使用」两个动作挂它左侧，「配置」只是编辑/保存这组固定配置
 * 的入口，不是另一个层级）。条目形状与词条只有这一处出处：
 * 界面按枚举铺下拉，测试按枚举驱动映射——不再各自手抄一份。
 */
enum class GlobalConfigAction(val tier: AccessTier?) {
    DEFAULT(null),
    ALL_DENIED(AccessTier.DENIED),
    ALL_ASK(AccessTier.ASK),
    ALL_ALWAYS(AccessTier.ALWAYS),
    CUSTOM(null),
}

/** 五个条目各自的词条；与 [globalConfigLabelRes] 共用同一批字符串资源。 */
@StringRes
fun globalConfigActionLabel(action: GlobalConfigAction): Int = when (action) {
    GlobalConfigAction.DEFAULT -> R.string.pilot_config_default
    GlobalConfigAction.ALL_DENIED -> R.string.pilot_tier_all_denied
    GlobalConfigAction.ALL_ASK -> R.string.pilot_tier_all_ask
    GlobalConfigAction.ALL_ALWAYS -> R.string.pilot_tier_all_always
    GlobalConfigAction.CUSTOM -> R.string.pilot_config_custom
}
