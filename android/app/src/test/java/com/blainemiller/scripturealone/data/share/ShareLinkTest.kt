package com.blainemiller.scripturealone.data.share

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A port of `ShareLinkPayloadTests.swift`, plus byte equality with the Swift encoder. */
class ShareLinkTest {

    private fun json(payload: ShareLinkPayload): String =
        String(ShareLinkPayload.base64UrlDecode(payload.encoded())!!, Charsets.UTF_8)

    private fun String.slice16(range: IntRange) = substring(range.first, range.last + 1)

    private val unicodeText = "א ALEPH. “Blessed are they”—perfect in the way… 𝔊 “I am the way”"
    private fun quoteOf(text: String, quote: String) = text.indexOf(quote).let { it until it + quote.length }

    /**
     * Captured from the Swift `ShareLinkPayload.encoded()` (JSONEncoder, sorted keys, unescaped
     * slashes). A link made on Android must be the very bytes iOS makes, or the two apps' links drift.
     */
    @Test fun encodesByteForByteAsSwiftDoes() {
        val plain = ShareLinkPayload("John 3:16", "43003016-43003016", "ASV", "For God so loved the world", listOf(0 until 7))
        val unicode = ShareLinkPayload(
            "Psalms 119:1", "19119001-19119001", "ASV", unicodeText,
            listOf(quoteOf(unicodeText, "“I am the way”")), "night", "iowan", "story",
        )
        val full = ShareLinkPayload(
            "John 3:16–17", "43003016-43003017,43003020-43003020", "BSB",
            "16 For God / so \"loved\" \\ the world\ttab", listOf(3 until 10), "parchment", "serif", "square",
        )
        assertEquals(
            "eyJrIjoiNDMwMDMwMTYtNDMwMDMwMTYiLCJyZWQiOltbMCw3XV0sInJlZiI6IkpvaG4gMzoxNiIsInQiOiJGb3IgR29kIHNvIGxvdmVkIHRoZSB3b3JsZCIsInRyIjoiQVNWIiwidiI6MX0",
            plain.encoded(),
        )
        assertEquals(
            "eyJhIjoic3RvcnkiLCJmIjoiaW93YW4iLCJrIjoiMTkxMTkwMDEtMTkxMTkwMDEiLCJyZWQiOltbNTEsMTRdXSwicmVmIjoiUHNhbG1zIDExOToxIiwidCI6IteQIEFMRVBILiDigJxCbGVzc2VkIGFyZSB0aGV54oCd4oCUcGVyZmVjdCBpbiB0aGUgd2F54oCmIPCdlIog4oCcSSBhbSB0aGUgd2F54oCdIiwidHAiOiJuaWdodCIsInRyIjoiQVNWIiwidiI6MX0",
            unicode.encoded(),
        )
        assertEquals(
            "eyJhIjoic3F1YXJlIiwiZiI6InNlcmlmIiwiayI6IjQzMDAzMDE2LTQzMDAzMDE3LDQzMDAzMDIwLTQzMDAzMDIwIiwicmVkIjpbWzMsN11dLCJyZWYiOiJKb2huIDM6MTbigJMxNyIsInQiOiIxNiBGb3IgR29kIC8gc28gXCJsb3ZlZFwiIFxcIHRoZSB3b3JsZFx0dGFiIiwidHAiOiJwYXJjaG1lbnQiLCJ0ciI6IkJTQiIsInYiOjF9",
            full.encoded(),
        )
    }

    @Test fun roundTripsThroughTheWebUrl() {
        val payload = ShareLinkPayload(
            "John 3:16–17", "43003016-43003017", "ASV",
            "16 For God so loved the world… 17 For God sent not the Son…", listOf(3 until 30),
            "parchment", "serif", "square",
        )
        val url = payload.webUrl()
        assertTrue(url.startsWith("https://wemiller.com/apps/scripture-alone/#s="))
        val encoded = payload.encoded()
        assertFalse(encoded.contains('=') || encoded.contains('+') || encoded.contains('/'))
        assertEquals(payload, ShareLinkPayload.fromUrl(url))
        assertEquals(payload, ShareLinkPayload.decode(encoded))
        assertEquals(listOf(VerseRange.of(VerseRef(43, 3, 16), VerseRef(43, 3, 17))), payload.ranges)
    }

