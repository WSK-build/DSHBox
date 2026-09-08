package com.dshbox.app.ui.files.viewer

import com.dshbox.app.util.viewer.FileTypeClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 查看器路由决策单测（纯 JVM）。
 *
 * M3 返工 A 回归：路由决策此前内嵌 Composable 无法单测，OFFICE 抽文本视图未接通
 * （抽取成功的 docx/xlsx 落信息卡，只有手动「按文本打开」可见）而 147 例全绿漏检——
 * 决策抽为 [resolveBodyMode] 纯函数后由此类锁定。
 */
class ViewerRoutingTest {


    private fun auto(kind: FileTypeClassifier.FileKind?, ext: String = "", sub: String? = null, hasFull: Boolean = false): ViewerMode =
        resolveBodyMode(ViewerMode.AUTO, kind, ext, sub, hasFull)

    // ---------------- 返工 A：OFFICE 抽文本视图接通 ----------------

    @Test
    fun officeWithExtractedTextRoutesToReadonlyText() {
        assertEquals(ViewerMode.TEXT, auto(FileTypeClassifier.FileKind.OFFICE, "docx", "docx", hasFull = true))
        assertEquals(ViewerMode.TEXT, auto(FileTypeClassifier.FileKind.OFFICE, "xlsx", "xlsx", hasFull = true))
    }

    @Test
    fun officeWithoutExtractedTextFallsBackToInfo() {
        // 抽取失败/空文本/pptx（不支持抽取，loadViewerFile 不产出 fullBytes）→ 信息卡兜底，绝不进 Hex
        assertEquals(ViewerMode.INFO, auto(FileTypeClassifier.FileKind.OFFICE, "docx", "docx", hasFull = false))
        assertEquals(ViewerMode.INFO, auto(FileTypeClassifier.FileKind.OFFICE, "xlsx", "xlsx", hasFull = false))
        assertEquals(ViewerMode.INFO, auto(FileTypeClassifier.FileKind.OFFICE, "pptx", "pptx", hasFull = false))
    }

    // ---------------- M3 路由表全量锁定 ----------------

    @Test
    fun pdfAndArchiveRouting() {
        assertEquals(ViewerMode.PDF, auto(FileTypeClassifier.FileKind.PDF, "pdf", "pdf"))
        // 可承接压缩包（zip 族 + tar 族）
        assertEquals(ViewerMode.ARCHIVE, auto(FileTypeClassifier.FileKind.ARCHIVE, "zip", "zip"))
        assertEquals(ViewerMode.ARCHIVE, auto(FileTypeClassifier.FileKind.ARCHIVE, "tar.gz", "tar.gz"))
        assertEquals(ViewerMode.ARCHIVE, auto(FileTypeClassifier.FileKind.ARCHIVE, "tar", "tar"))
        // 不承接：7z/rar/纯 gzip/zstd/bzip2 容器 → 信息卡
        assertEquals(ViewerMode.INFO, auto(FileTypeClassifier.FileKind.ARCHIVE, "7z", "7z"))
        assertEquals(ViewerMode.INFO, auto(FileTypeClassifier.FileKind.ARCHIVE, "rar", "rar"))
        assertEquals(ViewerMode.INFO, auto(FileTypeClassifier.FileKind.ARCHIVE, "gz", "gzip"))
        assertEquals(ViewerMode.INFO, auto(FileTypeClassifier.FileKind.ARCHIVE, "zst", "zstd"))
    }

    @Test
    fun markupPreviewExtsRouteToMarkupOthersToText() {
        assertEquals(ViewerMode.MARKUP, auto(FileTypeClassifier.FileKind.MARKUP, "md", "md"))
        assertEquals(ViewerMode.MARKUP, auto(FileTypeClassifier.FileKind.MARKUP, "markdown", "markdown"))
        assertEquals(ViewerMode.MARKUP, auto(FileTypeClassifier.FileKind.MARKUP, "html", "html"))
        assertEquals(ViewerMode.MARKUP, auto(FileTypeClassifier.FileKind.MARKUP, "svg", "svg"))
        // xml/css/vue 维持纯文本编辑（无预览）
        assertEquals(ViewerMode.TEXT, auto(FileTypeClassifier.FileKind.MARKUP, "xml", "xml"))
        assertEquals(ViewerMode.TEXT, auto(FileTypeClassifier.FileKind.MARKUP, "css", "css"))
        assertEquals(ViewerMode.TEXT, auto(FileTypeClassifier.FileKind.MARKUP, "vue", "vue"))
    }

    @Test
    fun textImageHexAndFallbackRouting() {
        assertEquals(ViewerMode.TEXT, auto(FileTypeClassifier.FileKind.TEXT, "txt", null))
        assertEquals(ViewerMode.IMAGE, auto(FileTypeClassifier.FileKind.IMAGE, "png", "png"))
        assertEquals(ViewerMode.HEX, auto(FileTypeClassifier.FileKind.HEX, "so", "elf-aarch64"))
        assertEquals(ViewerMode.HEX, auto(FileTypeClassifier.FileKind.UNKNOWN, "", null))
        assertEquals(ViewerMode.INFO, auto(FileTypeClassifier.FileKind.AUDIO_VIDEO, "mp4", "mp4"))
        assertEquals(ViewerMode.INFO, auto(null))
    }

@Test
    fun explicitTextAndHexOverrideWinOverAuto() {
        assertEquals(ViewerMode.TEXT, resolveBodyMode(ViewerMode.TEXT, FileTypeClassifier.FileKind.PDF, "pdf", "pdf", false))
        assertEquals(ViewerMode.HEX, resolveBodyMode(ViewerMode.HEX, FileTypeClassifier.FileKind.TEXT, "txt", null, true))
        assertEquals(ViewerMode.HEX, resolveBodyMode(ViewerMode.HEX, FileTypeClassifier.FileKind.OFFICE, "docx", "docx", true))
    }

