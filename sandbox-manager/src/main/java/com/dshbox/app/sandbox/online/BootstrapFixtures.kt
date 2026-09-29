package com.dshbox.app.sandbox.online

import android.content.res.AssetManager
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/**
 * S6 补件：回放**安装期产物**。
 *
 * `.deb` 的 data.tar 只含打包时刻的文件；`/etc/profile`、`/etc/passwd`、
 * `/etc/alternatives/` 下全部条目这类是 debootstrap 第二阶段与各包 postinst 在安装时
 * 生成的，任何 `.deb` 里都不存在。随包层走过完整 debootstrap 所以齐全，在线组装必须补。
 *
 * 资产由 `tools/gen_bootstrap_fixtures.py` 从随包层抽取，可复现、勿手改：
 *  - `bootstrap.index`：`<八进制权限>\t<相对路径>`，正文在 `bootstrap/<相对路径>`；
 *  - `bootstrap.dirs`：待补建的空目录；
 *  - `links.map`：`<链接相对路径>\t<目标字面值>`。
 *
 * 不经资产的：`etc/hostname`（随包层存的是构建容器 ID，属构建环境元数据，改写中性值）、
 * `etc/ld.so.cache`（架构相关，guest `ldconfig` 现生成）、`etc/ssl/certs/`（guest
 * `update-ca-certificates` 现生成）、`etc/.pwd.lock`（点文件，aapt 不打包）。
 */
class BootstrapFixtures(
    private val assets: AssetManager,
    private val assetDir: String = DEFAULT_ASSET_DIR,
) {

    data class Stats(
        val files: Int,
        val dirs: Int,
        val links: Int,
        val skipped: Int,
    ) {
        val total: Int get() = files + dirs + links
    }

    /**
     * 把补件铺进 [rootfs]。永不抛：单条失败只计数并记日志，关键项由 S8 自检兜底。
     */
    fun apply(rootfs: File): Stats {
        var files = 0
        var dirs = 0
        var links = 0
        var skipped = 0

        for (raw in readLines(DIRS)) {
            val rel = raw.trim()
            if (rel.isEmpty()) continue
            val dir = File(rootfs, rel)
            if (dir.isDirectory) {
                skipped++
            } else if (dir.mkdirs() || dir.isDirectory) {
                dirs++
            } else {
                Log.w(TAG, "cannot create dir: $rel")
            }
        }

        for (raw in readLines(INDEX)) {
            val tab = raw.indexOf('\t')
            if (tab <= 0) continue
            val mode = raw.substring(0, tab).trim().toIntOrNull(8) ?: DEFAULT_FILE_MODE
            val rel = raw.substring(tab + 1).trim()
            if (rel.isEmpty()) continue
            val out = File(rootfs, rel)
            try {
                out.parentFile?.mkdirs()
                // 索引里是相对 rootfs 的路径，资产正文在 bootstrap/ 子目录下。
                assets.open("$assetDir/$BOOTSTRAP_DIR/$rel").use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output, COPY_BUFFER) }
                }
                runCatching { Os.chmod(out.absolutePath, mode) }
                    .onFailure { Log.w(TAG, "chmod ${out.absolutePath} failed: ${it.message}") }
                files++
            } catch (t: Throwable) {
                Log.w(TAG, "bootstrap file failed: $rel (${t.message})")
            }
        }

        // 点文件只能代码建：aapt 默认忽略 `.` 开头的资产（ignoreAssetsPattern 的 `.*`）。
        val lock = File(rootfs, "etc/.pwd.lock")
        if (!lock.exists()) {
            runCatching {
                lock.parentFile?.mkdirs()
                lock.createNewFile()
                Os.chmod(lock.absolutePath, 0b110000000) // 0600
                files++
            }.onFailure { Log.w(TAG, "create .pwd.lock failed: ${it.message}") }
        }

        for (raw in readLines(LINKS)) {
            val tab = raw.indexOf('\t')
            if (tab <= 0) continue
            val rel = raw.substring(0, tab).trim()
            val target = raw.substring(tab + 1).trim()
            if (rel.isEmpty() || target.isEmpty()) continue
            val link = File(rootfs, rel)
            // 不覆盖已产出的条目。exists() 对悬空软链返回 false，故须并问 isSymbolicLink。
            if (link.exists() || Files.isSymbolicLink(link.toPath())) {
                skipped++
                continue
            }
            try {
                link.parentFile?.mkdirs()
                Os.symlink(target, link.absolutePath)
                links++
            } catch (t: Throwable) {
                Log.w(TAG, "bootstrap link failed: $rel -> $target (${t.message})")
            }
        }

        return Stats(files = files, dirs = dirs, links = links, skipped = skipped)
    }

    /**
     * 中性 hostname：随包层存的是构建容器 ID，属构建环境元数据，不写进用户设备。
     */
    fun writeHostname(rootfs: File, name: String = DEFAULT_HOSTNAME) {
        runCatching {
            File(rootfs, "etc/hostname").writeText("$name\n")
            val hosts = File(rootfs, "etc/hosts")
            if (!hosts.isFile) {
                hosts.writeText(
                    "127.0.0.1\tlocalhost\n" +
                        "127.0.1.1\t$name\n\n" +
                        "::1\tlocalhost ip6-localhost ip6-loopback\n" +
                        "fe00::0\tip6-localnet\n" +
                        "ff00::0\tip6-mcastprefix\n" +
                        "ff02::1\tip6-allnodes\n" +
                        "ff02::2\tip6-allrouters\n",
                )
            }
        }.onFailure { Log.w(TAG, "write hostname failed: ${it.message}") }
    }

    private fun readLines(assetName: String): List<String> = try {
        assets.open("$assetDir/$assetName").bufferedReader(Charsets.UTF_8).use { it.readLines() }
    } catch (t: Throwable) {
        Log.w(TAG, "fixture index missing: $assetName (${t.message})")
        emptyList()
    }

    companion object {
        const val DEFAULT_ASSET_DIR = "runtime-online"
        const val DEFAULT_HOSTNAME = "dshbox"

        private const val TAG = "BootstrapFixtures"
        private const val INDEX = "bootstrap.index"
        private const val DIRS = "bootstrap.dirs"
        private const val LINKS = "links.map"
        private const val BOOTSTRAP_DIR = "bootstrap"
        private const val COPY_BUFFER = 64 * 1024
        private const val DEFAULT_FILE_MODE = 0b110100100 // 0644
    }
}
