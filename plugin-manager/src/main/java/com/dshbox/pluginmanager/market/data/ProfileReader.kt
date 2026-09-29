package com.dshbox.pluginmanager.market.data

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
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
)

/**
 * 这个包是否有一个**真实存在**的入口产物。
 *
 * 与 [PackageFacts.hasBundle] / [PackageFacts.hasClient]（只是声明）严格区分：
 * 只有声明对应的文件真的躺在磁盘上，才算"有入口产物"。
 */
internal fun PackageFacts.hasUsableEntry(): Boolean =
    (hasBundle && bundleEntryExists) || (hasClient && clientEntryExists)

/**
 * profile 文件的读取。
 *
 * ## 为什么清单解析失败必须上报而不是给空列表
 *
 * profile 的 `package.json` 读不出来时，若按"空清单"处理，界面会显示
 * "一个插件都没装"。用户看到的是**功能消失了**，而真正的问题是文件坏了；
 * 两者需要完全不同的处理。所以这里把不可读与"确实没装插件"严格分开：
 * 前者是 [AppResult.Failure]，后者是空 [ProfileManifest]。
 *
 * ## 为什么不用"目录存在"当已装的判据
 *
 * 只认 `dependencies` 里的名字：`node_modules` 里可能有被 pnpm 提升上来的
 * 传递依赖，它们不是用户装的插件，列出来只会造成误解。目录存在与否用来
 * 判断 [PackageFacts.installed]，即"清单里有、文件在不在"。
 *
 * ## 文案语言
 *
 * 本文件里所有面向用户的失败说明都是"中文 / English"双语串，由 UI 侧的
 * `localizeBilingual` 按界面语言择半。**同一时刻只支持 zh / en 两种**：
 * ar / es / fr / ru 会回退到英文那一半。这不是"已完整本地化"，是刻意保留的
 * 最小实现——把这些串搬进 `strings.xml` 才是完整做法，本轮没做。
 */
