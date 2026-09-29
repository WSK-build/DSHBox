package com.dshbox.pluginmanager.market.data

import com.dshbox.pluginmanager.core.PluginPaths
import com.dshbox.pluginmanager.market.model.HostCompatibility
import java.io.File
import org.json.JSONObject

/**
 * 最小 semver 实现：比较、以及 `^` / `~` / `>=` / `<=` / `>` / `<` / 精确版本 / `||` 区间的判定。
 *
 * 刻意不引第三方 semver 库，也不追求完整实现：这里只回答一个问题——
 * "声明的版本区间是否包含当前宿主版本"。回答不了时返回 null（未知），
 * **绝不**把"读不懂"当成"不满足"。
 *
 * 预发布版本按 npm 的 `includePrerelease` 语义参与比较（不做"必须同 [major,minor,patch]
 * 且自身带预发布"的收紧）：DSH 的发布线本身就是预发布（如 `0.1.5-rc.3`），
 * 用默认语义会把每一个正常插件都判成不满足。
 */
object Semver {

    /** 解析出来的版本号。 */
    data class Version(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val pre: List<String>,
    )

    /** 宽松版本号：允许缺位（`0.9` → 0.9.0、`1` → 1.0.0）。用于版本号之间的比较。 */
    private val SEMVER_RE =
        Regex("""^(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

    /**
     * 严格版本号：三段必须写全。
     *
     * 区间里的比较子（`^`、`>=` 的目标）用这一条解析，**保持"读不懂一律未知"
     * 的既有语义**：`engines.dsh: "1"` 这类写法在 npm 里是区间而不是精确版本，
     * 按精确版本判定会给出一个偏"不兼容"的错误结论。宽松解析只用于版本号之间
     * 的直接比较（更新检测），不放松区间判定。
     */
    private val STRICT_SEMVER_RE =
        Regex("""^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

    /** 区间串的长度上限：超过这个长度的声明一律当作不可解析。 */
    private const val MAX_RANGE_LENGTH = 256

    /**
     * 解析版本号。
     *
     * 允许**缺位**（`0.9` → 0.9.0）：npm 生态里 `0.9` 这类写法真实存在，而
     * "字典序兜底"会把 `0.9` 与 `0.10.0` 判反。缺位补 0 是标准做法，也让更新
     * 检测能把它们真正比出来。带前缀的写法（`v1.0`）仍视为不可解析——我们只认
     * 纯版本号，避免把任意字符串当版本。
     */
    fun parse(value: String): Version? = parseWith(SEMVER_RE, value)

    private fun parseStrict(value: String): Version? = parseWith(STRICT_SEMVER_RE, value)

    private fun parseWith(regex: Regex, value: String): Version? {
        val match = regex.find(value.trim()) ?: return null
        val pre = match.groupValues[4]
        return Version(
            major = match.groupValues[1].toInt(),
            minor = match.groupValues[2].ifEmpty { "0" }.toInt(),
            patch = match.groupValues[3].ifEmpty { "0" }.toInt(),
            pre = if (pre.isEmpty()) emptyList() else pre.split('.'),
        )
    }

    /** 比较两个版本：负数表示 a 更旧。无法解析时按字典序兜底（保证全序，不抛异常）。 */
    fun compare(a: String, b: String): Int {
        val av = parse(a)
        val bv = parse(b)
        if (av == null || bv == null) return a.compareTo(b)
        return compareVersions(av, bv)
    }

    private fun compareVersions(a: Version, b: Version): Int {
        if (a.major != b.major) return a.major - b.major
        if (a.minor != b.minor) return a.minor - b.minor
        if (a.patch != b.patch) return a.patch - b.patch
        if (a.pre.isEmpty() && b.pre.isEmpty()) return 0
        // 预发布版本排在同基线的正式版之前。
        if (a.pre.isEmpty()) return 1
        if (b.pre.isEmpty()) return -1
        return comparePrerelease(a.pre, b.pre)
    }

    private fun comparePrerelease(a: List<String>, b: List<String>): Int {
        val length = maxOf(a.size, b.size)
        for (index in 0 until length) {
            val x = a.getOrNull(index)
            val y = b.getOrNull(index)
            if (x == null) return if (y == null) 0 else -1
            if (y == null) return 1
            if (x == y) continue
            val xn = x.toIntOrNull()
            val yn = y.toIntOrNull()
            if (xn != null && yn != null) return xn - yn
            if (xn != null) return -1
            if (yn != null) return 1
            return x.compareTo(y)
        }
        return 0
    }

    private enum class Op { EXACT, GTE, LTE, GT, LT, CARET, TILDE }

    private data class Comparator(val op: Op, val target: Version)

    private class Alternative(val comparators: List<Comparator>) {

        val exact: Version? = comparators.firstOrNull { it.op == Op.EXACT }?.target

        fun matches(version: Version): Boolean =
            comparators.all { comparator -> matchesComparator(version, comparator) }

        fun belowMinimum(version: Version): Boolean {
            exact?.let { return compareVersions(version, it) < 0 }
            val bound = lowestBound() ?: return false
            val cmp = compareVersions(version, bound.target)
            return if (bound.op == Op.GT) cmp <= 0 else cmp < 0
        }

        fun aboveMaximum(version: Version): Boolean {
            val bound = highestCeiling() ?: return false
            val cmp = compareVersions(version, bound.first)
            return if (bound.second) cmp >= 0 else cmp > 0
        }

        /** 上界是否来自显式声明（`<=`/`<`/精确版本），而不是 `^`/`~` 推算出来的天花板。 */
        fun hasExplicitCeiling(): Boolean =
            exact != null || comparators.any { it.op == Op.LTE || it.op == Op.LT }

        private fun lowestBound(): Comparator? {
            var best: Comparator? = null
            for (comparator in comparators) {
                if (comparator.op != Op.GTE && comparator.op != Op.GT &&
                    comparator.op != Op.CARET && comparator.op != Op.TILDE
                ) {
                    continue
                }
                val current = best
                if (current == null || compareVersions(comparator.target, current.target) > 0) best = comparator
            }
            return best
        }

        /** 返回 (上界版本, 是否为"等于即越界")；`^`/`~` 的天花板是开区间。 */
        private fun highestCeiling(): Pair<Version, Boolean>? {
            var best: Pair<Version, Boolean>? = null
            for (comparator in comparators) {
                val ceiling: Pair<Version, Boolean> = when (comparator.op) {
                    Op.LTE -> comparator.target to false
                    Op.LT -> comparator.target to true
                    Op.EXACT -> comparator.target to false
                    Op.CARET -> caretCeiling(comparator.target) to true
                    Op.TILDE -> tildeCeiling(comparator.target) to true
                    else -> continue
                }
                val current = best
                if (current == null || compareVersions(ceiling.first, current.first) < 0) best = ceiling
            }
            return best
        }
    }

    private fun matchesComparator(version: Version, comparator: Comparator): Boolean {
        val cmp = compareVersions(version, comparator.target)
        return when (comparator.op) {
            Op.EXACT -> cmp == 0
            Op.GTE -> cmp >= 0
            Op.LTE -> cmp <= 0
            Op.GT -> cmp > 0
            Op.LT -> cmp < 0
            Op.CARET -> cmp >= 0 && compareVersions(version, caretCeiling(comparator.target)) < 0
            Op.TILDE -> cmp >= 0 && compareVersions(version, tildeCeiling(comparator.target)) < 0
        }
    }

    /** `^` 的上界：首个非零位进一。 */
    private fun caretCeiling(target: Version): Version = when {
        target.major > 0 -> Version(target.major + 1, 0, 0, emptyList())
        target.minor > 0 -> Version(0, target.minor + 1, 0, emptyList())
        else -> Version(0, 0, target.patch + 1, emptyList())
    }

    /** `~` 的上界：次版本进一。 */
    private fun tildeCeiling(target: Version): Version =
        Version(target.major, target.minor + 1, 0, emptyList())

    /** 区间的判定方向。 */
    enum class Direction { BELOW_MIN, ABOVE_MAX, UNKNOWN }

    data class Verdict(
        val satisfied: Boolean,
        val direction: Direction,
        /** 上界是否来自显式声明。仅在 [direction] 为 `ABOVE_MAX` 时有意义。 */
        val explicitCeiling: Boolean,
    )

    /**
     * 判定 [version] 是否落在 [range] 里。
     *
     * @return true/false 为确定结论；null 表示**区间读不懂**，调用方必须按
     *   "未知"处理，不得当成不满足。
     */
    fun evaluate(version: String, range: String): Verdict? {
        val parsed = parse(version) ?: return null
        val alternatives = parseAlternatives(range) ?: return null
        if (alternatives.isEmpty()) return Verdict(true, Direction.UNKNOWN, false)
        if (alternatives.any { it.matches(parsed) }) {
            return Verdict(true, Direction.UNKNOWN, false)
        }
        if (alternatives.all { it.belowMinimum(parsed) }) {
            return Verdict(false, Direction.BELOW_MIN, alternatives.all { it.hasExplicitCeiling() })
        }
        if (alternatives.all { it.aboveMaximum(parsed) }) {
            return Verdict(false, Direction.ABOVE_MAX, alternatives.all { it.hasExplicitCeiling() })
        }
        return Verdict(false, Direction.UNKNOWN, alternatives.all { it.hasExplicitCeiling() })
    }

    /** 便捷入口：只关心"是否满足"。 */
    fun satisfies(version: String, range: String): Boolean? = evaluate(version, range)?.satisfied

    /**
     * 解析区间。返回 null 表示存在读不懂的部分——**整条区间作废**，
     * 而不是忽略那一部分后给出一个偏乐观的结论。
     */
    private fun parseAlternatives(range: String): List<Alternative>? {
        val trimmed = range.trim()
        if (trimmed.length > MAX_RANGE_LENGTH) return null
        // 空区间等于"没有约束"，这是 npm 语义（`*` 的简写）。
        if (trimmed.isEmpty()) return listOf(Alternative(emptyList()))
        val alternatives = mutableListOf<Alternative>()
        for (rawAlternative in trimmed.split("||")) {
            val parts = rawAlternative.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            val comparators = mutableListOf<Comparator>()
            for (part in parts) {
                when (val parsed = parseComparator(part)) {
                    null -> return null
                    is Parsed.Wildcard -> Unit
                    is Parsed.Single -> comparators.add(parsed.comparator)
                }
            }
            alternatives.add(Alternative(comparators))
        }
        return alternatives
    }

    /** 一个比较子的解析结果。通配与"读不懂"必须分开：前者放行，后者作废整条区间。 */
    private sealed class Parsed {
        object Wildcard : Parsed()
        data class Single(val comparator: Comparator) : Parsed()
    }

    private fun parseComparator(part: String): Parsed? {
        val token = part.trim()
        if (token.isEmpty() || token == "*" || token == "x" || token == "X") return Parsed.Wildcard
        val match = Regex("""^(\^|~|>=|<=|>|<|=)?(.*)$""").find(token) ?: return null
        val op = match.groupValues[1]
        val target = match.groupValues[2].trim()
        // 区间里的比较子用严格解析：缺位的写法（`1`、`1.0`）在 npm 里是区间
        // 而不是精确版本，当成精确版本会给出偏"不兼容"的错误结论。
        val parsed = parseStrict(target) ?: return null
        val kind = when (op) {
            "", "=" -> Op.EXACT
            ">=" -> Op.GTE
            "<=" -> Op.LTE
            ">" -> Op.GT
            "<" -> Op.LT
            "^" -> Op.CARET
            "~" -> Op.TILDE
            else -> return null
        }
        return Parsed.Single(Comparator(kind, parsed))
    }
}

/** 一条对宿主版本的声明。 */
data class HostRequirement(
    /** [Kind.ENGINE] = `engines.dsh`；[Kind.PEER] = 某个 `@deepseek-ai/dsh*` 的 peer 区间。 */
    val kind: Kind,
    val range: String,
    /** peer 声明的包名；`engines.dsh` 为 null。 */
    val packageName: String? = null,
) {
    enum class Kind { ENGINE, PEER }
}

/**
 * 由"声明集合 + 当前宿主版本"推出兼容性结论。
 *
 * ## 三条纪律
 *
 * 1. **取不到信息一律 UNKNOWN**。没有声明是 `UNDECLARED`，宿主版本读不出来是
 *    `MANIFEST`，包清单不可读是 `UNAVAILABLE`。绝不因为"不知道"而判不兼容。
 * 2. **只有声明确实表明不满足才判 INCOMPATIBLE**。区间读不懂时那一项记为未知，
 *    不能把未知当不满足。
 * 3. **`^`/`~` 推算出来的上界不是宿主的天花板**。生态里大量插件写着 `^0.0.1`，
 *    它的上界从来不是"不能在更新的宿主上跑"的意思，所以"高于隐式上界"不判
 *    不兼容；而显式的 `<=` / `<` / 精确版本越界是真的越界。
 */
object HostCompatJudge {

