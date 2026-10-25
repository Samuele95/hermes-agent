package com.nousresearch.hermes.android.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.nousresearch.hermes.android.chat.Markdown.Block
import com.nousresearch.hermes.android.chat.Markdown.Span
import com.nousresearch.hermes.android.ui.CodeFont
import com.nousresearch.hermes.android.ui.Hermes

/** A reply's Markdown, drawn flat like the desktop's: prose in the body face, code in JetBrains Mono on the widget surface. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val blocks = remember(text) { Markdown.parse(text) }
    val typography = MaterialTheme.typography
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            // Items of one list sit close together; every other block gets a paragraph's gap.
            val gap = when {
                index == 0 -> 0.dp
                block is Block.ListItem && blocks[index - 1] is Block.ListItem -> 4.dp
                block is Block.Heading -> 16.dp
                else -> 10.dp
            }
            val spaced = Modifier.padding(top = gap)
            when (block) {
                is Block.Paragraph -> Text(styled(block.spans), spaced, style = style)
                is Block.Heading -> Text(styled(block.spans), spaced, style = when (block.level) {
                    1 -> typography.titleLarge
                    2 -> typography.titleMedium.copy(fontSize = 18.sp)
                    else -> typography.titleSmall.copy(fontSize = 16.sp)
                })
                is Block.Code -> CodeBlock(block, spaced)
                is Block.ListItem -> Row(spaced.padding(start = (block.depth * 18).dp)) {
                    Text(block.number?.let { "$it." } ?: if (block.depth == 0) "•" else "◦", Modifier.widthIn(min = 20.dp),
                         style = style, color = muted)
                    Text(styled(block.spans), style = style)
                }
                is Block.Quote -> Row(spaced.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(Hermes.colors.stroke, RoundedCornerShape(2.dp)))
                    Text(styled(block.spans), Modifier.padding(start = 12.dp), style = style, color = muted)
                }
                is Block.Table -> Table(block, spaced, style)
                Block.Rule -> HorizontalDivider(spaced.padding(vertical = 4.dp), color = Hermes.colors.stroke)
            }
        }
    }
}

@Composable
private fun CodeBlock(block: Block.Code, modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Column(modifier.fillMaxWidth().background(Hermes.colors.widget, shape).border(1.dp, Hermes.colors.stroke, shape)) {
        if (block.language.isNotEmpty()) {
            Text(block.language, Modifier.padding(start = 12.dp, top = 8.dp), style = MaterialTheme.typography.labelSmall,
                 color = Hermes.colors.textTertiary)
        }
        Text(block.text, Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
             style = TextStyle(fontFamily = CodeFont, fontSize = 13.sp, lineHeight = 19.sp), softWrap = false)
    }
}

@Composable
private fun Table(block: Block.Table, modifier: Modifier, style: TextStyle) {
    val stroke = Hermes.colors.stroke
    val shape = RoundedCornerShape(8.dp)
    // Wider than the screen, the table scrolls sideways; the column takes its rows' width so the rules span it.
    Box(modifier.horizontalScroll(rememberScrollState())) {
        Column(Modifier.width(IntrinsicSize.Max).border(1.dp, stroke, shape)) {
            (listOf(block.header) + block.rows).forEachIndexed { r, row ->
                if (r > 0) HorizontalDivider(color = stroke)
                Row(if (r == 0) Modifier.fillMaxWidth().background(Hermes.colors.widget, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    else Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    row.forEach { cell ->
                        Text(styled(cell), Modifier.width(140.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                             style = if (r == 0) style.copy(fontWeight = FontWeight.SemiBold) else style)
                    }
                }
            }
        }
    }
}

/** One block's runs as styled text; links open in the browser when tapped. */
@Composable
private fun styled(spans: List<Span>): AnnotatedString {
    val link = MaterialTheme.colorScheme.primary
    val codeBackground = Hermes.colors.widget
    return remember(spans, link, codeBackground) {
        buildAnnotatedString {
            spans.forEach { span ->
                val look = SpanStyle(
                    fontWeight = if (span.bold) FontWeight.SemiBold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    fontFamily = if (span.code) CodeFont else null,
                    fontSize = if (span.code) 0.92.em else TextUnit.Unspecified,
                    background = if (span.code) codeBackground else Color.Unspecified,
                    textDecoration = if (span.strike) TextDecoration.LineThrough else null,
                )
                val target = span.link
                if (target != null) {
                    withLink(LinkAnnotation.Url(target, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) {
                        withStyle(look) { append(span.text) }
                    }
                } else withStyle(look) { append(span.text) }
            }
        }
    }
}
