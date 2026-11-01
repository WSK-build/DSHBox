package com.dshbox.pluginmanager.market.data

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.pluginmanager.contract.HostCapabilities
import com.dshbox.pluginmanager.contract.HostContract
import com.dshbox.pluginmanager.core.HostPackages
import com.dshbox.pluginmanager.core.PluginPaths
import com.dshbox.pluginmanager.market.model.ActivationState
import java.io.File
import org.json.JSONObject

/** profile 的 `package.json` 里与市场相关的两部分。 */
data class ProfileManifest(
    /** 已装清单：包名 → 安装 spec（版本区间、`github:`、`link:` 等原样保留）。 */
    val dependencies: Map<String, String>,
    /** `dsh.profile.bundles` —— 重启后会进入 loader 组合的包。 */
    val bundles: List<String>,
)

/**
 * 一个已装包在磁盘上的事实。
 *
 * 刻意与「激活状态」分开：这里只记录**读到了什么**，不含任何判定。
 * 判定集中在 [ActivationClassifier]，这样判定表可以脱离文件系统单测，
 * 而这里只需要回答"文件在不在、能不能读、声明了什么"。
 */
data class PackageFacts(
    val name: String,
    /** 来自 profile 清单的安装 spec。 */
    val spec: String?,
    /** `node_modules/<name>/package.json` 里的 `version`。 */
    val version: String?,
    /** `node_modules/<name>` 目录是否存在。 */
    val installed: Boolean,
    /** 该包的清单能否读出来（目录在但清单缺失/损坏时为 false）。 */
    val manifestReadable: Boolean,
    /** 是否声明了 `dsh` 字段——这是"这是一个 DSH 插件"的唯一标志。 */
    val hasDshField: Boolean,
    /** **声明了** `dsh.bundle`（宿主侧入口声明）。声明存在 ≠ 产物存在。 */
    val hasBundle: Boolean,
    /** **声明了** `dsh.client`（客户端侧入口声明）。声明存在 ≠ 产物存在。 */
    val hasClient: Boolean,
    /**
     * `dsh.bundle` 声明的入口**产物文件确实存在**（按声明路径相对包目录解析并 `isFile`）。
     *
     * 构建脚本默认被 pnpm 拦截，于是"需要构建才有 dist/bundle.js"的插件会被装成
     * **没有产物**的形态。只看声明会让守卫报成功，重启后 loader 抛
     * `failed to apply loader entry` —— 这正是守卫该抓的主因。
     */
    val bundleEntryExists: Boolean = false,
    /** `dsh.client` 声明的入口产物文件确实存在。 */
    val clientEntryExists: Boolean = false,
    /**
     * **载体（carrier）形态成立**：包自己没有入口产物，但它的 bundle 补丁
     * 挂载了其它包，且至少一个目标包在任一解析根下有真实入口产物。
     *
     * 这类包"自身无入口"是合法形态；不认这一条会把装好的载体判成
     * 不可加载并回收。只有 `hasBundle` 且自身产物缺失时才有意义。
     */
    val hasCarrierEntry: Boolean = false,
    /**
     * 清单声明 `dsh.kind: "library"`（且无 bundle / client）：共享代码，不是
     * 等待启动的插件。上游对同形态判 inert 并单列 library 标记。
     */
    val declaredLibrary: Boolean = false,
)

/**
 * 这个包是否有一个**真实存在**的入口产物（自身的，或经载体挂载的）。
 *
 * 与 [PackageFacts.hasBundle] / [PackageFacts.hasClient]（只是声明）严格区分：
 * 只有声明对应的文件真的躺在磁盘上，才算"有入口产物"。
 */
internal fun PackageFacts.hasUsableEntry(): Boolean =
    (hasBundle && (bundleEntryExists || hasCarrierEntry)) || (hasClient && clientEntryExists)

