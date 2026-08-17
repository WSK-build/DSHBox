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
