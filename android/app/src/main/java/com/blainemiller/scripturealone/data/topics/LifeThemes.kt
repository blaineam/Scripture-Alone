package com.blainemiller.scripturealone.data.topics

import com.blainemiller.scripturealone.data.VerseRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer

/** A heading in the Topics directory — "Worry & Hard Feelings", "Who God Is". */
data class LifeThemeGroup(val id: String, val name: String, val localizedName: String = name)

/**
 * Something a reader may be going through, and passages that speak to it — `LifeThemes.swift`. The
 * same curated list as iOS (`Data/topics/`, built by `Tools/build_topics.py` into `LifeThemes.json`,
 * which the build copies in), so a theme holds the same passages on both platforms.
 *
 * Passages are references only, in KJV keys: the words always come from the translation being read.
 * The English fields are the source; the localized ones come from `life_themes.xml` in each `res/values` folder.
 */
data class LifeTheme(
    /** Stable: never renamed. */
    val id: String,
    val group: String,
    val name: String,
    val description: String,
    val synonyms: List<String>,
    val passages: List<VerseRange>,
    /** Nave's Topical Bible topics that cover the same ground, by name. */
    val naveTopics: List<String>,
    val localizedName: String = name,
    val localizedDescription: String = description,
    val localizedSynonyms: List<String> = emptyList(),
)

/** The curated life themes. */
class LifeThemeCatalog(val groups: List<LifeThemeGroup>, val themes: List<LifeTheme>) {

    fun theme(id: String): LifeTheme? = themes.firstOrNull { it.id == id }
    fun themes(group: LifeThemeGroup): List<LifeTheme> = themes.filter { it.group == group.id }

    /**
     * The catalog in the app's language: [groupNames], [names], [descriptions] and [synonyms] are the
     * string arrays of `life_themes.xml`, in `LifeThemes.json` order. A list of the wrong length (a
     * stale generated file) leaves the English rather than pairing a theme with another's words.
     */
    fun localized(groupNames: List<String>, names: List<String>, descriptions: List<String>, synonyms: List<String>): LifeThemeCatalog {
        val groupsOk = groupNames.size == groups.size
        val themesOk = names.size == themes.size && descriptions.size == themes.size && synonyms.size == themes.size
        return LifeThemeCatalog(
            groups.mapIndexed { i, g -> if (groupsOk) g.copy(localizedName = groupNames[i]) else g },
            themes.mapIndexed { i, t ->
                if (!themesOk) {
                    t
                } else {
                    t.copy(
                        localizedName = names[i],
                        localizedDescription = descriptions[i],
                        localizedSynonyms = splitSynonyms(synonyms[i]),
                    )
                }
            },
        )
    }

    /**
     * Themes a search speaks to, best first — `LifeThemeCatalog.search` on iOS: the name and search
     * words, in English and the app's language, scored by [TopicSearch.score].
     */
    fun search(query: String, limit: Int = 3): List<LifeTheme> {
        val needle = TopicSearch.normalize(query)
        if (needle.length < TopicSearch.minimumLength(needle)) return emptyList()
        return themes.withIndex().mapNotNull { (index, theme) ->
            val terms = listOf(theme.name, theme.localizedName) + theme.synonyms + theme.localizedSynonyms
            val score = terms.maxOf { TopicSearch.score(TopicSearch.normalize(it), needle) }
            if (score > 0) Triple(score, index, theme) else null
        }.sortedWith(compareByDescending<Triple<Int, Int, LifeTheme>> { it.first }.thenBy { it.second })
            .take(limit)
            .map { it.third }
    }

    companion object {
        val EMPTY = LifeThemeCatalog(emptyList(), emptyList())

        /** `LifeThemes.json`: groups, then themes with their passages as storage strings. */
        fun parse(json: String): LifeThemeCatalog {
            val root = Json.parseToJsonElement(json).jsonObject
            fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.content.orEmpty()
            fun JsonObject.strings(key: String) = (get(key) as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            val groups = root.getValue("groups").jsonArray.map { it.jsonObject }.map { LifeThemeGroup(it.string("id"), it.string("name")) }
            val themes = root.getValue("themes").jsonArray.map { it.jsonObject }.map { t ->
                LifeTheme(
                    id = t.string("id"),
                    group = t.string("group"),
                    name = t.string("name"),
                    description = t.string("description"),
                    synonyms = t.strings("synonyms"),
                    passages = t.strings("refs").mapNotNull(VerseRange::parse),
                    naveTopics = t.strings("nave"),
                )
            }
            return LifeThemeCatalog(groups, themes)
        }

        /** The catalog keeps each theme's search words as one string, separated by commas. */
        fun splitSynonyms(line: String): List<String> = line.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/**
 * How the Topics directory matches what someone types against names and search words — `TopicSearch`
 * in `LifeThemes.swift`, with the same folding and the same scores, so a word finds the same topics on
 * both platforms.
 */
object TopicSearch {
    /**
     * Lowercased, accents and width folded, apostrophes dropped ("God’s" → "gods"), runs of anything
     * that isn't a letter or digit made one space.
     */
    fun normalize(text: String): String {
        // Accents are the combining marks U+0300–036F; Japanese voicing marks are not accents. Composed
        // again afterwards, so a Korean syllable or a voiced kana is one letter again.
        val stripped = Normalizer.normalize(text, Normalizer.Form.NFKD).filter { it.code !in 0x0300..0x036F }
        val folded = Normalizer.normalize(stripped, Normalizer.Form.NFC)
            .lowercase()
            .replace("’", "").replace("'", "")
        val out = StringBuilder()
        var pendingSpace = false
        for (c in folded) {
            if (c.isLetterOrDigit()) {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                out.append(c)
            } else {
                pendingSpace = true
            }
        }
        return out.toString()
    }

    /** Two characters in Chinese, Japanese and Korean, where two make a word; three elsewhere. */
    fun minimumLength(text: String): Int = if (text.any(::isCjk)) 2 else 3

    private fun isCjk(c: Char): Boolean = c.code in 0x3040..0x30FF || c.code in 0x3400..0x9FFF || c.code in 0xAC00..0xD7AF

    /**
     * How well a normalized [term] answers a normalized [query]; 0 for not at all. 100 the term is the
     * query; 80 the query begins it; 70 it is a whole word or phrase inside the query; 60 the query is
     * or begins a word of a longer term. Chinese, Japanese and Korean have no spaces between words, so
     * there containment either way counts.
     */
    fun score(term: String, query: String): Int {
        if (term.isEmpty() || query.isEmpty()) return 0
        if (term == query) return 100
        if (term.startsWith(query)) return 80
        if (term.any(::isCjk) || query.any(::isCjk)) {
            if (term.length >= 2 && query.contains(term)) return 70
            if (query.length >= 2 && term.contains(query)) return 60
            return 0
        }
        if (term.length >= 3 && " $query ".contains(" $term ")) return 70
        if (term.split(' ').any { it.startsWith(query) }) return 60
        return 0
    }
}
