package com.dshbox.app.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Formats a byte count as a human-readable size (e.g. "1.5 GB"). */
fun formatFileSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.1f GB", bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> String.format("%.1f MB", bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> String.format("%.1f KB", bytes.toDouble() / (1L shl 10))
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
