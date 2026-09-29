package com.dshbox.app.runtime

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.system.Os
import android.util.Log
import com.dshbox.app.R
import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.DebianArchiveSource
import com.dshbox.app.common.DebianSources
import com.dshbox.app.common.NodeDistSource
import com.dshbox.app.common.NodeSources
import com.dshbox.app.common.UiText
import com.dshbox.app.common.coroutineFailureHandler
import com.dshbox.app.sandbox.BundleManager
import com.dshbox.app.sandbox.DshUpdateOutcome
import com.dshbox.app.sandbox.SandboxConfig
import com.dshbox.app.sandbox.SandboxManager
import com.dshbox.app.sandbox.SandboxProcessRunner
import com.dshbox.app.sandbox.SandboxState
import com.dshbox.app.sandbox.online.BaseManifest
import com.dshbox.app.sandbox.online.BootstrapFixtures
import com.dshbox.app.sandbox.online.DebUnpacker
import com.dshbox.app.sandbox.online.DebianReleaseVerifier
import com.dshbox.app.sandbox.online.DirHash
import com.dshbox.app.sandbox.online.DpkgDbFixtures
import com.dshbox.app.sandbox.online.DpkgDbWriter
import com.dshbox.app.sandbox.online.PackagesIndex
import com.dshbox.app.sandbox.online.RuntimeProfileWriter
import com.dshbox.app.sandbox.online.Shasums256
import com.dshbox.app.sandbox.online.TrimSpec
import com.dshbox.app.util.BackgroundOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 在线导入的 UI 状态（Debian 层 / node 层各一份）。 */
data class OnlineImportState(
    val running: Boolean = false,
    val stage: UiText = UiText.Raw(""),
    /** 技术性进度（如 `12/158 bash`），不本地化（与 npm 原始日志同口径）。 */
    val progress: String? = null,
    val logs: List<String> = emptyList(),
    val result: AppResult<Unit>? = null,
    val cancelled: Boolean = false,
    val startedAtMs: Long = 0L,
)

/**
 * 单源探测结果。`hasTarget` = 该源**实际提供了所需内容**（Debian：Release 声明了
 * 目标架构的 Packages.gz；node：SHASUMS256.txt 含目标 tarball 行）——UI 以
 * 「hasTarget → 延迟升序 → 其余」排序，让用户从真正可用的源里挑。
 */
data class OnlineSourceProbe(
    val url: String,
    val reachable: Boolean,
    val hasTarget: Boolean,
    val latencyMs: Long,
    val error: UiText? = null,
)

/**
 * 在线导入运行环境（base / node 两个独立流程）。
 *
 * - Debian 流程：镜像探测 → 索引（Release 锚定）→ 逐包下载（索引 SHA256 校验 +
 *   内容寻址缓存断点续传）→ 安全解包 → dpkg 库（install ok installed，设备上
 *   dpkg configure 在设备侧不可行）→ 宿主侧补齐
 *   （ldconfig + alternatives 符号链接 + /etc 骨架 + env.d）→ 裁剪 → 三级自检 →
 *   现算 hash 生成 profile（**不含 node**）→ installAssembledRuntime → DSH 基线 →
 *   启动沙箱（node 已在位时才顺带启动 DSH）。
 * - node 流程：镜像探测 → SHASUMS256 锚定下载 → 解包剥前缀 → installNodeLayer
 *   （旧 node→previous，按在位层重建 profile）→ DSH 基线（缺失时）→ 启动沙箱与 DSH。
 *   要求 base 层已在位（先导入 Linux 层）。
 */
