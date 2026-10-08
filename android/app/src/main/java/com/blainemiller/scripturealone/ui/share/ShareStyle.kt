package com.blainemiller.scripturealone.ui.share

import androidx.annotation.StringRes
import androidx.datastore.preferences.core.MutablePreferences
import com.blainemiller.scripturealone.data.prefs.ReaderKeys
import com.blainemiller.scripturealone.data.prefs.ReaderSettings
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.share.SharePassageText
import com.blainemiller.scripturealone.data.share.ShareVerse
import com.blainemiller.scripturealone.text.AppText
import com.blainemiller.scripturealone.ui.reader.ReaderFontFamily
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The eight card looks share links know by name (`tp`) — `ShareTemplate` in
 * `ScriptureAlone/Share/ShareStyle.swift`. The app has more grounds than the web; each travels as
 * the nearest of these ([ShareBackground.linkTemplate]).
 */
enum class ShareTemplate(val raw: String) {
    PARCHMENT("parchment"), INK("ink"), DAWN("dawn"), NIGHT("night"), LINEN("linen"), STONE("stone"), OLIVE("olive"),
    MINIMAL("minimal");

    val background: ShareBackground get() = ShareBackground.fromRaw(raw) ?: ShareBackground.PARCHMENT

    companion object {
        fun fromRaw(raw: String?): ShareTemplate? = entries.firstOrNull { it.raw == raw }
    }
}

/** How a ground is painted beneath its texture — `ShareGradient`. */
enum class ShareGradient { VERTICAL, DIAGONAL, RADIAL }

/** What a ground is made of, drawn by [ShareBackdrop] — `ShareTexture`. */
sealed class ShareTexture {
    data object Clean : ShareTexture()
    data object Paper : ShareTexture()
    data object Linen : ShareTexture()
    data object Canvas : ShareTexture()
    data object Grain : ShareTexture()
    data class Watercolor(val pigments: List<Long>) : ShareTexture()
    data class Bokeh(val lights: List<Long>) : ShareTexture()
    data object Glow : ShareTexture()
    data class Lattice(val line: Long) : ShareTexture()
    data class Contour(val line: Long) : ShareTexture()
}

/** A shadow under the card's text — `ShareShadow`: dark under light text, a light halo around dark. */
enum class ShareShadow(val raw: String, @StringRes private val titleRes: Int) {
    NONE("none", R.string.share_shadow_none), SOFT("soft", R.string.share_shadow_soft), STRONG("strong", R.string.share_shadow_strong);

    val title: String get() = AppText.get(titleRes)

    /** One layer under text of a size: blur radius and downward offset in points, opacity. */
    data class Layer(val radius: Float, val y: Float, val opacity: Float)

    fun layers(size: Float, glow: Boolean): List<Layer> = when (this) {
        NONE -> emptyList()
        SOFT -> listOf(Layer(size * 0.10f, if (glow) 0f else size * 0.025f, if (glow) 0.75f else 0.55f))
        STRONG -> listOf(
            Layer(size * 0.035f, if (glow) 0f else size * 0.015f, if (glow) 0.9f else 0.75f),
            Layer(size * 0.16f, if (glow) 0f else size * 0.04f, if (glow) 0.8f else 0.6f),
        )
    }

    companion object {
        fun fromRaw(raw: String?): ShareShadow? = entries.firstOrNull { it.raw == raw }
    }
}

/** A named text colour in the designer's palette — `ShareInkSwatch`. */
data class ShareInkSwatch(val hex: Long, @StringRes val nameRes: Int) {
    val name: String get() = AppText.get(nameRes)

    companion object {
        val FOR_LIGHT = listOf(
            ShareInkSwatch(0x111111, R.string.share_ink_black), ShareInkSwatch(0x1F2F4A, R.string.share_ink_navy),
            ShareInkSwatch(0x24402F, R.string.share_ink_forest), ShareInkSwatch(0x6B1E2A, R.string.share_ink_burgundy),
            ShareInkSwatch(0x4E3A26, R.string.share_ink_sepia),
        )
        val FOR_DARK = listOf(
            ShareInkSwatch(0xFFFFFF, R.string.share_ink_white), ShareInkSwatch(0xF5EBD7, R.string.share_ink_cream),
            ShareInkSwatch(0xE9C77F, R.string.share_ink_gold), ShareInkSwatch(0xCFE0F7, R.string.share_ink_sky),
            ShareInkSwatch(0xF6CFD0, R.string.share_ink_rose),
        )
    }
}

