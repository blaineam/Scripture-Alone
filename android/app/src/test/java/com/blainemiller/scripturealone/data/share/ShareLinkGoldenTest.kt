package com.blainemiller.scripturealone.data.share

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A share link made on Android opens on an iPhone and on the web, and the reverse: Kotlin must write the
 * very bytes Swift writes for the same passage. The golden link (`share/golden-share-link.txt`) is the
 * one `ShareLinkGoldenTests.swift` in ScriptureAloneCore holds Swift to.
 */
class ShareLinkGoldenTest {
    private val golden: String =
        javaClass.getResourceAsStream("/share/golden-share-link.txt")!!.readBytes().toString(Charsets.UTF_8).trim()

    private val payload = ShareLinkPayload(
        "John 14:5–6", "43014005-43014006", "ASV",
        "5 Thomas saith unto him, Lord, we know not whither thou goest; 6 Jesus saith unto him, “I am the way.”",
        listOf(76 until 91), "parchment", "serif", "square",
    )

    @Test fun encodesToTheGoldenLinkSwiftWrites() {
        assertEquals(golden, payload.webUrl())
    }

    @Test fun decodesTheGoldenLinkSwiftWrites() {
        val decoded = ShareLinkPayload.fromUrl(golden)
        assertEquals(payload, decoded)
        assertEquals(listOf(VerseRange.of(VerseRef(43, 14, 5), VerseRef(43, 14, 6))), decoded.ranges)
        assertEquals(AppLink.Share(payload), AppLink.parse(golden))
    }
}
