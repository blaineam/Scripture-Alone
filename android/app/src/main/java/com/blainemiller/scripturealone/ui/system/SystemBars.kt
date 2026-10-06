package com.blainemiller.scripturealone.ui.system

import android.content.res.Configuration
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.core.view.WindowCompat

/**
 * Edge to edge, the one way: every activity draws behind transparent status and navigation bars from
 * `onCreate` (before `setContent`), on every Android version — not only on 15+, where the system
 * enforces it — and each screen pads itself by the insets it needs. Afterwards a screen only changes
 * the bars' *icons* ([icons]), never the window again.
 */
object SystemBars {

    /**
     * What chrome along the top must keep clear of: the status bar, and — in landscape — a navigation
     * bar or display cutout at either side (3-button navigation sits on the side there).
     */
    val topSafe: WindowInsets
        @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

    /** The same for chrome along the bottom: the navigation bar, and side bars or cutouts. */
    val bottomSafe: WindowInsets
        @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

    /** A pane along the trailing edge (Study beside the reader): the top and that side. */
    val trailingPaneSafe: WindowInsets
        @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.End)

    /** Sheets and panels: only the sides — a landscape side navigation bar, or a cutout. */
    val sideSafe: WindowInsets
        @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)

    /** Transparent bars, with no scrim even under 3-button navigation: the page shows through. */
    private fun transparent(dark: Boolean): SystemBarStyle =
        if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)

    /** Call in `onCreate`, before `setContent`. Icons start out matching the system's light or dark. */
    fun enable(activity: ComponentActivity) {
        val dark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        activity.enableEdgeToEdge(statusBarStyle = transparent(dark), navigationBarStyle = transparent(dark))
    }

    /**
     * Light (for a dark page) or dark icons in the status and navigation bars — what's behind them,
     * not the system theme: a Sepia page on a dark-mode phone still needs dark icons.
     */
    fun icons(activity: ComponentActivity, lightStatusIcons: Boolean, lightNavigationIcons: Boolean) {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !lightStatusIcons
            isAppearanceLightNavigationBars = !lightNavigationIcons
        }
    }
}
