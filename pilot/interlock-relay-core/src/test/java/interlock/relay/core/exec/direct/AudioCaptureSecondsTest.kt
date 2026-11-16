package interlock.relay.core.exec.direct

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 录音时长的收敛口径：上限 120 秒（与入口/插件的等待预算同表），越界**夹紧**而不是
 * 拒绝——与截屏同一张表，越界的请求录到上限为止并如实回带时长。30 秒的旧硬上限
 * 不复存在：60 秒的请求原样收下。
 */
class AudioCaptureSecondsTest {

    @Test
    fun `30 秒以上的请求原样收下`() {
        assertEquals(30, AudioCapture.clampSeconds(30))
        assertEquals(45, AudioCapture.clampSeconds(45))
        assertEquals(60, AudioCapture.clampSeconds(60))
        assertEquals(119, AudioCapture.clampSeconds(119))
    }

    @Test
    fun `120 秒是上限且原样收下`() {
        assertEquals(120, AudioCapture.clampSeconds(120))
        assertEquals(AudioCapture.MAX_SECONDS, 120)
    }

    @Test
    fun `越界夹紧到边界而不是拒绝`() {
        assertEquals(1, AudioCapture.clampSeconds(0))
        assertEquals(1, AudioCapture.clampSeconds(-5))
        assertEquals(120, AudioCapture.clampSeconds(121))
        assertEquals(120, AudioCapture.clampSeconds(Int.MAX_VALUE))
    }

    @Test
    fun `缺省时长仍是 5 秒`() {
        assertEquals(5, AudioCapture.clampSeconds(AudioCapture.DEFAULT_SECONDS))
    }
}
