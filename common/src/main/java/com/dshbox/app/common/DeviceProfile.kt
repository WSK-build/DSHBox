package com.dshbox.app.common

enum class PerformanceTier {
    HIGH,
    STANDARD,
    LIGHT,
    UNSUPPORTED,
}

data class DeviceProfile(
    val androidApi: Int,
    val cpuAbi: String,
    val cpuCoreCount: Int,
    val totalRamGb: Float,
    val freeStorageGb: Float,
    val webViewVersion: String,
    val pageSizeBytes: Int,
) {
    val tier: PerformanceTier
        get() = when {
            totalRamGb >= 12f -> PerformanceTier.HIGH
            totalRamGb >= 8f -> PerformanceTier.STANDARD
            totalRamGb >= 6f -> PerformanceTier.LIGHT
            totalRamGb >= 4f -> PerformanceTier.LIGHT
            else -> PerformanceTier.UNSUPPORTED
        }
}