    /**
     * @param requirements null 表示**取不到**这个包的声明（清单不存在或读不出来），
     *   结论落在 `UNKNOWN / UNAVAILABLE`。空列表表示"清单读到了、但里面没有声明"，
     *   结论是 `UNKNOWN / UNDECLARED`。两者必须分开：把"我们没读到"说成
     *   "插件未声明宿主要求"是把我们自己的问题算在插件头上。
     */
    fun judge(requirements: List<HostRequirement>?, hostVersion: String?): HostCompatibility {
        if (requirements == null) {
            return HostCompatibility.unknown
        }
        if (requirements.isEmpty()) {
            return HostCompatibility(
                status = HostCompatibility.Status.UNKNOWN,
                basis = HostCompatibility.Basis.UNDECLARED,
            )
        }
        val requirement = requirements.map { it.range }.distinct()
            .joinToString(" ∩ ")
            .ifEmpty { null }
        if (hostVersion.isNullOrBlank()) {
            return HostCompatibility(
                status = HostCompatibility.Status.UNKNOWN,
                basis = HostCompatibility.Basis.MANIFEST,
                requirement = requirement,
            )
        }
        val outcomes = requirements.map { evaluate(it, hostVersion) }
        val status = when {
            outcomes.any { it == false } -> HostCompatibility.Status.INCOMPATIBLE
            outcomes.all { it == true } -> HostCompatibility.Status.COMPATIBLE
            else -> HostCompatibility.Status.UNKNOWN
        }
        return HostCompatibility(
            status = status,
            basis = HostCompatibility.Basis.MANIFEST,
            requirement = requirement,
        )
    }