/**
 * A card ground — `ShareBackground` in `ShareStyle.swift`, with the same colours, textures and
 * ready-made styles (copied, not re-tuned). [raw] is the stored setting; the first eight
 * templates' names are among them.
 */
enum class ShareBackground(
    val raw: String,
    @StringRes private val titleRes: Int,
    /** Ground colours in gradient order; one = flat. */
    val colors: List<Long>,
    val gradient: ShareGradient,
    val texture: ShareTexture,
    /** The curated text colour: the automatic choice whenever none is picked. */
    val ink: Long,
    /** Reference, rule, verse numbers and wordmark. */
    val accent: Long,
    /** Words of Christ. */
    val red: Long,
    val presetShadow: ShareShadow,
    val presetFamily: ReaderFontFamily,
    val presetAlignment: ShareAlignment,
) {
    PARCHMENT("parchment", R.string.share_template_parchment, listOf(0xF6EDD9, 0xEBDDBF), ShareGradient.VERTICAL, ShareTexture.Paper,
        0x3B2F20, 0x8A5A2B, 0xA12A1C, ShareShadow.NONE, ReaderFontFamily.NEW_YORK, ShareAlignment.CENTER),
    WATERCOLOR("watercolor", R.string.share_background_watercolor, listOf(0xFBF8F3), ShareGradient.VERTICAL,
        ShareTexture.Watercolor(listOf(0x8FB3D9, 0xA9CBB7, 0xE8BFA8)),
        0x22334A, 0x4C6A8A, 0xA3271F, ShareShadow.SOFT, ReaderFontFamily.PALATINO, ShareAlignment.CENTER),
    GLOW("glow", R.string.share_background_glow, listOf(0x2A1A14, 0x130D0A), ShareGradient.VERTICAL, ShareTexture.Glow,
        0xFFF3E3, 0xFFC98F, 0xFF9C85, ShareShadow.SOFT, ReaderFontFamily.PALATINO, ShareAlignment.CENTER),
    LINEN("linen", R.string.share_template_linen, listOf(0xF8F5EF), ShareGradient.VERTICAL, ShareTexture.Linen,
        0x2E2A25, 0x9C7A4E, 0xB0261B, ShareShadow.NONE, ReaderFontFamily.IOWAN, ShareAlignment.CENTER),
    NIGHT("night", R.string.share_template_night, listOf(0x0B1026, 0x1D2A57), ShareGradient.VERTICAL, ShareTexture.Clean,
        0xE9EDF8, 0xA9B8F0, 0xFF8A80, ShareShadow.NONE, ReaderFontFamily.NEW_YORK, ShareAlignment.CENTER),
    BOKEH("bokeh", R.string.share_background_bokeh, listOf(0x141A36, 0x2A1B3F), ShareGradient.DIAGONAL,
        ShareTexture.Bokeh(listOf(0xFFC67A, 0xFF8FB1, 0x8FD3FF)),
        0xFFFFFF, 0xFFE0B8, 0xFFA99E, ShareShadow.STRONG, ReaderFontFamily.AVENIR, ShareAlignment.CENTER),
    DAWN("dawn", R.string.share_template_dawn, listOf(0xF7D9C4, 0xEFB4A8, 0xA893CC), ShareGradient.VERTICAL, ShareTexture.Clean,
        0x2E2236, 0x6B4A6E, 0x9E1B32, ShareShadow.NONE, ReaderFontFamily.AVENIR, ShareAlignment.CENTER),
    LATTICE("lattice", R.string.share_background_lattice, listOf(0xEFF3EC), ShareGradient.VERTICAL, ShareTexture.Lattice(0x4F6B55),
        0x1F3326, 0x4A6650, 0xA3271F, ShareShadow.NONE, ReaderFontFamily.SAN_FRANCISCO, ShareAlignment.CENTER),
    MIST("mist", R.string.share_background_mist, listOf(0xE9EFF4, 0xDCE4EC), ShareGradient.VERTICAL, ShareTexture.Clean,
        0x1E2B3A, 0x55697F, 0xA8322A, ShareShadow.NONE, ReaderFontFamily.AVENIR, ShareAlignment.LEADING),
    GRAIN("grain", R.string.share_background_grain, listOf(0x3A342E, 0x1A1815), ShareGradient.RADIAL, ShareTexture.Grain,
        0xF3EADB, 0xD8B98A, 0xFF9C85, ShareShadow.SOFT, ReaderFontFamily.CHARTER, ShareAlignment.LEADING),
    CONTOUR("contour", R.string.share_background_contour, listOf(0xEFF1F4, 0xE1E6EC), ShareGradient.VERTICAL, ShareTexture.Contour(0x5D6B80),
        0x1E2633, 0x55637A, 0xA8322A, ShareShadow.NONE, ReaderFontFamily.CHARTER, ShareAlignment.LEADING),
    DUSK("dusk", R.string.share_background_dusk, listOf(0x2E335F, 0x8E4B69), ShareGradient.DIAGONAL, ShareTexture.Clean,
        0xFFF4EE, 0xF6C7B6, 0xFFB0A0, ShareShadow.SOFT, ReaderFontFamily.NEW_YORK, ShareAlignment.CENTER),
    CANVAS("canvas", R.string.share_background_canvas, listOf(0xEAE4D8), ShareGradient.VERTICAL, ShareTexture.Canvas,
        0x2A2620, 0x75634A, 0xA3271F, ShareShadow.NONE, ReaderFontFamily.GEORGIA, ShareAlignment.CENTER),
    SAGE("sage", R.string.share_background_sage, listOf(0xDFE8D7, 0xD3DEC9), ShareGradient.VERTICAL, ShareTexture.Clean,
        0x223321, 0x52684D, 0xA3271F, ShareShadow.NONE, ReaderFontFamily.IOWAN, ShareAlignment.CENTER),
    TIDE("tide", R.string.share_background_tide, listOf(0x0E3A46, 0x2F6A5E), ShareGradient.DIAGONAL, ShareTexture.Clean,
        0xF1F7F2, 0xA8D8C6, 0xFFB4A2, ShareShadow.SOFT, ReaderFontFamily.AVENIR, ShareAlignment.LEADING),
    INK("ink", R.string.share_template_ink, listOf(0x14161A), ShareGradient.VERTICAL, ShareTexture.Clean,
        0xEDE8DF, 0xC9A45C, 0xFF7A6B, ShareShadow.NONE, ReaderFontFamily.GEORGIA, ShareAlignment.CENTER),
    STONE("stone", R.string.share_template_stone, listOf(0xDEDCD7, 0xC3C0B9), ShareGradient.VERTICAL, ShareTexture.Clean,
        0x26262A, 0x5A5A62, 0x9B2226, ShareShadow.NONE, ReaderFontFamily.CHARTER, ShareAlignment.CENTER),
    SAND("sand", R.string.share_background_sand, listOf(0xF2EADC, 0xE7DAC5), ShareGradient.VERTICAL, ShareTexture.Clean,
        0x3A2E22, 0x80603E, 0xA3271F, ShareShadow.NONE, ReaderFontFamily.PALATINO, ShareAlignment.CENTER),
    BLUSH("blush", R.string.share_background_blush, listOf(0xF7E8E3, 0xEED7D0), ShareGradient.VERTICAL, ShareTexture.Clean,
        0x4A2630, 0x8E5260, 0xA3271F, ShareShadow.NONE, ReaderFontFamily.AVENIR, ShareAlignment.CENTER),
    OLIVE("olive", R.string.share_template_olive, listOf(0x46512F, 0x2D3520), ShareGradient.VERTICAL, ShareTexture.Clean,
        0xF2EFDD, 0xD6C58C, 0xFFA48A, ShareShadow.NONE, ReaderFontFamily.NEW_YORK, ShareAlignment.CENTER),
    MINIMAL("minimal", R.string.share_template_minimal, listOf(0xFFFFFF), ShareGradient.VERTICAL, ShareTexture.Clean,
        0x111111, 0x6E6E6E, 0xC0392B, ShareShadow.NONE, ReaderFontFamily.SAN_FRANCISCO, ShareAlignment.LEADING);

    val title: String get() = AppText.get(titleRes)

    val isTextured: Boolean get() = texture != ShareTexture.Clean

    /** A hairline frame inset from the edge (the paper-like grounds). */
    val hasFrame: Boolean get() = this == PARCHMENT || this == LINEN || this == CANVAS

    /** Light text on this ground (judged from its colours, before texture). */
    val isDark: Boolean get() = ShareContrast.luminance(ShareContrast.average(colors)) < 0.3

    /** Five text colours that suit the ground, besides its own. */
    val palette: List<ShareInkSwatch>
        get() = (if (isDark) ShareInkSwatch.FOR_DARK else ShareInkSwatch.FOR_LIGHT).filter { it.hex != ink }.take(5)

    /** The template a share link carries for this ground: itself, or the nearest the web knows. */
    val linkTemplate: ShareTemplate
        get() = ShareTemplate.fromRaw(raw) ?: when (this) {
            WATERCOLOR, CANVAS -> ShareTemplate.LINEN
            GLOW, GRAIN -> ShareTemplate.INK
            BOKEH, DUSK, TIDE -> ShareTemplate.NIGHT
            LATTICE, CONTOUR, MIST, SAGE -> ShareTemplate.STONE
            SAND -> ShareTemplate.PARCHMENT
            BLUSH -> ShareTemplate.DAWN
            else -> ShareTemplate.PARCHMENT
        }

    /**
     * The ground as the designer measured it on iOS ([ShareBackdrop.stats] measures Android's own
     * drawing at run time; this table is what the JVM tests check the curated colours against, and
     * `ShareBackdropDeviceTest` checks the drawing stays close to it).
     */
    val referenceStats: ShareBackdropStats get() = REFERENCE.getValue(this)

    companion object {
        fun fromRaw(raw: String?): ShareBackground? = entries.firstOrNull { it.raw == raw }
        val CLEAN: List<ShareBackground> get() = entries.filter { !it.isTextured }
        val TEXTURED: List<ShareBackground> get() = entries.filter { it.isTextured }

        private val REFERENCE: Map<ShareBackground, ShareBackdropStats> by lazy {
            mapOf(
                PARCHMENT to ShareBackdropStats(0xE9DEC6L, 0xF1E8D3L, 0xDBCFB4L),
                WATERCOLOR to ShareBackdropStats(0xE8E8E3L, 0xF9F6F1L, 0xC0C6C0L),
                GLOW to ShareBackdropStats(0x42271CL, 0x87512CL, 0x231712L),
                LINEN to ShareBackdropStats(0xF6F3EDL, 0xF7F4EEL, 0xF4F1EBL),
                NIGHT to ShareBackdropStats(0x141D3FL, 0x1D2955L, 0x0C1229L),
                BOKEH to ShareBackdropStats(0x221D3AL, 0x403E5EL, 0x12162BL),
                DAWN to ShareBackdropStats(0xDFB5B8L, 0xF6D5C1L, 0xAF96C8L),
                LATTICE to ShareBackdropStats(0xEDF1EAL, 0xEEF2EBL, 0xECF1EAL),
                MIST to ShareBackdropStats(0xE2E9F0L, 0xE8EEF3L, 0xDCE4ECL),
                GRAIN to ShareBackdropStats(0x292622L, 0x3A352FL, 0x171613L),
                CONTOUR to ShareBackdropStats(0xE6EAEEL, 0xEDEFF2L, 0xE0E4EBL),
                DUSK to ShareBackdropStats(0x5E3F64L, 0x884A69L, 0x343560L),
                CANVAS to ShareBackdropStats(0xE4DED3L, 0xE8E3D7L, 0xDCD6CBL),
                SAGE to ShareBackdropStats(0xD9E2D0L, 0xDEE7D6L, 0xD3DEC9L),
                TIDE to ShareBackdropStats(0x1F5252L, 0x2E685DL, 0x103D48L),
                INK to ShareBackdropStats(0x14161AL, 0x14161AL, 0x14161AL),
                STONE to ShareBackdropStats(0xD0CEC8L, 0xDCDAD5L, 0xC4C1BAL),
                SAND to ShareBackdropStats(0xECE2D0L, 0xF1E9DAL, 0xE7DAC6L),
                BLUSH to ShareBackdropStats(0xF2DFD9L, 0xF6E7E2L, 0xEED7D1L),
                OLIVE to ShareBackdropStats(0x3A4328L, 0x45502FL, 0x2F3721L),
                MINIMAL to ShareBackdropStats(0xFFFFFFL, 0xFFFFFFL, 0xFFFFFFL),
            )
        }
    }
}

