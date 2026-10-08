package com.blainemiller.scripturealone.ui.widget

import com.blainemiller.scripturealone.data.sabible.SabibleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * What the phone tells the Wear OS watch about the reader's translation (1.1.4): a translation whose
 * signed terms keep it off wearables is flagged so the watch can say why it isn't there, and the
 * watch's footer names the translation as the phone shows it — the package's or the import's own
 * abbreviation, not its id.
 */
class WearPublisherTest {
    @get:Rule val folder = TemporaryFolder()

    /** A sealed package's preamble and header — all `unverifiedHeader` reads; the body is never opened. */
    private fun sealed(policy: String, abbreviation: String = "NASB", name: String = "P.sabible"): File {
        val header = (
            """{"chapters":[{"book":43,"chapter":3,"length":64,"offset":0,"verses":1}],"createdAt":"2026-10-08T00:00:00Z",""" +
                """"crypto":{"aad":"${SabibleFormat.ASSOCIATED_DATA_VERSION}","cipher":"${SabibleFormat.CIPHER}","keyID":"k",""" +
                """"publisherKeyID":"p","signature":"${SabibleFormat.SIGNATURE_ALGORITHM}"},"format":${SabibleFormat.VERSION},""" +
                """"packageID":"P","policy":$policy,"translation":{"abbreviation":"$abbreviation","copyright":"c",""" +
                """"id":"NASB2020","license":"l","name":"Test","publisher":"p"}}"""
            ).toByteArray()
        val bytes = ByteArrayOutputStream().apply {
            write(SabibleFormat.MAGIC)
            write(ByteBuffer.allocate(2).putShort(SabibleFormat.VERSION.toShort()).array())
            write(ByteBuffer.allocate(4).putInt(header.size).array())
            write(header)
            write(ByteBuffer.allocate(2).putShort(64).array())
            write(ByteArray(64 + 64))
        }.toByteArray()
        return File(folder.root, name).apply { writeBytes(bytes) }
    }

    @Test fun aBundledTranslationGoesAsItsIdAlone() {
        val item = WearPublisher.translationItem("BSB", 1.5, sealed = null, importedAbbreviation = null)
        assertFalse(item.notForWatch)
        assertNull("the watch names the BSB itself", item.label)
        assertEquals("t:BSB@1.5", item.signature)
    }

    @Test fun aPackageThatAllowsWearablesGoesWithItsOwnAbbreviation() {
        val item = WearPublisher.translationItem("NASB2020", 2.0, sealed("""{"allowCopy":true}""", abbreviation = "NASB"), null)
        assertFalse(item.notForWatch)
        assertEquals("NASB", item.label)
        assertEquals("t:NASB2020@2.0:NASB", item.signature)
    }

    @Test fun aPackageLicensedOffWearablesIsFlaggedForTheWatch() {
        val item = WearPublisher.translationItem(
            "NASB1995", 3.0, sealed("""{"allowCopy":true,"wearables":"prohibited"}""", abbreviation = "NASB 1995"), null,
        )
        assertTrue("the watch is told why it doesn't have it", item.notForWatch)
        assertEquals("NASB 1995", item.label)
        assertEquals("t:NASB1995@3.0:off:NASB 1995", item.signature)
    }

    @Test fun anUnreadablePackageStaysOffTheWatchToo() {
        val junk = File(folder.root, "junk.sabible").apply { writeBytes(ByteArray(40) { 1 }) }
        val item = WearPublisher.translationItem("X", 1.0, junk, importedAbbreviation = null)
        assertTrue(item.notForWatch)
        assertNull(item.label)
    }

    @Test fun anImportIsNamedByItsAbbreviation() {
        val item = WearPublisher.translationItem("Imported-1A2B", 1.0, sealed = null, importedAbbreviation = "WEB")
        assertFalse(item.notForWatch)
        assertEquals("WEB", item.label)
        // A blank abbreviation is no name: the watch falls back to the id.
        assertNull(WearPublisher.translationItem("Imported-1A2B", 1.0, null, importedAbbreviation = " ").label)
    }

    @Test fun anyChangeChangesTheSignatureSoItIsSentAgain() {
        val base = WearPublisher.translationItem("BSB", 1.0, null, null)
        assertNotEquals(base.signature, WearPublisher.translationItem("BSB", 2.0, null, null).signature)
        assertNotEquals(base.signature, WearPublisher.translationItem("KJV", 1.0, null, null).signature)
        assertNotEquals(base.signature, WearPublisher.translationItem("BSB", 1.0, null, "Berean").signature)
    }
}
