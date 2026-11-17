package com.nousresearch.hermes.android.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.Hermes
import com.nousresearch.hermes.android.ui.Hint
import com.nousresearch.hermes.android.ui.Page
import com.nousresearch.hermes.android.ui.SecondaryButton
import com.nousresearch.hermes.android.ui.SectionCaption

/** What the hub page hands over: [query] the field's text, [search] the hub for it, [browse] a page (from 1), [close] the page. */
class HubActions(val query: (String) -> Unit, val search: (String) -> Unit, val browse: (Long) -> Unit, val close: () -> Unit)

/**
 * The Skills Hub (capabilities-08), above the Skills page: a search over the whole hub, which first narrows "Available to
 * install" as it is typed; the last search's hits or browsed page with its paging; then the official skills not
 * installed. It shows only what the core answered; Back stays open while the core is asked (D11).
 */
@Composable
fun HubScreen(state: HubUi, actions: HubActions, modifier: Modifier = Modifier) {
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
        if (results != null) Results(results, state, actions)
        if (results?.items.isNullOrEmpty()) {
            SecondaryButton(stringResource(R.string.hub_browse), { actions.browse(1) }, Modifier.padding(top = 12.dp).testTag("hub-browse"),
                            enabled = !state.busy)
        }
        state.available()?.let { available ->
            SectionCaption(stringResource(R.string.hub_official), Modifier.testTag("hub-official"))
            available.forEach { HubRow(it, installed = false, enabled = !state.busy, onOpen = null) }
        }
    }
}

/** The last search's hits ("Results for …") or a browsed page ("Page n of m", with its paging); none says so. */
@Composable
private fun Results(results: HubResults, state: HubUi, actions: HubActions) {
    val caption = results.query?.let { stringResource(R.string.hub_results, it) }
        ?: stringResource(R.string.hub_page, results.page, results.pages)
    SectionCaption(caption, Modifier.testTag("hub-results"))
    if (results.items.isEmpty()) {
        Hint(stringResource(R.string.hub_no_results), Modifier.testTag("hub-no-results"))
    }
    results.items.forEach { HubRow(it, installed = it.name in state.installed, enabled = !state.busy, onOpen = null) }
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

/** A hub skill's pills, tagged `[prefix]-<kind>-<key>`: category, "Official" or the source, trust, "Installed". */
@Composable
private fun HubPills(item: HubItem, installed: Boolean, prefix: String) {
    val source = if (item.official) stringResource(R.string.hub_official_pill) else item.source
    if (item.category == null && source == null && item.trust == null && !installed) return
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        item.category?.let { Pill(categoryLabel(it), "$prefix-category-${item.key}") }
        source?.let { Pill(it, "$prefix-source-${item.key}") }
        item.trust?.let { Pill(it, "$prefix-trust-${item.key}") }
        if (installed) Pill(stringResource(R.string.hub_installed), "$prefix-installed-${item.key}", MaterialTheme.colorScheme.primary)
    }
}
