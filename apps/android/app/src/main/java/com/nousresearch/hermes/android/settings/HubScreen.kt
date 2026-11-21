package com.nousresearch.hermes.android.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.CodeBox
import com.nousresearch.hermes.android.ui.Hermes
import com.nousresearch.hermes.android.ui.Hint
import com.nousresearch.hermes.android.ui.Page
import com.nousresearch.hermes.android.ui.PrimaryButton
import com.nousresearch.hermes.android.ui.SecondaryButton
import com.nousresearch.hermes.android.ui.SectionCaption

/**
 * What the hub page hands over: [query] the field's text, [search] the hub for it, [browse] a page (from 1), [close] the
 * page; with [item], a row opens its preview.
 */
class HubActions(val query: (String) -> Unit, val search: (String) -> Unit, val browse: (Long) -> Unit, val close: () -> Unit,
                 val item: HubItemActions? = null)

/** The preview's: [preview] a row by its key, [closePreview], [scan] it again and [install] it, by the identifier the core resolved. */
class HubItemActions(val preview: (String) -> Unit, val closePreview: () -> Unit, val scan: (String) -> Unit, val install: (String) -> Unit)

/**
 * The Skills Hub (capabilities-08), above the Skills page: a search over the whole hub, which first narrows "Available to
 * install" as it is typed; the last search's hits or browsed page with its paging; then the official skills not
 * installed. It shows only what the core answered; Back stays open while the core is asked (D11). With [HubActions.item],
 * a tap on a row opens its preview and security scan, where Install is offered as [mayInstall] says (D1-D3).
 */
@Composable
fun HubScreen(state: HubUi, actions: HubActions, modifier: Modifier = Modifier) {
    val open = state.open
    val item = actions.item
    if (open != null && item != null) {
        PreviewPage(state, open, item, modifier)
        return
    }
    val onOpen: (HubItem) -> (() -> Unit)? = { row -> item?.let { { it.preview(row.key) } } }
    BackHandler(onBack = actions.close)
    Page(stringResource(R.string.hub_title), modifier.testTag("hub"), onBack = actions.close, backTag = "hub-close", busy = state.busy) {
        Hint(stringResource(R.string.hub_explain))
        state.error?.let { Hint(it, Modifier.padding(top = 12.dp).testTag("hub-error"), color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(state.query, actions.query, Modifier.weight(1f).testTag("hub-search"), singleLine = true,
                              label = { Text(stringResource(R.string.hub_search)) },
                              keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                              keyboardActions = KeyboardActions(onSearch = { actions.search(state.query) }))
            TextButton({ actions.search(state.query) }, Modifier.padding(start = 4.dp).testTag("hub-search-run"),
                       enabled = !state.busy && state.query.isNotBlank()) {
                Text(stringResource(if (state.busy) R.string.hub_searching else R.string.hub_search_run))
            }
        }
        val results = state.items
        if (results != null) Results(results, state, actions, onOpen)
        if (results?.items.isNullOrEmpty()) {
            SecondaryButton(stringResource(R.string.hub_browse), { actions.browse(1) }, Modifier.padding(top = 12.dp).testTag("hub-browse"),
                            enabled = !state.busy)
        }
        state.available()?.let { available ->
            SectionCaption(stringResource(R.string.hub_official), Modifier.testTag("hub-official"))
            available.forEach { HubRow(it, installed = false, enabled = !state.busy, onOpen = onOpen(it)) }
        }
    }
}

/** The last search's hits ("Results for …") or a browsed page ("Page n of m", with its paging); none says so. */
@Composable
private fun Results(results: HubResults, state: HubUi, actions: HubActions, onOpen: (HubItem) -> (() -> Unit)?) {
    val caption = results.query?.let { stringResource(R.string.hub_results, it) }
        ?: stringResource(R.string.hub_page, results.page, results.pages)
    SectionCaption(caption, Modifier.testTag("hub-results"))
    if (results.items.isEmpty()) {
        Hint(stringResource(R.string.hub_no_results), Modifier.testTag("hub-no-results"))
    }
    results.items.forEach { HubRow(it, installed = it.name in state.installed, enabled = !state.busy, onOpen = onOpen(it)) }
    if (results.query == null && (results.page > 1 || results.page < results.pages)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (results.page > 1) {
                SecondaryButton(stringResource(R.string.hub_previous), { actions.browse(results.page - 1) }, Modifier.testTag("hub-previous"),
                                enabled = !state.busy)
            }
            if (results.page < results.pages) {
                SecondaryButton(stringResource(R.string.hub_next), { actions.browse(results.page + 1) }, Modifier.testTag("hub-next"),
                                enabled = !state.busy)
            }
        }
    }
}

