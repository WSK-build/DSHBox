package com.dshbox.remote.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * cookie 头名称解析契约：`getCookie` 串的 `name=value; …` 拆名——空/空白
 * 串空清单、多 cookie 拆全、两侧空白容忍、无 `=` 与空名段丢弃。
 */
class CookieNameParserTest {

    @Test fun nullAndBlankGiveEmptyList() {
        assertTrue(CookieNameParser.names(null).isEmpty())
        assertTrue(CookieNameParser.names("").isEmpty())
        assertTrue(CookieNameParser.names("   ").isEmpty())
    }

    @Test fun multipleCookiesParsed() {
        assertEquals(
            listOf("sid", "gate", "pref"),
            CookieNameParser.names("sid=abc; gate=tok123; pref=dark"),
        )
    }

    @Test fun surroundingWhitespaceTolerated() {
        assertEquals(
            listOf("sid", "gate"),
            CookieNameParser.names("  sid=abc ;  gate=tok "),
        )
    }

    @Test fun malformedSegmentsDropped() {
        // 无 `=` 的段与空名段丢弃；含值内 `=` 的段取首个 `=` 前
        assertEquals(
            listOf("sid", "weird"),
            CookieNameParser.names("junk; sid=abc; =novalue; weird=a=b"),
        )
    }

    @Test fun emptyValueStillParsesName() {
        assertEquals(listOf("sid"), CookieNameParser.names("sid="))
    }

    @Test fun singleCookie() {
        assertEquals(listOf("gate"), CookieNameParser.names("gate=tok"))
    }
}
