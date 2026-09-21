package com.blainemiller.scripturealone.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A port of `EBibleCatalogTests.swift`. The catalogue is a third-party file we don't control, so the
 * parser is tested against the shapes eBible actually publishes — quoted commas in copyright lines,
 * added columns, and rows we must not offer.
 */
class EBibleCatalogTest {

    /** Header taken verbatim from https://ebible.org/Scriptures/translations.csv. */
    private val header =
        "\"languageCode\",\"translationId\",\"languageName\",\"languageNameInEnglish\",\"dialect\",\"homeDomain\"," +
            "\"title\",\"description\",\"Redistributable\",\"Copyright\",\"UpdateDate\",\"publicationURL\",\"OTbooks\"," +
            "\"OTchapters\",\"OTverses\",\"NTbooks\",\"NTchapters\",\"NTverses\",\"DCbooks\",\"DCchapters\",\"DCverses\"," +
            "\"FCBHID\",\"Certified\",\"inScript\",\"swordName\",\"rodCode\",\"textDirection\",\"downloadable\",\"font\"," +
            "\"shortTitle\",\"PODISBN\",\"script\",\"sourceDate\""

    private fun row(
        id: String = "engwebp", language: String = "English", title: String = "World English Bible",
        redistributable: String = "True", downloadable: String = "True",
        copyright: String = "Public Domain", ot: Int = 39, otVerses: Int = 23145,
        nt: Int = 27, ntVerses: Int = 7957, direction: String = "ltr",
        short: String = "WEB",
    ): String =
        "\"eng\",\"$id\",\"$language\",\"$language\",\"\",\"ebible.org\",\"$title\",\"A description\"," +
            "\"$redistributable\",\"$copyright\",\"2026-01-01\",\"https://ebible.org/$id/\",\"$ot\",\"929\"," +
            "\"$otVerses\",\"$nt\",\"260\",\"$ntVerses\",\"0\",\"0\",\"0\",\"ENGWEB\",\"True\",\"\",\"$id\",\"\",\"$direction\"," +
            "\"$downloadable\",\"Gentium\",\"$short\",\"\",\"Latin\",\"2026-01-01\""

    /** The published file begins with a UTF-8 byte-order mark (seen on the live catalogue, 2026-09). */
    @Test fun ignoresALeadingByteOrderMark() {
        val entries = EBibleCatalog.parse("﻿" + header + "\n" + row() + "\n")
        assertEquals(listOf("engwebp"), entries.map { it.id })
    }

    @Test fun readsATranslation() {
        val entries = EBibleCatalog.parse(header + "\n" + row() + "\n")
        assertEquals(1, entries.size)
        val web = requireNotNull(entries.firstOrNull())
        assertEquals("engwebp", web.id)
        assertEquals("World English Bible", web.title)
        assertEquals("WEB", web.shortTitle)
        assertEquals("Public Domain", web.copyright)
        assertEquals("Complete Bible", web.scope)
        assertEquals(31102, web.verseCount)
        assertFalse(web.isRightToLeft)
        assertEquals("https://ebible.org/Scriptures/engwebp_usfm.zip", web.downloadURL)
    }

    @Test fun dropsWhatWeMayNotOffer() {
        val csv = listOf(
            header,
            row(id = "keep"),
            row(id = "norights", redistributable = "False"),
            row(id = "nofile", downloadable = "False"),
            row(id = "empty", ot = 0, otVerses = 0, nt = 0, ntVerses = 0),
        ).joinToString("\n")
        assertEquals(listOf("keep"), EBibleCatalog.parse(csv).map { it.id })
    }

    /** Copyright lines routinely contain commas, and some contain quoted names. */
    @Test fun handlesCommasAndQuotesInsideFields() {
        val line = row(copyright = "Copyright © 2009 Wycliffe Bible Translators, Inc., all rights reserved")
        val entry = requireNotNull(EBibleCatalog.parse(header + "\n" + line).firstOrNull())
        assertEquals("Copyright © 2009 Wycliffe Bible Translators, Inc., all rights reserved", entry.copyright)
    }

    @Test fun handlesDoubledQuotes() {
        val csv = header + "\n" + row(title = "The \"\"Good News\"\" Bible")
        val entry = requireNotNull(EBibleCatalog.parse(csv).firstOrNull())
        assertEquals("The \"Good News\" Bible", entry.title)
    }

    /** Columns are addressed by name, so eBible adding one in the middle must not shift the rest. */
    @Test fun survivesAnInsertedColumn() {
        val header = header.replace("\"title\"", "\"newColumn\",\"title\"")
        val row = row().replace("\"World English Bible\"", "\"something\",\"World English Bible\"")
        val entry = requireNotNull(EBibleCatalog.parse(header + "\n" + row).firstOrNull())
        assertEquals("World English Bible", entry.title)
        assertEquals("Public Domain", entry.copyright)
    }

    @Test fun rejectsACatalogueMissingItsColumns() {
        try {
            EBibleCatalog.parse("\"a\",\"b\"\n\"1\",\"2\"\n")
            fail("expected EBibleCatalog.Failure")
        } catch (_: EBibleCatalog.Failure) {
        }
    }

    @Test fun readsScopeAndDirection() {
        val nt = requireNotNull(EBibleCatalog.parse(header + "\n" + row(ot = 0, otVerses = 0, direction = "rtl")).firstOrNull())
        assertEquals("New Testament", nt.scope)
        assertTrue(nt.isRightToLeft)
    }

    @Test fun toleratesCRLFAndATrailingNewline() {
        val csv = header + "\r\n" + row() + "\r\n"
        assertEquals(1, EBibleCatalog.parse(csv).size)
    }

    /** A repeated column name keeps its first occurrence, as Swift now does, rather than failing. */
    @Test fun aRepeatedColumnKeepsTheFirst() {
        val header = header + ",\"title\""
        val row = row() + ",\"A Later Title\""
        val entry = requireNotNull(EBibleCatalog.parse(header + "\n" + row).firstOrNull())
        assertEquals("World English Bible", entry.title)
    }
}
