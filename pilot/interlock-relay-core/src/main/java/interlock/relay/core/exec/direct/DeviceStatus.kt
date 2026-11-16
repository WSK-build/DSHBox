package interlock.relay.core.exec.direct

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.getSystemService
import org.json.JSONObject
import java.util.TimeZone

/**
 * 设备状态读取：一条能力一次返回一组只读基础数据。本类不改任何设置、不发起任何
 * 系统弹框，除网络状态依赖安装时普通权限 `ACCESS_NETWORK_STATE` 外不依赖任何运行时权限。
 *
 * 字段判据是**逐字段容错**：厂商 ROM 上单个读数（音量流、亮度键、电池意图）随时可能
 * 缺失或抛错，把一个字段的问题放大成整条能力失败，等于让一次电池读数拖垮整组数据。
 * 每个字段独立取值：抛错或取到空白，数值字段落 null、字符串字段落 "unknown"，
 * 其余字段照常返回。「读到了 0」与「没读到」由 null 分开，不并成一回事。
 *
 * 读数接口收拢在 [Sources] 后面：设备走 [AndroidSources]，JVM 单测注入假读数，
 * 即可穷举「字段齐全」与「单字段抛错不拖垮其余字段」两类场景。
 */
internal class DeviceStatus internal constructor(
    private val read: Sources,
) {

    constructor(context: Context) : this(AndroidSources(context.applicationContext))

    /** 单字段读数口。每个方法只读一个字段，异常由 [snapshot] 的容错层兜住。 */
    internal interface Sources {
        fun timeEpochMs(): Long
        fun timezoneId(): String
        fun batteryLevel(): Int
        fun batteryPlugged(): Int
        fun batteryStatus(): Int
        fun screenOn(): Boolean
        fun keyguardLocked(): Boolean
        fun networkType(): String
        fun networkConnected(): Boolean
        fun streamVolume(stream: Int): Int
        fun brightness(): Int
        fun brightnessAuto(): Boolean
        fun manufacturer(): String
        fun model(): String
        fun androidVersion(): String
        fun resolution(): String
        fun language(): String
    }

    /**
     * 一次调用返回的整组数据。分组与能力说明的字段清单一一对应：
     * 时间/时区、电量/充电、屏幕/锁屏、网络、四类音量、亮度、设备信息。
     */
    fun snapshot(): JSONObject = JSONObject().apply {
        put("time", section {
            put("epochMs", numberLong { read.timeEpochMs() })
            put("timezoneId", text { read.timezoneId() })
        })
        put("battery", section {
            put("level", number { read.batteryLevel() })
            // 「在充电」看状态位（EXTRA_STATUS）：EXTRA_PLUGGED 说的是「接着电源」，
            // 接着电充满的状态不是在充电，两个字段不能混。
            put("charging", boolean { read.batteryStatus() == BatteryManager.BATTERY_STATUS_CHARGING })
            // 充电方式看接电类型（EXTRA_PLUGGED）：ac/usb/无线/底座，未接电源落 "none"。
            put("chargingMethod", text { chargingMethod(read.batteryPlugged()) })
        })
        put("screen", section {
            put("on", boolean { read.screenOn() })
            put("locked", boolean { read.keyguardLocked() })
        })
        put("network", section {
            put("type", text { read.networkType() })
            put("connected", boolean { read.networkConnected() })
        })
        put("volumes", section {
            put("media", number { read.streamVolume(AudioManager.STREAM_MUSIC) })
            put("ring", number { read.streamVolume(AudioManager.STREAM_RING) })
            put("notification", number { read.streamVolume(AudioManager.STREAM_NOTIFICATION) })
            put("alarm", number { read.streamVolume(AudioManager.STREAM_ALARM) })
        })
        put("brightness", section {
            put("level", number { read.brightness() })
            put("auto", boolean { read.brightnessAuto() })
        })
        put("device", section {
            put("manufacturer", text { read.manufacturer() })
            put("model", text { read.model() })
            put("androidVersion", text { read.androidVersion() })
            put("resolution", text { read.resolution() })
            put("language", text { read.language() })
        })
    }

    /** 充电方式：`EXTRA_PLUGGED` 的公开取值映射；0（未接电源）落在 "none"。 */
    private fun chargingMethod(plugged: Int): String = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
        0 -> "none"
        else -> UNKNOWN
    }

    /** 每个分组自己也是容错单元：组内读数抛错只让该组落空，不拖垮兄弟分组。 */
    private fun section(fill: JSONObject.() -> Unit): JSONObject =
        runCatching { JSONObject().apply(fill) }.getOrElse { JSONObject().put("error", UNKNOWN) }

    /** 数值字段：抛错落 null。「读到了 0」与「没读到」由 null 分开。 */
    private fun number(value: () -> Int): Any? = runCatching(value).getOrNull() ?: JSONObject.NULL

    /** 同数值字段的容错口径，长整型（纪元毫秒一类）走这一档。 */
    private fun numberLong(value: () -> Long): Any? = runCatching(value).getOrNull() ?: JSONObject.NULL

    /** 布尔字段：同数值字段的容错口径。 */
    private fun boolean(value: () -> Boolean): Any? = runCatching(value).getOrNull() ?: JSONObject.NULL

    /** 字符串字段：空白与异常同样落 "unknown"。 */
    private fun text(value: () -> String): String =
        runCatching(value).getOrDefault(UNKNOWN).ifBlank { UNKNOWN }

    private companion object {
        const val UNKNOWN = "unknown"
    }

    /**
     * 设备读数。每个方法只做一次系统调用并原样抛出：容错统一由 [snapshot] 兜，
     * 这里再各自捕获会把「哪里读不到」藏进第二层默认值里。
     */
    private class AndroidSources(private val context: Context) : Sources {

        override fun timeEpochMs(): Long = System.currentTimeMillis()

        override fun timezoneId(): String = TimeZone.getDefault().id

        override fun batteryLevel(): Int {
            val manager = context.getSystemService<BatteryManager>()
                ?: throw IllegalStateException("no battery service")
            val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level !in 0..100) throw IllegalStateException("battery level out of range: $level")
            return level
        }

        override fun batteryPlugged(): Int {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: throw IllegalStateException("no battery sticky intent")
            return intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        }

        override fun batteryStatus(): Int {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: throw IllegalStateException("no battery sticky intent")
            return intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        }

        override fun screenOn(): Boolean {
            val manager = context.getSystemService<PowerManager>()
                ?: throw IllegalStateException("no power service")
            return manager.isInteractive
        }

        override fun keyguardLocked(): Boolean {
            val manager = context.getSystemService<KeyguardManager>()
                ?: throw IllegalStateException("no keyguard service")
            return manager.isKeyguardLocked
        }

        override fun networkType(): String {
            val manager = context.getSystemService<ConnectivityManager>()
                ?: return OFFLINE
            val network = manager.activeNetwork ?: return OFFLINE
            val capabilities = manager.getNetworkCapabilities(network) ?: return OFFLINE
            return when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> OFFLINE
            }
        }

        override fun networkConnected(): Boolean {
            val manager = context.getSystemService<ConnectivityManager>() ?: return false
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        override fun streamVolume(stream: Int): Int {
            val manager = context.getSystemService<AudioManager>()
                ?: throw IllegalStateException("no audio service")
            return manager.getStreamVolume(stream)
        }

        override fun brightness(): Int =
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)

        override fun brightnessAuto(): Boolean =
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

        override fun manufacturer(): String = Build.MANUFACTURER

        override fun model(): String = Build.MODEL

        override fun androidVersion(): String = Build.VERSION.RELEASE

        override fun resolution(): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val manager = context.getSystemService<WindowManager>()
                    ?: throw IllegalStateException("no window service")
                val bounds = manager.currentWindowMetrics.bounds
                return "${bounds.width()}x${bounds.height()}"
            }
            @Suppress("DEPRECATION")
            val display = context.getSystemService<WindowManager>()?.defaultDisplay
                ?: throw IllegalStateException("no display")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            return "${metrics.widthPixels}x${metrics.heightPixels}"
        }

        override fun language(): String = context.resources.configuration.getLocales().get(0).toLanguageTag()

        private companion object {
            const val OFFLINE = "offline"
        }
    }
}
