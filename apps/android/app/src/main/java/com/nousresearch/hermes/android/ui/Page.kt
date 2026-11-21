package com.nousresearch.hermes.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R

/**
 * The frame of every page the chat opens, in the desktop's settings-page shape: a back arrow ([onBack], tagged
 * [backTag]) or, on a first page, the brand mark; the title; an optional [action]; a thin progress line while
 * [busy]; then the content on the page background, scrolling when [scroll] (a page holding a lazy list scrolls
 * itself).
 */
@Composable
fun Page(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, backTag: String = "page-back",
         backEnabled: Boolean = true, busy: Boolean = false, scroll: Boolean = true,
         action: (@Composable RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onBack, Modifier.testTag(backTag), enabled = backEnabled) {
                    Icon(HermesIcons.Back, stringResource(R.string.setup_back), Modifier.size(22.dp))
                }
            } else {
                Image(painterResource(if (Hermes.colors.dark) R.drawable.brand_mark_dark else R.drawable.brand_mark), null,
                      Modifier.padding(horizontal = 12.dp).size(28.dp).clip(RoundedCornerShape(7.dp)))
            }
            Text(title, Modifier.weight(1f).padding(horizontal = 4.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1,
                 overflow = TextOverflow.Ellipsis)
            action?.invoke(this)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.height(2.dp))
        val body = Modifier.fillMaxWidth().weight(1f)
        Column((if (scroll) body.verticalScroll(rememberScrollState()) else body).padding(horizontal = 20.dp, vertical = 8.dp),
               content = content)
    }
}

/** A section of a page: the sidebar's caption (a small tracked label and a hairline). */
@Composable
fun SectionCaption(label: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), style = SectionLabel, color = Hermes.colors.textTertiary)
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(Modifier.weight(1f), color = Hermes.colors.stroke)
    }
}

/** One row of a flat list: a title, an optional quieter line, a hairline beneath; [trailing] sits at its end. */
@Composable
fun ListRow(title: String, modifier: Modifier = Modifier, detail: String? = null, enabled: Boolean = true,
            trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            trailing?.invoke()
        }
        HorizontalDivider(color = Hermes.colors.stroke)
    }
}

/** A row's chevron: the row leads somewhere. */
@Composable
fun Chevron() {
    Icon(HermesIcons.ChevronRight, null, Modifier.size(16.dp), tint = Hermes.colors.textTertiary)
}

/** Explanatory text under a heading: the page's quieter voice. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = color)
}

/** The desktop's buttons are rounded rectangles, not pills. */
val ButtonShape = RoundedCornerShape(10.dp)
private val ButtonPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick, modifier, enabled = enabled, shape = ButtonShape, contentPadding = ButtonPadding) { Text(text) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                    color: Color = MaterialTheme.colorScheme.onSurface) {
    OutlinedButton(onClick, modifier, enabled = enabled, shape = ButtonShape, contentPadding = ButtonPadding,
                   border = BorderStroke(1.dp, Hermes.colors.stroke), colors = ButtonDefaults.outlinedButtonColors(contentColor = color)) {
        Text(text)
    }
}

/** A value shown for reading and comparing (a fingerprint, a key, a code): the code face on the widget surface. */
@Composable
fun CodeBox(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Text(text, modifier.fillMaxWidth().background(Hermes.colors.widget, shape).border(1.dp, Hermes.colors.stroke, shape)
             .padding(horizontal = 12.dp, vertical = 10.dp),
         style = MaterialTheme.typography.bodyMedium.copy(fontFamily = CodeFont))
}
