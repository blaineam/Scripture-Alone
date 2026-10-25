package com.blainemiller.scripturealone.data.plans

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A source of web requests; the app hands in [HttpUrlFetcher], tests a fixed table. */
fun interface PlanFetcher {
    /** A response, or an [IOException] when the request never reached the server (offline, timeout, dead host). */
    @Throws(IOException::class)
    fun fetch(url: String): Response

    data class Response(val code: Int, val body: String, val headers: Map<String, String> = emptyMap())
}

class HttpUrlFetcher : PlanFetcher {
    override fun fetch(url: String): PlanFetcher.Response {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000
            c.readTimeout = 20_000
            c.setRequestProperty("Accept", "application/json, application/rss+xml, application/xml;q=0.9")
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val headers = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.firstOrNull().orEmpty() }
            return PlanFetcher.Response(code, body, headers)
        } finally {
            c.disconnect()
        }
    }
}

/**
 * Reads a plan's schedule from a pasted address, and the weekly study on demand — the networked half of
 * `PlanFeedImporter.swift`, over [PlanFeed]'s pure logic. Blocking: call from `Dispatchers.IO`.
 * Only the schedule is kept; what a site republishes of the passage text is never fetched.
 */
class PlanImporter(private val fetcher: PlanFetcher = HttpUrlFetcher()) {
    /** A known church plan: category 2 is the daily reading, 7 the Saturday Study. */
    data class Preset(val name: String, val site: String, val scheduleCategory: Int, val studyCategory: Int?)

    sealed interface Mode {
        /** Follow the dates the site gave its days, from today on. */
        data object FollowCalendar : Mode
        /** Count the site's days from today, taking posts published on or after [since]. */
        data class FromDayOne(val since: LocalDate) : Mode
    }

    private fun get(url: String): PlanFetcher.Response {
        val response = try {
            fetcher.fetch(url)
        } catch (e: IOException) {
            throw PlanFeedException(PlanFeedException.Kind.OFFLINE)
        }
        if (response.code !in 200..299) throw PlanFeedException(PlanFeedException.Kind.NOT_A_FEED)
        return response
    }

    private fun rest(site: String, route: String, query: Map<String, String>): String =
        site.trimEnd('/') + "/wp-json/wp/v2/$route?" +
            query.toSortedMap().entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    fun categories(site: String): List<Triple<Int, String, Int>> =
        PlanFeed.categories(get(rest(site, "categories", mapOf("per_page" to "100", "hide_empty" to "true", "_fields" to "id,name,count"))).body)

    /** Posts oldest first, from [after] on, at most [limit]. A WordPress site is paged 100 at a time. */
    fun entries(endpoint: PlanFeed.Endpoint, after: LocalDate? = null, limit: Int = 400): List<FeedEntry> = when (endpoint) {
        is PlanFeed.Endpoint.Rss -> parseRss(get(endpoint.url).body).sortedBy { it.date }
        is PlanFeed.Endpoint.WordPress -> {
            val result = mutableListOf<FeedEntry>()
            var page = 1
            var pages = 1
            while (page <= pages && result.size < limit) {
                val query = mutableMapOf("per_page" to "100", "page" to page.toString(), "orderby" to "date", "order" to "asc",
                    "_fields" to "id,date,title,link")
                endpoint.category?.let { query["categories"] = it.toString() }
                after?.let { query["after"] = "${it}T00:00:00" }
                val response = try {
                    get(rest(endpoint.site, "posts", query))
                } catch (e: PlanFeedException) {
                    // WordPress answers 400 for a page past the last.
                    if (page > 1 && e.kind == PlanFeedException.Kind.NOT_A_FEED) break else throw e
                }
                pages = response.headers["x-wp-totalpages"]?.toIntOrNull() ?: pages
                result += PlanFeed.entriesFromRest(response.body)
                page++
            }
            result.take(limit)
        }
    }

    /** Tries each endpoint a pasted address could mean until one answers with posts. */
    fun entries(pasted: String, after: LocalDate? = null, limit: Int = 400): Pair<PlanFeed.Endpoint, List<FeedEntry>> {
        val candidates = PlanFeed.endpoints(pasted)
        if (candidates.isEmpty()) throw PlanFeedException(PlanFeedException.Kind.BAD_URL)
        var failure = PlanFeedException(PlanFeedException.Kind.NOT_A_FEED)
        for (endpoint in candidates) {
            try {
                val found = entries(endpoint, after, limit)
                if (found.isNotEmpty()) return endpoint to found
            } catch (e: PlanFeedException) {
                if (e.kind == PlanFeedException.Kind.OFFLINE) throw e
                failure = e
            }
        }
        throw failure
    }

