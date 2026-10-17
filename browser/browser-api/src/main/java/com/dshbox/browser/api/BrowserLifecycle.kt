package com.dshbox.browser.api

/** Supplies monotonic milliseconds; wall-clock time must not be used for lifecycle deadlines. */
fun interface BrowserClock {
    fun nowMillis(): Long
}

enum class BrowserLifecycle {
    CLOSED,
    ACTIVE,
    CLOSING,
    ERROR,
}

enum class BrowserOwner {
    NONE,
    HUMAN,
    AI,
}

enum class BrowserCloseReason {
    IDLE_TIMEOUT,
    USER_CLOSE,
    AI_CLOSE,
    RENDERER_GONE,
    ACTIVITY_DESTROYED,
    UNKNOWN,
}

enum class BrowserHomeStatus {
    RUNNING,
    IDLE,
}

data class BrowserSnapshot(
    val sessionGeneration: Long,
    val controlGeneration: Long,
    val operationGeneration: Long,
    val lifecycle: BrowserLifecycle,
    val owner: BrowserOwner,
    val visible: Boolean,
    val inFlightOperationId: String?,
    val hiddenAtMillis: Long?,
    val lastEffectiveOperationCompletedAtMillis: Long?,
    val idleTimeoutMillis: Long,
    val operationDeadlineAtMillis: Long?,
    val closeReason: BrowserCloseReason = BrowserCloseReason.UNKNOWN,
) {
    fun homeStatus(): BrowserHomeStatus? = when {
        lifecycle != BrowserLifecycle.ACTIVE -> null
        inFlightOperationId != null -> BrowserHomeStatus.RUNNING
        else -> BrowserHomeStatus.IDLE
    }

    fun commandContext(): CommandContext =
        CommandContext(sessionGeneration, controlGeneration, owner)
}

data class CommandContext(
    val sessionGeneration: Long,
    val controlGeneration: Long,
    val owner: BrowserOwner,
)

data class BrowserOperationToken(
    val operationId: String,
    val sessionGeneration: Long,
    val controlGeneration: Long,
    val operationGeneration: Long,
    val owner: BrowserOwner,
    val deadlineAtMillis: Long,
)

class BrowserIdlePolicy(
    val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
    val maxOperationDurationMillis: Long = DEFAULT_MAX_OPERATION_DURATION_MILLIS,
) {
    init {
        require(idleTimeoutMillis in MIN_IDLE_TIMEOUT_MILLIS..MAX_IDLE_TIMEOUT_MILLIS)
        require(maxOperationDurationMillis > 0)
    }

    fun closeDeadline(snapshot: BrowserSnapshot): Long? {
        if (snapshot.lifecycle != BrowserLifecycle.ACTIVE || snapshot.visible) return null
        val hiddenAt = snapshot.hiddenAtMillis ?: return null
        val idleBase = maxOf(hiddenAt, snapshot.lastEffectiveOperationCompletedAtMillis ?: hiddenAt)
        val idleDeadline = saturatingAdd(idleBase, snapshot.idleTimeoutMillis)
        val operationDeadline = snapshot.operationDeadlineAtMillis
        return if (snapshot.inFlightOperationId != null && operationDeadline != null) {
            maxOf(idleDeadline, operationDeadline)
        } else {
            idleDeadline
        }
    }

    fun isCloseDue(snapshot: BrowserSnapshot, nowMillis: Long): Boolean =
        closeDeadline(snapshot)?.let { nowMillis >= it } ?: false

    /** Remaining hidden-session lease, or null while no idle deadline is applicable. */
    fun remainingMillis(snapshot: BrowserSnapshot, nowMillis: Long): Long? =
        closeDeadline(snapshot)?.let { deadline ->
            if (deadline <= nowMillis) 0L
            else if (nowMillis < 0 && deadline > Long.MAX_VALUE + nowMillis) Long.MAX_VALUE
            else deadline - nowMillis
        }

    companion object {
        const val MIN_IDLE_TIMEOUT_MILLIS = 60_000L
        const val DEFAULT_IDLE_TIMEOUT_MILLIS = 5 * 60_000L
        const val MAX_IDLE_TIMEOUT_MILLIS = 15 * 60_000L
        const val DEFAULT_MAX_OPERATION_DURATION_MILLIS = 60_000L

        private fun saturatingAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }
}

/**
 * Mutable lifecycle state confined to one caller thread. All calls, including timer callbacks,
 * must be serialized by the owner. The supplied clock must report monotonic milliseconds.
 */
