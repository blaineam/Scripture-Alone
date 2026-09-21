package com.blainemiller.scripturealone.data.rights

import java.time.Instant

/**
 * What may be done with a translation's text beyond reading it on this device. A port of
 * `ScriptureAloneCore/TranslationRights.swift`.
 *
 * Reading, searching, highlighting, noting and listening are local and always allowed. What needs a
 * rule is text *leaving* the device: a share link carries the verses inside the URL, an export writes
 * them to a file someone else can open, and a hand-off passes them to another app.
 *
 * There is exactly one answer to "may this text leave?", and it is this class. A translation that
 * arrived in a signed package carries the publisher's own answer (its policy's rights); every other
 * translation has one derived from its licence line by [of]. Every gate asks the same value the same
 * way, so a packaged translation and the public-domain ASV are judged by one code path.
 */
data class TranslationRights(
    /** Copy the verses to the clipboard. */
    val allowCopy: Boolean,
    /** Share the verses as text — the share sheet and share links, which carry the text itself. */
    val allowShare: Boolean,
    /** Render the verses into an image to post. */
    val allowVerseImages: Boolean,
    /** Write the verses into an exported notes file (text or PDF). */
    val allowNotesExport: Boolean,
    /** Hand the verses to another app to render. Not quotation: it copies the text into software the publisher never licensed. */
    val allowExternalHandoff: Boolean,
    /** Keep the text on the device at all. A package that forbids it must not be copied into the library. */
    val allowOfflineStorage: Boolean,
    /** Most verses that may leave in one quotation; [UNLIMITED_QUOTATION] for a text that needs no permission. */
    val maxQuotationVerses: Long,
    /** When the grant lapses. Null for a grant that does not. */
    val expires: Instant? = null,
) {
    enum class Permission { COPY, SHARE, VERSE_IMAGES, NOTES_EXPORT, EXTERNAL_HANDOFF, OFFLINE_STORAGE }

    fun hasExpired(now: Instant = Instant.now()): Boolean = expires != null && !now.isBefore(expires)

    /**
     * An expired grant permits nothing. A package past its date refuses to open at all; this is the
     * second gate, for a translation already open when the date passes.
     */
    fun permits(permission: Permission, now: Instant = Instant.now()): Boolean = !hasExpired(now) && when (permission) {
        Permission.COPY -> allowCopy
        Permission.SHARE -> allowShare
        Permission.VERSE_IMAGES -> allowVerseImages
        Permission.NOTES_EXPORT -> allowNotesExport
        Permission.EXTERNAL_HANDOFF -> allowExternalHandoff
        Permission.OFFLINE_STORAGE -> allowOfflineStorage
    }

    /** Whether a quotation of this many verses may leave the device. */
    fun mayQuote(verseCount: Int, now: Instant = Instant.now()): Boolean =
        !hasExpired(now) && verseCount <= maxQuotationVerses

    companion object {
        /** No cap at all — larger than any quotation can be (the whole canon is 31,102 verses). */
        const val UNLIMITED_QUOTATION = Long.MAX_VALUE

        /**
         * Publishers' standard permissions converge on a few hundred verses before written permission
         * is needed; the lowest of Crossway, Lockman and Holman's governs. A package may set its own.
         */
        const val QUOTATION_VERSE_LIMIT = 500L

        /** A text that needs nobody's permission: the bundled ASV, BSB and KJV, and any public-domain import. */
        val PUBLIC_DOMAIN = TranslationRights(
            allowCopy = true, allowShare = true, allowVerseImages = true, allowNotesExport = true,
            allowExternalHandoff = true, allowOfflineStorage = true, maxQuotationVerses = UNLIMITED_QUOTATION,
        )

        /**
         * What a copyrighted text may do without a specific grant: quotation with attribution up to
         * the published limit, and nothing that hands the text to other software. An import whose
         * licence is unknown lands here too — "unknown" must behave like "licensed", never like "free".
         */
        val LICENSED_DEFAULT = TranslationRights(
            allowCopy = true, allowShare = true, allowVerseImages = true, allowNotesExport = true,
            allowExternalHandoff = false, allowOfflineStorage = true, maxQuotationVerses = QUOTATION_VERSE_LIMIT,
        )

        /**
         * True when the text itself may be passed on without a publisher's permission. Reads the
         * licence line and nothing else: a fact about the text, not a permission — gate on [of].
         */
        fun isPublicDomain(license: String, copyright: String): Boolean =
            license.contains("public domain", ignoreCase = true) ||
                copyright.contains("public domain", ignoreCase = true) ||
                // No copyright line at all means the bundled texts, which are all public domain.
                copyright.isBlank()

        /** The one value every gate asks: a signed package's grant if there is one, else the licence line. */
        fun of(license: String, copyright: String, granted: TranslationRights? = null): TranslationRights =
            granted ?: if (isPublicDomain(license, copyright)) PUBLIC_DOMAIN else LICENSED_DEFAULT

        /** The line that must travel with a quotation, or null when none is required. */
        fun attributionNotice(license: String, copyright: String): String? =
            if (isPublicDomain(license, copyright)) null else copyright.trim()
    }
}
