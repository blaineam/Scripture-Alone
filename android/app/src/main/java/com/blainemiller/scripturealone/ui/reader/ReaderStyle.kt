package com.blainemiller.scripturealone.ui.reader

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.text.AppText

/**
 * The reader's themes, with the exact page / ink / secondary / red / accent values of
 * `ReaderTheme.palette(for:)` in `ScriptureAlone/Reader/ReaderStyle.swift`. These are the colours a
 * reader who uses both platforms will compare side by side, so they are copied, not re-tuned.
 */
enum class ReaderTheme(@StringRes private val titleRes: Int) {
    SYSTEM(R.string.reader_theme_auto), LIGHT(R.string.reader_theme_light), SEPIA(R.string.reader_theme_sepia),
    DARK(R.string.reader_theme_dark), BLACK(R.string.reader_theme_black);

    /** The theme's name in the Appearance sheet, in the app's language. */
    val title: String get() = AppText.get(titleRes)

    /** [systemDark] is the device's current appearance; only [SYSTEM] follows it. */
    fun palette(systemDark: Boolean): ReaderPalette = when (this) {
        SEPIA -> ReaderPalette(
            page = rgb(0xF5EDDC), ink = rgb(0x3A2E1F), secondary = rgb(0x86725A),
            red = rgb(0xA12A1C), accent = rgb(0x8A5A2B), isDark = false,
        )
        BLACK -> ReaderPalette(
            page = rgb(0x000000), ink = rgb(0xD9D6D0), secondary = rgb(0x8C8983),
            red = rgb(0xFF7A6B), accent = rgb(0xE0B872), isDark = true,
        )
        DARK -> ReaderPalette.Dark
        LIGHT -> ReaderPalette.Light
        SYSTEM -> if (systemDark) ReaderPalette.Dark else ReaderPalette.Light
    }

    /** The Swift raw value, as stored under `reader.theme`: "system", "sepia", … */
    val raw: String get() = name.lowercase()

    companion object {
        fun fromRaw(raw: String?): ReaderTheme? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * The colour of verse numbers, links, selection and controls. `SUNRISE` is the icon's gold and the
 * default. Each has a light-page and a dark-page value, from `ReaderAccent.pair`: the dark one is
 * lifted rather than merely brightened, because a colour that reads well on paper goes muddy on
 * black at the same saturation.
 */
enum class ReaderAccent(@StringRes private val titleRes: Int, private val light: Long, private val dark: Long) {
    SUNRISE(R.string.reader_accent_sunrise, 0x9A6B2F, 0xE0B872),
    EMBER(R.string.reader_accent_ember, 0xA8412A, 0xF08A6C),
    OLIVE(R.string.reader_accent_olive, 0x5E6B32, 0xB6C57A),
    SEA(R.string.reader_accent_sea, 0x1F6F72, 0x76CBCE),
    LAPIS(R.string.reader_accent_lapis, 0x2C4C8C, 0x8FB3F0),
    PLUM(R.string.reader_accent_plum, 0x6E3A72, 0xC79AD0),
    INK(R.string.reader_accent_ink, 0x45484D, 0xB3B7BE);

    /** The accent's name in the Appearance sheet, in the app's language. */
    val title: String get() = AppText.get(titleRes)

    fun color(isDark: Boolean): Color = rgb(if (isDark) dark else light)

    /** The swatch in a picker, always its light-page value — as on iOS. */
    val swatch: Color get() = rgb(light)

    /** The Swift raw value, as stored under `reader.accent`: "sunrise", "sea", … */
    val raw: String get() = name.lowercase()

    companion object {
        fun fromRaw(raw: String?): ReaderAccent? = entries.firstOrNull { it.raw == raw }
    }
}

data class ReaderPalette(
    val page: Color,
    val ink: Color,
    val secondary: Color,
    val red: Color,
    val accent: Color,
    val isDark: Boolean,
) {
    /** The same palette with the reader's chosen accent in place of the theme's own. */
    fun accented(accent: ReaderAccent): ReaderPalette = copy(accent = accent.color(isDark))

    companion object {
        val Light = ReaderPalette(
            page = rgb(0xFDFCFA), ink = rgb(0x1D1B18), secondary = rgb(0x7A756D),
            red = rgb(0xB0261B), accent = rgb(0x9A6B2F), isDark = false,
        )
        val Dark = ReaderPalette(
            page = rgb(0x17181A), ink = rgb(0xE6E3DD), secondary = rgb(0x9A968F),
            red = rgb(0xFF6F61), accent = rgb(0xE0B872), isDark = true,
        )
    }
}

enum class ReadingLayout(@StringRes private val titleRes: Int) {
    PARAGRAPHS(R.string.reader_layout_paragraphs), VERSES(R.string.reader_layout_verses);

    /** The layout's name in the Appearance sheet, in the app's language. */
    val title: String get() = AppText.get(titleRes)

    /** The Swift raw value, as stored under `reader.layout`: "paragraphs" or "verses". */
    val raw: String get() = name.lowercase()

    companion object {
        fun fromRaw(raw: String?): ReadingLayout? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * Everything the renderer needs to know about presentation, as one comparable value — `ReaderStyle`
 * on iOS. The defaults are the iOS `@AppStorage` defaults in `ReaderView.swift`: 19 pt, line
 * spacing 1.35, paragraphs, every "show" toggle on.
 *
 * [size] is in points on iOS and is used here as sp, so it follows the system font scale the way
 * the iOS size follows Dynamic Type.
 */
data class ReaderStyle(
    val size: Float = DEFAULT_SIZE,
    val lineSpacing: Float = DEFAULT_LINE_SPACING,
    val layout: ReadingLayout = ReadingLayout.PARAGRAPHS,
    val redLetters: Boolean = true,
    val verseNumbers: Boolean = true,
    val headings: Boolean = true,
    val footnotes: Boolean = true,
    val palette: ReaderPalette,
    val family: ReaderFontFamily = ReaderFontFamily.DEFAULT,
) {
    companion object {
        const val DEFAULT_SIZE = 19f
        const val DEFAULT_LINE_SPACING = 1.35f
        val SIZE_RANGE = 12f..40f
        val LINE_SPACING_RANGE = 1.0f..2.0f
    }
}

private fun rgb(hex: Long): Color = Color(0xFF000000 or hex)
