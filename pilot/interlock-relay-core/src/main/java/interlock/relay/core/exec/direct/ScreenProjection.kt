package interlock.relay.core.exec.direct

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.core.content.getSystemService
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.RelayResponse
import interlock.relay.core.exec.BackendCall
import interlock.relay.core.exec.BackendResult
import interlock.relay.core.log.LogEvent
import interlock.relay.core.log.LogSubsystem
import interlock.relay.core.log.RunLog
import interlock.relay.core.runtime.monotonicNow
import interlock.relay.core.storage.RelayPaths
import interlock.relay.core.storage.StorageReaper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * 空闲授权还剩多少毫秒；负值即已到期。
 *
 * 抽成纯函数是为了让「token 已获但无人使用、到点必须结清」这一判定能在无设备环境下
 * 推着注入的钟验证，而不是藏在主线程回调的深处。
 */
internal fun idleTimeLeftMs(idleSinceMs: Long, nowMs: Long, idleExpiryMs: Long): Long =
    idleSinceMs + idleExpiryMs - nowMs

/**
 * 屏幕采集通路（MediaProjection）。承担 `screen.capture` 的免无障碍路径、`screen.observe`
 * 的单帧观察，以及 `screen.record` 的短时录屏。
 *
 * 四条硬约束决定了这个类的形状：
 * 1. `createScreenCaptureIntent()` 只能由 **Activity** 发起并收结果，而调用是从信箱（后台协程）
 *    来的，所以这里做一条「挂起—交棒—回填」的握手：把待启动的意图备好，交给那页透明的
 *    [interlock.relay.core.runtime.RelayConsentActivity] 点一次火，自己等结果。后台启动 Activity 在
 *    Android 10 起被限制，做成隐式行为只会时灵时不灵，因此带不动时如实报错而不是硬试。
 * 2. 系统弹框名义上是「每次会话」一次（能力上限 `SESSION_ONLY` 即照此定），但近版本系统上
 *    「一次会话」比字面意思窄得多：一个 token 只允许挂**一路**虚拟屏——建第二条时系统直接把
 *    整个会话判废（系统日志：`MediaProjectionManagerService: Reusing token: Throw exception
 *    due to invalid projection`），之后每条调用只剩 SecurityException。
 *    所以这里一次调用一条显示，用完 [endSession] 结清，下一次重新要授权：
 *    宁可让用户多点一次弹框，也不留一条「看着还在、其实已废」的通道。
 * 3. Android 14 起，取 token 要求**已有一个 `mediaProjection` 类型的前台服务在跑**，
 *    而 `startForegroundService` 是异步的：所以系统弹框结果先存下，等服务回调
 *    [mintPendingToken] 再换 token。顺序反了不只是失败，还会在停掉一个没落地
 *    `startForeground` 的服务时被判 ForegroundServiceDidNotStartInTimeException 带走整个进程。
 * 4. 用户从通知栏停止采集时系统只回调 `MediaProjection.Callback.onStop`、不抛异常：
 *    不在那里清掉 token，后面每一条采集都会抱着已作废的会话静默失败。
 *
 * 界面不在前台时不排队干等：等满 [CONSENT_GRACE_MS] 就如实回「正在等用户点弹框」，
 * 让助手拿到一个可重试的明确失败，而不是把没人应答的挂起留在链上。
 */
