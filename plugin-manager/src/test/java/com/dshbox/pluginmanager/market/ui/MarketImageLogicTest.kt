package com.dshbox.pluginmanager.market.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 图标回落决策（[avatarFallbackUrl]）的四态钉死。
 *
 * 这一行决定"要不要为一张卡片额外发一次 github 头像请求"：只有图标明确
 * 失败才回落——预取会让每张带图标的卡片都多发一次请求，未失败就回落会让
 * 页面出现"先头像后图标"的闪切。
 */
class MarketImageLogicTest {

    @Test
    fun iconPresentNeverFallsBack() {
        assertNull(avatarFallbackUrl(iconPresent = true, iconFailed = false, owner = "owner", sizeDp = 18))
    }

    @Test
    fun iconStillLoadingDoesNotFallBack() {
        assertNull(avatarFallbackUrl(iconPresent = false, iconFailed = false, owner = "owner", sizeDp = 18))
    }

    @Test
    fun iconFailedFallsBackToAvatar() {
        assertEquals(
            "https://github.com/owner.png?size=144",
            avatarFallbackUrl(iconPresent = false, iconFailed = true, owner = "owner", sizeDp = 18),
        )
    }

    @Test
    fun blankOwnerYieldsNoFallbackUrl() {
        assertNull(avatarFallbackUrl(iconPresent = false, iconFailed = true, owner = "  ", sizeDp = 18))
    }
}
