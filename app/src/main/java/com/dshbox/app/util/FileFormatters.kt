package com.dshbox.app.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale

/** Formats a byte count as a human-readable size (e.g. "1.5 GB"). */
fun formatFileSize(bytes: Long): String = when {
    // 固定 Locale.US——俄/法 locale 用逗号小数、阿语用阿拉伯-印度数字，
    // 与 /§3.7「西文数字」决策冲突。
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}

/** Resolves a content URI's display name via the ContentResolver. */
fun queryDisplayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull() ?: File(uri.path ?: "").name
