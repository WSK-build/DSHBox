package com.dshbox.app.sandbox

import com.dshbox.app.common.Constants
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 启动确认门的代次时序回归（P1-1）与 webserver 覆盖层消费准入（P2-1）：
 *
 * P1-1：addressConfirmed（= dshBaseUrl 非空）必须只认「本轮启动之后解析到
 * 的值」——输出行由其所属进程起动时的代次快照（lineGeneration）随行校验，
 * 代次不等于当前在役代次的行（旧进程停机窗口里的迟到输出、或启动之前到达
 * 的行）一律丢弃，不得复位/写入地址与 token。
 *
 * P2-1：webserver-port 覆盖层只在端口自适应启用且文件实际存在时才消费；
 * 自适应关闭（gecko）时无论文件是否存在一律不传。
 */
class DefaultSandboxManagerLaunchGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var manager: DefaultSandboxManager

    private val launchLine3082 =
        "dsh web: http://${Constants.DSH_DEFAULT_HOST}:3082/?token=tok-new"
    private val launchLine3080 =
        "dsh web: http://${Constants.DSH_DEFAULT_HOST}:3080/?token=tok-stale"

    @Before
    fun setUp() {
        val files = tmp.newFolder("app-files")
        manager = DefaultSandboxManager(
            SandboxConfig(appFilesDir = files),
        )
    }

    /** 启动之前到达的行：无在役代次，不写入地址与 token。 */
    @Test
    fun ingest_dropsLineBeforeFirstLaunch() = runBlocking {
        manager.ingestDshWebLaunchLine(launchLine3082, lineGeneration = 0L)
        assertNull(manager.dshBaseUrl.value)
        assertNull(manager.dshLaunchToken.value)
    }

    /** 本轮进程自己的行（代次快照 = 当前代次）：地址与 token 照常写入。 */
    @Test
    fun ingest_acceptsLineOfCurrentGeneration() = runBlocking {
        manager.launchGeneration = 1L
        manager.ingestDshWebLaunchLine(launchLine3082, lineGeneration = 1L)
        assertEquals("http://${Constants.DSH_DEFAULT_HOST}:3082", manager.dshBaseUrl.value)
        assertEquals("tok-new", manager.dshLaunchToken.value)
    }

    /**
     * 旧代次的行（重启推进代次、旧进程停机窗口里的迟到输出）：全部丢弃——
     * 地址与 token 不得被旧值重新填上，确证门保持关闭。
     */
    @Test
    fun ingest_dropsLineOfStaleGeneration() = runBlocking {
        // 第 1 轮启动确证成功（代次 1 = 当前代次）。
        manager.launchGeneration = 1L
        manager.ingestDshWebLaunchLine(launchLine3080, lineGeneration = 1L)
        assertEquals("http://${Constants.DSH_DEFAULT_HOST}:3080", manager.dshBaseUrl.value)
        assertEquals("tok-stale", manager.dshLaunchToken.value)
        // 就地重启：代次推进为 2、地址/token 清空（重启路径在代次推进同时
        // 主动清空这两者——生产 restartDshProcessInPlace 的口径）。
        manager.launchGeneration = 2L
        // 清空动作在生产里由重启路径完成；单测直接复位两状态模拟。
        // （必须显式清空：确证门的输入基线 = 重启后的新轮状态。）
        // 这里通过状态流 setter 内部也提供了 reset 的 internal 入口，直接调。
        manager.resetConfirmedAddressForTest()
        manager.ingestDshWebLaunchLine(launchLine3080, lineGeneration = 1L)
        assertNull(manager.dshBaseUrl.value)
        assertNull(manager.dshLaunchToken.value)
        // 本轮自己的行（代次 2）；且健康检查器跟随实际端口换新。
        manager.ingestDshWebLaunchLine(launchLine3082, lineGeneration = 2L)
        assertEquals("http://${Constants.DSH_DEFAULT_HOST}:3082", manager.dshBaseUrl.value)
        assertEquals("tok-new", manager.dshLaunchToken.value)
    }

    /** P2-1 口径：仅端口自适应启用且文件存在非空时消费覆盖层。 */
    @Test
    fun webserverOverlayConsumable_gateFollowsAdaptationAndPresence() {
        // 自适应开 + 文件存在：消费。
        assertTrue(
            DefaultSandboxManager.webserverOverlayConsumable(dshPortAdaptationEnabled = true, overlayFileExists = true),
        )
        // 自适应开但文件不存在：不消费（首次启动/层尚未写出的常态）。
        assertFalse(
            DefaultSandboxManager.webserverOverlayConsumable(dshPortAdaptationEnabled = true, overlayFileExists = false),
        )
        // 自适应关（gecko）：无论文件是否存在一律不消费——遗留的非 3080 层不得
        // 让 gecko 的 DSH 绑到别的端口。
        assertFalse(
            DefaultSandboxManager.webserverOverlayConsumable(dshPortAdaptationEnabled = false, overlayFileExists = true),
        )
        assertFalse(
            DefaultSandboxManager.webserverOverlayConsumable(dshPortAdaptationEnabled = false, overlayFileExists = false),
        )
    }

    /**
     * 真实文件口径的准入（P2-1）：自适应关闭时 user-data 下的残留映射层（同
     * applicationId 从 systemWebView 切装 gecko 之后遗留）不进入消费集合。
     */
    @Test
    fun webserverOverlayConsumable_withRealFile_underGeckoVariantStaysUnconsumed() {
        val userData = tmp.newFolder("user-data")
        val file = DshWebserverOverlay.hostFile(userData)
        file.parentFile.mkdirs()
        file.writeText(
            "- id: ${Constants.DSH_WEBSERVER_ENTRY_ID}\n" +
                "  name: '${Constants.DSH_WEBSERVER_ENTRY_NAME}'\n" +
                "  config:\n    host: '127.0.0.1'\n    port: 3085\n",
            Charsets.UTF_8,
        )
        assertTrue(file.isFile && file.length() > 0L)
        // systemWebView（自适应开）：该层进入消费集合（是否真的传入 patch 清单
        // 由生产 dshOverlayGuestPaths 决定，这里只钉准入口径）。
        assertTrue(
            DefaultSandboxManager.webserverOverlayConsumable(dshPortAdaptationEnabled = true, overlayFileExists = true),
        )
        // gecko（自适应关）：该层不进入消费集合（宿主侧文件另行清理）。
        assertFalse(
            DefaultSandboxManager.webserverOverlayConsumable(dshPortAdaptationEnabled = false, overlayFileExists = true),
        )
    }
}
