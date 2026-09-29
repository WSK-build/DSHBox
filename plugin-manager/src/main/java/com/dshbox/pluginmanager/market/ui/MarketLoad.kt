package com.dshbox.pluginmanager.market.ui

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.pluginmanager.market.MarketRepository
import com.dshbox.pluginmanager.market.model.InstalledPlugin
import com.dshbox.pluginmanager.market.model.Registry
import com.dshbox.pluginmanager.market.model.RegistryFailure
import com.dshbox.pluginmanager.market.model.UpdateStatus

/**
 * 一次页面加载的结果。
 *
 * ## 为什么失败要带出来而不是折叠成空
 *
 * 三个加载项都可能失败，而**失败与"确实没有"在界面上完全不同**：
 * `loadInstalled` 失败时如果折叠成空列表，界面会显示"尚未安装社区插件"——
 * 用户看到的是"我的插件全没了"，而真实原因是 profile 清单读不出来。
 * `checkUpdates` 失败折叠成空 map 也一样：每一行会掉进"已是最新"的兜底分支。
 * 所以这里把失败原样带出来，由界面渲染原因 + 重试。
 *
 * 这个函数刻意不依赖 Compose，也不依赖 `android.content.Context`：它是
 * 页面数据流里唯一有分支判断的一段，放在这里就能用假 [MarketRepository]
 * 直接单测。
 */
internal data class MarketLoadSnapshot(
    /** 目录快照；失败时为 null（调用方保留上一份，不要清空用户正在看的列表）。 */
    val registry: Registry?,
    /** 最近一次目录失败（成功时为 null）。 */
    val registryFailure: RegistryFailure?,
    /** 已装插件；**读失败时为空列表**，此时必须看 [installedFailure] 才知道是
     *  "真的没装" 还是 "读不出来"。 */
    val installed: List<InstalledPlugin>,
    /** 已装清单读不出来的原因；null 表示读成功了。 */
    val installedFailure: AppError?,
    val updates: Map<String, UpdateStatus>,
    /** 更新检测失败的原因；null 表示检测成功。 */
    val updatesFailure: AppError?,
)

/**
 * 拉一次页面需要的三份数据。
 *
 * @param force 显式刷新：目录绕过内存副本、更新检测绕过 TTL 缓存。本地操作
 *   （启停、备注）必须传 false，否则每拨一次开关都要重下几 MB 的目录并重新
 *   查一遍 npm。
 * @param reuseUpdates 纯本地重载（不可能改变任何插件版本的操作用，如启停、
 *   写备注）时传入上一份更新结果：**跳过更新检测**，直接复用。更新检测即使
 *   有缓存也要逐个读本地版本、逐行重算，还会在查询失败时留下"检测中"的空窗；
 *   这些本地操作根本不会改变版本，重算没有意义。装/卸/更新结束后的重载**不该**
 *   走这条路：那时版本可能真的变了，复用会显示过期的"有更新"。
 */
internal suspend fun loadMarket(
    repository: MarketRepository,
    force: Boolean,
    reuseUpdates: Map<String, UpdateStatus>? = null,
): MarketLoadSnapshot {
    val registryResult = repository.loadRegistry(force = force)
    val installedResult = repository.loadInstalled()
    val reused = reuseUpdates?.takeIf { !force }
    val updatesResult = if (reused == null) repository.checkUpdates(force = force) else null
    return MarketLoadSnapshot(
        registry = (registryResult as? AppResult.Success)?.value,
        registryFailure = repository.registryFailure.value,
        installed = (installedResult as? AppResult.Success)?.value.orEmpty(),
        installedFailure = (installedResult as? AppResult.Failure)?.error,
        updates = reused ?: (updatesResult as? AppResult.Success)?.value.orEmpty(),
        updatesFailure = (updatesResult as? AppResult.Failure)?.error,
    )
}

/**
 * 「已安装」列表**正文**要显示成什么。
 *
 * 抽成纯函数是因为这里有一条**必须钉住**的规则：profile 读不出来时显示
 * "读不出来 + 重试"，**不是**"尚未安装社区插件"。后者会让用户以为插件没了。
 * （失败横幅由 `loadFailure != null` 单独驱动，与正文分支互不干扰：手里还有
 * 上一份列表时，横幅 + 旧列表一起显示。）
 */
internal enum class InstalledListState { LOADING, FAILED, EMPTY, CONTENT }

internal fun installedListState(
    loading: Boolean,
    /** 已装清单是否读失败。 */
    failed: Boolean,
    count: Int,
): InstalledListState = when {
    // 手里有数据就先显示它（横幅另行说明刷新失败）。
    count > 0 -> InstalledListState.CONTENT
    // 没有数据又读失败：只能说"读不出来"，绝不能说"没有插件"。
    failed -> InstalledListState.FAILED
    loading -> InstalledListState.LOADING
    else -> InstalledListState.EMPTY
}

/**
 * 「没检测」的原因归类。
 *
 * `checked == false` 有两种成因，必须分开说：**该来源不支持**（`link:` /
 * `file:` / `github:` 没有"最新版"）与**查询失败**（npm 查不到、本地版本读不
 * 出来）。把前者说成后者会让用户一直点重试，把后者说成前者会让用户以为
 * 永远没救。UI 按这个枚举选文案。
 */
internal enum class UpdateCheckGap { SOURCE_UNSUPPORTED, QUERY_FAILED }

internal fun updateCheckGap(kind: String?): UpdateCheckGap =
    if (kind == null || kind == "npm") UpdateCheckGap.QUERY_FAILED else UpdateCheckGap.SOURCE_UNSUPPORTED

/**
 * 本次加载要不要绕过缓存（目录内存副本 + 更新检测 TTL）。
 *
 * 判据是"**refreshKey 相对上一次加载变化了**"，而不是"refreshKey > 0"。
 * 后者会在用户点过一次刷新之后，让此后每一次本地重载（拨开关、写备注、
 * 装完回读）都重新联网——把"每次操作都重下目录"这个 bug 从 reloadKey 挪到
 * refreshKey 上而已。
 */
internal fun forceForLoad(refreshKey: Int, appliedRefreshKey: Int): Boolean =
    refreshKey != appliedRefreshKey

/**
 * 列表项 key。
 *
 * 直接用显示身份（`displayName`）：解析层已按显示身份去重（`npm` 名，否则
 * `owner/name`，且都 trim 过），所以目录里不可能出现两条同 `displayName` 的
 * 条目——这正是它敢当 key 的前提。**不再带下标**：带下标会让同一逻辑条目在
 * 列表顺序变化时拿到不同的 key，Compose 因此丢掉/重建整行状态（滚动位置、
 * 展开状态）。
 *
 * 已装清单的 key 同理用包名（`InstalledPlugin.name`，来自 profile 的依赖键，
 * 本身就是唯一的）。
 */
internal fun registryItemKey(displayName: String): String = displayName
