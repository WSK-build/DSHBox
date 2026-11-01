package com.dshbox.pluginmanager.market.data

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.pluginmanager.core.GuestCommandRunner
import com.dshbox.pluginmanager.core.GuestCommands
import com.dshbox.pluginmanager.contract.HostSignals
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
    /**
     * 失败类型：对齐上游 pnpm 分类码，另加两个本模块自有类型
     * （[PATCH_ENTRY_NOT_FOUND] 是宿主补丁层告警，[ERR_PNPM] 是兜底）。
     * 标注"本平台不可达"的条目仍保留判定，只是在本平台匹配不到。
     */
    enum class Kind {
        /** 找不到 pnpm：包管理器还没准备好。 */
        PNPM_MISSING,

        /** 依赖的构建脚本被 pnpm 默认拦截。 */
        IGNORED_BUILDS,

        /** 插件声明的 DSH 版本区间与运行时不满足，宿主兼容门禁拒绝了本次安装。 */
        INCOMPATIBLE,

        /** 我们写进层的停用条目打不中任何 loader 条目。**本模块自有类型，非 pnpm 码。** */
        PATCH_ENTRY_NOT_FOUND,

        /** 目标包在 registry 上不存在（`ERR_PNPM_FETCH_404` / E404）。 */
        NOT_FOUND,

        /** 版本区间在 registry 上无匹配（`ERR_PNPM_NO_MATCHING_VERSION`）。 */
        NO_MATCHING_VERSION,

        /** 精确版本在**当前下载线路**上暂时看不到（常见于镜像未同步刚发布的版本）。 */
        EXACT_VERSION_MISSING,

        /** 瞬时网络故障，值得直接重试。 */
        TRANSIENT,

        /** 下载超时。 */
        TIMEOUT,

        /**
         * 宿主的静默看门狗杀掉了 pnpm。
         * **本平台不可达**：CLI 路径不捕获输出，看门狗不 arm。
         */
        PNPM_STALLED,

        /** profile 的写锁等不到（另一个操作在跑，或上次被中断留下锁）。 */
        PROFILE_WRITE_LOCK,

        /** node_modules 由**另一个 pnpm 大版本**创建，布局不兼容，必须重建。 */
        HOIST_PATTERN_DIFF,

        /** node_modules 记录的 store 与 pnpm 现在解析的 store 不同。 */
        UNEXPECTED_STORE,

        /** 锁文件里的 tarball 缺 integrity，pnpm 拒绝该 profile 的所有操作。 */
        MISSING_TARBALL_INTEGRITY,

        /** 锁文件 tarball 地址与 registry 元数据不一致（镜像 + 非同源锁文件）。 */
        TARBALL_URL_MISMATCH,

        /** 补丁打不上（包升级后挪动了补丁指向的文件）。 */
        PATCH_FAILED,

        /** 补丁没被用上（`patchedDependencies` 的键已越过版本）。 */
        UNUSED_PATCH,

        /** workspace root 上没带 `-w`。**本平台不可达**：按需注入。 */
        ADDING_TO_ROOT,

        /** 非 workspace 目录上带了 `-w`。**本平台不可达**：按 `pnpm-workspace.yaml` 是否存在注入。 */
        NOT_A_WORKSPACE,

        /** 新发布被最小发布年龄策略暂缓（`minimumReleaseAge`）。 */
        RELEASE_AGE_VIOLATION,

        /** pnpm 原生引擎分配失败。**本平台不可达**：pnpm 12 专属缺陷。 */
        NATIVE_OOM,

        /** `allowBuilds` 里的 git 来源键被读成版本联合，整个 profile 的包操作全挂。 */
        UNPARSEABLE_BUILD_KEY,

        /** git 走 SSH 且认证失败。 */
        SSH_AUTH_FAILED,

        /** git 插件的 prepare 被 pnpm 拦截（需在 `allowBuilds` 放行）。 */
        GIT_PREPARE_NOT_ALLOWED,

        /** git 插件的 prepare 跑了但失败。 */
        GIT_PREPARE_FAILED,

        /** 锁文件与清单不一致（`ERR_PNPM_OUTDATED_LOCKFILE`）。 */
        LOCKFILE_OUTDATED,

        /** 锁文件损坏或缺条目。 */
        LOCKFILE_BROKEN,

        /** Windows 上 profile 文件被占用。**本平台不可达**。 */
        PROFILE_FILE_LOCKED,

        /** Windows 上包目录被占用。**本平台不可达**。 */
        WINDOWS_FILE_LOCKED,

        /** 直接依赖的本地路径不存在。 */
        MISSING_LOCAL_DEPENDENCY,

        /** pnpm 存在但启动不了（无执行权限 / noexec / 退出码 9009）。 */
        PNPM_UNUSABLE,

        /** pnpm 报了一个我们没有专门归类的错误码。**本模块兜底类型，非 pnpm 码。** */
        ERR_PNPM,
    }
}

