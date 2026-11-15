package com.nousresearch.hermes.android.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.CodeBox
import com.nousresearch.hermes.android.ui.Hint

/**
 * The core's `/status` text for the chat's session as a bottom sheet over the chat (protocol-21): exactly as the core
 * wrote it, line for line, in the code face (D10); selectable, so a line can be copied by hand. Always dismissible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusSheet(ui: StatusUi, onClose: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, modifier = Modifier.testTag("status-sheet"), sheetState = sheet,
                     containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.status_title), Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.titleMedium)
            when {
                ui.text != null -> SelectionContainer { CodeBox(ui.text, Modifier.testTag("status-text")) }
                ui.error != null -> Hint(ui.error, Modifier.testTag("status-error"), color = MaterialTheme.colorScheme.error)
                else -> Hint(stringResource(R.string.status_loading), Modifier.testTag("status-loading"))
            }
        }
    }
}
