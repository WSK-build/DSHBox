package com.dshbox.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import com.dshbox.app.common.Constants
import java.io.File

/**
 * 共享区（Android 公共存储）宿主侧访问。
 *
 * 共享区是用户资产：目录位于公共存储 `/storage/emulated/0/DSHBox`，所有应用与电脑可见；
 * guest 侧由 PRoot 绑定为 `/root/share/DSHBox`（同一份数据）。目录已存在时一律沿用
 * （adopt），本类不清理、不重建任何既有内容。
 *
 * Android 11+ 读写公共存储需要「所有文件访问」（MANAGE_EXTERNAL_STORAGE，系统设置页授予）；
 * Android 10 走旧式存储（requestLegacyExternalStorage + WRITE_EXTERNAL_STORAGE）。
 */
object ShareArea {

    /** 共享区宿主根目录（不判断是否存在 / 可读写）。 */
    @Suppress("DEPRECATION")
    fun hostRoot(): File = File(
        // 个别 ROM 该 API 可能返回 null：退回主存储约定路径，避免组合期 NPE。
        Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0"),
        Constants.SHARE_AREA_DIR_NAME,
    )

    /** 是否已取得公共存储的完整读写能力。 */
    fun hasAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 共享区目录就绪：已存在即沿用；缺失时创建（含父目录）。
     * 返回目录当前是否可用。
     */
    fun ensureReady(): Boolean {
        val root = hostRoot()
        if (root.isDirectory) return true
        return root.mkdirs() || root.isDirectory
    }

    /** 系统外部存储 DocumentsProvider 的 authority：SAF 文档 id 的提供方。 */
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** 可写性探测用的临时文件名前缀（配合随机唯一名，探测后尽力删除）。 */
    private const val PROBE_PREFIX = ".dshbox-probe-"

    /** 主存储根路径；个别 ROM 的 API 返回 null 时退回约定路径。 */
    @Suppress("DEPRECATION")
    fun primaryRootPath(): String =
        Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"

    /**
     * 目录可写性探测：`canWrite()` 通过后仍需真正建出一个文件才算可写。
     * 「能挂载但读写全静默失败」的目录（授权缺失、只读卷、系统限制）在挂载前被拦下。
     *
     * 判据只看**创建是否成功**：探测文件用随机唯一名新建（[File.createTempFile] 仅在
     * 新建成功时返回句柄），只删除本次成功创建的那一个文件——目录内的用户同名文件绝不
     * 触碰。删除降级为尽力而为：FAT/exFAT、部分 ROM 限制或文件被占用都会让删除返回
     * false，但创建已经成功即已证明目录可写，不能因此把可写目录误判为不可写。
     * 删除失败的代价是留下一个随机名的小文件（[PROBE_PREFIX] 前缀），属可接受代价。
     */
    fun isWritableDir(dir: File): Boolean {
        if (!dir.isDirectory || !dir.canWrite()) return false
        val probe = runCatching { File.createTempFile(PROBE_PREFIX, null, dir) }.getOrNull()
            ?: return false
        // 删除是 best-effort：失败不影响可写判定，也不抛出。
        runCatching { probe.delete() }
        return true
    }

    /**
     * SAF 树文档 id（`primary:Download/foo` 或卷 ID 前缀）→ 宿主真实路径；
     * 非手机存储卷（第三方 DocumentsProvider）返回 null。
     *
     * 卷 ID → 根目录的对应关系由 [StorageManager] 提供（见 [storageVolumeRoots]）；
     * 部分 OEM 卷 ID 不带 `XXXX-XXXX` 形态，故未命中卷表时按通用卷 ID 规则回退。
     */
    fun hostPathOfDocumentId(context: Context, documentId: String): String? =
        hostPathOfDocumentId(documentId, primaryRootPath(), storageVolumeRoots(context))

    /**
     * API 30+ 的外部存储卷表（卷 ID → 卷根目录）；API 29 无对应 API，返回空表由回退路径兜底。
     * 卷 ID 为 null（主存储等无 UUID 的卷）的卷跳过。
     */
    private fun storageVolumeRoots(context: Context): Map<String, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyMap()
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return emptyMap()
        val volumes = runCatching { manager.storageVolumes }.getOrNull() ?: return emptyMap()
        val roots = LinkedHashMap<String, String>()
        for (volume in volumes) {
            val uuid = volume.uuid ?: continue
            val dir = runCatching { volume.directory }.getOrNull() ?: continue
            roots[uuid] = dir.absolutePath
        }
        return roots
    }

    /**
     * [hostPathOfDocumentId] 的纯判定（供 JVM 单测直调）：非手机存储卷返回 null。
     *
     * 卷 ID 解析顺序（[volumeRoots] 为已知卷表，可为空）：
     * 1. `primary`（忽略大小写）→ [primaryRoot];
     * 2. 卷表中按原名、再按忽略大小写命中 → 该卷目录；
     * 3. 未命中且形态为通用卷 ID → 回退 `/storage/<volume>`（保留旧行为，兼容多卷 OEM）。
     *
     * 文档 id 的路径部分逐段校验：`.`、`..` 与空段（`a//b`）一律整体拒绝，
     * 防止外部构造的文档 id 借上跳段逃出卷根。
     */
    fun hostPathOfDocumentId(
        documentId: String,
        primaryRoot: String,
        volumeRoots: Map<String, String> = emptyMap(),
    ): String? {
        val parts = documentId.split(':', limit = 2)
        if (parts.size != 2) return null
        val volume = parts[0].trim()
        if (volume.isEmpty()) return null
        val base = when {
            volume.equals("primary", ignoreCase = true) -> primaryRoot
            else -> volumeRoots[volume]
                ?: volumeRoots.entries.firstOrNull { it.key.equals(volume, ignoreCase = true) }?.value
                ?: if (GENERIC_VOLUME_ID.matches(volume)) "/storage/$volume" else return null
        }
        val trimmed = parts[1].trim('/')
        // 段级校验：拒绝上跳段、当前目录段与空段（trim('/') 后出现的空段即形如 a//b）。
        if (trimmed.isEmpty()) return base
        val segments = trimmed.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return "$base/${segments.joinToString("/")}"
    }

    /** 通用卷 ID 形态（放宽旧 UUID 限制，兼容 OEM 卷 ID）；第三方 DocumentsProvider 仍被拒绝。 */
    private val GENERIC_VOLUME_ID = Regex("^[A-Za-z0-9-]{1,32}$")
}
