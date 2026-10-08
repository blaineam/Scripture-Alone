package com.blainemiller.scripturealone.companion

import com.blainemiller.scripturealone.companion.WearFavorites.Action
import com.blainemiller.scripturealone.companion.WearFavorites.Request
import com.blainemiller.scripturealone.data.VerseRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The watch's heart: the request path, what the phone does with a request, and what the watch shows meanwhile. */
class WearFavoritesTest {
    private val john316 = VerseRange.parse("43003016-43003016")!!
    private val rom838 = VerseRange.parse("45008038-45008039")!!

    @Test fun pathsRoundTripAndShareThePrefix() {
        for (range in listOf(john316, rom838)) {
            val path = WearFavorites.path(range)
            assertTrue(path.startsWith(WearLink.PATH_PREFIX + "/"))
            assertEquals(range, WearFavorites.range(path))
        }
        // Never mistaken for an edition or another fixed path.
        assertNull(WearLink.editionId(WearFavorites.path(john316)))
    }

    @Test fun malformedPathsAreRefused() {
        for (bad in listOf(
            WearLink.PATH_FAVORITE_PREFIX, WearLink.PATH_FAVORITE_PREFIX + "../x", WearLink.PATH_FAVORITE_PREFIX + "abc",
            WearLink.PATH_FAVORITE_PREFIX + "43003016-43003016/x", WearLink.PATH_SNAPSHOT, "/other/favorite/43003016-43003016",
            WearLink.PATH_FAVORITE_PREFIX + "1".repeat(40),
        )) assertNull(bad, WearFavorites.range(bad))
    }

    @Test fun addingWhatIsMissingAndNothingTwice() {
        assertEquals(Action.ADD, WearFavorites.decide(Request(john316, true, 1_000), existingAt = null))
        assertEquals(Action.NONE, WearFavorites.decide(Request(john316, true, 1_000), existingAt = 500))
    }

    @Test fun removingOnlyWhatIsThereAndNotANewerPhoneChoice() {
        assertEquals(Action.REMOVE, WearFavorites.decide(Request(john316, false, 1_000), existingAt = 500))
        assertEquals(Action.NONE, WearFavorites.decide(Request(john316, false, 1_000), existingAt = null))
        // Favorited again on the phone after the watch's tap: the phone's newer choice stands.
        assertEquals(Action.NONE, WearFavorites.decide(Request(john316, false, 1_000), existingAt = 2_000))
    }

    @Test fun thePendingRequestShowsUntilTheSnapshotAgrees() {
        val snapshot = setOf(rom838.storageString)
        assertFalse(WearFavorites.shown(john316, snapshot, emptyMap()))
        assertTrue(WearFavorites.shown(rom838, snapshot, emptyMap()))
        val pending = mapOf(john316.storageString to true, rom838.storageString to false)
        assertTrue(WearFavorites.shown(john316, snapshot, pending))
        assertFalse(WearFavorites.shown(rom838, snapshot, pending))
        // The phone took the John 3:16 add; the Romans removal hasn't arrived yet.
        val next = setOf(rom838.storageString, john316.storageString)
        assertEquals(mapOf(rom838.storageString to false), WearFavorites.settle(pending, next))
        assertEquals(emptyMap<String, Boolean>(), WearFavorites.settle(pending, setOf(john316.storageString)))
    }
}
