package com.nousresearch.hermes.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few icons the app draws, from Tabler Icons (MIT), the outline set the desktop uses: 24-unit grid, 2-unit
 * round strokes, tinted by `Icon`. Kept as path data here rather than a dependency on an icon library.
 */
object HermesIcons {
    private fun icon(name: String, vararg paths: String, filled: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { data ->
                addPath(addPathNodes(data), fill = if (filled) SolidColor(Color.Black) else null,
                        stroke = if (filled) null else SolidColor(Color.Black), strokeLineWidth = 2f,
                        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
        }.build()

    val Menu = icon("menu", "M4 6h16", "M4 12h16", "M4 18h16")
    val Send = icon("arrow-up", "M12 5v14", "M18 11l-6-6", "M6 11l6-6")
    val Stop = icon("player-stop", "M7 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", filled = true)
    val NewChat = icon("edit", "M7 7h-1a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h9a2 2 0 0 0 2-2v-1",
                       "M20.385 6.585a2.1 2.1 0 0 0-2.97-2.97l-8.415 8.385v3h3l8.385-8.415z", "M16 5l3 3")
    val Terminal = icon("terminal-2", "M8 9l3 3l-3 3", "M13 15h3",
                        "M3 6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-14a2 2 0 0 1-2-2z")
    val Gateway = icon("broadcast", "M18.364 19.364a9 9 0 1 0-12.728 0", "M15.536 16.536a5 5 0 1 0-7.072 0",
                       "M11 13a1 1 0 1 0 2 0a1 1 0 1 0-2 0")
    val Model = icon("sparkles", "M16 18a2 2 0 0 1 2 2a2 2 0 0 1 2-2a2 2 0 0 1-2-2a2 2 0 0 1-2 2z",
                     "M16 6a2 2 0 0 1 2 2a2 2 0 0 1 2-2a2 2 0 0 1-2-2a2 2 0 0 1-2 2z",
                     "M9 18a6 6 0 0 1 6-6a6 6 0 0 1-6-6a6 6 0 0 1-6 6a6 6 0 0 1 6 6z")
    val Settings = icon("settings",
                        "M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 0 0 2.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 0 0 1.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 0 0-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 0 0-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 0 0-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 0 0-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 0 0 1.066-2.573c-.94-1.543.826-3.31 2.37-2.37c1 .608 2.296.07 2.572-1.065z",
                        "M9 12a3 3 0 1 0 6 0a3 3 0 0 0-6 0")
    val ChevronRight = icon("chevron-right", "M9 6l6 6l-6 6")
    val ChevronDown = icon("chevron-down", "M6 9l6 6l6-6")
    val Back = icon("arrow-left", "M5 12h14", "M5 12l6 6", "M5 12l6-6")
    val Tool = icon("tool", "M7 10h3v-3l-3.5-3.5a6 6 0 0 1 8 8l6 6a2 2 0 0 1-3 3l-6-6a6 6 0 0 1-8-8l3.5 3.5")
    val Check = icon("check", "M5 12l5 5l10-10")
    val Alert = icon("alert-circle", "M3 12a9 9 0 1 0 18 0a9 9 0 0 0-18 0", "M12 8v4", "M12 16h.01")
}
