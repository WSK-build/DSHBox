package com.dshbox.dsh.renderer.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 页面外链宿主通道消息的解析契约：合法 JSON 帧返回绝对 http(s) 地址原文；
 * 非 JSON、类型不符、缺字段、非 http(s)、带内嵌凭据、超长与相对地址一律
 * 拒绝（返回 null，调用方不外放）。
 */
class DshExternalMessageTest {

    @Test fun validAbsoluteHttpsFrameParses() {
        assertEquals(
            "https://example.com/path?q=1#section",
            DshExternalMessage.parse("{\"type\":\"open-external\",\"url\":\"https://example.com/path?q=1#section\"}"),
        )
    }

    @Test fun validHttpFrameWithEscapedUrlParses() {
        // 截断类转义 URL 也按原文返回：地址层校验只看 scheme/凭据/长度
        assertEquals(
            "http://example.com/a%20b",
            DshExternalMessage.parse("{\"url\":\"http:\\/\\/example.com\\/a%20b\",\"type\":\"open-external\"}"),
        )
    }

    @Test fun nonJsonRejected() {
        assertNull(DshExternalMessage.parse("not json"))
        assertNull(DshExternalMessage.parse("[1,2,3]"))
        assertNull(DshExternalMessage.parse(""))
        assertNull(DshExternalMessage.parse(null))
        assertNull(DshExternalMessage.parse(42))
    }

