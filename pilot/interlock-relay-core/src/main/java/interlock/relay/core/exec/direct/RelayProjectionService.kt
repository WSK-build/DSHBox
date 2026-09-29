package interlock.relay.core.exec.direct

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import interlock.relay.core.R
import interlock.relay.core.runtime.RelayRuntime
import interlock.relay.core.spi.RelayChannelRole
import interlock.relay.core.spi.RelayNotificationChannelSpec

/**
 * 承载屏幕采集的前台服务。
 *
 * 它存在的理由只有一条：Android 14 起，拿着 MediaProjection token 建虚拟屏之前，
 * 必须有一个 **`mediaProjection` 类型**的前台服务在跑，否则 `createVirtualDisplay`
 * 直接抛 SecurityException。服务本身不做任何事，也不接 binder——
 * 采集会话由 [ScreenProjection] 在应用进程内持有。
 *
 * 只在用户确认过系统弹框之后启动（见 `ScreenProjection.onConsentResult`），
 * 因此不会出现在用户没同意采集却被挂上一条常驻通知的场合。
 */
class RelayProjectionService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 这条回调在主线程上，抛出去就是整个宿主进程终止。Android 12 起应用在后台时
        // startForeground 会抛 ForegroundServiceStartNotAllowedException——采集通路刻意
        // 不抢前台，正好落在那一族的边界上。失败了要 stopSelf：不进前台又不退的服务会被
        // 系统判 ForegroundServiceDidNotStartInTimeException，那一次是系统替我们杀进程。
        val seated = runCatching {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        }
        if (seated.isFailure) {
            runCatching { stopSelf() }
        }
        showing = seated.isSuccess
        // Android 14 起，`getMediaProjection()` 本身就要求这条 mediaProjection 前台服务
        // 已经在跑：同步紧跟着取 token 会拿到 SecurityException，而失败后去 stop 一个
        // 还没落地 startForeground 的服务，会被系统判
        // ForegroundServiceDidNotStartInTimeException 直接带走整个进程。
        // 所以「服务已就位」这件事在这里交回给采集侧，由它再去换 token。
        // 没坐稳也叫：token 换不到时采集侧会回「后端不可用」，比让那次调用干等到超时诚实。
        runCatching { onReady?.invoke() }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification() = ongoingNotification(this)

    override fun onDestroy() {
        showing = false
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 0x5049

        /** 这条通知只在一次采集会话期间存在；语言核对只在这期间做事。 */
        @Volatile
        private var showing = false

        private fun channelSpec(context: Context): RelayNotificationChannelSpec =
            RelayRuntime.effectiveSurfaces().notificationChannels()
                .firstOrNull { it.role == RelayChannelRole.PROJECTION }
                ?: RelayNotificationChannelSpec.defaults().first { it.role == RelayChannelRole.PROJECTION }

        /**
         * 采集会话进行到一半时用户可能换了语言。服务自己没有任何周期，所以这一问由
         * 通道那条前台服务的周期核对带一次（见 `RelayService`）：它没在跑时
         * 也不会有采集会话在跑，两条通道的存续期是同一条。
         */
        fun refreshOngoing(context: Context) {
            if (!showing) return
            runCatching {
                context.getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, ongoingNotification(context))
            }
        }

        private fun ongoingNotification(context: Context): android.app.Notification {
            // 服务与 applicationContext 不带手选语言，取文案一律过 RelayText。
            val text = RelayRuntime.effectiveText(context)
            return NotificationCompat.Builder(context, channelSpec(context).id)
                .setSmallIcon(R.drawable.ic_relay_notification)
                .setContentTitle(text.string(R.string.relay_module_title))
                .setContentText(text.string(R.string.relay_projection_ongoing))
                .setOngoing(true)
                .setSilent(true)
                .build()
        }

        /** 由采集侧在下发启动前设置，服务进入前台后回调一次。 */
        @Volatile
        var onReady: (() -> Unit)? = null

        /**
         * 把服务起出去，返回调用是否被系统接下。
         *
         * 启动失败（后台启动被系统拦下等）必须显式说出去：等待方拿着「弹框已确认」的结果
         * 却等不来前台服务，Android 14 起连 token 都换不到，静默吞掉只会让那一路干等
         * 到握手超时，还以为只是用户没点。返回 false 时由采集侧立刻收场。
         */
        fun start(context: Context): Boolean {
            ensureChannel(context)
            val intent = Intent(context, RelayProjectionService::class.java)
            return runCatching {
                context.startForegroundService(intent)
            }.isSuccess
        }

        fun stop(context: Context) {
            onReady = null
            runCatching { context.stopService(Intent(context, RelayProjectionService::class.java)) }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelSpec = channelSpec(context)
            if (manager.getNotificationChannel(channelSpec.id) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    channelSpec.id,
                    RelayRuntime.effectiveText(context).string(channelSpec.titleRes),
                    channelSpec.importance,
                ),
            )
        }
    }
}
