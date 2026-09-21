package com.blainemiller.scripturealone.data.layout

import com.blainemiller.scripturealone.data.ChapterRows
import com.blainemiller.scripturealone.data.layout.ChapterLayout.Kind
import com.blainemiller.scripturealone.data.layout.ChapterLayout.Span.Style
import com.blainemiller.scripturealone.data.sabible.ContentKey
import com.blainemiller.scripturealone.data.sabible.PublisherKeyring
import com.blainemiller.scripturealone.data.sabible.ScalarRange
import com.blainemiller.scripturealone.data.sabible.TranslationPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The layout port, against hand-written JSON for the edge cases and against every chapter the app
 * ships for the rest. The databases are read in place from the iOS resources; a missing file fails
 * the test rather than skipping it.
 */
class ChapterLayoutTest {

    private val resources = File(
        System.getProperty("scripturealone.resources") ?: error("scripturealone.resources is not set — run through Gradle"),
    )

    private fun db(name: String): Connection {
        val file = File(resources, "Bibles/$name")
        assertTrue("missing ${file.path}", file.isFile)
        return DriverManager.getConnection("jdbc:sqlite:${file.path}")
    }

    private fun layoutJson(db: Connection, book: Int, chapter: Int): String =
        db.prepareStatement("SELECT layout FROM chapters WHERE book = ? AND chapter = ?").use { st ->
            st.setInt(1, book)
            st.setInt(2, chapter)
            st.executeQuery().use { rs -> check(rs.next()) { "no $book:$chapter" }; rs.getString(1) }
        }

    // MARK: - The format

    @Test
    fun decodesEveryField() {
        val layout = ChapterLayout.parse(
            """{"b":[{"k":"s1","t":"Heading"},{"k":"p","f":[{"v":3,"n":1,"t":"Jesus said, “Truly.”",
               "s":[[12,8,"r"],[0,5,"i"],[1,1,"z"]],"fn":[[20,"A note."]]},{"v":4,"t":"Tail."}]},{"k":"b"},{"k":"zz"}]}""",
        )
        assertEquals(listOf(Kind.HEADING, Kind.PARAGRAPH, Kind.STANZA_BREAK, Kind.UNKNOWN), layout.blocks.map { it.kind })
        assertEquals("Heading", layout.blocks[0].text)
        assertTrue(layout.blocks[0].fragments.isEmpty())
        val (first, second) = layout.blocks[1].fragments
        assertEquals(3, first.verse)
        assertTrue(first.numbered)
        assertEquals(listOf(Style.WORDS_OF_CHRIST, Style.SUPPLIED, null), first.spans.map { it.style })
        assertEquals(12, first.spans[0].start)
        assertEquals(8, first.spans[0].length)
        assertEquals(ChapterLayout.Footnote(20, "A note."), first.footnotes.single())
        assertFalse("n absent means not numbered", second.numbered)
        assertTrue(second.spans.isEmpty() && second.footnotes.isEmpty())
    }

    @Test
    fun malformedLayoutsAreRefusedWhereSwiftRefusesThem() {
        for (bad in listOf(
            "not json",
            """{"x":[]}""",
            """{"b":[{"t":"no kind"}]}""",
            """{"b":[{"k":"p","f":[{"t":"no verse"}]}]}""",
            """{"b":[{"k":"p","f":[{"v":1}]}]}""",
            """{"b":[{"k":"p","f":[{"v":1,"t":"x","s":[[0,1]]}]}]}""",
            """{"b":[{"k":"p","f":[{"v":1,"t":"x","fn":[["0","n"]]}]}]}""",
        )) {
            try {
                ChapterLayout.parse(bad)
                fail("accepted $bad")
            } catch (expected: LayoutFormatException) {
                // refused, as JSONDecoder would
            }
        }
    }

    @Test
    fun redColumnParsesAndSkipsMalformedPairs() {
        assertEquals(listOf(ScalarRange(0, 135)), ChapterRows.parseRed("[[0, 135]]"))
        assertEquals(listOf(ScalarRange(1, 2)), ChapterRows.parseRed("[[1,2],[3],[\"a\",1]]"))
        assertTrue(ChapterRows.parseRed(null).isEmpty())
        assertTrue(ChapterRows.parseRed("garbage").isEmpty())
    }

    // MARK: - Every chapter that ships

    @Test
    fun everyBsbChapterParses() = assertEveryChapterParses("BSB.sqlite")

    @Test
    fun everyKjvChapterParses() = assertEveryChapterParses("KJV.sqlite")

    /** The sealed ASV's layouts, decrypted through the real package reader — what the app parses by default. */
    @Test
    fun everyAsvPackageChapterParses() {
        val pkg = File(resources, "Packages/ASV.sabible")
        val key = File(resources, "Packages/bundled-signing.pub")
        TranslationPackage.open(pkg, PublisherKeyring(listOf(key.readBytes())), ContentKey.derive(ContentKey.BUNDLED_SEED, "ASV")).use { p ->
            var parsed = 0
            for (ref in p.chapters) {
                val layout = ChapterLayout.parse(p.chapter(ref).layoutJson)
                assertTrue("$ref has no blocks", layout.blocks.isNotEmpty())
                parsed++
            }
            println("ASV.sabible: parsed $parsed chapter layouts")
            assertEquals(1189, parsed)
        }
    }

