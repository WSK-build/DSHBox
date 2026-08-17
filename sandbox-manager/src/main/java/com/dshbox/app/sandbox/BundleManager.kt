package com.dshbox.app.sandbox

import android.system.Os
import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Runtime/DSH/Plugin bundle install, verification and rollback.
 * Never overwrite the running Runtime directly; always use two slots.
 */
class BundleManager(
    private val config: SandboxConfig,
) {
    fun currentSlotDir(): File = File(config.runtimeDir, "runtime-current")
    fun newSlotDir(): File = File(config.runtimeDir, "runtime-new")
    fun backupSlotDir(): File = File(config.runtimeDir, "runtime-previous")
    fun failedSlotDir(): File = File(config.runtimeDir, "runtime-failed")

    fun sha256(file: File): String {
        require(file.isFile) { "not a file: ${file.absolutePath}" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifySha256(file: File, expectedSha256: String): Boolean {
        if (!file.isFile) return false
        if (expectedSha256.isBlank()) return false
        return try {
            sha256(file).equals(expectedSha256.trim(), ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Extracts a .tar.gz archive into [destDir]. The destination must already
     * be the target slot directory. The caller is responsible for clearing any
     * stale slot contents before calling this.
     */
    private fun applyEntryMode(target: File, mode: Int) {
        try {
            // Tar modes are Unix mode bits (octal): owner read = 0400, owner
            // write = 0200, owner execute = 0100. Preserve the OWNER bits only
            // (Android only exposes owner flags), so sensitive files such as
            // DSH's /root/.dsh/.credentials.yaml (mode 600, owner-only
            // enforced by the credentials module) stay owner-only after
            // extraction instead of being widened to 666.
            target.setReadable((mode and 0x100) != 0, true)
            target.setWritable((mode and 0x80) != 0, true)
            target.setExecutable((mode and 0x49) != 0, true)
        } catch (_: Exception) {
            // Mode preservation is best-effort; the caller may still read the file.
        }
    }

    fun extractTarGz(tarFile: File, destDir: File): AppResult<Unit> {
        if (!tarFile.isFile) {
            return AppResult.Failure(AppError("BUNDLE_NOT_FOUND", "bundle not found: ${tarFile.absolutePath}"))
        }
        destDir.mkdirs()
        val destRoot = destDir.canonicalFile
        return try {
            GzipCompressorInputStream(
                BufferedInputStream(FileInputStream(tarFile)),
            ).use { gzip ->
                TarArchiveInputStream(gzip).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val relative = entry.name.trimStart('/')
                        if (relative.isEmpty()) {
                            entry = tar.nextEntry
                            continue
                        }
                        val target = File(destRoot, relative).canonicalFile
                        if (!target.path.startsWith(destRoot.path + File.separator) && target != destRoot) {
                            return AppResult.Failure(AppError("BUNDLE_UNSAFE_PATH", "unsafe tar path: ${entry.name}"))
                        }
                        when {
                            entry.isDirectory -> {
                                target.mkdirs()
                                applyEntryMode(target, entry.mode)
                            }
                            entry.isSymbolicLink -> {
                                target.parentFile?.mkdirs()
                                // Resolve relative links against their own
                                // directory (../x in var/spool resolves inside
                                // the rootfs), absolute links against the root.
                                val linkTarget = if (entry.linkName.startsWith('/')) {
                                    File(destRoot, entry.linkName.trimStart('/')).canonicalFile
                                } else {
                                    File(target.parentFile, entry.linkName).canonicalFile
                                }
                                if (!isWithinRoot(linkTarget, destRoot)) {
                                    return AppResult.Failure(
                                        AppError("BUNDLE_UNSAFE_LINK", "unsafe symlink: ${entry.name} -> ${entry.linkName}"),
                                    )
                                }
                                try {
                                    Os.symlink(entry.linkName, target.path)
                                } catch (e: Exception) {
                                    return AppResult.Failure(
                                        AppError("BUNDLE_SYMLINK_FAILED", "failed to create symlink: ${entry.name}", e),
                                    )
                                }
                            }
                            entry.isLink -> {
                                target.parentFile?.mkdirs()
                                val linkTarget = if (entry.linkName.startsWith('/')) {
                                    File(destRoot, entry.linkName.trimStart('/')).canonicalFile
                                } else {
                                    File(target.parentFile, entry.linkName).canonicalFile
                                }
                                if (!isWithinRoot(linkTarget, destRoot)) {
                                    return AppResult.Failure(
                                        AppError("BUNDLE_UNSAFE_LINK", "unsafe hard link: ${entry.name} -> ${entry.linkName}"),
                                    )
                                }
                                if (linkTarget.isFile) {
                                    linkTarget.inputStream().use { input ->
                                        FileOutputStream(target).use { out -> input.copyTo(out) }
                                    }
                                    applyEntryMode(target, entry.mode)
                                }
                            }
                            entry.isFile -> {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { out ->
                                    tar.copyTo(out)
                                }
                                applyEntryMode(target, entry.mode)
                            }
                            else -> Unit // skip device nodes, sockets, fifos; PRoot binds /dev etc.
                        }
                        entry = tar.nextEntry
                    }
                }
            }
            AppResult.Success(Unit)
        } catch (e: IOException) {
            AppResult.Failure(AppError("BUNDLE_EXTRACT_FAILED", e.message ?: "extract failed", e))
        } catch (e: SecurityException) {
            AppResult.Failure(AppError("BUNDLE_EXTRACT_FAILED", e.message ?: "extract failed", e))
        }
    }

    fun clearSlot(slot: File) {
        if (slot.exists()) {
            slot.deleteRecursively()
        }
        slot.mkdirs()
    }

    /**
     * Installs a verified bundle into runtime-new. Does not switch current.
     */
    fun installToNewSlot(bundleFile: File, expectedSha256: String): AppResult<File> {
        if (!verifySha256(bundleFile, expectedSha256)) {
            return AppResult.Failure(AppError("BUNDLE_SHA256_MISMATCH", "bundle SHA-256 mismatch"))
        }
        clearSlot(newSlotDir())
        val debianDir = File(newSlotDir(), "debian")
        return when (val extracted = extractTarGz(bundleFile, debianDir)) {
            is AppResult.Success -> AppResult.Success(newSlotDir())
            is AppResult.Failure -> extracted
        }
    }

    /**
     * Promotes runtime-new to runtime-current, preserving the previous current
     * as runtime-previous for rollback. The caller must stop the sandbox first.
     */
    fun promoteNewSlotToCurrent(): AppResult<Unit> {
        if (!newSlotDir().exists() || newSlotDir().listFiles()?.isEmpty() == true) {
            return AppResult.Failure(AppError("BUNDLE_NEW_SLOT_EMPTY", "runtime-new is empty"))
        }
        val previous = backupSlotDir()
        val current = currentSlotDir()
        val failed = failedSlotDir()

        return try {
            // Drop failed slot; keep at most one rollback slot.
            failed.deleteRecursively()
            // Current -> previous.
            if (current.exists()) {
                previous.deleteRecursively()
                if (!current.renameTo(previous)) {
                    return AppResult.Failure(AppError("BUNDLE_SLOT_SWITCH_FAILED", "failed to move current to previous"))
                }
            }
            // New -> current.
            if (!newSlotDir().renameTo(current)) {
                // Restore previous as current so the system always has a usable slot.
                if (previous.exists()) {
                    previous.renameTo(current)
                }
                return AppResult.Failure(AppError("BUNDLE_SLOT_SWITCH_FAILED", "failed to move new to current"))
            }
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError("BUNDLE_SLOT_SWITCH_FAILED", e.message ?: "slot switch failed", e))
        }
    }

    fun rollback(): AppResult<Unit> {
        val previous = backupSlotDir()
        val current = currentSlotDir()
        val failed = failedSlotDir()
        if (!previous.exists() || previous.listFiles()?.isEmpty() == true) {
            return AppResult.Failure(AppError("BUNDLE_NO_ROLLBACK_SLOT", "no rollback slot available"))
        }
        return try {
            failed.deleteRecursively()
            if (current.exists() && !current.renameTo(failed)) {
                return AppResult.Failure(AppError("BUNDLE_ROLLBACK_FAILED", "failed to move current aside"))
            }
            if (!previous.renameTo(current)) {
                // Restore the current slot we moved aside so the system is not left without one.
                if (failed.exists()) {
                    failed.renameTo(current)
                }
                return AppResult.Failure(AppError("BUNDLE_ROLLBACK_FAILED", "failed to restore previous slot"))
            }
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError("BUNDLE_ROLLBACK_FAILED", e.message ?: "rollback failed", e))
        }
    }

    private fun isWithinRoot(path: File, root: File): Boolean {
        val rootPath = root.canonicalFile.absolutePath.trimEnd(File.separatorChar)
        val pathPath = path.canonicalFile.absolutePath
        return pathPath == rootPath || pathPath.startsWith("$rootPath${File.separator}")
    }
}
