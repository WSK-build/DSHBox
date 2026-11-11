package com.dshbox.remote.session

import android.content.Context
import com.dshbox.remote.R
import com.dshbox.remote.model.Device
import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.Endpoint
import com.dshbox.remote.model.EndpointChoice
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.model.PairingTarget
import com.dshbox.remote.model.SessionMode
import com.dshbox.remote.model.effectiveSessionMode
import com.dshbox.remote.parse.ParserIds
import com.dshbox.remote.parse.ParsedPairing
import com.dshbox.remote.parse.UrlParserSupport
import com.dshbox.remote.proxy.AuthInjector
import com.dshbox.remote.proxy.AuthInjectors
import com.dshbox.remote.proxy.CookieJar
import com.dshbox.remote.proxy.DownloadPageRenderer
import com.dshbox.remote.proxy.GateCheck
import com.dshbox.remote.proxy.GateTokenStore
import com.dshbox.remote.proxy.LoopbackProxy
import com.dshbox.remote.proxy.ProxyStartException
import com.dshbox.remote.proxy.SayaChSessionInjector
import com.dshbox.remote.proxy.TlsTrust
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * 生产会话工厂：由宿主上下文组装设备存档、槽位分配器、凭据存档与回环代理。
 *
 * 槽位/端口承诺在此兑现：设备首次会话绑定槽位 IP；端点首次会话选取可用
 * 随机端口（避开退役名单）并在代理绑定成功后写入端点档案，此后终身复用。
 *
 * [gateDeliveryEnabled] 指示渲染引擎能否承载门禁 cookie：systemWebView 变体
 * 经进程级 CookieManager 下发；gecko 变体的 cookie 注入路径暂不可用，会话
 * 改用代理侧 bypass 门禁（无门禁运行，回环监听仍仅本机可达）。
 */
