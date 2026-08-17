package com.dshbox.app.ui.sandbox

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.dshbox.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sandbox terminal. One proot bash process per session (survives tab switches
 * because [MainScreen] keeps every tab composed). The output area supports
 * long-press text selection; the keypad below provides the single keys a phone
 * keyboard lacks: 换行/空格/Tab/Esc/Ctrl/Alt/方向键/退格/Home/End, plus a
 * keyboard summon key. Letters and symbols come from the device keyboard.
 */
@Composable
fun TerminalScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNewTerminal: () -> Unit = {},
    clearSignal: Int = 0,
    isActiveTab: Boolean = true,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }

    var currentPath by remember { mutableStateOf("/root") }
    val prompt = stringResource(R.string.terminal_prompt)
    var output by remember { mutableStateOf("") }
    var inputState by remember { mutableStateOf(TextFieldValue("")) }
    var ctrlDown by remember { mutableStateOf(false) }
    var altDown by remember { mutableStateOf(false) }
    var keyboardShown by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableIntStateOf(-1) }
    val shellProcess = remember { mutableStateOf<Process?>(null) }

    // Only the active tab owns the back key, so a hidden terminal session can
    // neither swallow other tabs' back presses nor be destroyed by them.
    BackHandler(enabled = isActiveTab, onBack = onBack)

    fun appendOutput(text: String) {
        output = if (output.isEmpty()) text else output + text
    }

    // 清屏 from the top action row.
    LaunchedEffect(clearSignal) {
        if (clearSignal > 0) output = ""
    }

    LaunchedEffect(output) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    LaunchedEffect(Unit) {
        try {
            val process = createSandboxShell(context)
            shellProcess.value = process
            scope.launch(Dispatchers.IO) {
                try {
                    process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        if (line.startsWith("WARNING: linker:")) return@forEachLine
                        scope.launch(Dispatchers.Main) {
                            appendOutput("$line\n")
                        }
                    }
                } catch (_: java.io.IOException) {
                    // Stream closed by process destroy; expected on session close.
                }
            }
        } catch (t: Throwable) {
            appendOutput("\n[启动终端失败: ${t.message ?: t.javaClass.simpleName}]\n")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            shellProcess.value?.destroy()
            shellProcess.value = null
        }
    }

    fun sendBytes(bytes: ByteArray) {
        val process = shellProcess.value ?: return
        scope.launch(Dispatchers.IO) {
            try {
                process.outputStream.write(bytes)
                process.outputStream.flush()
            } catch (_: Throwable) {
                // The shell may already be gone.
            }
        }
    }

    fun runCommand() {
        val userCommand = inputState.text.trim()
        if (userCommand.isEmpty()) return
        val process = shellProcess.value ?: return

        appendOutput(prompt + userCommand + "\n")
        inputState = TextFieldValue("")
        history.add(userCommand)
        historyIndex = -1

        scope.launch(Dispatchers.IO) {
            try {
                process.outputStream.write((userCommand + "\n").toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    appendOutput("\n[执行失败: ${t.message ?: t.javaClass.simpleName}]\n")
                }
            }
        }
    }

    fun insertAtCursor(text: String) {
        val sel = inputState.selection
        val cur = inputState.text
        val start = sel.start.coerceIn(0, cur.length)
        val end = sel.end.coerceIn(start, cur.length)
        val newText = cur.substring(0, start) + text + cur.substring(end)
        inputState = TextFieldValue(newText, selection = TextRange(start + text.length))
    }

    fun backspace() {
        val sel = inputState.selection
        val cur = inputState.text
        if (sel.start == sel.end && sel.start == 0) return
        val start = if (sel.start == sel.end) (sel.start - 1).coerceAtLeast(0) else sel.start
        val end = if (sel.start == sel.end) sel.start else sel.end
        val newText = cur.substring(0, start) + cur.substring(end.coerceAtMost(cur.length))
        inputState = TextFieldValue(newText, selection = TextRange(start))
    }

    fun moveCursor(delta: Int) {
        val sel = inputState.selection
        val pos = (sel.start + delta).coerceIn(0, inputState.text.length)
        inputState = inputState.copy(selection = TextRange(pos))
    }

    fun historyUp() {
        if (history.isEmpty()) return
        historyIndex = if (historyIndex <= 0) 0 else historyIndex - 1
        val text = history[historyIndex]
        inputState = TextFieldValue(text, selection = TextRange(text.length))
    }

    fun historyDown() {
        if (historyIndex < 0) return
        historyIndex++
        if (historyIndex >= history.size) {
            historyIndex = -1
            inputState = TextFieldValue("")
        } else {
            val text = history[historyIndex]
            inputState = TextFieldValue(text, selection = TextRange(text.length))
        }
    }

    fun toggleKeyboard() {
        // toggleSoftInput(0, 0) force-switches the IME between shown and
        // hidden. Explicit show/hide calls are unreliable on some vivo builds
        // (floating keyboard: no ime insets, hideSoftInputFromWindow ignored),
        // while the toggle path always flips the current state.
        focusRequester.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        view.post { imm.toggleSoftInput(0, 0) }
        keyboardShown = !keyboardShown
    }

    // Two independent layers so the IME never disturbs the output weight:
    //  - output layer: padded at the bottom by (measured keypad height + IME
    //    inset), so its weighted box fills exactly down to the keypad and the
    //    scrollable text is never covered;
    //  - bottom layer: keypad + input, aligned to the bottom and lifted by
    //    imePadding(). On devices that resize the window the ime inset is
    //    consumed by the window, so this works with and without resizing.
    val density = LocalDensity.current
    // IME insets are measured from the SCREEN bottom and therefore include
    // the app's bottom NavigationBar (Material3 fixed 80dp), while our
    // content area already ends above that NavigationBar. Note that
    // WindowInsets.navigationBars reads 0 here because Scaffold's bottomBar
    // has consumed it, so subtract the NavigationBar's own height instead.
    // (On devices whose window actually resizes above the IME, the content
    // area ends at the keyboard top, so the coerceAtLeast(0) keeps the
    // padding at zero there.)
    val keyboardInsetDp = with(density) {
        (WindowInsets.ime.getBottom(density) - 80.dp.roundToPx())
            .coerceAtLeast(0)
            .toDp()
    }
    var bottomBlockH by remember { mutableStateOf(0) }
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = with(density) { bottomBlockH.toDp() } + keyboardInsetDp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(12.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = output.ifEmpty { prompt },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFE0E0E0),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = keyboardInsetDp)
                // Measure AFTER the keyboard padding: this reports the
                // keypad's own content height, so the output padding below is
                // exactly keypadHeight + keyboardInset (no double-counting).
                .onSizeChanged { bottomBlockH = it.height },
        ) {
            HorizontalDivider()

        // Row 1: keyboard summon + single keys a phone keyboard lacks.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TerminalKey(
                label = stringResource(R.string.terminal_key_keyboard),
                active = keyboardShown,
            ) { toggleKeyboard() }
            TerminalKey(label = stringResource(R.string.terminal_key_enter)) { runCommand() }
            TerminalKey(label = stringResource(R.string.terminal_key_space)) { insertAtCursor(" ") }
            TerminalKey(label = stringResource(R.string.terminal_key_tab)) { insertAtCursor("\t") }
            TerminalKey(label = stringResource(R.string.terminal_key_esc)) { inputState = TextFieldValue("") }
            TerminalKey(label = stringResource(R.string.terminal_key_ctrl), active = ctrlDown) {
                ctrlDown = !ctrlDown
                altDown = false
            }
            TerminalKey(label = stringResource(R.string.terminal_key_alt), active = altDown) {
                altDown = !altDown
                ctrlDown = false
            }
        }

        // Row 2: cursor / history / editing keys.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TerminalKey(label = stringResource(R.string.terminal_key_up)) { historyUp() }
            TerminalKey(label = stringResource(R.string.terminal_key_down)) { historyDown() }
            TerminalKey(label = stringResource(R.string.terminal_key_left)) { moveCursor(-1) }
            TerminalKey(label = stringResource(R.string.terminal_key_right)) { moveCursor(1) }
            TerminalKey(label = stringResource(R.string.terminal_key_backspace)) { backspace() }
            TerminalKey(label = stringResource(R.string.terminal_key_home)) {
                inputState = inputState.copy(selection = TextRange(0))
            }
            TerminalKey(label = stringResource(R.string.terminal_key_end)) {
                inputState = inputState.copy(selection = TextRange(inputState.text.length))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = inputState,
                onValueChange = { newValue ->
                    // Ctrl/Alt armed: the next typed character goes to the
                    // shell as a control sequence instead of into the field.
                    if (ctrlDown || altDown) {
                        val old = inputState.text
                        val added = extractAdded(old, newValue.text)
                        if (added.isNotEmpty()) {
                            if (ctrlDown) {
                                sendBytes(byteArrayOf((added[0].code and 0x1f).toByte()))
                            } else {
                                sendBytes(
                                    byteArrayOf(0x1b) + added[0].toString().toByteArray(Charsets.UTF_8),
                                )
                            }
                            ctrlDown = false
                            altDown = false
                            return@OutlinedTextField
                        }
                    }
                    inputState = newValue
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.terminal_input_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { runCommand() }),
            )
        }
        }
    }
}

/** Small flex key for the auxiliary keypad. */
@Composable
private fun RowScope.TerminalKey(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    OutlinedButton(
        shape = MaterialTheme.shapes.medium,
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .height(40.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Returns the substring added between [oldText] and [newText] (or ""). */
private fun extractAdded(oldText: String, newText: String): String {
    var prefix = 0
    while (prefix < oldText.length && prefix < newText.length && oldText[prefix] == newText[prefix]) {
        prefix++
    }
    var suffix = 0
    while (
        suffix < oldText.length - prefix &&
        suffix < newText.length - prefix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) {
        suffix++
    }
    return newText.substring(prefix, newText.length - suffix)
}
