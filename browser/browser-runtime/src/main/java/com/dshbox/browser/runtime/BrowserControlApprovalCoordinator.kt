package com.dshbox.browser.runtime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.dshbox.browser.api.BrowserLifecycle
import com.dshbox.browser.api.BrowserOwner
import com.dshbox.browser.api.BrowserTaskPageIdentity
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Browser-owned authority and native presentation for a CLI request to enable AI control. */
internal class BrowserControlApprovalCoordinator(
    context: Context,
    private val hostSupplier: () -> Host?,
    private val store: BrowserControlApprovalStore = BrowserControlApprovalStore(),
    private val elapsedNow: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val wallNow: () -> Long = System::currentTimeMillis,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    internal interface Host {
        fun identity(): BrowserTaskPageIdentity?
        val aiEnabled: Boolean
        val owner: BrowserOwner
        val lifecycle: BrowserLifecycle
        val controlTransferPending: Boolean
        fun presentationContext(): Context?
        fun showForeground(summary: String, onDecision: (Boolean) -> Unit): Boolean
        fun dismissForeground(approvalId: String) {}
        fun grantAfterVerifiedApproval(): Boolean
    }

    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private var overlayWindow: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var overlayApprovalId: String? = null
    private var notificationApprovalId: String? = null
    private var presentedApprovalId: String? = null
    private var expiryRunnable: Runnable? = null
    private var identityRunnable: Runnable? = null

    fun execute(request: JSONObject): JSONObject = onMainBlocking {
        if (closed.get()) return@onMainBlocking response("SERVICE_CLOSED", JSONObject())
        when (request.optString("action")) {
            "request" -> requestApproval(request)
            "status" -> status(request)
            "cancel" -> cancel(request)
            else -> response("INVALID_ARGUMENT", JSONObject())
        }
    } ?: response("APPROVAL_SURFACE_UNAVAILABLE", JSONObject())

    /** Called when Browser session/page identity changes; any old approval is settled and dismissed. */
    fun onHostStateChanged() = runOnMain {
        refreshPendingState()
    }

    /** Called when the Activity that owns a foreground confirmation is closing. */
    fun onActivityClosed() = runOnMain {
        refreshExpiration()
        val approval = store.pending(elapsedNow())
        if (approval != null) finish(approval, BrowserControlApprovalStatus.CANCELLED)
        else presentedApprovalId?.let(::finishPresented)
    }

    /** Called when the owning Browser service is destroyed. */
    fun close() = runOnMain {
        if (!closed.compareAndSet(false, true)) return@runOnMain
        val id = presentedApprovalId
        store.close(elapsedNow())
        id?.let(::finishPresented)
        dismissAll()
    }

    internal fun onNativeDecision(approvalId: String, nonce: String, approved: Boolean) = runOnMain {
        refreshPendingState()
        val current = store.pending(elapsedNow())?.takeIf { it.id == approvalId } ?: return@runOnMain
        if (nonce != current.nonce) return@runOnMain
        if (!BrowserControlApprovalPolicy.mayGrant(current, nonce, hostState(), elapsedNow())) {
            finish(current, BrowserControlApprovalStatus.CANCELLED)
            return@runOnMain
        }
        if (!approved) {
            finish(current, BrowserControlApprovalStatus.DENIED)
            return@runOnMain
        }
        val granted = runCatching { hostSupplier()?.grantAfterVerifiedApproval() == true }.getOrDefault(false)
        finish(current, if (granted) BrowserControlApprovalStatus.APPROVED else BrowserControlApprovalStatus.CANCELLED)
    }

    private fun requestApproval(request: JSONObject): JSONObject {
        refreshPendingState()
        if (request.length() !in 2..3 || !request.has("summary") ||
            request.has("taskId") && request.opt("taskId") !is String ||
            request.keys().asSequence().any { it !in setOf("action", "summary", "taskId") }
        ) return response("INVALID_ARGUMENT", JSONObject())
        val summary = request.optString("summary")
        if (summary.trim().isEmpty() || summary.length > MAX_SUMMARY_LENGTH) return response("INVALID_ARGUMENT", JSONObject())
        val taskId = if (request.has("taskId")) request.optString("taskId").takeIf { it.isNotEmpty() } else null
        if (taskId != null && taskId.length > MAX_TASK_ID_LENGTH) return response("INVALID_ARGUMENT", JSONObject())
        val host = hostSupplier() ?: return response("NO_ACTIVE_SESSION", JSONObject())
        val state = hostState(host)
        BrowserControlApprovalPolicy.requestError(state)?.let { return response(it, JSONObject()) }
        val identity = state.identity ?: return response("NO_ACTIVE_SESSION", JSONObject())
        if (host.controlTransferPending && !store.hasPending()) return response("CONTROL_TRANSFER_PENDING", JSONObject())
        val id = UUID.randomUUID().toString().replace("-", "")
        val nonce = randomNonce()
        val created = store.start(id, nonce, summary, taskId, identity, elapsedNow(), wallNow())
        when (created) {
            BrowserControlApprovalStore.Start.PendingConflict -> return response("CONTROL_REQUEST_PENDING", JSONObject())
            is BrowserControlApprovalStore.Start.Coalesced -> return response("OK", data(created.approval, coalesced = true))
            is BrowserControlApprovalStore.Start.Created -> {
                val approval = created.approval
                if (host.controlTransferPending) {
                    store.settle(approval.id, approval.nonce, BrowserControlApprovalStatus.CANCELLED, elapsedNow())
                    return response("CONTROL_TRANSFER_PENDING", JSONObject())
                }
                val presented = tryPresent(host, approval)
                if (!presented) {
                    store.settle(approval.id, approval.nonce, BrowserControlApprovalStatus.CANCELLED, elapsedNow())
                    runCatching { host.dismissForeground(approval.id) }
                    dismissAll()
                    return response("APPROVAL_SURFACE_UNAVAILABLE", data(approval.copy(status = BrowserControlApprovalStatus.CANCELLED)))
                }
                scheduleExpiry(approval)
                scheduleIdentityChecks()
                return response("OK", data(approval))
            }
        }
    }

    private fun status(request: JSONObject): JSONObject {
        refreshPendingState()
        if (request.length() != 2 || !validId(request.optString("approvalId")) ||
            request.keys().asSequence().any { it !in setOf("action", "approvalId") }) return response("INVALID_ARGUMENT", JSONObject())
        var approval = store.get(request.getString("approvalId"), elapsedNow())
            ?: return response("APPROVAL_NOT_FOUND", JSONObject())
        cleanupPresentedIfTerminal(approval.id)
        if (approval.status == BrowserControlApprovalStatus.PENDING) {
            validateActive(approval)
            approval = store.get(approval.id, elapsedNow()) ?: return response("APPROVAL_NOT_FOUND", JSONObject())
        }
        return response("OK", data(approval))
    }

    private fun cancel(request: JSONObject): JSONObject {
        refreshPendingState()
        if (request.length() != 2 || !validId(request.optString("approvalId")) ||
            request.keys().asSequence().any { it !in setOf("action", "approvalId") }) return response("INVALID_ARGUMENT", JSONObject())
        val id = request.getString("approvalId")
        val active = store.pending(elapsedNow())?.takeIf { it.id == id }
        if (active != null) finish(active, BrowserControlApprovalStatus.CANCELLED)
        val approval = store.get(id, elapsedNow()) ?: return response("APPROVAL_NOT_FOUND", JSONObject())
        cleanupPresentedIfTerminal(id)
        BrowserControlApprovalPolicy.cancelError(active != null, approval.status)?.let { return response(it, JSONObject()) }
        return response("OK", data(approval))
    }

    private fun validateActive(approval: BrowserControlApproval) {
        if (!BrowserControlApprovalPolicy.mayGrant(approval, approval.nonce, hostState(), elapsedNow())) {
            finish(approval, BrowserControlApprovalStatus.CANCELLED)
        }
    }

    private fun tryPresent(host: Host, approval: BrowserControlApproval): Boolean {
        presentedApprovalId = approval.id
        var foreground = false
        try {
            foreground = host.showForeground(approval.summary) { approved ->
                onNativeDecision(approval.id, approval.nonce, approved)
            }
        } catch (_: RuntimeException) { }
        if (foreground) return true
        runCatching { host.dismissForeground(approval.id) }
        if (showOverlay(approval)) return true
        if (postNotification(approval)) return true
        if (presentedApprovalId == approval.id) presentedApprovalId = null
        return false
    }

    private fun showOverlay(approval: BrowserControlApproval): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !Settings.canDrawOverlays(appContext)) return false
        val manager = appContext.getSystemService(WindowManager::class.java) ?: return false
        val uiContext = hostSupplier()?.presentationContext() ?: appContext
        val palette = BrowserRuntimePalette.resolve(uiContext)
        val card = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            background = GradientDrawable().apply {
                setColor(palette.paper); cornerRadius = dp(18).toFloat(); setStroke(dp(1), palette.line)
            }
            elevation = dp(18).toFloat()
        }
        card.addView(TextView(uiContext).apply {
            text = uiContext.getString(com.dshbox.browser.runtime.R.string.browser_control_transfer_to_ai_title)
            setTextColor(palette.ink); textSize = 17f; setTypeface(null, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(uiContext).apply {
            text = approval.summary; setTextColor(palette.muted); textSize = 14f
            maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(8), 0, dp(12))
        })
        card.addView(LinearLayout(uiContext).apply {
            gravity = Gravity.END; orientation = LinearLayout.HORIZONTAL
            addView(Button(uiContext).apply {
                text = uiContext.getString(com.dshbox.browser.runtime.R.string.browser_cancel)
                setOnClickListener { onNativeDecision(approval.id, approval.nonce, false) }
            })
            addView(Button(uiContext).apply {
                text = uiContext.getString(com.dshbox.browser.runtime.R.string.browser_control_transfer_confirm)
                filterTouchesWhenObscured = true
                setOnClickListener { onNativeDecision(approval.id, approval.nonce, true) }
            })
        })
        return try {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(48) }
            manager.addView(card, params)
            overlayWindow = manager; overlayView = card; overlayApprovalId = approval.id
            presentedApprovalId = approval.id
            true
        } catch (_: RuntimeException) { false }
    }

    private fun postNotification(approval: BrowserControlApproval): Boolean {
        return try {
            val manager = appContext.getSystemService(NotificationManager::class.java) ?: return false
            if (!manager.areNotificationsEnabled()) return false
            if (Build.VERSION.SDK_INT >= 33 && appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) return false
            val existing = manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
            if (existing?.importance == NotificationManager.IMPORTANCE_NONE) return false
            val uiContext = hostSupplier()?.presentationContext() ?: appContext
            if (existing == null) manager.createNotificationChannel(NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                uiContext.getString(com.dshbox.browser.runtime.R.string.browser_control_transfer_to_ai_title),
                NotificationManager.IMPORTANCE_HIGH,
            ))
            val allow = decisionIntent(approval, approved = true)
            val deny = decisionIntent(approval, approved = false)
            val notification = Notification.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            notification.setSmallIcon(appContext.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
                .setContentTitle(uiContext.getString(com.dshbox.browser.runtime.R.string.browser_control_transfer_to_ai_title))
                .setContentText(approval.summary)
                .setStyle(Notification.BigTextStyle().bigText(approval.summary))
                .setTimeoutAfter(APPROVAL_TTL_MILLIS)
                .setDeleteIntent(deny)
                .setAutoCancel(false)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(Notification.Action.Builder(null,
                    uiContext.getString(com.dshbox.browser.runtime.R.string.browser_control_transfer_confirm), allow).build())
                .addAction(Notification.Action.Builder(null,
                    uiContext.getString(com.dshbox.browser.runtime.R.string.browser_cancel), deny).build())
            manager.notify(NOTIFICATION_ID, notification.build())
            notificationApprovalId = approval.id
            presentedApprovalId = approval.id
            true
        } catch (_: RuntimeException) { false }
    }

    private fun decisionIntent(approval: BrowserControlApproval, approved: Boolean): PendingIntent {
        val intent = Intent(appContext, BrowserControlApprovalReceiver::class.java)
            .setAction(if (approved) ACTION_ALLOW else ACTION_DENY)
            .setData(Uri.parse("dshbox://browser-control-approval/${approval.id}/${if (approved) "allow" else "deny"}"))
            .putExtra(EXTRA_APPROVAL_ID, approval.id)
            .putExtra(EXTRA_NONCE, approval.nonce)
        val flags = PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
        return PendingIntent.getBroadcast(appContext, approval.id.hashCode(), intent, flags)
    }

    private fun finish(approval: BrowserControlApproval, status: BrowserControlApprovalStatus) {
        val settled = store.settle(approval.id, approval.nonce, status, elapsedNow())
        if (settled != null) finishPresented(settled.id)
        else {
            val current = store.get(approval.id, elapsedNow())
            if (current == null || current.status != BrowserControlApprovalStatus.PENDING) finishPresented(approval.id)
        }
    }

    private fun finishPresented(approvalId: String) {
        if (presentedApprovalId == approvalId) presentedApprovalId = null
        runCatching { hostSupplier()?.dismissForeground(approvalId) }
        dismissOverlay(approvalId)
        dismissNotification(approvalId)
        cancelSchedules()
    }

    private fun dismissAll() {
        overlayApprovalId?.let(::dismissOverlay)
        notificationApprovalId?.let(::dismissNotification)
        runCatching { hostSupplier()?.let { host -> store.pending(elapsedNow())?.let { host.dismissForeground(it.id) } } }
        cancelSchedules()
    }

    private fun dismissOverlay(id: String) {
        if (overlayApprovalId != id) return
        val manager = overlayWindow; val view = overlayView
        overlayWindow = null; overlayView = null; overlayApprovalId = null
        if (manager != null && view != null) runCatching { manager.removeView(view) }
    }

    private fun dismissNotification(id: String) {
        if (notificationApprovalId != id) return
        notificationApprovalId = null
        appContext.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun scheduleExpiry(approval: BrowserControlApproval) {
        expiryRunnable?.let(handler::removeCallbacks)
        val runnable = Runnable {
            store.expireIfNeeded(elapsedNow())?.let { finishPresented(it.id) }
        }
        expiryRunnable = runnable
        handler.postDelayed(runnable, (approval.expiresAtElapsedMillis - elapsedNow()).coerceAtLeast(0))
    }

    private fun refreshExpiration() {
        store.expireIfNeeded(elapsedNow())?.let { finishPresented(it.id) }
        cleanupPresentedIfTerminal(presentedApprovalId)
    }

    private fun cleanupPresentedIfTerminal(id: String?) {
        if (id == null || presentedApprovalId != id) return
        val current = store.get(id, elapsedNow())
        if (current == null || current.status != BrowserControlApprovalStatus.PENDING) finishPresented(id)
    }

    private fun refreshPendingState() {
        refreshExpiration()
        val approval = store.pending(elapsedNow()) ?: run {
            cleanupPresentedIfTerminal(presentedApprovalId)
            return
        }
        if (!BrowserControlApprovalPolicy.mayGrant(approval, approval.nonce, hostState(), elapsedNow())) {
            finish(approval, BrowserControlApprovalStatus.CANCELLED)
        }
    }

    private fun scheduleIdentityChecks() {
        identityRunnable?.let(handler::removeCallbacks)
        val runnable = object : Runnable {
            override fun run() {
                refreshPendingState()
                val approval = store.pending(elapsedNow()) ?: return
                if (!BrowserControlApprovalPolicy.mayGrant(approval, approval.nonce, hostState(), elapsedNow())) {
                    finish(approval, BrowserControlApprovalStatus.CANCELLED)
                } else handler.postDelayed(this, IDENTITY_CHECK_INTERVAL_MILLIS)
            }
        }
        identityRunnable = runnable
        handler.postDelayed(runnable, IDENTITY_CHECK_INTERVAL_MILLIS)
    }

    private fun cancelSchedules() {
        expiryRunnable?.let(handler::removeCallbacks); expiryRunnable = null
        identityRunnable?.let(handler::removeCallbacks); identityRunnable = null
    }

    private fun hostState(host: Host? = hostSupplier()) = BrowserControlApprovalHostState(
        identity = host?.identity(), enabled = host?.aiEnabled == true,
        owner = host?.owner ?: BrowserOwner.NONE, lifecycle = host?.lifecycle ?: BrowserLifecycle.CLOSED,
    )

    private fun data(approval: BrowserControlApproval, coalesced: Boolean = false) = JSONObject()
        .put("approvalId", approval.id).put("status", approval.status.name)
        .put("summary", approval.summary).put("taskId", approval.taskId ?: JSONObject.NULL)
        .put("createdAtMillis", approval.createdAtMillis).put("expiresAtMillis", approval.expiresAtMillis)
        .put("coalesced", coalesced)

    private fun response(code: String, data: JSONObject) = JSONObject()
        .put("code", code).put("executed", false).put("data", data)

    private fun onMainBlocking(block: () -> JSONObject): JSONObject? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: JSONObject? = null
        val latch = CountDownLatch(1)
        if (!handler.post { try { result = block() } finally { latch.countDown() } }) return null
        return try { if (latch.await(MAIN_DISPATCH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) result else null }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); null }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density + 0.5f).toInt()

    private fun randomNonce(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun validId(value: String): Boolean = ID_PATTERN.matches(value)

    companion object {
        const val EXTRA_APPROVAL_ID = "browser_control_approval_id"
        const val EXTRA_NONCE = "browser_control_approval_nonce"
        const val ACTION_ALLOW = "com.dshbox.browser.runtime.CONTROL_APPROVAL_ALLOW"
        const val ACTION_DENY = "com.dshbox.browser.runtime.CONTROL_APPROVAL_DENY"
        const val NOTIFICATION_CHANNEL_ID = "browser_control_approval"
        private const val NOTIFICATION_ID = 0x425241
        private const val MAX_SUMMARY_LENGTH = 1000
        private const val MAX_TASK_ID_LENGTH = 64
        private const val APPROVAL_TTL_MILLIS = 120_000L
        private const val IDENTITY_CHECK_INTERVAL_MILLIS = 500L
        private const val MAIN_DISPATCH_TIMEOUT_MILLIS = 5000L
        private val ID_PATTERN = Regex("[a-fA-F0-9]{32}")
        private val random = SecureRandom()

    }
}

