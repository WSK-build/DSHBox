package com.dshbox.pluginmanager.market.data

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.pluginmanager.core.GuestCommandRunner
import com.dshbox.pluginmanager.core.IsolationLedger
import com.dshbox.pluginmanager.core.PluginPaths
import com.dshbox.pluginmanager.layer.HostInfrastructure
import com.dshbox.pluginmanager.layer.PluginLayer
import com.dshbox.pluginmanager.market.MarketRepository
import com.dshbox.pluginmanager.market.model.HostCompatibility
import com.dshbox.pluginmanager.market.model.InstalledPlugin
import com.dshbox.pluginmanager.market.model.Registry
import com.dshbox.pluginmanager.market.model.RegistryFailure
import com.dshbox.pluginmanager.market.model.UpdateStatus
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 查一个 npm 包的最新发布版本；取不到返回 null（绝不猜）。 */
fun interface LatestVersionLookup {
    fun lookup(npmName: String): String?
}

/**
 * 直接问 npm registry 的 `dist-tags.latest`。
 *
 * 取不到就返回 null，调用方据此报"没有可判定的更新"——把查询失败说成
 * "已是最新"是这里最容易犯也最不该犯的错。
 *
 * 注：本实现直连 npmjs.org，没有接下载区域/镜像路由；在镜像可用的网络里
 * 查询会失败，表现为"检测不到更新"而不是错误结论。
 */
class NpmLatestVersionLookup(
    private val registryBase: String = "https://registry.npmjs.org",
    private val timeoutMs: Int = 8_000,
) : LatestVersionLookup {

    override fun lookup(npmName: String): String? = try {
        // 作用域包的斜杠必须编码，否则路径会被 registry 当成两级。
        val encoded = npmName.replace("/", "%2F")
        val conn = URL("$registryBase/$encoded/latest").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode !in 200..299) {
                null
            } else {
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                JSONObject(body).optString("version").ifBlank { null }
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Throwable) {
        null
    }
}

/**
 * 市场数据层的默认实现：把目录、profile 文件、市场状态、guest 命令与兼容性
 * 判定串起来，实现 [MarketRepository]。
 *
 * ## 与上游的差异（都写在明处，不假装完整）
 *
 * 1. **没有热挂载**。上游在会话内直接挂载/卸载插件、并据此报告"已生效"；
 *    我们只写持久层，**启用/停用一律下次启动生效**，界面如实说"重启后生效"。
 * 2. **entry id 靠补丁文件解析**（[PackageRowIds]，移植上游 `rowIdsForPackage`）。
 *    上游还会把当前 loader 里的条目并进来（进程内可见）；我们拿不到那个视角，
 *    所以只读"这个包自己 insert 的行"。解析不出来时**拒绝操作**，不用包名猜。
 * 3. **纯客户端插件（没有宿主条目的）目前不能停用**：本版本没有上游那套
 *    "进程内回放市场状态"的执行者，写层也打不中条目，因此如实拒绝而不是假装成功。
 * 4. **更新检测只对 npm 来源有效**。`link:`/`file:` 没有"最新版"；`github:`
 *    需要问 git ref，本版本没有这条通道。这三种情况在 [UpdateStatus.checked]
 *    上如实体现为"没查到"，界面显示"未检测"而不是"已是最新"。
 *
 * ## 文案语言
 *
 * 本文件里所有面向用户的失败说明都是"中文 / English"双语串，由 UI 侧的
 * `localizeBilingual` 按界面语言择半。**同一时刻只支持 zh / en 两种**：
 * ar / es / fr / ru 会回退到英文那一半。这不是"已完整本地化"——完整做法是
 * 把这些串搬进 `strings.xml`，本轮没做（改动面太大）。
 */
