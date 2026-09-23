package com.blainemiller.scripturealone.data.listen

import androidx.annotation.StringRes
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.text.AppText

/**
 * Hands-free scrolling — `autoScrollControl` in `ReaderView.swift` and the display-link stepper in
 * `ChapterTextView.swift`.
 */
object AutoScroll {
    /** The four speeds, in points (dp) per second, with iOS's names. */
    enum class Speed(@StringRes private val titleRes: Int, val pointsPerSecond: Double) {
        SLOW(R.string.listen_scroll_slow, 16.0),
        RELAXED(R.string.listen_scroll_relaxed, 28.0),
        STEADY(R.string.listen_scroll_steady, 44.0),
        BRISK(R.string.listen_scroll_brisk, 64.0),
        ;

        val title: String get() = AppText.get(titleRes)
    }

    /** iOS's `@AppStorage(SettingsKey.autoScrollSpeed)` default. */
    const val DEFAULT_SPEED = 28.0

    /** A stored speed, or the default when it is none of the four (the menu can only set those). */
    fun sanitize(stored: Double?): Double =
        Speed.entries.firstOrNull { it.pointsPerSecond == stored }?.pointsPerSecond ?: DEFAULT_SPEED

    /**
     * Turns frame times into whole pixels to scroll, carrying the fraction to the next frame so slow
     * speeds still move smoothly — the `carry` of `ChapterTextView.Coordinator.step`. The first frame
     * after a start only sets the clock.
     */
    class Stepper(private val pixelsPerPoint: Float) {
        private var lastNanos = 0L
        private var carry = 0.0

        /** Pixels to move on the frame at [frameNanos] for [pointsPerSecond]. */
        fun step(frameNanos: Long, pointsPerSecond: Double): Int {
            val last = lastNanos
            lastNanos = frameNanos
            if (last == 0L || frameNanos <= last) return 0
            // A frame gap longer than a quarter second (the app was paused) is not scrolled through.
            val seconds = ((frameNanos - last) / 1e9).coerceAtMost(0.25)
            carry += pointsPerSecond * pixelsPerPoint * seconds
            val whole = kotlin.math.floor(carry)
            carry -= whole
            return whole.toInt()
        }

        fun reset() {
            lastNanos = 0L
            carry = 0.0
        }
    }
}
