package com.blainemiller.scripturealone.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The name and tag a catalogue download is imported under — `CatalogTranslation+Identity.swift`,
 * whose doc comment is the specification: the tags land on the abbreviations these translations are
 * actually known by.
 */
class CatalogIdentityTest {

    @Test fun initialsLandOnTheKnownAbbreviations() {
        assertEquals("WEB", CatalogIdentity.abbreviation("World English Bible", "engwebp"))
        assertEquals("WEBBE", CatalogIdentity.abbreviation("World English Bible British Edition", "engwebpb"))
        assertEquals("ASV", CatalogIdentity.abbreviation("American Standard Version (1901)", "eng-asv"))
        assertEquals("BBE", CatalogIdentity.abbreviation("Bible in Basic English", "engBBE"))
        // An apostrophe splits a word, as Swift's `isLetter` separator does.
        assertEquals("YSLT", CatalogIdentity.abbreviation("Young's Literal Translation", "engylt"))
    }

    @Test fun anUnusableTitleFallsBackToTheId() {
        // One initial is too short to be a tag…
        assertEquals("ENGNET", CatalogIdentity.abbreviation("NET", "engnet"))
        // …and so is a title of nothing but skipped words or numbers.
        assertEquals("ENGKJV2006", CatalogIdentity.abbreviation("of the 1611", "eng-kjv2006"))
        assertEquals("IMPORT", CatalogIdentity.abbreviation("", "--"))
    }

    @Test fun theCatalogueRowWinsOverTheFilesOwnCopyrightPage() {
        val row = CatalogTranslation(
            id = "engwebp", languageCode = "eng", languageName = "English", languageNameInEnglish = "English",
            title = "World English Bible", shortTitle = "World English Bible", copyright = "Public Domain",
            isRedistributable = true, otBooks = 39, ntBooks = 27, otVerses = 23145, ntVerses = 7957,
            textDirection = "ltr", script = "Latin",
        )
        val identity = row.importIdentity
        assertEquals("ENGWEBP", identity.id)
        assertEquals("World English Bible", identity.name)
        assertEquals("WEB", identity.abbreviation)
        assertEquals("Public Domain", identity.copyright)
        assertEquals("Public Domain", identity.license)
        assertEquals("eBible.org", identity.source)

        val bare = row.copy(title = "", copyright = "").importIdentity
        assertEquals("engwebp", bare.name)
        assertEquals("Source: eBible.org", bare.copyright)
    }
}
