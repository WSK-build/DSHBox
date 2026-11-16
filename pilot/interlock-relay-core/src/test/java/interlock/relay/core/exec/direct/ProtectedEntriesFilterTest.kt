package interlock.relay.core.exec.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据面：notify.read「读全部」按名单过滤的纯函数矩阵（[filterProtectedEntries]，
 * 生产调用点在 DirectBackend.readNotifications 组装回包之前）。带 `package` 且命中名单
 * 的入口拒绝不在这里判——那在 protectedTarget / RelayCoordinator 的测试里。
 */
class ProtectedEntriesFilterTest {

    /** 通知条目的形状假体：与 RelayNotificationListener.Entry 同构（包名 + 正文字段）。 */
    private data class Note(val pkg: String, val title: String, val text: String)

    private val packageNameOf: (Note) -> String = { it.pkg }

    @Test
    fun `名单为空时原样返回零开销`() {
        val entries = listOf(Note("com.bank", "t", "body"), Note("com.gallery", "p", "q"))
        assertEquals(entries, filterProtectedEntries(entries, packageNameOf, emptySet()))
    }

    @Test
    fun `空名单上的空列表也原样`() {
        assertEquals(emptyList<Note>(), filterProtectedEntries(emptyList(), packageNameOf, emptySet()))
    }

    @Test
    fun `命中名单的条目整个丢弃其余原样`() {
        val entries = listOf(
            Note("com.gallery", "photo", "album"),
            Note("com.bank", "验证码", "123456"),
            Note("com.notes", "memo", "buy milk"),
        )
        val visible = filterProtectedEntries(entries, packageNameOf, setOf("com.bank"))
        // 受保护应用的通知正文一条都不交出。
        assertTrue(visible.none { it.pkg == "com.bank" })
        // 未受保护的条目字段与顺序原样保留。
        assertEquals(listOf(entries[0], entries[2]), visible)
    }

    @Test
    fun `多条命中全丢弃且保序`() {
        val entries = listOf(
            Note("com.bank", "a", "1"),
            Note("com.wallet", "b", "2"),
            Note("com.gallery", "c", "3"),
            Note("com.bank", "d", "4"),
        )
        val visible = filterProtectedEntries(entries, packageNameOf, setOf("com.bank", "com.wallet"))
        assertEquals(listOf(entries[2]), visible)
    }

    @Test
    fun `精确相等才过滤不认前缀`() {
        val entries = listOf(Note("com.bank.extra", "x", "y"))
        assertEquals(entries, filterProtectedEntries(entries, packageNameOf, setOf("com.bank")))
        // 大小写敏感：包名是整体。
        assertEquals(entries, filterProtectedEntries(entries, packageNameOf, setOf("COM.BANK")))
    }

    @Test
    fun `全部条目受保护时回空列表`() {
        val entries = listOf(Note("com.bank", "a", "1"), Note("com.bank", "b", "2"))
        assertEquals(
            emptyList<Note>(),
            filterProtectedEntries(entries, packageNameOf, setOf("com.bank")),
        )
    }

    @Test
    fun `名单非空而包名读不到的条目不丢`() {
        // 空包名不是"读到受保护应用"：把它丢掉等于把读不到说成命中。
        val entries = listOf(Note("", "unknown", "src"), Note("com.bank", "code", "1"))
        val visible = filterProtectedEntries(entries, packageNameOf, setOf("com.bank"))
        assertEquals(listOf(entries[0]), visible)
    }
}
