package com.dshbox.app.sandbox.online

import java.io.File
import java.io.IOException
import java.io.Writer
import java.security.MessageDigest

/**
 * 在线 base 组装 S5：从逐包 [DebUnpacker.Record] 合成 dpkg 库——
 * `<rootfs>/var/lib/dpkg/status` + `info/<stem>.<member>` + `updates/` 空目录。
 *
 * **字段必须全量保真**（只写部分字段会直接搞坏 guest 内的包管理）：
 *  - 缺 `Provides` → 虚拟包（`awk`、`base`…）解析不出来，`base-files: Pre-Depends: awk`
 *    被判 "not installable"；
 *  - 缺 `Multi-Arch` → `perl:any` / `python3:any` 这类限定依赖解析不出来；
 *  - 缺 `Description` / `Maintainer` / `Section` / `Installed-Size` → `dpkg --audit` 逐包报警；
 *  - 缺 `Conffiles` → dpkg 不认哪些是配置文件，后续 apt 升级会**静默覆盖用户改过的配置**；
 *  - 缺 `Breaks` / `Replaces` / `Conflicts` / `Recommends` → apt 解算缺约束。
 *
 * 因此本类**原样回放 control 的全部字段**（保持 control 中的顺序，多行续行原样搬运），
 * 另加 `Status`；包有 conffiles 时补 `Conffiles:` 段（路径 + 现算 md5，格式与 dpkg 一致）。
 *
 * 命名遵循 dpkg：`Multi-Arch: same` 的包为 `info/<包>:<架构>.<member>`
 * （由 [DebUnpacker.infoStemOf] 决定，与 deb 解包端同一处规则）。
 */
object DpkgDbWriter {

    fun write(rootfs: File, records: List<DebUnpacker.Record>) {
        val dpkgDir = File(rootfs, "var/lib/dpkg")
        File(dpkgDir, "info").mkdirs()
        File(dpkgDir, "updates").mkdirs()

        File(dpkgDir, "status").bufferedWriter().use { w ->
            records.forEachIndexed { idx, r ->
                if (idx > 0) w.write("\n")
                writeStanza(w, rootfs, r)
            }
        }

        val infoDir = File(dpkgDir, "info")
        for (r in records) {
            File(infoDir, "${r.infoStem}.list")
                .writeText(r.fileList.joinToString("") { "$it\n" })
        }
    }

    /** 单个软件包节：control 全部字段 + `Status` + `Conffiles`。 */
    private fun writeStanza(w: Writer, rootfs: File, r: DebUnpacker.Record) {
        val fields = r.fields
        if (fields.isEmpty()) {
            // 兜底：字段表缺失时按最小合法集输出（顺序与历史实现一致，兼容既有断言）。
            w.write("Package: ${r.packageName}\n")
            if (r.essential) w.write("Essential: yes\n")
            w.write("Status: install ok installed\n")
            if (r.priority.isNotBlank()) w.write("Priority: ${r.priority}\n")
            w.write("Version: ${r.version}\n")
            w.write("Architecture: ${r.architecture}\n")
            if (!r.preDepends.isNullOrBlank()) w.write("Pre-Depends: ${r.preDepends}\n")
            if (!r.depends.isNullOrBlank()) w.write("Depends: ${r.depends}\n")
            return
        }
        // dpkg 的字段顺序：`Package` → `Essential`（若有）→ `Status` → 其余。
        val insertAfter = if (fields.containsKey("Essential")) "Essential" else "Package"
        var statusWritten = false
        for ((key, value) in fields) {
            w.write("$key: $value\n")
            if (!statusWritten && key == insertAfter) {
                w.write("Status: install ok installed\n")
                statusWritten = true
            }
        }
        if (!statusWritten) w.write("Status: install ok installed\n")
        writeConffiles(w, rootfs, r)
    }

    private fun writeConffiles(w: Writer, rootfs: File, r: DebUnpacker.Record) {
        if (r.conffiles.isEmpty()) return
        val lines = r.conffiles.mapNotNull { path ->
            val file = File(rootfs, path.trim().trimStart('/'))
            if (!file.isFile) null else md5(file)?.let { " $path $it" }
        }
        if (lines.isEmpty()) return
        w.write("Conffiles:\n")
        for (line in lines) w.write("$line\n")
    }

    /** 取不到 md5 时返回 null（宁可整行不写，也不写一个空值骗 dpkg）。 */
    private fun md5(file: File): String? = try {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
