package com.dshbox.app.ui.files

import android.content.Intent
import android.net.Uri

/**
 * 外部应用分享或打开进来的一条待导入内容。
 *
 * 只在文件页内部流转：入口是系统意图（分享到 / 用其他应用打开），出口是用户选定的目录。
 */
sealed interface InboundShareItem {
    /** 外部应用给出的文件 URI（已排除本应用自身 FileProvider 的 URI）。 */
    data class FileUri(val uri: Uri) : InboundShareItem
    /** 纯文字内容（分享文字或链接），按用户预期落盘为 .txt。 */
    data class PlainText(val text: String, val subject: String?) : InboundShareItem
}

/**
 * 一次待执行的导入：目标目录与该批内容作为不可分割的一等对象一起传递。
 *
 * 风险确认弹窗可能停留一段时间，期间可能再来一批分享；把目标与批次绑在一起，
 * 避免"确认时读到的是另一批内容"而把新批次写进旧目标。
 */
data class InboundCommit(val target: java.io.File, val items: List<InboundShareItem>)

/**
 * 解析外部分享 / 打开意图为待导入清单。
 *
 * 三条入口：ACTION_SEND（单条）、ACTION_SEND_MULTIPLE（多条）、ACTION_VIEW（打开方式）。
 * 文件 URI 同时从 EXTRA_STREAM 与 clipData 收集——两者都可能承载：平台在发送方带
 * 读授权标志时会把 EXTRA_STREAM 迁进 clipData，而多选分享按契约用 ArrayList 传
 * EXTRA_STREAM，只认其中一种会静默丢内容。
 *
 * 两条口径：
 * - 只接受 content scheme：这是现代应用给出的文件 URI 唯一形态，其它 scheme 不解析；
 * - 本应用自身 FileProvider 的 URI 直接忽略——那是我方文件被再次送回，导入自身没有意义。
 */
fun parseInboundShare(intent: Intent?, ownAuthority: String): List<InboundShareItem> {
    if (intent == null) return emptyList()
    val uris = mutableListOf<Uri>()
    when (intent.action) {
        Intent.ACTION_SEND -> uris += streamUris(intent)
        Intent.ACTION_SEND_MULTIPLE -> uris += streamUris(intent)
        Intent.ACTION_VIEW -> intent.data?.let { uris += it }
        else -> return emptyList()
    }

    val items = mutableListOf<InboundShareItem>()
    uris.asSequence()
        .distinct()
        .filter { it.scheme == "content" && it.authority != ownAuthority }
        .forEach { items += InboundShareItem.FileUri(it) }

    // 没有附带文件、但有文字内容时按纯文字处理（分享文章 / 链接即此形态）。
    // 不做类型白名单：带文字就收，避免把可用的内容拦在门外。
    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
    if (items.isEmpty() && !text.isNullOrBlank()) {
        items += InboundShareItem.PlainText(text, intent.getStringExtra(Intent.EXTRA_SUBJECT))
    }
    return items
}

/** 收集意图里的全部文件 URI：EXTRA_STREAM（单值或容器）与 clipData、data 都算。 */
private fun streamUris(intent: Intent): List<Uri> {
    val result = mutableListOf<Uri>()
    // 读原始值再按类型分支，而不是对同一 key 分别按 Uri / ArrayList 两种类型取值：
    // API 33 以下那条回退路径是裸转型，取错类型会抛 ClassCastException（单个 Uri 与
    // ArrayList 是两种都会出现的形态：前者是单文件分享，后者是多选分享的契约形态）。
    when (val raw = intent.extras?.get(Intent.EXTRA_STREAM)) {
        is Uri -> result += raw
        is Iterable<*> -> result += raw.filterIsInstance<Uri>()
        is Array<*> -> result += raw.filterIsInstance<Uri>()
    }
    val clip = intent.clipData
    if (clip != null) {
        for (index in 0 until clip.itemCount) {
            clip.getItemAt(index).uri?.let { result += it }
        }
    }
    intent.data?.let { result += it }
    return result
}

/**
 * 外部分享条目的落盘文件名。
 *
 * 显示名可信（含扩展名、主干不是纯数字、长度正常）时原样使用——那是来源端的真实名字，
 * 不做改写；否则说明提供器只给了内部标识（如 `1234567`）或干脆没给，此时改用时间戳
 * 命名并按内容类型补后缀，避免把内部编号当成文件名交给用户。
 *
 * @param displayName 提供器返回的显示名（可为 null）
 * @param fallbackExtension 内容类型推出的后缀（可为 null，如 "apk"、"jpg"）
 * @param timestampMillis 兜底命名用的时间戳
 */
fun inboundFileName(displayName: String?, fallbackExtension: String?, timestampMillis: Long): String {
    val cleaned = displayName?.trim().orEmpty()
    val stem = cleaned.substringBeforeLast('.', cleaned)
    // 上限收在 80 字符：多字节名字按字节算也不会越过文件系统的 255 字节上限，
    // 超长时改用时间戳名——保证落得下，而不是整条导入失败。
    val plausible = cleaned.isNotEmpty() &&
        cleaned.contains('.') &&
        stem.isNotBlank() &&
        !stem.all(Char::isDigit) &&
        cleaned.length <= 80
    if (plausible) return cleaned
    val base = "shared-$timestampMillis"
    return if (fallbackExtension.isNullOrBlank()) base else "$base.$fallbackExtension"
}

/**
 * 纯文字分享落盘用的文件名：有主题用主题，没有则用时间戳，统一带 .txt。
 *
 * 主题可能含路径分隔符等非法字符，由调用处统一走文件名净化；此处只负责取名、限长与补后缀。
 */
fun inboundTextFileName(subject: String?, timestampMillis: Long): String {
    val base = subject?.trim().orEmpty().take(MAX_NAME_CHARS)
    return if (base.isEmpty()) "shared-$timestampMillis.txt" else "$base.txt"
}

/** 文件名长度上限（字符）：兼顾多字节名字不越过文件系统字节上限。 */
private const val MAX_NAME_CHARS = 80
