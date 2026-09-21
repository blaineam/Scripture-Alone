package com.blainemiller.scripturealone.data.online

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.layout.ChapterLayout
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors

/**
 * The on-demand chapter cache. One test per test in `OnlineChapterCacheTests.swift`; the reads go
 * through [CachedStore], which opens the file the way `BibleStore` does. Fixture text is invented —
 * the ESV never appears in this repository.
 */
class OnlineChapterCacheTest {

    @get:Rule val temporary = TemporaryFolder()

    private val driver = JdbcCacheDriver()

    private fun directory(): File = temporary.newFolder()

    /** A cache with a small ceiling, so eviction can be exercised with small fixtures. */
    private fun cache(limit: Int = 10, translation: OnlineTranslation = OnlineTranslation.ESV) =
        OnlineChapterCache(File(directory(), "${translation.id}.sqlite"), translation, driver, limit)

    private fun chapter(book: BookID, chapter: Int) = ChapterRef(book.number, chapter)
    private fun ref(book: BookID, chapter: Int, verse: Int) = VerseRef(book.number, chapter, verse)
    private fun range(from: VerseRef, to: VerseRef = from) = VerseRange(from, to)

    private fun verses(chapter: ChapterRef, numbers: IntRange, text: (Int) -> String = { "Invented verse $it." }) =
        numbers.map { VerseText(VerseRef(chapter.book, chapter.chapter, it), text(it)) }

    private fun <T> store(cache: OnlineChapterCache, body: (CachedStore) -> T): T = CachedStore(cache.file).use(body)

    // ---- Round trip ----

    @Test fun storesAChapterTheReaderCanOpen() {
        val cache = cache()
        val write = cache.store(verses(chapter(BookID.JOHN, 3), 1..4), chapter(BookID.JOHN, 3))
        assertEquals(4, write.versesStored)
        assertEquals(4, write.cachedVerses)
        assertTrue(write.evicted.isEmpty())
        assertFalse(write.exceedsLimit)

        store(cache) { store ->
            assertEquals("ESV", store.id)
            assertEquals("ESV", store.abbreviation)
            // The publisher's required notice has to be there for the reader to print.
            assertEquals(EsvTerms.REQUIRED_COPYRIGHT, store.copyright)
            assertTrue(store.contains(chapter(BookID.JOHN, 3)))
            assertEquals(4, store.verseCount(chapter(BookID.JOHN, 3)))

            val rows = store.verses(range(ref(BookID.JOHN, 3, 1), ref(BookID.JOHN, 3, 4)))
            assertEquals((1..4).map { "Invented verse $it." }, rows.map { it.text })
            // The same verse key the bundled stores use, so highlights and notes line up.
            assertEquals(43_003_001, rows.firstOrNull()?.ref?.key)
        }
    }

    @Test fun layoutIsOneNumberedProseParagraph() {
        val cache = cache()
        cache.store(verses(chapter(BookID.JOHN, 3), 1..3), chapter(BookID.JOHN, 3))
        val layout = store(cache) { it.layout(chapter(BookID.JOHN, 3)) }
        assertEquals(1, layout.blocks.size)
        val block = layout.blocks.first()
        assertEquals(ChapterLayout.Kind.PARAGRAPH, block.kind)
        assertEquals(listOf(1, 2, 3), block.fragments.map { it.verse })
        assertTrue(block.fragments.none { !it.numbered })
        assertEquals("Invented verse 1.", block.fragments.firstOrNull()?.text)
        // Matches what the in-memory equivalent produces.
        val prose = ChapterLayout.prose(verses(chapter(BookID.JOHN, 3), 1..3).map { it.ref.verse to it.text })
        assertEquals(block.fragments.map { it.verse }, prose.blocks.firstOrNull()?.fragments?.map { it.verse })
        assertEquals(block.kind, prose.blocks.firstOrNull()?.kind)
    }

    @Test fun theFullTextIndexFindsACachedVerse() {
        val cache = cache()
        cache.store(listOf(
            VerseText(ref(BookID.JOHN, 3, 16), "A sentence about a shepherd."),
            VerseText(ref(BookID.JOHN, 3, 17), "A sentence about a gate."),
        ), chapter(BookID.JOHN, 3))
        store(cache) { store ->
            assertEquals(listOf(ref(BookID.JOHN, 3, 16)), store.search("shepherd"))
            assertEquals(listOf(ref(BookID.JOHN, 3, 17)), store.search("gate"))
            assertTrue(store.search("camel").isEmpty())
        }
    }

    // ---- Replacing ----

