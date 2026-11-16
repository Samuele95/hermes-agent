package com.nousresearch.hermes.android.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.CodeFont
import com.nousresearch.hermes.android.ui.Hermes
import com.nousresearch.hermes.android.ui.HermesIcons

/**
 * A tool the agent ran: one quiet line, its name in the code face, how long it took and a diff's size; a tap opens what
 * it ran and what came back ([ToolDetail.of]), each part with its own Copy when [onCopy] is given (chat-08, bet 6).
 * It reads only [tool]: opening it never asks the core for anything.
 */
@Composable
internal fun ToolRow(tool: Entry.Tool, onCopy: ((String) -> Unit)?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // Kept by the transcript item's key (the entry's stableId), so it follows this call as the transcript grows.
    var open by rememberSaveable { mutableStateOf(false) }
    val notKept = tool.done && !tool.resultKept
    val expandable = notKept || tool.args?.isNotEmpty() == true || tool.result != null || tool.diff != null
    val stats = remember(tool.diff) { tool.diff?.let(ToolSection.Diff::of) }
    Column(Modifier.fillMaxWidth()) {
        val (label, state) = stringResource(R.string.tool_details) to stringResource(if (open) R.string.tool_expanded else R.string.tool_collapsed)
        val toggle = if (expandable) Modifier.clickable(onClickLabel = label) { open = !open }.semantics { stateDescription = state } else Modifier
        Row(Modifier.fillMaxWidth().then(toggle).testTag("tool-row").heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(HermesIcons.Tool, null, Modifier.size(14.dp), tint = Hermes.colors.textTertiary)
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontFamily = CodeFont, fontSize = 13.sp)) { append(tool.name) }
                tool.summary?.takeIf { tool.done && it.isNotBlank() }?.let { withStyle(SpanStyle(color = muted)) { append(" — $it") } }
            }, Modifier.weight(1f, fill = false).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                 overflow = TextOverflow.Ellipsis)
            // A summary the core wrote already ends with the time it measured (D6).
            tool.durationS?.takeIf { tool.done && tool.summary.isNullOrBlank() }?.let(ToolDetail::duration)?.takeIf { it.isNotEmpty() }?.let {
                Text(it, Modifier.padding(start = 8.dp).testTag("tool-duration"), style = MaterialTheme.typography.labelSmall, color = muted)
            }
            stats?.takeIf { tool.done && it.added + it.removed > 0 }?.let { DiffStats(it) }
            if (!tool.done) CircularProgressIndicator(Modifier.padding(start = 8.dp).size(12.dp), strokeWidth = 1.5.dp, color = muted)
            if (expandable) Icon(if (open) HermesIcons.ChevronDown else HermesIcons.ChevronRight, null, Modifier.padding(start = 6.dp).size(14.dp), tint = muted)
        }
        if (open) ToolBody(tool, notKept, onCopy)
    }
}

/** A diff's size beside the row's name: "+N" in the added colour, "−N" in the removed one, each only when not 0. */
@Composable
private fun DiffStats(stats: ToolSection.Diff) {
    val colors = Hermes.colors
    Text(buildAnnotatedString {
        if (stats.added > 0) withStyle(SpanStyle(color = colors.added)) { append("+${stats.added}") }
        if (stats.added > 0 && stats.removed > 0) append(" ")
        if (stats.removed > 0) withStyle(SpanStyle(color = colors.removed)) { append("−${stats.removed}") }
    }, Modifier.padding(start = 8.dp).testTag("tool-diff-stats"), style = TextStyle(fontFamily = CodeFont, fontSize = 12.sp))
}

/** The open row: its sections in [ToolDetail.of]'s order, the "not kept" line before the payload. Built only while open (§6). */
@Composable
private fun ToolBody(tool: Entry.Tool, notKept: Boolean, onCopy: ((String) -> Unit)?) {
    val sections = remember(tool) { ToolDetail.of(tool) }
    Column(Modifier.fillMaxWidth().padding(start = 22.dp, top = 6.dp).testTag("tool-detail"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        sections.forEach { section ->
            when (section) {
                is ToolSection.Command -> CommandSection(section, onCopy)
                is ToolSection.Search -> SearchSection(section, onCopy)
                is ToolSection.Diff -> DiffSection(section, onCopy)
                is ToolSection.Output -> OutputSection(section, tool.failed, onCopy)
                is ToolSection.Payload -> Unit  // always last: drawn after the "not kept" line
            }
        }
        if (notKept) Text(stringResource(R.string.tool_output_not_kept), Modifier.testTag("tool-not-kept"), style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant)
        sections.filterIsInstance<ToolSection.Payload>().firstOrNull()?.let { PayloadSection(it, onCopy) }
    }
}

/**
 * One part's box, as the transcript's other inline widgets (tool details' surface): a header with [caption] on the left
 * (or [lead] in its place) and the part's Copy on the right when [onCopy] is given, then [content].
 */
@Composable
private fun SectionBox(caption: String?, copy: String, copyLabel: String, copyTag: String, onCopy: ((String) -> Unit)?,
                       modifier: Modifier = Modifier, lead: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(modifier.fillMaxWidth().background(Hermes.colors.widget, shape).border(1.dp, Hermes.colors.stroke, shape)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { if (caption != null) Text(caption, style = MaterialTheme.typography.labelSmall, color = Hermes.colors.textTertiary) else lead() }
            onCopy?.let { CopyButton(copy, it, copyLabel, Modifier.testTag(copyTag)) }
        }
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), content = content)
    }
}

