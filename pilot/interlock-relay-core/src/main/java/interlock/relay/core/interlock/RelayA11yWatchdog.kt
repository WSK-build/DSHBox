package interlock.relay.core.interlock

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import interlock.relay.core.exec.direct.RelayService

/**
 * 周期巡检「无障碍还开着、但进程已被系统带走」这一种状态，并把进程重新拉起来。
 *
 * 它只做成一件事：让进程回来。系统只在本应用的进程活着时才去绑定名单里那个无障碍服务，
 * 进程被顺手清掉就一直不绑。挡得住的是系统对后台进程的普通回收，挡不住 `am force-stop`
 * 与厂商的强制清理 —— 那两类会连带取消本任务，是平台行为。所以这条不是对抗
 * 「用户或厂商主动停我们」的手段，只是不让一次普通回收变成永久失效。
 *
 * 那条「正在等待指令」的常驻通知不在本任务的承诺范围内：被它拉起的进程里没有会话
 * 要伺候，通知只由通道自身的状态决定挂不挂（见 [RelayService.start]）。
 *
 * 用平台自带的 JobScheduler 而不是 WorkManager：本模块不引入新依赖，
 * 而这里需要的只是一条周期任务。
 */
class RelayA11yWatchdog : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val gap = SystemStateProbe(applicationContext).accessibilityGap()
        // 名单里已经没有本应用 = 权限真的被关掉了。把进程重新拉起来拿不回一个被撤销的授权，
        // 只会留下一条白打扰用户的常驻通知，所以这一档什么都不做。
        // gap 为 null 表示服务已经绑着，本就没有要修的东西。
        if (gap == AccessibilityGap.NOT_BOUND) {
            // 只是把「通道在跑时进程别掉出前台档」这件事重新补上；通道没在跑时这一句什么都不做。
            RelayService.start(applicationContext)
        }
        params?.let { jobFinished(it, false) }
        return false
    }

    /** 不要求重排：这一次没做成，下一个周期还会再来，即时重试没有额外收益。 */
    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val TAG = "RelayA11yWatchdog"

        /** 15 分钟是平台对周期任务的下限，写更小的值只会被向上取整，不如如实写这一档。 */
        private const val INTERVAL_MS = 15L * 60L * 1000L
        private const val JOB_ID = 0x5041

        /** 通道启动时登记。同一 [JOB_ID] 重复登记即替换，与 [cancel] 一样按幂等看待。 */
        fun schedule(context: Context) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, RelayA11yWatchdog::class.java))
                // 周期任务：不设约束的话系统会把它当成一次立即执行的任务，跑完就再无下文。
                .setPeriodic(INTERVAL_MS)
                // 不跨重启保留：开机后本模块的通道并不会自己起来，那时没有会话要伺候，
                // 任务只会在没人的时候白跑一遍；且持久化还要另加开机广播权限。
                .setPersisted(false)
                .build()
            val outcome = runCatching {
                context.getSystemService(JobScheduler::class.java)
                    ?.schedule(job) ?: JobScheduler.RESULT_FAILURE
            }
            when {
                outcome.isFailure -> Log.w(TAG, "schedule failed", outcome.exceptionOrNull())
                outcome.getOrNull() != JobScheduler.RESULT_SUCCESS -> Log.w(TAG, "schedule rejected")
            }
        }

        /** 通道停止时取消：留一个没有通道可服务的周期任务，等于替用户挂着一个不做的事。 */
        fun cancel(context: Context) {
            runCatching {
                context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
            }
        }
    }
}
