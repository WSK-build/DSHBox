package com.dshbox.remote.session

import com.dshbox.remote.model.Device
import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.Endpoint
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.session.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备档案存档测试：代次字段往返、旧版档案兼容、损坏行跳过、地址编辑的
 * 代次自增与标识全局唯一。
 */
class DeviceArchiveGenerationTest {

    private fun store() = DeviceStore(InMemoryKeyValueStore())

    private val device = Device(
        id = DeviceId("dev-a"),
        name = "工作电脑",
        endpoints = listOf(
            Endpoint(EndpointId("ep-1"), "局域网", "http://192.168.1.5:3081", "dsh-pocket", generation = 3),
        ),
        addedAtMillis = 1_700_000_000_000,
    )

    @Test fun generationSurvivesArchiveRoundtrip() {
        val store = store()
        store.save(device)
        val loaded = store.find(DeviceId("dev-a"))!!
        assertEquals(3L, loaded.endpoints.single().generation)
    }

    @Test fun legacyArchiveWithoutGenerationDecodesWithZero() {
        // 旧版编码（端点四字段一组）：代次按 0 处理，清单可读
        val legacy = "1|dev-a|工作电脑|1700000000000|1|ep-1|局域网|http://192.168.1.5:3081|dsh-pocket"
        val store = store()
        store.save(device) // 占位写入被下述直接放键值覆盖
        val rawStore = InMemoryKeyValueStore()
        rawStore.putString("device.dev-a", legacy)
        val loaded = DeviceStore(rawStore).find(DeviceId("dev-a"))!!
        assertEquals(0L, loaded.endpoints.single().generation)
        assertEquals("dsh-pocket", loaded.endpoints.single().parserId)
        // 旧档案的中文通道标签读取时归一化为枚举名
        assertEquals("LAN", loaded.endpoints.single().channelLabel)
    }

    @Test fun corruptRowsAreSkippedWhileGoodRowsSurvive() {
        val rawStore = InMemoryKeyValueStore()
        rawStore.putString("device.dev-good", DeviceArchiveCodec.encode(device))
        rawStore.putString("device.dev-broken", "2|dev-broken|broken-name|not-a-number|1|ep|l|u|p|0")
        rawStore.putString("device.dev-truncated", "2|dev-truncated|x|1")
        val devices = DeviceStore(rawStore).devices()
        assertEquals(listOf("dev-a"), devices.map { it.id.value })
    }

    @Test fun targetEditBumpsGenerationAndKeepsIdentity() {
        // 重新配对路径（rePair）以「读取-改写-保存」更新端点：代次自增随存档持久化
        val store = store()
        store.save(device)
        val loaded = store.find(DeviceId("dev-a"))!!
        store.save(
            loaded.copy(
                endpoints = loaded.endpoints.map {
                    if (it.id == EndpointId("ep-1")) {
                        it.copy(targetUrl = "https://tunnel.example.com/", generation = it.generation + 1)
                    } else {
                        it
                    }
                },
            ),
        )
        val endpoint = store.find(DeviceId("dev-a"))!!.endpoints.single()
        assertEquals("https://tunnel.example.com/", endpoint.targetUrl)
        assertEquals(4L, endpoint.generation)
        assertEquals("ep-1", endpoint.id.value)
        // 原内存对象不被误更新
        assertEquals(3L, device.endpoints.single().generation)
    }

    @Test fun pairingIdsAreGloballyUnique() {
        val deviceIds = (0 until 1_000).map { PairingIds.newDeviceId() }
        val endpointIds = (0 until 1_000).map { PairingIds.newEndpointId() }
        assertEquals(1_000, deviceIds.toSet().size)
        assertEquals(1_000, endpointIds.toSet().size)
        assertNotEquals(deviceIds.first(), deviceIds.last())
    }

}
