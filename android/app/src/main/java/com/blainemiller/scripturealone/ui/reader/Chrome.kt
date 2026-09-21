package com.blainemiller.scripturealone.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * The reader's stand-in for Liquid Glass: one translucent fill lifted off the surface beneath and one
 * hairline — never a background inside a background. [lifted] adds the soft drop shadow iOS gives
 * glass on a light page (the search field, the sheet's Close button); on a dark page glass reads by
 * its edge, and a shadow there is invisible anyway.
 */
fun Modifier.glass(
    palette: ReaderPalette,
    shape: Shape,
    surface: Color = palette.page,
    lifted: Boolean = false,
    opacity: Float = 0.92f,
): Modifier {
    val lift = if (palette.isDark) Color.White else Color.Black
    val shadowed = if (lifted && !palette.isDark) {
        shadow(8.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.14f))
    } else {
        this
    }
    return shadowed
        .clip(shape)
        .background(lift.copy(alpha = if (palette.isDark) 0.07f else 0.045f).compositeOver(surface.copy(alpha = opacity)))
        .border(0.5.dp, lift.copy(alpha = if (palette.isDark) 0.12f else 0.08f), shape)
}

/**
 * The surfaces and fills iOS's system styles give a sheet, derived from the reader's page so a Sepia
 * reader gets a Sepia sheet. The fills are UIKit's semantic fills (`tertiarySystemFill`,
 * `secondarySystemFill`), which are grey at a fixed alpha over whatever is beneath.
 */
object SheetColors {
    /** A sheet over the page: the page itself when light, lifted a step when dark (as iOS elevates). */
    fun surface(palette: ReaderPalette): Color =
        if (palette.isDark) Color.White.copy(alpha = 0.06f).compositeOver(palette.page) else palette.page

    /** `.fill.tertiary` — unselected chapter cells. */
    fun tertiaryFill(palette: ReaderPalette): Color = Color(0xFF767680).copy(alpha = if (palette.isDark) 0.24f else 0.12f)

    /** `.bordered` button fill — the Recent chips. */
    fun buttonFill(palette: ReaderPalette): Color = Color(0xFF787880).copy(alpha = if (palette.isDark) 0.32f else 0.16f)

    /** A separator line. */
    fun separator(palette: ReaderPalette): Color = palette.secondary.copy(alpha = if (palette.isDark) 0.35f else 0.28f)

    /** The footnote popover — iOS's popover material, over the reader's page. */
    fun popover(palette: ReaderPalette): Color =
        if (palette.isDark) Color.White.copy(alpha = 0.11f).compositeOver(palette.page) else palette.page
}

/**
 * Takes taps — a scrim's tap-to-dismiss, or a sheet swallowing taps so they don't fall through to the
 * text behind — **without** becoming an accessibility node. A `clickable` there would be a TalkBack
 * button with no label, and worse, would merge every text inside the sheet into that one node. TalkBack
 * dismisses with Back instead, which every sheet handles.
 */
fun Modifier.takesTaps(onTap: () -> Unit = {}): Modifier = composed {
    // The latest action, without restarting the gesture detector (and losing a tap) on recomposition.
    val action by rememberUpdatedState(onTap)
    pointerInput(Unit) { detectTapGestures { action() } }
}

/**
 * Bars keep their text within [max]× the system font size, as iOS's toolbars and navigation bars stop
 * growing at the accessibility sizes (where iOS offers the Large Content Viewer instead): a row of
 * fixed pills can't hold 2× text without clipping the passage title to "Jo…". The reading text and the
 * sheets' contents still take the full scale, and TalkBack reads every control's label either way.
 */
@Composable
fun CappedFontScale(max: Float = BAR_FONT_SCALE_MAX, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (density.fontScale <= max) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides Density(density.density, max), content = content)
    }
}

/**
 * One line of bar text that scales down to fit — to 70%, SwiftUI's `minimumScaleFactor(0.7)` — before
 * it is cut with an ellipsis. For navigation-bar titles, which a larger font size would otherwise clip.
 */
@Composable
fun FitTitle(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = FontWeight.SemiBold,
    textAlign: TextAlign? = TextAlign.Center,
) {
    var scale by remember(text) { mutableStateOf(1f) }
    Text(
        text, color = color, fontSize = fontSize * scale, fontWeight = fontWeight, textAlign = textAlign,
        maxLines = 1, softWrap = false,
        overflow = if (scale > 0.7f) TextOverflow.Clip else TextOverflow.Ellipsis,
        onTextLayout = { if (it.didOverflowWidth && scale > 0.7f) scale -= 0.05f },
        modifier = modifier,
    )
}

/** How far the bars' text follows the system font size. */
const val BAR_FONT_SCALE_MAX = 1.3f
