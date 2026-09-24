package com.blainemiller.scripturealone.data.importer

import androidx.annotation.StringRes
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.text.AppText

/**
 * Why an import stopped, ported from `Import/BibleImportError.swift`. Every failure is typed so the
 * UI can say something true, and so the refusal cases can be asserted in tests.
 *
 * Two errors are equal when they are the same case with the same payload, as Swift's `Equatable`
 * enum is — so a test can assert `ProtectedByDRM(ENCRYPTION_MANIFEST)` rather than a string. The
 * [message] is Swift's `errorDescription`, word for word.
 */
sealed class BibleImportError(message: String) : Exception(message) {
    /** The file could not be read off disk at all. */
    class UnreadableFile(val detail: String) : BibleImportError(AppText.get(R.string.data_import_unreadable_file, detail))

    /** Not a ZIP container (an ePub is a ZIP). */
    class NotAZipArchive : BibleImportError(AppText.get(R.string.data_import_not_a_zip))

    /** A ZIP that is truncated, mis-indexed, or whose contents fail their checksum. */
    class DamagedArchive(val detail: String) : BibleImportError(AppText.get(R.string.data_import_damaged_archive, detail))

    /** An entry that claims a size we will not inflate. */
    class EntryTooLarge(val name: String) : BibleImportError(AppText.get(R.string.data_import_entry_too_large, name))

    /** A ZIP that is not an ePub: wrong mimetype, no container.xml, no package document. */
    class NotAnEPUB(val detail: String) : BibleImportError(AppText.get(R.string.data_import_not_an_epub, detail))

    /** A file the engine has no reader for. */
    class UnsupportedFormat(val detail: String) : BibleImportError(AppText.get(R.string.data_import_unsupported_format, detail))

    /** The file is protected. The engine refuses it and reads none of its content. */
    class ProtectedByDRM(val evidence: DRMEvidence) :
        BibleImportError(AppText.get(R.string.data_import_protected_by_drm, evidence.explanation))

    /** The ePub opened and parsed, but nothing in it looked like scripture. */
    class NoScriptureFound : BibleImportError(AppText.get(R.string.data_import_no_scripture_found))

    /** An import must carry a copyright line forward; the file had none and the caller supplied none. */
    class MissingCopyright :
        BibleImportError(AppText.get(R.string.data_import_missing_copyright))

    /** The text came out too damaged to store ([ImportQuality]); the score says how far. */
    class PoorQuality(val score: Int) : BibleImportError(AppText.get(R.string.data_import_poor_quality, score))

    /** Writing the SQLite store failed. */
    class DatabaseWrite(val detail: String) : BibleImportError(AppText.get(R.string.data_import_database_write, detail))

    /** The case's associated value, for equality. */
    private val payload: Any?
        get() = when (this) {
            is UnreadableFile -> detail
            is DamagedArchive -> detail
            is EntryTooLarge -> name
            is NotAnEPUB -> detail
            is UnsupportedFormat -> detail
            is ProtectedByDRM -> evidence
            is DatabaseWrite -> detail
            is PoorQuality -> score
            is NotAZipArchive, is NoScriptureFound, is MissingCopyright -> null
        }

    override fun equals(other: Any?): Boolean =
        other is BibleImportError && other::class == this::class && other.payload == payload

    override fun hashCode(): Int = this::class.hashCode() * 31 + (payload?.hashCode() ?: 0)

    override fun toString(): String = "${this::class.simpleName}(${payload ?: ""})"
}

/**
 * What made the engine decide a file is protected. Named, not described, so the refusal is a tested
 * invariant rather than a string comparison.
 *
 * The engine ships no decryption of any kind: circumventing a technical protection measure is a
 * separate violation from copyright, and owning the file does not excuse it. These cases exist so the
 * engine can *stop*, never so it can *proceed differently*. A visible watermark is not DRM and is not
 * detected here. [rawValue] is the Swift case name, for anything persisted.
 */
enum class DRMEvidence(val rawValue: String, @StringRes private val explanationRes: Int) {
    /** `META-INF/encryption.xml` — the OCF encryption manifest. */
    ENCRYPTION_MANIFEST("encryptionManifest", R.string.data_import_drm_encryption_manifest),

    /** Adobe ADEPT: `META-INF/rights.xml`, or an `Adept.*` key in the package metadata. */
    ADOBE_ADEPT("adobeADEPT", R.string.data_import_drm_adobe_adept),

    /** Readium LCP: `META-INF/license.lcpl`. */
    READIUM_LCP("readiumLCP", R.string.data_import_drm_readium_lcp),

    /** Apple FairPlay: `META-INF/sinf.xml`. */
    APPLE_FAIRPLAY("appleFairPlay", R.string.data_import_drm_apple_fairplay),

    /** The ZIP's own entry-encryption bit is set. */
    ZIP_ENTRY_ENCRYPTION("zipEntryEncryption", R.string.data_import_drm_zip_entry_encryption);

    /** Why the file is refused, in the reader's language. */
    val explanation: String get() = AppText.get(explanationRes)
}
