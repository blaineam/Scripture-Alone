package com.blainemiller.scripturealone.data.online

import com.blainemiller.scripturealone.data.Canon
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.reference.ReferenceParser
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.search.SearchHit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** What came back from one GET: the status and the whole body. */
class HttpResponse(val status: Int, val body: ByteArray)

/**
 * The one network call the online clients make — a GET with headers. A seam so the clients are
 * proven on the JVM against captured responses instead of Crossway's and API.Bible's live services
 * (and so no test ever needs, or leaks, a key).
 */
fun interface HttpTransport {
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

/**
 * [HttpTransport] over `HttpURLConnection`, HTTPS only. Headers are set on the request and never
 * logged: one of them is the reader's API key.
 */
object UrlConnectionTransport : HttpTransport {
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        require(url.startsWith("https://")) { "online translations are fetched over HTTPS only" }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            val status = connection.responseCode
            val stream = if (status in 200 until 300) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            return HttpResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * A translation the app reads over the network because no one may ship its text — `OnlineProvider`
 * in `ScriptureAlone/Translations/OnlineTranslations.swift`. In both cases the key belongs to the
 * reader, not the app: the free tiers are per-key allowances, and a key shipped inside the app would
 * be spent by a handful of people and abuse a non-commercial grant.
 */
enum class OnlineProvider(val raw: String, val title: String, val signupUrl: String) {
    CROSSWAY("crossway", "Crossway (ESV)", ESVClient.SIGNUP_URL),
    API_BIBLE("apiBible", "API.Bible (CSB, NASB, NKJV…)", APIBibleClient.SIGNUP_URL);

    /** The notice that must travel with this publisher's text. */
    val copyrightNotice: String
        get() = when (this) {
            CROSSWAY -> EsvTerms.REQUIRED_COPYRIGHT
            API_BIBLE -> "Used by permission of the publisher through API.Bible."
        }

    val licenseSummary: String
        get() = when (this) {
            CROSSWAY -> "Licensed — read from Crossway's API with your key"
            API_BIBLE -> "Licensed — read from API.Bible with your key"
        }

    val explanation: String
        get() = when (this) {
            CROSSWAY -> "Crossway's free tier allows 5,000 requests a day for non-commercial use. The key is " +
                "yours, and the ESV is read over the network — up to 500 verses are kept on the " +
                "device, which is Crossway's limit."
            API_BIBLE -> "The American Bible Society's free Starter plan is for non-commercial use and lets you " +
                "pick three copyrighted translations. The key is yours."
        }

    companion object {
        fun of(raw: String): OnlineProvider? = entries.firstOrNull { it.raw == raw }
    }
}

/** Why an online chapter couldn't be read. [message] is what the reader is shown. */
sealed class OnlineFailure(message: String) : Exception(message) {
    class NeedsKey(val provider: OnlineProvider) : OnlineFailure(
        when (provider) {
            OnlineProvider.CROSSWAY -> "Add your free Crossway key in Manage Translations to read the ESV."
            OnlineProvider.API_BIBLE -> "Add your free API.Bible key in Manage Translations to read this translation."
        },
    )
    class Unauthorized(provider: OnlineProvider) : OnlineFailure(
        when (provider) {
            OnlineProvider.CROSSWAY -> "Crossway didn't accept that API key. Check it at api.esv.org."
            OnlineProvider.API_BIBLE -> "API.Bible didn't accept that key. Check it on api.bible."
        },
    )
    class RateLimited(provider: OnlineProvider) : OnlineFailure(
        when (provider) {
            OnlineProvider.CROSSWAY -> "You've reached Crossway's daily limit for your key. It resets tomorrow."
            OnlineProvider.API_BIBLE -> "You've used your API.Bible requests for this month."
        },
    )
    class NotAvailable(name: String) :
        OnlineFailure("$name isn't one of the translations your key can read. Choose it on api.bible first.")
    class Http(provider: OnlineProvider, val code: Int) : OnlineFailure(
        when (provider) {
            OnlineProvider.CROSSWAY -> "Crossway's API returned HTTP $code."
            OnlineProvider.API_BIBLE -> "API.Bible returned HTTP $code."
        },
    )
    class Empty(provider: OnlineProvider, reference: String) : OnlineFailure(
        when (provider) {
            OnlineProvider.CROSSWAY -> "Crossway returned nothing for $reference."
            OnlineProvider.API_BIBLE -> "API.Bible returned nothing for $reference."
        },
    )
    class Malformed(provider: OnlineProvider) : OnlineFailure(
        when (provider) {
            OnlineProvider.CROSSWAY -> "Crossway's reply couldn't be read."
            OnlineProvider.API_BIBLE -> "API.Bible's reply couldn't be read."
        },
    )
}

/**
 * Reads passages from Crossway's ESV API — a port of `ScriptureAloneCore/ESV/ESVClient.swift`, with
 * the same endpoint, the same query and the same failures. Its terms shape everything around it: the
 * key is the reader's own, at most 500 verses may be cached ([OnlineChapterCache] enforces that), and
 * the copyright line travels with the text.
 */
class ESVClient(private val key: String, private val transport: HttpTransport = UrlConnectionTransport) {

    /** One chapter, with its structure: verses, the words of Christ, poetry lines, psalm titles. */
    fun chapter(chapter: ChapterRef): ParsedPassage {
        val url = chapterUrl(chapter)
        val response = transport.get(url, mapOf("Authorization" to "Token $key"))
        checkStatus(response, OnlineProvider.CROSSWAY)
        val root = parseJson(response.body, OnlineProvider.CROSSWAY)
        val passages = ((root as? JsonObject)?.get("passages") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?: throw OnlineFailure.Malformed(OnlineProvider.CROSSWAY)
        val passage = ESVPassageHTML.parse(passages.joinToString("\n"), chapter)
        if (passage.isEmpty) throw OnlineFailure.Empty(OnlineProvider.CROSSWAY, Canon.display(chapter))
        return passage
    }

    /**
     * Crossway's own search, so the ESV is searchable like any other translation — `ESVClient.search`.
     * The whole text can't be indexed on the device (Crossway's terms cap the cache at 500 verses), so
     * the search happens at their end and costs one request.
     */
    fun search(query: String, limit: Int = 100): List<SearchHit> {
        val response = transport.get(searchUrl(query, limit), mapOf("Authorization" to "Token $key"))
        checkStatus(response, OnlineProvider.CROSSWAY)
        val results = ((parseJson(response.body, OnlineProvider.CROSSWAY) as? JsonObject)?.get("results") as? JsonArray)
            ?: throw OnlineFailure.Malformed(OnlineProvider.CROSSWAY)
        return results.mapNotNull { element ->
            val result = element as? JsonObject ?: return@mapNotNull null
            val reference = result.string("reference") ?: return@mapNotNull null
            val content = result.string("content") ?: return@mapNotNull null
            searchHit(reference, content)
        }
    }

    companion object {
        const val SIGNUP_URL = "https://api.esv.org/account/create-application/"
        private const val ENDPOINT = "https://api.esv.org/v3/passage/html/"

        /** `/v3/passage/search/` with `q` and `page-size` (at most 100), as the Swift client asks. */
        fun searchUrl(text: String, limit: Int): String =
            "https://api.esv.org/v3/passage/search/?" + query("q" to text, "page-size" to minOf(limit, 100).toString())

        /**
         * The HTML endpoint, because it states its structure instead of drawing it: words of Christ
         * are `<span class="woc">`, poetry is `<span class="line">`, a psalm's superscription is an
         * `<h4 class="psalm-title">`. The text endpoint has none of that.
         */
        fun chapterUrl(chapter: ChapterRef): String = ENDPOINT + "?" + query(
            "q" to Canon.display(chapter),
            "include-verse-numbers" to "true",
            "include-headings" to "false",
            "include-footnotes" to "false",
            "include-short-copyright" to "false",
            "include-passage-references" to "false",
            "include-css-link" to "false",
            "inline-styles" to "false",
            "wrapping-div" to "false",
            "include-book-titles" to "false",
            "include-chapter-numbers" to "true",
            // Defaults to true, and drops an `<a class="mp3link">` into the passage heading.
            "include-audio-link" to "false",
            "include-crossrefs" to "false",
        )
    }
}

/** One translation the reader's API.Bible key can reach. */
data class APIBibleTranslation(
    /** Opaque, e.g. "de4e12af7f28f599-02". */
    val id: String,
    val name: String,
    val abbreviation: String,
    val language: String,
    val copyright: String,
)

/**
 * Reads passages from the American Bible Society's API.Bible — a port of `APIBibleClient.swift`. Which
 * translations a key may read is chosen on API.Bible's own dashboard, so the app asks the key rather
 * than hard-coding anything.
 */
class APIBibleClient(
    private val key: String,
    private val bibleId: String = "",
    private val transport: HttpTransport = UrlConnectionTransport,
) {
    /** Every translation this key may read, so the reader picks from what they actually have. */
    fun availableTranslations(): List<APIBibleTranslation> {
        val root = get("$BASE/bibles", "the Bible list")
        val data = ((root as? JsonObject)?.get("data") as? JsonArray) ?: throw OnlineFailure.Malformed(OnlineProvider.API_BIBLE)
        return data.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val id = entry.string("id") ?: return@mapNotNull null
            APIBibleTranslation(
                id = id,
                name = entry.string("name") ?: entry.string("abbreviation") ?: id,
                abbreviation = entry.string("abbreviationLocal") ?: entry.string("abbreviation") ?: "",
                language = (entry["language"] as? JsonObject)?.string("name") ?: "",
                copyright = entry.string("copyright") ?: "Used by permission via API.Bible.",
            )
        }
    }

    fun chapter(chapter: ChapterRef): ParsedPassage {
        val root = get(chapterUrl(bibleId, chapter), Canon.display(chapter))
        val content = ((root as? JsonObject)?.get("data") as? JsonObject)?.string("content")
            ?: throw OnlineFailure.Malformed(OnlineProvider.API_BIBLE)
        val passage = APIBiblePassageHTML.parse(content, chapter)
        if (passage.isEmpty) throw OnlineFailure.Empty(OnlineProvider.API_BIBLE, Canon.display(chapter))
        return passage
    }

    /**
     * API.Bible's own search, for the same reason Crossway's is used: the text can't be indexed on the
     * device, so the provider does the searching — `APIBibleClient.search`.
     */
    fun search(query: String, limit: Int = 100): List<SearchHit> {
        val root = get(searchUrl(bibleId, query, limit), query)
        val data = (root as? JsonObject)?.get("data") as? JsonObject ?: throw OnlineFailure.Malformed(OnlineProvider.API_BIBLE)
        val verses = data["verses"] as? JsonArray ?: return emptyList()
        return verses.mapNotNull { element ->
            val verse = element as? JsonObject ?: return@mapNotNull null
            searchHit(verse.string("reference") ?: return@mapNotNull null, verse.string("text") ?: return@mapNotNull null)
        }
    }

    private fun get(url: String, reference: String): JsonElement {
        val response = transport.get(url, mapOf("api-key" to key))
        when (response.status) {
            in 200 until 300 -> Unit
            401 -> throw OnlineFailure.Unauthorized(OnlineProvider.API_BIBLE)
            403 -> throw OnlineFailure.NotAvailable(reference)
            429 -> throw OnlineFailure.RateLimited(OnlineProvider.API_BIBLE)
            else -> throw OnlineFailure.Http(OnlineProvider.API_BIBLE, response.status)
        }
        return parseJson(response.body, OnlineProvider.API_BIBLE)
    }

    companion object {
        const val BASE = "https://api.scripture.api.bible/v1"
        const val SIGNUP_URL = "https://api.bible/"

        /**
         * Chapter ids are "<USFM book code>.<number>" — the codes the canon already carries. HTML,
         * not text: its markup is USFM with the markers as class names (`wj`, `q1`, `d`, `s1`), the
         * same vocabulary this app's layout speaks.
         */
        /** `bibles/{id}/search` with `query`, `limit` (at most 100) and canonical order. */
        fun searchUrl(bibleId: String, text: String, limit: Int): String =
            "$BASE/bibles/${encode(bibleId)}/search?" + query(
                "query" to text,
                "limit" to minOf(limit, 100).toString(),
                "sort" to "canonical",
            )

        fun chapterUrl(bibleId: String, chapter: ChapterRef): String {
            val code = BookID.of(chapter.book)?.code ?: error("no book ${chapter.book}")
            return "$BASE/bibles/${encode(bibleId)}/chapters/$code.${chapter.chapter}?" + query(
                "content-type" to "html",
                "include-verse-numbers" to "true",
                "include-chapter-numbers" to "false",
                "include-notes" to "false",
                "include-titles" to "true",
            )
        }
    }
}

private fun checkStatus(response: HttpResponse, provider: OnlineProvider) {
    when (response.status) {
        in 200 until 300 -> Unit
        401, 403 -> throw OnlineFailure.Unauthorized(provider)
        429 -> throw OnlineFailure.RateLimited(provider)
        else -> throw OnlineFailure.Http(provider, response.status)
    }
}

private fun parseJson(body: ByteArray, provider: OnlineProvider): JsonElement = try {
    Json.parseToJsonElement(body.toString(Charsets.UTF_8))
} catch (_: IllegalArgumentException) {
    throw OnlineFailure.Malformed(provider)
}

/**
 * A provider's result as a hit: its reference ("John 3:16", "John 3:16-17") parsed to the first verse,
 * as `ReferenceParser.parse(...).firstVerse` in Swift. A reference that doesn't parse is dropped.
 */
private fun searchHit(reference: String, text: String): SearchHit? {
    val passage = ReferenceParser.parse(reference) ?: return null
    return SearchHit(VerseRef(passage.book.number, passage.startChapter, passage.startVerse ?: 1), text)
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** `URLComponents` percent-encoding: spaces as %20, never '+'. */
private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

private fun query(vararg items: Pair<String, String>): String =
    items.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