class BrowserLifecycleController(
    private val clock: BrowserClock,
    initialIdleTimeoutMillis: Long = BrowserIdlePolicy.DEFAULT_IDLE_TIMEOUT_MILLIS,
    private val maxOperationDurationMillis: Long = BrowserIdlePolicy.DEFAULT_MAX_OPERATION_DURATION_MILLIS,
    initialSessionGeneration: Long = 0,
) {
    init {
        require(initialSessionGeneration >= 0 && initialSessionGeneration <= Long.MAX_VALUE - 1024)
    }

    private var current = BrowserSnapshot(
        sessionGeneration = initialSessionGeneration,
        controlGeneration = 0,
        operationGeneration = 0,
        lifecycle = BrowserLifecycle.CLOSED,
        owner = BrowserOwner.NONE,
        visible = false,
        inFlightOperationId = null,
        hiddenAtMillis = null,
        lastEffectiveOperationCompletedAtMillis = null,
        idleTimeoutMillis = initialIdleTimeoutMillis,
        operationDeadlineAtMillis = null,
        closeReason = BrowserCloseReason.UNKNOWN,
    )
    private val idlePolicy = BrowserIdlePolicy(initialIdleTimeoutMillis, maxOperationDurationMillis)

    fun snapshot(): BrowserSnapshot = current

    fun open(owner: BrowserOwner = BrowserOwner.HUMAN): BrowserSnapshot {
        require(owner != BrowserOwner.NONE)
        if (current.lifecycle == BrowserLifecycle.ACTIVE) return current
        if (current.lifecycle == BrowserLifecycle.CLOSING) return current
        val now = clock.nowMillis()
        current = current.copy(
            sessionGeneration = current.sessionGeneration + 1,
            controlGeneration = current.controlGeneration + 1,
            lifecycle = BrowserLifecycle.ACTIVE,
            owner = owner,
            visible = true,
            inFlightOperationId = null,
            hiddenAtMillis = null,
            lastEffectiveOperationCompletedAtMillis = now,
            operationDeadlineAtMillis = null,
            closeReason = BrowserCloseReason.UNKNOWN,
        )
        return current
    }

    fun setVisible(visible: Boolean): BrowserSnapshot {
        if (current.lifecycle != BrowserLifecycle.ACTIVE || current.visible == visible) return current
        val now = clock.nowMillis()
        if (visible) {
            expireIfNeeded(now)
            if (current.lifecycle != BrowserLifecycle.ACTIVE) return current
        }
        current = current.copy(
            visible = visible,
            hiddenAtMillis = if (visible) null else now,
        )
        if (!visible) expireIfNeeded(now)
        return current
    }

    fun updateIdleTimeout(timeoutMillis: Long): BrowserSnapshot {
        require(timeoutMillis in BrowserIdlePolicy.MIN_IDLE_TIMEOUT_MILLIS..BrowserIdlePolicy.MAX_IDLE_TIMEOUT_MILLIS)
        current = current.copy(idleTimeoutMillis = timeoutMillis)
        expireIfNeeded(clock.nowMillis())
        return current
    }

    fun beginOperation(
        operationId: String,
        context: CommandContext,
        requestedDeadlineAtMillis: Long,
    ): BrowserOperationToken? {
        val now = clock.nowMillis()
        expireIfNeeded(now)
        if (operationId.isBlank() || current.lifecycle != BrowserLifecycle.ACTIVE) return null
        if (!matches(context) || current.owner == BrowserOwner.NONE) return null
        if (current.inFlightOperationId != null || requestedDeadlineAtMillis <= now) return null
        val maximumDeadline = saturatingAdd(now, maxOperationDurationMillis)
        val deadline = minOf(requestedDeadlineAtMillis, maximumDeadline)
        if (deadline <= now) return null
        val token = BrowserOperationToken(
            operationId = operationId,
            sessionGeneration = current.sessionGeneration,
            controlGeneration = current.controlGeneration,
            operationGeneration = current.operationGeneration + 1,
            owner = current.owner,
            deadlineAtMillis = deadline,
        )
        current = current.copy(
            operationGeneration = token.operationGeneration,
            inFlightOperationId = operationId,
            operationDeadlineAtMillis = deadline,
        )
        return token
    }

    fun completeOperation(token: BrowserOperationToken, effective: Boolean): Boolean {
        val now = clock.nowMillis()
        expireIfNeeded(now)
        if (!isCurrent(token, now)) return false
        current = current.copy(
            inFlightOperationId = null,
            operationDeadlineAtMillis = null,
            lastEffectiveOperationCompletedAtMillis =
                if (effective) now else current.lastEffectiveOperationCompletedAtMillis,
        )
        expireIfNeeded(now)
        return true
    }

    fun recordEffectiveActivity(context: CommandContext): Boolean {
        val now = clock.nowMillis()
        expireIfNeeded(now)
        if (current.lifecycle != BrowserLifecycle.ACTIVE || !matches(context)) return false
        current = current.copy(lastEffectiveOperationCompletedAtMillis = now)
        return true
    }

    /** Records a successful, fresh DOM read for the current AI-owned session. */
    fun recordReadActivity(context: CommandContext): Boolean {
        val now = clock.nowMillis()
        expireIfNeeded(now)
        if (current.lifecycle != BrowserLifecycle.ACTIVE ||
            current.owner != BrowserOwner.AI ||
            !matches(context)
        ) return false
        current = current.copy(lastEffectiveOperationCompletedAtMillis = now)
        return true
    }

    fun takeOver(): BrowserSnapshot {
        if (current.lifecycle != BrowserLifecycle.ACTIVE) return current
        current = current.copy(
            controlGeneration = current.controlGeneration + 1,
            operationGeneration = current.operationGeneration + 1,
            owner = BrowserOwner.HUMAN,
            inFlightOperationId = null,
            operationDeadlineAtMillis = null,
        )
        return current
    }

    fun handToAi(): BrowserSnapshot {
        if (current.lifecycle != BrowserLifecycle.ACTIVE) return current
        current = current.copy(
            controlGeneration = current.controlGeneration + 1,
            operationGeneration = current.operationGeneration + 1,
            owner = BrowserOwner.AI,
            inFlightOperationId = null,
            operationDeadlineAtMillis = null,
        )
        return current
    }

    fun expireIfNeeded(nowMillis: Long = clock.nowMillis()): BrowserSnapshot {
        if (current.inFlightOperationId != null &&
            current.operationDeadlineAtMillis?.let { nowMillis >= it } == true
        ) {
            current = current.copy(inFlightOperationId = null, operationDeadlineAtMillis = null)
        }
        if (idlePolicy.isCloseDue(current, nowMillis)) beginClose(BrowserCloseReason.IDLE_TIMEOUT)
        return current
    }

    fun beginClose(reason: BrowserCloseReason = BrowserCloseReason.UNKNOWN): BrowserSnapshot {
        if (current.lifecycle != BrowserLifecycle.ACTIVE) return current
        current = current.copy(
            sessionGeneration = current.sessionGeneration + 1,
            controlGeneration = current.controlGeneration + 1,
            operationGeneration = current.operationGeneration + 1,
            lifecycle = BrowserLifecycle.CLOSING,
            owner = BrowserOwner.NONE,
            visible = false,
            inFlightOperationId = null,
            hiddenAtMillis = null,
            operationDeadlineAtMillis = null,
            closeReason = reason,
        )
        return current
    }

    fun finishClose(): BrowserSnapshot {
        if (current.lifecycle == BrowserLifecycle.CLOSING) {
            current = current.copy(lifecycle = BrowserLifecycle.CLOSED)
        }
        return current
    }

    fun close(reason: BrowserCloseReason = BrowserCloseReason.UNKNOWN): BrowserSnapshot {
        beginClose(reason)
        return finishClose()
    }

    fun fail(): BrowserSnapshot {
        if (current.lifecycle != BrowserLifecycle.ACTIVE) return current
        current = current.copy(
            sessionGeneration = current.sessionGeneration + 1,
            controlGeneration = current.controlGeneration + 1,
            operationGeneration = current.operationGeneration + 1,
            lifecycle = BrowserLifecycle.ERROR,
            owner = BrowserOwner.NONE,
            visible = false,
            inFlightOperationId = null,
            operationDeadlineAtMillis = null,
        )
        return current
    }

    private fun matches(context: CommandContext): Boolean =
        context.sessionGeneration == current.sessionGeneration &&
            context.controlGeneration == current.controlGeneration &&
            context.owner == current.owner

    private fun isCurrent(token: BrowserOperationToken, nowMillis: Long): Boolean =
        current.lifecycle == BrowserLifecycle.ACTIVE &&
            current.inFlightOperationId == token.operationId &&
            token.sessionGeneration == current.sessionGeneration &&
            token.controlGeneration == current.controlGeneration &&
            token.operationGeneration == current.operationGeneration &&
            token.owner == current.owner &&
            current.operationDeadlineAtMillis == token.deadlineAtMillis &&
            nowMillis < token.deadlineAtMillis

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
