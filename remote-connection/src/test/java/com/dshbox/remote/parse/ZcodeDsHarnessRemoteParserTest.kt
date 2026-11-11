package com.dshbox.remote.parse

import com.dshbox.remote.parse.PairingParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账号型直连 URL 签名解析器（ZCode 工作区 / DSH Remote）：签名识别、设备
 * 默认名、直连会话模式透传与注册表路由优先级（generic 兜底之前）。
 */
class ZcodeDsHarnessRemoteParserTest {

    // ---- ZCode 工作区 ----

    @Test fun zcodeRemoteUrlIsClaimed() {
        val parser = ZcodeRemoteParser()
        assertTrue(parser.matches("https://zcode.z.ai/remote/abc"))
        assertTrue(parser.matches("https://zcode.z.ai/remote/abc?tab=1"))
        assertTrue(parser.matches("https://zcode.z.ai/remote"))
        assertFalse(parser.matches("https://zcode.z.ai/console/abc"))
        // 段匹配：`/remoteX` 这类把签名头段粘跑的形态不再命中
        assertFalse(parser.matches("https://zcode.z.ai/remoteX/abc"))
        assertFalse(parser.matches("https://evil.example/remote/abc"))
        assertFalse(parser.matches("http://192.168.1.5:3081"))
    }

    @Test fun zcodeRemoteParsesToDirectEndpoint() {
        val pairing = ZcodeRemoteParser().parse("https://zcode.z.ai/remote/abc")
        assertEquals(ParserIds.ZCODE_REMOTE, pairing.parserId)
        assertEquals("ZCode 工作区", pairing.deviceName)
        val endpoint = pairing.endpoints.single()
        assertEquals("https://zcode.z.ai/remote/abc", endpoint.targetUrl)
        assertEquals(ChannelKind.PUBLIC.name, endpoint.channelLabel)
        assertNull(endpoint.credential)
        assertEquals("DIRECT", endpoint.sessionMode)
    }

    @Test fun zcodeRemoteRejectsCredentialEmbeddedUrl() {
        val parser = ZcodeRemoteParser()
        // 签名命中且内嵌凭据（user:pass@host）：显式拒绝，异常文案经脱敏
        val error = runCatching {
            parser.parse("https://user:pass@zcode.z.ai/remote/abc")
        }.exceptionOrNull()
        assertTrue(error is PairingParseException)
        assertFalse(error!!.message!!.contains("user:pass"))
    }

    @Test fun zcodeRemoteRejectsPlaintextTargetAndKeepsHttpsDirect() {
        val parser = ZcodeRemoteParser()
        // 签名命中但目标明文（http:）：显式拒绝建档——http+DIRECT 组合会落
        // 入恒失败档案；异常文案对原始载荷脱敏
        val error = runCatching {
            parser.parse("http://zcode.z.ai/remote/abc")
        }.exceptionOrNull()
        assertTrue(error is PairingParseException)
        assertTrue(error!!.message!!.contains("https address"))
        // 文案出自脱敏通道（plain URL 无遮蔽项，原文按截断口径呈现）
        assertTrue(error.message!!.contains("http://zcode.z.ai/remote/abc"))
        // https 目标仍产出直连端点（正常路径不受明文拒绝影响）
        assertEquals(
            "DIRECT",
            parser.parse("https://zcode.z.ai/remote/abc").endpoints.single().sessionMode,
        )
    }

    // ---- DSH Remote ----

    @Test fun dsHarnessRemoteUrlIsClaimed() {
        val parser = DsHarnessRemoteParser()
        assertTrue(parser.matches("https://dsh.r2049.cn/app/xyz"))
        assertTrue(parser.matches("https://dsh.r2049.cn/app/xyz?next=/home"))
        // 兑换链接签名（code 非空；允许尾斜杠与查询参数）
        assertTrue(parser.matches("https://dsh.r2049.cn/api/v1/auth/q/ab12cd34"))
        assertTrue(parser.matches("https://dsh.r2049.cn/api/v1/auth/q/ab12cd34/"))
        assertTrue(parser.matches("https://dsh.r2049.cn/api/v1/auth/q/ab12cd34?from=qr"))
        // 段匹配收紧：contains 宽松口径下的 `/login/app`、`/my/app` 不再命中
        assertFalse(parser.matches("https://dsh.r2049.cn/login"))
        assertFalse(parser.matches("https://dsh.r2049.cn/login/app"))
        assertFalse(parser.matches("https://dsh.r2049.cn/my/app/xyz"))
        assertFalse(parser.matches("https://evil.example/app/xyz"))
    }

    @Test fun dsHarnessAuthQOtherPathsNotClaimed() {
        val parser = DsHarnessRemoteParser()
        // `/api/v1/auth/` 下非 `q/` 分支与其余 api 路径不命中签名
        assertFalse(parser.matches("https://dsh.r2049.cn/api/v1/auth"))
        assertFalse(parser.matches("https://dsh.r2049.cn/api/v1/auth/login"))
        assertFalse(parser.matches("https://dsh.r2049.cn/api/v1/auth/refresh"))
        assertFalse(parser.matches("https://dsh.r2049.cn/api/v1/authq/abc"))
        assertFalse(parser.matches("https://dsh.r2049.cn/api/v1/auth/q/")) // 空 code
        // code 路径恰一段：`q/<code>/extra` 的粘跑段不命中（钓鱼形态拒绝）
        assertFalse(parser.matches("https://dsh.r2049.cn/api/v1/auth/q/abc/extra"))
        assertFalse(parser.matches("https://dsh.r2049.cn/api/v1/auth/q/abc/extra/deep"))
        assertFalse(parser.matches("https://dsh.r2049.cn/api/v2/auth/q/abc"))
        assertFalse(parser.matches("https://dsh.r2049.cn/api/"))
    }

