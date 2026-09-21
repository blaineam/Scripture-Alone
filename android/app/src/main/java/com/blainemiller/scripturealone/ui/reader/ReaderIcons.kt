package com.blainemiller.scripturealone.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Outline glyphs for SF Symbols that Material has no close match for, drawn on a 24-unit grid with
 * SF's regular stroke so they sit beside Material's outlined icons without looking borrowed.
 */
object ReaderIcons {
    /** `eraser` — the selection bar's Remove Highlight. */
    val Eraser: ImageVector by lazy {
        outline("Eraser") {
            moveTo(3f, 15f); lineTo(13f, 5f); lineTo(19.5f, 11.5f); lineTo(9.5f, 21.5f); close()
            moveTo(7f, 11f); lineTo(13.5f, 17.5f)
        }
    }

    /** `square.and.pencil` — Add Note and New Note. */
    val SquareAndPencil: ImageVector by lazy {
        outline("SquareAndPencil") {
            moveTo(12f, 4.5f); horizontalLineTo(6.5f)
            quadTo(4.5f, 4.5f, 4.5f, 6.5f); verticalLineTo(17.5f)
            quadTo(4.5f, 19.5f, 6.5f, 19.5f); horizontalLineTo(17.5f)
            quadTo(19.5f, 19.5f, 19.5f, 17.5f); verticalLineTo(12f)
            moveTo(9.5f, 14.5f); lineTo(10.2f, 11.4f); lineTo(18.2f, 3.4f); lineTo(20.6f, 5.8f)
            lineTo(12.6f, 13.8f); close()
        }
    }

    /** `note.text` — the Notes button. */
    val NoteText: ImageVector by lazy {
        outline("NoteText") {
            moveTo(6f, 4.5f); horizontalLineTo(18f)
            quadTo(20f, 4.5f, 20f, 6.5f); verticalLineTo(17.5f)
            quadTo(20f, 19.5f, 18f, 19.5f); horizontalLineTo(6f)
            quadTo(4f, 19.5f, 4f, 17.5f); verticalLineTo(6.5f)
            quadTo(4f, 4.5f, 6f, 4.5f); close()
            moveTo(7.5f, 9f); horizontalLineTo(16.5f)
            moveTo(7.5f, 12f); horizontalLineTo(16.5f)
            moveTo(7.5f, 15f); horizontalLineTo(13f)
        }
    }

    /** `text.bubble.fill` — a note's marker in the text: a filled bubble with lines knocked out. */
    val TextBubbleFill: ImageVector by lazy {
        ImageVector.Builder("TextBubbleFill", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd) {
                moveTo(5f, 3f); horizontalLineTo(19f)
                quadTo(22f, 3f, 22f, 6f); verticalLineTo(14f)
                quadTo(22f, 17f, 19f, 17f); horizontalLineTo(10f)
                lineTo(5.5f, 21f); verticalLineTo(17f); horizontalLineTo(5f)
                quadTo(2f, 17f, 2f, 14f); verticalLineTo(6f)
                quadTo(2f, 3f, 5f, 3f); close()
                // The two lines of text, cut out.
                moveTo(6.5f, 7.2f); horizontalLineTo(17.5f); verticalLineTo(8.8f); horizontalLineTo(6.5f); close()
                moveTo(6.5f, 11.2f); horizontalLineTo(14f); verticalLineTo(12.8f); horizontalLineTo(6.5f); close()
            }
        }.build()
    }

    private fun outline(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
            )
        }.build()
}
