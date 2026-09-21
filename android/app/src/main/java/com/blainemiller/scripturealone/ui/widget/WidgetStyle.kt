package com.blainemiller.scripturealone.ui.widget

import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.RemoteViews
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.unit.ColorProvider
import com.blainemiller.scripturealone.MainActivity
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.companion.ScriptureLink
import com.blainemiller.scripturealone.companion.VersePalette
import com.blainemiller.scripturealone.data.VerseRange

/**
 * The widget sizes, as WidgetKit's families. Android lets a widget be any size; these are the three
 * layouts it switches between (`SizeMode.Responsive`), each the smallest size the iOS layout suits.
 */
enum class WidgetFamily {
    SMALL, MEDIUM, LARGE;

    companion object {
        val SMALL_SIZE = DpSize(110.dp, 110.dp)
        val MEDIUM_SIZE = DpSize(240.dp, 110.dp)
        val LARGE_SIZE = DpSize(240.dp, 240.dp)
        val sizes = setOf(SMALL_SIZE, MEDIUM_SIZE, LARGE_SIZE)

        fun of(size: DpSize): WidgetFamily = when {
            size.width < MEDIUM_SIZE.width -> SMALL
            size.height < LARGE_SIZE.height -> MEDIUM
            else -> LARGE
        }
    }
}

/** The palette as Glance colors. Resource-backed, so each follows the system theme on its own. */
object WidgetColors {
    val ink = ColorProvider(R.color.widget_ink)
    val secondaryInk = ColorProvider(R.color.widget_secondary_ink)
    val accent = ColorProvider(R.color.widget_accent)

    /** The favorite badge: SwiftUI's `.red`. */
    val heart = ColorProvider(Color(0xFFFF3B30))

    fun highlight(name: String?) = ColorProvider(Color(VersePalette.highlight(name)))
}

/**
 * Text the launcher draws itself, through `RemoteViews`: Glance's `Text` can neither color part of a
 * string (the words of Christ) nor shrink to fit (`minimumScaleFactor` on iOS), and a TextView can do
 * both — spans for the red, `autoSizeTextType="uniform"` for the fit — in Source Serif 4.
 */
object WidgetText {

    /**
     * The verse body for [family]. [red] holds words-of-Christ spans as (start, length) in Unicode
     * scalars, as the databases and `DailyVerses.json` store them; null or empty draws plain text.
     */
    fun verse(context: Context, family: WidgetFamily, text: String, red: List<Pair<Int, Int>>? = null): RemoteViews {
        val layout = when (family) {
            WidgetFamily.SMALL -> R.layout.widget_verse_small
            WidgetFamily.MEDIUM -> R.layout.widget_verse_medium
            WidgetFamily.LARGE -> R.layout.widget_verse_large
        }
        return RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.verse_day, colored(text, red, VersePalette.LIGHT.wordsOfChrist.toInt()))
            setTextViewText(R.id.verse_night, colored(text, red, VersePalette.DARK.wordsOfChrist.toInt()))
            setContentDescription(R.id.verse_day, text)
            setContentDescription(R.id.verse_night, text)
        }
    }

    /** A passage reference in the serif semibold, in the accent. */
    fun reference(context: Context, text: String): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_reference).apply { setTextViewText(R.id.reference, text) }

    /** [text] with each scalar span in [red] colored [color]. Out-of-range spans are clipped, never thrown. */
    fun colored(text: String, red: List<Pair<Int, Int>>?, color: Int): CharSequence {
        if (red.isNullOrEmpty()) return text
        val scalars = text.codePointCount(0, text.length)
        val spannable = SpannableString(text)
        for ((start, length) in red) {
            val lo = start.coerceIn(0, scalars)
            val hi = (start + length).coerceIn(lo, scalars)
            if (lo == hi) continue
            spannable.setSpan(
                ForegroundColorSpan(color),
                text.offsetByCodePoints(0, lo), text.offsetByCodePoints(0, hi),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return spannable
    }
}

/**
 * Opens [range] in the reader — the widgets' `widgetURL`. `MainActivity` takes the chapter as launch
 * extras; the verse and the `scripturealone://` link ride along for when it scrolls to a verse.
 * [translation] is left out when null, so the reader stays in whatever it has open.
 */
fun openPassageIntent(context: Context, range: VerseRange, translation: String? = null): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra("book", range.start.book)
        putExtra("chapter", range.start.chapter)
        putExtra("verse", range.start.verse)
        putExtra("ref", range.storageString)
        putExtra("link", ScriptureLink.url(range))
        translation?.let { putExtra("translation", it) }
    }
