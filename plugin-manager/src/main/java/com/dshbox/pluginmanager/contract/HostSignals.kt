package com.dshbox.pluginmanager.contract

/**
 * 宿主输出文本的契约：依赖宿主日志与命令输出字面量的解析都集中在这里。
 *
 * 这些字面量是最脆的一层：上游改一个词，解析就静默失效（不报错，只是认不出来）。
 * 收敛到本对象后，上游一变只需改这里，[HostContractSentinelTest] 会把每个形态钉住。
 */
object HostSignals {

    // ───────────────── 装配失败（启动期，逐条目） ─────────────────

    /**
     * 装配失败归因串：`failed to <stage> loader entry <id> (<name>): <detail>`。
     *
     * 0.1.x 形态，0.2.x 不再产出；保留是因为 [com.dshbox.pluginmanager.safety.FailureAttribution]
     * 仍在引用，而该机制由 [com.dshbox.pluginmanager.core.PluginSafetySeal] 整体封存、可恢复。
     */
    val LOADER_ENTRY_FAILURE = Regex("""failed to \S+ loader entry (\S+) \((.*?)\): *(.*)$""")

    /** 链式失败的分段形状；0.1.x 形态，同上保留。 */
    val LOADER_ENTRY_CHAIN = Regex("""failed to \S+ loader entry""")

    /** 聚合行，仅作兜底起点，不算失败信号。 */
    const val LOADER_AGGREGATE = "loader entries failed to apply"

    /** `plugin(s) failed to load: a, b`；0.1.x 形态，同上保留。 */
    val PLUGIN_FAILED_TO_LOAD = Regex("""plugin\(s\) failed to load: *([^\n;]*)""")

    /** `N entries did not activate`：0.2.x 失败条目的标准记法。 */
    val ENTRIES_NOT_ACTIVATED = Regex("""(\d+) entries did not activate""")

    /** 顶格的条目点名行 `<entry>: <reason>`；缩进行是栈帧，不匹配。 */
    val NAMED_ENTRY = Regex("""^(\S.*?) *: +(.*)$""")

    // ───────────────── 装配拒绝 ─────────────────

    /**
     * 兼容性拒绝：`dsh: disabling profile plugin row "<id>": <reason>` 或
     * `dsh: disabling profile plugin <name>: <reason>`。
     *
     * id 允许含冒号，因此按引号成对提取，不能用字符类排除冒号。
     */
    val PLUGIN_DISABLED_ROW = Regex("""^\s*dsh: disabling profile plugin row "([^"]+)": (.+)$""")
    val PLUGIN_DISABLED_NAME = Regex("""^\s*dsh: disabling profile plugin ([^"\s].+?): (.+)$""")

    /** bundle 跳过，每次启动只报一次。 */
    val BUNDLE_SKIPPED = Regex("""^\s*dsh: skipping profile bundle "?([^":]+)"?: (.+)$""")

    // ───────────────── 补丁层告警 ─────────────────

    /** 补丁行打不中任何条目；不是失败，宿主照常加载其余条目。 */
    val PATCH_ENTRY_NOT_FOUND = Regex("""patch: entry (\S+) not found""")

    // ───────────────── 进程退出分段 ─────────────────

    /** node 退出时打印的版本行，用作"一次启动尝试"的分段标记。 */
    val NODE_TRAILER = Regex("""(^|\s)Node\.js v\d""")

    /** 启动分段标记，写入侧在每次启动前写。 */
    val BOOT_MARKER = Regex("""^===== DSH boot .+ =====$""")

    // ───────────────── 安装链（pnpm / 宿主）失败文本 ─────────────────

    /** pnpm 不在 PATH。 */
    val PNPM_MISSING = Regex("pnpm not found on PATH", RegexOption.IGNORE_CASE)

    /** 构建脚本被拦，两种写法都要认。 */
    val IGNORED_BUILDS = Regex("""Ignored build scripts:?\s*([^\n]+)""", RegexOption.IGNORE_CASE)

