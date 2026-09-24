package com.blainemiller.scripturealone.data.importer

import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.text.AppText
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Dublin Core metadata from the package document. The [rights] line is the one that matters most: it
 * is the publisher's copyright notice, and it has to survive into the imported store.
 */
data class EPUBMetadata(
    val title: String? = null,
    val creator: String? = null,
    val publisher: String? = null,
    val rights: String? = null,
    val language: String? = null,
    val identifier: String? = null,
    val date: String? = null,
    val subjects: List<String> = emptyList(),
)

data class EPUBManifestItem(
    val id: String,
    /** Path inside the archive, resolved against the package document's directory. */
    val path: String,
    val mediaType: String,
    val properties: List<String>,
) {
    val isXHTML: Boolean
        get() {
            val lowered = path.lowercase()
            return mediaType.contains("xhtml") || mediaType.contains("text/html") ||
                lowered.endsWith(".xhtml") || lowered.endsWith(".html") || lowered.endsWith(".htm")
        }
}

/**
 * An ePub opened for reading: container, package document, manifest, spine order and metadata.
 * Ported from `Import/EPUBPackage.swift`.
 *
 * Opening is refusal-first. Before anything is decompressed, the archive's *names* are checked for
 * protection artifacts, and a match ends the import there. The engine contains no decryption code of
 * any kind and never will: circumventing a technical protection measure is a separate violation from
 * copyright and is not excused by the user having bought the file. A file that merely carries a
 * visible watermark is not protected and opens normally.
 */
class EPUBPackage internal constructor(private val zip: ZipReader) {
    val metadata: EPUBMetadata
    val manifest: List<EPUBManifestItem>

    /** Reading order, resolved to manifest items (idrefs that name nothing are dropped). */
    val spine: List<EPUBManifestItem>

    /** Path of the package document inside the archive. */
    val packagePath: String

    constructor(data: ByteArray) : this(ZipReader(data))

    init {
        refuseIfProtected(zip)

        // The mimetype entry is the ePub's own declaration of what it is.
        zip.entry("mimetype")?.let { mimetype ->
            val declared = SwiftText.trimWhitespaceAndNewlines(String(zip.data(mimetype), Charsets.UTF_8))
            if (declared != "application/epub+zip") throw BibleImportError.NotAnEPUB("its mimetype is “$declared”")
        }

        if (!zip.contains("META-INF/container.xml")) throw BibleImportError.NotAnEPUB(AppText.get(R.string.data_import_detail_no_container))
        val containerXML = text(zip.data("META-INF/container.xml"))
        val rootPath = rootfilePath(containerXML) ?: throw BibleImportError.NotAnEPUB("its container names no package document")
        if (!zip.contains(rootPath)) throw BibleImportError.NotAnEPUB("its package document ($rootPath) is missing")
        packagePath = rootPath

        val opf = text(zip.data(rootPath))
        val parsed = parsePackage(opf, directory(rootPath))
        metadata = parsed.metadata
        manifest = parsed.manifest
        val byID = LinkedHashMap<String, EPUBManifestItem>()
        for (item in parsed.manifest) byID.putIfAbsent(item.id, item)
        var order = parsed.spine.mapNotNull { byID[it] }
        if (order.isEmpty()) {
            // A malformed spine should not cost us the whole book: fall back to manifest order.
            order = parsed.manifest.filter { it.isXHTML }
        }
        spine = order.filter { it.isXHTML && zip.contains(it.path) }
    }

    /** The raw bytes of any file in the archive — a picture a document shows. */
    fun data(path: String): ByteArray = zip.data(path)

    /** The decoded text of one document in the archive. */
    fun document(item: EPUBManifestItem): String = text(zip.data(item.path))

    /** Every stylesheet the manifest lists, decoded. Unreadable ones are skipped: styling is a hint. */
    val stylesheets: List<String>
        get() = manifest.filter { it.mediaType == "text/css" }.mapNotNull {
            try {
                document(it)
            } catch (_: BibleImportError) {
                null
            }
        }

    /** Every file name in the archive, for diagnostics. Reading a name decompresses nothing. */
    val entryNames: List<String> get() = zip.names

