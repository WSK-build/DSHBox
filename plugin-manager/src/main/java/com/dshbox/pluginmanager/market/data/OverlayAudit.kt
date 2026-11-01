package com.dshbox.pluginmanager.market.data

import com.dshbox.pluginmanager.contract.HostContract

/**
 * 停用覆盖层的**残留行审计**（纯函数，可脱离文件系统单测）。
 *
 * ## 什么是"残留行"
 *
 * 停用覆盖层（`cordis.patch.yml` 那一层）里的每一行都按 id 指向某个 loader 条目。
 * 条目被卸载后，指向它的停用行**不会自动消失**——它成了一条谁都不认领的孤儿行，
 * 而宿主每次启动都会为它产生一条告警（`patch: entry … not found`）。用户看不到
 * 任何症状，只有日志在长。这就是"残留行"。
 *
 * ## 认领规则
 *
 * 一行不算残留，当且仅当满足以下任一：
 * 1. **被某个已装包认领**：行 id 等于包名，或等于该包补丁文件里声明的条目 id；
 * 2. **是宿主 loader 合成条目**（`cordis:` 命名空间）——由宿主自己管理；
 * 3. **是我方随包资产**（`@local/`）——由装配链管理，不归市场清理。
 *
 * 第 2、3 条是**豁免**而非"认领"：它们即便不在任何包的声明里也不算残留。
 *
 * ## 抽成纯函数的原因
 *
 * 真实审计要读 profile 清单、要遍历每个包的补丁文件——都依赖 `android.content.Context`，
 * JVM 单测里跑不起来。而"哪些行是残留"这个**判定本身**与文件系统无关，只与
 * 「停用行集合 + 已装包集合 + 每包的行 id 解析结果」有关。抽出来后规则可被用例
 * 钉住；留在 `DefaultMarketRepository` 里只能靠整机验证。
 */
object OverlayAudit {

    /**
     * 从停用行里筛出**无人认领**的残留行。
     *
     * @param disabledRowIds 覆盖层里的全部停用行 id。接受 `Collection`：生产侧的
     *   [com.dshbox.pluginmanager.layer.PluginLayer.disabledIds] 返回 `Set`，其迭代
     *   顺序即文件顺序（LinkedHashSet），据此输出顺序稳定、便于界面稳定展示。
     * @param installedNames 已装包名集合：`dependencies` 的键 ∪ `bundles` 里的托管条目。
     * @param rowIdsOf 包名 → 该包在补丁层里拥有的条目 id 列表。
     *   解析不出来时返回空列表（调用方负责，见 [ProfileReader.rowIdsForPackage]）。
     * @return 残留行 id，顺序与 [disabledRowIds] 一致。
     */
    fun staleRows(
        disabledRowIds: Collection<String>,
        installedNames: Collection<String>,
        rowIdsOf: (String) -> List<String>,
    ): List<String> {
        val claimed = HashSet<String>()
        // 「已解析过的包名」必须与认领集分开：认领集里也装着行 id，若用它判重，
        // 某个包声明的行 id 恰好等于另一个包名时，后者会被跳过、它自己的行 id
        // 进不了认领集，于是被误判成残留。
        val resolved = HashSet<String>()
        for (name in installedNames) {
            if (name.isBlank()) continue
            claimed += name
            if (!resolved.add(name)) continue
            claimed += rowIdsOf(name)
        }
        // 子条目 id 形如 `<包名>:<后缀>` 或 `<包名>/<后缀>`，由该包的条目派生但不会
        // 出现在它的补丁文件里。按前缀认领：多认只会多留一条启动告警，少认会让
        // 一键清理删掉真实生效的停用行、插件被复活。
        return disabledRowIds.filterNot { rowId ->
            rowId in claimed ||
                claimed.any { owner -> rowId.startsWith("$owner:") || rowId.startsWith("$owner/") } ||
                HostContract.isLoaderEntry(rowId) ||
                HostContract.isLocalAsset(rowId)
        }
    }
}
