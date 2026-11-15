package com.nousresearch.hermes.android.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.Hermes
import com.nousresearch.hermes.android.ui.Hint
import com.nousresearch.hermes.android.ui.Page
import com.nousresearch.hermes.android.ui.SectionLabel

/**
 * The core's skills by category, each category's switch and each skill's own (F11). It shows only what the core last
 * answered; while a change is written, every switch is off.
 */
@Composable
fun SkillsScreen(state: SkillsUi, onEnable: (names: List<String>, on: Boolean) -> Unit, onClose: () -> Unit,
                 modifier: Modifier = Modifier) {
    Page(stringResource(R.string.skills_title), modifier.testTag("skills"), onBack = onClose, backTag = "skills-close", busy = state.busy) {
        Hint(stringResource(R.string.skills_explain))
        Hint(stringResource(R.string.settings_new_chats), Modifier.padding(top = 8.dp).testTag("skills-new-chats"))
        state.error?.let { Hint(it, Modifier.padding(top = 12.dp).testTag("skills-error"), color = MaterialTheme.colorScheme.error) }
        state.rows?.let { rows ->
            if (rows.isEmpty()) Hint(stringResource(R.string.skills_none), Modifier.padding(top = 12.dp).testTag("skills-none"))
            else SkillGroup.of(rows).forEach { CategoryBlock(it, enabled = !state.busy, onEnable) }
        }
    }
}

/** One category: its heading with the category's switch, then each skill with its own. */
@Composable
private fun CategoryBlock(group: SkillGroup, enabled: Boolean, onEnable: (names: List<String>, on: Boolean) -> Unit) {
    val key = group.category ?: "general"
    val label = categoryLabel(group.category)
    val switchLabel = stringResource(R.string.skills_category_switch, label)
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp).testTag("skills-category-$key"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), Modifier.weight(1f), style = SectionLabel, color = Hermes.colors.textTertiary)
        Switch(group.on, { on -> onEnable(group.switching(on), on) },
               Modifier.testTag("skills-category-switch-$key").semantics { contentDescription = switchLabel }, enabled = enabled)
    }
    HorizontalDivider(color = Hermes.colors.stroke)
    group.rows.forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("skill-${row.name}"), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.bodyLarge)
                if (row.description.isNotBlank()) {
                    Text(row.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                         maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Switch(row.enabled, { on -> onEnable(listOf(row.name), on) },
                   Modifier.testTag("skill-switch-${row.name}").semantics { contentDescription = row.name }, enabled = enabled)
        }
        HorizontalDivider(color = Hermes.colors.stroke)
    }
}

/** A category as the page heads it: none reads "General", a name Desktop's `prettyName` (`_` a space, each word capitalised; D9). */
@Composable
private fun categoryLabel(category: String?): String =
    if (category == null) stringResource(R.string.skills_general)
    else Regex("""\b\w""").replace(category.replace('_', ' ')) { it.value.uppercase() }
