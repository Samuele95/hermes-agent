package com.nousresearch.hermes.android.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.Hermes
import com.nousresearch.hermes.android.ui.HermesIcons

/**
 * The out-of-credits wall above the composer, as the desktop's billing banner: the provider, the core's first line, one
 * way to act ([onOpenUrl] for the page the core named, else [onSetup]) and Dismiss. It never stops the composer.
 */
@Composable
fun BillingBanner(wall: BillingWall, onOpenUrl: ((String) -> Unit)?, onSetup: (() -> Unit)?, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 4.dp)
               .background(MaterialTheme.colorScheme.surfaceContainer, shape).border(1.dp, Hermes.colors.stroke, shape)
               .padding(bottom = 6.dp).testTag("billing-wall")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(HermesIcons.CreditCard, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
            Text(if (wall.nous) stringResource(R.string.billing_title_nous) else stringResource(R.string.billing_title_provider, wall.label),
                 Modifier.padding(start = 8.dp).weight(1f).testTag("billing-title"), style = MaterialTheme.typography.labelLarge)
            IconButton(onDismiss, Modifier.testTag("billing-dismiss")) {
                Icon(HermesIcons.Close, stringResource(R.string.billing_dismiss), Modifier.size(16.dp))
            }
        }
        Text(wall.message, Modifier.padding(horizontal = 12.dp).testTag("billing-message"), style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        BillingAction(wall, onOpenUrl, onSetup)
    }
}

/**
 * The wall's one way to act: the page the core named, in the browser ("Open billing" for Nous's portal, D1, else "Add
 * credits"), else the provider setup (D2), else none. It leaves the wall in place: the next completed turn clears it (D8).
 */
@Composable
private fun BillingAction(wall: BillingWall, onOpenUrl: ((String) -> Unit)?, onSetup: (() -> Unit)?) {
    val url = wall.url
    val (label, act) = when {
        url != null && onOpenUrl != null -> (if (wall.nous) R.string.billing_open else R.string.billing_add) to { onOpenUrl(url) }
        onSetup != null -> R.string.setup_title to onSetup
        else -> return
    }
    TextButton(act, Modifier.padding(start = 4.dp).testTag("billing-action")) { Text(stringResource(label)) }
}