/** What the agent ran: "$ " and the command (or the code), with the terminal's "exit N" chip in the header. */
@Composable
private fun CommandSection(section: ToolSection.Command, onCopy: ((String) -> Unit)?) {
    val primary = MaterialTheme.colorScheme.primary
    SectionBox(null, section.copy, stringResource(R.string.tool_copy_command), "copy-command", onCopy, lead = {
        section.exit?.let { exit ->
            Text(stringResource(R.string.tool_exit, exit), Modifier.testTag("tool-exit"), style = MaterialTheme.typography.labelSmall,
                 color = if (exit == 0L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
    }) {
        Text(buildAnnotatedString { withStyle(SpanStyle(color = primary)) { append("$ ") }; append(section.text) }, Modifier.testTag("tool-command"),
             style = TextStyle(fontFamily = CodeFont, fontSize = 13.sp, lineHeight = 19.sp))
    }
}

/** A web search: the query, then each hit's title, link and snippet. */
@Composable
private fun SearchSection(section: ToolSection.Search, onCopy: ((String) -> Unit)?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    SectionBox(stringResource(R.string.tool_search), section.copy, stringResource(R.string.tool_copy_results), "copy-results", onCopy,
               Modifier.testTag("tool-search")) {
        if (section.query.isNotBlank()) Text(section.query, style = MaterialTheme.typography.bodyMedium)
        if (section.hits.isNotEmpty()) {
            Text(stringResource(R.string.tool_search_results), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall,
                 color = Hermes.colors.textTertiary)
            section.hits.forEach { hit ->
                Column(Modifier.padding(top = 6.dp)) {
                    if (hit.title.isNotBlank()) Text(hit.title, style = MaterialTheme.typography.bodyMedium)
                    if (hit.url.isNotBlank()) Text(hit.url, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    hit.snippet?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

/** An edit's diff: added lines in the added colour, removed ones in the removed colour, scrolled sideways like code. */
@Composable
private fun DiffSection(section: ToolSection.Diff, onCopy: ((String) -> Unit)?) {
    val (added, removed) = Hermes.colors.added to Hermes.colors.removed
    val text = remember(section.text, added, removed) {
        buildAnnotatedString {
            section.text.lines().forEachIndexed { i, line ->
                if (i > 0) append("\n")
                val color = if (line.startsWith("+") && !line.startsWith("+++")) added else if (line.startsWith("-") && !line.startsWith("---")) removed else null
                if (color == null) append(line) else withStyle(SpanStyle(color = color, background = color.copy(alpha = 0.12f))) { append(line) }
            }
        }
    }
    SectionBox(stringResource(R.string.tool_diff), section.copy, stringResource(R.string.tool_copy_diff), "copy-diff", onCopy) {
        Text(text, Modifier.horizontalScroll(rememberScrollState()).testTag("tool-diff"),
             style = TextStyle(fontFamily = CodeFont, fontSize = 12.sp, lineHeight = 18.sp), softWrap = false)
    }
}

/** What the tool printed, plain monospace in its own scrolling box; "Error details" for a call the core says failed (D7). */
@Composable
private fun OutputSection(section: ToolSection.Output, failed: Boolean, onCopy: ((String) -> Unit)?) {
    SectionBox(stringResource(if (failed) R.string.tool_error_details else R.string.tool_output), section.copy,
               stringResource(R.string.tool_copy_output), "copy-output", onCopy) {
        ScrollingCode(section.text, "tool-output", 12)
    }
}

/** The call as the core sent it, behind its own disclosure, closed until asked for (DC-P6). */
@Composable
private fun PayloadSection(section: ToolSection.Payload, onCopy: ((String) -> Unit)?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var shown by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.clickable { shown = !shown }.testTag("tool-payload-toggle").heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (shown) HermesIcons.ChevronDown else HermesIcons.ChevronRight, null, Modifier.size(14.dp), tint = muted)
            Text(stringResource(R.string.tool_payload), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelMedium, color = muted)
        }
        if (shown) {
            val (arguments, result) = stringResource(R.string.tool_arguments) to stringResource(R.string.tool_result)
            val text = listOfNotNull(section.args?.let { "$arguments:\n$it" }, section.result?.let { "$result:\n$it" }).joinToString("\n\n")
            SectionBox(null, section.copy, stringResource(R.string.tool_copy_payload), "copy-payload", onCopy) {
                ScrollingCode(text, "tool-payload", 11)
            }
        }
    }
}

/** Selectable monospace [text] ([size] sp) in a phone-sized box that scrolls on its own past 280 dp (Desktop's TerminalOutput). */
@Composable
private fun ScrollingCode(text: String, tag: String, size: Int) {
    Box(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
        SelectionContainer { Text(text, Modifier.testTag(tag), style = TextStyle(fontFamily = CodeFont, fontSize = size.sp, lineHeight = (size + 6).sp)) }
    }
}
