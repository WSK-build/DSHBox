package com.dshbox.pluginmanager.market.data

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.pluginmanager.core.GuestCommandRunner
import com.dshbox.pluginmanager.core.GuestCommands
import com.dshbox.pluginmanager.core.PluginPaths

/**
 * 一次失败命令的归类结果。
 *
 * @property kind 归出的失败类型，同时用作 [AppError.code]，便于调用方按类型分支。
 * @property message 给用户看的中英双语说明。
 * @property replaceOutput true 表示这条说明应当**取代**原始输出。只有在原始
 *   输出里没有一个用户能读的字节时才置位（例如命令行把错误写成了替换字符）。
 */
data class PluginFailureVerdict(
    val kind: Kind,
    val message: String,
    val replaceOutput: Boolean = false,
) {
    enum class Kind {
        /** 找不到 pnpm：包管理器还没准备好。 */
        PNPM_MISSING,

        /** 依赖的构建脚本被 pnpm 默认拦截。 */
        IGNORED_BUILDS,

        /** 我们写进层的停用条目打不中任何 loader 条目。 */
        PATCH_ENTRY_NOT_FOUND,

        /** 目标（或某个依赖）在 registry 上不存在。 */
        NOT_FOUND,

        /** 瞬时网络故障，值得直接重试。 */
        TRANSIENT,

        /** 下载超时。 */
        TIMEOUT,

        /** pnpm 报了一个我们没有专门归类的错误码。 */
        ERR_PNPM,
    }
}

/**
 * pnpm/dsh 失败输出的文案分类。
 *
 * 为什么需要它：dsh 的包装层只会在失败时留下一句"pnpm failed in profile
 * directory …"，不携带原因；真正的原因在 pnpm 的诊断文本里，而那段文本对
 * 用户来说不可读。分类器把已知的几种形态翻成"该怎么办"。
 *
 * 匹配顺序就是优先级：**越具体的形态越靠前**。特别是
 * `patch: entry X not found` 里也含 `not found`，若通用"找不到"先匹配，
 * 用户就会拿到一句与真实原因无关的提示。
 *
 * ## 文案语言
 *
 * 下面每条 message 都是"中文 / English"双语串，由 UI 侧的 `localizeBilingual`
 * 按界面语言择半。**同一时刻只支持 zh / en 两种**：ar / es / fr / ru 会回退到
 * 英文那一半——这不是"已完整本地化"，完整做法是把这些串搬进 `strings.xml`。
 */
object PnpmFailureText {

    private val PNPM_MISSING_RE = Regex("pnpm not found on PATH", RegexOption.IGNORE_CASE)

    /** pnpm 报告的"这些包的构建脚本被忽略了"，两种写法都要认。 */
    private val IGNORED_BUILDS_RE =
        Regex("""Ignored build scripts:?\s*([^\n]+)""", RegexOption.IGNORE_CASE)

    /** DSH 层的告警：我们写的 `- id: X` 打不中任何 loader 条目。 */
    private val PATCH_ENTRY_RE = Regex("""patch: entry (\S+) not found""")

    private val NOT_FOUND_RE =
        Regex("""ERR_PNPM_FETCH_404|E404|\b404\b|not found in the registry|could not be found""", RegexOption.IGNORE_CASE)

    private val TRANSIENT_RE = Regex(
        """ERR_PNPM_FETCH_5\d\d|ERR_PNPM_META_FETCH_FAIL|FetchError|ECONNRESET|ETIMEDOUT|EAI_AGAIN|ENETUNREACH|socket hang up|network timeout""",
        RegexOption.IGNORE_CASE,
    )

    private val TIMEOUT_RE = Regex(
        """ERR_PNPM_FETCH_TIMEOUT|request timed out|timeout of \d+|ESOCKETTIMEDOUT""",
        RegexOption.IGNORE_CASE,
    )

    private val ERR_PNPM_CODE_RE = Regex("""ERR_PNPM_[A-Z0-9_]+""")

