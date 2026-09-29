package interlock.relay.core.exec.direct

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `pkg.query` 的 `keyword` 过滤：命中包名或显示名，大小写不敏感，空白等于不过滤。 */
class AppKeywordFilterTest {

    @Test
    fun `空白关键词不过滤任何一条`() {
        assertTrue(appMatches("", "com.foo", "Foo"))
        assertTrue(appMatches("   ", "com.foo", "Foo"))
    }

    @Test
    fun `包名与显示名都算命中且不分大小写`() {
        assertTrue(appMatches("deskclock", "com.android.deskclock", "时钟"))
        assertTrue(appMatches("DESK", "com.android.deskclock", "时钟"))
        assertTrue(appMatches("时钟", "com.android.deskclock", "闹钟时钟"))
    }

    @Test
    fun `两边都不含时不命中`() {
        assertFalse(appMatches("clock", "com.android.settings", "设置"))
    }
}
