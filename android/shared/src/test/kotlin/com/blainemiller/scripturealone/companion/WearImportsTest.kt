package com.blainemiller.scripturealone.companion

import com.blainemiller.scripturealone.companion.WearImports.Decision
import com.blainemiller.scripturealone.companion.WearImports.Held
import com.blainemiller.scripturealone.companion.WearImports.Import
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearImportsTest {

    private val imports = listOf(
        Import("IMPORT-B2", allowsOfflineStorage = true),
        Import("IMPORT-A1", allowsOfflineStorage = true),
        Import("IMPORT-C3", allowsOfflineStorage = false),
        Import("../escape", allowsOfflineStorage = true),
    )

    @Test fun nothingIsOfferedBeforeTheLibraryLoads() {
        // The placeholder's empty list would wipe every import on the watch.
        assertNull(WearImports.offered(loaded = false, imports = emptyList()))
        assertNull(WearImports.offered(loaded = false, imports = imports))
        assertTrue(WearImports.sendable(loaded = false, imports = imports).isEmpty())
    }

    @Test fun anEmptyLibraryOnceLoadedIsSaidOutLoud() {
        assertEquals(emptyList<String>(), WearImports.offered(loaded = true, imports = emptyList()))
    }

    @Test fun theListCarriesEverySafeImportSorted() {
        // Whether its terms allow storing it or not: one that can't be sent simply never arrives.
        assertEquals(listOf("IMPORT-A1", "IMPORT-B2", "IMPORT-C3"), WearImports.offered(true, imports))
    }

    @Test fun onlyImportsWhoseTermsAllowOfflineStorageAreSent() {
        assertEquals(listOf("IMPORT-B2", "IMPORT-A1"), WearImports.sendable(true, imports).map { it.id })
    }

    @Test fun theVersionCarriesTheEditionFormat() {
        assertEquals("f1-abc", WearImports.version("abc", format = 1))
        assertNotEquals(WearImports.version("abc", format = 1), WearImports.version("abc", format = 2))
    }

    @Test fun anImportNeverPutIsSent() {
        assertEquals(Decision.SEND, WearImports.decide("X", "v1", held = null, put = null, putAt = 0, now = 0))
        assertEquals(Decision.SEND, WearImports.decide("X", "v1", Held(emptySet(), emptyMap()), null, 0, 0))
    }

    @Test fun theVersionTheWatchHoldsIsNotSentAgain() {
        val held = Held(setOf("X"), mapOf("X" to "v1"))
        assertEquals(Decision.SKIP, WearImports.decide("X", "v1", held, put = null, putAt = 0, now = 0))
        assertEquals(Decision.SKIP, WearImports.decide("X", "v1", held, put = "v1", putAt = 0, now = Long.MAX_VALUE))
    }

    @Test fun aNewerVersionReplacesTheWatchesOlderOne() {
        val held = Held(setOf("X"), mapOf("X" to "v1"))
        assertEquals(Decision.SEND, WearImports.decide("X", "v2", held, put = "v1", putAt = 0, now = 0))
    }

    @Test fun whatWasPutIsLeftToTheDataLayerWithoutAReport() {
        // An older watch app that never reports: the Data Layer still delivers it.
        assertEquals(Decision.SKIP, WearImports.decide("X", "v1", held = null, put = "v1", putAt = 0, now = Long.MAX_VALUE))
    }

    @Test fun aWatchThatLacksWhatWasPutGetsItAgainOnceItHadTimeToArrive() {
        val lacking = Held(emptySet(), emptyMap())
        val settle = WearImports.SETTLE_MILLIS
        assertEquals(Decision.SKIP, WearImports.decide("X", "v1", lacking, put = "v1", putAt = 1_000, now = 1_000 + settle - 1))
        assertEquals(Decision.RESEND, WearImports.decide("X", "v1", lacking, put = "v1", putAt = 1_000, now = 1_000 + settle))
        // Holding an older copy counts as lacking this one.
        val older = Held(setOf("X"), mapOf("X" to "v0"))
        assertEquals(Decision.RESEND, WearImports.decide("X", "v1", older, put = "v1", putAt = 0, now = settle))
    }

    @Test fun theWatchDropsOnlyImportsTheListNoLongerHas() {
        assertEquals(setOf("IMPORT-B2"), WearImports.toRemove(setOf("IMPORT-A1", "IMPORT-B2"), listOf("IMPORT-A1")))
        // A language Bible isn't in the received-imports set, so no list removes it.
        assertEquals(emptySet<String>(), WearImports.toRemove(emptySet(), emptyList()))
    }

    @Test fun anEditionOfAnImportRemovedSinceIsSetAside() {
        assertTrue(WearImports.accepts("X", offered = null))
        assertTrue(WearImports.accepts("X", offered = listOf("X")))
        assertFalse(WearImports.accepts("X", offered = listOf("Y")))
    }
}