/** The ground as text sees it — `ShareBackdropStats`: its average and its lightest and darkest patches. */
data class ShareBackdropStats(val mean: Long, val lightest: Long, val darkest: Long) {
    companion object {
        /** From the ground's colours alone, until its drawing has been measured. */
        fun approximate(background: ShareBackground): ShareBackdropStats {
            val stops = background.colors
            return ShareBackdropStats(
                ShareContrast.average(stops), stops.maxBy { ShareContrast.luminance(it) }, stops.minBy { ShareContrast.luminance(it) },
            )
        }
    }
}

/** The colours and shadow a card is actually drawn with — `ShareColors`. */
data class ShareColors(
    val ink: Long,
    val accent: Long,
    val red: Long,
    val shadow: ShareShadow,
    /** Black under light text, white around dark text. */
    val shadowColor: Long,
    /** The text colour or shadow was changed to keep the text readable. */
    val adjusted: Boolean,
) {
    val glow: Boolean get() = shadowColor == 0xFFFFFFL
}

/** WCAG contrast, and the rules that keep a card's text readable — `ShareContrast`. */
object ShareContrast {
    const val TARGET = 4.5
    const val MINIMUM = 3.0

    fun luminance(hex: Long): Double {
        fun channel(v: Long): Double {
            val c = (v and 0xFF).toDouble() / 255
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(hex shr 16) + 0.7152 * channel(hex shr 8) + 0.0722 * channel(hex)
    }

    fun ratio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** [a] moved [t] (0…1) of the way to [b], per channel. */
    fun mix(a: Long, b: Long, t: Double): Long {
        fun channel(shift: Int): Long {
            val x = ((a shr shift) and 0xFF).toDouble()
            val y = ((b shr shift) and 0xFF).toDouble()
            return Math.round(x + (y - x) * t).coerceIn(0, 255) shl shift
        }
        return channel(16) or channel(8) or channel(0)
    }

    fun average(colors: List<Long>): Long {
        if (colors.isEmpty()) return 0
        fun channel(shift: Int): Long = (colors.sumOf { (it shr shift) and 0xFF } / colors.size) shl shift
        return channel(16) or channel(8) or channel(0)
    }

    data class Check(val mean: Double, val worst: Double)

    /** Contrast against the average ground, and against its worst patch (or the average, if lower). */
    fun check(ink: Long, stats: ShareBackdropStats): Check {
        val mean = ratio(ink, stats.mean)
        return Check(mean, minOf(mean, ratio(ink, stats.lightest), ratio(ink, stats.darkest)))
    }

    /** The candidate with the most contrast on the ground's worst patch. */
    fun bestInk(stats: ShareBackdropStats, candidates: List<Long>): Long =
        candidates.maxByOrNull { check(it, stats).worst } ?: 0x111111

    /** [color] nudged toward black or white until it reaches [target] on the average ground. */
    fun nudge(color: Long, target: Double, stats: ShareBackdropStats): Long {
        if (ratio(color, stats.mean) >= target) return color
        val pole = if (ratio(0xFFFFFF, stats.mean) >= ratio(0x000000, stats.mean)) 0xFFFFFFL else 0x000000L
        for (step in 1..20) {
            val candidate = mix(color, pole, step * 0.05)
            if (ratio(candidate, stats.mean) >= target) return candidate
        }
        return pole
    }

    /** `ShareContrast.resolve`: the curated colours, or a picked one nudged to 4.5:1, plus a shadow if a patch is under 3:1. */
    fun resolve(background: ShareBackground, picked: Long?, chosen: ShareShadow, stats: ShareBackdropStats): ShareColors {
        var ink = picked ?: background.ink
        var shadow = chosen
        var adjusted = false
        if (check(ink, stats).mean < TARGET) {
            ink = nudge(ink, TARGET, stats)
            adjusted = true
        }
        if (check(ink, stats).worst < MINIMUM && shadow == ShareShadow.NONE) {
            shadow = ShareShadow.SOFT
            adjusted = true
        }
        val accent = nudge(picked?.let { mix(it, stats.mean, 0.22) } ?: background.accent, MINIMUM, stats)
        val red = nudge(background.red, MINIMUM, stats)
        val shadowColor = if (luminance(ink) > 0.4) 0x000000L else 0xFFFFFFL
        return ShareColors(ink, accent, red, shadow, shadowColor, adjusted)
    }
}

/** `ShareAspect`: layout size in points; the export renders at 2× (2160 px on the long side). */
enum class ShareAspect(val raw: String, @StringRes private val titleRes: Int, val width: Float, val height: Float) {
    SQUARE("square", R.string.share_aspect_square, 1080f, 1080f),
    STORY("story", R.string.share_aspect_story, 608f, 1080f),
    WIDE("wide", R.string.share_aspect_wide, 1080f, 608f);

