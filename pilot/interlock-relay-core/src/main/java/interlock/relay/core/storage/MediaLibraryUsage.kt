package interlock.relay.core.storage

import android.content.Context
import android.provider.MediaStore

/**
 * 已写进系统媒体库的字节，按"用户能不能在相册里看到"分成两项。
 *
 * 分项的理由：图片与视频进系统相册（Gallery 只索引这两类），音频进的是媒体库的 Audio
 * 集合、由音乐应用消费 —— 相册里看不到它。两者合成一行，用户会拿着"相册里没有这首歌"
 * 来对账；分开报，读数才与他在设备上能看到的东西一一对应。
 *
 * 这些条目落在本应用私有目录之外：不受产物配额约束，也不会被「立即清理」回收，
 * 且除单次上传上限外没有任何总量上限。占用页必须报出来，否则占用读数与设备实际发生的一切对不上。
 *
 * 不做缓存：一次查询即一个瞬时值，界面按次读取；缓存会让"立即清理"之后读数不动。
 */
class MediaLibraryUsage(
    private val context: Context,
    /**
     * 相册目录名，来自 [interlock.relay.core.spi.RelayPathPolicy.mediaAlbumDir]。
     * 查询前缀必须与写入落点（DirectBackend 的 uploadDirs）同源：两边各写一份时，
     * 宿主换目录名只改一半，占用读数就会与相册里实际看到的东西对不上。
     */
    private val mediaAlbumDir: String = interlock.relay.core.spi.RelayPathPolicy.BuiltIn.mediaAlbumDir,
) {

    /** 图片与视频：系统相册里能看到的那两类。 */
    fun galleryBytes(): Long = bytesUnder(galleryPrefixes(mediaAlbumDir))

    /** 音频：在系统媒体库的音频集合里，相册看不到。 */
    fun audioBytes(): Long = bytesUnder(audioPrefixes(mediaAlbumDir))

    /** 查询失败或本机没有可查的媒体库时回 0，绝不让占用页因此打不开。 */
    private fun bytesUnder(prefixes: List<String>): Long {
        var total = 0L
        val cursor = runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(MediaStore.Files.FileColumns.SIZE),
                selection(prefixes),
                selectionArgs(prefixes),
                null,
            )
        }.getOrNull() ?: return 0L
        cursor.use {
            val sizeAt = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            while (it.moveToNext()) total += it.getLong(sizeAt)
        }
        return total
    }

    companion object {
        /**
         * 助手写入系统媒体库的**三个固定落点**，由写入处的落点规则给出（两处共用同一个
         * [interlock.relay.core.spi.RelayPathPolicy.mediaAlbumDir]，改值只改策略一处）。
         * 前两个是相册能看到的（图片、视频），第三个是音频（相册看不到）。
         * 纯函数形态：判据要能在纯 JVM 单测里被逐条校验，不依赖 Android 类型。
         *
         * 判据只能是这三个前缀，不能按目录名的文字特征做子串匹配：
         * 后者会把用户在别处解压出的同名目录一并算成助手占用，
         * 读出的字节数与实际写入媒体库的对不上。
         */
        internal fun galleryPrefixes(albumDir: String): List<String> =
            listOf("Pictures/$albumDir/", "Movies/$albumDir/")

        internal fun audioPrefixes(albumDir: String): List<String> =
            listOf("Music/$albumDir/")

        /**
         * 列名取字面量而不是 `MediaStore` 常量：判据要能在纯 JVM 单测里被逐条校验，
         * 而 Android 常量在桌面 JVM 上取不到（值即 `relative_path`，与常量等值）。
         */
        private const val COLUMN_RELATIVE_PATH = "relative_path"

        /** `RELATIVE_PATH` 的取值以 `/` 结尾（目录路径），因此前缀加 `%` 即可覆盖该目录下所有条目。 */
        internal fun selection(prefixes: List<String>): String =
            prefixes.joinToString(prefix = "(", separator = " OR ", postfix = ")") {
                "$COLUMN_RELATIVE_PATH LIKE ?"
            }

        internal fun selectionArgs(prefixes: List<String>): Array<String> =
            prefixes.map { "$it%" }.toTypedArray()
    }
}
