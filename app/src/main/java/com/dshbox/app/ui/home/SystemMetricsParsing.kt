package com.dshbox.app.ui.home

import java.util.Locale
import kotlin.math.roundToInt

/** 温度读数与来源：芯片热区读不到时退回电池温度，来源要在界面上如实标注。 */
internal data class Temperature(val celsius: Int, val fromBattery: Boolean)

/** 已用/总量读数（内存与存储共用）：占用率 + 具体字节数。 */
internal data class Usage(val percent: Int, val usedBytes: Long, val totalBytes: Long)

/** /proc/stat 首行前 8 个字段的累计计数（jiffies，USER_HZ 单位）。 */
internal data class CpuTicks(
    val user: Long,
    val nice: Long,
    val system: Long,
    val idle: Long,
    val iowait: Long,
    val irq: Long,
    val softirq: Long,
    val steal: Long,
) {
    /**
     * 全部忙时 + 空闲的累计和。与 psutil 的净值等价——psutil 先把 10 列求和再减去
     * guest/guest_nice，而 guest 已计入 user、guest_nice 已计入 nice，净值同为前八项之和，
     * 故这里不必再为 guest 建字段做减法。
     */
    val total: Long get() = user + nice + system + idle + iowait + irq + softirq + steal

    /** 空闲折算：iowait 是「等 I/O 的空闲」而非忙，与 idle 同扣（psutil/htop/procps 口径）。 */
    val idleAll: Long get() = idle + iowait
}

/**
 * CPU 读数的口径。各家 ROM 对 /proc 的开放程度不同（平台策略对 /proc/stat、
 * /proc/loadavg 有 neverallow，部分厂商在自家策略里放开），所以哪一档拿得到就用哪一档，
 * 界面按这里如实标注来源，不把不同口径混成同一个数字。
 */
internal enum class CpuSource { SYSTEM, SYSTEM_LOAD, APP, NONE }

/**
 * 监控卡上「内核读数 → 界面数值」的全部换算：解析、归一、越界过滤与文案格式化。
 *
 * 这里不碰文件、不碰 Android 上下文——采样侧只负责把文本读进来，判定与换算都落在这里，
 * 于是每条规则都能被 JVM 单测直接覆盖（见 `SystemMetricsParsingTest`）。
 */
internal object SystemMetricsParsing {

    /** 读数合理性上限（℃）：热区/电池文件偶发给出万分位或负值时据此丢弃。 */
    const val TEMP_SANE_MAX_CELSIUS = 120

    /** 温度条形图满格刻度（℃）。 */
    const val TEMP_BAR_FULL_CELSIUS = 80f

    /** CPU 差值窗口下限（jiffies）：刚记完基线就采时窗口过短，宁可不给值也不给假读数。 */
    const val MIN_CPU_DELTA_TICKS = 10L

    /**
     * 应用档窗口下限（毫秒）：与 [MIN_CPU_DELTA_TICKS] 同性质——prime 后第一窗可能只有几毫秒，
     * 差值无意义；宁可不给值（沿用上一格），也不给被墙钟分母放大的假读数。
     */
    const val MIN_APP_WINDOW_MS = 250L

    /** 读不到的项统一画这个占位符。 */
    const val UNAVAILABLE_TEXT = "—"

    /**
     * SoC 热区识别黑名单（小写子串，命中即排除，**先于白名单**判断）。
     *
     * 各类非 CPU 热区（电池、充电、外壳、射频、显示、PMIC 电源轨等）在部分机型上会带
     * 「cpu」字样：Google Tensor 的 `S2M_VDD_CPUCL2` 是电源轨温度，含 cpu 会被白名单
     * 误捞成 CPU 结温（静默的错值），所以先黑后白。`tsens_tz_sensor` 是高通旧式纯序号名，
     * 无法从名字判语义，宁可退回电池档也不给错值。
     */
    private val SOC_ZONE_DENY = listOf(
        "gpu", "g3d", "battery", "batt", "charg", "usb", "skin", "quiet",
        "pa_therm", "pa-therm", "mtktspa", "modem", "wlan", "wifi", "wmt",
        "display", "lcd", "cam", "isp", "ddr", "dram", "pmic", "vdd", "s2m_",
        "npu", "tpu", "audio", "speaker", "flash", "ufs", "mmc",
        "cooldev", "uspace", "user_space", "hotplug", "virtual",
        "tsens_tz_sensor",
    )

    /**
     * SoC 热区识别白名单（小写子串，命中即纳入候选）。
     *
     * 除通用 cpu/soc/cluster/core 外，补上各家 SoC 私有命名：Exynos 的 atlas/apollo、
     * 联发科的 mif、Kirin 的 cls、以及 big/mid/little 大小核分档名。
     */
    private val SOC_ZONE_ALLOW = listOf(
        "cpu", "soc", "cluster", "core", "acpu",
        "atlas", "apollo", "mif", "cls", "bigcore", "smallcore",
        "big", "mid", "little", "ap_therm", "ap-therm",
    )