class DefaultMarketRepository(
    private val paths: PluginPaths,
    private val layer: PluginLayer,
    private val guest: GuestCommandRunner,
    private val registrySource: RegistrySource = RegistrySource(sources = CatalogSource.DEFAULT.addresses),
    // 数据源选择单独一个文件：共享的 state.json 字段归上游／桌面端所有。
    private val catalogSourceStore: CatalogSourceStore = CatalogSourceStore(paths.catalogSourceFile),
    private val profileReader: ProfileReader = ProfileReader(paths),
    private val stateStore: MarketStateStore = MarketStateStore(paths.marketStateFile),
    private val pluginOps: GuestPluginOps = GuestPluginOps(guest, paths.profile),
    private val compatChecker: HostCompatChecker = HostCompatChecker(paths),
    private val latestVersion: LatestVersionLookup = NpmLatestVersionLookup(),
    /**
     * 启停后与安全模式的隔离清单同步（见 [IsolationLedger]）。
     * 未装配时保持旧行为（只写层），用于尚未接线市场的场景。
     */
    private val isolationLedger: IsolationLedger? = null,
) : MarketRepository {

    private companion object {
        /** 市场自己的包名；它不能把自己关掉，否则这个功能就没了。 */
        val MARKET_SELF_NAMES = setOf("dshmarket", "dsh-market")
    }

    private val updates = UpdateDetector(latestVersion)

    /** 安装的「假成功守卫」：命令成功之后还要核对磁盘事实（见 [InstallGuard]）。 */
    private val installGuard = InstallGuard(pluginOps, profileReader, profileReader)

    override val registryFailure: StateFlow<RegistryFailure?> = registrySource.failure

    private val _catalogSource = MutableStateFlow(CatalogSource.DEFAULT)
    override val catalogSource: StateFlow<CatalogSource> = _catalogSource

    override suspend fun loadCatalogSource() {
        applyCatalogSource(catalogSourceStore.read())
    }

    override suspend fun setCatalogSource(source: CatalogSource): AppResult<Unit> {
        val stored = catalogSourceStore.write(source)
        // 落盘失败照样切：内存里已经换了源，只是下次启动会回到旧选择 ——
        // 比"存不住就拒绝用户操作"更合理，失败用一句提示如实说清。
        applyCatalogSource(source)
        return if (stored) {
            AppResult.Success(Unit)
        } else {
            AppResult.Failure(
                AppError(
                    code = "CATALOG_SOURCE_NOT_SAVED",
                    message = "数据源已切换，但这次选择没能存住 / switched, but the choice was not saved",
                ),
            )
        }
    }

    /**
     * 把选择应用到取数点。
     *
     * 丢掉上一个源的目录副本由 [RegistrySource.setSources] 内部负责 —— 这是刻意的：
     * 少了那一步，切换后下一次非强制加载仍会把旧源的目录当成当前目录交出去。
     */
    private fun applyCatalogSource(source: CatalogSource) {
        _catalogSource.value = source
        registrySource.setSources(source.addresses)
    }

    override suspend fun loadRegistry(force: Boolean): AppResult<Registry> = registrySource.load(force)

    override suspend fun loadInstalled(): AppResult<List<InstalledPlugin>> = withContext(Dispatchers.IO) {
        val manifest = when (val result = profileReader.readManifest()) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return@withContext result
        }
        val state = stateStore.read()
        // 层是唯一真相，而层里存的是**条目 id**、不是包名（`dshmarket` → `dsh-market`）。
        // 所以判定要把这个包自己的条目 id 解析出来对：拿包名去比层里的 id，
        // 会出现"层里写着 disabled、界面显示启用中"的反向错配。
        val layerDisabled = layer.disabledIds()
        val bundles = manifest.bundles.toSet()
        val plugins = manifest.dependencies.map { (name, spec) ->
            val facts = profileReader.facts(name, spec)
            val rowIds = profileReader.rowIdsForPackage(name)
            // 解析不出条目 id 的包（纯客户端插件等）：只看层里有没有同名行
            // （旧版本写过包名行的情况），否则按"启用中"显示——本版本不能停用它们，
            // 显示"已停用"会是假话。
            val off = if (rowIds.isEmpty()) name in layerDisabled else rowIds.any { it in layerDisabled }
            val verdict = ActivationClassifier.classify(
                facts = facts,
                inBundles = name in bundles,
                disabled = off,
            )
            InstalledPlugin(
                name = name,
                version = facts.version,
                spec = spec,
                activation = verdict.state,
                reasons = verdict.reasons,
                note = state.notes[name],
                thirdParty = isThirdPartyPackage(name, profileReader.hostPackages()),
            )
        }
        AppResult.Success(plugins)
    }

    override suspend fun checkUpdates(): AppResult<Map<String, UpdateStatus>> = checkUpdates(force = false)

    /**
     * 逐插件更新检测。
     *
     * @param force 绕过 [UpdateDetector] 的进程内 TTL 缓存（用户显式刷新时）。
     *   默认实现退化为不带参数的那一条，保持接口的向后兼容。
     */
    override suspend fun checkUpdates(force: Boolean): AppResult<Map<String, UpdateStatus>> =
        withContext(Dispatchers.IO) {
            val manifest = when (val result = profileReader.readManifest()) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> return@withContext result
            }
            // 本地版本逐个读（本地文件，便宜）；网络查询由 UpdateDetector 做
            // 有限并发 + TTL 缓存，串行几十次 npm 查询会让整页等十几秒。
            val versions = HashMap<String, String?>(manifest.dependencies.size)
            for (name in manifest.dependencies.keys) {
                versions[name] = runCatching { profileReader.facts(name, manifest.dependencies[name]).version }
                    .getOrNull()
            }
            AppResult.Success(
                updates.check(
                    dependencies = manifest.dependencies,
                    currentVersion = { versions[it] },
                    force = force,
                ),
            )
        }

    override suspend fun hostCompatibility(names: List<String>): Map<String, HostCompatibility> =
        withContext(Dispatchers.IO) { compatChecker.check(names) }

    override suspend fun setEnabled(name: String, enabled: Boolean): AppResult<Unit> =
        withContext(Dispatchers.IO) {
            if (name.isBlank()) {
                return@withContext failure("PLUGIN_NAME_REQUIRED", "插件名不能为空 / the plugin name is required")
            }
            if (isMarketItself(name)) {
                return@withContext failure(
                    "MARKET_SELF_PROTECTED",
                    "不能停用市场自己 / the market cannot disable itself",
                )
            }
            // **包名不是 loader 条目 id**：`dshmarket` 的条目 id 是 `dsh-market`、
            // `dsh-drop-caret` 是 `drop-caret`（用上游真源码在本机 dsh 上实测）。
            // 从前这里直接拿包名当 id 写层，写出来的是打不中的行——DSH 只打印
            // `patch: entry "X" not found` 然后照常加载，界面显示"已停用"而插件仍在跑。
            // 现在按上游 `rowIdsForPackage` 的语义解析这个包**自己 insert 的行**；
            // 解析不出来就拒绝，绝不猜。
            val rowIds = profileReader.rowIdsForPackage(name)
            if (rowIds.isEmpty()) {
                return@withContext failure(
                    "ENTRY_ID_UNRESOLVED",
                    "在 `$name` 的补丁文件里找不到它自己的条目 id（这类插件没有宿主侧条目，例如纯客户端插件），已拒绝：写一条打不中的行只会让界面显示已停用、实际照常在加载 / no layer row id could be resolved for `$name`, so the change was refused instead of writing a row that matches nothing",
                )
            }
            // 判定基于**包名**：宿主自带包集合在运行时从安装层推导（会随 DSH 升级变化），
            // 而不是写死一份名单；判不出包名时归入受保护一侧。
            val protectedRows = rowIds.filter {
                HostInfrastructure.isProtected(it, name, profileReader.hostPackages())
            }
            if (protectedRows.isNotEmpty()) {
                return@withContext failure(
                    "PROTECTED_INFRASTRUCTURE",
                    "`$name` 的条目（${protectedRows.joinToString(", ")}）属于宿主基础设施（热加载、传输、存储链），关掉它会破坏 DSH 自身，已拒绝 / those rows are part of the host infrastructure — disabling them would break DSH itself, so it was refused",
                )
            }
            // 关 = 写 `disabled: true`；开 = **删掉那一行**，而不是写 `disabled: false`。
            // 后者会留下一条"强制启用"：一旦这个插件是确定性崩溃的，每次启动都会
            // 重新应用它——崩溃就变成崩溃循环。删行回到"默认启用"，仍崩就由守卫再隔离。
            // 与隔离清单的"重新启用"同一纪律（见 PluginSafetyMode.restorePlugin）。
            val failed = if (enabled) {
                rowIds.filterNot { layer.removePatchEntry(it) }
            } else {
                rowIds.filterNot { layer.setDisabled(it, true) }
            }
            if (failed.isNotEmpty()) {
                return@withContext failure(
                    "LAYER_WRITE_FAILED",
                    "写层失败（${failed.joinToString(", ")}），本次改动不完整 / writing the layer failed",
                )
            }
            // 与安全模式的隔离清单同步：用户既然亲手改了这个插件的状态，我们就不再
            // 把它列成"被安全模式跳过"——否则两处显示会互相打架，而且守卫的
            // "清单↔层对齐"下一步会把用户刚启用的插件又翻回停用。
            isolationLedger?.forget(rowIds)
            val state = stateStore.read()
            val nextDisabled =
                if (enabled) state.disabled.filterNot { it == name } else state.disabled + name
            when (val stateWrite = stateStore.writeOutcome(nextDisabled, state.notes)) {
                is MarketStateWrite.Ok -> AppResult.Success(Unit)
                // 盘上的状态文件读不出来：**拒绝写入**，原文件保持不动。
                // 层已经写成功了，但状态没落盘；此时"停用"仍是安全的一侧：
                // 层是真正的载体，状态没写只会让记录与实况不一致。
                is MarketStateWrite.Refused -> failure("STATE_UNREADABLE", stateWrite.reason)
                is MarketStateWrite.IoFailed -> failure("STATE_WRITE_FAILED", stateWrite.reason)
            }
        }

    private fun isMarketItself(name: String): Boolean =
        name in MARKET_SELF_NAMES ||
            MARKET_SELF_NAMES.any { name.endsWith("/$it") }

    /**
     * 备份并重建损坏的市场状态文件。
     *
     * 状态文件读不出来时所有写入都会被拒（见 [MarketStateStore.writeOutcome]），
     * 用户会在界面上看到 `STATE_UNREADABLE` 却无从下手；这里给它一条出路。
     * 原文件改名留档后，新文件只含 `disabled` / `notes`——原文件里我们不理解的
     * 字段读不出来，只能靠备份保留。
     */
    override suspend fun rebuildState(): AppResult<Unit> = withContext(Dispatchers.IO) {
        val state = stateStore.read()
        when (val outcome = stateStore.rebuild(state.disabled, state.notes)) {
            is MarketStateWrite.Ok -> AppResult.Success(Unit)
            is MarketStateWrite.Refused -> failure("STATE_UNREADABLE", outcome.reason)
            is MarketStateWrite.IoFailed -> failure("STATE_WRITE_FAILED", outcome.reason)
        }
    }

    /**
     * 安装一个目录条目。
     *
     * **装后必须校验**（假成功守卫，见 [InstallGuard]）：`dsh plugin add` 退出码
     * 为 0 只说明 pnpm 这一趟没报错，装出来的包仍可能不可加载（清单损坏、
     * 根本不是 DSH 插件、`dsh.bundle` / `dsh.client` 都没声明）。所以命令成功
     * 之后还要核对磁盘事实——目标包目录存在 + `package.json` 可读 + 有 `dsh`
     * 字段 + bundle/client 至少一个；任何一项不满足就执行 `remove` 回收并返回
     * 失败（附原因），绝不把"装上了但用不了"的包留在 profile 里。
     */
    override suspend fun install(
        pluginName: String,
        installTarget: String,
        onLine: (String) -> Unit,
    ): AppResult<Unit> {
        if (installTarget.isBlank()) {
            return failure("INSTALL_TARGET_REQUIRED", "缺少安装目标 / the install target is required")
        }
        // 安装链完全交给 dsh 官方命令：它会转发 pnpm 并在成功后就地对账 bundles。
        // 命令之后的那道校验由 InstallGuard 负责。
        return installGuard.install(installTarget, pluginName, onLine)
    }

    override suspend fun uninstall(name: String, onLine: (String) -> Unit): AppResult<Unit> {
        if (name.isBlank()) {
            return failure("PLUGIN_NAME_REQUIRED", "插件名不能为空 / the plugin name is required")
        }
        // 行 id 必须在**卸载之前**解析：包目录一删，它的 patch 文件就读不到了，
        // 层里留下的停用行就变成打不中的幽灵行（DSH 每次启动都警告 not found，
        // 界面上还会挂一条永远"已隔离"的条目）。上游同样在卸载后清理这些行。
        val rowIds = profileReader.rowIdsForPackage(name)
        val result = pluginOps.remove(name, onLine)
        if (result is AppResult.Success) {
            if (rowIds.isNotEmpty()) {
                val leftover = rowIds.filterNot { layer.removePatchEntry(it) }
                if (leftover.isEmpty()) isolationLedger?.forget(rowIds)
            }
            // 市场状态里的停用记录也一并去掉，避免留下指向不存在插件的名字。
            val state = stateStore.read()
            stateStore.writeOutcome(state.disabled.filterNot { it == name }, state.notes)
        }
        return result
    }

    /**
     * 更新一个插件到最新。
     *
     * 两件事必须先定下来：
     * 1. **只对 npm 来源可用**。`link:` / `file:` 指向本地开发件、`github:` 要问
     *    git ref，都没有"最新版"可言；对它们跑 `add <name>@latest` 要么失败，
     *    要么把一个本地开发件换成 registry 上的另一个包。所以直接拒绝并说明。
     * 2. **用真实包名**。清单里的键可能是 `npm:` 别名（`npm:foo@1.0.0` 的键是
     *    别名），拿别名去 `add …@latest` 一定装不上；必须换成冒号后面的真名。
     */
    override suspend fun update(name: String, onLine: (String) -> Unit): AppResult<Unit> {
        if (name.isBlank()) {
            return failure("PLUGIN_NAME_REQUIRED", "插件名不能为空 / the plugin name is required")
        }
        val spec = when (val manifest = profileReader.readManifest()) {
            is AppResult.Success -> manifest.value.dependencies[name]
            // 清单读不出来就不猜来源：猜错会把本地开发件换成 registry 包。
            is AppResult.Failure -> return manifest
        }
        return when (val target = updateTargetFor(name, spec)) {
            is UpdateTarget.Unsupported -> failure(
                "UPDATE_SOURCE_UNSUPPORTED",
                "该来源不支持一键更新（`link:` / `file:` / `github:` 没有「最新版」） / " +
                    "this source does not support one-click updates (`link:` / `file:` / `github:` have no \"latest\")",
            )

            is UpdateTarget.Npm -> pluginOps.update(target.packageName, onLine)
        }
    }

    override suspend fun setNote(name: String, note: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        if (name.isBlank()) {
            return@withContext failure("PLUGIN_NAME_REQUIRED", "插件名不能为空 / the plugin name is required")
        }
        val state = stateStore.read()
        val nextNotes = LinkedHashMap(state.notes)
        val trimmed = note.trim()
        // 空串等于清除：留着一条空备注会让界面显示"有备注"却是空白。
        if (trimmed.isEmpty()) nextNotes.remove(name) else nextNotes[name] = trimmed
        when (val written = stateStore.writeOutcome(state.disabled, nextNotes)) {
            is MarketStateWrite.Ok -> AppResult.Success(Unit)
            is MarketStateWrite.Refused -> failure("STATE_UNREADABLE", written.reason)
            is MarketStateWrite.IoFailed -> failure("STATE_WRITE_FAILED", written.reason)
        }
    }

    private fun failure(code: String, message: String): AppResult.Failure =
        AppResult.Failure(AppError(code = code, message = message))
}
