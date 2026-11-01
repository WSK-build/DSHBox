package developer.access.core

/**
 * 开发者模式提权能力的宿主侧布局与 guest 侧路径。
 *
 * **一处定义、全局引用**：bind 参数、PATH 片段、资产落点、密钥目录与 socket 路径
 * 都从这里取值，避免同一路径在多处各写一遍后逐渐漂移。
 *
 * ## 目录划分
 *
 * 宿主侧有两个互不相通的目录：
 *
 * - **[elevationDir]**：bind 进沙箱的目录。沙箱与本 App 同 uid，故其内容对 guest 可见，
 *   DSH 可读写——这是刻意的（开发者模式要完整自主权）。
 * - **[privateDir]**：**不** bind 进沙箱的目录。存放只应由用户配置的内容
 *   （Root 通道的传输方式与访问码），guest 够不到，DSH 改不动。
 *
 * ## bind 源回避
 *
 * 运行环境已有的 bind 源目录（`files/` 下的 runtime 层、dshbox、pilot、browser-tools、
 * user-data 等）会被映射进 guest。**[privateDir] 必须避开全部既有 bind 源**，
 * 否则 guest 可经符号链接触达，Root 通道的「仅用户可配」就不成立。
 */
object ElevationPaths {

    /** guest 侧挂载点。与宿主侧的 bind 目标一一对应。 */
    const val GUEST_ELEVATION_DIR = "/opt/elevation"
    const val GUEST_ELEVATION_BIN_DIR = "$GUEST_ELEVATION_DIR/bin"
    const val GUEST_ELEVATION_REAL_DIR = "$GUEST_ELEVATION_DIR/real"
    const val GUEST_ELEVATION_HOME_DIR = "$GUEST_ELEVATION_DIR/home"
    const val GUEST_ANDROID_DIR = "$GUEST_ELEVATION_HOME_DIR/.android"
    const val GUEST_ADB_SOCKET = "$GUEST_ELEVATION_DIR/adb.sock"
    const val GUEST_ROOT_SOCKET = "$GUEST_ELEVATION_DIR/root.sock"

    /** guest 侧 ADB 客户端在 PATH 上的落点。 */
    const val GUEST_ADB_WRAPPER = "$GUEST_ELEVATION_BIN_DIR/adb"

    /** 登录 shell 的 profile.d 覆盖点：基础镜像会重写 PATH，需在其后补回。 */
    const val GUEST_PROFILE_OVERRIDE = "/etc/profile.d/zz-dshbox-path.sh"

    /**
     * 沙箱内 root CLI 的名称与落点。
     *
     * **落点是本能力自己的开关目录，不是工作区 `.dsh/dshbox/bin`**：后者是随包 CLI 与插件
     * 目录（`SandboxService.provisionDshCliShim()` 每次 bootstrap 整体覆写 `dsh`/`pnpm`/
     * `apt`/`dpkg`/`opencode`，插件目录也在同一棵树，`SandboxFiles.isAppManagedAsset()`
     * 把整棵树锁成 App 管理资产）。能力命令放进去会被覆写清掉，且把两种语义混成一棵树。
     * 放在 [GUEST_ELEVATION_BIN_DIR] 则与 adb wrapper 同目录，随开关增删，关闭即不可见。
     */
    const val GUEST_CLI_DIR = GUEST_ELEVATION_BIN_DIR
    const val ROOT_CLI_NAME = "android-root"

    /**
     * 随包 CLI 目录（工作区 `.dsh/dshbox/bin`）。
     *
     * **只读引用，禁止写入**：属主是 bootstrap 的随包资产装配，不是能力模块。
     * 登录 shell 的 PATH 仍需要它（`dsh`/`pnpm` 由此命中），故在 PATH 里保留。
     */
    const val GUEST_WORKSPACE_CLI_DIR = "/root/projects/.dsh/dshbox/bin"

    /** 宿主侧 bind 目录名（位于 App 私有 files 目录下）。 */
    const val ELEVATION_DIR_NAME = "elevation"

    /**
     * 宿主侧私有目录名（不 bind 进沙箱）。
     *
     * 名称刻意与 [ELEVATION_DIR_NAME] 无包含关系，且不落在既有 bind 源目录内
     * （`elevation` 与 `elevation-root-conf` 是同级兄弟）。
     */
    const val PRIVATE_DIR_NAME = "elevation-root-conf"

    /** 宿主侧文件名（位于 bind 目录内）。 */
    const val FILE_ADB_WRAPPER = "bin/adb"
    const val FILE_ADB_REAL = "real/adb"
    const val FILE_SERVER_CONF = "server.conf"
    const val FILE_STATE = "state"

    /** 宿主侧文件名（位于私有目录内）。 */
    const val FILE_ROOT_SERVER_CONF = "server.conf"
    const val FILE_ROOT_PORT = "port"
    const val FILE_ROOT_SECRET = "secret"

    /** guest 侧密钥目录（附加密钥的加载目标）。 */
    const val GUEST_VENDOR_KEYS_DIR = "$GUEST_ANDROID_DIR/keys"

    /** ADB server 的默认 socket spec（unix socket，落在 bind 目录内）。 */
    const val DEFAULT_ADB_SERVER_SPEC = "localfilesystem:$GUEST_ADB_SOCKET"

    /** ADB server 的默认 TCP 端口，取 adb 自身的惯例端口。 */
    const val DEFAULT_ADB_SERVER_PORT = 5037

    /** Root 执行器的默认 TCP 端口。与 ADB 的 5037 错开，两者可并存。 */
    const val DEFAULT_ROOT_SERVER_PORT = 5038
}