    private val WHITESPACE = Regex("\\s+")

    /**
     * /proc/stat 首行 → CPU 累计计数。
     *
     * 只取前八项（user…steal）：guest / guest_nice 已分别计入 user / nice，
     * 再求和会把访客时间重复计入分母、系统性压低占用率。字段不足八个时缺项补 0。
     * 前八个字段逐个严格解析：出现非数字就整行作废——若把坏字段丢掉再取数，后面的字段会
     * 左移顶位（idle 被当成 iowait 一类），是看不出异常的静默错值。
     */
    fun cpuTicks(statLine: String): CpuTicks? {
        if (!statLine.startsWith("cpu ")) return null
        val fields = statLine.trim().split(WHITESPACE).drop(1)
        if (fields.size < 4) return null
        val values = LongArray(8)
        for (i in 0 until minOf(fields.size, 8)) {
            values[i] = fields[i].toLongOrNull() ?: return null
        }
        return CpuTicks(
            user = values[0],
            nice = values[1],
            system = values[2],
            idle = values[3],
            iowait = values[4],
            irq = values[5],
            softirq = values[6],
            steal = values[7],
        )
    }

    /**
     * 前后两次 /proc/stat 计数之差 → 整机占用率（0..100，占整机 CPU 的比例）。
     *
     * 逐字段检查回绕：只看聚合值会漏掉「一涨一跌」的坏窗（如 steal 回退 + user 暴涨），
     * 那种窗的差值没有意义，直接返回 null（调用方会把本次计数记为新基线）。
     * steal 留在分母且计入忙时（psutil/procps 口径，htop 才把它排除）；应用环境无虚拟化，
     * 真机上 steal 恒为 0，两种口径实际无差。
     * 分子不单列 guest/guest_nice：那是访客机的工作时间，psutil 会在分子里再减掉；真机
     * guest 恒 0（本机即宿主），故与 psutil 无差——分母的等价关系见 [CpuTicks.total]。
     * [MIN_CPU_DELTA_TICKS] 的单位是 tick（USER_HZ），不是毫秒。
     */
    fun cpuUtilizationPercent(previous: CpuTicks?, current: CpuTicks?): Double? {
        if (previous == null || current == null) return null
        if (current.user < previous.user || current.nice < previous.nice ||
            current.system < previous.system || current.idle < previous.idle ||
            current.iowait < previous.iowait || current.irq < previous.irq ||
            current.softirq < previous.softirq || current.steal < previous.steal
        ) {
            return null
        }
        val deltaTotal = current.total - previous.total
        if (deltaTotal < MIN_CPU_DELTA_TICKS) return null
        val deltaIdle = current.idleAll - previous.idleAll
        return ((deltaTotal - deltaIdle) * 100.0 / deltaTotal).coerceIn(0.0, 100.0)
    }

    /** /proc/loadavg → 一分钟平均负载 ÷ 核数（%，占整机比例）：系统压力的平滑代理。 */
    fun cpuLoadPercent(loadavgText: String, cores: Int): Double? {
        val load = loadavgText.trim().split(WHITESPACE).firstOrNull()?.toDoubleOrNull() ?: return null
        if (load < 0.0) return null
        return (load / cores.coerceAtLeast(1) * 100.0).coerceIn(0.0, 100.0)
    }

    /**
     * 应用侧 CPU 时间差 → 占整机 CPU 的比例（0..100）。
     *
     * [deltaCpuMs] 是本应用（主进程 + 沙箱进程树）在一个窗口里消耗的 CPU 时间总和，
     * 分母是「墙钟 × 核数」——即整机在这段时间里可供消耗的 CPU 总量。这样得到的是
     * 「占整机比例」，与其余三行同一把尺，也不会出现单核 200% 这类读数。
     * 窗口短于 [MIN_APP_WINDOW_MS] 或增量非法返回 null。
     */
    fun cpuCapacityPercent(deltaCpuMs: Long?, wallMs: Long, cores: Int): Double? {
        if (deltaCpuMs == null || deltaCpuMs < 0L || wallMs < MIN_APP_WINDOW_MS) return null
        val capacityMs = wallMs * cores.coerceAtLeast(1)
        return (deltaCpuMs * 100.0 / capacityMs).coerceIn(0.0, 100.0)
    }

    /**
     * 采样序列的均值（%）：空序列返回 null。
     *
     * 用来把突发型的占用读数平滑成趋势值——进程 CPU 是突发性的（轮询、GC、渲染扎堆），
     * 单个 2 秒窗口的瞬时值会大幅摆动，直接显示只会让人误判。
     */
    fun averagePercent(samples: List<Double>): Double? {
        if (samples.isEmpty()) return null
        return samples.sum() / samples.size
    }

