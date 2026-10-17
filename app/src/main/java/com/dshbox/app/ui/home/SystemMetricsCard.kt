package com.dshbox.app.ui.home

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dshbox.app.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** 采样间隔：2 秒一次，够看出趋势，又不给系统添负担。 */
private const val SAMPLE_INTERVAL_MILLIS = 2_000L

/** 采样间隔的宽限倍数：间隔超过 2 倍视为「中间停过表」，重记基线再采。 */
private const val SAMPLE_GAP_TOLERANCE = 2L

/** 电池温度退回系统广播的最小刷新间隔：电池温度变化很慢，没必要每轮都问一次系统服务。 */
private const val BATTERY_BROADCAST_REFRESH_MILLIS = 30_000L

/** CPU 告警阈值（整机口径两档：占整机 CPU 的比例，%）。 */
private const val CPU_ALERT_PERCENT = 90.0

/** 应用口径的告警阈值（占整机比例）：半数核被吃满就值得标一下。 */
private const val APP_CAPACITY_ALERT_PERCENT = 50.0

/** 内存 / 存储占用告警阈值（%）。 */
private const val USAGE_ALERT_PERCENT = 90

/** 温度告警阈值（℃）。 */
private const val TEMP_ALERT_CELSIUS = 50

/**
 * CPU 读数的平滑窗口：最近 5 个采样（约 10 秒）的均值。
 *
 * 进程 CPU 是突发性的——DSH 那一页 WebView 保活、Pilot 节拍轮询、沙箱健康探测、GC
 * 都会在某个 2 秒窗口里扎堆，瞬时值能在 3% 与 80% 之间乱跳；监视卡要的是趋势值。
 */
private const val CPU_SMOOTHING_SAMPLES = 5

/** SoC 热区短名单全部取值失败的连续轮数上限：达到即判定名单过期，清空后重扫一次。 */
private const val SOC_ZONE_FAIL_STREAK_LIMIT = 3

/** SoC 热区重扫节流（毫秒）：无匹配 zone / 名单失效后隔这么久才再扫一次目录。 */
private const val SOC_REPROBE_INTERVAL_MILLIS = 60_000L

private const val TAG = "DshSystemMetrics"

/** CPU 读数：数值 + 口径来源（界面按来源如实标注，不把不同口径混成一个数字）。 */
private data class CpuReading(val percent: Double?, val source: CpuSource)

/** 一次采样的结果；读不到的项为 null / [CpuSource.NONE]。 */
private data class SystemMetrics(
    val cpu: CpuReading,
    val temperature: Temperature?,
    val memory: Usage?,
    val storage: Usage?,
)

/**
 * 控制页「系统状态」卡：CPU 压力、温度、内存、存储四项，纵向四行。
 *
 * CPU 口径是分档的（各 ROM 对 /proc 的开放程度不同，见 [SystemMetricsSampler.readCpu]）：
 * 整机占用率 → 整机负载 → 本应用（含沙箱），界面按实际拿到的那一档标注来源；四档都是
 * 「占整机 CPU 的比例」——应用那档把主进程与沙箱进程树的 CPU 时间合在一起除以
 * 「墙钟 × 核数」，与其它三行同一把尺，不会出现单核 200% 这类读数。显示值是最近
 * [CPU_SMOOTHING_SAMPLES] 个采样（约 10 秒）的均值——占用读数本身是突发性的，
 * 单窗口的瞬时值会大幅摆动，直接显示只会误导。
 * 温度同理：芯片热区优先，退回电池温度并标注。
 *
 * 开销纪律（本卡不做任何常驻动作）：只在 [active]（控制页可见）且 App 处于前台
 * （RESUMED）时每 [SAMPLE_INTERVAL_MILLIS] 采一次；退后台由 repeatOnLifecycle 直接
 * 取消采样协程（不等重组），回到前台重新记基线再采，暂停期间不产生任何读取。
 * 采样在 IO 线程上做，单次只有几次极小的 /proc、sysfs 与 statfs 读取——不起服务、
 * 不注册常驻监听、不持唤醒锁，也不需要额外权限。唯一的系统服务调用是电池温度退回
 * 系统广播时的查询，且按 [BATTERY_BROADCAST_REFRESH_MILLIS] 降频。
 *
 * [sandboxCpuMillis] 由宿主注入（沙箱与 DSH 进程树的累计 CPU 毫秒数）：沙箱本来就是这个
 * 应用内部的东西，所以并入「本应用」一档一起统计，不单列条目。未运行时注入方返回 null。
 */
