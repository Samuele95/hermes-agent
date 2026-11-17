package com.nousresearch.hermes.android.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.CodeBox
import com.nousresearch.hermes.android.ui.Hermes
import com.nousresearch.hermes.android.ui.HermesIcons
import com.nousresearch.hermes.android.ui.Hint
import com.nousresearch.hermes.android.ui.Page
import com.nousresearch.hermes.android.ui.SectionLabel

/**
 * What the page does beyond its switches (F10): [query] narrows the list, [refresh] rescans the core's skills, [read]
 * opens one skill and [closeSkill] returns to the list.
 */
class SkillsActions(val query: (String) -> Unit, val refresh: () -> Unit, val read: (String) -> Unit, val closeSkill: () -> Unit)

/**
 * The core's skills by category, each category's switch and each skill's own (F11). It shows only what the core last
 * answered; while a change is written, every switch is off. With [actions] (F10): a search over name, description and
 * category, a menu that refreshes the core's skills, each row's provenance badge and use count, and a tap that opens the
 * skill's SKILL.md; while a search is typed the category switches hide (D8).
 */
@Composable
fun SkillsScreen(state: SkillsUi, onEnable: (names: List<String>, on: Boolean) -> Unit, onClose: () -> Unit,
                 modifier: Modifier = Modifier, actions: SkillsActions? = null) {
    val open = state.open
    if (actions != null && open != null) {
        SkillPage(state, open, actions, modifier)
        return
    }
    Page(stringResource(R.string.skills_title), modifier.testTag("skills"), onBack = onClose, backTag = "skills-close", busy = state.busy,
         action = actions?.let { a -> { SkillsMenu(state.busy, a) } }) {
        Hint(stringResource(R.string.skills_explain))
        Hint(stringResource(R.string.settings_new_chats), Modifier.padding(top = 8.dp).testTag("skills-new-chats"))
        state.error?.let { Hint(it, Modifier.padding(top = 12.dp).testTag("skills-error"), color = MaterialTheme.colorScheme.error) }
        if (actions != null) {
            OutlinedTextField(state.query, actions.query, Modifier.fillMaxWidth().padding(top = 12.dp).testTag("skills-search"),
                              singleLine = true, label = { Text(stringResource(R.string.skills_search)) },
                              keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
        }
        state.shown()?.let { shown ->
            when {
                state.rows.isNullOrEmpty() -> Hint(stringResource(R.string.skills_none), Modifier.padding(top = 12.dp).testTag("skills-none"))
                shown.isEmpty() -> Hint(stringResource(R.string.skills_no_match, state.query.trim()),
                                        Modifier.padding(top = 12.dp).testTag("skills-no-match"))
                else -> SkillGroup.of(shown).forEach {
                    CategoryBlock(it, enabled = !state.busy, bulk = state.query.isBlank(), onEnable, actions?.read)
                }
            }
        }
    }
}

/** The page's ⋮ menu: Refresh skills. */
@Composable
private fun SkillsMenu(busy: Boolean, actions: SkillsActions) {
    var expanded by remember { mutableStateOf(false) }  // transient UI
    Box {
        IconButton({ expanded = true }, Modifier.testTag("skills-menu"), enabled = !busy) {
            Icon(HermesIcons.More, stringResource(R.string.skills_menu), Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.skills_refresh)) }, onClick = { expanded = false; actions.refresh() },
                             Modifier.testTag("skills-refresh"))
        }
    }
}

/**
 * One category: its heading with the category's switch ([bulk]: hidden while a search narrows the list, D8), then each
 * skill with its own; with [onOpen], a tap on a skill's name opens it.
 */
