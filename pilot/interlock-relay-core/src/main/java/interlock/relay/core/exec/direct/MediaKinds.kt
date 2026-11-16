package interlock.relay.core.exec.direct

import android.Manifest
import android.os.Build

/**
 * 媒体读取的**按类型**权限判据。三条媒体类型各自只看实际用到的权限：
 *
 * - Android 13 起，图片与视频用 `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`，音频用
 * `READ_MEDIA_AUDIO`；
 * - Android 14 起，用户只给「选定照片」时授予的是 `READ_MEDIA_VISUAL_USER_SELECTED`：
 * 那是系统对图片/视频的正式授权口径，视为图片与视频已授权；音频不在此列。
 * 该权限 Android 14 才存在，Android 13 不作为候选；
 * - Android 12 及以下，三类共用 `READ_EXTERNAL_STORAGE`。
 *
 * 全部判据收在纯函数里：输入只有「某条权限此刻是否已授予」与系统版本号，
 * 不碰 Context，JVM 单测可以按假权限表穷举三种类型 × 各授予状态。
 */
enum class MediaKind(val wire: String) {
    IMAGE("image"),
    VIDEO("video"),
    AUDIO("audio"),
    ;

    companion object {
        /** 通道里的类型字面量只有这三个：未知值一律拒，不落到默认集合上。 */
        fun fromWire(value: String): MediaKind? = entries.firstOrNull { it.wire == value }
    }
}

internal object MediaKinds {

    /**
     * 读取一条媒体类型需要的权限。返回的是**候选集**：Android 14 的「选定照片」与
     * 完整的图片/视频权限互为替代，命中其一即可，因此每类返回一个列表而非单条。
     * 「选定照片」候选只在 Android 14 起存在，Android 13 不进候选。
     */
    internal fun permissionsFor(kind: MediaKind, sdkInt: Int): List<String> = when {
        sdkInt < Build.VERSION_CODES.TIRAMISU ->
            listOf(@Suppress("DEPRECATION") Manifest.permission.READ_EXTERNAL_STORAGE)

        kind == MediaKind.IMAGE -> buildList {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
        }

        kind == MediaKind.VIDEO -> buildList {
            add(Manifest.permission.READ_MEDIA_VIDEO)
            if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
        }

        else -> listOf(Manifest.permission.READ_MEDIA_AUDIO)
    }

    /** 一条类型的**主**权限：候选集首条——报告缺口时只报它，不报替代候选。 */
    private fun primaryFor(kind: MediaKind, sdkInt: Int): String =
        permissionsFor(kind, sdkInt).first()

    /** 该类型此刻是否可读：候选集中任意一条已授予即可。 */
    internal fun kindGranted(kind: MediaKind, granted: (String) -> Boolean, sdkInt: Int): Boolean =
        permissionsFor(kind, sdkInt).any(granted)

    /**
     * 该类型此刻还缺什么。**调用侧的出闸判据就是这份清单**：类型可读（候选集中
     * 任意一条已授予，例如 Android 14 只给「选定照片」）时返回空——「还有候选没给」
     * 不是缺口，替代候选命中其一就够；只有整类完全不可读时，才报这一类的**主**权限。
     */
    internal fun missingFor(kind: MediaKind, granted: (String) -> Boolean, sdkInt: Int): List<String> {
        if (kindGranted(kind, granted, sdkInt)) return emptyList()
        return listOf(primaryFor(kind, sdkInt))
    }

    /**
     * 整条能力是否至少有一类可读。探测（能力清单与闸门）用它回答：
     * 一类可读就算系统侧可达，剩余类型缺什么由调用时按类型校验再说——
     * 探测口径若按权限并集回答，「缺音频权限」就会把图片/视频也判成不可用。
     */
    internal fun anyKindReadable(granted: (String) -> Boolean, sdkInt: Int): Boolean =
        MediaKind.entries.any { kindGranted(it, granted, sdkInt) }

    /**
     * 整条能力完全不可读时的缺口清单：**此刻读不到的**每一类各报它的主权限，
     * 已可读的类型不报——「缺音频」的缺口清单里不该出现已经授权的图片类。
     * 与 [missingFor] 同源：可读的类型本就返回空，这里按类型汇总再去重。
     */
    internal fun allMissing(granted: (String) -> Boolean, sdkInt: Int): List<String> =
        MediaKind.entries
            .flatMap { missingFor(it, granted, sdkInt) }
            .distinct()
}
