package com.dshbox.pluginmanager.market.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 市场状态的读-改-写。
 *
 * 重点只有一条：**只改自己拥有的字段**。状态文件是桌面端与本 app 共用的，
 * 里面还有分组、收藏、下载区域、更新通道等我们不解释的字段；写回时丢掉
 * 任何一个都等于把用户的选择静默抹掉。所以这里既验证"未知字段逐字保留"，
 * 也验证"渲染出的正文确实把它们带上了"。
 */
class MarketStateCodecTest {

    private fun diskState(): MarketState = MarketState(
        disabled = listOf("a"),
        notes = emptyMap(),
        preserved = linkedMapOf(
            "groups" to """{"g":["x"]}""",
            "groupOrder" to """["g"]""",
            "favorites" to """["https://example.com/p"]""",
            "channel" to "\"beta\"",
            "region" to "\"china\"",
            "regionAuto" to "true",
            "githubProxy" to "\"https://proxy.example\"",
            "somethingWeDoNotKnow" to """{"nested":[1,2,3]}""",
        ),
    )

    @Test
    fun preservedKeysExcludeOwnedFields() {
        val keys = listOf("disabled", "notes", "disabledSkins", "groups", "channel")
        assertEquals(listOf("groups", "channel"), MarketStateCodec.preservedKeys(keys))
    }

    @Test
    fun mergeKeepsEveryPreservedField() {
        val merged = MarketStateCodec.merge(diskState(), listOf("a", "b"), mapOf("a" to "note"))
        assertEquals(diskState().preserved, merged.preserved)
        assertEquals(listOf("a", "b"), merged.disabled)
        assertEquals(mapOf("a" to "note"), merged.notes)
    }

    @Test
    fun encodeCarriesPreservedFieldsVerbatim() {
        val merged = MarketStateCodec.merge(diskState(), listOf("a", "b"), mapOf("a" to "note"))
        val text = MarketStateCodec.encode(merged)
        assertTrue(text.contains(""""channel": "beta""""))
        assertTrue(text.contains(""""region": "china""""))
        assertTrue(text.contains(""""regionAuto": true"""))
        assertTrue(text.contains(""""groups": {"g":["x"]}"""))
        assertTrue(text.contains(""""somethingWeDoNotKnow": {"nested":[1,2,3]}"""))
        assertTrue(text.contains(""""disabled": ["a","b"]"""))
        assertTrue(text.contains(""""notes": {"a":"note"}"""))
    }

    @Test
    fun encodeOmitsNotesWhenEmpty() {
        val text = MarketStateCodec.encode(MarketStateCodec.merge(diskState(), listOf("a"), emptyMap()))
        assertFalse(text.contains("\"notes\""))
    }

    @Test
    fun mergeDeDuplicatesAndDropsBlankDisabled() {
        val merged = MarketStateCodec.merge(MarketState.EMPTY, listOf("a", "a", "", "b"), emptyMap())
        assertEquals(listOf("a", "b"), merged.disabled)
    }

    @Test
    fun notesDropBlankEntries() {
        val sanitized = MarketStateCodec.sanitizeNotes(mapOf("a" to "  ", "b" to "keep"))
        assertEquals(mapOf("b" to "keep"), sanitized)
    }

    @Test
    fun notesAreTruncatedToLimit() {
        val long = "x".repeat(MarketStateCodec.MAX_NOTE + 50)
        val sanitized = MarketStateCodec.sanitizeNotes(mapOf("a" to long))
        assertEquals(MarketStateCodec.MAX_NOTE, sanitized.getValue("a").length)
    }

    @Test
    fun encodeStringEscapesJsonMetacharacters() {
        assertEquals("\"a\\\"b\"", MarketStateCodec.encodeString("a\"b"))
        assertEquals("\"a\\\\b\"", MarketStateCodec.encodeString("a\\b"))
        assertEquals("\"a\\nb\"", MarketStateCodec.encodeString("a\nb"))
        assertEquals("\"a\\u0001b\"", MarketStateCodec.encodeString("a\u0001b"))
    }

    @Test
    fun encodeProducesSingleJsonObject() {
        val text = MarketStateCodec.encode(MarketStateCodec.merge(diskState(), listOf("a"), emptyMap()))
        assertTrue(text.startsWith("{"))
        assertTrue(text.trimEnd().endsWith("}"))
        // 顶层只能有一个对象：多出一个顶层节点会让解析方直接读不出这个文件。
        assertEquals(text.count { it == '{' }, text.count { it == '}' })
    }
}