    private fun evaluate(requirement: HostRequirement, hostVersion: String): Boolean? {
        val verdict = Semver.evaluate(hostVersion, requirement.range) ?: return null
        if (verdict.satisfied) return true
        // `engines.dsh` 是显式的宿主要求，按字面判定。
        if (requirement.kind == HostRequirement.Kind.ENGINE) return false
        return when (verdict.direction) {
            Semver.Direction.BELOW_MIN -> false
            // 高于隐式上界（`^`/`~` 推出来的）不算不满足；显式上界越界才算。
            Semver.Direction.ABOVE_MAX -> !verdict.explicitCeiling
            Semver.Direction.UNKNOWN -> null
        }
    }
}

/**
 * 宿主兼容性判定。
 *
 * ## 版本从哪来
 *
 * DSH 运行时按层落在 `runtime-current/{base,node,dsh}` 下。**真正的产品版本**
 * 在 `runtime-current/dsh/node_modules/@deepseek-ai/dsh/package.json`（`dsh` 层
 * 根部的 `package.json` 只是构建桩，见 sandbox-manager 的 `DshLayer`）；另一种
 * 打包方式把整棵依赖树放在 `runtime-current/node_modules` 下；profile 自己的
 * `node_modules` 里也可能有一份。三处按优先级探测，取第一个能读出 `version`
 * 的。都读不到就返回 null——那意味着"宿主版本未知"，所有判定随之降级为
 * UNKNOWN，而不是编一个版本号。
 *
 * 读文本一律**先剥 UTF-8 BOM**：随包的桩清单带 BOM（EF BB BF），
 * `org.json` 遇到 BOM 会直接抛异常，于是"宿主版本未知 → 兼容性恒 UNKNOWN"。
 * sandbox-manager 踩过同一个坑（离线导入被记成版本 "unknown"）。
 *
 * ## 为什么不需要联网
 *
 * 判定只需要插件**本机已安装清单**里声明的区间，与宿主版本比对，都是本地
 * 文件。上游在浏览期还要为未安装的条目去 npm 查 manifest，那属于另一件事
 * （浏览期过滤），不在本版本范围内。
 *
 * ## 文案语言
 *
 * 本文件的说明串是"中文 / English"双语，`localizeBilingual` 按界面语言择半；
 * **同一时刻只支持 zh / en**，ar / es / fr / ru 回退英文。
 */
class HostCompatChecker(
    private val filesDir: File,
    private val nodeModulesDir: File,
    private val manifestFor: (String) -> File?,
) {

    /** 生产路径：全部取自 [PluginPaths]；参数化构造是为了让本类能在 JVM 单测里跑。 */
    constructor(paths: PluginPaths) : this(
        filesDir = paths.filesDir,
        nodeModulesDir = paths.nodeModulesDir,
        manifestFor = { paths.installedManifest(it) },
    )

    /** 当前宿主版本；读不到返回 null。 */
    fun hostVersion(): String? {
        for (candidate in hostVersionCandidates(filesDir, nodeModulesDir)) {
            val version = readHostVersion(candidate)
            if (version != null) return version
        }
        return null
    }

    /**
     * 读一个已装包声明的宿主要求。
     *
     * @return null 表示**取不到**（清单文件不存在或读不出来）。空列表表示
     *   清单读到了、里面确实没有宿主要求。两者的结论不同（UNAVAILABLE vs
     *   UNDECLARED），所以不能都折叠成空列表。
     */
    fun requirementsFor(name: String): List<HostRequirement>? {
        val manifest = manifestFor(name) ?: return null
        val text = runCatching { manifest.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return runCatching { parseHostRequirements(text) }.getOrNull()
    }

    /** 逐包判定；单个包读不出来只会得到 UNKNOWN，不影响其它包。 */
    fun check(names: List<String>): Map<String, HostCompatibility> {
        val host = hostVersion()
        val out = LinkedHashMap<String, HostCompatibility>(names.size)
        for (name in names) {
            out[name] = runCatching {
                HostCompatJudge.judge(requirementsFor(name), host)
            }.getOrDefault(HostCompatibility.unknown)
        }
        return out
    }
}

/**
 * 宿主版本的候选清单，**按可信度排序**。
 *
 * 1. `dsh` 层里 `node_modules/@deepseek-ai/dsh/package.json` 才是产品包本身；
 * 2. app 的**权威纯文本记录** `runtime/runtime-current/dsh/.dshbox/version`
 *    （只写版本号，读起来最便宜也最不会受清单格式影响）；
 * 3. `runtime-current/node_modules` 是另一种打包方式；
 * 4. profile 的 `node_modules` 是第三处。
 *
 * 刻意**不再**把 `dsh` 层根部的 `package.json` 当候选：它只是构建桩
 * （`name = "dsh-layer"`），必被 [readHostVersion] 的名字校验拒绝，留着是死代码。
 */
internal fun hostVersionCandidates(filesDir: File, nodeModulesDir: File): List<File> = listOf(
    File(filesDir, "runtime/runtime-current/dsh/node_modules/$HOST_PACKAGE/package.json"),
    File(filesDir, "runtime/runtime-current/dsh/.dshbox/version"),
    File(filesDir, "runtime/runtime-current/node_modules/$HOST_PACKAGE/package.json"),
    File(nodeModulesDir, "$HOST_PACKAGE/package.json"),
)

private const val HOST_PACKAGE = "@deepseek-ai/dsh"

/**
 * 读一个清单文件（或 app 的纯文本版本记录）里的 `version`。
 *
 * 两种格式都认：
 * - 清单 JSON：BOM 必须先剥（随包的桩清单带 BOM，`JSONObject` 会直接抛异常，
 *   读失败会被上层当成"宿主版本未知"）；有 `name` 时必须是宿主包本身。
 * - 纯文本：`runtime-current/dsh/.dshbox/version` 只写一行版本号。只接受能解析
 *   成 semver 的内容，避免把任意文本当版本号。
 */
internal fun readHostVersion(file: File): String? {
    if (!file.isFile) return null
    return runCatching {
        val text = file.readText(Charsets.UTF_8).trimStart('\uFEFF').trim()
        if (text.isEmpty()) return@runCatching null
        if (!text.startsWith("{")) {
            // 纯文本版本记录：只认形如版本号的内容。
            return@runCatching text.takeIf { Semver.parse(it) != null }
        }
        val obj = JSONObject(text)
        val name = obj.optString("name")
        // 有 name 时必须是宿主包本身，避免从别的包上读到版本。
        if (name.isNotBlank() && name != HOST_PACKAGE) return@runCatching null
        obj.optString("version").ifBlank { null }
    }.getOrNull()
}

/** 从清单正文里解析宿主要求。正文不是合法 JSON 时抛异常，由调用方转成 null。 */
internal fun parseHostRequirements(manifestText: String): List<HostRequirement> {
    val obj = JSONObject(manifestText.trimStart('\uFEFF'))
    val out = mutableListOf<HostRequirement>()
    val enginesRange = obj.optJSONObject("engines")?.optString("dsh")?.ifBlank { null }
    // 两种写法生态里都在用；顶层声明优先。
    val nestedRange = obj.optJSONObject("dsh")
        ?.optJSONObject("engines")
        ?.optString("dsh")
        ?.ifBlank { null }
    (enginesRange ?: nestedRange)?.let {
        out.add(HostRequirement(HostRequirement.Kind.ENGINE, it))
    }
    obj.optJSONObject("peerDependencies")?.let { peers ->
        for (key in peers.keys()) {
            if (!HOST_PEER_RE.containsMatchIn(key)) continue
            val range = peers.optString(key).ifBlank { null } ?: continue
            out.add(HostRequirement(HostRequirement.Kind.PEER, range, key))
        }
    }
    return out
}

/** 宿主包命名空间下、属于同一条发布线的 peer 声明。 */
private val HOST_PEER_RE = Regex("""^@deepseek-ai/dsh(?:-|$)""")
