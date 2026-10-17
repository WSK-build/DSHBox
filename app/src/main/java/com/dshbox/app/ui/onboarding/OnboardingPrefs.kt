package com.dshbox.app.ui.onboarding

import android.content.Context

/**
 * 新手引导标志的轻量持久化（SharedPreferences）。
 *
 * 与 [com.dshbox.app.ui.theme.AppThemeState] 同款写法：共用应用的
 * `app_settings` 偏好文件，只增一个布尔键。
 *
 * 该标志仅记录「用户已完成或跳过一次引导」，不再参与「是否展示引导」的判定——
 * 判定由 [OnboardingGate] 按两层运行环境（Debian / node）的实时状态得出，
 * 环境未装完时（含 profile 缺失的半成品）下次启动仍会重新展示引导。键名与
 * 存储方式保持不变。
 */
object OnboardingPrefs {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

    fun isCompleted(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_COMPLETED, false)

    fun markCompleted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ONBOARDING_COMPLETED, true)
            .apply()
    }
}
