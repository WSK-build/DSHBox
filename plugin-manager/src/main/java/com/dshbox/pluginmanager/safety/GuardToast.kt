package com.dshbox.pluginmanager.safety

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.dshbox.pluginmanager.R

/**
 * 安全模式的提示 —— 用 app 里到处都在用的那个 **Toast**。
 *
 * 为什么不自己画、也不发通知栏通知：
 * - app 的一次性告知统一走 `Toast.makeText(...)`（全仓 30 处：设置里的"装配移动端适配包"、
 *   文件页、查看器、诊断页…）。系统 Toast 会自动在左侧显示**应用图标**
 *   （`mipmap/brand_icon.png`：黑底白花），所以"方框 + app 图标 + 文字描述"这件事
 *   不需要我们准备任何图标或样式；
 * - 通知栏那条会常驻、用户关不掉；Toast 自己会消失，不会留下状态。
 *
 * 线程约束：`Toast` 必须在**带 Looper 的线程**上创建与显示，否则平台抛
 * `Can't toast on a thread that has not called Looper.prepare()`。本类的调用方
 * （启动守护）跑在 `Dispatchers.IO` / 模块自有 scope 上，因此统一 post 到主线程后再显示。
 *
 * 纪律：**只给"安全模式启动相关"的动作发提示**（跳过插件 / 已启动 / 兜底 / 停止重试），
 * 其余情况一律不弹。
 *
 * 时长：救援中与失败用 [Toast.LENGTH_LONG]（要看细节），成功用 [Toast.LENGTH_SHORT]（只看结果）。
 */
class GuardToast(private val context: Context) {

    /** 主线程 Handler：见类注释的线程约束。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 救援中：本轮跳过了这些插件，正在重启。 */
    fun attempting(round: Int, isolated: List<String>) {
        val title = context.getString(R.string.pm_guard_toast_attempt_title, round)
        val detail = context.getString(
            R.string.pm_guard_toast_attempt_skipped,
            isolated.size,
            isolated.joinToString(", "),
        )
        show("$title\n$detail", Toast.LENGTH_LONG)
    }

    /** 救援成功（本轮确实跳过了插件）。 */
    fun recovered(isolated: List<String>) {
        val title = context.getString(R.string.pm_guard_toast_recovered_title, isolated.size)
        val detail = context.getString(
            R.string.pm_guard_toast_recovered_skipped,
            isolated.joinToString(", "),
        )
        show("$title\n$detail", Toast.LENGTH_SHORT)
    }

    /** 兜底：改为绝对安全模式启动。 */
    fun escalatedToAbsolute() {
        show(context.getString(R.string.pm_guard_toast_absolute), Toast.LENGTH_LONG)
    }

    /** 收敛为"停止自动重试"。 */
    fun gaveUp() {
        show(context.getString(R.string.pm_guard_toast_failed), Toast.LENGTH_LONG)
    }

    /**
     * 在主线程显示；失败必须留痕。
     *
     * 这里曾经静默吞异常，导致"提示完全没出现、日志里也查不到任何线索"。
     */
    private fun show(text: String, duration: Int) {
        mainHandler.post {
            runCatching { Toast.makeText(context.applicationContext, text, duration).show() }
                .onFailure { Log.w(TAG, "toast failed: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "PluginGuard"
    }
}
