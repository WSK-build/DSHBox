package com.dshbox.remote.render

import android.graphics.Color
import com.dshbox.dsh.renderer.api.DshMainFrameNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染器配置组装契约：直连会话以真实上游 origin 为可信基准（AnyHttps 放行
 * 主框架 + `dshbox` 内部 scheme 承接页外链桥），代理会话保持回环基准与仅
 * 可信 origin 主框架，并同样注册 `dshbox` 内部 scheme；两档都声明启用宿主
 * 消息通道（systemWebView 适配器注册 `dshboxHost`，页面外链经该通道强制
 * 外放）。本机 DSH 容器组装不声明开关（默认 false，页面行为零变化）——该
 * 组装在 app 模块内，此处按配置默认值锁定反向口径。
 */
class RendererConfigAssemblyTest {

    private val loopbackOrigin = "http://127.0.0.3:51000"
    private val upstreamOrigin = "https://zcode.z.ai"

    @Test
    fun directModeUsesUpstreamOriginWithAnyHttpsAndDshboxScheme() {
        val config = buildRendererConfig(
            directMode = true,
            upstreamOrigin = upstreamOrigin,
            loopbackOrigin = loopbackOrigin,
            canvasColor = Color.WHITE,
        )
        assertEquals(upstreamOrigin, config.trustedBaseOrigin)
        assertEquals(DshMainFrameNavigation.AnyHttps, config.mainFrameNavigation)
        assertEquals(setOf(INTERNAL_SCHEME_DSHBOX), config.internalSchemes)
        assertTrue(config.hostMessageChannelEnabled)
        // 换行档透传：构造参数默认 true 锁定换行档口径
        assertTrue(config.enterKeyNewline)
        assertFalse(config.debuggingEnabled)
    }

    @Test
    fun proxiedModeKeepsLoopbackBaselineAndTrustedOnlyWithDshboxScheme() {
        val config = buildRendererConfig(
            directMode = false,
            upstreamOrigin = upstreamOrigin,
            loopbackOrigin = loopbackOrigin,
            canvasColor = Color.WHITE,
        )
        assertEquals(loopbackOrigin, config.trustedBaseOrigin)
        assertEquals(DshMainFrameNavigation.TrustedOriginOnly, config.mainFrameNavigation)
        assertEquals(setOf(INTERNAL_SCHEME_DSHBOX), config.internalSchemes)
        assertTrue(config.hostMessageChannelEnabled)
    }

    @Test fun sendModeConfigPropagatesToBothSessionShapes() {
        // 发送档：两档会话组装都按传入值写入配置（注入层据此不安装拦截）
        val config = buildRendererConfig(
            directMode = true,
            upstreamOrigin = upstreamOrigin,
            loopbackOrigin = loopbackOrigin,
            enterKeyNewline = false,
        )
        assertFalse(config.enterKeyNewline)

        val proxied = buildRendererConfig(
            directMode = false,
            upstreamOrigin = upstreamOrigin,
            loopbackOrigin = loopbackOrigin,
            enterKeyNewline = false,
        )
        assertFalse(proxied.enterKeyNewline)
    }

    @Test
    fun directModeFallsBackToLoopbackWhenUpstreamMissing() {
        // 理论不可达（工厂构造期已保证 origin 非空）；兜底口径锁定为回环值
        val config = buildRendererConfig(
            directMode = true,
            upstreamOrigin = null,
            loopbackOrigin = loopbackOrigin,
        )
        assertEquals(loopbackOrigin, config.trustedBaseOrigin)
    }

    @Test
    fun hostMessageChannelDefaultsToOff() {
        // 本机 DSH 容器组装不声明开关：配置默认值必须为 false（页面行为零
        // 变化；远程两档的显式 true 由上方两用例锁定）。锁默认值即锁本机口径
        val defaultConfig = com.dshbox.dsh.renderer.api.DshRendererConfig(
            trustedBaseOrigin = loopbackOrigin,
        )
        assertFalse(defaultConfig.hostMessageChannelEnabled)
        // 回车键开关的配置默认即换行档（true）：远程两档与本机容器都显式
        // 以设置值装配，默认值锁定页面层的换行语义
        assertTrue(defaultConfig.enterKeyNewline)
        // 导航型下载判定默认关闭：本机容器不装配下载器
        assertEquals(null, defaultConfig.downloadUrlPredicate)
    }

    @Test
    fun directModeCarriesDownloadNavigationPredicate() {
        val predicate: (String) -> Boolean = { it.contains("data-tencentsmh.com") }
        val config = buildRendererConfig(
            directMode = true,
            upstreamOrigin = upstreamOrigin,
            loopbackOrigin = loopbackOrigin,
            downloadUrlPredicate = predicate,
        )
        assertTrue(config.downloadUrlPredicate?.invoke("https://smhwb-gz-002.data-tencentsmh.com/x") == true)
        // 代理模式不装配下载监听，谓词不参与判定（组装层不传即 null）
        val proxied = buildRendererConfig(
            directMode = false,
            upstreamOrigin = upstreamOrigin,
            loopbackOrigin = loopbackOrigin,
        )
        assertEquals(null, proxied.downloadUrlPredicate)
    }
}