class ProfileReader(private val paths: PluginPaths) : PackageFactsSource, DependencyNamesSource {

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
     * 读包自己的声明与约定补丁文件；解析不出来返回空列表——调用方必须据此拒绝写层。
     */
    fun rowIdsForPackage(name: String): List<String> {
        val dir = paths.installedPackageDir(name)
        val declared = runCatching {
            File(dir, "package.json").takeIf { it.isFile }?.readText(Charsets.UTF_8)
        }.getOrNull()?.let { PackageRowIds.declaredPatchOf(it) }
        return PackageRowIds.forPackage(dir, declared)
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
        root.optJSONObject("dependencies")?.let { deps ->
            for (key in deps.keys()) dependencies[key] = deps.optString(key)
        }
        val bundles = mutableListOf<String>()
        val array = root.optJSONObject("dsh")
            ?.optJSONObject("profile")
            ?.optJSONArray("bundles")
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
        return runCatching {
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
    }

    private fun JSONObject?.hasNonNull(key: String): Boolean =
        this != null && has(key) && !isNull(key)
}

/**
 * 纯解析：一份包清单正文 → 磁盘事实。
 *
 * 与文件 IO 分开是为了让"`dsh` 字段怎么读"这件事能被单测直接钉住（本地单测
 * 里造不出 `android.content.Context`，所以 [ProfileReader.facts] 本身没法
 * 在 JVM 上跑）。正文不是合法 JSON 时抛异常，由调用方转成"清单读不出来"。
 *
 * @param entryExists 判断 `dsh.bundle` / `dsh.client` 声明的路径是否真的存在；
 *   默认恒 false（纯解析拿不到文件系统），由 [ProfileReader.facts] 注入真实实现。
 */
/** `exports` 子对象里可能承载入口的键（按可靠性排序）。 */
private val ENTRY_KEYS = listOf("import", "require", "default", "browser", "node")

/**
 * 解析 `package.json` 的 `dsh` 字段与入口产物。
 *
 * ## 入口产物到底指什么
 *
 * `dsh.bundle` 的值形态不固定：可能是一个对象（`{patch: "./cordis.patch.yml"}`，
 * dshmarket 就是这样），也可能是字符串。**patch 文件只描述组合层，不是可加载入口**——
 * 之前把 `dsh.bundle` 当路径字符串去查文件，任何"对象形态"的插件都会被误判成
 * "产物不存在"，装好的包随即被守卫回收（真机上把 dshmarket 删了）。
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
    val dsh = obj.optJSONObject("dsh")
    val bundleValue = dsh?.opt("bundle")
    val clientValue = dsh?.opt("client")
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
        (obj.opt("main") as? String)?.let { add(relative(it)) }
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
    )
}

/** 声明字段的非空字符串值；null / 空串都算"没声明"。 */
private fun JSONObject?.nonBlank(key: String): String? =
    this?.let { if (it.has(key) && !it.isNull(key)) it.optString(key).ifBlank { null } else null }

/**
 * 激活状态的判定表。
 *
 * ## 为什么这一版不返回 LIVE
 *
 * 上游能宣称"正在运行"是因为它**跑在宿主进程里**，可以直接读 loader 的
 * 实时条目清单，把观测结果当作压倒性事实。我们不在那个进程里：宿主没有向
 * 外暴露"当前加载了哪些条目"的接口，我们能看到的只有磁盘上的文件。
 * 用磁盘推断"正在运行"会把"装好了但这次没加载"说成"已生效"——这正是
 * 最不该对用户说错的一句话。所以这里最高只到 [ActivationState.RESTART]
 * （已装、下次启动生效），把"实时"留给将来真有实时清单的时候。
 *
 * ## 判定顺序即优先级
 *
 * 开关状态 > 文件是否存在 > 有没有 dsh 元数据 > 是不是纯客户端插件 >
 * 是否进了 bundle 层。顺序不能换：一个被停用且文件已删的包，用户关心的是
 * "我关掉了它"，而不是"文件不见了"。
 *
 * ## 为什么"纯客户端插件"不是 BROKEN
 *
 * `dsh.profile.bundles` 是**宿主侧** loader 的组合清单。一个只声明了
 * `dsh.client`（没有 `dsh.bundle`）的插件本来就不该出现在 bundles 里：它的
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
 */
object ActivationClassifier {

    data class Verdict(
        val state: ActivationState,
        val reasons: List<String>,
    )

    fun classify(facts: PackageFacts, inBundles: Boolean, disabled: Boolean): Verdict {
        if (disabled) {
            return Verdict(
                ActivationState.DISABLED,
                listOf(
                    "已停用（市场开关或补丁层），重启后保持关闭 / disabled (market toggle or patch layer) — stays off across restarts",
                ),
            )
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
        if (!facts.hasDshField) {
            return Verdict(
                ActivationState.INERT,
                listOf(
                    "普通依赖（未声明 dsh 元数据），不是 profile 层插件 / a plain dependency with no dsh metadata — not a profile-layer plugin",
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
        // 宿主侧 bundle：声明了但产物不在，同样是不可加载的。
        if (facts.hasBundle && !facts.bundleEntryExists) {
            return Verdict(
                ActivationState.BROKEN,
                listOf(
                    "它声明了 dsh.bundle 但入口产物不存在（构建脚本被拦时会装成没有产物的形态），加载会失败 / it declares dsh.bundle but the entry artifact is missing (with build scripts blocked it installs without artifacts) — loading would fail",
                ),
            )
        }
        if (inBundles) {
            return Verdict(
                ActivationState.RESTART,
                listOf(
                    "已装且已进入 profile bundle 层，重启后生效 / installed and in the profile bundle layer — applies after a restart",
                ),
            )
        }
        return Verdict(
            ActivationState.BROKEN,
            listOf(
                "声明了 dsh 元数据但不在 dsh.profile.bundles 里，缺入口声明，加载会失败 / declares dsh metadata but is not listed in dsh.profile.bundles — no entry declaration, loading would fail",
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
