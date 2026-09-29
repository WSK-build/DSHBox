package interlock.relay.core.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 交付目录布局的**跨语言契约**：宿主 [ArtifactOut] 的映射与回收参数，
 * 必须与入口脚本 `relay-client.cjs` 里的同名常量逐条相等。
 *
 * 为什么必须相等：CLI 在缺省 `--out` 时自己算出落点（`<挂载点>/out/<kind>/…`），
 * 而回收这棵树的是宿主。两边任何一侧单独改动，产物就会落到一个没人回收的目录里，
 * 或者被按错误的保留策略删掉——而这两种错都不会有编译报错，只有这条测试能拦。
 */
class ArtifactOutTest {

    @Test
    fun subdirMappingMatchesTheEntryScript() {
        val script = entryScript()
        val table = tableIn(script)
        assertNotNull("入口脚本里找不到 OUT_SUBDIRS", table)
        val pairs = Regex("'([a-z.]+)'\\s*:\\s*'([a-z]+)'").findAll(table!!)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(
            "OUT_SUBDIRS 与宿主 ArtifactOut 的映射不一致",
            mapOf(
                "screen.capture" to ArtifactOut.SHOT,
                "screen.record" to ArtifactOut.VIDEO,
                "audio.capture" to ArtifactOut.AUDIO,
            ),
            pairs,
        )
        assertEquals(ArtifactOut.FILE, jsString(script, "OUT_DEFAULT_SUBDIR"))
    }

    @Test
    fun retentionParametersMatchTheEntryScript() {
        val script = entryScript()
        assertEquals(ArtifactOut.KEEP_PER_KIND.toLong(), jsNumber(script, "OUT_KEEP_PER_KIND"))
        assertEquals(ArtifactOut.MAX_TOTAL_BYTES, jsNumber(script, "OUT_MAX_TOTAL_BYTES"))
        assertEquals(ArtifactOut.MAX_AGE_MS, jsNumber(script, "OUT_MAX_AGE_MS"))
        assertEquals(ArtifactOut.RECENT_GRACE_MS, jsNumber(script, "OUT_RECENT_GRACE_MS"))
        // OUT_ROOT 已由脚本从自身位置推导（<entry>/out），不再有可对账的字面量；
        // 这里钉住它的定义形态，保证推导规则不被改回写死路径。
        assert(script.contains("const OUT_ROOT = path.join(ENTRY_DIR, 'out')")) { "OUT_ROOT must stay derived from the entry directory" }
    }

    /** 没列到的能力一律落 file：宁可粗一点，也不要把"录屏"猜成"截图"。 */
    @Test
    fun unknownCapabilitiesFallBackToFile() {
        assertEquals(ArtifactOut.FILE, ArtifactOut.subdirFor("pkg.query"))
        assertEquals(ArtifactOut.FILE, ArtifactOut.subdirFor("media.write"))
        assertEquals(ArtifactOut.FILE, ArtifactOut.subdirFor(""))
    }

    private fun tableIn(script: String): String? {
        val start = script.indexOf("const OUT_SUBDIRS = {")
        if (start < 0) return null
        val end = script.indexOf("};", start)
        if (end < 0) return null
        return script.substring(start, end)
    }

    private fun jsNumber(script: String, name: String): Long {
        val match = Regex("const\\s+$name\\s*=\\s*(\\d+)").find(script)
            ?: throw AssertionError("入口脚本里找不到常量 $name")
        return match.groupValues[1].toLong()
    }

    private fun jsString(script: String, name: String): String {
        val match = Regex("const\\s+$name\\s*=\\s*'([^']*)'").find(script)
            ?: throw AssertionError("入口脚本里找不到常量 $name")
        return match.groupValues[1]
    }

    private fun entryScript(): String {
        val candidates = listOf(
            "src/main/assets/relay/relay-client.cjs",
            "interlock-relay-core/src/main/assets/relay/relay-client.cjs",
        )
        return candidates.map { File(it) }.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到入口脚本 relay-client.cjs；候选路径：$candidates")
    }
}
