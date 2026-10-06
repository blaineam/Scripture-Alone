package com.blainemiller.scripturealone.ui.system

import android.content.res.Configuration
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat

/**
 * Edge to edge, the one way: every activity draws behind transparent status and navigation bars from
 * `onCreate` (before `setContent`), on every Android version — not only on 15+, where the system
 * enforces it — and each screen pads itself by the insets it needs. Afterwards a screen only changes
 * the bars' *icons* ([icons]), never the window again.
 */
object SystemBars {

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