/**
 * profile 文件的读取。
 *
 * ## 清单解析失败必须上报的原因
 *
 * profile 的 `package.json` 读不出来时，若按"空清单"处理，界面会显示
 * "一个插件都没装"。用户看到的是**功能消失了**，而真正的问题是文件坏了；
 * 两者需要完全不同的处理。所以这里把不可读与"确实没装插件"严格分开：
 * 前者是 [AppResult.Failure]，后者是空 [ProfileManifest]。
 *
 * ## 不用"目录存在"作已装判据的原因
 *
 * 只认 `dependencies` 里的名字：`node_modules` 里可能有被 pnpm 提升上来的
 * 传递依赖，它们不是用户装的插件，列出来只会造成误解。目录存在与否用来
 * 判断 [PackageFacts.installed]，即"清单里有、文件在不在"。
 *
 * ## 文案语言
 *
 * 本文件里所有面向用户的失败说明都是"中文 / English"双语串，由 UI 侧的
 * `localizeBilingual` 按界面语言择半。**同一时刻只支持 zh / en 两种**：
 * ar / es / fr / ru 回退到英文。
 */
class ProfileReader(
    private val paths: PluginPaths,
    /**
     * 宿主能力（决定 `dsh.bundle.patch` 数组声明是否有效等）。
     * 默认"最宽"——未接线能力探测时保持既有行为，不因探测缺失而改变判定。
     */
    private val capabilities: HostCapabilities.Capabilities = HostCapabilities.Capabilities.WIDEST,
) : PackageFactsSource, DependencyNamesSource {

    /**
     * 宿主自带包集合（安装层实际有什么，运行时推导一次即可）。
     *
     * 判定"能不能停用"全靠它：官方集合会随 DSH 升级变化，写死名单必然陈旧。
     */
    private val hostPackages: Set<String> by lazy { HostPackages.installedPackages(paths) }

    /** 供调用方（启停/绝对安全模式）复用的同一个推导结果。 */
    fun hostPackages(): Set<String> = hostPackages

    /**
     * 一个包在补丁层里拥有的行 id（包名 ≠ 条目 id，见 [PackageRowIds]）。
     *
     * 读包自己的声明与约定补丁文件（声明支持字符串与字符串数组两种宿主形态）；
     * 解析不出来返回空列表——调用方必须据此拒绝写层。
     */
    fun rowIdsForPackage(name: String): List<String> {
        val dir = paths.installedPackageDir(name)
        val declared = declaredPatchFilesOf(dir)
        return PackageRowIds.forPackage(dir, declared)
    }

    /**
     * 一个包补丁里的「邻居行」id（针对其它插件的顶层行，见 [PackageRowIds.foreignRowIds]）。
     *
     * 启停的双层对账要用它：带邻居行的包**不能**从 `dsh.profile.bundles` 摘除，
     * 否则连邻居的配置一起带走。补丁文件读不出来按"没有邻居行"处理（保守方向：
     * 只写行、不动栈，不会造成额外破坏）。
     */
    fun foreignRowIdsForPackage(name: String): List<String> {
        val dir = paths.installedPackageDir(name)
        return PackageRowIds.patchFiles(dir, declaredPatchFilesOf(dir)).flatMap { file ->
            runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
                .let { PackageRowIds.foreignRowIds(it) }
        }
    }

    private fun declaredPatchFilesOf(dir: File): List<String> =
        runCatching {
            File(dir, HostContract.PROFILE_MANIFEST).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        }.getOrNull()?.let { PackageRowIds.declaredPatchFiles(it, capabilities.acceptsPatchArray) }
            ?: emptyList()

    /**
     * 该包的**声明与补丁文件是否都读得出来**（文件不存在不算读不出）。
     *
     * [rowIdsForPackage] 把"读不出"与"确无声明"都折成空列表——启停据此拒绝
     * 是安全侧，但残留行审计据此清理会误删真实生效的停用行。审计用它做
     * 保守闸门：读不出就放弃本轮审计。
     *
     * ## 同时检查包清单的原因
     *
     * 该包声明的补丁路径来自它自己的 `package.json`（`dsh.bundle.patch`）。
     * 清单**存在但读不出**时，[declaredPatchFilesOf] 会**静默**返回空列表，
     * 于是"该包声明的补丁文件"整体从 [PackageRowIds.patchFiles] 的输入里消失：
     * 只剩下约定位置的 `cordis.patch.yml`。若那个约定文件恰好不存在，`.all{}`
     * 对空集返回 true —— 闸门**放行**，而该包声明里的行 id 全部缺失，
     * 它们会落进"无人认领"被当成残留清理掉，**用户刻意停用的插件被复活**。
     *
     * 所以清单读失败必须与补丁文件读失败同等对待。
     */
    fun patchFilesReadable(name: String): Boolean {
        val dir = paths.installedPackageDir(name)
        val manifest = File(dir, HostContract.PROFILE_MANIFEST)
        if (manifest.isFile && runCatching { manifest.readText(Charsets.UTF_8) }.isFailure) return false
        return PackageRowIds.patchFiles(dir, declaredPatchFilesOf(dir)).all { file ->
            !file.isFile || runCatching { file.readText(Charsets.UTF_8) }.isSuccess
        }
    }

    /**
     * 已装依赖的键集（差集判定的输入）；清单读不出来时返回 null。
     *
     * 安装守卫用它做"装前 / 装后"的差集：差集才是"本次新增了哪个包"的真值，
     * 从安装目标反推包名在仓库名 ≠ 包名时会把成功判成失败。
     */
    override fun dependencyNames(): Set<String>? =
        when (val result = readManifest()) {
            is AppResult.Success -> result.value.dependencies.keys.toSet()
            is AppResult.Failure -> null
        }

    /** 读 profile 清单。文件缺失或 JSON 损坏都返回失败。 */
    /**
     * 一个已装包清单的**原文**。
     *
     * 兼容门禁要原文而不是解析结果：它必须区分「`engines.dsh` 存在但为空」
     * （宿主判不满足）与「根本没有这个字段」（改看 peers）。
     *
     * @return null 表示清单不存在或读不出来。
     */
    fun manifestText(name: String): String? {
        val file = paths.installedManifest(name) ?: return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    fun readManifest(): AppResult<ProfileManifest> {
        val file = paths.profileManifest
        if (!file.isFile) {
            return AppResult.Failure(
                AppError(
                    code = "PROFILE_MANIFEST_MISSING",
                    // 路径只进日志/内部诊断，不写进用户可见文案。
                    message = "profile 清单不存在 / the profile manifest is missing",
                ),
            )
        }
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse { t ->
            return AppResult.Failure(
                AppError(
                    code = "PROFILE_MANIFEST_UNREADABLE",
                    message = "profile 清单读不出来：${t.message ?: t::class.java.simpleName} / the profile manifest could not be read",
                    cause = t,
                ),
            )
        }
        return runCatching { AppResult.Success(parseManifest(text)) }.getOrElse { t ->
            AppResult.Failure(
                AppError(
                    code = "PROFILE_MANIFEST_INVALID",
                    message = "profile 清单不是合法 JSON：${t.message ?: t::class.java.simpleName} / the profile manifest is not valid JSON",
                    cause = t,
                ),
            )
        }
    }

    /** 解析清单正文。JSON 不合法时抛异常，由 [readManifest] 转成失败。 */
    fun parseManifest(text: String): ProfileManifest {
        val root = JSONObject(text)
        val dependencies = LinkedHashMap<String, String>()
        root.optJSONObject(HostContract.FIELD_DEPENDENCIES)?.let { deps ->
            for (key in deps.keys()) dependencies[key] = deps.optString(key)
        }
        val bundles = mutableListOf<String>()
        val bundlesPath = HostContract.FIELD_PROFILE_BUNDLES
        val array = root.optJSONObject(bundlesPath[0])
            ?.optJSONObject(bundlesPath[1])
            ?.optJSONArray(bundlesPath[2])
        if (array != null) {
            for (index in 0 until array.length()) {
                val value = array.optString(index)
                if (value.isNotBlank()) bundles.add(value)
            }
        }
        return ProfileManifest(dependencies = dependencies, bundles = bundles)
    }

    /**
     * 读一个已装包的磁盘事实。
     *
     * 任何读失败都只影响这一个包的 [PackageFacts]，不会中断整批读取——
     * 一个包的清单坏了不该让"已安装"列表整个打不开。
     */
    override fun facts(name: String, spec: String?): PackageFacts {
        val dir = paths.installedPackageDir(name)
        if (!dir.isDirectory) {
            return PackageFacts(
                name = name, spec = spec, version = null,
                installed = false, manifestReadable = false,
                hasDshField = false, hasBundle = false, hasClient = false,
            )
        }
        val manifest = paths.installedManifest(name)
            ?: return PackageFacts(
                name = name, spec = spec, version = null,
                installed = true, manifestReadable = false,
                hasDshField = false, hasBundle = false, hasClient = false,
            )
        // 入口产物的存在性只能在文件系统上问：`dsh.bundle` / `dsh.client` 的路径
        // 相对包目录解析。声明了但文件不在，插件装出来就是不可加载的。
        val facts = runCatching {
            parsePackageFacts(name, spec, manifest.readText(Charsets.UTF_8)) { relative ->
                File(dir, relative.removePrefix("./")).isFile
            }
        }.getOrElse {
            PackageFacts(
                name = name, spec = spec, version = null,
                installed = true, manifestReadable = false,
                hasDshField = false, hasBundle = false, hasClient = false,
            )
        }
        if (!facts.manifestReadable) return facts
        // 自身产物缺失时问一次"载体挂载"：包自己的入口不在，但它的 bundle 补丁
        // 挂载的某个目标包有真实产物，同样是可加载的合法形态。
        if (facts.hasBundle && !facts.bundleEntryExists) {
            val text = runCatching { manifest.readText(Charsets.UTF_8) }.getOrDefault("")
            val carrier = carrierEntryUsable(dir, text, name)
            if (carrier) return facts.copy(hasCarrierEntry = true)
        }
        return facts
    }

    /**
     * 载体判定：包的 bundle 补丁挂载的目标包里，是否至少一个在任一解析根下
     * 有真实入口产物。
     *
     * 解析根与宿主的运行时解析对齐：profile 的 node_modules（workspace 安装面）、
     * 包内嵌 node_modules、profile 上层（pnpm 对 workspace 成员的提升位）、
     * 安装本体的 node_modules（宿主 0.2.0 的双锚点解析：@deepseek-ai 前缀的
     * bundle 组件优先落在安装层）。
     */
    private fun carrierEntryUsable(packageDir: File, manifestText: String, ownName: String): Boolean {
        val targets = PackageRowIds.patchFiles(packageDir, PackageRowIds.declaredPatchFiles(manifestText))
            .flatMap { file ->
                runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
                    .let { PackageRowIds.patchTargetNames(it) }
            }
            .filter { it != ownName }
            .distinct()
        if (targets.isEmpty()) return false
        val roots = listOf(
            paths.nodeModulesDir,
            File(paths.profilesDir, HostContract.NODE_MODULES),
            File(paths.runtimeCurrentDir, "${HostContract.DSH_LAYER}/${HostContract.NODE_MODULES}"),
            File(packageDir, HostContract.NODE_MODULES),
        )
        return targets.any { target ->
            roots.any { root -> entryArtifactExists(File(root, target)) }
        }
    }

    private fun JSONObject?.hasNonNull(key: String): Boolean =
        this != null && has(key) && !isNull(key)
}

/**
 * 一个包目录是否有真实可加载的 JS 入口（`main` / `exports["."]` / 兜底 `index.js`）。
 *
 * 与上游 `entryArtifactExists` 同口径：exports 为对象时收集其全部字符串值；
 * 目录不存在或清单读不出来按 false 处理（不猜）。
 */
internal fun entryArtifactExists(dir: File): Boolean {
    val manifest = File(dir, HostContract.PROFILE_MANIFEST)
    if (!manifest.isFile) return false
    return runCatching {
        val obj = org.json.JSONObject(manifest.readText(Charsets.UTF_8).trimStart('\uFEFF'))
        val candidates = mutableListOf<String>()
        (obj.opt(HostContract.ENTRY_FIELDS[0]) as? String)?.let { candidates.add(it.trim().removePrefix("./")) }
        when (val exports = obj.opt(HostContract.ENTRY_FIELDS[1])) {
            is String -> candidates.add(exports.trim().removePrefix("./"))
            is org.json.JSONObject -> {
                val root = exports.opt(".")
                when (root) {
                    is String -> candidates.add(root.trim().removePrefix("./"))
                    is org.json.JSONObject -> {
                        for (key in root.keys()) (root.opt(key) as? String)?.let { value ->
                            candidates.add(value.trim().removePrefix("./"))
                        }
                    }
                    else -> Unit
                }
            }
            else -> Unit
        }
        if (candidates.isEmpty()) candidates.add("index.js")
        candidates.any { relative -> relative.isNotEmpty() && File(dir, relative).isFile }
    }.getOrDefault(false)
}

/**
 * 纯解析：一份包清单正文 → 磁盘事实。
 *
 * 解析逻辑与文件 IO 分离。正文不是合法 JSON 时抛异常，由调用方呈现读取失败。
 *
 * @param entryExists 判断 `dsh.bundle` / `dsh.client` 声明的路径是否真的存在；
 *   默认恒 false（纯解析拿不到文件系统），由 [ProfileReader.facts] 注入真实实现。
 */
/** `exports` 子对象里可能承载入口的键（按可靠性排序；取自契约层）。 */
private val ENTRY_KEYS = HostContract.EXPORT_ENTRY_KEYS

/**
 * 解析 `package.json` 的 `dsh` 字段与入口产物。
 *
 * ## 入口产物到底指什么
 *
 * `dsh.bundle` 的值形态不固定：可能是一个对象（`{patch: "./cordis.patch.yml"}`，
 * dshmarket 即采用此结构），也可能是字符串。patch 文件描述组合层，不作为可加载入口。
 *
 * 可加载入口是**包自身的 JS 入口**：`main` / `exports` / 兜底的 `lib/index.js`、
 * `index.js`。纯客户端包则看 `exports["./client"]`。
 *
 * @param entryExists 相对包目录判断文件是否存在；默认恒 false（纯解析拿不到
 *   文件系统），由 [ProfileReader.facts] 注入真实实现。
 */
internal fun parsePackageFacts(
    name: String,
    spec: String?,
    manifestText: String,
    entryExists: (String) -> Boolean = { false },
): PackageFacts {
    val obj = JSONObject(manifestText.trimStart('\uFEFF'))
    val dsh = obj.optJSONObject(HostContract.FIELD_DSH)
    val bundleValue = dsh?.opt(HostContract.FIELD_BUNDLE)
    val clientValue = dsh?.opt(HostContract.FIELD_CLIENT)
    val hasBundle = bundleValue != null && bundleValue != JSONObject.NULL
    val hasClient = clientValue != null && clientValue != JSONObject.NULL

    val exports = obj.opt("exports")

    fun relative(value: String): String = value.trim().removePrefix("./")

    fun stringsFrom(value: Any?): List<String> = when (value) {
        is String -> listOf(relative(value))
        is JSONObject -> ENTRY_KEYS.mapNotNull { key -> (value.opt(key) as? String)?.let(::relative) }
        else -> emptyList()
    }

    // 包的 JS 入口候选（按可靠性排序）。
    val entryCandidates = buildList {
        (obj.opt(HostContract.ENTRY_FIELDS[0]) as? String)?.let { add(relative(it)) }
        when (exports) {
            is String -> add(relative(exports))
            is JSONObject -> addAll(stringsFrom(exports.opt(".")))
            else -> Unit
        }
        add("lib/index.js")
        add("index.js")
    }.filter { it.isNotEmpty() }.distinct()

    // 纯客户端包的产物候选。
    val clientCandidates = buildList {
        when (val client = clientValue) {
            is String -> add(relative(client))
            is JSONObject -> addAll(stringsFrom(client))
            else -> Unit
        }
        (exports as? JSONObject)?.let { addAll(stringsFrom(it.opt("./client"))) }
        add("client/client.js")
    }.filter { it.isNotEmpty() }.distinct()

    return PackageFacts(
        name = name,
        spec = spec,
        version = obj.optString("version").ifBlank { null },
        installed = true,
        manifestReadable = true,
        hasDshField = dsh != null,
        hasBundle = hasBundle,
        hasClient = hasClient,
        // 有声明且至少一个候选真的在磁盘上；没有声明就不参与判定。
        bundleEntryExists = hasBundle && entryCandidates.any { entryExists(it) },
        clientEntryExists = hasClient && clientCandidates.any { entryExists(it) },
        declaredLibrary = dsh?.nonBlank(HostContract.FIELD_KIND) == HostContract.LIBRARY_KIND &&
            dsh.has(HostContract.FIELD_BUNDLE).not() && dsh.has(HostContract.FIELD_CLIENT).not(),
    )
}

/** 声明字段的非空字符串值；null / 空串都算"没声明"。 */
private fun JSONObject?.nonBlank(key: String): String? =
    this?.let { if (it.has(key) && !it.isNull(key)) it.optString(key).ifBlank { null } else null }

/**
 * 激活状态的判定表。
 *
 * ## LIVE 的含义（与上游不同）
 *
 * 上游的 `live` 是**进程内观测**：它跑在宿主进程里，直接读 loader 的实时条目
 * 清单。本模块没有这条通道，这里只回答一件事——**这次变更是否还需要宿主重新组合**：
 *
 * - 安装与 DSH 自身页面的开关写 profile / 用户补丁层，宿主立即加载或由 HMR 应用；
 * - 市场自己的启停写启动参数里的 `--patch` 层，宿主只在下次组合时读它。
 *
 * 因此只有"市场刚拨过、还没等到宿主重启"的包判 [ActivationState.RESTART]，
 * 其余在 bundle 层里的包判 [ActivationState.LIVE]。这不是"观测到正在运行"，
 * 而是"没有已知的待生效变更"——文案必须按这个口径写，不能承诺进程内事实。
 *
 * ## 判定顺序即优先级
 *
 * 开关状态 > 文件是否存在 > 有没有 dsh 元数据 > 是不是纯客户端插件 >
 * 是否进了 bundle 层。顺序不能换：一个被停用且文件已删的包，用户关心的是
 * "我关掉了它"，而不是"文件不见了"。
 *
 * ## "纯客户端插件"不判 BROKEN 的原因
 *
 * `dsh.profile.bundles` 是**宿主侧** loader 的组合清单。一个只声明了
 * `dsh.client`（没有 `dsh.bundle`）的插件不应出现在 bundles 里：它的
 * 代码由客户端侧加载，宿主侧没有入口产物也不缺什么。把这种包判成 BROKEN
 * （"缺入口声明，加载会失败"）是把一个正常的插件说成坏的——用户会去"修"
 * 一个没坏的东西。所以：有 `dsh` 字段、没有 bundle、有 client → INERT，
 * 理由里如实写明"纯客户端插件"。
 *
 * ## 两个例外：声明了 ≠ 产物存在；纯客户端进了 bundles
 *
 * 1. `dsh.client` **声明了但产物文件不在**：这不是"正常的纯客户端插件"，而是
 *    一次不完整的安装（构建脚本被拦是常见成因），加载时同样会失败 → BROKEN。
 * 2. `dsh.client` 声明且产物在，但**被列进了 `dsh.profile.bundles`**：宿主侧
 *    loader 会去加载一个不存在的宿主侧入口，重启时直接报错。这不能显示成
 *    "已安装，未使用"（那会掩盖会拖垮启动的状态）→ BROKEN，理由里点名
 *    bundles 成员资格并建议停用它。
 *
 * ## 载体挂载与两条宿主语义（0.2.x 对齐）
 *
 * 1. **载体（carrier）**：`dsh.bundle` 声明了但自身产物缺失时，若其 bundle 补丁
 *    挂载的目标包在任一解析根下有真实产物（[PackageFacts.hasCarrierEntry]），
 *    这是合法的可加载形态，不算 BROKEN。
 * 2. **未进入 bundles ≠ 坏**：声明了 bundle 但不在 `dsh.profile.bundles` 里的包，
 *    宿主根本不会加载它——这是"未装配/被对账移除"，按 INERT 如实说明（被用户
 *    补丁层按名引用时判 RESTART）。反过来，"没有 dsh 元数据却进了 bundles"才是
 *    BROKEN（宿主解析不出任何条目）。
 */
object ActivationClassifier {

    data class Verdict(
        val state: ActivationState,
        val reasons: List<String>,
    )

    fun classify(
        facts: PackageFacts,
        inBundles: Boolean,
        disabled: Boolean,
        /** 用户的补丁层按**包名**引用了它（insert 行或顶层行的 `name:` 值）。 */
        referencedByUserPatch: Boolean = false,
        /**
         * 宿主兼容门禁会拒绝它时，这里是那条不满足的声明（如 `engines.dsh: ^0.1.0`）；
         * 不会拒绝、或无法判定时传 null。
         *
         * 由调用方用 [HostGateJudge] 先算好再传进来：判定要读宿主产物与豁免文件，
         * 不适合放在这个纯分类器里。
         */
        gateRequirement: String? = null,
        /**
         * 这次变化要等 DSH 下次启动才生效（市场自己拨的开关写的是启动参数里的
         * `--patch` 层，宿主只在组合时读）。
         */
        restartPending: Boolean = false,
    ): Verdict {
        // 停用对"声明的库"让位（上游同语义）：库没有插件面，对它说"已停用"
        // 没有信息量；观测到 loader 仍在加载它时才恢复停用优先——本模块观测不到，
        // 因此一律让位。
        if (disabled && !facts.declaredLibrary) {
            // 市场刚写的停用要等宿主下次组合：此刻插件往往还在跑，不能只说"已停用"。
            return if (restartPending) {
                Verdict(
                    ActivationState.DISABLED_PENDING,
                    listOf(
                        "已写入停用，重启 DSH 后才不再加载 / the disable is written — DSH stops loading it after a restart",
                    ),
                )
            } else {
                Verdict(
                    ActivationState.DISABLED,
                    listOf(
                        "已停用（市场开关或补丁层），重启后保持关闭 / disabled (market toggle or patch layer) — stays off across restarts",
                    ),
                )
            }
        }
        if (!facts.installed) {
            return Verdict(
                ActivationState.MISSING,
                listOf(
                    "清单里有这个包，但 node_modules 里没有它 / listed in dependencies but absent from node_modules",
                ),
            )
        }
        if (!facts.manifestReadable) {
            return Verdict(
                ActivationState.INERT,
                listOf(
                    "包清单读不出来，无法判断它是不是插件 / the package manifest is unreadable — cannot tell whether it is a plugin",
                ),
            )
        }
        // 宿主只按 dsh 元数据解析 bundle 条目：被列进 bundles 却没有元数据的包，
        // 宿主解析不出任何条目（0.1.x 语义下直接报错；0.2.x 记为跳过）。
        if (!facts.hasDshField && inBundles) {
            return Verdict(
                ActivationState.BROKEN,
                listOf(
                    "它被列在 dsh.profile.bundles 里，但没有 dsh 元数据，宿主解析不出任何条目 / it is listed in dsh.profile.bundles but declares no dsh metadata — the host can resolve nothing from it",
                ),
            )
        }
        if (!facts.hasDshField) {
            return Verdict(
                ActivationState.INERT,
                listOf(
                    "普通依赖（未声明 dsh 元数据），不是 profile 层插件 / a plain dependency with no dsh metadata — not a profile-layer plugin",
                ),
            )
        }
        // 声明的库：共享代码，不是等待启动的插件。该清单事实不推翻 loader 观测或
        // 真实插件面（有 bundle / client 就不是声明库），库也无需可加载的入口产物。
        if (facts.declaredLibrary) {
            return Verdict(
                ActivationState.LIBRARY,
                listOf(
                    "供其他插件使用，无需启用 / for other plugins to use; no need to enable it",
                ),
            )
        }
        // 纯客户端插件：由客户端侧加载，本该出现在 bundles 之外。
        if (!facts.hasBundle && facts.hasClient) {
            if (!facts.clientEntryExists) {
                return Verdict(
                    ActivationState.BROKEN,
                    listOf(
                        "它声明了 dsh.client 但入口产物不存在（构建脚本被拦时会装成没有产物的形态），加载会失败 / it declares dsh.client but the entry artifact is missing (with build scripts blocked it installs without artifacts) — loading would fail",
                    ),
                )
            }
            if (inBundles) {
                return Verdict(
                    ActivationState.BROKEN,
                    listOf(
                        "它被列在 dsh.profile.bundles 里，但宿主侧没有入口产物（这是纯客户端插件），重启时宿主侧会加载失败，建议停用它 / it is listed in dsh.profile.bundles but has no host-side entry artifact (it is a client-only plugin), so the host side will fail to load it after a restart — consider disabling it",
                    ),
                )
            }
            return Verdict(
                ActivationState.INERT,
                listOf(
                    "纯客户端插件（由客户端侧加载），宿主侧没有入口产物也不需要 / a client-only plugin loaded on the client side — the host side has no entry artifact and needs none",
                ),
            )
        }
        // 宿主侧 bundle：声明了但产物不在、且不构成载体挂载，同样是不可加载的。
        if (facts.hasBundle && !facts.bundleEntryExists && !facts.hasCarrierEntry) {
            return Verdict(
                ActivationState.BROKEN,
                listOf(
                    "它声明了 dsh.bundle 但入口产物不存在（构建脚本被拦时会装成没有产物的形态），加载会失败 / it declares dsh.bundle but the entry artifact is missing (with build scripts blocked it installs without artifacts) — loading would fail",
                ),
            )
        }
        // 有 dsh 元数据却什么入口都没声明（无 bundle 也无 client）、又不在
        // bundles 里：宿主从它身上解析不出任何可加载的东西，与"声明了但产物
        // 不在"同类。在 bundles 里的同形态交给启动日志的拒绝扫描修正——那里
        // 有宿主真实的"跳过/拒绝"证据，磁盘推断不出。
        if (!facts.hasBundle && !facts.hasClient && !inBundles) {
            return Verdict(
                ActivationState.BROKEN,
                listOf(
                    "它声明了 dsh 元数据但既没有 bundle 也没有 client 入口声明，且不在 dsh.profile.bundles 里，宿主无可加载 / it declares dsh metadata but neither a bundle nor a client entry, and is not listed in dsh.profile.bundles — the host has nothing to load",
                ),
            )
        }
        // 宿主兼容门禁会拒绝它：**重启永远不会生效**，此时说"重启后生效"就是假承诺。
        //
        // 只在 `inBundles` 时判：不在 bundles 里的包宿主不会加载，判它"不兼容"
        // 没有意义（那是"未装配"，上一条已覆盖）。这与上游在 inBundles 分支内判门禁
        // 的位置一致。
        if (inBundles && gateRequirement != null) {
            return Verdict(
                ActivationState.INCOMPATIBLE,
                listOf(
                    "宿主会在启动期拒绝加载（$gateRequirement），重启不会生效 / the host refuses to load it at startup ($gateRequirement) — restarting will not help",
                ),
            )
        }
        if (inBundles) {
            return Verdict(
                if (restartPending) ActivationState.RESTART else ActivationState.LIVE,
                listOf(
                    if (restartPending) {
                        "刚在市场里改过，重启 DSH 后生效 / just changed here — takes effect after DSH restarts"
                    } else {
                        "已进入 profile bundle 层，宿主会自行加载 / listed in the profile bundle layer — the host loads it"
                    },
                ),
            )
        }
        if (referencedByUserPatch) {
            return Verdict(
                ActivationState.RESTART,
                listOf(
                    "用户的补丁层按名引用了它，由你的配置加载 / referenced by name in your own patch layer, so your setup loads it",
                ),
            )
        }
        // 声明了 bundle 却不在 bundles 里：宿主不会加载它。这不是"坏"，而是
        // "未装配/被宿主对账移除"的正常状态——上游同形态判 inert。
        return Verdict(
            ActivationState.INERT,
            listOf(
                "声明了 dsh.bundle 但未进入 dsh.profile.bundles（未装配或被宿主对账移除），宿主侧不会加载 / it declares dsh.bundle but is not listed in dsh.profile.bundles (not assembled, or removed by the host's reconciliation) — the host will not load it",
            ),
        )
    }
}

/**
 * 是否属于第三方。
 *
 * 判据与「绝对安全模式」共用同一处实现，官方命名空间与我方 `@local/` 前缀都不算
 * 第三方。判断入口只能有一个：两处各写一套，迟早会出现一边把随包资产当外部插件
 * 处理的情况（例如把移动端适配插件也当成社区插件停掉）。
 */
fun isThirdPartyPackage(name: String, installed: Set<String>): Boolean =
    com.dshbox.pluginmanager.layer.HostInfrastructure.isThirdParty(name, installed)
