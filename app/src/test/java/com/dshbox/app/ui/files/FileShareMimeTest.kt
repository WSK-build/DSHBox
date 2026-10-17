package com.dshbox.app.ui.files

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分享意图的 MIME 类型必须按文件实际类型给出：给错会让分享面板里的应用
 * 收不到该文件（例如音频只对 audio 应用可见）。未知类型一律二进制流，
 * 避免用具体类型把可用目标筛没。
 */
class FileShareMimeTest {

    @Test
    fun imageExtensionsMapToImage() {
        assertEquals("image/*", shareMimeTypeOf("photo-1.jpg"))
        assertEquals("image/*", shareMimeTypeOf("photo-1.JPEG"))
        assertEquals("image/*", shareMimeTypeOf("shot.png"))
        assertEquals("image/*", shareMimeTypeOf("shot.heic"))
    }

    @Test
    fun videoExtensionsMapToVideo() {
        assertEquals("video/*", shareMimeTypeOf("clip.mp4"))
        assertEquals("video/*", shareMimeTypeOf("clip.mkv"))
    }

    @Test
    fun audioExtensionsMapToAudio() {
        assertEquals("audio/*", shareMimeTypeOf("song.mp3"))
        assertEquals("audio/*", shareMimeTypeOf("song.m4a"))
        assertEquals("audio/*", shareMimeTypeOf("song.flac"))
    }

    @Test
    fun textAndArchiveExtensionsMapTheirTypes() {
        assertEquals("text/plain", shareMimeTypeOf("notes.txt"))
        assertEquals("text/plain", shareMimeTypeOf("build.sh"))
        assertEquals("application/pdf", shareMimeTypeOf("doc.pdf"))
        assertEquals("application/zip", shareMimeTypeOf("pack.zip"))
    }

    @Test
    fun unknownOrMissingExtensionFallsBackToOctetStream() {
        assertEquals("application/octet-stream", shareMimeTypeOf("mystery.bin"))
        assertEquals("application/octet-stream", shareMimeTypeOf("noextension"))
        assertEquals("application/octet-stream", shareMimeTypeOf(""))
    }
}