/**
 * pnpm/dsh 失败输出的文案分类：把不可读的诊断文本翻成"该怎么办"。
 *
 * 匹配顺序即优先级，越具体的形态越靠前——`patch: entry X not found` 也含 `not found`，
 * 通用"找不到"先命中就会给出与真实原因无关的提示。无法归类时返回 null，调用方原样展示输出。
 *
 * 每条 message 都是"中文 / English"双语串，由 UI 侧 localizeBilingual 择半；
 * ar / es / fr / ru 回退英文那一半。
 */
object PnpmFailureText {

    // 全部形态取自契约层（[HostSignals]）：这些是 pnpm 与宿主输出的字面量，
    // 属最脆的契约，收敛到一处便于上游变更时集中核对。
    private val PNPM_MISSING_RE = HostSignals.PNPM_MISSING

    /** pnpm 报告的"这些包的构建脚本被忽略了"，两种写法都要认。 */
    private val IGNORED_BUILDS_RE = HostSignals.IGNORED_BUILDS

    /** DSH 层的告警：我们写的 `- id: X` 打不中任何 loader 条目。 */
    private val PATCH_ENTRY_RE = HostSignals.PATCH_ENTRY_NOT_FOUND

    private val NOT_FOUND_RE = HostSignals.NOT_FOUND

    /**
     * DSH 0.2.x 起的版本兼容门禁：装前预检拒绝（`installation rejected`，什么都不装）
     * 与启动期对不兼容行的处置（`disabling profile plugin`）。两者都是宿主官方输出，
     * 语义在 dsh-app-boot / dsh-plugin-manager 里。
     */
    private val INCOMPATIBLE_RE = HostSignals.INCOMPATIBLE

    private val TRANSIENT_RE = HostSignals.TRANSIENT

    private val TIMEOUT_RE = HostSignals.TIMEOUT

    private val ERR_PNPM_CODE_RE = HostSignals.ERR_PNPM_CODE

    // ── 以下为对齐 dsh-market 1.67 新增的 24 个分类（契约层持有形态） ──
    private val PNPM_STALLED_RE = HostSignals.PNPM_STALLED
    private val PROFILE_WRITE_LOCK_RE = HostSignals.PROFILE_WRITE_LOCK
    private val HOIST_PATTERN_DIFF_RE = HostSignals.HOIST_PATTERN_DIFF
    private val UNEXPECTED_STORE_RE = HostSignals.UNEXPECTED_STORE
    private val MISSING_TARBALL_INTEGRITY_RE = HostSignals.MISSING_TARBALL_INTEGRITY
    private val TARBALL_URL_MISMATCH_RE = HostSignals.TARBALL_URL_MISMATCH
    private val PATCH_FAILED_RE = HostSignals.PATCH_FAILED
    private val UNUSED_PATCH_RE = HostSignals.UNUSED_PATCH
    private val ADDING_TO_ROOT_RE = HostSignals.ADDING_TO_ROOT
    private val NOT_A_WORKSPACE_RE = HostSignals.NOT_A_WORKSPACE
    private val RELEASE_AGE_VIOLATION_RE = HostSignals.RELEASE_AGE_VIOLATION
    private val NATIVE_OOM_RE = HostSignals.NATIVE_OOM
    private val INVALID_VERSION_UNION_RE = HostSignals.INVALID_VERSION_UNION
    private val SSH_AUTH_FAILED_RE = HostSignals.SSH_AUTH_FAILED
    private val GIT_PREPARE_NOT_ALLOWED_RE = HostSignals.GIT_PREPARE_NOT_ALLOWED
    private val GIT_PREPARE_FAILED_RE = HostSignals.GIT_PREPARE_FAILED
    private val NO_MATCHING_VERSION_RE = HostSignals.NO_MATCHING_VERSION
    private val LOCKFILE_OUTDATED_RE = HostSignals.LOCKFILE_OUTDATED
    private val LOCKFILE_BROKEN_RE = HostSignals.LOCKFILE_BROKEN
    private val PROFILE_FILE_LOCKED_RE = HostSignals.PROFILE_FILE_LOCKED
    private val WINDOWS_FILE_LOCKED_RE = HostSignals.WINDOWS_FILE_LOCKED
    private val MISSING_LOCAL_DEPENDENCY_RE = HostSignals.MISSING_LOCAL_DEPENDENCY
    private val PNPM_UNUSABLE_RE = HostSignals.PNPM_UNUSABLE