    val title: String get() = AppText.get(titleRes)

    companion object {
        fun fromRaw(raw: String?): ShareAspect? = entries.firstOrNull { it.raw == raw }
    }
}

/** `ShareAlignment`. */
enum class ShareAlignment(val raw: String, @StringRes private val titleRes: Int) {
    LEADING("leading", R.string.share_alignment_leading), CENTER("center", R.string.share_alignment_center);

    val title: String get() = AppText.get(titleRes)

    companion object {
        fun fromRaw(raw: String?): ShareAlignment? = entries.firstOrNull { it.raw == raw }
    }
}

/** Everything the designer lets you change, as one value — `ShareStyle`. */
data class ShareStyle(
    val background: ShareBackground = ShareBackground.PARCHMENT,
    /** The text colour picked, or null for the ground's own (picked afresh when the ground changes). */
    val ink: Long? = null,
    val shadow: ShareShadow = ShareShadow.NONE,
    val aspect: ShareAspect = ShareAspect.SQUARE,
    val family: ReaderFontFamily = ReaderFontFamily.DEFAULT,
    val alignment: ShareAlignment = ShareAlignment.CENTER,
    val redLetters: Boolean = true,
    val verseNumbers: Boolean = true,
    val wordmark: Boolean = true,
) {
    /** The share-link template (`tp`). */
    val template: ShareTemplate get() = background.linkTemplate

    /** A ready-made style: the ground with its own text colour, shadow, typeface and alignment. */
    fun applying(look: ShareBackground): ShareStyle = copy(
        background = look, ink = null, shadow = look.presetShadow, family = look.presetFamily, alignment = look.presetAlignment,
    )

    fun colors(stats: ShareBackdropStats): ShareColors = ShareContrast.resolve(background, ink, shadow, stats)

    /** Remembers this design under iOS's `share.*` keys. */
    fun save(p: MutablePreferences) {
        p[ReaderKeys.SHARE_TEMPLATE] = background.raw
        p[ReaderKeys.SHARE_INK] = ink?.let { "%06X".format(it and 0xFFFFFF) } ?: ""
        p[ReaderKeys.SHARE_SHADOW] = shadow.raw
        p[ReaderKeys.SHARE_ASPECT] = aspect.raw
        p[ReaderKeys.SHARE_FONT_FAMILY] = family.raw
        p[ReaderKeys.SHARE_ALIGNMENT] = alignment.raw
        p[ReaderKeys.SHARE_RED_LETTERS] = redLetters
        p[ReaderKeys.SHARE_VERSE_NUMBERS] = verseNumbers
        p[ReaderKeys.SHARE_WORDMARK] = wordmark
    }

    companion object {
        /** The last design, as remembered — a template saved before these styles is a ground of the same name. */
        fun from(saved: ReaderSettings): ShareStyle = ShareStyle(
            background = ShareBackground.fromRaw(saved.shareTemplate) ?: ShareBackground.PARCHMENT,
            ink = saved.shareInk?.takeIf { it.length == 6 }?.toLongOrNull(16),
            shadow = ShareShadow.fromRaw(saved.shareShadow) ?: ShareShadow.NONE,
            aspect = ShareAspect.fromRaw(saved.shareAspect) ?: ShareAspect.SQUARE,
            family = ReaderFontFamily.fromRaw(saved.shareFontFamily) ?: ReaderFontFamily.DEFAULT,
            alignment = ShareAlignment.fromRaw(saved.shareAlignment) ?: ShareAlignment.CENTER,
            redLetters = saved.shareRedLetters ?: true,
            verseNumbers = saved.shareVerseNumbers ?: true,
            wordmark = saved.shareWordmark ?: true,
        )
    }
}

/** What a card says, after fitting — `ShareCardContent`. */
data class ShareCardContent(
    val passage: SharePassageText,
    /** "John 3:16–17" */
    val reference: String,
    /** "ASV" */
    val translation: String,
    /** The translation's required notice (licensed translations only; public-domain texts need none). */
    val notice: String?,
    /** Point size of the passage text, chosen by [ShareCardFitter]. */
    val fontSize: Float,
)

/**
 * Proportions of a card, derived from its size so the three aspects share one design —
 * `ShareCardMetrics`, mirrored in `docs/share-links.md` for the web renderer.
 */
data class ShareCardMetrics(val width: Float, val height: Float) {
    constructor(aspect: ShareAspect) : this(aspect.width, aspect.height)

