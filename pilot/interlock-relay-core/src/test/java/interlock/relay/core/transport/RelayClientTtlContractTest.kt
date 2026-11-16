package interlock.relay.core.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 录音等待预算的**跨层契约**：120 秒内容上限之外，等待方（沙盒入口脚本的 call TTL
 * 上限、插件本层的入口区间、这里的宿主有效期）必须都留出收尾余量。任何一侧仍按内容
 * 上限夹紧，满时长录音都会在产物落地之前被那一侧掐断——而单元测试各测各的模块，
 * 谁也看不见别人改没改，所以这里直接读四处源文件逐项比对，而不是各自维护一份"应该
 * 是多少"的副本。
 *
 * 与 [ResourceParityTest] 同一条纪律：找不到源文件就当场失败，不静默跳过。
 */
class RelayClientTtlContractTest {

    /** 录音内容上限（AudioCapture 的参数上界）：等待方的下界参照。 */
    private val contentCapMs = 120_000L

    /** 等待方的统一上限：内容 + 收尾余量。 */
    private val waitingCapMs = 130_000L

    @Test
    fun `入口脚本的 call TTL 上限与宿主有效期同表且高于内容上限`() {
        val script = sourceFile("pilot/interlock-relay-core/src/main/assets/relay/relay-client.cjs").readText()
        assertEquals(waitingCapMs, constantOf(script, "CALL_MAX_TTL_MS"))
        assertEquals(1_000L, constantOf(script, "CALL_MIN_TTL_MS"))
        // help 文案与夹紧实现出自同一对常量：文案写 120000 而实现放 130000 的错位由这里挡住。
        assertTrue(
            "入口 help 文案的 call 区间应写 1000..130000",
            script.contains("how long this process waits, 1000..130000"),
        )
        // ask 是另一条通路（等用户答复），上限独立在 120000：契约只锁 call，不锁 ask。
        assertEquals(120_000L, constantOf(script, "ASK_MAX_TIMEOUT_MS"))
        assertEquals(waitingCapMs, EnvelopeCodec.MAX_TTL_MS)
    }

    @Test
    fun `插件本层的入口区间与 audio 档位同表`() {
        val pilot = sourceFile("plugin-manager/dshbox-plugins/assets/plugins/mobile-pilot/plugin/lib/pilot.js").readText()
        assertEquals(waitingCapMs, constantOf(pilot, "ENTRY_TIMEOUT_MAX_MS"))
        // audio.capture 的档位行必须写到放宽后的上限，写回内容上限的旧档由这里挡住。
        assertTrue(
            "插件 TIMEOUTS 表的 audio.capture 档位应是 130000",
            pilot.contains("""/^audio\.capture$/, 130000]"""),
        )
    }

    @Test
    fun `USAGE 文案的入口区间与录音预算已同步`() {
        val usage = sourceFile("pilot/interlock-relay-core/src/main/java/interlock/relay/core/transport/GuestAssets.kt")
            .readText()
            .replace(Regex("""\s+"""), " ")
        assertTrue("USAGE 的 --timeout 区间应写 1000..130000", usage.contains("clamped into 1000..130000"))
        assertTrue("USAGE 的录音预算应写 130000ms（内容 120 秒 + 收尾余量）", usage.contains("calling budget at 130000ms"))
    }

    /** 等待方的上限必须高于内容上限：余量没了，这条契约就失去了存在意义。 */
    @Test
    fun `等待上限严格高于录音内容上限`() {
        assertTrue(waitingCapMs > contentCapMs)
        assertEquals(contentCapMs + 10_000L, waitingCapMs)
    }

    /**
     * 从工作目录向上找仓库布局里的源文件。单测的工作目录通常是模块目录，但换一种
     * 执行方式（IDE 里单跑、聚合任务）也不能因此静默跳过——找不到就当场失败。
     */
    private fun sourceFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("找不到 $relative（从工作目录向上逐层找过），工作目录：${System.getProperty("user.dir")}")
    }

    /** 取脚本里 `const NAME = <int>;` 形态的数值常量：契约测试只认声明的那个数。 */
    private fun constantOf(source: String, name: String): Long =
        Regex("""const\s+$name\s*=\s*(\d+)""").find(source)
            ?.groupValues?.get(1)?.toLong()
            ?: throw AssertionError("在源文件里找不到常量 $name")
}
