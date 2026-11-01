package com.dshbox.pluginmanager.market.data

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.pluginmanager.contract.HostCapabilities
import com.dshbox.pluginmanager.contract.HostContract
import com.dshbox.pluginmanager.core.GuestCommandRunner
import com.dshbox.pluginmanager.core.IsolationLedger
import com.dshbox.pluginmanager.core.PluginPaths
import com.dshbox.pluginmanager.layer.HostInfrastructure
import com.dshbox.pluginmanager.layer.PatchDialect
import com.dshbox.pluginmanager.layer.PluginLayer
import com.dshbox.pluginmanager.market.MarketRepository
import com.dshbox.pluginmanager.market.model.ActivationState
import com.dshbox.pluginmanager.market.model.HostCompatibility
import com.dshbox.pluginmanager.market.model.InstalledPlugin
import com.dshbox.pluginmanager.market.model.Registry
import com.dshbox.pluginmanager.market.model.RegistryFailure
import com.dshbox.pluginmanager.market.model.RegistryPlugin
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
 * 查一个 npm 包在**某个 dist-tag** 上的版本（如 `next` / `alpha` / `beta` / `canary`）。
 *
 * 与 [LatestVersionLookup] 分开，是两个原因：
 * 1. 它是**可选能力**——默认实现返回 null，未接线时行为与以前完全一致；
 * 2. 它只在「用户装的那条线不是 `latest`」时才被调用，绝大多数包一次都不会查。
 */
fun interface TagVersionLookup {
    fun lookup(npmName: String, tag: String): String?
}

/**
 * 直接问 npm registry 的某个 dist-tag。
 *
 * 与 [NpmLatestVersionLookup] 同源：路径换成 `/<encoded>/<tag>`。取不到返回 null。
 */
