package com.dshbox.remote.session

import com.dshbox.remote.model.Device
import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.Endpoint
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.model.SessionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 真实生产工厂的直连分支守卫：非 https 端点显式拒绝（会话 Failed，不冒泡
 * 到渲染器构造期崩溃）；https 正常产出无代理会话；直连建立不触碰槽位
 * 绑定、端口记录与 cookie 罐（存档层零副作用）。
 */
class DefaultSessionFactoryDirectTest {

    private val store = InMemoryKeyValueStore()
    private val vaultDir = createTempVaultDir()

    private val device = Device(
        id = DeviceId("dev-1"),
        name = "direct-dev",
        endpoints = listOf(
            Endpoint(
                id = EndpointId("ep-https"),
                channelLabel = "PUBLIC",
                targetUrl = "https://zcode.z.ai/remote/abc",
                parserId = "zcode-remote",
                sessionMode = SessionMode.DIRECT.name,
            ),
            Endpoint(
                id = EndpointId("ep-http"),
                channelLabel = "LAN",
                targetUrl = "http://192.168.1.5:3081",
                parserId = "generic-url",
                sessionMode = SessionMode.DIRECT.name,
            ),
        ),
        addedAtMillis = 1_700_000_000_000,
    )

    private fun factory() = DefaultSessionFactory(
        StubContext(),
        storeOverride = { store },
        vaultDirOverride = vaultDir,
        cipherOverride = PlainVaultCipher,
    )

    @Test
    fun directHttpsEndpointBuildsNoProxySessionWithoutArchiveSideEffects() {
        DeviceStore(store).save(device)
        val session = factory().createSession(DeviceId("dev-1"), device.endpoints.first())
        // https 直连：origin 即真实上游，首载地址为真实入口，无门禁
        assertEquals("https://zcode.z.ai", session.loopbackOrigin)
        assertEquals("https://zcode.z.ai", session.upstreamOrigin)
        assertEquals("https://zcode.z.ai/remote/abc", session.initialLoadUrl)
        assertTrue(session is DirectSession)
        // 存档层零副作用：无槽位绑定、无端口记录、无 cookie 罐条目（凭据存档
        // 目录在工厂构造期即装配创建，只是直连路径不向其中写入任何条目）
        assertTrue(store.keys().none { it.startsWith("slot.") })
        assertTrue(vaultDir.isDirectory)
        assertTrue(vaultDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun directHttpEndpointIsRejectedExplicitlyBeforeRendererConstruction() {
        DeviceStore(store).save(device)
        val endpoint = device.endpoints.last()
        val exception = assertThrows(SessionStartException::class.java) {
            factory().createSession(DeviceId("dev-1"), endpoint)
        }
        // 失败可读且不含 URL 等载荷细节
        assertEquals("Direct mode requires an https endpoint", exception.message)
        // 失败路径同样不落任何存档副作用
        assertTrue(store.keys().none { it.startsWith("slot.") })
    }

    @Test
    fun directSessionForMissingDeviceFailsAsExpiredArchive() {
        val exception = assertThrows(SessionStartException::class.java) {
            factory().createSession(DeviceId("dev-1"), device.endpoints.first())
        }
        assertTrue(exception.message!!.contains("Device is removed"))
    }

    @Test
    fun rePairEndpointPersistsExplicitSessionModeAndBumpsGeneration() {
        // 显式模式选择经工厂的重配对链路落盘（档案 v4 字段）：既有 https 直
        // 连端点同 URL 提交代理档——模式真变更，代次自增并持久化（在途建
        // 立按代次漂移拒收的数据前提）
        DeviceStore(store).save(device)
        val archiver = factory()
        val endpointId = device.endpoints.first().id
        val changed = archiver.rePairEndpoint(
            DeviceId("dev-1"),
            endpointId,
            com.dshbox.remote.parse.ParsedPairing(
                parserId = "zcode-remote",
                deviceName = "dev",
                endpoints = listOf(
                    com.dshbox.remote.parse.ParsedEndpoint(
                        targetUrl = "https://zcode.z.ai/remote/abc",
                        channelLabel = "PUBLIC",
                        credential = null,
                    ),
                ),
            ),
            requestedSessionMode = SessionMode.PROXIED.name,
        )
        assertEquals(setOf(endpointId), changed)
        val endpoint = DeviceStore(store).find(DeviceId("dev-1"))!!.endpoints.first { it.id == endpointId }
        assertEquals(SessionMode.PROXIED.name, endpoint.sessionMode)
        assertEquals(1L, endpoint.generation)
    }

    private fun createTempVaultDir(): File =
        File(System.getProperty("java.io.tmpdir"), "sf-direct-${System.nanoTime()}")
            .apply { mkdirs() }
}
