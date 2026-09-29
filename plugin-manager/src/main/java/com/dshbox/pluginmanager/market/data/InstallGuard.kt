package com.dshbox.pluginmanager.market.data

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult

/**
 * 读一个已装包在磁盘上的事实。
 *
 * [ProfileReader] 是生产实现；抽出接口是为了让"装后校验"这条链路能在单测里
 * 用一个**伪造的**事实来源驱动，不需要真机与真实文件系统。
 */
interface PackageFactsSource {
    fun facts(name: String, spec: String?): PackageFacts
}

/**
 * 读 profile 清单里**已装依赖的键集**。
 *
 * 这是"这次安装到底新增了哪个包"的唯一真值来源：装前读一次、装后再读一次，
 * 差集就是本次新增的包。刻意**不从安装目标反推包名**——仓库名与 npm 包名经常
 * 不一样（仓库 `awesome-dsh-plugin` 里的包叫 `dsh-market`），反推出来的名字去
 * `node_modules` 里找不到目录，会把一次成功的安装判成失败；回收时还会对一个
 * 从未验证存在的名字执行 `remove`，若它恰好是别人的真实依赖就会误删。
 *
 * @return 键集；null 表示清单读不出来（差集不可用，退回目录条目的 npm 名）。
 */
interface DependencyNamesSource {
    fun dependencyNames(): Set<String>?
}

/**
 * 装后校验发现的缺口。
 *
 * 用"中文 / 英文"两半而不是一整句双语：上层把它拼进最终文案时只留**一个**
 * ` / ` 分隔符，`localizeBilingual` 才能干净地择半（嵌套双语会被切错）。
 */
internal data class InstallGap(val zh: String, val en: String)

/**
 * 一个已装包能不能被加载。
 *
 * 判据只有"dsh 元数据 + 入口产物"这一组：
 * 1. 目录存在（否则什么都没装上）；
 * 2. `package.json` 读得出来（损坏的清单等于不可加载）；
 * 3. 声明了 `dsh` 字段（这是"它是一个 DSH 插件"的唯一标志）；
 * 4. **声明的入口产物真的存在**：`dsh.bundle` / `dsh.client` 里至少有一个，
 *    且它声明的路径指向一个真实文件。"声明存在" ≠ "产物存在"——构建脚本默认
 *    被 pnpm 拦截，"需要构建才有 dist/bundle.js"的插件会装成没有产物的形态，
 *    只看声明会让守卫报成功，重启后 loader 抛 `failed to apply loader entry`。
 *
 * @return null 表示可加载；否则给出缺口。
 */
internal fun installGap(facts: PackageFacts): InstallGap? = when {
    !facts.installed -> InstallGap(
        zh = "node_modules 里找不到它的目录",
        en = "its directory is missing from node_modules",
    )

    !facts.manifestReadable -> InstallGap(
        zh = "它的 package.json 读不出来",
        en = "its package.json could not be read",
    )

    !facts.hasDshField -> InstallGap(
        zh = "它的 package.json 里没有 dsh 字段（不是 DSH 插件）",
        en = "its package.json has no dsh field (it is not a DSH plugin)",
    )

    // 声明了入口但产物文件不在：装出来就是不可加载的（构建脚本被拦是常见成因）。
    (facts.hasBundle || facts.hasClient) && !facts.hasUsableEntry() -> InstallGap(
        zh = "它声明的入口产物文件不存在（构建脚本被拦时插件会装成没有产物的形态）",
        en = "the entry artifact it declares does not exist (with build scripts blocked the plugin installs without its artifacts)",
    )

    !facts.hasBundle && !facts.hasClient -> InstallGap(
        zh = "它的 dsh 字段既没有 bundle 也没有 client（没有入口产物）",
        en = "its dsh field declares neither bundle nor client (no entry artifact)",
    )

    else -> null
}

/** 包名形态：`name` 或 `@scope/name`。用来过滤"明显不是包名"的候选。 */
private val PACKAGE_NAME_RE = Regex("""^(@[A-Za-z0-9._-]+/)?[A-Za-z0-9._-]+$""")

/**
 * 目录条目给的 npm 名（`declaredName`）→ 可用于校验与回收的包名。
 *
 * 这是**唯一**允许的兜底来源：差集拿不到（装前/装后清单读不出来，或本次安装
 * 没有新增依赖）时用它。**不从安装目标反推包名**：`github:owner/repo` 推出来的
 * `repo` 只是仓库名，可能既不是包目录名、又是别人依赖里的真实包名。
 */
internal fun declaredPackageName(declaredName: String): String? {
    val trimmed = declaredName.trim()
    return trimmed.takeIf { PACKAGE_NAME_RE.matches(it) }
}

