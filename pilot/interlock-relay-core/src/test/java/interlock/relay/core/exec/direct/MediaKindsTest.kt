package interlock.relay.core.exec.direct

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体读取的按类型权限判据。三类各自只看实际用到的权限：缺音频不得把图片/视频
 * 一起判死；Android 14 的「选定照片」对图片/视频算已授权，对音频不算。
 * 判据是纯函数，这里用假权限表穷举三种系统版本口径。
 */
class MediaKindsTest {

    private fun table(vararg granted: String): (String) -> Boolean = { it in granted.toSet() }

    // ── kind 的通道字面量 ──

    @Test
    fun `kind 通道字面量只有三个`() {
        assertEquals(MediaKind.IMAGE, MediaKind.fromWire("image"))
        assertEquals(MediaKind.VIDEO, MediaKind.fromWire("video"))
        assertEquals(MediaKind.AUDIO, MediaKind.fromWire("audio"))
        assertEquals(null, MediaKind.fromWire("vido"))
        assertEquals(null, MediaKind.fromWire(""))
        assertEquals(null, MediaKind.fromWire("IMAGE"))
    }

    // ── Android 12 及以下：三类共用旧存储权限 ──

    @Test
    fun `API 31 缺旧存储权限时三类都缺`() {
        val granted = table()
        MediaKind.entries.forEach { kind ->
            assertEquals(
                listOf(@Suppress("DEPRECATION") Manifest.permission.READ_EXTERNAL_STORAGE),
                MediaKinds.missingFor(kind, granted, Build.VERSION_CODES.S),
            )
            assertFalse(MediaKinds.kindGranted(kind, granted, Build.VERSION_CODES.S))
        }
    }

    @Test
    fun `API 31 旧存储权限一条管三类`() {
        val granted = table(@Suppress("DEPRECATION") Manifest.permission.READ_EXTERNAL_STORAGE)
        MediaKind.entries.forEach { kind ->
            assertTrue(MediaKinds.kindGranted(kind, granted, Build.VERSION_CODES.S))
            assertTrue(MediaKinds.missingFor(kind, granted, Build.VERSION_CODES.S).isEmpty())
        }
    }

    // ── Android 13 起：三类各自独立 ──

    @Test
    fun `API 33 只给图片权限时音频与视频仍缺`() {
        val granted = table(Manifest.permission.READ_MEDIA_IMAGES)
        assertTrue(MediaKinds.kindGranted(MediaKind.IMAGE, granted, Build.VERSION_CODES.TIRAMISU))
        assertFalse(MediaKinds.kindGranted(MediaKind.VIDEO, granted, Build.VERSION_CODES.TIRAMISU))
        assertFalse(MediaKinds.kindGranted(MediaKind.AUDIO, granted, Build.VERSION_CODES.TIRAMISU))
        // 调用侧出闸就是这份清单：可读的类型必须返回空，不可读的只报主权限。
        assertEquals(
            emptyList<String>(),
            MediaKinds.missingFor(MediaKind.IMAGE, granted, Build.VERSION_CODES.TIRAMISU),
        )
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_VIDEO),
            MediaKinds.missingFor(MediaKind.VIDEO, granted, Build.VERSION_CODES.TIRAMISU),
        )
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_AUDIO),
            MediaKinds.missingFor(MediaKind.AUDIO, granted, Build.VERSION_CODES.TIRAMISU),
        )
    }

    /** 核心场景：缺音频权限不把图片/视频判死——任一类可读即整条能力系统侧可达。 */
    @Test
    fun `缺音频权限时图片与视频仍可读且整条能力可达`() {
        val granted = table(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        assertTrue(MediaKinds.anyKindReadable(granted, Build.VERSION_CODES.TIRAMISU))
        // 缺口清单只列真正缺的，不把已授权的类型一起报。
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_AUDIO),
            MediaKinds.allMissing(granted, Build.VERSION_CODES.TIRAMISU),
        )
    }

    // ── Android 14「选定照片」：对图片/视频算已授权，对音频不算 ──

    @Test
    fun `API 34 选定照片视为图片与视频已授权`() {
        val granted = table(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertTrue(MediaKinds.kindGranted(MediaKind.IMAGE, granted, Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
        assertTrue(MediaKinds.kindGranted(MediaKind.VIDEO, granted, Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
        assertFalse(MediaKinds.kindGranted(MediaKind.AUDIO, granted, Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
        assertTrue(MediaKinds.anyKindReadable(granted, Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
    }

    /**
     * 调用侧场景（BLOCKER 复核口径）：只给「选定照片」的用户请求图片/视频必须放行——
     * missingFor 只认「整类可读与否」，不认「候选里还有没给的」。
     */
    @Test
    fun `API 34 仅选定照片时图片与视频的缺口为空而音频仍报缺`() {
        val granted = table(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertEquals(
            emptyList<String>(),
            MediaKinds.missingFor(MediaKind.IMAGE, granted, Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
        assertEquals(
            emptyList<String>(),
            MediaKinds.missingFor(MediaKind.VIDEO, granted, Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_AUDIO),
            MediaKinds.missingFor(MediaKind.AUDIO, granted, Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_AUDIO),
            MediaKinds.allMissing(granted, Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
    }

    /** 调用侧场景：只给图片主权限（没给选定照片）同样放行图片——主权限命中即整类可读。 */
    @Test
    fun `API 34 只给图片主权限时图片缺口为空`() {
        val granted = table(Manifest.permission.READ_MEDIA_IMAGES)
        assertEquals(
            emptyList<String>(),
            MediaKinds.missingFor(MediaKind.IMAGE, granted, Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
    }

    /** 调用侧场景：替代候选（选定照片）没给而主权限也没给时，缺口只报主权限。 */
    @Test
    fun `API 34 图片完全不可读时缺口只报主权限`() {
        val granted = table(Manifest.permission.READ_MEDIA_AUDIO)
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES),
            MediaKinds.missingFor(MediaKind.IMAGE, granted, Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
    }

    @Test
    fun `API 34 三类全缺时缺口取并集且去重`() {
        val granted = table()
        val missing = MediaKinds.allMissing(granted, Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        // 三类各自报主权限：选定照片是满足授权的另一种途径，不是必缺的一环，不进清单。
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            ),
            missing,
        )
        assertEquals(missing.size, missing.distinct().size)
    }
}
