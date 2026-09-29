package com.dshbox.pluginmanager.market.ui

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.pluginmanager.market.MarketRepository
import com.dshbox.pluginmanager.market.data.CatalogSource
import com.dshbox.pluginmanager.market.model.HostCompatibility
import com.dshbox.pluginmanager.market.model.InstalledPlugin
import com.dshbox.pluginmanager.market.model.Registry
import com.dshbox.pluginmanager.market.model.RegistryFailure
import com.dshbox.pluginmanager.market.model.RegistryPlugin
import com.dshbox.pluginmanager.market.model.UpdateStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面数据流。
 *
 * 这里钉的是**界面上的三种"没有"必须分得开**：
 * 读不出来（Failure）、确实没有（空列表）、还在加载。原来
 * `.valueOrNull().orEmpty()` 把第一种折叠成第二种，于是 profile 清单读不出来
 * 时界面显示"尚未安装社区插件"——用户看到的是"我的插件全没了"。
 */
class MarketLoadTest {

    /** 可编程的假数据层：记录 force 参数，按需返回成功/失败。 */
    private class FakeRepository(
        private val registry: AppResult<Registry> = AppResult.Success(
            Registry(updated = "x", count = 0, categories = emptyMap(), plugins = emptyList()),
        ),
        private val installed: AppResult<List<InstalledPlugin>> = AppResult.Success(emptyList()),
        private val updates: AppResult<Map<String, UpdateStatus>> = AppResult.Success(emptyMap()),
        override val registryFailure: StateFlow<RegistryFailure?> = MutableStateFlow(null),
    ) : MarketRepository {

        val registryForce = mutableListOf<Boolean>()
        val updatesForce = mutableListOf<Boolean>()

        override suspend fun loadRegistry(force: Boolean): AppResult<Registry> {
            registryForce.add(force)
            return registry
        }

        override suspend fun loadInstalled(): AppResult<List<InstalledPlugin>> = installed

        // 数据源对本文件要钉的"三种没有"没有影响：给个默认源、读写都是空操作。
        override val catalogSource: StateFlow<CatalogSource> = MutableStateFlow(CatalogSource.DEFAULT)

        override suspend fun loadCatalogSource() = Unit

        override suspend fun setCatalogSource(source: CatalogSource): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun checkUpdates(): AppResult<Map<String, UpdateStatus>> = updates

        override suspend fun checkUpdates(force: Boolean): AppResult<Map<String, UpdateStatus>> {
            updatesForce.add(force)
            return updates
        }

        override suspend fun hostCompatibility(names: List<String>): Map<String, HostCompatibility> =
            names.associateWith { HostCompatibility.unknown }

        override suspend fun setEnabled(name: String, enabled: Boolean): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun install(
            pluginName: String,
            installTarget: String,
            onLine: (String) -> Unit,
        ): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun uninstall(name: String, onLine: (String) -> Unit): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun update(name: String, onLine: (String) -> Unit): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun setNote(name: String, note: String): AppResult<Unit> = AppResult.Success(Unit)
    }

    private val plugin = InstalledPlugin(
        name = "pkg",
        version = "1.0.0",
        spec = "^1.0.0",
        activation = com.dshbox.pluginmanager.market.model.ActivationState.RESTART,
    )

    @Test
    fun installedFailureIsPreservedInsteadOfBecomingAnEmptyList() = runBlocking {
        val error = AppError("PROFILE_MANIFEST_MISSING", "profile 清单不存在")
        val snapshot = loadMarket(
            FakeRepository(installed = AppResult.Failure(error)),
            force = false,
        )
        assertEquals("失败必须带出来", error, snapshot.installedFailure)
        assertTrue(snapshot.installed.isEmpty())
        // 界面据此显示"读不出来 + 重试"，而不是"尚未安装社区插件"。
        assertEquals(
            InstalledListState.FAILED,
            installedListState(loading = false, failed = snapshot.installedFailure != null, count = 0),
        )
    }

    @Test
    fun updatesFailureIsPreservedInsteadOfBecomingAnEmptyMap() = runBlocking {
        val error = AppError("PROFILE_MANIFEST_INVALID", "profile 清单不是合法 JSON")
        val snapshot = loadMarket(FakeRepository(updates = AppResult.Failure(error)), force = false)
        assertEquals(error, snapshot.updatesFailure)
        assertTrue(snapshot.updates.isEmpty())
    }

    @Test
    fun successfulLoadClearsTheFailures() = runBlocking {
        val snapshot = loadMarket(FakeRepository(installed = AppResult.Success(listOf(plugin))), force = false)
        assertNull(snapshot.installedFailure)
        assertNull(snapshot.updatesFailure)
        assertEquals(listOf(plugin), snapshot.installed)
    }

    @Test
    fun registryFailureIsSurfacedAndTheSnapshotIsNull() = runBlocking {
        val failure = RegistryFailure(message = "HTTP 503（2 秒，尝试 2 次） / HTTP 503", attempts = 2, elapsedMs = 2_000)
        val repository = FakeRepository(
            registry = AppResult.Failure(AppError("REGISTRY_UNAVAILABLE", failure.message)),
            registryFailure = MutableStateFlow(failure),
        )
        val snapshot = loadMarket(repository, force = true)
        assertNull("失败时不返回目录，调用方保留上一份快照", snapshot.registry)
        assertEquals(failure, snapshot.registryFailure)
    }

