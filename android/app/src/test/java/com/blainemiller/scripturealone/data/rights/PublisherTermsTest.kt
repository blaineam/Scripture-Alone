package com.blainemiller.scripturealone.data.rights

import com.blainemiller.scripturealone.data.ChapterVerse
import com.blainemiller.scripturealone.data.TranslationInfo
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.userdata.Selection
import com.blainemiller.scripturealone.data.importer.BibleFileImporter
import com.blainemiller.scripturealone.data.importer.EPUBPackage
import com.blainemiller.scripturealone.data.importer.ImportFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ported from `PublisherTermsTests.swift`. */
class PublisherTermsTest {
    @Test fun recognisesByAbbreviationThenByName() {
        assertEquals("ESV", PublisherTerms.matching("ESV", "", "")?.abbreviation)
        assertEquals("NASB1995", PublisherTerms.matching("NASB95", "", "")?.abbreviation)
        assertEquals("ESV", PublisherTerms.matching("RSB", "Study Bible", "The Holy Bible, English Standard Version")?.abbreviation)
        // More specific names win over the names they contain.
        assertEquals("HCSB", PublisherTerms.matching("Holman Christian Standard Bible®")?.abbreviation)
        assertEquals("NRSV", PublisherTerms.matching("New Revised Standard Version Bible")?.abbreviation)
        assertEquals("RSV", PublisherTerms.matching("Revised Standard Version")?.abbreviation)
        assertNull(PublisherTerms.matching("XYZ", "My Bible", "© Me"))
    }

    @Test fun eachTranslationGetsItsOwnLimits() {
        fun terms(abbreviation: String) = PublisherTerms.matching(abbreviation, "", "")!!.rights
        assertEquals(500L, terms("ESV").maxQuotationVerses)
        assertEquals(1000L, terms("CSB").maxQuotationVerses)
        assertEquals(TranslationRights.UNLIMITED_QUOTATION, terms("NET").maxQuotationVerses)
        assertEquals(false, terms("NIV").allowVerseImages)
        assertEquals(true, terms("NIV").allowShare)
        assertEquals(false, terms("NRSV").allowShare)
        assertEquals(false, terms("MSG").allowVerseImages)
        assertEquals(true, terms("ESV").allowCopy)
        assertEquals(false, terms("ESV").allowExternalHandoff)
    }

    private fun info(abbreviation: String, name: String = "", copyright: String = "© A Publisher") =
        TranslationInfo(abbreviation, name, abbreviation, copyright, "")

    @Test fun publicDomainAndUnknownTranslationsKeepTheirOldAnswers() {
        val asv = TranslationInfo("ASV", "American Standard Version", "ASV", "", "Public Domain")
        assertNull(asv.publisherTerms)
        assertEquals(TranslationRights.PUBLIC_DOMAIN, asv.rights)
        assertNull(asv.attributionNotice)
        val unknown = info("XYZ")
        assertEquals(TranslationRights.LICENSED_DEFAULT, unknown.rights)
        assertEquals("© A Publisher", unknown.attributionNotice)
    }

    @Test fun eachTranslationInfoGetsItsOwnLimitAndNotice() {
        assertEquals(500L, info("ESV").rights.maxQuotationVerses)
        assertEquals(1000L, info("CSB").rights.maxQuotationVerses)
        assertEquals(true, info("CSB").attributionNotice?.contains("Holman Bible Publishers"))
        assertEquals(false, info("NIV").rights.permits(TranslationRights.Permission.VERSE_IMAGES))
        assertEquals(false, info("NRSV").rights.permits(TranslationRights.Permission.SHARE))
        // Recognised by name when the abbreviation is its own.
        assertEquals("ESV", info("RSB", "Study Bible", "The Holy Bible, English Standard Version").publisherTerms?.abbreviation)
    }

    @Test fun wholeBooksAndShareOfABookAreRefused() {
        // Jude: one chapter of 25 verses.
        val jude = (1..25).map { VerseRef(BookID.JUDE.number, 1, it).key }
        val sizes: (BookID) -> Int = { if (it == BookID.JUDE) 25 else 0 }
        assertEquals(QuotationRefusal.WholeBook(BookID.JUDE), info("CSB").quotationRefusal(jude, sizes))
        // The ESV also caps any one book at half.
        assertEquals(QuotationRefusal.TooMuchOfBook(BookID.JUDE, 50), info("ESV").quotationRefusal(jude.take(13), sizes))
        assertNull(info("ESV").quotationRefusal(jude.take(12), sizes))
        // CSB has no share-of-a-book rule, only whole books.
        assertNull(info("CSB").quotationRefusal(jude.take(24), sizes))
        // NET allows a whole book.
        assertNull(info("NET").quotationRefusal(jude, sizes))
        // A book whose size isn't known is held to the verse limit alone.
        assertNull(info("CSB").quotationRefusal(jude) { 0 })
    }

    @Test fun tooManyVersesIsRefusedFirst() {
        val psalms = (1..150).flatMap { chapter -> (1..3).map { VerseRef(BookID.PSALMS.number, chapter, it).key } }
        assertNull(info("ESV").quotationRefusal(psalms) { 2461 })
        val more = psalms + (1..150).map { VerseRef(BookID.PSALMS.number, it, 4).key }
        assertEquals(QuotationRefusal.TooManyVerses(500), info("ESV").quotationRefusal(more) { 2461 })
    }

    @Test fun theNoticeTravelsWithTheQuotation() {
        val verses = listOf(ChapterVerse(VerseRef(43, 3, 16), "For God so loved the world.", emptyList()))
        val range = VerseRange.of(VerseRef(43, 3, 16), VerseRef(43, 3, 16))
        val quoted = Selection.quotation(listOf(range), verses, "ESV", info("ESV").attributionNotice)
        assertEquals(true, quoted.endsWith("\n\n" + PublisherTerms.matching("ESV", "", "")!!.notice))
        assertEquals(false, Selection.quotation(listOf(range), verses, "ASV", null).contains("\n\n"))
    }

    @Test fun theImporterReadsTheTranslationOffTheCopyrightPage() {
        val data = ImportFixtures.epub(
            listOf(
                ImportFixtures.Document("copy.xhtml", "<p>The Holy Bible, English Standard Version® (ESV®). Copyright © 2001 by Crossway.</p>"),
                ImportFixtures.Document("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning.</p>"),
            ),
        )
        val pkg = EPUBPackage(data)
        assertEquals("ESV", BibleFileImporter.recognizedTerms(pkg)?.abbreviation)
        val identity = BibleFileImporter.suggestedIdentity(pkg)
        assertEquals("ESV", identity.abbreviation)
        // The file's own rights line is long enough to keep.
        assertEquals(ImportFixtures.defaultMetadata["rights"], identity.copyright)
    }
}
