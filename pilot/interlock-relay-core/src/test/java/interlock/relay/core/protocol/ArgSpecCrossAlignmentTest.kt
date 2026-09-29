package interlock.relay.core.protocol

import interlock.relay.core.exec.a11y.A11yBackend
import interlock.relay.core.exec.direct.DirectBackend
import interlock.relay.core.exec.shizuku.ShizukuBackend
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * 预校验表与三张后端键表的跨层对齐。
 *
 * 预校验跑在闸门之前，后端键表（三张 `ARG_SPECS`）跑在闸门之后；后端一律拒未知键，
 * 因此预校验的 allowed 必须逐条包含每张后端表的 allowed —— 少一个键，后端本会接受的
 * 调用就会在预校验被误杀，且发生在用户确认之前，调用方只看到一句「unknown arg」，
 * 查不到是哪一层把契约改窄了。三张表以 internal 暴露供这里核对；后端实例依赖框架，
 * 键表本身是纯数据，JVM 上按注册表的「谁服务这条能力」逐对判即可。
 */
class ArgSpecCrossAlignmentTest {

    /** 后端键表按注册表的后端归属取：注册表新增一条能力，这里自动把它纳入核对。 */
    private val backendAllowed: Map<BackendId, Map<CapabilityId, Set<String>>> = mapOf(
        BackendId.DIRECT to DirectBackend.ARG_SPECS.mapValues { it.value.allowed },
        BackendId.A11Y to A11yBackend.ARG_SPECS.mapValues { it.value.allowed },
        BackendId.SHIZUKU to ShizukuBackend.ARG_SPECS.mapValues { it.value.allowed },
    )

    /** 后端会接受的每个键，预校验都必须放行：探针里只放这一个键，非「unknown arg」即达意。 */
    @Test
    fun prevalidationAcceptsEveryKeyTheServingBackendsAccept() {
        CapabilityRegistry.allDescriptors.forEach { descriptor ->
            descriptor.backends.forEach { backendId ->
                val allowed = backendAllowed.getValue(backendId)[descriptor.id]
                // 注册表里声明了这条后端，后端就必须有这张能力的键表：表缺失时该后端
                // 对参数形状不设防，多余键被静默丢掉、调用跑成另一件事。
                assertTrue("${descriptor.id.wire} 在 ${backendId.name} 缺少键表条目", allowed != null)
                allowed.orEmpty().forEach { key ->
                    val reason = CapabilityArgsSpec.validate(descriptor.id, JSONObject().put(key, 1))
                    assertTrue(
                        "${descriptor.id.wire}：预校验拒绝了后端（${backendId.name}）会接受的键 " +
                            "$key —— ${reason.orEmpty()}",
                        reason == null || !reason.startsWith("unknown arg: $key"),
                    )
                }
            }
        }
    }

    /**
     * nodeId 在后端（NodeSelector）按整数值收下，`1.5` 这类小数与 NaN/Infinity 必须在
     * 预校验就被打回；字符串仍走类型档的那句「must be a number」，与后端措辞同源。
     */
    @Test
    fun nodeIdMustBeAWholeNumberBeforeTheGate() {
        fun target(nodeId: Any): String? =
            CapabilityArgsSpec.validate(CapabilityId.UI_CLICK, JSONObject().put("nodeId", nodeId))

        assertTrue(target(1.5).orEmpty().startsWith("nodeId must be a whole number"))
        assertNull(target(4))
        assertNull(target(4L))
        assertNull(target(4.0))
        assertTrue(target("4").orEmpty().startsWith("nodeId must be a number"))

        // 非有限数进不了 JSONObject.put，而 org.json 的分词器把 NaN/Infinity 字面量收成
        // 字符串 —— 请求正文里真的可能出现，落到类型档按「must be a number」打回：
        // 两个入口都在闸门之前被拒，判据本身另有 isFinite 兜底（Number 通路上防线）。
        fun parsed(raw: String): String? = CapabilityArgsSpec.validate(CapabilityId.UI_CLICK, JSONObject(raw))

        assertTrue(parsed("{\"nodeId\": NaN}").orEmpty().startsWith("nodeId must be a number"))
        assertTrue(parsed("{\"nodeId\": Infinity}").orEmpty().startsWith("nodeId must be a number"))
    }
}