    val short: Float get() = min(width, height)
    val horizontalPadding: Float get() = width * 0.09f
    val verticalPadding: Float get() = height * 0.09f
    val referenceSize: Float get() = max(18f, short * 0.034f)
    val wordmarkSize: Float get() = max(14f, short * 0.024f)
    val ruleWidth: Float get() = referenceSize * 1.6f
    val textWidth: Float get() = width - horizontalPadding * 2
    /** Rule, gap and reference line under the passage. */
    val referenceBlock: Float get() = referenceSize * 3.6f
    val wordmarkBlock: Float get() = wordmarkSize * 2.4f
    val maxFontSize: Float get() = sqrt(width * height) * 0.066f
    val minFontSize: Float get() = max(20f, short * 0.022f)
    val lineSpacingRatio: Float get() = 0.28f

    fun textHeight(footer: Boolean): Float =
        height - verticalPadding * 2 - referenceBlock - (if (footer) wordmarkBlock else 0f)

    companion object {
        const val NUMBER_SCALE = 0.55f
        const val NUMBER_RISE = 0.32f
    }
}

/**
 * Chooses the largest type that fits, and trims long passages to whole verses when even the smallest
 * size can't hold them — `ShareCardFitter`. The measurement itself is a parameter ([measure]: the
 * wrapped height of a passage at a size, in card points), so the search is tested on the JVM and the
 * app measures with the same `StaticLayout` the card draws with.
 */
object ShareCardFitter {
    /** More than this many verses stops being a card and becomes a page. */
    const val MAX_VERSES = 12

