package com.blainemiller.scripturealone.data.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Against the real files both apps ship: the bundled cross references and the commentary pack. */
class StudyStoreTest {

    private val john316 = 43_003_016

    @Test fun decodesPackedRecordsLittleEndian() {
        // to_start = John 3:16, to_end = John 3:17, votes = 300 (0x012C)
        val start = 43_003_016
        val end = 43_003_017
        fun le(v: Long, w: Int) = ByteArray(w) { ((v shr (8 * it)) and 0xFF).toByte() }
        val blob = le(start.toLong(), 4) + le(end.toLong(), 4) + le(300, 2) +
            le(99_001_001, 4) + le(99_001_001, 4) + le(1, 2) // book 99 is not a book: skipped
        assertEquals(
            listOf(CrossReference(VerseKeyRange(start, end), 300)),
            StudyStore.decodeCrossReferences(blob),
        )
    }

    @Test fun crossReferencesComeFromTheBundledFileAndMatchThePack() {
        JdbcSqlSource(resource("Study/CrossReferences.sqlite")).use { bundledDb ->
            JdbcSqlSource(resource("Study/Study.sqlite")).use { packDb ->
                val bundled = StudyStore(bundledDb)
                val pack = StudyStore(packDb)
                assertNotNull(bundled.crossReferenceSource)
                val refs = bundled.crossReferences(john316)
                assertTrue("John 3:16 should have cross references", refs.size > 5)
                // Strongest first.
                assertEquals(refs.map { it.votes }.sortedDescending(), refs.map { it.votes })
                // The split file carries exactly what the pack does.
                assertEquals(pack.crossReferences(john316), refs)
                assertEquals(pack.crossReferenceCounts(43, 3), bundled.crossReferenceCounts(43, 3))
                assertTrue(bundled.crossReferenceCounts(43, 3).keys.all { it / 1_000 == 43_003 })
                // The bundled file has no commentary: asking must fail loudly, never return a guess.
                assertTrue(runCatching { bundled.commentary("gill", john316) }.isFailure)
            }
        }
    }

    @Test fun commentaryInflatesAndSplitsCleanly() {
        JdbcSqlSource(resource("Study/Study.sqlite")).use { db ->
            val store = StudyStore(db)
            val ids = store.commentarySources.map { it.id }
            assertEquals(3, ids.size)
            for (id in ids) {
                val onVerse = store.commentary(id, john316)
                assertTrue("$id says nothing on John 3:16", onVerse.isNotEmpty())
                for (entry in onVerse) {
                    assertFalse(entry.isIntroduction)
                    val range = entry.range!!
                    assertTrue(range.start <= john316 && range.end >= john316)
                    assertFalse("a separator leaked into $id text", entry.text.contains(Char(0)))
                    assertTrue(entry.text.isNotBlank())
                }
            }
            assertTrue(store.sourcesCommenting(john316).containsAll(ids))
        }
    }

    /** Every commentary body in the file must inflate: a partial port would fail on some chapters. */
    @Test fun everyCommentaryBodyInflates() {
        JdbcSqlSource(resource("Study/Study.sqlite")).use { db ->
            val bodies = db.query("SELECT id, body FROM commentary_text") { it.long(0) to it.blob(1) }
            val failed = bodies.filter { (_, body) -> StudyStore.inflate(body) == null }.map { it.first }
            println("commentary bodies: ${bodies.size}, failed to inflate: ${failed.size}")
            assertEquals(emptyList<Long>(), failed)
        }
    }

    @Test fun chapterIntroductionsAreVerseZero() {
        JdbcSqlSource(resource("Study/Study.sqlite")).use { db ->
            val store = StudyStore(db)
            val intro = store.commentarySources.firstNotNullOfOrNull { store.introduction(it.id, 43, 3) }
            assertNotNull("some source introduces John 3", intro)
            assertTrue(intro!!.isIntroduction)
            assertNull(intro.range)
            val chapter = store.commentary(intro.source, 43, 3)
            assertTrue("introduction comes first", chapter.first().isIntroduction)
        }
    }
}
