package com.dshbox.remote.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手动输入/重配对载荷的输入口径测试：确认门禁放宽到任意非空 scheme、JSON
 * 与竖线分隔形态；整段载荷优先的合成规则（原样提交 vs ?auth= 追加）。
 */
class ManualPayloadSupportTest {

    // ---- 确认门禁 ----

    @Test fun gateAcceptsAllRoutablePayloadShapes() {
        assertTrue(ManualPayloadSupport.isPlausiblePayload("http://192.168.1.5:3081"))
        assertTrue(ManualPayloadSupport.isPlausiblePayload("https://gw.example.com/pair#s3cret"))
        assertTrue(ManualPayloadSupport.isPlausiblePayload("dshremote://pair?token=t&server=192.168.1.5:8787"))
        assertTrue(ManualPayloadSupport.isPlausiblePayload("http://192.168.1.8:8787|long-term-token"))
        assertTrue(ManualPayloadSupport.isPlausiblePayload("""{"type":"dsh-mobile-iroh-pairing"}"""))
        assertTrue(ManualPayloadSupport.isPlausiblePayload("  http://host  ")) // 前后空白容忍
    }

    @Test fun gateRejectsShapelessTextAndEmptyInput() {
        assertFalse(ManualPayloadSupport.isPlausiblePayload(""))
        assertFalse(ManualPayloadSupport.isPlausiblePayload("   "))
        assertFalse(ManualPayloadSupport.isPlausiblePayload("random text payload"))
        assertFalse(ManualPayloadSupport.isPlausiblePayload("192.168.1.5:3081")) // 无 scheme 无竖线
    }

    // ---- 整段载荷优先合成 ----

    @Test fun delimitedAndNonHttpPayloadsAreSubmittedVerbatim() {
        // 含竖线：原样提交（令牌栏忽略——竖线后内容可能是载荷自身组成部分）
        assertEquals(
            "http://192.168.1.8:8787|long-term-token",
            ManualPayloadSupport.compose("http://192.168.1.8:8787|long-term-token", "ignored"),
        )
        // 自定义 scheme：原样提交
        assertEquals(
            "dshremote://pair?token=tok&server=192.168.1.5:8787",
            ManualPayloadSupport.compose("dshremote://pair?token=tok&server=192.168.1.5:8787", ""),
        )
        // JSON 描述符：原样提交
        assertEquals("""{"type":"pair"}""", ManualPayloadSupport.compose("""{"type":"pair"}""", "x"))
    }

    @Test fun tokenAppendsToPlainHttpAddressOnly() {
        assertEquals(
            "http://192.168.1.6:3082/?auth=bridge-token",
            ManualPayloadSupport.compose("http://192.168.1.6:3082/", "bridge-token"),
        )
        // 已有 query 用 & 连接
        assertEquals(
            "http://host:3082/?room=x&auth=tok",
            ManualPayloadSupport.compose("http://host:3082/?room=x", "tok"),
        )
        // 令牌栏为空：原样返回
        assertEquals("http://host:3082/", ManualPayloadSupport.compose("http://host:3082/", "  "))
    }

    @Test fun addressWithEmbeddedCredentialsIgnoresTokenField() {
        // userinfo 内嵌凭据：不再追加（避免凭据双写歧义），原样提交
        assertEquals(
            "http://admin:s3cret@host:3082/",
            ManualPayloadSupport.compose("http://admin:s3cret@host:3082/", "tok"),
        )
    }

    @Test fun emptyAddressComposesToEmpty() {
        assertEquals("", ManualPayloadSupport.compose("", "tok"))
        assertEquals("", ManualPayloadSupport.compose("   ", ""))
    }
}
