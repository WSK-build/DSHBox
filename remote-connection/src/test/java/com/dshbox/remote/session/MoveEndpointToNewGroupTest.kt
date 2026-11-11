package com.dshbox.remote.session

import com.dshbox.remote.model.Device
import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.Endpoint
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.model.PairingTarget
import com.dshbox.remote.parse.ParsedEndpoint
import com.dshbox.remote.parse.ParsedPairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 端点移入新建分组（JVM）：名字写入新设备、端点与凭据键迁移、源设备只剩
 * 其它端点；源空由控制器另行删除（此处只断言档案层）。
 */
class MoveEndpointToNewGroupTest {

    private val store = InMemoryKeyValueStore()
    private val vaultDir = File(
        System.getProperty("java.io.tmpdir"),
        "move-new-group-${System.nanoTime()}",
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

    private fun device() = Device(
        id = DeviceId("dev-1"),
        name = "原分组",
        endpoints = listOf(
            Endpoint(
                id = EndpointId("ep-1"),
                channelLabel = "LAN",
                targetUrl = "http://192.168.1.5:3080/app",
                parserId = "generic-url",
            ),
            Endpoint(
                id = EndpointId("ep-2"),
                channelLabel = "LAN",
                targetUrl = "http://192.168.1.5:3081/app",
                parserId = "generic-url",
            ),
        ),
        addedAtMillis = 1_700_000_000_000,
    )

    @Test fun moveToNewGroupCreatesDeviceWithOnlyMovedEndpoint() {
        DeviceStore(store).save(device())

        assertTrue(factory().moveEndpointToNewDevice(DeviceId("dev-1"), EndpointId("ep-1"), "  新分组  "))

        val devices = DeviceStore(store).devices()
        val target = devices.firstOrNull { it.id != DeviceId("dev-1") }
        assertNotNull(target)
        assertEquals("新分组", target?.name)
        assertEquals(listOf(EndpointId("ep-1")), target?.endpoints?.map { it.id })
        val source = devices.first { it.id == DeviceId("dev-1") }
        assertEquals(listOf(EndpointId("ep-2")), source.endpoints.map { it.id })
    }

    /** 确认卡命名的分组优先于手动输入的设备备注（备注仅作未命名时的默认名）。 */
    @Test fun typedGroupNameBeatsManualNote() {
        val pairing = ParsedPairing(
            parserId = "generic-url",
            deviceName = "载荷默认名",
            endpoints = listOf(
                ParsedEndpoint(
                    targetUrl = "http://192.168.1.5:3080/app",
                    channelLabel = "LAN",
                    credential = null,
                ),
            ),
        )

        val created = factory().archivePairing(
            pairing = pairing,
            displayName = "手输备注",
            choices = null,
            target = PairingTarget.NewGroup("用户命名"),
        )

        assertEquals("用户命名", created.name)
    }

    @Test fun unnamedNewGroupFallsBackToManualNote() {
        val pairing = ParsedPairing(
            parserId = "generic-url",
            deviceName = "载荷默认名",
            endpoints = listOf(
                ParsedEndpoint(
                    targetUrl = "http://192.168.1.5:3080/app",
                    channelLabel = "LAN",
                    credential = null,
                ),
            ),
        )

        val created = factory().archivePairing(
            pairing = pairing,
            displayName = "手输备注",
            choices = null,
            target = PairingTarget.NewGroup(null),
        )

        assertEquals("手输备注", created.name)
    }

    @Test fun moveFromMissingDeviceIsNoOp() {
        assertFalse(factory().moveEndpointToNewDevice(DeviceId("dev-x"), EndpointId("ep-1"), "组"))
        assertTrue(DeviceStore(store).devices().isEmpty())
    }
}
