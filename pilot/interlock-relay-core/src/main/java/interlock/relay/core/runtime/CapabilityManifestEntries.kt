package interlock.relay.core.runtime

import interlock.relay.core.interlock.InterlockGate
import interlock.relay.core.protocol.AccessTier
import org.json.JSONArray
import org.json.JSONObject

/**
 * 助手侧能力清单的单条条目构造。纯函数：输入是装配根已经判定好的状态，
 * 这里只做派生与序列化，不碰 Context、不探测系统。
 *
 * 派生口径与真调用共用同一张判定表（[InterlockGate]）：
 * - `approvalPolicy`：档位对应的审批策略（denied / ask_each_call / none）；
 * - `requiresApproval`：普通调用此刻会不会弹助手审批。档位为「禁止」时这条能力
 * 不可调用，回 false 而不是 true——它是审批口径，不承担"能不能调"的表达
 * （那由 `usable` 与 `approvalPolicy` 各自说）；
 * - `sessionGrantable`：仅「询问审批」档为真。完全访问不需要问，禁止不给问，
 * 两者都拿不到会话授权按钮。
 */
internal object CapabilityManifestEntries {

    fun entry(
        id: String,
        category: String,
        systemPermission: String,
        tier: AccessTier,
        sessionGranted: Boolean,
        usable: Boolean,
        implemented: Boolean,
        minSdk: Int,
        surfaces: List<String>,
        guide: String,
        surface: String?,
        degradeReason: String?,
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("category", category)
        put("systemPermission", systemPermission)
        put("assistantTier", tier.name)
        put("approvalPolicy", InterlockGate.approvalPolicy(tier))
        put(
            "requiresApproval",
            tier != AccessTier.DENIED && InterlockGate.requiresApproval(tier, sessionGranted),
        )
        put("sessionGranted", sessionGranted)
        put("sessionGrantable", tier == AccessTier.ASK)
        put("usable", usable)
        put("implemented", implemented)
        put("minSdk", minSdk)
        put("surfaces", org.json.JSONArray(surfaces))
        put("guide", guide)
        surface?.let { put("surface", it) }
        degradeReason?.let { put("degradeReason", it) }
        if (!implemented) put("reason", "not_implemented")
    }

    /**
     * 清单信封：协议版本、入口脚本、宿主包名、核心版本/构建标识与能力条目数组。
     * 纯函数；后端链路的实时状态由装配根在返回值上另行补挂。
     */
    fun envelope(
        protocol: Int,
        entryScript: String,
        hostPackage: String,
        coreVersion: String,
        coreBuild: Long,
        capabilities: JSONArray,
    ): JSONObject = JSONObject().apply {
        put("protocol", protocol)
        put("entry", entryScript)
        put("host", JSONObject().put("package", hostPackage))
        put("core", JSONObject().apply {
            put("version", coreVersion)
            put("build", coreBuild)
        })
        put("capabilities", capabilities)
    }
}
