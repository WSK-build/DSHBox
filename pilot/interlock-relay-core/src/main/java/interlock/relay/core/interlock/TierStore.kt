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

    fun tierOf(id: CapabilityId): AccessTier =
        prefs.getString(file, key(id), null)
            ?.let { runCatching { AccessTier.valueOf(it) }.getOrNull() }
            ?: defaultTier(id)

    /**
     * 用户没拨过时的档位。
     *
     * `sys.shell` 是唯一一条默认「禁止」的：它一次覆盖的就是一批 shell 身份的动词，
     * 与其他那条「逐次弹框」的把关不在同一档。要开必须由用户自己在界面上拨一下，
     * 不随默认值走进来 —— 表内的只读动词也要等这一步之后才谈得上可用。
     */
    private fun defaultTier(id: CapabilityId): AccessTier =
        if (id == CapabilityId.SYS_SHELL) AccessTier.DENIED else DEFAULT_TIER

    /**
     * 档位按用户选定的原样存储。不可逆能力的「每次必问」由裁决层按上限强制
     * （见 InterlockGate.requiresApproval），不在写入时替用户改主意——
     * 否则界面上点了「完全访问」却回到「询问审批」，等于界面在撒谎。
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
        val DEFAULT_TIER = AccessTier.ASK
    }
}