    @Test fun replacingAChapterLeavesNoStaleVerses() {
        val cache = cache()
        val chapter = chapter(BookID.JOHN, 3)
        cache.store(verses(chapter, 1..6) { "First fetch $it." }, chapter)
        val write = cache.store(verses(chapter, 1..2) { "Second fetch $it." }, chapter)

        assertEquals(2, write.versesStored)
        assertEquals(2, write.cachedVerses) // the four extra rows are gone, not merged
        assertTrue(write.evicted.isEmpty())
        store(cache) { store ->
            val rows = store.verses(range(ref(BookID.JOHN, 3, 1), ref(BookID.JOHN, 3, 6)))
            assertEquals(listOf("Second fetch 1.", "Second fetch 2."), rows.map { it.text })
            assertEquals(2, store.verseCount(chapter))
            assertEquals(listOf(chapter), cache.cachedChapters())
            // The index has to forget them too.
            assertTrue(store.search("First").isEmpty())
        }
    }

    // ---- The ceiling ----

    @Test fun evictsLeastRecentlyReadUntilTheWriteFits() {
        val cache = cache(limit = 10)
        cache.store(verses(chapter(BookID.GENESIS, 1), 1..4), chapter(BookID.GENESIS, 1))
        cache.store(verses(chapter(BookID.GENESIS, 2), 1..4), chapter(BookID.GENESIS, 2))
        assertEquals(8, cache.cachedVerseCount())

        val write = cache.store(verses(chapter(BookID.GENESIS, 3), 1..4), chapter(BookID.GENESIS, 3))
        assertEquals(listOf(chapter(BookID.GENESIS, 1)), write.evicted)
        assertEquals(8, write.cachedVerses)
        assertTrue(cache.cachedVerseCount() <= 10)
        assertFalse(cache.contains(chapter(BookID.GENESIS, 1)))
        assertTrue(cache.contains(chapter(BookID.GENESIS, 2)))
        assertTrue(cache.contains(chapter(BookID.GENESIS, 3)))
        // Most recently read first.
        assertEquals(listOf(chapter(BookID.GENESIS, 3), chapter(BookID.GENESIS, 2)), cache.cachedChapters())

        store(cache) { store ->
            assertFalse(store.contains(chapter(BookID.GENESIS, 1)))
            assertTrue(store.verses(range(ref(BookID.GENESIS, 1, 1), ref(BookID.GENESIS, 1, 4))).isEmpty())
        }
    }

    @Test fun evictsAsManyChaptersAsItTakes() {
        val cache = cache(limit = 10)
        for (n in 1..5) cache.store(verses(chapter(BookID.GENESIS, n), 1..2), chapter(BookID.GENESIS, n))
        assertEquals(10, cache.cachedVerseCount())
        val write = cache.store(verses(chapter(BookID.GENESIS, 6), 1..6), chapter(BookID.GENESIS, 6))
        // Six incoming verses need three of the two-verse chapters gone, oldest first.
        assertEquals(listOf(chapter(BookID.GENESIS, 1), chapter(BookID.GENESIS, 2), chapter(BookID.GENESIS, 3)), write.evicted)
        assertEquals(10, write.cachedVerses)
    }

    @Test fun markReadChangesWhatGetsEvicted() {
        val cache = cache(limit = 10)
        cache.store(verses(chapter(BookID.GENESIS, 1), 1..4), chapter(BookID.GENESIS, 1))
        cache.store(verses(chapter(BookID.GENESIS, 2), 1..4), chapter(BookID.GENESIS, 2))
        // The reader goes back to Genesis 1, so Genesis 2 is now the stale one.
        cache.markRead(chapter(BookID.GENESIS, 1))
        assertEquals(listOf(chapter(BookID.GENESIS, 1), chapter(BookID.GENESIS, 2)), cache.cachedChapters())

        val write = cache.store(verses(chapter(BookID.GENESIS, 3), 1..4), chapter(BookID.GENESIS, 3))
        assertEquals(listOf(chapter(BookID.GENESIS, 2)), write.evicted)
        assertTrue(cache.contains(chapter(BookID.GENESIS, 1)))
        assertFalse(cache.contains(chapter(BookID.GENESIS, 2)))
    }

    @Test fun markingAnUncachedChapterDoesNothing() {
        val cache = cache()
        cache.store(verses(chapter(BookID.GENESIS, 1), 1..2), chapter(BookID.GENESIS, 1))
        cache.markRead(chapter(BookID.REVELATION, 22))
        assertEquals(listOf(chapter(BookID.GENESIS, 1)), cache.cachedChapters())
        assertEquals(2, cache.cachedVerseCount())
    }

