package com.blainemiller.scripturealone.data.importer

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
    class UnreadableFile(val detail: String) : BibleImportError("Couldn’t read that file: $detail")

    /** Not a ZIP container (an ePub is a ZIP). */
    class NotAZipArchive : BibleImportError("That file isn’t an ePub — it isn’t a ZIP container.")

    /** A ZIP that is truncated, mis-indexed, or whose contents fail their checksum. */
    class DamagedArchive(val detail: String) : BibleImportError("That ePub is damaged: $detail")

    /** An entry that claims a size we will not inflate. */
    class EntryTooLarge(val name: String) : BibleImportError("That ePub contains an implausibly large file ($name).")

    /** A ZIP that is not an ePub: wrong mimetype, no container.xml, no package document. */
    class NotAnEPUB(val detail: String) : BibleImportError("That file isn’t a readable ePub: $detail")

    /** A file the engine has no reader for. */
    class UnsupportedFormat(val detail: String) : BibleImportError("This app can’t read that file: $detail")

    /** The file is protected. The engine refuses it and reads none of its content. */
    class ProtectedByDRM(val evidence: DRMEvidence) :
        BibleImportError("${evidence.explanation} This app cannot open protected files.")

    /** The ePub opened and parsed, but nothing in it looked like scripture. */
    class NoScriptureFound : BibleImportError("No Bible text was found in that ePub.")

    /** An import must carry a copyright line forward; the file had none and the caller supplied none. */
    class MissingCopyright :
        BibleImportError("That ePub carries no copyright line. Enter the publisher’s copyright notice to continue.")

    /** Writing the SQLite store failed. */
    class DatabaseWrite(val detail: String) : BibleImportError("Couldn’t save the imported text: $detail")

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
enum class DRMEvidence(val rawValue: String, val explanation: String) {
    /** `META-INF/encryption.xml` — the OCF encryption manifest. */
    ENCRYPTION_MANIFEST("encryptionManifest", "That ePub is encrypted (it carries an encryption manifest)."),

    /** Adobe ADEPT: `META-INF/rights.xml`, or an `Adept.*` key in the package metadata. */
    ADOBE_ADEPT("adobeADEPT", "That ePub is protected with Adobe DRM."),

    /** Readium LCP: `META-INF/license.lcpl`. */
    READIUM_LCP("readiumLCP", "That ePub is protected with an LCP licence."),

    /** Apple FairPlay: `META-INF/sinf.xml`. */
    APPLE_FAIRPLAY("appleFairPlay", "That ePub is protected with Apple’s FairPlay DRM."),

    /** The ZIP's own entry-encryption bit is set. */
    ZIP_ENTRY_ENCRYPTION("zipEntryEncryption", "That ePub’s contents are password-encrypted."),
}
