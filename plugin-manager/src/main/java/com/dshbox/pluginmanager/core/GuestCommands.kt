package com.dshbox.pluginmanager.core

import com.dshbox.app.common.Constants

/**
 * guest 侧命令的构造。
 *
 * **为什么不能直接用裸 `dsh`**：我们的一次性命令由宿主以 `/system/bin/sh -c` 执行，
 * 那不是登录 shell，`/etc/profile.d` 不会加载，因此 PATH 里没有
 * `/root/projects/.dsh/dshbox/bin`——真机上表现为
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
     * 那里除了 `dsh` 还有 app 自己的 `pnpm` 包装脚本（把 store 固定到
     * `/opt/dshbox-cache/pnpm-store`）。`dsh plugin` 内部会 spawn `pnpm`，
     * 若让默认 PATH 上另一个 pnpm 抢到（`/root/.local/share/pnpm`），
     * pnpm 会因为 store 与 profile 里已有的链接不一致直接拒绝：
     * `ERR_PNPM_UNEXPECTED_STORE`——真机上就是这么装不上的。
     */
    private const val BIN_PATH = Constants.DSHBOX_ASSETS_GUEST_DIR + "/bin"

    /** DSH 的 home（`user-data` 绑定在 `/root/projects`，`.dsh` 是它的 home 目录）。 */
    private const val DSH_HOME = "/root/projects/.dsh"

    /** 统一的 guest 环境前缀：PATH 优先资产目录 + 显式 DSH_HOME + 非交互 CI。 */
    private fun envPrefix(): String = "env PATH=$BIN_PATH:\$PATH DSH_HOME=$DSH_HOME CI=true"

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
