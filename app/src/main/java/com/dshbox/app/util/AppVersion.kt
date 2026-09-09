package com.dshbox.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.dshbox.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本号比较（纯 JVM，可单测）。
 * 规则：可带 "v"/"V" 前缀；主次修订最多取 4 段数字；"-rc.x"/"+meta" 等尾缀忽略。
 * @return >0 表示 a 更新，<0 表示 b 更新，0 表示相等。
 */
fun compareVersions(a: String, b: String): Int {
    fun norm(s: String): List<Int> {
        val core = s.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-').substringBefore('+')
        val parts = core.split('.').mapNotNull { it.toIntOrNull() }
        return (parts + List(4 - parts.size) { 0 }).take(4)
    }
    val x = norm(a)
    val y = norm(b)
    for (i in 0 until 4) {
        if (x[i] != y[i]) return x[i].compareTo(y[i])
    }
    return 0
}

/** App 自动更新检测：从 GitHub Releases 读取最新 tag 与本地版本比对。 */
object AppUpdater {
    /** 官网首页（含下载入口） */
    const val SITE_URL = "https://wsk-build.github.io/DSHBox/"

    private const val API_URL = "https://api.github.com/repos/WSK-build/DSHBox/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    val currentVersion: String
        get() = BuildConfig.VERSION_NAME

    data class CheckResult(
        val latestTag: String?,
        val currentVersion: String,
        val error: Boolean,
    ) {
        val isNewer: Boolean
            get() = latestTag != null && compareVersions(latestTag, currentVersion) > 0
    }

    /** 拉取 GitHub Releases latest 的 tag_name；网络/解析失败置 error=true。 */
    suspend fun checkLatest(): CheckResult = withContext(Dispatchers.IO) {
        try {
            val conn = URL(API_URL).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                // GitHub API 要求显式 UA，否则返回 403。
                conn.setRequestProperty("User-Agent", "DSHBox/${currentVersion}")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val code = conn.responseCode
                val ok = code in 200..299
                val body = if (ok) conn.inputStream.bufferedReader().readText() else null
                val tag = body?.let { JSONObject(it).optString("tag_name").takeIf { s -> s.isNotBlank() } }
                CheckResult(latestTag = tag, currentVersion = currentVersion, error = !ok || tag == null)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            CheckResult(latestTag = null, currentVersion = currentVersion, error = true)
        }
    }

    /** 打开系统浏览器到官网首页（下载入口所在）。 */
    fun openSite(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SITE_URL)))
        } catch (_: Exception) {
            // 设备上没有可用浏览器时静默失败。
        }
    }

    // ---- “忽略该版本”持久化（仅作用于启动自动弹窗，避免反复打扰） ----
    private const val PREFS_NAME = "app_update_ignore"
    private const val KEY_IGNORED_VERSION = "ignored_version"

    /** 用户此前忽略的版本 tag（无则 null）。 */
    fun ignoredVersion(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_IGNORED_VERSION, null)

    /** 记录“忽略该版本”：之后自动检测到不高于该版本的新版时不再弹提示。 */
    fun ignoreVersion(context: Context, tag: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_IGNORED_VERSION, tag)
            .apply()
    }

    /** 判断某最新 tag 是否应被静默（已被忽略，或未高于已忽略版本）。 */
    fun shouldSuppressAutoPrompt(context: Context, latestTag: String?): Boolean {
        if (latestTag == null) return true
        val ignored = ignoredVersion(context) ?: return false
        return compareVersions(latestTag, ignored) <= 0
    }
}