@Composable
internal fun SystemMetricsCard(active: Boolean, sandboxCpuMillis: suspend () -> Long? = { null }) {
    val context = LocalContext.current
    val sampler = remember(context) { SystemMetricsSampler(context) }
    var metrics by remember { mutableStateOf<SystemMetrics?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(active, lifecycleOwner) {
        if (!active) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            sampler.prime(sandboxCpuMillis)
            var lastSampleAt = SystemClock.elapsedRealtime()
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                // 中间停过表（长时间暂停/系统冻结）：先重记基线，本轮交给缓存的上一格
                // 读数顶着，不把暂停期间的均值画进来。
                if (now - lastSampleAt > SAMPLE_INTERVAL_MILLIS * SAMPLE_GAP_TOLERANCE) {
                    sampler.prime(sandboxCpuMillis)
                }
                lastSampleAt = now
                metrics = withContext(Dispatchers.IO) { sampler.sample(sandboxCpuMillis) }
                delay(SAMPLE_INTERVAL_MILLIS)
            }
        }
    }

    val cpu = metrics?.cpu
    val cpuLabel = when (cpu?.source) {
        CpuSource.SYSTEM -> stringResource(
            R.string.home_metrics_cpu_with_source,
            stringResource(R.string.home_metrics_source_system),
        )
        CpuSource.SYSTEM_LOAD -> stringResource(
            R.string.home_metrics_cpu_with_source,
            stringResource(R.string.home_metrics_source_system_load),
        )
        CpuSource.APP -> stringResource(
            R.string.home_metrics_cpu_with_source,
            stringResource(R.string.home_metrics_source_app),
        )
        CpuSource.NONE, null -> stringResource(R.string.home_metrics_cpu)
    }
    val temperature = metrics?.temperature
    val temperatureLabel = if (temperature == null) {
        stringResource(R.string.home_metrics_temp)
    } else {
        stringResource(
            R.string.home_metrics_temp_with_source,
            stringResource(
                if (temperature.fromBattery) {
                    R.string.home_metrics_source_battery
                } else {
                    R.string.home_metrics_source_soc
                },
            ),
        )
    }
    val cpuPercent = cpu?.percent
    val memory = metrics?.memory
    val storage = metrics?.storage

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.home_metrics_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            MetricsRow(
                label = cpuLabel,
                detail = null,
                value = SystemMetricsParsing.cpuText(cpuPercent, cpu?.source ?: CpuSource.NONE),
                fraction = cpuPercent?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) },
                // 应用口径的阈值按「占整机比例」给：半数核被吃满就标一下；整机两档沿用 90%。
                alert = when (cpu?.source) {
                    CpuSource.APP -> (cpuPercent ?: 0.0) >= APP_CAPACITY_ALERT_PERCENT
                    else -> (cpuPercent ?: 0.0) >= CPU_ALERT_PERCENT
                },
            )
            MetricsRow(
                label = temperatureLabel,
                detail = null,
                value = SystemMetricsParsing.temperatureText(temperature?.celsius),
                fraction = temperature?.let {
                    SystemMetricsParsing.temperatureFraction(it.celsius)
                },
                alert = (temperature?.celsius ?: 0) >= TEMP_ALERT_CELSIUS,
            )
            MetricsRow(
                label = stringResource(R.string.home_metrics_memory),
                detail = memory?.let { SystemMetricsParsing.formatUsage(it) },
                value = SystemMetricsParsing.percentText(memory?.percent),
                fraction = memory?.percent?.let { it / 100f },
                alert = (memory?.percent ?: 0) >= USAGE_ALERT_PERCENT,
            )
            MetricsRow(
                label = stringResource(R.string.home_metrics_storage),
                detail = storage?.let { SystemMetricsParsing.formatUsage(it) },
                value = SystemMetricsParsing.percentText(storage?.percent),
                fraction = storage?.percent?.let { it / 100f },
                alert = (storage?.percent ?: 0) >= USAGE_ALERT_PERCENT,
            )
        }
    }
}

/**
 * 一行指标：左标签（可带一行「已用/总量」小字）+ 右数值 + 下方细进度条
 * （超阈值转错误色；没有读数就只画空轨）。
 */
@Composable
private fun MetricsRow(
    label: String,
    detail: String?,
    value: String,
    fraction: Float?,
    alert: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (detail != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (fraction != null && fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(
                            if (alert) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        ),
                )
            }
        }
    }
}

