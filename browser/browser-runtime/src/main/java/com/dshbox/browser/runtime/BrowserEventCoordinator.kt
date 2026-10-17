package com.dshbox.browser.runtime

import android.os.Handler
import android.os.SystemClock
import org.json.JSONObject
import java.security.SecureRandom
import java.util.ArrayDeque

internal interface BrowserEventScheduler {
    fun postDelayed(runnable: Runnable, delayMillis: Long)
    fun removeCallbacks(runnable: Runnable)
}

internal class HandlerBrowserEventScheduler(private val handler: Handler) : BrowserEventScheduler {
    override fun postDelayed(runnable: Runnable, delayMillis: Long) { handler.postDelayed(runnable, delayMillis) }
    override fun removeCallbacks(runnable: Runnable) { handler.removeCallbacks(runnable) }
}

/** Bounded, process-local, action-scoped event subscriptions. All methods run on the main thread. */
internal class BrowserEventCoordinator(
    private val scheduler: BrowserEventScheduler,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    internal class OneShotClaim internal constructor(
        private val expiresAtElapsedMillis: Long, private val now: () -> Long,
    ) {
        private val lock = Any()
        private var valid = true
        fun isValid(): Boolean = synchronized(lock) { valid && now() < expiresAtElapsedMillis }
        fun revoke() = synchronized(lock) { valid = false }
        fun <T> executeIfValid(action: () -> T): T? = synchronized(lock) {
            if (!valid || now() >= expiresAtElapsedMillis) null else action()
        }
    }

    internal data class Identity(
        val sessionGeneration: Long,
        val controlGeneration: Long,
        val pageId: String,
        val documentGeneration: Long,
        val navigationId: Long,
    )

    internal data class Subscription(
        val id: String,
        internal var nonce: String,
        val taskId: String,
        internal var identity: Identity,
        val initialIdentity: Identity,
        val bindingEpoch: Long,
        val kinds: Set<String>,
        val expiresAt: Long,
        internal var actionRequestId: String? = null,
        internal var actionBound: Boolean = false,
        internal var navigationStarted: Boolean = false,
        internal val events: ArrayDeque<JSONObject> = ArrayDeque(),
        internal var waiter: ((JSONObject) -> Unit)? = null,
        internal var timeout: Runnable? = null,
        internal var expiry: Runnable? = null,
        internal var terminal: Boolean = false,
        internal val oneShotClaims: MutableMap<String, OneShotClaim> = HashMap(),
        internal val publishedOneShotKinds: MutableSet<String> = HashSet(),
    )
    private data class DialogEntry(
        val subscription: Subscription,
        var resolver: ((String, String?) -> Boolean)?,
    )

    sealed interface ArmResult {
        data class Created(val subscription: Subscription) : ArmResult
        data object Full : ArmResult
    }

    private val random = SecureRandom()
    private val subscriptions = LinkedHashMap<String, Subscription>()
    private val dialogEvents = HashMap<String, DialogEntry>()
    private val uncertainActionRequests = LinkedHashSet<String>()

    fun arm(taskId: String, identity: Identity, bindingEpoch: Long, kinds: Set<String>, ttlMillis: Long): ArmResult {
        prune()
        if (subscriptions.size >= MAX_SUBSCRIPTIONS) return ArmResult.Full
        val subscription = Subscription(
            id = randomHex(16), nonce = randomHex(32), taskId = taskId, identity = identity,
            initialIdentity = identity, bindingEpoch = bindingEpoch,
            kinds = kinds.toSet(), expiresAt = now() + ttlMillis,
        )
        subscriptions[subscription.id] = subscription
        val expiry = Runnable { expire(subscription.id) }
        subscription.expiry = expiry
        scheduler.postDelayed(expiry, ttlMillis)
        return ArmResult.Created(subscription)
    }

    fun bindAction(
        id: String, nonce: String, taskId: String, identity: Identity, actionRequestId: String,
        supportedKinds: Set<String>,
    ): Subscription? {
        prune()
        val item = subscriptions[id] ?: return null
        if (item.terminal || item.actionBound || item.nonce != nonce || item.taskId != taskId ||
            item.identity != identity || item.kinds.any { it !in supportedKinds }
        ) return null
        item.actionBound = true
        item.actionRequestId = actionRequestId
        item.nonce = ""
        return item
    }

    fun publish(
        id: String, kind: String, identity: Identity, payload: JSONObject = JSONObject(),
        attribution: String = "action_scoped_observation", terminal: Boolean = false,
        dialogResolver: ((String, String?) -> Boolean)? = null,
    ): JSONObject? {
        prune()
        val item = subscriptions[id] ?: return null
        if (!item.actionBound || item.terminal) return null
        if (payload.toString().toByteArray(Charsets.UTF_8).size > MAX_EVENT_PAYLOAD_BYTES) return null
        val expected = item.identity
        val wantsNavigation = item.kinds.any { it in setOf("navigation_started", "navigation_finished", "navigation_failed") }
        val sameStablePage = identity.sessionGeneration == expected.sessionGeneration &&
            identity.controlGeneration == expected.controlGeneration && identity.pageId == expected.pageId
        val isNavigationStart = kind == "navigation_started" && wantsNavigation && sameStablePage &&
            (!item.navigationStarted || identity.navigationId == expected.navigationId)
        if (identity != item.identity && !isNavigationStart) return null
        if (isNavigationStart) {
            // The armed main-frame start consumes the old document identity and binds the new navigation.
            if (!item.navigationStarted && identity.navigationId == expected.navigationId) return null
            item.navigationStarted = true
            item.identity = identity
        }
        if (kind !in item.kinds) return null
        if (kind in ONE_SHOT_KINDS && !item.publishedOneShotKinds.add(kind)) return null
        val event = JSONObject(payload.toString())
            .put("eventId", randomHex(16)).put("subscriptionId", item.id)
            .put("actionRequestId", item.actionRequestId).put("eventKind", kind)
            .put("attribution", attribution).put("pageId", identity.pageId)
            .put("documentGeneration", identity.documentGeneration).put("navigationId", identity.navigationId)
        if (item.events.size >= MAX_QUEUED_EVENTS) item.events.removeFirst()
        item.events.addLast(event)
        if (kind == "javascript_dialog") dialogEvents[event.optString("eventId")] = DialogEntry(item, dialogResolver)
        if (terminal) item.terminal = true
        val waiter = item.waiter
        if (waiter != null) {
            item.waiter = null
            item.timeout?.let(scheduler::removeCallbacks)
            item.timeout = null
            item.events.remove(event)
            waiter(event)
        }
        if (item.terminal && item.waiter == null && item.events.isEmpty()) remove(item.id)
        return event
    }

    fun await(id: String, taskId: String, identity: Identity, timeoutMillis: Long, callback: (JSONObject) -> Unit): Boolean {
        prune()
        val item = subscriptions[id] ?: return false
        if (item.taskId != taskId || !sameCurrent(item.identity, identity) || item.waiter != null) return false
        if (item.events.isNotEmpty()) {
            callback(item.events.removeFirst())
            if (item.terminal && item.events.isEmpty()) remove(id)
            return true
        }
        item.waiter = callback
        val timeout = Runnable {
            if (subscriptions[id] !== item || item.waiter == null) return@Runnable
            item.waiter = null
            item.timeout = null
            callback(JSONObject().put("code", "EVENT_TIMEOUT").put("subscriptionId", id))
            if (item.terminal) remove(id)
        }
        item.timeout = timeout
        scheduler.postDelayed(timeout, timeoutMillis)
        return true
    }

    fun registerDialogResolver(id: String, resolver: (String, String?) -> Boolean): Boolean {
        val dialog = dialogEvents[id] ?: return false
        if (dialog.resolver != null) return false
        dialog.resolver = resolver
        return true
    }

    fun resolveDialog(
        eventId: String, taskId: String, identity: Identity, decision: String, promptText: String?,
    ): Boolean {
        val dialog = dialogEvents[eventId] ?: return false
        val item = dialog.subscription
        if (item.taskId != taskId || !sameCurrent(item.identity, identity)) return false
        val resolver = dialog.resolver ?: return false
        dialogEvents.remove(eventId)
        item.events.removeAll { it.optString("eventId") == eventId }
        return resolver(decision, promptText)
    }

    /** Dismisses and releases dialog resolvers for one page without ending its event subscription. */
    fun dismissDialogsForPage(pageId: String): Int = dismissDialogs { it.subscription.identity.pageId == pageId }

    /** Dismisses and releases every pending dialog resolver while preserving subscriptions. */
    fun dismissAllDialogs(): Int = dismissDialogs { true }

    private fun dismissDialogs(matches: (DialogEntry) -> Boolean): Int {
        val eventIds = dialogEvents.filterValues(matches).keys.toList()
        eventIds.forEach { eventId ->
            val dialog = dialogEvents.remove(eventId) ?: return@forEach
            dialog.subscription.events.removeAll { it.optString("eventId") == eventId }
            runCatching { dialog.resolver?.invoke("dismiss", null) }
        }
        return eventIds.size
    }

    fun cancel(id: String, taskId: String): Boolean {
        val item = subscriptions[id] ?: return false
        if (item.taskId != taskId) return false
        remove(id, "user_cancelled")
        return true
    }

    fun expire(id: String) {
        val item = subscriptions[id] ?: return
        val waiter = item.waiter
        item.waiter = null
        waiter?.invoke(JSONObject().put("code", "EVENT_TIMEOUT").put("subscriptionId", id))
        remove(id)
    }

    fun cancelWait(id: String): Boolean {
        val item = subscriptions[id] ?: return false
        val callback = item.waiter ?: return false
        item.waiter = null
        item.timeout?.let(scheduler::removeCallbacks)
        item.timeout = null
        callback(JSONObject().put("code", "EVENT_CANCELLED").put("subscriptionId", id).put("reason", "user_cancelled"))
        return true
    }

    fun invalidateAll() {
        subscriptions.keys.toList().forEach { remove(it, "session_changed") }
    }

    fun invalidatePage(pageId: String) {
        subscriptions.values.filter { it.identity.pageId == pageId }.map { it.id }.forEach { remove(it, "page_changed") }
    }

    fun invalidateTask(taskId: String) {
        subscriptions.values.filter { it.taskId == taskId }.map { it.id }.forEach { remove(it, "task_changed") }
    }

    fun invalidateAction(actionRequestId: String) {
        subscriptions.values.filter { it.actionRequestId == actionRequestId }.map { it.id }.forEach { remove(it, "action_invalidated") }
    }

    private fun prune() {
        val time = now()
        subscriptions.values.filter { it.expiresAt <= time }.map { it.id }.forEach { id ->
            expire(id)
        }
    }

    private fun remove(id: String, reason: String = "subscription_removed") {
        subscriptions.remove(id)?.let { item ->
            item.oneShotClaims.values.forEach(OneShotClaim::revoke)
            item.timeout?.let(scheduler::removeCallbacks)
            item.expiry?.let(scheduler::removeCallbacks)
            val waiter = item.waiter
            item.waiter = null
            waiter?.invoke(JSONObject().put("code", "EVENT_CANCELLED").put("subscriptionId", id).put("reason", reason))
            val dialogs = dialogEvents.filterValues { it.subscription === item }.keys.toList()
            dialogs.forEach { eventId ->
                val dialog = dialogEvents.remove(eventId)
                if (dialog?.resolver != null) {
                    item.actionRequestId?.let { actionId ->
                        uncertainActionRequests.add(actionId)
                        while (uncertainActionRequests.size > MAX_SUBSCRIPTIONS) uncertainActionRequests.remove(uncertainActionRequests.first())
                    }
                    runCatching { dialog.resolver?.invoke("dismiss", null) }
                }
            }
            item.events.clear()
        }
    }

    fun publishForAction(
        actionRequestId: String, kind: String, identity: Identity, payload: JSONObject = JSONObject(),
        terminal: Boolean = false,
    ): JSONObject? {
        val item = subscriptions.values.firstOrNull { it.actionRequestId == actionRequestId && !it.terminal } ?: return null
        return publish(item.id, kind, identity, payload, terminal = terminal)
    }

    fun publishForPage(kind: String, pageId: String, identity: Identity, payload: JSONObject = JSONObject(), terminal: Boolean = false): JSONObject? {
        val candidates = subscriptions.values.filter {
            it.actionBound && !it.terminal && it.identity.pageId == pageId &&
                (kind in it.kinds || kind == "navigation_started" && it.kinds.any { eventKind -> eventKind.startsWith("navigation_") }) &&
                !(kind in ONE_SHOT_KINDS && kind in it.publishedOneShotKinds)
        }
        if (candidates.size != 1) return null
        return publish(candidates.single().id, kind, identity, payload, terminal = terminal)
    }

    fun publishDialogForPage(
        pageId: String, identity: Identity, payload: JSONObject,
        resolver: (String, String?) -> Boolean,
    ): JSONObject? {
        val candidates = subscriptions.values.filter {
            it.actionBound && !it.terminal && it.identity.pageId == pageId && "javascript_dialog" in it.kinds
        }
        if (candidates.size != 1) return null
        return publish(candidates.single().id, "javascript_dialog", identity, payload, dialogResolver = resolver)
    }

    fun hasBoundExpectation(pageId: String, kind: String): Boolean = subscriptions.values.any {
        it.actionBound && !it.terminal && it.identity.pageId == pageId && kind in it.kinds &&
            kind !in it.publishedOneShotKinds && it.oneShotClaims[kind]?.isValid() != true
    }

    fun claimOneShotExpectation(pageId: String, kind: String, identity: Identity): OneShotClaim? {
        prune()
        if (kind !in ONE_SHOT_KINDS || identity.pageId != pageId) return null
        val candidates = subscriptions.values.filter {
            it.actionBound && !it.terminal && it.identity == identity && kind in it.kinds &&
                kind !in it.publishedOneShotKinds && it.oneShotClaims[kind]?.isValid() != true
        }
        if (candidates.size != 1) return null
        val claim = OneShotClaim(candidates.single().expiresAt, now)
        candidates.single().oneShotClaims[kind] = claim
        return claim
    }

    fun onMainFrameStarted(pageId: String, identity: Identity, payload: JSONObject = JSONObject()): Boolean {
        prune()
        val candidates = subscriptions.values.filter {
            it.actionBound && !it.terminal && it.identity.pageId == pageId &&
                it.kinds.any { eventKind -> eventKind in setOf("navigation_started", "navigation_finished", "navigation_failed") }
        }
        if (candidates.size != 1) return false
        val item = candidates.single()
        val old = item.identity
        if (identity.sessionGeneration != old.sessionGeneration || identity.controlGeneration != old.controlGeneration ||
            identity.pageId != old.pageId || (item.navigationStarted && identity.navigationId != old.navigationId) ||
            (!item.navigationStarted && identity.navigationId == old.navigationId)
        ) return false
        item.navigationStarted = true
        item.identity = identity
        if ("navigation_started" in item.kinds) {
            publish(item.id, "navigation_started", identity, payload)
        }
        return true
    }

    fun subscription(id: String): Subscription? { prune(); return subscriptions[id] }
    fun subscriptionForDialog(eventId: String): Subscription? = dialogEvents[eventId]?.subscription
    fun consumeUncertainAction(actionRequestId: String): Boolean = uncertainActionRequests.remove(actionRequestId)

    private fun randomHex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun sameCurrent(left: Identity, right: Identity): Boolean =
        left.sessionGeneration == right.sessionGeneration && left.controlGeneration == right.controlGeneration &&
            left.pageId == right.pageId && left.documentGeneration == right.documentGeneration && left.navigationId == right.navigationId

    companion object {
        const val MAX_SUBSCRIPTIONS = 32
        const val MAX_QUEUED_EVENTS = 8
        const val MAX_EVENT_PAYLOAD_BYTES = 8192
        val EVENT_KINDS = setOf(
            "navigation_started", "navigation_finished", "navigation_failed", "new_window", "download", "javascript_dialog",
        )
        private val ONE_SHOT_KINDS = setOf("new_window", "download")
    }
}
