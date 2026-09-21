package com.blainemiller.scripturealone.data.study

import com.blainemiller.scripturealone.data.sql.SqlSource

/** The language one interlinear word is written in. */
enum class InterlinearLanguage(val code: String, val displayName: String) {
    HEBREW("H", "Hebrew"), ARAMAIC("A", "Aramaic"), GREEK("G", "Greek");

    /** Hebrew and Aramaic are written right to left; Greek is not. */
    val isRightToLeft: Boolean get() = this != GREEK

    companion object {
        fun of(code: String): InterlinearLanguage = entries.firstOrNull { it.code == code } ?: HEBREW
    }
}

/** A morphological parsing, interned in the database's `parsings` table. */
data class InterlinearParsing(val code: String, val description: String)

/**
 * One original-language word of a verse, with the English the BSB renders it as.
 *
 * [english] can legitimately be empty — Hebrew's object marker, a Greek article the BSB folds into
 * the following noun. That is a word to show with a blank English cell, not one to drop.
 *
 * [range] is where [english] sits in the BSB verse text, in UTF-16 units: exactly Kotlin's string
 * indices, so it slices and highlights directly. Null for a superscription word (the BSB keeps Psalm
 * titles out of the verse), a word rendered with nothing, or a word of one of the few verses whose
 * records don't reproduce the BSB text exactly.
 */
data class InterlinearWord(
    val position: Int,
    val english: String,
    val original: String,
    val transliteration: String,
    val parsing: InterlinearParsing?,
    val strongs: String?,
    val language: InterlinearLanguage,
    /** 1-based position in the original-language sentence; over a verse, a permutation of 1…n. */
    val originalOrder: Int,
    val range: IntRange?,
    val isSuperscription: Boolean,
) {
    val parsingDescription: String get() = parsing?.description ?: ""
}

/** One sense of a Strong's number, as STEPBible's TBESH / TBESG write it. */
data class LexiconSense(
    val lemma: String,
    val transliteration: String,
    val morphology: String,
    val gloss: String,
    val definition: String,
) {
    val lines: List<String> get() = definition.split('\n').filter { it.isNotEmpty() }
}

/** A Strong's number's entry: its senses in STEPBible's order. */
data class LexiconEntry(val strongs: String, val senses: List<LexiconSense>) {
    val gloss: String get() = senses.firstOrNull()?.gloss ?: ""
    val lemma: String get() = senses.firstOrNull()?.lemma ?: ""
    val transliteration: String get() = senses.firstOrNull()?.transliteration ?: ""
    /** The fullest definition the entry carries — the first sense that has one. */
    val definition: String get() = senses.firstOrNull { it.definition.isNotEmpty() }?.definition ?: ""
}

/**
 * The attribution both sources ask for. STEPBible's CC BY 4.0 *requires* credit and a statement of
 * changes, so [requiredLines] must appear somewhere the reader can reach.
 */
data class InterlinearAttribution(
    val words: String,
    val wordsLicense: String,
    val wordsLicenseUrl: String,
    val wordsSourceUrl: String,
    val lexicon: String,
    val lexiconLicense: String,
    val lexiconLicenseUrl: String,
    val lexiconSourceUrl: String,
    val lexiconChanges: String,
) {
    val requiredLines: List<String> get() = listOf(words, lexicon, lexiconChanges)
}

/** What the database says about itself — the counts the build recorded. */
data class InterlinearStatistics(
    val version: String,
    val checked: String,
    val lexiconCommit: String,
    val words: Int,
    val taggedWords: Int,
    val strongsNumbers: Int,
    val versesAligned: Int,
    val verses: Int,
)

/** The verse's records don't fit the text passed in — it isn't the BSB's text for that verse. */
class InterlinearTextMismatch(verseKey: Int) :
    IllegalArgumentException("Original-language data for $verseKey doesn't match this text; it is aligned to the BSB.")

