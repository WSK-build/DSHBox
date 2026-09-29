package com.dshbox.app.sandbox.online

import com.dshbox.app.common.AppResult
import com.dshbox.app.sandbox.BundleManager
import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream

/**
 * .deb 拆包器（在线 base 组装 S4 阶段）。
 *
 * `.deb = ar 归档`，成员依次为 debian-binary / control.tar.* / data.tar.*：
 *  - data.tar.*：解压流先落盘为裸 tar，同时收集条目名（供 info/<pkg>.list），随后交
 *    [BundleManager.extractTarGz] 做安全解包——魔数识别、防越界、防保护区、owner 权限位
 *    收敛等语义全部沿用既有实现，这里不复制安全逻辑；
 *  - control.tar.*：`control` 解析为包字段；其余成员（postinst/md5sums/conffiles/
 *    shlibs/triggers…）按 dpkg 约定写入 `<rootfs>/var/lib/dpkg/info/<pkg>.<member>`，
 *    maintainer 脚本的可执行位按 tar mode 的 owner 位恢复。
 *
 * 纯 JVM 实现（ar/tar/xz/zstd/gzip 全部来自既有依赖），可在 testDebugUnitTest 直测。
 */
class DebUnpacker(private val bundleManager: BundleManager) {

    data class Record(
        val packageName: String,
        val version: String,
        val architecture: String,
        val priority: String,
        val essential: Boolean,
        val depends: String?,
        val preDepends: String?,
        /** data.tar 条目（归一化为绝对路径），供 [DpkgDbWriter] 生成 info/<pkg>.list。 */
        val fileList: List<String>,
        /**
         * control 的**完整字段表**（保持原顺序），供 [DpkgDbWriter] 合成全保真 status：
         * 缺 `Provides`/`Multi-Arch` 会让 apt 解析不了虚拟包与 `:any` 依赖，
         * 缺 `Description`/`Maintainer`/`Section`/`Installed-Size` 会让 `dpkg --audit` 报错。
         */
        val fields: Map<String, String> = emptyMap(),
        /** control.tar 里的 `conffiles` 成员（每行一个配置文件路径），供 status 的 `Conffiles:` 段。 */
        val conffiles: List<String> = emptyList(),
        /**
         * dpkg 的 `info/<stem>.<member>` 命名规则：`Multi-Arch: same` 的包用 `<包>:<架构>`，
         * 其余用包名。与真 dpkg 一致，否则 `dpkg -L <包>:<架构>`、`dpkg --verify` 取不到元数据。
         */
        val infoStem: String = packageName,
    )

    private data class ControlData(
        val fields: Map<String, String>,
        /** (member 名, 内容, tar mode)；"control" 本身不入 members（只解析成 fields）。 */
        val members: List<Triple<String, ByteArray, Int>>,
    )


    fun unpack(deb: File, rootfs: File, scratch: File): Record {
        scratch.mkdirs()
        var controlTar: File? = null
        var dataTar: File? = null
        try {
            var debianBinarySeen = false
            ArArchiveInputStream(BufferedInputStream(FileInputStream(deb), BUFFER)).use { ar ->
                while (true) {
                    val entry = ar.nextEntry ?: break
                    when {
                        entry.name == "debian-binary" -> {
                            val marker = ar.readBytes().decodeToString().trim()
                            debianBinarySeen = true
                            if (marker != "2.0") throw IOException("unsupported .deb format marker: $marker")
                        }
                        entry.name.startsWith("control.tar") -> {
                            check(controlTar == null) { "duplicate control.tar in ${deb.name}" }
                            controlTar = drainToPlainTar(ar, File(scratch, "control.tar"))
                        }
                        entry.name.startsWith("data.tar") -> {
                            check(dataTar == null) { "duplicate data.tar in ${deb.name}" }
                            dataTar = drainToPlainTar(ar, File(scratch, "data.tar"))
                        }
                        else -> throw IOException("unexpected .deb member: ${entry.name}")
                    }
                }
            }
            val ctl = controlTar ?: throw IOException("${deb.name} has no control.tar (not a Debian package?)")
            val dat = dataTar ?: throw IOException("${deb.name} has no data.tar (not a Debian package?)")

            val control = parseControlTar(ctl)
            val pkg = control.fields["Package"]
                ?: throw IOException("control is missing Package (in ${deb.name})")
            val stem = infoStemOf(control.fields)
            val infoDir = File(rootfs, "var/lib/dpkg/info").apply { mkdirs() }
            for ((member, bytes, mode) in control.members) {
                val out = File(infoDir, "$stem.$member")
                out.writeBytes(bytes)
                applyOwnerMode(out, mode)
            }

            val fileList = collectDataTarNames(dat)
            when (val result = bundleManager.extractTarGz(dat, rootfs)) {
                is AppResult.Failure -> throw IOException("data.tar extraction rejected: ${result.error.message}")
                is AppResult.Success -> Unit
            }
            return Record(
                packageName = pkg,
                version = control.fields["Version"].orEmpty(),
                architecture = control.fields["Architecture"].orEmpty(),
                priority = control.fields["Priority"].orEmpty(),
                essential = control.fields["Essential"]?.equals("yes", ignoreCase = true) == true,
                depends = control.fields["Depends"],
                preDepends = control.fields["Pre-Depends"],
                fileList = fileList,
                fields = control.fields,
                conffiles = control.members
                    .firstOrNull { it.first == "conffiles" }
                    ?.second
                    ?.decodeToString()
                    ?.lineSequence()
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.toList()
                    .orEmpty(),
                infoStem = stem,
            )
        } finally {
            controlTar?.delete()
            dataTar?.delete()
        }
    }

