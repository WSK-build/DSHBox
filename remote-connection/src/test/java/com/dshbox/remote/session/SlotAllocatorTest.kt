package com.dshbox.remote.session

import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.EndpointId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SlotAllocatorTest {

    private val store = InMemoryKeyValueStore()

    private fun allocator(picker: PortPicker = PortPicker.Ephemeral) = SlotAllocator(store, picker)

    @Test fun deviceBindingIsStableForLife() {
        val allocator = allocator()
        val first = allocator.bindDevice(DeviceId("dev-1"))
        val second = allocator.bindDevice(DeviceId("dev-1"))
        assertEquals(first.loopbackIp, second.loopbackIp)
        assertTrue(first.loopbackIp in loopbackSlotPool)
    }

    @Test fun distinctDevicesGetDistinctSlots() {
        val allocator = allocator()
        val first = allocator.bindDevice(DeviceId("dev-1")).loopbackIp
        val second = allocator.bindDevice(DeviceId("dev-2")).loopbackIp
        assertTrue(first != second)
    }

    @Test fun poolExhaustionFailsExplicitlyWithoutEviction() {
        val allocator = allocator()
        repeat(loopbackSlotPool.size) { index ->
            allocator.bindDevice(DeviceId("dev-$index"))
        }
        assertThrows(SlotAllocationException.PoolExhausted::class.java) {
            allocator.bindDevice(DeviceId("dev-extra"))
        }
        // 池满后既有绑定不被驱逐
        assertEquals(loopbackSlotPool[0], allocator.loopbackIpFor(DeviceId("dev-0")))
    }

    @Test fun endpointPortIsStableOnceRecorded() {
        val allocator = allocator()
        val device = DeviceId("dev-1")
        allocator.bindDevice(device)
        assertNull(allocator.portForEndpoint(device, EndpointId("ep-1")))
        allocator.recordPort(device, EndpointId("ep-1"), 49152)
        assertEquals(49152, allocator.portForEndpoint(device, EndpointId("ep-1")))
        allocator.recordPort(device, EndpointId("ep-1"), 49152)
        assertEquals(49152, allocator.portForEndpoint(device, EndpointId("ep-1")))
        assertEquals(49152, allocator.portForEndpoint(device, EndpointId("ep-1")))
    }

    @Test fun releasedDeviceSlotIsRecycledWithFreshPorts() {
        val store = InMemoryKeyValueStore()
        val pickCounter = AtomicInteger(0)
        val picker = PortPicker { _, exclude ->
            // 顺序端口序列：验证退役名单被传给选取器并被排除
            pickCounter.incrementAndGet()
            50000 + pickCounter.get()
        }
        val allocator = SlotAllocator(store, picker)
        val oldDevice = DeviceId("dev-old")
        val ip = allocator.bindDevice(oldDevice).loopbackIp
        allocator.recordPort(oldDevice, EndpointId("ep-1"), 50000)
        allocator.recordPort(oldDevice, EndpointId("ep-2"), 50001)

        allocator.releaseDevice(oldDevice)
        assertNull(allocator.loopbackIpFor(oldDevice))
        assertEquals(setOf(50000, 50001), allocator.retiredPorts(ip))

        // 回收的 IP 可分配给新设备，且退役端口被显式排除（不复用历史端口）
        val newDevice = DeviceId("dev-new")
        val newAllocator = SlotAllocator(store) { loopback, exclude ->
            assertEquals(ip, loopback)
            check(50000 !in exclude && 50001 !in exclude) { "retired ports must be excluded" }
            50002
        }
        assertEquals(ip, newAllocator.bindDevice(newDevice).loopbackIp)
    }

    @Test fun releaseWithoutBindingIsNoop() {
        allocator().releaseDevice(DeviceId("ghost"))
    }

    @Test fun ephemeralPickerReturnsBindablePort() {
        val picked = PortPicker.Ephemeral.pick("127.0.0.2", emptySet())
        assertTrue(picked in 1..65535)
    }

    @Test fun ephemeralPickerExcludesRetiredPorts() {
        // 真实绑定：拿一个可用端口，再把它放进退役名单，选取器必须给出不同端口
        val first = PortPicker.Ephemeral.pick("127.0.0.3", emptySet())
        val second = PortPicker.Ephemeral.pick("127.0.0.3", setOf(first))
        assertTrue(first != second)
    }

    @Test fun allocationTableSurvivesStoreRestart() {
        val kv = InMemoryKeyValueStore()
        val first = SlotAllocator(kv)
        val device = DeviceId("dev-1")
        val binding = first.bindDevice(device)
        first.recordPort(device, EndpointId("ep-1"), 52000)
        // 新分配器实例（模拟进程重启）读同一存储
        val second = SlotAllocator(kv)
        assertEquals(binding.loopbackIp, second.bindDevice(device).loopbackIp)
        assertEquals(52000, second.portForEndpoint(device, EndpointId("ep-1")))
    }

    @Test fun concurrentBindDoesNotExceedPoolOrDuplicateSlots() {
        // 并发绑定：成功的槽位两两不同，成功数不超过池容量，其余显式池满
        val threadCount = loopbackSlotPool.size * 2
        val startGate = java.util.concurrent.CountDownLatch(1)
        val bindings = java.util.Collections.synchronizedList(mutableListOf<String>())
        val exhausted = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (0 until threadCount).map { index ->
            Thread {
                startGate.await()
                try {
                    bindings.add(allocator().bindDevice(DeviceId("dev-$index")).loopbackIp)
                } catch (expected: SlotAllocationException.PoolExhausted) {
                    exhausted.incrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        startGate.countDown()
        threads.forEach { it.join() }

        assertEquals(loopbackSlotPool.size, bindings.size)
        assertEquals(loopbackSlotPool.size, exhausted.get())
        assertEquals(loopbackSlotPool.size, bindings.toSet().size)
    }
}
