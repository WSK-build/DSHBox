package com.dshbox.remote.session

import com.dshbox.remote.model.Device
import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.Endpoint
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.model.RetentionPolicy
import com.dshbox.remote.model.toArchiveValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStoreTest {

    private val store = DeviceStore(InMemoryKeyValueStore())

    private fun device(
        id: String = "dev-1",
        name: String = "工作电脑",
        endpoints: List<Endpoint> = emptyList(),
    ) = Device(
        id = DeviceId(id),
        name = name,
        endpoints = endpoints,
        addedAtMillis = 1_700_000_000_000,
    )

    private fun endpoint(
        id: String = "ep-1",
        label: String = "LAN",
        url: String = "http://192.168.1.5:3081",
    ) = Endpoint(id = EndpointId(id), channelLabel = label, targetUrl = url, parserId = "generic")

    @Test fun saveAndLoadRoundTrip() {
        store.save(device(endpoints = listOf(endpoint(), endpoint("ep-2", "PUBLIC", "http://gateway.example:443"))))
        val loaded = store.find(DeviceId("dev-1"))!!
        assertEquals("工作电脑", loaded.name)
        assertEquals(2, loaded.endpoints.size)
        assertEquals("LAN", loaded.endpoints[0].channelLabel)
        assertEquals("http://gateway.example:443", loaded.endpoints[1].targetUrl)
        assertEquals("generic", loaded.endpoints[1].parserId)
    }

    @Test fun specialCharactersSurviveRoundTrip() {
        val trickyName = "名称|带\\竖线\n与|混合"
        val trickyUrl = "http://host|:3081/x\\y"
        store.save(device(name = trickyName, endpoints = listOf(endpoint(url = trickyUrl))))
        val loaded = store.find(DeviceId("dev-1"))!!
        assertEquals(trickyName, loaded.name)
        assertEquals(trickyUrl, loaded.endpoints[0].targetUrl)
    }

    @Test fun missingDeviceReturnsNull() {
        assertNull(store.find(DeviceId("nope")))
    }

    @Test fun savedEndpointEditKeepsIdentity() {
        // 重新配对路径的「读取-改写-保存」更新：身份字段保持，目标地址替换
        store.save(device(endpoints = listOf(endpoint())))
        val loaded = store.find(DeviceId("dev-1"))!!
        store.save(
            loaded.copy(
                endpoints = loaded.endpoints.map {
                    if (it.id == EndpointId("ep-1")) it.copy(targetUrl = "http://tunnel.example") else it
                },
            ),
        )
        val updated = store.find(DeviceId("dev-1"))!!
        assertEquals("http://tunnel.example", updated.endpoints[0].targetUrl)
        assertEquals("ep-1", updated.endpoints[0].id.value)
        assertEquals("LAN", updated.endpoints[0].channelLabel)
    }

    @Test fun unknownEndpointOrDeviceIsNotUpdated() {
        // 读取-改写-保存路径对未知端点/设备的更新无落点（不改写即无副作用）
        store.save(device(endpoints = listOf(endpoint())))
        val loaded = store.find(DeviceId("dev-1"))!!
        val untouched = loaded.copy(
            endpoints = loaded.endpoints.map { endpoint ->
                if (endpoint.id == EndpointId("ep-x")) endpoint.copy(targetUrl = "http://x") else endpoint
            },
        )
        store.save(untouched)
        assertEquals(loaded, store.find(DeviceId("dev-1")))
    }

    @Test fun deleteRemovesEntry() {
        store.save(device(endpoints = listOf(endpoint())))
        store.delete(DeviceId("dev-1"))
        assertNull(store.find(DeviceId("dev-1")))
        assertTrue(store.devices().isEmpty())
    }

    @Test fun devicesListsMultipleEntriesSortedByAddedAt() {
        store.save(device("dev-2", endpoints = listOf(endpoint())))
        store.save(Device(DeviceId("dev-1"), "更早", emptyList(), 1_600_000_000_000))
        assertEquals(listOf("dev-1", "dev-2"), store.devices().map { it.id.value })
    }

    @Test fun corruptedRecordIsSkippedNotFatal() {
        val kv = InMemoryKeyValueStore()
        val kvStore = DeviceStore(kv)
        kvStore.save(device(endpoints = listOf(endpoint())))
        kv.putString("device.dev-bad", "not-a-record")
        assertEquals(listOf("dev-1"), kvStore.devices().map { it.id.value })
    }

    @Test fun v2ArchiveReadsBackWithNullRetentionOverride() {
        // v2 旧档（每端点五字段，无保留档覆盖）：读入按 null 补齐，不使既有设备掉档
        val kv = InMemoryKeyValueStore()
        val kvStore = DeviceStore(kv)
        kv.putString(
            "device.dev-old",
            "2|dev-old|old|1700000000000|2|ep-a|LAN|http://a:3081|generic|0|ep-b|PUBLIC|http://b|generic|7",
        )
        val loaded = kvStore.find(DeviceId("dev-old"))!!
        assertEquals(2, loaded.endpoints.size)
        assertEquals(0L, loaded.endpoints[0].generation)
        assertEquals(7L, loaded.endpoints[1].generation)
        assertNull(loaded.endpoints[0].retentionOverride)
        assertNull(loaded.endpoints[1].retentionOverride)
    }

    @Test fun retentionOverrideSurvivesV3RoundTrip() {
        val withOverride = device(
            endpoints = listOf(
                endpoint().copy(retentionOverride = RetentionPolicy.Retain(15).toArchiveValue()),
                endpoint("ep-2", "PUBLIC", "http://gw.example:443").copy(
                    retentionOverride = RetentionPolicy.FOREVER.toArchiveValue(),
                ),
            ),
        )
        store.save(withOverride)
        val loaded = store.find(DeviceId("dev-1"))!!
        assertEquals(RetentionPolicy.Retain(15).toArchiveValue(), loaded.endpoints[0].retentionOverride)
        assertEquals(RetentionPolicy.FOREVER.toArchiveValue(), loaded.endpoints[1].retentionOverride)
        // v3 往返后行为等价：再次保存读回不变
        store.save(loaded)
        assertEquals(loaded, store.find(DeviceId("dev-1")))
    }

    @Test fun unknownRetentionOverrideValueFallsBackToInherit() {
        // 覆盖值损坏（未知编码）：按继承默认处理，不因脏数据丢设备
        val kv = InMemoryKeyValueStore()
        val kvStore = DeviceStore(kv)
        kv.putString(
            "device.dev-bad",
            "3|dev-bad|bad|1|1|ep-a|LAN|http://a:3081|generic|0|BOGUS",
        )
        val loaded = kvStore.find(DeviceId("dev-bad"))!!
        assertNull(loaded.endpoints.single().retentionOverride)
    }

    @Test fun v3ArchiveReadsBackWithNullSessionMode() {
        // v3 旧档（每端点六字段，无会话模式）：读入按 null（默认代理档）补齐，
        // 不使既有设备掉档
        val kv = InMemoryKeyValueStore()
        val kvStore = DeviceStore(kv)
        kv.putString(
            "device.dev-old",
            "3|dev-old|old|1700000000000|1|ep-a|LAN|http://a:3081|generic|0|",
        )
        val loaded = kvStore.find(DeviceId("dev-old"))!!
        assertNull(loaded.endpoints.single().sessionMode)
    }

    @Test fun sessionModeSurvivesV4RoundTrip() {
        // v4 往返：显式 PROXIED 与 DIRECT 各自保留，再次保存读回不变
        val mixed = device(
            endpoints = listOf(
                endpoint().copy(sessionMode = com.dshbox.remote.model.SessionMode.PROXIED.name),
                endpoint("ep-2", "PUBLIC", "https://zcode.example/remote/x").copy(
                    sessionMode = com.dshbox.remote.model.SessionMode.DIRECT.name,
                ),
            ),
        )
        store.save(mixed)
        val loaded = store.find(DeviceId("dev-1"))!!
        assertEquals("PROXIED", loaded.endpoints[0].sessionMode)
        assertEquals("DIRECT", loaded.endpoints[1].sessionMode)
        store.save(loaded)
        assertEquals(loaded, store.find(DeviceId("dev-1")))
    }

    @Test fun defaultEndpointEncodesAsEmptySessionMode() {
        // 未设模式的端点（null = 默认代理档）：往返后仍是 null（默认档语义不变）
        val plain = device(endpoints = listOf(endpoint()))
        store.save(plain)
        val loaded = store.find(DeviceId("dev-1"))!!
        assertNull(loaded.endpoints.single().sessionMode)
        assertEquals(plain, loaded)
    }

    @Test fun unknownSessionModeValueFallsBackToDefault() {
        // 会话模式损坏（未知枚举名）：按默认代理档处理，不因脏数据丢设备
        val kv = InMemoryKeyValueStore()
        val kvStore = DeviceStore(kv)
        kv.putString(
            "device.dev-bad",
            "4|dev-bad|bad|1|1|ep-a|LAN|http://a:3081|generic|0||BOGUS",
        )
        val loaded = kvStore.find(DeviceId("dev-bad"))!!
        assertNull(loaded.endpoints.single().sessionMode)
    }
}
