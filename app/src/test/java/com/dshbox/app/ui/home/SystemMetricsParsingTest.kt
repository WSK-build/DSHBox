package com.dshbox.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 监控卡取数规则的纯 JVM 测试（无 Android Context、不碰设备）。
 *
 * 覆盖：/proc/stat 计数解析与占用率差值、/proc/loadavg 负载换算、本应用 CPU 时间换算、
 * /proc/meminfo 与 statfs 的占用率、热区与电池温度的归一与越界过滤、以及界面文案格式化。
 * 逐项验证数值解析和格式化，防止错误数据进入监控显示。
 */
class SystemMetricsParsingTest {

    // ---------- CPU：/proc/stat ----------

    @Test
    fun cpuTicksExcludesGuestFieldsFromTotal() {
        // user nice system idle iowait irq softirq steal guest guest_nice
        val ticks = SystemMetricsParsing.cpuTicks("cpu  100 20 30 400 5 6 7 8 9 10")!!
        assertEquals(100L, ticks.user)
        assertEquals(20L, ticks.nice)
        assertEquals(30L, ticks.system)
        assertEquals(400L, ticks.idle)
        assertEquals(5L, ticks.iowait)
        assertEquals(6L, ticks.irq)
        assertEquals(7L, ticks.softirq)
        assertEquals(8L, ticks.steal)
        assertEquals(576L, ticks.total) // 100+20+30+400+5+6+7+8，guest 9/10 不再重复计入
        assertEquals(405L, ticks.idleAll) // idle + iowait
    }

    @Test
    fun cpuTicksPadsMissingFieldsWithZero() {
        // 只有 7 个字段（无 steal）：缺项补 0，不因缺字段整体作废
        val ticks = SystemMetricsParsing.cpuTicks("cpu 1 2 3 4 5 6 7")!!
        assertEquals(0L, ticks.steal)
        assertEquals(28L, ticks.total)
    }

    @Test
    fun cpuTicksRejectsNonCpuAndShortLines() {
        assertNull(SystemMetricsParsing.cpuTicks("cpu0 1 2 3 4"))
        assertNull(SystemMetricsParsing.cpuTicks("intr 12345"))
        assertNull(SystemMetricsParsing.cpuTicks("cpu 1 2 3"))
        assertNull(SystemMetricsParsing.cpuTicks("cpu a b c d"))
    }

    @Test
    fun cpuTicksRejectsNonNumericFieldInsteadOfShifting() {
        // 中间字段非数字：若把它丢掉再取数，后面的字段会左移顶位（idle 被读成 iowait 的值）
        assertNull(SystemMetricsParsing.cpuTicks("cpu 100 20 30 abc 400 5 6 7 8"))
    }

    /** 构造累计计数：后 6 项默认 0，只有测试关心的字段显式给出。 */
    private fun ticks(
        user: Long = 0L,
        nice: Long = 0L,
        system: Long = 0L,
        idle: Long = 0L,
        iowait: Long = 0L,
        irq: Long = 0L,
        softirq: Long = 0L,
        steal: Long = 0L,
    ) = CpuTicks(user, nice, system, idle, iowait, irq, softirq, steal)

    @Test
    fun cpuUtilizationUsesWindowDelta() {
        val previous = ticks(user = 500L, system = 100L, idle = 400L) // total 1000, idleAll 400
        val current = ticks(user = 800L, system = 100L, idle = 700L) // total 1600, idleAll 700
        // Δ总 600、Δ空闲 300 → 忙 300 → 50%
        assertEquals(
            50.0,
            SystemMetricsParsing.cpuUtilizationPercent(previous, current)!!,
            0.001,
        )
    }

    @Test
    fun cpuUtilizationRejectsTooShortWindow() {
        val previous = ticks(user = 500L, system = 100L, idle = 400L)
        // 窗口过短（刚记完基线就采）：宁可不给值
        assertNull(
            SystemMetricsParsing.cpuUtilizationPercent(
                previous,
                ticks(user = 503L, system = 100L, idle = 400L, iowait = 2L), // Δ总 5 < 10
            ),
        )
    }

    @Test
    fun cpuUtilizationRejectsFieldLevelRegression() {
        // 只看聚合值会漏掉「一涨一跌」的坏窗：字段级逐项比较，任一字段回退即判坏窗
        val previous = ticks(user = 500L, system = 100L, idle = 400L, steal = 9L)
        // idle 回退
        assertNull(
            SystemMetricsParsing.cpuUtilizationPercent(
                previous,
                ticks(user = 900L, system = 100L, idle = 300L, steal = 9L),
            ),
        )
        // steal 回退 + user 暴涨：旧实现（只比 deltaIdle 与 deltaTotal）会漏过
        assertNull(
            SystemMetricsParsing.cpuUtilizationPercent(
                previous,
                ticks(user = 900L, system = 100L, idle = 400L, steal = 8L),
            ),
        )
        assertNull(SystemMetricsParsing.cpuUtilizationPercent(null, previous))
        assertNull(SystemMetricsParsing.cpuUtilizationPercent(previous, null))
    }