    /**
     * The ceiling is on what is *kept*, never a reason to fail the chapter the reader is looking at.
     * Nothing canonical comes close — Psalm 119, the longest, is 176 verses.
     */
    @Test fun aChapterLargerThanTheCeilingIsStillStored() {
        val cache = cache(limit = 3)
        cache.store(verses(chapter(BookID.GENESIS, 1), 1..2), chapter(BookID.GENESIS, 1))
        val write = cache.store(verses(chapter(BookID.PSALMS, 119), 1..8), chapter(BookID.PSALMS, 119))
        assertTrue(write.exceedsLimit)
        assertEquals(8, write.versesStored)
        assertEquals(listOf(chapter(BookID.GENESIS, 1)), write.evicted) // everything else goes first
        assertEquals(listOf(chapter(BookID.PSALMS, 119)), cache.cachedChapters())

        store(cache) { store ->
            assertEquals(8, store.verseCount(chapter(BookID.PSALMS, 119)))
            assertEquals(8, store.verses(range(ref(BookID.PSALMS, 119, 1), ref(BookID.PSALMS, 119, 8))).size)
        }
    }

    @Test fun theRealCeilingIsCrosswaysFiveHundred() {
        val cache = cache(limit = EsvTerms.CACHE_VERSE_LIMIT)
        assertEquals(500, cache.verseLimit)
        // Ten chapters of 60 verses is 600, over the cap, so the oldest are dropped.
        for (n in 1..10) cache.store(verses(chapter(BookID.PSALMS, n), 1..60), chapter(BookID.PSALMS, n))
        assertTrue(cache.cachedVerseCount() <= 500)
        assertEquals(480, cache.cachedVerseCount())
        assertFalse(cache.contains(chapter(BookID.PSALMS, 1)))
        assertTrue(cache.contains(chapter(BookID.PSALMS, 10)))
    }

    // ---- Clearing ----

    @Test fun clearEmptiesTheCacheButKeepsTheStoreOpenable() {
        val cache = cache()
        cache.store(verses(chapter(BookID.JOHN, 3), 1..4), chapter(BookID.JOHN, 3))
        cache.clear()

        assertEquals(0, cache.cachedVerseCount())
        assertTrue(cache.cachedChapters().isEmpty())
        assertFalse(cache.contains(chapter(BookID.JOHN, 3)))

        store(cache) { store ->
            assertFalse(store.contains(chapter(BookID.JOHN, 3)))
            assertTrue(store.verses(range(ref(BookID.JOHN, 3, 1), ref(BookID.JOHN, 3, 4))).isEmpty())
            assertTrue(store.search("Invented").isEmpty())
            // The identity survives, so the reader can still name what it is showing.
            assertEquals(EsvTerms.REQUIRED_COPYRIGHT, store.copyright)
        }

        // …and it still works afterwards.
        cache.store(verses(chapter(BookID.JOHN, 3), 1..2), chapter(BookID.JOHN, 3))
        assertEquals(2, cache.cachedVerseCount())
    }

    // ---- The file ----

    @Test fun opensAnExistingCacheWithoutLosingIt() {
        val file = File(directory(), "ESV.sqlite")
        val first = OnlineChapterCache(file, OnlineTranslation.ESV, driver, verseLimit = 10)
        first.store(verses(chapter(BookID.JOHN, 3), 1..4), chapter(BookID.JOHN, 3))

        val second = OnlineChapterCache(file, OnlineTranslation.ESV, driver, verseLimit = 10)
        assertTrue(second.contains(chapter(BookID.JOHN, 3)))
        assertEquals(4, second.cachedVerseCount())
        // The read counter persists, so eviction order survives a relaunch.
        second.store(verses(chapter(BookID.JOHN, 4), 1..4), chapter(BookID.JOHN, 4))
        val write = second.store(verses(chapter(BookID.JOHN, 5), 1..4), chapter(BookID.JOHN, 5))
        assertEquals(listOf(chapter(BookID.JOHN, 3)), write.evicted)
    }

    /**
     * A cache is disposable. A file that is corrupt, or simply not one of ours, is replaced rather
     * than left to strand the reader.
     */
    @Test fun replacesAFileThatIsNotACache() {
        val file = File(directory(), "ESV.sqlite")
        file.writeText("this is not a database")
        val cache = OnlineChapterCache(file, OnlineTranslation.ESV, driver, verseLimit = 10)
        cache.store(verses(chapter(BookID.JOHN, 3), 1..2), chapter(BookID.JOHN, 3))
        assertEquals(2, cache.cachedVerseCount())
        assertEquals(2, CachedStore(file).use { it.verseCount(chapter(BookID.JOHN, 3)) })
    }

