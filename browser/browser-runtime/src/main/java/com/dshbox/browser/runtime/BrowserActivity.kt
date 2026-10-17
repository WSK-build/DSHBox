package com.dshbox.browser.runtime

import android.app.Activity
import android.app.AlertDialog
import android.app.AlarmManager
import android.app.Dialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Color
import android.graphics.Insets
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.PixelCopy
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.DownloadListener
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.webkit.URLUtil
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.ColorDrawable
import android.content.DialogInterface
import android.util.AtomicFile
import android.util.Log
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.SeekBar
import com.dshbox.browser.api.BrowserClock
import com.dshbox.browser.api.BrowserArtifactFiles
import com.dshbox.browser.api.BrowserContract
import com.dshbox.browser.api.BrowserUiSettings
import com.dshbox.browser.api.BrowserCloseReason
import com.dshbox.browser.api.BrowserHomeStatus
import com.dshbox.browser.api.BrowserIdlePolicy
import com.dshbox.browser.api.BrowserLifecycle
import com.dshbox.browser.api.BrowserLifecycleController
import com.dshbox.browser.api.BrowserOperationToken
import com.dshbox.browser.api.BrowserOwner
import com.dshbox.browser.api.BrowserSnapshot
import com.dshbox.browser.api.BrowserTaskPageIdentity
import com.dshbox.app.common.R as CommonR
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.util.Locale
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.nio.channels.Channels
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private typealias BrowserHistoryEntry = BrowserHistoryStore.Entry

class BrowserActivity : Activity(), BrowserCommandGateway {
    private lateinit var uiContext: Context
    // Activity construction happens before Android attaches the base Context.
    private var runtimePalette: BrowserRuntimePalette = BrowserRuntimePalette.create(false)
    private val actionGate = BrowserActionGate { SystemClock.elapsedRealtime() }
    private var activeLocaleTag: String = ""
    private var localeFollowsSystem = true
    private val mainHandler = Handler(Looper.getMainLooper())
    private data class PendingUploadApprovalReply(
        val response: JSONObject,
        val callback: (JSONObject) -> Unit,
        val authorized: () -> Boolean,
        val identity: BrowserTaskPageIdentity,
    )
    private val pendingUploadApprovalReplies = mutableMapOf<String, PendingUploadApprovalReply>()
    private var pendingUploadApprovalPresentationId: String? = null
    private val controlTransferPolicy = BrowserControlTransferPolicy()
    private var pendingControlTransferDialog: AlertDialog? = null
    private var browserInputGeneration = 0L
    private data class PendingBrowserInput(val field: EditText, val generation: Long)
    private var pendingBrowserInput: PendingBrowserInput? = null
    private val javascriptPromptDialogs = linkedSetOf<AlertDialog>()
    private var pendingExternalControlDecisionId: Long? = null
    private var pendingExternalControlDecision: ((Boolean) -> Unit)? = null
    private var pendingExternalControlIdentity: BrowserTaskPageIdentity? = null
    internal var cancelPendingNativeApprovalRequests: (() -> Unit)? = null
    private var consumeAiTouchGesture = false
    private var suppressAiSwitchListener = false
    internal val controlTransferPending: Boolean get() = controlTransferPolicy.hasPending
    internal val controlApprovalUiContext: Context get() = uiContext
    internal val controlRequestSnapshot: BrowserSnapshot get() = lifecycleController.snapshot()
    internal val aiControlPreferenceEnabled: Boolean
        get() = preferences.getBoolean(KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)
    val currentControlRequestIdentity: BrowserTaskPageIdentity?
        get() = if (closed || reservedSessionGeneration == null ||
            lifecycleController.snapshot().lifecycle != BrowserLifecycle.ACTIVE
        ) null else commandExecutor?.currentUploadPageIdentity()
    private val operationIds = AtomicLong()
    private val selectionExecutor = Executors.newSingleThreadExecutor()
    private var selectionReceiptPollGeneration = 0L
    private var browserBackCallback: android.window.OnBackInvokedCallback? = null
    private var transitionForeground = false
    private var reservedSessionGeneration: Long? = null
    private val preferences by lazy { BrowserRuntimeSettings.preferences(this) }
    private val pageFactory by lazy {
        BrowserPageFactory(
            context = uiContext,
            renderingSettings = { BrowserRenderingPreferences.read(preferences) },
            themePolicy = { BrowserWebThemePolicy(runtimePalette.dark, runtimePalette.paper) },
        )
    }
    private val statusPublisher by lazy { BrowserStatusPublisher(this, File(filesDir, "browser-tools/run")) }
    private lateinit var selectionStore: BrowserSelectionStore
    private lateinit var taskFeedbackStore: BrowserTaskFeedbackStore
    private lateinit var lifecycleController: BrowserLifecycleController
    private var adoptingGateway: BrowserSessionGateway? = null

