package interlock.relay.core.interlock

import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.spi.RelayPrefs

/**
 * agent 授权档位的存储。
 *
 * 档位只存宿主私有偏好文件，绝不落任何共享目录：沙盒与宿主同 uid，
 * 放在沙盒可读位置等于让助手改写自己的权限。
 * 「会话内允许」只存内存，助手会话结束即失效，不跨会话残留。
 */
class TierStore(private val prefs: RelayPrefs) {

    private val file = RelayPrefs.FILE_MAIN

    private val sessionGrants = java.util.concurrent.ConcurrentHashMap<CapabilityId, Long>()

    init {
        ensureOnboarding()
    }

    /**
     * 新老用户的分界。状态机可恢复，写一半的引导不会被当成别的状态：
     *
     * - **done**：引导已收口，直接返回——此后用户新增的手动修改、以及未来版本新增
     * 能力上的自身默认值（[defaultTier]）都不被任何预设扫动，也不自动回预设。
     * - **in_progress**：上一次新安装引导写到一半（进程被杀）。无条件把默认配置重新
     * 铺满 40 条（幂等：半成品被完整覆盖），校验通过后写 done——半成品绝不冒充
     * 老用户，否则新用户会停在「部分默认 + 其余旧回退」的混合态。
     * - **无标记**：分界——
     * - **老用户升级**（文件里已有既有键：审计盐或任一档位键）：只写 done，一条
     * 档位不写，现有档位（已存的键与未存键上的旧默认值）原样保留；
     * - **新安装**（全新文件）：先写 in_progress，再把内置默认配置写进 40 条普通
     * 能力，校验 40 条都可读后写 done。
     */
    private fun ensureOnboarding() {
        when (prefs.getString(file, KEY_ONBOARDING, null)) {
            STATE_DONE -> return
            STATE_IN_PROGRESS -> {
                applyPreset(TierPresets.DEFAULT)
                finishOnboarding()
                return
            }
        }
        val upgradedInstall = prefs.getString(file, RelayPrefs.KEY_DIGEST_SALT, null) != null ||
            TierPresets.GLOBAL_SCOPE.any { prefs.getString(file, key(it), null) != null }
        if (upgradedInstall) {
            prefs.putString(file, KEY_ONBOARDING, STATE_DONE)
            return
        }
        prefs.putString(file, KEY_ONBOARDING, STATE_IN_PROGRESS)
        applyPreset(TierPresets.DEFAULT)
        finishOnboarding()
    }

    /** 引导收口：40 条档位全部可读才写 done；写缺了就留在 in_progress，下次启动补齐。 */
    private fun finishOnboarding() {
        val complete = TierPresets.GLOBAL_SCOPE.all { prefs.getString(file, key(it), null) != null }
        if (complete) prefs.putString(file, KEY_ONBOARDING, STATE_DONE)
    }

    /** 一次写入一组档位。只接受全局范围内的能力；范围外的条目（特权能力）一律忽略。 */
    fun applyPreset(tiers: Map<CapabilityId, AccessTier>) {
        tiers.forEach { (id, tier) -> if (id in TierPresets.GLOBAL_SCOPE) setTier(id, tier) }
    }

    fun tierOf(id: CapabilityId): AccessTier =
        prefs.getString(file, key(id), null)
            ?.let { runCatching { AccessTier.valueOf(it) }.getOrNull() }
            ?: defaultTier(id)

    /**
     * 能力上没有已存档位时的回退默认值（之前的旧口径，原样保留）。
     *
     * 之后这份表的读者只剩两类： 引导标记存在但该能力没有键的场景（老用户的
     * 既有档位，以及未来版本**新增能力**只用自身默认值、不被任何预设扫动），和
     * 引导之前的持久化场景。新用户的初始配置由 [ensureOnboarding] 按
     * [TierPresets.DEFAULT] 显式写入，不走这里。
     *
     * `sys.shell` 默认「禁止」：它一次覆盖的就是一批 shell 身份的动词，要开必须由
     * 用户自己在界面上拨一下。`sys.shell.full` 同样默认「禁止」，而且是它仅有的两态
     * 之一：特权开关关着即禁止、开着即完全访问，没有「询问」这一档可落。`device.status`
     * 默认「完全访问」：它只读一组无害的基础数据、不修改任何设置。
     */
    private fun defaultTier(id: CapabilityId): AccessTier = when {
        id == CapabilityId.SYS_SHELL || id == CapabilityId.SYS_SHELL_FULL -> AccessTier.DENIED
        id == CapabilityId.DEVICE_STATUS -> AccessTier.ALWAYS
        else -> DEFAULT_TIER
    }

    /**
     * 档位按用户选定的原样存储。裁决层完全按这一档判定（见 InterlockGate.requiresApproval），
     * 不在写入时替用户改主意——否则界面上点了「完全访问」却回到「询问审批」，
     * 等于界面在撒谎。
     */
    fun setTier(id: CapabilityId, tier: AccessTier) {
        prefs.putString(file, key(id), tier.name)
        if (tier != AccessTier.ALWAYS) sessionGrants.remove(id)
    }

    fun grantForSession(id: CapabilityId, expiresAtMs: Long) {
        sessionGrants[id] = expiresAtMs
    }

    fun sessionGranted(id: CapabilityId, now: Long): Boolean {
        val expires = sessionGrants[id] ?: return false
        if (expires <= now) {
            sessionGrants.remove(id)
            return false
        }
        return true
    }

    /** agent 会话边界：换会话或沙盒重启时一律清空。 */
    fun clearSession() = sessionGrants.clear()

    private fun key(id: CapabilityId) = "$KEY_PREFIX${id.wire.replace('.', '_')}"

    private companion object {
        const val KEY_PREFIX = "tier_"

        /**
         * 引导状态键：`in_progress`（新安装默认配置写到一半，下次启动补齐）、
         * `done`（新装铺默认 / 老用户保留的分界已收口，此后引导不再进入）。
         */
        const val KEY_ONBOARDING = "tier_onboarding_v2"
        const val STATE_IN_PROGRESS = "in_progress"
        const val STATE_DONE = "done"
        val DEFAULT_TIER = AccessTier.ASK
    }
}