/**
 * One hub skill: its name, two lines of description, and its pills: the category, "Official" or the source, the trust as
 * the core says it, and "Installed" when [installed]. With [onOpen] a tap opens it, not while [enabled] is false.
 */
@Composable
private fun HubRow(item: HubItem, installed: Boolean, enabled: Boolean, onOpen: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().testTag("hub-item-${item.key}"), verticalAlignment = Alignment.CenterVertically) {
        val text = Modifier.weight(1f).padding(vertical = 8.dp)
        Column(if (onOpen == null) text else text.clickable(enabled = enabled, onClick = onOpen)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge)
            if (item.description.isNotBlank()) {
                Text(item.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                     maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            HubPills(item, installed, "hub")
        }
    }
    HorizontalDivider(color = Hermes.colors.stroke)
}

/** A hub skill's pills, tagged `[prefix]-<kind>-<key>`: category, "Official" or the source, trust, "Installed", then [tags]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HubPills(item: HubItem, installed: Boolean, prefix: String, tags: List<String> = emptyList()) {
    val source = if (item.official) stringResource(R.string.hub_official_pill) else item.source
    if (item.category == null && source == null && item.trust == null && !installed && tags.isEmpty()) return
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item.category?.let { Pill(categoryLabel(it), "$prefix-category-${item.key}") }
        source?.let { Pill(it, "$prefix-source-${item.key}") }
        item.trust?.let { Pill(it, "$prefix-trust-${item.key}") }
        if (installed) Pill(stringResource(R.string.hub_installed), "$prefix-installed-${item.key}", MaterialTheme.colorScheme.primary)
        tags.forEach { Pill(it, "$prefix-tag-$it") }
    }
}

/**
 * A hub skill's preview (capabilities-08): its description and pills, then its security scan, run at once (D1); Install
 * once [mayInstall] says so (D1-D3), "Installing..." while it runs, when Back waits; a refusal in the core's words with
 * "View scan" when the scan blocked it (D4); then the core's SKILL.md preview, selectable. The verdict reads in
 * Desktop's words, any other as the core says it.
 */
