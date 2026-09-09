package com.dshbox.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.dshbox.app.service.SandboxService
import com.dshbox.app.ui.MainScreen
import com.dshbox.app.ui.theme.AppLocaleState
import com.dshbox.app.ui.theme.DshAppTheme

/**
 * 改继承 AppCompatActivity——应用内语言切换
 * （AppCompatDelegate.setApplicationLocales）依赖它完成 API<33 的
 * 持久化恢复与前台 Activity 自动重建；Compose 用法不受影响。
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DshAppTheme {
                MainScreen()
            }
        }
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
