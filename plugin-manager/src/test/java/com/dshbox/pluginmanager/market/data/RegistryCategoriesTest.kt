package com.dshbox.pluginmanager.market.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录条目分类的归一。
 *
 * 覆盖三种真实输入形态：单个字符串（旧版目录）、数组（新版目录）、以及
 * 数组里混入空串或非字符串。归一后为空必须能被调用方识别出来——上游对
 * 这种条目是直接拒收的，所以"空结果"是一个有意义的结论，不是"无所谓"。
 */
class RegistryCategoriesTest {

    @Test
    fun acceptsSingleString() {
        assertEquals(listOf("tools"), RegistryCategories.normalize("tools"))
    }

    @Test
    fun acceptsArray() {
        assertEquals(
            listOf("tools", "themes"),
            RegistryCategories.normalize(listOf("tools", "themes")),
        )
    }

    @Test
    fun deDuplicatesPreservingOrder() {
        assertEquals(
            listOf("b", "a"),
            RegistryCategories.normalize(listOf("b", "a", "b")),
        )
    }

    @Test
    fun dropsEmptyAndNonStringMembers() {
        assertEquals(
            listOf("ok"),
            RegistryCategories.normalize(listOf("", "ok", 42, null)),
        )
    }

    @Test
    fun normalizesMissingCategoryToEmpty() {
        assertTrue(RegistryCategories.normalize(null).isEmpty())
    }

    @Test
    fun normalizesAllInvalidMembersToEmpty() {
        assertTrue(RegistryCategories.normalize(listOf("", 7)).isEmpty())
        assertTrue(RegistryCategories.normalize(42).isEmpty())
    }

    @Test
    fun acceptsPlainArray() {
        assertEquals(
            listOf("x"),
            RegistryCategories.normalize(arrayOf("x", "")),
        )
    }
}
