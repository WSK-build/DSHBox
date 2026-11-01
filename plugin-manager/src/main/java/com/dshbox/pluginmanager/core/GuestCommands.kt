package com.dshbox.pluginmanager.core

import com.dshbox.app.common.Constants

/**
 * guest 侧命令的构造。
 *
 * **不能用裸 `dsh` 的原因**：一次性命令由 guest 内的
 * `/usr/bin/bash -c` 执行（非登录 shell，早期为宿主 `/system/bin/sh -c`——部分厂商
 * ROM 的内核对 proot 内执行宿主 `/system/bin/` 下的二进制会以 EPERM 拒绝，故改为
 * rootfs 内 bash），`/etc/profile.d` 不会加载，因此 PATH 里没有
 * `/root/projects/.dsh/dshbox/bin`，失败形态为
 * `timeout: failed to run command 'dsh': No such file or directory`（exit=127）。
 * 所以这里一律用**绝对路径**调用包装脚本，并把 `DSH_HOME` 显式传进去，
 * 不再依赖任何环境变量。
 */
object GuestCommands {

    /** dsh 包装脚本在 guest 内的绝对路径（由 app 在资产目录里装好）。 */
    private const val DSH_WRAPPER = Constants.DSHBOX_ASSETS_GUEST_DIR + "/bin/dsh"

    /**
     * 资产目录的 bin 必须**排在 PATH 最前**。
     *
     * 那里除了 `dsh` 还有 app 自己的 `pnpm` 包装脚本，它统一注入三件事：内置
     * `COREPACK_HOME`（离线可用并锁版本）、随 profile 自适应的 store 位置、
     * Android 必需的 copy 导入方式；**不注入 registry**，源由 DSH 面板与市场
     * 判定面各自决定。`dsh plugin` 内部会 spawn `pnpm`，若让默认 PATH 上另一个
     * pnpm 抢到（`/root/.local/share/pnpm`），pnpm 会因为 store 与 profile 里
     * 已有的链接不一致直接拒绝：`ERR_PNPM_UNEXPECTED_STORE`。
     */
    private const val BIN_PATH = Constants.DSHBOX_ASSETS_GUEST_DIR + "/bin"

    /** DSH 的 home（`user-data` 绑定在 `/root/projects`，`.dsh` 是它的 home 目录）。 */
    private const val DSH_HOME = "/root/projects/.dsh"

    /**
     * 统一的 guest 环境前缀：PATH 优先资产目录、显式 DSH_HOME、非交互 CI，以及 git 不交互。
     *
     * 一次性命令没有可交互终端；git 插件若触发凭据或主机密钥提示会一直等待到软超时，
     * 因此关闭终端提示并让 ssh 使用 BatchMode。
     */
    private fun envPrefix(): String =
        "env PATH=$BIN_PATH:\$PATH DSH_HOME=$DSH_HOME CI=true " +
            "GIT_TERMINAL_PROMPT=0 GIT_SSH_COMMAND='ssh -oBatchMode=yes'"

    /**
     * `dsh plugin --profile <profile> ...` 的完整命令。
     *
     * @param args 已拼好的参数（调用方负责引号与白名单校验）
     * @param timeoutSeconds 非空时套一层 `timeout`，避免命令卡住把界面拖死
     */
    fun dshPlugin(profile: String, args: String, timeoutSeconds: Int? = null): String =
        buildString {
            if (timeoutSeconds != null) append("timeout ").append(timeoutSeconds).append(' ')
            append(envPrefix()).append(' ')
            append(DSH_WRAPPER).append(" plugin --profile '").append(profile).append("' ").append(args)
        }

    /** `dsh --profile <profile> --dump-config` 的完整命令（静态合成，不启动服务）。 */
    fun dshDumpConfig(profile: String, timeoutSeconds: Int = 60): String =
        "timeout $timeoutSeconds ${envPrefix()} " +
            "$DSH_WRAPPER --profile '$profile' --dump-config"
}
