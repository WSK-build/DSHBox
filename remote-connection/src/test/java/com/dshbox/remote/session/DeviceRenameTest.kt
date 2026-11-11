package com.dshbox.remote.session

import com.dshbox.remote.model.Device
import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.Endpoint
import com.dshbox.remote.model.EndpointId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设备重命名的存档语义（JVM）：只改设备显示名，端点、标识与建档时间原样
 * 保留；设备不在档时返回 false 且不新建条目。
 */
class DeviceRenameTest {

    private val store = InMemoryKeyValueStore()
    private val vaultDir = File(
        System.getProperty("java.io.tmpdir"),
        "device-rename-${System.nanoTime()}",
    ).apply { mkdirs() }

    private fun factory() = DefaultSessionFactory(
        StubContext(),
        storeOverride = { store },
        vaultDirOverride = vaultDir,
        cipherOverride = PlainVaultCipher,
        cleanerOverride = object : SessionDataCleaner {
            override fun clearOrigins(origins: List<String>, cookieUrls: List<String>) = Unit
        },
    )

    private fun device(name: String) = Device(
        id = DeviceId("dev-1"),
        name = name,
        endpoints = listOf(
            Endpoint(
                id = EndpointId("ep-1"),
                channelLabel = "LAN",
                targetUrl = "http://192.168.1.5:3080/app",
                parserId = "generic-url",
            ),
        ),
        addedAtMillis = 1_700_000_000_000,
    )

    @Test fun renameKeepsEndpointsAndMetadata() {
        val original = device("书房主机")
        DeviceStore(store).save(original)

        assertTrue(factory().renameDevice(original.id, "  客厅主机  "))

        val saved = DeviceStore(store).find(original.id)
        assertEquals("客厅主机", saved?.name)
        assertEquals(original.endpoints, saved?.endpoints)
        assertEquals(original.addedAtMillis, saved?.addedAtMillis)
    }

    @Test fun renameMissingDeviceIsNoOp() {
        assertFalse(factory().renameDevice(DeviceId("dev-missing"), "任意名称"))
        assertTrue(DeviceStore(store).devices().isEmpty())
    }
}
