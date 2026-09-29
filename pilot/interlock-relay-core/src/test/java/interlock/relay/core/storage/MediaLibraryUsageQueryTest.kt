package interlock.relay.core.storage

import interlock.relay.core.spi.RelayPathPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体库用量的查询判据：只能认助手写入的那三个固定前缀，且按"相册能不能看到"分成两项。
 *
 * 为什么单独钉：判据一旦退回按目录名的文字特征做子串匹配，用户在别处解压出的同名目录
 * 就会被算成助手占用，占用页那一行随即与实际写入媒体库的字节对不上，
 * 而这类放宽没有任何编译期信号。
 */
class MediaLibraryUsageQueryTest {

    /** 前缀由路径策略的相册目录名推出：写入与对账共用 core 内建默认值。 */
    private val gallery = MediaLibraryUsage.galleryPrefixes(RelayPathPolicy.BuiltIn.mediaAlbumDir)
    private val audio = MediaLibraryUsage.audioPrefixes(RelayPathPolicy.BuiltIn.mediaAlbumDir)

    @Test
    fun defaultAlbumDirIsTheNeutralName() {
        assertEquals("Relay", RelayPathPolicy.BuiltIn.mediaAlbumDir)
        assertEquals(RelayPathPolicy.DEFAULT_MEDIA_ALBUM_DIR, RelayPathPolicy.BuiltIn.mediaAlbumDir)
    }

    /** 宿主传入自己的目录名时，前缀必须整体跟着变：这是 core 不写死取值的唯一出口。 */
    @Test
    fun albumDirComesFromThePathPolicyNotFromAHardcodedName() {
        val custom = MediaLibraryUsage.galleryPrefixes("MyAlbum") +
            MediaLibraryUsage.audioPrefixes("MyAlbum")
        assertEquals(listOf("Pictures/MyAlbum/", "Movies/MyAlbum/", "Music/MyAlbum/"), custom)
    }

    @Test
    fun gallerySelectionOnlyMatchesTheTwoWriteLocations() {
        // 完整形状逐字钉住：两个前缀各一个条件、OR 连接、整体加括号。
        assertEquals(
            "(relative_path LIKE ? OR relative_path LIKE ?)",
            MediaLibraryUsage.selection(gallery),
        )
    }

    @Test
    fun audioSelectionOnlyMatchesTheMusicLocation() {
        assertEquals(
            "(relative_path LIKE ?)",
            MediaLibraryUsage.selection(audio),
        )
    }

    @Test
    fun galleryArgumentsArePicturesAndMovies() {
        assertEquals(
            listOf("Pictures/Relay/%", "Movies/Relay/%"),
            MediaLibraryUsage.selectionArgs(gallery).toList(),
        )
    }

    @Test
    fun audioArgumentsAreMusicOnly() {
        assertEquals(
            listOf("Music/Relay/%"),
            MediaLibraryUsage.selectionArgs(audio).toList(),
        )
    }

    /**
     * 两项合起来必须正好是助手写入媒体库的三个落点：少一个会漏报占用，多一个会把
     * 助手不写的地方也算进来（音频只在 Music 下，不该出现在图视那一项里）。
     */
    @Test
    fun theTwoItemsTogetherCoverExactlyTheThreeWriteLocations() {
        val all = gallery + audio
        assertEquals(listOf("Pictures/Relay/", "Movies/Relay/", "Music/Relay/"), all)
        assertFalse("音频不得混进相册那一项", gallery.any { it.startsWith("Music") })
    }

    /** 旧的宽松判据（单个子串匹配）不得回归：它会把任何路径里含同名片段的条目都算进来。 */
    @Test
    fun looseSubstringMatchIsNotUsed() {
        val args = (MediaLibraryUsage.selectionArgs(gallery) +
            MediaLibraryUsage.selectionArgs(audio)).toList()
        assertFalse(args.any { it.startsWith("%") && it.endsWith("%") })
        assertTrue("参数不得以通配符开头（那是子串匹配的形状）", args.none { it.startsWith("%") })
    }
}
