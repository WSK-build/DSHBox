package interlock.relay.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台按所有者记账：助手独立页与宿主页签是两个互不知情的写入方，
 * 单布尔会被后到的 ON_STOP 把先到的 onResume 覆盖掉——独立页实际在前台，呈现裁决
 * 却按后台走。所有者集合下任一所有者可见即为前台，两个都退才真正不可见。
 */
class ForegroundTrackerTest {

    /** 任一所有者可见即前台。 */
    @Test
    fun anyVisibleOwnerMeansForeground() {
        val tracker = ForegroundTracker()
        assertFalse(tracker.visible)
        tracker.setOwnerVisible(RelayContainer.FOREGROUND_OWNER_HOST, true)
        assertTrue(tracker.visible)
        tracker.setOwnerVisible(RelayContainer.FOREGROUND_OWNER_PILOT, true)
        assertTrue(tracker.visible)
    }

    /** 宿主 ON_STOP 晚于独立页 onResume：不得把前台按成后台。 */
    @Test
    fun hostStopAfterPilotResumeKeepsForeground() {
        val tracker = ForegroundTracker()
        tracker.setOwnerVisible(RelayContainer.FOREGROUND_OWNER_PILOT, true)
        tracker.setOwnerVisible(RelayContainer.FOREGROUND_OWNER_HOST, true)
        tracker.setOwnerVisible(RelayContainer.FOREGROUND_OWNER_HOST, false)
        assertTrue("独立页还在前台，宿主退场不算数", tracker.visible)
        tracker.setOwnerVisible(RelayContainer.FOREGROUND_OWNER_PILOT, false)
        assertFalse("两个所有者都退了才真正不可见", tracker.visible)
    }

    /** 重复写同一状态是幂等的；再进场要能重新算前台。 */
    @Test
    fun repeatedWritesAreIdempotentAndReentryWorks() {
        val tracker = ForegroundTracker()
        tracker.setOwnerVisible(RelayContainer.FOREGROUND_OWNER_PILOT, true)
        tracker.setOwnerVisible(RelayContainer.FOREGROUND_OWNER_PILOT, true)
        assertTrue(tracker.visible)
        tracker.setOwnerVisible(RelayContainer.FOREGROUND_OWNER_PILOT, false)
        tracker.setOwnerVisible(RelayContainer.FOREGROUND_OWNER_PILOT, false)
        assertFalse(tracker.visible)
        tracker.setOwnerVisible(RelayContainer.FOREGROUND_OWNER_PILOT, true)
        assertTrue(tracker.visible)
    }
}
