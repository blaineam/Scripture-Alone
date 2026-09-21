package com.blainemiller.scripturealone.data.online

import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.layout.ChapterLayout
import com.blainemiller.scripturealone.data.rights.TranslationRights
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The online path end to end — request, parse, cache, read back as a chapter the reader renders —
 * through a fake transport. No live service is reached and no real key exists: the transport answers
 * with Crossway's and API.Bible's response envelopes around captured passage HTML (kept outside the
 * repository, as licensed text; skipped where absent) or invented text in the same markup.
 */
class OnlineChapterLoaderTest {

    @get:Rule val temporary = TemporaryFolder()

    private val key = "test-key-0123456789abcdef"

    /** Serves canned responses by URL substring and records every request. */
    private class FakeTransport(private val routes: Map<String, HttpResponse>) : HttpTransport {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            requests += url to headers
            return routes.entries.firstOrNull { url.contains(it.key) }?.value ?: HttpResponse(404, ByteArray(0))
        }
    }

    private fun esvEnvelope(html: String) =
        HttpResponse(200, JsonObject(mapOf("passages" to JsonArray(listOf(JsonPrimitive(html))))).toString().toByteArray())

    private fun apiBibleEnvelope(html: String) =
        HttpResponse(200, JsonObject(mapOf("data" to JsonObject(mapOf("content" to JsonPrimitive(html))))).toString().toByteArray())

    private fun chapter(book: BookID, chapter: Int) = ChapterRef(book.number, chapter)

    private fun loader(transport: HttpTransport, limit: Int = EsvTerms.CACHE_VERSE_LIMIT, keyed: Boolean = true) =
        OnlineChapterLoader(temporary.newFolder(), JdbcCacheDriver(), transport, { if (keyed) key else null }, limit)

    private val captures = File(System.getProperty("user.home"), ".scripture-alone-import/online")
    private fun capture(name: String): String? = File(captures, name).takeIf { it.isFile }?.readText()

    /** Invented verses in the ESV HTML endpoint's markup. */
    private fun inventedESV(chapter: ChapterRef, verses: Int): String = buildString {
        append("<p class=\"starts-chapter\">")
        for (v in 1..verses) {
            val id = VerseRef(chapter.book, chapter.chapter, v).key
            if (v == 1) {
                append("<b class=\"chapter-num\" id=\"v$id-1\">${chapter.chapter}:1&nbsp;</b>")
            } else {
                append("<b class=\"verse-num\" id=\"v$id-1\">$v&nbsp;</b>")
            }
            append(if (v == 2) "<span class=\"woc\">“Invented words, spoken.”</span> " else "Invented verse $v. ")
        }
        append("</p>")
    }

    // ---- Requests ----

    @Test fun esvRequestsTheHtmlEndpointWithTheReadersKeyAsAHeader() {
        val transport = FakeTransport(mapOf("api.esv.org" to esvEnvelope(inventedESV(chapter(BookID.JOHN, 3), 4))))
        ESVClient(key, transport).chapter(chapter(BookID.JOHN, 3))
        val (url, headers) = transport.requests.single()
        assertTrue(url, url.startsWith("https://api.esv.org/v3/passage/html/?q=John%203&"))
        for (param in listOf("include-verse-numbers=true", "include-headings=false", "include-footnotes=false",
                             "include-audio-link=false", "include-chapter-numbers=true", "wrapping-div=false")) {
            assertTrue("$param missing from $url", param in url)
        }
        assertEquals("Token $key", headers["Authorization"])
        // The key travels only as a header, never in the URL.
        assertFalse(url.contains(key))
    }

    @Test fun apiBibleRequestsTheChapterByUsfmCodeWithTheKeyHeader() {
        val transport = FakeTransport(mapOf("/chapters/" to apiBibleEnvelope("<p class=\"p\"><span data-number=\"1\" class=\"v\">1</span>Invented.</p>")))
        APIBibleClient(key, "de4e12af7f28f599-02", transport).chapter(chapter(BookID.FIRST_JOHN, 4))
        val (url, headers) = transport.requests.single()
        assertEquals(
            "https://api.scripture.api.bible/v1/bibles/de4e12af7f28f599-02/chapters/1JN.4?content-type=html&" +
                "include-verse-numbers=true&include-chapter-numbers=false&include-notes=false&include-titles=true",
            url,
        )
        assertEquals(key, headers["api-key"])
    }

    @Test fun singleChapterBooksAreAskedForByName() {
        assertTrue(ESVClient.chapterUrl(chapter(BookID.JUDE, 1)).contains("?q=Jude&"))
    }

    // ---- Failures ----

    @Test fun failuresSayWhatHappenedAndNeverCarryTheKey() {
        val cases = mapOf(401 to OnlineFailure.Unauthorized::class, 403 to OnlineFailure.Unauthorized::class,
                          429 to OnlineFailure.RateLimited::class, 500 to OnlineFailure.Http::class)
        for ((status, expected) in cases) {
            val transport = FakeTransport(mapOf("api.esv.org" to HttpResponse(status, "{\"detail\":\"no\"}".toByteArray())))
            try {
                ESVClient(key, transport).chapter(chapter(BookID.JOHN, 3))
                fail("HTTP $status should fail")
            } catch (e: OnlineFailure) {
                assertEquals(expected, e::class)
                assertFalse(e.message!!.contains(key))
            }
        }
        val forbidden = FakeTransport(mapOf("api.scripture" to HttpResponse(403, ByteArray(0))))
        try {
            APIBibleClient(key, "x", forbidden).chapter(chapter(BookID.JOHN, 3))
            fail("403 should fail")
        } catch (e: OnlineFailure.NotAvailable) {
            assertEquals("John 3 isn't one of the translations your key can read. Choose it on api.bible first.", e.message)
        }
        val garbage = FakeTransport(mapOf("api.esv.org" to HttpResponse(200, "<html>".toByteArray())))
        try {
            ESVClient(key, garbage).chapter(chapter(BookID.JOHN, 3))
            fail("a reply that isn't JSON should fail")
        } catch (_: OnlineFailure.Malformed) {
        }
    }

    @Test fun withoutAKeyNothingIsRequested() {
        val transport = FakeTransport(emptyMap())
        try {
            loader(transport, keyed = false).chapter(OnlineEntry.ESV, chapter(BookID.JOHN, 3))
            fail("expected NeedsKey")
        } catch (e: OnlineFailure.NeedsKey) {
            assertEquals("Add your free Crossway key in Manage Translations to read the ESV.", e.message)
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun theBibleListDecodes() {
        val body = """{"data":[{"id":"abc-01","name":"Christian Standard Bible","abbreviation":"CSB","abbreviationLocal":"CSB",
            "language":{"name":"English"},"copyright":"© Holman"},{"id":"bare"}]}"""
        val list = APIBibleClient(key, transport = FakeTransport(mapOf("/bibles" to HttpResponse(200, body.toByteArray()))))
            .availableTranslations()
        assertEquals(APIBibleTranslation("abc-01", "Christian Standard Bible", "CSB", "English", "© Holman"), list[0])
        assertEquals(APIBibleTranslation("bare", "bare", "", "", "Used by permission via API.Bible."), list[1])
    }

    // ---- Through the cache ----

    @Test fun aFetchedChapterIsCachedAndReadBackAsARenderableChapter() {
        val john3 = chapter(BookID.JOHN, 3)
        val transport = FakeTransport(mapOf("api.esv.org" to esvEnvelope(inventedESV(john3, 5))))
        val loader = loader(transport)

        val first = loader.chapter(OnlineEntry.ESV, john3)
        assertEquals(1, transport.requests.size)
        assertEquals(5, first.verses.size)
        assertEquals("Invented verse 1.", first.verses[0].text)
        assertTrue("words of Christ survive the cache", first.verses[1].red.isNotEmpty())
        assertTrue(first.layout.blocks.isNotEmpty())
        // The publisher's notice is what the reader's footer prints.
        assertEquals(EsvTerms.REQUIRED_COPYRIGHT, first.translation.copyright)
        // Licensed text: quotation up to 500 verses, and nothing handed to other apps.
        assertEquals(TranslationRights.LICENSED_DEFAULT, first.translation.rights)

        // Already read: no request at all.
        val again = loader.chapter(OnlineEntry.ESV, john3)
        assertEquals(1, transport.requests.size)
        assertEquals(first.verses, again.verses)
    }

    @Test fun theEsvCacheHoldsAtMostFiveHundredVerses() {
        val transport = FakeTransport(emptyMap())
        assertEquals(500, loader(transport).cache(OnlineEntry.ESV).verseLimit)
    }

    @Test fun theVerseCeilingEvictsTheLeastRecentlyReadChapter() {
        val a = chapter(BookID.PSALMS, 1)
        val b = chapter(BookID.PSALMS, 2)
        val c = chapter(BookID.PSALMS, 3)
        val transport = FakeTransport(mapOf(
            "q=Psalms%201&" to esvEnvelope(inventedESV(a, 6)),
            "q=Psalms%202&" to esvEnvelope(inventedESV(b, 12)),
            "q=Psalms%203&" to esvEnvelope(inventedESV(c, 8)),
        ))
        val loader = loader(transport, limit = 20)
        loader.chapter(OnlineEntry.ESV, a)
        loader.chapter(OnlineEntry.ESV, b)
        loader.chapter(OnlineEntry.ESV, c) // 6 + 12 + 8 > 20: Psalm 1, the least recently read, goes
        val cache = loader.cache(OnlineEntry.ESV)
        assertTrue(cache.cachedVerseCount() <= 20)
        assertEquals(listOf(b, c), cache.cachedChapters().sortedBy { it.key })
        assertEquals(3, transport.requests.size)
        loader.chapter(OnlineEntry.ESV, a) // gone, so fetched again
        assertEquals(4, transport.requests.size)
    }

    @Test fun previewsReadOnlyWhatIsCached() {
        val john3 = chapter(BookID.JOHN, 3)
        val transport = FakeTransport(mapOf("api.esv.org" to esvEnvelope(inventedESV(john3, 5))))
        val loader = loader(transport)
        assertTrue(loader.cachedVerses(OnlineEntry.ESV, 43003001, 43003005).isEmpty())
        assertTrue("a preview never goes to the network", transport.requests.isEmpty())
        loader.chapter(OnlineEntry.ESV, john3)
        assertEquals(listOf(2, 3), loader.cachedVerses(OnlineEntry.ESV, 43003002, 43003003).map { it.ref.verse })
        loader.clear(OnlineEntry.ESV)
        assertTrue(loader.cachedVerses(OnlineEntry.ESV, 43003001, 43003005).isEmpty())
    }

    @Test fun apiBibleEntriesCarryTheirOwnIdentity() {
        val entry = OnlineEntry("CSB", "Christian Standard Bible", OnlineProvider.API_BIBLE, "abc-01")
        assertEquals("CSB.sqlite", entry.cacheFileName)
        assertEquals("Used by permission of the publisher through API.Bible.", entry.translation.copyright)
        assertEquals("ESV.sqlite", OnlineEntry.ESV.cacheFileName)
        assertEquals("ONLINE.sqlite", OnlineEntry("✝︎", "", OnlineProvider.API_BIBLE, "x").cacheFileName)
    }

    // ---- The captured responses ----

    @Test fun capturedEsvChaptersRoundTripThroughTheCache() {
        val john = capture("esv-JHN3.html")
        val psalm = capture("esv-PSA23.html")
        val matthew = capture("esv-MAT5.html")
        assumeTrue("no captured ESV responses in $captures", john != null && psalm != null && matthew != null)
        val transport = FakeTransport(mapOf(
            "q=John%203&" to esvEnvelope(john!!),
            "q=Psalms%2023&" to esvEnvelope(psalm!!),
            "q=Matthew%205&" to esvEnvelope(matthew!!),
        ))
        val loader = loader(transport)
        val john3 = loader.chapter(OnlineEntry.ESV, chapter(BookID.JOHN, 3))
        assertEquals(36, john3.verses.size)
        assertTrue(john3.verses.any { it.red.isNotEmpty() })
        val psalm23 = loader.chapter(OnlineEntry.ESV, chapter(BookID.PSALMS, 23))
        assertEquals(6, psalm23.verses.size)
        assertTrue(psalm23.layout.blocks.any { it.kind == ChapterLayout.Kind.TITLE })
        assertTrue(psalm23.layout.blocks.any { it.kind.isPoetry })
        val matthew5 = loader.chapter(OnlineEntry.ESV, chapter(BookID.MATTHEW, 5))
        assertEquals(48, matthew5.verses.size)
        assertEquals(36 + 6 + 48, loader.cache(OnlineEntry.ESV).cachedVerseCount())
    }

    @Test fun capturedEsvChaptersRespectASmallCeiling() {
        val john = capture("esv-JHN3.html")
        val matthew = capture("esv-MAT5.html")
        assumeTrue("no captured ESV responses in $captures", john != null && matthew != null)
        val transport = FakeTransport(mapOf("q=John%203&" to esvEnvelope(john!!), "q=Matthew%205&" to esvEnvelope(matthew!!)))
        val loader = loader(transport, limit = 60)
        loader.chapter(OnlineEntry.ESV, chapter(BookID.JOHN, 3))
        loader.chapter(OnlineEntry.ESV, chapter(BookID.MATTHEW, 5)) // 36 + 48 > 60: John 3 goes
        assertEquals(listOf(chapter(BookID.MATTHEW, 5)), loader.cache(OnlineEntry.ESV).cachedChapters())
    }

    @Test fun capturedApiBibleChapterRoundTripsThroughTheCache() {
        val john = capture("apibible-JHN3.html")
        assumeTrue("no captured API.Bible responses in $captures", john != null)
        val entry = OnlineEntry("BSB-API", "Berean Standard Bible", OnlineProvider.API_BIBLE, "bba9f40183526463-01")
        val transport = FakeTransport(mapOf("/chapters/JHN.3?" to apiBibleEnvelope(john!!)))
        val chapter = loader(transport).chapter(entry, chapter(BookID.JOHN, 3))
        assertEquals(36, chapter.verses.size)
        assertTrue(chapter.verses.any { it.red.isNotEmpty() })
        assertNotNull(chapter.layout.blocks.firstOrNull { it.kind == ChapterLayout.Kind.HEADING })
        assertEquals("Used by permission of the publisher through API.Bible.", chapter.translation.copyright)
    }
}