    /** Reads the schedule at [pasted] (or [preset]) into a plan. The caller adds and starts it. */
    fun import(pasted: String, preset: Preset?, mode: Mode, today: LocalDate): PlanImport {
        // A month back, so a weekend or a holiday still finds posts; the plan starts today all the same.
        val recent = today.minusDays(30)
        val following = mode == Mode.FollowCalendar
        // A site posts each day's reading on the day, so nothing is published ahead. It repeats its reading
        // order every few years, though: read that far back to find the last pass and project the days still
        // to come from it (PlanFeed.projection).
        val after = when (mode) {
            Mode.FollowCalendar -> today.minusYears(5)
            is Mode.FromDayOne -> mode.since
        }
        val limit = if (following) 2_500 else 400
        val endpoints = preset?.let { listOf(PlanFeed.Endpoint.WordPress(it.site, it.scheduleCategory)) } ?: PlanFeed.endpoints(pasted)
        if (endpoints.isEmpty()) throw PlanFeedException(PlanFeedException.Kind.BAD_URL)
        var found: Pair<PlanFeed.Endpoint, List<FeedEntry>>? = null
        var failure = PlanFeedException(PlanFeedException.Kind.NOT_A_FEED)
        for (endpoint in endpoints) {
            try {
                val entries = entries(endpoint, after, limit)
                if (entries.isNotEmpty()) { found = endpoint to entries; break }
            } catch (e: PlanFeedException) {
                if (e.kind == PlanFeedException.Kind.OFFLINE) throw e
                failure = e
            }
        }
        val (endpoint, entries) = found ?: throw failure
        val (site, api, category) = when (endpoint) {
            is PlanFeed.Endpoint.WordPress -> Triple(endpoint.site, endpoint.site.trimEnd('/') + "/wp-json/wp/v2", endpoint.category)
            is PlanFeed.Endpoint.Rss -> Triple(endpoint.url, null, null)
        }
        val name = preset?.name ?: runCatching { java.net.URI(site).host }.getOrNull() ?: site
        return PlanFeed.plan(entries, name, PlanSource(site, api, preset?.studyCategory, name, category),
            keepingFrom = if (following) recent.toString() else null, projecting = following)
    }

    /** Fetches the days a followed site has published since the plan was imported; quiet on failure. */
    fun refresh(store: PlanStore, today: LocalDate) {
        for (plan in store.imported) {
            val source = plan.source ?: continue
            val first = plan.days.firstOrNull()?.date ?: continue
            if (store.progress(plan.id)?.followsCalendar != true) continue
            // The days up to the last one the site really posted; whatever follows is a projection.
            val posted = plan.days.filter { !it.projected }
            if ((posted.lastOrNull()?.date ?: "") >= today.toString()) continue
            val site = source.apiRoot?.removeSuffix("/wp-json/wp/v2") ?: continue
            // A plan made before days could be projected has none: read the site's history once a week to
            // look for the earlier pass (a site with none isn't asked again every time the app opens).
            val lookBack = plan.days.none { it.projected } &&
                (store.projectionTried(plan.id)?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today) > 7 } ?: true)
            val result = runCatching {
                val from = if (lookBack) today.minusYears(5) else LocalDate.parse(first).minusDays(1)
                val entries = entries(PlanFeed.Endpoint.WordPress(site, source.scheduleCategory), from, if (lookBack) 2_500 else 400)
                if (lookBack) store.markProjectionTried(plan.id, today)
                PlanFeed.plan(entries, plan.title, source, keepingFrom = if (lookBack) first else null, projecting = lookBack)
            }.getOrNull() ?: continue
            if (result.plan.id != plan.id || result.plan.days.count { !it.projected } < posted.size) continue
            // What the site hadn't posted yet when the plan was made carries on from where it now stands.
            val ahead = if (lookBack) emptyList() else PlanFeed.projection(result.plan.days, plan.days)
            store.add(result.plan.copy(days = result.plan.days + ahead))
        }
    }

    /** The most recent commentary post published on or before [date], as plain paragraphs. Fetched each time it is wanted. */
    fun latestStudy(source: PlanSource, date: LocalDate): StudyPost? {
        val api = source.apiRoot ?: return null
        val category = source.studyCategory ?: return null
        val site = api.removeSuffix("/wp-json/wp/v2")
        val body = get(rest(site, "posts", mapOf("categories" to category.toString(), "per_page" to "1", "orderby" to "date",
            "order" to "desc", "before" to "${date}T23:59:59", "_fields" to "id,date,title,link,content"))).body
        val rows = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonArray
            ?: throw PlanFeedException(PlanFeedException.Kind.NOT_A_FEED)
        val row = rows.firstOrNull()?.jsonObject ?: return null
        val title = (row["title"] as? JsonObject)?.get("rendered")?.jsonPrimitive?.contentOrNull ?: return null
        val html = (row["content"] as? JsonObject)?.get("rendered")?.jsonPrimitive?.contentOrNull ?: return null
        return StudyPost(PlanFeed.plainText(title), row["date"]?.jsonPrimitive?.contentOrNull.orEmpty().take(10),
            row["link"]?.jsonPrimitive?.contentOrNull, PlanFeed.paragraphs(html))
    }

    companion object {
        val DISCIPLES_CHURCH = Preset("Disciples Church Bible Reading Plan", "https://discipleschurch.com/bible", 2, 7)

        /** RSS items → entries, dated by `pubDate` in UTC. */
        fun parseRss(xml: String): List<FeedEntry> {
            val doc = runCatching {
                DocumentBuilderFactory.newInstance().apply {
                    // A feed we don't control gets no DOCTYPE, so no external entities.
                    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                    isExpandEntityReferences = false
                }.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
            }.getOrNull() ?: throw PlanFeedException(PlanFeedException.Kind.NOT_A_FEED)
            val items = doc.getElementsByTagName("item")
            val formats = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            val out = (0 until items.length).mapNotNull { i ->
                val item = items.item(i) as org.w3c.dom.Element
                fun text(tag: String) = item.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
                val date = runCatching { java.time.ZonedDateTime.parse(text("pubDate"), formats).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDate() }
                    .getOrNull() ?: return@mapNotNull null
                FeedEntry(PlanFeed.plainText(text("title")), date.toString(), text("link").ifEmpty { null })
            }
            if (out.isEmpty()) throw PlanFeedException(PlanFeedException.Kind.NOT_A_FEED)
            return out
        }
    }
}
