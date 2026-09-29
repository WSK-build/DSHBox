package dshbox.adapter.entry

import android.app.NotificationManager
import android.content.Context
import dshbox.adapter.R
import dshbox.adapter.locale.withAppLanguage

/**
 * 通知渠道标题的语言同步。
 *
 * 平台只把手选语言应用到 Activity 上下文，由 `applicationContext` 或 Service 派生的
 * 通知与渠道标题会退回系统语言 —— 审批卡那一条已按同一口径修过（`PilotLocale`），
 * 但渠道名是**建它的那一刻冻住**的，之后再切语言也不会跟着变，用户在系统设置里看到的
 * 分组标题就与 app 里其余文字不同语言。
 *
 * 这里只做一件事：按同一个 id 把已存在的渠道**改名**。importance、声音、振动、锁屏可见性
 * 一概不碰 —— 那几项由各自的建渠道处决定，顺手重建会把静默档变成会响的档。
 * 渠道还不存在时什么都不做：建它是使用方的事，这里不替它决定重要性。
 */
object PilotNotificationChannels {

    /** 渠道 id → 标题词条。id 与各使用方保持一致。 */
    private val TITLES = mapOf(
        "pilot_channel" to R.string.pilot_fgs_label,
        "pilot_projection" to R.string.pilot_projection_channel,
        "pilot.assistant" to R.string.pilot_notify_channel_name,
        "pilot_approval" to R.string.pilot_approval_notify_channel,
    )

    /** 把手选语言下的渠道标题写回去；没手选语言时标题本来就一致，等于不做事。 */
    fun syncNames(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val localized = context.withAppLanguage()
        for ((id, titleRes) in TITLES) {
            val channel = manager.getNotificationChannel(id) ?: continue
            val wanted = runCatching { localized.getString(titleRes) }.getOrNull() ?: continue
            if (channel.name == wanted) continue
            channel.name = wanted
            runCatching { manager.createNotificationChannel(channel) }
        }
    }

    /**
     * 常驻通知与渠道名一起对回当前语言。
     *
     * 时机有两处：界面回到前台（用户刚在宿主设置里换过语言，回到这一页是最自然的对表点），
     * 以及通道那条前台服务的续租拍（90 秒）。后者兜住"换了语言但没再打开页面"的情况，
     * 前者让语言切换在肉眼时间内跟上新语言。
     *
     * [interlock.relay.core.exec.direct.RelayService.start] 自带"通道没在跑就直接
     * return"，所以这里叫它只在真有通道时重新坐实一次前台通知。
     */
    fun syncAll(context: Context) {
        syncNames(context)
        interlock.relay.core.exec.direct.RelayService.start(context)
        interlock.relay.core.exec.direct.RelayProjectionService.refreshOngoing(context)
    }
}