    /**
     * 归类一次失败输出；无法归类时返回 null，调用方应原样展示输出。
     *
     * 返回 null 是**有意的**：我们只翻自己能负责的形态，剩下的一律把 pnpm 的
     * 说明交给用户与日志，而不是编一句可能误导的总结。
     */
    fun classify(output: String): PluginFailureVerdict? {
        if (PNPM_MISSING_RE.containsMatchIn(output)) {
            return PluginFailureVerdict(
                PluginFailureVerdict.Kind.PNPM_MISSING,
                "找不到 pnpm：沙箱里还没有可用的包管理器，装插件前需要先把它准备好 / pnpm is not on PATH — the sandbox needs a working package manager before plugins can be installed",
            )
        }
        if (output.contains("ERR_PNPM_IGNORED_BUILDS") || IGNORED_BUILDS_RE.containsMatchIn(output)) {
            return PluginFailureVerdict(
                PluginFailureVerdict.Kind.IGNORED_BUILDS,
                "有依赖需要执行构建脚本，被 pnpm 默认拦截了。放行这些构建脚本后重试即可 / a dependency needs to run build scripts, which pnpm blocks by default — approve those scripts and retry",
            )
        }
        PATCH_ENTRY_RE.find(output)?.let { match ->
            val id = match.groupValues[1]
            return PluginFailureVerdict(
                PluginFailureVerdict.Kind.PATCH_ENTRY_NOT_FOUND,
                "停用条目 `$id` 打不中任何 loader 条目。本版本用包名当作条目 id，若某个 bundle 的条目 id 与包名不同就会出现这条告警；它只影响这一个开关，不影响启动 / the disable row `$id` matches no loader entry. This version uses the package name as the entry id, so a bundle whose entry id differs produces this warning; it affects only that one toggle, not startup",
            )
        }
        if (NOT_FOUND_RE.containsMatchIn(output)) {
            return PluginFailureVerdict(
                PluginFailureVerdict.Kind.NOT_FOUND,
                "这个插件（或它的某个依赖）在 registry 上不存在：可能是名字写错了，或者已经被下架 / the plugin (or one of its dependencies) does not exist on the registry — the name may be wrong, or it has been unpublished",
            )
        }
        if (TRANSIENT_RE.containsMatchIn(output)) {
            return PluginFailureVerdict(
                PluginFailureVerdict.Kind.TRANSIENT,
                "网络中断导致这次操作失败，重试通常即可 / a transient network failure interrupted the operation; retrying usually works",
            )
        }
        if (TIMEOUT_RE.containsMatchIn(output)) {
            return PluginFailureVerdict(
                PluginFailureVerdict.Kind.TIMEOUT,
                "下载超时：这个插件的安装包较大或网络较慢，稍后再试 / the download timed out — this plugin ships a large package or the network is slow; try again later",
            )
        }
        ERR_PNPM_CODE_RE.find(output)?.let { match ->
            return PluginFailureVerdict(
                PluginFailureVerdict.Kind.ERR_PNPM,
                "pnpm 报告了 ${match.value}，本次改动没有完成。请导出日志以便定位 / pnpm reported ${match.value} and nothing was changed; export the log to investigate",
            )
        }
        return null
    }
}

/**
 * 经 guest 执行 `dsh plugin …` 的安装/卸载/更新。
 *
 * ## 为什么只调官方命令
 *
 * 安装的正确做法由 dsh 自己掌握：它会转发给 pnpm，并在成功后就地核对
 * `dsh.profile.bundles`。我们自己拼 tarball 地址或自己下载包，等于把这套
 * 对账逻辑复制一遍并让它有机会走偏——尤其是"只在 pnpm 退出码为 0 时才改
 * bundles"这条：中途失败的残行会让下次启动的 loader 拒绝加载整个 profile。
 * 所以这里只负责构造命令、回传输出、翻译失败。
 *
 * ## 为什么要显式带上 CI
 *
 * pnpm 在没有 TTY 的环境里遇到交互式提示会**永久挂住**（典型场景是对一个已
 * 固定的 git 目标重复安装）。CI 模式让它要么直接做完、要么明确失败，而不是
 * 等一个永远不会到来的按键。
 */
