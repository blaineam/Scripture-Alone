package com.blainemiller.scripturealone.ui.reader

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.FontRes
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat
import com.blainemiller.scripturealone.R
import java.util.Locale

/**
 * The seven reading faces — `FontFamily` in `ScriptureAlone/Reader/ReaderStyle.swift`. Apple's
 * faces are not licensed for other platforms, so each is an open-licensed stand-in bundled in
 * `res/font/`, with its licence in `assets/licenses/` (listed in the Appearance sheet's Font
 * Licences):
 *
 * | iOS | Android | Licence | Source |
 * |---|---|---|---|
 * | New York | Source Serif 4 | OFL 1.1 | google/fonts `ofl/sourceserif4` |
 * | San Francisco | Inter | OFL 1.1 | google/fonts `ofl/inter` |
 * | Charter | Charis SIL (a Charter derivative) | OFL 1.1 | google/fonts `ofl/charissil` |
 * | Iowan Old Style | Literata | OFL 1.1 | google/fonts `ofl/literata` |
 * | Georgia | Gelasio (Georgia-metric) | OFL 1.1 | google/fonts `ofl/gelasio` |
 * | Palatino | Domitian (URW Palladio, extended) | OFL 1.1 (of AGPL/LPPL/OFL) | CTAN `fonts/domitian` |
 * | Avenir Next | Nunito Sans | OFL 1.1 | google/fonts `ofl/nunitosans` |
 *
 * All but Source Serif 4 and Charis SIL are cut down by `android/tools/subset_fonts.py` to the Latin,
 * Greek and Cyrillic ranges and weights 400–700; letters outside them fall back to the system face.
 * Charis SIL ships exactly as SIL publishes it, because its licence reserves its name for unmodified
 * copies. Palatino's stand-in is Domitian rather than TeX Gyre Pagella because Pagella's GUST licence
 * is neither OFL nor Apache.
 *
 * [raw] is the Swift raw value, stored under `reader.fontFamily` and `share.fontFamily`;
 * [shareToken] is the `f` token of a share link (`docs/share-links.md`).
 */
enum class ReaderFontFamily(
    val raw: String,
    /** The face as the picker names it — the one actually drawn. */
    val title: String,
    /** The iOS face it stands in for, shown beneath in the picker. */
    val iosTitle: String,
    val shareToken: String,
    @FontRes private val roman: Int,
    @FontRes private val italic: Int,
    /** A separate bold file for a static face; null where the roman is variable. */
    @FontRes private val bold: Int?,
    /** Real small capitals (`smcp`) in the roman and in the italic — the divine name needs them. */
    val romanHasSmallCaps: Boolean,
    val italicHasSmallCaps: Boolean,
    /** Whether the files carry an optical-size axis, set to the size the text is drawn at. */
    private val opticalSizes: ClosedFloatingPointRange<Float>? = null,
) {
    NEW_YORK("newYork", "Source Serif", "New York", "serif", R.font.source_serif_4, R.font.source_serif_4_italic, null,
        romanHasSmallCaps = true, italicHasSmallCaps = false, opticalSizes = 8f..60f),
    SAN_FRANCISCO("sanFrancisco", "Inter", "San Francisco", "sans", R.font.inter, R.font.inter_italic, null,
        romanHasSmallCaps = false, italicHasSmallCaps = false),
    CHARTER("charter", "Charis SIL", "Charter", "charter", R.font.charis_sil, R.font.charis_sil_italic, R.font.charis_sil_bold,
        romanHasSmallCaps = true, italicHasSmallCaps = true),
    IOWAN("iowan", "Literata", "Iowan Old Style", "iowan", R.font.literata, R.font.literata_italic, null,
        romanHasSmallCaps = true, italicHasSmallCaps = true),
    GEORGIA("georgia", "Gelasio", "Georgia", "georgia", R.font.gelasio, R.font.gelasio_italic, null,
        romanHasSmallCaps = false, italicHasSmallCaps = false),
    PALATINO("palatino", "Domitian", "Palatino", "palatino", R.font.domitian, R.font.domitian_italic, R.font.domitian_bold,
        romanHasSmallCaps = true, italicHasSmallCaps = false),
    AVENIR("avenir", "Nunito Sans", "Avenir Next", "avenir", R.font.nunito_sans, R.font.nunito_sans_italic, null,
        romanHasSmallCaps = false, italicHasSmallCaps = false);

    /**
     * The face at [size] points (sp) for Compose: roman and italic at 400, and bold at 700 (the share
     * card's reference line). Variable files get their weight — and, for Source Serif 4, an optical
     * size — as variation settings; static files ignore them.
     */
    @OptIn(ExperimentalTextApi::class)
    fun fontFamily(size: Float): FontFamily {
        fun settings(weight: Int): FontVariation.Settings {
            val opsz = opticalSizes?.let { size.coerceIn(it) }
            return if (opsz != null) {
                FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", opsz))
            } else {
                FontVariation.Settings(FontVariation.weight(weight))
            }
        }
        return FontFamily(
            Font(roman, FontWeight.Normal, FontStyle.Normal, variationSettings = settings(400)),
            Font(italic, FontWeight.Normal, FontStyle.Italic, variationSettings = settings(400)),
            Font(bold ?: roman, FontWeight.Bold, FontStyle.Normal, variationSettings = settings(700)),
        )
    }

    /**
     * The face for an `android.graphics` canvas — the share card, drawn off screen at export size.
     * Pair it with [variationSettings] on the paint: a variable file's weight is a paint setting there.
     */
    fun typeface(context: Context, bold: Boolean = false, italic: Boolean = false): Typeface {
        val res = when {
            italic -> this.italic
            bold -> this.bold ?: roman
            else -> roman
        }
        return ResourcesCompat.getFont(context, res) ?: Typeface.SERIF
    }

    /** `Paint.fontVariationSettings` for text at [size] in [bold] or regular weight. */
    fun variationSettings(size: Float, bold: Boolean = false): String {
        val weight = "'wght' ${if (bold) 700 else 400}"
        val opsz = opticalSizes?.let { String.format(Locale.US, ", 'opsz' %.1f", size.coerceIn(it)) } ?: ""
        return weight + opsz
    }

    companion object {
        val DEFAULT = NEW_YORK
        fun fromRaw(raw: String?): ReaderFontFamily? = entries.firstOrNull { it.raw == raw }
        fun fromShareToken(token: String?): ReaderFontFamily? = entries.firstOrNull { it.shareToken == token }
    }
}

object ReaderTypography {

    /** Source Serif 4 at [opticalSize] points, roman and italic — Study's serif. */
    fun sourceSerif(opticalSize: Float): FontFamily = ReaderFontFamily.NEW_YORK.fontFamily(opticalSize)

    /**
     * The families for a reader at [size] in [family]: the text cut for the verses, the display cut
     * for the chapter number (only Source Serif 4 has an optical-size axis to cut).
     */
    fun fonts(size: Float, family: ReaderFontFamily = ReaderFontFamily.DEFAULT): ReaderFonts = ReaderFonts(
        body = family.fontFamily(size),
        display = family.fontFamily(size * 2.6f),
        chrome = FontFamily.SansSerif,
        romanHasSmallCaps = family.romanHasSmallCaps,
        italicHasSmallCaps = family.italicHasSmallCaps,
    )
}