class ScreenProjection(
    context: Context,
    private val reaper: StorageReaper,
    private val runLog: RunLog,
    private val startConsentHost: () -> Boolean,
    /**
     * 单调时钟。默认走系统单调钟；可注入以便在无设备环境下推着走，
     * 把「空闲授权到点必须结清」这类时刻判定钉在测试里。
     */
    private val now: () -> Long = ::monotonicNow,
) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService<MediaProjectionManager>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val consentState = MutableStateFlow<Intent?>(null)

    /** 非空即「弹框已备好，等发起方点一次火」。发起方是那页透明界面，它读这一条。 */
    val consentRequest: StateFlow<Intent?> = consentState

    private val waiting = AtomicReference<CompletableDeferred<Consent>?>(null)

    /** 弹框在外的起始时刻，用于 [CONSENT_LIFETIME_MS] 之后重走一次握手。 */
    @Volatile
    private var consentStartedAt = 0L

    @Volatile
    private var projection: MediaProjection? = null

    /** 系统弹框已确认、但前台服务还没就位时暂存的那次结果。 */
    private var pendingConsent: Pair<Int, Intent>? = null

    /** 空闲计时的起点：token 换到的那一刻。0 表示没有待到期的空闲授权。 */
    @Volatile
    private var idleSinceMs = 0L

    /** mint 兜底：结果存下之后服务迟迟不就位，就用它收场。只跑在主线程。 */
    private val mintTimeoutRunnable = Runnable { onMintTimeout() }

    /** 空闲到期：token 已获但没有人来用，就用它结清。只跑在主线程。 */
    private val idleExpiryRunnable = Runnable { onIdleExpiry() }

    /** 区分「本模块自己收掉会话」与「用户从通知栏停止采集」，见 [endSession]。 */
    @Volatile
    private var selfStopping = false

    /** 采集授权的几种结局。各自对应一个错误码：混成一种就会把「用户还没点」报成「后端挂了」。 */
    private sealed class Consent {
        data class Granted(val projection: MediaProjection) : Consent()

        /** 弹框还挂在用户面前，这一次答不了。 */
        data object Awaiting : Consent()

        /** 用户在系统弹框上按了拒绝/取消。 */
        data object Declined : Consent()

        /** 用户点了允许，但系统不给出 token：会话已作废一类，与「用户说不」是两件事。 */
        data object TokenFailed : Consent()

        /** 本模块带不起能发系统弹框的界面。 */
        data object NoUi : Consent()
    }

    /**
     * 取一个可用的采集会话，需要时把系统弹框交给透明界面去发起。
     *
     * 等不满 [CONSENT_GRACE_MS] 就回 [Consent.Awaiting] 并**留着那张弹框**：用户随后点确认，
     * token 会缓存下来，下一条采集直接可用。答得慢不该让白点一次确认。
     */
    private suspend fun obtain(): Consent {
        projection?.let { return Consent.Granted(it) }
        val gate = waiting.get() ?: createConsentGate() ?: return Consent.NoUi
        val remaining = (CONSENT_LIFETIME_MS - (now() - consentStartedAt)).coerceAtLeast(0L)
        withTimeoutOrNull(minOf(CONSENT_GRACE_MS, remaining)) { gate.await() }?.let { return it }
        projection?.let { return Consent.Granted(it) }
        if (now() - consentStartedAt >= CONSENT_LIFETIME_MS) {
            // 弹框挂得太久：多半被用户划走了。收掉这一路，下一条重新要一次授权。
            runLog.warn(LogSubsystem.GATE, LogEvent.PROJECTION_CONSENT_TIMEOUT)
            resetConsent()
        } else {
            runLog.info(LogSubsystem.GATE, LogEvent.PROJECTION_CONSENT_PENDING)
        }
        return Consent.Awaiting
    }

    /** 发起一次握手；已有在途的一路时复用它，同一时刻只谈一张弹框。 */
    private fun createConsentGate(): CompletableDeferred<Consent>? {
        val source = manager ?: return null
        val intent = consentIntent(source) ?: return null
        val created = CompletableDeferred<Consent>()
        if (!waiting.compareAndSet(null, created)) return waiting.get()
        // 先把待发起的意图放出去，再带那页透明界面：界面读的是这条流的**当前值**，
        // 顺序反了它就读到 null 直接收场，于是这张弹框永远不出现，
        // 而调用侧要等满存在期才回一句「还在等用户点」。
        consentState.value = intent
        consentStartedAt = now()
        if (!startConsentHost()) {
            runLog.warn(LogSubsystem.GATE, LogEvent.PROJECTION_NO_UI)
            resetConsent()
            return null
        }
        return created
    }

    /**
     * 要一张什么样的画面。
     *
     * Android 14 起可以带 `MediaProjectionConfig` 去要，这里要的是**整块屏**
     * （`createConfigForDefaultDisplay`）。不带的话系统弹框默认停在「单个应用」那一档：
     * 单应用镜像只在被镜像的那个应用刷新时才出帧，画面静止时编码器一帧都收不到
     * （`media error what=268435556 extra=-1007`，stagefright 的 `ERROR_TIMED_OUT`），
     * 于是助手对着一个**它自己没选过的默认值**失败。而本模块的采集语义从来就是"当前屏幕"，
     * 没有"只录某一个应用"这一档 —— 把选不到、也不该选的源头从弹框里去掉，比事后
     * 靠错误提示教用户改默认值更实在。
     *
     * 低版本没有这个入口；带 config 那条一旦在某个 ROM 上抛异常也退回无参形式 ——
     * 拿不到更好的默认值不该把采集本身挡掉。
     */
    private fun consentIntent(source: MediaProjectionManager): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val config = MediaProjectionConfig.createConfigForDefaultDisplay()
            runCatching { source.createScreenCaptureIntent(config) }.getOrNull()?.let { return it }
        }
        return runCatching { source.createScreenCaptureIntent() }.getOrNull()
    }

    private fun resetConsent() {
        waiting.set(null)
        consentState.value = null
    }

    private fun consentFailed(consent: Consent): BackendResult.Failed = when (consent) {
        Consent.Declined -> BackendResult.Failed(
            RelayError.SURFACE_SYSTEM_CONSENT_REQUIRED,
            "user declined the system capture dialog",
            consentKind = RelayResponse.CONSENT_KIND_ANDROID_PROJECTION,
        )

        Consent.TokenFailed -> BackendResult.Failed(
            RelayError.BACKEND_UNAVAILABLE,
            "system refused the capture token for this session",
            consentKind = RelayResponse.CONSENT_KIND_ANDROID_PROJECTION,
        )

        Consent.NoUi -> BackendResult.Failed(
            RelayError.GATE_NO_FOREGROUND,
            "no foreground surface can present the system capture dialog",
            consentKind = RelayResponse.CONSENT_KIND_ANDROID_PROJECTION,
        )

        else -> BackendResult.Failed(
            RelayError.GATE_AWAITING_CONSENT,
            "system capture dialog is waiting for the user",
            consentKind = RelayResponse.CONSENT_KIND_ANDROID_PROJECTION,
        )
    }

    /** 界面侧把系统弹框的结果原样递回来。 */
    fun onConsentResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            runLog.info(LogSubsystem.GATE, LogEvent.PROJECTION_CONSENT_DENIED)
            finishWaiting(Consent.Declined)
            return
        }
        // token 不在这里取：Android 14 起 `getMediaProjection` 要求前台服务已经在跑，
        // 而 startForegroundService 是异步的。先把结果存下、把服务起出去，
        // 服务进入前台后回调 [mintPendingToken] 再换 token。
        pendingConsent = resultCode to data
        RelayProjectionService.onReady = { mainHandler.post { mintPendingToken() } }
        // 服务迟迟不就位也要有兜底：onStartCommand 没跑起来时 onReady 永远不来，
        // 这一路不能就这么挂着等满握手期限。
        mainHandler.removeCallbacks(mintTimeoutRunnable)
        mainHandler.postDelayed(mintTimeoutRunnable, MINT_TIMEOUT_MS)
        val started = RelayProjectionService.start(appContext)
        if (!started) {
            // 启动调用本身就被系统拒了（后台启动被拦等）：没有那条前台服务连 token 都
            // 换不到，等下去只会等满超时。显式收场比吞掉异常诚实。
            runLog.warn(LogSubsystem.GATE, LogEvent.PROJECTION_TOKEN_FAILED, "cause" to "fgs-start-failed")
            cancelMintTimeout()
            RelayProjectionService.stop(appContext)
            finishWaiting(Consent.TokenFailed)
        }
    }

    /** 兜底：结果存下之后服务在限定时间内没有就位，token 换不成，就地收场。 */
    private fun onMintTimeout() {
        if (pendingConsent == null) return
        runLog.warn(LogSubsystem.GATE, LogEvent.PROJECTION_TOKEN_FAILED, "cause" to "fgs-ready-timeout")
        pendingConsent = null
        RelayProjectionService.stop(appContext)
        finishWaiting(Consent.TokenFailed)
    }

    private fun cancelMintTimeout() {
        mainHandler.removeCallbacks(mintTimeoutRunnable)
    }

    private fun mintPendingToken() {
        val (code, payload) = pendingConsent ?: return
        pendingConsent = null
        cancelMintTimeout()
        val obtained = runCatching { manager?.getMediaProjection(code, payload) }
            .onFailure {
                runLog.warn(
                    LogSubsystem.GATE,
                    LogEvent.PROJECTION_TOKEN_FAILED,
                    "cause" to "${it.javaClass.simpleName}: ${it.message ?: "no message"}",
                )
            }
            .getOrNull()
        if (obtained == null) {
            RelayProjectionService.stop(appContext)
            finishWaiting(Consent.TokenFailed)
            return
        }
        obtained.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // 用户从通知栏停止投射时系统只回调这里、不抛异常：不清掉 token，
                // 后面每一条采集都会拿着已作废的会话静默失败。
                projection = null
                disarmIdleExpiry()
                if (!selfStopping) runLog.info(LogSubsystem.GATE, LogEvent.PROJECTION_REVOKED)
            }
        }, mainHandler)
        projection = obtained
        // token 已获但没人用不得无限期持有：从这一刻起安排空闲到期，到点结清。
        armIdleExpiry()
        runLog.info(LogSubsystem.GATE, LogEvent.PROJECTION_CONSENT_GRANTED)
        finishWaiting(Consent.Granted(obtained))
    }

    /** 把这一次握手的结论交给在等的采集路；没人等时只是清场。 */
    private fun finishWaiting(result: Consent) {
        waiting.getAndSet(null)?.complete(result)
        consentState.value = null
    }

    /** token 换到就起表：从现在起空闲 [IDLE_EXPIRY_MS]，到点没人用就结清会话。 */
    private fun armIdleExpiry() {
        idleSinceMs = now()
        mainHandler.removeCallbacks(idleExpiryRunnable)
        mainHandler.postDelayed(idleExpiryRunnable, IDLE_EXPIRY_MS)
    }

    /** 采集开始或会话已结清时停表：token 正在被用、或已经不在了，都没有「空闲」可言。 */
    private fun disarmIdleExpiry() {
        idleSinceMs = 0L
        mainHandler.removeCallbacks(idleExpiryRunnable)
    }

    private fun onIdleExpiry() {
        if (projection == null || idleSinceMs == 0L) return
        // 唤醒只可能迟到：单调钟包含休眠时长，走得比 postDelayed 的 uptime 快。
        // 但迟到多少不由它说了算，用注入的钟复核一次，没到点就把剩下的时长再排上。
        val leftMs = idleTimeLeftMs(idleSinceMs, now(), IDLE_EXPIRY_MS)
        if (leftMs > 0L) {
            mainHandler.postDelayed(idleExpiryRunnable, leftMs)
            return
        }
        // 到点仍在缓存里的 token 就地结清：一条没人用的采集授权不该无限期挂着，
        // 它意味着一块还在投放的虚拟屏和一条常驻的前台服务通知。
        runLog.warn(LogSubsystem.GATE, LogEvent.PROJECTION_TOKEN_FAILED, "cause" to "idle-expired")
        endSession()
    }

    /** 通道停止时收掉采集会话与那条前台服务通知，不留一个没人用的常驻项。 */
    fun release() {
        endSession()
        // 在途的等待方由信箱的取消收尾，这里不替它编一个结论。
        resetConsent()
    }

    /** 单帧落 PNG 产物：`screen.capture` 的免无障碍路径与 `screen.observe` 共用这一条。 */
    suspend fun captureFrame(call: BackendCall): BackendResult {
        val consent = obtain()
        if (consent !is Consent.Granted) return consentFailed(consent)
        // 采集已在进行：空闲停表，结清由这一路自己的收尾（grabFrame 的 finally）负责。
        disarmIdleExpiry()
        val frame = grabFrame()
            ?: return BackendResult.Failed(RelayError.BACKEND_UNAVAILABLE, "no frame from projection")
        val bitmap = frame.bitmap
        val staged = reaper.newArtifactFile(call.requestId, "png", estimatePng(bitmap))
            ?: run {
                bitmap.recycle()
                return BackendResult.Failed(RelayError.STORAGE_FULL, "quota exhausted")
            }
        val written = runCatching {
            FileOutputStream(staged).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out) }
            staged.length() > 0L
        }.getOrDefault(false)
        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()
        if (!written) {
            reaper.discard(staged)
            return BackendResult.Failed(RelayError.INTERNAL, "frame encode failed")
        }
        return publish(staged, call, JSONObject().put("width", width).put("height", height))
    }

    /** 录屏：把虚拟屏直接接给编码器，跑满时长后停表落文件。 */
    suspend fun record(call: BackendCall): BackendResult {
        val consent = obtain()
        if (consent !is Consent.Granted) return consentFailed(consent)
        val source = consent.projection
        // 录屏最长半分钟，不能让空闲到期在录制中途把 token 收走：停表，结清归这一路的 finally。
        disarmIdleExpiry()
        val seconds = call.args.optInt(KEY_SECONDS, DEFAULT_SECONDS).coerceIn(1, MAX_SECONDS)
        val staged = reaper.newArtifactFile(call.requestId, "mp4", estimateVideo(seconds))
            ?: run {
                // 配额在 finally 之前就打回：这一路已经拿到过 token，
                // 不走清理结构就会留一条没人用的采集会话挂着。
                endSession()
                return BackendResult.Failed(RelayError.STORAGE_FULL, "quota exhausted")
            }
        // 编码器要求偶数边长，奇数分辨率的机型上直接 prepare 会抛。
        val width = metricsWidth() and 1.inv()
        val height = metricsHeight() and 1.inv()
        // 无参构造在 Android 12 起被带进程标签的重载取代，而后者是 API 31；本模块下限 29。
        @Suppress("DEPRECATION")
        val recorder = MediaRecorder()
        var recorderError: String? = null
        var display: VirtualDisplay? = null
        return try {
            val prepared = runCatching {
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                recorder.setVideoSize(width, height)
                recorder.setVideoFrameRate(VIDEO_FRAME_RATE)
                recorder.setVideoEncodingBitRate(VIDEO_BITS_PER_SECOND.toInt())
                recorder.setOutputFile(staged.absolutePath)
                // 编码器在服务端出错时只会回调，不会让 stop() 说清为什么；不留这一行，
                // 「没录到东西」就永远只有一句结论没有成因。
                recorder.setOnErrorListener { _, what, extra ->
                    recorderError = "media error what=$what extra=$extra"
                    true
                }
                recorder.prepare()
                recorder.start()
            }.isSuccess
            if (!prepared) {
                reaper.discard(staged)
                return BackendResult.Failed(RelayError.BACKEND_UNAVAILABLE, "recorder rejected")
            }
            display = createDisplay(source, recorder.surface, width, height)
            if (display == null) {
                reaper.discard(staged)
                return BackendResult.Failed(RelayError.BACKEND_UNAVAILABLE, "no display from projection")
            }
            delay(seconds * 1000L)
            // stop 会把缓冲写完，任何分支都不能跳过；失败统一在 finally 里丢暂存件。
            val stopped = runCatching { recorder.stop() }
            val bytes = staged.length()
            if (stopped.isFailure || bytes <= 0L) {
                reaper.discard(staged)
                // 「编码器一帧都没收到」与「文件写坏了」都不是宿主代码的 bug：前者多半是这块屏
                // 当时没有可刷新的画面（熄屏、投屏 token 已被上一次调用结掉），重试或换采集方式就够，
                // 报 exit 6 会把助手引到「等我们修」的死路上。
                val cause = stopped.exceptionOrNull()?.let {
                    "stop failed: ${it.javaClass.simpleName}" +
                        (recorderError?.let { e -> " ($e)" } ?: "") +
                        " — the encoder got no frame; if the system dialog is set to a single " +
                        "app, pick whole screen and retry"
                } ?: "encoder wrote no bytes"
                return BackendResult.Failed(RelayError.BACKEND_UNAVAILABLE, cause)
            }
            publish(staged, call, JSONObject().put("seconds", seconds).put("bytes", bytes))
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            display?.release()
            endSession()
            if (staged.exists()) reaper.discard(staged)
        }
    }

    private data class Frame(val bitmap: Bitmap, val width: Int, val height: Int)

    /**
     * 取一帧：建显示 → 等首帧 → 收显示 → 结会话。
     *
     * 显示既不能留着复用，也不能一个会话挂两条——见类注释第 4 条平台约束：
     * 建第二条时系统直接把整个 token 判废，之后每条调用只剩 SecurityException。
     * 所以一次调用一条显示，用完就地结清，不给界面留一条「看着还在、其实已废」的通道。
     */
    private suspend fun grabFrame(): Frame? {
        val source = projection ?: return null
        val width = (metricsWidth() * FRAME_SCALE).toInt().coerceAtLeast(MIN_FRAME_PX)
        val height = (metricsHeight() * FRAME_SCALE).toInt().coerceAtLeast(MIN_FRAME_PX)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        var display: VirtualDisplay? = null
        try {
            display = createDisplay(source, reader.surface, width, height) ?: return null
            val image = awaitImage(reader) ?: return null
            return try {
                Frame(bitmapOf(image), image.width, image.height)
            } finally {
                image.close()
            }
        } finally {
            display?.release()
            reader.close()
            endSession()
        }
    }

    /** 会话到此为止：清 token、停那条前台服务通知。下一次采集重新要一次授权。 */
    private fun endSession() {
        val current = projection
        projection = null
        pendingConsent = null
        disarmIdleExpiry()
        cancelMintTimeout()
        if (current != null) {
            // 我们自己收掉的会话不算「用户停止」：授权记录里那一条要说的是用户的动作，
            // 混进来会让事后核对以为用户中途关掉了采集。
            selfStopping = true
            runCatching { current.stop() }
            selfStopping = false
        }
        RelayProjectionService.stop(appContext)
    }

    private fun createDisplay(source: MediaProjection, surface: Surface, width: Int, height: Int): VirtualDisplay? {
        val created = runCatching {
            source.createVirtualDisplay(
                DISPLAY_NAME,
                width,
                height,
                appContext.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                mainHandler,
            )
        }.onFailure {
            // 系统判这个会话已作废（最后一路虚拟屏被释放、或用户从通知栏停止）：
            // 清掉缓存，下一条调用重新走一次系统授权，而不是抱着死 token 一直失败。
            if (it is SecurityException) endSession()
            runLog.warn(LogSubsystem.GATE, LogEvent.PROJECTION_FRAME_FAILED, "cause" to it.javaClass.simpleName)
        }.getOrNull()
        // 返回 null 而不抛异常的那一路同样说明这个会话给不出屏：留着缓存，下一条调用
        // 就永远拿着一个建不出屏的 token 回「可重试」，助手照做也只是原地绕圈。
        if (created == null) endSession()
        return created
    }

    /** 等第一帧：虚拟屏建立后系统会推一帧当前画面，但推多久没有上界，故带上限轮询。 */
    private suspend fun awaitImage(reader: ImageReader): Image? {
        repeat(FRAME_POLL_TRIES) {
            reader.acquireLatestImage()?.let { return it }
            delay(FRAME_POLL_MS)
        }
        return null
    }

    private fun bitmapOf(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * image.width
        val padded = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(buffer)
        if (rowPadding == 0) return padded
        // 行末补齐出来的那几列不是画面内容，裁回真实宽度再交给上层。
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun publish(staged: File, call: BackendCall, data: JSONObject): BackendResult {
        val bytes = staged.length()
        val artifact = reaper.publishArtifact(staged, call.requestId)
            ?: return BackendResult.Failed(RelayError.STORAGE_FULL, "artifact publish failed")
        val guestPath = RelayPaths.toGuestPath(artifact, call.paths)
            ?: run {
                reaper.discard(artifact)
                return BackendResult.Failed(RelayError.INTERNAL, "artifact path unmappable")
            }
        return BackendResult.Ok(data = data, artifacts = listOf(guestPath), artifactBytes = bytes)
    }

    private fun metricsWidth(): Int = appContext.resources.displayMetrics.widthPixels

    private fun metricsHeight(): Int = appContext.resources.displayMetrics.heightPixels

    private fun estimatePng(bitmap: Bitmap): Long =
        (bitmap.width.toLong() * bitmap.height * BYTES_PER_PIXEL).coerceAtMost(MAX_ESTIMATE_BYTES)

    private fun estimateVideo(seconds: Int): Long =
        (seconds.toLong() * VIDEO_BITS_PER_SECOND / 8L).coerceAtMost(MAX_ESTIMATE_BYTES)

    private companion object {
        const val DISPLAY_NAME = "relay-projection"

        const val CONSENT_GRACE_MS = 10_000L

        /**
         * 一张弹框在外面最多算数多久。
         *
         * 比审批窗的默认窗口长：Android 15 起这条链上是**三个**弹框——本模块的确认窗、
         * 系统的「开始录制/投放」、再选一个应用。默认窗口那一档若比它长，用户还在读第二张框
         * 时这一路就已判超时；实际等待还会被本次调用的预算截短（见 CONSENT_GRACE_MS）。
         * 挂过这条上限才作罢——那时它多半已被用户划走，重发一次比沿用一张不存在的框诚实。
         */
        const val CONSENT_LIFETIME_MS = 90_000L

        /**
         * 系统弹框结果存下之后，等服务回报就位的上限。
         *
         * startForegroundService 是异步的，而服务没就位时 token 根本换不到：
         * 没有这条兜底，一次没跑起来的启动会让整条采集挂满握手期限。
         */
        const val MINT_TIMEOUT_MS = 5_000L

        /**
         * token 已获但无人使用的空闲期限。一条没人用的采集授权挂着的是一块还在投放的
         * 虚拟屏和一条常驻的前台服务通知；下一次采集本来就要重新握手，留它没有收益。
         */
        const val IDLE_EXPIRY_MS = 60_000L

        const val FRAME_SCALE = 0.5f
        const val MIN_FRAME_PX = 64
        const val MAX_IMAGES = 2
        const val FRAME_POLL_TRIES = 20
        const val FRAME_POLL_MS = 100L
        const val PNG_QUALITY = 100
        const val BYTES_PER_PIXEL = 4L
        const val MAX_ESTIMATE_BYTES = 32L * 1024 * 1024
        const val KEY_SECONDS = "seconds"
        const val DEFAULT_SECONDS = 5
        const val MAX_SECONDS = 30
        const val VIDEO_FRAME_RATE = 15
        const val VIDEO_BITS_PER_SECOND = 6_000_000L
    }
}
