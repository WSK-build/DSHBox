package interlock.relay.core.runtime

import interlock.relay.core.protocol.AccessTier
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 助手侧能力清单的条目契约。清单是助手判断「这一条现在会不会弹」的唯一依据，
 * 字段缺一个或口径歪一档，助手就会按错误的审批预期反复重试或绕过确认。
 *
 * 条目构造是纯函数（[CapabilityManifestEntries]），本测试直接对账序列化结果；
 * 容器侧只负责喂已判定的状态，不再有第二份字段拼装。
 */
class CapabilityManifestEntriesTest {

    private fun entry(
        tier: AccessTier,
        sessionGranted: Boolean = false,
    ) = CapabilityManifestEntries.entry(
        id = "test.cap",
        category = "data",
        systemPermission = "GRANTED",
        tier = tier,
        sessionGranted = sessionGranted,
        usable = tier != AccessTier.DENIED,
        implemented = true,
        minSdk = 29,
        surfaces = listOf("foreground"),
        guide = "NOT_REQUIRED",
        surface = "foreground",
        degradeReason = null,
    )

    @Test
    fun askTierEntryReportsTheFullApprovalContract() {
        val json = entry(AccessTier.ASK)
        assertEquals("ASK", json.getString("assistantTier"))
        assertEquals("ask_each_call", json.getString("approvalPolicy"))
        assertTrue(json.getBoolean("requiresApproval"))
        assertFalse(json.getBoolean("sessionGranted"))
        assertTrue(json.getBoolean("sessionGrantable"))
        assertTrue(json.getBoolean("usable"))
        assertTrue(json.getBoolean("implemented"))
        assertEquals(29, json.getInt("minSdk"))
        assertEquals(listOf("foreground"), json.getJSONArray("surfaces").asStringList())
        assertEquals("NOT_REQUIRED", json.getString("guide"))
        assertEquals("foreground", json.getString("surface"))
        assertFalse("reason only on unimplemented entries", json.has("reason"))
    }

    @Test
    fun fullAccessEntryNeverAsksAndOffersNoSessionButton() {
        val json = entry(AccessTier.ALWAYS)
        assertEquals("none", json.getString("approvalPolicy"))
        assertFalse(json.getBoolean("requiresApproval"))
        // 完全访问没有可授予的会话档：按钮为真会把「不再询问」伪装成「已授权一次会话」。
        assertFalse(json.getBoolean("sessionGrantable"))
        assertFalse(json.getBoolean("sessionGranted"))
    }

    @Test
    fun deniedEntryIsNotCallableAndIsNotDescribedAsPendingApproval() {
        val json = entry(AccessTier.DENIED)
        assertEquals("denied", json.getString("approvalPolicy"))
        // 「禁止」是不可调用，不是「需要审批」：requiresApproval 表达审批口径，
        // 「能不能调」由 approvalPolicy 与 usable 各自说。
        assertFalse(json.getBoolean("requiresApproval"))
        assertFalse(json.getBoolean("sessionGrantable"))
        assertFalse(json.getBoolean("usable"))
    }

    @Test
    fun entriesNoLongerCarryAConsentScreenField() {
        // 档位是审批的唯一开关，清单里不再有「目标落在系统授权界面就必问」的独立字段；
        // 旧字段残留会让机器读者以为仍有一层与档位并列的拦截。
        for (tier in AccessTier.entries) {
            assertFalse("$tier", entry(tier).has("consentScreenAlwaysAsks"))
        }
    }

    @Test
    fun sessionGrantSilencesTheAskTierWithinTheReportedContract() {
        val json = entry(AccessTier.ASK, sessionGranted = true)
        assertTrue(json.getBoolean("sessionGranted"))
        assertFalse(json.getBoolean("requiresApproval"))
        assertTrue(json.getBoolean("sessionGrantable"))
    }

    /** 旧上限字段不再出现在对外清单里：它的值不生效，留在输出里只会误导机器读者。 */
    @Test
    fun legacyCeilingFieldIsGone() {
        for (tier in AccessTier.entries) {
            assertFalse("ceiling in entry for $tier", entry(tier).has("ceiling"))
        }
    }

    @Test
    fun unimplementedEntriesCarryTheReasonAndOptionalFieldsStayOptional() {
        val json = CapabilityManifestEntries.entry(
            id = "test.cap",
            category = "control",
            systemPermission = "RUNTIME_ASKABLE",
            tier = AccessTier.ASK,
            sessionGranted = false,
            usable = false,
            implemented = false,
            minSdk = 33,
            surfaces = listOf("foreground", "trusted-display"),
            guide = "NO_SETTINGS_PAGE",
            surface = null,
            degradeReason = "E_SURFACE_MODE_REQUIRED",
        )
        assertEquals("not_implemented", json.getString("reason"))
        assertNull(json.optString("surface", null))
        assertEquals("E_SURFACE_MODE_REQUIRED", json.getString("degradeReason"))
        assertEquals(listOf("foreground", "trusted-display"), json.getJSONArray("surfaces").asStringList())
    }

    /** 信封层：core 标识是判定方版本的对账依据，必须随清单落盘。 */
    @Test
    fun envelopeCarriesProtocolEntryHostAndCoreBuildIdentifier() {
        val envelope = CapabilityManifestEntries.envelope(
            protocol = 1,
            entryScript = "/opt/pilot/bin/pilot",
            hostPackage = "com.example.host",
            coreVersion = "1.5.1",
            coreBuild = 10L,
            capabilities = JSONArray(),
        )
        assertEquals(1, envelope.getInt("protocol"))
        assertEquals("/opt/pilot/bin/pilot", envelope.getString("entry"))
        assertEquals("com.example.host", envelope.getJSONObject("host").getString("package"))
        assertEquals("1.5.1", envelope.getJSONObject("core").getString("version"))
        assertEquals(10L, envelope.getJSONObject("core").getLong("build"))
        assertTrue(envelope.has("capabilities"))
    }

    private fun JSONArray.asStringList(): List<String> =
        (0 until length()).map { getString(it) }
}
