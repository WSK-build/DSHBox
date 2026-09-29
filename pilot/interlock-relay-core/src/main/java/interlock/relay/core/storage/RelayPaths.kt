package interlock.relay.core.storage

import android.system.Os
import android.system.OsConstants
import interlock.relay.core.spi.RelayPathPolicy
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * core 使用的全部目录。
 *
 * 放置纪律：凡需要不对沙盒暴露的内容（授权记录、配额账本），一律**不能放在被绑定的
 * 子树内部**。绑定以目录为粒度，父目录一旦映射，其下所有子目录在沙盒侧同样可读写，
 * 而沙盒与宿主同 uid，放在里面等于让被约束方改写约束依据。
 *
 * 沙盒侧常量与宿主目录名全部从 [RelayPathPolicy] 派生；子目录名（run/inbox/outbox/
 * control-inbox/control-outbox/out/artifacts/uploads）是固定词（不随策略变）。
 */
class RelayPaths(
    hostFilesDir: File,
    private val policy: RelayPathPolicy = RelayPathPolicy.BuiltIn,
) {

    /**
     * 整体映射到沙盒侧的那个目录。内部不得放任何私有内容，
     * 但信箱与产物可以：它们本就是双向的。
     *
     * 信箱落在本目录之下而不是另开一个绑定，是为了只用**一条** `--bind`：
     * 挂载点与其子树若分别绑定，PRoot 对嵌套绑定的解析次序没有稳定保证，
     * 父绑定盖住子绑定时通道会完全不通且没有任何报错。
     */
    val entryDir = File(hostFilesDir, policy.hostDirName)
    val binDir = File(entryDir, "bin")
    val capabilitiesFile = File(entryDir, "capabilities.json")
    val usageFile = File(entryDir, "USAGE.md")
    val clientScript = File(binDir, policy.cliName)

    /** 打包在 assets 里的入口脚本路径，随策略取。 */
    val assetClient: String = policy.assetClient

    /** 沙盒侧命令名：提交标记键与入口脚本同名同源。 */
    val cliName: String = policy.cliName

    /**
     * 提交标记：三份资产（入口脚本、清单、说明书）同批发布的依据。
     * 重铺成功路径的最后一步才写它，内含三份文件各自的 sha256；与那三份一样
     * 是绑定子树内的普通文件，沙盒侧可读它判断自己拿到的是不是同一批。
     */
    val versionMarkerFile = File(entryDir, "version.json")

    /** 可膨胀内容：信箱与产物。落在绑定子树内，随 `entryDir` 一起映射给沙盒。 */
    val runDir = File(entryDir, "run")
    val inboxDir = File(runDir, "inbox")
    val processingDir = File(runDir, "processing")
    val outboxDir = File(runDir, "outbox")
    val artifactsDir = File(runDir, "artifacts")

    /**
     * 控制通道收发目录：提交/查状态/取消/探活走这里，与能力请求分队列。
     * 仍在绑定子树内（沿用既有的一条 `--bind`），但独立目录让控制请求可以
     * 先于能力请求被服务，而不必挤同一条处理队列。
     */
    val controlInboxDir = File(runDir, "control-inbox")
    val controlOutboxDir = File(runDir, "control-outbox")

    /**
     * 入站文件中转区：沙盒侧把要交给宿主的字节放这里，宿主读完后删除。
     *
     * 信箱本身有 256 KiB 上限（信封要整体读进内存），文件不能走信封；
     * 单独一个目录是为了让"未消费的中转文件"有一处可被清扫按龄回收，
     * 而不是散落在绑定子树里等卸载。
     */
    val uploadsDir = File(runDir, "uploads")

    /**
     * 产物的**交付目录**：agent 与用户直接消费的东西落这里，按类型分子目录
     * （见 [ArtifactOut]）。它是共享树里的膨胀物，因此由 [StorageReaper] 按份数/龄期/
     * 总量三道闸统一回收——工作区（/root/projects）不是产物落点，那是用户的工作目录。
     */
    val outDir = File(entryDir, "out")
    val outShotDir = File(outDir, ArtifactOut.SHOT)
    val outVideoDir = File(outDir, ArtifactOut.VIDEO)
    val outAudioDir = File(outDir, ArtifactOut.AUDIO)
    val outFileDir = File(outDir, ArtifactOut.FILE)

    /** 沙盒侧路径常量，随 [RelayPathPolicy] 派生。 */
    val guestEntry: String = policy.guestEntry
    val guestRun: String = "$guestEntry/run"
    val guestInbox: String = "$guestRun/inbox"
    val guestOutbox: String = "$guestRun/outbox"
    val guestArtifacts: String = "$guestRun/artifacts"
    val guestUploads: String = "$guestRun/uploads"
    val guestControlInbox: String = "$guestRun/control-inbox"
    val guestControlOutbox: String = "$guestRun/control-outbox"
    val guestOut: String = "$guestEntry/out"
    val guestScript: String = "$guestEntry/bin/${policy.cliName}"
    val guestCapabilities: String = "$guestEntry/capabilities.json"

    /** 以下各项均不映射到沙盒侧：agent 可读写它们就等于能改写自己的权限与账本。 */
    val privateDir = File(hostFilesDir, policy.hostDirName + "-state")
    /**
     * 请求状态目录：每条已提交请求一份阶段与终态记录。不在绑定子树内——
     * 里面存着「同一件事」的摘要与终态回包，沙盒可写就能偷换重放判据。
     */
    val requestStateDir = File(privateDir, "requests")
    /** 产物写入前的落点。不绑定给沙盒，写完用 rename 送进 [runDir] 下的产物目录。 */
    val stagingDir = File(hostFilesDir, policy.hostDirName + "-stage")
    val quotaFile = File(privateDir, "quota.properties")
    val auditDir = File(hostFilesDir, policy.hostDirName + "-audit")

    /** 与宿主日志同目录，为的是共用那套按 `.log` 后缀整目录清理的白名单。 */
    val logsDir = File(hostFilesDir, LOGS_DIR_NAME)

    /** 目录创建失败必须让调用方知道：静默失败会让通道看起来就绪而实际完全不可用。 */
    fun ensureWritableDirs(): Boolean = listOf(
        inboxDir, processingDir, outboxDir, artifactsDir, uploadsDir,
        controlInboxDir, controlOutboxDir,
        outDir, outShotDir, outVideoDir, outAudioDir, outFileDir,
        stagingDir, privateDir, requestStateDir, auditDir, logsDir,
    ).all { it.isDirectory || it.mkdirs() }

    /**
     * 通道收发只依赖这六个目录：能力请求三个（收、处理、回）与控制请求两个，
     * 外加产物目录。控制目录算进「生死判定」——建不起来时控制通道同样不可用，
     * 不能让状态查询悄悄退化成永远无应答。日志目录与宿主共享、账本与暂存目录
     * 另有用途，它们建不起来应当降级各自的写入，而不该把整条通道判成不可用。
     */
    fun ensureMailboxDirs(): Boolean = listOf(
        inboxDir, processingDir, outboxDir, artifactsDir, controlInboxDir, controlOutboxDir,
    ).all {
        if (it.isDirectory) return@all true
        // 沙盒侧有权把其中任意一个目录换成一个普通文件（同 uid），那条路径上
        // `mkdirs()` 永远失败、通道永久停摆。这些是宿主自己命名的信箱目录，
        // 挡路的普通文件不可能是别处需要的资产，删掉重建是唯一的复位方式。
        if (it.isFile) runCatching { it.delete() }
        it.mkdirs()
    }

    /**
     * 沙盒侧递交的文件名 → 宿主中转区里的那个文件。
     *
     * 参数只接受**单个名字**：分隔符不在白名单里，`..`、绝对路径、`/` 一律整条拒绝，
     * 目录遍历因此无路。过完名字再过逐级链接检查 —— 中转区在绑定子树内，
     * guest 既可以把 `uploads` 本身换成指向别处的链接，也可以在里面放一个指向
     * 宿主私有文件的符号链接让宿主去读。
     *
     * 返回 null 表示"这个递交不合法或文件不存在"，调用方据此回错误而不是抛异常。
     */
    fun resolveUpload(name: String): File? {
        if (!UPLOAD_NAME.matches(name) || name.contains("..")) return null
        // 同 uid 的沙盒侧可以删掉整个中转目录，因此这里要能自己补回来：
        // 删除之后的下一次递交应当直接成功，不必等启动或重铺。
        if (!uploadsDir.isDirectory && !uploadsDir.mkdirs()) return null
        val file = File(uploadsDir, name)
        if (!symlinkFreeFrom(entryDir, file)) return null
        return file.takeIf { it.isFile }
    }

    /**
     * 打开并核验沙盒递交的入站文件，返回 null 表示"这个递交不合法，直接拒"。
     *
     * 为什么不能像出站那样"查一遍路径再去用"：同 uid 的对手随时能用 `rename`
     * 换掉某个名字（改名不跟随末级链接，同 uid 必成），所以路径检查只是快照。
     * 这里的次序是**核验 inode → 打开 → 用 fd 反查 inode**，最后一步比的是
     * 真正拿到的那个 inode，**检查之后**的任何换入（换成链接、换成别的普通文件）
     * 都会因 inode 对不上而在此暴露。
     *
     * 它挡不住**检查之前**就落位的文件：guest 把一份宿主私有文件 `cp`/`mv` 进中转区时，
     * 末级不是链接、`st_nlink` 为 1、类型与大小全合法、`fstat` 与快照恒等，五道判据
     * 一道都不会报警。这不算越权：同 UID 下那些字节 guest 本来就读得到，也可以自己
     * 重新生成一份再递交，`cp` 相对「自己写一遍」没有额外能力。这条通路的边界是
     * 「guest 能否让宿主把它拿不到的东西发出去」，答案是否。
     * 因此文件头魔数只是防误用判据（挡住 `report.txt + kind=image` 这类会在相册留下
     * 坏条目的递交），不是对抗蓄意对手的安全边界。
     *
     * 四道判据各有其故：
     * - `lstat` 与 `stat` 的 `st_ino` 不等 ⇒ 末级是符号链接（指向宿主私有文件时，
     *   读了就是把私有数据发布进公共媒体库）；
     * - `st_nlink > 1` ⇒ 硬链接。硬链接不是链接，`isSymbolicLink` 与
     *   `lstat/stat` 比对全都看不见它，只能靠链接计数：`/data` 上允许跨目录硬链接，
     *   guest 可以 `ln /proc/self/root/…/quota.properties uploads/x.png` 造出一个
     *   "看起来是普通文件"的私有数据别名；
     * - 必须为常规文件（`S_IFREG`）：目录、FIFO、设备文件在此一律拒，
     *   否则 `/dev/zero` 这类会让复制永远不结束；
     * - 字节数取 `fstat` 而不是先 `length()` 再复制：检查之后追加写仍然有效，
     *   复制上限必须以打开那一刻的实际大小为准。
     */
    fun openVettedUpload(name: String, maxBytes: Long): VettedUpload? {
        val file = resolveUpload(name) ?: return null
        val path = file.absolutePath
        val snapshot = runCatching { Os.lstat(path) }.getOrNull() ?: return null
        if (snapshot.st_ino != runCatching { Os.stat(path) }.getOrNull()?.st_ino) return null
        if (snapshot.st_nlink != 1L) return null
        if (snapshot.st_size <= 0L || snapshot.st_size > maxBytes) return null
        if ((snapshot.st_mode and OsConstants.S_IFMT) != OsConstants.S_IFREG) return null
        val handle = runCatching { RandomAccessFile(file, "r") }.getOrNull() ?: return null
        val onDisk = runCatching { Os.fstat(handle.fd) }.getOrNull()
        if (onDisk == null || onDisk.st_ino != snapshot.st_ino ||
            onDisk.st_size <= 0L || onDisk.st_size > maxBytes ||
            (onDisk.st_mode and OsConstants.S_IFMT) != OsConstants.S_IFREG
        ) {
            runCatching { handle.close() }
            return null
        }
        return VettedUpload(file, handle, onDisk.st_size)
    }

    /** 已核验的入站文件：句柄与核验时确认的字节数。用毕必须 [VettedUpload.close]。 */
    class VettedUpload(
        val file: File,
        private val handle: RandomAccessFile,
        val size: Long,
    ) {
        /** 读取通道：底层就是核验过的那个 fd，不再按路径二次解析。 */
        fun openRead(): FileChannel = handle.channel

        /**
         * 核验过那个 fd 的副本，交给 binder 传出去。
         *
         * 只给 fd、不给路径：同 uid 的沙盒侧能在「核验」与「再打开」之间改名换掉这个文件，
         * 按路径二次解析等于把核验结论作废。
         */
        fun openDescriptor(): android.os.ParcelFileDescriptor =
            android.os.ParcelFileDescriptor.dup(handle.fd)

        fun deleteSource() {
            runCatching { file.delete() }
        }

        fun close() {
            runCatching { handle.close() }
        }
    }

    companion object {
        private val UPLOAD_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

        /** 与宿主日志同目录的固定名字，借用系统按 `.log` 后缀整目录清理的白名单。 */
        const val LOGS_DIR_NAME = "logs"

        /**
         * [root] 到 [target] 之间的每一级（含 target 自身）都不得是符号链接。
         *
         * 为什么必须有这一条：绑定只是 PRoot 的路径翻译，guest 在挂载点子树下
         * 建一个内容为 `../../../shared_prefs` 的符号链接，落点是宿主自己的
         * filesDir 共享树；此后**由宿主**去解析这条路径时走的是内核解析，不受
         * 翻译约束，于是宿主的写入被引到绑定子树之外。比较 `canonicalFile` 挡不住它 ——
         * 两侧同源、同时被解析，恒等。只有逐级 `isSymbolicLink` 能分辨「中间这一级被换过」。
         *
         * 只查 root 以下的各级：`filesDir` 本身在部分设备上是 `/data/data/...` 的
         * 符号链接，那是系统的布局而不是入侵痕迹，必须放过。
         */
        fun symlinkFreeFrom(root: File, target: File): Boolean {
            // 前缀比较用统一的 '/' 分隔符：Windows 上 File#absolutePath 用 '\'，
            // 不归一的话这道检查在桌面 JVM（单测）里对任何路径都判假。
            // Android 上路径本就是 '/'，此归一不改变生产语义。
            val rootPath = root.absolutePath.replace('\\', '/').trimEnd('/')
            val targetPath = target.absolutePath.replace('\\', '/')
            if (targetPath != rootPath && !targetPath.startsWith("$rootPath/")) return false
            var cursor = root
            if (isLink(cursor)) return false
            for (segment in targetPath.removePrefix("$rootPath/").split('/').dropLast(1)) {
                cursor = File(cursor, segment)
                if (isLink(cursor)) return false
            }
            return !isLink(target)
        }

        private fun isLink(file: File): Boolean = java.nio.file.Files.isSymbolicLink(file.toPath())

        /**
         * 宿主产物路径 → 沙盒侧路径。要求分隔符边界，否则
         * `run/artifactsX/...` 会被 `run/artifacts` 前缀误命中。
         */
        fun toGuestPath(artifact: File, paths: RelayPaths): String? {
            val hostDir = paths.artifactsDir.canonicalPath
            val hostFile = runCatching { artifact.canonicalPath }.getOrNull() ?: return null
            if (hostFile != hostDir && !hostFile.startsWith("$hostDir/")) return null
            return paths.guestArtifacts + hostFile.removePrefix(hostDir)
        }
    }
}