/**
 * Word-level Hebrew, Aramaic and Greek for the whole Bible, aligned to the bundled BSB, plus
 * STEPBible's Strong's lexicon. A port of `ScriptureAloneCore/Interlinear/InterlinearStore.swift`.
 *
 * **The alignment is to the BSB's text.** A word's English is a slice of the BSB verse, so
 * [words] must be given the BSB's text for the verse — the reader offers the original languages in
 * every translation, but always glosses with the BSB. Given another translation's text it throws
 * [InterlinearTextMismatch] rather than mis-highlighting.
 */
class InterlinearStore(private val db: SqlSource) {

    val attribution: InterlinearAttribution
    val statistics: InterlinearStatistics
    private val parsings: Map<Long, InterlinearParsing>
    private val chapterCache = LruCache<Long, List<String>>(4)
    private val lexiconCache = LruCache<Long, List<String>>(8)

    init {
        val meta = db.query("SELECT key, value FROM meta") { it.text(0) to it.text(1) }.toMap()
        fun m(key: String) = meta[key] ?: ""
        fun n(key: String) = meta[key]?.toIntOrNull() ?: 0
        attribution = InterlinearAttribution(
            m("bsb_attribution"), m("bsb_license"), m("bsb_license_url"), m("bsb_url"),
            m("stepbible_attribution"), m("stepbible_license"), m("stepbible_license_url"),
            m("stepbible_url"), m("stepbible_changes"),
        )
        statistics = InterlinearStatistics(
            m("version"), m("checked"), m("stepbible_commit"), n("words"), n("tagged_words"),
            n("strongs"), n("verses_aligned"), n("verses_total"),
        )
        parsings = db.query("SELECT id, code, description FROM parsings") {
            it.long(0) to InterlinearParsing(it.text(1), it.text(2))
        }.toMap()
    }

    /** Words per verse of a chapter; verses with none are absent. One range scan, nothing inflated. */
    fun wordCounts(book: Int, chapter: Int): Map<Int, Int> {
        val base = book * 1_000_000 + chapter * 1_000
        return db.query(
            "SELECT verse_key, count FROM verses WHERE verse_key BETWEEN ? AND ? AND count > 0",
            base, base + 999,
        ) { it.long(0).toInt() to it.long(1).toInt() }.toMap()
    }

    /** Whether one verse has any words. Nehemiah 7:68 is the only BSB verse with none. */
    fun hasWords(verseKey: Int): Boolean =
        (db.query("SELECT count FROM verses WHERE verse_key = ?", verseKey) { it.long(0) }.firstOrNull() ?: 0L) > 0

    /**
     * Every original-language word of one verse, in English (BSB) reading order. Superscription
     * words come first and are flagged. A verse with no data returns an empty list.
     *
     * @param verseText the **BSB's** text for the verse, exactly as stored — the very string the
     *   returned ranges index into.
     */
    fun words(verseKey: Int, verseText: String): List<InterlinearWord> {
        val records = records(verseKey)
        return records.mapIndexed { position, record ->
            val fields = record.split('\t')
            require(fields.size >= 10) { "malformed word record in $verseKey" }
            val start = fields[7].toIntOrNull() ?: -1
            val length = fields[8].toIntOrNull() ?: -1
            var english = fields[0]
            var range: IntRange? = null
            if (start >= 0 && length >= 0) {
                if (start + length > verseText.length) throw InterlinearTextMismatch(verseKey)
                english = verseText.substring(start, start + length)
                range = start until start + length
            }
            val strongs = fields[4]
            InterlinearWord(
                position = position,
                english = english,
                original = fields[1],
                transliteration = fields[2],
                parsing = parsings[fields[3].toLongOrNull() ?: 0L],
                strongs = strongs.ifEmpty { null },
                language = InterlinearLanguage.of(fields[5]),
                originalOrder = fields[6].toIntOrNull() ?: (position + 1),
                range = range,
                isSuperscription = ((fields[9].toIntOrNull() ?: 0) and 1) == 1,
            )
        }
    }