    data class Result(val content: ShareCardContent, val shownVerses: Int, val totalVerses: Int) {
        val trimmed: Boolean get() = shownVerses < totalVerses
    }

    fun fit(
        verses: List<ShareVerse>,
        translation: String,
        notice: String?,
        style: ShareStyle,
        rangesOf: (List<ShareVerse>) -> List<VerseRange>,
        measure: (SharePassageText, Float) -> Float,
    ): Result {
        val metrics = ShareCardMetrics(style.aspect)
        val footer = style.wordmark || notice != null
        val height = metrics.textHeight(footer)
        var count = min(verses.size, MAX_VERSES)
        var chosen: Pair<SharePassageText, Float>? = null
        while (count > 0) {
            val passage = SharePassageText.of(verses.take(count), numbered = style.verseNumbers)
            val size = largestFittingSize(metrics, height) { measure(passage, it) }
            if (size != null) {
                chosen = passage to size
                break
            }
            if (count == 1) break
            count -= 1
        }
        // A single verse too long for the smallest size still renders; the card scales it down.
        val (passage, size) = chosen
            ?: (SharePassageText.of(verses.take(1), numbered = style.verseNumbers) to metrics.minFontSize)
        val shown = max(1, count)
        val reference = rangesOf(verses.take(shown)).joinToString(", ") { it.display }
        return Result(ShareCardContent(passage, reference, translation, notice, size), shown, verses.size)
    }

