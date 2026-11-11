package com.dshbox.app.ui.theme

import com.dshbox.app.common.Constants
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 运行期 DSH 地址状态（派生口径）单测：确证值优先、异常形态
 * 与未确证回退默认常量。
 */
class AppDshAddressStateTest {

    /** 未确证（null）：回退编译期默认常量。 */
    @Test
    fun derive_fallsBackToConstant_whenUnconfirmed() {
        assertEquals(Constants.DSH_BASE_URL, AppDshAddressState.derive(null))
    }

    /** 空串/畸形形态：一并回退常量。 */
    @Test
    fun derive_fallsBackToConstant_onMalformed() {
        assertEquals(Constants.DSH_BASE_URL, AppDshAddressState.derive(""))
        assertEquals(Constants.DSH_BASE_URL, AppDshAddressState.derive("http://"))
        assertEquals(Constants.DSH_BASE_URL, AppDshAddressState.derive("https://127.0.0.1:3080"))
    }

    /** 合法确证值原样透传。 */
    @Test
    fun derive_passesThroughConfirmed() {
        assertEquals(
            "http://127.0.0.1:3083",
            AppDshAddressState.derive("http://127.0.0.1:3083"),
        )
        assertEquals(
            "http://127.0.0.1:3099",
            AppDshAddressState.derive("http://127.0.0.1:3099"),
        )
    }

    /** 读写往返：update 后读 baseUrl 即运行期地址；reset 回默认。 */
    @Test
    fun updateAndReset_roundTrip() {
        val previous = AppDshAddressState.confirmedBaseUrl
        try {
            AppDshAddressState.update("http://127.0.0.1:3087")
            assertEquals("http://127.0.0.1:3087", AppDshAddressState.baseUrl)
            AppDshAddressState.update(null)
            assertEquals(Constants.DSH_BASE_URL, AppDshAddressState.baseUrl)
        } finally {
            AppDshAddressState.update(previous)
        }
    }
}
