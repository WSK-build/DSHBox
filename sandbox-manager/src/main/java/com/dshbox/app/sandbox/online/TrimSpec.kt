package com.dshbox.app.sandbox.online

import java.io.File

/**
 * 在线 base 组装 S7：与随包层同口径的裁剪，纯宿主侧文件操作：
 *  - `/usr/share/locale`：只留白名单目录，`locale.alias` 文件保留；
 *  - `/usr/share/doc`：只留 `copyright`（许可声明红线），其余**文件**删除、目录保留；
 *  - `/usr/share/man`：整体删除。
 *
 * doc 目录必须保留（哪怕被删空），悬空软链也保留：随包层里包声明的空目录与指向
 * 已删文件的软链都在，删掉只会让两条路径的文件树凭空多出差异。
 * （`isFile` 对悬空链返回 false，故软链天然留下。）
 */
class TrimSpec(private val localeWhitelist: Set<String>) {

    data class Stats(
        val localeDirsRemoved: Int,
        val docFilesRemoved: Int,
        val manRemoved: Boolean,
    )

    fun trim(rootfs: File): Stats {
        var localeRemoved = 0
        var docRemoved = 0

        val localeDir = File(rootfs, "usr/share/locale")
        if (localeDir.isDirectory) {
            for (child in localeDir.listFiles() ?: emptyArray()) {
                if (child.isDirectory && child.name !in localeWhitelist) {
                    child.deleteRecursively()
                    localeRemoved++
                }
            }
        }

        val docDir = File(rootfs, "usr/share/doc")
        if (docDir.isDirectory) {
            docDir.walkTopDown().filter { it.isFile }.forEach {
                if (it.name != "copyright") {
                    it.delete()
                    docRemoved++
                }
            }
        }

        val manDir = File(rootfs, "usr/share/man")
        var manRemoved = false
        if (manDir.exists()) {
            manDir.deleteRecursively()
            manRemoved = !manDir.exists()
        }
        return Stats(localeRemoved, docRemoved, manRemoved)
    }
}