    /** 从安装目标里取版本部分（`@scope/pkg@1.2.3` → `1.2.3`；`@scope/pkg` → `pkg`）。 */
    private fun versionPartOf(spec: String?): String? =
        spec?.substringAfterLast('@')?.takeIf { it.isNotBlank() && it != spec }

    private fun of(kind: PluginFailureVerdict.Kind, message: String) =
        PluginFailureVerdict(kind, message)

        /**
         * 归类一次失败输出；无法归类时返回 null，由调用方原样展示。
         *
         * @param requestedSpec 本次要装的目标。仅用于区分「精确版本暂时不可见」与
         *   「版本区间无匹配」——两者报同一个错误码，靠请求形态区分。
         */
    fun classify(output: String, requestedSpec: String? = null): PluginFailureVerdict? {
        if (PNPM_MISSING_RE.containsMatchIn(output)) {
            return PluginFailureVerdict(
                PluginFailureVerdict.Kind.PNPM_MISSING,
                "找不到 pnpm：沙箱里还没有可用的包管理器，装插件前需要先把它准备好 / pnpm is not on PATH — the sandbox needs a working package manager before plugins can be installed",
            )
        }
        if (output.contains(HostSignals.CODE_IGNORED_BUILDS) || IGNORED_BUILDS_RE.containsMatchIn(output)) {
            return PluginFailureVerdict(
                PluginFailureVerdict.Kind.IGNORED_BUILDS,
                "有依赖需要执行构建脚本，被 pnpm 默认拦截了。放行这些构建脚本后重试即可 / a dependency needs to run build scripts, which pnpm blocks by default — approve those scripts and retry",
            )
        }
        // ── 具体 pnpm 码：必须排在通用 `ERR_PNPM_*` 兜底之前 ──
        // 顺序照上游 classifyPnpmFailure（1.67）：越靠前越具体、越不该被后面抢走。
        if (PNPM_STALLED_RE.containsMatchIn(output)) {
            return of(
                PluginFailureVerdict.Kind.PNPM_STALLED,
                "pnpm 长时间没有任何输出，被终止了，什么都没装上。通常是某个依赖的安装脚本在下载文件而网络不通；换个网络或开启代理后重试 / pnpm printed nothing for a long time and was stopped, so nothing was installed — a dependency's install script is usually downloading something this network cannot reach; retry on another network or through a proxy",
            )
        }
        PROFILE_WRITE_LOCK_RE.find(output)?.let { m ->
            val path = m.groupValues[1].trim()
            return of(
                PluginFailureVerdict.Kind.PROFILE_WRITE_LOCK,
                "profile 的写锁一直没释放：`$path`。要么另一个插件操作还在跑（等它结束），要么上次被中断留下了锁。确认没有操作在进行后，删掉这个文件再重试 / the profile's writer lock was never released: `$path`. Either another plugin operation is still running, or an interrupted one left it behind. Once nothing is running, delete that file and retry",
            )
        }
        if (HOIST_PATTERN_DIFF_RE.containsMatchIn(output)) {
            return of(
                PluginFailureVerdict.Kind.HOIST_PATTERN_DIFF,
                "这个 profile 的 node_modules 是**另一个 pnpm 大版本**创建的，当前 pnpm 的布局规则不同，必须重建后才能继续。请导出日志确认后再重建（重建会重新下载依赖） / this profile's node_modules was created by a different pnpm major and must be rebuilt before changes can be applied (rebuilding re-downloads dependencies)",
            )
        }
        if (UNEXPECTED_STORE_RE.containsMatchIn(output)) {
            return of(
                PluginFailureVerdict.Kind.UNEXPECTED_STORE,
                "profile 里的 node_modules 链接到的 pnpm store 与当前解析出的不是同一个，pnpm 因此拒绝这个 profile 的所有安装与卸载。典型原因是 store 位置变了（例如缓存被清理或迁盘）。持久修法是把记录里的那个 store 写进 profile 的 pnpm-workspace.yaml（`storeDir:`），或清空重装 node_modules / the profile's node_modules links a different pnpm store than the one pnpm now resolves, so every install and uninstall is refused. Usually the store location changed (cache cleared, or the profile moved). Durable fix: write the recorded store into the profile's pnpm-workspace.yaml as `storeDir:`, or purge and reinstall node_modules",
            )
        }
        if (MISSING_TARBALL_INTEGRITY_RE.containsMatchIn(output)) {
            return of(
                PluginFailureVerdict.Kind.MISSING_TARBALL_INTEGRITY,
                "锁文件里有 tarball 依赖缺少 integrity 校验值，pnpm 因此拒绝这个 profile 的所有操作（连卸载它自己也不行）。这种条目通常是旧版市场写入的镜像前缀 tarball 地址造成的。请删掉锁文件里点名的那条依赖记录后重试——不要删整个 pnpm-lock.yaml / a tarball dependency in the lockfile has no integrity value, so pnpm refuses every operation in this profile. Such entries usually come from an older market version writing mirror-prefixed tarball URLs. Delete the named entry from pnpm-lock.yaml and retry — do not delete the whole lockfile",
            )
        }
        if (TARBALL_URL_MISMATCH_RE.containsMatchIn(output)) {
            return of(
                PluginFailureVerdict.Kind.TARBALL_URL_MISMATCH,
                "锁文件里有些条目的 tarball 地址与当前 registry 的元数据不一致，pnpm 的供应链检查因此拒绝该 profile 的所有操作。常见于镜像 registry 遇上用 npmjs 生成的锁文件。按 pnpm 提示清理锁文件后重装，或把 registry 切回生成该锁文件的源 / some lockfile entries carry tarball URLs that do not match the current registry's metadata, so pnpm's supply-chain check refuses every operation. Common when a mirror registry meets a lockfile generated against npmjs. Clean the lockfile as pnpm suggests, or switch the registry back to the source that generated it",
            )
        }
        if (PATCH_FAILED_RE.containsMatchIn(output)) {
            return of(
                PluginFailureVerdict.Kind.PATCH_FAILED,
                "profile 里的一个 pnpm 补丁打不上了。pnpm 会照常装上包，但装的是**没打补丁的原版**——通常下次启动才以「插件加载失败」暴露。多半是包升级后挪动了补丁指向的文件，请更新或删掉这条补丁 / a pnpm patch no longer applies. pnpm installs the package anyway, but unpatched — which usually surfaces at the next boot as a failed plugin. The release probably moved the file the patch targets; update or remove that patch",
            )
        }
        if (UNUSED_PATCH_RE.containsMatchIn(output)) {
            return of(
                PluginFailureVerdict.Kind.UNUSED_PATCH,
                "profile 里有一条 pnpm 补丁已经用不上了（`patchedDependencies` 按「包名@精确版本」钉，而版本已经越过它）。较新的 pnpm 会把整条命令判失败、什么都不写，所以看起来像「点了更新没反应」。请把补丁改到新版本，或删掉这条 / a pnpm patch in this profile is no longer used (keys are pinned to package@exactVersion and the version moved past it). Newer pnpm fails the whole command and writes nothing, which looks like 'the update did nothing'. Retarget the patch or remove that entry",
            )
        }
        if (ADDING_TO_ROOT_RE.containsMatchIn(output)) {
            return of(
                PluginFailureVerdict.Kind.ADDING_TO_ROOT,
                "在 workspace 根目录安装却没带 `-w`（pnpm 9 会拒绝）。本应用会按 profile 是否有 pnpm-workspace.yaml 自动注入该参数；出现这条说明 profile 结构异常，请导出日志 / installing at a workspace root without `-w` (pnpm 9 refuses). This app injects the flag based on whether the profile has pnpm-workspace.yaml, so seeing this means the profile structure is unexpected — export the log",
            )
        }
        if (NOT_A_WORKSPACE_RE.containsMatchIn(output)) {
            return of(
                PluginFailureVerdict.Kind.NOT_A_WORKSPACE,
                "在一个**不是** workspace 的目录上带了 `-w`（pnpm 会拒绝）。本应用只在 profile 有 pnpm-workspace.yaml 时才注入该参数；出现这条说明 profile 在命令执行期间被改动过，重试一次 / `-w` was used in a directory that is not a workspace. This app only injects it when the profile has pnpm-workspace.yaml, so the profile changed during the command — retry once",
            )
        }
        if (RELEASE_AGE_VIOLATION_RE.containsMatchIn(output)) {
            return of(
                PluginFailureVerdict.Kind.RELEASE_AGE_VIOLATION,
                "要装的版本太新，被 pnpm 的最小发布年龄策略暂缓了。等一段时间再试即可；这是保护措施，不是插件有问题 / the version is too new and was held back by pnpm's minimum release age policy. Wait a while and retry; this is a safety policy, not a problem with the plugin",
            )
        }
        if (NATIVE_OOM_RE.containsMatchIn(output)) {
            return of(
                PluginFailureVerdict.Kind.NATIVE_OOM,
                "pnpm 的原生引擎在处理这个 profile 时耗尽了内存并中止——和要装的插件无关。若当前 pnpm 是 12.x，请改用 11.x 后重试 / pnpm's native engine ran out of memory on this profile and aborted — the plugin is not the cause. If this is pnpm 12.x, switch to 11.x and retry",
            )
        }
        if (INVALID_VERSION_UNION_RE.containsMatchIn(output)) {
            return of(
                PluginFailureVerdict.Kind.UNPARSEABLE_BUILD_KEY,
                "这个版本的 pnpm 读不懂 `pnpm-workspace.yaml` 里 `allowBuilds` 的 git 来源键（pnpm 10.26+ 与 11.0–11.5 的已知缺陷），整个 profile 的所有包操作都会因此失败。请把该键改成 pnpm 能读的形式，或换用没有这个缺陷的 pnpm 版本 / this pnpm version cannot read a git-source key in `allowBuilds` (a known defect in pnpm 10.26+ and 11.0–11.5), which fails every package operation in the profile. Rewrite that key into a form pnpm accepts, or use a pnpm version without the defect",
            )
        }
        if (SSH_AUTH_FAILED_RE.containsMatchIn(output)) {
            return of(
                PluginFailureVerdict.Kind.SSH_AUTH_FAILED,
                "git 走 SSH 时认证失败（`Permission denied (publickey)`）。本应用已关闭交互式口令提示，所以它会直接失败而不是挂起。请确认该仓库对本机可访问，或改用 https 形式的地址 / SSH authentication to the git host failed (`Permission denied (publickey)`). Interactive prompts are disabled here, so it fails immediately instead of hanging. Make sure this machine can reach the repository, or use its https form",
            )
        }
        if (GIT_PREPARE_NOT_ALLOWED_RE.containsMatchIn(output)) {
            return of(
                PluginFailureVerdict.Kind.GIT_PREPARE_NOT_ALLOWED,
                "git 托管插件的构建脚本（prepare）被 pnpm 默认拦截了。按 pnpm 打印的那个确切键，在 profile 的 `pnpm-workspace.yaml` 的 `allowBuilds` 下放行后重试 / a git-hosted plugin's prepare script is blocked by pnpm's default. Allow the exact key pnpm printed under `allowBuilds` in the profile's `pnpm-workspace.yaml`, then retry",
            )
        }
        if (GIT_PREPARE_FAILED_RE.containsMatchIn(output)) {
            return of(
                PluginFailureVerdict.Kind.GIT_PREPARE_FAILED,
                "git 托管插件的构建脚本跑了但失败了，包没有装上。请展开日志看 prepare 阶段的报错（常是缺少构建工具或网络） / the git-hosted plugin's prepare script ran and failed, so nothing was installed. Expand the log for the prepare-stage error (usually a missing build tool or network)",
            )
        }
        if (INCOMPATIBLE_RE.containsMatchIn(output)) {
            return PluginFailureVerdict(
                PluginFailureVerdict.Kind.INCOMPATIBLE,
                "宿主拒绝了这次安装：插件声明的 DSH 版本区间与当前运行时不满足（0.2.x 起强制检查）。请优先更新该插件；确需强行使用时，可由用户明确承担风险后对确切版本授予豁免（宿主命令 `dsh plugin allow-version <包名>@<版本> --dsh-version <运行时版本> --accept-risk`） / the host rejected this install: the plugin's declared DSH version range does not satisfy the running runtime (enforced on the 0.2.x line). Update the plugin first; an exact-version exemption (`dsh plugin allow-version <pkg>@<ver> --dsh-version <runtime> --accept-risk`) exists but must be granted at the user's explicit risk",
            )
        }
        PATCH_ENTRY_RE.find(output)?.let { match ->
            val id = match.groupValues[1]
            return PluginFailureVerdict(
                PluginFailureVerdict.Kind.PATCH_ENTRY_NOT_FOUND,
                "停用条目 `$id` 打不中任何 loader 条目。本版本用包名当作条目 id，若某个 bundle 的条目 id 与包名不同就会出现这条告警；它只影响这一个开关，不影响启动 / the disable row `$id` matches no loader entry. This version uses the package name as the entry id, so a bundle whose entry id differs produces this warning; it affects only that one toggle, not startup",
            )
        }
        // 版本区间无匹配。pnpm 对「精确版本暂时不可见」和「区间无匹配」报同一个码，
        // 只有知道请求的形态才能给出准确提示（对应上游的 exact-version-missing）。
        if (NO_MATCHING_VERSION_RE.containsMatchIn(output)) {
            val requested = versionPartOf(requestedSpec)
            if (requested != null && HostSignals.EXACT_VERSION.matches(requested)) {
                return of(
                    PluginFailureVerdict.Kind.EXACT_VERSION_MISSING,
                    "当前下载线路暂时找不到精确版本 $requested（不等于这个版本不存在，常见于镜像还没同步刚发布的版本）。稍后再试，或在数据源里换一条线路 / this download route cannot see the exact version $requested yet (that does not mean it is missing — a mirror may not have synced a just-published release). Retry shortly, or switch the catalog source",
                )
            }
            return of(
                PluginFailureVerdict.Kind.NO_MATCHING_VERSION,
                "没有任何已发布版本满足插件声明的版本区间。多半是该区间里还没有发布版（或下载线路尚未同步）；若是宿主自带的包，属正常现象 / no published version satisfies the declared range. Usually nothing has been published in that range yet (or the mirror has not synced); normal for packages the host itself provides",
            )
        }
        if (NOT_FOUND_RE.containsMatchIn(output)) {
            return PluginFailureVerdict(
                PluginFailureVerdict.Kind.NOT_FOUND,
                "这个插件（或它的某个依赖）在 registry 上不存在：可能是名字写错了，或者已经被下架 / the plugin (or one of its dependencies) does not exist on the registry — the name may be wrong, or it has been unpublished",
            )
        }
        if (LOCKFILE_OUTDATED_RE.containsMatchIn(output)) {
            return of(
                PluginFailureVerdict.Kind.LOCKFILE_OUTDATED,
                "锁文件与 profile 的 package.json 不一致（有依赖改了但锁文件没跟上）。让 pnpm 重新生成锁文件后重试即可 / the lockfile is out of date with the profile's package.json. Regenerate it with pnpm and retry",
            )
        }
        if (LOCKFILE_BROKEN_RE.containsMatchIn(output)) {
            return of(
                PluginFailureVerdict.Kind.LOCKFILE_BROKEN,
                "锁文件损坏或缺少条目，pnpm 因此无法继续。删掉 `pnpm-lock.yaml` 让它重新生成即可（其余插件的版本会重新解析一次） / the lockfile is broken or missing entries. Delete `pnpm-lock.yaml` and let pnpm regenerate it (other plugins' versions get re-resolved once)",
            )
        }
        if (PROFILE_FILE_LOCKED_RE.containsMatchIn(output)) {
            return of(
                PluginFailureVerdict.Kind.PROFILE_FILE_LOCKED,
                "profile 的文件正被另一个进程占用，改不了名。请先退出 DSH 再重试 / a profile file is locked by another process and cannot be renamed. Stop DSH and retry",
            )
        }
        if (WINDOWS_FILE_LOCKED_RE.containsMatchIn(output)) {
            return of(
                PluginFailureVerdict.Kind.WINDOWS_FILE_LOCKED,
                "有包目录正被另一个进程占用。请先退出 DSH 再重试 / a package directory is locked by another process. Stop DSH and retry",
            )
        }
        // 超时优先于瞬时故障：`request timed out` 也含瞬时形态的措辞，
        // 排在后面会被归成"重试即可"，而用户需要知道的是"网络慢/包大"。
        if (TIMEOUT_RE.containsMatchIn(output)) {
            return PluginFailureVerdict(
                PluginFailureVerdict.Kind.TIMEOUT,
                "下载超时：这个插件的安装包较大或网络较慢，稍后再试 / the download timed out — this plugin ships a large package or the network is slow; try again later",
            )
        }
        if (TRANSIENT_RE.containsMatchIn(output)) {
            return PluginFailureVerdict(
                PluginFailureVerdict.Kind.TRANSIENT,
                "网络中断导致这次操作失败，重试通常即可 / a transient network failure interrupted the operation; retrying usually works",
            )
        }
        // 直接依赖的本地路径不存在：ENOENT 到处都是，必须同时出现 pnpm 的上下文句。
        if (MISSING_LOCAL_DEPENDENCY_RE.containsMatchIn(output) &&
            output.contains("while installing a direct dependency")
        ) {
            return of(
                PluginFailureVerdict.Kind.MISSING_LOCAL_DEPENDENCY,
                "某个直接依赖指向的本地路径不存在（`file:` / `link:` 目标被删了或挪走了）。请修正 profile 清单里的那条依赖 / a direct dependency points at a local path that no longer exists (a `file:` or `link:` target was deleted or moved). Fix that entry in the profile manifest",
            )
        }
        // pnpm 存在但启动不了：比通用错误码更具体，但必须排在兜底之前。
        if (PNPM_UNUSABLE_RE.containsMatchIn(output)) {
            return of(
                PluginFailureVerdict.Kind.PNPM_UNUSABLE,
                "系统里有一个 pnpm，但它启动不了（没有执行权限、所在分区 noexec，或包装脚本缺少环境变量）。在终端执行一次 `pnpm --version` 可以确认是哪一种 / a pnpm exists but cannot be started (no execute permission, a noexec partition, or a wrapper script missing environment variables). Run `pnpm --version` in a terminal to tell which",
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
 * 经 guest 执行 `dsh plugin …` 的安装、卸载与更新。
 *
 * 只调官方命令：安装的规则与 profile 对账都由 dsh 自己掌握，自己拼 tarball 或下载包
 * 等于复制这套逻辑并让它有机会走偏。本类只负责构造命令、回传输出、翻译失败。
 *
 * 命令显式带 CI：pnpm 在没有 TTY 的环境里遇到交互式提示会永久挂住。
 */
class GuestPluginOps(
    private val runner: GuestCommandRunner,
    private val profile: String,
        /**
         * profile 是否是 pnpm workspace root。
         *
         * workspace root 上 `add`/`remove` 不带 `-w` 会被拒绝，非 workspace 目录带上 `-w`
         * 也一定失败，因此必须按每次操作时的磁盘事实判定，不能在构造时定死。
         */
    private val isWorkspaceProfile: () -> Boolean = { false },
) {

    companion object {
        /** `-w` 只对这两个动词注入（install/why 等只读动词不适用）。 */
        private val WORKSPACE_VERBS = setOf("add", "remove")

        /**
         * 安装链的软超时（秒）。对齐上游的市场级默认预算：慢网络与 git 安装
         * 都要装得下，但要有一个明确的终点。guest 执行器另有更长的一次性命令
         * 硬上限在它自己那一层。
         */
        private const val TIMEOUT_SECONDS = 15 * 60

            /**
             * 允许放进命令行的安装目标形态。
             *
             * 目标会被拼进命令行并整体包在单引号里：`^`、`~`、`=` 放行（清单里的区间
             * spec 合法），空白、引号与 shell 元字符一律拒绝。以 `-` 开头的目标会被
             * CLI 当成选项，因此首字符额外限制；裸 `..` 段会穿越到 profile 上层，同样拒绝。
             */
        private val TARGET_RE = Regex("""^[A-Za-z0-9@:./_#+~^=-]+$""")

        /** `#path:` 子路径选择器的段校验：空段与 `..` 都不允许（路径穿越面）。 */
        private val SUBPATH_RE = Regex("""#path:(.*)$""")

        /**
         * 目标的 `#path:` 子路径是否合法。
         *
         * 仓库地址里的 `#path:/sub` 由宿主转发给 pnpm 做子目录安装；空段
         * （`a//b`、以 `/` 结尾）与 `..` 段会让安装落点逃出预期目录。
         */
        fun hasValidSubpath(target: String): Boolean {
            val match = SUBPATH_RE.find(target) ?: return true
            val path = match.groupValues[1]
            if (path.isEmpty() || path.endsWith("/")) return false
            // 前导 `/` 是惯例写法（`#path:/sub`），不属于空段；
            // 内部空段（`a//b`）、`.` 与 `..` 才是穿越面。
            val segments = if (path.startsWith("/")) path.substring(1) else path
            return segments.split('/').none { it.isEmpty() || it == "." || it == ".." }
        }

        fun isValidTarget(target: String): Boolean =
            target.isNotEmpty() && target.first() != '-' && target.first() != '=' &&
                TARGET_RE.matches(target) &&
                !target.split('/').any { it == ".." } &&
                hasValidSubpath(target)
    }

        /**
         * 安装一个目录条目；[target] 用目录给的 `install` 字段原样传入。
         *
         * 返回成功只代表命令这一趟没报错，不代表装出来的包可加载：调用方（[InstallGuard]）
         * 必须接着做装后校验，否则界面会显示"安装成功"而插件并不存在。
         */
    suspend fun add(target: String, onLine: (String) -> Unit): AppResult<Unit> =
        run("add", target, onLine)

    /** 卸载一个已装包。 */
    suspend fun remove(name: String, onLine: (String) -> Unit): AppResult<Unit> =
        run("remove", name, onLine)

        /**
         * 更新到最新：`add <name>@latest`。
         *
         * [name] 必须是真实 npm 包名；清单的键可能是 `npm:` 别名，脱别名的责任在调用方。
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
        // workspace root 上的 add/remove 必须 `-w`（上游 pluginArgsFor 的语义）：
        // 标志紧跟动词，作用于 pnpm 侧而不是 dsh 侧。
        val workspaceFlag = if (verb in WORKSPACE_VERBS && isWorkspaceProfile()) "-w " else ""
        // 绝对路径调用包装脚本：一次性命令不是登录 shell，PATH 里没有 dsh。
        // timeout 兜底一次 guest 硬上限之外的软预算：pnpm 静默挂住时命令以
        // 明确的失败收场，而不是占满执行器等待更长超时。
        val command = GuestCommands.dshPlugin(profile, "$verb $workspaceFlag'$target'", timeoutSeconds = TIMEOUT_SECONDS)
        val result = runner.run(command, onLine)
        if (result.ok) return AppResult.Success(Unit)
        // 把本次的目标也交给分类器：pnpm 对「精确版本暂时不可见」与「区间无匹配」
        // 报同一个错误码，只有知道请求形态才能给出准确提示。
        val verdict = PnpmFailureText.classify(result.text, target)
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
