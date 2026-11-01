package developer.access.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootAuthCodeTest {

    @Test
    fun `生成的访问码长度为 26 位`() {
        assertEquals(RootAuthCode.CODE_LENGTH, RootAuthCode.generate().length)
    }

    @Test
    fun `生成的访问码只含去易混字符后的 base32 字母表`() {
        val allowed = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        repeat(64) {
            val code = RootAuthCode.generate()
            assertTrue("出现字母表外字符：$code", code.all { it in allowed })
        }
    }

    @Test
    fun `多次生成的访问码互不相同`() {
        val codes = (0 until 64).map { RootAuthCode.generate() }.toSet()
        assertTrue("出现重复访问码", codes.size == 64)
    }

    @Test
    fun `分组显示后还原为原始访问码`() {
        val code = RootAuthCode.generate()
        val grouped = RootAuthCode.group(code)
        assertEquals(code, RootAuthCode.normalize(grouped))
    }

    @Test
    fun `归一化去除空白与分组符并转为大写`() {
        assertEquals("ABCD2345", RootAuthCode.normalize(" abcd-2345 "))
        assertEquals("ABCD2345", RootAuthCode.normalize("abcd 2345"))
    }

    @Test
    fun `挑战应答对同一 nonce 稳定且对不同 nonce 不同`() {
        val secret = RootAuthCode.generate()
        val nonce = "0123456789abcdef"
        assertEquals(RootAuthCode.sign(secret, nonce), RootAuthCode.sign(secret, nonce))
        assertNotEquals(RootAuthCode.sign(secret, nonce), RootAuthCode.sign(secret, "fedcba9876543210"))
    }

    @Test
    fun `挑战应答不因分组形式而改变`() {
        val code = RootAuthCode.generate()
        assertEquals(
            RootAuthCode.sign(code, "n"),
            RootAuthCode.sign(RootAuthCode.group(code), "n"),
        )
    }

    @Test
    fun `不同访问码产生不同应答`() {
        assertNotEquals(
            RootAuthCode.sign(RootAuthCode.generate(), "n"),
            RootAuthCode.sign(RootAuthCode.generate(), "n"),
        )
    }
}
