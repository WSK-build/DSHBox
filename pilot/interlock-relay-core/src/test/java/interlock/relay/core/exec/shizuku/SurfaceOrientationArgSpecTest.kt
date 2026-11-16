package interlock.relay.core.exec.shizuku

import interlock.relay.core.protocol.BackendId
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.CapabilityRegistry
import interlock.relay.core.protocol.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * `orientation` 参数必须穿过 [ShizukuBackend.execute] 的
 * `argsError` 键表检查（原有后端 ArgSpec 漏了这个键，预校验放行的调用在后端
 * 被 "unknown arg: orientation" 拒掉，displayAction 的 orientSize/orientationError
 * 永不执行）。
 *
 * `argsError` 与后端键表是同一条读取路径：`execute()` 入口第一查就是
 * `argSpec(call.descriptor.id)`（缺键回 E_TRANSPORT_MALFORMED），因此这里的用例
 * 分三层钉：
 * 1. `argSpec(SURFACE_VIRTUAL)` 的 allowed 含 `orientation`（execute 级判据本体）；
 * 2. 预校验（CapabilityArgsSpec）与后端键表**逐键相等**——缺键/多键都跨层错位；
 * 3. 注册表声明了这条后端，两表都有条目。
 */
class SurfaceOrientationArgSpecTest {

    /** execute() 的 argsError 读取的正是这张键表（同一函数同一集合）。 */
    private val backendSpec: ShizukuBackend.ArgSpec = ShizukuBackend.ARG_SPECS.getValue(CapabilityId.SURFACE_VIRTUAL)

    private val descriptor = CapabilityRegistry.allDescriptors.first { it.id == CapabilityId.SURFACE_VIRTUAL }

    @Test
    fun backendKeyTableAcceptsOrientation() {
        // argsError 的判据：`key !in spec.allowed` → E_TRANSPORT_MALFORMED。
        assertTrue(
            "后端键表必须放行 orientation（MAJOR-1：缺键时预校验放行的调用在后端被 unknown arg 拒掉）",
            "orientation" in backendSpec.allowed,
        )
        // 有键名没键表条目不行：argsError 的判据读取的正是 companion 里的 ARG_SPECS
        // （argSpec(id) = ARG_SPECS[id]，同一函数同一集合）。
        assertEquals(backendSpec.allowed, ShizukuBackend.ARG_SPECS[CapabilityId.SURFACE_VIRTUAL]?.allowed)
    }

    @Test
    fun prevalidationAndBackendTablesAgreeKeyForSurfacaVirtual() {
        val protocolKeys = interlock.relay.core.protocol.CapabilityArgsSpec
            .allowedKeysOf(CapabilityId.SURFACE_VIRTUAL)
        assertNotNull("预校验表必须有 SURFACE_VIRTUAL 条目", protocolKeys)
        // 逐键相等：orientation 两层都要有（的根因就是只有一层有）。
        assertEquals(protocolKeys, backendSpec.allowed)
    }

    @Test
    fun registryDeclaresShizukuForSurfaceVirtual() {
        // 跨层对齐只在「预校验 ⇄ 同一条后端键表」之间成立：注册表必须声明这条配对。
        assertTrue(
            "SURFACE_VIRTUAL 必须声明 Shizuku 后端，跨层对齐才有对象",
            descriptor.backends.contains(BackendId.SHIZUKU),
        )
    }

    /** 白名单形状（execute 级判据的另一侧）：displayAction 里的 orientationError 同口径。 */
    @Test
    fun orientationWhitelistAcceptsCaseInsensitiveValues() {
        val good = JSONObject("""{"action":"create","orientation":"LandScape"}""")
        val bad = JSONObject("""{"action":"create","orientation":"diagonal"}""")
        val blank = JSONObject("""{"action":"create"}""")
        assertNull(ShizukuBackend.orientationError(good))
        assertNull(ShizukuBackend.orientationError(blank))
        val reason = ShizukuBackend.orientationError(bad)
        assertTrue("非白名单值回 malformed 短句", reason.orEmpty().contains("portrait"))
    }
}