class GuestPluginOps(
    private val runner: GuestCommandRunner,
    private val profile: String,
) {

    companion object {
        /**
         * 允许放进命令行的安装目标形态。
         *
         * `^`、`~`、`=` 是刻意允许的（非首字符）：清单里的 spec（如 `^0.14.0`）
         * 会被拼成 `pkg@^0.14.0`，合法的版本区间不能当成注入。空白与 shell
         * 元字符一律拒绝——它们才是真正危险的部分。
         *
         * **首字符额外限制**：目标会被拼进命令行（`… add '<target>'`），以
         * `-` 或 `=` 开头的目标会被 CLI 当成选项而不是包名（`--foo`、`=`），
         * 于是命令要么被劫持、要么报一个与用户操作无关的错。显式拒绝。
         */
        private val TARGET_RE = Regex("""^[A-Za-z0-9@:./_#+~^=-]+$""")

        fun isValidTarget(target: String): Boolean =
            target.isNotEmpty() && target.first() != '-' && target.first() != '=' &&
                TARGET_RE.matches(target)
    }

    /**
     * 安装一个目录条目。`target` 用目录给的 `install` 字段原样传入。
     *
     * **返回成功只代表命令这一趟没报错**，不代表装出来的包可加载。调用方
     * （[InstallGuard]）必须接着做装后校验：目标包目录存在 + `package.json`
     * 可读 + 有 `dsh` 字段 + bundle/client 至少一个。把这里的 `ok` 直接当成
     * "装好了"是本模块最容易犯的错——用户会看到"安装成功"，然后重启后发现
     * 插件根本不存在。
     */
    suspend fun add(target: String, onLine: (String) -> Unit): AppResult<Unit> =
        run("add", target, onLine)

    /** 卸载一个已装包。 */
    suspend fun remove(name: String, onLine: (String) -> Unit): AppResult<Unit> =
        run("remove", name, onLine)

    /**
     * 更新到最新。
     *
     * 用 `add <name>@latest` 而不是别的动词：更新在 pnpm 侧就是"重新解析到
     * 最新版本再装一次"，dsh 也只对 add/remove 做 bundles 对账。
     *
     * [name] 必须是**真实 npm 包名**：清单里的键可能是 `npm:` 别名
     * （`npm:foo@1.0.0` 的键是别名），拿别名来 `add` 一定装不上。脱别名的
     * 责任在调用方（[npmNameOf] / `updateTargetFor`），这里只负责执行。
     */
    suspend fun update(name: String, onLine: (String) -> Unit): AppResult<Unit> =
        run("add", "$name@latest", onLine)

    private suspend fun run(
        verb: String,
        target: String,
        onLine: (String) -> Unit,
    ): AppResult<Unit> {
        if (!PluginPaths.isValidProfileName(profile)) {
            return AppResult.Failure(
                AppError(
                    code = "PROFILE_NAME_INVALID",
                    message = "profile 名含有不允许的字符，已拒绝执行：$profile / the profile name contains characters that are not allowed",
                ),
            )
        }
        if (!isValidTarget(target)) {
            return AppResult.Failure(
                AppError(
                    code = "TARGET_REJECTED",
                    message = "安装目标含有不允许的字符，已拒绝执行：$target / the install target contains characters that are not allowed",
                ),
            )
        }
        // 绝对路径调用包装脚本：一次性命令不是登录 shell，PATH 里没有 dsh。
        val command = GuestCommands.dshPlugin(profile, "$verb '$target'")
        val result = runner.run(command, onLine)
        if (result.ok) return AppResult.Success(Unit)
        val verdict = PnpmFailureText.classify(result.text)
        val message = verdict?.message
            ?: "插件命令没有成功结束，本次改动没有完成。以下是原始输出 / the plugin command did not finish successfully and nothing was changed"
        return AppResult.Failure(
            AppError(
                code = verdict?.kind?.name ?: "PLUGIN_COMMAND_FAILED",
                message = message,
            ),
        )
    }
}
