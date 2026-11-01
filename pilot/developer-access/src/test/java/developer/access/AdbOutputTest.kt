package developer.access

import developer.access.adb.AdbOutput
import developer.access.adb.PairingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * adb 输出文本 → 失败原因的分类。
 *
 * adb 没有机器可读错误码，只能按文案判断；这里固定住几条已知形态，避免
 * "码过期被说成泛化失败"——失败必须给出可操作的原因。
 */
class AdbOutputTest {

    @Test
    fun `成功配对的文案`() {
        assertTrue(AdbOutput.classify("Successfully paired to 192.168.1.5:37123") is PairingResult.Success)
    }

    @Test
    fun `成功连接的文案`() {
        assertTrue(AdbOutput.classify("connected to 192.168.1.5:37123") is PairingResult.Success)
    }

    @Test
    fun `配对码错误或过期`() {
        assertTrue(
            AdbOutput.classify("Failed to pair to 192.168.1.5:37123: incorrect code") is PairingResult.CodeRejected,
        )
        assertTrue(
            AdbOutput.classify("Failed to pair to 192.168.1.5:37123: code expired") is PairingResult.CodeRejected,
        )
        assertTrue(AdbOutput.classify("failed to authenticate") is PairingResult.CodeRejected)
    }

    @Test
    fun `网络不可达`() {
        assertTrue(
            AdbOutput.classify("Failed to pair to 192.168.1.5:37123: Connection refused") is PairingResult.Unreachable,
        )
        assertTrue(AdbOutput.classify("unable to connect to 192.168.1.5:37123") is PairingResult.Unreachable)
        assertTrue(AdbOutput.classify("error: no route to host") is PairingResult.Unreachable)
        // 连接动作下对端不是连接服务时的原文。
        assertTrue(
            AdbOutput.classify(
                "error: protocol fault (couldn't read status message): Success",
                AdbOutput.Operation.CONNECT,
            ) is PairingResult.Unreachable,
        )
    }

    @Test
    fun `配对动作下的 protocol fault 是配对码被拒`() {
        // 设备上确认：地址不可达时 adb 输出 failed to connect；握手被拒才输出 protocol fault。
        val text = "error: protocol fault (couldn't read status message): No error"
        assertTrue(AdbOutput.classify(text, AdbOutput.Operation.PAIR) is PairingResult.CodeRejected)
        assertTrue(AdbOutput.classify(text, AdbOutput.Operation.CONNECT) is PairingResult.Unreachable)
        assertTrue(
            AdbOutput.classify("failed to connect to 192.168.1.5:37123", AdbOutput.Operation.PAIR) is
                PairingResult.Unreachable,
        )
    }

    @Test
    fun `无法归类的失败保留原文`() {
        val result = AdbOutput.classify("Failed to pair to 192.168.1.5:37123: something new")
        assertTrue(result is PairingResult.Failed)
        assertEquals("Failed to pair to 192.168.1.5:37123: something new", (result as PairingResult.Failed).detail)
    }

    @Test
    fun `匹配不区分大小写`() {
        assertTrue(AdbOutput.classify("SUCCESSFULLY PAIRED TO 10.0.0.2:5555") is PairingResult.Success)
        assertTrue(AdbOutput.classify("INCORRECT CODE") is PairingResult.CodeRejected)
    }
}
