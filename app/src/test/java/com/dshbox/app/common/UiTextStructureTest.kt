package com.dshbox.app.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UiText 结构测试（纯 JVM，无 Android Context）。
 * 覆盖 Res/Raw/Concat/Separator 的构造与嵌套解析顺序；
 * asString(context) 需 Android 资源环境，由真机轮覆盖。
 */
class UiTextStructureTest {

    @Test
    fun resKeepsIdAndArgs() {
        val t = UiText.Res(0x7f000001, listOf("a", 2))
        assertEquals(0x7f000001, (t as UiText.Res).id)
        assertEquals(listOf<Any>("a", 2), t.args)
    }

    @Test
    fun rawHoldsTextAndNullNormalizesToEmpty() {
        assertEquals("x", (UiText.Raw("x") as UiText.Raw).text)
        assertEquals("", (UiText.raw(null) as UiText.Raw).text)
    }

    @Test
    fun concatPreservesPartOrder() {
        val t = UiText.Concat(
            listOf(
                UiText.Res(1, emptyList()),
                UiText.Separator("; "),
                UiText.Raw("tail"),
            ),
        )
        val parts = (t as UiText.Concat).parts
        assertEquals(3, parts.size)
        assertEquals(UiText.Res(1), parts[0])
        assertEquals(UiText.Separator("; "), parts[1])
        assertEquals(UiText.Raw("tail"), parts[2])
    }

    @Test
    fun separatorIsDistinctFromRaw() {
        assertEquals(UiText.Separator("；"), UiText.Separator("；"))
        assertEquals(UiText.Separator("；") as Any, UiText.Separator("；"))
        // 数据语义上 Separator 与 Raw 不等价（渲染点处理不同）
        assertEquals(false, UiText.Separator("x") == UiText.Raw("x"))
    }

    @Test
    fun nestedConcatFlattensAtRenderNotStructurally() {
        val inner = UiText.Concat(listOf(UiText.Raw("a"), UiText.Raw("b")))
        val outer = UiText.Concat(listOf(UiText.Raw("0"), inner, UiText.Raw("1")))
        val parts = (outer as UiText.Concat).parts
        assertEquals(3, parts.size)
        assertEquals(inner, parts[1])
    }

    @Test
    fun emptyConcatAndEmptyRawAreValid() {
        assertEquals(0, (UiText.Concat(emptyList()) as UiText.Concat).parts.size)
        assertEquals("", (UiText.Raw("") as UiText.Raw).text)
    }
}