class NpmTagVersionLookup(
    private val registryBase: String = HostContract.DEFAULT_REGISTRY_BASE,
    private val timeoutMs: Int = 8_000,
) : TagVersionLookup {

    override fun lookup(npmName: String, tag: String): String? = try {
        val encoded = npmName.replace("/", "%2F")
        val conn = URL("$registryBase/$encoded/$tag").openConnection() as HttpURLConnection
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
 * 直接问 npm registry 的 `dist-tags.latest`。
 *
 * 取不到就返回 null，调用方据此报"没有可判定的更新"——把查询失败说成
 * "已是最新"是这里最容易犯也最不该犯的错。
 *
 * 基址由装配点注入（镜像源覆盖文件的解析结果），缺省官方；安装链的源另由
 * DSH 面板决定，两者不保证一致。
 */
class NpmLatestVersionLookup(
    private val registryBase: String = HostContract.DEFAULT_REGISTRY_BASE,
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
 * ar / es / fr / ru 回退到英文。
 */
class DefaultMarketRepository(
    private val paths: PluginPaths,
    private val layer: PluginLayer,
    private val guest: GuestCommandRunner,
    private val registrySource: RegistrySource = RegistrySource(sources = CatalogSource.DEFAULT.addresses),
    // 数据源选择单独一个文件：共享的 state.json 字段归上游／桌面端所有。
    private val catalogSourceStore: CatalogSourceStore = CatalogSourceStore(paths.catalogSourceFile),
    /**
     * 宿主能力（由装配点探测后注入）。
     *
     * 默认取 [HostCapabilities.Capabilities.WIDEST]（一切按支持处理）——**这是刻意的**：
     * 未接线能力探测时必须与改造前行为完全一致（改造是重构，不改行为）。生产路径由
     * `PluginManagerFactory` 注入真实探测结果。
     */
    private val capabilities: HostCapabilities.Capabilities = HostCapabilities.Capabilities.WIDEST,
    private val profileReader: ProfileReader = ProfileReader(paths, capabilities),
    private val stateStore: MarketStateStore = MarketStateStore(paths.marketStateFile),
    private val pluginOps: GuestPluginOps = GuestPluginOps(
        guest,
        paths.profile,
        // 每次操作时按磁盘事实判定：profile 可能此刻才被宿主初始化出来。
        isWorkspaceProfile = { paths.pnpmWorkspaceFile.isFile },
    ),
    private val compatChecker: HostCompatChecker = HostCompatChecker(paths),
    /**
     * 判定面查询（更新检测 / 预发布通道 / 浏览期兼容性）的 registry 基址。
     *
     * 由装配点从镜像源覆盖文件解析注入。**与安装链不保证同源**：`dsh plugin` 的源
     * 由 DSH 面板决定，镜像领先时会提示有更新却装不上、滞后时检不到更新。
     * 缺席时回退官方。
     */
    private val registryBase: String = HostContract.DEFAULT_REGISTRY_BASE,
    private val latestVersion: LatestVersionLookup = NpmLatestVersionLookup(registryBase = registryBase),
    /**
     * 预发布通道查询（`.next` / `.alpha` / `.beta` / `.canary`）。
     *
     * 只在「用户装的那条线不是 `latest`」时才会被调用，取不到时对应字段保持 null。
     */
    private val tagVersionLookup: TagVersionLookup = NpmTagVersionLookup(registryBase = registryBase),
    /**
     * 浏览期兼容性取数点。基址同上；取不到 manifest 时结论降级为 UNKNOWN，
     * 不影响其它能力。
     */
    private val remoteCompatChecker: RemoteHostCompatChecker = RemoteHostCompatChecker(
        hostVersion = { compatChecker.hostVersion() },
        fetcher = RemoteHostCompatChecker.httpFetcher(registryBase),
    ),
    /** `dsh.profile.bundles` 的外科手术编辑器（启停双层对账用）。 */
    private val bundlesStore: ProfileBundlesStore = ProfileBundlesStore(paths.profileManifest),
    /** 卸载中断的行日志：remove 之前落盘，下次卸载入口结算。 */
    private val rowJournal: PendingRowJournal = PendingRowJournal(paths.pendingRowsFile),
    /** 市场自己拨过、要等 DSH 下次启动才生效的开关；只影响状态文案，不影响判定。 */
    private val toggleJournal: PendingToggleJournal = PendingToggleJournal(paths.pendingTogglesFile),
    /**
     * 启停后与安全模式的隔离清单同步（见 [IsolationLedger]）。
     * 未装配时保持旧行为（只写层），用于尚未接线市场的场景。
     */
    private val isolationLedger: IsolationLedger? = null,
) : MarketRepository {

    private companion object {
        /** 市场自己的包名；它不能把自己关掉，否则这个功能就没了。 */
        val MARKET_SELF_NAMES = setOf("dshmarket", "dsh-market")

        /** 启动日志取尾部多少字节做拒绝扫描（一次启动的失败报告远小于此）。 */
        const val BOOT_LOG_SCAN_LIMIT = 256 * 1024
    }

    private val updates = UpdateDetector(latestVersion, tagLookup = tagVersionLookup)

    /** 安装的「假成功守卫」：命令成功之后还要核对磁盘事实（见 [InstallGuard]）。 */
    private val installGuard = InstallGuard(pluginOps, profileReader, profileReader)

    /**
     * 市场状态文件「读-改-写」的进程内串行锁。
     *
     * `setEnabled`（UI 不经单飞直接拨开关）、`setNote` 与卸载收尾都会做
     * "读盘 → 合并 → 写回"，并发时后写覆盖前写会丢掉另一路的停用记录——
     * 那份记录在 profile 重建/重放场景是开关的唯一凭据。三个写点用同一把
     * 监视锁串行；锁内只碰本地文件，没有挂起点，`synchronized` 是安全的。
     */
    private val stateWriteLock = Any()

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
        // 市场每次启动先结算中断的卸载（对齐上游路由挂载点）：日志里有记录而包
        // 已不在时，层里的行只有这里会主动清。日志为空时零开销。
        settleInterruptedUninstalls()
        val manifest = when (val result = profileReader.readManifest()) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return@withContext result
        }
        val state = stateStore.read()
        // 层是唯一真相，而层里存的是**条目 id**、不是包名（`dshmarket` → `dsh-market`）。
        // 所以判定要把这个包自己的条目 id 解析出来对：拿包名去比层里的 id，
        // 会出现"层里写着 disabled、界面显示启用中"的反向错配。
        val layerDisabled = layer.disabledIds()
        val layerForced = layer.forcedIds()
        // 用户自己的补丁层也在压制/引用条目：市场必须把它并进判定，否则
        // 用户手改 cordis.patch.yml 停用的插件在这里会显示"启用中"。
        val userPatch = PatchDialect.scan(paths.userPatchFile)
        val bundles = manifest.bundles.toSet()
        // 日志尾部只读一次：拒绝扫描与启动时刻都从同一段文本解析。
        val bootTail = bootLogTail()
        // 宿主启动期的"拒绝加载"（兼容性置停用 / bundle 跳过）是磁盘推断不出
        // 的一态，只能从启动日志读。
        val denials = scanBootDenials(bootTail)
        val hostPackages = profileReader.hostPackages()
        // 兼容门禁：一次性读好口径、豁免与宿主版本，逐包判定时只读各自的清单。
        // 判据**按已装宿主的产物推断**（不是按版本号），见 HostGateJudge.modeFor。
        // 两个布局都读不到产物时按 engines 独占判：宁可多报"不兼容"，也不能承诺
        // "重启后生效"——后者会被宿主在启动期打脸，而前者只是提示。
        val gateMode = HostGateJudge.detectMode(paths) ?: GateMode.ENGINES_FIRST
        val gateExemptions = HostGateJudge.readExemptions(paths.compatibilityFile)
        val hostVersion = runCatching { compatChecker.hostVersion() }.getOrNull()
        // 宿主随安装自带的 bundle 不进已安装列表（与上游同一份名单）。
        runCatching {
            toggleJournal.pruneStartedBefore(bootTail?.let(BootStamp::lastBootMillis))
        }
        val pendingToggles = toggleJournal.pending()
        val plugins = manifest.dependencies.filterKeys { it !in HostContract.IN_BOX_BUNDLES }.map { (name, spec) ->
            val facts = profileReader.facts(name, spec)
            val rowIds = profileReader.rowIdsForPackage(name)
            // 解析不出条目 id 的包（纯客户端插件等）：只看层里有没有同名行
            // （旧版本写过包名行的情况），否则按"启用中"显示——本版本不能停用它们，
            // 显示"已停用"会是假话。
            val off = if (rowIds.isEmpty()) {
                (name in layerDisabled || name in userPatch.disabled) && name !in layerForced
            } else {
                val ourOff = rowIds.any { it in layerDisabled }
                val ourForced = rowIds.any { it in layerForced }
                val userOff = rowIds.any { it in userPatch.disabled }
                // 合成顺序里我方层在用户层之后：我方层的 disabled:true 优先于一切；
                // 我方层的 disabled:false（强制启用）能把用户层的压制救回来。
                ourOff || (userOff && !ourForced)
            }
            // 只有会进 bundles 的包才判门禁：不在 bundles 里的包宿主不会加载，
            // 判它"不兼容"没有意义（那是"未装配"）。同时省掉大部分清单重读。
            val gateRequirement = if (name in bundles) {
                runCatching {
                    HostGateJudge.judge(
                        manifestText = profileReader.manifestText(name),
                        packageName = name,
                        packageVersion = facts.version,
                        hostVersion = hostVersion,
                        exemptions = gateExemptions,
                        mode = gateMode,
                    ).takeIf { it.incompatible }?.requirement
                }.getOrNull()
            } else {
                null
            }
            val verdict = ActivationClassifier.classify(
                facts = facts,
                inBundles = name in bundles,
                disabled = off,
                referencedByUserPatch = name in userPatch.names,
                gateRequirement = gateRequirement,
                restartPending = name in pendingToggles,
            )
            val plugin = InstalledPlugin(
                name = name,
                version = facts.version,
                spec = spec,
                activation = verdict.state,
                reasons = verdict.reasons,
                note = state.notes[name],
                thirdParty = isThirdPartyPackage(name, hostPackages),
                // 真实判定在 applyDenial 之后：拒绝态也会让开关失去意义。
                toggleable = false,
            )
            val denied = applyDenial(plugin, rowIds, denials)
            denied.copy(
                toggleable = isToggleablePackage(
                    name = name,
                    rowIds = rowIds,
                    hostPackages = hostPackages,
                    activation = denied.activation,
                    isMarketItself = isMarketItself(name),
                ),
            )
        }
        AppResult.Success(plugins)
    }

    /** DSH 进程日志尾部正文；缺失或读不出返回 null。 */
    private fun bootLogTail(): String? = runCatching {
        val log = paths.dshProcessLog
        if (!log.isFile) return null
        val length = log.length()
        val skip = (length - BOOT_LOG_SCAN_LIMIT).coerceAtLeast(0L)
        // RandomAccessFile 精确定位：InputStream.skip 的契约允许少跳，会扫入更旧的记录。
        java.io.RandomAccessFile(log, "r").use { file ->
            file.seek(skip)
            val buffer = ByteArray((length - skip).toInt())
            file.readFully(buffer)
            String(buffer, Charsets.UTF_8)
        }
    }.getOrNull()

    /** 扫描宿主拒绝行；日志缺失/读不出返回空结果。 */
    private fun scanBootDenials(tail: String?): BootDenialScanner.BootDenials =
        tail?.let(BootDenialScanner::scan)
            ?: BootDenialScanner.BootDenials(emptyMap(), emptyMap(), emptyMap())

    /**
     * 把宿主拒绝态叠到已装条目上。
     *
     * 匹配：跳过行按**包名**；停用行按包名或该包自己的行 id。命中的条目
     * 改判 [ActivationState.DENIED]，理由里带宿主原文——"重启后生效"这类
     * 承诺只留给宿主真的会加载的条目。
     *
     * 不改判的只有一类：已停用（DISABLED / DISABLED_PENDING）与声明的库
     * （LIBRARY）——停用是压倒性事实，库没有可拒绝的加载行为。改写规则有两条：宿主原文里的
     * ` / ` 先归一（任意外部文本不能污染双语分隔符）；原文为空时用
     * 「原因未给出」兜底。
     */
    private fun applyDenial(
        plugin: InstalledPlugin,
        rowIds: List<String>,
        denials: BootDenialScanner.BootDenials,
    ): InstalledPlugin {
        if (denials.isEmpty) return plugin
        // 停用是压倒性事实；声明的库没有可拒绝的加载行为，"拒绝加载"对它是空话。
        // 停用（含"刚写入、待重启"）是压倒性事实：宿主拒绝一个已停用的包没有信息量。
        if (isStopFact(plugin.activation)) return plugin
        val skippedReason = denials.skippedPackages[plugin.name]
        val deniedReason = denials.deniedNames[plugin.name]
            ?: rowIds.firstNotNullOfOrNull { denials.deniedRowIds[it] }
        val rawReason = (skippedReason ?: deniedReason) ?: return plugin
        // 宿主原文进双语串的两半，但先把双语分隔符形态从原文里清掉，
        // 避免 localizeBilingual 的择半被任意文本切出错误候选。
        val reason = rawReason.ifBlank { "原因未给出" }.replace(" / ", "；")
        return plugin.copy(
            activation = ActivationState.DENIED,
            reasons = listOf(
                "宿主在启动期拒绝加载它：$reason / the host denied loading it at startup: $reason",
            ),
        )
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
            val statuses = updates.check(
                dependencies = manifest.dependencies,
                currentVersion = { versions[it] },
                force = force,
            )
            AppResult.Success(gateUpdatability(manifest, statuses))
        }

    /**
     * 收回「不是插件」的包的更新入口。
     *
     * 已装清单里混着插件带进来的普通库；对它们执行 `add <name>@latest` 会静默改写
     * profile 的依赖版本而不动 bundles。判定与理由见 [isUpdatablePackage]。
     *
     * 只在真有 `updateAvailable` 时才做逐包判定：没有更新的包不需要读这些事实。
     */
    private fun gateUpdatability(
        manifest: ProfileManifest,
        statuses: Map<String, UpdateStatus>,
    ): Map<String, UpdateStatus> {
        if (statuses.values.none { it.updateAvailable }) return statuses
        val bundles = manifest.bundles.toSet()
        // 「补丁文件读不出」与「里面没有引用」必须分开：读不出时保守放行，
        // 读失败时不收回插件的更新入口。
        val patchFile = paths.userPatchFile
        val patchReadable = !patchFile.isFile ||
            runCatching { patchFile.readText(Charsets.UTF_8) }.isSuccess
        val patchNames: Set<String>? = if (patchReadable) PatchDialect.scan(patchFile).names.toSet() else null

        val out = LinkedHashMap<String, UpdateStatus>(statuses.size)
        for ((name, status) in statuses) {
            if (!status.updateAvailable) {
                out[name] = status
                continue
            }
            val facts = runCatching { profileReader.facts(name, manifest.dependencies[name]) }.getOrNull()
            val updatable = isUpdatablePackage(
                name = name,
                hasManifest = paths.installedManifest(name) != null,
                hasDshField = facts?.hasDshField == true,
                inBundles = name in bundles,
                userPatchNames = patchNames,
            )
            out[name] = if (updatable) {
                status
            } else {
                status.copy(updateAvailable = false, notAPlugin = true)
            }
        }
        return out
    }

    override suspend fun hostCompatibility(names: List<String>): Map<String, HostCompatibility> =
        withContext(Dispatchers.IO) { compatChecker.check(names) }

    override suspend fun browseCompatibility(entries: List<RegistryPlugin>): Map<String, HostCompatibility> {
        if (entries.isEmpty()) return emptyMap()
        return remoteCompatChecker.check(entries.map { it.displayName }) { entryName ->
            // 显示名当 npm 名仅在有 npm 名的条目上成立；`owner/name` 形态的
            // 条目没有 npm 通道，远端判定不猜。
            entries.firstOrNull { it.displayName == entryName }?.npm
        }
    }

    override suspend fun auditOverlay(): List<String> = withContext(Dispatchers.IO) {
        val manifest = when (val result = profileReader.readManifest()) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return@withContext emptyList()
        }
        val disabled = layer.disabledIds()
        if (disabled.isEmpty()) return@withContext emptyList()
        // 保守闸门：任一依赖包的补丁文件此刻**存在但读不出**时，本包的行 id
        // 集合是不完整的——继续审计会把真实生效的停用行误判成残留、一键清理
        // 会把被刻意停用的插件复活。宁漏报勿误删，本轮放弃。
        //
        // 触发面很窄：`patchFilesReadable` 只在"补丁文件**存在**且读失败"时返回
        // false（文件不存在、包目录不存在都算可读）。正常情况下不触发，因此
        // 审计在常规路径生效；该闸门只兜异常路径，不是常态开关。
        if (manifest.dependencies.keys.any { !profileReader.patchFilesReadable(it) }) {
            return@withContext emptyList()
        }
        // 判定本身与文件系统无关，抽到 OverlayAudit 以便单测钉死（见其 KDoc）。
        OverlayAudit.staleRows(
            disabledRowIds = disabled,
            installedNames = manifest.dependencies.keys + manifest.bundles,
            rowIdsOf = { profileReader.rowIdsForPackage(it) },
        )
    }

    override suspend fun cleanOverlayRows(rowIds: List<String>): AppResult<Unit> = withContext(Dispatchers.IO) {
        val failed = rowIds.filterNot { layer.removePatchEntry(it) }
        if (failed.isEmpty()) {
            isolationLedger?.forget(rowIds)
            AppResult.Success(Unit)
        } else {
            failure(
                "LAYER_WRITE_FAILED",
                "部分残留行清理失败（${failed.joinToString(", ")}）/ some stale rows could not be removed",
            )
        }
    }

    override suspend fun setEnabled(name: String, enabled: Boolean): AppResult<Unit> =
        withContext(Dispatchers.IO) {
            synchronized(stateWriteLock) {
            if (name.isBlank()) {
                return@withContext failure("PLUGIN_NAME_REQUIRED", "插件名不能为空 / the plugin name is required")
            }
            if (isMarketItself(name)) {
                return@withContext failure(
                    "MARKET_SELF_PROTECTED",
                    "不能停用市场自己 / the market cannot disable itself",
                )
            }
            // 只有装进 profile 依赖的包才归市场管（与上游开关端点同一口径）：
            // 不在依赖里的名字根本没有可写的行，拒绝而不是猜。
            val manifest = when (val result = profileReader.readManifest()) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> return@withContext result
            }
            if (name !in manifest.dependencies) {
                return@withContext failure(
                    "NOT_IN_PROFILE_DEPENDENCIES",
                    "`$name` 不在 profile 依赖里（未安装），市场不能在这里启停 / `$name` is not in the profile dependencies (not installed), so the market cannot toggle it",
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
            // 双层对账：社区 bundle 的启停要同时动行层与 bundles 栈层，且"要么都写、
            // 要么都不写"。带邻居行（配置/停用别人的行）的包不出栈——摘除会让整份补丁
            // 连同邻居配置一起失效。出栈/回栈只作用于**社区**包：宿主内置与 `@local/`
            // 资产的栈位归宿主与装配链。
            val foreignRows = profileReader.foreignRowIdsForPackage(name)
            val inBundles = name in manifest.bundles
            val thirdParty = isThirdPartyPackage(name, profileReader.hostPackages())
            val stackRemove = !enabled && thirdParty && inBundles && foreignRows.isEmpty()
            val stackAdd = enabled && thirdParty && !inBundles && foreignRows.isEmpty() &&
                profileReader.facts(name, manifest.dependencies[name]).hasBundle
            if (stackRemove) {
                if (!bundlesStore.remove(name, isPresent = true)) {
                    return@withContext failure(
                        "BUNDLE_STACK_WRITE_FAILED",
                        "从 dsh.profile.bundles 摘除失败，已放弃本次停用（层与栈必须同写） / removing it from dsh.profile.bundles failed; the disable was abandoned (both layers must change together)",
                    )
                }
            }
            // 关 = 写 `disabled: true`；开 = 删掉那一行，回到"默认启用"——但若
            // 用户的补丁层仍在压制它，则写 `disabled: false` 强制启用（我方层在
            // 用户层之后，能救回来）。后者只在我方层留下一条显式覆盖，不会把
            // 确定性崩溃的插件变成崩溃循环的起点：那条行描述的是"用户要它开"。
            val failed = if (enabled) {
                val userPatch = PatchDialect.scan(paths.userPatchFile)
                rowIds.filterNot { rowId ->
                    if (rowId in userPatch.disabled) layer.setDisabled(rowId, false)
                    else layer.removePatchEntry(rowId)
                }
            } else {
                rowIds.filterNot { layer.setDisabled(it, true) }
            }
            if (failed.isNotEmpty()) {
                // 行写失败而栈已出：回滚栈，保持"要么都写、要么都不写"。
                // 回滚本身也可能失败（清单此刻读不出等）——回滚结果必须进文案，
                // 绝不能让"已放弃"的说法掩盖"包已出栈"的磁盘事实。
                val stackRolledBack = !stackRemove || bundlesStore.add(name, isPresent = false)
                val rollbackNote = if (stackRolledBack) "" else {
                    "；dsh.profile.bundles 回滚也失败，包可能已出栈 / the rollback to dsh.profile.bundles also failed; the package may have left the stack"
                }
                return@withContext failure(
                    "LAYER_WRITE_FAILED",
                    "写层失败（${failed.joinToString(", ")}），本次改动不完整$rollbackNote / writing the layer failed$rollbackNote",
                )
            }
            if (stackAdd && !bundlesStore.add(name, isPresent = false)) {
                // 栈回不来：把刚删的行重新压回停用（可写的一侧），不留半启用态。
                // 恢复同样可能失败——磁盘实况是"行已删、栈未进"，文案必须如实说。
                val restoreFailed = rowIds.filter { !layer.setDisabled(it, true) }
                val restoreNote = if (restoreFailed.isEmpty()) {
                    "已恢复停用行 / the disable rows were restored"
                } else {
                    "停用行恢复失败（${restoreFailed.joinToString(", ")}），插件当前处于半启用状态，请重试或重新停用 / restoring the disable rows also failed (${restoreFailed.joinToString(", ")}); the plugin is half-enabled — retry or disable it again"
                }
                return@withContext failure(
                    "BUNDLE_STACK_WRITE_FAILED",
                    "回写 dsh.profile.bundles 失败，$restoreNote / re-adding it to dsh.profile.bundles failed: $restoreNote",
                )
            }
            // 与安全模式的隔离清单同步：用户既然亲手改了这个插件的状态，我们就不再
            // 把它列成"被安全模式跳过"——否则两处显示会互相打架，而且守卫的
            // "清单↔层对齐"下一步会把用户刚启用的插件又翻回停用。
            isolationLedger?.forget(rowIds)
            // 这次是市场自己写 `--patch` 层：宿主下次组合才读它，先记一笔，
            // 让列表显示"重启后生效"而不是"已生效"。
            toggleJournal.record(name)
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
        synchronized(stateWriteLock) {
        val state = stateStore.read()
        when (val outcome = stateStore.rebuild(state.disabled, state.notes)) {
            is MarketStateWrite.Ok -> AppResult.Success(Unit)
            is MarketStateWrite.Refused -> failure("STATE_UNREADABLE", outcome.reason)
            is MarketStateWrite.IoFailed -> failure("STATE_WRITE_FAILED", outcome.reason)
        }
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

    override suspend fun uninstall(name: String, onLine: (String) -> Unit): AppResult<Unit> = withContext(Dispatchers.IO) {
        if (name.isBlank()) {
            return@withContext failure("PLUGIN_NAME_REQUIRED", "插件名不能为空 / the plugin name is required")
        }
        // 行 id 必须在**卸载之前**解析：包目录一删，它的 patch 文件就读不到了，
        // 层里留下的停用行就变成打不中的幽灵行（DSH 每次启动都警告 not found，
        // 界面上还会挂一条永远"已隔离"的条目）。上游同样在卸载后清理这些行。
        // 先结算此前中断的卸载：按当前磁盘事实裁决孤儿行；清单读不出就不动，
        // 宁可留着记录，也不在事实不明时删层。
        settleInterruptedUninstalls()
        val rowIds = profileReader.rowIdsForPackage(name)
        // 意图先落盘：remove 与清行之间进程中断（用户杀 app 是常态）时，
        // 下次卸载入口据此收尾。
        rowJournal.begin(name, rowIds)
        val result = pluginOps.remove(name, onLine)
        if (result is AppResult.Success) {
            if (rowIds.isNotEmpty()) {
                val leftover = rowIds.filterNot { layer.removePatchEntry(it) }
                if (leftover.isEmpty()) {
                    isolationLedger?.forget(rowIds)
                    rowJournal.finish(name)
                }
                // 清行有失败：记录保留，下次卸载入口重试结算。
            } else {
                rowJournal.finish(name)
            }
            toggleJournal.forget(name)
            // 市场状态里的停用记录也一并去掉，避免留下指向不存在插件的名字。
            synchronized(stateWriteLock) {
                val state = stateStore.read()
                stateStore.writeOutcome(state.disabled.filterNot { it == name }, state.notes)
            }
        }
        // remove 失败时记录保留：包多半还在，下次卸载入口结算时按磁盘事实丢弃。
        return@withContext result
    }
    /** 结算此前中断的卸载意图；安装清单读不出时整轮跳过（宁留记录，不误删层）。 */
    private fun settleInterruptedUninstalls() {
        val operations = rowJournal.list()
        if (operations.isEmpty()) return
        val manifest = when (val result = profileReader.readManifest()) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return
        }
        val settled = PendingRowSettle.settle(operations, manifest.dependencies.keys + manifest.bundles)
        settled.orphaned.forEach { op ->
            val leftover = op.rows.filterNot { layer.removePatchEntry(it) }
            if (leftover.isEmpty()) {
                isolationLedger?.forget(op.rows)
                rowJournal.finish(op.name)
            }
            // 清行失败：记录保留，下轮重试。
        }
        settled.intact.forEach { rowJournal.finish(it.name) }
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
        synchronized(stateWriteLock) {
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
    }

    private fun failure(code: String, message: String): AppResult.Failure =
        AppResult.Failure(AppError(code = code, message = message))
}
