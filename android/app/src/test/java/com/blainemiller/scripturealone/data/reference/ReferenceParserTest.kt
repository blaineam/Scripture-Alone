package com.blainemiller.scripturealone.data.reference

import com.blainemiller.scripturealone.data.canon.BookID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ported case for case from `ScriptureAloneCoreTests/ReferenceParserTests.swift`, so the two apps
 * accept exactly the same input. Add a case to both or to neither.
 */
class ReferenceParserTest {

    private val cases = listOf(
        "jn 3 16" to "John 3:16",
        "John 3:16" to "John 3:16",
        "jn3:16" to "John 3:16",
        "Jn. 3.16" to "John 3:16",
        "rom 8:28-39" to "Romans 8:28–39",
        "Rom 8:28–39" to "Romans 8:28–39",
        "1co13" to "1 Corinthians 13",
        "1 cor 13:4-7" to "1 Corinthians 13:4–7",
        "I Cor 13" to "1 Corinthians 13",
        "First John 1:9" to "1 John 1:9",
        "ii tim 3:16" to "2 Timothy 3:16",
        "ps 23" to "Psalms 23",
        "psalm 119:105" to "Psalms 119:105",
        "ps 23-24" to "Psalms 23–24",
        "gen 1:1-2:3" to "Genesis 1:1–2:3",
        "Jude 3" to "Jude 3",
        "jude 3-5" to "Jude 3–5",
        "jude" to "Jude",
        "song of solomon 2:4" to "Song of Solomon 2:4",
        "phil 4:13" to "Philippians 4:13",
        "eph" to "Ephesians 1",
        "rev 22:20" to "Revelation 22:20",
    )

    @Test fun parses() {
        val failures = cases.mapNotNull { (input, expected) ->
            val got = ReferenceParser.parse(input)?.display
            if (got == expected) null else "\"$input\" → $got, expected $expected"
        }
        assertEquals("parse failures:\n" + failures.joinToString("\n"), emptyList<String>(), failures)
    }

    @Test fun partialTypingStillResolves() {
        assertEquals("Romans 8:28", ReferenceParser.parse("rom 8:28-")?.display)
        assertEquals(BookID.JOHN, ReferenceParser.parse("jo")?.book)
        assertEquals(BookID.PHILIPPIANS, ReferenceParser.parse("ph")?.book)
        assertEquals(BookID.FIRST_CORINTHIANS, ReferenceParser.parse("1c")?.book)
    }

    @Test fun rejectsNonsense() {
        assertNull(ReferenceParser.parse(""))
        assertNull(ReferenceParser.parse("zzz 3"))
        assertNull(ReferenceParser.parse("3:16"))
    }

    @Test fun bookSuggestionsRankExactAbbreviationsFirst() {
        assertEquals(BookID.JUDE, ReferenceParser.books(matching = "jud").first())
        assertEquals(BookID.JUDGES, ReferenceParser.books(matching = "judg").first())
        assertEquals(BookID.JOHN, ReferenceParser.books(matching = "j").first())
        assertEquals(BookID.JOB, ReferenceParser.books(matching = "job").first())
    }

    @Test fun parsesLists() {
        assertEquals(
            listOf("Ephesians 2:1–10", "Romans 3:23", "Romans 6:23", "Romans 6:25"),
            ReferenceParser.parseList("Eph 2:1-10; Rom 3:23, 6:23, 25").map { it.display },
        )
        assertEquals(listOf("Psalms 23", "Psalms 24"), ReferenceParser.parseList("Ps 23, 24").map { it.display })
    }

    /** Swift checks this through `VerseRange.display` ("Psalms 117:1–2"); here, through the keys. */
    @Test fun wholeChapterResolvesAgainstVerseCount() {
        val passage = ReferenceParser.parse("ps 117")!!
        assertEquals(19_117_001 to 19_117_002, passage.range { _, _ -> 2 })
    }

    /** Guards the generated canon table: ordinals are the database's book numbers. */
    @Test fun canonMatchesTheDatabaseNumbering() {
        assertEquals(66, BookID.entries.size)
        assertEquals(1, BookID.GENESIS.number)
        assertEquals(19, BookID.PSALMS.number)
        assertEquals(43, BookID.JOHN.number)
        assertEquals(66, BookID.REVELATION.number)
        assertEquals(1189, BookID.entries.sumOf { it.chapterCount })
        assertEquals("songofsolomon", BookID.SONG_OF_SOLOMON.aliases.first())
    }
}