    private fun assertEveryChapterParses(name: String) {
        db(name).use { db ->
            var parsed = 0
            var fragments = 0
            val unknownKinds = mutableSetOf<String>()
            db.createStatement().use { st ->
                st.executeQuery("SELECT book, chapter, layout FROM chapters ORDER BY book, chapter").use { rs ->
                    while (rs.next()) {
                        val where = "${rs.getInt(1)}:${rs.getInt(2)}"
                        val json = rs.getString(3)
                        val layout = try {
                            ChapterLayout.parse(json)
                        } catch (e: LayoutFormatException) {
                            throw AssertionError("$name $where: ${e.message}", e)
                        }
                        assertTrue("$name $where has no blocks", layout.blocks.isNotEmpty())
                        for (block in layout.blocks) {
                            if (block.kind == Kind.UNKNOWN) {
                                unknownKinds += Regex("\"k\":\"(\\w+)\"").findAll(json).map { it.groupValues[1] }
                                    .filter { Kind.of(it) == Kind.UNKNOWN }
                            }
                            for (f in block.fragments) {
                                fragments++
                                val scalars = f.text.codePointCount(0, f.text.length)
                                for (span in f.spans) {
                                    assertTrue("$name $where span past its fragment", span.start >= 0 && span.start + span.length <= scalars)
                                }
                                for (note in f.footnotes) {
                                    assertTrue("$name $where footnote past its fragment", note.position in 0..scalars)
                                }
                            }
                        }
                        parsed++
                    }
                }
            }
            println("$name: parsed $parsed chapter layouts, $fragments fragments")
            assertEquals(1189, parsed)
            assertTrue("$name has markers this port doesn't know", unknownKinds.isEmpty())
        }
    }

    // MARK: - Known structure

    @Test
    fun psalm23HasATitleAndTwoLevelsOfPoetry() {
        val layout = db("BSB.sqlite").use { ChapterLayout.parse(layoutJson(it, 19, 23)) }
        val kinds = layout.blocks.map { it.kind }
        assertEquals(Kind.HEADING, kinds[0])
        assertEquals("The LORD Is My Shepherd", layout.blocks[0].text)
        assertEquals(Kind.PARALLEL, kinds[1])
        val title = layout.blocks.single { it.kind == Kind.TITLE }
        assertEquals("A Psalm of David.", title.fragments.single().text)
        assertTrue(Kind.POETRY1 in kinds && Kind.POETRY2 in kinds)
        assertEquals("one stanza break, before verse 5", 1, kinds.count { it == Kind.STANZA_BREAK })
        val firstLine = layout.blocks.first { it.kind == Kind.POETRY1 }.fragments.single()
        assertEquals(1, firstLine.verse)
        assertTrue(firstLine.numbered)
        assertEquals("The LORD is my shepherd;", firstLine.text)
        assertEquals(1, firstLine.footnotes.size)
        // A continuation line carries the same verse but no number.
        val secondLine = layout.blocks.first { it.kind == Kind.POETRY2 }.fragments.single()
        assertEquals(1, secondLine.verse)
        assertFalse(secondLine.numbered)
    }

    @Test
    fun john316IsAllWordsOfChrist() {
        db("BSB.sqlite").use { db ->
            val layout = ChapterLayout.parse(layoutJson(db, 43, 3))
            val v16 = layout.blocks.flatMap { it.fragments }.single { it.verse == 16 }
            assertTrue(v16.text.startsWith("For God so loved the world"))
            val red = v16.spans.single { it.style == Style.WORDS_OF_CHRIST }
            assertEquals(0, red.start)
            assertEquals(v16.text.codePointCount(0, v16.text.length), red.length)

            // The layout span and the `verses.red` column agree about the same words.
            val column = db.prepareStatement("SELECT text, red FROM verses WHERE id = 43003016").use { st ->
                st.executeQuery().use { rs -> rs.next(); rs.getString(1) to ChapterRows.parseRed(rs.getString(2)) }
            }
            assertEquals(v16.text, column.first)
            assertEquals(listOf(ScalarRange(red.start, red.length)), column.second)

            // Verse 1 is narration, not red.
            val v1 = layout.blocks.flatMap { it.fragments }.single { it.verse == 1 }
            assertNull(v1.spans.firstOrNull { it.style == Style.WORDS_OF_CHRIST })
        }
    }

    @Test
    fun kjvMarksSuppliedWordsInItalics() {
        val layout = db("KJV.sqlite").use { ChapterLayout.parse(layoutJson(it, 1, 2)) }
        val v4 = layout.blocks.flatMap { it.fragments }.single { it.verse == 4 }
        val supplied = v4.spans.first { it.style == Style.SUPPLIED }
        val range = v4.text.utf16Range(supplied.start, supplied.length)
        assertEquals("are", v4.text.substring(range.first, range.last + 1))
    }
}
