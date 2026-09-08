package com.dshbox.app.ui.files.viewer

import com.dshbox.app.util.viewer.ArchiveBrowser
import com.dshbox.app.util.viewer.FileTypeClassifier

/**
 * 查看器路由决策（1.2.0 §6.1/§6.3，纯 JVM 可单测——M3 返工 A/B 后自 FileViewerScreen
 * 抽出：路由缺陷（OFFICE 抽文本视图未接通）曾因决策内嵌 Composable 无法单测而漏检，
 * 固化为纯函数后由 [ViewerRoutingTest] 锁定）。
 */

/** 查看模式（AUTO = 按类型分发；TEXT/HEX = 菜单强制覆盖；INFO = 信息卡兜底主视图；M3 增 PDF/ARCHIVE/MARKUP）。 */
internal enum class ViewerMode { AUTO, TEXT, IMAGE, HEX, INFO, PDF, ARCHIVE, MARKUP }

/** 落文本查看器的大类（TEXT + MARKUP；OFFICE 抽文本走 [resolveBodyMode] 的专用分支，不可编辑）。 */
internal val TEXT_KINDS = setOf(
    FileTypeClassifier.FileKind.TEXT,
    FileTypeClassifier.FileKind.MARKUP,
)

/** 落 Hex 查看的类型（UNKNOWN 为分类器预留输出，不产出；兜底同口径）。 */
internal val HEX_KINDS = setOf(
    FileTypeClassifier.FileKind.HEX,
    FileTypeClassifier.FileKind.UNKNOWN,
)

/**
 * AUTO 路由决策（纯函数）。
 *
 * **显式覆盖最先判定（含 INFO）**：TEXT/HEX/INFO 都是覆盖模式（菜单「按文本/按 Hex
 * 打开」与渲染失败降级 onFallback 均写 viewMode=INFO）。**INFO 短路是 M3 真机阻断缺陷
 * （返工批次）**：此前缺此分支，PDF/ARCHIVE/MARKUP 的 kind 分支无视 INFO，onFallback
 * 置 INFO 后 bodyMode 仍为原类型 → 查看器重组 → 错误态/密码框无限重弹、全屏覆盖层
 * 关不掉吃掉所有触摸（「移动到无反应」为次生现象）。
 * - PDF → PDF；可承接压缩包（formatOf 非 null）→ ARCHIVE，7z/rar/纯 gzip 等维持信息卡；
 * - MARKUP 预览扩展（md/html/svg）→ MARKUP，其余标记扩展（xml/css/vue）维持文本编辑；
 * - **OFFICE 且抽取成功（hasFullBytes）→ TEXT 只读视图**（抽取失败/空文本/pptx → INFO）；
 * - HEX/UNKNOWN → Hex；其余（音视频等）→ 信息卡。
 */
internal fun resolveBodyMode(
    viewMode: ViewerMode,
    kind: FileTypeClassifier.FileKind?,
    extension: String,
    subType: String?,
    hasFullBytes: Boolean,
): ViewerMode = when {
    viewMode == ViewerMode.HEX -> ViewerMode.HEX
    viewMode == ViewerMode.TEXT -> ViewerMode.TEXT
    viewMode == ViewerMode.INFO -> ViewerMode.INFO
    kind == null -> ViewerMode.INFO
    kind == FileTypeClassifier.FileKind.IMAGE -> ViewerMode.IMAGE
    markupKindOf(kind, extension) != null -> ViewerMode.MARKUP
    kind in TEXT_KINDS -> ViewerMode.TEXT
    kind == FileTypeClassifier.FileKind.PDF -> ViewerMode.PDF
    kind == FileTypeClassifier.FileKind.ARCHIVE &&
        ArchiveBrowser.formatOf(extension, subType) != null -> ViewerMode.ARCHIVE
    kind == FileTypeClassifier.FileKind.OFFICE && hasFullBytes -> ViewerMode.TEXT
    kind in HEX_KINDS -> ViewerMode.HEX
    else -> ViewerMode.INFO
}

/** M3（§6.7）：MARKUP 类型的预览承接（仅 MARKUP kind；xml/css 等仍走纯文本编辑）。 */
internal fun markupKindOf(kind: FileTypeClassifier.FileKind, extension: String): MarkupKind? {
    if (kind != FileTypeClassifier.FileKind.MARKUP) return null
    return when (extension) {
        "md", "markdown" -> MarkupKind.MD
        "html", "htm" -> MarkupKind.HTML
        "svg" -> MarkupKind.SVG
        else -> null
    }
}