class DefaultSessionFactory(
    context: Context,
    private val gateDeliveryEnabled: Boolean = true,
    /** 下载目录（null = 无下载能力）：代理下载分支的落盘位置。 */
    private val downloadDir: File? = null,
    /** JVM 单测覆写：设备/槽位簿记存储来源（null = 生产 SharedPreferences）。 */
    storeOverride: (() -> KeyValueStore)? = null,
    /** JVM 单测覆写：凭据存档目录（context.filesDir 不可用的 JVM 环境）。 */
    vaultDirOverride: File? = null,
    /** JVM 单测覆写：凭据加密器（Android Keystore 不可用的 JVM 环境）。 */
    cipherOverride: VaultCipher? = null,
    /** 测试覆写：渲染器侧站点数据清理（null = 生产 WebView 清理实现）。 */
    private val cleanerOverride: SessionDataCleaner? = null,
    /** 下载结束回调（宿主拉起下载器/权限引导）：true = 落盘成功。 */
    private val onDownloadFinished: ((Boolean, String?) -> Unit)? = null,
    /**
     * saya-ch HTTP 传输工厂（生产默认真实 HTTPS；JVM 单测注入 fake 以覆盖
     * CA 引导/兑换/失败态——网络不进测试）。每端点兑换调用一次工厂。
     */
    private val sayaTransportFactory: () -> SayaChHttpTransport = { SayaChHttpTransport() },
) : SessionFactory, PairingArchiver {

    private val appContext: Context = context.applicationContext

    /**
     * 存档装配在构造期完成（与改造前的生产装配一致）：设备/槽位簿记存储、
     * 凭据存档目录与凭据加解密器的拼装并不延迟——[gateTokenStore] 等持
     * 凭据存档引用的组件在构造期即绑定，不加解密器惰性实例化的说法。JVM
     * 单测覆写 [storeOverride]/[vaultDirOverride]/[cipherOverride] 即可
     * 离线驱动建立流程；生产路径装配结果不因覆写入口的存在而改变。
     */
    private val store: KeyValueStore = storeOverride?.invoke()
        ?: SharedPreferencesStore.of(appContext, PREFERENCES_NAME)
    private val deviceStore: DeviceStore = DeviceStore(store)
    private val allocator: SlotAllocator = SlotAllocator(store)
    private val keystoreCipher: VaultCipher by lazy { AndroidKeystoreCipher() }
    private val vault: CredentialVault by lazy {
        CredentialVault(
            FileVaultStore.of(vaultDirOverride ?: File(appContext.filesDir, VAULT_DIR_NAME)),
            cipherOverride ?: keystoreCipher,
        )
    }
    private val gateTokenStore = GateTokenStore(vault)
    private val tls: TlsTrust by lazy { TlsTrust.systemDefault() }

    /**
     * 渲染器侧站点数据清理：测试覆写优先；生产恒用 WebView 清理实现
     * （gecko 变体的会话存储不在系统 WebView 中，清理抹除不到——结构上
     * 的已知限制，不再按引擎选择实现）。
     */
    private val cleaner: SessionDataCleaner by lazy {
        cleanerOverride ?: productionCleaner(appContext)
    }

    private fun productionCleaner(context: Context): SessionDataCleaner =
        com.dshbox.remote.render.WebViewSessionDataCleaner(context)

    /**
     * 设备生命周期锁：删除设备与会话侧的存档写入（jar 回写、门禁令牌落档）
     * 互斥。删除后的写入一律拒绝，保证「删除设备 = 销毁存档」不可被在途
     * 会话的延迟写复活。
     */
    private val deviceLifecycleLock = Any()

    /**
     * saya-ch 兑换的端点级互斥（键 = 端点标识）：网络请求在生命周期锁外执行
     * 后，同端点的并发会话建立以该锁防「重复兑换一次性票」——票单次兑换，
     * 第二次必然失败。表项随设备删除、端点删除、端点移动与重配对清理
     * （[sayaExchangeLocks] 只在上述生命周期事件中移除，平时只增）。
     */
    private val sayaExchangeLocks = HashMap<EndpointId, Any>()

    /**
     * 兑换成功但写前复查漂移（重配对/删除）时暂存的兑换产物：票已在网关侧
     * 消费，产物不落档但保留在恢复位——同端点未漂移的后续会话可直接采纳，
     * 不再耗票。设备删除时随条目清理。
     */
    private val orphanExchanges = HashMap<EndpointId, ExchangeOrphan>()

    /** 兑换成功但未落档的产物：generation 锁定配对代次，CA 与票同存。 */
    private data class ExchangeOrphan(
        val generation: Long,
        val result: SayaChPairResult,
        val caBase64: String?,
    )

    override fun devices(): List<Device> = deviceStore.devices()

    override fun createSession(deviceId: DeviceId, endpoint: Endpoint): ProxySession {
        // 直连端点：不占回环槽位与端口、不建 cookie 罐，仅校验设备在档后返回
        // 无代理会话（渲染器直接加载真实上游 origin，门禁与注入不适用）
        if (endpoint.sessionMode == SessionMode.DIRECT.name) {
            synchronized(deviceLifecycleLock) {
                if (deviceStore.find(deviceId) == null) {
                    throw SessionStartException("Device is removed: ${deviceId.value}")
                }
            }
            val origin = upstreamOriginOf(endpoint)
            // 直连会话以真实 origin 作为渲染器可信基准，而渲染器信任策略只对
            // https origin 放宽回环限制（AnyHttps + http 非回环是构造期非法
            // 配置）。会话层先行拒绝明文端点，避免非法 origin 深入渲染层才
            // 崩在 Compose 组合期；设备卡的非 https 直连选项一并禁用（UI 层
            // 双保险）。
            if (!origin.startsWith("https://")) {
                throw SessionStartException("Direct mode requires an https endpoint")
            }
            return DirectSession(
                entryUrl = endpoint.targetUrl.trim(),
                upstreamOrigin = origin,
            )
        }
        // 槽位首次分配（此前无绑定 = 本次新建绑定）：该 IP 可能刚从别的设备
        // 回收而来，先按新 IP 清一次 cookie（cookie URL 口径——此时本端点端
        // 口未知，不够成 origin 口径；设备删除路径在端口已记账后按 origin 兜
        // 底）。既有
        // 绑定（同设备重建会话）不清理——每次建立都清会把该设备自己的登录
        // 态 cookie（渲染器侧门禁/双写的非罐 cookie 属进程级）一并抹掉。
        val (boundIp, previouslyBound) = synchronized(store) {
            val prior = allocator.loopbackIpFor(deviceId)
            val binding = try {
                allocator.bindDevice(deviceId)
            } catch (e: SlotAllocationException) {
                throw SessionStartException(e.message ?: "Slot allocation failed", e)
            }
            binding.loopbackIp to (prior != null)
        }
        if (!previouslyBound) {
            val (origins, cookieUrls) = SessionCleanupTargets.forSlotFirstBind(boundIp)
            cleaner.clearOrigins(origins, cookieUrls)
        }
        val recordedPort = allocator.portForEndpoint(deviceId, endpoint.id)
        val pickedPort = recordedPort
            ?: try {
                PortPicker.Ephemeral
                    .pick(boundIp, allocator.retiredPorts(boundIp))
            } catch (e: SlotAllocationException) {
                throw SessionStartException(e.message ?: "Port pick failed", e)
            }
        val jar = loadJar(deviceId)
        val gateToken = synchronized(deviceLifecycleLock) {
            if (deviceStore.find(deviceId) == null) {
                throw SessionStartException("Device is removed: ${deviceId.value}")
            }
            gateTokenStore.tokenFor(deviceId)
        }
        // gecko 降级：门禁 cookie 无法注入渲染器，代理改用 bypass 校验（全放行），
        // 会话不向显示槽暴露令牌，渲染器加载不再等待门禁下发。
        val gate = if (gateDeliveryEnabled) GateCheck(gateToken) else GateCheck.bypass()
        val credential = synchronized(deviceLifecycleLock) {
            vault.loadCredentials(deviceId)[credentialKey(endpoint.id)]
        }
        // 一次性凭据已消费（兑换完成）的端点：不再重建含凭据的首次加载地址、
        // 注入器全程透明——把消费过的票带回配对页只会失败，已兑换出的会话
        // cookie 由罐自动注入。
        val consumed = synchronized(deviceLifecycleLock) {
            vault.isCredentialConsumed(deviceId, endpoint.id)
        }
        // 重连预检：罐里已持有该上游的认证会话 cookie 时不再重发凭据（token
        // 不进上游日志），由罐注入。
        val upstreamOrigin = upstreamOriginOf(endpoint)
        // saya-ch 通道：未兑换过会话时先做 native-pair 兑换（CA 引导→指纹核对
        // →兑换→落档），失败即会话建立失败——绝不静默建无凭据会话（无凭据
        // 请求只会被网关 302 到登录页，页面停留在加载态）。已兑换（vault 有会话
        // cookie）直接复用。
        if (endpoint.parserId == ParserIds.SAYA_CH) {
            ensureSayaSession(deviceId, endpoint, upstreamOrigin)
        }
        // 兑换可能刚落档 CA/会话凭据：重新读 vault，首次 LAN 会话必须能拿到
        // 引导落档的 CA（sessionTlsFor 的输入以兑换后的最新存档为准）。
        val credentials = synchronized(deviceLifecycleLock) { vault.loadCredentials(deviceId) }
        // saya-ch 通道的会话凭据读取（兑换后 vault 必有）：会话 cookie 写入
        // 设备罐统一注入——jar 是会话 cookie 的运行时权威（带 8h Max-Age，
        // 网关默认会话时长），vault 只作首次兑换存根（不回写覆盖罐）。
        // 罐里已有该会话 cookie（重连/未到期）时不重写：每次重建都按
        // now+8h 重算 Max-Age 会把剩余寿命悄悄拉满，与网关真实会话漂移。
        val sayaSession = if (endpoint.parserId == ParserIds.SAYA_CH) {
            synchronized(deviceLifecycleLock) { vault.loadSayaSession(deviceId, endpoint.id) }
        } else {
            null
        }
        if (sayaSession != null && !jar.containsCookie(upstreamOrigin, sayaSession.cookieName)) {
            val cookieLine = buildString {
                append(sayaSession.cookieName).append('=').append(sayaSession.cookieValue)
                append("; Path=/; Max-Age=").append(SAYA_SESSION_MAX_AGE_SECONDS)
                if (upstreamOrigin.startsWith("https://")) append("; Secure")
                append("; HttpOnly")
            }
            jar.storeAll(upstreamOrigin, listOf(cookieLine))
        }
        // dsh-mobile 的浏览器断线重连页只走 POST /mobile-access/auth/renew，
        // 且该路径用设备凭据 cookie（浏览器配对时下发）换新会话。电脑端会话
        // 只驻内存，网关重启后 App 的会话 cookie 全部失效、页面会退到重连页；
        // native-pair 不返回该 cookie，这里按路径补进罐，重连页才能自助恢复，
        // 而不是显示“此设备已不在配对列表中”。重配对后凭据变化，按值覆盖。
        if (endpoint.parserId == ParserIds.SAYA_CH) {
            val deviceToken = synchronized(deviceLifecycleLock) {
                vault.loadSayaDeviceToken(deviceId, endpoint.id)
            }
            if (deviceToken != null) {
                val renewUri = URI(upstreamOrigin + SAYA_DEVICE_COOKIE_PATH)
                if (jar.valueFor(renewUri, SAYA_DEVICE_COOKIE_NAME) != deviceToken) {
                    val cookieLine = buildString {
                        append(SAYA_DEVICE_COOKIE_NAME).append('=').append(deviceToken)
                        append("; Path=").append(SAYA_DEVICE_COOKIE_PATH)
                        append("; Max-Age=").append(SAYA_DEVICE_MAX_AGE_SECONDS)
                        if (upstreamOrigin.startsWith("https://")) append("; Secure")
                        append("; HttpOnly")
                    }
                    jar.storeAll(renewUri, listOf(cookieLine))
                }
            }
        }
        val sessionAuthenticated = when (endpoint.parserId) {
            // dsh-bridge：罐里已有其固定名的会话 cookie
            ParserIds.BRIDGE -> jar.containsCookie(upstreamOrigin, AuthInjectors.BRIDGE_AUTH_COOKIE)
            // saya-ch：罐里已有兑换出的 cookieName（vault 无档时退回 false）
            ParserIds.SAYA_CH -> sayaSession != null &&
                jar.containsCookie(upstreamOrigin, sayaSession.cookieName)
            else -> false
        }
        val sayaInjector = if (endpoint.parserId == ParserIds.SAYA_CH) {
            AuthInjectors.forParser(
                parserId = endpoint.parserId,
                credential = credential,
                credentialConsumed = consumed,
                sessionAlreadyAuthenticated = sessionAuthenticated,
                sayaCsrf = sayaSession?.csrfToken,
            ) as? SayaChSessionInjector
        } else {
            null
        }
        // CSRF 以罐内现值为准：页面自助续期（重连页）会轮换 CSRF，静态存档
        // 值只在罐里还没有新值时兜底，避免续期后写请求仍带旧值被 403。
        sayaInjector?.csrfProvider = { target ->
            runCatching {
                val uri = URI(upstreamOrigin + target)
                jar.valueFor(uri, SAYA_CSRF_COOKIE_NAME)
                    ?: jar.valueFor(uri, SAYA_HOST_CSRF_COOKIE_NAME)
            }.getOrNull()
        }
        val sayaRenew: (() -> Boolean)? = if (sayaInjector != null && sayaSession != null) {
            val deviceToken = synchronized(deviceLifecycleLock) {
                vault.loadSayaDeviceToken(deviceId, endpoint.id)
            }
            val caForRenew = credentials[vault.caCertificateKey(endpoint.id)]
            val lastSayaRenewedAt = java.util.concurrent.atomic.AtomicLong(0L)
            if (deviceToken == null) {
                null
            } else {
                {
                    // 同端点串行续期：并发 401 只兑换一次，其余请求等新 cookie。
                    // 5 秒内已续期则直接放行重试，避免重复换票把前一张 session
                    // 顶掉（网关有会话数上限，新换会淘汰旧会话）
                    val renewMutex = synchronized(sayaExchangeLocks) {
                        sayaExchangeLocks.getOrPut(endpoint.id) { Any() }
                    }
                    synchronized(renewMutex) {
                        val now = System.currentTimeMillis()
                        if (now - lastSayaRenewedAt.get() < 5_000L) {
                            return@synchronized true
                        }
                        val renewedOk = runCatching {
                            val renewed = sayaTransportFactory().renew(upstreamOrigin, deviceToken, caForRenew)
                            synchronized(deviceLifecycleLock) {
                                val current = deviceStore.find(deviceId)?.endpoints
                                    ?.find { it.id == endpoint.id }
                                // 设备删除或重配对代次漂移：旧 deviceToken 换出的
                                // session 不得写进新档
                                if (current == null || current.generation != endpoint.generation) {
                                    return@runCatching false
                                }
                                val previous = vault.loadCredentials(deviceId)
                                val writes = buildMap {
                                    putAll(previous)
                                    put(vault.sayaCookieNameKey(endpoint.id), renewed.cookieName)
                                    put(vault.sayaCookieKey(endpoint.id), renewed.cookieValue)
                                    put(vault.sayaCsrfKey(endpoint.id), renewed.csrfToken)
                                    renewed.sessionExpiresAt?.let {
                                        put(vault.sayaSessionExpiresKey(endpoint.id), it.toString())
                                    }
                                }
                                val maxAge = renewed.sessionExpiresAt
                                    ?.let { ((it - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L) }
                                    ?: SAYA_SESSION_MAX_AGE_SECONDS
                                val cookieLine = buildString {
                                    append(renewed.cookieName).append('=').append(renewed.cookieValue)
                                    append("; Path=/; Max-Age=").append(maxAge)
                                    if (upstreamOrigin.startsWith("https://")) append("; Secure")
                                    append("; HttpOnly")
                                }
                                try {
                                    vault.saveCredentials(deviceId, writes)
                                    jar.storeAll(upstreamOrigin, listOf(cookieLine))
                                    sayaInjector.updateCsrfToken(renewed.csrfToken)
                                } catch (error: Exception) {
                                    // 档案与罐保持旧值，避免新 csrf + 旧 cookie 的半写
                                    runCatching { vault.saveCredentials(deviceId, previous) }
                                    throw error
                                }
                            }
                            true
                        }.getOrDefault(false)
                        if (renewedOk) lastSayaRenewedAt.set(now)
                        renewedOk
                    }
                }
            }
        } else {
            null
        }
        val proxy = LoopbackProxy(
            loopbackIp = boundIp,
            requestedPort = pickedPort,
            upstreamOrigin = upstreamOrigin,
            jar = jar,
            gate = gate,
            injector = sayaInjector ?: AuthInjectors.forParser(
                parserId = endpoint.parserId,
                credential = credential,
                credentialConsumed = consumed,
                sessionAlreadyAuthenticated = sessionAuthenticated,
                sayaCsrf = sayaSession?.csrfToken,
            ),
            // 会话级 TLS 信任：自签上游（JSON 配对描述符下发 CA）按端点载入
            // 其会话 CA，不污染全局信任；其余端点沿用系统默认信任
            tls = sessionTlsFor(deviceId, endpoint, credentials),
            // saya-ch 会话过期自动续期：deviceToken 换新 session/csrf 后，
            // 代理在同一连接内对 GET/HEAD 重发一次；非幂等写请求保留原 401，
            // 403 不续期；续期失败保留原失败态
            onAuthExpired = sayaRenew,
            // 宿主下载目录与结果页文案：成对提供（null = 无下载能力，响应照旧透传）
            downloadDir = downloadDir,
            downloadPages = downloadDir?.let { remoteDownloadPages() },
            onDownloadFinished = onDownloadFinished,
        )
        return object : ProxySession {
            override val loopbackOrigin: String
                get() = proxy.loopbackOrigin

            override val gateToken: String?
                get() = gateToken.takeIf { gateDeliveryEnabled }

            override val initialLoadUrl: String?
                get() = SessionInitialLoad.resolve(endpoint.parserId, proxy.loopbackOrigin, credential, consumed)

            // 上游 origin 与代理转发目标共用同一来源（出站改写的归一化基准），
            // 页内链接的就地打开判定不会与实际转发目标漂移
            override val upstreamOrigin: String?
                get() = proxy.upstreamOriginText

            override fun start() {
                try {
                    proxy.start()
                } catch (e: ProxyStartException) {
                    throw SessionStartException(e.message ?: "Proxy start failed", e)
                }
                if (recordedPort == null) {
                    allocator.recordPort(deviceId, endpoint.id, proxy.boundPort)
                }
            }

            override fun stop() {
                proxy.stop()
            }
        }
    }

    override fun removeDevice(deviceId: DeviceId) {
        // 清理目标在销毁存档前读取（槽位/端口档案销毁后无从计算）：
        // 回环 cookie URL + 各代理端点的回环 origin（端口已记账的端点）+
        // 直连端点的真实上游 origin 与其 cookie URL；去重后交渲染器侧清
        // 理。清理失败的尽力语义：接口实现不抛，销毁流程不受影响。
        val cleanup = synchronized(deviceLifecycleLock) {
            val device = deviceStore.find(deviceId)
            val boundIp = allocator.loopbackIpFor(deviceId)
            val targets = device?.let { d ->
                SessionCleanupTargets.forDeviceRemoval(
                    targetUrls = d.endpoints.map { it.targetUrl },
                    sessionModes = d.endpoints.map { it.sessionMode },
                    boundIp = boundIp,
                    portFor = { index ->
                        d.endpoints.getOrNull(index)?.let { allocator.portForEndpoint(deviceId, it.id) }
                    },
                )
            } ?: (emptyList<String>() to emptyList<String>())
            Triple(device to boundIp, targets.first, targets.second)
        }
        val (archive, origins, cookieUrls) = cleanup
        synchronized(deviceLifecycleLock) {
            deviceStore.delete(deviceId)
            vault.destroyDevice(deviceId)
            allocator.releaseDevice(deviceId)
        }
        // 锁表与恢复位随设备销毁清理（表项只增不删会随设备数无界增长）
        val removedIds = archive.first?.endpoints?.map { it.id }.orEmpty()
        synchronized(sayaExchangeLocks) {
            removedIds.forEach { sayaExchangeLocks.remove(it) }
            orphanExchanges.keys.removeAll(removedIds.toSet())
        }
        if (archive.first != null && (origins.isNotEmpty() || cookieUrls.isNotEmpty())) {
            cleaner.clearOrigins(origins, cookieUrls)
        }
    }

    /**
     * 设备级 jar：无存档则建新罐，任何写入即时加密落盘。回写前在生命周期锁
     * 内复查设备在档：删除设备后，在途会话经上游 Set-Cookie 触发的延迟写
     * 不得向已销毁的存档重建条目。
     */
    private fun loadJar(deviceId: DeviceId): CookieJar {
        val jar = vault.loadCookieJar(deviceId) ?: CookieJar()
        jar.onChangeListener = {
            synchronized(deviceLifecycleLock) {
                if (deviceStore.find(deviceId) != null) {
                    vault.saveCookieJar(deviceId, jar)
                }
            }
        }
        return jar
    }

    /** 端点目标地址的 origin 部分（scheme+authority）；非法地址显式报错。 */
    private fun upstreamOriginOf(endpoint: Endpoint): String {
        val uri = runCatching { URI(endpoint.targetUrl.trim()) }.getOrElse {
            throw SessionStartException("Endpoint target is not a valid URI: ${endpoint.id.value}", it)
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if ((scheme != "http" && scheme != "https") || host.isNullOrBlank()) {
            throw SessionStartException("Endpoint target must be an absolute http(s) URL: ${endpoint.id.value}")
        }
        val defaultPort = if (scheme == "https") 443 else 80
        return buildString {
            append(scheme).append("://").append(host.lowercase())
            if (uri.port != -1 && uri.port != defaultPort) append(':').append(uri.port)
        }
    }

    /**
     * saya-ch 通道的会话准备：vault 已有兑换出的会话 cookie 直接复用；否则
     * 走「CA 引导 → 指纹核对 → native-pair 兑换 → 落档」流程：
     * 1. CA：优先用端点存档 CA（JSON 描述符建档即有）；没有则 [SayaChHttpTransport.fetchCa]
     *    引导（LAN 自签网关下发；公网证书入口 404 得 null，用系统信任）。引导
     *    得到的 CA 写入 `cacert.<ep>`，供后续会话 [sessionTlsFor] 读取。
     * 2. 指纹核对：端点的 pairingInstance（配对链接 `instance` / 描述符
     *    `instanceId`，比对口径与解析层一致）在「使用了引导/存档 CA」时必填
     *    且必须命中——LAN 自签入口没有 instance 就没有信任依据，明确失败；
     *    仅公网 404（系统信任）允许缺 instance。
     * 3. 兑换：凭据存档里的一次性票（配对链接 `token`）经 native-pair 换
     *    会话 cookie + CSRF；成功后 CA + 会话三元组 + 消费标记一次合并落档。
     * 任何失败抛 [SessionStartException]，明确「配对兑换失败/CA 指纹不符」，
     * 不静默继续建无凭据会话。
     *
     * 并发与锁纪律：网络请求（CA 引导与兑换 POST）在 [deviceLifecycleLock]
     * 之外执行；同端点经 [sayaExchangeLocks] 互斥（防并发重复兑换一次性票）。
     * 兑换前再校验一次「设备在档 + 端点代次未变」——一次性票发出去就可能被
     * 消费，漂移场景提前失败可以省一张票。写档为**单次合并** `saveCredentials`
     * （CA + 四键 + consumed 一起），不留半写状态；恢复位采纳的复查与写档、
     * 兑换产物的写前复查与落档分别是同一临界区（见 [deviceLifecycleLock]
     * 段内注释），重配对无法插进复查与写档之间。
     */
    private fun ensureSayaSession(deviceId: DeviceId, endpoint: Endpoint, upstreamOrigin: String) {
        val endpointMutex = synchronized(sayaExchangeLocks) {
            sayaExchangeLocks.getOrPut(endpoint.id) { Any() }
        }
        synchronized(endpointMutex) {
            // 恢复位采纳：上次兑换成功但写档漂移的产物，端点未再漂移时直接
            // 落档复用（不再耗票）
            val orphan = synchronized(sayaExchangeLocks) { orphanExchanges[endpoint.id] }
            if (orphan != null) {
                // 代次预检（只读）：代次不匹配的孤产物是旧配对遗留的死数据，
                // 先清掉再回落正常兑换——LAN 无 CA 的致命门禁只应拦「同代次
                // 却无法自恢复」的孤产物，不能把已重配对端点的重新兑换永久
                // 堵死。权威复查仍在下方写档临界区内。
                val orphanMatches = synchronized(deviceLifecycleLock) {
                    deviceStore.find(deviceId)?.endpoints
                        ?.find { it.id == endpoint.id }
                        ?.let { it.generation == orphan.generation } == true
                }
                if (!orphanMatches) {
                    synchronized(sayaExchangeLocks) { orphanExchanges.remove(endpoint.id) }
                } else {
                    // LAN 自签端点没有 CA 的孤产物不可采纳：sessionTlsFor 会失败，
                    // 票已耗、无法自行恢复——明确要求重新配对，不再做必然失败的兑换
                    val lanNeedsCa = orphan.caBase64 == null &&
                        UrlParserSupport.isPrivateHost(hostOf(upstreamOrigin))
                    if (lanNeedsCa) {
                        throw SessionStartException(
                            "saya-ch pairing session cannot be restored: CA is missing, " +
                                "please pair again (${endpoint.id.value})",
                        )
                    }
                    // 采纳与代次复查在同一个生命周期锁临界区内完成：重配对无法
                    // 插进「复查通过」与「写档」之间把旧票产物写进新档
                    val adopted = try {
                        synchronized(deviceLifecycleLock) {
                            val current = deviceStore.find(deviceId)?.endpoints
                                ?.find { it.id == endpoint.id }
                            if (current == null || current.generation != orphan.generation) {
                                // 临界区内复查漂移（预检后并发重配对/删除）：
                                // 孤产物随重配对清理路径移除，这里再清一次兜底
                                synchronized(sayaExchangeLocks) { orphanExchanges.remove(endpoint.id) }
                                false
                            } else {
                                val writes = buildMap {
                                    putAll(vault.loadCredentials(deviceId))
                                    orphan.caBase64?.let { put(vault.caCertificateKey(endpoint.id), it) }
                                    put(vault.sayaCookieNameKey(endpoint.id), orphan.result.cookieName)
                                    put(vault.sayaCookieKey(endpoint.id), orphan.result.cookieValue)
                                    put(vault.sayaCsrfKey(endpoint.id), orphan.result.csrfToken)
                                    orphan.result.deviceToken?.let {
                                        put(vault.sayaDeviceTokenKey(endpoint.id), it)
                                    }
                                    orphan.result.sessionExpiresAt?.let {
                                        put(vault.sayaSessionExpiresKey(endpoint.id), it.toString())
                                    }
                                    put(vault.consumedKey(endpoint.id), "1")
                                }
                                vault.saveCredentials(deviceId, writes)
                                true
                            }
                        }
                    } catch (error: Exception) {
                        // 写档失败保留恢复位，下一次会话再试
                        throw SessionStartException(
                            "saya-ch pairing session persistence failed (${endpoint.id.value})",
                            error,
                        )
                    }
                    if (adopted) {
                        synchronized(sayaExchangeLocks) { orphanExchanges.remove(endpoint.id) }
                        return
                    }
                    // 临界区内复查漂移：孤产物已清，回落下方正常兑换流程
                }
            }
            // 前置读（持锁）：已兑换即复用；票/instance 一并读出
            val precondition = synchronized(deviceLifecycleLock) {
                if (deviceStore.find(deviceId) == null) {
                    throw SessionStartException("Device is removed: ${deviceId.value}")
                }
                if (vault.loadSayaSession(deviceId, endpoint.id) != null) return
                val credentials = vault.loadCredentials(deviceId)
                Triple(
                    credentials[credentialKey(endpoint.id)],
                    vault.loadSayaPairingInstance(deviceId, endpoint.id),
                    credentials[vault.caCertificateKey(endpoint.id)],
                )
            }
            val (token, pairingInstance, archivedCa) = precondition
            if (token == null) {
                throw SessionStartException(
                    "saya-ch pairing exchange failed: pairing token is missing (${endpoint.id.value})",
                )
            }
            // 兑换前复查（持锁）：设备在档 + 端点代次未变——漂移场景在消耗
            // 一次性票之前显式失败
            assertSayaEndpointCurrent(deviceId, endpoint)
            val transport = sayaTransportFactory()
            // CA：存档优先；否则引导（网络请求在生命周期锁外）
            var caBase64: String? = archivedCa
            if (caBase64 == null) {
                caBase64 = transport.fetchCa(upstreamOrigin)
            }
            // 指纹核对（安全）：任何非系统信任路径（引导/存档 CA）都要求
            // pairingInstance 存在且命中——缺失与不匹配同语义拒绝，防「无凭
            // 据信任一份未经核对的 CA」；仅公网 404（系统信任）允许缺 instance
            if (caBase64 != null) {
                if (pairingInstance.isNullOrBlank()) {
                    throw SessionStartException(
                        "saya-ch CA fingerprint is missing for endpoint ${endpoint.id.value} " +
                            "(self-signed gateway requires pairing instance)",
                    )
                }
                if (!SayaChHttpTransport.fingerprintMatches(caBase64, pairingInstance)) {
                    throw SessionStartException(
                        "saya-ch CA fingerprint mismatch for endpoint ${endpoint.id.value}",
                    )
                }
            }
            // 兑换（网络请求在生命周期锁外）——此后票已在网关侧被消费
            val result = try {
                transport.postNativePair(upstreamOrigin, token, caBase64)
            } catch (error: SayaChPairException) {
                throw SessionStartException(
                    "saya-ch pairing exchange failed: ${error.message}",
                    error,
                )
            } catch (error: java.net.UnknownHostException) {
                throw SessionStartException(
                    "无法解析配对主机：请检查手机网络，或二维码里的地址是否仍有效",
                    error,
                )
            } catch (error: IOException) {
                throw SessionStartException(
                    "saya-ch pairing exchange failed: network error (${error.javaClass.simpleName})",
                    error,
                )
            }
            // 写前复查 + 落档在同一个生命周期锁临界区内完成：重配对无法插进
            // 「复查通过」与「写档」之间；漂移/删除时票已在网关侧消费，产物
            // 留在恢复位（带 generation + CA），同端点未漂移的后续会话可采纳。
            val persisted = try {
                synchronized(deviceLifecycleLock) {
                    val current = deviceStore.find(deviceId)?.endpoints
                        ?.find { it.id == endpoint.id }
                    if (current == null || current.generation != endpoint.generation) {
                        false
                    } else {
                        val writes = buildMap {
                            putAll(vault.loadCredentials(deviceId))
                            caBase64?.let { put(vault.caCertificateKey(endpoint.id), it) }
                            put(vault.sayaCookieNameKey(endpoint.id), result.cookieName)
                            put(vault.sayaCookieKey(endpoint.id), result.cookieValue)
                            put(vault.sayaCsrfKey(endpoint.id), result.csrfToken)
                            result.deviceToken?.let {
                                put(vault.sayaDeviceTokenKey(endpoint.id), it)
                            }
                            result.sessionExpiresAt?.let {
                                put(vault.sayaSessionExpiresKey(endpoint.id), it.toString())
                            }
                            put(vault.consumedKey(endpoint.id), "1")
                        }
                        vault.saveCredentials(deviceId, writes)
                        true
                    }
                }
            } catch (error: Exception) {
                synchronized(sayaExchangeLocks) {
                    orphanExchanges[endpoint.id] = ExchangeOrphan(
                        generation = endpoint.generation,
                        result = result,
                        caBase64 = caBase64,
                    )
                }
                throw SessionStartException(
                    "saya-ch pairing session persistence failed (${endpoint.id.value})",
                    error,
                )
            }
            if (!persisted) {
                synchronized(sayaExchangeLocks) {
                    orphanExchanges[endpoint.id] = ExchangeOrphan(
                        generation = endpoint.generation,
                        result = result,
                        caBase64 = caBase64,
                    )
                }
                throw SessionStartException(
                    "saya-ch pairing exchange discarded: endpoint was re-paired or removed " +
                        "(${endpoint.id.value}); the exchanged session is kept for the current pairing",
                )
            }
            synchronized(sayaExchangeLocks) { orphanExchanges.remove(endpoint.id) }
        }
    }

    /**
     * 端点的 saya-ch 会话是否已兑换（vault 持有兑换产物存根）：渲染层在
     * 会话 8 小时到期后 401 时据此给出「会话已过期，请重新配对」失败态。
     */
    fun sayaSessionExchanged(endpointId: EndpointId): Boolean =
        synchronized(deviceLifecycleLock) {
            deviceIdFor(endpointId)?.let { vault.loadSayaSession(it, endpointId) != null } == true
        }

    /** 端点标识 → 所属设备（清单扫描；不在档返回 null）。 */
    private fun deviceIdFor(endpointId: EndpointId): DeviceId? =
        deviceStore.devices().firstOrNull { device -> device.endpoints.any { it.id == endpointId } }?.id

    /**
     * 复查「设备在档 + 端点代次未变」；漂移即抛 [SessionStartException]。
     * [discardingTicket] 仅用于文案区分（兑换前= 票未消耗可重试；写前= 票已
     * 消耗、产物保留在恢复位）。
     */
    private fun assertSayaEndpointCurrent(deviceId: DeviceId, endpoint: Endpoint) {
        val current = synchronized(deviceLifecycleLock) {
            deviceStore.find(deviceId)?.endpoints?.find { it.id == endpoint.id }
        }
        when {
            current == null -> throw SessionStartException(
                "saya-ch pairing exchange discarded: endpoint is gone (${endpoint.id.value})",
            )
            current.generation != endpoint.generation -> throw SessionStartException(
                "saya-ch pairing exchange discarded: endpoint was re-paired (${endpoint.id.value})",
            )
        }
    }

    /**
     * 会话级 TLS 信任（saya-ch 通道）：存档有 CA（JSON 描述符建档即有，或
     * 兑换引导所得）→ 仅含该 CA 的独立信任链；无存档 CA 时按目标地址区分：
     * 公网（非私网）证书入口 → 合法回落系统默认信任；LAN 私网（自签）没有
     * CA 属非法状态（兑换流程本应落档），明确失败而非静默系统信任。其余
     * 解析器端点沿用系统默认信任。
     */
    private fun sessionTlsFor(
        deviceId: DeviceId,
        endpoint: Endpoint,
        credentials: Map<String, String>,
    ): TlsTrust {
        if (endpoint.parserId != ParserIds.SAYA_CH) return tls
        val base64 = credentials[vault.caCertificateKey(endpoint.id)]
            ?: run {
                // 无存档 CA：公网入口合法回落系统信任；私网（LAN 自签）必须有 CA
                if (!UrlParserSupport.isPrivateHost(hostOf(upstreamOriginOf(endpoint)))) return tls
                throw SessionStartException(
                    "saya-ch session CA certificate is missing for a self-signed gateway: ${endpoint.id.value}",
                )
            }
        val certificate = runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(Base64.getMimeDecoder().decode(base64).inputStream()) as X509Certificate
        }.getOrElse {
            throw SessionStartException("Session CA certificate is not decodable: ${endpoint.id.value}", it)
        }
        return TlsTrust.custom(listOf(certificate))
    }

    private fun hostOf(targetUrl: String): String? = runCatching { URI(targetUrl).host }.getOrNull()

    /**
     * 配对建档：设备与端点生成全局唯一标识（端点代次归零），非空凭据按
     * 端点写入加密存档（明文只入存档，零日志零清单落盘）；载荷携带的会话
     * 级 CA 证书同存端点键值对（随设备删除销毁）。确认卡的选择（会话模式
     * 与保留档覆盖）随建档一次写入端点档案；会话模式一律经直连口径函数
     * 收口（非 https 目标的直连选择/声明回落代理档，`非 https + 直连`
     * 死档不得经建档出口落进档案），保留档覆盖原样透传（null = 继承全局）。
     *
     * 归属目标 [target]：新建设备（既有行为，[PairingTarget.NewGroup]）/
     * 并入既有设备（[PairingTarget.Existing]，设备不在档时抛异常）/ 并入
     * 「无分组」（[PairingTarget.Ungrouped]，名称为空串的档案条目，没有则
     * 现建）。并入目标的端点追加在目标设备档案末尾，凭据写入该设备的加密
     * 存档（并入目标的登录态语义与其余端点一致）。
     */
    override fun archivePairing(
        pairing: ParsedPairing,
        displayName: String?,
        choices: List<EndpointChoice>?,
        target: PairingTarget,
    ): Device {
        // 并入目标先解析出宿主设备：Existing 指向的设备与 Ungrouped 的空名
        // 条目（无则现建）。找不到即失败——设备可能在确认期间被删除，不静默
        // 改道新建设备
        val host: Device? = when (target) {
            is PairingTarget.Existing -> synchronized(deviceLifecycleLock) {
                deviceStore.find(target.deviceId)
            }
            PairingTarget.Ungrouped -> synchronized(deviceLifecycleLock) {
                deviceStore.devices().firstOrNull { it.name.isEmpty() }
            }
            is PairingTarget.NewGroup -> null
        }
        val endpoints = pairing.endpoints.mapIndexed { index, parsed ->
            // 错位/缺失按解析默认（载荷声明的会话模式、继承全局保留档）；
            // 会话模式落档前统一收口（直连要求 https，第二道防线）
            val choice = choices?.getOrNull(index)
            Endpoint(
                id = PairingIds.newEndpointId(),
                channelLabel = parsed.channelLabel,
                targetUrl = parsed.targetUrl,
                parserId = pairing.parserId,
                sessionMode = effectiveSessionMode(
                    parsed.targetUrl,
                    choice?.sessionMode ?: parsed.sessionMode ?: SessionMode.PROXIED.name,
                ),
                retentionOverride = choice?.retentionOverride,
            )
        }
        val device = if (host != null) {
            // 并入既有宿主：端点追加在档案末尾，条目身份不变
            host.copy(endpoints = host.endpoints + endpoints)
        } else {
            Device(
                id = PairingIds.newDeviceId(),
                name = when (target) {
                    is PairingTarget.NewGroup ->
                        // 用户在确认卡里命名的分组优先；未命名才用手动输入备注，
                        // 最后回退载荷默认名
                        target.name?.takeIf { it.isNotBlank() }
                            ?: displayName?.takeIf { it.isNotBlank() }
                            ?: pairing.deviceName
                    // 无分组目标的现建条目：名称恒为空串（展示层按无分组渲染）
                    PairingTarget.Ungrouped -> ""
                    is PairingTarget.Existing -> throw IllegalStateException(
                        "Pairing target device is removed: ${target.deviceId.value}",
                    )
                },
                endpoints = endpoints,
                addedAtMillis = System.currentTimeMillis(),
            )
        }
        synchronized(deviceLifecycleLock) {
            // 凭据先入加密存档，设备条目后落盘：存档失败时不留无凭据条目
            // （无需回滚——条目未写即无残留）
            val endpointCredentials = device.endpoints.takeLast(endpoints.size).zip(pairing.endpoints)
                .flatMap { (endpoint, parsed) ->
                    listOfNotNull(
                        parsed.credential?.let { credentialKey(endpoint.id) to it },
                        parsed.caCertificate?.let { vault.caCertificateKey(endpoint.id) to it },
                        parsed.pairingInstance?.let { vault.sayaInstanceKey(endpoint.id) to it },
                    )
                }
                .toMap()
            if (endpointCredentials.isNotEmpty()) {
                vault.saveCredentials(device.id, vault.loadCredentials(device.id) + endpointCredentials)
            }
            deviceStore.save(device)
        }
        return device
    }

    override fun rePairEndpoint(
        deviceId: DeviceId,
        endpointId: EndpointId,
        pairing: ParsedPairing,
        requestedSessionMode: String?,
    ): Set<EndpointId> {
        synchronized(deviceLifecycleLock) {
            val device = deviceStore.find(deviceId) ?: return emptySet()
            val credentials = vault.loadCredentials(deviceId)
            // 端点对齐（触发端点 + 同设备同解析器的同 host 兄弟端点）集中
            // 在对齐器：多端点载荷重配对不再让兄弟端点的旧凭据静默失效；
            // 显式模式选择与不变量收口（直连要求 https）同在对齐器完成
            val outcome = RePairAligner.align(
                endpoints = device.endpoints,
                triggeredId = endpointId,
                pairing = pairing,
                currentCredential = { credentials[credentialKey(it)] },
                currentCaCertificate = { credentials[vault.caCertificateKey(it)] },
                requestedSessionMode = requestedSessionMode,
            ) ?: return emptySet()
            val writes = outcome.credentialUpdates.mapKeys { credentialKey(it.key) } +
                outcome.caCertificateUpdates.mapKeys { vault.caCertificateKey(it.key) }
            if (writes.isNotEmpty()) {
                vault.saveCredentials(deviceId, credentials + writes)
            }
            // 凭据更新即重新配对：消费标记清除（新票据恢复未消费语义）
            outcome.resetConsumed.forEach { vault.clearCredentialConsumed(deviceId, it) }
            // saya-ch 键随重配对收口：凭据变更的端点清掉旧兑换产物（会话
            // cookie/CSRF）与旧 CA（新载荷未携带 CA 时旧 CA 不得沿用——新票
            // 会用旧信任链握手/指纹失败）；instance 按各端点在载荷中的对应
            // 条目写入（host 匹配对齐，与对齐器的兄弟端点配对口径一致），
            // 不统一取首条
            for (endpointId in outcome.resetConsumed) {
                val endpointHost = hostOf(
                    device.endpoints.firstOrNull { it.id == endpointId }?.targetUrl.orEmpty(),
                )
                val parsedInstance = (
                    pairing.endpoints
                        .firstOrNull { UrlParserSupport.hostOf(it.targetUrl) == endpointHost }
                        ?: pairing.endpoints.firstOrNull()
                    )
                    ?.pairingInstance
                    ?.takeIf { it.isNotBlank() }
                vault.clearSayaEntries(deviceId, endpointId)
                if (outcome.caCertificateUpdates[endpointId] == null) {
                    vault.saveCredentials(
                        deviceId,
                        vault.loadCredentials(deviceId) - vault.caCertificateKey(endpointId),
                    )
                }
                parsedInstance?.let { vault.saveSayaPairingInstance(deviceId, endpointId, it) }
            }
            // 重配对作废既有恢复位（旧票的产物不适用新码）；兑换锁表项保留
            // （同一端点标识的下一轮兑换仍需互斥，端点删除时才移除）
            outcome.changedEndpointIds.forEach { synchronized(sayaExchangeLocks) { orphanExchanges.remove(it) } }
            deviceStore.save(device.copy(endpoints = outcome.endpoints))
            return outcome.changedEndpointIds
        }
    }

    /** 端点级保留档覆盖落盘（档案 v3 字段）；设备或端点不在档时静默忽略。 */
    override fun setEndpointRetentionOverride(deviceId: DeviceId, endpointId: EndpointId, retentionOverride: String?) {
        updateEndpointArchive(deviceId, endpointId) { endpoint ->
            endpoint.copy(retentionOverride = retentionOverride)
        }
    }

    /**
     * 端点移动到另一台设备：端点条目摘出并入目标档案末尾，凭据/CA/消费
     * 标记随端点迁入目标设备的加密存档。同设备、任一端不在档或端点不在源
     * 设备下时静默忽略（无档案变更，返回 false）。设备 cookie jar 为设备
     * 级存档不随端点迁移。
     */
    override fun moveEndpoint(fromDeviceId: DeviceId, endpointId: EndpointId, toDeviceId: DeviceId): Boolean {
        if (fromDeviceId == toDeviceId) return false
        synchronized(deviceLifecycleLock) {
            val from = deviceStore.find(fromDeviceId) ?: return false
            val to = deviceStore.find(toDeviceId) ?: return false
            val moved = from.endpoints.firstOrNull { it.id == endpointId } ?: return false
            deviceStore.save(from.copy(endpoints = from.endpoints.filter { it.id != endpointId }))
            deviceStore.save(to.copy(endpoints = to.endpoints + moved))
            vault.moveEndpointEntries(fromDeviceId, endpointId, toDeviceId)
            return true
        }
    }

    /** 端点移入新建分组：新建空设备承载该端点，源设备由调用方移空后删除。 */
    override fun moveEndpointToNewDevice(
        fromDeviceId: DeviceId,
        endpointId: EndpointId,
        name: String,
    ): Boolean {
        synchronized(deviceLifecycleLock) {
            val from = deviceStore.find(fromDeviceId) ?: return false
            val moved = from.endpoints.firstOrNull { it.id == endpointId } ?: return false
            val target = Device(
                id = PairingIds.newDeviceId(),
                name = name.trim(),
                endpoints = listOf(moved),
                addedAtMillis = System.currentTimeMillis(),
            )
            deviceStore.save(from.copy(endpoints = from.endpoints.filter { it.id != endpointId }))
            deviceStore.save(target)
            vault.moveEndpointEntries(fromDeviceId, endpointId, target.id)
            return true
        }
    }

    /**
     * 删除端点（不删设备）：条目移出档案，端点凭据/CA/消费标记一并清除。
     * 端点不在档时静默忽略返回 false；设备清空由调用方转删除设备口径。
     * 锁表与恢复位表项随端点删除清理。
     */
    override fun removeEndpoint(deviceId: DeviceId, endpointId: EndpointId): Boolean {
        synchronized(deviceLifecycleLock) {
            val device = deviceStore.find(deviceId) ?: return false
            if (device.endpoints.none { it.id == endpointId }) return false
            deviceStore.save(device.copy(endpoints = device.endpoints.filter { it.id != endpointId }))
            vault.clearEndpointEntries(deviceId, endpointId)
        }
        synchronized(sayaExchangeLocks) {
            sayaExchangeLocks.remove(endpointId)
            orphanExchanges.remove(endpointId)
        }
        return true
    }

    /** 设备重命名（空串即无分组）：只改档案显示名，端点与凭据不动。 */
    override fun renameDevice(deviceId: DeviceId, name: String): Boolean {
        synchronized(deviceLifecycleLock) {
            val device = deviceStore.find(deviceId) ?: return false
            deviceStore.save(device.copy(name = name.trim()))
            return true
        }
    }

    /** 端点档案的受锁更新：设备或端点不在档时静默忽略。 */
    private fun updateEndpointArchive(
        deviceId: DeviceId,
        endpointId: EndpointId,
        update: (Endpoint) -> Endpoint,
    ) {
        synchronized(deviceLifecycleLock) {
            val device = deviceStore.find(deviceId) ?: return
            if (device.endpoints.none { it.id == endpointId }) return
            deviceStore.save(
                device.copy(
                    endpoints = device.endpoints.map { endpoint ->
                        if (endpoint.id == endpointId) update(endpoint) else endpoint
                    },
                ),
            )
        }
    }

    override fun pendingCredentialEndpoints(): Set<EndpointId> = synchronized(deviceLifecycleLock) {
        val devices = deviceStore.devices()
        devices.flatMap { device ->
            device.endpoints
                .filter { endpoint ->
                    // 仅消费型凭据进入未消费集合：长期令牌端点的 401 是普通
                    // 上游认证失败，不得触发重配对引导
                    endpoint.parserId in ParserIds.CONSUMABLE_CREDENTIAL_PARSER_IDS &&
                        vault.loadCredentials(device.id).containsKey(credentialKey(endpoint.id)) &&
                        !vault.isCredentialConsumed(device.id, endpoint.id)
                }
                .map { it.id }
        }.toSet()
    }

    override var credentialConsumedListener: ((EndpointId) -> Unit)? = null

    /**
     * 提交一次性凭据消费：凭据存档置位 + 经监听摘除未消费集合（显示槽的
     * 干净首帧触发）。设备归属按端点扫描（档案规模以十为界）；设备已删除
     * 时静默忽略（存档已销毁，消费语义无意义）。
     */
    override fun markCredentialConsumed(endpointId: EndpointId, generation: Long) {
        synchronized(deviceLifecycleLock) {
            val owner = deviceStore.devices().firstOrNull { device ->
                device.endpoints.any { it.id == endpointId }
            } ?: return
            // 代次校验：提交期间端点重新配对（代次漂移）时静默忽略——旧会话
            // 的迟到提交不得误标新凭据
            val current = owner.endpoints.firstOrNull { it.id == endpointId } ?: return
            if (current.generation != generation) return
            vault.markCredentialConsumed(owner.id, endpointId)
            credentialConsumedListener?.invoke(endpointId)
        }
    }


    /** 端点凭据在设备凭据键值对中的键（统一委托凭据存档的规范构造）。 */
    private fun credentialKey(endpointId: EndpointId) = vault.endpointCredentialKey(endpointId)

    /** 下载结果页文案：代理在自身线程取用，字符串经宿主上下文本地化。成功页
     *  的第二行是表单提交导出的返回提示（代理结果页按行分段呈现）。 */
    private fun remoteDownloadPages(): DownloadPageRenderer = object : DownloadPageRenderer {
        override fun savedHtml(downloadDirName: String, fileName: String): String =
            appContext.getString(R.string.remote_download_saved, downloadDirName, fileName) + "\n" +
                appContext.getString(R.string.remote_download_saved_hint)

        override fun failureHtml(detail: String): String =
            appContext.getString(R.string.remote_download_failed, detail)

        override fun dirUnavailableReason(): String =
            appContext.getString(R.string.remote_download_dir_unavailable)

        override fun backLabel(): String =
            appContext.getString(R.string.remote_download_back)
    }

    private companion object {
        const val PREFERENCES_NAME = "dshbox_remote_devices"
        const val VAULT_DIR_NAME = "remote_vault"

        /** saya-ch 会话 cookie 的罐内 Max-Age（秒）= 网关默认会话时长（8h）。 */
        const val SAYA_SESSION_MAX_AGE_SECONDS = 8 * 60 * 60

        /** dsh-mobile 设备凭据 cookie：只作用于浏览器重连页的续期入口。 */
        const val SAYA_DEVICE_COOKIE_NAME = "dsh_ma_device"
        const val SAYA_DEVICE_COOKIE_PATH = "/mobile-access/auth/renew"

        /** 设备凭据有效期（秒）：网关侧默认 90 天，罐内上限与之一致。 */
        const val SAYA_DEVICE_MAX_AGE_SECONDS = 90L * 24 * 60 * 60

        /** dsh-mobile 的 CSRF cookie 名（TLS 下同时下发 __Host- 前缀版本）。 */
        const val SAYA_CSRF_COOKIE_NAME = "dsh_ma_csrf"
        const val SAYA_HOST_CSRF_COOKIE_NAME = "__Host-dsh_ma_csrf"
    }
}