    @Test fun trailingContentRejected() {
        // 结构多余尾巴不得截断放行
        assertNull(DshExternalMessage.parse("{\"type\":\"open-external\",\"url\":\"https://a.example/\"} trail"))
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\",\"url\":\"https://a.example/\"}{\"url\":\"https://b.example/\"}",
        ))
    }

    @Test fun wrongTypeOrMissingFieldsRejected() {
        assertNull(DshExternalMessage.parse("{\"type\":\"other\",\"url\":\"https://a.example/\"}"))
        assertNull(DshExternalMessage.parse("{\"url\":\"https://a.example/\"}"))
        assertNull(DshExternalMessage.parse("{\"type\":\"open-external\"}"))
        assertNull(DshExternalMessage.parse("{}"))
        // url 字段非字符串与对象整体重复均按结构非法拒绝
        assertNull(DshExternalMessage.parse("{\"type\":\"open-external\",\"url\":42}"))
    }

    @Test fun nonHttpSchemesRejected() {
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\",\"url\":\"dshbox://open-external?url=x\"}",
        ))
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\",\"url\":\"javascript:alert(1)\"}",
        ))
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\",\"url\":\"data:text/plain,hi\"}",
        ))
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\",\"url\":\"file:///etc/passwd\"}",
        ))
    }

    @Test fun embeddedCredentialsRejected() {
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\",\"url\":\"https://user:pass@example.com/\"}",
        ))
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\",\"url\":\"https://user@example.com/\"}",
        ))
    }

    @Test fun oversizedUrlRejected() {
        val path = "a".repeat(DshExternalMessage.MAX_URL_CHARS)
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\",\"url\":\"https://example.com/$path\"}",
        ))
    }

    @Test fun relativeUrlRejected() {
        // 相对地址无 scheme，绝对化由页面侧完成；通道消息必须已是绝对地址。
        // 协议相对形态 `//evil` 经 URI 解析无 scheme，同样拒绝（页面侧会先
        // 以当前 origin 绝对化，通道消息不接受歧义输入）
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\",\"url\":\"/relative/path\"}",
        ))
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\",\"url\":\"other/page.html\"}",
        ))
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\",\"url\":\"//evil/path?a=1\"}",
        ))
    }

    @Test fun uppercaseSchemeAcceptedAsHttp() {
        // scheme 大小写在地址层校验按 lower 归一：HTTP: 与 http 同判（绝对
        // http(s) 且无凭据即受理；原文外放不动）
        assertEquals(
            "HTTP://example.com/path",
            DshExternalMessage.parse(
                "{\"type\":\"open-external\",\"url\":\"HTTP://example.com/path\"}",
            ),
        )
        assertEquals(
            "HTTPS://example.com/",
            DshExternalMessage.parse(
                "{\"type\":\"open-external\",\"url\":\"HTTPS://example.com/\"}",
            ),
        )
        // 大写非 http scheme 仍拒绝（lower 后不进 http/s 白名单）
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\",\"url\":\"JAVASCRIPT:alert(1)\"}",
        ))
    }

    @Test fun ipv6LoopbackLiteralAccepted() {
        // IPv6 字面量是合法的绝对 http(s) 地址（host 非空）；外放决策在外放
        // 路径处理，通道层不再附加回环限制
        assertEquals(
            "http://[::1]:8080/x",
            DshExternalMessage.parse(
                "{\"type\":\"open-external\",\"url\":\"http://[::1]:8080/x\"}",
            ),
        )
    }

    @Test fun nullByteEscapesRejected() {
        // `\u0000` 转义经 JSON 还原为控制字符后，地址层 URI 解析对 NUL 直接
        // 失败（非法字符），整条拒绝——控制字符不能伪造 host 或拆分地址
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\"," +
                "\"url\":\"http://example.com/\\u0000x\"}",
        ))
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\"," +
                "\"url\":\"\\u0000http://example.com/\"}",
        ))
        // 非转义形态的控制字符落在消息文本里：JSON 结构层按 code < 0x20 拒绝
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\",\"url\":\"http://a/\"}\u0000",
        ))
    }

    @Test fun duplicatedUrlKeyRejected() {
        // 结构严格口径：重复键（type/url/未知名）一律整条拒绝，不做后写覆盖
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\",\"url\":\"http://a.example/\"," +
                "\"type\":\"open-external\"}",
        ))
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\",\"url\":\"http://a.example/\"," +
                "\"url\":\"http://b.example/\"}",
        ))
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\",\"x\":1,\"type\":\"open-external\"," +
                "\"url\":\"http:///b\"}",
        ))
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\",\"x\":1,\"x\":2," +
                "\"url\":\"http://a.example/\"}",
        ))
    }

    @Test fun duplicateTypeAfterUnknownKeyRejected() {
        // 题设对抗帧：未知键夹在中间，type 第二次出现 → 整帧拒绝
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"other\",\"x\":1,\"type\":\"open-external\",\"url\":\"http://a/b\"}",
        ))
    }

    @Test fun nestedObjectUrlValueRejected() {
        // url 字段为嵌套对象：结构层要求 url 是字符串，非字符串按缺字段拒绝
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\",\"url\":{\"href\":\"http://a.example/\"}}",
        ))
    }

    @Test fun wholeFrameOversizeRejected() {
        // 整帧超长（出现于 MAX_MESSAGE_CHARS 上限）在结构层即拒绝；单独拼接
        // 超长 url 的用例由 oversizedUrlRejected 覆盖（此处锁定整帧阈值）
        val fill = "a".repeat(DshExternalMessage.MAX_URL_CHARS + 128)
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\",\"url\":\"http://example.com/$fill\"}",
        ))
        // 恰在上限内的帧仍可解析（锁边界：阈值不是更收紧的魔法数）
        val within = "a".repeat(DshExternalMessage.MAX_URL_CHARS - 128)
        assertEquals(
            "http://example.com/$within",
            DshExternalMessage.parse(
                "{\"type\":\"open-external\",\"url\":\"http://example.com/$within\"}",
            ),
        )
    }

    @Test fun emptyUrlStringRejected() {
        // 空串与纯空白地址：无 host 可提取，一律拒绝
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\",\"url\":\"\"}",
        ))
        assertNull(DshExternalMessage.parse(
            "{\"type\":\"open-external\",\"url\":\"   \"}",
        ))
    }
}
