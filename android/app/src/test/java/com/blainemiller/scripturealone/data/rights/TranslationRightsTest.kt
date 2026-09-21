package com.blainemiller.scripturealone.data.rights

import com.blainemiller.scripturealone.data.rights.TranslationRights.Permission
import com.blainemiller.scripturealone.data.sabible.PackagePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** A port of `TranslationRightsTests.swift`: the rule that decides whether text may leave the device. */
class TranslationRightsTest {

    @Test fun bundledPublicDomainTextsAreFree() {
        val license = "Public domain"
        val copyright = "American Standard Version (1901). Public domain."
        assertTrue(TranslationRights.isPublicDomain(license, copyright))
        assertNull(TranslationRights.attributionNotice(license, copyright))
        assertTrue(TranslationRights.of(license, copyright).permits(Permission.EXTERNAL_HANDOFF))
    }

    /** An imported translation is judged by its licence, not by having been imported. */
    @Test fun importedPublicDomainIsStillFree() {
        assertTrue(TranslationRights.of("public domain", "public domain").permits(Permission.EXTERNAL_HANDOFF))
    }

    @Test fun aLicensedTranslationCarriesItsNoticeAndStaysOnTheDevice() {
        val license = "All rights reserved"
        val copyright = "Copyright © 2017 by Holman Bible Publishers"
        assertFalse(TranslationRights.isPublicDomain(license, copyright))
        assertEquals(copyright, TranslationRights.attributionNotice(license, " $copyright\n"))
        assertFalse(TranslationRights.of(license, copyright).permits(Permission.EXTERNAL_HANDOFF))
    }

    @Test fun quotationIsCappedForLicensedTextOnly() {
        val csb = TranslationRights.of("All rights reserved", "Copyright © 2017 Holman")
        val asv = TranslationRights.of("Public domain", "")
        assertTrue(csb.mayQuote(12))
        assertTrue(csb.mayQuote(500))
        assertFalse(csb.mayQuote(501))
        assertTrue(asv.mayQuote(31_102))
    }

    @Test fun aBlankCopyrightIsTreatedAsFree() {
        assertTrue(TranslationRights.isPublicDomain("", "   "))
    }

    @Test fun aPackagesGrantWinsOverItsLicenceLine() {
        val policy = PackagePolicy(
            allowCopy = true, allowShare = false, allowVerseImages = false, allowNotesExport = true,
            allowExternalHandoff = false, allowOfflineStorage = true, maxQuotationVerses = 25, expires = "2027-01-01",
        )
        // Public domain by its licence line, but the package's own policy governs.
        val rights = TranslationRights.of("Public domain", "", granted = policy.rights())
        val before = Instant.parse("2026-12-31T23:59:59Z")
        val after = Instant.parse("2027-01-01T00:00:00Z")
        assertTrue(rights.permits(Permission.COPY, before))
        assertFalse(rights.permits(Permission.SHARE, before))
        assertTrue(rights.mayQuote(25, before))
        assertFalse(rights.mayQuote(26, before))
        // An expired grant permits nothing, at the instant it lapses.
        assertFalse(rights.permits(Permission.COPY, after))
        assertFalse(rights.mayQuote(1, after))
        assertTrue(runCatching { policy.copy(expires = "next year").rights() }.isFailure)
    }
}