    internal class ParsedPackage {
        var metadata = EPUBMetadata()
        val manifest = ArrayList<EPUBManifestItem>()
        val spine = ArrayList<String>()
    }

    companion object {
        fun open(file: File): EPUBPackage {
            val data = try {
                file.readBytes()
            } catch (error: IOException) {
                throw BibleImportError.UnreadableFile(error.message ?: error.toString())
            }
            return EPUBPackage(data)
        }

        // MARK: - Refusal

        /** Protection artifacts, matched on archive *names* only — nothing is decompressed to decide this. */
        fun protectionEvidence(names: List<String>): DRMEvidence? {
            for (name in names) {
                val lowered = name.lowercase()
                val leaf = SwiftText.split(lowered, '/').lastOrNull() ?: lowered
                when (leaf) {
                    "encryption.xml" -> return DRMEvidence.ENCRYPTION_MANIFEST
                    "rights.xml" -> return DRMEvidence.ADOBE_ADEPT
                    "license.lcpl" -> return DRMEvidence.READIUM_LCP
                    "sinf.xml" -> return DRMEvidence.APPLE_FAIRPLAY
                }
                if (leaf.endsWith(".adept") || lowered.contains("adept")) return DRMEvidence.ADOBE_ADEPT
                if (leaf.endsWith(".lcpl")) return DRMEvidence.READIUM_LCP
            }
            return null
        }

        private fun refuseIfProtected(zip: ZipReader) {
            protectionEvidence(zip.names)?.let { throw BibleImportError.ProtectedByDRM(it) }
            if (zip.entries.any { it.isEncrypted }) throw BibleImportError.ProtectedByDRM(DRMEvidence.ZIP_ENTRY_ENCRYPTION)
        }

        // MARK: - Container and package parsing

        fun rootfilePath(containerXML: String): String? {
            for (event in XMLScanner.scan(containerXML)) {
                if (event !is XMLEvent.Start || event.tag.name != "rootfile") continue
                val path = event.tag.attribute("full-path")
                if (!path.isNullOrEmpty()) return normalizePath(path)
            }
            return null
        }

        internal fun parsePackage(opf: String, base: String): ParsedPackage {
            val parsed = ParsedPackage()
            var section = ""              // "metadata" | "manifest" | "spine"
            var metaField: String? = null // the dc:* element whose text we are collecting
            val metaText = StringBuilder()

            fun commitMetaField() {
                val field = metaField ?: return
                val value = SwiftText.trimWhitespaceAndNewlines(metaText.toString())
                metaField = null
                metaText.setLength(0)
                if (value.isEmpty()) return
                val m = parsed.metadata
                parsed.metadata = when (field) {
                    "title" -> m.copy(title = m.title ?: value)
                    "creator" -> m.copy(creator = m.creator ?: value)
                    "publisher" -> m.copy(publisher = m.publisher ?: value)
                    "rights" -> m.copy(rights = m.rights ?: value)
                    "language" -> m.copy(language = m.language ?: value)
                    "identifier" -> m.copy(identifier = m.identifier ?: value)
                    "date" -> m.copy(date = m.date ?: value)
                    "subject" -> m.copy(subjects = m.subjects + value)
                    else -> m
                }
            }

            for (event in XMLScanner.scan(opf)) {
                when (event) {
                    is XMLEvent.Start -> {
                        val tag = event.tag
                        when {
                            tag.name == "metadata" || tag.name == "manifest" || tag.name == "spine" -> section = tag.name
                            tag.name == "meta" && section == "metadata" -> {
                                // Adobe ADEPT announces itself here even when rights.xml was stripped.
                                val name = (tag.attribute("name") ?: "").lowercase()
                                val property = (tag.attribute("property") ?: "").lowercase()
                                if (name.startsWith("adept.") || property.startsWith("adept.")) {
                                    throw BibleImportError.ProtectedByDRM(DRMEvidence.ADOBE_ADEPT)
                                }
                            }
                            tag.name == "item" && section == "manifest" -> {
                                val id = tag.attribute("id")
                                val href = tag.attribute("href")
                                if (id != null && href != null) {
                                    val properties = SwiftText.split(tag.attribute("properties") ?: "") { chars, i -> chars.isWhitespace(i) }
                                    parsed.manifest.add(EPUBManifestItem(id, resolve(base, href), tag.attribute("media-type") ?: "", properties))
                                }
                            }
                            tag.name == "itemref" && section == "spine" -> tag.attribute("idref")?.let { parsed.spine.add(it) }
                            tag.name in metadataFields -> if (section == "metadata") {
                                commitMetaField()
                                metaField = tag.name
                                metaText.setLength(0)
                            }
                        }
                    }
                    is XMLEvent.Text -> if (metaField != null) metaText.append(event.text)
                    is XMLEvent.End -> {
                        if (event.name == metaField) commitMetaField()
                        if (event.name == "metadata" || event.name == "manifest" || event.name == "spine") section = ""
                    }
                }
            }
            commitMetaField()
            if (parsed.manifest.isEmpty()) throw BibleImportError.NotAnEPUB("its package document lists no files")
            return parsed
        }

        private val metadataFields = setOf("title", "creator", "publisher", "rights", "language", "identifier", "date", "subject")

        // MARK: - Paths

        fun directory(path: String): String {
            val slash = path.lastIndexOf('/')
            return if (slash < 0) "" else path.substring(0, slash + 1)
        }

        /**
         * Resolves a manifest href against the package document's directory, dropping any fragment and
         * percent-decoding the name so it matches the archive's own entry names.
         */
        fun resolve(base: String, href: String): String {
            var target = href
            val hash = target.indexOf('#')
            if (hash >= 0) target = target.substring(0, hash)
            target = removingPercentEncoding(target) ?: target
            if (target.startsWith("/")) return normalizePath(target.substring(1))
            return normalizePath(base + target)
        }

        /** Collapses "." and ".." segments. */
        fun normalizePath(path: String): String {
            val stack = ArrayList<String>()
            for (piece in SwiftText.split(path, '/')) {
                when (piece) {
                    "." -> continue
                    ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    else -> stack.add(piece)
                }
            }
            return stack.joinToString("/")
        }

        /**
         * Foundation's `removingPercentEncoding`: every `%` must start a two-digit hex escape and the
         * bytes must be valid UTF-8, or the answer is null. `+` stays a plus. (Not `URLDecoder`, which
         * turns `+` into a space and whose charset overload is API 33.)
         */
        internal fun removingPercentEncoding(s: String): String? {
            if (!s.contains('%')) return s
            val out = ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%') {
                    if (i + 2 >= s.length) return null
                    val hi = Character.digit(s[i + 1], 16)
                    val lo = Character.digit(s[i + 2], 16)
                    if (hi < 0 || lo < 0 || s[i + 1].code > 0x7F || s[i + 2].code > 0x7F) return null
                    out.write(hi * 16 + lo)
                    i += 3
                } else {
                    val end = if (Character.isHighSurrogate(c) && i + 1 < s.length) i + 2 else i + 1
                    out.write(s.substring(i, end).toByteArray(Charsets.UTF_8))
                    i = end
                }
            }
            return decodeStrictly(out.toByteArray(), Charsets.UTF_8)
        }

        private fun decodeStrictly(bytes: ByteArray, charset: Charset): String? = try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

        /**
         * Text in whatever encoding the file used: UTF-16 by its byte-order mark, else UTF-8, else
         * Latin-1. A byte-order mark is not text, so it is dropped: Foundation's UTF-8 decoding drops
         * its own, and the Swift reader drops the U+FEFF an explicit-endian UTF-16 decode keeps.
         */
        fun text(data: ByteArray): String {
            if (data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) {
                return (decodeStrictly(data, Charsets.UTF_16LE) ?: String(data, Charsets.UTF_8)).removePrefix("\uFEFF")
            }
            if (data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()) {
                return (decodeStrictly(data, Charsets.UTF_16BE) ?: String(data, Charsets.UTF_8)).removePrefix("\uFEFF")
            }
            decodeStrictly(data, Charsets.UTF_8)?.let { return it.removePrefix("﻿") }
            return String(data, Charsets.ISO_8859_1)
        }
    }
}
