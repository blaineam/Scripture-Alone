package com.blainemiller.scripturealone.ui.reader

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The tap felt when a verse is favorited or unfavorited — `FavoriteButton`'s `.sensoryFeedback(.selection)`
 * on iOS. Through [View.performHapticFeedback] without `FLAG_IGNORE_GLOBAL_SETTING`, so it follows the
 * system's touch-feedback setting: off there, nothing here.
 */
object FavoriteHaptics {
    /** A confirming tap for a new favorite (Android 11+; a key tap before), a lighter tick for a removal. */
    fun feedback(nowFavorite: Boolean, sdk: Int = Build.VERSION.SDK_INT): Int = when {
        !nowFavorite -> HapticFeedbackConstants.CLOCK_TICK
        sdk >= Build.VERSION_CODES.R -> HapticFeedbackConstants.CONFIRM
        else -> HapticFeedbackConstants.VIRTUAL_KEY
    }

    fun perform(view: View, nowFavorite: Boolean) {
        view.performHapticFeedback(feedback(nowFavorite))
    }
}
