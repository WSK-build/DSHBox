package com.dshbox.pluginmanager.market.data

import com.dshbox.pluginmanager.contract.HostContract
import com.dshbox.pluginmanager.core.PluginPaths
import java.io.File
import org.json.JSONObject

/**
 * 宿主兼容门禁的**判定口径**。
 *
 * 宿主改过规则，两种口径对同一份清单可能给出相反结论：
 *
 * - [PEERS_ONLY]：只看 `peerDependencies` 里的 `@deepseek-ai/dsh*`；
 * - [ENGINES_FIRST]：`engines.dsh` **存在就它说了算**，peers 完全忽略。
 *
 * 因此「以哪一版为准」不能靠版本号写死——用户升级后那个阈值立刻过期。这里改为
 * 从**已装宿主的产物**推断（见 [HostGateJudge.modeFor]）。
 */
enum class GateMode {
    /** 只看 peers（较早的口径）。 */
    PEERS_ONLY,

    /** `engines.dsh` 存在则独占（较新的口径）。 */
    ENGINES_FIRST,
}

/**
 * 一次门禁判定的结果。
 *
 * @property incompatible 宿主**会在启动期拒绝**这个条目（不是"可能有问题"）。
 * @property requirement 触发拒绝的那条声明，形如 `engines.dsh: ^0.1.0`。
 * @property basis 结论依据，UI 与日志用它解释判定来源。
 */
data class GateVerdict(
    val incompatible: Boolean,
    val requirement: String? = null,
    val basis: Basis,
) {
    enum class Basis {
        /** 读到了声明并完成判定。 */
        MANIFEST,

        /** 用户已就该确切版本授予豁免，宿主不会拒绝。 */
        EXEMPTED,

        /** 清单或宿主版本读不到，**不判**（绝不把不知道说成不兼容）。 */
        UNAVAILABLE,

        /** 清单里没有任何 dsh 声明，门禁不会拦它。 */
        UNDECLARED,
    }

    companion object {
        fun clear(basis: Basis): GateVerdict = GateVerdict(incompatible = false, basis = basis)

        fun unmet(requirement: String): GateVerdict =
            GateVerdict(incompatible = true, requirement = requirement, basis = Basis.MANIFEST)

        fun unknown(): GateVerdict = GateVerdict(incompatible = false, basis = Basis.UNAVAILABLE)
    }
}

/**
 * 复刻宿主**兼容门禁**的严格判定。
 *
 * ## 与浏览期判定的分工
 *
 * [HostCompatJudge] 服务**浏览期提示**，刻意放宽两处（`^0.0.1` 的上界不算天花板、
 * 高于隐式上界不算不兼容）。本层服务"宿主是否会在启动期拒绝"，要求逐字复刻：
 * 差一个 `-0` 就会把随包的那组声明判成满足。
 *
 * ## 与上游 `hostPeerGate` 的对齐点
 *
 * 1. `engines.dsh` 存在时**独占判定**，peers 忽略；
 * 2. 上界一律按 `<ceil-0`（见 [Semver.satisfiesStrict]）；
 * 3. 预发布参与区间（本模块比较器不排除预发布）；
 * 4. `compatibility.json` 的 `name@exactVersion → [runtimeVersion]` 命中则**不判**；
 * 5. 空区间 = 不满足（不是"未声明"）；
 * 6. peer 的 `workspace:^` / `workspace:~` / `workspace:*` 视作当前运行时版本；
 *    但 `engines.dsh` 上的 `workspace:` 不算满足（与宿主一致）；
 * 7. **`engines.dsh` 的区间读不懂时按不满足处理**（上游 `satisfied ? null : unmet`），
 *    而 peer 的区间读不懂时**跳过**（上游只在明确 `false` 时才算 unmet）。这条不对称
 *    是刻意复刻的：engines 是插件自己声明的硬要求，读不懂它不能当作没问题。
 *
 * ## 不判的情形（与实现一一对应）
 *
 * - 清单读不出、宿主版本读不到、判定口径读不出（[modeFor] 返回 null）→ `unknown`；
 * - 声明不是字符串 → `unknown`（宿主自己会抛错，那不是能预测的结论）；
 * - `engines.dsh` 区间读不懂 → 判不满足；peer 区间读不懂 → 跳过该条。
 */
object HostGateJudge {

    /** workspace 协议的三种写法：都指向「当前运行时版本」。仅对 peer 生效。 */
    private val WORKSPACE_RANGES = setOf("workspace:^", "workspace:~", "workspace:*")

    /**
     * 已装宿主 `dsh-app-boot` 的入口候选（语义探针读它）。
     *
     * 两种打包布局都真实存在：依赖树在 DSH 层内，或直接在运行层根。只探一种会
     * 让"口径读不出"从异常路径变成常态。
     */
    fun appBootEntriesOf(runtimeCurrentDir: File): List<File> = listOf(
        File(
            runtimeCurrentDir,
            "${HostContract.DSH_LAYER}/${HostContract.NODE_MODULES}/${HostContract.APP_BOOT_ENTRY}",
        ),
        File(runtimeCurrentDir, "${HostContract.NODE_MODULES}/${HostContract.APP_BOOT_ENTRY}"),
    )

    /** 同上，路径取自 [PluginPaths]。 */
    fun appBootEntriesOf(paths: PluginPaths): List<File> = appBootEntriesOf(paths.runtimeCurrentDir)