    /**
     * A reader opens with `immutable=1` and reads its chapter table once, when it is made. An
     * instance made before a write therefore cannot see that write — it is not a bug, it is the
     * contract, and it is what "the chapter didn't load" will turn out to be six months from now.
     */
    @Test fun aReaderOpenedBeforeAWriteMustBeReopened() {
        val cache = cache()
        cache.store(verses(chapter(BookID.JOHN, 3), 1..2), chapter(BookID.JOHN, 3))
        CachedStore(cache.file).use { stale ->
            assertTrue(stale.contains(chapter(BookID.JOHN, 3)))
            assertFalse(stale.contains(chapter(BookID.JOHN, 4)))

            cache.store(verses(chapter(BookID.JOHN, 4), 1..2), chapter(BookID.JOHN, 4))

            // The old instance is finished. Its chapter table was read when it was made, so it cannot
            // know about John 4 whatever the file does; and because the file it promised SQLite would
            // never change has been replaced underneath it, a read may also simply fail with an I/O
            // error. Either is acceptable. What must never happen — and is what `immutable=1` would
            // allow if the cache wrote in place — is a torn page or rows from a half-written chapter.
            assertFalse(stale.contains(chapter(BookID.JOHN, 4)))
            val staleRows = runCatching { stale.verses(range(ref(BookID.JOHN, 4, 1), ref(BookID.JOHN, 4, 2))) }
                .getOrDefault(emptyList())
            assertTrue(staleRows.isEmpty())
        }

        // Re-opening is all it takes, and it is the whole contract.
        store(cache) { fresh ->
            assertTrue(fresh.contains(chapter(BookID.JOHN, 4)))
            assertEquals(2, fresh.verses(range(ref(BookID.JOHN, 4, 1), ref(BookID.JOHN, 4, 2))).size)
            assertEquals(2, fresh.verses(range(ref(BookID.JOHN, 3, 1), ref(BookID.JOHN, 3, 2))).size)
        }
    }

    @Test fun wordsOfChristSurviveTheUTF16ToScalarConversion() {
        // An emoji ahead of the span makes the two offset systems disagree, which is the whole point
        // of the conversion.
        val text = "🕊 He said, Follow me."
        val quoted = "Follow me."
        val location = Utf16Range(text.indexOf(quoted), quoted.length)
        val cache = cache()
        cache.store(listOf(VerseText(ref(BookID.JOHN, 1, 43), text, listOf(location))), chapter(BookID.JOHN, 1))
        val row = store(cache) { it.verses(range(ref(BookID.JOHN, 1, 43))) }.first()
        val red = row.red.first()
        assertEquals(quoted, red.substring(row.text))
    }

    /**
     * API.Bible's real John 3 has three section headings. They were written as `{"k":"s1","t":""}`,
     * and the reader — which reads a heading's words from `t` alone — drew nothing. They must come
     * back with their words, in exactly the shape a bundled Bible's heading has: the bundled BSB's
     * John 3 layout opens with `{"k":"s1","t":"Jesus and Nicodemus"}`.
     */
    @Test fun realAPIBibleHeadingsSurviveTheRoundTrip() {
        val capture = File(System.getProperty("user.home"), ".scripture-alone-import/online/apibible-JHN3.html")
        assumeTrue("no captured API.Bible response at $capture", capture.isFile)
        val chapter = chapter(BookID.JOHN, 3)
        val passage = APIBiblePassageHTML.parse(capture.readText(), chapter)
        val cache = cache(limit = EsvTerms.CACHE_VERSE_LIMIT)
        cache.store(passage.verses, chapter, passage.blocks)

        store(cache) { store ->
            val headings = store.layout(chapter).blocks.filter { it.kind.isHeading }
            assertEquals(List(3) { ChapterLayout.Kind.HEADING }, headings.map { it.kind })
            assertEquals(listOf("Jesus and Nicodemus", "Jesus and John the Baptist", "The One from Heaven"),
                headings.map { it.text })
            assertTrue(headings.all { it.fragments.isEmpty() })
            // Byte for byte what the bundled store holds for the same heading.
            assertTrue(store.layoutJSON(chapter).contains("{\"k\":\"s1\",\"t\":\"Jesus and Nicodemus\"}"))
            assertEquals(36, store.verses(range(ref(BookID.JOHN, 3, 1), ref(BookID.JOHN, 3, 36))).size)
        }
    }

    @Test fun writesOffTheMainActor() {
        val cache = cache()
        val chapter = chapter(BookID.JOHN, 3)
        val rows = verses(chapter, 1..4)
        val executor = Executors.newSingleThreadExecutor()
        val write = try {
            executor.submit<CacheWrite> { cache.store(rows, chapter) }.get()
        } finally {
            executor.shutdown()
        }
        assertEquals(4, write.versesStored)
        assertEquals(4, store(cache) { it.verseCount(chapter) })
    }
}
