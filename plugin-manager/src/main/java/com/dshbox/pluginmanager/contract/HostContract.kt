package com.dshbox.pluginmanager.contract

/**
 * 宿主契约：命名空间、包标识、profile 结构与字段名、运行层布局。
 *
 * 宿主事实只在此处定义一份，业务代码一律引用这里的名字，不得各自硬编码；
 * 变更时改这一处即可，[HostContractSentinelTest] 钉住每条事实。
 *
 * 只放事实与纯判定，不放行为：文件读写、命令执行、语义判定留在各自模块。
 */
object HostContract {

    // ───────────────────────── 包命名空间 ─────────────────────────

    /** 官方包命名空间。 */
    const val OFFICIAL_NAMESPACE = "@deepseek-ai/"

    /** loader 合成条目的命名空间（include / loader / group / base）。停用它们会破坏加载链。 */
    const val LOADER_NAMESPACE = "cordis:"

    /** 我方随包资产的命名空间前缀；不属于第三方，且受保护不被自动隔离。 */
    const val LOCAL_NAMESPACE = "@local/"

    /** 宿主主包名。 */
    const val HOST_PACKAGE = "@deepseek-ai/dsh"

    /** 同一条发布线下的宿主 peer 声明（`@deepseek-ai/dsh` 与 `@deepseek-ai/dsh-*`）。 */
    val HOST_PEER_RE = Regex("""^@deepseek-ai/dsh(?:-|$)""")

    // ───────────────────────── profile 结构 ─────────────────────────

    /** profile 清单文件名。 */
    const val PROFILE_MANIFEST = "package.json"

    /** 用户的补丁层文件名；本模块只读，永不写入。 */
    const val USER_PATCH = "cordis.patch.yml"

    /** profile 的 pnpm workspace 声明；存在与否决定安装命令要不要注入 `-w`。 */
    const val PNPM_WORKSPACE = "pnpm-workspace.yaml"

    /** 宿主版本豁免文件。 */
    const val COMPATIBILITY = "compatibility.json"

    /** 已装包目录名。 */
    const val NODE_MODULES = "node_modules"

    /** profile 清单里的依赖表字段。 */
    const val FIELD_DEPENDENCIES = "dependencies"

    /** profile 清单里的 bundle 层栈路径；顺序即层优先级，读写都不得打乱。 */
    val FIELD_PROFILE_BUNDLES = listOf("dsh", "profile", "bundles")

    /** `engines` 字段（宿主要求声明所在）。 */
    const val FIELD_ENGINES = "engines"

    /** `peerDependencies`：兼容门禁的第二个判据；插件声明 `engines.dsh` 时它不参与判定。 */
    const val FIELD_PEER_DEPENDENCIES = "peerDependencies"

    // ─────────────────── 插件清单（package.json）字段 ───────────────────

    /** 插件清单里标识"这是一个 DSH 插件"的字段。 */
    const val FIELD_DSH = "dsh"

    /** `dsh.bundle`：宿主侧入口声明。 */
    const val FIELD_BUNDLE = "bundle"

    /** `dsh.client`：客户端侧入口声明。 */
    const val FIELD_CLIENT = "client"

    /** `dsh.kind`：包对自己角色的声明。 */
    const val FIELD_KIND = "kind"

    /** `dsh.kind` 的"库"取值：共享代码，不是等待启动的插件。 */
    const val LIBRARY_KIND = "library"

    /** `dsh.bundle.patch`：补丁层声明（字符串或字符串数组）。 */
    const val FIELD_PATCH = "patch"

    /** 包自身 JS 入口候选字段（按可靠性排序）。 */
    val ENTRY_FIELDS = listOf("main", "exports")

    /** `exports` 子对象里可能承载入口的键（按可靠性排序）。 */
    val EXPORT_ENTRY_KEYS = listOf("import", "require", "default", "browser", "node")

    // ───────────────────────── 运行层布局 ─────────────────────────

    /** 当前运行层根（`base` / `node` / `dsh` 各层都在它下面）。 */
    const val RUNTIME_CURRENT = "runtime/runtime-current"

    /** DSH 层在运行层根下的目录名。 */
    const val DSH_LAYER = "dsh"

    /** `dsh-app-boot` 包名与入口文件（相对 DSH 层的 `node_modules`）。 */
    const val APP_BOOT_PACKAGE = "@deepseek-ai/dsh-app-boot"
    const val APP_BOOT_ENTRY = "$APP_BOOT_PACKAGE/lib/index.js"

    /**
     * 「engines 独占」语义在 `dsh-app-boot` 产物里的字面量标记。
     *
     * 兼容门禁的判据按**已装宿主的产物**推断，不得改回按版本号比较：版本阈值在
     * 宿主升级后立刻过期，产物标记会随上游走。
     */
    const val APP_BOOT_ENGINES_GATE = "engines.dsh"

