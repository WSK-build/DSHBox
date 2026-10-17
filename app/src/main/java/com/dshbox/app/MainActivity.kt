package com.dshbox.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import com.dshbox.browser.api.BrowserContract
import com.dshbox.app.service.SandboxService
import com.dshbox.app.ui.MainScreen
import com.dshbox.app.ui.files.InboundShareItem
import com.dshbox.app.ui.files.parseInboundShare
import com.dshbox.app.ui.theme.AppLocaleState
import com.dshbox.app.ui.theme.DshAppTheme
import com.dshbox.app.util.viewer.ExternalOpener

/** 已消费外部分享意图的保存标记键：配置变更/进程恢复重建本页时据此跳过重复解析。 */
private const val STATE_INBOUND_CONSUMED = "inbound_share_consumed"

/**
 * 改继承 AppCompatActivity——应用内语言切换
 * （AppCompatDelegate.setApplicationLocales）依赖它完成 API<33 的
 * 持久化恢复与前台 Activity 自动重建；Compose 用法不受影响。
 */
class MainActivity : AppCompatActivity() {
    private val browserDshRouteRequest = mutableLongStateOf(0L)

    /** 外部应用分享/打开进来的待导入清单；交给文件页消费后置空。 */
    private val inboundShareItems = mutableStateOf<List<InboundShareItem>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeBrowserRoute(intent)
        // 配置变更（如应用内切换语言）与进程恢复会带着同一个启动意图重建本页：
        // 系统侧的意图是独立副本，清本地字段挡不住它，故用已消费标记兜底，避免重复导入同一批。
        if (savedInstanceState?.getBoolean(STATE_INBOUND_CONSUMED) != true) {
            consumeInboundShare(intent)
        }
        enableEdgeToEdge()
        // 全局返回兜底：注册早于 setContent 内的一切页面级处理器（返回派发按后注册者
        // 优先，本回调优先级最低）——首页 / 文件 / 终端三个页签的根部都不消费返回键，
        // 到这里统一回到系统桌面；DSH / 设置 / Pilot / Cordis 各有自己的根部处理器，
        // 不会走到这里。
        // 不用 moveTaskToBack：它只把任务退到后台，前台要让给"上一个用过的任务"——此前
        // 开过的浏览器是独立任务，因此会被顶到前台，表现为"按返回跳到浏览器"。任务本身
        // 仍留在后台（前台服务继续跑），从桌面或最近任务回来时状态不丢。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        })
        setContent {
            DshAppTheme {
                MainScreen(
                    browserDshRouteRequest = browserDshRouteRequest.longValue,
                    inboundShareItems = inboundShareItems.value,
                    onInboundShareConsumed = { inboundShareItems.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeBrowserRoute(intent)
        consumeInboundShare(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 无论本次有没有收到分享都记下"已消费"：重建时不再重复解析启动意图。
        outState.putBoolean(STATE_INBOUND_CONSUMED, true)
    }

    private fun consumeBrowserRoute(intent: Intent?) {
        if (intent?.getBooleanExtra(BrowserContract.EXTRA_OPEN_DSH_PAGE, false) != true) return
        intent.removeExtra(BrowserContract.EXTRA_OPEN_DSH_PAGE)
        browserDshRouteRequest.longValue += 1L
    }

    /**
     * 收下外部分享/打开的文件与文字。
     *
     * 文件 URI 可能落在 data / clipData / extras 任一处，故解析成功后三处一并清空：
     * 即便某条路径被系统重新投递，也不会再解析出同一批。空清单不改状态（用户取消分享、
     * 或意图与本次功能无关时都会走到这里）。
     */
    private fun consumeInboundShare(intent: Intent?) {
        val items = parseInboundShare(intent, ExternalOpener.FILE_PROVIDER_AUTHORITY)
        if (items.isEmpty()) return
        intent?.apply {
            replaceExtras(android.os.Bundle())
            data = null
            clipData = null
        }
        inboundShareItems.value = items
    }

    override fun onResume() {
        super.onResume()
        // 同步语言事实源（含 API 33+ 系统设置里直接改应用语言的情况），
        // 并在语言变化后刷新前台服务通知（通知不随 Activity 重建自动更新）。
        AppLocaleState.refresh(this)
        // 跟随系统时镜像 tag 恒为空——比对「实际生效 locale」，
        // 用户改系统语言后通知文案也能刷新。
        val effectiveTag = AppLocaleState.currentTag(this).ifEmpty {
            java.util.Locale.getDefault().toLanguageTag()
        }
        val prefs = getSharedPreferences(AppLocaleState.PREFS_NAME, MODE_PRIVATE)
        if (prefs.getString(AppLocaleState.KEY_LAST_NOTIFIED_TAG, null).orEmpty() != effectiveTag) {
            prefs.edit().putString(AppLocaleState.KEY_LAST_NOTIFIED_TAG, effectiveTag).apply()
            SandboxService.refreshNotification(this)
        }
    }
}
