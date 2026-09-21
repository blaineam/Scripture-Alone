package com.blainemiller.scripturealone.data.catalog

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** One translation offered by eBible.org. Ported from `ScriptureAloneCore/Catalog/EBibleCatalog.swift`. */
data class CatalogTranslation(
    /** eBible's own id, which is also the download filename ("engwebp"). */
    val id: String,
    val languageCode: String,
    /** "English", as the language calls itself. */
    val languageName: String,
    /** "English" in English — the two differ for most of the catalogue. */
    val languageNameInEnglish: String,
    val title: String,
    val shortTitle: String,
    val copyright: String,
    /** eBible's own flag. False means the publisher allows eBible to show it but not to pass it on. */
    val isRedistributable: Boolean,
    val otBooks: Int,
    val ntBooks: Int,
    val otVerses: Int,
    val ntVerses: Int,
    val textDirection: String,
    /**
     * "Latin", "Han", "Arabic" — eBible's own script name, which is how the two Chinese written forms
     * are told apart.
     */
    val script: String,
) {
    val bookCount: Int get() = otBooks + ntBooks

    /**
     * A whole Bible: all 39 Old Testament books and all 27 New Testament books.
     *
     * The catalogue is mostly New Testaments and portions — 1,291 entries collapse to 214 once this is
     * required. The app offers only complete Bibles, so nobody downloads what turns out to be four
     * gospels.
     */
    val isCompleteCanon: Boolean get() = otBooks >= 39 && ntBooks >= 27
    val verseCount: Int get() = otVerses + ntVerses
    val isRightToLeft: Boolean get() = textDirection.lowercase() == "rtl"

    /** Whole-Bible translations say so; the rest are mostly New Testaments. */
    val scope: String
        get() = when {
            otBooks >= 39 && ntBooks >= 27 -> "Complete Bible"
            otBooks == 0 && ntBooks >= 1 -> "New Testament"
            otBooks >= 1 && ntBooks == 0 -> "Old Testament"
            else -> "$bookCount book${if (bookCount == 1) "" else "s"}"
        }

    /** Where eBible hosts the USFM zip. A string, as Swift's `URL.absoluteString`. */
    val downloadURL: String get() = "https://ebible.org/Scriptures/${id}_usfm.zip"
}

/**
 * Reads eBible.org's published catalogue of freely-licensed translations.
 *
 * Nothing here runs on its own: the app has no background fetches and makes no network request until
 * someone taps to look. The catalogue is a single CSV eBible publishes for this purpose.
 */
object EBibleCatalog {
    const val CATALOG_URL = "https://ebible.org/Scriptures/translations.csv"

    sealed class Failure(message: String) : Exception(message) {
        class Http(val code: Int) : Failure("eBible.org couldn't be reached (HTTP $code). Try again later.")
        class Malformed(val what: String) : Failure("eBible.org's catalogue couldn't be read ($what).")
    }

    /**
     * Downloads and parses the catalogue. Blocking — call it off the main thread. The Swift version is
     * `async` over URLSession; the timeout (30 s) and revalidate-the-cache policy are the same.
     */
    @Throws(IOException::class, Failure::class)
    fun fetch(): List<CatalogTranslation> {
        val connection = URL(CATALOG_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            // `.reloadRevalidatingCacheData`: a cached copy may be used only once the server agrees.
            connection.useCaches = true
            connection.setRequestProperty("Cache-Control", "no-cache")
            val status = connection.responseCode
            if (status !in 200 until 300) throw Failure.Http(status)
            val data = connection.inputStream.use { it.readBytes() }
            // UTF-8, falling back to Latin-1, which decodes any bytes at all — so "not text" cannot
            // actually happen, in Swift or here.
            val text = decodeStrictUtf8(data) ?: String(data, Charsets.ISO_8859_1)
            return parse(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private val requiredColumns = listOf("translationId", "languageCode", "title", "Copyright", "Redistributable", "downloadable")

    /**
     * Parses the published CSV. Columns are addressed by header name, not position: eBible has added
     * columns before, and a positional reader would silently start reporting the wrong field rather
     * than failing.
     */
    @Throws(Failure::class)
    fun parse(csv: String): List<CatalogTranslation> {
        val rows = CSV.rows(csv).iterator()
        if (!rows.hasNext()) throw Failure.Malformed("empty")
        val header = rows.next()
        // Swift builds this with `Dictionary(uniqueKeysWithValues:)`, which traps on a repeated column
        // name. A catalogue we don't control must not crash the app, so here it is refused instead.
        val index = HashMap<String, Int>()
        for ((i, name) in header.withIndex()) {
            if (index.put(name, i) != null) throw Failure.Malformed("a repeated $name column")
        }
        for (required in requiredColumns) {
            if (required !in index) throw Failure.Malformed("no $required column")
        }
        fun field(row: List<String>, name: String): String {
            val i = index[name] ?: return ""
            return if (i < row.size) row[i] else ""
        }
        fun number(row: List<String>, name: String): Int = field(row, name).toIntOrNull() ?: 0
        fun flag(row: List<String>, name: String): Boolean = field(row, name).lowercase() == "true"

        val out = mutableListOf<CatalogTranslation>()
        while (rows.hasNext()) {
            val row = rows.next()
            if (row.isEmpty() || field(row, "translationId").isEmpty()) continue
            // Only what eBible says may be passed on, and only what it actually hosts a file for.
            if (!flag(row, "downloadable") || !flag(row, "Redistributable")) continue
            val entry = CatalogTranslation(
                id = field(row, "translationId"),
                languageCode = field(row, "languageCode"),
                languageName = field(row, "languageName"),
                languageNameInEnglish = field(row, "languageNameInEnglish"),
                title = field(row, "title"),
                shortTitle = field(row, "shortTitle"),
                copyright = field(row, "Copyright"),
                isRedistributable = true,
                otBooks = number(row, "OTbooks"), ntBooks = number(row, "NTbooks"),
                otVerses = number(row, "OTverses"), ntVerses = number(row, "NTverses"),
                textDirection = field(row, "textDirection"),
                script = field(row, "script"),
            )
            // A row with no verses at all is a placeholder, not a translation.
            if (entry.verseCount <= 0) continue
            out += entry
        }
        return out
    }
}

/**
 * A CSV reader that handles quoted fields, embedded commas and doubled quotes — eBible's copyright
 * column contains all three.
 */
internal object CSV {
    fun rows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false

        fun endField() { row += field.toString(); field.setLength(0) }
        fun endRow() {
            endField()
            if (!(row.size == 1 && row[0].isEmpty())) rows += row
            row = mutableListOf()
        }

        // Walked by code point. Swift walks grapheme clusters, where "\r\n" is one Character; here CR
        // and LF arrive separately, the CR ends the row and the LF then ends an empty one, which
        // `endRow` discards — the same rows either way.
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            i += Character.charCount(c)
            if (quoted) {
                if (c == '"'.code) {
                    if (i < text.length && text[i] == '"') { field.append('"'); i++ } else quoted = false
                } else {
                    field.appendCodePoint(c)
                }
                continue
            }
            when (c) {
                '"'.code -> quoted = true
                ','.code -> endField()
                '\n'.code, '\r'.code -> endRow()
                else -> field.appendCodePoint(c)
            }
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