    @Test
    fun cpuUtilizationRejectsEachFieldRegression() {
        // 每一个字段单独回退都要能识别：逐字段清单漏掉任何一项都会漏过对应坏窗
        val previous = ticks(user = 500L, system = 100L, idle = 400L, iowait = 5L, irq = 6L, softirq = 7L)
        assertNull(
            SystemMetricsParsing.cpuUtilizationPercent(
                previous,
                ticks(user = 900L, system = 100L, idle = 400L, iowait = 4L, irq = 6L, softirq = 7L),
            ),
        )
        assertNull(
            SystemMetricsParsing.cpuUtilizationPercent(
                previous,
                ticks(user = 900L, system = 100L, idle = 400L, iowait = 5L, irq = 5L, softirq = 7L),
            ),
        )
        assertNull(
            SystemMetricsParsing.cpuUtilizationPercent(
                previous,
                ticks(user = 900L, system = 100L, idle = 400L, iowait = 5L, irq = 6L, softirq = 6L),
            ),
        )
        // 全部字段一起回退（模拟 CPU 下线）：整行比上轮小，仍要判坏窗
        assertNull(SystemMetricsParsing.cpuUtilizationPercent(previous, ticks()))
    }

    @Test
    fun cpuUtilizationAcceptsWindowExactlyAtMinimum() {
        // Δ总 恰为下限 10：门槛是 < MIN 才拒，等于下限应当放行
        assertEquals(
            100.0,
            SystemMetricsParsing.cpuUtilizationPercent(
                ticks(user = 500L, idle = 400L),
                ticks(user = 510L, idle = 400L),
            )!!,
            0.001,
        )
    }

    @Test
    fun cpuUtilizationSaturatesAtFullBusy() {
        assertEquals(
            100.0,
            SystemMetricsParsing.cpuUtilizationPercent(
                ticks(),
                ticks(user = 500L),
            )!!,
            0.001,
        )
    }

    // ---------- CPU：/proc/loadavg ----------

    @Test
    fun loadPercentDividesByCores() {
        val line = "1.60 1.20 1.00 1/100 12345"
        assertEquals(20.0, SystemMetricsParsing.cpuLoadPercent(line, cores = 8)!!, 0.001)
        assertEquals(100.0, SystemMetricsParsing.cpuLoadPercent(line, cores = 1)!!, 0.001)
    }

    @Test
    fun loadPercentHandlesDegenerateInput() {
        assertEquals(0.0, SystemMetricsParsing.cpuLoadPercent("0.00 0.00 0.00", cores = 8)!!, 0.001)
        // 核数为 0（理论上不可能）按 1 核处理，不做除零
        assertEquals(50.0, SystemMetricsParsing.cpuLoadPercent("0.50 0.00 0.00", cores = 0)!!, 0.001)
        assertNull(SystemMetricsParsing.cpuLoadPercent("", cores = 8))
        assertNull(SystemMetricsParsing.cpuLoadPercent("abc 0 0", cores = 8))
        assertNull(SystemMetricsParsing.cpuLoadPercent("-1.0 0 0", cores = 8))
    }

    // ---------- CPU：应用侧（占整机比例） ----------

    @Test
    fun capacityPercentDividesByWallClockTimesCores() {
        // 8 核、2 秒窗口：可用 CPU 总量 16000ms；用掉 200ms → 1.25%
        assertEquals(
            1.25,
            SystemMetricsParsing.cpuCapacityPercent(deltaCpuMs = 200L, wallMs = 2_000L, cores = 8)!!,
            0.001,
        )
        // 4 核、2 秒里烧掉 4000ms（等同两个核满载）→ 50%
        assertEquals(
            50.0,
            SystemMetricsParsing.cpuCapacityPercent(deltaCpuMs = 4_000L, wallMs = 2_000L, cores = 4)!!,
            0.001,
        )
        assertEquals(
            0.0,
            SystemMetricsParsing.cpuCapacityPercent(deltaCpuMs = 0L, wallMs = 2_000L, cores = 8)!!,
            0.001,
        )
    }

    @Test
    fun capacityPercentRejectsInvalidWindow() {
        assertNull(SystemMetricsParsing.cpuCapacityPercent(deltaCpuMs = null, wallMs = 2_000L, cores = 8))
        // 负窗口独立于最小窗口门槛（那条见下一个用例），仍是各自一条边界
        assertNull(SystemMetricsParsing.cpuCapacityPercent(deltaCpuMs = 100L, wallMs = -1L, cores = 8))
        assertNull(SystemMetricsParsing.cpuCapacityPercent(deltaCpuMs = -5L, wallMs = 2_000L, cores = 8))
    }

