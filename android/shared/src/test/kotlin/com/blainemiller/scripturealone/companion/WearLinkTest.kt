package com.blainemiller.scripturealone.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Data Layer paths the phone writes and the watch listens on — `WatchLinkKeysTests.swift` on iOS.
 * An edition path's last segment becomes a file name on the watch.
 */
class WearLinkTest {
    @Test fun editionPathsRoundTrip() {
        for (id in listOf("ASV", "LSG", "Imported-1A2B", "eng_web")) {
            assertEquals(id, WearLink.editionId(WearLink.editionPath(id)))
        }
    }

    @Test fun anEditionPathNeverYieldsAnUnsafeName() {
        assertNull(WearLink.editionId(WearLink.PATH_EDITION_PREFIX + "../ASV"))
        assertNull(WearLink.editionId(WearLink.PATH_EDITION_PREFIX + "a/b"))
        assertNull(WearLink.editionId(WearLink.PATH_EDITION_PREFIX + "ASV.sqlite"))
        assertNull(WearLink.editionId(WearLink.PATH_EDITION_PREFIX))
        assertNull(WearLink.editionId(WearLink.PATH_TRANSLATION))
        assertNull(WearLink.editionId("/other/edition/ASV"))
    }

    /** The watch's listener filters on the prefix: every path the phone writes must carry it. */
    @Test fun everyPathSharesThePrefixAndIsDistinct() {
        val paths = listOf(WearLink.PATH_TRANSLATION, WearLink.PATH_ACCENT, WearLink.PATH_SNAPSHOT,
            WearLink.PATH_IMPORTS, WearLink.PATH_HELD, WearLink.editionPath("ASV"))
        assertTrue(paths.all { it.startsWith(WearLink.PATH_PREFIX + "/") })
        assertEquals(paths.size, paths.toSet().size)
        // An edition path is never mistaken for one of the fixed ones.
        assertTrue(paths.dropLast(1).none { it.startsWith(WearLink.PATH_EDITION_PREFIX) })
    }

    /** Matches iOS: ASCII letters, digits, '_' and '-' only, up to 64. */
    @Test fun safeIdsMatchTheIphonesRule() {
        assertTrue(WearLink.isSafeId("A".repeat(64)))
        for (bad in listOf("", "a b", "名前", "Ü", "a\nb", "A".repeat(65), "a.b")) {
            assertTrue("\"$bad\" should be refused", !WearLink.isSafeId(bad))
        }
    }
}
