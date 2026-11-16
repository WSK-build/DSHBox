package interlock.relay.core.exec.shizuku

import interlock.relay.core.exec.stream.PanelInputPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 面板输入的**执行面纯判据**：命令串拼装与屏内边界复核。
 * 这两条是 `panelInput` 落 `input -d` 前的最后一道形状/边界判据，抽出纯函数后 JVM 可测。
 */
class PanelInputArgsTest {

    private fun cmd(
        action: String,
        x: Int? = null, y: Int? = null,
        x1: Int? = null, y1: Int? = null, x2: Int? = null, y2: Int? = null,
        durationMs: Int? = null,
    ) = PanelInputPolicy.Command(action, x, y, x1, y1, x2, y2, durationMs)

    @Test
    fun `命令串按动作拼装`() {
        assertEquals("tap 100 200", buildPanelInputArgs(cmd("tap", x = 100, y = 200)))
        assertEquals("swipe 0 0 2559 2063 300", buildPanelInputArgs(cmd("swipe", x1 = 0, y1 = 0, x2 = 2559, y2 = 2063, durationMs = 300)))
        // swipe 缺 duration 用缺省 300
        assertEquals("swipe 1 2 3 4 300", buildPanelInputArgs(cmd("swipe", x1 = 1, y1 = 2, x2 = 3, y2 = 4)))
        assertEquals("keyevent 4", buildPanelInputArgs(cmd("back")))
        assertEquals("keyevent 3", buildPanelInputArgs(cmd("home")))
        // 未知动作 / 坐标缺失 → null（调用方按失败回话，绝不过 shell 解析）
        assertNull(buildPanelInputArgs(cmd("text")))
        assertNull(buildPanelInputArgs(cmd("tap")))
        assertNull(buildPanelInputArgs(cmd("swipe", x1 = 1, y1 = 2)))
    }

    @Test
    fun `越界与负值坐标按屏内边界拒`() {
        // 屏内 → null（放行）
        assertNull(panelInputOutOfBounds(cmd("tap", x = 0, y = 0), 2560, 2064))
        assertNull(panelInputOutOfBounds(cmd("tap", x = 2559, y = 2063), 2560, 2064))
        assertNull(panelInputOutOfBounds(cmd("back"), 2560, 2064))
        assertNull(panelInputOutOfBounds(cmd("home"), 2560, 2064))
        // 越界 / 负值
        assertTrue(panelInputOutOfBounds(cmd("tap", x = 2560, y = 0), 2560, 2064)!!.contains("outside"))
        assertTrue(panelInputOutOfBounds(cmd("tap", x = -1, y = 0), 2560, 2064)!!.contains("outside"))
        assertTrue(panelInputOutOfBounds(cmd("swipe", x1 = 0, y1 = 0, x2 = 2560, y2 = 0), 2560, 2064)!!.contains("outside"))
        // 尺寸未知：不再 0 尺寸放行
        assertTrue(panelInputOutOfBounds(cmd("tap", x = 5, y = 5), 0, 0)!!.contains("size unknown"))
        assertTrue(panelInputOutOfBounds(cmd("tap", x = 5, y = 5), 2560, 0)!!.contains("size unknown"))
    }
}
