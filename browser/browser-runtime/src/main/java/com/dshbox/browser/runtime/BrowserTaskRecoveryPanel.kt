package com.dshbox.browser.runtime

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.dshbox.browser.api.BrowserEffectPhase
import com.dshbox.browser.api.BrowserTaskRecoveryMetadata
import com.dshbox.browser.api.BrowserTaskStatus
import com.dshbox.browser.api.BrowserUserEffectChoice
import com.dshbox.app.common.R as CommonR
import org.json.JSONArray
import org.json.JSONObject

/** User-confirmed task binding and UNKNOWN-effect review. All journal reads/writes run through the coordinator. */
internal class BrowserTaskRecoveryPanel(
    private val activity: Activity,
    private val uiContext: Context,
    private val liveContext: () -> BrowserTaskLiveContext?,
    private val currentPageOrigin: () -> String?,
    private val onTaskBindingStarted: (String) -> Unit = {},
    private val onSendRecoveryDraft: ((String) -> Boolean)? = null,
) {
    private val density = activity.resources.displayMetrics.density
    private var dialog: Dialog? = null
    private var modalDialog: Dialog? = null
    private lateinit var rows: LinearLayout
    private val loadedTasks = mutableListOf<BrowserTaskRecoveryMetadata>()
    private fun dp(value: Int) = (value * density).toInt()

    fun show() {
        dialog?.dismiss()
        val close = ImageButton(uiContext).apply {
            setImageResource(CommonR.drawable.ic_x)
            contentDescription = uiContext.getText(R.string.browser_cancel)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setColorFilter(BrowserRuntimePalette.resolve(activity).ink)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { dismiss() }
        }
        val shell = BrowserManagementPanel.create(
            activity, uiContext.getText(R.string.browser_task_recovery), close,
        )
        rows = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }
        shell.content.addView(ScrollView(uiContext).apply { addView(rows) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        dialog = Dialog(activity).also { BrowserManagementPanel.show(activity, it, shell.root) }
        loadedTasks.clear()
        render(emptyList())
        BrowserTaskCoordinatorRegistry.get()?.readRecoveryMetadata(0, TASK_PAGE_SIZE) { render(it, append = true) }
            ?: showUnavailable()
    }

    fun inputDialogWindow(): android.view.Window? = modalDialog?.window

    fun releaseInput() {
        val window = modalDialog?.window ?: return
        val focused = window.currentFocus
        val token = focused?.windowToken ?: window.decorView.windowToken
        if (token != null) activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            ?.let { it as? android.view.inputmethod.InputMethodManager }
            ?.hideSoftInputFromWindow(token, 0)
        focused?.clearFocus()
    }

    fun dismiss() { releaseInput(); modalDialog?.dismiss(); modalDialog = null; dialog?.dismiss(); dialog = null }

    private fun render(tasks: List<BrowserTaskRecoveryMetadata>, append: Boolean = false) {
        if (!::rows.isInitialized || activity.isFinishing || activity.isDestroyed || dialog?.isShowing != true) return
        if (append) loadedTasks.addAll(tasks) else loadedTasks.clear()
        rows.removeAllViews()
        rows.addView(label(uiContext.getText(R.string.browser_task_recovery_intro).toString()))
        if (loadedTasks.isEmpty()) {
            rows.addView(label(uiContext.getText(R.string.browser_task_recovery_empty).toString()))
            return
        }
        loadedTasks.forEach { metadata ->
            val task = metadata.task
            val card = LinearLayout(uiContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(BrowserRuntimePalette.resolve(activity).soft)
            }
            card.addView(label(uiContext.getString(
                R.string.browser_task_recovery_task_summary,
                task.taskId, uiContext.getString(statusLabel(task.status)),
            )))
            if (task.remainingOperations > 0 && task.status !in setOf(
                    BrowserTaskStatus.BUDGET_EXHAUSTED, BrowserTaskStatus.COMPLETED,
                    BrowserTaskStatus.FAILED, BrowserTaskStatus.EXPIRED,
                )) {
                card.addView(label(uiContext.getString(
                    R.string.browser_task_recovery_available_operations, task.remainingOperations,
                )))
            }
            if (task.title.isNotBlank()) card.addView(label(task.title))
            card.addView(label(task.goal.takeIf { it.isNotBlank() }?.let { goal ->
                if (goal.length > GOAL_PREVIEW_LENGTH) goal.take(GOAL_PREVIEW_LENGTH) + "…" else goal
            } ?: uiContext.getString(R.string.browser_task_recovery_goal_missing)))
            if (task.notes.isNotBlank()) card.addView(label(task.notes))
            task.failureReason?.let { reason ->
                card.addView(label(uiContext.getString(R.string.browser_task_failure_reason, reason)))
            }
            metadata.unknownEffects.forEach { effect ->
                val effectLabelRes = if (effect.phase == BrowserEffectPhase.INTENT) R.string.browser_task_recovery_effect_in_flight
                    else R.string.browser_task_recovery_effect_unknown
                card.addView(label(uiContext.getString(effectLabelRes, effect.method, effect.requestId)))
                effect.lastUserChoice?.let { choice ->
                    card.addView(label(uiContext.getString(
                        R.string.browser_task_recovery_effect_user_choice,
                        effect.method, uiContext.getString(choiceLabel(choice)),
                    )))
                }
                if (task.status in setOf(BrowserTaskStatus.PAUSED, BrowserTaskStatus.NEEDS_RECONCILIATION, BrowserTaskStatus.WAITING_USER) &&
                    effect.phase == BrowserEffectPhase.UNKNOWN
                ) {
                    card.addView(button(R.string.browser_task_recovery_verify) {
                        chooseEffectOutcome(metadata, effect.requestId)
                    })
                }
            }
            if (task.status in setOf(BrowserTaskStatus.PAUSED, BrowserTaskStatus.NEEDS_RECONCILIATION, BrowserTaskStatus.WAITING_USER)) {
                card.addView(button(R.string.browser_task_recovery_bind) { prepareBinding(metadata) })
            }
            card.addView(button(R.string.browser_task_recovery_edit) { editTask(metadata) })
            if (task.goal.isNotBlank() && onSendRecoveryDraft != null) card.addView(button(R.string.browser_task_recovery_send_draft) {
                val queued = onSendRecoveryDraft.invoke(recoveryDraft(metadata).toString())
                Toast.makeText(uiContext, if (queued) R.string.browser_task_recovery_draft_sent else R.string.browser_task_recovery_draft_failed,
                    Toast.LENGTH_SHORT).show()
            })
            val canDelete = task.status in setOf(BrowserTaskStatus.BUDGET_EXHAUSTED, BrowserTaskStatus.COMPLETED, BrowserTaskStatus.FAILED, BrowserTaskStatus.EXPIRED) &&
                metadata.unknownEffects.isEmpty()
            card.addView(button(R.string.browser_task_recovery_delete) { if (canDelete) confirmDelete(metadata) }
                .apply { isEnabled = canDelete })
            if (!canDelete) card.addView(label(uiContext.getString(R.string.browser_task_recovery_delete_disabled)))
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = dp(10)
            rows.addView(card, params)
        }
        if (tasks.size == TASK_PAGE_SIZE) rows.addView(button(R.string.browser_task_recovery_load_more) {
            BrowserTaskCoordinatorRegistry.get()?.readRecoveryMetadata(loadedTasks.size, TASK_PAGE_SIZE) {
                render(it, append = true)
            } ?: showUnavailable()
        })
    }

    private fun editTask(metadata: BrowserTaskRecoveryMetadata) {
        val task = metadata.task
        val form = LinearLayout(uiContext).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun field(label: Int, value: String, maxLines: Int): EditText = EditText(uiContext).apply {
            hint = uiContext.getString(label); setText(value); this.maxLines = maxLines
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val title = field(R.string.browser_task_recovery_title_hint, task.title, 2)
        val goal = field(R.string.browser_task_recovery_goal_hint, task.goal, 5)
        val notes = field(R.string.browser_task_recovery_notes_hint, task.notes, 4)
        form.addView(title); form.addView(goal); form.addView(notes)
        modalDialog = AlertDialog.Builder(uiContext).setTitle(R.string.browser_task_recovery_edit)
            .setView(form).setNegativeButton(R.string.browser_cancel, null)
            .setPositiveButton(R.string.browser_save) { _, _ ->
                if (title.text.length > 120 || goal.text.length > 4000 || notes.text.length > 2000) {
                    Toast.makeText(uiContext, R.string.browser_task_recovery_text_too_long, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                BrowserTaskCoordinatorRegistry.get()?.updateManagedTask(task.taskId, task.revision,
                    title.text.toString(), goal.text.toString(), notes.text.toString()) { result ->
                    if (result is BrowserTaskUpdateResult.Updated) refresh() else showFailure(null)
                } ?: showUnavailable()
            }.show()
    }

    private fun confirmDelete(metadata: BrowserTaskRecoveryMetadata) {
        val task = metadata.task
        AlertDialog.Builder(uiContext).setTitle(R.string.browser_task_recovery_delete)
            .setMessage(uiContext.getString(R.string.browser_task_recovery_delete_confirm, task.taskId))
            .setNegativeButton(R.string.browser_cancel, null)
            .setPositiveButton(R.string.browser_task_recovery_delete) { _, _ ->
                BrowserTaskCoordinatorRegistry.get()?.deleteManagedTask(task.taskId, task.revision) { result ->
                    when (result) {
                        BrowserTaskDeleteResult.Deleted -> refresh()
                        BrowserTaskDeleteResult.NotFound -> showDeleteFailure(R.string.browser_task_recovery_delete_not_found)
                        BrowserTaskDeleteResult.StaleRevision -> showDeleteFailure(R.string.browser_task_recovery_delete_stale)
                        BrowserTaskDeleteResult.NotTerminal -> showDeleteFailure(R.string.browser_task_recovery_delete_not_terminal)
                        BrowserTaskDeleteResult.UnresolvedEffects -> showDeleteFailure(R.string.browser_task_recovery_delete_unresolved_effects)
                        null -> showUnavailable()
                    }
                } ?: showUnavailable()
            }.show()
    }

    private fun recoveryDraft(metadata: BrowserTaskRecoveryMetadata): JSONObject = JSONObject()
        .put("kind", "task_recovery")
        .put("taskId", metadata.task.taskId)
        .put("title", safeDraftText(metadata.task.title))
        .put("goal", safeDraftText(metadata.task.goal))
        .put("notes", safeDraftText(metadata.task.notes))
        .put("failureReason", metadata.task.failureReason?.let(::safeDraftText) ?: JSONObject.NULL)
        .put("status", metadata.task.status.wireValue)
        .put("revision", metadata.task.revision)
        .put("unknownEffects", JSONArray().apply {
            metadata.unknownEffects.take(DRAFT_UNKNOWN_EFFECTS_LIMIT).forEach { effect -> put(JSONObject()
                .put("method", effect.method).put("requestId", effect.requestId)
                .put("phase", effect.phase.wireValue)
                .put("lastUserChoice", effect.lastUserChoice?.wireValue ?: JSONObject.NULL))
            }
        })
        .put("unknownEffectsTotal", metadata.unknownEffects.size)
        .put("effectsTruncated", metadata.unknownEffects.size > DRAFT_UNKNOWN_EFFECTS_LIMIT)
        .put("createdAtWallMillis", metadata.task.createdAtWallMillis)

    private fun safeDraftText(value: String): String = value
        .replace(Regex("(?i)(https?://)([^/?#\\s]+)([^?#\\s]*)(?:\\?[^#\\s]*)?(?:#[^\\s]*)?")) { match ->
            val authority = match.groupValues[2].substringAfterLast('@')
            "${match.groupValues[1]}$authority${match.groupValues[3]}"
        }
        .replace(Regex("(?i)(?:[A-Z]:\\\\(?:Users|Documents and Settings)\\\\|/data/(?:data|user|user_de)/|/storage/emulated/|/home/)[^\\s,;]+"), "[local path omitted]")
        .replace(Regex("(?i)(api[_-]?key|access[_-]?token|password|secret|authorization)\\s*[:=]\\s*[^\\s,;]+"), "$1=[redacted]")

    private fun prepareBinding(metadata: BrowserTaskRecoveryMetadata) {
        val coordinator = BrowserTaskCoordinatorRegistry.get() ?: return showUnavailable()
        coordinator.prepareRecoveryBinding(metadata.task.taskId, liveContext(), currentPageOrigin()) { challenge, error ->
            if (dialog?.isShowing != true || activity.isFinishing || activity.isDestroyed) return@prepareRecoveryBinding
            if (challenge == null) return@prepareRecoveryBinding showFailure(error)
            modalDialog = AlertDialog.Builder(uiContext)
                .setTitle(uiContext.getText(R.string.browser_task_recovery_confirm_title))
                .setMessage(uiContext.getString(
                    R.string.browser_task_recovery_confirm_message,
                    challenge.taskId, challenge.taskRevision,
                    challenge.oldIdentity?.pageId ?: uiContext.getString(R.string.browser_task_recovery_no_checkpoint),
                    challenge.oldIdentity?.sessionGeneration ?: 0L,
                    challenge.oldIdentity?.controlGeneration ?: 0L,
                    challenge.oldIdentity?.documentGeneration ?: 0L,
                    uiContext.getString(statusLabel(challenge.oldStatus)), challenge.unknownEffectCount,
                    challenge.newPageOrigin.orEmpty().ifBlank { challenge.newIdentity.pageId },
                ))
                .setNegativeButton(uiContext.getText(R.string.browser_cancel), null)
                .setPositiveButton(uiContext.getText(R.string.browser_task_recovery_confirm_bind)) { _, _ ->
                    onTaskBindingStarted(challenge.taskId)
                    coordinator.consumeRecoveryBinding(challenge.challengeId, liveContext) { record, reason ->
                        if (dialog?.isShowing != true || activity.isFinishing || activity.isDestroyed) return@consumeRecoveryBinding
                        if (record == null) showFailure(reason) else {
                            if (dialog?.isShowing == true && !activity.isFinishing && !activity.isDestroyed)
                                Toast.makeText(uiContext, R.string.browser_task_recovery_bound, Toast.LENGTH_SHORT).show()
                            refresh()
                        }
                    }
                }.show()
        }
    }

    private fun chooseEffectOutcome(metadata: BrowserTaskRecoveryMetadata, effectId: String) {
        val content = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        content.addView(label(uiContext.getString(R.string.browser_task_recovery_attestation_message, effectId)))
        listOf(
            BrowserUserEffectChoice.OCCURRED to R.string.browser_task_recovery_occurred,
            BrowserUserEffectChoice.NOT_OCCURRED to R.string.browser_task_recovery_not_occurred,
            BrowserUserEffectChoice.STILL_UNKNOWN to R.string.browser_task_recovery_still_unknown,
        ).forEach { (choice, title) ->
            content.addView(button(title) {
                modalDialog?.dismiss()
                BrowserTaskCoordinatorRegistry.get()?.recordUserEffectChoice(
                    metadata.task.taskId, metadata.task.revision, effectId, choice, liveContext,
                ) { saved ->
                    if (dialog?.isShowing != true || activity.isFinishing || activity.isDestroyed) return@recordUserEffectChoice
                    if (saved) {
                        if (dialog?.isShowing == true && !activity.isFinishing && !activity.isDestroyed)
                            Toast.makeText(uiContext, R.string.browser_task_recovery_attested, Toast.LENGTH_SHORT).show()
                        refresh()
                    } else showFailure(null)
                } ?: showUnavailable()
            })
        }
        modalDialog = AlertDialog.Builder(uiContext)
            .setTitle(uiContext.getText(R.string.browser_task_recovery_user_attestation))
            .setView(content)
            .setNegativeButton(uiContext.getText(R.string.browser_cancel), null)
            .show()
    }

    private fun refresh() {
        loadedTasks.clear()
        BrowserTaskCoordinatorRegistry.get()?.readRecoveryMetadata(0, TASK_PAGE_SIZE) { render(it, append = true) } ?: showUnavailable()
    }

    private fun label(value: String) = TextView(uiContext).apply {
        text = value
        textSize = 14f
        setTextColor(BrowserRuntimePalette.resolve(activity).ink)
        setPadding(0, dp(4), 0, dp(6))
    }

    private fun button(textId: Int, action: () -> Unit) = Button(uiContext).apply {
        text = uiContext.getText(textId)
        setOnClickListener { action() }
    }

    private fun showUnavailable() {
        if (dialog?.isShowing == true && !activity.isFinishing && !activity.isDestroyed)
            Toast.makeText(uiContext, R.string.browser_task_recovery_unavailable, Toast.LENGTH_SHORT).show()
    }

    private fun showFailure(reason: String?) {
        val message = if (reason == "TASK_BUDGET_EXHAUSTED") R.string.browser_task_recovery_budget
            else R.string.browser_task_recovery_failed
        if (dialog?.isShowing == true && !activity.isFinishing && !activity.isDestroyed)
            Toast.makeText(uiContext, message, Toast.LENGTH_SHORT).show()
    }

    private fun showDeleteFailure(message: Int) {
        if (dialog?.isShowing == true && !activity.isFinishing && !activity.isDestroyed)
            Toast.makeText(uiContext, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TASK_PAGE_SIZE = 100
        const val GOAL_PREVIEW_LENGTH = 240
        const val DRAFT_UNKNOWN_EFFECTS_LIMIT = 32
    }

    private fun statusLabel(status: BrowserTaskStatus): Int = when (status) {
        BrowserTaskStatus.RUNNING -> R.string.browser_task_status_running
        BrowserTaskStatus.WAITING_PAGE -> R.string.browser_task_status_waiting_page
        BrowserTaskStatus.WAITING_USER -> R.string.browser_task_status_waiting_user
        BrowserTaskStatus.PAUSED -> R.string.browser_task_status_paused
        BrowserTaskStatus.NEEDS_RECONCILIATION -> R.string.browser_task_status_needs_reconciliation
        BrowserTaskStatus.BUDGET_EXHAUSTED -> R.string.browser_task_status_budget_exhausted
        BrowserTaskStatus.COMPLETED -> R.string.browser_task_status_completed
        BrowserTaskStatus.FAILED -> R.string.browser_task_status_failed
        BrowserTaskStatus.EXPIRED -> R.string.browser_task_status_expired
    }

    private fun choiceLabel(choice: BrowserUserEffectChoice): Int = when (choice) {
        BrowserUserEffectChoice.OCCURRED -> R.string.browser_task_recovery_occurred
        BrowserUserEffectChoice.NOT_OCCURRED -> R.string.browser_task_recovery_not_occurred
        BrowserUserEffectChoice.STILL_UNKNOWN -> R.string.browser_task_recovery_still_unknown
    }
}