    private var webView: WebView? = null
    private var pageManager: BrowserPageManager<WebView>? = null
    private var addressField: EditText? = null
    private var findField: EditText? = null
    private var findBar: LinearLayout? = null
    private var backButton: ImageButton? = null
    private var forwardButton: ImageButton? = null
    private var reloadButton: ImageButton? = null
    private var addressSearchButton: ImageButton? = null
    private var progressBar: ProgressBar? = null
    private var titleView: TextView? = null
    private var aiIndicator: View? = null
    private var collapseButton: ImageButton? = null
    private var bookmarkButton: ImageButton? = null
    private var moreButton: ImageButton? = null
    private var selectionButton: ImageButton? = null
    private var navigationRow: LinearLayout? = null
    private var pickerToolbar: LinearLayout? = null
    private var pageContainer: FrameLayout? = null
    private var pickerOverlay: View? = null
    private var pickerSurface: BrowserElementPickerView? = null
    private var pickerMode: String = BrowserSelectionStore.MODE_ELEMENT
    private var pickerSelection: JSONObject? = null
    private var pickerBounds: RectF? = null
    private var pickerFrame: Bitmap? = null
    private var pickerCommentRequested = false
    private var pickerElementIdentity: BrowserPickerElementIdentity? = null
    private var pickerProvenance: JSONObject? = null
    private var pickerPreviewCrop: Bitmap? = null
    private var pageDocumentGeneration = 0L
    private var pickerComment: EditText? = null
    private var pickerCommentPanel: LinearLayout? = null
    private var pickerHint: TextView? = null
    private var pickerElementModeButton: View? = null
    private var pickerAreaModeButton: View? = null
    @Volatile private var pickerGeneration = 0L
    @Volatile private var pickerFrameRequest = 0L
    private var pickerCaptureFailureCode: String? = null
    private var advancedDialog: Dialog? = null
    private var advancedSurfaceGeneration: Long? = null
    private var historyDialog: Dialog? = null
    private var bookmarksDialog: Dialog? = null
    private var pagesDialog: Dialog? = null
    private var recoveryPanel: BrowserTaskRecoveryPanel? = null
    private var menuPopup: PopupWindow? = null
    private var settingsDialog: Dialog? = null
    private var zoomPercent = 100
    private var requestedZoomPercent = 100
    private var zoomRequestGeneration = 0L
    private var returnComponent: ComponentName? = null
    private val navigationTracker = BrowserNavigationTracker<WebView, BrowserOperationToken>()
    private lateinit var pageStateTracker: BrowserPageStateTracker<WebView>
    private val pendingJavaScriptDialogDismissals = java.util.IdentityHashMap<WebView, MutableSet<() -> Unit>>()
    private var receiverRegistered = false
    private var lastTouchActivityAt = 0L
    private var closed = false
    private var aiAnimating = false
    private var scheduledAlarmSession: Long? = null
    private var scheduledAlarmDeadline: Long? = null
    private var commandExecutor: BrowserCommandExecutor? = null
    private var advancedController: BrowserAdvancedController? = null
    private var pendingHarArtifact: File? = null
    private data class PendingDownloadSave(val id: String, val fileName: String)
    private var pendingDownloadSave: PendingDownloadSave? = null
    @Volatile private var downloadsManager: BrowserDownloadsManager? = null
    private val bookmarksStore by lazy { BrowserBookmarksStore(this) }
    private var downloadsSubscription: AutoCloseable? = null
    private var downloadsPanel: BrowserDownloadsPanel? = null
    private var downloadsPage = BrowserDownloadPage(emptyList(), null, false)
    private var downloadsRequestGeneration = 0L
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private var pendingFileChooserWebView: WebView? = null
    private var pendingFileChooserRequestCode: Int? = null
    private data class PendingUploadChooser(
        val callback: ValueCallback<Array<Uri>>,
        val view: WebView,
        val grantId: String,
        val taskId: String,
        val identity: BrowserTaskPageIdentity,
        val acceptTypes: List<String>,
        val multiple: Boolean,
    )
    private data class PendingUploadActivation(
        val grantId: String,
        val taskId: String,
        val identity: BrowserTaskPageIdentity,
        val view: WebView,
        val callback: (JSONObject) -> Unit,
        val timeout: Runnable,
        var dispatched: Boolean = false,
        var completed: Boolean = false,
    )
    private var pendingUploadChooser: PendingUploadChooser? = null
    private var pendingUploadActivation: PendingUploadActivation? = null
    private val uploadActivationIsolation = BrowserUploadActivationIsolation<WebView, BrowserTaskPageIdentity>()
    private var uploadApprovalDialog: AlertDialog? = null
    private var uploadApprovalGrantId: String? = null
    private var uploadApprovalFocusObserver: BrowserUploadApprovalFocusObserver? = null
    private val uploadApprovalNotice by lazy { BrowserUploadApprovalNotice(this) }
    private val uploadUriTokensByGrant = HashMap<String, MutableSet<String>>()
    private lateinit var uploadGrantStore: BrowserUploadGrantStore
    private val uploadFilesRoot by lazy { filesDir.canonicalFile }
    private val uploadStager by lazy { BrowserUploadStager(uploadFilesRoot) }
    private val uploadIo = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2),
        { task -> Thread(task, "browser-upload-io").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val downloadsRefresh = Runnable { if (!closed && downloadsPanel != null) refreshDownloadsPanel(null) }
    private val downloadsExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4),
        { task -> Thread(task, "browser-file-io").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private var openingRequestId: String? = null
    private var openingUrl: String? = null
    private var chromeDrawListener: android.view.ViewTreeObserver.OnDrawListener? = null
    private var chromeDrawPosted = false
    private var webViewInitializationStarted = false

    private val lifecycleMonitor = object : Runnable {
        override fun run() {
            if (closed) return
            sweepUploadGrants()
            val snapshot = lifecycleController.expireIfNeeded()
            clearStaleNavigationAttempt(snapshot)
            publishStatus(snapshot)
            if (snapshot.lifecycle == BrowserLifecycle.CLOSING) {
                releaseSession(finishActivity = true)
            } else {
                scheduleLifecycleMonitor()
                scheduleIdleAlarm(snapshot)
            }
        }
    }

    private val lifecycleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BrowserContract.ACTION_QUERY_STATUS -> checkAndPublishStatus()
                BrowserContract.ACTION_REVOKE_CONTROL -> {
                    runCatching { cancelPendingNativeApprovalRequests?.invoke() }
                    dismissPendingControlTransfer()
                    dismissExternalAiControlRequest()
                    val snapshot = lifecycleController.snapshot()
                    if (snapshot.lifecycle == BrowserLifecycle.ACTIVE && snapshot.owner == BrowserOwner.AI) {
                        publishStatus(transferToHuman())
                    }
                }
                ACTION_IDLE_CHECK -> {
                    scheduledAlarmSession = null
                    scheduledAlarmDeadline = null
                    val alarmGeneration = intent.getLongExtra(EXTRA_ALARM_SESSION, -1L)
                    val snapshot = lifecycleController.snapshot()
                    if (alarmGeneration != snapshot.sessionGeneration) return
                    checkAndPublishStatus()
                }
                BrowserContract.ACTION_UI_SETTINGS -> applyBrowserUiSettings(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchKind = BrowserApprovalLaunchPolicy.classify(
            approvalOnly = intent.getBooleanExtra(BrowserRuntimeSettings.EXTRA_UPLOAD_APPROVAL_ONLY, false),
            startAsAi = intent.getBooleanExtra(BrowserRuntimeSettings.EXTRA_START_AS_AI, false),
        )
        if (launchKind == BrowserActivityLaunchKind.APPROVAL_ONLY) {
            closed = true
            finish()
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            browserBackCallback = android.window.OnBackInvokedCallback { onBackPressed() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, it,
                )
            }
        }
        updateUiContext(intent)
        refreshBrowserTheme(intent.getStringExtra(BrowserContract.EXTRA_THEME_MODE), dismissPanels = false)
        persistLocalePreference()
        openingRequestId = intent.getStringExtra(BrowserRuntimeSettings.EXTRA_OPEN_REQUEST_ID)
        openingUrl = intent.getStringExtra(BrowserRuntimeSettings.EXTRA_OPEN_URL)
        val requestedAiStart = launchKind == BrowserActivityLaunchKind.AI_SESSION
        if (requestedAiStart && !preferences.getBoolean(KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)) {
            closed = true
            finish()
            return
        }
        if (requestedAiStart && openingRequestId != null) {
            val deadline = intent.getLongExtra(BrowserRuntimeSettings.EXTRA_OPEN_DEADLINE_ELAPSED, 0L)
            if (deadline <= SystemClock.elapsedRealtime()) {
                closed = true
                finish()
                return
            }
        }
        // Every window (cold AI open or user-initiated) runs on one session-owned lifecycle and
        // execution layer, so background tools continue after the window is closed.
        val session = BrowserSessionGatewayRegistry.current() ?: createOwnSession() ?: run {
            closed = true
            finish()
            return
        }
        adoptingGateway = session
        lifecycleController = session.lifecycleController
        reservedSessionGeneration = lifecycleController.snapshot().sessionGeneration
        uploadGrantStore = session.uploadGrantStoreOrNull()
        selectionStore = session.selectionStoreOrNull()
        pageStateTracker = session.pageStateTrackerOrNull()
        taskFeedbackStore = session.taskFeedbackStoreOrNull()
        syncAdvancedPermission()
        window.statusBarColor = COLOR_PAPER
        window.navigationBarColor = COLOR_PAPER
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (runtimePalette.dark) 0 else
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    (if (runtimePalette.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) or
                    // 全屏沉浸（API 29）：systemUiVisibility 是窗口属性，窗口挂载前设置同样
                    // 生效，与上方几个标志位同期写入即可；开关与 API 30+ 的 insets 路径共用
                    // 同一个常量（见 applyBrowserImmersive）。
                    if (BROWSER_IMMERSIVE) {
                        View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    } else {
                        0
                    }
        }
        returnComponent = readReturnComponent(intent)
        val aiControlEnabled = preferences.getBoolean(KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)
        val initialOwner = if (requestedAiStart || aiControlEnabled) BrowserOwner.AI else BrowserOwner.HUMAN
        // Open through the session so the host surface and shared page manager are established,
        // then adopt exactly that WebView. Reopening an already-active session is a no-op.
        val initial = session.open(initialOwner)
        buildPanel()
        applyBrowserLayoutDirection()
        if (adoptingGateway == null) {
        advancedController = BrowserAdvancedController(
            context = this,
            debugEnabled = preferences.getBoolean(BrowserRuntimeSettings.KEY_ADVANCED_ENABLED, false),
            networkMutationEnabled = preferences.getBoolean(BrowserRuntimeSettings.KEY_NETWORK_MUTATION_ENABLED, false),
        )
        commandExecutor = BrowserCommandExecutor(
            pauseMutatingDispatch = { controlTransferPending },
            activity = this,
            lifecycle = { lifecycleController },
            webView = { webView },
            actionGate = actionGate,
            automationEnabled = { preferences.getBoolean(KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED) },
            capture = ::captureFreshCurrentPage,
            publish = ::publishStatus,
            loadUrl = ::loadCommandUrl,
            closeSession = { releaseSession(finishActivity = true, reason = BrowserCloseReason.AI_CLOSE) },
            executePage = ::executePageCommand,
            executeFile = ::executeFileCommand,
            executeUpload = ::executeUploadCommand,
            invalidateUploadTask = ::revokeUploadGrantsForTask,
            selectionSequence = { selectionStore.currentSequence() },
            pageState = { webView?.let(pageStateTracker::state) },
            commitSameDocument = { view, before, after -> pageStateTracker.sameDocumentCommitted(view, before, after) },
            scheduleIdle = ::scheduleIdleAlarm,
            onAiToolActivity = { (aiIndicator as? BrowserAiIndicatorView)?.noteToolActivity() },
            activePageId = { pageManager?.activePageId },
            visualSurfaceAvailable = ::isVisualActionSurfaceAvailable,
            performNavigation = ::performAiNavigation,
            executeAdvanced = advanced@{ family, args, deadline, stillAuthorized, callback ->
                when (family) {
                    "selection" -> {
                        if (!stillAuthorized()) {
                            callback(JSONObject().put("code", "SESSION_REVOKED").put("executed", false).put("data", JSONObject()))
                        } else {
                            val session = lifecycleController.snapshot().sessionGeneration
                            callback(executeSelection(args.optString("action"), args, session))
                        }
                    }
                    "debug", "network" -> {
                        if (family == "debug" && args.optString("action") == "rendering") {
                            callback(BrowserRenderingCommand(
                                read = { BrowserRenderingPreferences.read(preferences) },
                                write = { BrowserRenderingPreferences.write(preferences, it) },
                                apply = ::applyRenderingPolicy,
                            ).execute(args, stillAuthorized))
                            return@advanced
                        }
                        val controller = advancedController
                        if (controller == null) {
                            callback(JSONObject().put("code", "CLOSED").put("executed", false).put("data", JSONObject()))
                        } else {
                            controller.executeAi(family, args, deadline, stillAuthorized, callback)
                        }
                    }
                    else -> callback(JSONObject().put("code", "UNSUPPORTED").put("executed", false).put("data", JSONObject()))
                }
            },
        )
        commandExecutor?.attachActionGate()
        } else {
            advancedController = adoptingGateway!!.advancedControllerOrNull()
            commandExecutor = adoptingGateway!!.ensureExecutor()
        }
        adoptingGateway?.setUiHost(sessionUiHost)
        BrowserCommandGatewayRegistry.install(this)
        cancelPendingNativeApprovalRequests = { BrowserControlApprovalRegistry.onActivityClosed() }
        runCatching { uploadIo.execute { runCatching { uploadStager.cleanupExpired() } } }
        registerLifecycleReceiver()
        if (requestedAiStart && openingRequestId != null &&
            SystemClock.elapsedRealtime() >= intent.getLongExtra(BrowserRuntimeSettings.EXTRA_OPEN_DEADLINE_ELAPSED, 0L)
        ) {
            releaseSession(finishActivity = true)
            return
        }
        // 转场放在全部早退分支之后：早退 finish 的窗口从未展示，
        // 不该给被覆盖的宿主页武装切出动画。
        applySoftTransition()
        deferWebViewUntilChromeDraw(initial)
        // 全屏在窗口挂载后断言（buildPanel→setContentView 已执行）：insetsController
        // 只在窗口挂载后可靠，提前拿可能为 null 被静默吞掉；onResume 里还会再断言一次。
        applyBrowserImmersive()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(BrowserRuntimeSettings.EXTRA_UPLOAD_APPROVAL_ONLY, false)) {
            val approvalId = intent.getStringExtra(BrowserRuntimeSettings.EXTRA_UPLOAD_APPROVAL_ID)
            if (!approvalId.isNullOrBlank()) {
                pendingUploadApprovalPresentationId = approvalId
                mainHandler.post { showPendingUploadApproval(approvalId) }
            }
            return
        }
        applySoftTransition()
        setIntent(intent)
        val oldLocaleTag = activeLocaleTag
        val oldLocaleFollowsSystem = localeFollowsSystem
        updateUiContext(intent)
        refreshBrowserTheme(intent.getStringExtra(BrowserContract.EXTRA_THEME_MODE))
        persistLocalePreference()
        if (oldLocaleTag != activeLocaleTag || oldLocaleFollowsSystem != localeFollowsSystem) {
            dismissMenu()
            recoveryPanel?.dismiss()
            recoveryPanel = null
            settingsDialog?.dismiss()
            if (pickerOverlay != null) cancelElementPicker()
            applyBrowserLayoutDirection()
            refreshChromeLabels()
        }
        readReturnComponent(intent)?.let { returnComponent = it }
        val snapshot = adoptingGateway?.setVisible(true) ?: lifecycleController.setVisible(true)
        if (snapshot.lifecycle == BrowserLifecycle.CLOSING) {
            releaseSession(finishActivity = true)
            return
        }
        cancelIdleAlarm()
        webView?.onResume()
        publishStatus(snapshot)
        scheduleLifecycleMonitor()
    }

    override fun onStart() {
        super.onStart()
        if (closed) return
        val snapshot = adoptingGateway?.setVisible(true) ?: lifecycleController.setVisible(true)
        if (snapshot.lifecycle == BrowserLifecycle.CLOSING) {
            releaseSession(finishActivity = true)
            return
        }
        cancelIdleAlarm()
        webView?.onResume()
        updateNavigationButtons()
        publishStatus(snapshot)
        scheduleLifecycleMonitor()
    }

    override fun onResume() {
        super.onResume()
        transitionForeground = true
        if (!closed) {
            // 首帧若仍未拿到 insetsController，resume 时自愈一次（见 applyBrowserImmersive）。
            applyBrowserImmersive()
            webView?.onResume()
            publishStatus(lifecycleController.snapshot())
            showPendingUploadApproval()
            pendingUploadApprovalPresentationId?.let(::showPendingUploadApproval)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !closed) {
            val pendingInput = pendingBrowserInput
            if (pendingInput != null) {
                pendingBrowserInput = null
                pendingInput.field.post {
                    if (pendingInput.generation == browserInputGeneration && transitionForeground && !closed &&
                        !isFinishing && !isDestroyed && hasWindowFocus() && pendingInput.field.isAttachedToWindow &&
                        pendingInput.field.isShown && pendingInput.field.hasFocus()
                    ) {
                        getSystemService(InputMethodManager::class.java)
                            ?.showSoftInput(pendingInput.field, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }
            mainHandler.post {
                showPendingUploadApproval()
                pendingUploadApprovalPresentationId?.let(::showPendingUploadApproval)
            }
        }
    }

    override fun onPause() {
        transitionForeground = false
        releaseBrowserInput()
        super.onPause()
    }

    private fun releaseBrowserInput() {
        browserInputGeneration++
        pendingBrowserInput = null
        val inputMethod = getSystemService(InputMethodManager::class.java)
        val dialogWindows = listOfNotNull(
            advancedDialog?.window, historyDialog?.window, bookmarksDialog?.window,
            pagesDialog?.window, settingsDialog?.window, uploadApprovalDialog?.window,
            pendingControlTransferDialog?.window,
        ) + javascriptPromptDialogs.mapNotNull { it.window } + listOfNotNull(recoveryPanel?.inputDialogWindow())
        val focusedViews = listOfNotNull(currentFocus, addressField, findField, pickerComment, webView) +
            dialogWindows.mapNotNull { it.currentFocus }
        (focusedViews.mapNotNull { it.windowToken } + listOfNotNull(window.decorView.windowToken) +
            dialogWindows.mapNotNull { it.decorView.windowToken }).distinct().forEach {
            inputMethod?.hideSoftInputFromWindow(it, 0)
        }
        focusedViews.forEach { it.clearFocus() }
    }

    private fun showBrowserInput(field: EditText) {
        val pendingInput = PendingBrowserInput(field, ++browserInputGeneration)
        pendingBrowserInput = pendingInput
        field.post {
            if (pendingBrowserInput !== pendingInput) return@post
            if (pendingInput.generation != browserInputGeneration || !transitionForeground || closed || isFinishing ||
                isDestroyed || !field.isAttachedToWindow || !field.isShown || !field.hasFocus()) {
                pendingBrowserInput = null
                return@post
            }
            if (!hasWindowFocus()) return@post
            pendingBrowserInput = null
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onStop() {
        if (!closed) {
            val snapshot = adoptingGateway?.setVisible(false) ?: lifecycleController.setVisible(false)
            webView?.onPause()
            publishStatus(snapshot)
            if (snapshot.lifecycle == BrowserLifecycle.CLOSING) {
                releaseSession(finishActivity = true)
            } else {
                scheduleIdleAlarm(snapshot)
                scheduleLifecycleMonitor()
            }
        }
        super.onStop()
    }

    override fun onBackPressed() {
        when {
            keyboardVisible() -> {
                val focused = currentFocus ?: pickerComment
                if (focused != null) {
                    (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.hideSoftInputFromWindow(focused.windowToken, 0)
                    focused.clearFocus()
                }
            }
            pickerOverlay != null -> cancelElementPicker()
            advancedDialog?.isShowing == true -> advancedDialog?.dismiss()
            historyDialog?.isShowing == true -> historyDialog?.dismiss()
            bookmarksDialog?.isShowing == true -> bookmarksDialog?.dismiss()
            pagesDialog?.isShowing == true -> pagesDialog?.dismiss()
            settingsDialog?.isShowing == true -> settingsDialog?.dismiss()
            downloadsPanel?.isShowing() == true -> downloadsPanel?.dismiss()
            menuPopup?.isShowing == true -> dismissMenu()
            findBar?.visibility == View.VISIBLE -> {
                webView?.clearMatches()
                findBar?.visibility = View.GONE
            }
            webView?.canGoBack() == true -> {
                webView?.let { view -> navigateAction(historyTargetUrl(view, -1)) { it.goBack() } }
            }
            else -> collapseToHome()
        }
    }

    private fun keyboardVisible(): Boolean {
        val root = window.decorView
        val visible = android.graphics.Rect()
        root.getWindowVisibleDisplayFrame(visible)
        return root.rootView.height - visible.bottom > dp(120)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        recoveryPanel?.dismiss()
        recoveryPanel = null
        refreshUiContextForConfiguration(newConfig)
        refreshBrowserTheme(null)
        refreshChromeLabels()
        applyBrowserLayoutDirection()
        if (pickerOverlay != null) cancelElementPicker()
    }

    private data class BrowserFrameIdentity(
        val sessionGeneration: Long,
        val controlGeneration: Long,
        val operationGeneration: Long,
        val pageId: String,
        val documentGeneration: Long,
        val url: String,
        val width: Int,
        val height: Int,
        val scrollX: Int,
        val scrollY: Int,
        val scale: Float,
        val zoomPercent: Int,
        val rotation: Int,
        val windowX: Int,
        val windowY: Int,
    )

    private fun browserFrameIdentity(view: WebView): BrowserFrameIdentity {
        val snapshot = lifecycleController.snapshot()
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return BrowserFrameIdentity(
            sessionGeneration = snapshot.sessionGeneration,
            controlGeneration = snapshot.controlGeneration,
            operationGeneration = snapshot.operationGeneration,
            pageId = pageManager?.activePageId.orEmpty(),
            documentGeneration = pageDocumentGeneration,
            url = view.url.orEmpty(),
            width = view.width,
            height = view.height,
            scrollX = view.scrollX,
            scrollY = view.scrollY,
            scale = view.scale,
            zoomPercent = zoomPercent,
            rotation = windowManager.defaultDisplay.rotation,
            windowX = location[0],
            windowY = location[1],
        )
    }

    private fun captureWindowFrame(
        view: WebView,
        identity: BrowserFrameIdentity,
        callback: (Bitmap?, String) -> Unit,
    ) {
        val decor = window.decorView
        if (closed) {
            callback(null, "BACKGROUND_UNAVAILABLE")
            return
        }
        if (!hasWindowFocus()) {
            callback(null, "WINDOW_NOT_ACTIVE")
            return
        }
        if (!lifecycleController.snapshot().visible || !decor.isAttachedToWindow ||
            !view.isAttachedToWindow || view.width <= 0 || view.height <= 0
        ) {
            callback(null, "BACKGROUND_UNAVAILABLE")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            callback(null, "PIXEL_READ_FAILED")
            return
        }

        val webLocation = IntArray(2)
        val decorLocation = IntArray(2)
        view.getLocationInWindow(webLocation)
        decor.getLocationInWindow(decorLocation)
        val source = Rect(webLocation[0], webLocation[1], webLocation[0] + view.width, webLocation[1] + view.height)
        val fullWebViewRect = Rect(source)
        // PixelCopy reads this window's surface, not the screen area excluding system overlays.
        val safeWindow = Rect(
            decorLocation[0],
            decorLocation[1],
            decorLocation[0] + decor.width,
            decorLocation[1] + decor.height,
        )
        if (!source.intersect(safeWindow) || source.isEmpty || source != fullWebViewRect) {
            callback(null, "CLIPPED_VIEWPORT")
            return
        }
        val width = source.width()
        val height = source.height()
        if (width.toLong() * height.toLong() > MAX_CAPTURE_PIXELS) {
            callback(null, "PIXEL_READ_FAILED")
            return
        }
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (_: RuntimeException) {
            callback(null, "PIXEL_READ_FAILED")
            return
        } catch (_: OutOfMemoryError) {
            callback(null, "PIXEL_READ_FAILED")
            return
        }

        val pickerIdentity = pickerOverlay
        val pickerView = pickerIdentity?.takeIf { it.visibility == View.VISIBLE }
        val pickerVisibility = pickerView?.visibility
        val finished = AtomicBoolean(false)
        var pixelCopyStarted = false
        var frameCommitObserver: android.view.ViewTreeObserver? = null
        var frameCommitCallback: Runnable? = null
        fun releaseFrameCommitCallback() {
            val observer = frameCommitObserver
            val pending = frameCommitCallback
            frameCommitObserver = null
            frameCommitCallback = null
            if (observer != null && pending != null && observer.isAlive) {
                runCatching { observer.unregisterFrameCommitCallback(pending) }
            }
        }
        val timeout = Runnable {
            if (finished.compareAndSet(false, true)) {
                releaseFrameCommitCallback()
                restorePickerVisibility(pickerView, pickerVisibility)
                if (!pixelCopyStarted) bitmap.recycle()
                callback(null, if (pixelCopyStarted) "PIXEL_READ_FAILED" else "NO_FRAME")
            }
        }
        fun finish(resultBitmap: Bitmap?, code: String) {
            if (!finished.compareAndSet(false, true)) {
                resultBitmap?.recycle()
                return
            }
            mainHandler.removeCallbacks(timeout)
            releaseFrameCommitCallback()
            restorePickerVisibility(pickerView, pickerVisibility)
            if (resultBitmap == null && !bitmap.isRecycled) bitmap.recycle()
            callback(resultBitmap, code)
        }
        fun readCommittedWindowFrame() {
            mainHandler.post {
                if (finished.get()) return@post
                if (closed) return@post finish(null, "BACKGROUND_UNAVAILABLE")
                if (!hasWindowFocus()) return@post finish(null, "WINDOW_NOT_ACTIVE")
                if (!lifecycleController.snapshot().visible) return@post finish(null, "BACKGROUND_UNAVAILABLE")
                if (!decor.isAttachedToWindow || !view.isAttachedToWindow) {
                    return@post finish(null, "BACKGROUND_UNAVAILABLE")
                }
                if (view !== webView || browserFrameIdentity(view) != identity) {
                    return@post finish(null, "IDENTITY_CHANGED")
                }
                if (pickerOverlay !== pickerIdentity || pickerOverlay?.visibility == View.VISIBLE) {
                    return@post finish(null, "IDENTITY_CHANGED")
                }
                pixelCopyStarted = true
                try {
                    PixelCopy.request(window, source, bitmap, PixelCopy.OnPixelCopyFinishedListener { result ->
                        mainHandler.post {
                            if (finished.get()) {
                                bitmap.recycle()
                                return@post
                            }
                            if (closed) return@post finish(null, "BACKGROUND_UNAVAILABLE")
                            if (!hasWindowFocus()) return@post finish(null, "WINDOW_NOT_ACTIVE")
                            if (!lifecycleController.snapshot().visible) return@post finish(null, "BACKGROUND_UNAVAILABLE")
                            if (!decor.isAttachedToWindow || !view.isAttachedToWindow) {
                                return@post finish(null, "BACKGROUND_UNAVAILABLE")
                            }
                            if (pickerOverlay !== pickerIdentity || pickerOverlay?.visibility == View.VISIBLE) {
                                return@post finish(null, "IDENTITY_CHANGED")
                            }
                            if (result != PixelCopy.SUCCESS) {
                                finish(null, if (result == PixelCopy.ERROR_SOURCE_NO_DATA) "NO_FRAME" else "PIXEL_READ_FAILED")
                            } else if (view !== webView || browserFrameIdentity(view) != identity) {
                                finish(null, "IDENTITY_CHANGED")
                            } else {
                                finish(bitmap, "OK")
                            }
                        }
                    }, mainHandler)
                } catch (_: RuntimeException) {
                    finish(null, "PIXEL_READ_FAILED")
                }
            }
        }

        mainHandler.postDelayed(timeout, PIXEL_COPY_TIMEOUT_MILLIS)
        if (pickerView == null) {
            readCommittedWindowFrame()
        } else {
            pickerView.visibility = View.INVISIBLE
            try {
                val observer = decor.viewTreeObserver
                val committed = Runnable {
                    frameCommitObserver = null
                    frameCommitCallback = null
                    readCommittedWindowFrame()
                }
                frameCommitObserver = observer
                frameCommitCallback = committed
                observer.registerFrameCommitCallback(committed)
            } catch (_: RuntimeException) {
                finish(null, "NO_FRAME")
            }
        }
    }

    private fun restorePickerVisibility(view: View?, visibility: Int?) {
        if (view != null && pickerOverlay === view && view.parent != null && view.visibility == View.INVISIBLE) {
            view.visibility = visibility ?: View.VISIBLE
        }
    }

    /** Captures the current WebView window region into a caller-owned bitmap. */
    fun captureCurrentPage(callback: (Bitmap?) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { captureCurrentPage(callback) }
            return
        }
        val view = webView
        if (closed || lifecycleController.snapshot().lifecycle != BrowserLifecycle.ACTIVE || view == null) {
            callback(null)
            return
        }
        captureWindowFrame(view, browserFrameIdentity(view)) { bitmap, _ -> callback(bitmap) }
    }

    fun captureFreshCurrentPage(deadlineAtElapsedMillis: Long, callback: (Bitmap?, String) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { captureFreshCurrentPage(deadlineAtElapsedMillis, callback) }
            return
        }
        val view = webView
        if (closed || view == null || lifecycleController.snapshot().lifecycle != BrowserLifecycle.ACTIVE) {
            callback(null, "BACKGROUND_UNAVAILABLE")
            return
        }
        val identity = browserFrameIdentity(view)
        val remaining = deadlineAtElapsedMillis - SystemClock.elapsedRealtime()
        if (remaining <= 0L) {
            callback(null, "DEADLINE_EXPIRED")
            return
        }
        if (!hasWindowFocus() || !lifecycleController.snapshot().visible || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
            captureBackgroundCurrentPage(identity, deadlineAtElapsedMillis, callback)
            return
        }
        val finished = AtomicBoolean(false)
        val timeoutReason = if (remaining <= VISUAL_STATE_TIMEOUT_MILLIS) "DEADLINE_EXPIRED" else "NO_FRAME"
        val timeout = Runnable {
            if (finished.compareAndSet(false, true)) callback(null, timeoutReason)
        }
        mainHandler.postDelayed(timeout, minOf(remaining, VISUAL_STATE_TIMEOUT_MILLIS))
        try {
            view.postVisualStateCallback(operationIds.incrementAndGet(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (finished.get()) return
                    val settle = Runnable {
                        if (finished.get()) return@Runnable
                        if (SystemClock.elapsedRealtime() >= deadlineAtElapsedMillis) {
                            if (finished.compareAndSet(false, true)) {
                                mainHandler.removeCallbacks(timeout)
                                callback(null, "DEADLINE_EXPIRED")
                            }
                        } else {
                            captureWindowFrame(view, identity) { bitmap, captureCode ->
                                if (finished.compareAndSet(false, true)) {
                                    mainHandler.removeCallbacks(timeout)
                                    callback(bitmap, captureCode)
                                }
                                else bitmap?.recycle()
                            }
                        }
                    }
                    mainHandler.postDelayed(settle, minOf(CAPTURE_SETTLE_MILLIS, remaining))
                }
            })
        } catch (_: RuntimeException) {
            mainHandler.removeCallbacks(timeout)
            if (finished.compareAndSet(false, true)) callback(null, "BACKGROUND_UNAVAILABLE")
        }
    }

    private fun captureBackgroundCurrentPage(
        identity: BrowserFrameIdentity,
        deadlineAtElapsedMillis: Long,
        callback: (Bitmap?, String) -> Unit,
    ) {
        val controller = advancedController
        val documentIdentity = controller?.currentDocumentIdentity()
        if (controller == null || documentIdentity == null || identity.url.isBlank()) {
            callback(null, "BACKGROUND_UNAVAILABLE")
            return
        }
        val finished = AtomicBoolean(false)
        val remaining = (deadlineAtElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
        val timeout = Runnable {
            if (finished.compareAndSet(false, true)) callback(null, "DEADLINE_EXPIRED")
        }
        mainHandler.postDelayed(timeout, remaining)
        controller.captureBackgroundScreenshot(identity.url, documentIdentity, identity.width, identity.height,
            deadlineAtElapsedMillis) { result ->
            mainHandler.post {
                val image = result.screenshot
                if (finished.get()) {
                    image?.pngBytes?.fill(0)
                    return@post
                }
                var bitmap: Bitmap? = null
                var code = result.code
                try {
                    val currentView = webView
                    if (closed || currentView == null || browserFrameIdentity(currentView) != identity ||
                        controller.currentDocumentIdentity() != documentIdentity
                    ) {
                        code = "IDENTITY_CHANGED"
                    } else if (image != null) {
                        val bytes = image.pngBytes
                        if (!BrowserBackgroundCaptureGate.validPng(bytes, image.viewportWidth, image.viewportHeight)) {
                            code = if (bytes.size > BrowserBackgroundCaptureGate.MAX_PNG_BYTES) "PNG_TOO_LARGE" else "PNG_INVALID"
                        } else {
                            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap == null || bitmap.width != image.viewportWidth || bitmap.height != image.viewportHeight) {
                                bitmap?.recycle()
                                bitmap = null
                                code = "PNG_INVALID"
                            } else code = "OK"
                        }
                    }
                } catch (_: OutOfMemoryError) {
                    bitmap?.recycle()
                    bitmap = null
                    code = "PNG_TOO_LARGE"
                } catch (_: RuntimeException) {
                    bitmap?.recycle()
                    bitmap = null
                    code = "PNG_INVALID"
                } finally {
                    image?.pngBytes?.fill(0)
                }
                if (finished.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeout)
                    callback(bitmap, code)
                } else bitmap?.recycle()
            }
        }
    }

    override fun onDestroy() {
        runCatching { cancelPendingNativeApprovalRequests?.invoke() }
        pendingExternalControlDecisionId?.let { resolveExternalControlDecision(it, approved = false) }
        controlTransferPolicy.destroy()
        pendingControlTransferDialog?.dismiss()
        pendingControlTransferDialog = null
        consumeAiTouchGesture = false
        uploadApprovalFocusObserver?.close()
        uploadApprovalFocusObserver = null
        failPendingUploadApprovalReplies("SESSION_REVOKED")
        // The adopted session owns its upload notice; only a window-owned notice is cancelled here.
        if (adoptingGateway == null) uploadApprovalNotice.cancel()
        recoveryPanel?.dismiss()
        recoveryPanel = null
        if (Build.VERSION.SDK_INT >= 33) {
            browserBackCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            browserBackCallback = null
        }
        cancelElementPicker()
        advancedDialog?.dismiss()
        historyDialog?.dismiss()
        bookmarksDialog?.dismiss()
        pagesDialog?.dismiss()
        settingsDialog?.dismiss()
        downloadsPanel?.dismiss()
        pendingDownloadSave = null
        mainHandler.removeCallbacks(webViewInitialization)
        chromeDrawListener?.let { listener ->
            window.decorView.viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(listener)
        }
        chromeDrawListener = null
        // Only release activity-owned resources. When adopting a background session the advanced
        // controller and WebView debugging belong to the gateway and must survive window teardown;
        // BrowserSessionGateway.close() reclaims them when the session itself ends.
        if (adoptingGateway == null) {
            advancedController?.close()
            advancedController = null
            WebView.setWebContentsDebuggingEnabled(false)
        }
        selectionExecutor.shutdownNow()
        mainHandler.removeCallbacks(lifecycleMonitor)
        cancelIdleAlarm()
        unregisterLifecycleReceiver()
        BrowserCommandGatewayRegistry.remove(this)
        BrowserControlApprovalRegistry.onActivityClosed()
        if (!closed) {
            val gateway = adoptingGateway
            if (gateway != null) {
                // The background session outlives this window: only detach and drop the UI host.
                runCatching { gateway.setUiHost(null) }
                // Release activity-bound JS dialog resolvers so they cannot retain this window;
                // unrelated session state is untouched.
                runCatching { javascriptPromptDialogs.toList().forEach { it.dismiss() } }
                runCatching { javascriptPromptDialogs.clear() }
                runCatching {
                    // Settle every pending alert/confirm/beforeunload/prompt result exactly once
                    // before dropping the map, so page JS is not left blocked after unmount.
                    val pendingDismissals = pendingJavaScriptDialogDismissals.values.flatMap { it.toList() }
                    pendingJavaScriptDialogDismissals.clear()
                    pendingDismissals.forEach { dismissal -> runCatching { dismissal.invoke() } }
                }
                runCatching { gateway.restoreSessionCallbacks() }
                runCatching { gateway.setVisible(false) }
                val detached = runCatching { gateway.host.unmount() }.getOrDefault(false)
                if (detached) {
                    publishStatus(gateway.host.snapshot())
                } else {
                    // Could not detach (capture restore failed). Terminate the session so the host's
                    // terminal force-dispose releases the destroyed activity/view references rather
                    // than retaining a dead window.
                    runCatching { gateway.close(BrowserCloseReason.ACTIVITY_DESTROYED) }
                    BrowserSessionGatewayRegistry.clear(gateway)
                    BrowserSessionHostRegistry.clear(gateway.host)
                }
            } else {
                releaseSession(finishActivity = false, reason = BrowserCloseReason.ACTIVITY_DESTROYED)
            }
        }
        // task feedback is session-owned; BrowserSessionGateway.close() reclaims the single store.
        super.onDestroy()
    }

    private val webViewInitialization: Runnable = Runnable {
        chromeDrawListener?.let { listener ->
            window.decorView.viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(listener)
        }
        chromeDrawListener = null
        if (closed || isFinishing || isDestroyed || webViewInitializationStarted) return@Runnable
        webViewInitializationStarted = true
        createWebView()
        if (webView == null) {
            webViewInitializationStarted = false
            AlertDialog.Builder(uiContext)
                .setMessage(R.string.browser_page_error)
                .setPositiveButton(R.string.browser_refresh) { _, _ -> mainHandler.post(webViewInitialization) }
                .setNegativeButton(R.string.browser_cancel, null)
                .show()
            return@Runnable
        }
        val snapshot = beginStartupNavigation(lifecycleController.snapshot())
        publishStatus(snapshot)
        scheduleLifecycleMonitor()
    }

    private fun deferWebViewUntilChromeDraw(initial: BrowserSnapshot) {
        val decor = window.decorView
        val listener = object : android.view.ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (chromeDrawListener !== this || chromeDrawPosted) return
                chromeDrawPosted = true
                if (!closed && !isFinishing && !isDestroyed) mainHandler.post(webViewInitialization)
            }
        }
        chromeDrawListener = listener
        decor.viewTreeObserver.addOnDrawListener(listener)
        if (initial.lifecycle != BrowserLifecycle.ACTIVE) mainHandler.post(webViewInitialization)
    }

    override fun execute(requestJson: String, callback: (String) -> Unit) {
        val executor = commandExecutor
        if (executor == null || closed) {
            val requestId = runCatching { JSONObject(requestJson).optString("requestId") }.getOrDefault("")
            callback(commandResponse(requestId, "CLOSED", false, "CLOSED").toString())
            return
        }
        executor.execute(requestJson, callback)
    }

    fun executeAdvanced(requestJson: String, callback: (String) -> Unit) = execute(requestJson, callback)

    fun executeSelection(action: String, args: JSONObject, sessionGeneration: Long): JSONObject {
        fun only(vararg names: String): Boolean = args.keys().asSequence().all { it in names }
        val snapshot = lifecycleController.snapshot()
        if (closed || snapshot.lifecycle != BrowserLifecycle.ACTIVE) {
            return JSONObject().put("code", "CLOSED").put("executed", false).put("data", JSONObject())
        }
        if (!preferences.getBoolean(KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)) {
            return JSONObject().put("code", "AUTOMATION_DISABLED").put("executed", false).put("data", JSONObject())
        }
        if (snapshot.sessionGeneration != sessionGeneration) {
            return JSONObject().put("code", "STALE_SESSION").put("executed", false).put("data", JSONObject())
        }
        if (action == "task_pending") {
            val cursor = args.opt("afterSequence")
            val destination = args.opt("destinationSessionKey")
            if (!only("action", "afterSequence", "destinationSessionKey") || cursor !is Int && cursor !is Long ||
                args.optLong("afterSequence") < 0 || destination !is String
            ) return JSONObject().put("code", "INVALID_ARGUMENT").put("executed", false).put("data", JSONObject())
            return taskFeedbackStore.pending(args.optLong("afterSequence"), sessionGeneration, destination)
        }
        if (action == "task_claim" || action == "task_admit") {
            if (args.length() != 5 || !only("action", "feedbackId", "destinationSessionKey", "requestId", "payloadDigest"))
                return JSONObject().put("code", "INVALID_ARGUMENT").put("executed", false).put("data", JSONObject())
            return if (action == "task_claim") taskFeedbackStore.claim(args, sessionGeneration)
                else taskFeedbackStore.admit(args, sessionGeneration)
        }
        if (action == "task_receipt") {
            if (args.length() != 5 || !only("action", "feedbackId", "destinationSessionKey", "requestId", "deliveryCode"))
                return JSONObject().put("code", "INVALID_ARGUMENT").put("executed", false).put("data", JSONObject())
            return taskFeedbackStore.receipt(args, sessionGeneration)
        }
        if (action == "claim" || action == "admit") {
            if (args.length() != 5 || !only("action", "selectionId", "destinationSessionKey", "requestId", "payloadDigest"))
                return JSONObject().put("code", "INVALID_ARGUMENT").put("executed", false).put("data", JSONObject())
            val nowElapsed = SystemClock.elapsedRealtime()
            return if (action == "claim") selectionStore.claim(args, nowElapsed) else selectionStore.admit(args, nowElapsed)
        }
        if (action == "receipt") {
            if (args.optString("destinationSessionKey").isBlank())
                return JSONObject().put("code", "INVALID_ARGUMENT").put("executed", false).put("data", JSONObject())
            if (!only("action", "selectionId", "destinationSessionKey", "deliveryCode", "requestId", "imageIncluded"))
                return JSONObject().put("code", "INVALID_ARGUMENT").put("executed", false).put("data", JSONObject())
            return selectionStore.recordReceipt(args, sessionGeneration, SystemClock.elapsedRealtime())
        }
        if (action == "pending") {
            val cursor = args.opt("afterSequence")
            if (!only("action", "afterSequence", "destinationSessionKey") ||
                (args.has("destinationSessionKey") && args.opt("destinationSessionKey") !is String) || (cursor != null &&
                ((cursor !is Int && cursor !is Long) || args.optLong("afterSequence") < 0))) {
                return JSONObject().put("code", "INVALID_ARGUMENT").put("executed", false).put("data", JSONObject())
            }
            return selectionStore.pending(args.optLong("afterSequence", 0), sessionGeneration,
                SystemClock.elapsedRealtime(), args.optString("destinationSessionKey"))
        }
        if (action == "list") {
            if (args.length() != 1 || !only("action")) return JSONObject().put("code", "INVALID_ARGUMENT").put("executed", false).put("data", JSONObject())
            return selectionStore.list(SystemClock.elapsedRealtime())
        }
        if (args.length() != 1 || args.opt("action") !is String) {
            return JSONObject().put("code", "INVALID_ARGUMENT").put("executed", false).put("data", JSONObject())
        }
        return selectionStore.execute(action, sessionGeneration, SystemClock.elapsedRealtime())
    }

    internal fun enqueueRecoveryDraft(draftJson: String): Boolean {
        val snapshot = lifecycleController.snapshot()
        if (closed || snapshot.lifecycle != BrowserLifecycle.ACTIVE || snapshot.sessionGeneration <= 0) return false
        return runCatching { taskFeedbackStore.enqueue(draftJson, snapshot.sessionGeneration) }.getOrDefault(false)
    }

    override fun cancel(requestId: String, callback: (String) -> Unit) {
        commandExecutor?.cancel(requestId, callback)
            ?: callback(commandResponse(requestId, "CLOSED", false, "CLOSED").toString())
    }

    override fun cancelWait(requestId: String, code: String): Boolean =
        commandExecutor?.cancelWait(requestId, code) ?: false

    private val sessionUiHost: BrowserSessionUiHost = object : BrowserSessionUiHost {
        override fun onSessionClosed(reason: BrowserCloseReason) {
            // The gateway has already destroyed these surfaces and execution resources.
            // Remove the retained window too, so a later launch creates a fresh session.
            webView = null
            pageManager = null
            commandExecutor = null
            advancedController = null
            pageContainer?.removeAllViews()
            releaseSession(finishActivity = true, reason = reason)
        }

        override val controlTransferPending: Boolean
            get() = this@BrowserActivity.controlTransferPending

        override fun onAiToolActivity() {
            (aiIndicator as? BrowserAiIndicatorView)?.noteToolActivity()
        }

        override fun installMountedCallbacks(view: WebView) {
            attachPageCallbacks(view, attachGate = true)
        }

        override fun executePage(action: String, args: JSONObject): BrowserPageCommandResult? =
            runCatching { executePageCommand(action, args) }.getOrNull()

        override fun executeFile(
            action: String,
            args: JSONObject,
            deadlineElapsedMillis: Long,
            authorized: () -> Boolean,
            callback: (JSONObject) -> Unit,
        ): Boolean {
            executeFileCommand(action, args, deadlineElapsedMillis, authorized, callback)
            return true
        }

        override fun executeUpload(
            request: BrowserCommandRequest,
            identity: BrowserTaskPageIdentity,
            token: BrowserOperationToken,
            authorized: () -> Boolean,
            callback: (JSONObject) -> Unit,
        ): Boolean {
            executeUploadCommand(request, identity, token, authorized, callback)
            return true
        }

        override fun executeAdvanced(
            family: String,
            args: JSONObject,
            deadlineElapsedMillis: Long,
            authorized: () -> Boolean,
            callback: (JSONObject) -> Unit,
        ): Boolean {
            if (family == "debug" && args.optString("action") == "rendering") {
                callback(BrowserRenderingCommand(
                    read = { BrowserRenderingPreferences.read(preferences) },
                    write = { BrowserRenderingPreferences.write(preferences, it) },
                    apply = ::applyRenderingPolicy,
                ).execute(args, authorized))
                return true
            }
            val controller = advancedController ?: return false
            controller.executeAi(family, args, deadlineElapsedMillis, authorized, callback)
            return true
        }

        override fun capture(deadlineElapsedMillis: Long, callback: (Bitmap?, String) -> Unit): Boolean {
            if (closed || isFinishing || isDestroyed) return false
            captureFreshCurrentPage(deadlineElapsedMillis, callback)
            return true
        }
    }

    private fun beginStartupNavigation(initial: BrowserSnapshot): BrowserSnapshot {
        val rawUrl = openingUrl?.trim().orEmpty()
        if (initial.owner != BrowserOwner.AI || rawUrl.isBlank()) return initial
        val uri = toWebUri(rawUrl) ?: return initial
        val token = lifecycleController.beginOperation(
            operationId = "startup-" + openingRequestId.orEmpty(),
            context = lifecycleController.snapshot().commandContext(),
            requestedDeadlineAtMillis = SystemClock.elapsedRealtime() + NAVIGATION_DEADLINE_MILLIS,
        ) ?: return lifecycleController.snapshot()
        webView?.let { view -> navigationTracker.begin(view, token, uri.toString()) }
        webView?.let { view -> pageStateTracker.begin(view, uri.toString(), view.url) }
        commandExecutor?.onDocumentStarted()
        webView?.loadUrl(uri.toString())
        return lifecycleController.snapshot()
    }

    private fun loadCommandUrl(rawUrl: String): Boolean {
        val uri = toWebUri(rawUrl) ?: return false
        val view = webView ?: return false
        pageStateTracker.begin(view, uri.toString(), view.url)
        commandExecutor?.onDocumentStarted()
        view.loadUrl(uri.toString())
        return true
    }

    private fun performAiNavigation(action: String): Boolean {
        val view = webView ?: return false
        val expectedUrl = when (action) {
            "back" -> if (view.canGoBackOrForward(-1)) historyTargetUrl(view, -1) else return false
            "forward" -> if (view.canGoBackOrForward(1)) historyTargetUrl(view, 1) else return false
            "reload" -> view.url
            else -> return false
        }
        pageStateTracker.begin(view, expectedUrl, view.url)
        commandExecutor?.onDocumentStarted()
        when (action) {
            "back" -> view.goBackOrForward(-1)
            "forward" -> view.goBackOrForward(1)
            "reload" -> view.reload()
        }
        updateNavigationButtons()
        return true
    }

    private fun updateUiContext(source: Intent?) {
        val hasLocaleExtra = source?.hasExtra(BrowserContract.EXTRA_LOCALE_TAG) == true
        val requestedTag = if (hasLocaleExtra) {
            BrowserUiSettings.normalizeLocaleTag(source?.getStringExtra(BrowserContract.EXTRA_LOCALE_TAG))
        } else {
            BrowserUiSettings.normalizeLocaleTag(preferences.getString(BrowserRuntimeSettings.KEY_LOCALE_TAG, null))
        }
        val configuredTag = if (Build.VERSION.SDK_INT >= 24) {
            resources.configuration.locales[0]?.toLanguageTag().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            resources.configuration.locale?.toLanguageTag().orEmpty()
        }
        localeFollowsSystem = requestedTag.isBlank()
        activeLocaleTag = requestedTag.ifBlank { configuredTag }
        val locale = Locale.forLanguageTag(activeLocaleTag)
        val configuration = Configuration(resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        val palette = BrowserRuntimePalette.resolve(this, source?.getStringExtra(BrowserContract.EXTRA_THEME_MODE))
        uiContext = ContextThemeWrapper(
            this,
            if (palette.dark) R.style.BrowserRuntimeThemeDark else R.style.BrowserRuntimeTheme,
        ).apply { applyOverrideConfiguration(configuration) }
    }

    private fun reserveSessionGeneration(): Long? {
        val start = preferences.getLong(KEY_NEXT_SESSION_GENERATION, 0L)
        if (start < 0L || start > MAX_SAFE_GENERATION - SESSION_GENERATION_RESERVATION) return null
        val next = start + SESSION_GENERATION_RESERVATION
        val committed = preferences.edit().putLong(KEY_NEXT_SESSION_GENERATION, next).commit()
        return start.takeIf { committed }
    }

    /** Creates the session this window owns when no background session exists yet. */
    private fun createOwnSession(): BrowserSessionGateway? =
        BrowserSessionFactory.createOrReuse(applicationContext)

    private fun refreshUiContextForConfiguration(configuration: Configuration) {
        val configuredTag = if (Build.VERSION.SDK_INT >= 24) {
            configuration.locales[0]?.toLanguageTag().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            configuration.locale?.toLanguageTag().orEmpty()
        }
        if (localeFollowsSystem) activeLocaleTag = configuredTag
        val locale = Locale.forLanguageTag(activeLocaleTag)
        val localized = Configuration(configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        val dark = BrowserRuntimePalette.resolve(this).dark
        uiContext = ContextThemeWrapper(
            this,
            if (dark) R.style.BrowserRuntimeThemeDark else R.style.BrowserRuntimeTheme,
        ).apply { applyOverrideConfiguration(localized) }
        persistLocalePreference()
    }

    private fun persistLocalePreference() {
        preferences.edit()
            .putString(BrowserRuntimeSettings.KEY_LOCALE_TAG, if (localeFollowsSystem) "" else activeLocaleTag)
            .apply()
    }

    private fun refreshChromeLabels() {
        titleView?.text = uiContext.getString(R.string.browser_title)
        aiIndicator?.contentDescription = uiContext.getString(R.string.browser_ai_control)
        collapseButton?.contentDescription = uiContext.getString(R.string.browser_collapse)
        backButton?.contentDescription = uiContext.getString(R.string.browser_back)
        forwardButton?.contentDescription = uiContext.getString(R.string.browser_forward)
        reloadButton?.contentDescription = uiContext.getString(R.string.browser_refresh)
        addressSearchButton?.contentDescription = uiContext.getString(R.string.browser_search_submit)
        moreButton?.contentDescription = uiContext.getString(R.string.browser_more)
        selectionButton?.contentDescription = uiContext.getString(R.string.browser_selection_tab)
        updateBookmarkButton()
        addressField?.hint = uiContext.getString(R.string.browser_address_hint)
        findField?.hint = uiContext.getString(R.string.browser_find_hint)
    }

    private fun applyBrowserLayoutDirection() {
        window.decorView.layoutDirection = uiContext.resources.configuration.layoutDirection
    }

    private fun buildPanel() {
        val root = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_PAPER)
        }
        applyWindowInsets(root)
        val titleRow = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(12), 0)
            minimumHeight = dp(44)
            setBackgroundColor(COLOR_PAPER)
        }
        val navigationGroup = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
            background = roundedBackground(COLOR_PAPER, COLOR_LINE, dp(22))
        }
        backButton = iconButton(CommonR.drawable.ic_arrow_left, R.string.browser_back) {
            webView?.let { view -> navigateAction(historyTargetUrl(view, -1)) { it.goBack() } }
        }
        forwardButton = iconButton(CommonR.drawable.ic_arrow_right, R.string.browser_forward) {
            webView?.let { view -> navigateAction(historyTargetUrl(view, 1)) { it.goForward() } }
        }
        reloadButton = iconButton(CommonR.drawable.ic_refresh, R.string.browser_refresh) {
            webView?.let { view -> navigateAction(view.url) { it.reload() } }
        }
        navigationGroup.addView(backButton, LinearLayout.LayoutParams(dp(38), dp(40)))
        navigationGroup.addView(forwardButton, LinearLayout.LayoutParams(dp(38), dp(40)))
        navigationGroup.addView(verticalDivider())
        navigationGroup.addView(reloadButton, LinearLayout.LayoutParams(dp(38), dp(40)))
        aiIndicator = BrowserAiIndicatorView(uiContext).apply {
            contentDescription = uiContext.getString(R.string.browser_ai_control)
            setOnClickListener { openDshPage() }
        }
        collapseButton = iconButton(CommonR.drawable.ic_x, R.string.browser_collapse) { collapseToHome() }
        titleView = null
        titleRow.addView(navigationGroup)
        titleRow.addView(View(uiContext), LinearLayout.LayoutParams(0, 1, 1f))
        selectionButton = iconButton(CommonR.drawable.ic_browser_selection, R.string.browser_selection_tab) {
            showElementPicker()
        }.apply { background = roundedBackground(COLOR_PAPER, COLOR_LINE, dp(22)) }
        titleRow.addView(selectionButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        bookmarkButton = iconButton(R.drawable.ic_browser_star_toggle, R.string.browser_bookmark_add) {
            toggleCurrentPageBookmark()
        }
        titleRow.addView(bookmarkButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        titleRow.addView(aiIndicator, LinearLayout.LayoutParams(dp(40), dp(40)))
        titleRow.addView(collapseButton)

        val addressRow = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
            minimumHeight = dp(48)
            setBackgroundColor(COLOR_TOOLBAR)
        }
        val addressContainer = LinearLayout(uiContext).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), 0, dp(10), 0)
            background = roundedBackground(COLOR_PAPER, COLOR_LINE, dp(24))
        }
        addressField = EditText(uiContext).apply {
            hint = uiContext.getString(R.string.browser_address_hint)
            textSize = 13f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setTextColor(COLOR_INK)
            setHintTextColor(COLOR_MUTED)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setPadding(dp(12), 0, dp(6), 0)
            background = null
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    navigateFromAddress()
                    true
                } else false
            }
        }
        addressContainer.addView(addressField, LinearLayout.LayoutParams(0, dp(36), 1f))
        addressSearchButton = iconButton(CommonR.drawable.ic_search, R.string.browser_search_submit) {
            navigateFromAddress()
        }
        addressContainer.addView(addressSearchButton, LinearLayout.LayoutParams(dp(36), dp(36)))
        moreButton = iconButton(CommonR.drawable.ic_dots_horizontal, R.string.browser_more) { showMoreMenu(it) }
        moreButton?.background = roundedBackground(COLOR_PAPER, COLOR_LINE, dp(24))
        addressRow.addView(addressContainer, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            marginEnd = dp(8)
        })
        addressRow.addView(moreButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        navigationRow = addressRow

        pickerToolbar = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
            minimumHeight = dp(48)
            setBackgroundColor(COLOR_TOOLBAR)
            visibility = View.GONE
            val element = pickerModeButton(CommonR.drawable.ic_browser_selection, R.string.browser_picker_element) {
                setPickerMode(BrowserSelectionStore.MODE_ELEMENT)
            }
            val area = pickerModeButton(CommonR.drawable.ic_browser_camera, R.string.browser_picker_area) {
                setPickerMode(BrowserSelectionStore.MODE_AREA)
            }
            pickerElementModeButton = element
            pickerAreaModeButton = area
            addView(element, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(area, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(iconButton(CommonR.drawable.ic_x, R.string.browser_picker_cancel) {
                cancelElementPicker()
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
        }

        findBar = buildFindBar().apply { visibility = View.GONE }
        progressBar = ProgressBar(uiContext, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(COLOR_INK)
            progressBackgroundTintList = ColorStateList.valueOf(COLOR_LINE)
            visibility = View.GONE
        }
        root.addView(titleRow)
        root.addView(horizontalDivider())
        root.addView(addressRow)
        root.addView(horizontalDivider())
        root.addView(pickerToolbar)
        root.addView(findBar)
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        setContentView(root)
    }

    private fun applyWindowInsets(root: View) {
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, windowInsets ->
            val safeInsets = if (Build.VERSION.SDK_INT >= 30) {
                val bars = windowInsets.getInsets(android.view.WindowInsets.Type.systemBars())
                val cutout = windowInsets.getInsets(android.view.WindowInsets.Type.displayCutout())
                val ime = windowInsets.getInsets(android.view.WindowInsets.Type.ime())
                Insets.of(
                    maxOf(bars.left, cutout.left),
                    maxOf(bars.top, cutout.top),
                    maxOf(bars.right, cutout.right),
                    maxOf(bars.bottom, cutout.bottom, ime.bottom),
                )
            } else if (BROWSER_IMMERSIVE) {
                // API 29 使用当前可见系统窗口的 insets：隐藏状态栏时不保留顶部占位，
                // 底部导航栏或手势条的 inset 仍保留。
                @Suppress("DEPRECATION")
                Insets.of(
                    windowInsets.systemWindowInsetLeft,
                    windowInsets.systemWindowInsetTop,
                    windowInsets.systemWindowInsetRight,
                    windowInsets.systemWindowInsetBottom,
                )
            } else {
                @Suppress("DEPRECATION")
                Insets.of(
                    windowInsets.stableInsetLeft,
                    windowInsets.stableInsetTop,
                    windowInsets.stableInsetRight,
                    windowInsets.stableInsetBottom,
                )
            }
            view.setPadding(
                initialLeft + safeInsets.left,
                initialTop + safeInsets.top,
                initialRight + safeInsets.right,
                initialBottom + safeInsets.bottom,
            )
            windowInsets
        }
    }

    private fun refreshBrowserTheme(mode: String?, dismissPanels: Boolean = true) {
        val normalizedMode = mode?.let(BrowserUiSettings::normalizeThemeMode)
            ?: BrowserRuntimeSettings.preferences(this).getString(BrowserRuntimeSettings.KEY_THEME_MODE, null)
                ?.let(BrowserUiSettings::normalizeThemeMode)
            ?: BrowserUiSettings.THEME_SYSTEM
        BrowserRuntimeSettings.preferences(this).edit()
            .putString(BrowserRuntimeSettings.KEY_THEME_MODE, normalizedMode)
            .apply()
        val previous = runtimePalette
        val next = BrowserRuntimePalette.resolve(this, normalizedMode)
        if (previous == next) return
        runtimePalette = next
        pageManager?.all?.forEach { page ->
            page.content.context.setTheme(if (next.dark) R.style.BrowserRuntimeThemeDark else R.style.BrowserRuntimeTheme)
            BrowserWebTheme.apply(page.content, BrowserWebThemePolicy(next.dark, next.paper))
        }
        uiContext = ContextThemeWrapper(
            uiContext,
            if (next.dark) R.style.BrowserRuntimeThemeDark else R.style.BrowserRuntimeTheme,
        )
        window.statusBarColor = next.paper
        window.navigationBarColor = next.paper
        @Suppress("DEPRECATION")
        run {
            var flags = window.decorView.systemUiVisibility
            flags = if (next.dark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            window.decorView.systemUiVisibility = flags
        }
        if (dismissPanels) {
            dismissMenu()
            historyDialog?.dismiss()
            bookmarksDialog?.dismiss()
            pagesDialog?.dismiss()
            settingsDialog?.dismiss()
            advancedDialog?.dismiss()
            downloadsPanel?.dismiss()
        }
        rethemeViewTree(window.decorView, previous, next)
        (aiIndicator as? BrowserAiIndicatorView)?.setTheme(next.ink, next.blue, next.dark)
        updateBookmarkButton()
        refreshChromeLabels()
    }

    private fun rethemeViewTree(view: View, old: BrowserRuntimePalette, new: BrowserRuntimePalette) {
        if (view is WebView) return
        view.background?.let { drawable ->
            if (drawable is android.graphics.drawable.ColorDrawable) {
                drawable.color = remapBrowserColor(drawable.color, old, new)
            } else if (drawable is BrowserThemedGradientDrawable) {
                drawable.remapColors { remapBrowserColor(it, old, new) }
            } else if (drawable is android.graphics.drawable.GradientDrawable) {
                drawable.color?.defaultColor?.let { drawable.setColor(remapBrowserColor(it, old, new)) }
            }
        }
        if (view is TextView) view.setTextColor(remapBrowserColor(view.currentTextColor, old, new))
        if (view is ImageView) {
            view.colorFilter?.let {
                view.setColorFilter(if (view === bookmarkButton && view.isSelected) new.blue else new.ink)
            }
        }
        if (view is BrowserElementPickerView) view.setThemeColors(new.blue)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) rethemeViewTree(view.getChildAt(index), old, new)
        }
    }

    private fun remapBrowserColor(color: Int, old: BrowserRuntimePalette, new: BrowserRuntimePalette): Int = when (color) {
        old.paper -> new.paper
        old.ink -> new.ink
        old.muted -> new.muted
        old.soft -> new.soft
        old.toolbar -> new.toolbar
        old.line -> new.line
        old.strongLine -> new.strongLine
        old.blue -> new.blue
        old.pickerBlueSoft -> new.pickerBlueSoft
        else -> color
    }

    private fun applyBrowserUiSettings(intent: Intent) {
        val previousLocale = activeLocaleTag
        val previousFollowSystem = localeFollowsSystem
        updateUiContext(intent)
        persistLocalePreference()
        refreshBrowserTheme(intent.getStringExtra(BrowserContract.EXTRA_THEME_MODE))
        if (previousLocale != activeLocaleTag || previousFollowSystem != localeFollowsSystem) {
            dismissMenu()
            historyDialog?.dismiss()
            bookmarksDialog?.dismiss()
            pagesDialog?.dismiss()
            settingsDialog?.dismiss()
            advancedDialog?.dismiss()
            downloadsPanel?.dismiss()
            if (pickerOverlay != null) cancelElementPicker()
            applyBrowserLayoutDirection()
            refreshChromeLabels()
        }
    }

    private fun buildFindBar(): LinearLayout {
        val row = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setBackgroundColor(COLOR_SOFT)
        }
        findField = EditText(uiContext).apply {
            hint = uiContext.getString(R.string.browser_find_hint)
            textSize = 14f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = roundedBackground(COLOR_PAPER, COLOR_LINE, dp(8))
            addTextChangedListener(SimpleTextWatcher { query ->
                if (query.isBlank()) webView?.clearMatches() else webView?.findAllAsync(query)
            })
        }
        row.addView(findField, LinearLayout.LayoutParams(0, dp(40), 1f))
        row.addView(iconButton(CommonR.drawable.ic_arrow_left, R.string.browser_find_previous) { webView?.findNext(false) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(iconButton(CommonR.drawable.ic_arrow_right, R.string.browser_find_next) { webView?.findNext(true) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(iconButton(CommonR.drawable.ic_x, R.string.browser_cancel) {
            webView?.clearMatches()
            findBar?.visibility = View.GONE
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        return row
    }

    private fun createWebView() {
        if (webView != null) return
        val adopting = adoptingGateway
        if (adopting != null) {
            // The session already owns exactly one WebView (created in onCreate via session.open).
            val view = adopting.host.surfaceOrNull()
                ?: adopting.open(lifecycleController.snapshot().owner).let { adopting.host.surfaceOrNull() }
                ?: return
            pageManager = adopting.pageManagerOrNull()
            attachPageCallbacks(view, attachGate = true)
            val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as? LinearLayout
            val container = pageContainer ?: FrameLayout(uiContext)
            pageContainer = container
            if (container.parent == null) root?.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            // Only bind the WebView after the host actually reparents it; if a capture still owns
            // the view the mount aborts and we leave webView null so the deferred init can retry.
            if (!adopting.host.mount(container, ActivitySessionWindowHost(this))) return
            webView = view
            addressField?.setText(browserAddressText(view.url))
            view.onResume()
            adopting.setVisible(true)
            updateNavigationButtons()
            return
        }
        pageManager = BrowserPageManager()
        val view = createPageWebView()
        val page = pageManager?.add(view) ?: run {
            destroyPageWebView(view)
            return
        }
        webView = view
        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as? LinearLayout
        val container = FrameLayout(uiContext).apply {
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        pageContainer = container
        root?.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (openingUrl.isNullOrBlank() || lifecycleController.snapshot().owner != BrowserOwner.AI) {
            view.loadUrl("about:blank")
        }
        updateNavigationButtons()
    }

    private fun createPageWebView(attachGate: Boolean = true): WebView {
        // Adopting sessions create pages from the session's application-context factory so a
        // long-lived WebView never captures the activity-wrapped context; activity callbacks are
        // installed only for the mounted window and removed again by restoreSessionCallbacks.
        val surface = adoptingGateway?.createPageSurface() ?: pageFactory.create()
        return attachPageCallbacks(surface, attachGate)
    }

    /**
     * Installs the activity's page callbacks (downloads, dialogs, upload chooser, navigation and
     * error handling) on an existing WebView. A background session restores its own callbacks on
     * unmount through BrowserSessionGateway.restoreSessionCallbacks, so adopted surfaces do not
     * retain a destroyed activity.
     */
    private fun attachPageCallbacks(view: WebView, attachGate: Boolean = true): WebView {
        view.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val target = runCatching { Uri.parse(url) }.getOrNull()
            if (!isActivePage(view) || closed || target == null || !isSupportedWebUri(target)) return@DownloadListener
            val displayDownloadPanel = lifecycleController.snapshot().owner == BrowserOwner.HUMAN
            val aiDownloadPageId = if (!displayDownloadPanel) {
                val id = pageManager?.all?.firstOrNull { it.content === view }?.id ?: return@DownloadListener
                id
            } else null
            val aiDownloadClaim = aiDownloadPageId?.let { commandExecutor?.claimBrowserEventExpectation(it, "download") }
            if (aiDownloadPageId != null && aiDownloadClaim == null) return@DownloadListener
            val dispatchAuth = lifecycleController.snapshot()
            val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            val safeName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = BrowserDownloadRequest(
                    url = url,
                    suggestedFileName = safeName,
                    cookieHeader = cookie,
                    userAgent = userAgent?.take(1024),
            )
            runCatching {
                downloadsExecutor.execute {
                    val manager = runCatching { ensureDownloadsManager() }.getOrNull()
                    if (aiDownloadClaim != null) {
                        mainHandler.post {
                            val latest = lifecycleController.snapshot()
                            if (manager == null || latest.lifecycle != BrowserLifecycle.ACTIVE ||
                                latest.owner != BrowserOwner.AI || latest.sessionGeneration != dispatchAuth.sessionGeneration ||
                                latest.controlGeneration != dispatchAuth.controlGeneration
                            ) return@post
                            val started = aiDownloadClaim.executeIfValid { manager.start(request) } ?: return@post
                            if (started.state == BrowserDownloadState.FAILED) {
                                toast(R.string.browser_download_failed)
                            } else if (started.state in setOf(
                                    BrowserDownloadState.QUEUED, BrowserDownloadState.DOWNLOADING, BrowserDownloadState.COMPLETED,
                                )) {
                                val safe = BrowserPageStateTracker.publicUrl(url)
                                commandExecutor?.publishBrowserEvent("download", requireNotNull(aiDownloadPageId),
                                    JSONObject().put("url", safe.value ?: JSONObject.NULL).put("downloadId", started.id))
                            }
                        }
                    } else {
                        val started = runCatching { manager?.start(request) }.getOrNull()
                        mainHandler.post {
                            if (started == null || started.state == BrowserDownloadState.FAILED) toast(R.string.browser_download_failed)
                            else if (displayDownloadPanel) showDownloadsPanel()
                        }
                    }
                }
            }.onFailure {
                toast(R.string.browser_download_failed)
            }
        })
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (view == null || !isActivePage(view)) return
                progressBar?.apply {
                    progress = newProgress
                    visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
                updateNavigationButtons()
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                // Single shared popup path: the adopted session owns new-window policy/creation.
                adoptingGateway?.let { return it.handleCreateWindow(view, isUserGesture, resultMsg) }
                val parent = view ?: return false
                val manager = pageManager ?: return false
                val parentPageId = manager.all.firstOrNull { it.content === parent }?.id ?: return false
                if (closed || lifecycleController.snapshot().owner != BrowserOwner.AI ||
                    !isActivePage(parent) ||
                    resultMsg == null || manager.size >= BrowserPageManager.MAX_PAGES
                ) return false
                val claim = commandExecutor?.claimBrowserEventExpectation(parentPageId, "new_window") ?: return false
                val dispatchAuth = lifecycleController.snapshot()
                val previousActive = manager.activePageId
                val child = runCatching { createPageWebView(attachGate = false) }.getOrNull() ?: return false
                val childPage = manager.add(child)
                if (childPage == null) {
                    destroyPageWebView(child)
                    return false
                }
                previousActive?.let(manager::switchTo)
                val transport = resultMsg.obj as? WebView.WebViewTransport
                if (transport == null) {
                    manager.remove(child)
                    destroyPageWebView(child)
                    return false
                }
                transport.webView = child
                val latestAuth = lifecycleController.snapshot()
                if (!claim.isValid() || latestAuth.owner != BrowserOwner.AI ||
                    latestAuth.sessionGeneration != dispatchAuth.sessionGeneration ||
                    latestAuth.controlGeneration != dispatchAuth.controlGeneration || closed || !isActivePage(parent)
                ) {
                    manager.remove(child)
                    destroyPageWebView(child)
                    return false
                }
                val published = commandExecutor?.publishBrowserEvent("new_window", parentPageId,
                    JSONObject().put("newPageId", childPage.id))
                if (published == null) {
                    manager.remove(child)
                    destroyPageWebView(child)
                    return false
                }
                resultMsg.sendToTarget()
                return true
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean =
                handleJavaScriptDialog(view, url, "alert", message, null, result, null)

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean =
                handleJavaScriptDialog(view, url, "confirm", message, null, result, null)

            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?,
            ): Boolean = handleJavaScriptDialog(view, url, "prompt", message, defaultValue, result, result)

            override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean =
                handleJavaScriptDialog(view, url, "beforeunload", message, null, result, null)

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                if (webView != null && filePathCallback != null && uploadActivationIsolation.consumeLateChooser(webView)) {
                    filePathCallback.onReceiveValue(null)
                    return true
                }
                if (webView == null || filePathCallback == null || fileChooserParams == null ||
                    webView !== this@BrowserActivity.webView || !isActivePage(webView) || closed
                ) {
                    filePathCallback?.onReceiveValue(null)
                    return true
                }
                val snapshot = lifecycleController.snapshot()
                val activation = pendingUploadActivation
                if (snapshot.owner == BrowserOwner.AI) {
                    if (activation == null || activation.view !== webView || activation.identity != commandExecutor?.currentUploadPageIdentity()) {
                        filePathCallback.onReceiveValue(null)
                        return true
                    }
                    val grantCurrent = uploadGrantStore.get(activation.grantId, SystemClock.elapsedRealtime())?.let {
                        it.state == BrowserUploadGrantState.READY && it.taskId == activation.taskId && it.identity == activation.identity
                    } == true
                    if (!isVisualActionSurfaceAvailable() || !grantCurrent) {
                        mainHandler.removeCallbacks(activation.timeout)
                        pendingUploadActivation = null
                        if (activation.dispatched) uploadActivationIsolation.markUnresolved(activation.view, activation.identity)
                        completeUploadActivation(activation, uploadResult("UPLOAD_ACTIVATION_UNRESOLVED", activation.dispatched))
                        filePathCallback.onReceiveValue(null)
                        return true
                    }
                    mainHandler.removeCallbacks(activation.timeout)
                    pendingUploadActivation = null
                    val handled = interceptApprovedUploadChooser(
                        webView, filePathCallback, fileChooserParams, activation.grantId, activation.taskId,
                    )
                    if (handled && pendingUploadChooser?.grantId == activation.grantId) {
                        completeUploadActivation(activation, uploadResult("OK", true,
                            JSONObject().put("approvalId", activation.grantId).put("state", "chooser_pending")))
                    } else {
                        completeUploadActivation(activation, uploadResult("UPLOAD_GRANT_MISMATCH", activation.dispatched))
                    }
                    return true
                }
                if (activation != null) {
                    mainHandler.removeCallbacks(activation.timeout)
                    pendingUploadActivation = null
                    if (activation.dispatched) uploadActivationIsolation.markUnresolved(activation.view, activation.identity)
                    completeUploadActivation(activation, uploadResult("UPLOAD_ACTIVATION_UNRESOLVED", activation.dispatched))
                    filePathCallback.onReceiveValue(null)
                    return true
                }
                if (interceptApprovedUploadChooser(webView, filePathCallback, fileChooserParams)) return true
                launchSystemFileChooser(webView, filePathCallback, fileChooserParams)
                return true
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!isSupportedWebUri(request.url)) {
                    toast(R.string.browser_blocked_scheme)
                    return true
                }
                if (request.isForMainFrame && request.hasGesture() && isActivePage(view)) {
                    beginUserNavigation(view, request.url.toString())
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (url != null && view.url != url) return
                pageStateTracker.pageStarted(view, url, view.url)
                if (pendingFileChooserWebView === view) cancelPendingFileChooser()
                if (isActivePage(view)) commandExecutor?.currentUploadPageIdentity()?.let(::revokeUploadGrantsForIdentity)
                updatePageInfo(view, view.title.orEmpty(), view.url.orEmpty())
                if (!isActivePage(view)) return
                navigationTracker.bindStart(view, url, view.url)
                pageDocumentGeneration++
                zoomRequestGeneration++
                zoomPercent = 100
                requestedZoomPercent = 100
                updateZoomLabels()
                cancelElementPicker()
                advancedController?.onDocumentStarted(url)
                commandExecutor?.onDocumentStarted()
                pageManager?.all?.firstOrNull { it.content === view }?.id?.let { pageId ->
                    commandExecutor?.onMainFrameStarted(pageId)
                }
                if (url != null && isSupportedWebUri(Uri.parse(url))) addressField?.setText(url)
                updateNavigationButtons()
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                pageStateTracker.pageCommittedVisible(view, url, view.url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (url != null && view.url != url) return
                if (url != null) {
                    val navId = pageStateTracker.state(view)?.navigationId
                    view.evaluateJavascript("document.readyState") { encoded ->
                        val readyState = runCatching {
                            org.json.JSONTokener(encoded).nextValue() as? String
                        }.getOrNull()
                        val current = pageStateTracker.state(view)
                        if (current?.navigationId == navId && view.url == url) {
                            val finished = pageStateTracker.pageFinished(view, url, view.url, readyState)
                            if (finished?.loadState == BrowserPageStateTracker.LoadState.FINISHED && isActivePage(view)) {
                                pageManager?.all?.firstOrNull { it.content === view }?.id?.let { pageId ->
                                    val safe = BrowserPageStateTracker.publicUrl(url)
                                    commandExecutor?.publishBrowserEvent("navigation_finished", pageId, JSONObject()
                                        .put("url", safe.value ?: JSONObject.NULL))
                                }
                            }
                        }
                    }
                }
                updatePageInfo(view, view.title.orEmpty(), view.url.orEmpty())
                if (!isActivePage(view)) return
                advancedController?.updatePageUrl(url)
                if (url != null && isSupportedWebUri(Uri.parse(url))) addressField?.setText(url)
                if (url != null && Uri.parse(url).scheme in setOf("http", "https")) {
                    recordHistoryPage(url, view.title.orEmpty())
                }
                verifyUserNavigationFinished(view, url)
                updateNavigationButtons()
                publishStatus(lifecycleController.snapshot())
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    val failed = pageStateTracker.mainFrameError(view, request.url.toString(), view.url, error.errorCode)
                    if (failed?.loadState == BrowserPageStateTracker.LoadState.FAILED && isActivePage(view)) {
                        pageManager?.all?.firstOrNull { it.content === view }?.id?.let { pageId ->
                            val safe = BrowserPageStateTracker.publicUrl(request.url.toString())
                            commandExecutor?.publishBrowserEvent("navigation_failed", pageId, JSONObject()
                                .put("url", safe.value ?: JSONObject.NULL))
                        }
                    }
                }
                if (request.isForMainFrame && isActivePage(view) &&
                    failUserNavigationIfCurrent(view, request.url.toString())
                ) {
                    toast(R.string.browser_page_error)
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse,
            ) {
                if (request.isForMainFrame) {
                    pageStateTracker.mainFrameHttpStatus(
                        view, request.url.toString(), view.url, errorResponse.statusCode,
                    )
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                return handlePageRendererGone(view)
            }
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    consumeAiTouchGesture = false
                    if (controlTransferPolicy.hasPending) {
                        consumeAiTouchGesture = true
                        true
                    } else if (lifecycleController.snapshot().owner == BrowserOwner.AI) {
                        consumeAiTouchGesture = true
                        requestControlTransfer(
                            BrowserControlTransferTarget.HUMAN,
                            R.string.browser_control_transfer_to_human_title,
                        ) {
                            if (lifecycleController.snapshot().owner == BrowserOwner.AI) takeOverForHuman()
                        }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (consumeAiTouchGesture) {
                        consumeAiTouchGesture = false
                        true
                    } else {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastTouchActivityAt >= TOUCH_ACTIVITY_THROTTLE_MILLIS) {
                            lastTouchActivityAt = now
                            recordEffectiveActivity()
                        }
                        false
                    }
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_CANCEL -> {
                    val consume = consumeAiTouchGesture || controlTransferPolicy.hasPending
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) consumeAiTouchGesture = false
                    consume
                }
                else -> consumeAiTouchGesture || controlTransferPolicy.hasPending
            }
        }
        pageStateTracker.ensure(view, view.url ?: "about:blank")
        if (attachGate) commandExecutor?.attachActionGate(view)
        return view
    }

    private fun handleJavaScriptDialog(
        view: WebView?, url: String?, dialogType: String, message: String?, defaultValue: String?,
        result: android.webkit.JsResult?, promptResult: android.webkit.JsPromptResult?,
    ): Boolean {
        val target = view ?: return false
        val jsResult = result ?: return false
        if (closed || !isActivePage(target)) {
            runCatching { jsResult.cancel() }
            return true
        }
        val pageId = pageManager?.all?.firstOrNull { it.content === target }?.id
        val aiOwned = lifecycleController.snapshot().owner == BrowserOwner.AI
        if (aiOwned) {
            if (pageId == null || commandExecutor?.hasBrowserEventExpectation(pageId, "javascript_dialog") != true) {
                runCatching { jsResult.cancel() }
                return true
            }
            val settled = java.util.concurrent.atomic.AtomicBoolean(false)
            lateinit var dismissPending: () -> Unit
            val resolver: (String, String?) -> Boolean = { decision, promptText ->
                if (!settled.compareAndSet(false, true)) false else {
                    pendingJavaScriptDialogDismissals[target]?.remove(dismissPending)
                    runCatching {
                        if (promptResult != null) {
                            if (decision == "prompt") promptResult.confirm(promptText.orEmpty()) else promptResult.cancel()
                        } else if (decision == "accept") {
                            jsResult.confirm()
                        } else {
                            jsResult.cancel()
                        }
                    }.isSuccess
                }
            }
            dismissPending = {
                if (settled.compareAndSet(false, true)) {
                    runCatching { jsResult.cancel() }
                    pendingJavaScriptDialogDismissals[target]?.remove(dismissPending)
                }
            }
            pendingJavaScriptDialogDismissals.getOrPut(target) { linkedSetOf() }.add(dismissPending)
            val payload = JSONObject().put("dialog", JSONObject()
                .put("dialogType", dialogType)
                .put("message", message.orEmpty().take(512))
                .apply { if (dialogType == "prompt") put("promptDefault", defaultValue.orEmpty().take(512)) })
            if (commandExecutor?.publishBrowserDialog(pageId, payload, resolver) == null) dismissPending()
            return true
        }
        val safeOrigin = runCatching {
            Uri.parse(url).let { uri -> "${uri.scheme}://${uri.host}".take(256) }
        }.getOrDefault("")
        val body = android.widget.LinearLayout(uiContext).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
        }
        val prompt = if (dialogType == "prompt") android.widget.EditText(uiContext).apply {
            setText(defaultValue.orEmpty())
            body.addView(this)
        } else null
        body.addView(android.widget.TextView(uiContext).apply { text = message.orEmpty().take(2048) })
        val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var dismissHumanDialog: () -> Unit
        fun settleHumanDialog(accept: Boolean) {
            if (!resolved.compareAndSet(false, true)) return
            pendingJavaScriptDialogDismissals[target]?.remove(dismissHumanDialog)
            if (accept) {
                if (prompt != null) promptResult?.confirm(prompt.text.toString()) else jsResult.confirm()
            } else jsResult.cancel()
        }
        val alert = AlertDialog.Builder(uiContext)
            .setTitle(safeOrigin)
            .setView(body)
            .setPositiveButton(android.R.string.ok) { _, _ -> settleHumanDialog(true) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> settleHumanDialog(false) }
            .setOnCancelListener { settleHumanDialog(false) }
            .create()
        dismissHumanDialog = {
            settleHumanDialog(false)
            if (alert.isShowing) alert.dismiss()
        }
        pendingJavaScriptDialogDismissals.getOrPut(target) { linkedSetOf() }.add(dismissHumanDialog)
        if (prompt != null) javascriptPromptDialogs.add(alert)
        alert.setOnDismissListener {
            settleHumanDialog(false)
            javascriptPromptDialogs.remove(alert)
            val token = alert.window?.decorView?.windowToken ?: prompt?.windowToken
            if (token != null) getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(token, 0)
            prompt?.clearFocus()
        }
        alert.show()
        return true
    }

    private fun isActivePage(view: WebView): Boolean = pageManager?.active?.content === view

    private fun isVisualActionSurfaceAvailable(): Boolean = hasWindowFocus() && !closed &&
        lifecycleController.snapshot().visible && pickerOverlay == null &&
        listOf(advancedDialog, historyDialog, bookmarksDialog, pagesDialog, settingsDialog).none { it?.isShowing == true } &&
        menuPopup?.isShowing != true && downloadsPanel?.isShowing() != true && findBar?.isShown != true

    private fun updatePageInfo(view: WebView, title: String, url: String) {
        if (isActivePage(view)) updateBookmarkButton(url)
        pageManager?.update(view, title.take(MAX_PAGE_TITLE_CHARS), url.take(MAX_PAGE_URL_CHARS))
    }

    private fun activatePage(page: BrowserPageManager.Page<WebView>): Boolean {
        val target = page.content
        val current = webView
        if (current === target) {
            // Idempotent: the UI already shows the target; make the host surface agree (this also
            // restores the host after a failed reparent). A same-surface setActiveSurface is a
            // guarded no-op returning true; non-adopting windows have no host to align.
            return adoptingGateway?.host?.setActiveSurface(target) ?: true
        }
        val container = pageContainer ?: return false
        // Honor the host reparent arbitration: if it cannot move (a capture still owns the view),
        // abort the switch before touching the container so UI and host surface never diverge.
        if (adoptingGateway?.host?.setActiveSurface(target) == false) return false
        cancelPendingFileChooser()
        cancelUserNavigation()
        cancelElementPicker()
        commandExecutor?.detachActionGate()
        current?.onPause()
        (current?.parent as? ViewGroup)?.removeView(current)
        webView = target
        container.removeAllViews()
        container.addView(target, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        if (lifecycleController.snapshot().visible) target.onResume() else target.onPause()
        commandExecutor?.onDocumentStarted()
        commandExecutor?.attachActionGate()
        // Real target switch: onPageChanged revokes the old CDP target and updates the URL once.
        advancedController?.onPageChanged(target.url)
        addressField?.setText(browserAddressText(target.url))
        progressBar?.visibility = View.GONE
        updateNavigationButtons()
        return true
    }

    private fun destroyPageWebView(view: WebView) {
        uploadActivationIsolation.release(view)
        pendingUploadActivation?.takeIf { it.view === view }?.let { activation ->
            mainHandler.removeCallbacks(activation.timeout)
            pendingUploadActivation = null
            completeUploadActivation(activation, uploadResult("UPLOAD_ACTIVATION_UNRESOLVED", activation.dispatched))
        }
        pendingJavaScriptDialogDismissals.remove(view)?.toList()?.forEach { dismiss -> runCatching(dismiss) }
        commandExecutor?.invalidateBrowserEvents()
        pageStateTracker.remove(view)
        BrowserVisualScript.remove(view)
        if (pendingFileChooserWebView === view) cancelPendingFileChooser()
        if (pendingUploadChooser?.view === view) {
            pendingUploadChooser?.grantId?.let(::revokeUploadGrant)
            cancelPendingUploadChooser()
        }
        view.stopLoading()
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        (view.parent as? ViewGroup)?.removeView(view)
        view.removeAllViews()
        view.destroy()
    }

    private fun handlePageRendererGone(view: WebView): Boolean {
        val manager = pageManager ?: run {
            destroyPageWebView(view)
            return true
        }
        val page = manager.all.firstOrNull { it.content === view } ?: run {
            destroyPageWebView(view)
            return true
        }
        val wasActive = manager.activePageId == page.id
        if (manager.size > 1) {
            val closedPage = manager.close(page.id)
            if (closedPage != null) {
                if (wasActive) activatePage(closedPage.active)
                destroyPageWebView(view)
                bumpPageContextPreservingOwner()
                return true
            }
        }
        releaseSession(finishActivity = true, reason = BrowserCloseReason.RENDERER_GONE)
        return true
    }

    private fun bumpPageContextPreservingOwner() {
        val nextIdentity = commandExecutor?.currentUploadPageIdentity()
        uploadGrantStore.revokeExcept(nextIdentity, SystemClock.elapsedRealtime()).forEach { revokeUploadGrant(it.id) }
        if (pendingUploadChooser?.identity != nextIdentity) cancelPendingUploadChooser()
        actionGate.invalidate()
        commandExecutor?.onDocumentStarted()
        advancedController?.revoke()
        val before = lifecycleController.snapshot()
        val after = if (before.owner == BrowserOwner.AI) lifecycleController.handToAi() else lifecycleController.takeOver()
        commandExecutor?.updateAuthorizationState(
            after,
            preferences.getBoolean(KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED),
        )
        publishStatus(after)
    }

    private fun pageResultData(): JSONObject {
        val manager = pageManager ?: return JSONObject()
        val pages = JSONArray()
        manager.all.forEach { page ->
            pages.put(JSONObject()
                .put("pageId", page.id)
                .put("title", page.title.take(MAX_PAGE_TITLE_CHARS))
                .put("url", safePageUrl(page.url))
                .put("active", page.id == manager.activePageId))
        }
        return JSONObject()
            .put("pages", pages)
            .put("activePageId", manager.activePageId ?: JSONObject.NULL)
            .put("limit", BrowserPageManager.MAX_PAGES)
    }

    private fun safePageUrl(raw: String): String {
        if (raw == "about:blank") return raw
        return runCatching {
            val uri = java.net.URI(raw)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return@runCatching "about:blank"
            val host = uri.host ?: return@runCatching "about:blank"
            if (scheme !in setOf("http", "https")) return@runCatching "about:blank"
            val authorityHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            val defaultPort = (scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443)
            val port = if (uri.port >= 0 && !defaultPort) ":${uri.port}" else ""
            "$scheme://$authorityHost$port/"
        }.getOrDefault("about:blank").take(MAX_PAGE_URL_CHARS)
    }

    private fun executePageCommand(action: String, args: JSONObject): BrowserPageCommandResult {
        val manager = pageManager ?: return BrowserPageCommandResult("CLOSED", false, JSONObject())
        if (action == "list") return BrowserPageCommandResult("OK", false, pageResultData())
        val targetId = args.optString("pageId")
        when (action) {
            "new" -> {
                if (manager.size >= BrowserPageManager.MAX_PAGES) {
                    return BrowserPageCommandResult("PAGE_LIMIT", false, pageResultData())
                }
                if (pageContainer == null) {
                    return BrowserPageCommandResult("PAGE_CREATE_FAILED", false, pageResultData())
                }
                val created = runCatching { createPageWebView() }.getOrElse {
                    return BrowserPageCommandResult("PAGE_CREATE_FAILED", false, pageResultData())
                }
                // Preflight the host reparent before committing to the manager.
                if (adoptingGateway?.host?.setActiveSurface(created) == false) {
                    destroyPageWebView(created)
                    return BrowserPageCommandResult("PAGE_CREATE_FAILED", false, pageResultData())
                }
                val previousActive = manager.active
                val page = manager.add(created) ?: run {
                    destroyPageWebView(created)
                    return BrowserPageCommandResult("PAGE_LIMIT", false, pageResultData())
                }
                fun restorePrevious() {
                    val previous = previousActive ?: return
                    manager.switchTo(previous.id)
                    // activatePage is host-aligning even when the UI webView already equals the
                    // target, so it restores the host surface after a failed reparent.
                    activatePage(previous)
                }
                if (!activatePage(page)) {
                    manager.close(page.id)
                    restorePrevious()
                    destroyPageWebView(created)
                    return BrowserPageCommandResult("PAGE_CREATE_FAILED", false, pageResultData())
                }
                runCatching { created.loadUrl("about:blank") }.onFailure {
                    manager.close(page.id)
                    restorePrevious()
                    destroyPageWebView(created)
                    return BrowserPageCommandResult("PAGE_CREATE_FAILED", false, pageResultData())
                }
                return BrowserPageCommandResult("OK", true, pageResultData(), contextChanged = true)
            }
            "switch" -> {
                val page = manager.page(targetId)
                    ?: return BrowserPageCommandResult("PAGE_NOT_FOUND", false, pageResultData())
                if (page.id == manager.activePageId) {
                    return BrowserPageCommandResult("OK", false, pageResultData())
                }
                if (pageContainer == null) {
                    return BrowserPageCommandResult("PAGE_SWITCH_FAILED", false, pageResultData())
                }
                // Prepare the host reparent before mutating the manager's active page.
                if (!BrowserPageTransactions.activate(manager, page.id) {
                        adoptingGateway?.host?.setActiveSurface(it) ?: true
                    }
                ) {
                    return BrowserPageCommandResult("PAGE_SWITCH_FAILED", false, pageResultData())
                }
                if (!activatePage(page)) {
                    return BrowserPageCommandResult("PAGE_SWITCH_FAILED", false, pageResultData())
                }
                return BrowserPageCommandResult("OK", true, pageResultData(), contextChanged = true)
            }
            "close" -> {
                if (manager.page(targetId) == null) {
                    return BrowserPageCommandResult("PAGE_NOT_FOUND", false, pageResultData())
                }
                if (manager.size <= 1) {
                    return BrowserPageCommandResult("LAST_PAGE", false, pageResultData())
                }
                if (manager.activePageId == targetId && pageContainer == null) {
                    return BrowserPageCommandResult("PAGE_CLOSE_FAILED", false, pageResultData())
                }
                val closed = BrowserPageTransactions.close(manager, targetId) {
                    adoptingGateway?.host?.setActiveSurface(it) ?: true
                } ?: return BrowserPageCommandResult("PAGE_CLOSE_FAILED", false, pageResultData())
                // Closing an inactive page is not a target switch: leave the active document
                // identity / CDP network capture untouched. Only an active close reparents the UI.
                val switched = !closed.wasActive || activatePage(closed.result.active)
                destroyPageWebView(closed.result.removed.content)
                updateNavigationButtons()
                return if (switched) {
                    BrowserPageCommandResult("OK", true, pageResultData(), contextChanged = true)
                } else {
                    BrowserPageCommandResult("PAGE_SWITCH_FAILED", false, pageResultData())
                }
            }
        }
        return BrowserPageCommandResult("INVALID_ARGUMENT", false, pageResultData())
    }

    private fun showPagesDialog() {
        pagesDialog?.dismiss()
        val manager = pageManager ?: return
        val closeButton = iconButton(CommonR.drawable.ic_x, R.string.browser_cancel) {
            pagesDialog?.dismiss()
        }.apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)) }
        val newButton = iconButton(CommonR.drawable.ic_plus, R.string.browser_pages_new) {
            createPageFromUi {
                pagesDialog?.dismiss()
                showPagesDialog()
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)) }
        val shell = BrowserManagementPanel.create(
            this,
            uiContext.getString(R.string.browser_pages_title),
            closeButton,
            newButton,
        )
        val scroll = ScrollView(uiContext).apply { isFillViewport = true }
        val rows = LinearLayout(uiContext).apply { orientation = LinearLayout.VERTICAL }
        manager.all.forEach { page ->
            val row = LinearLayout(uiContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            val details = LinearLayout(uiContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(6), dp(4), dp(6))
                isClickable = true
                isFocusable = true
                background = selectableBackground()
                setOnClickListener {
                    switchPageFromUi(page.id) {
                        pagesDialog?.dismiss()
                        showPagesDialog()
                    }
                }
            }
            details.addView(TextView(uiContext).apply {
                text = page.title.ifBlank { uiContext.getString(R.string.browser_pages_untitled) }
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(COLOR_INK)
            })
            details.addView(TextView(uiContext).apply {
                text = safePageUrl(page.url)
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(COLOR_MUTED)
            })
            row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(iconButton(CommonR.drawable.ic_x, R.string.browser_pages_close) {
                closePageFromUi(page.id) {
                    pagesDialog?.dismiss()
                    if ((pageManager?.size ?: 0) > 0) showPagesDialog()
                }
            }.apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)) })
            rows.addView(row)
            rows.addView(menuDivider())
        }
        scroll.addView(rows)
        shell.content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val dialog = Dialog(this).apply {
            setOnDismissListener { if (pagesDialog === this) pagesDialog = null }
        }
        pagesDialog = dialog
        BrowserManagementPanel.show(this, dialog, shell.root)
    }

    private fun createPageFromUi(onComplete: () -> Unit = {}) {
        val manager = pageManager ?: return
        if (manager.size >= BrowserPageManager.MAX_PAGES) {
            toast(R.string.browser_pages_limit)
            return
        }
        if (!canPreparePageUiMutation()) return
        requestHumanAction {
            if (manager.size >= BrowserPageManager.MAX_PAGES) {
                toast(R.string.browser_pages_limit)
                return@requestHumanAction
            }
            if (!preparePageUiMutation()) return@requestHumanAction
            val result = executePageCommand("new", JSONObject())
            if (result.executed == true) bumpPageContextPreservingOwner() else toast(R.string.browser_page_error)
            onComplete()
        }
    }

    private fun switchPageFromUi(pageId: String, onComplete: () -> Unit = {}) {
        val manager = pageManager ?: return
        if (manager.activePageId == pageId) return
        if (manager.page(pageId) == null) return
        if (!canPreparePageUiMutation()) return
        requestHumanAction {
            if (manager.page(pageId) == null || !preparePageUiMutation()) return@requestHumanAction
            val result = executePageCommand("switch", JSONObject().put("pageId", pageId))
            if (result.executed == true) bumpPageContextPreservingOwner() else toast(R.string.browser_page_error)
            onComplete()
        }
    }

    private fun closePageFromUi(pageId: String, onComplete: () -> Unit = {}) {
        val manager = pageManager ?: return
        if (manager.size <= 1) {
            toast(R.string.browser_pages_last)
            return
        }
        if (manager.page(pageId) == null) return
        if (!canPreparePageUiMutation()) return
        requestHumanAction {
            if (manager.page(pageId) == null || manager.size <= 1 || !preparePageUiMutation()) return@requestHumanAction
            val result = executePageCommand("close", JSONObject().put("pageId", pageId))
            if (result.executed == true) bumpPageContextPreservingOwner() else toast(R.string.browser_page_error)
            onComplete()
        }
    }

    private fun canPreparePageUiMutation(): Boolean {
        val snapshot = lifecycleController.snapshot()
        val navigation = navigationTracker.current()
        if (snapshot.lifecycle != BrowserLifecycle.ACTIVE ||
            (snapshot.inFlightOperationId != null && snapshot.inFlightOperationId != navigation?.token?.operationId)
        ) {
            toast(R.string.browser_pages_busy)
            return false
        }
        return true
    }

    private fun preparePageUiMutation(): Boolean {
        var snapshot = lifecycleController.snapshot()
        val navigation = navigationTracker.current()
        if (navigation != null && snapshot.inFlightOperationId == navigation.token.operationId) {
            cancelUserNavigation()
            snapshot = lifecycleController.snapshot()
        }
        if (snapshot.lifecycle != BrowserLifecycle.ACTIVE || snapshot.inFlightOperationId != null) {
            toast(R.string.browser_pages_busy)
            return false
        }
        actionGate.invalidate()
        return true
    }

    private fun showMoreMenu(anchor: View) {
        menuPopup?.dismiss()
        val panel = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))

        }
        panel.addView(menuToggleRow(
            if (lifecycleController.snapshot().owner == BrowserOwner.AI) R.string.browser_ai_control_on
            else R.string.browser_ai_control_off,
            lifecycleController.snapshot().owner == BrowserOwner.AI,
        ) { enabled -> setAiControlEnabled(enabled) })
        panel.addView(menuDivider())
        panel.addView(menuRow(R.string.browser_pages_new, CommonR.drawable.ic_plus) {
            createPageFromUi(onComplete = ::dismissMenu)
        })
        panel.addView(menuRow(R.string.browser_pages_manage, CommonR.drawable.ic_browser_window) {
            dismissMenu()
            showPagesDialog()
        })
        panel.addView(menuRow(R.string.browser_task_recovery, CommonR.drawable.ic_list) {
            dismissMenu()
            recoveryPanel = BrowserTaskRecoveryPanel(
                this, uiContext, { commandExecutor?.recoveryContextForUi() },
                { webView?.url?.let { runCatching { Uri.parse(it).host }.getOrNull() } },
                { taskId -> commandExecutor?.invalidateTaskBrowserEvents(taskId) },
                onSendRecoveryDraft = { draft ->
                    enqueueRecoveryDraft(draft).also { queued ->
                        if (queued) {
                            recoveryPanel?.dismiss()
                            recoveryPanel = null
                            openDshPage()
                        }
                    }
                },
            )
            recoveryPanel?.show()
        })
        panel.addView(menuDivider())
        panel.addView(menuRow(R.string.browser_menu_find, CommonR.drawable.ic_search) { dismissMenu(); showFindBar() })
        panel.addView(menuZoomRow())
        panel.addView(menuDivider())
        panel.addView(menuRow(R.string.browser_menu_capture, CommonR.drawable.ic_browser_camera) { dismissMenu(); capturePageForPreview() })
        panel.addView(menuRow(R.string.browser_menu_history, R.drawable.ic_browser_history) { dismissMenu(); showHistory() })
        panel.addView(menuRow(R.string.browser_bookmarks, CommonR.drawable.ic_browser_bookmark) {
            dismissMenu()
            showBookmarks()
        })
        panel.addView(menuRow(R.string.browser_menu_downloads, CommonR.drawable.ic_download) {
            dismissMenu()
            showDownloadsPanel()
        })
        panel.addView(menuRow(R.string.browser_menu_external, CommonR.drawable.ic_external_link) {
            openAddressExternally()
        })
        panel.addView(menuRow(R.string.browser_menu_clear, CommonR.drawable.ic_trash) { dismissMenu(); confirmClearData() })
        panel.addView(menuDivider())
        panel.addView(menuRow(R.string.browser_menu_settings, CommonR.drawable.ic_settings) { dismissMenu(); showSettings() })
        panel.addView(menuRow(R.string.browser_advanced, CommonR.drawable.ic_adjustments_horizontal) {
            dismissMenu()
            showAdvancedPanel()
        })
        panel.addView(menuRow(R.string.browser_menu_close, CommonR.drawable.ic_x) { dismissMenu(); confirmClose() })

        val scroll = ScrollView(uiContext).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val popup = PopupWindow(this).apply {
            contentView = FrameLayout(uiContext).apply {
                background = roundedBackground(COLOR_PAPER, COLOR_STRONG_LINE, dp(16))
                clipToOutline = true
                addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            val bounds = android.graphics.Rect()
            window.decorView.getWindowVisibleDisplayFrame(bounds)
            val anchorLocation = IntArray(2)
            anchor.getLocationOnScreen(anchorLocation)
            val availableBelow = (bounds.bottom - anchorLocation[1] - anchor.height - dp(8)).coerceAtLeast(1)
            width = minOf(dp(304), (bounds.width() - dp(16)).coerceAtLeast(1))
            height = minOf(dp(520), availableBelow)
            isFocusable = true
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(12).toFloat()
            setOnDismissListener { if (menuPopup === this) menuPopup = null }
        }
        menuPopup = popup
        popup.showAsDropDown(anchor, 0, dp(5), Gravity.END)
    }

    private fun dismissMenu() {
        menuPopup?.dismiss()
        menuPopup = null
    }

    private fun menuRow(labelRes: Int, iconRes: Int, action: () -> Unit): View =
        LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, dp(10), 0)
            minimumHeight = dp(43)
            isClickable = true
            isFocusable = true
            background = selectableBackground()
            setOnClickListener { action() }
            addView(TextView(uiContext).apply {
                text = uiContext.getText(labelRes)
                textSize = 13f
                setTextColor(COLOR_INK)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(iconView(iconRes, labelRes, dp(18)))
        }

    private fun menuToggleRow(labelRes: Int, checked: Boolean, onChanged: (Boolean) -> Unit): View =
        LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, dp(7), 0)
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            background = selectableBackground()
            val label = TextView(uiContext).apply {
                text = uiContext.getText(labelRes)
                textSize = 13f
                setTextColor(COLOR_INK)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val toggle = CheckBox(uiContext).apply {
                isChecked = checked
                buttonTintList = ColorStateList.valueOf(COLOR_MUTED)
                contentDescription = uiContext.getString(labelRes)
                setOnCheckedChangeListener { _, enabled -> onChanged(enabled) }
            }
            addView(label)
            addView(toggle, LinearLayout.LayoutParams(dp(28), dp(36)).apply { marginStart = dp(8) })
            setOnClickListener { toggle.isChecked = !toggle.isChecked }
        }

    private fun menuZoomRow(): View = LinearLayout(uiContext).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(11), dp(4), dp(5), dp(4))
        minimumHeight = dp(47)
        addView(TextView(uiContext).apply {
            text = uiContext.getText(R.string.browser_menu_zoom)
            textSize = 13f
            setTextColor(COLOR_INK)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val zoomControl = LinearLayout(uiContext).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            background = roundedBackground(COLOR_SOFT, COLOR_LINE, dp(10))
        }
        zoomControl.addView(iconButton(CommonR.drawable.ic_browser_minus, R.string.browser_zoom_out) { changeZoom(-10) }, LinearLayout.LayoutParams(dp(32), dp(32)))
        val value = TextView(uiContext).apply {
            text = "$zoomPercent%"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(COLOR_INK)
            minWidth = dp(48)
            tag = ZOOM_VALUE_TAG
        }
        zoomControl.addView(value, LinearLayout.LayoutParams(dp(49), dp(32)))
        zoomControl.addView(iconButton(CommonR.drawable.ic_plus, R.string.browser_zoom_in) { changeZoom(10) }, LinearLayout.LayoutParams(dp(32), dp(32)))
        addView(zoomControl)
        addView(iconButton(CommonR.drawable.ic_refresh, R.string.browser_zoom_reset) { resetZoom() }, LinearLayout.LayoutParams(dp(34), dp(34)))
    }

    private fun menuDivider(): View = View(uiContext).apply {
        setBackgroundColor(COLOR_LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
            marginStart = dp(6)
            marginEnd = dp(6)
        }
    }

    private fun changeZoom(delta: Int) {
        applyPageZoom((requestedZoomPercent + delta).coerceIn(70, 150))
    }

    private fun resetZoom() {
        applyPageZoom(100)
    }

    private fun applyPageZoom(target: Int) {
        val view = webView ?: return
        if (target !in 70..150 || closed || lifecycleController.snapshot().lifecycle != BrowserLifecycle.ACTIVE) return
        requestHumanAction {
            if (webView !== view || closed) return@requestHumanAction
            if (pickerOverlay != null) cancelElementPicker()
            requestedZoomPercent = target
            val request = ++zoomRequestGeneration
            val document = pageDocumentGeneration
            view.evaluateJavascript(pageZoomExpression(target)) { encoded ->
                if (closed || webView !== view || request != zoomRequestGeneration || document != pageDocumentGeneration) return@evaluateJavascript
                val result = runCatching { JSONObject(JSONArray("[$encoded]").getString(0)) }.getOrNull()
                if (result?.optBoolean("ok") == true && result.optInt("percent") in 70..150) {
                    zoomPercent = result.getInt("percent")
                    requestedZoomPercent = zoomPercent
                    updateZoomLabels()
                    recordEffectiveActivity()
                } else {
                    requestedZoomPercent = zoomPercent
                    toast(R.string.browser_zoom_unavailable)
                }
            }
        }
    }

    private fun updateZoomLabels() {
        menuPopup?.contentView?.let { root ->
            root.findViewWithTag<TextView>(ZOOM_VALUE_TAG)?.text = "$zoomPercent%"
        }
    }

    private fun showFindBar() {
        findBar?.visibility = View.VISIBLE
        findField?.requestFocus()
        findField?.let(::showBrowserInput)
    }

    private fun openAiControl() {
        if (!preferences.getBoolean(KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)) {
            toast(R.string.browser_ai_disabled_notice)
            showSettings()
            return
        }
        toggleAiOwner()
    }

    private fun toggleAiOwner() {
        if (!preferences.getBoolean(KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)) {
            toast(R.string.browser_ai_disabled_notice)
            return
        }
        val target = if (lifecycleController.snapshot().owner == BrowserOwner.AI) {
            BrowserControlTransferTarget.HUMAN
        } else BrowserControlTransferTarget.AI
        val title = if (target == BrowserControlTransferTarget.HUMAN) {
            R.string.browser_control_transfer_to_user_title
        } else R.string.browser_control_transfer_to_ai_title
        requestControlTransfer(target, title) {
            dismissMenu()
            cancelElementPicker()
            val current = lifecycleController.snapshot()
            if (current.lifecycle != BrowserLifecycle.ACTIVE) return@requestControlTransfer
            val snapshot = when {
                target == BrowserControlTransferTarget.HUMAN && current.owner == BrowserOwner.AI -> transferToHuman()
                target == BrowserControlTransferTarget.AI && current.owner != BrowserOwner.AI &&
                    preferences.getBoolean(KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED) -> lifecycleController.handToAi()
                else -> current
            }
            publishStatus(snapshot)
        }
    }

    private fun setAiControlEnabled(enabled: Boolean, refreshSettings: Boolean = false) {
        val target = if (enabled) BrowserControlTransferTarget.AI else BrowserControlTransferTarget.HUMAN
        val title = if (enabled) R.string.browser_control_transfer_to_ai_title
        else R.string.browser_control_transfer_to_user_title
        requestControlTransfer(target, title) {
            dismissMenu()
            applyAiControlEnabled(enabled, refreshSettings)
        }
    }

    private fun applyAiControlEnabled(enabled: Boolean, refreshSettings: Boolean) {
        if (!enabled) {
            setAutomationEnabled(false, refreshSettings = false)
            if (refreshSettings && lifecycleController.snapshot().lifecycle == BrowserLifecycle.ACTIVE) {
                mainHandler.post { if (!closed) showSettings() }
            }
            return
        }
        if (!preferences.getBoolean(KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)) {
            setAutomationEnabled(true, refreshSettings = false)
        }
        val current = lifecycleController.snapshot()
        if (current.lifecycle == BrowserLifecycle.ACTIVE && current.owner != BrowserOwner.AI) {
            publishStatus(lifecycleController.handToAi())
        }
        if (refreshSettings && lifecycleController.snapshot().lifecycle == BrowserLifecycle.ACTIVE) {
            mainHandler.post { if (!closed) showSettings() }
        }
    }

    private fun requestControlTransfer(
        target: BrowserControlTransferTarget,
        titleRes: Int,
        onRejected: () -> Unit = {},
        onConfirmed: () -> Unit,
    ) {
        val expected = lifecycleController.snapshot()
        if (expected.lifecycle != BrowserLifecycle.ACTIVE) { onRejected(); return }
        val expectedIdentity = currentControlRequestIdentity
        val pending = controlTransferPolicy.begin(target, expected.sessionGeneration, expected.controlGeneration)
            ?: run { onRejected(); return }
        var decisionDelivered = false
        fun reject() {
            if (!decisionDelivered) {
                decisionDelivered = true
                onRejected()
            }
        }
        fun resolve(confirmed: Boolean): BrowserControlTransferTarget? {
            val current = lifecycleController.snapshot()
            val stillSameControl = current.lifecycle == BrowserLifecycle.ACTIVE &&
                current.sessionGeneration == expected.sessionGeneration &&
                current.controlGeneration == expected.controlGeneration && current.owner == expected.owner &&
                currentControlRequestIdentity == expectedIdentity
            return controlTransferPolicy.resolve(
                pending.id,
                confirmed && stillSameControl,
                current.sessionGeneration,
                current.controlGeneration,
            )
        }
        val dialog = try {
            AlertDialog.Builder(uiContext)
                .setTitle(titleRes)
                .setMessage(R.string.browser_control_transfer_message)
                .setNegativeButton(R.string.browser_cancel) { _, _ ->
                    resolve(confirmed = false)
                    reject()
                }
                .setPositiveButton(R.string.browser_control_transfer_confirm) { _, _ ->
                    if (resolve(confirmed = true) == target) {
                        decisionDelivered = true
                        onConfirmed()
                    } else reject()
                }
                .setOnCancelListener {
                    resolve(confirmed = false)
                    reject()
                }
                .setOnDismissListener {
                    controlTransferPolicy.cancel(pending.id)
                    if (pendingControlTransferDialog?.isShowing != true) pendingControlTransferDialog = null
                    reject()
                }
                .create()
        } catch (_: RuntimeException) {
            controlTransferPolicy.cancel(pending.id)
            reject()
            return
        }
        pendingControlTransferDialog = dialog
        try {
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.filterTouchesWhenObscured = true
        } catch (_: RuntimeException) {
            controlTransferPolicy.cancel(pending.id)
            pendingControlTransferDialog = null
            dialog.dismiss()
            reject()
        }
    }

    private fun requestHumanAction(onRejected: () -> Unit = {}, commit: () -> Unit) {
        val snapshot = lifecycleController.snapshot()
        if (closed || snapshot.lifecycle != BrowserLifecycle.ACTIVE) { onRejected(); return }
        if (snapshot.owner != BrowserOwner.AI) {
            commit()
            return
        }
        requestControlTransfer(BrowserControlTransferTarget.HUMAN, R.string.browser_control_transfer_to_human_title, onRejected) {
            if (lifecycleController.snapshot().lifecycle != BrowserLifecycle.ACTIVE) {
                onRejected()
                return@requestControlTransfer
            }
            takeOverForHuman()
            commit()
        }
    }

    private fun dismissPendingControlTransfer() {
        val dialog = pendingControlTransferDialog ?: return
        pendingControlTransferDialog = null
        dialog.setOnDismissListener(null)
        controlTransferPolicy.cancelPending()
        dialog.dismiss()
    }

    /** Presents the native AI-control approval card while this Activity is already foregrounded. */
    fun showAiControlRequest(summary: String, onDecision: (Boolean) -> Unit): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper() || reservedSessionGeneration == null || closed || isFinishing || isDestroyed ||
            lifecycleController.snapshot().lifecycle != BrowserLifecycle.ACTIVE ||
            !lifecycleController.snapshot().visible || !hasWindowFocus()
        ) return false
        val requestSnapshot = lifecycleController.snapshot()
        val pending = controlTransferPolicy.begin(
            BrowserControlTransferTarget.AI,
            requestSnapshot.sessionGeneration,
            requestSnapshot.controlGeneration,
        ) ?: return false
        pendingExternalControlDecisionId = pending.id
        pendingExternalControlDecision = onDecision
        pendingExternalControlIdentity = currentControlRequestIdentity
        val safeSummary = summary.trim().take(MAX_CONTROL_REQUEST_SUMMARY_LENGTH)
            .ifEmpty { uiContext.getString(R.string.browser_control_transfer_message) }
        val dialog = try {
            AlertDialog.Builder(uiContext)
                .setTitle(R.string.browser_control_transfer_to_ai_title)
                .setMessage(safeSummary)
                .setNegativeButton(R.string.browser_cancel) { _, _ ->
                    resolveExternalControlDecision(pending.id, approved = false)
                }
                .setPositiveButton(R.string.browser_control_transfer_confirm) { _, _ ->
                    resolveExternalControlDecision(pending.id, approved = true)
                }
                .setOnCancelListener { resolveExternalControlDecision(pending.id, approved = false) }
                .setOnDismissListener {
                    resolveExternalControlDecision(pending.id, approved = false)
                    if (pendingControlTransferDialog?.isShowing != true) pendingControlTransferDialog = null
                }
                .create()
        } catch (_: RuntimeException) {
            pendingExternalControlDecisionId = null
            pendingExternalControlDecision = null
            pendingExternalControlIdentity = null
            controlTransferPolicy.cancel(pending.id)
            return false
        }
        pendingControlTransferDialog = dialog
        return try {
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.filterTouchesWhenObscured = true
            dialog.isShowing
        } catch (_: RuntimeException) {
            pendingExternalControlDecisionId = null
            pendingExternalControlDecision = null
            pendingExternalControlIdentity = null
            controlTransferPolicy.cancel(pending.id)
            pendingControlTransferDialog = null
            dialog.dismiss()
            false
        }
    }

    private fun resolveExternalControlDecision(id: Long, approved: Boolean) {
        if (pendingExternalControlDecisionId != id) return
        val decision = pendingExternalControlDecision
        val expectedIdentity = pendingExternalControlIdentity
        pendingExternalControlDecisionId = null
        pendingExternalControlDecision = null
        pendingExternalControlIdentity = null
        val current = lifecycleController.snapshot()
        val stillSamePage = current.lifecycle == BrowserLifecycle.ACTIVE &&
            currentControlRequestIdentity == expectedIdentity
        val resolvedTarget = controlTransferPolicy.resolve(
            id,
            confirmed = approved && stillSamePage,
            sessionGeneration = current.sessionGeneration,
            controlGeneration = current.controlGeneration,
        )
        if (approved && resolvedTarget != BrowserControlTransferTarget.AI) {
            decision?.invoke(false)
        } else {
            decision?.invoke(approved)
        }
    }

    internal fun dismissExternalAiControlRequest() {
        val id = pendingExternalControlDecisionId ?: return
        resolveExternalControlDecision(id, approved = false)
        pendingControlTransferDialog?.dismiss()
    }

    /** Called by the native approval coordinator only after its pending request is revalidated. */
    fun grantApprovedAiControl(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper() || reservedSessionGeneration == null || closed ||
            isFinishing || isDestroyed || lifecycleController.snapshot().lifecycle != BrowserLifecycle.ACTIVE
        ) return false
        applyAiControlEnabled(enabled = true, refreshSettings = false)
        val current = lifecycleController.snapshot()
        return current.lifecycle == BrowserLifecycle.ACTIVE && current.owner == BrowserOwner.AI &&
            preferences.getBoolean(KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)
    }

    private fun setAutomationEnabled(enabled: Boolean, refreshSettings: Boolean) {
        val previous = preferences.getBoolean(KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)
        if (previous == enabled) {
            val current = lifecycleController.snapshot()
            if (!enabled && current.lifecycle == BrowserLifecycle.ACTIVE && current.owner == BrowserOwner.AI) {
                actionGate.update(current, automationEnabled = false)
                commandExecutor?.updateAuthorizationState(current, enabled = false)
                advancedController?.revoke()
                publishStatus(transferToHuman())
            }
            return
        }
        if (!enabled) {
            val current = lifecycleController.snapshot()
            actionGate.update(current, automationEnabled = false)
            commandExecutor?.updateAuthorizationState(current, enabled = false)
            advancedController?.revoke()
        }
        preferences.edit().putBoolean(KEY_AI_ENABLED, enabled).apply()
        if (!enabled && lifecycleController.snapshot().owner == BrowserOwner.AI) {
            transferToHuman()
        }
        publishStatus(lifecycleController.snapshot())
        if (refreshSettings && lifecycleController.snapshot().lifecycle == BrowserLifecycle.ACTIVE) {
            showSettings()
        }
    }

    private fun capturePageForPreview() {
        captureForegroundPreviewWhenReady(SystemClock.elapsedRealtime() + CAPTURE_PREVIEW_DEADLINE_MILLIS)
    }

    private fun captureForegroundPreviewWhenReady(deadline: Long) {
        if (closed || isFinishing || isDestroyed) return
        if (SystemClock.elapsedRealtime() >= deadline) {
            showCaptureFailure("WINDOW_NOT_ACTIVE")
            return
        }
        if (!hasWindowFocus() || webView?.isLayoutRequested == true) {
            window.decorView.postOnAnimation { captureForegroundPreviewWhenReady(deadline) }
            return
        }
        captureFreshCurrentPage(deadline) { bitmap, code ->
            if (closed || isFinishing || isDestroyed) {
                bitmap?.takeUnless { it.isRecycled }?.recycle()
                return@captureFreshCurrentPage
            }

            if (bitmap == null) {
                Log.w(TAG, "page_preview_capture_failed:$code")
                showCaptureFailure(code)
                return@captureFreshCurrentPage
            }
            val dialog = Dialog(this)
            val content = LinearLayout(uiContext).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(COLOR_PAPER)
            }
            val header = LinearLayout(uiContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(10), 0)
                minimumHeight = dp(54)
            }
            header.addView(TextView(uiContext).apply {
                text = uiContext.getText(R.string.browser_capture_title)
                textSize = 15f
                setTextColor(COLOR_INK)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            header.addView(iconButton(CommonR.drawable.ic_x, R.string.browser_capture_close) {
                dialog.dismiss()
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            val image = ImageView(uiContext).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = uiContext.getString(R.string.browser_capture_title)
                adjustViewBounds = true
                setPadding(dp(12), dp(8), dp(12), dp(12))
            }
            content.addView(header)
            content.addView(horizontalDivider())
            content.addView(ScrollView(uiContext).apply { addView(image) })
            dialog.setContentView(content)
            dialog.setOnDismissListener {
                image.setImageDrawable(null)
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            val width = minOf(dp(620), resources.displayMetrics.widthPixels - dp(24))
            val height = minOf(dp(760), resources.displayMetrics.heightPixels - dp(48))
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(COLOR_PAPER))
                setLayout(width, height)
                addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                attributes = attributes.apply { windowAnimations = 0 }
            }
            dialog.show()
        }
    }

    private fun captureFailureMessage(code: String?): Int = when (BrowserCaptureFeedbackPolicy.captureFailure(code)) {
        BrowserCaptureFailure.PAGE_NOT_READY -> R.string.browser_capture_page_not_ready
        BrowserCaptureFailure.TARGET_CHANGED -> R.string.browser_capture_target_changed
        BrowserCaptureFailure.SAVE_FAILED -> R.string.browser_capture_save_failed
        BrowserCaptureFailure.IMAGE_FAILED -> R.string.browser_capture_image_failed
        BrowserCaptureFailure.FRAME_MISSING -> R.string.browser_capture_no_frame
        BrowserCaptureFailure.UNKNOWN -> R.string.browser_capture_failed
    }

    private fun showCaptureFailure(code: String?) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(uiContext)
            .setTitle(uiContext.getText(R.string.browser_capture_title))
            .setMessage(uiContext.getString(
                R.string.browser_capture_failure_details,
                uiContext.getString(captureFailureMessage(code)),
                code ?: "UNKNOWN",
            ))
            .setPositiveButton(R.string.browser_action_ok, null)
            .show()
    }

    private fun showZoomDialog() {
        val options = arrayOf(
            uiContext.getString(R.string.browser_zoom_out),
            uiContext.getString(R.string.browser_zoom_reset),
            uiContext.getString(R.string.browser_zoom_in),
        )
        AlertDialog.Builder(uiContext)
            .setTitle(uiContext.getText(R.string.browser_menu_zoom))
            .setItems(options) { _, selected ->
                when (selected) {
                    0 -> changeZoom(-10)
                    1 -> resetZoom()
                    2 -> changeZoom(10)
                }
            }
            .show()
    }

    private fun showHistory() {
        historyDialog?.dismiss()
        val closeButton = iconButton(CommonR.drawable.ic_x, R.string.browser_cancel) {
            historyDialog?.dismiss()
        }.apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)) }
        val clearButton = iconButton(CommonR.drawable.ic_trash, R.string.browser_history_clear_all) {
            AlertDialog.Builder(uiContext)
                .setTitle(uiContext.getText(R.string.browser_history_clear_all))
                .setMessage(uiContext.getText(R.string.browser_history_clear_message))
                .setNegativeButton(uiContext.getText(R.string.browser_cancel), null)
                .setPositiveButton(uiContext.getText(R.string.browser_history_clear_all)) { _, _ ->
                    requestHumanAction {
                        writeHistoryEntries(emptyList())
                        historyDialog?.dismiss()
                        showHistory()
                    }
                }.show()
        }.apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)) }
        val shell = BrowserManagementPanel.create(this, uiContext.getText(R.string.browser_menu_history), closeButton, clearButton)
        val rows = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(18))
        }
        shell.content.addView(ScrollView(uiContext).apply { addView(rows) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        val dialog = Dialog(this)
        historyDialog = dialog
        dialog.setOnDismissListener { if (historyDialog === dialog) historyDialog = null }
        BrowserManagementPanel.show(this, dialog, shell.root)
        renderHistoryRows(rows)
    }

    private fun showBookmarks() {
        bookmarksDialog?.dismiss()
        val closeButton = iconButton(CommonR.drawable.ic_x, R.string.browser_cancel) {
            bookmarksDialog?.dismiss()
        }.apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)) }
        val addButton = iconButton(CommonR.drawable.ic_plus, R.string.browser_bookmark_add) {
            editBookmark(null)
        }.apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)) }
        val shell = BrowserManagementPanel.create(
            this,
            uiContext.getString(R.string.browser_bookmarks),
            closeButton,
            addButton,
        )
        val rows = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(18))
        }
        val bookmarks = bookmarksStore.all()
        if (bookmarks.isEmpty()) {
            rows.addView(TextView(uiContext).apply {
                text = uiContext.getString(R.string.browser_bookmark_empty)
                textSize = 14f
                setTextColor(COLOR_MUTED)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(28), dp(12), dp(28))
            })
        } else bookmarks.forEach { bookmark ->
            val row = LinearLayout(uiContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            val details = LinearLayout(uiContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(7), dp(6), dp(7))
                isClickable = true
                isFocusable = true
                background = selectableBackground()
                setOnClickListener { openBookmark(bookmark) }
            }
            details.addView(TextView(uiContext).apply {
                text = bookmark.title
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(COLOR_INK)
            })
            details.addView(TextView(uiContext).apply {
                text = bookmark.url
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(iconButton(CommonR.drawable.ic_pencil, R.string.browser_bookmark_edit) {
                editBookmark(bookmark)
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(iconButton(CommonR.drawable.ic_trash, R.string.browser_history_delete) {
                if (bookmarksStore.delete(bookmark.id)) {
                    updateBookmarkButton()
                    bookmarksDialog?.dismiss()
                    showBookmarks()
                } else toast(R.string.browser_bookmark_delete_failed)
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            rows.addView(row)
            rows.addView(menuDivider())
        }
        val scroll = ScrollView(uiContext).apply {
            isFillViewport = true
            addView(rows)
        }
        shell.content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val dialog = Dialog(this).apply {
            setOnDismissListener { if (bookmarksDialog === this) bookmarksDialog = null }
        }
        bookmarksDialog = dialog
        BrowserManagementPanel.show(this, dialog, shell.root)
    }

    private fun editBookmark(bookmark: BrowserBookmark?) {
        val form = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val title = EditText(uiContext).apply {
            hint = uiContext.getString(R.string.browser_bookmark_title_hint)
            setSingleLine(true)
            setText(bookmark?.title ?: webView?.title.orEmpty())
        }
        val address = EditText(uiContext).apply {
            hint = uiContext.getString(R.string.browser_bookmark_url_hint)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(bookmark?.url ?: webView?.url.orEmpty())
        }
        form.addView(title)
        form.addView(address)
        AlertDialog.Builder(uiContext)
            .setTitle(uiContext.getString(if (bookmark == null) R.string.browser_bookmark_add else R.string.browser_bookmark_edit))
            .setView(form)
            .setNegativeButton(R.string.browser_cancel, null)
            .setPositiveButton(R.string.browser_save) { _, _ ->
                val rawUrl = address.text.toString().trim()
                if (rawUrl.equals("about:blank", ignoreCase = true) || rawUrl.isBlank()) {
                    toast(R.string.browser_bookmark_invalid_url)
                    return@setPositiveButton
                }
                val saved = bookmarksStore.save(rawUrl, title.text.toString(), bookmark?.id)
                if (saved == null) {
                    toast(when {
                        !bookmarksStore.supportsUrl(rawUrl) -> R.string.browser_bookmark_invalid_url
                        bookmark == null && bookmarksStore.all().size >= 500 && bookmarksStore.findByUrl(rawUrl) == null -> R.string.browser_bookmark_limit
                        else -> R.string.browser_bookmark_save_failed
                    })
                } else {
                    updateBookmarkButton()
                    toast(R.string.browser_bookmark_saved)
                    bookmarksDialog?.dismiss()
                    showBookmarks()
                }
            }
            .show()
    }

    private fun openBookmark(bookmark: BrowserBookmark) {
        val target = toWebUri(bookmark.url)
        val view = webView
        if (target == null || view == null || !isSupportedWebUri(target)) {
            toast(R.string.browser_invalid_address)
            return
        }
        navigateAction(target.toString(), beforeNavigation = { bookmarksDialog?.dismiss() }) { it.loadUrl(target.toString()) }
    }

    private fun toggleCurrentPageBookmark() {
        val view = webView ?: return
        val url = view.url.orEmpty()
        if (!bookmarksStore.supportsUrl(url)) {
            toast(R.string.browser_bookmark_invalid_url)
            return
        }
        val existing = bookmarksStore.findByUrl(url)
        if (existing != null) {
            if (!bookmarksStore.delete(existing.id)) {
                toast(R.string.browser_bookmark_delete_failed)
                return
            }
            updateBookmarkButton(url)
            toast(R.string.browser_bookmark_removed)
            return
        }
        val saved = bookmarksStore.save(url, view.title.orEmpty())
        if (saved == null) {
            toast(if (bookmarksStore.all().size >= 500) R.string.browser_bookmark_limit else R.string.browser_bookmark_save_failed)
            return
        }
        updateBookmarkButton(url)
        toast(R.string.browser_bookmark_saved)
    }

    private fun updateBookmarkButton(url: String = webView?.url.orEmpty()) {
        val selected = url.isNotBlank() && bookmarksStore.findByUrl(url) != null
        bookmarkButton?.apply {
            isSelected = selected
            contentDescription = uiContext.getString(if (selected) R.string.browser_bookmark_remove else R.string.browser_bookmark_add)
            setColorFilter(if (selected) COLOR_BLUE else COLOR_INK)
        }
    }

    private fun historyEntries(): MutableList<BrowserHistoryEntry> = runCatching {
        val stored = preferences.getString(KEY_HISTORY_ENTRIES, "[]") ?: "[]"
        BrowserHistoryStore.decode(stored)
    }.getOrDefault(mutableListOf())

    private fun writeHistoryEntries(entries: List<BrowserHistoryEntry>) {
        // Confirm the bounded history write before reporting completion or closing the page.
        if (!preferences.edit().putString(KEY_HISTORY_ENTRIES, BrowserHistoryStore.encode(entries)).commit()) {
            toast(R.string.browser_history_save_failed)
        }
    }

    private fun recordHistoryPage(url: String, title: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (!isSupportedWebUri(uri) || uri.host.isNullOrBlank()) return
        val entries = historyEntries()
        val old = entries.firstOrNull { it.url == url }
        entries.removeAll { it.url == url }
        entries.add(0, BrowserHistoryEntry(
            id = old?.id ?: UUID.randomUUID().toString(),
            url = url.take(MAX_HISTORY_URL_CHARS),
            title = (old?.title?.takeIf { it != old.url } ?: title).ifBlank { uri.host.orEmpty() }.take(MAX_HISTORY_TITLE_CHARS),
        ))
        writeHistoryEntries(entries)
    }

    private fun renderHistoryRows(rows: LinearLayout) {
        rows.removeAllViews()
        val entries = historyEntries()
        if (entries.isEmpty()) {
            rows.addView(settingsNote(R.string.browser_history_empty))
            return
        }
        entries.forEach { entry ->
            val row = LinearLayout(uiContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(9))
                background = roundedBackground(COLOR_PAPER, COLOR_LINE, dp(10))
            }
            row.addView(TextView(uiContext).apply {
                text = entry.title
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(COLOR_INK)
                setOnClickListener {
                    val target = toWebUri(entry.url)
                    if (target == null || !isSupportedWebUri(target)) {
                        toast(R.string.browser_invalid_address)
                    } else {
                        if (webView == null) return@setOnClickListener
                        navigateAction(target.toString(), beforeNavigation = { historyDialog?.dismiss() }) { it.loadUrl(target.toString()) }
                    }
                }
            })
            row.addView(TextView(uiContext).apply {
                text = entry.url
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(4), 0, dp(6))
            })
            val actions = LinearLayout(uiContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            actions.addView(historyTextAction(R.string.browser_history_edit) { editHistoryEntry(entry) })
            actions.addView(historyTextAction(R.string.browser_history_delete) {
                writeHistoryEntries(historyEntries().filterNot { it.id == entry.id })
                renderHistoryRows(rows)
            })
            row.addView(actions)
            rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
    }

    private fun historyTextAction(labelRes: Int, action: () -> Unit) = TextView(uiContext).apply {
        text = uiContext.getText(labelRes)
        textSize = 12f
        setTextColor(COLOR_INK)
        setPadding(dp(14), dp(8), dp(8), dp(8))
        setOnClickListener { action() }
    }

    private fun editHistoryEntry(entry: BrowserHistoryEntry) {
        val form = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val title = EditText(uiContext).apply {
            hint = uiContext.getString(R.string.browser_history_title_hint)
            setText(entry.title)
            setSingleLine(true)
        }
        val address = EditText(uiContext).apply {
            hint = uiContext.getString(R.string.browser_history_url_hint)
            setText(entry.url)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        form.addView(title)
        form.addView(address)
        AlertDialog.Builder(uiContext)
            .setTitle(uiContext.getText(R.string.browser_history_edit))
            .setView(form)
            .setNegativeButton(uiContext.getText(R.string.browser_cancel), null)
            .setPositiveButton(uiContext.getText(R.string.browser_history_save), null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val uri = runCatching { Uri.parse(address.text.toString().trim()) }.getOrNull()
                        if (uri == null || !isSupportedWebUri(uri) || uri.host.isNullOrBlank()) {
                            toast(R.string.browser_invalid_address)
                            return@setOnClickListener
                        }
                        val updated = historyEntries().map {
                            if (it.id == entry.id) it.copy(
                                url = uri.toString().take(MAX_HISTORY_URL_CHARS),
                                title = title.text.toString().trim().ifBlank { uri.host.orEmpty() }.take(MAX_HISTORY_TITLE_CHARS),
                            ) else it
                        }
                        writeHistoryEntries(updated)
                        dialog.dismiss()
                        historyDialog?.dismiss()
                        showHistory()
                    }
                }
                dialog.show()
            }
    }

    private fun applyRenderingSettings(view: WebView, settings: BrowserRenderingSettings) {
        pageFactory.applyRendering(view, settings)
    }

    private fun changeRenderingSetting(key: String, value: Any, expectedRevision: Long): Boolean {
        val current = BrowserRenderingPreferences.read(preferences)
        val change = runCatching { current.update(key, value, expectedRevision) }.getOrNull() ?: return false
        if (!change.changed) return true
        val saved = runCatching { BrowserRenderingPreferences.write(preferences, change.settings) }.getOrDefault(false)
        val effective = BrowserRenderingPreferences.read(preferences)
        applyRenderingPolicy(effective)
        if (!saved) toast(R.string.browser_rendering_save_uncertain)
        return effective == change.settings
    }

    private fun applyRenderingPolicy(settings: BrowserRenderingSettings) {
        pageManager?.all?.forEach { applyRenderingSettings(it.content, settings) }
        if (pageManager == null) webView?.let { applyRenderingSettings(it, settings) }
        cancelElementPicker()
        commandExecutor?.onRenderingSettingsChanged()
    }

    private fun showSettings() {
        settingsDialog?.dismiss()
        val closeButton = iconButton(CommonR.drawable.ic_x, R.string.browser_cancel) {
            settingsDialog?.dismiss()
        }.apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)) }
        val shell = BrowserManagementPanel.create(this, uiContext.getText(R.string.browser_settings_title), closeButton)

        val body = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
        }
        body.addView(settingsHeading(R.string.browser_idle_timeout))
        val durations = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }
        val selectedMinutes = preferences.getInt(KEY_IDLE_MINUTES, DEFAULT_IDLE_MINUTES)
        IDLE_MINUTES.forEach { minutes ->
            val option = TextView(uiContext).apply {
                text = uiContext.getString(R.string.browser_idle_duration, minutes)
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (minutes == selectedMinutes) COLOR_PAPER else COLOR_INK)
                setPadding(dp(5), dp(10), dp(5), dp(10))
                minWidth = dp(45)
                background = roundedBackground(
                    if (minutes == selectedMinutes) COLOR_INK else COLOR_SOFT,
                    if (minutes == selectedMinutes) COLOR_INK else COLOR_LINE,
                    dp(9),
                )
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(5)
                }
                setOnClickListener {
                    setIdleTimeout(minutes)
                    if (lifecycleController.snapshot().lifecycle == BrowserLifecycle.ACTIVE) showSettings()
                }
            }
            durations.addView(option)
        }
        body.addView(durations)
        body.addView(settingsNote(R.string.browser_background_note))
        body.addView(settingsSeparator())

        val aiSwitch = CheckBox(uiContext).apply {
            text = uiContext.getText(R.string.browser_ai_setting)
            isChecked = preferences.getBoolean(KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)
            setTextColor(COLOR_INK)
            setOnCheckedChangeListener { button, enabled ->
                if (suppressAiSwitchListener) return@setOnCheckedChangeListener
                val committed = preferences.getBoolean(KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)
                if (enabled == committed) return@setOnCheckedChangeListener
                suppressAiSwitchListener = true
                button.isChecked = committed
                suppressAiSwitchListener = false
                setAiControlEnabled(enabled, refreshSettings = true)
            }
        }
        body.addView(aiSwitch)
        body.addView(settingsNote(R.string.browser_ai_status_note))
        body.addView(settingsSeparator())

        val advancedSwitch = CheckBox(uiContext).apply {
            text = uiContext.getText(R.string.browser_advanced_setting)
            isChecked = preferences.getBoolean(BrowserRuntimeSettings.KEY_ADVANCED_ENABLED, false)
            setTextColor(COLOR_INK)
            setOnCheckedChangeListener { _, enabled ->
                setAdvancedEnabled(enabled)
                showSettings()
            }
        }
        body.addView(advancedSwitch)
        body.addView(settingsNote(R.string.browser_advanced_note))
        val networkMutationSwitch = CheckBox(uiContext).apply {
            text = uiContext.getText(R.string.browser_network_mutation_setting)
            isChecked = preferences.getBoolean(BrowserRuntimeSettings.KEY_NETWORK_MUTATION_ENABLED, false)
            isEnabled = preferences.getBoolean(BrowserRuntimeSettings.KEY_ADVANCED_ENABLED, false)
            setTextColor(if (isEnabled) COLOR_INK else COLOR_MUTED)
            setOnCheckedChangeListener { _, enabled ->
                setNetworkMutationEnabled(enabled)
                showSettings()
            }
        }
        body.addView(networkMutationSwitch)
        body.addView(settingsNote(R.string.browser_network_mutation_note))
        val ownerStatusRes = if (lifecycleController.snapshot().owner == BrowserOwner.AI) {
            R.string.browser_ai_state_ai
        } else R.string.browser_ai_state_human
        body.addView(settingsNote(ownerStatusRes))
        body.addView(settingsSeparator())

        body.addView(settingsHeading(R.string.browser_rendering_title))
        val rendering = BrowserRenderingPreferences.read(preferences)
        BrowserRenderingSettingsPanel.addTo(body, uiContext, COLOR_INK, rendering) { key, value ->
            requestHumanAction(onRejected = {
                mainHandler.post {
                    if (!closed && settingsDialog?.isShowing == true &&
                        lifecycleController.snapshot().lifecycle == BrowserLifecycle.ACTIVE) showSettings()
                }
            }) {
                if (!changeRenderingSetting(key, value, rendering.revision)) toast(R.string.browser_rendering_stale)
                showSettings()
            }
        }
        body.addView(settingsNote(R.string.browser_rendering_note))
        body.addView(menuRow(R.string.browser_refresh, CommonR.drawable.ic_refresh) {
            settingsDialog?.dismiss()
            webView?.let { view -> navigateAction(view.url) { it.reload() } }
        })
        body.addView(settingsSeparator())

        body.addView(settingsHeading(R.string.browser_search_engine))
        val searchGroup = RadioGroup(uiContext).apply { orientation = RadioGroup.VERTICAL }
        val currentSearchEngine = preferences.getString(KEY_SEARCH_ENGINE, DEFAULT_SEARCH_ENGINE)
            ?: DEFAULT_SEARCH_ENGINE
        listOf(
            SEARCH_BING to R.string.browser_search_bing,
            SEARCH_GOOGLE to R.string.browser_search_google,
            SEARCH_BAIDU to R.string.browser_search_baidu,
        ).forEach { (engine, labelRes) ->
            searchGroup.addView(RadioButton(uiContext).apply {
                id = View.generateViewId()
                text = uiContext.getText(labelRes)
                tag = engine
                isChecked = currentSearchEngine == engine
                setTextColor(COLOR_INK)
                setOnClickListener {
                    preferences.edit().putString(KEY_SEARCH_ENGINE, engine).apply()
                }
            })
        }
        body.addView(searchGroup)
        body.addView(settingsSeparator())

        val clearRow = menuRow(R.string.browser_menu_clear, CommonR.drawable.ic_trash) {
            settingsDialog?.dismiss()
            confirmClearData()
        }
        body.addView(clearRow)
        body.addView(settingsNote(R.string.browser_clear_message))

        shell.content.addView(ScrollView(uiContext).apply { addView(body) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        val dialog = Dialog(this)
        settingsDialog = dialog
        dialog.setOnDismissListener { if (settingsDialog === dialog) settingsDialog = null }
        BrowserManagementPanel.show(this, dialog, shell.root)
    }

    private fun syncAdvancedPermission() {
        WebView.setWebContentsDebuggingEnabled(
            preferences.getBoolean(BrowserRuntimeSettings.KEY_ADVANCED_ENABLED, false),
        )
    }

    private fun refreshAdvancedPermission() {
        val debugEnabled = preferences.getBoolean(BrowserRuntimeSettings.KEY_ADVANCED_ENABLED, false)
        val networkMutationEnabled = preferences.getBoolean(BrowserRuntimeSettings.KEY_NETWORK_MUTATION_ENABLED, false)
        advancedController?.updatePermissions(debugEnabled, networkMutationEnabled)
        WebView.setWebContentsDebuggingEnabled(debugEnabled)
    }

    private fun showAdvancedPanel() {
        advancedDialog?.dismiss()
        if (!preferences.getBoolean(BrowserRuntimeSettings.KEY_ADVANCED_ENABLED, false)) {
            AlertDialog.Builder(uiContext)
                .setTitle(uiContext.getText(R.string.browser_advanced_panel))
                .setMessage(uiContext.getText(R.string.browser_advanced_disabled))
                .setPositiveButton(R.string.browser_action_ok, null)
                .show()
            return
        }
        val controller = advancedController ?: run {
            AlertDialog.Builder(uiContext)
                .setTitle(uiContext.getText(R.string.browser_advanced_panel))
                .setMessage(uiContext.getText(R.string.browser_advanced_connection_unavailable))
                .setPositiveButton(R.string.browser_action_ok, null)
                .show()
            return
        }
        var surfaceGeneration = controller.openHumanSurface()
        advancedSurfaceGeneration = surfaceGeneration
        lateinit var panel: BrowserDevToolsPanel
        panel = BrowserDevToolsPanel(
            context = uiContext,
            networkMutationEnabled = preferences.getBoolean(BrowserRuntimeSettings.KEY_NETWORK_MUTATION_ENABLED, false),
            execute = { family, args, callback ->
                if (advancedActionRequiresTakeover(family, args)) {
                    requestHumanAction {
                        if (advancedDialog?.isShowing != true || closed || isFinishing || isDestroyed) return@requestHumanAction
                        surfaceGeneration = controller.openHumanSurface()
                        advancedSurfaceGeneration = surfaceGeneration
                        executeAdvancedAction(controller, panel, family, args, callback, surfaceGeneration)
                    }
                } else {
                    executeAdvancedAction(controller, panel, family, args, callback, surfaceGeneration)
                }
            },
            onResult = ::showAdvancedResult,
            onEntrySelected = ::showNetworkEntryDetail,
            onReplayApprovals = ::showReplayApprovalPanel,
        )
        val closeButton = iconButton(CommonR.drawable.ic_x, R.string.browser_cancel) {
            advancedDialog?.dismiss()
        }.apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)) }
        val shell = BrowserManagementPanel.create(this, uiContext.getText(R.string.browser_advanced_panel), closeButton)
        val dialog = Dialog(this)
        advancedDialog = dialog
        val scroll = ScrollView(uiContext).apply {
            isFillViewport = true
            addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        shell.content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setOnDismissListener {
            if (advancedDialog === dialog) advancedDialog = null
            if (advancedSurfaceGeneration == surfaceGeneration) advancedSurfaceGeneration = null
            controller.closeHumanSurface(surfaceGeneration)
        }
        BrowserManagementPanel.show(this, dialog, shell.root)
    }

    private fun executeAdvancedAction(
        controller: BrowserAdvancedController,
        panel: BrowserDevToolsPanel,
        family: String,
        args: JSONObject,
        callback: (JSONObject) -> Unit,
        surfaceGeneration: Long,
    ) {
        panel.setBusy(true)
        val requestSurfaceGeneration = surfaceGeneration
        controller.executeHuman(
            family,
            args,
            SystemClock.elapsedRealtime() + ADVANCED_UI_DEADLINE_MILLIS,
            surfaceGeneration,
        ) { result ->
            mainHandler.post {
                if (closed || isFinishing || isDestroyed ||
                    advancedSurfaceGeneration != requestSurfaceGeneration || advancedDialog?.isShowing != true) return@post
                panel.setBusy(false)
                result.put("_uiAction", args.optString("action"))
                when (args.optString("action")) {
                    "body" -> showNetworkBody(result)
                    "har", "export_frames" -> beginHarExport(result, args.optString("action") == "export_frames")
                    else -> callback(result)
                }
            }
        }
    }

    private fun showReplayApprovalPanel() {
        advancedDialog?.dismiss()
        val controller = advancedController ?: return
        if (closed || isFinishing || isDestroyed) return
        val surface = controller.openHumanSurface()
        advancedSurfaceGeneration = surface
        val dialog = Dialog(this)
        advancedDialog = dialog
        val closeButton = iconButton(CommonR.drawable.ic_x, R.string.browser_cancel) { dialog.dismiss() }
        val shell = BrowserManagementPanel.create(this, uiContext.getText(R.string.browser_network_replay_approvals), closeButton)
        val rows = LinearLayout(uiContext).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(12)) }
        rows.addView(settingsNote(R.string.browser_network_replay_risk))
        val snapshot = lifecycleController.snapshot()
        val pending = controller.pendingReplayApprovals()
        var count = 0
        for (index in 0 until minOf(pending.length(), 16)) {
            val request = pending.optJSONObject(index) ?: continue
            if (snapshot.owner != BrowserOwner.AI || request.optLong("sessionGeneration") != snapshot.sessionGeneration ||
                request.optLong("controlGeneration") != snapshot.controlGeneration) continue
            val preview = request.optJSONObject("preview") ?: continue
            count++
            val id = request.optString("requestId")
            rows.addView(settingsSeparator())
            rows.addView(TextView(uiContext).apply {
                text = "${preview.optString("method")}\n${request.optString("requestUrlForConfirmation")}"
                textSize = 14f; setTextColor(COLOR_INK); setTextIsSelectable(true)
                setPadding(0, dp(8), 0, dp(8))
            })
            rows.addView(TextView(uiContext).apply {
                text = "${uiContext.getString(R.string.browser_network_replay_body)}\n${request.optString("requestBodyForConfirmation")}\nSHA-256: ${preview.optString("requestBodySha256", preview.optString("bodySha256"))}"
                textSize = 12f; setTextColor(COLOR_INK); typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true); setPadding(0, dp(4), 0, dp(8))
            })
            fun decide(approve: Boolean) {
                val current = lifecycleController.snapshot()
                if (current.owner != BrowserOwner.AI || current.sessionGeneration != snapshot.sessionGeneration ||
                    current.controlGeneration != snapshot.controlGeneration) {
                    dialog.dismiss(); return
                }
                val onDecision: (JSONObject) -> Unit = { result ->
                    mainHandler.post {
                        if (closed || isFinishing || isDestroyed || advancedDialog !== dialog || !dialog.isShowing) return@post
                        if (result.optString("code") == "OK") {
                            if (approve) toast(R.string.browser_network_replay_approved)
                            showReplayApprovalPanel()
                        } else showAdvancedResult(result)
                    }
                }
                if (approve) controller.approveReplayFromHuman(id, current.sessionGeneration, current.controlGeneration, surface, onDecision)
                else controller.denyReplayFromHuman(id, current.sessionGeneration, current.controlGeneration, surface, onDecision)
            }
            rows.addView(historyTextAction(R.string.browser_network_replay_approve) { decide(true) })
            rows.addView(historyTextAction(R.string.browser_upload_deny) { decide(false) })
        }
        if (count == 0) rows.addView(settingsNote(R.string.browser_network_replay_none))
        shell.content.addView(ScrollView(uiContext).apply { isVerticalScrollBarEnabled = false; addView(rows) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setOnDismissListener {
            if (advancedDialog === dialog) advancedDialog = null
            if (advancedSurfaceGeneration == surface) advancedSurfaceGeneration = null
            controller.closeHumanSurface(surface)
        }
        BrowserManagementPanel.show(this, dialog, shell.root)
    }

    private fun advancedActionRequiresTakeover(family: String, args: JSONObject): Boolean {
        val action = args.optString("action")
        return when (family) {
            "debug" -> action in setOf("evaluate", "emulate")
            "network" -> action in setOf("start", "stop", "replay", "rules", "clear_rules", "resolve")
            else -> true
        }
    }

    private fun showAdvancedResult(message: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(uiContext)
            .setTitle(uiContext.getText(R.string.browser_advanced_result))
            .setMessage(message)
            .setPositiveButton(R.string.browser_action_ok, null)
            .show()
    }

    private fun showAdvancedResult(result: JSONObject) {
        if (isFinishing || isDestroyed) return
        val formatted = BrowserAdvancedResultFormatter.format(result)
        val body = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        body.addView(TextView(uiContext).apply {
            text = uiContext.getText(if (formatted.success) R.string.browser_advanced_success else R.string.browser_advanced_failure)
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (formatted.success) BrowserRuntimePalette.resolve(uiContext).blue else BrowserRuntimePalette.resolve(uiContext).ink)
        })
        val failureHint = when (BrowserCaptureFeedbackPolicy.advancedFailureHint(
            result.optString("_uiAction"), formatted.code,
        )) {
            BrowserAdvancedFailureHint.NO_PAGE -> R.string.browser_advanced_no_page
            BrowserAdvancedFailureHint.CONNECTION_UNAVAILABLE -> R.string.browser_advanced_connection_unavailable
            BrowserAdvancedFailureHint.NONE -> 0
        }
        if (failureHint != 0) body.addView(TextView(uiContext).apply {
            text = uiContext.getText(failureHint)
            textSize = 14f
            setTextColor(BrowserRuntimePalette.resolve(uiContext).ink)
            setPadding(0, dp(6), 0, dp(8))
        })
        val actionLabel = when (result.optString("_uiAction")) {
            "status" -> R.string.browser_debug_connection_status
            "console" -> R.string.browser_debug_console
            "dom" -> R.string.browser_debug_dom
            "evaluate" -> R.string.browser_debug_evaluate
            "emulate" -> R.string.browser_debug_emulate
            "start" -> R.string.browser_network_start
            "stop" -> R.string.browser_network_stop
            "list" -> R.string.browser_network_list
            "body" -> R.string.browser_network_body
            "har" -> R.string.browser_network_har
            "replay" -> R.string.browser_network_replay
            "rules" -> R.string.browser_network_rules
            "clear_rules" -> R.string.browser_network_clear_rules
            "paused" -> R.string.browser_network_paused
            "resolve" -> R.string.browser_network_continue
            else -> 0
        }
        if (actionLabel != 0) body.addView(TextView(uiContext).apply {
            text = uiContext.getString(R.string.browser_advanced_action_label, uiContext.getString(actionLabel))
            textSize = 13f
            setTextColor(BrowserRuntimePalette.resolve(uiContext).ink)
            setPadding(0, dp(5), 0, dp(3))
        })
        body.addView(TextView(uiContext).apply {
            text = "${uiContext.getString(R.string.browser_advanced_code)}: ${formatted.code}"
            textSize = 12f
            setTextColor(BrowserRuntimePalette.resolve(uiContext).muted)
            setPadding(0, dp(5), 0, dp(8))
        })
        formatted.fields.forEach { field ->
            val label = when (field.key) {
                "message", "error", "reason" -> R.string.browser_advanced_reason
                "url" -> R.string.browser_advanced_url
                "count", "entryCount", "requestCount", "pausedCount" -> R.string.browser_advanced_count
                "durationMillis" -> R.string.browser_advanced_duration
                "state" -> R.string.browser_advanced_field_state
                "owner" -> R.string.browser_advanced_field_owner
                "connected" -> R.string.browser_advanced_field_connected
                "enabled" -> R.string.browser_advanced_field_enabled
                "matched" -> R.string.browser_advanced_field_matched
                "width" -> R.string.browser_advanced_field_width
                "height" -> R.string.browser_advanced_field_height
                "mobile" -> R.string.browser_advanced_field_mobile
                "title" -> R.string.browser_advanced_field_title
                "mimeType" -> R.string.browser_advanced_field_mime
                else -> R.string.browser_advanced_details
            }
            body.addView(TextView(uiContext).apply {
                text = "${uiContext.getString(label)}: ${field.value}"
                textSize = 13f
                setTextColor(BrowserRuntimePalette.resolve(uiContext).ink)
                setPadding(0, dp(3), 0, dp(3))
                setTextIsSelectable(true)
            })
        }
        val raw = TextView(uiContext).apply {
            text = formatted.rawJson
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(BrowserRuntimePalette.resolve(uiContext).ink)
            setTextIsSelectable(true)
            visibility = View.GONE
            setPadding(0, dp(8), 0, dp(8))
        }
        body.addView(TextView(uiContext).apply {
            text = uiContext.getText(R.string.browser_advanced_show_raw)
            textSize = 13f
            setTextColor(BrowserRuntimePalette.resolve(uiContext).blue)
            isClickable = true
            setPadding(0, dp(10), 0, dp(8))
            setOnClickListener {
                raw.visibility = if (raw.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                text = uiContext.getText(if (raw.visibility == View.VISIBLE) R.string.browser_advanced_hide_raw else R.string.browser_advanced_show_raw)
            }
        })
        body.addView(raw)
        AlertDialog.Builder(uiContext)
            .setTitle(uiContext.getText(R.string.browser_advanced_result))
            .setView(ScrollView(uiContext).apply { addView(body) })
            .setPositiveButton(R.string.browser_action_ok, null)
            .show()
    }

    private fun showNetworkEntryDetail(entry: JSONObject) {
        if (closed || isFinishing || isDestroyed) return
        val request = entry.optJSONObject("request") ?: JSONObject()
        val response = entry.optJSONObject("response") ?: JSONObject()
        val requestHeaders = request.optJSONObject("headers") ?: JSONObject()
        val responseHeaders = response.optJSONObject("headers") ?: JSONObject()
        val duration = if (entry.has("durationMillis") && !entry.isNull("durationMillis")) {
            "${entry.optLong("durationMillis")} ms"
        } else uiContext.getString(R.string.browser_network_duration_pending)
        val detail = buildString {
            append(request.optString("method"))
            append(" ")
            append(request.optString("url"))
            append("\n")
            append("HTTP ")
            append(response.optInt("status"))
            append("  ·  ")
            append(duration)
            append("\n\n")
            append(uiContext.getString(R.string.browser_network_request_headers))
            append("\n")
            append(requestHeaders.keys().asSequence().map { key -> "$key: ${requestHeaders.opt(key)}" }.joinToString("\n").ifBlank { "—" })
            append("\n\n")
            append(uiContext.getString(R.string.browser_network_response_headers))
            append("\n")
            append(responseHeaders.keys().asSequence().map { key -> "$key: ${responseHeaders.opt(key)}" }.joinToString("\n").ifBlank { "—" })
        }
        val builder = AlertDialog.Builder(uiContext)
            .setTitle(uiContext.getText(R.string.browser_network_request_detail))
            .setMessage(detail)
            .setNegativeButton(R.string.browser_cancel, null)
        if (entry.optBoolean("bodyAvailable")) {
            builder.setPositiveButton(R.string.browser_network_body) { _, _ ->
                val controller = advancedController ?: return@setPositiveButton
                val surfaceGeneration = advancedSurfaceGeneration ?: return@setPositiveButton
                val requestSurfaceGeneration = surfaceGeneration
                controller.executeHuman(
                    "network",
                    JSONObject().put("action", "body").put("entryId", entry.optString("entryId")),
                    SystemClock.elapsedRealtime() + ADVANCED_UI_DEADLINE_MILLIS,
                    surfaceGeneration,
                ) { result -> mainHandler.post {
                    if (closed || isFinishing || isDestroyed ||
                        advancedSurfaceGeneration != requestSurfaceGeneration || advancedDialog?.isShowing != true) return@post
                    showNetworkBody(result)
                } }
            }
        }
        builder.show()
    }

    private fun showNetworkBody(result: JSONObject) {
        if (closed || isFinishing || isDestroyed) return
        if (result.optString("code") != "OK") return showAdvancedResult(result.put("_uiAction", "body"))
        val data = result.optJSONObject("data") ?: return showAdvancedResult(uiContext.getString(R.string.browser_advanced_error))
        val file = resolveNetworkArtifact(data.optString("artifactPath"))
            ?: return showAdvancedResult(uiContext.getString(R.string.browser_advanced_error))
        val mime = data.optString("mimeType").lowercase(Locale.ROOT)
        val textual = mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") ||
            mime.contains("javascript") || mime.contains("x-www-form-urlencoded")
        if (!textual) {
            AlertDialog.Builder(uiContext)
                .setTitle(uiContext.getText(R.string.browser_network_body))
                .setMessage(uiContext.getText(R.string.browser_network_binary_body))
                .setPositiveButton(R.string.browser_action_ok, null)
                .show()
            return
        }
        val content = runCatching {
            val bytes = file.readBytes()
            String(bytes, Charsets.UTF_8).take(MAX_NETWORK_BODY_PREVIEW_CHARS)
        }.getOrElse { return showAdvancedResult(uiContext.getString(R.string.browser_advanced_error)) }
        AlertDialog.Builder(uiContext)
            .setTitle(uiContext.getText(R.string.browser_network_body))
            .setMessage(uiContext.getString(R.string.browser_network_body_notice) + "\n\n" + content)
            .setPositiveButton(R.string.browser_action_ok, null)
            .show()
    }

    private fun beginHarExport(result: JSONObject, webSocketFrames: Boolean = false) {
        if (closed || isFinishing || isDestroyed) return
        if (result.optString("code") != "OK") return showAdvancedResult(result.put("_uiAction", "har"))
        val data = result.optJSONObject("data") ?: return showAdvancedResult(uiContext.getString(R.string.browser_advanced_error))
        val file = resolveNetworkArtifact(data.optString("artifactPath"))
            ?: return showAdvancedResult(uiContext.getString(R.string.browser_advanced_error))
        if (data.optString("mimeType") != "application/json") {
            return showAdvancedResult(uiContext.getString(R.string.browser_advanced_error))
        }
        pendingHarArtifact = file
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, if (webSocketFrames) "browser-websocket-frames.json" else "browser-capture.har")
        try {
            startActivityForResult(intent, REQUEST_EXPORT_HAR)
        } catch (_: RuntimeException) {
            pendingHarArtifact = null
            showAdvancedResult(uiContext.getString(R.string.browser_advanced_error))
        }
    }

    private fun resolveNetworkArtifact(guestPath: String): File? = runCatching {
        val prefix = "/opt/browser/out/network/"
        require(guestPath.startsWith(prefix))
        val name = guestPath.removePrefix(prefix)
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,128}")))
        val root = filesDir.canonicalFile
        var directory = File(root, "browser-tools")
        require(!java.nio.file.Files.isSymbolicLink(directory.toPath()))
        directory = File(directory, "out")
        require(!java.nio.file.Files.isSymbolicLink(directory.toPath()))
        directory = File(directory, "network")
        require(!java.nio.file.Files.isSymbolicLink(directory.toPath()))
        val file = File(directory, name)
        require(!java.nio.file.Files.isSymbolicLink(file.toPath()))
        val canonical = file.canonicalFile
        require(canonical.parentFile == directory.canonicalFile)
        require(canonical.isFile && canonical.length() in 1..MAX_NETWORK_ARTIFACT_BYTES)
        canonical
    }.getOrNull()

    @Deprecated("Use the document picker result for the explicit Browser export flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pendingFileChooserRequestCode) {
            val callback = pendingFileChooser
            val sourceView = pendingFileChooserWebView
            pendingFileChooser = null
            pendingFileChooserWebView = null
            pendingFileChooserRequestCode = null
            val selected = if (resultCode == RESULT_OK && !closed && sourceView === webView) {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                    ?.filter { it.scheme == "content" }?.take(20)?.toTypedArray()
            } else null
            runCatching { callback?.onReceiveValue(selected?.takeIf { it.isNotEmpty() }) }
            return
        }
        if (requestCode == REQUEST_SAVE_DOWNLOAD) {
            val pending = pendingDownloadSave
            pendingDownloadSave = null
            if (resultCode != RESULT_OK || pending == null) return
            val destination = data?.data ?: return
            copyDownloadToDocument(pending, destination)
            return
        }
        if (requestCode != REQUEST_EXPORT_HAR) return
        val source = pendingHarArtifact
        pendingHarArtifact = null
        val destination = data?.data
        if (resultCode != RESULT_OK || source == null || destination == null) return
        val copied = runCatching {
            val bytes = BrowserArtifactFiles.readNetworkArtifact(filesDir, source.name, MAX_NETWORK_ARTIFACT_BYTES.toInt()) ?: error("missing export")
            contentResolver.openOutputStream(destination, "wt")?.use { output ->
                output.write(bytes)
            } ?: error("cannot write export")
        }.isSuccess
        if (!copied) return showAdvancedResult(uiContext.getString(R.string.browser_advanced_error))
        Toast.makeText(this, uiContext.getText(R.string.browser_network_har_saved), Toast.LENGTH_SHORT).show()
        val share = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, destination)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(share, uiContext.getText(R.string.browser_network_har))) }
    }

    private fun setAdvancedEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(BrowserRuntimeSettings.KEY_ADVANCED_ENABLED, enabled)
            .apply()
        if (!enabled) {
            preferences.edit()
                .putBoolean(BrowserRuntimeSettings.KEY_NETWORK_MUTATION_ENABLED, false)
                .apply()
        }
        syncAdvancedPermission()
        refreshAdvancedPermission()
    }

    private fun setNetworkMutationEnabled(enabled: Boolean) {
        val permitted = enabled && preferences.getBoolean(BrowserRuntimeSettings.KEY_ADVANCED_ENABLED, false)
        preferences.edit()
            .putBoolean(BrowserRuntimeSettings.KEY_NETWORK_MUTATION_ENABLED, permitted)
            .apply()
        refreshAdvancedPermission()
    }

    private fun showElementPicker() {
        val container = pageContainer ?: return
        if (closed || lifecycleController.snapshot().lifecycle != BrowserLifecycle.ACTIVE) return
        requestHumanAction { showElementPickerAfterApproval(container) }
    }

    private fun showElementPickerAfterApproval(container: FrameLayout) {
        if (pageContainer !== container || closed || lifecycleController.snapshot().lifecycle != BrowserLifecycle.ACTIVE) return
        advancedDialog?.dismiss()
        cancelElementPicker()
        pickerMode = BrowserSelectionStore.MODE_ELEMENT
        pickerSelection = null
        pickerBounds = null
        navigationRow?.visibility = View.GONE
        pickerToolbar?.visibility = View.VISIBLE
        updateSelectionButton(true)
        updatePickerModeButtons()
        val generation = ++pickerGeneration
        pickerElementIdentity = BrowserPickerElementIdentity()
        pickerProvenance = capturePickerProvenance()
        val overlay = FrameLayout(uiContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusableInTouchMode = true
        }
        val surface = BrowserElementPickerView(
            context = uiContext,
            onSelectionGesture = { gesture -> onPickerGesture(generation, gesture) },
            onDoubleTap = { point ->
                val selectedBounds = pickerBounds
                if (pickerSelection != null && selectedBounds != null && selectedBounds.contains(point.centerX(), point.centerY())) {
                    showPickerComment(selectedBounds)
                }
            },
            onForwardedTouch = ::forwardPickerTouch,
            onForwardingStarted = {
                clearPickerSelectionForViewportChange()
            },
            onForwardingFinished = {
                if (generation == pickerGeneration && pickerOverlay != null) {
                    pickerProvenance = capturePickerProvenance()
                }
            },
        )
        pickerSurface = surface
        overlay.addView(surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val commentPanel = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(6), dp(5))
            background = roundedBackground(COLOR_PAPER, COLOR_LINE, dp(18))
            visibility = View.GONE
        }
        val selectionHint = TextView(uiContext).apply {
            text = uiContext.getText(R.string.browser_picker_double_tap)
            textSize = 12f
            setTextColor(COLOR_MUTED)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        pickerHint = selectionHint
        pickerComment = EditText(uiContext).apply {
            hint = uiContext.getString(R.string.browser_picker_comment_hint)
            textSize = 13f
            setTextColor(COLOR_INK)
            setHintTextColor(COLOR_MUTED)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(9), 0, dp(5), 0)
            background = roundedBackground(COLOR_SOFT, COLOR_LINE, dp(14))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
        }
        val saveButton = TextView(uiContext).apply {
            text = uiContext.getText(R.string.browser_picker_save)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(COLOR_BLUE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isEnabled = false
            alpha = 0.45f
            setOnClickListener { commitElementSelection(generation) }
        }
        commentPanel.addView(selectionHint)
        commentPanel.addView(pickerComment)
        commentPanel.addView(saveButton)
        commentPanel.tag = saveButton
        pickerCommentPanel = commentPanel
        overlay.addView(commentPanel, FrameLayout.LayoutParams(
            minOf(dp(320), (resources.displayMetrics.widthPixels - dp(20)).coerceAtLeast(dp(200))),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START,
        ).apply { setMargins(dp(10), dp(66), dp(10), 0) })
        overlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val currentBounds = pickerBounds
            if (commentPanel.visibility == View.VISIBLE && currentBounds != null) {
                positionPickerPanel(commentPanel, currentBounds)
            }
        }
        overlay.setOnApplyWindowInsetsListener { _, insets ->
            val currentBounds = pickerBounds
            if (commentPanel.visibility == View.VISIBLE && currentBounds != null) {
                positionPickerPanel(commentPanel, currentBounds)
            }
            insets
        }
        pickerOverlay = overlay
        container.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        surface.requestFocus()
    }

    private fun pickerModeButton(iconRes: Int, labelRes: Int, action: () -> Unit): View =
        LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            isClickable = true
            isFocusable = true
            background = pickerModeBackground(false)
            addView(iconView(iconRes, labelRes, dp(17)))
            addView(TextView(uiContext).apply {
                text = uiContext.getText(labelRes)
                textSize = 12f
                setTextColor(COLOR_INK)
                setPadding(dp(5), 0, 0, 0)
            })
            setOnClickListener { action() }
        }

    private fun setPickerMode(mode: String) {
        if (mode != BrowserSelectionStore.MODE_ELEMENT && mode != BrowserSelectionStore.MODE_AREA) return
        pickerMode = mode
        pickerSelection = null
        pickerBounds = null
        clearPickerFrame()
        pickerSurface?.apply {
            setElementMode(mode == BrowserSelectionStore.MODE_ELEMENT)
            contentDescription = uiContext.getString(
                if (mode == BrowserSelectionStore.MODE_ELEMENT) R.string.browser_picker_element else R.string.browser_picker_area,
            )
            clearSelection()
        }
        pickerCommentPanel?.visibility = View.GONE
        updatePickerModeButtons()
    }

    private fun updatePickerModeButtons() {
        updatePickerModeButton(pickerElementModeButton, pickerMode == BrowserSelectionStore.MODE_ELEMENT)
        updatePickerModeButton(pickerAreaModeButton, pickerMode == BrowserSelectionStore.MODE_AREA)
    }

    private fun updatePickerModeButton(button: View?, selected: Boolean) {
        button ?: return
        button.background = pickerModeBackground(selected)
        ((button as? ViewGroup)?.getChildAt(0) as? ImageView)?.setColorFilter(if (selected) COLOR_BLUE else COLOR_MUTED)
        ((button as? ViewGroup)?.getChildAt(1) as? TextView)?.setTextColor(if (selected) COLOR_BLUE else COLOR_INK)
    }

    private fun pickerModeBackground(selected: Boolean) = roundedBackground(
        if (selected) COLOR_PICKER_BLUE_SOFT else COLOR_PAPER,
        if (selected) COLOR_BLUE else COLOR_LINE,
        dp(10),
    )

    private fun showPickerComment(bounds: RectF) {
        if (pickerFrame == null) {
            pickerCommentRequested = true
            return
        }
        pickerCommentRequested = false
        val panel = pickerCommentPanel ?: return
        panel.visibility = View.VISIBLE
        panel.getChildAt(0)?.visibility = View.GONE
        pickerComment?.visibility = View.VISIBLE
        (panel.tag as? TextView)?.visibility = View.VISIBLE
        positionPickerPanel(panel, bounds)
        pickerComment?.requestFocus()
        pickerComment?.let(::showBrowserInput)
        pickerOverlay?.requestApplyInsets()
    }

    private fun showPickerQuickAction(bounds: RectF) {
        val panel = pickerCommentPanel ?: return
        panel.visibility = View.VISIBLE
        panel.getChildAt(0)?.visibility = View.VISIBLE
        pickerComment?.visibility = View.GONE
        updatePickerSaveButton()
        positionPickerPanel(panel, bounds)
    }

    private fun updatePickerSaveButton() {
        (pickerCommentPanel?.tag as? TextView)?.apply {
            isEnabled = pickerSelection != null && pickerFrame != null
            alpha = if (isEnabled) 1f else 0.45f
        }
    }

    private fun positionPickerPanel(panel: View, bounds: RectF) {
        panel.post {
            val overlay = pickerOverlay as? FrameLayout ?: return@post
            val left = bounds.left.toInt().coerceIn(dp(10), (overlay.width - panel.measuredWidth - dp(10)).coerceAtLeast(dp(10)))
            val below = bounds.bottom.toInt() + dp(8)
            val visibleFrame = android.graphics.Rect()
            val overlayLocation = IntArray(2)
            overlay.getWindowVisibleDisplayFrame(visibleFrame)
            overlay.getLocationOnScreen(overlayLocation)
            val visibleBottom = (visibleFrame.bottom - overlayLocation[1]).coerceIn(0, overlay.height)
            val availableBottom = minOf(overlay.height, visibleBottom)
            val preferredTop = if (below + panel.measuredHeight < availableBottom - dp(8)) {
                below
            } else bounds.top.toInt() - panel.measuredHeight - dp(8)
            val minimumTop = dp(8)
            val maximumTop = (availableBottom - panel.measuredHeight - dp(8)).coerceAtLeast(minimumTop)
            val top = preferredTop.coerceIn(minimumTop, maximumTop)
            (panel.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.gravity = Gravity.TOP or Gravity.START
                if (params.leftMargin == left && params.topMargin == top) return@let
                params.setMargins(left, top, dp(10), 0)
                panel.layoutParams = params
            }
        }
    }

    private fun forwardPickerTouch(event: MotionEvent) {
        val source = pickerSurface ?: return
        val target = webView ?: return
        val sourceLocation = IntArray(2)
        val targetLocation = IntArray(2)
        source.getLocationOnScreen(sourceLocation)
        target.getLocationOnScreen(targetLocation)
        val forwarded = MotionEvent.obtain(event)
        try {
            forwarded.offsetLocation(
                (sourceLocation[0] - targetLocation[0]).toFloat(),
                (sourceLocation[1] - targetLocation[1]).toFloat(),
            )
            target.dispatchTouchEvent(forwarded)
        } finally {
            forwarded.recycle()
        }
    }

    private fun clearPickerSelectionForViewportChange() {
        pickerSelection = null
        pickerBounds = null
        clearPickerFrame()
        pickerSurface?.clearSelection()
        pickerComment?.setText("")
        pickerCommentPanel?.visibility = View.GONE
        pickerProvenance = capturePickerProvenance()
    }

    private fun onPickerGesture(generation: Long, gesture: RectF) {
        if (generation != pickerGeneration || pickerOverlay == null) return
        clearPickerFrame()
        if (pickerMode == BrowserSelectionStore.MODE_AREA) {
            if (gesture.width() < dp(12) || gesture.height() < dp(12)) {
                pickerSelection = null
                pickerBounds = null
                clearPickerFrame()
                return
            }
            pickerBounds = gesture
            pickerSelection = JSONObject()
                .put("kind", "area")
                .put("width", gesture.width().toInt())
                .put("height", gesture.height().toInt())
            showPickerQuickAction(gesture)
            captureFrozenPickerFrame(generation)
            return
        }
        val view = webView ?: return
        val x = (gesture.centerX() / view.scale).coerceAtLeast(0f)
        val y = (gesture.centerY() / view.scale).coerceAtLeast(0f)
        val xLiteral = String.format(Locale.US, "%.2f", x)
        val yLiteral = String.format(Locale.US, "%.2f", y)
        val script = """(() => { const e=document.elementFromPoint($xLiteral,$yLiteral); if(!e)return null;
            const editableSelector='input,textarea,select,[role=textbox],[contenteditable]:not([contenteditable=false])';
            const editable=n=>{ for(let p=n;p;p=p.parentElement){ if(p.isContentEditable||p.matches(editableSelector)) return true; }
              if(n.querySelector&&n.querySelector(editableSelector)) return true;
              const l=n.closest&&n.closest('label'); return !!(l&&l.querySelector(editableSelector)); };
            if(editable(e))return null; const r=e.getBoundingClientRect();
            const label=e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.getAttribute('title')||'';
            const text=String(e.innerText||'').replace(/\s+/g,' ').trim().slice(0,500);
            let link=''; if(e.tagName==='A') { try { const u=new URL(e.href); if(u.protocol==='http:'||u.protocol==='https:') link=u.origin; } catch(_){} }
            ${pickerElementIdentity?.rememberScript("e").orEmpty()}
            return JSON.stringify({tag:e.tagName.toLowerCase(),role:e.getAttribute('role')||'',type:e.getAttribute('type')||'',label:String(label).slice(0,160),text,link,left:r.left,top:r.top,right:r.right,bottom:r.bottom}); })()"""
        view.evaluateJavascript(script) { encoded ->
            if (generation != pickerGeneration || pickerOverlay == null) return@evaluateJavascript
            val raw = runCatching { JSONArray("[$encoded]").optString(0) }.getOrNull()
            val element = runCatching { raw?.let(::JSONObject) }.getOrNull()
            if (element == null) {
                pickerSelection = null
                pickerBounds = null
                clearPickerFrame()
                return@evaluateJavascript
            }
            val scale = view.scale
            val rect = RectF(
                (element.optDouble("left") * scale).toFloat(),
                (element.optDouble("top") * scale).toFloat(),
                (element.optDouble("right") * scale).toFloat(),
                (element.optDouble("bottom") * scale).toFloat(),
            )
            rect.intersect(0f, 0f, view.width.toFloat(), view.height.toFloat())
            if (rect.isEmpty) {
                pickerSelection = null
                pickerBounds = null
                clearPickerFrame()
                return@evaluateJavascript
            }
            element.remove("left")
            element.remove("top")
            element.remove("right")
            element.remove("bottom")
            pickerSelection = element.put("kind", "element")
            pickerBounds = rect
            pickerSurface?.showSelection(rect)
            if (pickerComment?.visibility != View.VISIBLE) showPickerQuickAction(rect)
            captureFrozenPickerFrame(generation)
        }
    }

    private fun commitElementSelection(generation: Long) {
        if (generation != pickerGeneration || pickerOverlay == null) return
        val bounds = pickerBounds ?: return
        val selected = pickerSelection ?: return
        val frame = pickerFrame ?: run {
            val code = pickerCaptureFailureCode ?: "NO_FRAME"
            Log.w(TAG, "selection_commit_without_frame:$code")
            showCaptureFailure(code)
            return
        }
        val frameRequest = pickerFrameRequest
        fun selectionStillCurrent() = generation == pickerGeneration && pickerOverlay != null &&
            frameRequest == pickerFrameRequest && frame === pickerFrame && !frame.isRecycled &&
            bounds === pickerBounds && selected === pickerSelection
        val comment = pickerComment?.text?.toString().orEmpty().trim()
        if (comment.length > 2_048) {
            toast(R.string.browser_advanced_error)
            return
        }
        val provenance = pickerProvenance ?: return toast(R.string.browser_capture_target_changed)
        if (!pickerProvenanceStillCurrent(provenance)) {
            cancelElementPicker()
            toast(R.string.browser_capture_target_changed)
            return
        }
        val persist: () -> Unit = persist@{
            if (!selectionStillCurrent()) return@persist
            val crop = cropSelection(frame, bounds)
            if (crop == null) {
                Log.w(TAG, "selection_crop_failed")
                toast(R.string.browser_capture_image_failed)
            } else {
                val session = provenance.getLong("sessionGeneration")
                val mode = pickerMode
                val pageUrl = provenance.optString("pageUrl")
                val pageTitle = webView?.title.orEmpty()
                val selectedSnapshot = JSONObject(selected.toString())
                val provenanceSnapshot = JSONObject(provenance.toString())
                selectionExecutor.execute {
                    if (generation != pickerGeneration || frameRequest != pickerFrameRequest) {
                        crop.recycle()
                        return@execute
                    }
                    val result = runCatching {
                        selectionStore.save(
                            sessionGeneration = session,
                            nowElapsedMillis = SystemClock.elapsedRealtime(),
                            mode = mode,
                            comment = comment,
                            pageUrl = pageUrl,
                            pageTitle = pageTitle,
                            selection = selectedSnapshot,
                            provenance = provenanceSnapshot,
                            bounds = bounds,
                            bitmap = crop,
                        )
                    }
                    crop.recycle()
                    mainHandler.post {
                        if (!selectionStillCurrent()) return@post
                        if (result.isSuccess && pickerProvenanceStillCurrent(provenance)) {
                            cancelElementPicker()
                            val selectionId = result.getOrNull()?.optJSONObject("data")?.optString("selectionId").orEmpty()
                            selectionReceiptPollGeneration++
                            toast(R.string.browser_selection_waiting)
                            pollSelectionReceipt(selectionId, session, selectionReceiptPollGeneration,
                                SystemClock.elapsedRealtime() + 15_000L)
                        } else {
                            val errorCode = result.getOrNull()?.optString("code")
                                ?.takeIf { it != "OK" }
                                ?: result.exceptionOrNull()?.javaClass?.simpleName
                                ?: "STALE_PROVENANCE"
                            Log.w(TAG, "selection_persist_failed:$errorCode")
                            toast(R.string.browser_capture_save_failed)
                        }
                    }
                }
            }
        }
        if (pickerMode == BrowserSelectionStore.MODE_ELEMENT) {
            verifyPickerElement(generation, bounds, selected, allowRelayout = true) { matches ->
                if (!selectionStillCurrent()) return@verifyPickerElement
                if (!matches || !pickerProvenanceStillCurrent(provenance)) {
                    Log.w(TAG, "selection_commit_validation_failed:${if (matches) "STALE_PROVENANCE" else "ELEMENT_CHANGED"}")
                    cancelElementPicker()
                    toast(R.string.browser_capture_target_changed)
                } else persist()
            }
        } else persist()
    }

    private fun capturePickerProvenance(): JSONObject {
        val snapshot = lifecycleController.snapshot()
        return JSONObject()
            .put("sessionGeneration", snapshot.sessionGeneration)
            .put("controlGeneration", snapshot.controlGeneration)
            .put("documentGeneration", pageDocumentGeneration)
            .put("pageId", pageManager?.activePageId.orEmpty())
            .put("pageUrl", webView?.url.orEmpty())
            .put("zoomPercent", zoomPercent)
            .put("webScale", webView?.scale?.toDouble() ?: 1.0)
            .put("rotation", windowManager.defaultDisplay.rotation)
            .put("createdAtElapsedMillis", SystemClock.elapsedRealtime())
    }

    private fun pickerProvenanceStillCurrent(provenance: JSONObject): Boolean {
        val snapshot = lifecycleController.snapshot()
        return snapshot.lifecycle == BrowserLifecycle.ACTIVE &&
            snapshot.sessionGeneration == provenance.optLong("sessionGeneration", -1L) &&
            snapshot.controlGeneration == provenance.optLong("controlGeneration", -1L) &&
            pageDocumentGeneration == provenance.optLong("documentGeneration", -1L) &&
            pageManager?.activePageId.orEmpty() == provenance.optString("pageId") &&
            webView?.url.orEmpty() == provenance.optString("pageUrl") &&
            zoomPercent == provenance.optInt("zoomPercent", -1) &&
            kotlin.math.abs((webView?.scale?.toDouble() ?: 1.0) - provenance.optDouble("webScale", -1.0)) < 0.01 &&
            windowManager.defaultDisplay.rotation == provenance.optInt("rotation", -1)
    }

    private fun captureFrozenPickerFrame(generation: Long) {
        val provenance = pickerProvenance ?: return
        if (pickerBounds == null || pickerSelection == null) return
        val request = ++pickerFrameRequest
        captureFreshCurrentPage(SystemClock.elapsedRealtime() + SELECTION_CAPTURE_DEADLINE_MILLIS) { bitmap, code ->
            if (!BrowserCaptureRequestGate.isCurrent(
                    request, pickerFrameRequest, generation, pickerGeneration, pickerOverlay != null,
                )
            ) {
                bitmap?.recycle()
                return@captureFreshCurrentPage
            }
            if (!pickerProvenanceStillCurrent(provenance)) {
                bitmap?.recycle()
                pickerCaptureFailureCode = "STALE_PROVENANCE"
                pickerHint?.apply {
                    setText(R.string.browser_capture_target_changed)
                    setTextColor(COLOR_MUTED)
                }
                Log.w(TAG, "selection_frame_stale_provenance")
                return@captureFreshCurrentPage
            }
            if (bitmap == null || code != "OK") {
                bitmap?.recycle()
                pickerCaptureFailureCode = code
                pickerHint?.apply {
                    setText(captureFailureMessage(code))
                    setTextColor(COLOR_MUTED)
                }
                Log.w(TAG, "selection_frame_capture_failed:$code")
                return@captureFreshCurrentPage
            }
            val acceptFrame = {
                if (!BrowserCaptureRequestGate.isCurrent(
                        request, pickerFrameRequest, generation, pickerGeneration, pickerOverlay != null,
                    ) || !pickerProvenanceStillCurrent(provenance)
                ) {
                    bitmap.recycle()
                } else {
                    pickerCaptureFailureCode = null
                    pickerHint?.apply {
                        setText(R.string.browser_picker_double_tap)
                        setTextColor(COLOR_MUTED)
                    }
                    pickerFrame?.recycle()
                    pickerFrame = bitmap
                    pickerPreviewCrop?.recycle()
                    pickerPreviewCrop = pickerBounds?.let { cropSelection(bitmap, it) }
                    updatePickerSaveButton()
                    if (pickerCommentRequested) pickerBounds?.let(::showPickerComment)
                }
            }
            val bounds = pickerBounds
            val selected = pickerSelection
            if (pickerMode == BrowserSelectionStore.MODE_ELEMENT && bounds != null && selected != null) {
                verifyPickerElement(generation, bounds, selected) { matches ->
                    if (!BrowserCaptureRequestGate.isCurrent(
                            request, pickerFrameRequest, generation, pickerGeneration, pickerOverlay != null,
                        )
                    ) {
                        bitmap.recycle()
                        return@verifyPickerElement
                    }
                    if (!pickerProvenanceStillCurrent(provenance)) {
                        bitmap.recycle()
                        pickerCaptureFailureCode = "STALE_PROVENANCE"
                        pickerHint?.apply {
                            setText(R.string.browser_capture_target_changed)
                            setTextColor(COLOR_MUTED)
                        }
                        Log.w(TAG, "selection_frame_stale_after_element_check")
                        return@verifyPickerElement
                    }
                    if (matches) acceptProjectBoundPickerFrame(provenance, acceptFrame) else {
                        pickerCaptureFailureCode = "ELEMENT_CHANGED_DURING_CAPTURE"
                        pickerHint?.apply {
                            setText(R.string.browser_capture_target_changed)
                            setTextColor(COLOR_MUTED)
                        }
                        Log.w(TAG, "selection_frame_element_changed")
                        bitmap.recycle()
                    }
                }
            } else {
                acceptProjectBoundPickerFrame(provenance, acceptFrame)
            }
        }
    }

    /** Bind source metadata to this frozen frame, not to a later service occupying its port. */
    private fun acceptProjectBoundPickerFrame(provenance: JSONObject, acceptFrame: () -> Unit) {
        provenance.remove("projectContext")
        val view = webView
        if (view == null || !provenance.optString("pageUrl").startsWith("http://127.0.0.1:")) {
            acceptFrame()
            return
        }
        var finished = false // Every completion below runs on the main thread.
        val timeout = Runnable { if (!finished) { finished = true; acceptFrame() } }
        mainHandler.postDelayed(timeout, 1500L)
        val script = """(()=>{try{const nodes=document.querySelectorAll('meta[name="dsh-browser-project"]');
          if(nodes.length!==1)return '';const v=nodes[0].getAttribute('content');return typeof v==='string'&&v.length<=2048?v:''}catch(_){return ''}})()"""
        try {
            view.evaluateJavascript(script) { encoded ->
                if (finished) return@evaluateJavascript
                val marker = runCatching { org.json.JSONTokener(encoded).nextValue() as? String }.getOrNull().orEmpty()
                if (marker.isEmpty()) {
                    finished = true
                    mainHandler.removeCallbacks(timeout)
                    acceptFrame()
                } else {
                    try {
                        selectionExecutor.execute {
                            val context = BrowserProjectDocumentProof.capture(filesDir, marker, provenance.optString("pageUrl"))
                            mainHandler.post {
                                if (!finished) {
                                    finished = true
                                    mainHandler.removeCallbacks(timeout)
                                    if (context != null && pickerProvenanceStillCurrent(provenance)) provenance.put("projectContext", context)
                                    acceptFrame()
                                }
                            }
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        if (!finished) { finished = true; mainHandler.removeCallbacks(timeout); acceptFrame() }
                    }
                }
            }
        } catch (_: Exception) {
            if (!finished) { finished = true; mainHandler.removeCallbacks(timeout); acceptFrame() }
        }
    }

    private fun clearPickerFrame() {
        pickerFrameRequest++
        pickerCommentRequested = false
        pickerCaptureFailureCode = null
        pickerHint?.apply {
            setText(R.string.browser_picker_double_tap)
            setTextColor(COLOR_MUTED)
        }
        pickerFrame?.recycle()
        pickerFrame = null
        pickerPreviewCrop?.recycle()
        pickerPreviewCrop = null
        updatePickerSaveButton()
    }

    private fun verifyPickerElement(generation: Long, bounds: RectF, selected: JSONObject, allowRelayout: Boolean = false, callback: (Boolean) -> Unit) {
        val view = webView ?: return callback(false)
        val scale = view.scale.takeIf { it > 0f } ?: return callback(false)
        val x = (bounds.centerX() / scale).coerceAtLeast(0f)
        val y = (bounds.centerY() / scale).coerceAtLeast(0f)
        val identity = pickerElementIdentity ?: return callback(false)
        val script = """(() => { const e=${identity.resolveScript(x, y, allowRelayout)}; if(!e)return false;
          const inputLike='input,textarea,select,[role=textbox],[contenteditable]:not([contenteditable=false])';
          const bad=n=>{for(let p=n;p;p=p.parentElement){if(p.isContentEditable||p.matches(inputLike))return true;}
            if(n.querySelector&&n.querySelector(inputLike))return true;
            const l=n.closest&&n.closest('label');return !!(l&&l.querySelector(inputLike));};
          if(bad(e))return false;const r=e.getBoundingClientRect(),label=e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.getAttribute('title')||'';
          let link='';if(e.tagName==='A'){try{const u=new URL(e.href);if(u.protocol==='http:'||u.protocol==='https:')link=u.origin;}catch(_){}}
          const text=String(e.innerText||'').replace(/\s+/g,' ').trim().slice(0,500);return JSON.stringify({tag:e.tagName.toLowerCase(),role:e.getAttribute('role')||'',type:e.getAttribute('type')||'',label:String(label).slice(0,160),text,link,left:r.left,top:r.top,right:r.right,bottom:r.bottom});})()"""
        view.evaluateJavascript(script) { encoded ->
            if (generation != pickerGeneration || pickerOverlay == null) return@evaluateJavascript callback(false)
            val raw = runCatching { JSONArray("[$encoded]").optString(0) }.getOrNull()
            val actual = runCatching { raw?.let(::JSONObject) }.getOrNull()
            if (actual == null) return@evaluateJavascript callback(false)
            val expectedCss = floatArrayOf(bounds.left / scale, bounds.top / scale, bounds.right / scale, bounds.bottom / scale)
            val actualCss = floatArrayOf(actual.optDouble("left").toFloat(), actual.optDouble("top").toFloat(), actual.optDouble("right").toFloat(), actual.optDouble("bottom").toFloat())
            val stableFields = listOf("tag", "role", "type", "label", "text", "link").all { actual.optString(it) == selected.optString(it) }
            val stableBounds = expectedCss.indices.all { kotlin.math.abs(expectedCss[it] - actualCss[it]) <= PICKER_BOUNDS_TOLERANCE_CSS_PX }
            callback(stableFields && (allowRelayout || stableBounds))
        }
    }

    private fun cropSelection(source: Bitmap, bounds: RectF): Bitmap? = BrowserSelectionCrop.create(source, bounds)

    private fun updateSelectionButton(selected: Boolean) {
        selectionButton?.apply {
            isSelected = selected
            setColorFilter(if (selected) COLOR_BLUE else COLOR_INK)
            background = roundedBackground(
                if (selected) Color.rgb(235, 242, 255) else COLOR_PAPER,
                if (selected) Color.rgb(210, 225, 248) else COLOR_LINE,
                dp(22),
            )
        }
    }

    private fun cancelElementPicker() {
        pickerElementIdentity?.let { identity -> webView?.evaluateJavascript(identity.clearScript(), null) }
        pickerElementIdentity = null
        pickerComment?.let { field ->
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
            field.clearFocus()
        }
        pickerGeneration++
        clearPickerFrame()
        pickerOverlay?.let { overlay -> (overlay.parent as? ViewGroup)?.removeView(overlay) }
        pickerOverlay = null
        navigationRow?.visibility = View.VISIBLE
        pickerToolbar?.visibility = View.GONE
        updateSelectionButton(false)
        pickerSurface = null
        pickerComment = null
        pickerCommentPanel = null
        pickerHint = null
        pickerSelection = null
        pickerBounds = null
        pickerProvenance = null
    }

    private fun setIdleTimeout(minutes: Int) {
        preferences.edit().putInt(KEY_IDLE_MINUTES, minutes).apply()
        val snapshot = lifecycleController.updateIdleTimeout(minutes * MINUTE_MILLIS)
        publishStatus(snapshot)
        if (snapshot.lifecycle == BrowserLifecycle.CLOSING) releaseSession(finishActivity = true)
        else scheduleIdleAlarm(snapshot)
    }

    private fun settingsHeading(labelRes: Int): View = TextView(uiContext).apply {
        text = uiContext.getText(labelRes)
        textSize = 13f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setTextColor(COLOR_INK)
    }

    private fun settingsNote(labelRes: Int): View = TextView(uiContext).apply {
        text = uiContext.getText(labelRes)
        textSize = 12f
        setTextColor(COLOR_MUTED)
        setPadding(0, dp(5), 0, dp(3))
    }

    private fun settingsSeparator(): View = View(uiContext).apply {
        setBackgroundColor(COLOR_LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(18)
            bottomMargin = dp(18)
        }
    }

    private fun confirmClearData() {
        AlertDialog.Builder(uiContext)
            .setTitle(uiContext.getText(R.string.browser_clear_title))
            .setMessage(uiContext.getText(R.string.browser_clear_message))
            .setNegativeButton(uiContext.getText(R.string.browser_cancel), null)
            .setPositiveButton(uiContext.getText(R.string.browser_menu_clear)) { _, _ -> clearBrowsingData() }
            .show()
    }

    private fun clearBrowsingData() {
        val view = webView ?: return
        takeOverForHuman()
        cancelUserNavigation()
        pageManager?.all?.forEach { page ->
            page.content.stopLoading()
            page.content.clearHistory()
        }
        writeHistoryEntries(emptyList())
        view.clearCache(true)
        view.clearFormData()
        view.clearSslPreferences()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        val selectionClear = runCatching { selectionStore.execute(
            BrowserSelectionStore.ACTION_CLEAR,
            lifecycleController.snapshot().sessionGeneration,
            SystemClock.elapsedRealtime(),
        ) }.getOrNull()
        val artifactCleanup = runCatching {
            BrowserArtifactFiles.removeForCleanup(filesDir, System.currentTimeMillis()) { it.length() }
        }
        if (selectionClear?.optString("code") != "OK" || artifactCleanup.isFailure) {
            toast(R.string.browser_advanced_error)
        }
        view.loadUrl("about:blank")
        addressField?.setText("")
        updateNavigationButtons()
    }

    private fun confirmClose() {
        AlertDialog.Builder(uiContext)
            .setTitle(uiContext.getText(R.string.browser_close_title))
            .setMessage(uiContext.getText(R.string.browser_close_message))
            .setNegativeButton(uiContext.getText(R.string.browser_cancel), null)
            .setPositiveButton(uiContext.getText(R.string.browser_close_confirm)) { _, _ ->
                releaseSession(finishActivity = true, reason = BrowserCloseReason.USER_CLOSE)
            }
            .show()
    }

    private fun navigateFromAddress() {
        val raw = addressField?.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) {
            toast(R.string.browser_invalid_address)
            return
        }
        val uri = toWebUri(raw)
        if (uri == null) {
            toast(R.string.browser_invalid_address)
            return
        }
        val view = webView ?: return
        requestHumanAction {
            if (webView !== view || !isSupportedWebUri(uri)) return@requestHumanAction
            beginUserNavigation(view, uri.toString())
            addressField?.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(addressField?.windowToken, 0)
            view.loadUrl(uri.toString())
        }
    }

    private fun pollSelectionReceipt(id: String, session: Long, pollGeneration: Long, deadline: Long) {
        if (closed || pollGeneration != selectionReceiptPollGeneration ||
            lifecycleController.snapshot().sessionGeneration != session) return
        val receipt = selectionStore.receipt(id, session)
        if (receipt != null) {
            toast(when (receipt.optString("deliveryCode")) {
                "DELIVERED" -> R.string.browser_selection_queued
                "DELIVERED_TEXT_ONLY" -> R.string.browser_selection_queued_text_only
                "UNKNOWN" -> R.string.browser_selection_delivery_unknown
                else -> R.string.browser_selection_saved_not_sent
            })
            return
        }
        if (SystemClock.elapsedRealtime() >= deadline) {
            toast(R.string.browser_selection_receipt_pending)
            return
        }
        mainHandler.postDelayed({ pollSelectionReceipt(id, session, pollGeneration, deadline) }, 300L)
    }

    private fun openAddressExternally() {
        val raw = addressField?.text?.toString()?.trim().orEmpty().ifBlank { webView?.url.orEmpty() }
        val uri = toWebUri(raw)
        if (uri == null || !isSupportedWebUri(uri) || uri.host.isNullOrBlank()) {
            toast(R.string.browser_invalid_address)
            return
        }
        requestHumanAction {
            if (closed || !isSupportedWebUri(uri) || uri.host.isNullOrBlank()) return@requestHumanAction
            dismissMenu()
            val viewIntent = Intent(Intent.ACTION_VIEW, uri)
            try {
                startActivity(Intent.createChooser(viewIntent, uiContext.getText(R.string.browser_external_title)))
            } catch (_: android.content.ActivityNotFoundException) {
                toast(R.string.browser_blocked_scheme)
            } catch (_: RuntimeException) {
                toast(R.string.browser_blocked_scheme)
            }
        }
    }

    private fun toWebUri(input: String): Uri? {
        val candidate = when {
            input.contains("://") -> input
            input.contains('.') && !input.any(Char::isWhitespace) -> "https://$input"
            else -> searchUrl(input)
        }
        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        return uri.takeIf(::isSupportedWebUri)
    }

    private fun searchUrl(query: String): String {
        val encoded = Uri.encode(query)
        return when (preferences.getString(KEY_SEARCH_ENGINE, DEFAULT_SEARCH_ENGINE)) {
            SEARCH_GOOGLE -> "https://www.google.com/search?q=$encoded"
            SEARCH_BAIDU -> "https://www.baidu.com/s?wd=$encoded"
            else -> "https://www.bing.com/search?q=$encoded"
        }
    }

    private fun isSupportedWebUri(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "about") return uri.toString() == "about:blank"
        return (scheme == "http" || scheme == "https") &&
                !uri.host.isNullOrBlank() && uri.encodedUserInfo == null
    }

    private fun navigateAction(
        expectedUrl: String?,
        beforeNavigation: () -> Unit = {},
        action: (WebView) -> Unit,
    ) {
        val view = webView ?: return
        if (expectedUrl != null) {
            val uri = toWebUri(expectedUrl)
            if (uri == null || !isSupportedWebUri(uri)) return
        }
        requestHumanAction {
            if (closed || webView !== view) return@requestHumanAction
            beforeNavigation()
            beginUserNavigation(view, expectedUrl)
            action(view)
            updateNavigationButtons()
        }
    }

    private fun historyTargetUrl(view: WebView, offset: Int): String? {
        val list = view.copyBackForwardList()
        val targetIndex = list.currentIndex + offset
        return if (targetIndex in 0 until list.size) list.getItemAtIndex(targetIndex).url else null
    }

    private fun beginUserNavigation(view: WebView?, expectedUrl: String?) {
        val target = view ?: return
        val snapshot = lifecycleController.snapshot()
        if (snapshot.lifecycle != BrowserLifecycle.ACTIVE) return
        val previous = navigationTracker.current()
        if (previous != null) lifecycleController.completeOperation(previous.token, effective = false)
        val now = SystemClock.elapsedRealtime()
        val token = lifecycleController.beginOperation(
            operationId = "manual-${operationIds.incrementAndGet()}",
            context = lifecycleController.snapshot().commandContext(),
            requestedDeadlineAtMillis = now + NAVIGATION_DEADLINE_MILLIS,
        ) ?: run {
            navigationTracker.clear(previous)
            return
        }
        navigationTracker.begin(target, token, expectedUrl)
        pageStateTracker.begin(target, expectedUrl, target.url)
        publishStatus(lifecycleController.snapshot())
    }

    private fun verifyUserNavigationFinished(view: WebView, callbackUrl: String?) {
        val attempt = navigationTracker.current() ?: return
        val url = callbackUrl ?: return
        if (attempt.target !== view || attempt.startedUrl != url || view.url != url) return
        view.evaluateJavascript("document.readyState") { encoded ->
            val readyState = runCatching { org.json.JSONTokener(encoded).nextValue() as? String }.getOrNull()
            val snapshot = lifecycleController.snapshot()
            val stillCurrentToken = snapshot.lifecycle == BrowserLifecycle.ACTIVE &&
                snapshot.inFlightOperationId == attempt.token.operationId &&
                snapshot.sessionGeneration == attempt.token.sessionGeneration &&
                snapshot.controlGeneration == attempt.token.controlGeneration &&
                snapshot.operationGeneration == attempt.token.operationGeneration
            if (stillCurrentToken && navigationTracker.mayComplete(
                    attempt, view, callbackUrl, view.url, view.progress, readyState,
                ) && isActivePage(view)
            ) {
                completeUserNavigation(attempt, success = true)
            }
        }
    }

    private fun failUserNavigationIfCurrent(view: WebView, callbackUrl: String): Boolean {
        val attempt = navigationTracker.current() ?: return false
        if (!navigationTracker.mayFail(attempt, view, callbackUrl, view.url) || !isActivePage(view)) return false
        return completeUserNavigation(attempt, success = false)
    }

    private fun completeUserNavigation(
        attempt: BrowserNavigationTracker.Attempt<WebView, BrowserOperationToken>,
        success: Boolean,
    ): Boolean {
        if (!navigationTracker.clear(attempt)) return false
        val completed = lifecycleController.completeOperation(attempt.token, effective = success)
        publishStatus(lifecycleController.snapshot())
        scheduleIdleAlarm(lifecycleController.snapshot())
        return completed
    }

    private fun cancelUserNavigation() {
        val attempt = navigationTracker.current() ?: return
        navigationTracker.clear(attempt)
        lifecycleController.completeOperation(attempt.token, effective = false)
        publishStatus(lifecycleController.snapshot())
        scheduleIdleAlarm(lifecycleController.snapshot())
    }

    private fun clearStaleNavigationAttempt(snapshot: BrowserSnapshot) {
        val attempt = navigationTracker.current() ?: return
        if (snapshot.inFlightOperationId != attempt.token.operationId ||
            snapshot.operationGeneration != attempt.token.operationGeneration ||
            snapshot.sessionGeneration != attempt.token.sessionGeneration ||
            snapshot.controlGeneration != attempt.token.controlGeneration
        ) navigationTracker.clear(attempt)
    }

    private fun recordEffectiveActivity() {
        var snapshot = lifecycleController.snapshot()
        if (snapshot.lifecycle == BrowserLifecycle.ACTIVE && snapshot.owner == BrowserOwner.AI) {
            snapshot = transferToHuman()
            publishStatus(snapshot)
        }
        if (snapshot.lifecycle == BrowserLifecycle.ACTIVE) {
            lifecycleController.recordEffectiveActivity(snapshot.commandContext())
            val current = lifecycleController.snapshot()
            publishStatus(current)
            scheduleIdleAlarm(current)
        }
    }

    private fun takeOverForHuman() {
        val snapshot = lifecycleController.snapshot()
        if (snapshot.lifecycle == BrowserLifecycle.ACTIVE && snapshot.owner == BrowserOwner.AI) {
            publishStatus(transferToHuman())
        }
    }

    private fun transferToHuman(): BrowserSnapshot {
        revokeAllUploadGrants()
        actionGate.invalidate()
        advancedController?.revoke()
        val before = lifecycleController.snapshot()
        commandExecutor?.updateAuthorizationState(before, enabled = false)
        val after = lifecycleController.takeOver()
        commandExecutor?.updateAuthorizationState(
            after,
            enabled = preferences.getBoolean(KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED),
        )
        return after
    }

    private fun updateNavigationButtons() {
        val view = webView
        backButton?.apply {
            isEnabled = view?.canGoBack() == true
            alpha = if (isEnabled) 1f else 0.38f
        }
        forwardButton?.apply {
            isEnabled = view?.canGoForward() == true
            alpha = if (isEnabled) 1f else 0.38f
        }
        reloadButton?.apply {
            isEnabled = view != null
            alpha = if (isEnabled) 1f else 0.38f
        }
    }

    private fun collapseToHome() {
        releaseBrowserInput()
        cancelElementPicker()
        dismissMenu()
        settingsDialog?.dismiss()
        returnToMainWithoutTaskAnimation()
    }

    private fun openDshPage() {
        releaseBrowserInput()
        cancelElementPicker()
        dismissMenu()
        returnToMainWithoutTaskAnimation(openDshPage = true)
    }

    private fun returnToMainWithoutTaskAnimation(openDshPage: Boolean = false): Boolean {
        val component = returnComponent
            ?.takeIf { it.packageName == packageName && componentIsAvailable(it) }
            ?: ComponentName(packageName, "com.dshbox.app.MainActivity")
        try {
            val intent = if (openDshPage) BrowserDshPageRoute.createIntent(this) else Intent().setComponent(component).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
            startActivity(intent)
            applySoftTransition()
            return true
        } catch (_: RuntimeException) {
            toast(R.string.browser_return_home_unavailable)
            return false
        }
    }

    private fun readReturnComponent(source: Intent?): ComponentName? {
        if (source == null) return null
        val component = if (Build.VERSION.SDK_INT >= 33) {
            source.getParcelableExtra(BrowserContract.EXTRA_RETURN_COMPONENT, ComponentName::class.java)
        } else {
            @Suppress("DEPRECATION")
            source.getParcelableExtra(BrowserContract.EXTRA_RETURN_COMPONENT)
        }
        return component?.takeIf { it.packageName == packageName && componentIsAvailable(it) }
    }

    private fun componentIsAvailable(component: ComponentName): Boolean = runCatching {
        packageManager.getActivityInfo(component, 0).enabled
    }.getOrDefault(false)

    private fun registerLifecycleReceiver() {
        val filter = IntentFilter().apply {
            addAction(BrowserContract.ACTION_QUERY_STATUS)
            addAction(BrowserContract.ACTION_REVOKE_CONTROL)
            addAction(BrowserContract.ACTION_UI_SETTINGS)
            addAction(ACTION_IDLE_CHECK)
        }
        val permission = BrowserContract.internalPermission(packageName)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(lifecycleReceiver, filter, permission, mainHandler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(lifecycleReceiver, filter, permission, mainHandler)
        }
        receiverRegistered = true
    }

    private fun unregisterLifecycleReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(lifecycleReceiver) }
        receiverRegistered = false
    }

    private fun checkAndPublishStatus() {
        if (closed) {
            publishStatus(lifecycleController.snapshot())
            return
        }
        val snapshot = lifecycleController.expireIfNeeded()
        if (snapshot.lifecycle == BrowserLifecycle.CLOSING) {
            releaseSession(finishActivity = true)
        } else {
            publishStatus(snapshot)
            scheduleIdleAlarm(snapshot)
        }
    }

    private fun publishStatus(snapshot: BrowserSnapshot) {
        if (!closed && !webViewInitializationStarted) return
        val pickerContext = pickerProvenance
        if (pickerOverlay != null && pickerContext != null &&
            (snapshot.lifecycle != BrowserLifecycle.ACTIVE ||
                snapshot.sessionGeneration != pickerContext.optLong("sessionGeneration", -1L) ||
                snapshot.controlGeneration != pickerContext.optLong("controlGeneration", -1L))
        ) {
            cancelElementPicker()
        }
        val automationEnabled = preferences.getBoolean(KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)
        actionGate.update(snapshot, automationEnabled)
        commandExecutor?.updateAuthorizationState(snapshot, automationEnabled)
        val aiIndicatorEligible = snapshot.lifecycle == BrowserLifecycle.ACTIVE &&
            snapshot.owner == BrowserOwner.AI && automationEnabled
        (aiIndicator as? BrowserAiIndicatorView)?.setControlState(
            aiIndicatorEligible,
            aiIndicatorEligible && snapshot.inFlightOperationId != null,
        )
        statusPublisher.publish(snapshot, openingRequestId)
    }

    private fun scheduleLifecycleMonitor() {
        mainHandler.removeCallbacks(lifecycleMonitor)
        if (!closed && lifecycleController.snapshot().lifecycle == BrowserLifecycle.ACTIVE) {
            mainHandler.postDelayed(lifecycleMonitor, MONITOR_INTERVAL_MILLIS)
        }
    }

    private fun scheduleIdleAlarm(snapshot: BrowserSnapshot) {
        if (snapshot.lifecycle != BrowserLifecycle.ACTIVE || snapshot.visible) {
            cancelIdleAlarm()
            return
        }
        val deadline = BrowserIdlePolicy(snapshot.idleTimeoutMillis).closeDeadline(snapshot)
        if (deadline == null) {
            cancelIdleAlarm()
            return
        }
        if (scheduledAlarmSession == snapshot.sessionGeneration && scheduledAlarmDeadline == deadline) return
        cancelIdleAlarm()
        val alarm = getSystemService(AlarmManager::class.java) ?: return
        alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, idleAlarmPendingIntent(snapshot.sessionGeneration))
        scheduledAlarmSession = snapshot.sessionGeneration
        scheduledAlarmDeadline = deadline
    }

    private fun cancelIdleAlarm() {
        val previousSession = scheduledAlarmSession
        scheduledAlarmSession = null
        scheduledAlarmDeadline = null
        if (previousSession == null) return
        val alarm = getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = idleAlarmPendingIntent(previousSession)
        alarm.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun idleAlarmPendingIntent(sessionGeneration: Long): PendingIntent {
        val intent = Intent(ACTION_IDLE_CHECK)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
            .putExtra(EXTRA_ALARM_SESSION, sessionGeneration)
        return PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @Synchronized
    private fun ensureDownloadsManager(): BrowserDownloadsManager? {
        if (closed) return null
        return downloadsManager ?: BrowserDownloadsManager.getInstance(this).also { manager ->
            downloadsManager = manager
            downloadsSubscription = manager.addListener {
                mainHandler.post {
                    if (!closed && downloadsPanel != null) {
                        mainHandler.removeCallbacks(downloadsRefresh)
                        mainHandler.postDelayed(downloadsRefresh, 200L)
                    }
                }
            }
        }
    }

    private fun showDownloadsPanel() {
        if (closed) return
        val text: (Int) -> String = { uiContext.getString(it) }
        if (downloadsPanel == null) downloadsPanel = BrowserDownloadsPanel(
            this,
            BrowserDownloadsPanel.Labels(
                text(R.string.browser_menu_downloads), text(R.string.browser_close_confirm),
                text(R.string.browser_download_loading), text(R.string.browser_download_empty),
                text(R.string.browser_download_more), text(R.string.browser_download_queued),
                text(R.string.browser_download_active), text(R.string.browser_download_cancelling),
                text(R.string.browser_download_completed), text(R.string.browser_download_failed),
                text(R.string.browser_download_cancelled), text(R.string.browser_cancel),
                text(R.string.browser_download_save), text(R.string.browser_download_share),
                text(R.string.browser_download_delete),
                text(R.string.browser_download_unknown), { code -> "${text(R.string.browser_download_failed)} ($code)" },
            ),
            onCancel = { id ->
                requestHumanAction {
                    runCatching { downloadsExecutor.execute { downloadsManager?.cancel(id) } }
                }
            },
            onSave = { id -> requestHumanAction { beginDownloadSave(id) } },
            onShare = { id -> requestHumanAction { shareDownload(id) } },
            onDelete = { id -> requestHumanAction { confirmDownloadDelete(id) } },
            onLoadMore = ::refreshDownloadsPanel,
        )
        downloadsPanel?.show(downloadsPage, true)
        refreshDownloadsPanel(null)
    }

    private fun refreshDownloadsPanel(cursor: String?) {
        val generation = ++downloadsRequestGeneration
        runCatching {
            downloadsExecutor.execute {
                val page = runCatching { ensureDownloadsManager()?.list(20, cursor) }.getOrNull()
                mainHandler.post {
                    if (closed || generation != downloadsRequestGeneration) return@post
                    if (page == null) {
                        downloadsPanel?.update(downloadsPage, false)
                        toast(R.string.browser_download_failed)
                    } else {
                        downloadsPage = if (cursor == null) page else page.copy(
                            items = (downloadsPage.items + page.items).distinctBy { it.id }.take(500),
                        )
                        downloadsPanel?.update(downloadsPage)
                    }
                }
            }
        }.onFailure { downloadsPanel?.update(downloadsPage, false) }
    }

    private fun completedDownload(id: String): BrowserDownloadItem? = runCatching {
        downloadsManager?.find(id)?.takeIf {
            it.state == BrowserDownloadState.COMPLETED && it.fileName != null
        }
    }.getOrNull()

    private fun beginDownloadSave(id: String) {
        val item = completedDownload(id) ?: return toast(R.string.browser_download_unavailable)
        val metadata = runCatching { downloadsManager?.completedMetadata(id) }.getOrNull()
        if (metadata == null || metadata.size <= 0L) return toast(R.string.browser_download_unavailable)
        val fileName = metadata.displayName.ifBlank { item.fileName.orEmpty() }
        pendingDownloadSave = PendingDownloadSave(id, fileName)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(item.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream")
            .putExtra(Intent.EXTRA_TITLE, fileName)
        runCatching { startActivityForResult(intent, REQUEST_SAVE_DOWNLOAD) }.onFailure {
            pendingDownloadSave = null
            toast(R.string.browser_download_save_failed)
        }
    }

    private fun copyDownloadToDocument(pending: PendingDownloadSave, destination: Uri) {
        val manager = downloadsManager ?: return toast(R.string.browser_download_unavailable)
        try {
            downloadsExecutor.execute {
                val copied = runCatching {
                    manager.openCompletedChannel(pending.id).use { channel ->
                        contentResolver.openOutputStream(destination, "wt")?.use { output ->
                            Channels.newInputStream(channel).copyTo(output)
                        } ?: error("cannot write export")
                    }
                    true
                }.getOrDefault(false)
                mainHandler.post {
                    if (closed || isFinishing || isDestroyed) return@post
                    toast(if (copied) R.string.browser_download_save_done else R.string.browser_download_save_failed)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            toast(R.string.browser_download_save_failed)
        }
    }

    private fun shareDownload(id: String) {
        val item = completedDownload(id) ?: return toast(R.string.browser_download_unavailable)
        if (runCatching { downloadsManager?.completedMetadata(id) }.getOrNull() == null) {
            return toast(R.string.browser_download_unavailable)
        }
        val uri = BrowserDownloadProvider.uriFor(this, id)
        val mimeType = item.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val share = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newUri(contentResolver, item.fileName ?: "download", uri) }
        runCatching { startActivity(Intent.createChooser(share, uiContext.getText(R.string.browser_download_share))) }
            .onFailure { toast(R.string.browser_download_share_failed) }
    }

    private fun confirmDownloadDelete(id: String) {
        val item = completedDownload(id) ?: return toast(R.string.browser_download_unavailable)
        AlertDialog.Builder(uiContext)
            .setTitle(R.string.browser_download_delete_confirm_title)
            .setMessage(uiContext.getString(R.string.browser_download_delete_confirm_message, item.fileName.orEmpty()))
            .setNegativeButton(R.string.browser_cancel, null)
            .setPositiveButton(R.string.browser_download_delete) { _, _ -> deleteDownload(id) }
            .show()
    }

    private fun deleteDownload(id: String) {
        try {
            downloadsExecutor.execute {
                val deleted = runCatching { downloadsManager?.deleteCompleted(id) }.getOrNull() ==
                    BrowserDownloadDeleteResult.DELETED
                mainHandler.post {
                    if (closed || isFinishing || isDestroyed) return@post
                    toast(if (deleted) R.string.browser_download_delete_done else R.string.browser_download_delete_failed)
                    refreshDownloadsPanel(null)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            toast(R.string.browser_download_delete_failed)
        }
    }

    private fun downloadJson(item: BrowserDownloadItem): JSONObject = JSONObject()
        .put("id", item.id).put("state", item.state.name).put("fileName", item.fileName ?: JSONObject.NULL)
        .put("bytesReceived", item.bytesReceived).put("totalBytes", item.totalBytes ?: JSONObject.NULL)
        .put("mimeType", item.mimeType ?: JSONObject.NULL).put("artifactPath", item.artifactPath ?: JSONObject.NULL)
        .put("errorCode", item.errorCode ?: JSONObject.NULL).put("updatedAtMillis", item.updatedAtMillis)

    private fun executeUploadCommand(
        request: BrowserCommandRequest,
        identity: BrowserTaskPageIdentity,
        token: BrowserOperationToken,
        authorized: () -> Boolean,
        callback: (JSONObject) -> Unit,
    ) {
        val taskId = request.taskId ?: run { callback(uploadResult("INVALID_ARGUMENT", false)); return }
        when (request.args.optString("action")) {
            "request" -> {
                try {
                    uploadIo.execute {
                        val sourceResult = inspectUploadSources(request.args)
                        mainHandler.post {
                            if (!authorized() || commandExecutor?.currentUploadPageIdentity() != identity) {
                                callback(uploadResult("SESSION_REVOKED", false))
                                return@post
                            }
                            val sources = sourceResult.getOrNull()
                            if (sources == null) {
                                callback(uploadResult(sourceResult.exceptionOrNull()?.message?.takeIf { it in UPLOAD_ERROR_CODES } ?: "UPLOAD_SOURCE_DENIED", false))
                                return@post
                            }
                            val origin = uploadOrigin(webView?.url)
                            if (origin == null) {
                                callback(uploadResult("UPLOAD_PAGE_UNAVAILABLE", false))
                                return@post
                            }
                            val types = request.args.optJSONArray("acceptTypes")?.let { array ->
                                (0 until array.length()).map { array.getString(it).lowercase(Locale.ROOT) }
                            }.orEmpty()
                            val grant = uploadGrantStore.create(
                                taskId = taskId,
                                identity = identity,
                                origin = origin,
                                summary = request.args.optString("summary"),
                                acceptTypes = types,
                                multiple = request.args.optBoolean("multiple", false),
                                sources = sources,
                                nowMillis = SystemClock.elapsedRealtime(),
                            )
                            if (grant == null) {
                                callback(uploadResult("UPLOAD_BUSY", false))
                                return@post
                            }
                            val total = sources.sumOf { it.size }
                            val response = uploadResult("OK", true, JSONObject()
                                .put("state", "awaiting_approval")
                                .put("approvalId", grant.id)
                                .put("pageId", identity.pageId)
                                .put("documentGeneration", identity.documentGeneration)
                                .put("fileCount", sources.size)
                                .put("bytesTotal", total)
                                .put("expiresInMs", BrowserUploadPolicy.GRANT_TTL_MILLIS))
                            requestUploadApprovalPresentation(grant.id, identity, authorized, response, callback)
                        }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    callback(uploadResult("UPLOAD_BUSY", false))
                }
            }
            "list", "status" -> {
                val approvalId = request.args.getString("approvalId")
                val grant = uploadGrantStore.list(approvalId, taskId, identity, SystemClock.elapsedRealtime())
                if (grant == null) {
                    callback(uploadResult("UPLOAD_GRANT_NOT_FOUND", false))
                    return
                }
                val data = JSONObject().put("approvalId", grant.id)
                    .put("state", grant.state.wireName)
                    .put("expiresInMs", (grant.expiresAtMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                if (request.args.optString("action") == "list" && grant.state in setOf(
                        BrowserUploadGrantState.READY, BrowserUploadGrantState.CHOOSER_PENDING,
                    )
                ) {
                    data.put("files", JSONArray().apply {
                        grant.staged.forEach { file -> put(JSONObject()
                            .put("fileId", file.fileId).put("name", file.name).put("mimeType", file.mimeType)
                            .put("size", file.size)) }
                    })
                }
                callback(uploadResult("OK", false, data))
            }
            "cancel" -> {
                val approvalId = request.args.getString("approvalId")
                val grant = uploadGrantStore.cancel(approvalId, taskId, identity, SystemClock.elapsedRealtime())
                if (grant == null || grant.state != BrowserUploadGrantState.CANCELLED) {
                    callback(uploadResult("UPLOAD_GRANT_NOT_CANCELLABLE", false))
                    return
                }
                revokeUploadGrant(approvalId)
                callback(uploadResult("OK", true, JSONObject().put("approvalId", approvalId).put("state", "cancelled")))
            }
            "submit" -> submitUploadChooser(request, identity, taskId, callback)
            "activate" -> activateApprovedUploadTarget(request, identity, taskId, token, authorized, callback)
            else -> callback(uploadResult("INVALID_ARGUMENT", false))
        }
    }

    private fun activateApprovedUploadTarget(
        request: BrowserCommandRequest,
        identity: BrowserTaskPageIdentity,
        taskId: String,
        token: BrowserOperationToken,
        authorized: () -> Boolean,
        callback: (JSONObject) -> Unit,
    ) {
        val grantId = request.args.optString("approvalId")
        val grant = uploadGrantStore.get(grantId, SystemClock.elapsedRealtime())
        val view = webView
        val node = commandExecutor?.uploadNodeReference(request.args.optString("nodeId"))
        if (view != null && uploadActivationIsolation.isUnresolved(view)) {
            callback(uploadResult("UPLOAD_ACTIVATION_UNRESOLVED", false))
            return
        }
        if (grant == null || grant.state != BrowserUploadGrantState.READY || grant.id != grantId || grant.taskId != taskId ||
            grant.identity != identity ||
            !authorized() || view == null || !isActivePage(view) || !view.isAttachedToWindow ||
            !isVisualActionSurfaceAvailable() || node == null
        ) {
            callback(uploadResult(if (grant == null) "UPLOAD_GRANT_NOT_FOUND" else "UPLOAD_ACTIVATION_REJECTED", false))
            return
        }
        if (pendingUploadActivation != null) {
            callback(uploadResult("UPLOAD_ACTIVATION_BUSY", false))
            return
        }
        val nonce = UUID.randomUUID().toString().replace("-", "")
        if (!actionGate.register(nonce, token)) {
            callback(uploadResult("SESSION_REVOKED", false))
            return
        }
        val startWidth = view.width
        val startHeight = view.height
        val startScale = view.scale
        val startRotation = windowManager.defaultDisplay.rotation
        if (startWidth <= 0 || startHeight <= 0 || !startScale.isFinite() || startScale <= 0f) {
            callback(uploadResult("UPLOAD_TARGET_REJECTED", false))
            return
        }
        fun surfaceUnchanged(): Boolean = view === webView && isActivePage(view) && view.isAttachedToWindow &&
            view.width == startWidth && view.height == startHeight && kotlin.math.abs(view.scale - startScale) < 0.001f &&
            windowManager.defaultDisplay.rotation == startRotation && isVisualActionSurfaceAvailable()
        view.evaluateJavascript(BrowserDomScript.uploadActivationTarget(node.first, node.second, nonce)) { encoded ->
            val target = encoded?.let { runCatching { org.json.JSONTokener(it).nextValue() as? String }.getOrNull() }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (target?.optString("code") != "READY") {
                callback(uploadResult(target?.optString("code") ?: "UPLOAD_TARGET_UNAVAILABLE", false))
                return@evaluateJavascript
            }
            val currentGrant = uploadGrantStore.get(grantId, SystemClock.elapsedRealtime())
            val accept = target.optString("accept").split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct().take(20)
            val multiple = target.optBoolean("multiple")
            val cssViewportWidth = startWidth / startScale
            val cssViewportHeight = startHeight / startScale
            val viewportWidth = target.optDouble("viewportWidth", Double.NaN)
            val viewportHeight = target.optDouble("viewportHeight", Double.NaN)
            if (!viewportWidth.isFinite() || !viewportHeight.isFinite() ||
                kotlin.math.abs(viewportWidth - cssViewportWidth) > 1.5 ||
                kotlin.math.abs(viewportHeight - cssViewportHeight) > 1.5 ||
                currentGrant?.state != BrowserUploadGrantState.READY || currentGrant.taskId != taskId || currentGrant.identity != identity ||
                currentGrant.multiple != multiple || currentGrant.sources.none { source ->
                    BrowserUploadPolicy.accepts(source.mimeType, source.name, currentGrant.acceptTypes) &&
                        BrowserUploadPolicy.accepts(source.mimeType, source.name, accept)
                } || !authorized() || !surfaceUnchanged()
            ) {
                callback(uploadResult("UPLOAD_GRANT_MISMATCH", false))
                return@evaluateJavascript
            }
            val x = target.optDouble("x", Double.NaN) * view.scale
            val y = target.optDouble("y", Double.NaN) * view.scale
            val targetWidth = target.optDouble("width", Double.NaN)
            val targetHeight = target.optDouble("height", Double.NaN)
            if (!x.isFinite() || !y.isFinite() || !targetWidth.isFinite() || !targetHeight.isFinite() ||
                targetWidth <= 0 || targetHeight <= 0 || x < 0 || y < 0 || x >= view.width || y >= view.height
            ) {
                callback(uploadResult("UPLOAD_TARGET_REJECTED", false))
                return@evaluateJavascript
            }
            if (pendingUploadActivation != null) {
                callback(uploadResult("UPLOAD_ACTIVATION_BUSY", false))
                return@evaluateJavascript
            }
            lateinit var timeout: Runnable
            timeout = Runnable {
                val pending = pendingUploadActivation?.takeIf { it.grantId == grantId && it.callback === callback }
                if (pending != null) {
                    pendingUploadActivation = null
                    uploadActivationIsolation.markUnresolved(view, identity)
                    completeUploadActivation(pending, uploadResult("UPLOAD_ACTIVATION_UNRESOLVED", true))
                }
            }
            val activation = PendingUploadActivation(grantId, taskId, identity, view, callback, timeout)
            pendingUploadActivation = activation
            activation.dispatched = true
            val dispatched = BrowserVisualSurfaceTap.dispatch(view, x, y) {
                authorized() && commandExecutor?.currentUploadPageIdentity() == identity &&
                    surfaceUnchanged() && uploadGrantStore.get(grantId, SystemClock.elapsedRealtime())?.let {
                        it.state == BrowserUploadGrantState.READY && it.taskId == taskId && it.identity == identity
                    } == true
            }
            if (dispatched != "OK") {
                if (!activation.completed) {
                    if (pendingUploadActivation === activation) pendingUploadActivation = null
                    val unresolved = dispatched == "UNKNOWN"
                    if (unresolved) uploadActivationIsolation.markUnresolved(view, identity)
                    completeUploadActivation(
                        activation,
                        uploadResult(if (unresolved) "UPLOAD_ACTIVATION_UNRESOLVED" else "UPLOAD_TARGET_REJECTED", if (unresolved) true else false),
                    )
                }
            } else if (!activation.completed && pendingUploadActivation === activation) {
                mainHandler.postDelayed(timeout, 1200L)
            }
        }
    }

    private fun completeUploadActivation(activation: PendingUploadActivation, result: JSONObject) {
        if (activation.completed) return
        activation.completed = true
        activation.callback(result)
    }

    private fun inspectUploadSources(args: JSONObject): Result<List<BrowserUploadSource>> = runCatching {
        val canonicalRoot = uploadFilesRoot
        Log.i(TAG, "Browser upload trusted root alias=${filesDir.absoluteFile.toPath().normalize().toFile() != canonicalRoot}")
        val workspace = File(canonicalRoot, "user-data")
        val paths = args.getJSONArray("paths")
        require(paths.length() in 1..BrowserUploadPolicy.MAX_FILES) { "INVALID_ARGUMENT" }
        val relativePaths = (0 until paths.length()).map { paths.getString(it) }
        require(relativePaths.distinct().size == relativePaths.size) { "INVALID_ARGUMENT" }
        val types = args.optJSONArray("acceptTypes")?.let { array ->
            (0 until array.length()).map { array.getString(it).lowercase(Locale.ROOT) }
        }.orEmpty()
        val sources = relativePaths.map { path ->
            val source = BrowserUploadPolicy.inspect(workspace, path) { stage ->
                Log.w(TAG, "Browser upload source inspection denied stage=${stage.logTag}")
            } ?: error("UPLOAD_SOURCE_DENIED")
            require(BrowserUploadPolicy.accepts(source.mimeType, source.name, types)) { "UPLOAD_ACCEPT_MISMATCH" }
            source
        }
        require(sources.sumOf { it.size } <= BrowserUploadPolicy.MAX_TOTAL_BYTES) { "UPLOAD_SIZE_LIMIT" }
        sources
    }

    private fun uploadOrigin(rawUrl: String?): String? = runCatching {
        val uri = java.net.URI(rawUrl ?: return null)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        "${uri.scheme}://${uri.host}${if (uri.port >= 0) ":${uri.port}" else ""}".take(512)
    }.getOrNull()

    private fun interceptApprovedUploadChooser(
        view: WebView,
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
        requiredGrantId: String? = null,
        requiredTaskId: String? = null,
    ): Boolean {
        val identity = commandExecutor?.currentUploadPageIdentity() ?: return false
        val grant = if (requiredGrantId != null) uploadGrantStore.get(requiredGrantId, SystemClock.elapsedRealtime())
            ?.takeIf { it.state == BrowserUploadGrantState.READY && it.identity == identity && it.taskId == requiredTaskId }
        else uploadGrantStore.waitingForPage(identity, SystemClock.elapsedRealtime())
            ?: uploadGrantStore.readyForPage(identity, SystemClock.elapsedRealtime())
        if (grant == null) {
            return false
        }
        pendingUploadChooser?.let { old -> runCatching { old.callback.onReceiveValue(null) } }
        val actualTypes = params.acceptTypes.orEmpty().map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct().take(20)
        val multiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        if (requiredGrantId != null && grant.multiple != multiple) {
            callback.onReceiveValue(null)
            revokeUploadGrant(grant.id)
            return true
        }
        if (grant.sources.none {
                BrowserUploadPolicy.accepts(it.mimeType, it.name, grant.acceptTypes) &&
                    BrowserUploadPolicy.accepts(it.mimeType, it.name, actualTypes)
            }
        ) {
            callback.onReceiveValue(null)
            revokeUploadGrant(grant.id)
            toast(R.string.browser_upload_approval_expired)
            return true
        }
        if (grant.state == BrowserUploadGrantState.READY &&
            uploadGrantStore.attachChooser(grant.id, identity, actualTypes, multiple, SystemClock.elapsedRealtime()) == null
        ) {
            callback.onReceiveValue(null)
            revokeUploadGrant(grant.id)
            return true
        }
        pendingUploadChooser = PendingUploadChooser(callback, view, grant.id, grant.taskId, identity, actualTypes, multiple)
        return true
    }

    private fun submitUploadChooser(
        request: BrowserCommandRequest,
        identity: BrowserTaskPageIdentity,
        taskId: String,
        callback: (JSONObject) -> Unit,
    ) {
        val approvalId = request.args.getString("approvalId")
        val pending = pendingUploadChooser?.takeIf {
            it.grantId == approvalId && it.taskId == taskId && it.identity == identity && it.view === webView && isActivePage(it.view)
        }
        if (pending == null) {
            callback(uploadResult("UPLOAD_CHOOSER_NOT_PENDING", false))
            return
        }
        val fileIds = request.args.getJSONArray("fileIds").let { (0 until it.length()).map(it::getString) }
        val grant = uploadGrantStore.get(approvalId, SystemClock.elapsedRealtime())
        if (grant == null || grant.state != BrowserUploadGrantState.CHOOSER_PENDING || grant.actualAcceptTypes != pending.acceptTypes ||
            grant.chooserMultiple != pending.multiple || (!pending.multiple && fileIds.size != 1) ||
            grant.taskId != taskId || grant.identity != identity ||
            fileIds.any { id -> grant.staged.firstOrNull { it.fileId == id }?.let { file ->
                !BrowserUploadPolicy.accepts(file.mimeType, file.name, grant.acceptTypes) ||
                    !BrowserUploadPolicy.accepts(file.mimeType, file.name, grant.actualAcceptTypes)
            } != false }
        ) {
            callback(uploadResult("UPLOAD_GRANT_MISMATCH", false))
            return
        }
        val selected = uploadGrantStore.consume(approvalId, taskId, identity, fileIds, SystemClock.elapsedRealtime())
        if (selected == null) {
            callback(uploadResult("UPLOAD_FILE_NOT_ALLOWED", false))
            return
        }
        val uriToken = UUID.randomUUID().toString().replace("-", "")
        val uriMap = selected.associateBy { it.fileId }
        val uris = try {
            BrowserUploadProvider.register(this, uriToken, uriMap,
                System.currentTimeMillis() + BrowserUploadPolicy.URI_TTL_MILLIS)
        } catch (_: Exception) {
            revokeUploadGrant(approvalId)
            callback(uploadResult("UPLOAD_PROVIDER_FAILED", false))
            return
        }
        pendingUploadChooser = null
        try {
            pending.callback.onReceiveValue(uris.toTypedArray())
            uploadUriTokensByGrant.getOrPut(approvalId) { linkedSetOf() }.add(uriToken)
            callback(uploadResult("OK", true, JSONObject()
                .put("approvalId", approvalId).put("state", "chooser_delivered")
                .put("fileCount", uris.size).put("websiteUploadVerified", false)))
            scheduleUploadUriExpiry(uriToken)
        } catch (_: Exception) {
            revokeUploadUri(uriToken)
            callback(uploadResult("UNKNOWN", null, JSONObject().put("approvalId", approvalId)
                .put("state", "delivery_unconfirmed").put("websiteUploadVerified", false)))
        }
    }

    private fun uploadResult(code: String, executed: Boolean?, data: JSONObject = JSONObject()) = JSONObject()
        .put("code", code).put("executed", executed ?: JSONObject.NULL).put("data", data)

    private fun requestUploadApprovalPresentation(
        grantId: String,
        identity: BrowserTaskPageIdentity,
        authorized: () -> Boolean,
        response: JSONObject,
        callback: (JSONObject) -> Unit,
    ) {
        if (!authorized() || !isUploadIdentityCurrent(identity)) {
            revokeUploadGrant(grantId)
            callback(uploadResult("SESSION_REVOKED", false))
            return
        }
        val grant = uploadGrantStore.get(grantId, SystemClock.elapsedRealtime())
        val snapshot = lifecycleController.snapshot()
        if (grant == null || grant.identity != identity || grant.state != BrowserUploadGrantState.WAITING_USER ||
            snapshot.lifecycle != BrowserLifecycle.ACTIVE || snapshot.owner != BrowserOwner.AI ||
            !preferences.getBoolean(KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)
        ) {
            revokeUploadGrant(grantId)
            callback(uploadResult("UPLOAD_APPROVAL_UNAVAILABLE", false))
            return
        }
        when (BrowserUploadApprovalPresentationPolicy.select(snapshot.visible, hasWindowFocus())) {
            BrowserUploadApprovalPresentation.INLINE_DIALOG -> {
                pendingUploadApprovalReplies[grantId] = PendingUploadApprovalReply(response, callback, authorized, identity)
                showPendingUploadApproval(grantId)
                mainHandler.postDelayed({
                    if (pendingUploadApprovalReplies.containsKey(grantId)) {
                        failUploadApprovalPresentation(grantId, uploadApprovalPresentationFailureCode(grantId))
                    }
                }, UPLOAD_APPROVAL_PRESENTATION_TIMEOUT_MILLIS)
            }
            BrowserUploadApprovalPresentation.BACKGROUND_NOTICE -> {
                // Background request: prepare is complete and the grant stays WAITING_USER. The
                // user is informed without starting the activity; opening Browser later shows the
                // existing approval dialog through onNewIntent/onResume.
                if (!uploadApprovalNotice.show(grant)) {
                    revokeUploadGrant(grantId)
                    callback(uploadResult("UPLOAD_APPROVAL_UNAVAILABLE", false))
                    return
                }
                pendingUploadApprovalPresentationId = grantId
                callback(response)
            }
        }
    }

    private fun isPendingUploadApprovalAuthorized(reply: PendingUploadApprovalReply): Boolean =
        runCatching { reply.authorized() }.getOrDefault(false) && isUploadIdentityCurrent(reply.identity)

    private fun uploadApprovalPresentationFailureCode(grantId: String): String {
        val reply = pendingUploadApprovalReplies[grantId] ?: return "SESSION_REVOKED"
        val grant = uploadGrantStore.get(grantId, SystemClock.elapsedRealtime()) ?: return "SESSION_REVOKED"
        return if (grant.state == BrowserUploadGrantState.WAITING_USER && grant.identity == reply.identity &&
            isPendingUploadApprovalAuthorized(reply)
        ) "UPLOAD_APPROVAL_UNAVAILABLE" else "SESSION_REVOKED"
    }

    private fun failPendingUploadApprovalReplies(code: String) {
        pendingUploadApprovalReplies.keys.toList().forEach { failUploadApprovalPresentation(it, code) }
    }

    private fun failUploadApprovalPresentation(grantId: String, code: String) {
        if (pendingUploadApprovalPresentationId == grantId) pendingUploadApprovalPresentationId = null
        val reply = pendingUploadApprovalReplies.remove(grantId)
        revokeUploadGrant(grantId)
        reply?.callback(uploadResult(code, false))
    }

    private fun showPendingUploadApproval(expectedGrantId: String? = null) {
        if (closed || isFinishing || isDestroyed) {
            if (expectedGrantId != null) failUploadApprovalPresentation(expectedGrantId, "SESSION_REVOKED")
            return
        }
        if (!hasWindowFocus() || !lifecycleController.snapshot().visible ||
            uploadApprovalDialog?.isShowing == true || advancedDialog?.isShowing == true || historyDialog?.isShowing == true ||
            bookmarksDialog?.isShowing == true || pagesDialog?.isShowing == true || settingsDialog?.isShowing == true
        ) return
        val identity = commandExecutor?.currentUploadPageIdentity() ?: run {
            if (expectedGrantId != null) failUploadApprovalPresentation(expectedGrantId, "SESSION_REVOKED")
            return
        }
        val snapshot = lifecycleController.snapshot()
        if (snapshot.lifecycle != BrowserLifecycle.ACTIVE || snapshot.owner != BrowserOwner.AI ||
            !preferences.getBoolean(KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)
        ) {
            if (expectedGrantId != null) failUploadApprovalPresentation(expectedGrantId, "SESSION_REVOKED")
            return
        }
        val grant = if (expectedGrantId == null) uploadGrantStore.activeWaitingApproval(SystemClock.elapsedRealtime())
            else uploadGrantStore.get(expectedGrantId, SystemClock.elapsedRealtime())
        val pendingReply = grant?.let { pendingUploadApprovalReplies[it.id] }
        if (expectedGrantId != null && pendingReply == null) {
            failUploadApprovalPresentation(expectedGrantId, "SESSION_REVOKED")
            return
        }
        if (grant != null && pendingReply != null &&
            (pendingReply.identity != grant.identity || !isPendingUploadApprovalAuthorized(pendingReply))
        ) {
            failUploadApprovalPresentation(grant.id, "SESSION_REVOKED")
            return
        }
        if (grant == null || grant.identity != identity || grant.state != BrowserUploadGrantState.WAITING_USER ||
            !isUploadIdentityCurrent(grant.identity)
        ) {
            if (expectedGrantId != null) failUploadApprovalPresentation(expectedGrantId, "SESSION_REVOKED")
            return
        }
        if (!lifecycleController.snapshot().visible || !hasWindowFocus()) return
        val fileLines = grant.sources.joinToString("\n") { source ->
            "${source.relativePath} · ${formatUploadBytes(source.size)} · ${source.mimeType}"
        }
        val message = listOf(grant.summary.takeIf { it.isNotBlank() }, fileLines).filterNotNull().joinToString("\n\n")
        val dialog = AlertDialog.Builder(uiContext)
            .setTitle(R.string.browser_upload_approval_title)
            .setMessage(uiContext.getString(R.string.browser_upload_approval_message, grant.origin, message))
            .setPositiveButton(R.string.browser_upload_approve) { _, _ -> approveUploadGrant(grant.id) }
            .setNegativeButton(R.string.browser_upload_deny) { _, _ -> denyUploadGrant(grant.id) }
            .create()
        dialog.setOnCancelListener { denyUploadGrant(grant.id) }
        uploadApprovalDialog = dialog
        uploadApprovalGrantId = grant.id
        var dialogFocusObserver: BrowserUploadApprovalFocusObserver? = null
        dialog.setOnDismissListener {
            dialogFocusObserver?.close()
            if (uploadApprovalFocusObserver === dialogFocusObserver) uploadApprovalFocusObserver = null
            if (uploadApprovalDialog === dialog) uploadApprovalDialog = null
            if (uploadApprovalGrantId == grant.id) uploadApprovalGrantId = null
        }
        runCatching { dialog.show() }.onFailure { uploadApprovalDialog = null }
        if (dialog.isShowing) {
            uploadApprovalNotice.cancel()
            val decor = dialog.window?.decorView
            if (decor == null) {
                failUploadApprovalPresentation(grant.id, uploadApprovalPresentationFailureCode(grant.id))
                return
            }
            uploadApprovalFocusObserver?.close()
            val focusObserver = BrowserUploadApprovalFocusObserver(decor) {
                completeUploadApprovalPresentation(dialog, grant, pendingReply)
            }
            dialogFocusObserver = focusObserver
            uploadApprovalFocusObserver = focusObserver
            focusObserver.requestCheck()
        } else if (expectedGrantId != null) {
            failUploadApprovalPresentation(expectedGrantId, uploadApprovalPresentationFailureCode(expectedGrantId))
        }
    }

    private fun completeUploadApprovalPresentation(
        dialog: AlertDialog,
        grant: BrowserUploadGrant,
        pendingReply: PendingUploadApprovalReply?,
    ): Boolean {
        val dialogFocused = dialog.window?.decorView?.hasWindowFocus() == true
        val snapshot = lifecycleController.snapshot()
        if (!BrowserUploadApprovalFocusPolicy.canAcknowledge(
                activityVisible = snapshot.visible && !isFinishing && !isDestroyed,
                currentDialog = uploadApprovalDialog === dialog,
                dialogShowing = dialog.isShowing,
                dialogWindowFocused = dialogFocused,
            )
        ) return false
        val latestGrant = uploadGrantStore.get(grant.id, SystemClock.elapsedRealtime())
        val replyStillAuthorized = pendingReply == null ||
            pendingUploadApprovalReplies[grant.id] === pendingReply &&
            pendingReply.identity == grant.identity && isPendingUploadApprovalAuthorized(pendingReply)
        if (latestGrant == null || latestGrant.state != BrowserUploadGrantState.WAITING_USER ||
            latestGrant.identity != grant.identity || snapshot.lifecycle != BrowserLifecycle.ACTIVE ||
            snapshot.owner != BrowserOwner.AI || !snapshot.visible || !preferences.getBoolean(
                KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED,
            ) || !isUploadIdentityCurrent(grant.identity) || !replyStillAuthorized
        ) {
            dialog.dismiss()
            failUploadApprovalPresentation(grant.id, "SESSION_REVOKED")
            return true
        }
        if (pendingUploadApprovalPresentationId == grant.id) pendingUploadApprovalPresentationId = null
        pendingReply?.let { reply ->
            if (pendingUploadApprovalReplies.remove(grant.id) === reply) reply.callback(reply.response)
        }
        return true
    }

    private fun approveUploadGrant(grantId: String) {
        val now = SystemClock.elapsedRealtime()
        val grant = uploadGrantStore.get(grantId, now)
            ?.takeIf { it.state == BrowserUploadGrantState.WAITING_USER } ?: run { revokeUploadGrant(grantId); return }
        if (!isUploadIdentityCurrent(grant.identity)) {
            denyUploadGrant(grantId)
            toast(R.string.browser_upload_approval_expired)
            return
        }
        val coordinator = BrowserTaskCoordinatorRegistry.get()
        if (coordinator == null) {
            denyUploadGrant(grantId)
            return
        }
        coordinator.validateUploadTask(grant.taskId, grant.identity) { taskBound ->
            if (!taskBound || !isUploadIdentityCurrent(grant.identity) || SystemClock.elapsedRealtime() >= grant.expiresAtMillis) {
                denyUploadGrant(grantId)
                toast(R.string.browser_upload_approval_expired)
                return@validateUploadTask
            }
            try {
                uploadIo.execute {
                    val result = runCatching { uploadStager.stage(grantId, grant.sources) }
                    mainHandler.post {
                        val staged = result.getOrNull()
                        if (staged == null || !isUploadIdentityCurrent(grant.identity)) {
                            revokeUploadGrant(grantId)
                            toast(R.string.browser_upload_approval_expired)
                            return@post
                        }
                        val approved = uploadGrantStore.approve(grantId, staged, SystemClock.elapsedRealtime())
                        if (approved == null) {
                            revokeUploadGrant(grantId)
                            toast(R.string.browser_upload_approval_expired)
                            return@post
                        }
                        uploadApprovalNotice.cancel()
                        val pending = pendingUploadChooser?.takeIf { it.grantId == grantId }
                        if (pending != null && uploadGrantStore.attachChooser(
                                grantId, grant.identity, pending.acceptTypes, pending.multiple, SystemClock.elapsedRealtime(),
                            ) == null
                        ) {
                            cancelPendingUploadChooser()
                            revokeUploadGrant(grantId)
                            toast(R.string.browser_upload_approval_expired)
                            return@post
                        }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                denyUploadGrant(grantId)
            }
        }
    }

    private fun denyUploadGrant(grantId: String) {
        uploadGrantStore.deny(grantId, BrowserUploadGrantState.DENIED, SystemClock.elapsedRealtime())
        revokeUploadGrant(grantId)
    }

    private fun isUploadIdentityCurrent(identity: BrowserTaskPageIdentity): Boolean {
        val snapshot = lifecycleController.snapshot()
        val currentView = webView ?: return false
        return !closed && snapshot.lifecycle == BrowserLifecycle.ACTIVE && snapshot.owner == BrowserOwner.AI &&
            preferences.getBoolean(KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED) &&
            commandExecutor?.currentUploadPageIdentity() == identity && isActivePage(currentView)
    }

    private fun revokeUploadGrantsForIdentity(identity: BrowserTaskPageIdentity) {
        val grants = uploadGrantStore.revokeIdentity(identity, SystemClock.elapsedRealtime())
        grants.forEach { revokeUploadGrant(it.id) }
        if (pendingUploadChooser?.identity == identity) cancelPendingUploadChooser()
    }

    private fun revokeUploadGrantsForTask(taskId: String) {
        uploadGrantStore.revokeTask(taskId, SystemClock.elapsedRealtime()).forEach { revokeUploadGrant(it.id) }
    }

    private fun revokeAllUploadGrants() {
        uploadApprovalDialog?.dismiss()
        uploadApprovalDialog = null
        uploadApprovalGrantId = null
        uploadGrantStore.revokeAll(SystemClock.elapsedRealtime()).forEach { revokeUploadGrant(it.id) }
        cancelPendingUploadChooser()
    }

    private fun sweepUploadGrants() {
        uploadGrantStore.prune(SystemClock.elapsedRealtime()).forEach { revokeUploadGrant(it.id) }
        val snapshot = lifecycleController.snapshot()
        if (snapshot.lifecycle != BrowserLifecycle.ACTIVE || snapshot.owner != BrowserOwner.AI ||
            !preferences.getBoolean(KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)
        ) {
            uploadGrantStore.revokeAll(SystemClock.elapsedRealtime()).forEach { revokeUploadGrant(it.id) }
        } else {
            uploadGrantStore.revokeExcept(commandExecutor?.currentUploadPageIdentity(), SystemClock.elapsedRealtime())
                .forEach { revokeUploadGrant(it.id) }
        }
        runCatching { uploadIo.execute { runCatching { uploadStager.cleanupExpired() } } }
    }

    private fun revokeUploadGrant(grantId: String) {
        uploadGrantStore.deny(grantId, BrowserUploadGrantState.CANCELLED, SystemClock.elapsedRealtime())
        uploadApprovalNotice.cancel()
        pendingUploadActivation?.takeIf { it.grantId == grantId }?.let { activation ->
            mainHandler.removeCallbacks(activation.timeout)
            pendingUploadActivation = null
            if (activation.dispatched) uploadActivationIsolation.markUnresolved(activation.view, activation.identity)
            completeUploadActivation(activation, uploadResult("UPLOAD_ACTIVATION_UNRESOLVED", activation.dispatched))
        }
        if (uploadApprovalGrantId == grantId) {
            uploadApprovalGrantId = null
            uploadApprovalDialog?.dismiss()
            uploadApprovalDialog = null
        }
        uploadUriTokensByGrant.remove(grantId).orEmpty().forEach(::revokeUploadUri)
        val chooser = pendingUploadChooser
        if (chooser?.grantId == grantId) cancelPendingUploadChooser()
        runCatching { uploadIo.execute { runCatching { uploadStager.revoke(grantId) } } }
    }

    private fun cancelPendingUploadChooser() {
        val pending = pendingUploadChooser
        pendingUploadChooser = null
        runCatching { pending?.callback?.onReceiveValue(null) }
    }

    private fun revokeUploadUri(token: String) {
        BrowserUploadProvider.revoke(token)
        uploadUriTokensByGrant.entries.toList().forEach { (grantId, tokens) ->
            tokens.remove(token)
            if (tokens.isEmpty()) uploadUriTokensByGrant.remove(grantId)
        }
    }

    private fun scheduleUploadUriExpiry(token: String) {
        mainHandler.postDelayed({ revokeUploadUri(token) }, BrowserUploadPolicy.URI_TTL_MILLIS)
    }

    private fun formatUploadBytes(bytes: Long): String = if (bytes >= 1024 * 1024) {
        String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
    } else if (bytes >= 1024) {
        String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    } else "$bytes B"

    private fun executeFileCommand(
        action: String, args: JSONObject, deadline: Long, authorized: () -> Boolean, callback: (JSONObject) -> Unit,
    ) {
        val url = args.optString("url")
        val cookie = if (action == "start") runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull() else null
        val userAgent = webView?.settings?.userAgentString
        fun result(code: String, executed: Boolean, data: JSONObject = JSONObject()) =
            JSONObject().put("code", code).put("executed", executed).put("data", data)
        try {
            downloadsExecutor.execute {
                if (closed || !authorized() || SystemClock.elapsedRealtime() >= deadline) {
                    callback(result("SESSION_REVOKED", false))
                    return@execute
                }
                val reply = runCatching {
                    val manager = ensureDownloadsManager() ?: return@runCatching result("CLOSED", false)
                    if (action == "list") {
                        val page = manager.list(args.optInt("limit", 20), args.optString("beforeId").takeIf { it.isNotBlank() })
                        result("OK", false, JSONObject().put("items", JSONArray().apply { page.items.forEach { put(downloadJson(it)) } })
                            .put("nextCursor", page.nextCursor ?: JSONObject.NULL).put("truncated", page.truncated))
                    } else {
                        var cancelAccepted = false
                        val item = when (action) {
                            "start" -> manager.start(BrowserDownloadRequest(url, args.optString("suggestedFileName").takeIf { it.isNotBlank() }, cookie, userAgent = userAgent))
                            "cancel" -> { cancelAccepted = manager.cancel(args.getString("id")); manager.find(args.getString("id")) }
                            else -> manager.find(args.getString("id"))
                        }
                        if (item == null) result("NOT_FOUND", false)
                        else if (action == "cancel" && !cancelAccepted) result("NOT_CANCELLABLE", false, JSONObject().put("item", downloadJson(item)))
                        else result(if (action == "start" && item.state == BrowserDownloadState.FAILED) item.errorCode ?: "DOWNLOAD_FAILED" else "OK",
                            action == "start" && item.state != BrowserDownloadState.FAILED || action == "cancel",
                            JSONObject().put("item", downloadJson(item)))
                    }
                }.getOrElse { result("DOWNLOAD_FAILED", false) }
                callback(reply)
            }
        } catch (_: Exception) { callback(result("BUSY", false)) }
    }

    private fun cancelPendingFileChooser() {
        val callback = pendingFileChooser
        pendingFileChooser = null
        pendingFileChooserWebView = null
        pendingFileChooserRequestCode = null
        runCatching { callback?.onReceiveValue(null) }
    }

    @Suppress("DEPRECATION")
    private fun launchSystemFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams) {
        cancelPendingFileChooser()
        revokeAllUploadGrants()
        takeOverForHuman()
        pendingFileChooser = callback
        pendingFileChooserWebView = view
        val code = FILE_CHOOSER_REQUEST_IDS.incrementAndGet().toInt()
        if (code > 65535) { cancelPendingFileChooser(); return }
        pendingFileChooserRequestCode = code
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val types = params.acceptTypes.filter { it.contains('/') && !it.contains(';') }.take(20)
        if (types.isNotEmpty()) picker.putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
        runCatching { startActivityForResult(picker, code) }.onFailure { cancelPendingFileChooser() }
    }

    private fun releaseSession(
        finishActivity: Boolean,
        reason: BrowserCloseReason = BrowserCloseReason.UNKNOWN,
    ) {
        if (closed) return
        val returnBeforeFinish = finishActivity && transitionForeground && !isFinishing
        releaseBrowserInput()
        synchronized(this) { closed = true }
        uploadApprovalFocusObserver?.close()
        uploadApprovalFocusObserver = null
        failPendingUploadApprovalReplies("SESSION_REVOKED")
        mainHandler.removeCallbacks(webViewInitialization)
        chromeDrawListener?.let { listener ->
            window.decorView.viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(listener)
        }
        chromeDrawListener = null
        mainHandler.removeCallbacks(downloadsRefresh)
        cancelPendingFileChooser()
        downloadsPanel?.dismiss()
        downloadsSubscription?.close()
        downloadsSubscription = null
        downloadsManager?.close()
        downloadsManager = null
        downloadsExecutor.shutdownNow()
        uploadIo.shutdown()
        closeLifecycle(reason)
        mainHandler.removeCallbacks(lifecycleMonitor)
        cancelIdleAlarm()
        cancelUserNavigation()
        if (returnBeforeFinish) returnToMainWithoutTaskAnimation()
        destroyWebView()
        finishLifecycleClose()
        closed = true
        publishStatus(lifecycleController.snapshot())
        if (finishActivity && !isFinishing) {
            finish()
            applySoftTransition()
        }
    }

    @Suppress("DEPRECATION")
    private fun applySoftTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }

    /**
     * 浏览器全屏（[BROWSER_IMMERSIVE] 开关）：API 30+ 用平台 insets 控制器只隐藏
     * 状态栏（保留系统导航栏/手势条），隐藏后仍可顶部下滑临时唤出。
     *
     * insetsController 必须在窗口挂载后（setContentView 之后）才可靠，提前获取可能
     * 返回 null——因此 onCreate 末尾调用一次，onResume 再断言一次自愈。
     * API 29 不经过这里：走 onCreate 的 legacy systemUiVisibility 分支（窗口属性，
     * 挂载前设置同样生效）。
     */
    private fun applyBrowserImmersive() {
        if (!BROWSER_IMMERSIVE || Build.VERSION.SDK_INT < 30) return
        window.insetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.statusBars())
        }
    }

    private fun closeLifecycle(reason: BrowserCloseReason) {
        actionGate.invalidate()
        val current = lifecycleController.snapshot()
        advancedController?.revoke()
        commandExecutor?.updateAuthorizationState(current, enabled = false)
        val closing = if (current.lifecycle == BrowserLifecycle.ACTIVE) lifecycleController.beginClose(reason) else current
        actionGate.update(closing, automationEnabled = false)
    }

    private fun finishLifecycleClose() {
        if (lifecycleController.snapshot().lifecycle == BrowserLifecycle.CLOSING) {
            lifecycleController.finishClose()
        }
    }

    private fun destroyWebView() {
        commandExecutor?.detachActionGate()
        val pages = pageManager?.clear().orEmpty()
        val current = webView
        pageManager = null
        webView = null
        pages.forEach { page ->
            runCatching { destroyPageWebView(page.content) }
        }
        if (current != null && pages.none { it.content === current }) runCatching { destroyPageWebView(current) }
        pageContainer?.removeAllViews()
    }

    private fun iconButton(iconRes: Int, contentDescriptionRes: Int, action: (View) -> Unit) =
        ImageButton(uiContext).apply {
            setImageResource(iconRes)
            setColorFilter(COLOR_INK)
            contentDescription = uiContext.getString(contentDescriptionRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isFocusable = true
            isClickable = true
            background = selectableBackground()
            setOnClickListener(action)
        }

    private fun iconView(iconRes: Int, contentDescriptionRes: Int, size: Int) =
        ImageView(uiContext).apply {
            setImageResource(iconRes)
            setColorFilter(COLOR_MUTED)
            contentDescription = uiContext.getString(contentDescriptionRes)
            layoutParams = LinearLayout.LayoutParams(size, size)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

    private fun selectableBackground(): android.graphics.drawable.Drawable? {
        val value = android.util.TypedValue()
        uiContext.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return uiContext.getDrawable(value.resourceId)
    }

    private fun horizontalDivider(): View = View(uiContext).apply {
        setBackgroundColor(COLOR_LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun verticalDivider(): View = View(uiContext).apply {
        setBackgroundColor(COLOR_LINE)
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(22)).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        }
    }

    private fun roundedBackground(fill: Int, stroke: Int, radius: Int) =
        BrowserThemedGradientDrawable(fill, stroke, radius.toFloat(), dp(1))

    private fun toast(resourceId: Int) {
        Toast.makeText(this, uiContext.getText(resourceId), Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * uiContext.resources.displayMetrics.density).toInt()

    private class SimpleTextWatcher(private val onChanged: (String) -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            onChanged(s?.toString().orEmpty())
        }
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }

    companion object {
        private const val UPLOAD_APPROVAL_PRESENTATION_TIMEOUT_MILLIS = 5_000L
        private const val TAG = "BrowserActivity"
        private val UPLOAD_ERROR_CODES = setOf("UPLOAD_SOURCE_DENIED", "UPLOAD_SIZE_LIMIT", "UPLOAD_ACCEPT_MISMATCH")
        /**
         * 浏览器全屏总开关：置 false 一行回退到「纸色状态栏可见」的原有外观。
         * 覆盖两条路径——API 30+ 的 insetsController（applyBrowserImmersive）与
         * API 29 的 legacy systemUiVisibility 标志位（onCreate 窗口配置分支）。
         */
        private const val BROWSER_IMMERSIVE = true
        private const val KEY_IDLE_MINUTES = BrowserRuntimeSettings.KEY_IDLE_MINUTES
        private const val KEY_AI_ENABLED = BrowserRuntimeSettings.KEY_AI_ENABLED
        private const val KEY_HISTORY_ENTRIES = "history_entries"
        private const val KEY_SEARCH_ENGINE = BrowserRuntimeSettings.KEY_SEARCH_ENGINE
        private const val KEY_NEXT_SESSION_GENERATION = BrowserRuntimeSettings.KEY_NEXT_SESSION_GENERATION
        private const val SESSION_GENERATION_RESERVATION = BrowserRuntimeSettings.SESSION_GENERATION_RESERVATION
        private const val MAX_SAFE_GENERATION = BrowserRuntimeSettings.MAX_SAFE_GENERATION
        private const val DEFAULT_IDLE_MINUTES = BrowserRuntimeSettings.DEFAULT_IDLE_MINUTES
        private const val DEFAULT_SEARCH_ENGINE = BrowserRuntimeSettings.DEFAULT_SEARCH_ENGINE
        private const val SEARCH_BING = BrowserRuntimeSettings.SEARCH_BING
        private const val SEARCH_GOOGLE = BrowserRuntimeSettings.SEARCH_GOOGLE
        private const val SEARCH_BAIDU = BrowserRuntimeSettings.SEARCH_BAIDU
        private const val MINUTE_MILLIS = 60_000L
        private const val NAVIGATION_DEADLINE_MILLIS = 30_000L
        private const val TOUCH_ACTIVITY_THROTTLE_MILLIS = 1_000L
        private const val MONITOR_INTERVAL_MILLIS = 1_000L
        private const val MAX_CAPTURE_PIXELS = 16_777_216L
        private const val PIXEL_COPY_TIMEOUT_MILLIS = 1_000L
        private const val MAX_HISTORY_ENTRIES = 200
        private const val MAX_PAGE_TITLE_CHARS = 256
        private const val MAX_PAGE_URL_CHARS = 2048
        private const val ADVANCED_UI_DEADLINE_MILLIS = 15_000L
        private const val MAX_HISTORY_TITLE_CHARS = 256
        private const val MAX_HISTORY_URL_CHARS = 2048
        private const val VISUAL_STATE_TIMEOUT_MILLIS = 1_500L
        private const val CAPTURE_SETTLE_MILLIS = 250L
        private const val CAPTURE_PREVIEW_DEADLINE_MILLIS = 3_000L
        private const val SELECTION_CAPTURE_DEADLINE_MILLIS = 3_000L
        private const val MAX_CONTROL_REQUEST_SUMMARY_LENGTH = 500
        private const val MAX_NETWORK_ARTIFACT_BYTES = 4L * 1024L * 1024L
        private const val MAX_NETWORK_BODY_PREVIEW_CHARS = 16_384
        private const val PICKER_BOUNDS_TOLERANCE_CSS_PX = 2f
        private const val REQUEST_EXPORT_HAR = 71
        private const val REQUEST_SAVE_DOWNLOAD = 72
        private val FILE_CHOOSER_REQUEST_IDS = AtomicLong(1000L)
        private const val ACTION_IDLE_CHECK = "com.dshbox.browser.runtime.action.IDLE_CHECK"
        private const val EXTRA_ALARM_SESSION = "browser_idle_session_generation"
        private const val ZOOM_VALUE_TAG = "browser_zoom_value"
        private const val MENU_FIND = 1
        private const val MENU_ZOOM = 2
        private const val MENU_HISTORY = 3
        private const val MENU_SETTINGS = 4
        private const val MENU_CLEAR = 5
        private const val MENU_CLOSE = 6
        private val IDLE_MINUTES = listOf(1, 3, 5, 10, 15)
    }

    private val COLOR_PAPER get() = runtimePalette.paper
    private val COLOR_INK get() = runtimePalette.ink
    private val COLOR_MUTED get() = runtimePalette.muted
    private val COLOR_SOFT get() = runtimePalette.soft
    private val COLOR_TOOLBAR get() = runtimePalette.toolbar
    private val COLOR_LINE get() = runtimePalette.line
    private val COLOR_STRONG_LINE get() = runtimePalette.strongLine
    private val COLOR_BLUE get() = runtimePalette.blue
    private val COLOR_PICKER_BLUE_SOFT get() = runtimePalette.pickerBlueSoft
}
