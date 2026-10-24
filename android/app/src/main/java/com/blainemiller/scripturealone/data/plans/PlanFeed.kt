package com.blainemiller.scripturealone.data.plans

import com.blainemiller.scripturealone.data.reference.ReferenceParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/** One post of a feed, reduced to what a plan needs. */
data class FeedEntry(val title: String, /** "yyyy-MM-dd" */ val date: String, val link: String?)

class PlanFeedException(val kind: Kind) : Exception(kind.name) {
    enum class Kind { OFFLINE, NOT_A_FEED, NO_READINGS, BAD_URL }
}

data class PlanImport(val plan: ReadingPlan, val skipped: List<String>)

/**
 * The schedule half of the plan importer, ported from `PlanFeedImporter.swift`: what a pasted address
 * means, how a post title names a passage, and how posts become a plan. Fetching (WorkManager-free,
 * `HttpURLConnection` on an IO dispatcher) is the ⬜ part of the ledger; this is the logic it feeds.
 */
object PlanFeed {
    sealed interface Endpoint {
        data class WordPress(val site: String, val category: Int?) : Endpoint
        data class Rss(val url: String) : Endpoint
    }

    /** Reads whatever the reader pasted: a site to try WordPress then RSS on, a REST address, or a feed. */
    fun endpoints(pasted: String): List<Endpoint> {
        var text = pasted.trim()
        if (text.isEmpty()) return emptyList()
        if ("://" !in text) text = "https://$text"
        val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return emptyList()
        val host = uri.host ?: return emptyList()
        if ('.' !in host || uri.scheme !in listOf("http", "https")) return emptyList()
        val query = (uri.rawQuery ?: "").split("&").filter { it.isNotEmpty() }.associate {
            val (k, v) = (it.split("=", limit = 2) + "").take(2); java.net.URLDecoder.decode(k, "UTF-8") to v
        }
        val category = listOf("categories", "cat", "categories[0]").firstNotNullOfOrNull { query[it] }
            ?.split(",")?.firstOrNull()?.toIntOrNull()
        var base = text.substringBefore("?")
        base.indexOf("/wp-json").takeIf { it >= 0 }?.let { base = base.substring(0, it) }
        base = base.trimEnd('/')
        val path = (uri.path ?: "").lowercase()
        if ("/wp-json" in path) return listOf(Endpoint.WordPress(base, category))
        if (path.endsWith("/feed") || path.endsWith("/feed/") || path.endsWith(".xml") || path.endsWith("/rss")) {
            return listOf(Endpoint.Rss(text))
        }
        return listOf(Endpoint.WordPress(base, category), Endpoint.Rss("$base/feed/"))
    }

    /** "Epistle- 1 Corinthians 5" → label "Epistle", 1 Corinthians 5. Null when no reference is found. */
    fun parseTitle(title: String): Pair<String?, List<PlanPassage>>? {
        val text = plainText(title)
        ReferenceParser.parseAnyLanguage(text)?.let { return null to listOf(PlanPassage.of(it)) }
        for (match in SEPARATOR.findAll(text)) {
            val label = text.substring(0, match.range.first).trim()
            val tail = text.substring(match.range.last + 1)
            ReferenceParser.parseAnyLanguage(tail)?.let { return label.ifEmpty { null } to listOf(PlanPassage.of(it)) }
            val list = ReferenceParser.parseList(tail, "en").filter { it.book.chapterCount >= it.startChapter }
            val firstPart = tail.split(";").firstOrNull().orEmpty()
            if (list.isNotEmpty() && (list.size == 1 || ReferenceParser.parseAnyLanguage(firstPart) != null)) {
                return label.ifEmpty { null } to list.map(PlanPassage::of)
            }
        }
        return null
    }

    private val SEPARATOR = Regex("""\s*[-–—:|]\s+""")

    /** Turns posts into a plan: one day per date, each holding the passages its titles name. */
    fun plan(entries: List<FeedEntry>, title: String, source: PlanSource): PlanImport {
        val byDate = sortedMapOf<String, Pair<String?, MutableList<PlanPassage>>>()
        val skipped = mutableListOf<String>()
        for (entry in entries.sortedBy { it.date }) {
            val parsed = parseTitle(entry.title)
            if (parsed == null) { skipped += entry.title; continue }
            val day = byDate.getOrPut(entry.date) { parsed.first to mutableListOf() }
            for (p in parsed.second) if (p !in day.second) day.second += p
        }
        if (byDate.isEmpty()) throw PlanFeedException(PlanFeedException.Kind.NO_READINGS)
        val days = byDate.entries.mapIndexed { i, (date, v) -> PlanDay(i, date, v.first, v.second.toList()) }
        var skip = emptySet<Int>()
        if (days.size >= 14) {
            val seen = byDate.keys.map { (LocalDate.parse(it).dayOfWeek.value % 7) + 1 }.toSet()
            skip = (1..7).toSet() - seen
        }
        val id = "import-" + java.lang.Long.toUnsignedString(fnv(source.siteUrl + title), 36)
        return PlanImport(
            ReadingPlan(id, PlanKind.IMPORTED, title, "Schedule from ${source.name}. The text is read in your translation.",
                days, skip, source),
            skipped,
        )
    }

    /** Posts from a WordPress REST `posts` response (`_fields=id,date,title,link`). */
    fun entriesFromRest(json: String): List<FeedEntry> {
        val rows = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonArray
            ?: throw PlanFeedException(PlanFeedException.Kind.NOT_A_FEED)
        return rows.mapNotNull { row ->
            val o = row.jsonObject
            val date = o["date"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length >= 10 } ?: return@mapNotNull null
            val title = (o["title"] as? JsonObject)?.get("rendered")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            FeedEntry(plainText(title), date.take(10), o["link"]?.jsonPrimitive?.contentOrNull)
        }
    }

    /** Category ids and names from a REST `categories` response, most posts first. */
    fun categories(json: String): List<Triple<Int, String, Int>> {
        val rows = runCatching { Json.parseToJsonElement(json) }.getOrNull()?.jsonArray
            ?: throw PlanFeedException(PlanFeedException.Kind.NOT_A_FEED)
        return rows.mapNotNull { r ->
            val o = r.jsonObject
            val id = o["id"]?.jsonPrimitive?.int ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Triple(id, plainText(name), o["count"]?.jsonPrimitive?.int ?: 0)
        }.sortedByDescending { it.third }
    }

    /** Post HTML as plain text, one blank line between paragraphs. Shown with a link back; never stored. */
    fun paragraphs(html: String): String {
        var s = html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        s = s.replace(Regex("(?i)</(p|div|h[1-6]|li|blockquote)>|<br\\s*/?>"), "\n\n")
        s = decodeEntities(s.replace(Regex("<[^>]+>"), ""))
        return s.split("\n").map { it.replace(Regex("[ \\t]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    fun plainText(html: String): String =
        decodeEntities(html.replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()

    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "hellip" to "…")

    fun decodeEntities(s: String): String = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(s) { m ->
        val name = m.groupValues[1]
        when {
            name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            name.startsWith("#") -> name.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> NAMED[name]
        } ?: m.value
    }

    fun stableHash(s: String): Long = fnv(s)

    private fun fnv(s: String): Long {
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (b in s.toByteArray(Charsets.UTF_8)) h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L
        return h
    }
}