/** Process-scoped target for explicit immutable notification actions. */
internal object BrowserControlApprovalRegistry {
    private var processCoordinator = ProcessScopedInstance<BrowserControlApprovalCoordinator>()

    /** Keeps approval state alive across short-lived bound Service instances in this process. */
    @Synchronized
    fun getOrCreate(context: Context, hostSupplier: () -> BrowserControlApprovalCoordinator.Host?): BrowserControlApprovalCoordinator =
        processCoordinator.getOrCreate { BrowserControlApprovalCoordinator(context.applicationContext, hostSupplier) }

    @Synchronized
    fun get(): BrowserControlApprovalCoordinator? = processCoordinator.getOrNull()

    /** Explicit teardown hook for process owners; bound Service destruction must not call this. */
    @Synchronized
    fun clear(value: BrowserControlApprovalCoordinator) {
        if (processCoordinator.getOrNull() === value) {
            value.close()
            processCoordinator = ProcessScopedInstance()
        }
    }
    @Synchronized fun onActivityClosed() { processCoordinator.getOrNull()?.onActivityClosed() }
    @Synchronized fun onHostStateChanged() { processCoordinator.getOrNull()?.onHostStateChanged() }
    @Synchronized
    fun decide(id: String, nonce: String, approved: Boolean) {
        processCoordinator.getOrNull()?.onNativeDecision(id, nonce, approved)
    }
}

class BrowserControlApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BrowserControlApprovalCoordinator.ACTION_ALLOW &&
            intent.action != BrowserControlApprovalCoordinator.ACTION_DENY) return
        val id = intent.getStringExtra(BrowserControlApprovalCoordinator.EXTRA_APPROVAL_ID) ?: return
        val nonce = intent.getStringExtra(BrowserControlApprovalCoordinator.EXTRA_NONCE) ?: return
        val approved = intent.action == BrowserControlApprovalCoordinator.ACTION_ALLOW
        BrowserControlApprovalRegistry.decide(id, nonce, approved)
    }
}