    // ---------------- 返工：INFO 显式覆盖短路（阻断缺陷回归，逐 kind 全量） ----------------
    // onFallback 降级写 viewMode=INFO；此前缺 INFO 短路，PDF/ARCHIVE/MARKUP 的 kind 分支
    // 无视 INFO → 查看器重组、错误态/密码框无限重弹、全屏透明层关不掉吃触摸。
    // 修复要求：显式覆盖断言必须逐枚举值覆盖，不能只测 TEXT/HEX 两个捷径。

    @Test
    fun infoOverrideWinsForEveryKind() {
        // 覆盖页所有可降级类型：INFO 必须无条件返回 INFO（无视 kind/子类型/抽取态）
        assertEquals(ViewerMode.INFO, resolveBodyMode(ViewerMode.INFO, FileTypeClassifier.FileKind.PDF, "pdf", "pdf", false))
        assertEquals(ViewerMode.INFO, resolveBodyMode(ViewerMode.INFO, FileTypeClassifier.FileKind.PDF, "pdf", "pdf", true))
        assertEquals(ViewerMode.INFO, resolveBodyMode(ViewerMode.INFO, FileTypeClassifier.FileKind.ARCHIVE, "zip", "zip", false))
        assertEquals(ViewerMode.INFO, resolveBodyMode(ViewerMode.INFO, FileTypeClassifier.FileKind.ARCHIVE, "tar.gz", "tar.gz", false))
        assertEquals(ViewerMode.INFO, resolveBodyMode(ViewerMode.INFO, FileTypeClassifier.FileKind.ARCHIVE, "7z", "7z", false))
        assertEquals(ViewerMode.INFO, resolveBodyMode(ViewerMode.INFO, FileTypeClassifier.FileKind.MARKUP, "md", "md", false))
        assertEquals(ViewerMode.INFO, resolveBodyMode(ViewerMode.INFO, FileTypeClassifier.FileKind.MARKUP, "html", "html", true))
        assertEquals(ViewerMode.INFO, resolveBodyMode(ViewerMode.INFO, FileTypeClassifier.FileKind.MARKUP, "xml", "xml", false))
        assertEquals(ViewerMode.INFO, resolveBodyMode(ViewerMode.INFO, FileTypeClassifier.FileKind.OFFICE, "docx", "docx", true))
        assertEquals(ViewerMode.INFO, resolveBodyMode(ViewerMode.INFO, FileTypeClassifier.FileKind.OFFICE, "pptx", "pptx", false))
        // 其余 kind 同样被覆盖（含 AUTO 下的正常目标，防未来新增 kind 时漏短路）
        assertEquals(ViewerMode.INFO, resolveBodyMode(ViewerMode.INFO, FileTypeClassifier.FileKind.TEXT, "txt", null, true))
        assertEquals(ViewerMode.INFO, resolveBodyMode(ViewerMode.INFO, FileTypeClassifier.FileKind.IMAGE, "png", "png", false))
        assertEquals(ViewerMode.INFO, resolveBodyMode(ViewerMode.INFO, FileTypeClassifier.FileKind.HEX, "so", "elf", false))
        assertEquals(ViewerMode.INFO, resolveBodyMode(ViewerMode.INFO, FileTypeClassifier.FileKind.UNKNOWN, "", null, false))
        assertEquals(ViewerMode.INFO, resolveBodyMode(ViewerMode.INFO, FileTypeClassifier.FileKind.AUDIO_VIDEO, "mp4", "mp4", false))
        assertEquals(ViewerMode.INFO, resolveBodyMode(ViewerMode.INFO, null, "", null, false))
    }

    // ---------------- markupKindOf 门控 ----------------

    @Test
    fun markupKindOnlyForMarkupKind() {
        assertEquals(MarkupKind.MD, markupKindOf(FileTypeClassifier.FileKind.MARKUP, "md"))
        assertEquals(MarkupKind.HTML, markupKindOf(FileTypeClassifier.FileKind.MARKUP, "htm"))
        assertEquals(MarkupKind.SVG, markupKindOf(FileTypeClassifier.FileKind.MARKUP, "svg"))
        assertNull(markupKindOf(FileTypeClassifier.FileKind.MARKUP, "xml"))
        // 非 MARKUP kind 一律 null（如文本内容却带 .md 扩展名的空文件被归 TEXT 时）
        assertNull(markupKindOf(FileTypeClassifier.FileKind.TEXT, "md"))
        assertNull(markupKindOf(FileTypeClassifier.FileKind.HEX, "html"))
    }
}