/**
 * 取样器：一次 [sample] 只读内核暴露的现成计数，不做进程遍历。
 *
 * 实例持有 CPU 前后两次的基线与上一格有效读数，须复用一个实例；[prime] 用于恢复采样前
 * 重置基线。读不到的来源各记一条一次性的日志：这条链上所有读取都是静默降级的，没有日志
 * 时无法把「被系统策略拦住」与「真的没有该节点」区分开。
 */
private class SystemMetricsSampler(context: Context) {
    private val appContext = context.applicationContext
    private var lastSystemTicks: CpuTicks? = null
    private var lastAppCpuMs: Long? = null
    private var lastSandboxCpuMs: Long? = null
    private var lastCpuWallMs = 0L
    private var lastCpu: CpuReading = CpuReading(percent = null, source = CpuSource.NONE)
    private val cpuHistory = ArrayDeque<Double>()
    private var cpuHistorySource: CpuSource? = null
    private var cpuLoadLogged = false
    private var cpuAppLogged = false
    private var temperatureUnavailableLogged = false
    private var socProbeLogged = false
    private var batteryBroadcastCelsius: Int? = null
    private var batteryBroadcastAt = 0L
    private var socUnavailable = false
    private var socZoneShortlist: List<File>? = null
    private var socZoneFailStreak = 0
    private var socNextProbeAtMs = 0L

    /**
     * 记一次基线：恢复采样前调用，避免把暂停期间的平均值算进第一格。
     *
     * [sandboxCpuMillis] 为宿主注入的沙箱树累计 CPU 毫秒数（未运行/读不到返回 null）。
     */
    suspend fun prime(sandboxCpuMillis: suspend () -> Long?) {
        lastSystemTicks = readSystemTicks()
        lastAppCpuMs = Process.getElapsedCpuTime()
        lastSandboxCpuMs = runCatching { sandboxCpuMillis() }.getOrNull()
        lastCpuWallMs = SystemClock.elapsedRealtime()
        // 平滑窗口也一起清空：暂停前后的占用不该被平均进同一个数字。
        cpuHistory.clear()
        cpuHistorySource = null
    }

    suspend fun sample(sandboxCpuMillis: suspend () -> Long?): SystemMetrics = SystemMetrics(
        cpu = readCpu(runCatching { sandboxCpuMillis() }.getOrNull()),
        temperature = readTemperature(),
        memory = readMemoryUsage(),
        storage = readStorageUsage(),
    )

