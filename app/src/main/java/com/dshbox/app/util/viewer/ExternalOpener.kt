package com.dshbox.app.util.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.dshbox.app.util.FileOpException
import java.io.File

/**
 * 外部应用调用三路径（1.2.0 §6.11）：ACTION_VIEW / ACTION_EDIT / ACTION_SEND。
 *
 * - content URI 经 FileProvider（Manifest 注册 `com.dshbox.app.fileprovider`，
 *   res/xml/file_paths.xml 四根精确 files-path 最小授权）；
 * - MIME 用「扩展名 + 魔数」双重判定（[mimeFor]），未知给 application/octet-stream；
 * - 无应用响应 → [openView] 返回 false，调用方降级引导「导出后打开」（§6.11.1）；
 * - VIEW/SEND 仅授只读授权；EDIT 授读写授权，返回后的变更检测与 rootfs 权限位恢复
 *   由调用方（FileViewerScreen）在 ActivityResult 回调中执行；
 * - 风险确认（§4.2/§6.11）：风险层文件由 UI 先弹强确认再调这里；普通文件直接放行。
 */
object ExternalOpener {

    /** Manifest 中注册的 FileProvider authority 后缀。 */
    const val FILE_PROVIDER_AUTHORITY = "com.dshbox.app.fileprovider"

    /** 生成 content URI；路径未在 file_paths.xml 声明（如 legacy debian 根）时返回 null。 */
    fun contentUri(context: Context, file: File): Uri? = runCatching {
        FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
    }.getOrNull()

    /** MIME 判定：扩展名表 + 魔数兜底（§6.11.1）。 */
    fun mimeFor(name: String, type: FileTypeClassifier.FileType?): String {
        val ext = FileTypeClassifier.extensionOf(name)
        MIME_BY_EXT[ext]?.let { return it }
        return when (type?.kind) {
            FileTypeClassifier.FileKind.IMAGE -> "image/*"
            FileTypeClassifier.FileKind.PDF -> "application/pdf"
            FileTypeClassifier.FileKind.AUDIO_VIDEO -> "video/*"
            FileTypeClassifier.FileKind.TEXT, FileTypeClassifier.FileKind.MARKUP -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /**
     * 外部打开（ACTION_VIEW，主路径）。无可用应用返回 false（调用方降级导出）。
     * 使用 createChooser + 先行 queryIntentActivities 探测（targetSdk 30+ 包可见性
     * 由 Manifest <queries> 兜底）。
     */
    fun openView(context: Context, file: File, mime: String): Boolean {
        val uri = contentUri(context, file) ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            if (context.packageManager.queryIntentActivities(intent, 0).isEmpty()) return false
            context.startActivity(Intent.createChooser(intent, file.name))
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        }
    }

    /** 外部编辑（ACTION_EDIT）可启动的 Intent；无支持应用返回 null（调用方回退 VIEW）。 */
    fun buildEditIntent(context: Context, file: File, mime: String): Intent? {
        val uri = contentUri(context, file) ?: return null
        val edit = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        if (runCatching { context.packageManager.queryIntentActivities(edit, 0) }.getOrDefault(emptyList()).isEmpty()) {
            return null
        }
        return edit
    }

    /** 无 EDIT 支持时的回退打开 Intent（只读）。 */
    fun buildViewIntent(context: Context, file: File, mime: String): Intent? {
        val uri = contentUri(context, file) ?: return null
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (runCatching { context.packageManager.queryIntentActivities(view, 0) }.getOrDefault(emptyList()).isEmpty()) {
            return null
        }
        return view
    }

    /** 外部分享（ACTION_SEND，单文件只读授权；多文件 SEND_MULTIPLE 由文件页多选栏复用）。 */
    fun buildShareIntent(context: Context, files: List<File>, mime: String): Intent? {
        val uris = files.mapNotNull { contentUri(context, it) }
        if (uris.isEmpty()) return null
        return if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /** 单文件导出到 SAF（CreateDocument / OpenDocumentTree 目标），复制内容。 */
    fun exportSingleFile(context: Context, source: File, target: Uri) {
        val out = context.contentResolver.openOutputStream(target)
            ?: throw FileOpException("无法写入所选导出位置")
        out.use { o ->
            source.inputStream().use { it.copyTo(o) }
        }
    }

    private val MIME_BY_EXT: Map<String, String> = mapOf(
        "txt" to "text/plain", "md" to "text/markdown", "log" to "text/plain",
        "csv" to "text/csv", "html" to "text/html", "htm" to "text/html",
        "css" to "text/css", "xml" to "application/xml", "json" to "application/json",
        "pdf" to "application/pdf",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
        "svg" to "image/svg+xml", "heic" to "image/heic", "heif" to "image/heif",
        "avif" to "image/avif",
        "zip" to "application/zip", "jar" to "application/java-archive",
        "apk" to "application/vnd.android.package-archive",
        "gz" to "application/gzip", "tar" to "application/x-tar",
        "7z" to "application/x-7z-compressed", "rar" to "application/vnd.rar",
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "flac" to "audio/flac",
        "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm", "avi" to "video/x-msvideo",
        "doc" to "application/msword", "xls" to "application/vnd.ms-excel",
        "ppt" to "application/vnd.ms-powerpoint",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )
}
