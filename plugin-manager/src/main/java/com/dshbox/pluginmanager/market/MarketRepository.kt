package com.dshbox.pluginmanager.market

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.pluginmanager.market.data.CatalogSource
import com.dshbox.pluginmanager.market.model.HostCompatibility
import com.dshbox.pluginmanager.market.model.InstalledPlugin
import com.dshbox.pluginmanager.market.model.Registry
import com.dshbox.pluginmanager.market.model.RegistryFailure
import com.dshbox.pluginmanager.market.model.UpdateStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * 市场数据层的对外契约。
 *
 * 上游是一个挂在 DSH webServer 上的 HTTP 服务，有 41 条路由；我们在 app 内
 * 不需要跨进程边界，所以把那些路由塌缩成这组方法，由 UI 直接调用。
 *
 * 实现方（数据层）负责：文件读写、guest 命令、网络请求与各自的降级策略；
 * 调用方（UI）只关心结果与失败文案。
 */
interface MarketRepository {

    /** 目录快照；[force] 为 true 时忽略内存副本重新拉取。 */
    suspend fun loadRegistry(force: Boolean = false): AppResult<Registry>

    /** 最近一次目录失败的原因（成功后被清空）。 */
    val registryFailure: StateFlow<RegistryFailure?>

    /** 当前数据源。进入市场时先 [loadCatalogSource] 读一次盘。 */
    val catalogSource: StateFlow<CatalogSource>

    /**
     * 读一次盘上的数据源选择，并把它的地址应用到取数点。
     *
     * 进入市场的加载流程要**先**调它、再取目录：否则首次加载会用默认源去拉，
     * 用户选了非默认源时会白拉一次、界面还会闪一下别人的内容。
     */
    suspend fun loadCatalogSource()

    /**
     * 切换数据源。
     *
     * 实现要求：
     * - 落盘（存不住只如实提示，**不阻断**切换本身）；
     * - **立刻丢弃上一个源的目录内存副本**，否则下一次加载会把旧目录当当前目录；
     * - 整份换掉 —— 不做降级、不混源。
     */
    suspend fun setCatalogSource(source: CatalogSource): AppResult<Unit>

    /** 已安装插件（含激活状态与备注）。 */
    suspend fun loadInstalled(): AppResult<List<InstalledPlugin>>

    /** 逐插件更新检测；单个插件失败不影响其它插件。 */
    suspend fun checkUpdates(): AppResult<Map<String, UpdateStatus>>

    /**
     * 同 [checkUpdates]，但可以要求**绕过进程内缓存**。
     *
     * 用户显式点"刷新"时传 true：缓存是为了省掉反复的网络查询，不是为了让
     * 用户点了刷新还看到旧结论。带默认实现是为了让既有实现（含测试替身）
     * 不必改动——它们退化成"没有缓存"的 [checkUpdates]。
     */
    suspend fun checkUpdates(force: Boolean): AppResult<Map<String, UpdateStatus>> = checkUpdates()

    /** 宿主兼容性（浏览期按需查询，失败一律返回 UNKNOWN）。 */
    suspend fun hostCompatibility(names: List<String>): Map<String, HostCompatibility>

    /**
     * 启停一个插件。
     *
     * 实现要求（与上游一致，不得放宽）：
     * - 停用写进**我们自己的层**，不动用户的补丁层；
     * - **启用失败不得翻持久层**（否则会变成每次启动的崩溃循环）；
     * - 宿主基础设施链（timer/hmr/webserver/storage）拒绝开关；
     * - bundle 类条目必要时同步 `dsh.profile.bundles`。
     */
    suspend fun setEnabled(name: String, enabled: Boolean): AppResult<Unit>

    /**
     * 安装一个目录条目；[onLine] 实时回传进度行。
     *
     * 实现**必须**在命令成功后做装后校验：目标包目录存在 + `package.json`
     * 可读 + 有 `dsh` 字段（bundle / client 至少一个）。不满足时执行 `remove`
     * 回收并返回失败。命令行退出码为 0 不等于"这个包能用"。
     */
    suspend fun install(
        pluginName: String,
        installTarget: String,
        onLine: (String) -> Unit = {},
    ): AppResult<Unit>

    /** 卸载一个插件。 */
    suspend fun uninstall(name: String, onLine: (String) -> Unit = {}): AppResult<Unit>

    /**
     * 更新一个插件到最新。
     *
     * 实现**必须**用真实 npm 包名（`npm:` 别名要脱掉），且对非 npm 来源
     * （`link:` / `file:` / `github:` / `generation`）直接返回失败。
     */
    suspend fun update(name: String, onLine: (String) -> Unit = {}): AppResult<Unit>

    /** 写入/清除我写的备注（空串等于清除）。 */
    suspend fun setNote(name: String, note: String): AppResult<Unit>

    /**
     * 备份并重建损坏的市场状态文件。
     *
     * 状态文件读不出来时，[setNote] / [setEnabled] 的写入会被**拒绝**（避免把
     * 桌面端写进去的字段整块抹掉），于是开关与备注永久无法保存。实现应把原文件
     * 改名成 `state.json.broken-<时间戳>` 留档，再写一份只含 `disabled` /
     * `notes` 的新文件。
     *
     * 默认实现**明确失败**（绝不静默成功），兼容尚未迁移的实现与测试替身。
     */
    suspend fun rebuildState(): AppResult<Unit> = AppResult.Failure(
        AppError("REBUILD_UNSUPPORTED", "rebuilding the market state file is not supported"),
    )
}
