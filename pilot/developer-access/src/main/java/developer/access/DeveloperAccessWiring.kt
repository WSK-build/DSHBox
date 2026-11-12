package developer.access

import developer.access.core.ElevationDirs
import developer.access.core.ElevationPaths
import java.io.File

/**
 * 宿主接线：把本模块对运行环境的两处最小介入（PRoot 绑定、guest PATH）收敛成两个取值口。
 *
 * ## 为什么集中在这里
 *
 * 宿主（app 模块）需要在**三处 PRoot 启动**（终端会话、网页端 DSH、guest 命令）与
 * **两处 PATH 构造**（终端环境、DSH 环境）注入同一组值。若让宿主各自拼装路径字面量，
 * 同一路径会在五处各写一遍，日后必然漂移。故此处是唯一出处。
 *
 * ## 目录就绪要求
 *
 * PRoot **对不存在的绑定目标会报错**，故 [guestBinds] 调用前必须确保宿主目录已建好——
 * 本类内部先建目录再返回绑定，调用方无需额外处理。
 */
object DeveloperAccessWiring {

    /**
     * 返回注入给 PRoot 的绑定参数。
     *
     * @param filesDir App 私有 files 目录。
     * @return 形如 `["--bind=<宿主目录>:/opt/elevation"]`；目录已确保存在。
     */
    fun guestBinds(
        filesDir: File,
        baseRootfs: File? = null,
        /** 活跃实例的随包 CLI 目录（登录 shell PATH 补回）；缺省主实例。 */
        workspaceCliDir: String = ElevationPaths.GUEST_WORKSPACE_CLI_DIR,
    ): List<String> {
        val dirs = ElevationDirs(filesDir)
        dirs.ensureLayout()
        val binds = mutableListOf(
            "--bind=${dirs.elevationDir.absolutePath}:${ElevationPaths.GUEST_ELEVATION_DIR}",
        )
        // 基础镜像的 /etc/profile 会 `PATH="…"; export PATH`，把调用方注入的 PATH
        // 整段覆盖；之后 profile.d 里只有基础镜像自带的那支脚本会补回 CLI shim 目录，
        // 不含 /opt/elevation/bin。DSH 的工具 shell 走登录 shell，因此 **看不到 adb**
        // （该行为已在设备上验证：登录 shell 里 `command -v adb` 无输出，`bash -c` 能命中）。
        //
        // 修法：在 elevation 目录里放一份同名的 profile.d 脚本，bind 覆盖基础镜像那一支。
        // 这样登录 shell 在 profile 之后又能拿到 elevation 目录，且 `command -v adb`
        // 仍命中 `/opt/elevation/bin/adb`。基础镜像不改。
        val override = File(dirs.elevationDir, PROFILE_OVERRIDE_NAME)
        runCatching {
            // 覆盖脚本必须**保留基础镜像那一支的语义**（它只把随包 CLI 目录补回 PATH），
            // 再在其前面加上能力目录——否则登录 shell 里 `dsh`/`pnpm` 会一起消失。
            override.writeText(
                "# DSHBox developer mode: restore guest PATH after /etc/profile reset.\n" +
                    "export PATH=\"${ElevationPaths.GUEST_ELEVATION_BIN_DIR}:" +
                    "$workspaceCliDir:\$PATH\"\n",
            )
        }
        val profileDir = baseRootfs?.let { File(it, "etc/profile.d/$BASE_PROFILE_SCRIPT") }
        if (profileDir != null && profileDir.isFile) {
            binds += "--bind=${override.absolutePath}:${ElevationPaths.GUEST_PROFILE_OVERRIDE}"
        }
        return binds
    }

    /**
     * 返回追加到 guest PATH 的目录。
     *
     * 恒定返回（目录内是否真有命令由开关决定）：目录为空时 PATH 里多一项无害，
     * 开关关闭时其中无 `adb`，`command -v adb` 自然为空。
     */
    fun guestPathEntries(): List<String> = listOf(ElevationPaths.GUEST_ELEVATION_BIN_DIR)

    /** elevation 目录内的覆盖脚本文件名。 */
    private const val PROFILE_OVERRIDE_NAME = "profile-path.sh"

    /** 基础镜像里的 profile.d 脚本名（存在才做覆盖，避免基础层变动时 bind 失败）。 */
    private const val BASE_PROFILE_SCRIPT = "zz-dshbox-path.sh"
}
