package com.nousresearch.hermes.android.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The oversized lettering of an empty chat (apps/desktop/src/components/chat/wordmark.tsx): uppercase, tracked
 * wide, sized to span the width it is given, as the desktop's `.fit-text` does.
 */
@Composable
fun Wordmark(text: String, color: Color, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val base = TextStyle(fontFamily = WordmarkFont, fontWeight = FontWeight.Bold, fontSize = 100.sp,
                             letterSpacing = 0.08.em, lineHeight = 0.9.em, textAlign = TextAlign.Center)
        val measured = remember(text) { measurer.measure(text, base, softWrap = false, maxLines = 1).size.width }
        val fitted = (100f * constraints.maxWidth / measured.coerceAtLeast(1)).coerceIn(24f, 96f)
        Text(text, Modifier.fillMaxWidth(), style = base.copy(fontSize = fitted.sp), color = color, maxLines = 1, softWrap = false)
    }
}
