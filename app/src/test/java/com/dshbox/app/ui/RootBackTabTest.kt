package com.dshbox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DSH 根页面返回落点的纯 JVM 测试（页签下标 → 回退目标）。
 *
 * 这里钉的是宿主导航的边界：不能回到 DSH 自己（否则等于没返回），越界一律回首页。
 */
class RootBackTabTest {

    @Test
    fun keepsPreviousTabWhenItIsARealTab() {
        assertEquals(0, rootBackTabOf(0)) // 首页
        assertEquals(1, rootBackTabOf(1)) // 文件
        assertEquals(3, rootBackTabOf(3)) // 终端
        assertEquals(4, rootBackTabOf(4)) // 设置
    }

    @Test
    fun neverReturnsToDshItself() {
        assertEquals(0, rootBackTabOf(2))
    }

    @Test
    fun fallsBackToHomeForOutOfRangeValues() {
        assertEquals(0, rootBackTabOf(-1))
        assertEquals(0, rootBackTabOf(99))
    }
}
