package com.blainemiller.scripturealone.ui.reader

import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteHapticsTest {
    @Test fun addingConfirmsAndRemovingTicks() {
        assertEquals(HapticFeedbackConstants.CONFIRM, FavoriteHaptics.feedback(nowFavorite = true, sdk = 34))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, FavoriteHaptics.feedback(nowFavorite = true, sdk = 29))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, FavoriteHaptics.feedback(nowFavorite = false, sdk = 34))
    }
}
