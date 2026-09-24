package com.blainemiller.scripturealone.data.importer

import com.blainemiller.scripturealone.data.TranslationInfo
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.study.ImportedStudyStore
import com.blainemiller.scripturealone.data.study.JdbcSqlSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A study Bible's own material — notes, introductions, essays, pictures — kept apart from the text and
 * written beside it. Ported from `StudyMaterialImportTests.swift`. Text is public domain or invented.
 */
class StudyMaterialImportTest {
    private fun page(body: String): String =
        """<?xml version="1.0"?><html xmlns:epub="http://www.idpf.org/2007/ops"><body>$body</body></html>"""

    private val intro = "rom-intro.xhtml" to """
        <section title="Romans"><p class="heading">INTRODUCTION TO ROMANS</p>
        <p>Paul wrote this letter to the church at Rome before he had visited it, setting out the gospel he preached
        and the way it joins Jew and Gentile in one people.</p></section>
        """

    private val text = "rom.xhtml" to """
        <section title="Romans"><p><span class="chapter-num">1</span><a epub:type="noteref" href="#vc1">[✞]</a> Paul, a servant of Christ.
        <span class="verse-num">2</span><a epub:type="noteref" href="#vc1">[✞]</a> Which he promised afore.</p>
        <div class="gbox"><p class="theohead">GRACE</p><p class="theobody">An essay about grace, set beside the text.</p></div>
        <p class="image"><img src="images/rome.jpg" alt="rome-and-its-provinces"/></p>
        <p><span class="verse-num">3</span> Concerning his Son.</p>
        <aside epub:type="footnote" id="vc1"><p><b>ROMANS 1:1, 2 Paul.</b> Ancient letters began with a formula.</p><p>A second paragraph.</p></aside></section>
        """

    private fun extract(vararg documents: Pair<String, String>, options: BibleTextExtractor.Options = BibleTextExtractor.Options()) =
        BibleTextExtractor(options).extract(documents.map { it.first to page(it.second) })

    @Test fun studyNotesCoverEveryVerseThatCallsThem() {
        val bible = extract(intro, text)
        val note = required(bible.study.orderedNotes.firstOrNull())
        assertEquals(ref(BookID.ROMANS, 1, 1), note.start)
        assertEquals(ref(BookID.ROMANS, 1, 2), note.end)
        assertEquals("Paul. Ancient letters began with a formula.\n\nA second paragraph.", note.text)
        // None of it is scripture.
        assertEquals("Paul, a servant of Christ.", bible.verses[ref(BookID.ROMANS, 1, 1)]?.text)
        assertEquals("Which he promised afore.", bible.verses[ref(BookID.ROMANS, 1, 2)]?.text)
    }

    @Test fun introductionsEssaysAndPicturesAreKept() {
        val bible = extract(intro, text)
        val introduction = required(bible.study.articles.firstOrNull { it.kind == ExtractedStudy.ArticleKind.INTRODUCTION })
        assertEquals(BookID.ROMANS, introduction.book)
        assertEquals("INTRODUCTION TO ROMANS", introduction.title)
        val essay = required(bible.study.articles.firstOrNull { it.kind == ExtractedStudy.ArticleKind.ESSAY })
        assertEquals("GRACE", essay.title)
        assertEquals(ref(BookID.ROMANS, 1, 2), essay.anchor)
        assertEquals("An essay about grace, set beside the text.", essay.text)
        val image = required(bible.study.images.firstOrNull())
        assertEquals("images/rome.jpg", image.path)
        assertEquals("rome and its provinces", image.caption)
        assertEquals(ref(BookID.ROMANS, 1, 2), image.anchor)
    }

    @Test fun studyMaterialCanBeLeftOut() {
        val bible = extract(intro, text, options = BibleTextExtractor.Options(studyMaterial = false))
        assertTrue(bible.study.isEmpty)
    }

    private fun info(file: File) = ImportedStoreReader(file).use { reader ->
        TranslationInfo(reader.meta.getValue("id"), reader.meta.getValue("name"), reader.meta.getValue("abbreviation"),
                        reader.meta.getValue("copyright"), reader.meta.getValue("license"))
    }

    @Test fun theStoreCarriesItAndTheStudyReaderReadsIt() {
        val bible = extract(intro, text)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        bible.loadStudyImages { jpeg }
        val identity = ImportedTranslationIdentity("IMPORT-STUDY", "Example Study Bible", "ESB", "© Example")
        val file = File(ImportFixtures.scratchDirectory(), "IMPORT-STUDY.sqlite")
        ImportedBibleBuilder.write(bible, identity, file, ImportFixtures.jdbcWriter)
        JdbcSqlSource(file).use { source ->
            val study = required(ImportedStudyStore.open(source, info(file)))
            assertEquals("Example Study Bible", study.source.name)
            // The note, then the essay.
            assertEquals(2, study.commentary(ref(BookID.ROMANS, 1, 2).key).size)
            assertEquals(true, study.introduction(BookID.ROMANS.number, 1)?.text?.startsWith("INTRODUCTION TO ROMANS"))
            assertNull(study.introduction(BookID.ROMANS.number, 2))
            assertTrue(study.comments(ref(BookID.ROMANS, 1, 1).key))
            val images = study.images(BookID.ROMANS.number, 1)
            assertEquals(1, images.size)
            assertArrayEquals(jpeg, study.imageData(images[0].id))
        }
    }

    @Test fun aTranslationWithoutStudyMaterialHasNoStudyStore() {
        val bible = extract("rom.xhtml" to """<section title="Romans"><p><span class="chapter-num">1</span> Paul, a servant.</p></section>""")
        val identity = ImportedTranslationIdentity("IMPORT-PLAIN", "Plain", "PLN", "© Example")
        val file = File(ImportFixtures.scratchDirectory(), "IMPORT-PLAIN.sqlite")
        ImportedBibleBuilder.write(bible, identity, file, ImportFixtures.jdbcWriter)
        JdbcSqlSource(file).use { source -> assertNull(ImportedStudyStore.open(source, info(file))) }
    }
}
