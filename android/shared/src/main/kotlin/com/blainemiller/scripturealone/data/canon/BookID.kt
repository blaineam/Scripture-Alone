package com.blainemiller.scripturealone.data.canon

/**
 * The 66 books of the Protestant canon, ported from `ScriptureAloneCore/Canon.swift`.
 *
 * [number] is the canonical ordinal and matches the `book` column in every bundled database and the
 * leading digits of a verse key, so the order of these entries must never change. The aliases are the
 * lowercased, space-free spellings the passage parser accepts; the book's own name is always the first.
 * Keep this table in step with the Swift one — generated from it, not retyped.
 */
enum class BookID(
    val code: String,
    /** Always English — for stored text meant to be read anywhere (a database's `books` table, exports). */
    val englishName: String,
    /** The English abbreviation ("1 Cor"), whatever language books are being named in. */
    val englishAbbreviation: String,
    val chapterCount: Int,
    extraAliases: List<String>,
    val group: BookGroup,
) {
    GENESIS("GEN", "Genesis", "Gen", 50, listOf("gen", "ge", "gn"), BookGroup.LAW),
    EXODUS("EXO", "Exodus", "Exod", 40, listOf("exod", "exo", "ex"), BookGroup.LAW),
    LEVITICUS("LEV", "Leviticus", "Lev", 27, listOf("lev", "le", "lv"), BookGroup.LAW),
    NUMBERS("NUM", "Numbers", "Num", 36, listOf("num", "nu", "nm", "nb"), BookGroup.LAW),
    DEUTERONOMY("DEU", "Deuteronomy", "Deut", 34, listOf("deut", "deu", "dt"), BookGroup.LAW),
    JOSHUA("JOS", "Joshua", "Josh", 24, listOf("josh", "jos", "jsh"), BookGroup.HISTORY),
    JUDGES("JDG", "Judges", "Judg", 21, listOf("judg", "jdg", "jg", "jdgs"), BookGroup.HISTORY),
    RUTH("RUT", "Ruth", "Ruth", 4, listOf("rut", "ru", "rth"), BookGroup.HISTORY),
    FIRST_SAMUEL("1SA", "1 Samuel", "1 Sam", 31, listOf("1sam", "1sa", "1sm"), BookGroup.HISTORY),
    SECOND_SAMUEL("2SA", "2 Samuel", "2 Sam", 24, listOf("2sam", "2sa", "2sm"), BookGroup.HISTORY),
    FIRST_KINGS("1KI", "1 Kings", "1 Kgs", 22, listOf("1kgs", "1ki", "1kg", "1kin"), BookGroup.HISTORY),
    SECOND_KINGS("2KI", "2 Kings", "2 Kgs", 25, listOf("2kgs", "2ki", "2kg", "2kin"), BookGroup.HISTORY),
    FIRST_CHRONICLES("1CH", "1 Chronicles", "1 Chr", 29, listOf("1chr", "1ch", "1chron"), BookGroup.HISTORY),
    SECOND_CHRONICLES("2CH", "2 Chronicles", "2 Chr", 36, listOf("2chr", "2ch", "2chron"), BookGroup.HISTORY),
    EZRA("EZR", "Ezra", "Ezra", 10, listOf("ezr"), BookGroup.HISTORY),
    NEHEMIAH("NEH", "Nehemiah", "Neh", 13, listOf("neh", "ne"), BookGroup.HISTORY),
    ESTHER("EST", "Esther", "Esth", 10, listOf("esth", "est", "es"), BookGroup.HISTORY),
    JOB("JOB", "Job", "Job", 42, listOf("jb"), BookGroup.WISDOM),
    PSALMS("PSA", "Psalms", "Ps", 150, listOf("ps", "psa", "psalm", "pss", "psm", "pslm"), BookGroup.WISDOM),
    PROVERBS("PRO", "Proverbs", "Prov", 31, listOf("prov", "pro", "prv", "pr"), BookGroup.WISDOM),
    ECCLESIASTES("ECC", "Ecclesiastes", "Eccl", 12, listOf("eccl", "ecc", "ec", "eccles", "qoh"), BookGroup.WISDOM),
    SONG_OF_SOLOMON("SNG", "Song of Solomon", "Song", 8, listOf("song", "sng", "sos", "songofsongs", "canticles"), BookGroup.WISDOM),
    ISAIAH("ISA", "Isaiah", "Isa", 66, listOf("isa"), BookGroup.MAJOR_PROPHETS),
    JEREMIAH("JER", "Jeremiah", "Jer", 52, listOf("jer", "jr"), BookGroup.MAJOR_PROPHETS),
    LAMENTATIONS("LAM", "Lamentations", "Lam", 5, listOf("lam"), BookGroup.MAJOR_PROPHETS),
    EZEKIEL("EZK", "Ezekiel", "Ezek", 48, listOf("ezek", "ezk", "eze"), BookGroup.MAJOR_PROPHETS),
    DANIEL("DAN", "Daniel", "Dan", 12, listOf("dan", "dn"), BookGroup.MAJOR_PROPHETS),
    HOSEA("HOS", "Hosea", "Hos", 14, listOf("hos"), BookGroup.MINOR_PROPHETS),
    JOEL("JOL", "Joel", "Joel", 3, listOf("jol", "jl"), BookGroup.MINOR_PROPHETS),
    AMOS("AMO", "Amos", "Amos", 9, listOf("amo"), BookGroup.MINOR_PROPHETS),
    OBADIAH("OBA", "Obadiah", "Obad", 1, listOf("obad", "oba", "ob"), BookGroup.MINOR_PROPHETS),
    JONAH("JON", "Jonah", "Jonah", 4, listOf("jnh"), BookGroup.MINOR_PROPHETS),
    MICAH("MIC", "Micah", "Mic", 7, listOf("mic", "mc"), BookGroup.MINOR_PROPHETS),
    NAHUM("NAM", "Nahum", "Nah", 3, listOf("nah", "nam"), BookGroup.MINOR_PROPHETS),
    HABAKKUK("HAB", "Habakkuk", "Hab", 3, listOf("hab", "hb"), BookGroup.MINOR_PROPHETS),
    ZEPHANIAH("ZEP", "Zephaniah", "Zeph", 3, listOf("zeph", "zep", "zp"), BookGroup.MINOR_PROPHETS),
    HAGGAI("HAG", "Haggai", "Hag", 2, listOf("hag", "hg"), BookGroup.MINOR_PROPHETS),
    ZECHARIAH("ZEC", "Zechariah", "Zech", 14, listOf("zech", "zec", "zc"), BookGroup.MINOR_PROPHETS),
    MALACHI("MAL", "Malachi", "Mal", 4, listOf("mal", "ml"), BookGroup.MINOR_PROPHETS),
    MATTHEW("MAT", "Matthew", "Matt", 28, listOf("matt", "mat", "mt"), BookGroup.GOSPELS),
    MARK("MRK", "Mark", "Mark", 16, listOf("mrk", "mk", "mr"), BookGroup.GOSPELS),
    LUKE("LUK", "Luke", "Luke", 24, listOf("luk", "lk"), BookGroup.GOSPELS),
    JOHN("JHN", "John", "John", 21, listOf("jhn", "jn", "joh"), BookGroup.GOSPELS),
    ACTS("ACT", "Acts", "Acts", 28, listOf("act", "ac"), BookGroup.HISTORY),
    ROMANS("ROM", "Romans", "Rom", 16, listOf("rom", "ro", "rm"), BookGroup.PAUL),
    FIRST_CORINTHIANS("1CO", "1 Corinthians", "1 Cor", 16, listOf("1cor", "1co"), BookGroup.PAUL),
    SECOND_CORINTHIANS("2CO", "2 Corinthians", "2 Cor", 13, listOf("2cor", "2co"), BookGroup.PAUL),
    GALATIANS("GAL", "Galatians", "Gal", 6, listOf("gal", "ga"), BookGroup.PAUL),
    EPHESIANS("EPH", "Ephesians", "Eph", 6, listOf("eph", "ephes"), BookGroup.PAUL),
    PHILIPPIANS("PHP", "Philippians", "Phil", 4, listOf("phil", "php", "pp"), BookGroup.PAUL),
    COLOSSIANS("COL", "Colossians", "Col", 4, listOf("col"), BookGroup.PAUL),
    FIRST_THESSALONIANS("1TH", "1 Thessalonians", "1 Thess", 5, listOf("1thess", "1th", "1thes"), BookGroup.PAUL),
    SECOND_THESSALONIANS("2TH", "2 Thessalonians", "2 Thess", 3, listOf("2thess", "2th", "2thes"), BookGroup.PAUL),
    FIRST_TIMOTHY("1TI", "1 Timothy", "1 Tim", 6, listOf("1tim", "1ti", "1tm"), BookGroup.PAUL),
    SECOND_TIMOTHY("2TI", "2 Timothy", "2 Tim", 4, listOf("2tim", "2ti", "2tm"), BookGroup.PAUL),
    TITUS("TIT", "Titus", "Titus", 3, listOf("tit"), BookGroup.PAUL),
    PHILEMON("PHM", "Philemon", "Phlm", 1, listOf("phlm", "phm", "philem", "pm"), BookGroup.PAUL),
    HEBREWS("HEB", "Hebrews", "Heb", 13, listOf("heb"), BookGroup.GENERAL),
    JAMES("JAS", "James", "Jas", 5, listOf("jas", "jm", "jam"), BookGroup.GENERAL),
    FIRST_PETER("1PE", "1 Peter", "1 Pet", 5, listOf("1pet", "1pe", "1pt"), BookGroup.GENERAL),
    SECOND_PETER("2PE", "2 Peter", "2 Pet", 3, listOf("2pet", "2pe", "2pt"), BookGroup.GENERAL),
    FIRST_JOHN("1JN", "1 John", "1 John", 5, listOf("1jn", "1jo", "1jhn", "1joh"), BookGroup.GENERAL),
    SECOND_JOHN("2JN", "2 John", "2 John", 1, listOf("2jn", "2jo", "2jhn", "2joh"), BookGroup.GENERAL),
    THIRD_JOHN("3JN", "3 John", "3 John", 1, listOf("3jn", "3jo", "3jhn", "3joh"), BookGroup.GENERAL),
    JUDE("JUD", "Jude", "Jude", 1, listOf("jud", "jd"), BookGroup.GENERAL),
    REVELATION("REV", "Revelation", "Rev", 22, listOf("rev", "rv", "revelations", "apocalypse"), BookGroup.PROPHECY);

    /**
     * The book's name in the language of the Bible being read ([BookNames]) — "Jean", "约翰福音" —
     * English by default. `BookID.name` on iOS.
     */
    val displayName: String get() = BookNames.name(this) ?: englishName

    /** "Jn", "约" in the language books are named in; English by default. */
    val abbreviation: String get() = BookNames.abbreviation(this) ?: englishAbbreviation

    /** Canonical ordinal, 1–66. */
    val number: Int get() = ordinal + 1

    /** The book's normalized name first, then every other accepted spelling. */
    // Computed with the top-level function, not the companion's: a companion object is not yet
    // initialised while the enum's own entries are being constructed.
    val aliases: List<String> = normalizeBookToken(englishName).let { key -> listOf(key) + extraAliases.filter { it != key } }

    val isNewTestament: Boolean get() = number >= MATTHEW.number
    val isSingleChapter: Boolean get() = chapterCount == 1

    companion object {
        fun of(number: Int): BookID? = entries.getOrNull(number - 1)

        /** Lowercases, drops periods and whitespace: "1 Cor." → "1cor". */
        fun normalize(text: String): String = normalizeBookToken(text)
    }
}

private fun normalizeBookToken(text: String): String = text.lowercase().filter { !it.isWhitespace() && it != '.' }

enum class BookGroup(val title: String) {
    LAW("Law"),
    HISTORY("History"),
    WISDOM("Wisdom & Poetry"),
    MAJOR_PROPHETS("Major Prophets"),
    MINOR_PROPHETS("Minor Prophets"),
    GOSPELS("Gospels"),
    PAUL("Paul’s Letters"),
    GENERAL("General Letters"),
    PROPHECY("Prophecy"),
}
