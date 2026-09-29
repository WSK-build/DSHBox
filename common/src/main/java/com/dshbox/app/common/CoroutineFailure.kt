package com.dshbox.app.common

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Failure handler for process-lifetime coroutine scopes (the managers and the
 * foreground service).
 *
 * Such scopes are not tied to any UI lifecycle, so an uncaught exception in one of
 * their children would reach the thread's default handler and take the whole process
 * down. Recording it with a full stack trace keeps the scope alive for the work that
 * follows, instead of turning a single failed operation into a crash.
 *
 * The message goes through [LogRedactor] because child failures may carry guest
 * output or URLs that must not land in the log verbatim.
 */
fun coroutineFailureHandler(tag: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, failure ->
        val message = failure.message ?: failure.javaClass.simpleName
        Log.e(tag, "uncaught coroutine failure: ${LogRedactor.redact(message)}", failure)
    }
