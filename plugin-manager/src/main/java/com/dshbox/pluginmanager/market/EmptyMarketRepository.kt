package com.dshbox.pluginmanager.market

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.pluginmanager.market.data.CatalogSource
import com.dshbox.pluginmanager.market.model.HostCompatibility
import com.dshbox.pluginmanager.market.model.InstalledPlugin
import com.dshbox.pluginmanager.market.model.Registry
import com.dshbox.pluginmanager.market.model.RegistryFailure
import com.dshbox.pluginmanager.market.model.UpdateStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 市场数据层的空实现：**每个写操作都明确失败**，读操作返回空。
 *
 * 存在的意义有两个：一是模块的界面在数据层未就绪时依然能编译与展示（界面据此
 * 提示"市场不可用"，而不是显示假数据）；二是单测与预览可以用它当替身。
 * 它**不会**伪装成功——这一点很重要：装插件是有副作用的操作，
 * 假装成功比明确失败危险得多。
 */
class EmptyMarketRepository : MarketRepository {

    private val _failure = MutableStateFlow<RegistryFailure?>(null)
    override val registryFailure: StateFlow<RegistryFailure?> = _failure

    // 数据源是**读**的语义（不是写副作用），所以不跟着 unavailable()：
    // 界面仍能显示"当前用哪个源"，只是切换会明确失败。
    private val _catalogSource = MutableStateFlow(CatalogSource.DEFAULT)
    override val catalogSource: StateFlow<CatalogSource> = _catalogSource

    override suspend fun loadCatalogSource() = Unit

    override suspend fun setCatalogSource(source: CatalogSource): AppResult<Unit> = unavailable()

    override suspend fun loadRegistry(force: Boolean): AppResult<Registry> = unavailable()

    override suspend fun loadInstalled(): AppResult<List<InstalledPlugin>> = AppResult.Success(emptyList())

    override suspend fun checkUpdates(): AppResult<Map<String, UpdateStatus>> = AppResult.Success(emptyMap())

    override suspend fun hostCompatibility(names: List<String>): Map<String, HostCompatibility> =
        names.associateWith { HostCompatibility.unknown }

    override suspend fun setEnabled(name: String, enabled: Boolean): AppResult<Unit> = unavailable()

    override suspend fun install(
        pluginName: String,
        installTarget: String,
        onLine: (String) -> Unit,
    ): AppResult<Unit> = unavailable()

    override suspend fun uninstall(name: String, onLine: (String) -> Unit): AppResult<Unit> = unavailable()

    override suspend fun update(name: String, onLine: (String) -> Unit): AppResult<Unit> = unavailable()

    override suspend fun setNote(name: String, note: String): AppResult<Unit> = unavailable()

    override suspend fun rebuildState(): AppResult<Unit> = unavailable()

    private fun <T> unavailable(): AppResult<T> = AppResult.Failure(
        AppError(code = "MARKET_UNAVAILABLE", message = "市场数据层尚未接入"),
    )
}
