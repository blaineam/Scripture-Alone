package com.blainemiller.scripturealone.ui.reader

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.blainemiller.scripturealone.R

/**
 * The reading face: **Source Serif 4**, bundled in `res/font/` from Google Fonts' repository
 * (`google/fonts`, `ofl/sourceserif4/`), SIL Open Font License 1.1 — the licence travels with the
 * app in `assets/licenses/SourceSerif4-OFL.txt`.
 *
 * It stands in for New York, the iOS default, which Apple does not license for other platforms.
 * Chosen over Literata for how close it sits to New York on the page: a transitional serif with
 * a similar x-height and colour, real small capitals in the roman (`smcp`, which the divine name
 * needs) and an optical-size axis, as New York has.
 *
 * The files are the variable fonts (weight 200–900, optical size 8–60). The optical size is set to
 * the size the text is actually drawn at, which is what New York does on its own: the chapter
 * number gets the tighter display cut, the verses the text cut. Only weight 400 is used — the reader
 * never sets scripture in bold.
 */
@OptIn(ExperimentalTextApi::class)
object ReaderTypography {

    /** Source Serif 4 at [opticalSize] points, roman and italic. */
    fun sourceSerif(opticalSize: Float): FontFamily {
        val opsz = opticalSize.coerceIn(8f, 60f)
        fun settings() = FontVariation.Settings(FontVariation.weight(400), FontVariation.Setting("opsz", opsz))
        return FontFamily(
            Font(R.font.source_serif_4, FontWeight.Normal, FontStyle.Normal, variationSettings = settings()),
            Font(R.font.source_serif_4_italic, FontWeight.Normal, FontStyle.Italic, variationSettings = settings()),
        )
    }

    /** The families for a reader at [size]: text cut for the verses, display cut for the chapter number. */
    fun fonts(size: Float): ReaderFonts = ReaderFonts(
        body = sourceSerif(size),
        display = sourceSerif(size * 2.6f),
        chrome = FontFamily.SansSerif,
        italicHasSmallCaps = false,
    )
}
