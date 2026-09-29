package com.dshbox.app.ui.webview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [dshUrlWithToken] 的单测。
 *
 * ## 为什么必须锁住"替换"语义
 *
 * DSH 的 launch token **会随 DSH 重启轮换**，而 WebView 容器持有的 `url` 里烤着
 * 建容器那一刻的旧 token。如果这个函数遇到 `token=` 就原样返回（"幂等"写法），
 * 那么手动刷新与 401 自动重试都会继续用旧 token 请求 —— 表现是白屏 + 进度条卡死、
 * 且因 `autoRefreshedForAuth` 已置位而无法自愈。
 *
 * 因此下面两条是**回归锁**：把语义改回"幂等"会让它们立即失败。
 */
class DshUrlWithTokenTest {

    private val base = "http://127.0.0.1:3080"

    @Test
    fun appendsTokenWhenNoQuery() {
        assertEquals("$base?token=abc123", dshUrlWithToken(base, "abc123"))
    }

    @Test
    fun appendsWithAmpersandWhenQueryExists() {
        assertEquals("$base?x=1&token=abc123", dshUrlWithToken("$base?x=1", "abc123"))
    }

    /** 回归锁：带旧 token 的 URL 必须被**替换**为新 token，而不是原样返回。 */
    @Test
    fun replacesStaleToken() {
        assertEquals("$base?token=NEW", dshUrlWithToken("$base?token=OLD", "NEW"))
    }

    /** 回归锁：替换时保留其它 query 参数，且不产生 `&&` / 尾随 `&`。 */
    @Test
    fun replacesStaleTokenKeepingOtherParams() {
        assertEquals("$base?x=1&token=NEW", dshUrlWithToken("$base?x=1&token=OLD", "NEW"))
        assertEquals("$base?x=1&token=NEW", dshUrlWithToken("$base?token=OLD&x=1", "NEW"))
    }

    @Test
    fun leavesBaseUntouchedWhenTokenMissing() {
        assertEquals(base, dshUrlWithToken(base, null))
        assertEquals(base, dshUrlWithToken(base, ""))
        // 无 token 时也不应清掉已有参数
        assertEquals("$base?token=OLD", dshUrlWithToken("$base?token=OLD", null))
    }

    @Test
    fun escapesTokenCharacters() {
        assertEquals("$base?token=a%20b", dshUrlWithToken(base, "a b"))
        assertEquals("$base?token=a%26b", dshUrlWithToken(base, "a&b"))
        assertEquals("$base?token=a%23b", dshUrlWithToken(base, "a#b"))
        // base64url 字符集原样保留（当前 token 形态，走不到转义分支）
        assertEquals("$base?token=A-Za-z0-9_-", dshUrlWithToken(base, "A-Za-z0-9_-"))
    }
}
