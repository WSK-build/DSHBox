package interlock.relay.core.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 中介包的识别只认表内那两个包名：认错了会把一条正常的启动说成卡在选择框上。 */
class LaunchMediatorsTest {

    @Test
    fun `分身容器与打开方式选择器算中介包`() {
        assertTrue(LaunchMediators.isMediator("com.vivo.doubleinstance"))
        assertTrue(LaunchMediators.isMediator("com.android.intentresolver"))
    }

    @Test
    fun `普通应用与空值不算`() {
        assertFalse(LaunchMediators.isMediator("com.android.deskclock"))
        assertFalse(LaunchMediators.isMediator(null))
    }
}