    private fun records(verseKey: Int): List<String> {
        data class Row(val chapterKey: Long, val first: Int, val count: Int)
        val row = db.query("SELECT chapter_key, first, count FROM verses WHERE verse_key = ?", verseKey) {
            Row(it.long(0), it.long(1).toInt(), it.long(2).toInt())
        }.firstOrNull() ?: return emptyList()
        if (row.count <= 0) return emptyList()
        val lines = chapter(row.chapterKey)
        check(row.first >= 0 && row.first + row.count <= lines.size) {
            "chapter ${row.chapterKey} is shorter than verse $verseKey claims"
        }
        return lines.subList(row.first, row.first + row.count)
    }

    private fun chapter(chapterKey: Long): List<String> = chapterCache.getOrPut(chapterKey) {
        val body = db.query("SELECT words FROM chapters WHERE chapter_key = ?", chapterKey) { it.blob(0) }
            .firstOrNull() ?: ByteArray(0)
        val text = StudyStore.inflate(body) ?: error("chapter $chapterKey does not inflate")
        text.split('\n')
    }

    /**
     * A Strong's number's lexicon entry. Accepts "H0430" as the records spell it and "H430"/"h430"
     * as people write it; an extended key's suffix is dropped, as the build collapsed them.
     */
    fun entry(strongs: String): LexiconEntry? {
        val number = normalize(strongs) ?: return null
        data class Row(val bucket: Long, val part: Int)
        val row = db.query("SELECT entry_id, part FROM lexicon WHERE strongs = ?", number) {
            Row(it.long(0), it.long(1).toInt())
        }.firstOrNull() ?: return null
        val bucket = lexiconBucket(row.bucket)
        check(row.part < bucket.size) { "lexicon bucket ${row.bucket} has no part ${row.part}" }
        // Senses are separated by U+001E, their five fields by tabs.
        val senses = bucket[row.part].split(SENSE_SEPARATOR).mapNotNull { sense ->
            val f = sense.split('\t')
            if (f.size < 5) null else LexiconSense(f[0], f[1], f[2], f[3], f[4])
        }
        return if (senses.isEmpty()) null else LexiconEntry(number, senses)
    }

    /** A bucket of 64 entries, separated by U+0000. */
    private fun lexiconBucket(id: Long): List<String> = lexiconCache.getOrPut(id) {
        val body = db.query("SELECT body FROM lexicon_text WHERE id = ?", id) { it.blob(0) }
            .firstOrNull() ?: ByteArray(0)
        val text = StudyStore.inflate(body) ?: error("lexicon bucket $id does not inflate")
        text.split(ENTRY_SEPARATOR)
    }

    /**
     * A handful of inflated blobs, evicted oldest-first. A reader moves through one chapter at a time
     * and a lexicon bucket holds 64 consecutive numbers, so a small cache turns a verse's worth of
     * taps into one inflation.
     */
    private class LruCache<K, V>(private val limit: Int) {
        private val map = LinkedHashMap<K, V>()

        @Synchronized
        fun getOrPut(key: K, compute: () -> V): V {
            map[key]?.let { return it }
            val value = compute()
            map[key] = value
            if (map.size > limit) map.remove(map.keys.first())
            return value
        }
    }

    companion object {
        private const val SENSE_SEPARATOR = ''
        private const val ENTRY_SEPARATOR = ' '

        /** "h430" and "H0430G" both mean the entry stored as "H0430". Null for anything else. */
        fun normalize(strongs: String): String? {
            var letter: Char? = null
            val digits = StringBuilder()
            for (c in strongs.trim()) {
                if (letter == null) {
                    if (c !in "HhGg") return null
                    letter = c.uppercaseChar()
                } else if (c.isDigit()) {
                    if (digits.length >= 5) return null
                    digits.append(c)
                } else if (c.isLetter()) {
                    break // an extended key's suffix: H0430G is stored under H0430
                } else {
                    return null
                }
            }
            val value = digits.toString().toIntOrNull() ?: return null
            return "${letter ?: return null}${value.toString().padStart(4, '0')}"
        }
    }
}
