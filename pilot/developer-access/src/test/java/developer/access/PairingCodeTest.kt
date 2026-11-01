package developer.access

import developer.access.adb.PairingCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 配对码输入：只留 6 位数字，填满即视为完整（由调用方立即提交）。 */
class PairingCodeTest {

    @Test
    fun `六位数字才算完整`() {
        assertTrue(PairingCode.isComplete("123456"))
        assertFalse(PairingCode.isComplete("12345"))
        assertFalse(PairingCode.isComplete("12345a"))
        assertFalse(PairingCode.isComplete(""))
    }

    @Test
    fun `粘贴时分隔符被丢弃`() {
        assertEquals("123456", PairingCode.normalize("123 456"))
        assertEquals("123456", PairingCode.normalize("123-456"))
        assertEquals("123456", PairingCode.normalize(" 123456\n"))
    }

    @Test
    fun `非数字被丢弃且截断到六位`() {
        assertEquals("1234", PairingCode.normalize("12a34"))
        assertEquals("123456", PairingCode.normalize("1234567"))
        assertEquals("", PairingCode.normalize("abcdef"))
    }

    @Test
    fun `归一化结果可直接判定完整`() {
        assertTrue(PairingCode.isComplete(PairingCode.normalize("653 921")))
    }
}
