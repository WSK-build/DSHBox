package com.dshbox.app.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Touch-only extra-keys row shown above the soft keyboard.
 *
 * Two rows, each full-width and split evenly via [Modifier.weight], so the
 * keys stretch to fill narrow phones and resize gracefully on tablets.
 *
 * Every key is a pill [Surface] with its label centred both axes. The label
 * auto-shrinks its type size (via [TextMeasurer]) to the available key width,
 * so a narrow phone never clips the text — it simply renders smaller, while a
 * tablet keeps the full 14sp. Using a custom surface instead of an
 * [androidx.compose.material3.AssistChip] lets us drop the chip's default
 * 8dp horizontal label padding, giving the text more width to work with.
 *
 * Row 1 - primary controls: ESC / TAB / HOME / END / CTRL (sticky) / PASTE.
 * Row 2 - cursor + editing: arrows, Page-Up/Down, Backspace, Forward-Delete.
 *
 * Arrow / Home / End resolve their escape sequence at press-time through
 * [cursorKeyAppMode], which reads the terminal's live application-cursor-keys
 * state (DECCKM). Matching the library's KeyHandler, the sequence is "ESC O x"
 * (SS3) when that mode is active and "ESC [ x" (CSI) otherwise.
 */
@Composable
fun TerminalExtraKeysBar(
    ctrlEnabled: Boolean,
    onToggleCtrl: () -> Unit,
    onKeyBytes: (ByteArray) -> Unit,
    onPaste: () -> Unit,
    cursorKeyAppMode: () -> Boolean = { false },
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Row 1 - primary controls.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ExtraKey("ESC", Modifier.weight(1f)) { onKeyBytes(byteArrayOf(0x1B)) }
            ExtraKey("TAB", Modifier.weight(1f)) { onKeyBytes(byteArrayOf(0x09)) }
            ExtraKey("HOME", Modifier.weight(1f)) { onKeyBytes(cursorByte(cursorKeyAppMode(), ss3Letter = 'H', csiBody = "H")) }
            ExtraKey("END", Modifier.weight(1f)) { onKeyBytes(cursorByte(cursorKeyAppMode(), ss3Letter = 'F', csiBody = "F")) }
            ExtraKey("CTRL", Modifier.weight(1f), selected = ctrlEnabled, onClick = onToggleCtrl)
            ExtraKey("PASTE", Modifier.weight(1f), onClick = onPaste)
        }

        // Row 2 - cursor / paging / editing.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ExtraKey("↑", Modifier.weight(1f)) { onKeyBytes(cursorByte(cursorKeyAppMode(), ss3Letter = 'A', csiBody = "A")) }
            ExtraKey("↓", Modifier.weight(1f)) { onKeyBytes(cursorByte(cursorKeyAppMode(), ss3Letter = 'B', csiBody = "B")) }
            ExtraKey("←", Modifier.weight(1f)) { onKeyBytes(cursorByte(cursorKeyAppMode(), ss3Letter = 'D', csiBody = "D")) }
            ExtraKey("→", Modifier.weight(1f)) { onKeyBytes(cursorByte(cursorKeyAppMode(), ss3Letter = 'C', csiBody = "C")) }
            ExtraKey("PGUP", Modifier.weight(1f)) { onKeyBytes(csi("5~")) }
            ExtraKey("PGDN", Modifier.weight(1f)) { onKeyBytes(csi("6~")) }
            ExtraKey("BKSP", Modifier.weight(1f)) { onKeyBytes(byteArrayOf(0x7F)) }
            ExtraKey("DEL", Modifier.weight(1f)) { onKeyBytes(csi("3~")) }
        }
    }
}

/**
 * A single pill key.
 *
 * [selected] only affects the sticky CTRL key's highlight (primary container
 * surface + bold text); everything else passes it as false.
 */
@Composable
private fun ExtraKey(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        modifier = modifier.height(KEY_HEIGHT),
        shape = CircleShape,
        color = container,
        contentColor = content,
    ) {
        // Centre the label on both axes, leaving only a 2dp side inset so the
        // text gets the maximum width inside the pill.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            AutoSizeLabel(
                text = label,
                color = content,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * A single-line label that shrinks its [fontSize] to fit the width it is
 * given, capped at [MAX_LABEL_SP] (and floored at [MIN_LABEL_SP]).
 *
 * The width is measured at the natural size with a [TextMeasurer]; if it does
 * not fit the available width, the size is stepped down until it does. This
 * keeps every key's text visible and centred on narrow screens instead of
 * being clipped.
 */
@Composable
private fun AutoSizeLabel(
    text: String,
    color: Color,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier) {
        val availablePx = constraints.maxWidth
        val fontSize = fitFontSizeInPx(textMeasurer, text, fontWeight, availablePx)
        Text(
            text = text,
            fontSize = fontSize.sp,
            fontWeight = fontWeight,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Largest type size (in sp, between [MIN_LABEL_SP] and [MAX_LABEL_SP]) whose
 * natural single-line width fits within [availablePx].
 */
private fun fitFontSizeInPx(
    measurer: TextMeasurer,
    text: String,
    fontWeight: FontWeight,
    availablePx: Int,
): Float {
    var size = MAX_LABEL_SP
    while (size > MIN_LABEL_SP) {
        val widthPx = measurer.measure(
            text = AnnotatedString(text),
            style = TextStyle(fontSize = size.sp, fontWeight = fontWeight),
        ).size.width
        if (widthPx <= availablePx) return size
        size -= FONT_STEP_SP
    }
    return MIN_LABEL_SP
}

/** Builds an "ESC [ <body>" CSI sequence (e.g. Page-Up => "ESC [ 5 ~"). */
private fun csi(body: String): ByteArray =
    byteArrayOf(0x1B, '['.code.toByte()) + body.toByteArray(Charsets.US_ASCII)

/**
 * Cursor key sequence for arrows / Home / End.
 *
 * When application cursor keys mode (DECCKM) is active the terminal expects
 * "ESC O <letter>" (SS3); otherwise "ESC [ <csiBody>" (CSI). This mirrors the
 * library's `KeyHandler.getCode(...)` so touch keys behave like hardware keys.
 */
private fun cursorByte(cursorAppMode: Boolean, ss3Letter: Char, csiBody: String): ByteArray =
    if (cursorAppMode) {
        byteArrayOf(0x1B, 'O'.code.toByte()) + ss3Letter.toString().toByteArray(Charsets.US_ASCII)
    } else {
        csi(csiBody)
    }

/** Height of a single extra key. */
private val KEY_HEIGHT = 36.dp

/** Nominal (tablet / wide-screen) label size. */
private const val MAX_LABEL_SP = 14f

/** Smallest label size we will shrink to. */
private const val MIN_LABEL_SP = 7f

/** Per-step shrink while searching for a fitting size. */
private const val FONT_STEP_SP = 1f