    /** 解压流落盘为裸 tar（后续 extractTarGz 按流魔数直接识别 plain tar，不看扩展名）。 */
    private fun drainToPlainTar(ar: ArArchiveInputStream, out: File): File {
        FileOutputStream(out).use { fos -> autoDecompress(ar).copyTo(fos, BUFFER) }
        return out
    }

    private fun collectDataTarNames(dataTar: File): List<String> =
        TarArchiveInputStream(autoDecompress(BufferedInputStream(FileInputStream(dataTar), BUFFER))).use { tar ->
            buildList {
                while (true) {
                    val e = tar.nextTarEntry ?: break
                    val rel = e.name.trimStart('.', '/')
                    if (rel.isBlank()) continue
                    add("/" + rel.trimEnd('/'))
                }
            }
        }

    private fun parseControlTar(controlTar: File): ControlData {
        val fields = LinkedHashMap<String, String>()
        val members = mutableListOf<Triple<String, ByteArray, Int>>()
        TarArchiveInputStream(autoDecompress(BufferedInputStream(FileInputStream(controlTar), BUFFER))).use { tar ->
            var currentKey: String? = null
            val value = StringBuilder()
            fun commit() {
                val key = currentKey ?: return
                fields[key] = value.toString()
                currentKey = null
                value.setLength(0)
            }
            while (true) {
                val e = tar.nextTarEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.trimStart('.', '/').trimEnd('/')
                if (name == "control") {
                    for (line in tar.readBytes().decodeToString().lineSequence()) {
                        when {
                            line.isBlank() -> commit()
                            // 续行必须**原样保留行首空格**：dpkg 用「行首一个空格」表示字段续行，
                            // 去掉它再输出会被读成新字段名（dpkg 会报
                            // `field name 'This' must be followed by colon`，进而整个 status
                            // 解析失败、`dpkg -l` 全空）。
                            line[0] == ' ' -> if (currentKey != null) value.append('\n').append(line)
                            else -> {
                                val idx = line.indexOf(':')
                                if (idx > 0) {
                                    commit()
                                    currentKey = line.substring(0, idx).trim()
                                    value.append(line.substring(idx + 1).trim())
                                }
                            }
                        }
                    }
                    commit()
                } else {
                    members += Triple(name, tar.readBytes(), e.mode)
                }
            }
        }
        return ControlData(fields, members)
    }

    /**
     * 按流魔数自动识别压缩容器：zstd / gzip / xz / bzip2 / 裸流（与 BundleManager 同一套
     * 魔数口径；这里独立小实现，避免为此改动既有类）。trixie 的 data.tar 默认是 zstd。
     */
    internal fun autoDecompress(input: InputStream): InputStream {
        val pushback = PushbackInputStream(input, MAGIC_LEN)
        val head = ByteArray(MAGIC_LEN)
        var read = 0
        while (read < MAGIC_LEN) {
            val n = pushback.read(head, read, MAGIC_LEN - read)
            if (n < 0) break
            read += n
        }
        pushback.unread(head, 0, read)
        val b = head
        return when {
            read >= 4 && b[0] == 0x28.toByte() && b[1] == 0xB5.toByte() &&
                b[2] == 0x2F.toByte() && b[3] == 0xFD.toByte() -> ZstdInputStream(pushback)
            read >= 2 && b[0] == 0x1F.toByte() && b[1] == 0x8B.toByte() ->
                GzipCompressorInputStream(pushback)
            read >= 6 && b[0] == 0xFD.toByte() && b[1] == 0x37.toByte() && b[2] == 0x7A.toByte() &&
                b[3] == 0x58.toByte() && b[4] == 0x5A.toByte() && b[5] == 0x00.toByte() ->
                XZCompressorInputStream(pushback)
            read >= 3 && b[0] == 0x42.toByte() && b[1] == 0x5A.toByte() && b[2] == 0x68.toByte() ->
                BZip2CompressorInputStream(pushback)
            else -> pushback
        }
    }

    /** 与 BundleManager.applyEntryMode 同口径：只恢复 owner 位（Android 只暴露 owner 位）。 */
    private fun applyOwnerMode(target: File, mode: Int) {
        try {
            target.setReadable(mode and 0x100 != 0, true)
            target.setWritable(mode and 0x80 != 0, true)
            target.setExecutable(mode and 0x49 != 0, true)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val BUFFER = 1 shl 16
        private const val MAGIC_LEN = 6

        /** dpkg 的 info 目录命名规则：`Multi-Arch: same` → `<包>:<架构>`，其余用包名。 */
        internal fun infoStemOf(fields: Map<String, String>): String {
            val pkg = fields["Package"].orEmpty()
            val arch = fields["Architecture"].orEmpty()
            return if (fields["Multi-Arch"] == "same" && arch.isNotBlank()) "$pkg:$arch" else pkg
        }
    }
}
