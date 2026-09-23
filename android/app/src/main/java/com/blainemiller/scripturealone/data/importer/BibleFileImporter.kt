package com.blainemiller.scripturealone.data.importer

import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.text.AppText
import java.io.File
import java.io.IOException

/**
 * The kinds of file the engine reads. Both are ZIP containers, and both are refused outright if they
 * carry any protection artifact.
 */
enum class ImportedFileFormat(val rawValue: String, val label: String) {
    /** A DRM-free ePub the user already owns. */
    EPUB("epub", "ePub"),

    /** A zip of USFM books — the shape eBible.org publishes. */
    USFM_ZIP("usfmZip", "USFM"),
}

/** What a file turned out to be, and what it says about itself, without parsing its scripture. */
data class BibleImportPreview(
    val format: ImportedFileFormat,
    /**
     * A starting point for the import sheet. The user is expected to confirm the name and the
     * copyright line; nothing here is trusted.
     */
    val identity: ImportedTranslationIdentity,
    /** Spine documents (ePub) or USFM books (zip). */
    val documentCount: Int,
) {
    /**
     * True when the file itself carried a copyright line. When false the UI must collect one:
     * [ImportedBibleBuilder] refuses to write a store with nothing to attribute.
     */
    val hasCopyright: Boolean get() = SwiftText.trimWhitespaceAndNewlines(identity.copyright).isNotEmpty()
}

data class BibleImportResult(
    val storeFile: File,
    val format: ImportedFileFormat,
    val identity: ImportedTranslationIdentity,
    val report: ImportCoverageReport,
)

/**
 * One entry point: hand it a file and it works out what the file is, reads it, and writes a store the
 * reader can open. Ported from `Import/BibleFileImporter.swift`.
 *
 * Nothing here touches the main thread or the network. Importing a whole Bible takes seconds, so call
 * it from a background dispatcher. The engine writes one local store file and nothing else: no
 * export, no sharing, no sync. An imported text is for the user who imported it, on the device they
 * imported it to.
 *
 * [openStore] is how the store gets written — [BundledStoreWriter.opener] in the app. It is a
 * parameter (Swift links SQLite directly) so the JVM tests can write through JDBC.
 */
class BibleFileImporter(
    val openStore: ImportedStoreWriter.Opener,
    val options: BibleTextExtractor.Options = BibleTextExtractor.Options(),
) {
    /** Looks at the file and says what it is. Refuses protected files before reading any content. */
    fun preview(file: File): BibleImportPreview = preview(open(file))

    /**
     * Reads the file's scripture without writing anything — for callers that want to show the coverage
     * report before committing.
     */
    fun read(file: File): Pair<ExtractedBible, BibleImportPreview> {
        val zip = open(file)
        return when (format(zip)) {
            ImportedFileFormat.EPUB -> {
                val pkg = EPUBPackage(zip)
                val bible = BibleTextExtractor(options).extract(pkg)
                bible to BibleImportPreview(ImportedFileFormat.EPUB, ImportedTranslationIdentity.suggested(pkg.metadata), pkg.spine.size)
            }
            ImportedFileFormat.USFM_ZIP -> {
                val pkg = USFMPackage(zip)
                val bible = USFMImporter(options).extract(pkg)
                bible to BibleImportPreview(ImportedFileFormat.USFM_ZIP, ImportedTranslationIdentity.suggested(pkg.metadata), pkg.files.size)
            }
        }
    }

    /**
     * Reads the file and writes the store. [identity] overrides what the file said about itself — the
     * import sheet is expected to pass the name and copyright line the user confirmed.
     */
    fun importBible(file: File, identity: ImportedTranslationIdentity? = null, directory: File): BibleImportResult {
        val (bible, preview) = read(file)
        val chosen = identity ?: preview.identity
        val storeFile = File(directory, storeFilename(chosen))
        val report = ImportedBibleBuilder.write(bible, chosen, storeFile, openStore)
        return BibleImportResult(storeFile, preview.format, chosen, report)
    }

    private fun open(file: File): ZipReader {
        val data = try {
            file.readBytes()
        } catch (error: IOException) {
            throw BibleImportError.UnreadableFile(error.message ?: error.toString())
        }
        return ZipReader(data)
    }

    private fun preview(zip: ZipReader): BibleImportPreview = when (format(zip)) {
        ImportedFileFormat.EPUB -> {
            val pkg = EPUBPackage(zip)
            BibleImportPreview(ImportedFileFormat.EPUB, ImportedTranslationIdentity.suggested(pkg.metadata), pkg.spine.size)
        }
        ImportedFileFormat.USFM_ZIP -> {
            val pkg = USFMPackage(zip)
            BibleImportPreview(ImportedFileFormat.USFM_ZIP, ImportedTranslationIdentity.suggested(pkg.metadata), pkg.files.size)
        }
    }

    companion object {
        /** `IMPORT-XXXX.sqlite`, beside the bundled `ASV.sqlite` naming but never colliding with it. */
        fun storeFilename(identity: ImportedTranslationIdentity): String {
            val characters = SwiftCharacters(identity.id)
            val safe = buildString {
                for (i in 0 until characters.count) {
                    if (characters.isLetter(i) || characters.isNumber(i) || characters.isChar(i, '-') || characters.isChar(i, '_')) {
                        characters.appendTo(this, i)
                    }
                }
            }
            return safe.ifEmpty { "IMPORT" } + ".sqlite"
        }

        /**
         * Decides by structure, not by file extension — a `.zip` holding an ePub is an ePub. The
         * protection check comes first, so a protected file is refused as protected rather than as an
         * unreadable format, and no content is decompressed either way.
         */
        internal fun format(zip: ZipReader): ImportedFileFormat {
            EPUBPackage.protectionEvidence(zip.names)?.let { throw BibleImportError.ProtectedByDRM(it) }
            if (zip.entries.any { it.isEncrypted }) throw BibleImportError.ProtectedByDRM(DRMEvidence.ZIP_ENTRY_ENCRYPTION)
            if (zip.contains("META-INF/container.xml")) return ImportedFileFormat.EPUB
            if (zip.names.any { it.lowercase().endsWith(".usfm") || it.lowercase().endsWith(".sfm") }) return ImportedFileFormat.USFM_ZIP
            if (zip.names.any { it.lowercase().endsWith(".xhtml") || it.lowercase().endsWith(".html") }) {
                throw BibleImportError.NotAnEPUB(AppText.get(R.string.data_import_detail_no_container))
            }
            throw BibleImportError.UnsupportedFormat(AppText.get(R.string.data_import_detail_unknown_format))
        }
    }
}
