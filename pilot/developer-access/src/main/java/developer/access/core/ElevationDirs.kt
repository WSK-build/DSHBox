package developer.access.core

import java.io.File

/**
 * 宿主侧提权目录的生命周期管理。
 *
 * ## 开关语义
 *
 * ADB 通道的开关表现为**宿主目录内容的增删**：
 *
 * - 开：写入 wrapper 与真身，guest 侧的 `adb` 命令即刻可用；
 * - 关：删除二者，guest 侧的 `adb` 命令**彻底不可见**
 *   （`command -v adb` 为空），而非"存在但执行失败"。
 *
 * ## bind 恒定，内容可变
 *
 * 宿主目录**始终**被 bind 进沙箱，内容随开关变化。PRoot 的 bind 参数在进程启动那一刻
 * 定死，但 bind 的是**目录**——目录内的文件增删对 guest 立即可见。
 * 因此开关切换**不需要重启沙箱**。
 *
 * @param filesDir App 私有 files 目录。
 */
class ElevationDirs(private val filesDir: File) {

    /** 被 bind 进沙箱的目录。内容对 guest 可见。 */
    val elevationDir: File = File(filesDir, ElevationPaths.ELEVATION_DIR_NAME)

    /** 不 bind 进沙箱的目录。存放仅用户可配的内容。 */
    val privateDir: File = File(filesDir, ElevationPaths.PRIVATE_DIR_NAME)

    /** guest 可见的子目录。 */
    val guestBinDir: File = File(elevationDir, "bin")
    val guestRealDir: File = File(elevationDir, "real")
    val guestHomeDir: File = File(elevationDir, "home")
    val guestAndroidDir: File = File(guestHomeDir, ".android")
    val guestVendorKeysDir: File = File(guestAndroidDir, "keys")

    /** host 侧私有的子目录。 */
    val privateRootDir: File = privateDir

    /**
     * 建立 bind 目标与各级子目录。
     *
     * PRoot **对不存在的 bind 目标会报错**，故宿主目录必须在拉起沙箱前就位；
     * 这里统一建好并留空，使 bind 恒定、内容可变成立。
     */
    fun ensureLayout() {
        listOf(
            elevationDir, guestBinDir, guestRealDir, guestHomeDir, guestAndroidDir,
            guestVendorKeysDir, privateDir,
        ).forEach { it.mkdirs() }
    }

    /** wrapper 的宿主路径。 */
    fun adbWrapperFile(): File = File(elevationDir, ElevationPaths.FILE_ADB_WRAPPER)

    /** adb 真身的宿主路径。 */
    fun adbRealFile(): File = File(elevationDir, ElevationPaths.FILE_ADB_REAL)

    /** ADB 通道的 socket spec 配置文件。 */
    fun adbServerConfFile(): File = File(elevationDir, ElevationPaths.FILE_SERVER_CONF)

    /** Root 通道的 socket spec 配置文件（宿主私有，guest 不可见）。 */
    fun rootServerConfFile(): File = File(privateDir, ElevationPaths.FILE_ROOT_SERVER_CONF)

    /** Root 通道的 TCP 端口配置文件（宿主私有）。 */
    fun rootPortFile(): File = File(privateDir, ElevationPaths.FILE_ROOT_PORT)

    /** Root 通道的访问码文件（宿主私有）。 */
    fun rootSecretFile(): File = File(privateDir, ElevationPaths.FILE_ROOT_SECRET)

    /**
     * 写入 ADB 通道的 wrapper 脚本。
     *
     * 脚本只做三件事，且**必须用 `exec`**：
     *
     * 1. 固定 `HOME`——adb 只认 `HOME`（该构建不含 `ANDROID_SDK_HOME`/`ANDROID_USER_HOME`），
     *    密钥落点由它决定；
     * 2. 固定 `ADB_VENDOR_KEYS`——附加密钥的加载目录；
     * 3. 固定 `ADB_SERVER_SOCKET`——**三层优先级**：
     *    环境变量优先，其次配置文件，最后默认值。
     *    外部已设该变量时不覆盖，用户与 DSH 均可各自指定连接方式。
     */
    fun writeAdbWrapper() {
        ensureLayout()
        adbWrapperFile().writeText(developer.access.adb.AdbEnv.wrapperScript())
        adbWrapperFile().setExecutable(true, false)
    }

    /**
     * 移除 ADB 通道在 guest 侧的全部可执行入口。
     *
     * 真身与 wrapper 一并删除——只删真身会留下"命令在 PATH 上但执行失败"的中间态，
     * 对 guest 而言与不可见无差别，对用户却更难判断开关是否真的关了。
     */
    fun removeAdbEntryPoints() {
        adbWrapperFile().delete()
        adbRealFile().delete()
    }

    /** 真身是否就位。 */
    fun hasAdbReal(): Boolean = adbRealFile().isFile && adbRealFile().length() > 0

    /** ADB 通道的开关状态标记文件。 */
    fun stateFile(): File = File(elevationDir, ElevationPaths.FILE_STATE)

    /**
     * 写入 ADB 通道开关状态。
     *
     * 与 wrapper 存在性互为冗余：wrapper 是"命令可达"的事实来源，
     * 本文件是显式状态标记（`state`），便于直接查看通道开合。
     */
    fun writeState(enabled: Boolean) {
        ensureLayout()
        stateFile().writeText(if (enabled) "enabled" else "disabled")
    }

    /** Root 通道的端口文件。 */
    fun rootPortStorage(): File = File(privateDir, ElevationPaths.FILE_ROOT_PORT)
}
