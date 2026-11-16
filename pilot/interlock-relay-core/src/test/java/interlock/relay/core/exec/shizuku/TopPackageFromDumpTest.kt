package interlock.relay.core.exec.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 从 `dumpsys activity activities` 输出里取指定显示的顶层包名。
 * 流看门狗与坐标/取帧前的 fail-closed 判定都吃这份解析，解析错一个显示号
 * 就会把主屏的包当成虚拟屏的包（或者反之），必须单独钉住。
 */
class TopPackageFromDumpTest {

    private val dump = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #0 (activities from top to bottom):
          topResumedActivity=ActivityRecord{123 u0 com.launcher/.MainActivity} t12}
          other=ActivityRecord{124 u0 com.bank/.PayActivity} t13}
        Display #7 (activities from top to bottom):
          topResumedActivity=ActivityRecord{200 u0 com.wallet/.Main} t20}
        Display #9 (activities from top to bottom):
        Stack #0:
    """.trimIndent()

    @Test
    fun `按显示号取各自的顶层包名`() {
        assertEquals("com.launcher", ShizukuBackend.topPackageFromDump(dump, 0))
        assertEquals("com.wallet", ShizukuBackend.topPackageFromDump(dump, 7))
    }

    @Test
    fun `该显示段里没有顶层活动行时回 null`() {
        assertNull(ShizukuBackend.topPackageFromDump(dump, 9))
        assertNull(ShizukuBackend.topPackageFromDump(dump, 3))
    }

    @Test
    fun `空输出与残句回 null`() {
        assertNull(ShizukuBackend.topPackageFromDump("", 0))
        assertNull(ShizukuBackend.topPackageFromDump("nothing useful", 0))
    }
}