@Composable
private fun PreviewPage(state: HubUi, open: HubPreview, actions: HubItemActions, modifier: Modifier) {
    val idle = state.installing == null
    val key = open.item.key
    BackHandler(enabled = idle, onBack = actions.closePreview)
    Page(open.item.name, modifier.testTag("hub-preview"), onBack = actions.closePreview, backTag = "hub-preview-close", backEnabled = idle,
         busy = state.busy) {
        Hint(open.item.description.ifBlank { stringResource(R.string.skill_no_description) }, Modifier.testTag("hub-preview-description"))
        HubPills(open.item, installed = false, prefix = "hub-preview", tags = open.tags)
        state.notice?.let { name ->
            Column(Modifier.padding(top = 12.dp).testTag("hub-notice")) {
                Text(stringResource(R.string.hub_installed_notice, name), style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.primary)
                Hint(stringResource(R.string.skills_notice_new_chats, name))
            }
        }
        state.error?.let { Hint(it, Modifier.padding(top = 12.dp).testTag("hub-error"), color = MaterialTheme.colorScheme.error) }
        val refused = open.scan?.allowed == false
        ScanSection(open, refused, enabled = !state.busy) { actions.scan(key) }
        if (open.item.name in state.installed) {
            Row(Modifier.padding(top = 12.dp)) {
                Pill(stringResource(R.string.hub_installed), "hub-preview-installed", MaterialTheme.colorScheme.primary)
            }
        } else if (!refused) {
            PrimaryButton(stringResource(if (state.installing != null) R.string.hub_installing else R.string.hub_install), { actions.install(key) },
                          Modifier.padding(top = 12.dp).testTag("hub-install"), enabled = state.mayInstall())
        }
        state.blocked?.let { block ->
            Column(Modifier.padding(top = 12.dp).testTag("hub-blocked")) {
                Text(stringResource(R.string.hub_blocked_title, block.name), style = MaterialTheme.typography.bodyLarge,
                     color = MaterialTheme.colorScheme.error)
                Hint(block.words)
                if (block.byScan) {
                    TextButton({ actions.scan(key) }, Modifier.testTag("hub-view-scan"), enabled = !state.busy) {
                        Text(stringResource(R.string.hub_view_scan))
                    }
                }
            }
        }
        SectionCaption(stringResource(R.string.hub_skill_md))
        val skillMd = open.skillMd
        if (skillMd != null) SelectionContainer { CodeBox(skillMd, Modifier.testTag("hub-preview-text")) }
        else Hint(stringResource(R.string.hub_no_preview))
        Hint(stringResource(R.string.settings_new_chats), Modifier.padding(top = 12.dp).testTag("hub-new-chats"))
    }
}

/**
 * The preview's "Security scan": "Scanning..." until the bridge answers; then the verdict, the source's trust, the
 * policy's answer ([refused]) and every finding in the code face; or the bridge's words and "Scan again" ([onScan]).
 */
@Composable
private fun ScanSection(open: HubPreview, refused: Boolean, enabled: Boolean, onScan: () -> Unit) {
    val scan = open.scan
    val failed = open.scanFailed
    Column(Modifier.testTag("hub-scan")) {
        SectionCaption(stringResource(R.string.hub_scan_title))
        when {
            scan != null -> {
                Text(verdictWord(scan.verdict), style = MaterialTheme.typography.bodyLarge,
                     color = if (scan.verdict == "safe") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Hint(stringResource(R.string.hub_trust_source, scan.trust))
                Text(stringResource(if (refused) R.string.hub_policy_block else R.string.hub_policy_allow), Modifier.testTag("hub-policy"),
                     style = MaterialTheme.typography.bodyMedium,
                     color = if (refused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (scan.findings.isEmpty()) Hint(stringResource(R.string.hub_no_findings))
                else {
                    Hint(pluralStringResource(R.plurals.hub_findings, scan.findings.size, scan.findings.size))
                    SelectionContainer(Modifier.padding(top = 6.dp)) {
                        CodeBox(scan.findings.joinToString("\n"), Modifier.testTag("hub-findings"))
                    }
                }
            }
            failed != null -> Column(Modifier.testTag("hub-scan-failed")) {
                Text(stringResource(R.string.hub_scan_failed), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                Hint(failed)
                TextButton(onScan, Modifier.testTag("hub-scan-again"), enabled = enabled) { Text(stringResource(R.string.hub_scan_again)) }
            }
            else -> Hint(stringResource(R.string.hub_scanning), Modifier.testTag("hub-scanning"))
        }
    }
}

/** The core's verdict in Desktop's words; any other as the core says it. */
@Composable
private fun verdictWord(verdict: String): String = when (verdict) {
    "safe" -> stringResource(R.string.hub_verdict_safe)
    "caution" -> stringResource(R.string.hub_verdict_caution)
    "dangerous" -> stringResource(R.string.hub_verdict_dangerous)
    else -> verdict
}