    /** 宿主版本的权威纯文本记录（相对 DSH 层根）。 */
    const val VERSION_FILE = ".dshbox/version"

    // ───────────────────────── guest 侧布局 ─────────────────────────

    /** guest 内的 DSH home（`user-data` 绑定在 `/root/projects`）。 */
    const val GUEST_DSH_HOME = "/root/projects/.dsh"

    /** guest 内的 `web` profile 目录。 */
    const val GUEST_WEB_PROFILE = "$GUEST_DSH_HOME/profiles/web"

    /**
     * 宿主随安装自带的 bundle：由 DSH 安装层提供，永远不属于插件市场的已安装列表。
     *
     * 与上游同一份名单（`profile.ts` 的 `INBOX_BUNDLES`）。DSH 新增自带 bundle 时
     * 需同步此处，否则它会以普通已装插件的形态出现在市场里。
     */
    val IN_BOX_BUNDLES = setOf(
        "@deepseek-ai/dsh-base",
        "@deepseek-ai/dsh-web-app",
        "@deepseek-ai/dsh-headless",
    )

    // ───────────────────────── 工具链事实 ─────────────────────────

    /**
     * registry 覆盖文件缺席时的兜底基址（官方）。
     *
     * 判定面（更新检测、预发布通道、浏览期兼容性）读不到镜像文件时回退到这里；
     * 与安装链无关——`dsh plugin` 的源由 DSH 面板决定。
     */
    const val DEFAULT_REGISTRY_BASE = "https://registry.npmjs.org"

    /**
     * 解析镜像源覆盖文件的内容为 registry 基址。
     *
     * 只认首行、trim ASCII 空白、方案前缀小写敏感、剥尾部 `/`；loopback 与链路本地
     * 地址一律丢弃（该文件位于 guest 可写的挂载点，不接受指向本机的取数目标）。
     * 缺失、空、校验不过返回 null，调用方回退 [DEFAULT_REGISTRY_BASE]。
     */
    fun resolveRegistryBase(text: String?): String? {
        val first = text?.substringBefore('\n')?.trim { it <= ' ' }.orEmpty()
        if (!first.startsWith("https://") && !first.startsWith("http://")) return null
        val authority = first.substringAfter("://").substringBefore('/')
            .substringBefore('?').substringBefore('#')
        if (isLocalAuthority(authority)) return null
        return first.trimEnd('/')
    }

    /**
     * authority 是否指向本机：loopback、链路本地、未指定地址与无 host 形态。
     *
     * IPv6 字面量一律拒绝（自建镜像不用它，而映射、压缩、zone id 等写法难以穷举）；
     * IPv4 只认规范点分四段，整数、八进制段与 `0x` 写法都等于本机，一并拒绝。
     * 域名解析后落到本机的情形不在本判定内。
     */
    private fun isLocalAuthority(authority: String): Boolean {
        val host = authority.substringAfterLast('@')
        // 无 host 的形态（`http:///x`、`http://:80/x`）解析落点是本机。
        if (host.startsWith("[")) return true
        val bare = host.substringBefore(':').lowercase().trimEnd('.')
        if (bare.isEmpty() || bare == "localhost" || bare == "0.0.0.0" || bare.startsWith("0x") ||
            bare.startsWith("127.") || bare.startsWith("169.254.") || bare.startsWith("fe80:")
        ) {
            return true
        }
        if (!bare.all { it.isDigit() || it == '.' }) return false
        val parts = bare.split('.')
        return parts.size != 4 || parts.any { part ->
            part.isEmpty() || (part.length > 1 && part.startsWith("0")) || (part.toIntOrNull() ?: 256) > 255
        }
    }

    // ───────────────────────── 纯判定 ─────────────────────────

    /** 是否官方命名空间下的包。 */
    fun isOfficial(name: String?): Boolean = name != null && name.startsWith(OFFICIAL_NAMESPACE)

    /** 是否 loader 合成条目。 */
    fun isLoaderEntry(name: String?): Boolean = name != null && name.startsWith(LOADER_NAMESPACE)

    /** 是否我方随包资产。 */
    fun isLocalAsset(name: String?): Boolean = name != null && name.startsWith(LOCAL_NAMESPACE)

    /**
     * 从已解析的 profile 清单根对象里取出 bundle 层栈。
     *
     * 形态不对（缺字段、不是数组）返回 null，调用方必须与"空栈"区分。
     */
    fun readProfileBundles(root: org.json.JSONObject): List<String>? {
        val array = root.optJSONObject(FIELD_PROFILE_BUNDLES[0])
            ?.optJSONObject(FIELD_PROFILE_BUNDLES[1])
            ?.optJSONArray(FIELD_PROFILE_BUNDLES[2])
            ?: return null
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
    }
}