class OnlineRuntimeImportManager(
    private val appContext: Context,
    private val config: SandboxConfig,
    private val sandboxManager: SandboxManager,
) {
    private val tag = "OnlineRuntimeImport"

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + coroutineFailureHandler(tag),
    )

    @Volatile private var debianCancelled = false
    @Volatile private var nodeCancelled = false
    @Volatile private var activeGuestProcess: java.lang.Process? = null

    private val _debianState = MutableStateFlow(OnlineImportState())
    val debianState: StateFlow<OnlineImportState> = _debianState.asStateFlow()

    private val _nodeState = MutableStateFlow(OnlineImportState())
    val nodeState: StateFlow<OnlineImportState> = _nodeState.asStateFlow()

    private val bundleManager = BundleManager(config)
    private val processRunner = SandboxProcessRunner(config)
    private val cacheDir: File = config.appCacheDir ?: config.appFilesDir

    companion object {
        /** Debian 组装的磁盘护栏（全新装峰值 ~1.5GB；替换另加 previous 单副本）。 */
        const val MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024

        /** node 下载的磁盘护栏（tarball ~30MB + 解包 ~80MB + 余量）。 */
        const val NODE_MIN_FREE_BYTES = 512L * 1024 * 1024

        /** guest 命令标记（取消时按它定位并整树 SIGKILL）。 */
        const val GUEST_MARKER = "dshbox-online-assembly"

        /** 与 ensureGuestResolvConf 同值的公共 DNS（防 WSL 型坏地址在在线产物重现）。 */
        private val GUEST_DNS = listOf("114.114.114.114", "8.8.8.8", "223.5.5.5")

        /**
         * S8 安装期补件闸门：每项都代表一类会**静默失效**的功能，缺任何一个都不算
         * 装好——`/etc/profile` 管提示符与 PATH、passwd/group 管用户解析、
         * CA bundle 管 HTTPS、`/etc/alternatives/awk` 管 alternatives 链是否真建起来。
         */
        private val REQUIRED_POST_INSTALL_FILES = listOf(
            "etc/passwd",
            "etc/group",
            "etc/shadow",
            "etc/profile",
            "etc/shells",
            "etc/hostname",
            "etc/ca-certificates.conf",
            "etc/ssl/certs/ca-certificates.crt",
            "etc/alternatives/awk",
        )

        /** CA 证书 `.pem` 数量下限（trixie 的 mozilla 集为 150，留足版本漂移余量）。 */
        private const val MIN_CA_PEM_COUNT = 100

        /**
         * S8 dpkg 库闸门：`status` 里这几类字段的**行数下限**。
         *
         * 少写它们不会让组装失败，但会让 guest 里的包管理坏掉（apt 解析不了虚拟包与
         * `:any` 依赖、`dpkg --audit` 逐包报警），因此这里作为闸门固定住。
         * 下限取值参照随包层的 `Provides` 39、`Multi-Arch` 148，并留出余量。
         */
        private val REQUIRED_STATUS_FIELDS = mapOf(
            "Provides" to 30,
            "Multi-Arch" to 130,
            "Description" to 140,
            "Maintainer" to 140,
            "Section" to 140,
            "Installed-Size" to 140,
        )

        /** dpkg 包数下限（随包层为 159）。 */
        private const val MIN_DPKG_PACKAGE_COUNT = 155

        /** dpkg 自建元数据的代表项（缺则说明 S5b 未生效）。 */
        private val REQUIRED_DPKG_META = listOf(
            "var/lib/dpkg/info/format",
            "var/lib/dpkg/arch-native",
            "var/lib/dpkg/available",
            "var/lib/dpkg/alternatives/awk",
            "var/lib/dpkg/triggers/File",
            "var/lib/dpkg/status-old",
        )
    }

    // --------------------------------------------------------------- probing

    /** 并行探测全部 Debian archive 源（进页自动发起）；hasTarget=索引声明在位。 */
    suspend fun probeDebianSources(onEach: suspend (DebianArchiveSource, OnlineSourceProbe) -> Unit) =
        coroutineScope {
            val debArch = DebianSources.debArch(Build.SUPPORTED_64_BIT_ABIS.firstOrNull())
            for (source in DebianSources.ALL) {
                launch {
                    val probe = probeDebianOne(source, debArch)
                    withContext(Dispatchers.Main) { onEach(source, probe) }
                }
            }
        }

    private suspend fun probeDebianOne(
        source: DebianArchiveSource,
        debArch: String,
    ): OnlineSourceProbe = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        try {
            // 一次下载 Release 文本，可达性 + hasTarget 判定共用（不双发请求）。
            // 网络必须显式切 IO：本函数被 UI scope（Main）调用，直接跑会抛
            // NetworkOnMainThreadException：探测要遍历全部镜像源，留在主线程会直接抛异常。
            val body = downloadText(source.releaseUrl(DebianSources.SUITE))
            val hasTarget = body.contains("main/binary-$debArch/Packages.gz")
            OnlineSourceProbe(source.releaseUrl(DebianSources.SUITE), true, hasTarget,
                System.currentTimeMillis() - startedAt)
        } catch (t: Throwable) {
            Log.w(tag, "probe ${source.url} failed: $t")
            OnlineSourceProbe(source.releaseUrl(DebianSources.SUITE), false, false,
                System.currentTimeMillis() - startedAt,
                UiText.raw(t.message ?: t.toString()))
        }
    }

    /** 并行探测全部 node dist 源；hasTarget=SHASUMS256 含目标 tarball 行。 */
    suspend fun probeNodeSources(onEach: suspend (NodeDistSource, OnlineSourceProbe) -> Unit) =
        coroutineScope {
            val nodeArch = NodeSources.nodeArch(Build.SUPPORTED_64_BIT_ABIS.firstOrNull())
            for (source in NodeSources.ALL) {
                launch {
                    val probe = probeNodeOne(source, nodeArch)
                    withContext(Dispatchers.Main) { onEach(source, probe) }
                }
            }
        }

    private suspend fun probeNodeOne(
        source: NodeDistSource,
        nodeArch: String,
    ): OnlineSourceProbe = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val url = source.shasumsUrl(NodeSources.NODE_VERSION)
        try {
            val body = downloadText(url)
            val tarballName = "node-v${NodeSources.NODE_VERSION}-linux-$nodeArch.tar.xz"
            val hasTarget = body.contains(" $tarballName") || body.contains("*$tarballName")
            OnlineSourceProbe(url, true, hasTarget, System.currentTimeMillis() - startedAt)
        } catch (t: Throwable) {
            Log.w(tag, "probe $url failed: $t")
            OnlineSourceProbe(url, false, false, System.currentTimeMillis() - startedAt,
                UiText.raw(t.message ?: t.toString()))
        }
    }

    // ------------------------------------------------------- debian importing

    /** 双流程共享互斥：两条导入链都做 stopSandbox/staging 上位，交错运行不可预期。 */
    @Volatile private var importBusy = false

    fun startDebianImport(sources: List<DebianArchiveSource>) {
        if (_debianState.value.running || importBusy) return
        debianCancelled = false
        importBusy = true
        _debianState.value = OnlineImportState(
            running = true, stage = UiText.Res(R.string.online_stage_prepare),
            startedAtMs = System.currentTimeMillis(),
        )
        scope.launch {
            BackgroundOps.runTracked {
                try {
                    val result = runCatching { runDebianImport(sources) }.getOrElse { t ->
                        Log.w(tag, "debian import threw: ${t.message}", t)
                        AppResult.Failure(AppError("ONLINE_ATTEMPT_FAILED", t.message ?: "failed",
                            userMessage = UiText.Res(R.string.online_err_index, listOf(t.message ?: ""))))
                    }
                    if (result is AppResult.Failure) Log.w(tag, "debian import failed: ${result.error.code}")
                    _debianState.value = _debianState.value.copy(
                        running = false,
                        stage = if (result is AppResult.Success) UiText.Res(R.string.online_stage_done)
                        else UiText.Res(R.string.online_stage_failed),
                        progress = null, result = result, cancelled = debianCancelled,
                    )
                } finally {
                    activeGuestProcess = null
                    importBusy = false
                }
            }
        }
    }

    fun cancelDebianImport() {
        if (!_debianState.value.running) return
        debianCancelled = true
        appendLog(_debianState, "· debian import cancelled by user")
        scope.launch {
            processRunner.killAll(GUEST_MARKER)
            runCatching { activeGuestProcess?.destroyForcibly() }
        }
    }

    fun clearDebianResult() {
        if (!_debianState.value.running) _debianState.value = OnlineImportState()
    }

    private suspend fun runDebianImport(sources: List<DebianArchiveSource>): AppResult<Unit> =
        withContext(Dispatchers.IO) {
            val debArch = DebianSources.debArch(Build.SUPPORTED_64_BIT_ABIS.firstOrNull())
            setStage(_debianState, R.string.online_stage_prepare)
            sandboxManager.stopSandbox()
            val freeBytes = runCatching { StatFs(config.runtimeDir.absolutePath).availableBytes }
                .getOrDefault(Long.MAX_VALUE)
            if (freeBytes < MIN_FREE_BYTES) {
                return@withContext AppResult.Failure(AppError("ONLINE_LOW_STORAGE",
                    "insufficient storage (free ${freeBytes / (1024 * 1024)}MB)",
                    userMessage = UiText.Res(R.string.online_err_low_storage,
                        listOf("2", (freeBytes / (1024 * 1024 * 1024)).toString()))))
            }
            val manifest = try {
                appContext.assets.open("runtime-online/base-manifest.txt").use { BaseManifest.parse(it) }
            } catch (t: Throwable) {
                return@withContext AppResult.Failure(AppError("ONLINE_MANIFEST",
                    "manifest missing/unreadable: ${t.message}",
                    userMessage = UiText.Res(R.string.online_err_manifest)))
            }
            if (manifest.suite != DebianSources.SUITE || manifest.arch != debArch) {
                return@withContext AppResult.Failure(AppError("ONLINE_MANIFEST",
                    "manifest suite/arch mismatch: ${manifest.suite}/${manifest.arch}",
                    userMessage = UiText.Res(R.string.online_err_manifest)))
            }
            appendLog(_debianState, "· manifest ${manifest.suite}/${manifest.arch}: ${manifest.packages.size} packages")

            val staging = File(appContext.filesDir, "online-runtime-staging")
            val debCache = File(cacheDir, "online-deb-cache").apply { mkdirs() }
            // 半途被杀时 finally 不执行，staging 会残留半棵树；残留会让下一次解包
            // 撞 EEXIST。开工先清。
            staging.deleteRecursively()
            staging.mkdirs()
            try {
                var lastFailure: AppError? = null
                for (source in sources) {
                    if (debianCancelled) {
                        return@withContext AppResult.Failure(AppError("ONLINE_CANCELLED", "cancelled",
                            userMessage = UiText.Res(R.string.online_err_cancelled)))
                    }
                    appendLog(_debianState, "· source: ${source.url}")
                    val attempt = runCatching {
                        attemptDebianSource(source, manifest, debArch, staging, debCache)
                    }.getOrElse { t ->
                        AppResult.Failure(AppError("ONLINE_ATTEMPT_FAILED", t.message ?: "failed",
                            userMessage = UiText.Res(R.string.online_err_index, listOf(t.message ?: ""))))
                    }
                    if (attempt is AppResult.Success) return@withContext attempt
                    lastFailure = (attempt as AppResult.Failure).error
                    appendLog(_debianState, "· source failed: ${lastFailure.message}")
                    File(staging, "base").deleteRecursively()
                    File(staging, "android-side").deleteRecursively()
                    File(staging, "runtime-profile.json").delete()
                }
                AppResult.Failure(lastFailure ?: AppError("ONLINE_NO_SOURCE", "no usable source",
                    userMessage = UiText.Res(R.string.online_err_no_source)))
            } finally {
                staging.deleteRecursively()
            }
        }

    private suspend fun attemptDebianSource(
        source: DebianArchiveSource,
        manifest: BaseManifest,
        debArch: String,
        staging: File,
        debCache: File,
    ): AppResult<Unit> {
        val baseDir = File(staging, "base")
        val scratch = File(staging, "_scratch").apply { mkdirs() }

        // S2 索引：Release 文件 → GPG 验签（预埋 Debian 归档公钥，独立于 HTTPS 的信任
        // 链）→ Release 声明的 SHA256 锚定 Packages.gz。验签失败即拒源（fail-closed）。
        setStage(_debianState, R.string.online_stage_index)
        val releaseUrl = source.releaseUrl(DebianSources.SUITE)
        val releaseBytes = try {
            downloadBytes(releaseUrl)
        } catch (t: Throwable) {
            return AppResult.Failure(AppError("ONLINE_INDEX", "release fetch failed: ${t.message}",
                userMessage = UiText.Res(R.string.online_err_index, listOf(t.message ?: ""))))
        }
        val gpgBytes = try {
            downloadBytes("$releaseUrl.gpg")
        } catch (t: Throwable) {
            return AppResult.Failure(AppError("ONLINE_GPG", "Release.gpg fetch failed: ${t.message}",
                userMessage = UiText.Res(R.string.online_err_gpg)))
        }
        DebianReleaseVerifier.ensureProvider()
        if (!DebianReleaseVerifier.verify(releaseBytes, gpgBytes)) {
            return AppResult.Failure(AppError("ONLINE_GPG", "Release signature verification failed for ${source.url}",
                userMessage = UiText.Res(R.string.online_err_gpg)))
        }
        val releaseText = releaseBytes.decodeToString()
        val declaredSha = releaseShaOf(releaseText, "main/binary-$debArch/Packages.gz")
            ?: return AppResult.Failure(AppError("ONLINE_INDEX", "release has no SHA256 for Packages.gz",
                userMessage = UiText.Res(R.string.online_err_index, listOf(source.url))))
        val indexFile = File(scratch, "Packages.gz")
        try {
            downloadTo(source.packagesIndexUrl(DebianSources.SUITE, debArch), indexFile)
        } catch (t: Throwable) {
            return AppResult.Failure(AppError("ONLINE_INDEX", "index fetch failed: ${t.message}",
                userMessage = UiText.Res(R.string.online_err_index, listOf(t.message ?: ""))))
        }
        if (!bundleManager.verifySha256(indexFile, declaredSha)) {
            return AppResult.Failure(AppError("ONLINE_INDEX", "Packages.gz SHA256 mismatch vs Release",
                userMessage = UiText.Res(R.string.online_err_index, listOf("sha256"))))
        }
        val index = java.util.zip.GZIPInputStream(indexFile.inputStream()).use { gz ->
            PackagesIndex.parse(gz, manifest.packages.toSet())
        }
        val missing = index.missingFrom(manifest.packages)
        if (missing.isNotEmpty()) {
            return AppResult.Failure(AppError("ONLINE_MISSING", "packages missing: $missing",
                userMessage = UiText.Res(R.string.online_err_missing,
                    listOf(missing.take(5).joinToString(",")))))
        }

        // S3+S4：逐包下载（内容寻址缓存）→ 校验 → 解包。
        setStage(_debianState, R.string.online_stage_packages)
        val records = mutableListOf<DebUnpacker.Record>()
        val unpacker = DebUnpacker(bundleManager)
        val total = manifest.packages.size
        for ((i, name) in manifest.packages.withIndex()) {
            if (debianCancelled) {
                return AppResult.Failure(AppError("ONLINE_CANCELLED", "cancelled",
                    userMessage = UiText.Res(R.string.online_err_cancelled)))
            }
            val entry = index.entry(name)
                ?: return AppResult.Failure(AppError("ONLINE_MISSING", "$name vanished",
                    userMessage = UiText.Res(R.string.online_err_missing, listOf(name))))
            setStage(_debianState, R.string.online_stage_packages, "${i + 1}/$total $name")
            val cached = File(debCache, entry.sha256)
            if (!bundleManager.verifySha256(cached, entry.sha256)) {
                val tmp = File(scratch, "${entry.sha256}.part")
                try {
                    downloadTo("${source.url}/${entry.filename}", tmp)
                } catch (t: Throwable) {
                    return AppResult.Failure(AppError("ONLINE_DEB", "download $name failed: ${t.message}",
                        userMessage = UiText.Res(R.string.online_err_deb, listOf(name))))
                }
                if (!bundleManager.verifySha256(tmp, entry.sha256)) {
                    tmp.delete()
                    return AppResult.Failure(AppError("ONLINE_DEB", "sha mismatch: $name",
                        userMessage = UiText.Res(R.string.online_err_deb, listOf(name))))
                }
                tmp.renameTo(cached).let { renamed -> if (!renamed) tmp.copyTo(cached, overwrite = true) }
                tmp.delete()
            }
            records += unpacker.unpack(cached, baseDir, scratch)
        }

        // S5 dpkg 库（install ok installed——设备上 dpkg configure 不可行）。
        setStage(_debianState, R.string.online_stage_db)
        DpkgDbWriter.write(baseDir, records)
        // S5b dpkg 自建元数据（alternatives/triggers/diversions/available/arch-native…）：
        // 随包层有、合成流程不会产出的那一批，缺它们 apt/dpkg 的库不完整。
        DpkgDbFixtures(appContext.assets).apply(
            baseDir,
            debArch,
            DebianSources.debTriple(Build.SUPPORTED_64_BIT_ABIS.firstOrNull()),
        )

        // android-side 层就位。
        if (!prepareAndroidSide(staging)) {
            return AppResult.Failure(AppError("ONLINE_ASIDE", "cannot prepare android-side layer",
                userMessage = UiText.Res(R.string.online_err_verify, listOf("android-side"))))
        }

        // S6 配置补齐：①安装期产物回放 ②hostname ③CA 链 ④ldconfig 质量闸门。
        setStage(_debianState, R.string.online_stage_fixes)
        applyPostInstallFixes(baseDir)
        // 安装期产物回放（细节见 BootstrapFixtures）。
        val fixtures = BootstrapFixtures(appContext.assets)
        val fixtureStats = fixtures.apply(baseDir)
        fixtures.writeHostname(baseDir)
        appendLog(_debianState,
            "· bootstrap: files=${fixtureStats.files}, dirs=${fixtureStats.dirs}, " +
                "links=${fixtureStats.links}, kept=${fixtureStats.skipped}")
        // apt 源：写成组装时实际探测可达的镜像，而不是照抄随包层里那份——随包层那份的
        // 选源固化于构建时刻，镜像一旦不可达，guest 内 apt 会整体不可用。
        // 每次组装都重写，因此不存在"层更新即丢"的问题。
        writeAptSources(baseDir, source)
        // CA 证书链：缺失会让 guest 内所有 HTTPS 直接失败。
        if (!installCaCertificates(baseDir, scratch)) {
            return AppResult.Failure(AppError("ONLINE_CA", "CA store unavailable",
                userMessage = UiText.Res(R.string.online_err_verify, listOf("ca-certificates"))))
        }
        // 质量闸门：ldconfig 的退出码被 echo 吞掉，必须校验输出中的 RC 标记
        // （guest 命令整体退出码恒为 0，只看 runGuest 返回值会静默放过失败）。
        val ldOut = StringBuilder()
        if (!runGuest(baseDir, scratch,
                "echo $GUEST_MARKER; export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
                    "ldconfig 2>&1; echo LDCONFIG_RC=\$?",
            out = { line -> ldOut.appendLine(line) }
        ) || !ldOut.contains("LDCONFIG_RC=0")) {
            appendLog(_debianState, "· ldconfig output: ${ldOut.toString().trim().takeLast(200)}")
            return AppResult.Failure(AppError("ONLINE_LDCONFIG", "ldconfig failed",
                userMessage = UiText.Res(R.string.online_err_ldconfig)))
        }
        setStage(_debianState, R.string.online_stage_trim)
        val trimStats = TrimSpec(manifest.localeWhitelist).trim(baseDir)
        appendLog(_debianState, "· trim: locale -${trimStats.localeDirsRemoved}, doc -${trimStats.docFilesRemoved}")
        // python 字节码：py3compile 的等价物。只影响首次 import 速度，故尽力而为、不判死。
        val pyc = compilePythonBytecode(baseDir, scratch)
        appendLog(_debianState, "· pyc compiled: $pyc")

        // S8 自检：关键二进制在位 + 安装期补件闸门 + guest 冒烟。
        setStage(_debianState, R.string.online_stage_verify)
        val missingBins = manifest.smokeBinaries.filter { !File(baseDir, it.trimStart('/')).isFile }
        if (missingBins.isNotEmpty()) {
            return AppResult.Failure(AppError("ONLINE_VERIFY", "missing binaries: $missingBins",
                userMessage = UiText.Res(R.string.online_err_verify, listOf(missingBins.joinToString(",")))))
        }
        // 补件闸门：缺任一项都代表一类静默失效（提示符 / 用户解析 / HTTPS / alternatives）。
        val missingFixes = REQUIRED_POST_INSTALL_FILES.filter { !layerEntryExists(baseDir, it) }
        if (missingFixes.isNotEmpty()) {
            return AppResult.Failure(AppError("ONLINE_VERIFY", "post-install gaps: $missingFixes",
                userMessage = UiText.Res(R.string.online_err_verify, listOf(missingFixes.joinToString(",")))))
        }
        val pemCount = File(baseDir, "etc/ssl/certs").listFiles()?.count { it.name.endsWith(".pem") } ?: 0
        if (pemCount < MIN_CA_PEM_COUNT) {
            return AppResult.Failure(AppError("ONLINE_VERIFY", "CA store too small: $pemCount",
                userMessage = UiText.Res(R.string.online_err_verify, listOf("ca pem $pemCount"))))
        }
        // dpkg 库闸门：字段行数 + 包数 + 自建元数据，见 REQUIRED_STATUS_FIELDS 的说明。
        val dpkgGaps = verifyDpkgDb(baseDir)
        if (dpkgGaps.isNotEmpty()) {
            return AppResult.Failure(AppError("ONLINE_VERIFY", "dpkg db gaps: $dpkgGaps",
                userMessage = UiText.Res(R.string.online_err_verify, listOf(dpkgGaps.joinToString(",")))))
        }
        val smoke = StringBuilder()
        if (!runGuest(baseDir, scratch,
                "echo $GUEST_MARKER; export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
                    "/bin/bash -c 'echo BASH_OK' && /usr/bin/python3 --version 2>&1; echo SMOKE_RC=\$?",
                out = { line -> smoke.appendLine(line) }
            ) || !smoke.contains("BASH_OK") || !smoke.contains("SMOKE_RC=0")) {
            return AppResult.Failure(AppError("ONLINE_VERIFY", "guest smoke failed",
                userMessage = UiText.Res(R.string.online_err_verify, listOf("smoke"))))
        }

        // S9 定位（不含 node——node 层由独立入口导入）。
        setStage(_debianState, R.string.online_stage_seal)
        val baseSha = DirHash.sha256(baseDir)
        val asideSha = DirHash.sha256(File(staging, "android-side"))
        val builtAt = utcNow()
        File(staging, "runtime-profile.json").writeText(
            RuntimeProfileWriter.build(
                bundleVersion = "0.1.0", arch = debArch,
                baseVersion = "0.1.0", baseSha256 = baseSha, baseSizeBytes = DirHash.sizeBytes(baseDir),
                nodeVersion = null, nodeSha256 = null, nodeSizeBytes = null,
                asideVersion = "0.1.0", asideSha256 = asideSha,
                asideSizeBytes = DirHash.sizeBytes(File(staging, "android-side")),
                builtAtIsoUtc = builtAt,
            )
        )
        writeSentinel(baseDir, "base", baseSha)
        writeSentinel(File(staging, "android-side"), "android-side", asideSha)
        appendLog(_debianState, "· sealed: base=${baseSha.take(12)} (node layer not included)")

        // S10 上位 + DSH 基线 + 启动沙箱（node 在位才顺带启动 DSH）。
        setStage(_debianState, R.string.online_stage_install)
        when (val r = sandboxManager.installAssembledRuntime(staging)) {
            is AppResult.Failure -> return r
            is AppResult.Success -> Unit
        }
        setStage(_debianState, R.string.online_stage_dsh)
        provisionBundledDshBaseline(_debianState)
        sandboxManager.startSandbox()
        if (nodeLayerInPlace() && sandboxManager.sandboxState.value == SandboxState.RUNNING) {
            sandboxManager.startDsh()
        }
        return AppResult.Success(Unit)
    }

    // --------------------------------------------------------- node importing

    fun startNodeImport(sources: List<NodeDistSource>) {
        if (_nodeState.value.running || importBusy) return
        nodeCancelled = false
        importBusy = true
        _nodeState.value = OnlineImportState(
            running = true, stage = UiText.Res(R.string.online_stage_prepare),
            startedAtMs = System.currentTimeMillis(),
        )
        scope.launch {
            BackgroundOps.runTracked {
                try {
                    val result = runCatching { runNodeImport(sources) }.getOrElse { t ->
                        AppResult.Failure(AppError("ONLINE_ATTEMPT_FAILED", t.message ?: "failed",
                            userMessage = UiText.Res(R.string.online_err_node, listOf(t.message ?: ""))))
                    }
                    _nodeState.value = _nodeState.value.copy(
                        running = false,
                        stage = if (result is AppResult.Success) UiText.Res(R.string.online_stage_done)
                        else UiText.Res(R.string.online_stage_failed),
                        progress = null, result = result, cancelled = nodeCancelled,
                    )
                } finally {
                    activeGuestProcess = null
                    importBusy = false
                }
            }
        }
    }

    fun cancelNodeImport() {
        if (!_nodeState.value.running) return
        nodeCancelled = true
        appendLog(_nodeState, "· node import cancelled by user")
    }

    fun clearNodeResult() {
        if (!_nodeState.value.running) _nodeState.value = OnlineImportState()
    }

    private suspend fun runNodeImport(sources: List<NodeDistSource>): AppResult<Unit> =
        withContext(Dispatchers.IO) {
            val nodeArch = NodeSources.nodeArch(Build.SUPPORTED_64_BIT_ABIS.firstOrNull())
            setStage(_nodeState, R.string.online_stage_prepare)
            if (!baseLayerInPlace()) {
                return@withContext AppResult.Failure(AppError("NODE_NO_BASE",
                    "base layer not installed; import the Linux layer first",
                    userMessage = UiText.Res(R.string.online_err_need_debian)))
            }
            val freeBytes = runCatching { StatFs(config.runtimeDir.absolutePath).availableBytes }
                .getOrDefault(Long.MAX_VALUE)
            if (freeBytes < NODE_MIN_FREE_BYTES) {
                return@withContext AppResult.Failure(AppError("ONLINE_LOW_STORAGE",
                    "insufficient storage (free ${freeBytes / (1024 * 1024)}MB)",
                    userMessage = UiText.Res(R.string.online_err_low_storage,
                        listOf("0.5", (freeBytes / (1024 * 1024)).toString()))))
            }
            sandboxManager.stopSandbox()
            val staging = File(appContext.filesDir, "online-node-staging")
            try {
                staging.deleteRecursively(); staging.mkdirs()
                setStage(_nodeState, R.string.online_stage_node)
                val tarballName = "node-v${NodeSources.NODE_VERSION}-linux-$nodeArch.tar.xz"
                val tarball = File(staging, tarballName)
                var lastError = "no source"
                var assembled = false
                for (source in sources) {
                    if (nodeCancelled) {
                        return@withContext AppResult.Failure(AppError("ONLINE_CANCELLED", "cancelled",
                            userMessage = UiText.Res(R.string.online_err_cancelled)))
                    }
                    try {
                        val shasumsText = downloadText(source.shasumsUrl(NodeSources.NODE_VERSION))
                        val expected = Shasums256.parse(shasumsText)[tarballName]
                            ?: throw IOException("SHASUMS lacks $tarballName")
                        downloadTo(source.tarballUrl(NodeSources.NODE_VERSION, nodeArch), tarball)
                        if (!bundleManager.verifySha256(tarball, expected)) throw IOException("tarball sha mismatch")
                        val raw = File(staging, "node-raw").apply { deleteRecursively(); mkdirs() }
                        when (val r = bundleManager.extractTarGz(tarball, raw)) {
                            is AppResult.Failure -> throw IOException("extract failed: ${r.error.message}")
                            is AppResult.Success -> Unit
                        }
                        val inner = raw.listFiles()?.firstOrNull { it.isDirectory }
                            ?: throw IOException("unexpected node tarball layout")
                        val nodeDir = File(staging, "node").apply { deleteRecursively(); mkdirs() }
                        for (child in inner.listFiles() ?: emptyArray()) {
                            if (!child.renameTo(File(nodeDir, child.name))) {
                                child.copyRecursively(File(nodeDir, child.name), overwrite = true)
                            }
                        }
                        raw.deleteRecursively()
                        File(File(nodeDir, ".dshbox/env.d").apply { mkdirs() }, "node.sh").writeText(
                            "# L1 node (Node runtime is bound at /usr/local in the guest)\n" +
                                "export NODE_BIN=\"@NODE_BIN@\"\n",
                        )
                        assembled = true
                        break
                    } catch (t: Throwable) {
                        lastError = t.message ?: t.toString()
                        appendLog(_nodeState, "· node source failed: $lastError")
                        tarball.delete()
                    }
                }
                if (!assembled) {
                    return@withContext AppResult.Failure(AppError("ONLINE_NODE", "node fetch failed: $lastError",
                        userMessage = UiText.Res(R.string.online_err_node, listOf(lastError))))
                }
                setStage(_nodeState, R.string.online_stage_install)
                when (val r = sandboxManager.installNodeLayer(File(staging, "node"))) {
                    is AppResult.Failure -> return@withContext r
                    is AppResult.Success -> Unit
                }
                if (!File(config.runtimeDir, "runtime-current/dsh").isDirectory) {
                    setStage(_nodeState, R.string.online_stage_dsh)
                    provisionBundledDshBaseline(_nodeState)
                }
                sandboxManager.startSandbox()
                if (sandboxManager.sandboxState.value == SandboxState.RUNNING) {
                    sandboxManager.startDsh()
                }
                AppResult.Success(Unit)
            } finally {
                staging.deleteRecursively()
            }
        }

    // -------------------------------------------------------------- helpers

    /** Linux（精简 Debian）层是否已在位（node 页的前置防护用）。 */
    fun isDebianLayerInstalled(): Boolean = baseLayerInPlace()

    /**
     * node 层是否已在位（在线导入页的「安装状态」检测口用）。
     *
     * 与 [isDebianLayerInstalled] 刻意同口径：只认文件系统事实
     * （`runtime-current/node/bin/node`），**不掺** profile/权限校验。
     * 检测口要回答的是"这层装没装过"——若改用 `SandboxManager.isRuntimeInstalled()`
     * 的语义（proot + base + profile 一致 + 未损坏），会出现"明明装过、却显示未安装"
     * 的错位：用户眼前的判断依据是"我装过"，而不是运行时的整体健康状况。
     */
    fun isNodeLayerInstalled(): Boolean = nodeLayerInPlace()

    private fun baseLayerInPlace(): Boolean =
        File(config.runtimeDir, "runtime-current/base").isDirectory

    private fun nodeLayerInPlace(): Boolean =
        File(config.runtimeDir, "runtime-current/node/bin/node").isFile

    private fun setStage(state: MutableStateFlow<OnlineImportState>, res: Int, progress: String? = null) {
        state.value = state.value.copy(stage = UiText.Res(res), progress = progress)
    }

    private fun appendLog(state: MutableStateFlow<OnlineImportState>, line: String) {
        if (line.contains("WARNING: linker", ignoreCase = true) || line.contains("linkerconfig")) return
        state.value = state.value.copy(logs = (state.value.logs + line).takeLast(400))
    }

    private fun utcNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    private suspend fun provisionBundledDshBaseline(state: MutableStateFlow<OnlineImportState>) {
        val entries = runCatching { appContext.assets.list("dsh") }.getOrNull() ?: return
        val tarball = entries.firstOrNull { it.endsWith(".tar.zst") }
            ?: entries.firstOrNull { it.endsWith(".tar.gz") } ?: return
        val version = tarball.removeSuffix(".tar.zst").removeSuffix(".tar.gz").removeSuffix("-patched")
        val out = File(cacheDir, "online-dsh-baseline-$version.tar")
        try {
            appContext.assets.open("dsh/$tarball").use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            val sha = if (entries.contains("$tarball.sha256")) {
                appContext.assets.open("dsh/$tarball.sha256").use {
                    it.readBytes().decodeToString().trim().split(Regex("\\s+")).firstOrNull()
                }
            } else null
            when (val r = sandboxManager.updateDsh(out, sha, version)) {
                is AppResult.Success<DshUpdateOutcome> ->
                    appendLog(state, "· dsh baseline: ${if (r.value.changed) "installed ${r.value.version}" else "kept ${r.value.version}"}")
                is AppResult.Failure ->
                    appendLog(state, "· dsh baseline provision failed: ${r.error.message}")
            }
        } catch (t: Throwable) {
            appendLog(state, "· dsh baseline read failed: ${t.message}")
        } finally {
            out.delete()
        }
    }

    private suspend fun prepareAndroidSide(staging: File): Boolean = withContext(Dispatchers.IO) {
        val dest = File(staging, "android-side")
        val existing = File(config.runtimeDir, "runtime-current/android-side")
        if (existing.isDirectory) {
            existing.copyRecursively(dest, overwrite = true)
            return@withContext dest.isDirectory
        }
        return@withContext runCatching {
            val names = appContext.assets.list("runtime") ?: return@runCatching false
            val asset = names.firstOrNull { it.startsWith("android-side.tar.") } ?: return@runCatching false
            val tmp = File(staging, "_aside.tar")
            appContext.assets.open("runtime/$asset").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            val r = bundleManager.extractTarGz(tmp, dest)
            tmp.delete()
            r is AppResult.Success && dest.isDirectory
        }.getOrDefault(false)
    }

    /** 宿主侧安装期补齐：替代设备上不可行的 dpkg configure。 */
    private fun applyPostInstallFixes(baseDir: File) {
        val resolv = File(baseDir, "etc/resolv.conf")
        resolv.delete()
        resolv.parentFile?.mkdirs()
        resolv.writeText(GUEST_DNS.joinToString("") { "nameserver $it\n" })
        val zoneinfo = File(baseDir, "usr/share/zoneinfo/Etc/UTC")
        if (zoneinfo.isFile) {
            runCatching { symlink("/usr/share/zoneinfo/Etc/UTC", File(baseDir, "etc/localtime")) }
            File(baseDir, "etc/timezone").writeText("Etc/UTC\n")
        }
        // `usr/bin/awk` / `usr/bin/pager` 不再在这里手搓：它们本就是 alternatives 的
        // 产物（-> /etc/alternatives/<name>），由 BootstrapFixtures 的 links.map
        // 按随包层的真实拓扑重建，手搓版本反而会让 `awk` 直接指向 gawk 而丢掉中间层。
        val envDir = File(baseDir, ".dshbox/env.d").apply { mkdirs() }
        File(envDir, "base.sh").writeText(
            "# L0 base (Debian guest env)\n" +
                "export HOME=\"@HOME@\"\n" +
                "export TERM=\"@TERM@\"\n" +
                "export LANG=\"C.UTF-8\"\n" +
                "export PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\"\n" +
                "export DSH_PERMISSION_MODE=\"@DSH_PERMISSION_MODE@\"\n",
        )
    }

    /**
     * CA 证书链（ca-certificates postinst 的等价物）：跑 guest 的
     * `update-ca-certificates`，读 fixtures 铺好的 `/etc/ca-certificates.conf`，产出
     * `ca-certificates.crt` + 每证书 `.pem` + 哈希软链。工具链不可用时退回宿主侧拼接
     * （只保证 bundle——curl/openssl 默认读的就是它）。
     */
    private suspend fun installCaCertificates(baseDir: File, scratch: File): Boolean {
        val out = StringBuilder()
        runGuest(baseDir, scratch,
            "echo $GUEST_MARKER; export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
                "/usr/sbin/update-ca-certificates 2>&1; echo CA_RC=\$?",
            out = { line -> out.appendLine(line) },
        )
        val pemCount0 = File(baseDir, "etc/ssl/certs").listFiles()?.count { it.name.endsWith(".pem") } ?: 0
        var bundle = File(baseDir, "etc/ssl/certs/ca-certificates.crt")
        if (!out.contains("CA_RC=0") || !bundle.isFile || pemCount0 == 0) {
            appendLog(_debianState, "· update-ca-certificates 输出: ${out.toString().trim().takeLast(300)}")
            bundle = mergeCaBundle(baseDir)
        }
        val pemCount = File(baseDir, "etc/ssl/certs").listFiles()?.count { it.name.endsWith(".pem") } ?: 0
        appendLog(_debianState, "· ca: bundle=${bundle.isFile}, pem=$pemCount")
        return bundle.isFile && bundle.length() > 0
    }

    /**
     * 层内条目是否在位。不能用 `File.exists()`：`/etc/alternatives/awk -> /usr/bin/gawk`
     * 的目标是 guest 绝对路径，宿主侧解析必然落空；悬空软链同理。链接本身在即算在位。
     */
    private fun layerEntryExists(baseDir: File, rel: String): Boolean {
        val f = File(baseDir, rel)
        return f.exists() || Files.isSymbolicLink(f.toPath())
    }

    /**
     * 把 apt 源写成组装时实际探测可达的镜像（`deb <url> trixie main`）。
     *
     * 不能照抄随包层里那份 `sources.list`：它的选源固化于随包构建时刻，
     * 镜像一旦不可达，guest 内 apt 会整体不可用。这里每次组装都重写，
     * 因此不存在"层更新即丢"。
     */
    private fun writeAptSources(baseDir: File, source: DebianArchiveSource) {
        runCatching {
            val dir = File(baseDir, "etc/apt").apply { mkdirs() }
            val url = source.url.trimEnd('/')
            File(dir, "sources.list").writeText(
                "# 由 DSHBox 在线组装写入：本次实际跑通的 Debian 镜像\n" +
                    "deb $url ${DebianSources.SUITE} main\n",
            )
        }.onFailure { Log.w(tag, "write apt sources failed: ${it.message}") }
    }

    /**
     * dpkg 库自检：`status` 的字段行数与包数 + 自建元数据在位性。返回不满足项（空 = 通过）。
     *
     * 依据见 [REQUIRED_STATUS_FIELDS]：这些字段缺失时组装"成功"但 guest 内包管理坏掉，
     * 必须在 S8 就拦住，而不是等用户 `apt-get check` 报错。
     */
    private fun verifyDpkgDb(baseDir: File): List<String> {
        val status = File(baseDir, "var/lib/dpkg/status")
        if (!status.isFile) return listOf("status-missing")
        val text = runCatching { status.readText() }.getOrDefault("")
        val gaps = mutableListOf<String>()
        for ((field, min) in REQUIRED_STATUS_FIELDS) {
            val n = text.lineSequence().count { it.startsWith("$field:") }
            if (n < min) gaps += "$field=$n<$min"
        }
        val pkgs = text.lineSequence().count { it.startsWith("Package:") }
        if (pkgs < MIN_DPKG_PACKAGE_COUNT) gaps += "packages=$pkgs<$MIN_DPKG_PACKAGE_COUNT"
        gaps += REQUIRED_DPKG_META.filter { !layerEntryExists(baseDir, it) }.map { "meta:$it" }
        return gaps
    }

    /** 兜底：把 mozilla 下所有 `.crt` 拼成 bundle（update-ca-certificates 的核心动作）。 */
    private fun mergeCaBundle(baseDir: File): File {
        val out = File(baseDir, "etc/ssl/certs/ca-certificates.crt")
        out.parentFile?.mkdirs()
        runCatching {
            out.outputStream().buffered().use { sink ->
                File(baseDir, "usr/share/ca-certificates/mozilla")
                    .listFiles()?.sortedBy { it.name }?.forEach { crt ->
                        if (crt.isFile && crt.name.endsWith(".crt")) {
                            crt.inputStream().use { it.copyTo(sink) }
                        }
                    }
            }
        }.onFailure { Log.w(tag, "merge CA bundle failed: ${it.message}") }
        return out
    }

    /** Python 字节码（`py3compile` 的等价物），返回生成的 `.pyc` 数；失败不判死。 */
    private suspend fun compilePythonBytecode(baseDir: File, scratch: File): Int {
        if (!File(baseDir, "usr/bin/python3").isFile) return 0
        val out = StringBuilder()
        runGuest(baseDir, scratch,
            "echo $GUEST_MARKER; export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
                "for d in /usr/lib/python3.13 /usr/lib/python3/dist-packages /usr/share/python3; do " +
                "[ -d \$d ] && /usr/bin/python3 -m compileall -q -j 1 \$d >/dev/null 2>&1; done; " +
                "find /usr/lib/python3.13 /usr/lib/python3/dist-packages /usr/share/python3 -name '*.pyc' 2>/dev/null | wc -l",
            out = { line -> out.appendLine(line) },
        )
        return out.lineSequence().mapNotNull { it.trim().toIntOrNull() }.lastOrNull() ?: 0
    }

    private fun symlink(target: String, link: File) {
        runCatching { Os.symlink(target, link.absolutePath) }
            .onFailure { Log.w(tag, "symlink ${link.absolutePath} -> $target failed: ${it.message}") }
    }

    private fun writeSentinel(layerDir: File, layer: String, sha: String) {
        runCatching {
            val sentinel = File(File(layerDir, ".dshbox"), "layer-$layer.sha256")
            sentinel.parentFile?.mkdirs()
            sentinel.writeText(sha)
        }.onFailure { Log.w(tag, "write sentinel $layer failed: ${it.message}") }
    }

    /** guest 内跑一次性命令（PRoot `-0`，参照 TerminalCommandFactory 先例）。 */
    private suspend fun runGuest(
        baseRootfs: File,
        scratch: File,
        shellCommand: String,
        out: (String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val proot = prootBinary() ?: return@withContext false
        val cmd = buildList {
            add(proot.absolutePath)
            add("--rootfs=${baseRootfs.absolutePath}")
            add("-0")
            add("--bind=/system"); add("--bind=/apex"); add("--bind=/proc"); add("--bind=/dev")
            add("--cwd=/")
            add("--kill-on-exit")
            add("/system/bin/sh"); add("-c")
            add(shellCommand)
        }
        val env = mapOf(
            "LD_LIBRARY_PATH" to prootLibDir().absolutePath,
            "PROOT_LOADER" to prootLoaderFile().absolutePath,
            "PROOT_TMP_DIR" to File(scratch, "proot-tmp").apply { mkdirs() }.absolutePath,
            // ProcessBuilder 会继承宿主环境，其中的 TMPDIR 指向 Android 私有路径
            // （/data/user/0/<pkg>/cache），而 PRoot 没绑定那棵树：guest 里
            // mktemp/update-ca-certificates 之类会直接失败。临时目录一律改回 guest 的 /tmp。
            "TMPDIR" to "/tmp",
            "TMP" to "/tmp",
            "TEMP" to "/tmp",
        )
        val result = processRunner.runGuestCommand(
            cmd, env,
            onLine = { out(it) },
            onProcess = { process -> activeGuestProcess = process },
            shouldAbort = { debianCancelled },
        )
        result is AppResult.Success
    }

    private fun prootBinary(): File? {
        config.nativeLibraryDir?.let { dir ->
            val f = File(dir, "libproot.so")
            if (f.isFile) return f
        }
        File(config.runtimeDir, "runtime-current/android-side/bin/proot").takeIf { it.isFile }?.let { return it }
        File(config.appFilesDir, "online-runtime-staging/android-side/bin/proot").takeIf { it.isFile }?.let { return it }
        return null
    }

    private fun prootLibDir(): File {
        config.nativeLibraryDir?.let { dir ->
            if (File(dir, "libandroid-shmem.so").isFile) return File(dir)
        }
        File(config.runtimeDir, "runtime-current/android-side/lib").takeIf { it.isDirectory }?.let { return it }
        return File(config.appFilesDir, "online-runtime-staging/android-side/lib")
    }

    private fun prootLoaderFile(): File {
        config.nativeLibraryDir?.let { dir ->
            val f = File(dir, "libproot-loader.so")
            if (f.isFile) return f
        }
        File(config.runtimeDir, "runtime-current/android-side/libexec/proot/loader").takeIf { it.isFile }?.let { return it }
        return File(config.appFilesDir, "online-runtime-staging/android-side/libexec/proot/loader")
    }

    private fun releaseShaOf(releaseText: String, path: String): String? {
        var inSha = false
        for (raw in releaseText.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> inSha = false
                line.endsWith(":") -> inSha = line == "SHA256:"
                inSha -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 3 && parts[2] == path) return parts[0].lowercase()
                }
            }
        }
        return null
    }

    private fun downloadTo(url: String, target: File) {
        val conn = open(url, connectTimeoutMs = 15_000, readTimeoutMs = 60_000)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} for $url")
            conn.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadBytes(url: String): ByteArray {
        val conn = open(url, connectTimeoutMs = 15_000, readTimeoutMs = 60_000)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} for $url")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadText(url: String): String {
        val conn = open(url, connectTimeoutMs = 15_000, readTimeoutMs = 60_000)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} for $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, connectTimeoutMs: Long, readTimeoutMs: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs.toInt()
            readTimeout = readTimeoutMs.toInt()
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DSHBox/1.3")
            // 不主动 connect()：responseCode/getInputStream 懒触发（与 RuntimeUpdateManager 同口径）。
        }
}
