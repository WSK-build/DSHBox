package interlock.relay.core.exec.direct

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import interlock.relay.core.R
import interlock.relay.core.runtime.RelayRuntime
import interlock.relay.core.spi.RelayChannelRole
import interlock.relay.core.spi.RelayNotificationChannelSpec
import interlock.relay.core.spi.RelayText

/**
 * 通道运行期常驻的前台服务。
 *
 * 它不为完成任何一次调用，只为让宿主进程待在"系统与应用清理最先处理的那一档"之外：
 * 一个没有前台组件、也没有前台服务的进程被一键清理或后台限制杀掉之后，无障碍服务会
 * 跟着解绑，agent 侧看到的就是"能力忽然全灭"。挂一条前台服务不能挡住 `am force-stop`，
 * 挡住的是用户与厂商顺手的那一遍清理。
 *
 * 生命周期严格跟着 agent 通道：通道起时启动、通道停时立刻自撤。留着一条没有通道可服务的
 * 常驻通知，等于用一条打扰换一件不做的事。
 *
 * 它同时是那条通道唯一的持锁点：坐实之后按 [WAKE_LEASE_MS] 一段段留住 CPU，通道还在跑就
 * 一段接一段续下去（见 [wakeTick]）。[interlock.relay.core.transport.MailboxServer] 的循环与
 * [interlock.relay.core.interlock.RelayA11yWatchdog] 都不持锁。
 *
 * 锁的覆盖面就是"通道在跑"这段时间，不区分息屏期间有没有人在等回包。这是明确的取舍：
 * 信箱取件与回包要能在用户看不见屏幕的时候发生，代价是这段等待期间 CPU 不进深度休眠。
 */
class RelayService : Service() {

    private val wakeHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /** 连续几次读到"通道没在跑"才自撤；单次只放手，见 [wakeTick]。 */
    private var misses = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 那条通知的文案是"正在等待指令"，判据只能取通道自己的状态：
        // START_STICKY 的重新投递不经过 start()，进程被带走后系统会自己把这条 intent 送回来，
        // 而那一刻没有信箱循环在消费请求，贴出来就是一条反向承诺。
        if (!channelRunning()) {
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }
        // 与采集那条服务同一条约束：Android 12 起在后台调用 startForeground 会抛
        // ForegroundServiceStartNotAllowedException。抛出去不能吞了继续跑——一个声称
        // 在前台却没进前台的服务，会被系统判 ForegroundServiceDidNotStartInTimeException
        // 连带整个进程一起杀，那比没有这条服务更糟。
        val seated = runCatching {
            startForeground(NOTIFICATION_ID, buildNotification(), type())
        }
        seated.exceptionOrNull()?.let { cause ->
            // 失败必须留日志：这条服务的全部作用是「通道在跑时进程不被顺手清掉」，
            // 它没起来而外部只看到「清理照样生效」时，没有任何线索指向这里。
            Log.w(TAG, "startForeground failed", cause)
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }
        // 只有坐实了才留锁：startForeground 没成功的服务不算前台，留锁会让外部只看到耗电。
        // 先撤后投，避免 START_STICKY 重投递与 start() 各排一份续租。
        wakeHandler.removeCallbacks(wakeTick)
        wakeHandler.post(wakeTick)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeHandler.removeCallbacks(wakeTick)
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * 一段租期到点前重新确认通道还在跑，然后续上下一段。
     *
     * 续租而不是 acquire() 一次挂到底，是给"锁漏了"一个上限：服务被留在前台而通道已经不在
     * （进程重投、装配根被关掉）时，最多多持有一 [WAKE_LEASE_MS] 就会自己放手。
     */
    private val wakeTick = object : Runnable {
        override fun run() {
            if (!channelRunning()) {
                // 放手但不立刻退出：信箱目录被 guest 换掉时 dirsOk 只是短时 false，
                // [interlock.relay.core.transport.MailboxServer] 下一轮就会自愈。立刻 stopSelf()
                // 会让一次抖动换掉整段前台保护与 CPU 租期，所以先只放掉锁，等下一拍再判。
                // 真撤掉之后仍有起点：信箱目录恢复后，装配根的维护巡检（60 秒一拍）会按通道
                // 状态重新 start()（见 [interlock.relay.core.runtime.RelayContainer] 的 onMaintenance）。
                releaseWakeLock()
                misses++
                if (misses >= STOP_AFTER_MISSES) {
                    runCatching { stopSelf() }
                    return
                }
                wakeHandler.postDelayed(this, WAKE_RENEW_MS)
                return
            }
            misses = 0
            holdWakeLock()
            // 语言是运行期可改的，而这条常驻通知与各条渠道名都是一次性构建的。
            refreshOngoingText()
            RelayProjectionService.refreshOngoing(applicationContext)
            wakeHandler.postDelayed(this, WAKE_RENEW_MS)
        }
    }

    private fun holdWakeLock() {
        val lock = wakeLock ?: newWakeLock()
        if (lock == null) {
            // 拿不到锁只说明这台机器上这条通路不存在，不该让已经坐实的前台服务退出：
            // 挡清理仍然是它的本职，而取件最坏退回按 IDLE_POLL_MS 轮询。
            Log.w(TAG, "wake lock unavailable on this device")
            return
        }
        wakeLock = lock
        runCatching { lock.acquire(WAKE_LEASE_MS) }
            .onFailure { cause -> Log.w(TAG, "wake lock acquire failed", cause) }
    }

