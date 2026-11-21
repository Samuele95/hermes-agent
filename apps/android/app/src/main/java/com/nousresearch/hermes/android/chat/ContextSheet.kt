package com.nousresearch.hermes.android.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.Hermes
import com.nousresearch.hermes.android.ui.Hint
import com.nousresearch.hermes.android.ui.PrimaryButton
import com.nousresearch.hermes.android.ui.SectionCaption
import com.nousresearch.hermes.bridge.protocol.ContextCategory

/** What the context sheet does (sessions-16/17): [open] it from the meter, [compress] with a focus (null = none), [close] it. */
class ContextActions(val open: () -> Unit, val compress: (focus: String?) -> Unit, val close: () -> Unit)

/**
 * The meter's detail as a bottom sheet over the chat (DC-B11): the fill, what fills it, and Compress. It cannot be
 * dismissed while the chat's session compresses ([compressing], D3). [running]: a reply is on its way or being
 * written, so Compress waits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextSheet(ui: ContextUi, running: Boolean, compressing: Boolean, onCompress: (focus: String?) -> Unit, onClose: () -> Unit) {
    // The swipe goes through the sheet's state, Back and an outside tap through onDismissRequest: all three wait for
    // Compress, read live. Not through its properties: material3 1.4.0 fixes the window's Back callback from
    // shouldDismissOnBackPress when the sheet opens, so a sheet opened mid-compress would never close on Back.
    val busy by rememberUpdatedState(compressing)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !busy })
    ModalBottomSheet(onDismissRequest = { if (!busy) onClose() }, modifier = Modifier.testTag("context-panel"), sheetState = sheet,
                     containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Header(ui.meter)
            Breakdown(ui)
            ui.error?.let { Hint(it, Modifier.padding(top = 8.dp).testTag("context-error"), color = MaterialTheme.colorScheme.error) }
            SectionCaption(stringResource(R.string.context_compress))
            Hint(stringResource(R.string.context_compress_hint))
            var focus by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(focus, { focus = it }, Modifier.fillMaxWidth().padding(top = 8.dp).testTag("context-focus"), singleLine = true,
                              enabled = !compressing, label = { Text(stringResource(R.string.context_focus)) },
                              placeholder = { Text(stringResource(R.string.context_focus_hint)) })
            if (compressing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp).height(2.dp))
                Hint(stringResource(R.string.context_compressing), Modifier.testTag("context-compressing"))
            }
            PrimaryButton(stringResource(R.string.context_compress), { onCompress(focus.trim().ifEmpty { null }) },
                          Modifier.padding(top = 12.dp).testTag("compress"), enabled = !compressing && !running)
            ui.outcome?.let { Text(it, Modifier.padding(top = 12.dp).testTag("context-outcome"), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** "Context usage", the tokens used of the window, and how full it is: the desktop's header. */
@Composable
private fun Header(meter: ContextMeter?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.context_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        meter?.let { m ->
            val approx = if (m.estimated) "~" else ""
            Text(stringResource(R.string.context_tokens, approx + compactTokens(m.used), compactTokens(m.max)),
                 style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    meter?.let { m ->
        val approx = if (m.estimated) "~" else ""
        Text(stringResource(R.string.context_full, "$approx${m.percent}%"), style = MaterialTheme.typography.bodyMedium,
             color = if (m.warns) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

/** What fills the context, as the core split it: a segmented bar and one row per category, or why there is none. */
@Composable
private fun Breakdown(ui: ContextUi) {
    val breakdown = ui.breakdown
    when {
        breakdown == null -> if (ui.error == null) Hint(stringResource(R.string.context_loading), Modifier.padding(top = 12.dp))
        breakdown.categories.isEmpty() -> Hint(stringResource(R.string.context_empty), Modifier.padding(top = 12.dp))
        else -> {
            // The segments split the categories' own sum (the desktop's segmentTotal); a weight must be positive.
            Row(Modifier.padding(top = 12.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Hermes.colors.stroke)) {
                breakdown.categories.filter { it.tokens > 0 }.forEach { c ->
                    Box(Modifier.weight(c.tokens.toFloat()).fillMaxHeight().background(categoryColor(c.id)))
                }
            }
            Column(Modifier.padding(top = 8.dp)) { breakdown.categories.forEach { CategoryRow(it) } }
        }
    }
}

@Composable
private fun CategoryRow(c: ContextCategory) {
    Row(Modifier.testTag("context-category").padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(categoryColor(c.id)))
        Text(c.label, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("~" + compactTokens(c.tokens), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A category's colour (D8): the core names CSS variables, which the desktop resolves in `styles.css`; these are its
 * light values (its `color-mix` results computed), the system prompt's from the theme so it adapts.
 */
@Composable
private fun categoryColor(id: String): Color = when (id) {
    "system_prompt" -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    "tool_definitions" -> Color(0xFF9E94D5)
    "rules" -> Color(0xFF1F8A65)
    "skills" -> Color(0xFFC08532)
    "mcp" -> Color(0xFFC14A7A)
    "subagent_definitions" -> Color(0xFF1760DB)
    "memory" -> Color(0xFFD67446)
    "conversation" -> Color(0xFF4C7F8C)
    else -> Hermes.colors.textTertiary
}
