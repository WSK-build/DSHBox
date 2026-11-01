package com.dshbox.dsh.renderer.gecko

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate.*
import java.util.concurrent.CancellationException

internal class GeckoPromptController(
    private val context: Context,
    private val fileChooser: GeckoFileChooserController,
    private val isActive: () -> Boolean,
    private val allowPopup: (String?) -> Boolean,
) : GeckoSession.PromptDelegate {
    private var active: ActivePrompt? = null

    private val lifecycle = object : PromptInstanceDelegate {
        override fun onPromptDismiss(prompt: BasePrompt) {
            requireMainThread("GeckoPromptController.onPromptDismiss")
            val current = active?.takeIf { it.prompt === prompt } ?: return
            cancelActive(current)
        }

        override fun onPromptUpdate(prompt: BasePrompt) {
            requireMainThread("GeckoPromptController.onPromptUpdate")
            val current = active ?: return
            val updated = prompt as? ChoicePrompt ?: return
            if (!current.isChoice) return
            updateChoice(current, updated)
        }
    }

    override fun onFilePrompt(
        session: GeckoSession,
        prompt: FilePrompt,
    ): GeckoResult<PromptResponse> {
        requireMainThread("GeckoPromptController.onFilePrompt")
        cancelActive()
        return fileChooser.onFilePrompt(session, prompt)
    }

    override fun onAlertPrompt(
        session: GeckoSession,
        prompt: AlertPrompt,
    ): GeckoResult<PromptResponse> {
        requireMainThread("GeckoPromptController.onAlertPrompt")
        val result = GeckoResult<PromptResponse>()
        val current = begin(prompt, result) ?: return result
        val builder = dialogBuilder(prompt)
            .setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                confirmActive(current) { current.prompt.dismiss() }
            }
        installDialogs(current, builder)
        showDialog(current, builder)
        return result
    }

    override fun onButtonPrompt(
        session: GeckoSession,
        prompt: ButtonPrompt,
    ): GeckoResult<PromptResponse> {
        requireMainThread("GeckoPromptController.onButtonPrompt")
        val result = GeckoResult<PromptResponse>()
        val current = begin(prompt, result) ?: return result
        val builder = dialogBuilder(prompt)
            .setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                confirmActive(current) {
                    prompt.confirm(ButtonPrompt.Type.POSITIVE)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                confirmActive(current) {
                    prompt.confirm(ButtonPrompt.Type.NEGATIVE)
                }
            }
            .setOnCancelListener {
                confirmActive(current) {
                    prompt.confirm(ButtonPrompt.Type.NEGATIVE)
                }
            }
        installDialogs(current, builder)
        showDialog(current, builder)
        return result
    }

    override fun onTextPrompt(
        session: GeckoSession,
        prompt: TextPrompt,
    ): GeckoResult<PromptResponse> {
        requireMainThread("GeckoPromptController.onTextPrompt")
        val result = GeckoResult<PromptResponse>()
        val current = begin(prompt, result) ?: return result
        val input = EditText(context).apply {
            setText(prompt.defaultValue.orEmpty())
            setSingleLine(false)
        }
        val container = FrameLayout(context).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val builder = dialogBuilder(prompt)
            .setMessage(prompt.message)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                confirmActive(current) {
                    prompt.confirm(input.text.toString())
                }
            }
        installDialogs(current, builder)
        showDialog(current, builder)
        return result
    }

    override fun onChoicePrompt(
        session: GeckoSession,
        prompt: ChoicePrompt,
    ): GeckoResult<PromptResponse> {
        requireMainThread("GeckoPromptController.onChoicePrompt")
        val result = GeckoResult<PromptResponse>()
        val current = begin(prompt, result) ?: return result
        showChoice(current)
        return result
    }

    override fun onBeforeUnloadPrompt(
        session: GeckoSession,
        prompt: BeforeUnloadPrompt,
    ): GeckoResult<PromptResponse> =
        GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))

    override fun onRepostConfirmPrompt(
        session: GeckoSession,
        prompt: RepostConfirmPrompt,
    ): GeckoResult<PromptResponse> =
        GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))

    override fun onFolderUploadPrompt(
        session: GeckoSession,
        prompt: FolderUploadPrompt,
    ): GeckoResult<PromptResponse> =
        GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))

    override fun onAuthPrompt(
        session: GeckoSession,
        prompt: AuthPrompt,
    ): GeckoResult<PromptResponse> =
        GeckoResult.fromValue(prompt.dismiss())

    override fun onColorPrompt(
        session: GeckoSession,
        prompt: ColorPrompt,
    ): GeckoResult<PromptResponse> =
        GeckoResult.fromValue(prompt.dismiss())

    override fun onDateTimePrompt(
        session: GeckoSession,
        prompt: DateTimePrompt,
    ): GeckoResult<PromptResponse> =
        GeckoResult.fromValue(prompt.dismiss())

    override fun onPopupPrompt(
        session: GeckoSession,
        prompt: PopupPrompt,
    ): GeckoResult<PromptResponse> =
        GeckoResult.fromValue(
            prompt.confirm(
                if (isActive() && allowPopup(prompt.targetUri)) {
                    AllowOrDeny.ALLOW
                } else {
                    AllowOrDeny.DENY
                },
            ),
        )

    override fun onRedirectPrompt(
        session: GeckoSession,
        prompt: RedirectPrompt,
    ): GeckoResult<PromptResponse> =
        GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))

    override fun onSharePrompt(
        session: GeckoSession,
        prompt: SharePrompt,
    ): GeckoResult<PromptResponse> =
        GeckoResult.fromValue(prompt.dismiss())

    fun cancelForNavigation() {
        requireMainThread("GeckoPromptController.cancelForNavigation")
        cancelActive()
    }

    fun release() {
        requireMainThread("GeckoPromptController.release")
        cancelActive()
    }

    private fun begin(
        prompt: BasePrompt,
        result: GeckoResult<PromptResponse>,
    ): ActivePrompt? {
        val activity = findActivity(context)
        if (!isActive() || activity == null || activity.isFinishing || activity.isDestroyed) {
            result.complete(prompt.dismiss())
            return null
        }
        fileChooser.cancelPending()
        cancelActive()
        return ActivePrompt(prompt, result, activity).also {
            active = it
            prompt.setDelegate(lifecycle)
        }
    }

    private fun dialogBuilder(prompt: BasePrompt): AlertDialog.Builder {
        val activity = findActivity(context)
            ?: throw IllegalStateException("Prompt host activity is unavailable")
        return AlertDialog.Builder(activity).apply {
            prompt.title?.let(::setTitle)
            setNegativeButton(android.R.string.cancel) { _, _ -> cancelActive() }
        }
    }

    private fun installDialogs(
        current: ActivePrompt,
        builder: AlertDialog.Builder,
    ) {
        builder.setOnCancelListener { cancelActive(current) }
    }

    private fun showDialog(
        current: ActivePrompt,
        builder: AlertDialog.Builder,
    ) {
        val dialog = runCatching { builder.create() }.getOrNull()
        if (dialog == null) {
            cancelActive(current)
            return
        }
        current.dialog = dialog
        dialog.setOnDismissListener {
            if (!current.settled) cancelActive(current)
        }
        runCatching { dialog.show() }.onFailure {
            cancelActive(current)
        }
    }

    private fun showChoice(current: ActivePrompt) {
        val prompt = current.prompt as? ChoicePrompt ?: run {
            cancelActive(current)
            return
        }
        if (prompt.type !in SUPPORTED_CHOICE_TYPES) {
            cancelActive(current)
            return
        }

        prepareChoices(current, prompt)
        val adapter = ChoiceAdapter(current)
        current.adapter = adapter
        val builder = dialogBuilder(prompt)
            .setMessage(prompt.message)
            .setAdapter(adapter, null)
            .setOnCancelListener { cancelActive(current) }
        if (prompt.type != ChoicePrompt.Type.MENU) {
            builder.setPositiveButton(android.R.string.ok) { _, _ ->
                confirmChoice(current)
            }
        }

        val dialog = runCatching { builder.create() }.getOrNull()
        if (dialog == null) {
            cancelActive(current)
            return
        }
        current.dialog = dialog
        dialog.setOnDismissListener {
            if (!current.settled) cancelActive(current)
        }
        dialog.setOnShowListener {
            dialog.listView.setOnItemClickListener { _, _, position, _ ->
                val row = current.rows.getOrNull(position) ?: return@setOnItemClickListener
                selectChoice(current, row)
            }
            updateChoiceButton(current)
        }
        runCatching { dialog.show() }.onFailure {
            cancelActive(current)
        }
    }

    private fun updateChoice(current: ActivePrompt, prompt: ChoicePrompt) {
        if (!current.isChoice || current.settled) return
        hideDialog(current)
        current.prompt.setDelegate(null)
        current.prompt = prompt
        prompt.setDelegate(lifecycle)
        showChoice(current)
    }

    private fun prepareChoices(current: ActivePrompt, prompt: ChoicePrompt) {
        val rows = mutableListOf<ChoiceRow>()
        flattenChoices(prompt.choices, parentDisabled = false, rows)
        current.rows = rows
        current.selected.clear()
        for (row in rows) {
            if (row.kind != ROW_OPTION || !row.selectable || row.choice?.selected != true) continue
            current.selected += row
            if (prompt.type != ChoicePrompt.Type.MULTIPLE) break
        }
        current.adapter = null
    }

    private fun selectChoice(current: ActivePrompt, row: ChoiceRow) {
        if (current.settled || row.kind != ROW_OPTION || !row.selectable) return
        val prompt = current.prompt as? ChoicePrompt ?: return
        when (prompt.type) {
            ChoicePrompt.Type.MENU -> {
                val selected = row.choice ?: return
                confirmActive(current) { prompt.confirm(selected) }
            }

            ChoicePrompt.Type.SINGLE -> {
                current.selected.clear()
                current.selected += row
                current.adapter?.notifyDataSetChanged()
                updateChoiceButton(current)
            }

            ChoicePrompt.Type.MULTIPLE -> {
                if (!current.selected.remove(row)) current.selected += row
                current.adapter?.notifyDataSetChanged()
                updateChoiceButton(current)
            }
        }
    }

    private fun confirmChoice(current: ActivePrompt) {
        if (current.settled) return
        val prompt = current.prompt as? ChoicePrompt ?: return
        when (prompt.type) {
            ChoicePrompt.Type.SINGLE -> {
                val selected = current.selected.firstOrNull()?.choice ?: return
                confirmActive(current) { prompt.confirm(selected) }
            }

            ChoicePrompt.Type.MULTIPLE -> {
                val selected = current.selected
                    .mapNotNull { it.choice }
                    .toTypedArray()
                confirmActive(current) { prompt.confirm(selected) }
            }
        }
    }

    private fun updateChoiceButton(current: ActivePrompt) {
        val dialog = current.dialog ?: return
        val prompt = current.prompt as? ChoicePrompt ?: return
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE) ?: return
        positive.isEnabled = prompt.type != ChoicePrompt.Type.SINGLE || current.selected.isNotEmpty()
    }

    private fun confirmActive(
        current: ActivePrompt,
        confirm: () -> PromptResponse,
    ) {
        if (current.settled) return
        val response = runCatching(confirm).getOrNull()
        finish(current, response)
    }

    private fun cancelActive(target: ActivePrompt? = active) {
        val current = target ?: return
        if (current.settled) return
        val response = if (!current.prompt.isComplete) {
            runCatching { current.prompt.dismiss() }.getOrNull()
        } else {
            null
        }
        finish(current, response)
    }

    private fun finish(current: ActivePrompt, response: PromptResponse?) {
        if (current.settled) return
        current.settled = true
        if (active === current) active = null
        current.prompt.setDelegate(null)
        hideDialog(current)
        if (response != null) {
            current.result.complete(response)
        } else {
            current.result.completeExceptionally(CancellationException("Prompt is unavailable"))
        }
    }

    private fun hideDialog(current: ActivePrompt) {
        val dialog = current.dialog ?: return
        current.dialog = null
        current.adapter = null
        dialog.setOnDismissListener(null)
        dialog.setOnCancelListener(null)
        runCatching { dialog.dismiss() }
    }

    private fun flattenChoices(
        choices: Array<ChoicePrompt.Choice>?,
        parentDisabled: Boolean,
        output: MutableList<ChoiceRow>,
    ) {
        choices ?: return
        for (choice in choices) {
            if (choice.separator) {
                output += ChoiceRow(ROW_SEPARATOR, null, selectable = false)
            } else if (choice.items != null) {
                output += ChoiceRow(ROW_GROUP, choice, selectable = false)
                flattenChoices(choice.items, parentDisabled || choice.disabled, output)
            } else {
                output += ChoiceRow(
                    kind = ROW_OPTION,
                    choice = choice,
                    selectable = !(parentDisabled || choice.disabled),
                )
            }
        }
    }

    private inner class ChoiceAdapter(
        private val current: ActivePrompt,
    ) : BaseAdapter() {
        override fun getCount(): Int = current.rows.size

        override fun getItem(position: Int): Any = current.rows[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewTypeCount(): Int = 3

        override fun getItemViewType(position: Int): Int = current.rows[position].kind

        override fun areAllItemsEnabled(): Boolean = false

        override fun isEnabled(position: Int): Boolean =
            current.rows[position].selectable

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = current.rows[position]
            if (row.kind != ROW_OPTION) {
                val text = convertView as? TextView ?: LayoutInflater.from(current.activity)
                    .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
                if (row.kind == ROW_GROUP) {
                    text.text = row.choice?.label.orEmpty()
                    text.setTypeface(null, Typeface.BOLD)
                    text.alpha = 0.75f
                } else {
                    text.text = "--------------------"
                    text.setTypeface(null, Typeface.NORMAL)
                    text.alpha = 0.4f
                }
                text.isEnabled = false
                return text
            }

            val checked = convertView as? CheckedTextView
                ?: LayoutInflater.from(current.activity).inflate(
                    if ((current.prompt as? ChoicePrompt)?.type == ChoicePrompt.Type.MULTIPLE) {
                        android.R.layout.simple_list_item_multiple_choice
                    } else {
                        android.R.layout.simple_list_item_single_choice
                    },
                    parent,
                    false,
                ) as CheckedTextView
            checked.text = row.choice?.label.orEmpty()
            checked.isChecked = current.selected.contains(row)
            checked.isEnabled = row.selectable
            checked.alpha = if (row.selectable) 1f else 0.5f
            return checked
        }
    }

    private class ActivePrompt(
        var prompt: BasePrompt,
        val result: GeckoResult<PromptResponse>,
        val activity: Activity,
    ) {
        var dialog: AlertDialog? = null
        var rows: List<ChoiceRow> = emptyList()
        val selected: MutableList<ChoiceRow> = mutableListOf()
        var adapter: ChoiceAdapter? = null
        var settled = false

        val isChoice: Boolean
            get() = prompt is ChoicePrompt
    }

    private class ChoiceRow(
        val kind: Int,
        val choice: ChoicePrompt.Choice?,
        val selectable: Boolean,
    )
}

private fun findActivity(context: Context): Activity? {
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

private val SUPPORTED_CHOICE_TYPES = setOf(
    GeckoSession.PromptDelegate.ChoicePrompt.Type.MENU,
    GeckoSession.PromptDelegate.ChoicePrompt.Type.SINGLE,
    GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE,
)

private const val ROW_OPTION = 0
private const val ROW_GROUP = 1
private const val ROW_SEPARATOR = 2