    @Test fun dsHarnessAuthQRejectsPlaintextHttp() {
        val parser = DsHarnessRemoteParser()
        // 签名命中但 http 目标：显式拒绝建档（http+DIRECT 恒失败档案不产出）
        val error = runCatching {
            parser.parse("http://dsh.r2049.cn/api/v1/auth/q/ab12cd34")
        }.exceptionOrNull()
        assertTrue(error is PairingParseException)
        assertTrue(error!!.message!!.contains("https address"))
    }

    @Test fun dsHarnessAuthQTrailingSlashAndQueryKeepOriginalTarget() {
        val parser = DsHarnessRemoteParser()
        val pairing = parser.parse("https://dsh.r2049.cn/api/v1/auth/q/ab12cd34/")
        assertEquals(ParserIds.DS_HARNESS_REMOTE, pairing.parserId)
        val endpoint = pairing.endpoints.single()
        // targetUrl 保留原始完整地址（含尾斜杠）
        assertEquals("https://dsh.r2049.cn/api/v1/auth/q/ab12cd34/", endpoint.targetUrl)
        assertEquals("DIRECT", endpoint.sessionMode)
        assertNull(endpoint.credential)
        val withQuery = parser.parse("https://dsh.r2049.cn/api/v1/auth/q/ab12cd34?from=qr")
        assertEquals(
            "https://dsh.r2049.cn/api/v1/auth/q/ab12cd34?from=qr",
            withQuery.endpoints.single().targetUrl,
        )
    }

    @Test fun dsHarnessRemoteParsesToDirectEndpoint() {
        val pairing = DsHarnessRemoteParser().parse("https://dsh.r2049.cn/app/xyz")
        assertEquals(ParserIds.DS_HARNESS_REMOTE, pairing.parserId)
        assertEquals("DSH Remote", pairing.deviceName)
        val endpoint = pairing.endpoints.single()
        assertEquals("https://dsh.r2049.cn/app/xyz", endpoint.targetUrl)
        assertEquals(ChannelKind.PUBLIC.name, endpoint.channelLabel)
        assertNull(endpoint.credential)
        assertEquals("DIRECT", endpoint.sessionMode)
    }

    @Test fun dsHarnessRemoteRejectsCredentialEmbeddedUrl() {
        val parser = DsHarnessRemoteParser()
        // 签名命中且内嵌凭据（user:pass@host）：显式拒绝，异常文案经脱敏
        val error = runCatching {
            parser.parse("https://user:pass@dsh.r2049.cn/app/xyz")
        }.exceptionOrNull()
        assertTrue(error is PairingParseException)
        assertFalse(error!!.message!!.contains("user:pass"))
    }

    @Test fun dsHarnessRemoteRejectsPlaintextTargetAndKeepsHttpsDirect() {
        val parser = DsHarnessRemoteParser()
        // 签名命中但目标明文（http:）：显式拒绝建档——http+DIRECT 组合会落
        // 入恒失败档案；异常文案对原始载荷脱敏
        val error = runCatching {
            parser.parse("http://dsh.r2049.cn/app/xyz")
        }.exceptionOrNull()
        assertTrue(error is PairingParseException)
        assertTrue(error!!.message!!.contains("https address"))
        // 文案出自脱敏通道（plain URL 无遮蔽项，原文按截断口径呈现）
        assertTrue(error.message!!.contains("http://dsh.r2049.cn/app/xyz"))
        // https 目标仍产出直连端点（正常路径不受明文拒绝影响）
        assertEquals(
            "DIRECT",
            parser.parse("https://dsh.r2049.cn/app/xyz").endpoints.single().sessionMode,
        )
    }

    // ---- 注册表路由：签名通道先于 generic 兜底 ----

    @Test fun registryRoutesDirectAccountUrlsBeforeGenericFallback() {
        val registry = ParserRegistry(
            urlParsers = listOf(ZcodeRemoteParser(), DsHarnessRemoteParser()),
        )
        assertEquals(
            ParserIds.ZCODE_REMOTE,
            registry.parse("https://zcode.z.ai/remote/abc").getOrThrow().parserId,
        )
        assertEquals(
            ParserIds.DS_HARNESS_REMOTE,
            registry.parse("https://dsh.r2049.cn/app/xyz").getOrThrow().parserId,
        )
        // 签名不命中的 URL 仍落通用通道（generic 不受直连解析器挤占）
        assertEquals(
            ParserIds.GENERIC,
            registry.parse("https://portal.example/page").getOrThrow().parserId,
        )
    }

    @Test fun genericChannelKeepsNullSessionMode() {
        // 通用通道不声明会话模式：建档按默认代理档处理
        val pairing = GenericUrlParser().parse("https://portal.example/page")
        assertNull(pairing.endpoints.single().sessionMode)
    }
}
