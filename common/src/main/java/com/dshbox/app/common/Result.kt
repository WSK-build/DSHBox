package com.dshbox.app.common

sealed class AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>()
    data class Failure(val error: AppError) : AppResult<Nothing>()
}

data class AppError(
    val code: String,
    val message: String,
    val cause: Throwable? = null,
    val recoverable: Boolean = true,
    /**
     * 用户可见文案（可本地化）。[message] 保留原样用于日志与既有
     * 拼接路径；UI 渲染点优先展示 userMessage（为 null 时回退 message，
     * 兼容尚未迁移的生产方）。领域层产出文案请用 userMessage + UiText。
     */
    val userMessage: UiText? = null,
) {
    companion object {
        fun unrecoverable(code: String, message: String) =
            AppError(code, message, recoverable = false)
    }
}

inline fun <T> runCatchingApp(block: () -> T): AppResult<T> =
    try {
        AppResult.Success(block())
    } catch (t: Throwable) {
        AppResult.Failure(AppError("EXECUTION_FAILED", t.message ?: "unknown error", t))
    }
