package com.blainemiller.scripturealone.data.search

import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.sql.SqlSource
import java.text.Normalizer

/** One verse a search found: where it is, and its plain text for the results list. */
data class SearchHit(val ref: VerseRef, val text: String)

/**
 * Full-text search over a plain Bible store's `verses_fts` table — `BibleStore.search` in
 * `ScriptureAloneCore`. Every bundled plain store (BSB, KJV) carries the same FTS5 index the iOS app
 * queries, built `unicode61 remove_diacritics 2`, so the same query finds the same verses on both.
 *
 * Only for plain stores — bundled, imported, and an online translation's cache all carry the index.
 * The sealed ASV has no `verses_fts`; it searches its own sealed index
 * ([com.blainemiller.scripturealone.data.sabible.TranslationPackage.search]), which answers the same
 * queries with the same verses.
 */
class VerseSearch(private val sql: SqlSource) {

    /**
     * The matching verses in canonical order — `ORDER BY rowid`, and the rowid *is* the verse key —
     * at most [limit] of them. An empty or punctuation-only query finds nothing rather than failing.
     */
    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<SearchHit> {
        val match = ftsQuery(query) ?: return emptyList()
        return sql.query(
            "SELECT rowid, text FROM verses_fts WHERE verses_fts MATCH ? ORDER BY rowid LIMIT ?",
            match, limit,
        ) { row -> SearchHit(VerseRef.fromKey(row.long(0).toInt()), row.text(1)) }
    }

    companion object {
        /** iOS caps a search at 300 and the results header says "300+ verses" when it's hit. */
        const val DEFAULT_LIMIT = 300

        /**
         * Turns what the reader typed into an FTS5 query — a line-for-line port of
         * `BibleStore.ftsQuery`, because this is the whole of "search feels the same":
         *
         * - a query wrapped in double quotes is one exact phrase (inner quotes dropped);
         * - otherwise every word must appear, each quoted so FTS5 never reads one as an operator
         *   ("and", "or", "not", "near" are all words in the Bible), and the **last** word matches as
         *   a prefix, so the list narrows while the reader is still typing it.
         *
         * A word is a run of letters, marks and digits, plus the apostrophes — straight or curly, the
         * curly one straightened — that keep "LORD's" one word here; FTS5's tokenizer then splits it
         * into a phrase of its parts, which is what the iOS app asks for too.
         *
         * Swift works in grapheme clusters and Unicode scalars; this walks code points. They differ
         * only for a query of combining marks with no base, which neither side can usefully search.
         */
        fun ftsQuery(query: String): String? {
            val trimmed = query.trim { it.isWhitespace() }
            if (trimmed.codePointCount(0, trimmed.length) > 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                val phrase = trimmed.substring(1, trimmed.length - 1).replace("\"", "")
                return if (phrase.isEmpty()) null else "\"$phrase\""
            }
            val words = words(trimmed)
            if (words.isEmpty()) return null
            return words.mapIndexed { index, w ->
                if (index == words.lastIndex) "\"$w\" *" else "\"$w\""
            }.joinToString(" ")
        }

        /**
         * The words of a query: runs of letters, marks, digits and apostrophes, the curly apostrophe
         * straightened. Shared with the sealed index's query parser, which splits the same way.
         */
        fun words(text: String): List<String> {
            val words = mutableListOf<String>()
            val word = StringBuilder()
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (isWordCodePoint(cp)) {
                    word.appendCodePoint(if (cp == RIGHT_SINGLE_QUOTE) '\''.code else cp)
                } else if (word.isNotEmpty()) {
                    words += word.toString()
                    word.clear()
                }
                i += Character.charCount(cp)
            }
            if (word.isNotEmpty()) words += word.toString()
            return words
        }

        private const val RIGHT_SINGLE_QUOTE = 0x2019

        /**
         * Foundation's `CharacterSet.alphanumerics` — the L*, M* and N* general categories — plus the
         * two apostrophes.
         */
        private fun isWordCodePoint(cp: Int): Boolean {
            if (cp == '\''.code || cp == RIGHT_SINGLE_QUOTE) return true
            return when (Character.getType(cp).toByte()) {
                Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
                Character.MODIFIER_LETTER, Character.OTHER_LETTER,
                Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
                Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
                -> true
                else -> false
            }
        }
    }
}

/**
 * Where the typed words fall in a result, for bolding them — `PassagePicker.emphasized` on iOS.
 *
 * The same rules as there: the query is split on anything that is neither a letter nor a straight
 * apostrophe, words shorter than two letters are ignored (bolding every "a" is noise), and matching
 * ignores case and diacritics, so "naive" emphasises "naïve". Matches are found anywhere, not only at
 * word starts — a prefix search for "shep" should light up the "shep" in "shepherd".
 */
object SearchEmphasis {

    /** UTF-16 ranges of [text] to emphasise, sorted, non-overlapping. */
    fun ranges(text: String, query: String): List<IntRange> {
        val words = query.lowercase()
            .splitWhere { !it.isLetter() && it != '\'' }
            .filter { it.length >= 2 }
            .map(::fold)
            .distinct()
        if (words.isEmpty()) return emptyList()
        val haystack = fold(text)
        val marked = BooleanArray(text.length)
        for (word in words) {
            var from = 0
            while (true) {
                val at = haystack.indexOf(word, from)
                if (at < 0) break
                for (k in at until at + word.length) marked[k] = true
                from = at + word.length
            }
        }
        val out = mutableListOf<IntRange>()
        var start = -1
        for (k in marked.indices) {
            if (marked[k] && start < 0) start = k
            if (!marked[k] && start >= 0) {
                out += start until k
                start = -1
            }
        }
        if (start >= 0) out += start until text.length
        return out
    }

    /**
     * Lowercased, each UTF-16 unit folded **on its own** to its base letter. Folding unit by unit keeps
     * the folded string exactly as long as the original, so an index found in one is an index in the
     * other — decomposing the whole string would shift every offset after the first "ï".
     */
    private fun fold(s: String): String {
        val out = CharArray(s.length)
        for (i in s.indices) {
            val c = s[i]
            out[i] = if (c.code < 0x80 || c.isSurrogate()) {
                c.lowercaseChar()
            } else {
                Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar()
            }
        }
        return String(out)
    }

    private inline fun String.splitWhere(isSeparator: (Char) -> Boolean): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (c in this) {
            if (isSeparator(c)) {
                if (current.isNotEmpty()) parts += current.toString()
                current.clear()
            } else {
                current.append(c)
            }
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }
}
