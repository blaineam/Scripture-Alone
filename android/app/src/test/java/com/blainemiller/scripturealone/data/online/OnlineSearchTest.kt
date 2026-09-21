package com.blainemiller.scripturealone.data.online

import com.blainemiller.scripturealone.data.VerseRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Searching an online translation: as on iOS, at the provider — Crossway's `/v3/passage/search/`,
 * API.Bible's `bibles/{id}/search` — with the reader's key, one request per search. Through a fake
 * transport with the providers' response shapes; no live service is reached.
 */
class OnlineSearchTest {

    @get:Rule val temporary = TemporaryFolder()

    private val key = "test-key-0123456789abcdef"

    private class FakeTransport(private val response: HttpResponse) : HttpTransport {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            requests += url to headers
            return response
        }
    }

    private fun ok(json: String) = HttpResponse(200, json.toByteArray())

    @Test
    fun esvSearchAsksCrosswayWithTheKeyAsAHeader() {
        val transport = FakeTransport(ok("""{"page":1,"total_results":2,"results":[
            {"reference":"John 10:11","content":"I am the good shepherd."},
            {"reference":"Psalm 23:1","content":"The Lord is my shepherd; I shall not want."}]}"""))
        val hits = ESVClient(key, transport).search("good shepherd", limit = 300)
        val (url, headers) = transport.requests.single()
        assertEquals("https://api.esv.org/v3/passage/search/?q=good%20shepherd&page-size=100", url)
        assertEquals("Token $key", headers["Authorization"])
        assertEquals(listOf(VerseRef(43, 10, 11), VerseRef(19, 23, 1)), hits.map { it.ref })
        assertEquals("I am the good shepherd.", hits.first().text)
    }

    /** A range, or a reference that doesn't parse, as Swift's `ReferenceParser.parse(...)?.firstVerse`. */
    @Test
    fun aRangeLandsOnItsFirstVerseAndNonsenseIsDropped() {
        val transport = FakeTransport(ok("""{"results":[
            {"reference":"John 3:16-17","content":"For God so loved…"},
            {"reference":"Not a reference","content":"…"},
            {"reference":"Jude 1","content":"Jude, a servant…"}]}"""))
        val hits = ESVClient(key, transport).search("loved")
        assertEquals(listOf(VerseRef(43, 3, 16), VerseRef(65, 1, 1)), hits.map { it.ref })
    }

    @Test
    fun apiBibleSearchAsksForCanonicalOrder() {
        val transport = FakeTransport(ok("""{"data":{"query":"shepherd","verses":[
            {"id":"PSA.23.1","reference":"Psalms 23:1","text":"The LORD is my shepherd, I lack nothing."}]}}"""))
        val hits = APIBibleClient(key, "de4e12af7f28f599-02", transport).search("shepherd", limit = 40)
        val (url, headers) = transport.requests.single()
        assertEquals("https://api.scripture.api.bible/v1/bibles/de4e12af7f28f599-02/search?query=shepherd&limit=40&sort=canonical", url)
        assertEquals(key, headers["api-key"])
        assertEquals(listOf(VerseRef(19, 23, 1)), hits.map { it.ref })
    }

    /** API.Bible answers a passage-shaped query with `passages` and no `verses`: nothing to list. */
    @Test
    fun apiBibleWithNoVersesFindsNothing() {
        val hits = APIBibleClient(key, "x", FakeTransport(ok("""{"data":{"query":"john 3","passages":[]}}"""))).search("john 3")
        assertTrue(hits.isEmpty())
    }

    @Test
    fun refusalsAreTheProvidersSentences() {
        expect<OnlineFailure.Unauthorized> { ESVClient(key, FakeTransport(HttpResponse(401, ByteArray(0)))).search("x") }
        expect<OnlineFailure.RateLimited> { ESVClient(key, FakeTransport(HttpResponse(429, ByteArray(0)))).search("x") }
        expect<OnlineFailure.Malformed> { ESVClient(key, FakeTransport(ok("[]"))).search("x") }
        expect<OnlineFailure.Unauthorized> { APIBibleClient(key, "x", FakeTransport(HttpResponse(401, ByteArray(0)))).search("x") }
    }

    /** The loader picks the provider from the entry and needs the reader's key — never a request without one. */
    @Test
    fun theLoaderSearchesAtTheEntrysProviderWithTheReadersKey() {
        val transport = FakeTransport(ok("""{"results":[{"reference":"John 11:35","content":"Jesus wept."}]}"""))
        val loader = OnlineChapterLoader(temporary.newFolder(), JdbcCacheDriver(), transport, { key })
        assertEquals(listOf(VerseRef(43, 11, 35)), loader.search(OnlineEntry.ESV, "wept", 300).map { it.ref })
        val keyless = OnlineChapterLoader(temporary.newFolder(), JdbcCacheDriver(), transport, { null })
        expect<OnlineFailure.NeedsKey> { keyless.search(OnlineEntry.ESV, "wept", 300) }
        assertEquals(1, transport.requests.size)
    }

    private inline fun <reified E : OnlineFailure> expect(block: () -> Unit) {
        try {
            block()
        } catch (e: OnlineFailure) {
            if (e !is E) fail("expected ${E::class.simpleName}, got ${e::class.simpleName}")
            return
        }
        fail("expected ${E::class.simpleName}")
    }
}