    /**
     * /proc/meminfo 文本（前几行即可）→ 内存占用。
     *
     * 取 MemTotal 与 MemAvailable：前者是整机内存，后者是「不触发换页就能再分配的量」，
     * 比 MemFree 更贴近「还能用多少」。任一项缺失返回 null，不猜。
     */
    fun memoryUsage(meminfoText: String): Usage? {
        var totalKb = -1L
        var availableKb = -1L
        for (line in meminfoText.lineSequence()) {
            when {
                line.startsWith("MemTotal:") -> totalKb = firstNumber(line)
                line.startsWith("MemAvailable:") -> availableKb = firstNumber(line)
            }
            if (totalKb > 0L && availableKb >= 0L) break
        }
        if (totalKb <= 0L || availableKb < 0L) return null
        val usedKb = (totalKb - availableKb).coerceAtLeast(0L)
        return Usage(
            percent = usagePercent(usedKb, totalKb) ?: return null,
            usedBytes = usedKb * 1024L,
            totalBytes = totalKb * 1024L,
        )
    }

    /** statfs 的 total/available → 数据分区存储占用；total 非法返回 null。 */
    fun storageUsage(totalBytes: Long, availableBytes: Long): Usage? {
        if (totalBytes <= 0L) return null
        val used = (totalBytes - availableBytes).coerceAtLeast(0L)
        return Usage(
            percent = usagePercent(used, totalBytes) ?: return null,
            usedBytes = used,
            totalBytes = totalBytes,
        )
    }

    /** 占用率（0..100）；total 非法返回 null。 */
    fun usagePercent(used: Long, total: Long): Int? {
        if (total <= 0L) return null
        return (used.coerceAtLeast(0L) * 100.0 / total).roundToInt().coerceIn(0, 100)
    }

    /** 热区名（thermal_zone 的 type）是否属于 SoC 一类：先黑名单排除，再白名单纳入。 */
    fun isSocZone(type: String): Boolean {
        val normalized = type.trim().lowercase(Locale.US)
        if (SOC_ZONE_DENY.any { normalized.contains(it) }) return false
        return SOC_ZONE_ALLOW.any { normalized.contains(it) }
    }

    /**
     * 热区 temp 文本 → ℃。
     *
     * 内核 ABI 规定 thermal_zone 的 temp 恒为毫摄氏度
     * （Documentation/ABI/testing/sysfs-class-thermal），故定死 /1000，不做「大于 1000 才
     * 换算」的猜测。0 与负值是「驱动未上电/未接线」的常见哨兵值，一律当坏值丢弃。
     * 判界在 Long 域内完成再窄化：先 toInt() 会让超范围值静默回绕成本地看上去合法的读数。
     */
    fun socCelsius(tempText: String): Int? {
        val raw = tempText.trim().toLongOrNull() ?: return null
        if (raw <= 0L) return null
        val celsius = raw / 1_000L
        if (celsius !in 1L..TEMP_SANE_MAX_CELSIUS.toLong()) return null
        return celsius.toInt()
    }

    /** 电池温度原始值（0.1℃ 单位）→ ℃；越界或缺失返回 null。 */
    fun batteryCelsius(tenths: Long?): Int? {
        if (tenths == null) return null
        if (tenths !in 0..(TEMP_SANE_MAX_CELSIUS * 10L)) return null
        return (tenths / 10L).toInt()
    }

    /** 温度条形图比例（0..1）：按 0~[TEMP_BAR_FULL_CELSIUS]℃ 铺满。 */
    fun temperatureFraction(celsius: Int): Float =
        (celsius / TEMP_BAR_FULL_CELSIUS).coerceIn(0f, 1f)

    /** 「已用/总量」文案：GB 起按 GB 显示（一位小数），更小按 MB 显示。 */
    fun formatUsage(usage: Usage): String {
        val gib = 1024.0 * 1024.0 * 1024.0
        val mib = 1024.0 * 1024.0
        return if (usage.totalBytes >= gib) {
            String.format(Locale.US, "%.1f/%.1f GB", usage.usedBytes / gib, usage.totalBytes / gib)
        } else {
            String.format(Locale.US, "%.0f/%.0f MB", usage.usedBytes / mib, usage.totalBytes / mib)
        }
    }

    /** 百分比文案；无读数用占位符。 */
    fun percentText(percent: Int?): String = percent?.let { "$it%" } ?: UNAVAILABLE_TEXT

    /**
     * CPU 文案：应用口径的数通常只有零点几，给一位小数保留分辨率；
     * 系统两档本身是整机比例，取整即可。无读数用占位符。
     */
    fun cpuText(percent: Double?, source: CpuSource): String {
        if (percent == null) return UNAVAILABLE_TEXT
        return if (source == CpuSource.APP) {
            String.format(Locale.US, "%.1f%%", percent)
        } else {
            "${percent.roundToInt()}%"
        }
    }

    /** 温度文案；无读数用占位符。 */
    fun temperatureText(celsius: Int?): String = celsius?.let { "$it°C" } ?: UNAVAILABLE_TEXT

    /** 从 "MemTotal:  7864320 kB" 这类行里取第一个整数（kB）。 */
    private fun firstNumber(line: String): Long =
        line.filter { it.isDigit() }.toLongOrNull() ?: -1L
}
