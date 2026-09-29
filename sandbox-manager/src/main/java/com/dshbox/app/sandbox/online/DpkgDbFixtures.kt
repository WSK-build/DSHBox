package com.dshbox.app.sandbox.online

import android.content.res.AssetManager
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * S5b：回放 **dpkg 自建元数据**（在线组装不会产出、离线随包层里有的那一部分）。
 *
 * `var/lib/dpkg/` 下除了 `status` 与 `info/` 里的成员文件（由 [DpkgDbWriter] 合成），还有一批
 * dpkg 自己维护的账本：`alternatives/` 下各记录（替代项当前选择）、`triggers/` 下账本、
 * `diversions`（转移表）、`available`、`arch-native`、`cmethopt`、`info/format`、
 * `lock`/`lock-frontend`、`parts/`、`updates/`。缺它们的直接后果：
 *  - `update-alternatives --list/--config` 报"无该替代项"；
 *  - `dpkg --audit` / 触发器处理读到不完整账本；
 *  - apt/dpkg 后续写库时缺少必要骨架文件。
 *
 * 资产由 `tools/gen_dpkg_fixtures.py` 从随包层抽取（可复现、勿手改）：
 *  - `dpkg.index`：`<八进制权限>\t<kind>\t<相对路径>\t<字节数>`，kind ∈ file/empty/tpl；
 *  - `dpkg/<相对路径>`：file 原样、tpl 内含 `@ARCH@`（deb 架构）/`@DEBTRIPLE@`（三元组）占位；
 *  - empty 项只按 mode 建空文件（不依赖打包器如何处理 0 字节资产）。
 *
 * `status-old` 不在此列：它必须与合成的 `status` 一致，故由本类从 status 复制生成。
 */
class DpkgDbFixtures(
    private val assets: AssetManager,
    private val assetDir: String = DEFAULT_ASSET_DIR,
) {

    /**
     * @param arch deb 架构名（`amd64` / `arm64`），用于替换 `@ARCH@`
     * @param debTriple 架构三元组（`x86_64-linux-gnu` / `aarch64-linux-gnu`），用于替换 `@DEBTRIPLE@`
     */
    fun apply(rootfs: File, arch: String, debTriple: String) {
        File(rootfs, "var/lib/dpkg/parts").mkdirs()
        File(rootfs, "var/lib/dpkg/updates").mkdirs()
        File(rootfs, "var/lib/dpkg/alternatives").mkdirs()
        File(rootfs, "var/lib/dpkg/triggers").mkdirs()

        val entries = readLines(INDEX)
        var files = 0
        for (raw in entries) {
            val parts = raw.split('\t')
            if (parts.size < 5) continue
            val mode = parts[0].trim().toIntOrNull(8) ?: DEFAULT_FILE_MODE
            val kind = parts[1].trim()
            val rel = parts[2].trim()
            // 资产名与落盘名分开：以 `.gz` 结尾的资产会被打包器自动解压（见生成脚本注释），
            // 故资产侧统一改名，落盘按 dpkg 的真实路径。
            val assetRel = parts[3].trim()
            if (rel.isEmpty()) continue
            val out = File(rootfs, rel)
            try {
                out.parentFile?.mkdirs()
                when (kind) {
                    "empty" -> out.createNewFile()
                    "tpl" -> {
                        val text = assets.open("$assetDir/$ASSET_SUBDIR/$assetRel.tpl")
                            .bufferedReader(Charsets.UTF_8).use { it.readText() }
                        out.writeText(
                            text.replace("@ARCH@", arch).replace("@DEBTRIPLE@", debTriple),
                            Charsets.UTF_8,
                        )
                    }
                    else -> assets.open("$assetDir/$ASSET_SUBDIR/$assetRel").use { input ->
                        FileOutputStream(out).use { output -> input.copyTo(output, COPY_BUFFER) }
                    }
                }
                runCatching { Os.chmod(out.absolutePath, mode) }
                    .onFailure { Log.w(TAG, "chmod ${out.absolutePath} failed: ${it.message}") }
                files++
            } catch (t: Throwable) {
                Log.w(TAG, "dpkg fixture failed: $rel (${t.message})")
            }
        }
        // status-old 必须与刚合成的 status 一致（dpkg 用它与当前库做差异）。
        val status = File(rootfs, "var/lib/dpkg/status")
        if (status.isFile) {
            runCatching { status.copyTo(File(rootfs, "var/lib/dpkg/status-old"), overwrite = true) }
                .onFailure { Log.w(TAG, "status-old copy failed: ${it.message}") }
        }
        Log.i(TAG, "dpkg fixtures applied: $files/${entries.size} (arch=$arch)")
    }

    private fun readLines(assetName: String): List<String> = try {
        assets.open("$assetDir/$assetName").bufferedReader(Charsets.UTF_8).use { it.readLines() }
    } catch (t: Throwable) {
        Log.w(TAG, "dpkg fixture index missing: $assetName (${t.message})")
        emptyList()
    }

    companion object {
        const val DEFAULT_ASSET_DIR = "runtime-online"

        /** 资产正文所在子目录（索引在 `runtime-online/dpkg.index`，正文在 `runtime-online/dpkg/`）。 */
        private const val ASSET_SUBDIR = "dpkg"
        private const val TAG = "DpkgDbFixtures"
        private const val INDEX = "dpkg.index"
        private const val COPY_BUFFER = 64 * 1024
        private const val DEFAULT_FILE_MODE = 0b110100100 // 0644
    }
}