    /**
     * 从 `dsh-app-boot` 的产物文本推断口径。
     *
     * **判据来源是产物，不是版本号**：版本阈值在宿主升级后立即过期；产物里的
     * 语义标记随上游发布走。标记消失即上游改了判定方式，此时回落到 peers 规则，
     * 不会锁死在某个版本。
     *
     * @param appBootEntryText 产物内容；`null` 表示读不出来 → 返回 null，判定随之
     *   落到 [GateVerdict.unknown]。**不猜口径**：猜错会拒掉可安装的插件。
     */
    fun modeFor(appBootEntryText: String?): GateMode? = when {
        appBootEntryText == null -> null
        appBootEntryText.contains(HostContract.APP_BOOT_ENGINES_GATE) -> GateMode.ENGINES_FIRST
        else -> GateMode.PEERS_ONLY
    }

    /** 读已装宿主的产物并给出口径；两个布局都读不到时返回 null（不猜）。 */
    fun detectMode(runtimeCurrentDir: File): GateMode? = modeFor(
        appBootEntriesOf(runtimeCurrentDir)
            .firstNotNullOfOrNull { file -> runCatching { file.readText(Charsets.UTF_8) }.getOrNull() },
    )

    /** 同上，路径取自 [PluginPaths]。 */
    fun detectMode(paths: PluginPaths): GateMode? = detectMode(paths.runtimeCurrentDir)

    /**
     * 读 profile 的 `compatibility.json` 豁免表。
     *
     * 结构：`{ "<name>@<exactVersion>": ["<runtimeVersion>", …] }`。
     * 文件缺失、读不出、解析失败一律返回空表——与宿主一致（坏文件不能让 profile 不可用）。
     */
    fun readExemptions(file: File?): Map<String, List<String>> {
        if (file == null || !file.isFile) return emptyMap()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyMap()
        val root = runCatching { JSONObject(text.trimStart('\uFEFF')) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, List<String>>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val array = root.optJSONArray(key) ?: continue
            val versions = (0 until array.length())
                .mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
            if (versions.isNotEmpty()) out[key] = versions
        }
        return out
    }

    /**
     * 判定一个包会不会被宿主门禁拒绝。
     *
     * @param manifestText 包自己的 `package.json` 文本；null = 读不出。
     * @param packageName 包名（构造豁免键 `name@version`）。
     * @param packageVersion 该包已装的**精确**版本；null 时无法匹配豁免。
     * @param hostVersion 当前运行时版本；null/空白时**不判**。
     * @param exemptions [readExemptions] 的结果。
     * @param mode [modeFor] / [detectMode] 给出的口径；null 表示口径读不出，此时**不判**。
     */
    fun judge(
        manifestText: String?,
        packageName: String,
        packageVersion: String?,
        hostVersion: String?,
        exemptions: Map<String, List<String>> = emptyMap(),
        mode: GateMode? = GateMode.ENGINES_FIRST,
    ): GateVerdict {
        if (manifestText == null) return GateVerdict.unknown()
        if (hostVersion.isNullOrBlank()) return GateVerdict.unknown()
        // 口径读不出即不判：两种口径可能给出相反结论，猜错会拒掉可安装的插件。
        if (mode == null) return GateVerdict.unknown()

        if (packageVersion != null) {
            val key = "$packageName@$packageVersion"
            if (exemptions[key]?.contains(hostVersion) == true) {
                return GateVerdict.clear(GateVerdict.Basis.EXEMPTED)
            }
        }

        val manifest = runCatching { JSONObject(manifestText.trimStart('\uFEFF')) }.getOrNull()
            ?: return GateVerdict.unknown()

        val enginesLabel = "${HostContract.FIELD_ENGINES}.${HostContract.FIELD_DSH}"

        if (mode == GateMode.ENGINES_FIRST) {
            val engines = manifest.optJSONObject(HostContract.FIELD_ENGINES)
            if (engines != null && engines.has(HostContract.FIELD_DSH)) {
                val raw = engines.opt(HostContract.FIELD_DSH)
                // 非字符串会让宿主抛错；不属于可预测的结论。
                if (raw !is String) return GateVerdict.unknown()
                val range = raw.trim()
                // 空区间在宿主侧是「不满足」；`workspace:` 在 engines 上不算满足。
                if (range.isEmpty()) return GateVerdict.unmet("$enginesLabel: <empty>")
                // ⚠️ 这里与 peers **刻意不对称**：上游对 engines 的口径是
                // `satisfied ? null : unmet`，所以 `satisfiesRange` 返回 null
                // （区间读不懂）时也落到 unmet —— engines 是插件自己声明的硬要求，
                // 读不懂它就不能当作"没问题"。peers 则只在明确 false 时才 unmet。
                return if (Semver.satisfiesStrict(hostVersion, range) == true) {
                    GateVerdict.clear(GateVerdict.Basis.MANIFEST)
                } else {
                    GateVerdict.unmet("$enginesLabel: $range")
                }
            }
        }

        val peers = manifest.optJSONObject(HostContract.FIELD_PEER_DEPENDENCIES)
            ?: return GateVerdict.clear(GateVerdict.Basis.UNDECLARED)

        var sawRequirement = false
        val keys = peers.keys()
        while (keys.hasNext()) {
            val peer = keys.next()
            if (!HostContract.HOST_PEER_RE.containsMatchIn(peer)) continue
            val raw = peers.opt(peer)
            // 非字符串会让宿主抛错，不预测该情形。
            if (raw !is String) continue
            val declared = raw.trim()
            // peer 上的 workspace 协议指「当前运行时」，因此视为满足。
            val range = if (declared in WORKSPACE_RANGES) hostVersion else declared
            if (range.isEmpty()) return GateVerdict.unmet("$peer: <empty>")
            sawRequirement = true
            val satisfied = Semver.satisfiesStrict(hostVersion, range) ?: continue
            if (!satisfied) return GateVerdict.unmet("$peer: $range")
        }
        return GateVerdict.clear(
            if (sawRequirement) GateVerdict.Basis.MANIFEST else GateVerdict.Basis.UNDECLARED,
        )
    }
}
