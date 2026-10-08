package com.blainemiller.scripturealone.wear

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blainemiller.scripturealone.companion.VerseSnapshot
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.sabible.SabibleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant

/**
 * The Wear OS watch's library on Robolectric (1.1.4 parity): the heart on a verse shown at once and
 * settled by the phone's next snapshot, a translation the phone sent removed and kept removed until it
 * changes or is chosen again, a package licensed off wearables refused, and the phone's choice — its
 * name and its licence — carried to the picker.
 */
@RunWith(AndroidJUnit4::class)
class WatchBibleTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val watchResources = File(System.getProperty("scripturealone.watchResources")!!)

    /** Every test starts with a new watch: the singleton is the process's, the sandbox is the test's. */
    @Before fun freshWatch() {
        WatchBible::class.java.getDeclaredField("shared").apply { isAccessible = true }.set(null, null)
    }

    private fun bible() = WatchBible.get(app)

    private val john316 = VerseRange.of(VerseRef.fromKey(43_003_016), VerseRef.fromKey(43_003_016))

    private fun snapshot(vararg favorites: VerseRange) = VerseSnapshot(
        generatedAt = Instant.parse("2026-10-08T00:00:00Z"), translation = "ASV",
        items = favorites.map { range ->
            VerseSnapshot.Item(
                kind = VerseSnapshot.Kind.FAVORITE, range = range.storageString, startKey = range.start.key,
                endKey = range.end.key, reference = range.display, text = "For God so loved the world", date = Instant.EPOCH,
            )
        },
    ).encoded()

    @Test fun theWatchsOwnBibleIsTheFallback() {
        val state = bible().state.value
        assertEquals(WatchBible.BUNDLED.first(), state.translation)
        assertFalse("nobody chose it", state.chosen)
        assertTrue(state.editions.single { it.id == state.translation }.bundled)
        assertNotNull("today's verse reads from it", bible().verseOfDay())
    }

    @Test fun aHeartShowsAtOnceAndThePhonesSnapshotSettlesIt() {
        val bible = bible()
        val request = bible.toggleFavorite(john316, now = 1_000)
        assertTrue("the request asks the phone to add it", request.favorite)
        assertTrue("shown at once", bible.state.value.isFavorite(john316))
        assertEquals(listOf(john316.storageString), bible.state.value.favorites.map { it.range })

        bible.receiveSnapshot(snapshot(john316))
        assertTrue("the phone took it: nothing pending", bible.state.value.pendingFavorites.isEmpty())
        assertTrue(bible.state.value.isFavorite(john316))
        assertEquals("one entry, not two", 1, bible.state.value.favorites.size)

        assertFalse("tapped again it asks for removal", bible.toggleFavorite(john316).favorite)
        assertFalse(bible.state.value.isFavorite(john316))
        assertTrue("hidden from the list until the phone agrees", bible.state.value.favorites.isEmpty())
        bible.receiveSnapshot("{not json")
        assertFalse("a snapshot that doesn't decode changes nothing", bible.state.value.isFavorite(john316))
    }

    @Test fun aTranslationFromThePhoneIsRemovedAndStaysRemoved() {
        val bible = bible()
        val bsb = File(watchResources, "BSB-Watch.sqlite").readBytes()
        assertTrue(bible.receiveEdition("BSB", ByteArrayInputStream(bsb), digest = "d1"))
        assertTrue(bible.holdsEdition("BSB", "d1"))
        assertFalse(bible.state.value.editions.single { it.id == "BSB" }.bundled)

        assertTrue(bible.removeReceived("BSB"))
        assertNull("gone from the picker", bible.state.value.editions.firstOrNull { it.id == "BSB" })
        assertFalse(bible.holdsEdition("BSB", "d1"))
        assertTrue("the phone's standing offer doesn't bring it back", bible.declined("BSB", "d1"))
        assertFalse("a changed edition does", bible.declined("BSB", "d2"))
        assertFalse("nothing left to remove", bible.removeReceived("BSB"))
        assertFalse("the watch's own Bible stays", bible.removeReceived(WatchBible.BUNDLED.first()))

        // Choosing the BSB again on the phone asks for it back.
        bible.phoneChose("BSB", at = 100.0)
        assertFalse(bible.declined("BSB", "d1"))
    }

    @Test fun aBrokenEditionIsDroppedAndTheWatchKeepsWhatItHad() {
        val bible = bible()
        assertFalse(bible.receiveEdition("KJV", ByteArrayInputStream(ByteArray(4096) { 7 }), digest = "x"))
        assertNull(bible.state.value.editions.firstOrNull { it.id == "KJV" })
        assertFalse("an unsafe id is refused", bible.receiveEdition("../KJV", ByteArrayInputStream(ByteArray(1)), "x"))
    }

    @Test fun aPackageLicensedOffWearablesIsNeverKept() {
        val bible = bible()
        assertFalse(bible.receiveEdition("NASB1995", ByteArrayInputStream(prohibitedPackage()), digest = "n"))
        assertNull(bible.state.value.editions.firstOrNull { it.id == "NASB1995" })
        assertFalse(File(WatchBible.receivedDirectory(app), "NASB1995.sabible").exists())
    }

    @Test fun thePhonesChoiceReachesThePickerByItsNameAndLicence() {
        val bible = bible()
        bible.phoneChose("NASB1995", at = 10.0, notForWatch = true, label = "NASB 1995")
        val state = bible.state.value
        assertEquals("NASB1995", state.phoneTranslation)
        assertTrue(state.phoneTranslationNotForWatch)
        assertEquals("NASB 1995", state.phoneTranslationLabel)
        assertEquals("the watch reads what it has", WatchBible.BUNDLED.first(), state.translation)

        bible.phoneAccent(0x3366CC)
        assertEquals(0x3366CC, bible.state.value.accent)
        bible.phoneAccent(-1)
        assertEquals("a nonsense colour is ignored", 0x3366CC, bible.state.value.accent)
    }

    @Test fun choosingOnTheWatchWins() {
        val bible = bible()
        val bsb = File(watchResources, "BSB-Watch.sqlite").readBytes()
        bible.receiveEdition("BSB", ByteArrayInputStream(bsb), digest = "d1")
        bible.choose("BSB")
        assertEquals("BSB", bible.state.value.translation)
        assertTrue(bible.state.value.chosen)
        assertTrue("it reads John 3:16", bible.edition().verses(john316).single().text.contains("loved the world"))
    }

    /** A sealed package's header whose signed terms keep it off wearables (the body is never opened). */
    private fun prohibitedPackage(): ByteArray {
        val header = (
            """{"chapters":[{"book":43,"chapter":3,"length":64,"offset":0,"verses":1}],"createdAt":"2026-10-08T00:00:00Z",""" +
                """"crypto":{"aad":"${SabibleFormat.ASSOCIATED_DATA_VERSION}","cipher":"${SabibleFormat.CIPHER}","keyID":"k",""" +
                """"publisherKeyID":"p","signature":"${SabibleFormat.SIGNATURE_ALGORITHM}"},"format":${SabibleFormat.VERSION},""" +
                """"packageID":"P","policy":{"allowCopy":true,"wearables":"prohibited"},"translation":{"abbreviation":"NASB 1995",""" +
                """"copyright":"c","id":"NASB1995","license":"l","name":"Test","publisher":"p"}}"""
            ).toByteArray()
        return ByteArrayOutputStream().apply {
            write(SabibleFormat.MAGIC)
            write(ByteBuffer.allocate(2).putShort(SabibleFormat.VERSION.toShort()).array())
            write(ByteBuffer.allocate(4).putInt(header.size).array())
            write(header)
            write(ByteBuffer.allocate(2).putShort(64).array())
            write(ByteArray(128))
        }.toByteArray()
    }
}
