package interlock.relay.core.exec

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这个包名是不是宿主自己」这条判据的形状边界。
 *
 * 宿主自保护（无障碍窗口归属、写文本根节点、`force-stop` 目标、安装替换目标）在
 * 各调用点把场景取成包名后都交给 [namesHostPackage]；判据本体在此穷举包名形状，
 * 防止任何一处调用点各自放宽。它不接收档位：宿主自保护不随档位变化，
 * 「完全访问」放宽的是助手审批，不是这条判据。
 */
class HostSelfGuardTest {

    private val self = "com.example.host"

    @Test
    fun `精确等于宿主包名时命中`() {
        assertTrue(namesHostPackage(self, self))
    }

    @Test
    fun `空值与空串都不命中`() {
        assertFalse(namesHostPackage(null, self))
        assertFalse(namesHostPackage("", self))
    }

    @Test
    fun `前缀或后缀相同的包名都不是宿主`() {
        assertFalse(namesHostPackage("$self.extra", self))
        assertFalse(namesHostPackage("com.example.hostapp", self))
        assertFalse(namesHostPackage("other.$self", self))
    }

    @Test
    fun `包名大小写敏感`() {
        assertFalse(namesHostPackage(self.uppercase(), self))
    }

    @Test
    fun `宿主包名之外的任何应用都不命中`() {
        assertFalse(namesHostPackage("com.android.settings", self))
        assertFalse(namesHostPackage("com.other.app", self))
    }
}