    /** pnpm 拦截构建脚本的错误码（与上面的文案二选一出现）。 */
    const val CODE_IGNORED_BUILDS = "ERR_PNPM_IGNORED_BUILDS"

    /** 宿主兼容门禁拒绝安装。 */
    val INCOMPATIBLE = Regex("""installation rejected|is incompatible with dsh""", RegexOption.IGNORE_CASE)

    /** 目标或依赖在 registry 上不存在。 */
    val NOT_FOUND = Regex(
        """ERR_PNPM_FETCH_404|E404|\b404\b|not found in the registry|could not be found""",
        RegexOption.IGNORE_CASE,
    )

    /** 瞬时网络故障，值得直接重试。 */
    val TRANSIENT = Regex(
        """ERR_PNPM_FETCH_5\d\d|ERR_PNPM_META_FETCH_FAIL|FetchError|ECONNRESET|ETIMEDOUT|EAI_AGAIN|ENETUNREACH|socket hang up|network timeout""",
        RegexOption.IGNORE_CASE,
    )

    /** 下载超时。 */
    val TIMEOUT = Regex(
        """ERR_PNPM_FETCH_TIMEOUT|request timed out|timeout of \d+|ESOCKETTIMEDOUT""",
        RegexOption.IGNORE_CASE,
    )

    /** 未被专门归类的 pnpm 错误码。 */
    val ERR_PNPM_CODE = Regex("""ERR_PNPM_[A-Z0-9_]+""")

    // ───────────────── pnpm 失败码 ─────────────────
    //
    // 由 PnpmFailureText 按固定次序判定：具体码在前、通用 ERR_PNPM 在后。
    // 次序不得调换，否则宽匹配会抢走具体码。
    // 标注"本平台不可达"的条目仍保留：它们是宿主契约的一部分，删掉会让覆盖范围难以核对。

    /** 宿主静默看门狗终止 pnpm。本平台不可达：CLI 路径不捕获输出，看门狗不 arm。 */
    val PNPM_STALLED = Regex("""printed nothing for (\d+)ms and was terminated""")

    /** profile 写锁超时。 */
    val PROFILE_WRITE_LOCK = Regex("""timed out waiting for the writer lock at "?([^"\n]+)"?""")

    /** node_modules 由另一个 pnpm 大版本创建，布局不兼容。 */
    val HOIST_PATTERN_DIFF = Regex(
        """ERR_PNPM_PUBLIC_HOIST_PATTERN_DIFF|ERR_PNPM_VIRTUAL_STORE_DIR_MAX_LENGTH_DIFF""",
    )

    /** node_modules 记录的 store 与当前解析的 store 不同。 */
    val UNEXPECTED_STORE = Regex("""ERR_PNPM_UNEXPECTED_STORE""")

    /** 锁文件里的 tarball 缺 integrity。 */
    val MISSING_TARBALL_INTEGRITY = Regex("""ERR_PNPM_MISSING_TARBALL_INTEGRITY""")

    /** 锁文件 tarball 地址与 registry 元数据不一致。 */
    val TARBALL_URL_MISMATCH = Regex(
        """ERR_PNPM_TARBALL_URL_MISMATCH|has a tarball URL""",
    )

    /** 补丁打不上。 */
    val PATCH_FAILED = Regex("""ERR_PNPM_PATCH_FAILED""")

    /** 补丁没被用上。 */
    val UNUSED_PATCH = Regex(
        """ERR_PNPM_UNUSED_PATCH|The following patches were not used:""",
    )

    /** workspace root 上没带 `-w`。本平台按需注入 `-w`，通常不可达。 */
    val ADDING_TO_ROOT = Regex("""ERR_PNPM_ADDING_TO_ROOT""")

    /** 非 workspace 目录上带了 `-w`。本平台按 `pnpm-workspace.yaml` 是否存在注入，通常不可达。 */
    val NOT_A_WORKSPACE = Regex("""--workspace-root may only be used inside a workspace""")

