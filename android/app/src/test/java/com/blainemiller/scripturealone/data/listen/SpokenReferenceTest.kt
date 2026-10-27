package com.blainemiller.scripturealone.data.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenReferenceTest {
    @Test fun spokenFormsBecomeReferences() {
        val cases = listOf(
            "John 3 16" to "john 3:16", "John 3 colon 16" to "john 3:16", "john chapter 3 verse 16" to "john 3:16",
            "1 Corinthians 7 4" to "1 corinthians 7:4", "John 3 16 through 21" to "john 3:16-21", "John 3 16 21" to "john 3:16-21",
            "Romans 8:28 to 30" to "romans 8:28-30", "John 3.16" to "john 3:16", "Psalm 23" to "psalm 23",
            "Romans 8" to "romans 8", "John 3-5" to "john 3-5", "1 John 1 9" to "1 john 1:9",
        )
        for ((heard, expected) in cases) assertEquals(heard, expected, SpokenReference.normalize(heard))
    }

    @Test fun todayIsAskedForByName() {
        assertTrue(SpokenReference.asksForToday("today's reading"))
        assertTrue(SpokenReference.asksForToday("my reading plan"))
        assertFalse(SpokenReference.asksForToday("Psalm 23"))
    }

    /** What the assistant hands over, as the player reads it: [SpokenReference.plan] with the real reference parser. */
    private fun read(vararg heard: String): String = when (val r = SpokenReference.plan(heard.toList())) {
        SpokenReference.Request.Today -> "today"
        SpokenReference.Request.Resume -> "resume"
        SpokenReference.Request.Unknown -> "unknown"
        is SpokenReference.Request.Read -> r.passages.joinToString("; ") { it.display }
    }

    @Test fun aVerbAndTheAppsNameAreNotPartOfTheReference() {
        val cases = listOf(
            "read John chapter 1 in Scripture Alone" to "John 1",
            "Read John 1" to "John 1",
            "play John 3:16" to "John 3:16",
            "play John 3:16 on Scripture Alone" to "John 3:16",
            "hey Google, read John chapter 1 with Scripture Alone" to "John 1",
            "can you please read me John 3 16 out loud" to "John 3:16",
            "listen to Romans 8 28 through 30 using the Scripture Alone app" to "Romans 8:28–30",
            "read the gospel of John chapter 3 verse 16 through 21" to "John 3:16–21",
            "open Psalm 23 in scripture alone" to "Psalms 23",
        )
        for ((heard, expected) in cases) assertEquals(heard, expected, read(heard))
    }

    @Test fun numbersSaidAsWordsAreNumbers() {
        val cases = listOf(
            "John chapter one" to "John 1", "read Psalm twenty three" to "Psalms 23", "read Psalm twenty-three in Scripture Alone" to "Psalms 23",
            "psalm a hundred and fifty" to "Psalms 150", "Psalm one hundred nineteen" to "Psalms 119",
            "John three sixteen" to "John 3:16", "Romans eight twenty eight through thirty" to "Romans 8:28–30",
            "play John chapter three verse sixteen" to "John 3:16", "Genesis fifty" to "Genesis 50",
        )
        for ((heard, expected) in cases) assertEquals(heard, expected, read(heard))
    }

    @Test fun numberedBooksAndOrdinals() {
        val cases = listOf(
            "play first John chapter two" to "1 John 2", "read 1 John 1 9" to "1 John 1:9", "read one John chapter three" to "1 John 3",
            "second Timothy 3 16" to "2 Timothy 3:16", "the first book of Samuel chapter 3" to "1 Samuel 3",
            "read the second letter to the Corinthians chapter 5" to "2 Corinthians 5", "third John" to "3 John",
        )
        for ((heard, expected) in cases) assertEquals(heard, expected, read(heard))
    }

    @Test fun booksAsTheyAreSaid() {
        val cases = listOf(
            "read Song of Songs chapter 2" to "Song of Solomon 2", "read Song of Solomon 2" to "Song of Solomon 2",
            "read Psalms 23" to "Psalms 23", "read the book of Revelation chapter 22" to "Revelation 22",
            "read the gospel according to Matthew chapter 5" to "Matthew 5", "chapter 3 of John" to "John 3",
            "read chapter 3 in John" to "John 3",
        )
        for ((heard, expected) in cases) assertEquals(heard, expected, read(heard))
    }

    @Test fun aBookWithNoChapterIsTheWholeBook() {
        assertEquals("Romans 1–16", read("read the book of Romans"))
        assertEquals("Romans 1–16", read("read Romans in Scripture Alone"))
        assertEquals("Jude", read("read Jude"))
        // A chapter is only that chapter.
        assertEquals("Romans 8", read("read Romans 8"))
    }

    @Test fun carryingOnAndNothingInParticularResume() {
        for (heard in listOf("", "continue", "continue reading", "resume", "resume reading in Scripture Alone", "keep reading",
            "pick up where I left off", "read something", "read something in Scripture Alone", "play something from the Bible", "read a chapter")) {
            assertEquals(heard, "resume", read(heard))
        }
        assertEquals("no phrases at all", SpokenReference.Request.Resume, SpokenReference.plan(emptyList()))
        // …but a passage named in the same breath is that passage.
        assertEquals("John 3", read("continue reading John 3"))
    }

    @Test fun todayIsToday() {
        assertEquals("today", read("read today's reading in Scripture Alone"))
        assertEquals("today", read("play my daily reading"))
    }

    @Test fun whatIsNotScriptureIsUnknown() {
        assertEquals("unknown", read("play some jazz"))
        assertEquals("unknown", read("read the news"))
    }

    @Test fun theFirstPhraseThatIsAPassageWins() {
        // The assistant's search query is empty or not a passage, its structured title is.
        assertEquals("John 1", read("scripture alone", "John chapter 1"))
        assertEquals("John 1", read("", "John chapter 1"))
        assertEquals("Psalms 23", read("play something", "Psalm 23"))
    }

    @Test fun numberWordsToDigits() {
        val cases = listOf(
            "john one" to "john 1", "twenty three" to "23", "twenty-three" to "twenty-three", "one hundred fifty" to "150",
            "a hundred and fifty" to "150", "two hundred and five" to "205", "one hundred" to "100", "ninety nine" to "99",
            "three sixteen" to "3 16", "nineteen" to "19", "someone" to "someone", "a verse" to "a verse",
        )
        for ((words, expected) in cases) assertEquals(words, expected, SpokenReference.numbersAsDigits(words))
    }
}
