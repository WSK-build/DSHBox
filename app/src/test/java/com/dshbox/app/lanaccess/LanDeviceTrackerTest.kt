package com.dshbox.app.lanaccess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 局域网在线设备账（按来源 IP 聚合，JVM 纯账目单测）：
 * - 同一来源 IP 的多条连接 → 设备 1、连接 N（浏览器并行开多条连接）；
 * - 不同来源 IP → 设备数按 distinct IP 计；
 * - 关闭一条仍留设备、全部关闭归零；
 * - [LanDeviceTracker.clear] 后设备与连接一并归零；
 * - 未在册 IP 的出账是空操作（计数恒非负）；
 * - 并发增减下计数不出现负值、终值与账目自洽。
 */
class LanDeviceTrackerTest {

    private val now = 1_000L

    private fun tracker(): LanDeviceTracker = LanDeviceTracker { now }

    @Test
    fun twoConnectionsFromSameIpCountAsOneDevice() {
        val tracker = tracker()
        tracker.onConnect("192.168.1.7")
        tracker.onConnect("192.168.1.7")
        assertEquals(1, tracker.deviceCount)
        assertEquals(2, tracker.connectionCount)
        assertEquals(mapOf("192.168.1.7" to 2), tracker.sessionCounts)
        assertTrue(tracker.lastActiveMillisByIp.getValue("192.168.1.7") > 0L)
    }

    @Test
    fun distinctIpsCountAsDistinctDevices() {
        val tracker = tracker()
        tracker.onConnect("192.168.1.7")
        tracker.onConnect("192.168.1.8")
        tracker.onConnect("192.168.1.8")
        assertEquals(2, tracker.deviceCount)
        assertEquals(3, tracker.connectionCount)
        assertEquals(
            mapOf("192.168.1.7" to 1, "192.168.1.8" to 2),
            tracker.sessionCounts,
        )
    }

    @Test
    fun closingOneOfTwoConnectionsKeepsTheDevice() {
        val tracker = tracker()
        tracker.onConnect("192.168.1.7")
        tracker.onConnect("192.168.1.7")
        tracker.onDisconnect("192.168.1.7")
        assertEquals(1, tracker.deviceCount)
        assertEquals(1, tracker.connectionCount)
        assertEquals(mapOf("192.168.1.7" to 1), tracker.sessionCounts)
    }

    @Test
    fun closingAllConnectionsClearsLedger() {
        val tracker = tracker()
        tracker.onConnect("192.168.1.7")
        tracker.onConnect("192.168.1.8")
        tracker.onDisconnect("192.168.1.7")
        tracker.onDisconnect("192.168.1.8")
        assertEquals(0, tracker.deviceCount)
        assertEquals(0, tracker.connectionCount)
        assertTrue(tracker.sessionCounts.isEmpty())
        assertTrue(tracker.lastActiveMillisByIp.isEmpty())
    }

    @Test
    fun clearResetsDeviceAndConnectionCounts() {
        val tracker = tracker()
        tracker.onConnect("192.168.1.7")
        tracker.onConnect("192.168.1.7")
        tracker.onConnect("192.168.1.8")
        tracker.clear()
        assertEquals(0, tracker.deviceCount)
        assertEquals(0, tracker.connectionCount)
        assertTrue(tracker.sessionCounts.isEmpty())
        // 清账后的迟到出账是空操作：既不把账减成负数，也不重建条目
        tracker.onDisconnect("192.168.1.7")
        assertEquals(0, tracker.deviceCount)
        assertEquals(0, tracker.connectionCount)
        assertTrue(tracker.sessionCounts.isEmpty())
    }

    @Test
    fun disconnectOfUnknownIpIsNoOp() {
        val tracker = tracker()
        tracker.onDisconnect("10.0.0.9")
        assertEquals(0, tracker.deviceCount)
        assertEquals(0, tracker.connectionCount)
        tracker.onConnect("10.0.0.9")
        tracker.onDisconnect("10.0.0.9")
        tracker.onDisconnect("10.0.0.9")
        assertEquals(0, tracker.deviceCount)
        assertEquals(0, tracker.connectionCount)
    }

    @Test
    fun concurrentConnectAndDisconnectNeverGoesNegative() {
        val tracker = tracker()
        val threads = 8
        val rounds = 400
        val ips = listOf("192.168.1.7", "192.168.1.8", "192.168.1.9")
        val pool = Executors.newFixedThreadPool(threads + 1)
        val start = CountDownLatch(1)
        val workersDone = CountDownLatch(threads)
        val failure = AtomicReference<Throwable?>(null)
        val negative = AtomicBoolean(false)
        try {
            repeat(threads) { index ->
                pool.execute {
                    try {
                        start.await()
                        repeat(rounds) { round ->
                            val ip = ips[(index + round) % ips.size]
                            tracker.onConnect(ip)
                            tracker.onDisconnect(ip)
                            if (tracker.deviceCount < 0 || tracker.connectionCount < 0) {
                                negative.set(true)
                            }
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    } finally {
                        workersDone.countDown()
                    }
                }
            }
            // 观察者线程：同刻快照的账目自洽（连接数 ≥ 设备数 ≥ 0）
            val observer = pool.submit {
                while (workersDone.count > 0L) {
                    val counts = tracker.snapshot()
                    if (counts.devices < 0 || counts.connections < 0 ||
                        counts.connections < counts.devices
                    ) {
                        negative.set(true)
                    }
                }
            }
            start.countDown()
            observer.get(10, TimeUnit.SECONDS)
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertTrue("worker failure: ${failure.get()}", failure.get() == null)
        assertTrue("negative or inconsistent count observed", !negative.get())
        // 全部入账/出账配对：终值归零
        assertEquals(0, tracker.deviceCount)
        assertEquals(0, tracker.connectionCount)
        assertTrue(tracker.sessionCounts.isEmpty())
    }
}
