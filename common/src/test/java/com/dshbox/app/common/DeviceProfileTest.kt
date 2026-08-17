package com.dshbox.app.common

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceProfileTest {
    @Test
    fun tierByRam() {
        fun profile(gb: Float) = DeviceProfile(
            androidApi = 36,
            cpuAbi = "arm64-v8a",
            cpuCoreCount = 8,
            totalRamGb = gb,
            freeStorageGb = 32f,
            webViewVersion = "test",
            pageSizeBytes = 16384,
        )
        assertEquals(PerformanceTier.HIGH, profile(12f).tier)
        assertEquals(PerformanceTier.STANDARD, profile(8f).tier)
        assertEquals(PerformanceTier.LIGHT, profile(6f).tier)
        assertEquals(PerformanceTier.UNSUPPORTED, profile(3f).tier)
    }
}