    @Test
    fun forceIsPassedOnlyWhenExplicitlyRequested() = runBlocking {
        val local = FakeRepository()
        loadMarket(local, force = false)
        assertEquals(listOf(false), local.registryForce)
        assertEquals(listOf(false), local.updatesForce)

        val explicit = FakeRepository()
        loadMarket(explicit, force = true)
        assertEquals(listOf(true), explicit.registryForce)
        assertEquals(listOf(true), explicit.updatesForce)
    }

    @Test
    fun forceIsDecidedByTheRefreshKeyChangingNotByBeingNonZero() {
        // 初始加载：都没动过 → 不强制。
        assertTrue(!forceForLoad(refreshKey = 0, appliedRefreshKey = 0))
        // 用户点了刷新 → 强制一次。
        assertTrue(forceForLoad(refreshKey = 1, appliedRefreshKey = 0))
        // 关键回归：刷新之后紧接着一次本地重载（拨开关/写备注）**不该**再联网，
        // 否则"每次操作都重下整份目录"这个 bug 只是换了触发条件。
        assertTrue(!forceForLoad(refreshKey = 1, appliedRefreshKey = 1))
        assertTrue(!forceForLoad(refreshKey = 1, appliedRefreshKey = 1))
    }

    @Test
    fun installedListStateNeverShowsEmptyWhenTheReadFailed() {
        assertEquals(InstalledListState.FAILED, installedListState(loading = false, failed = true, count = 0))
        assertEquals(InstalledListState.FAILED, installedListState(loading = true, failed = true, count = 0))
        assertEquals(InstalledListState.LOADING, installedListState(loading = true, failed = false, count = 0))
        assertEquals(InstalledListState.EMPTY, installedListState(loading = false, failed = false, count = 0))
        assertEquals(InstalledListState.CONTENT, installedListState(loading = false, failed = false, count = 3))
        // 有旧数据在手时也要继续显示列表（横幅另行说明刷新失败）。
        assertEquals(InstalledListState.CONTENT, installedListState(loading = false, failed = true, count = 3))
    }

    @Test
    fun updateCheckGapSeparatesUnsupportedSourcesFromFailedQueries() {
        assertEquals(UpdateCheckGap.QUERY_FAILED, updateCheckGap(null))
        assertEquals(UpdateCheckGap.QUERY_FAILED, updateCheckGap("npm"))
        assertEquals(UpdateCheckGap.SOURCE_UNSUPPORTED, updateCheckGap("linked"))
        assertEquals(UpdateCheckGap.SOURCE_UNSUPPORTED, updateCheckGap("file"))
        assertEquals(UpdateCheckGap.SOURCE_UNSUPPORTED, updateCheckGap("github"))
    }

    @Test
    fun uncheckedStatusNeverRendersAsUpToDate() {
        // 这一条把「没查到」与「已是最新」在 UI 决策层上分开：
        // 只有 checked 的 UpdateStatus 才允许走"已是最新"那一支。
        val unchecked = UpdateStatus(updateAvailable = false, checked = false, kind = "linked")
        val checked = UpdateStatus(updateAvailable = false, checked = true, kind = "npm")
        assertTrue(!unchecked.checked && !unchecked.updateAvailable)
        assertTrue(checked.checked && !checked.updateAvailable)
        assertEquals(UpdateCheckGap.SOURCE_UNSUPPORTED, updateCheckGap(unchecked.kind))
        assertEquals(UpdateCheckGap.QUERY_FAILED, updateCheckGap(checked.kind))
    }

    @Test
    fun registryItemKeyIsTheDisplayIdentity() {
        // 解析层已按显示身份去重，所以 displayName 本身就能当 key；带下标反而会
        // 在列表顺序变化时丢掉整行状态。
        val plugin = RegistryPlugin(
            name = "dup",
            owner = "owner",
            url = "https://example.com/dup",
            categories = listOf("tools"),
        )
        assertEquals("owner/dup", registryItemKey(plugin.displayName))
        val npmNamed = plugin.copy(npm = "dup-npm")
        assertEquals("dup-npm", registryItemKey(npmNamed.displayName))
        assertNotEquals(
            "不同显示身份的 key 必须不同",
            registryItemKey(plugin.displayName),
            registryItemKey(npmNamed.displayName),
        )
    }

    @Test
    fun localReloadReusesPreviousUpdatesWithoutRechecking() = runBlocking {
        // 拨开关 / 写备注不可能改变版本：复用上一份更新结果，不重跑检测，
        // 免得每次本地操作都卡在"更新检测中"。
        val previous = mapOf(
            "pkg" to UpdateStatus(
                updateAvailable = true,
                current = "1.0.0",
                latest = "2.0.0",
                kind = "npm",
                checked = true,
            ),
        )
        val repository = FakeRepository()
        val snapshot = loadMarket(repository, force = false, reuseUpdates = previous)
        assertEquals("必须原样复用上一份结果", previous, snapshot.updates)
        assertNull(snapshot.updatesFailure)
        assertTrue("复用路径不该调用 checkUpdates", repository.updatesForce.isEmpty())
        // 目录与已装清单仍然要重读（磁盘状态可能变了）。
        assertEquals(listOf(false), repository.registryForce)
    }

    @Test
    fun explicitRefreshIgnoresTheReuseHint() = runBlocking {
        val previous = mapOf("pkg" to UpdateStatus(updateAvailable = true, checked = true))
        val repository = FakeRepository()
        val snapshot = loadMarket(repository, force = true, reuseUpdates = previous)
        assertEquals("显式刷新必须真的重跑检测", listOf(true), repository.updatesForce)
        assertTrue(snapshot.updates.isEmpty())
    }
}