    @Test
    fun capacityPercentRejectsWindowShorterThanMinimum() {
        // prime 后第一窗可能只有几毫秒：分母被放大成可见百分比，宁可不给值
        assertNull(SystemMetricsParsing.cpuCapacityPercent(deltaCpuMs = 1L, wallMs = 3L, cores = 8))
        assertNull(SystemMetricsParsing.cpuCapacityPercent(deltaCpuMs = 200L, wallMs = 100L, cores = 8))
        // 恰为下限放行：8 核 250ms 容量 2000ms，用掉 200ms → 10%
        assertEquals(
            10.0,
            SystemMetricsParsing.cpuCapacityPercent(deltaCpuMs = 200L, wallMs = 250L, cores = 8)!!,
            0.001,
        )
    }

    @Test
    fun capacityPercentNeverExceedsHundred() {
        // 异常读数（增量大于整机容量）也应该被夹住，不出现 >100%
        assertEquals(
            100.0,
            SystemMetricsParsing.cpuCapacityPercent(deltaCpuMs = 999_999L, wallMs = 1_000L, cores = 1)!!,
            0.001,
        )
    }

    @Test
    fun averagePercentSmoothsBurstySamples() {
        assertNull(SystemMetricsParsing.averagePercent(emptyList()))
        assertEquals(7.0, SystemMetricsParsing.averagePercent(listOf(7.0))!!, 0.001)
        assertEquals(41.0, SystemMetricsParsing.averagePercent(listOf(3.0, 40.0, 80.0))!!, 0.001)
        // 单个毛刺（80）被窗口摊薄：3,3,3,3,80 → 18.4，而不是把 80 直接甩到界面上
        assertEquals(
            18.4,
            SystemMetricsParsing.averagePercent(listOf(3.0, 3.0, 3.0, 3.0, 80.0))!!,
            0.001,
        )
    }

    // ---------- 内存 / 存储 ----------

    @Test
    fun memoryUsageFromMeminfo() {
        val text = """
            MemTotal:        8388608 kB
            MemFree:          500000 kB
            MemAvailable:    2097152 kB
            Buffers:           10000 kB
        """.trimIndent()
        val usage = SystemMetricsParsing.memoryUsage(text)!!
        assertEquals(75, usage.percent) // (8GiB-2GiB)/8GiB
        assertEquals(8_589_934_592L, usage.totalBytes)
        assertEquals(6_442_450_944L, usage.usedBytes)
    }

    @Test
    fun memoryUsageNeedsBothFields() {
        assertNull(SystemMetricsParsing.memoryUsage("MemTotal: 8388608 kB\nMemFree: 1 kB"))
        assertNull(SystemMetricsParsing.memoryUsage(""))
        assertNull(SystemMetricsParsing.memoryUsage("MemTotal: abc kB\nMemAvailable: 1 kB"))
    }

    @Test
    fun storageUsageFromStatfs() {
        val usage = SystemMetricsParsing.storageUsage(
            totalBytes = 100_000_000_000L,
            availableBytes = 25_000_000_000L,
        )!!
        assertEquals(75, usage.percent)
        assertEquals(75_000_000_000L, usage.usedBytes)
    }

    @Test
    fun storageUsageRejectsDegenerateTotals() {
        assertNull(SystemMetricsParsing.storageUsage(totalBytes = 0L, availableBytes = 0L))
        assertNull(SystemMetricsParsing.storageUsage(totalBytes = -1L, availableBytes = 0L))
        // available 大于 total（异常读数）：占用夹到 0，不出现负占用
        val usage = SystemMetricsParsing.storageUsage(1_000L, 2_000L)!!
        assertEquals(0, usage.percent)
        assertEquals(0L, usage.usedBytes)
    }

    @Test
    fun usagePercentRoundsAndClamps() {
        assertEquals(33, SystemMetricsParsing.usagePercent(1L, 3L))
        assertEquals(100, SystemMetricsParsing.usagePercent(5L, 3L))
        assertEquals(0, SystemMetricsParsing.usagePercent(-5L, 10L))
        assertNull(SystemMetricsParsing.usagePercent(1L, 0L))
    }

    // ---------- 温度 ----------

    @Test
    fun socZoneMatchingAcceptsCpuClassNames() {
        listOf(
            "cpu0-thermal", "cpu-thermal", "cpu_thermal", "mtktscpu",
            "soc-thermal", "soc_therm", "cluster0-thermal",
            "bigcore0", "smallcore0", "atlas0", "apollo",
            "cls0-thermal", "BIG", "MID", "LITTLE", "AP_THERM", "ap_therm0",
        ).forEach { assertTrue("should accept: $it", SystemMetricsParsing.isSocZone(it)) }
    }

