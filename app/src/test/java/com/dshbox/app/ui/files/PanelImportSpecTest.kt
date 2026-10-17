package com.dshbox.app.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 顶部加号面板的「动作名 → 选择器参数」映射必须唯一。
 *
 * 同一张映射同时服务两条路径：风险确认通过后的回跳执行，与免确认时的直通调用。
 * 若两条路径各自拼 MIME 与导入模式，任何一侧漂移都表现为「点了没反应且无报错」，
 * 编译期与运行时都不会报——故在此锁住映射本身：MIME 集合、导入模式、初始位置提示，
 * 以及拍摄动作不得出现在导入映射中（它由拍摄分支单独处理）。
 */
class PanelImportSpecTest {

    @Test
    fun importFileUsesAnyMimeInPlainMode() {
        val spec = panelImportSpecOf(PANEL_ACTION_IMPORT_FILE)
        assertEquals(listOf("*/*"), spec?.mimeTypes)
        assertEquals("file", spec?.mode)
        // 「手机文件」沿用选择器上次位置，不给初始位置提示
        assertNull(spec?.initialUri)
    }

    @Test
    fun importExtractUsesExtractMode() {
        val spec = panelImportSpecOf(PANEL_ACTION_IMPORT_EXTRACT)
        assertEquals(listOf("*/*"), spec?.mimeTypes)
        assertEquals("extract", spec?.mode)
        assertNull(spec?.initialUri)
    }

    @Test
    fun importImageCoversImagesAndVideos() {
        val spec = panelImportSpecOf(PANEL_ACTION_IMPORT_IMAGE)
        assertEquals(listOf("image/*", "video/*"), spec?.mimeTypes)
        assertEquals("file", spec?.mode)
    }

    @Test
    fun importImageHintsImageRoot() {
        // 选择器默认停在上次位置，图像入口须给分类根，否则落不到图片分类
        assertEquals(MEDIA_ROOT_IMAGES, panelImportSpecOf(PANEL_ACTION_IMPORT_IMAGE)?.initialUri)
    }

    @Test
    fun importAudioUsesAudioMime() {
        val spec = panelImportSpecOf(PANEL_ACTION_IMPORT_AUDIO)
        assertEquals(listOf("audio/*"), spec?.mimeTypes)
        assertEquals("file", spec?.mode)
    }

    @Test
    fun importAudioHintsAudioRoot() {
        assertEquals(MEDIA_ROOT_AUDIO, panelImportSpecOf(PANEL_ACTION_IMPORT_AUDIO)?.initialUri)
    }

    @Test
    fun mediaRootsAreDistinct() {
        // 两个分类根必须是不同 URI，否则音频入口会落到图片分类
        assertEquals(false, MEDIA_ROOT_IMAGES == MEDIA_ROOT_AUDIO)
    }

    @Test
    fun captureActionsAreNotImportEntries() {
        // 拍摄不经过系统选择器，必须落在规格之外，由拍摄分支处理
        assertNull(panelImportSpecOf(PANEL_ACTION_CAPTURE_PHOTO))
        assertNull(panelImportSpecOf(PANEL_ACTION_CAPTURE_VIDEO))
    }

    @Test
    fun unknownActionHasNoSpec() {
        assertNull(panelImportSpecOf("write"))
        assertNull(panelImportSpecOf(""))
    }
}