    /** 新发布被最小发布年龄策略暂缓。 */
    val RELEASE_AGE_VIOLATION = Regex(
        """ERR_PNPM_MINIMUM_RELEASE_AGE_VIOLATION|ERR_PNPM_NO_MATURE_MATCHING_VERSION|ERR_PNPM_RESOLUTION_POLICY_VIOLATIONS_UNHANDLED""",
    )

    /** pnpm 原生引擎分配失败。命令结果不携带退出码，只按文本判定。 */
    val NATIVE_OOM = Regex("""memory allocation of \d+ bytes failed""")

    /**
     * `allowBuilds` 里的 git 来源键被读成版本联合，会导致该 profile 的所有包操作失败。
     *
     * 本平台可达：pnpm 10.26+ 与 11.0–11.5 有此缺陷，而宿主自己会往
     * `pnpm-workspace.yaml` 写 `allowBuilds`。
     */
    val INVALID_VERSION_UNION = Regex("""ERR_PNPM_INVALID_VERSION_UNION""")

    /** git 走 SSH 且认证失败。 */
    val SSH_AUTH_FAILED = Regex("""Permission denied \(publickey\)|Could not read from remote repository""")

    /** git 插件的 prepare 被拦。 */
    val GIT_PREPARE_NOT_ALLOWED = Regex("""ERR_PNPM_GIT_DEP_PREPARE_NOT_ALLOWED""")

    /** git 插件的 prepare 执行失败。 */
    val GIT_PREPARE_FAILED = Regex("""Failed to prepare git-hosted package fetched from""")

    /** 版本区间在 registry 上无匹配。 */
    val NO_MATCHING_VERSION = Regex("""ERR_PNPM_NO_MATCHING_VERSION""")

    /** 锁文件与清单不一致。 */
    val LOCKFILE_OUTDATED = Regex("""ERR_PNPM_OUTDATED_LOCKFILE""")

    /** 锁文件损坏或缺条目。 */
    val LOCKFILE_BROKEN = Regex("""ERR_PNPM_LOCKFILE_MISSING_DEPENDENCY|Broken lockfile""")

    /** Windows 上 profile 文件被占用。本平台不可达。 */
    val PROFILE_FILE_LOCKED = Regex(
        """(?:ERR_PNPM_)?EPERM[^\n]*rename[^\n]*(?:[\\/](?:package\.json|pnpm-lock\.yaml|pnpm-workspace\.yaml)\.\d+|[\\/]\.pnpm[\\/]lock\.yaml\.\d+)""",
        RegexOption.IGNORE_CASE,
    )

    /** Windows 上包目录被占用。本平台不可达。 */
    val WINDOWS_FILE_LOCKED = Regex(
        """node_modules[\\/]+((?:@[a-z0-9][a-z0-9._-]*[\\/]+)?[a-z0-9][a-z0-9._-]*)_tmp_\d+""",
        RegexOption.IGNORE_CASE,
    )

    /** 直接依赖的本地路径不存在；需配合 `while installing a direct dependency` 一起判定。 */
    val MISSING_LOCAL_DEPENDENCY = Regex("""ENOENT: no such file or directory, open '[^']+'""")

    /**
     * pnpm 存在但启动不了（无执行权限 / noexec / 退出码 9009）。
     *
     * 命令结果不携带退出码，只按文本判定；要求输出里出现 pnpm，避免把其它文件的
     * EACCES 误判成包管理器损坏。
     */
    val PNPM_UNUSABLE = Regex(
        """pnpm[^\n]*(?:EACCES|permission denied|ENOENT)|(?:EACCES|permission denied|noexec)[^\n]*pnpm|exit(?:ed)? (?:code )?9009""",
        RegexOption.IGNORE_CASE,
    )

    /** 精确版本形态，用于区分 exact-version-missing 与 no-matching-version。 */
    val EXACT_VERSION = Regex("""^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$""")
}