    private fun newWakeLock(): android.os.PowerManager.WakeLock? {
        val manager = runCatching {
            getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        }.getOrNull() ?: return null
        return runCatching {
            // 非引用计数：续租是把同一段租期往后推，不是叠层。叠层会让一次漏掉的
            // release 把锁永久留在进程上。
            manager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false) }
        }.getOrNull()
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        runCatching { if (lock.isHeld) lock.release() }
    }

    private fun type(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
        }

    private fun buildNotification(): android.app.Notification {
        val spec = channelSpec(RelayChannelRole.FOREGROUND)
        return NotificationCompat.Builder(this, spec.id)
            .setSmallIcon(R.drawable.ic_relay_notification)
            .setContentTitle(text().string(R.string.relay_module_title))
            .setContentText(text().string(R.string.relay_fgs_ongoing))
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * 取文案用的口径。服务与 applicationContext 都不带手选语言（平台只把它应用到
     * Activity 上下文），core 侧统一走 [RelayText]：宿主的语言层（含运行期切换）在那里实现。
     */
    private fun text(): RelayText = RelayRuntime.effectiveText(applicationContext)

    private fun channelSpec(role: RelayChannelRole): RelayNotificationChannelSpec =
        RelayRuntime.effectiveSurfaces().notificationChannels()
            .firstOrNull { it.role == role }
            ?: RelayNotificationChannelSpec.defaults().first { it.role == role }

    /**
     * 语言可以在运行期被改，而这条通知是一次性贴上去的：每拍核一次文案，变了就重贴。
     * 顺带把 core 各渠道的标题也核一遍 —— 渠道名冻在第一次建它的那门语言里。
     */
    private fun refreshOngoingText() {
        val text = text().string(R.string.relay_fgs_ongoing)
        if (text != postedText) {
            postedText = text
            runCatching {
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
            }
        }
        syncChannelNames(applicationContext)
    }

    private var postedText: String? = null

    companion object {
        private const val TAG = "RelayService"
        private const val NOTIFICATION_ID = 0x5043

        /** 只留 CPU，不点亮屏幕、不改音量：为的是息屏期间信箱还能取件回包。 */
        private const val WAKE_LOCK_TAG = "relay:channel-wake"

        /** 单次租期。取件最坏等满这么久，而锁漏掉时最坏也只多耗这么久电。 */
        private const val WAKE_LEASE_MS = 120_000L

        /** 小于租期：主线程被短暂占住也不会让某一段租期在通道还在跑时断掉。 */
        private const val WAKE_RENEW_MS = 90_000L

        /** 通道连续这么多次不在跑才自撤，即最长容忍 [WAKE_RENEW_MS] × 2 的空窗。 */
        private const val STOP_AFTER_MISSES = 2

        fun start(context: Context) {
            // 挂不挂这条通知由通道自己回答，不由调用方声明：看门狗也会调这里，
            // 而它把进程拉回来不等于信箱有人在消费。
            if (!channelRunning()) return
            ensureChannel(context)
            val intent = Intent(context, RelayService::class.java)
            runCatching {
                context.startForegroundService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RelayService::class.java)) }
        }

        /**
         * 通道此刻是否在消费信箱，只问装配根，不在这里按进程或绑定状态猜：
         * [RelayRuntime.isChannelRunning] 读的就是信箱循环与目录可用性，
         * 并且**不会**为这一问构造装配根——那会让一次状态读取建起整套存储与账本。
         */
        private fun channelRunning(): Boolean =
            runCatching { RelayRuntime.isChannelRunning() }.getOrDefault(false)

        private fun spec(context: Context, role: RelayChannelRole): RelayNotificationChannelSpec =
            RelayRuntime.effectiveSurfaces().notificationChannels()
                .firstOrNull { it.role == role }
                ?: RelayNotificationChannelSpec.defaults().first { it.role == role }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelSpec = spec(context, RelayChannelRole.FOREGROUND)
            if (manager.getNotificationChannel(channelSpec.id) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    channelSpec.id,
                    RelayRuntime.effectiveText(context).string(channelSpec.titleRes),
                    channelSpec.importance,
                ),
            )
        }

        /**
         * 把 core 各渠道的标题对回当前语言：已存在的渠道按 id 改名，importance、声音、
         * 振动、锁屏可见性一概不碰；渠道还不存在时什么都不做（建它是使用方的事）。
         */
        internal fun syncChannelNames(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val text = RelayRuntime.effectiveText(context)
            for (channelSpec in RelayRuntime.effectiveSurfaces().notificationChannels()) {
                val channel = manager.getNotificationChannel(channelSpec.id) ?: continue
                val wanted = runCatching { text.string(channelSpec.titleRes) }.getOrNull() ?: continue
                if (channel.name == wanted) continue
                channel.name = wanted
                runCatching { manager.createNotificationChannel(channel) }
            }
        }
    }
}
