package com.blainemiller.scripturealone.data.importer

/**
 * One number for whether an import is fit to read, from the verses themselves. Ported from
 * `ImportQuality` in `Import/ImportCoverageReport.swift`.
 *
 * Every format and every file is judged the same way. Half the score is continuity — chapters whose
 * verses run in order without gaps, and no chapter missing between two that arrived — and half is
 * clean text: verses of a plausible length, free of the debris a bad read leaves (soft hyphens,
 * replacement characters, doubled spaces, words run together across a lost space). A file that holds
 * only part of the Bible isn't marked down for that: completeness is [ImportCoverageReport]'s to say,
 * and a New Testament on its own is a perfectly good import.
 */
class ImportQuality internal constructor(bible: ExtractedBible, books: List<ImportCoverageReport.BookCoverage>) {
    /** 0–100. */
    val score: Int

    /** Share of chapters that are whole and in order. */
    val continuity: Double

    /** Share of verses that look like clean text. */
    val cleanliness: Double
    val verseCount: Int

    val isAcceptable: Boolean get() = verseCount < JUDGED_FROM || score >= MINIMUM

    init {
        var chapters = 0
        var broken = 0
        for (book in books) {
            chapters += book.chaptersFound
            broken += book.chaptersWithGaps.size
            // Chapters missing between the first and last that arrived: a hole, not a portion.
            val found = bible.chapterOrder.filter { it.book == book.book }.map { it.chapter }
            val first = found.minOrNull()
            val last = found.maxOrNull()
            if (first != null && last != null) {
                val present = found.toSet()
                val holes = (first..last).count { it !in present }
                chapters += holes
                broken += holes
            }
        }
        continuity = if (chapters == 0) 0.0 else (chapters - broken).toDouble() / chapters
        val verses = bible.verses.values
        val clean = verses.count { looksClean(it.text) }
        cleanliness = if (verses.isEmpty()) 0.0 else clean.toDouble() / verses.size
        verseCount = verses.size
        // Swift's `rounded()` rounds half away from zero; `Math.round` agrees for these non-negative values.
        score = Math.round(continuity * 50 + cleanliness * 50).toInt()
    }

    companion object {
        /** Below this, an import is refused rather than stored. */
        const val MINIMUM = 80

        /**
         * Too few verses to judge by share: a short excerpt missing a verse would score badly for reasons
         * that say nothing about the file. Its gaps are still listed by the report.
         */
        const val JUDGED_FROM = 200

        // "gavehis" can't be seen, but "earth.The" and "wordThe" can.
        private val runTogether = Regex("""\p{Ll}[.,;:!?]\p{Lu}\p{Ll}|\p{Ll}{2}\p{Lu}\p{Ll}{2}""")

        fun looksClean(text: String): Boolean {
            if (text.isEmpty() || SwiftText.characterCount(text) > 1500) return false
            if (text.contains('\u00AD') || text.contains('\uFFFD') || text.contains("  ")) return false
            var index = 0
            while (index < text.length) {
                val cp = text.codePointAt(index)
                if (Character.getType(cp) == Character.CONTROL.toInt()) return false
                index += Character.charCount(cp)
            }
            return !runTogether.containsMatchIn(text)
        }
    }
}
