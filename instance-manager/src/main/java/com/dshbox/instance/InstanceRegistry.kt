package com.dshbox.instance

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.DshInstance
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 主实例备份条目（`.dsh-backup-<时间戳毫秒>` 目录）。 */
data class BackupEntry(
    val name: String,
    val createdAtMillis: Long,
    val sizeBytes: Long,
)

/**
 * 实例注册表：活跃槽位的持有与持久化、应急 home 的创建/删除、主实例备份的
 * 列出/还原/删除。只做槽位与目录操作；DSH 进程停起与空间预检由 app 服务层负责。
 * mutating 操作以实例锁串行化；调用方无需额外同步。
 */
class InstanceRegistry(
    private val userDataDir: File,
    private val store: SlotStore,
) {
    private val _active = MutableStateFlow(store.read())
    val active: StateFlow<DshInstance> = _active.asStateFlow()

    /** 切换活跃槽位（仅翻转持久化状态，不停/起进程）。 */
    @Synchronized
    fun switchTo(instance: DshInstance) {
        if (_active.value == instance) return
        store.write(instance)
        _active.value = instance
    }

    fun emergencyExists(): Boolean = emergencyHome().isDirectory

    /** 创建应急 home（幂等）：仅建目录；资产暂存与首启装配由调用方编排。 */
    @Synchronized
    fun createEmergencyHome(): AppResult<Unit> {
        val home = when (val r = validatedEmergencyHome()) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return r
        }
        if (home.isDirectory) return AppResult.Success(Unit)
        if (home.exists()) {
            return AppResult.Failure(
                AppError("INSTANCE_HOME_NOT_DIR", "emergency home path is occupied by a file: ${home.name}"),
            )
        }
        return if (home.mkdirs()) {
            AppResult.Success(Unit)
        } else {
            AppResult.Failure(AppError("INSTANCE_HOME_CREATE_FAILED", "cannot create emergency home: ${home.name}"))
        }
    }

    /**
     * 删除应急 home（幂等）。红线防御：路径本身或解析结果越界（符号链接/
     * junction 指向他处）一律拒绝；删除不跟随任何符号链接，链接只删自身。
     * 活跃槽为应急时拒绝。
     */
    @Synchronized
    fun deleteEmergencyHome(): AppResult<Unit> {
        if (!InstancePolicy.canDeleteEmergency(_active.value)) {
            return AppResult.Failure(
                AppError("INSTANCE_EMERGENCY_ACTIVE", "cannot delete emergency home while it is active"),
            )
        }
        val home = emergencyHome()
        if (!home.exists() && !Files.isSymbolicLink(home.toPath())) return AppResult.Success(Unit)
        when (val r = validatedEmergencyHome()) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return r
        }
        return deleteTreeNoFollow(home, "INSTANCE_HOME_DELETE_FAILED")
    }

    /**
     * 重置主实例 home（`.dsh`）：[backup]=true 改名为 `.dsh-backup-<时间戳>` 保留，
     * false 直接删除。备份的还原/删除见 [restoreBackup]/[deleteBackup]。
     * 红线守卫：拒绝符号链接/越界；删除不跟随链接。进程停起与重新装配由调用方编排。
     */
    @Synchronized
    fun resetMainHome(backup: Boolean): AppResult<Unit> {
        val home = DshInstance.MAIN.hostHome(userDataDir)
        if (!home.exists() && !Files.isSymbolicLink(home.toPath())) return AppResult.Success(Unit)
        when (val r = validatedMainHome()) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return r
        }
        if (backup) {
            val target = File(userDataDir, newBackupName())
            return if (home.renameTo(target)) {
                AppResult.Success(Unit)
            } else {
                AppResult.Failure(AppError("INSTANCE_RESET_BACKUP_FAILED", "cannot back up main home"))
            }
        }
        return deleteTreeNoFollow(home, "INSTANCE_RESET_DELETE_FAILED")
    }

    /** 列出全部主实例备份（仅 `.dsh-backup-*` 真实目录），按时间倒序；不含应急 home。 */
    fun listBackups(): List<BackupEntry> {
        val base = runCatching { userDataDir.canonicalFile }.getOrNull() ?: return emptyList()
        val children = base.listFiles() ?: return emptyList()
        return children.mapNotNull { f ->
            if (!f.name.startsWith(BACKUP_PREFIX)) return@mapNotNull null
            val safe = runCatching {
                f.isDirectory && !Files.isSymbolicLink(f.toPath()) && f.canonicalFile == File(base, f.name)
            }.getOrDefault(false)
            if (!safe) return@mapNotNull null
            val suffix = f.name.removePrefix(BACKUP_PREFIX)
            // 兼容两种命名：旧版 epoch 毫秒（纯数字）与新版可读日期（yyyyMMdd-HHmmss-SSS）。
            val ts = suffix.toLongOrNull() ?: parseBackupTime(suffix) ?: return@mapNotNull null
            BackupEntry(f.name, ts, dirSize(f))
        }.sortedByDescending { it.createdAtMillis }
    }

    /**
     * 还原指定备份到主实例（复制模式，零删除）：先把当前 `.dsh` 自动备份为新
     * `.dsh-backup-<时间戳>`，再**复制**选中备份为 `.dsh`（原备份保留、权限保留）。
     * 红线守卫：备份名必须为 `.dsh-backup-*` 真实目录，拒绝符号链接/越界。
     * 空间预检与进程停起由调用方编排。
     */
    @Synchronized
    fun restoreBackup(name: String): AppResult<Unit> {
        val backup = when (val r = validatedBackup(name)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return r
        }
        val main = DshInstance.MAIN.hostHome(userDataDir)
        var autoBackup: File? = null
        if (main.exists() || Files.isSymbolicLink(main.toPath())) {
            when (val r = validatedMainHome()) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> return r
            }
            if (main.exists()) {
                val target = File(userDataDir, newBackupName())
                if (!main.renameTo(target)) {
                    return AppResult.Failure(
                        AppError("INSTANCE_RESTORE_BACKUP_FAILED", "cannot back up current main home before restore"),
                    )
                }
                autoBackup = target
            }
        }
        return try {
            copyTreePreservingAttrs(backup.toPath(), main.toPath())
            AppResult.Success(Unit)
        } catch (t: Exception) {
            // 复制失败回滚：清掉半截主 home；有自动备份则恢复为主 home（选中备份未动）。
            runCatching {
                if (main.exists()) deleteTreeNoFollow(main, "INSTANCE_RESTORE_ROLLBACK")
                if (autoBackup != null && !main.exists()) autoBackup.renameTo(main)
            }.onFailure { android.util.Log.w("InstanceRegistry", "restore rollback incomplete: ${it.message}") }
            AppResult.Failure(AppError("INSTANCE_RESTORE_COPY_FAILED", "cannot restore backup: ${t.message}"))
        }
    }

    /** 删除指定备份（逐项，幂等）。红线守卫：仅 `.dsh-backup-*` 真实目录，拒绝符号链接/越界。 */
    @Synchronized
    fun deleteBackup(name: String): AppResult<Unit> {
        val backup = when (val r = validatedBackup(name)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return r
        }
        return deleteTreeNoFollow(backup, "INSTANCE_BACKUP_DELETE_FAILED")
    }

    /** 校验应急 home 路径：拒绝符号链接，且 canonical 全路径必须恰为 user-data/.dsh-emergency。 */
    private fun validatedEmergencyHome(): AppResult<File> {
        val home = emergencyHome()
        val safe = runCatching {
            val expected = File(userDataDir.canonicalFile, DshInstance.EMERGENCY.homeLeaf)
            !Files.isSymbolicLink(home.toPath()) && home.canonicalFile == expected
        }.getOrDefault(false)
        return if (safe) {
            AppResult.Success(home)
        } else {
            AppResult.Failure(
                AppError("INSTANCE_HOME_UNSAFE", "emergency home resolves outside user-data; refusing to operate"),
            )
        }
    }

    /** 校验主 home：拒绝符号链接，canonical 全路径必须恰为 user-data/.dsh。 */
    private fun validatedMainHome(): AppResult<File> {
        val home = DshInstance.MAIN.hostHome(userDataDir)
        val safe = runCatching {
            val expected = File(userDataDir.canonicalFile, DshInstance.MAIN.homeLeaf)
            !Files.isSymbolicLink(home.toPath()) && home.canonicalFile == expected
        }.getOrDefault(false)
        return if (safe) {
            AppResult.Success(home)
        } else {
            AppResult.Failure(
                AppError("INSTANCE_HOME_UNSAFE", "main home resolves outside user-data; refusing to operate"),
            )
        }
    }

    /** 校验备份目录：名须 `.dsh-backup-*`、user-data 下真实目录、拒绝符号链接/越界。 */
    private fun validatedBackup(name: String): AppResult<File> {
        if (!name.startsWith(BACKUP_PREFIX)) {
            return AppResult.Failure(AppError("INSTANCE_BACKUP_INVALID", "not a backup name: $name"))
        }
        val backup = File(userDataDir, name)
        val safe = runCatching {
            val expected = File(userDataDir.canonicalFile, name)
            backup.isDirectory && !Files.isSymbolicLink(backup.toPath()) && backup.canonicalFile == expected
        }.getOrDefault(false)
        return if (safe) {
            AppResult.Success(backup)
        } else {
            AppResult.Failure(
                AppError("INSTANCE_BACKUP_UNSAFE", "backup resolves outside user-data or is not a directory; refusing"),
            )
        }
    }

    /**
     * 删除目录树，不跟随任何目录链接：符号链接靠 attrs 识别；Windows junction
     * 不被 NIO 标记为链接，用 toRealPath 比对兜底。链接只删自身，绝不 descend。
     */
    private fun deleteTreeNoFollow(home: File, errorCode: String): AppResult<Unit> = try {
        val root = home.toPath()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != root) {
                        val linked = attrs.isSymbolicLink || runCatching {
                            dir.toRealPath() != dir.parent.toRealPath().resolve(dir.fileName)
                        }.getOrDefault(true)
                        if (linked) {
                            Files.delete(dir)
                            return FileVisitResult.SKIP_SUBTREE
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        AppResult.Success(Unit)
    } catch (t: IOException) {
        AppResult.Failure(AppError(errorCode, "cannot delete tree: ${t.message}"))
    }

    /** 复制目录树并保留权限（credentials 0600 等）；符号链接重建链接自身，绝不跟随。 */
    private fun copyTreePreservingAttrs(source: Path, target: Path) {
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.createDirectories(target.resolve(source.relativize(dir)))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val dest = target.resolve(source.relativize(file))
                    if (attrs.isSymbolicLink) {
                        // pnpm node_modules 是符号链接农场：重建链接本身，不跟随目标。
                        val linkTarget = Files.readSymbolicLink(file)
                        if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) Files.delete(dest)
                        Files.createSymbolicLink(dest, linkTarget)
                    } else {
                        Files.copy(file, dest, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                    throw exc ?: IOException("copy visit failed: $file")
                }
            },
        )
    }

    /** 统计目录真实文件大小：不跟随符号链接（pnpm 链接农场可成环），只计普通文件。 */
    private fun dirSize(dir: File): Long {
        var sum = 0L
        runCatching {
            Files.walkFileTree(
                dir.toPath(),
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) sum += attrs.size()
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult =
                        FileVisitResult.CONTINUE
                },
            )
        }
        return sum
    }

    private fun emergencyHome(): File = DshInstance.EMERGENCY.hostHome(userDataDir)

    companion object {
        /** 主实例备份目录前缀（重置/还原自动备份均用此命名）。 */
        const val BACKUP_PREFIX = ".dsh-backup-"

        /** 备份名时间格式：可读日期 + 毫秒（防同秒碰撞）。 */
        private const val BACKUP_TIME_PATTERN = "yyyyMMdd-HHmmss-SSS"

        /** 生成可读备份目录名（`.dsh-backup-yyyyMMdd-HHmmss-SSS`）。 */
        fun newBackupName(now: Long = System.currentTimeMillis()): String =
            BACKUP_PREFIX + java.text.SimpleDateFormat(BACKUP_TIME_PATTERN, java.util.Locale.US)
                .format(java.util.Date(now))

        /** 解析可读备份名时间；非法返回 null。 */
        private fun parseBackupTime(suffix: String): Long? = runCatching {
            val fmt = java.text.SimpleDateFormat(BACKUP_TIME_PATTERN, java.util.Locale.US)
            fmt.isLenient = false
            fmt.parse(suffix)?.time
        }.getOrNull()
    }
}