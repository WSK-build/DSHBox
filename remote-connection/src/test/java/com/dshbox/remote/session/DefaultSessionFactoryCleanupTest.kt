package com.dshbox.remote.session

import com.dshbox.remote.model.Device
import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.Endpoint
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.model.SessionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设备删除/槽位首次分配的站点数据清理调度（假 cleaner，JVM）：删除按端
 * 点档案与槽位簿记聚合目标且去重（cookie URL 口径：回环恒 http、直连端
 * 点用真实 scheme）；首次分配清 cookie URL、既有绑定不清理；cookie 名解
 * 析容错。
 */
class DefaultSessionFactoryCleanupTest {

    /** 假 cleaner：记录每次调用，便于断言调度。 */
    private class RecordingCleaner : SessionDataCleaner {
        val calls = mutableListOf<Pair<List<String>, List<String>>>()

        override fun clearOrigins(origins: List<String>, cookieUrls: List<String>) {
            calls.add(origins to cookieUrls)
        }
    }

    private val store = InMemoryKeyValueStore()
    private val vaultDir = createTempVaultDir()

    private fun device(endpoints: List<Endpoint>) = Device(
        id = DeviceId("dev-1"),
        name = "cleanup-dev",
        endpoints = endpoints,
        addedAtMillis = 1_700_000_000_000,
    )

    private fun factory(cleaner: RecordingCleaner) = DefaultSessionFactory(
        StubContext(),
        storeOverride = { store },
        vaultDirOverride = vaultDir,
        cipherOverride = PlainVaultCipher,
        cleanerOverride = cleaner,
    )

    @Test fun removeDeviceWithMixedEndpointsDedupesTargets() {
        val endpoints = listOf(
            // 代理端点（端口已记账）、代理端点（从未建立会话无端口）、直连 https
            Endpoint(
                id = EndpointId("ep-proxy-1"),
                channelLabel = "LAN",
                targetUrl = "http://192.168.1.5:3080/app",
                parserId = "generic-url",
            ),
            Endpoint(
                id = EndpointId("ep-proxy-2"),
                channelLabel = "LAN",
                targetUrl = "http://192.168.1.5:3081/app",
                parserId = "generic-url",
            ),
            Endpoint(
                id = EndpointId("ep-direct"),
                channelLabel = "PUBLIC",
                targetUrl = "https://zcode.z.ai/remote/abc",
                parserId = "zcode-remote",
                sessionMode = SessionMode.DIRECT.name,
            ),
        )
        DeviceStore(store).save(device(endpoints))
        // 槽位与端点 0 的端口先记账（模拟既有会话痕迹）
        val allocator = SlotAllocator(store)
        val ip = allocator.bindDevice(DeviceId("dev-1")).loopbackIp
        allocator.recordPort(DeviceId("dev-1"), EndpointId("ep-proxy-1"), 51000)

        val cleaner = RecordingCleaner()
        factory(cleaner).removeDevice(DeviceId("dev-1"))

        // 一次调度：origin = 回环 51000 + 直连上游 + 直连注册域与同族站点
        // （zcode.z.ai → z.ai 族：z.ai、chat.z.ai、open.bigmodel.cn、
        // bigmodel.cn）；cookie URL 口径对应（回环槽位恒 http，其余 https）
        assertEquals(1, cleaner.calls.size)
        val (origins, cookieUrls) = cleaner.calls[0]
        assertEquals(
            setOf(
                "http://$ip:51000",
                "https://zcode.z.ai",
                "https://z.ai",
                "https://chat.z.ai",
                "https://open.bigmodel.cn",
                "https://bigmodel.cn",
            ),
            origins.toSet(),
        )
        assertEquals(
            setOf(
                "http://$ip/",
                "https://zcode.z.ai/",
                "https://z.ai/",
                "https://chat.z.ai/",
                "https://open.bigmodel.cn/",
                "https://bigmodel.cn/",
            ),
            cookieUrls.toSet(),
        )
        // 簿记确已回收
        assertNull(allocator.loopbackIpFor(DeviceId("dev-1")))
    }

    @Test fun removeDeviceWithoutBindingOnlyCleansDirectTargets() {
        // 只含直连端点且设备从未绑定槽位：无回环目标，清上游 origin/host 及
        // 其注册域与同族站点（z.ai 族）
        val endpoints = listOf(
            Endpoint(
                id = EndpointId("ep-direct"),
                channelLabel = "PUBLIC",
                targetUrl = "https://zcode.z.ai/remote/abc",
                parserId = "zcode-remote",
                sessionMode = SessionMode.DIRECT.name,
            ),
        )
        DeviceStore(store).save(device(endpoints))
        val cleaner = RecordingCleaner()
        factory(cleaner).removeDevice(DeviceId("dev-1"))
        assertEquals(1, cleaner.calls.size)
        assertEquals(
            setOf("https://zcode.z.ai", "https://z.ai", "https://chat.z.ai", "https://open.bigmodel.cn", "https://bigmodel.cn"),
            cleaner.calls[0].first.toSet(),
        )
        assertEquals(
            setOf("https://zcode.z.ai/", "https://z.ai/", "https://chat.z.ai/", "https://open.bigmodel.cn/", "https://bigmodel.cn/"),
            cleaner.calls[0].second.toSet(),
        )
    }

    @Test fun removeDeviceWithNoTargetsSkipsCleaner() {
        // 从未建立会话的纯代理设备：删除不触发清理调度（无目标）
        val endpoints = listOf(
            Endpoint(
                id = EndpointId("ep-proxy"),
                channelLabel = "LAN",
                targetUrl = "http://192.168.1.5:3080/app",
                parserId = "generic-url",
            ),
        )
        DeviceStore(store).save(device(endpoints))
        val cleaner = RecordingCleaner()
        factory(cleaner).removeDevice(DeviceId("dev-1"))
        assertTrue(cleaner.calls.isEmpty())
    }

    @Test fun firstSlotBindCleansNewHostSecondBindSkips() {
        val endpoints = listOf(
            Endpoint(
                id = EndpointId("ep-proxy"),
                channelLabel = "LAN",
                targetUrl = "http://192.168.1.5:3080/app",
                parserId = "generic-url",
            ),
        )
        DeviceStore(store).save(device(endpoints))
        val cleaner = RecordingCleaner()
        val factory = factory(cleaner)
        val session = factory.createSession(DeviceId("dev-1"), endpoints.first())
        // 首次绑定：清一次 cookie URL（无 origin，端口未知）；回环 socket
        // 恒 http scheme
        assertEquals(1, cleaner.calls.size)
        assertTrue(cleaner.calls[0].first.isEmpty())
        assertEquals(1, cleaner.calls[0].second.size)
        assertTrue(cleaner.calls[0].second[0].startsWith("http://127.0.0."))
        val firstCookieUrl = cleaner.calls[0].second[0]
        // 同设备的下一次会话（既有绑定）不再清理
        val before = cleaner.calls.size
        factory.createSession(DeviceId("dev-1"), endpoints.first())
        assertEquals(before, cleaner.calls.size)
        // 首绑的 cookie URL 与槽位簿记一致
        assertEquals(
            firstCookieUrl,
            "http://" + SlotAllocator(store).loopbackIpFor(DeviceId("dev-1")) + "/",
        )
        session.start()
        session.stop()
    }

    private companion object {
        fun createTempVaultDir(): File =
            File(System.getProperty("java.io.tmpdir"), "cleanup-${java.util.UUID.randomUUID()}")
                .apply { mkdirs() }
    }
}
