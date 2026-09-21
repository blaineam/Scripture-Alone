package com.blainemiller.scripturealone.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
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