    @Test
    fun socZoneMatchingRejectsNonCpuZones() {
        listOf(
            "gpu-thermal", "g3d-thermal", "battery", "mtktsbattery", "charger",
            "usb-therm", "quiet_therm", "pa_therm0", "mtktspa", "modem-therm",
            "wlan", "display", "VIRTUAL-SKIN",
            // Tensor 电源轨：含 cpu 但先被 vdd/s2m_ 黑名单排除，不能当 CPU 结温
            "S2M_VDD_CPUCL2", "S2M_VDD_CPUCL1",
            "cpu-thermal-0-0_user_space", "thermal-cooldev",
            // 旧高通纯序号名：无法从名字判语义，宁可退电池档
            "tsens_tz_sensor0", "tsens_tz_sensor3",
        ).forEach { assertFalse("should reject: $it", SystemMetricsParsing.isSocZone(it)) }
    }

    @Test
    fun socCelsiusNormalizesMilliDegrees() {
        assertEquals(45, SystemMetricsParsing.socCelsius("45000"))
        assertEquals(1, SystemMetricsParsing.socCelsius("1000")) // 下边界：1℃
        assertEquals(120, SystemMetricsParsing.socCelsius("120000"))
        assertNull(SystemMetricsParsing.socCelsius("125000")) // 超过合理上限
        assertNull(SystemMetricsParsing.socCelsius("0")) // 未上电哨兵值
        assertNull(SystemMetricsParsing.socCelsius("45")) // 45 毫度 = 0℃，坏值
        assertNull(SystemMetricsParsing.socCelsius("450")) // 0.45℃ 截断为 0，坏值
        assertNull(SystemMetricsParsing.socCelsius("-5000"))
        assertNull(SystemMetricsParsing.socCelsius("abc"))
        assertNull(SystemMetricsParsing.socCelsius(""))
    }

    @Test
    fun batteryCelsiusFiltersOutOfRangeTenths() {
        assertEquals(35, SystemMetricsParsing.batteryCelsius(350L))
        assertEquals(0, SystemMetricsParsing.batteryCelsius(0L))
        assertEquals(120, SystemMetricsParsing.batteryCelsius(1200L))
        assertNull(SystemMetricsParsing.batteryCelsius(1201L))
        assertNull(SystemMetricsParsing.batteryCelsius(-1L))
        assertNull(SystemMetricsParsing.batteryCelsius(null))
    }

    @Test
    fun temperatureFractionScalesToBar() {
        assertEquals(0f, SystemMetricsParsing.temperatureFraction(0), 0.0001f)
        assertEquals(0.5f, SystemMetricsParsing.temperatureFraction(40), 0.0001f)
        assertEquals(1f, SystemMetricsParsing.temperatureFraction(80), 0.0001f)
        assertEquals(1f, SystemMetricsParsing.temperatureFraction(120), 0.0001f)
    }

    // ---------- 文案 ----------

    @Test
    fun formatUsageSwitchesUnitAtOneGib() {
        val gib = 1024L * 1024L * 1024L
        assertEquals(
            "0.5/1.5 GB",
            SystemMetricsParsing.formatUsage(Usage(33, gib / 2, gib * 3 / 2)),
        )
        val mib = 1024L * 1024L
        assertEquals(
            "256/512 MB",
            SystemMetricsParsing.formatUsage(Usage(50, 256 * mib, 512 * mib)),
        )
    }

    @Test
    fun valueTextsFallBackToPlaceholder() {
        assertEquals("—", SystemMetricsParsing.percentText(null))
        assertEquals("42%", SystemMetricsParsing.percentText(42))
        assertEquals("—", SystemMetricsParsing.temperatureText(null))
        assertEquals("43°C", SystemMetricsParsing.temperatureText(43))
    }

    @Test
    fun cpuTextKeepsOneDecimalForAppScope() {
        // 应用口径的数常常只有零点几（8 核机上空闲时），保留一位小数才看得见
        assertEquals("0.4%", SystemMetricsParsing.cpuText(0.375, CpuSource.APP))
        assertEquals("12.5%", SystemMetricsParsing.cpuText(12.5, CpuSource.APP))
        // 系统两档本身是整机比例，取整即可
        assertEquals("42%", SystemMetricsParsing.cpuText(41.6, CpuSource.SYSTEM))
        assertEquals("37%", SystemMetricsParsing.cpuText(37.0, CpuSource.SYSTEM_LOAD))
        assertEquals("—", SystemMetricsParsing.cpuText(null, CpuSource.NONE))
    }
}
