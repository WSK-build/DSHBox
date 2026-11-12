package com.dshbox.pluginmanager.core

import android.content.Context
import com.dshbox.app.common.Constants
import com.dshbox.app.common.DshInstance
import com.dshbox.pluginmanager.contract.HostContract
import java.io.File

/**
 * 插件管理模块用到的全部宿主侧路径，集中一处，避免各子模块各写一遍相对路径。
 *
 * 路径约定与 DSH 接入层保持一致：`filesDir/user-data` 就是 guest 内的
 * `/root/projects`，所以 profile 文件在宿主侧直接可读写，不需要进沙箱。
 *
 * @param profile DSH profile 名（默认 `web`），决定 profile 目录。
 */
class PluginPaths(
    context: Context,
    val profile: String = DEFAULT_PROFILE,
    /** 实例 home；插件面板/市场恒锚主实例，缺省即主 home。 */
    val instance: DshInstance = DshInstance.MAIN,
) {
    companion object {
        const val DEFAULT_PROFILE = "web"

        /** guest 侧同一份 overlay 的绝对路径（`--patch` 要传 guest 路径）。 */
        const val MARKET_OVERLAY_GUEST_PATH = Constants.DSH_PLUGIN_MARKET_OVERLAY_GUEST_PATH

        /** DSH 的 profile 名允许的字符集（对齐宿主 app-boot 的目录段校验）。 */
        private val PROFILE_NAME_RE = Regex("^[A-Za-z0-9_.-]{1,64}$")

        fun isValidProfileName(name: String): Boolean = PROFILE_NAME_RE.matches(name)
    }

    val filesDir: File = context.filesDir
    val userDataDir: File = File(filesDir, "user-data")

    /** 运行层根目录（base/node/dsh 各层都在它下面）。 */
    val runtimeCurrentDir: File = File(filesDir, HostContract.RUNTIME_CURRENT)

    /**
     * pnpm 镜像源覆盖文件（宿主侧；guest 内为 `/opt/dshbox-cache/pnpm-registry`）。
     *
     * 由 App 侧 bootstrap 探测后原子写入，一行裸 URL；执行面垫片不读它，唯一
     * 消费者是本模块的判定面（更新检测、预发布通道、浏览期兼容性）。
     */
    val pnpmRegistryFile: File =
        File(File(filesDir, Constants.GUEST_CACHE_DIR_LEAF), Constants.PNPM_REGISTRY_FILE_LEAF)

    /** DSH 的 home（按实例槽位解析）。 */
    val dshHomeDir: File = File(userDataDir, instance.homeLeaf)

    val profilesDir: File = File(dshHomeDir, "profiles")
    val profileDir: File = File(profilesDir, profile)

    /** profile 清单（依赖与 `dsh.profile.bundles`）。 */
    val profileManifest: File = File(profileDir, HostContract.PROFILE_MANIFEST)

    /** **用户的**补丁层：本模块只读，永不写入。 */
    val userPatchFile: File = File(profileDir, HostContract.USER_PATCH)

    /** 版本豁免文件；命中的插件不会被兼容门禁拒绝。 */
    val compatibilityFile: File = File(profileDir, HostContract.COMPATIBILITY)

    /** pnpm workspace 声明；存在与否决定安装命令要不要注入 `-w`。 */
    val pnpmWorkspaceFile: File = File(profileDir, HostContract.PNPM_WORKSPACE)

    val nodeModulesDir: File = File(profileDir, HostContract.NODE_MODULES)

    /** 市场状态（字段沿用上游格式，便于与桌面端互迁）。 */
    val marketStateFile: File = File(File(profileDir, ".dsh-market"), "state.json")

    /**
     * 当前数据源。**刻意不与 [marketStateFile] 合并**：那份文件的字段归上游／
     * 桌面端所有，我们只负责原样带回；手机端自己的设置另放一个文件。
     */
    val catalogSourceFile: File = File(File(profileDir, ".dsh-market"), "catalog-source")

    /**
     * 卸载中断的行日志（remove 之前记下将孤儿化的行 id，下次卸载入口结算）。
     *
     * 刻意**不与上游的 `pending-rows.json` 同名**：同路径下两侧 schema 互不兼容，
     * 整文件重写会静默毁掉对方的待结算记录。
     */
    val pendingRowsFile: File = File(File(profileDir, ".dsh-market"), "pending-rows.dshbox.json")

    /**
     * 市场自己拨过、但要等 DSH 下次启动才生效的包。
     *
     * 市场写的是启动参数里的 `--patch` 覆盖层，宿主只在组合时读它；安装与 DSH
     * 自身页面的开关走 profile/用户补丁层（HMR），是即时生效的。这张表只用来把
     * 前者与后者区分开。
     */
    val pendingTogglesFile: File = File(File(profileDir, ".dsh-market"), "pending-toggles.json")

    /** 我方随包资产目录（`.dsh/dshbox`）。 */
    val assetsDir: File = File(dshHomeDir, "dshbox")

    /** 安全模式的隔离清单与归因记录。 */
    val guardStateFile: File = File(assetsDir, "plugin-guard.json")

    /*
     * 封存：「上次启动」的那份快照文件（宿主侧）。
     *
     * 它由安全模式的生命周期观察者在启动前写入（该观察者随安全模式封存而不启动），面板的
     * 「上次启动」栏也已去掉 —— 保留策略改为按启动分段（见 BootSegmentedLog），与 DSH 日志同一份文件，
     * 不再需要这份副本。恢复步骤见 PluginSafetyMode.readBootLog 上方的注释。
     *
     * val previousBootLogFile: File = File(
     *     File(userDataDir, Constants.DSH_SAFE_MODE_ABSOLUTE_OVERLAY_RELATIVE_PATH).parentFile,
     *     "boot-log-previous.txt",
     * )
     */

    /**
     * **停用行**（宿主侧）；guest 侧见 [MARKET_OVERLAY_GUEST_PATH]。
     *
     * ⚠️ 由插件市场与安全模式**共写**：市场停用/启用、守卫的加载失败隔离与隔离清单恢复
     * 改的都是这一个集合。共用一份是刻意的（拆开会让两边互相看不见），勿拆。
     */
    val pluginMarketOverlayFile: File =
        File(userDataDir, Constants.dshPluginMarketOverlayRelativePath(instance))

    /**
     * 绝对安全模式独占的层（宿主侧）。
     *
     * 整层只归它所有：关闭该模式时直接删文件，因此永远不会覆盖守卫/市场写在
     * [pluginMarketOverlayFile] 里的停用行。
     */
    val absoluteSafeModeOverlayFile: File =
        File(userDataDir, Constants.dshSafeModeAbsoluteOverlayRelativePath(instance))

    val logsDir: File = File(filesDir, "logs")

    /** DSH 进程日志（`SandboxProcessRunner` 写入，按 2MB 滚动到 `.prev`）。 */
    val dshProcessLog: File = File(logsDir, "process-dsh.log")

    /** 沙箱 keepalive 进程日志。 */
    val sandboxProcessLog: File = File(logsDir, "process-sandbox.log")

    /** 访客命令日志（安装等一次性命令的输出）。 */
    val guestProcessLog: File = File(logsDir, "process-guest.log")

    /** 某个包在 profile 内的安装目录。 */
    fun installedPackageDir(name: String): File = File(nodeModulesDir, name)

    /** 某个包的清单；不存在返回 null。 */
    fun installedManifest(name: String): File? =
        File(installedPackageDir(name), HostContract.PROFILE_MANIFEST).takeIf { it.isFile }
}
