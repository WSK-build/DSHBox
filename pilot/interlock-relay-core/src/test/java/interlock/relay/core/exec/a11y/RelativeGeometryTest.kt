package interlock.relay.core.exec.a11y

import interlock.relay.core.exec.a11y.RelativeGeometry.Box
import interlock.relay.core.exec.a11y.RelativeGeometry.Outcome
import interlock.relay.core.exec.a11y.RelativeGeometry.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 相对定位的几何判据。这里钉的是"取哪一个"与"什么时候必须说歧义"——
 * 静默取第一个候选是本模块最不能接受的一类失败：它回成功，而操作落在别的东西上。
 */
class RelativeGeometryTest {

    private fun box(ref: Int, left: Int, top: Int, right: Int, bottom: Int, cls: String? = null) =
        Box(ref, left, top, right, bottom, cls)

    private val anchor = box(0, 100, 100, 200, 160, "android.widget.ImageButton")

    @Test
    fun `picks the nearest on the named side`() {
        val near = box(1, 210, 100, 260, 160)
        val far = box(2, 400, 100, 460, 160)
        val outcome = RelativeGeometry.pick(anchor, listOf(far, near, anchor), Side.RIGHT, 1, false)
        assertEquals(1, (outcome as Outcome.Pick).box.ref)
        assertEquals(10, outcome.gapPx)
    }

    @Test
    fun `nodes that overlap the anchor are not on any side`() {
        val straddling = box(1, 150, 100, 300, 160)
        assertEquals(Outcome.None, RelativeGeometry.pick(anchor, listOf(straddling), Side.RIGHT, 1, false))
    }

    @Test
    fun `straight ahead beats a far row at the same gap`() {
        val sameRow = box(1, 210, 110, 260, 150)
        val otherRow = box(2, 210, 900, 260, 940)
        val outcome = RelativeGeometry.pick(anchor, listOf(otherRow, sameRow), Side.RIGHT, 1, false)
        assertEquals(1, (outcome as Outcome.Pick).box.ref)
    }

    @Test
    fun `a tie is reported as ambiguous with both candidates`() {
        val a = box(3, 210, 100, 260, 160)
        val b = box(4, 211, 100, 261, 160)
        val outcome = RelativeGeometry.pick(anchor, listOf(a, b), Side.RIGHT, 1, false)
        assertTrue("$outcome", outcome is Outcome.Ambiguous)
        assertEquals(listOf(3, 4), (outcome as Outcome.Ambiguous).boxes.map { it.ref })
    }

    @Test
    fun `nth resolves a tie by the caller's choice`() {
        val a = box(3, 210, 100, 260, 160)
        val b = box(4, 500, 100, 560, 160)
        val c = box(5, 800, 100, 860, 160)
        assertEquals(5, (RelativeGeometry.pick(anchor, listOf(a, b, c), Side.RIGHT, 3, false) as Outcome.Pick).box.ref)
        // 超出候选数不是"取最后一个"，是那个方向上没有第 N 个。
        assertEquals(Outcome.None, RelativeGeometry.pick(anchor, listOf(a, b), Side.RIGHT, 5, false))
    }

    @Test
    fun `sameClass restricts the pool to the anchor's class`() {
        val sibling = box(1, 210, 100, 260, 160, "android.widget.ImageButton")
        val other = box(2, 205, 100, 255, 160, "android.widget.TextView")
        assertEquals(1, (RelativeGeometry.pick(anchor, listOf(sibling, other), Side.RIGHT, 1, true) as Outcome.Pick).box.ref)
    }

    @Test
    fun `above and below use the vertical gap`() {
        val under = box(1, 100, 200, 200, 240)
        val outcome = RelativeGeometry.pick(anchor, listOf(under), Side.BELOW, 1, false)
        assertEquals(1, (outcome as Outcome.Pick).box.ref)
        assertEquals(40, outcome.gapPx)
        assertEquals(Outcome.None, RelativeGeometry.pick(anchor, listOf(under), Side.ABOVE, 1, false))
    }

    @Test
    fun `side names accept the common synonyms`() {
        assertEquals(Side.BELOW, RelativeGeometry.sideOf("down"))
        assertEquals(Side.ABOVE, RelativeGeometry.sideOf("UP"))
        assertEquals(null, RelativeGeometry.sideOf("middle"))
    }
}
