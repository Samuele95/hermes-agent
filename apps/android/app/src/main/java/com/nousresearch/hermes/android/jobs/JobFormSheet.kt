package com.nousresearch.hermes.android.jobs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.CodeFont
import com.nousresearch.hermes.android.ui.Hermes
import com.nousresearch.hermes.android.ui.Hint
import com.nousresearch.hermes.android.ui.PrimaryButton
import com.nousresearch.hermes.android.ui.SecondaryButton
import com.nousresearch.hermes.android.ui.SectionCaption

/** What the form sheet does: [save] the finished form, [cancel] it. */
class JobFormActions(val save: (JobForm) -> Unit, val cancel: () -> Unit)

/**
 * The job form as a bottom sheet over the Scheduled jobs page (automation-02, -06, -08): name, prompt, when (a
 * preset in the core's grammar, or the job's own schedule kept), how many times, and where the result goes. The
 * typed draft lives here, keyed on the job edited, and is dropped on close; Save is enabled only when the draft can
 * be saved ([canSave]); a count (minutes, times to run) is typed in full, and one the form cannot take is red, with
 * Save off. Nothing can be tapped, and the sheet cannot be dismissed, while a save is on its way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobFormSheet(ui: JobsUi, form: JobForm, actions: JobFormActions) {
    val job = form.id?.let { id -> ui.jobs.find { it.id == id } }
    val key = form.id?.value ?: "new"
    val defaults = ScheduleDraft()
    var name by rememberSaveable(key) { mutableStateOf(form.name) }
    var prompt by rememberSaveable(key) { mutableStateOf(form.prompt) }
    var preset by rememberSaveable(key) { mutableStateOf(if (form.id == null) SchedulePreset.DAILY.name else null) }  // null: keep
    var minutes by rememberSaveable(key) { mutableStateOf(defaults.minutes) }
    var hour by rememberSaveable(key) { mutableStateOf(defaults.hour) }
    var minute by rememberSaveable(key) { mutableStateOf(defaults.minute) }
    var text by rememberSaveable(key) { mutableStateOf(defaults.text) }
    var repeat by rememberSaveable(key) { mutableStateOf(form.repeat?.toString() ?: "") }
    var deliver by rememberSaveable(key) { mutableStateOf(form.delivery.wire()) }

    val chosen = preset?.let { SchedulePreset.valueOf(it) }
    val schedule = chosen?.let { ScheduleDraft(it, minutes, hour, minute, text).schedule }
    val delivery = Delivery.parse(deliver)
    val draft = JobForm(form.id, name, prompt, schedule, delivery, repeatOf(schedule, repeat))
    val enabled = !ui.busy

    val busy by rememberUpdatedState(ui.busy)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !busy })
    ModalBottomSheet(onDismissRequest = { if (!busy) actions.cancel() }, modifier = Modifier.testTag("job-form"), sheetState = sheet,
                     containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
               verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (form.id == null) R.string.job_form_new else R.string.job_form_edit),
                 style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("job-form-name"), enabled = enabled, singleLine = true,
                              label = { Text(stringResource(R.string.job_form_name)) })
            val cut = job?.promptCut == true
            if (cut) Hint(stringResource(R.string.job_form_prompt_cut, job.prompt), Modifier.testTag("job-form-prompt-was"))
            OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth().testTag("job-form-prompt"), enabled = enabled, minLines = 3,
                              label = { Text(stringResource(if (cut) R.string.job_form_prompt_new else R.string.job_form_prompt)) },
                              placeholder = { Text(stringResource(R.string.job_form_prompt_hint)) })

            SectionCaption(stringResource(R.string.job_form_when))
            if (form.id != null) {
                val shown = job?.schedule ?: "?"
                Choice(preset == null, "job-schedule-keep", enabled, { preset = null }) {
                    val words = stringResource(R.string.job_form_keep_schedule, shown)
                    val at = words.indexOf(shown)
                    Text(buildAnnotatedString {
                        append(words.take(at))
                        withStyle(SpanStyle(fontFamily = CodeFont)) { append(shown) }
                        append(words.drop(at + shown.length))
                    }, style = MaterialTheme.typography.bodyLarge)
                }
            }
            SchedulePreset.entries.forEach { option ->
                val (label, tag) = when (option) {
                    SchedulePreset.EVERY -> R.string.job_schedule_every to "job-schedule-every"
                    SchedulePreset.DAILY -> R.string.job_schedule_daily to "job-schedule-daily"
                    SchedulePreset.WEEKDAYS -> R.string.job_schedule_weekdays to "job-schedule-weekdays"
                    SchedulePreset.ONCE -> R.string.job_schedule_once to "job-schedule-once"
                    SchedulePreset.CUSTOM -> R.string.job_schedule_custom to "job-schedule-custom"
                }
                Choice(chosen == option, tag, enabled, { preset = option.name }) {
                    Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                }
                if (chosen != option) return@forEach
                // Each field is judged alone by the draft's own rule (V2): only a value that makes no schedule is red.
                when (option) {
                    SchedulePreset.EVERY, SchedulePreset.ONCE ->
                        Digits(minutes, DigitField.COUNT, { minutes = it }, R.string.job_form_minutes, "job-form-minutes", enabled,
                               ScheduleDraft(option, minutes = minutes).schedule == null)
                    SchedulePreset.DAILY, SchedulePreset.WEEKDAYS -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Digits(hour, DigitField.CLOCK, { hour = it }, R.string.job_form_hour, "job-form-hour", enabled,
                               ScheduleDraft(option, hour = hour).schedule == null, Modifier.weight(1f))
                        Digits(minute, DigitField.CLOCK, { minute = it }, R.string.job_form_minute, "job-form-minute", enabled,
                               ScheduleDraft(option, minute = minute).schedule == null, Modifier.weight(1f))
                    }
                    SchedulePreset.CUSTOM -> {
                        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().testTag("job-form-custom"), enabled = enabled,
                                          singleLine = true, isError = schedule == null,
                                          label = { Text(stringResource(R.string.job_form_custom)) },
                                          keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                        Hint(stringResource(R.string.job_form_custom_hint))
                    }
                }
            }

            // A one-shot runs once, as the core sets it: the chosen preset's, or under keep the job's own.
            val oneShot = if (chosen != null) chosen == SchedulePreset.ONCE else job?.schedule?.startsWith("once") == true
            if (!oneShot) {
                Digits(repeat, DigitField.COUNT, { repeat = it }, R.string.job_form_repeat, "job-form-repeat", enabled,
                       error = !repeatFits(repeat))
            }

            SectionCaption(stringResource(R.string.job_form_deliver))
            // The job's own delivery, when no target is it (the agent's origin or chat, a list, a platform gone): kept (V5).
            if (ui.targets.none { it.delivery == form.delivery }) {
                Choice(delivery == form.delivery, "job-deliver-keep", enabled, { deliver = form.delivery.wire() }) {
                    Text(stringResource(R.string.job_deliver_keep, form.delivery.wire()), style = MaterialTheme.typography.bodyLarge)
                }
            }
            ui.targets.forEach { target ->
                val id = if (target.delivery == Delivery.Local) "local" else target.delivery.wire()
                Choice(delivery == target.delivery, "job-deliver-$id", enabled, { deliver = target.delivery.wire() }) {
                    Text(if (target.delivery == Delivery.Local) stringResource(R.string.job_deliver_local) else target.name,
                         style = MaterialTheme.typography.bodyLarge)
                    // Flagged, not disabled: the user may send /sethome before the job first runs (V6).
                    if (!target.homeSet) {
                        Text(stringResource(R.string.job_deliver_no_home), Modifier.testTag("job-deliver-$id-no-home"),
                             style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            ui.error?.let { Hint(it, Modifier.testTag("job-form-error"), color = MaterialTheme.colorScheme.error) }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(stringResource(if (form.id == null) R.string.job_form_create else R.string.job_form_save),
                              { actions.save(draft) }, Modifier.testTag("job-form-save"),
                              enabled = enabled && draft.canSave(job) && (oneShot || repeatFits(repeat)))
                SecondaryButton(stringResource(R.string.job_form_cancel), actions.cancel, Modifier.testTag("job-form-cancel"), enabled = enabled)
            }
        }
    }
}

/** One radio row, as the Settings page's: [content] beside the radio, a hairline beneath. */
@Composable
private fun Choice(selected: Boolean, tag: String, enabled: Boolean, onSelect: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().testTag(tag).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick = null, enabled = enabled)
            Column(Modifier.padding(start = 12.dp), content = content)
        }
        HorizontalDivider(color = Hermes.colors.stroke)
    }
}

/** A field that takes digits only, kept by [field]'s rule (a count is never cut), red when [error]. */
@Composable
private fun Digits(value: String, field: DigitField, onValue: (String) -> Unit, label: Int, tag: String, enabled: Boolean,
                   error: Boolean, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(value, { onValue(field.keep(it)) }, modifier.testTag(tag), enabled = enabled, singleLine = true,
                      isError = error, label = { Text(stringResource(label)) },
                      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}
