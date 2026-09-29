package interlock.relay.core.exec.a11y

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按编号取到的东西看不见」这条判据的四种组合。
 *
 * 快照会把不可见节点滤掉，测试环境里造不出一个"存在于当前树却看不见"的编号；设备上这一支
 * 已量到（编号落在滚动区外的节点上），跨屏那一支仍只能靠同一份可见性判断覆盖。
 */
class StaleNodeIdReasonTest {

    @Test
    fun aSelectorResolvedNodeIsNeverWarnedAbout() {
        assertNull(
            staleNodeIdReason(
                askedByNodeId = false,
                visibleToUser = false,
                hasOnScreenArea = false,
                displayId = 0,
            ),
        )
    }

    @Test
    fun aVisibleInAreaNodeByIdIsClean() {
        assertNull(
            staleNodeIdReason(
                askedByNodeId = true,
                visibleToUser = true,
                hasOnScreenArea = true,
                displayId = 0,
            ),
        )
    }

    @Test
    fun anInvisibleNodeByIdWarns() {
        val reason = staleNodeIdReason(
            askedByNodeId = true,
            visibleToUser = false,
            hasOnScreenArea = true,
            displayId = 0,
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("not visible on display 0"))
        assertTrue(reason.contains("fresh ui.snapshot"))
    }

    @Test
    fun aNodeWithNoOnScreenAreaByIdWarnsAndNamesItsDisplay() {
        val reason = staleNodeIdReason(
            askedByNodeId = true,
            visibleToUser = true,
            hasOnScreenArea = false,
            displayId = 43,
        )
        assertNotNull(reason)
        assertTrue("屏号要说出来，助手才知道是哪棵树", reason!!.contains("display 43"))
    }
}