    @Test fun jsonMatchesTheContract() {
        val payload = ShareLinkPayload("John 3:16", "43003016-43003016", "ASV", "For God so loved the world", listOf(0 until 7))
        val fields = Json.parseToJsonElement(json(payload)).jsonObject
        assertEquals("1", fields["v"]!!.jsonPrimitive.content)
        assertEquals("John 3:16", fields["ref"]!!.jsonPrimitive.content)
        assertEquals("43003016-43003016", fields["k"]!!.jsonPrimitive.content)
        assertEquals("ASV", fields["tr"]!!.jsonPrimitive.content)
        assertEquals("For God so loved the world", fields["t"]!!.jsonPrimitive.content)
        assertEquals(listOf(listOf("0", "7")), fields["red"]!!.jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.content } })
        // Optional keys are omitted rather than sent empty.
        assertTrue(fields.keys.none { it in setOf("tp", "f", "a") })

        val plain = ShareLinkPayload("Genesis 1:1", "1001001-1001001", "KJV", "In the beginning")
        assertFalse(json(plain).contains("\"red\""))
    }

    @Test fun survivesUnicode() {
        val payload = ShareLinkPayload(
            "Psalms 119:1", "19119001-19119001", "ASV", unicodeText,
            listOf(quoteOf(unicodeText, "“I am the way”")), "night", "iowan", "story",
        )
        val decoded = ShareLinkPayload.fromUrl(payload.webUrl())
        assertEquals(payload, decoded)
        assertEquals("“I am the way”", decoded.text.slice16(decoded.red[0]))
        // UTF-8 JSON, not \u escapes: Hebrew stays compact.
        val raw = json(payload)
        assertTrue(raw.contains("א ALEPH"))
        assertTrue(raw.contains("—"))
    }

    @Test fun dropsBadRedRangesInsteadOfFailing() {
        val raw = """{"v":1,"ref":"John 3:16","k":"43003016","tr":"ASV","t":"For God","red":[[0,3],[-1,2],[5,99],[40,2],[1],[2,0]]}"""
        val payload = ShareLinkPayload.decode(ShareLinkPayload.base64UrlEncode(raw.toByteArray()))
        assertEquals(listOf(0 until 3, 5 until 7), payload.red)
        assertEquals(listOf(VerseRange.of(VerseRef(43, 3, 16))), payload.ranges)
    }

    @Test fun rejectsMalformedPayloads() {
        fun failure(block: () -> Unit) = runCatching(block).exceptionOrNull()
        assertTrue(failure { ShareLinkPayload.fromUrl("https://wemiller.com/apps/scripture-alone/") } is ShareLinkException.MissingPayload)
        assertTrue(failure { ShareLinkPayload.decode("!!!") } is ShareLinkException.NotBase64)
        assertTrue(failure { ShareLinkPayload.decode(ShareLinkPayload.base64UrlEncode("{\"v\":1}".toByteArray())) } is ShareLinkException.Malformed)
        val v2 = failure { ShareLinkPayload.decode(ShareLinkPayload.base64UrlEncode("""{"v":2,"ref":"x","t":"y"}""".toByteArray())) }
        assertEquals(2, (v2 as ShareLinkException.UnsupportedVersion).version)
        val huge = "{\"v\":1,\"ref\":\"x\",\"t\":\"${"a".repeat(6_001)}\"}"
        assertTrue(failure { ShareLinkPayload.decode(ShareLinkPayload.base64UrlEncode(huge.toByteArray())) } is ShareLinkException.TooLong)
    }

    @Test fun composesNumberedVersesWithShiftedRedRanges() {
        val verses = listOf(
            ShareVerse(VerseRef(43, 3, 16), "For God so loved the world.", listOf(0 until 27)),
            ShareVerse(VerseRef(43, 3, 17), "“For God sent not the Son.”", listOf(1 until 26)),
        )
        val passage = SharePassageText.of(verses)
        assertEquals("16 For God so loved the world. 17 “For God sent not the Son.”", passage.text)
        assertEquals(listOf("16", "17"), passage.numbers.map { passage.text.slice16(it) })
        assertEquals(listOf("For God so loved the world.", "For God sent not the Son."), passage.red.map { passage.text.slice16(it) })

        val single = SharePassageText.of(listOf(verses[0]))
        assertEquals("For God so loved the world.", single.text)
        assertTrue(single.numbers.isEmpty())

        val unnumbered = SharePassageText.of(verses, numbered = false)
        assertEquals("For God so loved the world. “For God sent not the Son.”", unnumbered.text)
        assertEquals(listOf("For God so loved the world.", "For God sent not the Son."), unnumbered.red.map { unnumbered.text.slice16(it) })
    }

    @Test fun mergesRedRangesThatTouchAcrossVerses() {
        val passage = SharePassageText.of(
            listOf(ShareVerse(VerseRef(43, 3, 16), "ab", listOf(0 until 2)), ShareVerse(VerseRef(43, 3, 17), "cd", listOf(0 until 2))),
            numbered = false,
        )
        // "ab cd": the space between them is not red, so the ranges stay apart.
        assertEquals(listOf(0 until 2, 3 until 5), passage.red)
    }

    @Test fun convertsScalarRangesToUtf16() {
        // 𝔊 is one scalar but two UTF-16 units: a scalar range after it must shift by one.
        val verse = ShareVerse.fromScalars(VerseRef(19, 119, 1), "𝔊 “I am”", listOf(2 to 6))
        assertEquals("“I am”", verse.text.slice16(verse.red.single()))
    }

    @Test fun numbersAChapterBreakWithItsChapter() {
        val verses = listOf(
            ShareVerse(VerseRef(1, 1, 31), "It was very good."),
            ShareVerse(VerseRef(1, 2, 1), "Thus the heavens were finished."),
        )
        assertEquals("31 It was very good. 2:1 Thus the heavens were finished.", SharePassageText.of(verses).text)
    }

    @Test fun composesAPayloadFromRanges() {
        val range = VerseRange.of(VerseRef(43, 14, 5), VerseRef(43, 14, 6))
        assertEquals("John 14:5–6", range.display)
        val passage = SharePassageText.of(listOf(
            ShareVerse(VerseRef(43, 14, 5), "Thomas saith unto him, Lord, we know not whither thou goest;"),
            ShareVerse(VerseRef(43, 14, 6), "Jesus saith unto him, I am the way, and the truth, and the life:", listOf(22 until 64)),
        ))
        val decoded = ShareLinkPayload.fromUrl(ShareLinkPayload.of(listOf(range), range.display, "ASV", passage).webUrl())
        assertEquals("43014005-43014006", decoded.keys)
        assertTrue(decoded.text.startsWith("5 Thomas saith"))
        assertTrue(decoded.red.any { decoded.text.slice16(it).startsWith("I am the way") })
        assertTrue(decoded.fitsInLink)
    }

    @Test fun displaysRangesAsSwiftDoes() {
        assertEquals("John 3:16", VerseRange.of(VerseRef(43, 3, 16)).display)
        assertEquals("Genesis 1:1–2:3", VerseRange.of(VerseRef(1, 2, 3), VerseRef(1, 1, 1)).display)
        assertEquals("Romans 8:1–1 Corinthians 1:2", VerseRange.of(VerseRef(45, 8, 1), VerseRef(46, 1, 2)).display)
        assertNull(VerseRange.parse("99001001-99001002"))
    }

    @Test fun parsesAppLinks() {
        assertEquals(
            AppLink.Open(listOf(VerseRange.of(VerseRef(43, 3, 16), VerseRef(43, 3, 17)))),
            AppLink.parse("scripturealone://open?ref=43003016-43003017"),
        )
        assertEquals(
            AppLink.Open(listOf(VerseRange.of(VerseRef(45, 8, 1), VerseRef(45, 8, 4)), VerseRange.of(VerseRef(45, 8, 28)))),
            AppLink.parse("scripturealone://open?ref=45008001-45008004,45008028"),
        )
        assertEquals(
            "scripturealone://open?ref=43003016-43003017",
            AppLink.openUrl(listOf(VerseRange.of(VerseRef(43, 3, 16), VerseRef(43, 3, 17)))),
        )

        val payload = ShareLinkPayload("John 3:16–17", "43003016-43003017", "ASV", "16 For God")
        assertEquals(AppLink.Share(payload), AppLink.parse(payload.webUrl()))
        assertEquals(AppLink.Share(payload), AppLink.parse("https://www.WEMILLER.com/apps/scripture-alone/#s=${payload.encoded()}"))
        assertEquals(AppLink.Share(payload), AppLink.parse("scripturealone://open#s=${payload.encoded()}"))

        assertNull(AppLink.parse("https://example.com/apps/scripture-alone/#s=${payload.encoded()}"))
        assertNull(AppLink.parse("https://wemiller.com/apps/scripture-alone/"))
        assertNull(AppLink.parse("scripturealone://open?ref=nonsense"))
    }

    @Test fun capsLinkTextLengthInCharacters() {
        assertFalse(ShareLinkPayload("Psalms 119", "19119001-19119176", "ASV", "a".repeat(ShareLinkPayload.MAX_TEXT_LENGTH + 1)).fitsInLink)
        // 1,500 astral characters are 3,000 UTF-16 units but still 1,500 characters, as Swift counts them.
        assertTrue(ShareLinkPayload("x", "43003016", "ASV", "𝔊".repeat(ShareLinkPayload.MAX_TEXT_LENGTH)).fitsInLink)
    }
}