/**
 * 安装的「假成功守卫」。
 *
 * ## 为什么命令退出码为 0 还不够
 *
 * `dsh plugin add` 的成功只说明"pnpm 这一趟没报错"。下面几种情况它都会返回 0，
 * 而插件**根本加载不了**：包拉下来了但清单损坏；装的其实是同名的一个普通
 * npm 包（没有 `dsh` 元数据）；`dsh.bundle` / `dsh.client` 没声明、或声明了但
 * 入口产物不存在（构建脚本被拦）；甚至命令装到了别的目录。用户看到的会是
 * "安装成功"，然后重启后插件不存在——这比直接报失败难查得多。
 *
 * ## 校验谁：装前 / 装后的依赖差集
 *
 * 校验对象**不是**从安装目标猜出来的名字，而是 profile `dependencies` 的
 * 装前 / 装后**差集**——那才是"本次新增了哪些包"的真值。差集为空（本次安装
 * 没有新增依赖，例如重装同一版本）时退回目录条目的 npm 名；两者都拿不到
 * （清单读不出来且没有 npm 名）时**保留安装**并如实说"未校验"，而不是判失败。
 *
 * ## 回收只针对"本次新增且校验不过"的名字
 *
 * 差集之外的名字可能属于别人的真实依赖，对它们执行 `remove` 会把别人的包删了。
 * 所以回收严格限制在 `newNames` 里校验不过的那些。
 */
class InstallGuard(
    private val ops: GuestPluginOps,
    private val factsSource: PackageFactsSource,
    private val dependencySource: DependencyNamesSource,
) {

    /**
     * 装一个目标并校验结果；校验不过就回收。
     *
     * @param installTarget 目录条目给的安装目标，原样交给 guest 命令。
     * @param declaredName 目录条目的 npm 名，仅在差集拿不到时兜底。
     * @param onLine 进度行回调（安装输出与回收动作都经它回传）。
     */
    suspend fun install(
        installTarget: String,
        declaredName: String,
        onLine: (String) -> Unit,
    ): AppResult<Unit> {
        // 装前 / 装后的依赖键集：差集是"本次新增了哪些包"的真值。
        val before = runCatching { dependencySource.dependencyNames() }.getOrNull()
        val added = ops.add(installTarget, onLine)
        if (added is AppResult.Failure) return added
        val after = runCatching { dependencySource.dependencyNames() }.getOrNull()

        val diff: Set<String>? = if (before != null && after != null) after - before else null
        // 只有"差集可用且非空"才说明这些名字确实是本次新增的——**回收只允许在这一支里做**。
        val usedDiff = diff != null && diff.isNotEmpty()
        val newNames: List<String> = when {
            // 差集可用且非空：只用差集，任何"同名可加载包"都顶替不了。
            usedDiff -> diff!!.toList()
            // 差集为空或读不出来：退回目录条目的 npm 名（仅用于校验，见下）。
            else -> declaredPackageName(declaredName)?.let { listOf(it) } ?: emptyList()
        }

        if (newNames.isEmpty()) {
            // 推不出包名 ≠ 安装失败：tarball 直链这类目标的文件名里带版本号，
            // 本来就还原不出包名，目录条目也可能没有 npm 名。把它报成失败会让
            // 一次**成功**的安装看起来没装上，用户会反复重试。这里保留安装结果，
            // 并把"没校验"如实说出来，也绝不去 remove 一个猜出来的名字。
            onLine("未校验：无法确定它装成了哪个包，请刷新列表核对 / unverified: installed package unknown")
            return AppResult.Success(Unit)
        }

        val broken = newNames.filter { installGap(factsSource.facts(it, null)) != null }
        if (broken.isEmpty()) return AppResult.Success(Unit)

        val reason = installGap(factsSource.facts(broken.first(), null))
            ?: InstallGap("未知原因", "unknown reason")

        if (!usedDiff) {
            // 回退路径：这个名字只是"目录声明的名字"，没有任何证据表明本次安装动过它。
            // 可以拿它校验，但**绝不回收**——`remove` 一个未被证实是本次新增的包，
            // 可能把别人的真实依赖删掉，那正是本守卫要防的事。
            return failure(
                code = "INSTALL_VERIFY_FAILED",
                message = "命令报告成功，但目录声明的包不可加载：${reason.zh}（无法确认它是否本次新增，未执行回收） / " +
                    "the command reported success, but the catalog-declared package is not loadable: " +
                    "${reason.en} (not confirmed as newly added, so nothing was removed)",
            )
        }

        // 回收：把一个不可加载的包留在 profile 里，下次启动的 loader 会报错。
        // 只回收 broken 里的名字（= 本次新增且校验不过），不碰别的。
        val failedRollbacks = broken.mapNotNull { name ->
            onLine("rollback: dsh plugin remove '$name'")
            if (ops.remove(name, onLine) is AppResult.Failure) name else null
        }
        val rollbackNote = if (failedRollbacks.isNotEmpty()) {
            val names = failedRollbacks.joinToString("、")
            "；回收 $names 也失败了，磁盘上可能留下半个包 / the rollback ($names) failed too, so a half-installed package may remain"
        } else {
            ""
        }
        return failure(
            code = "INSTALL_VERIFY_FAILED",
            message = "命令报告成功，但装出来的包不可加载：${reason.zh}。已执行回收$rollbackNote / " +
                "the command reported success, but the installed package is not loadable: ${reason.en}; it has been rolled back",
        )
    }

    private fun failure(code: String, message: String): AppResult.Failure =
        AppResult.Failure(AppError(code = code, message = message))
}