    /** The binary search of `largestFittingSize`: 12 halvings between the metrics' bounds, floored. */
    fun largestFittingSize(metrics: ShareCardMetrics, height: Float, heightAt: (Float) -> Float): Float? {
        // A little slack for the difference between measurement and drawing, as on iOS.
        fun fits(size: Float) = kotlin.math.ceil(heightAt(size)) <= height * 0.95f
        var low = metrics.minFontSize
        var high = metrics.maxFontSize
        if (!fits(low)) return null
        if (fits(high)) return high
        repeat(12) {
            val mid = (low + high) / 2
            if (fits(mid)) low = mid else high = mid
        }
        return floor(low)
    }

    /** Where a passage too long for one card is trimmed, said plainly — the designer's note. */
    fun trimNote(result: Result): String? = if (!result.trimmed) null else
        AppText.get(R.string.share_trim_note, result.shownVerses, result.totalVerses, result.content.reference)
}

/**
 * Verse numbers in a share link's text, found as `docs/share-links.md` tells the web to: the numbers
 * expected from `k`, in order, each matched as a token at the start of the text or after a space and
 * followed by a space. A verse that opens a new chapter is "c:v". Anything that doesn't match stays
 * plain text — a link written by another app, or one whose text doesn't number its verses, simply
 * shows no styled numbers.
 */
object ShareLinkNumbers {
    fun find(text: String, ranges: List<VerseRange>): List<IntRange> {
        val starts = ranges.map { it.start }
        if (starts.isEmpty()) return emptyList()
        // Only a passage of more than one verse is numbered.
        if (ranges.size == 1 && ranges[0].start == ranges[0].end) return emptyList()
        val found = mutableListOf<IntRange>()
        var position = 0
        var book = starts[0].book
        var chapter = starts[0].chapter
        var verse = starts[0].verse
        var nextRange = 1

        fun tokenAt(at: Int, token: String): Boolean =
            (at == 0 || text[at - 1] == ' ') && text.startsWith(token, at) &&
                at + token.length < text.length && text[at + token.length] == ' '

        // The first verse's number opens the text.
        if (!tokenAt(0, "$verse")) return emptyList()
        found += 0 until "$verse".length
        position = "$verse".length + 1
        while (position < text.length && found.size < 2_000) {
            // The candidates for the next number: the next verse, the next chapter's first, the next range's start.
            val candidates = buildList {
                add(Triple("${verse + 1}", chapter, verse + 1))
                add(Triple("${chapter + 1}:1", chapter + 1, 1))
                starts.getOrNull(nextRange)?.let { s ->
                    if (s.book == book && s.chapter == chapter) add(Triple("${s.verse}", s.chapter, s.verse))
                    else add(Triple("${s.chapter}:${s.verse}", s.chapter, s.verse))
                }
            }
            var matched: Pair<Int, Triple<String, Int, Int>>? = null
            var search = position
            while (search < text.length && matched == null) {
                val space = text.indexOf(' ', search)
                if (space < 0) break
                val at = space + 1
                for (candidate in candidates) {
                    if (tokenAt(at, candidate.first)) {
                        matched = at to candidate
                        break
                    }
                }
                search = at
            }
            val (at, candidate) = matched ?: break
            found += at until at + candidate.first.length
            val jumpedRange = starts.getOrNull(nextRange)?.let { it.chapter == candidate.second && it.verse == candidate.third } == true
            if (jumpedRange) {
                book = starts[nextRange].book
                nextRange += 1
            }
            chapter = candidate.second
            verse = candidate.third
            position = at + candidate.first.length + 1
        }
        return found
    }
}
