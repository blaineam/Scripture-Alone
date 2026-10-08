package com.blainemiller.scripturealone.wear

import com.blainemiller.scripturealone.data.daily.DailyVerseCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/**
 * Verse of the Day on the tile, the complication and the home screen when the reader's translation is
 * one whose publisher keeps it off wearables. The watch never holds that edition ([WatchBible] neither
 * accepts nor opens it), so it has no text of its own to offer — and the passage falls back to the daily
 * list's own translation rather than showing nothing, or anything from the licensed text.
 */
class WearableVerseOfDayTest {
    private val catalog = DailyVerseCatalog.parse(
        File(
            File(System.getProperty("scripturealone.watchResources") ?: error("scripturealone.watchResources is not set")),
            "../../ScriptureAlone/Shared/DailyVerses.json",
        ).readText(),
    )
    private val at = Instant.parse("2026-10-08T12:00:00Z")

    @Test fun aProhibitedTranslationFallsBackToTheListsOwn() {
        for (licensed in listOf("NASB1995", "NASB2020")) {
            // The daily list never carries a licensed text, and the watch holds no edition of it.
            assertTrue(licensed !in catalog.translations)
            val shown = WatchVerseOfDay.translation(licensed, catalog.translations, listOf("en-US"), hasOwnText = false)
            val today = checkNotNull(WatchVerseOfDay.at(catalog, at, shown, ZoneOffset.UTC, ownText = { null }))
            assertEquals(DailyVerseCatalog.FALLBACK_TRANSLATION, today.translation)
            assertEquals(today.verse.text(DailyVerseCatalog.FALLBACK_TRANSLATION), today.text)
            assertTrue(today.text.isNotEmpty())

            // The complication's week, the same.
            val week = WatchVerseOfDay.week(catalog, at, shown, zone = ZoneOffset.UTC, ownText = { null })
            assertEquals(7, week.size)
            assertTrue(week.all { it.third.translation == DailyVerseCatalog.FALLBACK_TRANSLATION && it.third.ownText == null })
        }
    }
}

