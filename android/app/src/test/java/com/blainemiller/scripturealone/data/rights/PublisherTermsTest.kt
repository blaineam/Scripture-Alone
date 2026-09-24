package com.blainemiller.scripturealone.data.rights

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