@Composable
private fun CategoryBlock(group: SkillGroup, enabled: Boolean, bulk: Boolean, onEnable: (names: List<String>, on: Boolean) -> Unit,
                          onOpen: ((String) -> Unit)?) {
    val key = group.category ?: "general"
    val label = categoryLabel(group.category)
    val switchLabel = stringResource(R.string.skills_category_switch, label)
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp).testTag("skills-category-$key"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), Modifier.weight(1f), style = SectionLabel, color = Hermes.colors.textTertiary)
        if (bulk) {
            Switch(group.on, { on -> onEnable(group.switching(on), on) },
                   Modifier.testTag("skills-category-switch-$key").semantics { contentDescription = switchLabel }, enabled = enabled)
        }
    }
    HorizontalDivider(color = Hermes.colors.stroke)
    group.rows.forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("skill-${row.name}"), verticalAlignment = Alignment.CenterVertically) {
            val text = Modifier.weight(1f)
            Column(if (onOpen == null) text else text.testTag("skill-open-${row.name}").clickable(enabled = enabled) { onOpen(row.name) }) {
                Text(row.name, style = MaterialTheme.typography.bodyLarge)
                if (row.description.isNotBlank()) {
                    Text(row.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                         maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (onOpen != null) RowMeta(row)
            }
            Switch(row.enabled, { on -> onEnable(listOf(row.name), on) },
                   Modifier.testTag("skill-switch-${row.name}").semantics { contentDescription = row.name }, enabled = enabled)
        }
        HorizontalDivider(color = Hermes.colors.stroke)
    }
}

/** Under a row's description: where it came from (Desktop's `skillSubtitle`: only learned and hub skills say so) and "×N". */
@Composable
private fun RowMeta(row: SkillRow) {
    val badge = badgeOf(row.provenance)
    if (badge == null && row.uses <= 0) return
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        badge?.let { Pill(stringResource(it), "skill-badge-${row.name}", badgeColor(row.provenance)) }
        if (row.uses > 0) {
            Text(stringResource(R.string.skills_uses, row.uses), Modifier.testTag("skill-uses-${row.name}"),
                 style = MaterialTheme.typography.labelSmall, color = Hermes.colors.textTertiary)
        }
    }
}

/** The badge a skill's [provenance] shows: Learned for the agent's own, Hub for a hub install, none otherwise. */
private fun badgeOf(provenance: Provenance): Int? = when (provenance) {
    Provenance.AGENT -> R.string.skills_learned
    Provenance.HUB -> R.string.skills_hub
    Provenance.BUNDLED, Provenance.EXTERNAL -> null
}

@Composable
private fun badgeColor(provenance: Provenance): Color =
    if (provenance == Provenance.HUB) Hermes.colors.textTertiary else MaterialTheme.colorScheme.primary

/** A small rounded label on the widget surface. */
@Composable
private fun Pill(text: String, tag: String, color: Color = Hermes.colors.textTertiary) {
    val shape = RoundedCornerShape(6.dp)
    Text(text, Modifier.testTag(tag).background(Hermes.colors.widget, shape).border(1.dp, Hermes.colors.stroke, shape)
             .padding(horizontal = 6.dp, vertical = 1.dp),
         style = MaterialTheme.typography.labelSmall, color = color)
}

/**
 * One skill, open for reading (capabilities-03): its description, its category, provenance and use pills (D12), then
 * its whole SKILL.md as the core holds it, selectable in the code face. Back (the arrow and the system's) returns to
 * the list, not while a change runs.
 */
@Composable
private fun SkillPage(state: SkillsUi, open: SkillText, actions: SkillsActions, modifier: Modifier) {
    val row = state.rows?.find { it.name == open.name }
    BackHandler(enabled = !state.busy, onBack = actions.closeSkill)
    Page(open.name, modifier.testTag("skill"), onBack = actions.closeSkill, backTag = "skill-close", backEnabled = !state.busy,
         busy = state.busy) {
        Hint(row?.description?.takeIf { it.isNotBlank() } ?: stringResource(R.string.skill_no_description),
             Modifier.testTag("skill-description"))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(categoryLabel(row?.category), "skill-category")
            row?.let { r -> badgeOf(r.provenance)?.let { Pill(stringResource(it), "skill-provenance", badgeColor(r.provenance)) } }
            row?.uses?.takeIf { it > 0 }?.let { Pill(stringResource(R.string.skill_used, it), "skill-used") }
        }
        state.error?.let { Hint(it, Modifier.padding(top = 12.dp).testTag("skills-error"), color = MaterialTheme.colorScheme.error) }
        SelectionContainer(Modifier.padding(top = 12.dp)) { CodeBox(open.content, Modifier.testTag("skill-text")) }
    }
}

/** A category as the page heads it: none reads "General", a name Desktop's `prettyName` (`_` a space, each word capitalised; D9). */
@Composable
private fun categoryLabel(category: String?): String =
    if (category == null) stringResource(R.string.skills_general)
    else Regex("""\b\w""").replace(category.replace('_', ' ')) { it.value.uppercase() }
