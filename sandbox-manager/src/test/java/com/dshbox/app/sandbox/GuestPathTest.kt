package com.dshbox.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `GuestPath.withEntryFirst` 单测。
 *
 * 锁住三件事：**空 PATH 也能得到条目**、**缺失时前置**、**已存在不重复也不重排**
 * —— 第三条尤其重要：重复项会让 `which pnpm` 的结果取决于顺序，排到后面则可能被
 * `/usr/local/bin` 里的同名命令抢走。
 */
class GuestPathTest {

    private val shim = "/root/projects/.dsh/dshbox/bin"

    @Test
    fun emptyOrNullPathYieldsTheEntry() {
        assertEquals(shim, GuestPath.withEntryFirst(null, shim))
        assertEquals(shim, GuestPath.withEntryFirst("", shim))
    }

    @Test
    fun prependsWhenMissing() {
        assertEquals(
            "$shim:/usr/local/bin:/usr/bin",
            GuestPath.withEntryFirst("/usr/local/bin:/usr/bin", shim),
        )
    }

    @Test
    fun keepsExistingPlacement() {
        val path = "/usr/local/bin:$shim:/usr/bin"
        assertEquals(path, GuestPath.withEntryFirst(path, shim))
    }

    @Test
    fun dropsEmptySegmentsLeftByDoubleColons() {
        assertEquals(
            "$shim:/usr/local/bin:/usr/bin",
            GuestPath.withEntryFirst("/usr/local/bin::/usr/bin", shim),
        )
    }
}
