package com.blainemiller.scripturealone.data.topics

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.sql.SqlSource
import com.blainemiller.scripturealone.data.study.StudyStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** A topic in Nave's Topical Bible: "Abraham", "Care", "Prayer". */
data class IndexTopic(val id: Int, val name: String) {
    /** The letter it files under in an A–Z list. */
    val initial: String get() = name.firstOrNull()?.uppercase() ?: "#"
}

/**
 * One line of a topic: a heading ("Remedy for") with the passages it lists (KJV keys), and the topics
 * it sends the reader on to. A line at [level] 1 sits under the level-0 line before it.
 */
data class IndexEntry(val id: Int, val label: String, val level: Int, val passages: List<VerseRange>, val seeAlso: List<IndexTopic>)

/**
 * Nave's Topical Bible (Orville J. Nave, 1896; public domain) — `TopicalIndex.swift`, reading the same
 * `Topics.sqlite` the iOS app bundles (`Tools/build_topics.py`). English prose about English words, so
 * the app shows it only to readers using it in English. Topic names are read when it opens; a topic's
 * lines are compressed and read when it is opened.
 */
class TopicalIndex(private val db: SqlSource) {
    private val meta: Map<String, String> = db.query("SELECT key, value FROM meta") { it.text(0) to it.text(1) }.toMap()

    val name: String = meta["name"] ?: "Nave’s Topical Bible"
    val license: String = meta["license"].orEmpty()
    val attribution: String = meta["attribution"].orEmpty()

    val topics: List<IndexTopic> = db.query("SELECT id, name FROM topics ORDER BY id") { IndexTopic(it.long(0).toInt(), it.text(1)) }
    private val byId = topics.associateBy { it.id }
    private val searchNames = topics.map { TopicSearch.normalize(it.name) }

    /** Nave's topics under their first letter, for the A–Z list. */
    val lettered: List<Pair<String, List<IndexTopic>>> by lazy { topics.groupBy { it.initial }.toSortedMap().toList() }

    fun topic(id: Int): IndexTopic? = byId[id]

    /** A topic by its name, ignoring case — how a life theme names the topics beside it. */
    fun topic(named: String): IndexTopic? {
        val wanted = TopicSearch.normalize(named)
        return searchNames.indexOf(wanted).takeIf { it >= 0 }?.let { topics[it] }
    }

    /** Topics whose names answer a search, best first; ties keep A–Z order. */
    fun search(query: String, limit: Int = 50): List<IndexTopic> {
        val needle = TopicSearch.normalize(query)
        if (needle.length < 2) return emptyList()
        return searchNames.withIndex().mapNotNull { (i, name) -> TopicSearch.score(name, needle).takeIf { it > 0 }?.let { it to i } }
            .sortedWith(compareByDescending<Pair<Int, Int>> { it.first }.thenBy { it.second })
            .take(limit)
            .map { topics[it.second] }
    }

    /** The topic's lines, in the book's order. */
    fun entries(topic: IndexTopic): List<IndexEntry> {
        val body = db.query("SELECT body FROM topics WHERE id = ?", topic.id) { it.blob(0) }.firstOrNull() ?: return emptyList()
        val json = StudyStore.inflate(body) ?: return emptyList()
        return decodeEntries(json) { byId[it] }
    }

    companion object {
        /** `[[label, level, [start, end, …], [topic id, …]], …]` — see Tools/build_topics.py. */
        fun decodeEntries(json: String, topic: (Int) -> IndexTopic?): List<IndexEntry> {
            val rows = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return emptyList()
            return rows.withIndex().mapNotNull { (index, element) ->
                val row = element as? JsonArray ?: return@mapNotNull null
                if (row.size != 4) return@mapNotNull null
                runCatching {
                    val keys = (row[2] as JsonArray).map { it.jsonPrimitive.int }
                    val passages = keys.chunked(2).filter { it.size == 2 }.mapNotNull { (a, b) ->
                        VerseRange.parse("$a-$b")
                    }
                    IndexEntry(
                        id = index,
                        label = row[0].jsonPrimitive.content,
                        level = row[1].jsonPrimitive.int,
                        passages = passages,
                        seeAlso = (row[3] as JsonArray).mapNotNull { topic(it.jsonPrimitive.int) },
                    )
                }.getOrNull()
            }
        }
    }
}