    /**
     * CPU 压力读数，按可得性分三档（各 ROM 对 /proc 的开放策略不同，平台策略对
     * /proc/stat 与 /proc/loadavg 有 neverallow，厂商也可能自行放开，故逐档尝试）：
     *
     * 1. 整机占用率：/proc/stat 前后计数差值——最准，但多数新机型对应用封了该文件；
     * 2. 整机负载：/proc/loadavg 的一分钟平均负载 ÷ 核数——同样常被封；
     * 3. 本应用（含沙箱）：主进程 CPU 时间（Process.getElapsedCpuTime）加 [sandboxCpuMs]
     *    给出的沙箱进程树 CPU 时间——纯 API 加按 pid 的 /proc 读取，任何设备都拿得到。
     *
     * 三档都换算成「占整机 CPU 的比例」，与其余三行同一把尺。读不到值的那一轮（窗口过短、
     * 坏窗、读数缺失）沿用上一格读数，不闪占位符——[lastCpu] 只在成功读到值的那几轮更新。
     * 每档原始值都经 [smoothed] 并入最近约 10 秒的窗口再显示。
     */
    private fun readCpu(sandboxCpuMs: Long?): CpuReading {
        // 进入本轮先取应用档的两个原子读数并推进基线：档位切换后首窗必须只算一个窗口的
        // 增量——若基线只在应用档分支里推进，切档（如 /proc/stat 一度可读又不可读）后的
        // 第一窗会把跨越整段切换时间的累计增量当成单窗增量，画出一个虚峰。因此无论本轮
        // 最终用哪一档，都要把基线推进到本轮；应用档的增量用本轮 prev 局部变量算。
        val nowAppMs = Process.getElapsedCpuTime()
        val nowWallMs = SystemClock.elapsedRealtime()
        val previousAppMs = lastAppCpuMs
        val previousSandboxMs = lastSandboxCpuMs
        val previousWallMs = lastCpuWallMs
        lastAppCpuMs = nowAppMs
        lastSandboxCpuMs = sandboxCpuMs
        lastCpuWallMs = nowWallMs

        val ticks = readSystemTicks()
        SystemMetricsParsing.cpuUtilizationPercent(lastSystemTicks, ticks)?.let { percent ->
            lastSystemTicks = ticks
            val reading = smoothed(CpuReading(percent, CpuSource.SYSTEM))
            lastCpu = reading
            return reading
        }
        lastSystemTicks = ticks
        val loadPercent = readLoadavgLine()?.let { line ->
            SystemMetricsParsing.cpuLoadPercent(
                line,
                Runtime.getRuntime().availableProcessors(),
            )
        }
        if (loadPercent != null) {
            if (!cpuLoadLogged) {
                cpuLoadLogged = true
                Log.i(TAG, "cpu: system counters unavailable, using load average")
            }
            val reading = smoothed(CpuReading(loadPercent, CpuSource.SYSTEM_LOAD))
            lastCpu = reading
            return reading
        }
        // 沙箱未运行（或这一次读不到）时只算主进程；开始运行后的第一格只记基线不出值，
        // 免得把它「开机以来」的累计时间当成一个窗口的增量灌进去。
        val sandboxDeltaMs = if (sandboxCpuMs != null && previousSandboxMs != null) {
            (sandboxCpuMs - previousSandboxMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val appPercent = SystemMetricsParsing.cpuCapacityPercent(
            deltaCpuMs = previousAppMs?.let { (nowAppMs - it).coerceAtLeast(0L) + sandboxDeltaMs },
            wallMs = nowWallMs - previousWallMs,
            cores = Runtime.getRuntime().availableProcessors(),
        )
        if (appPercent != null) {
            if (!cpuAppLogged) {
                cpuAppLogged = true
                Log.i(TAG, "cpu: system counters unreadable, reporting this app's cpu time")
            }
            val reading = smoothed(CpuReading(appPercent, CpuSource.APP))
            lastCpu = reading
            return reading
        }
        return lastCpu
    }

    /**
     * 平滑：把本次原始读数并入最近 [CPU_SMOOTHING_SAMPLES] 个采样的窗口，返回窗口均值。
     * 口径切换时清空历史——不同口径的数字不能混在同一个均值里。
     */
    private fun smoothed(reading: CpuReading): CpuReading {
        val percent = reading.percent ?: return reading
        if (cpuHistorySource != reading.source) {
            cpuHistory.clear()
            cpuHistorySource = reading.source
        }
        cpuHistory.addLast(percent)
        while (cpuHistory.size > CPU_SMOOTHING_SAMPLES) {
            cpuHistory.removeFirst()
        }
        return reading.copy(percent = SystemMetricsParsing.averagePercent(cpuHistory.toList()))
    }

    /** /proc/stat 首行 → 累计计数；文件不可读（被策略拦住/不存在）返回 null。 */
    private fun readSystemTicks(): CpuTicks? {
        val line = runCatching {
            File("/proc/stat").bufferedReader().use { it.readLine() }
        }.getOrNull() ?: return null
        return SystemMetricsParsing.cpuTicks(line)
    }

    /** /proc/loadavg 首行；不可读返回 null。 */
    private fun readLoadavgLine(): String? = runCatching {
        File("/proc/loadavg").bufferedReader().use { it.readLine() }
    }.getOrNull()

    /** 整机内存：/proc/meminfo 的 MemTotal / MemAvailable（平台策略允许应用读该文件）。 */
    private fun readMemoryUsage(): Usage? {
        val head = runCatching {
            File("/proc/meminfo").bufferedReader().use { reader ->
                generateSequence { reader.readLine() }.take(8).joinToString("\n")
            }
        }.getOrNull() ?: return null
        return SystemMetricsParsing.memoryUsage(head)
    }

    /** 数据分区（应用与沙箱运行时都在其上）存储占用：statfs 一次系统调用。 */
    private fun readStorageUsage(): Usage? {
        val stat = runCatching { StatFs(appContext.dataDir.absolutePath) }.getOrNull() ?: return null
        return SystemMetricsParsing.storageUsage(stat.totalBytes, stat.availableBytes)
    }

    /**
     * 温度：优先 SoC 热区，读不到退回电池温度。
     *
     * 两者物理含义不同（芯片结温 / 电池表面温度），界面按来源标注，不混为一谈；
     * 部分机型对应用封了 /sys/class/thermal，此时只剩电池温度可用。
     */
    private fun readTemperature(): Temperature? {
        readSocCelsius()?.let { return Temperature(it, fromBattery = false) }
        readBatteryCelsius()?.let { return Temperature(it, fromBattery = true) }
        if (!temperatureUnavailableLogged) {
            temperatureUnavailableLogged = true
            Log.w(TAG, "temperature unavailable: no readable thermal zone and no battery temperature")
        }
        return null
    }

    /**
     * 取命名属于 SoC 一类的热区里最热的一个。
     *
     * zone 目录只在需要时扫描并把命中名单缓存下来：zone 集合在设备生命周期内是固定的，
     * 每 2 秒全量枚举一次 + 逐个读 type 是白烧的开销。
     *
     * 两种「拿不到名单」的处置不同：目录**读不了**（策略拒绝）是永久情形，置
     * [socUnavailable] 本会话不再试；**没有匹配 zone** 可能只是启动早期 thermal 尚未就绪，
     * 故不粘死，按 [SOC_REPROBE_INTERVAL_MILLIS] 节流重扫。短名单里全部取值失败/非法连续
     * [SOC_ZONE_FAIL_STREAK_LIMIT] 轮同样清空名单并进入节流——否则「有名单但恒读不到值」
     * 会退化成每 3 轮一次的无限全量枚举。
     */
    private fun readSocCelsius(): Int? {
        if (socUnavailable) return null
        val shortlist = socZoneShortlist ?: run {
            val now = SystemClock.elapsedRealtime()
            if (now < socNextProbeAtMs) return null
            probeSocZones() ?: run {
                socNextProbeAtMs = now + SOC_REPROBE_INTERVAL_MILLIS
                return null
            }
        }
        var hottest: Int? = null
        for (zone in shortlist) {
            val raw = runCatching { File(zone, "temp").readText() }.getOrNull() ?: continue
            val celsius = SystemMetricsParsing.socCelsius(raw) ?: continue
            hottest = maxOf(hottest ?: celsius, celsius)
        }
        if (hottest == null) {
            if (++socZoneFailStreak >= SOC_ZONE_FAIL_STREAK_LIMIT) {
                socZoneShortlist = null
                socZoneFailStreak = 0
                socNextProbeAtMs = SystemClock.elapsedRealtime() + SOC_REPROBE_INTERVAL_MILLIS
            }
        } else {
            socZoneFailStreak = 0
        }
        return hottest
    }

    /**
     * 扫描一次 /sys/class/thermal，返回命中 SoC 命名的 zone 目录并缓存短名单。
     *
     * 目录不可读（策略拒绝）→ 置 [socUnavailable]（本会话不再重试）+ 一次性日志；
     * 目录可读但没有匹配 zone → 只返回 null，是否重扫交给调用方的节流决定。
     */
    private fun probeSocZones(): List<File>? {
        val dir = File("/sys/class/thermal")
        val zones = runCatching {
            dir.listFiles { file -> file.name.startsWith("thermal_zone") }
        }.getOrNull()
        if (zones == null) {
            socUnavailable = true
            if (!socProbeLogged) {
                socProbeLogged = true
                Log.w(TAG, "temperature: cannot list $dir; falling back to battery only")
            }
            return null
        }
        val matching = zones.filter { zone ->
            runCatching { File(zone, "type").readText() }
                .getOrNull()
                ?.let { SystemMetricsParsing.isSocZone(it) } == true
        }
        if (matching.isEmpty()) {
            if (!socProbeLogged) {
                socProbeLogged = true
                Log.w(TAG, "temperature: no SoC thermal zone among ${zones.size} zone(s)")
            }
            return null
        }
        socZoneShortlist = matching
        socZoneFailStreak = 0
        return matching
    }

    /**
     * 电池温度：先读 sysfs（零 IPC）；该节点缺失或值不可信时才退回系统粘性广播
     * （一次系统服务调用），并按 [BATTERY_BROADCAST_REFRESH_MILLIS] 降频、其余轮次用缓存。
     */
    private fun readBatteryCelsius(): Int? {
        val fromSysfs = runCatching {
            File("/sys/class/power_supply/battery/temp").readText().trim().toLongOrNull()
        }.getOrNull()
        SystemMetricsParsing.batteryCelsius(fromSysfs)?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (batteryBroadcastCelsius == null || now - batteryBroadcastAt >= BATTERY_BROADCAST_REFRESH_MILLIS) {
            batteryBroadcastAt = now
            val battery = runCatching {
                appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()
            val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            batteryBroadcastCelsius = SystemMetricsParsing.batteryCelsius(
                tenths?.takeIf { it != Int.MIN_VALUE }?.toLong(),
            )
        }
        return batteryBroadcastCelsius
    }
}
