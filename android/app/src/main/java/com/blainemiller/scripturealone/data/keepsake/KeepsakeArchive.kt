package com.blainemiller.scripturealone.data.keepsake

import com.blainemiller.scripturealone.data.VerseRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * Reads and writes `.scripturelegacy` files, ported from `Keepsake/KeepsakeArchive.swift`.
 *
 * A keepsake is a ZIP archive of `manifest.json`, `highlights.json`, `notes.json` and `README.txt`.
 * A protected keepsake holds the three JSON files in an inner ZIP sealed with AES-256-GCM
 * (`payload.sealed`); its outer `manifest.json` carries only the format fields, the encryption
 * parameters and an optional hint. See [KeepsakeCrypto].
 *
 * JSON is written as the Swift encoder writes it — sorted keys, two-space indent, `" : "`, unescaped
 * slashes, millisecond ISO 8601 dates, upper-case UUIDs — so a keepsake from either app reads the same.
 */
object KeepsakeArchive {
    const val FILE_EXTENSION = "scripturelegacy"
    const val PAYLOAD_NAME = "payload.sealed"

    fun encode(
        keepsake: Keepsake, passphrase: String? = null, hint: String? = null,
        iterations: Int = KeepsakeCrypto.DEFAULT_ITERATIONS,
    ): ByteArray {
        val manifest = keepsake.manifest.copy(
            encryption = null, passphraseHint = null,
            formatVersion = KeepsakeManifest.CURRENT_VERSION, format = KeepsakeManifest.FORMAT_IDENTIFIER,
        )
        val date = manifest.createdAt
        val inner = listOf(
            ZipArchive.Entry("manifest.json", json(manifestJson(manifest)), date),
            ZipArchive.Entry("highlights.json", json(obj("highlights" to JsonArray(keepsake.highlights.map(::highlightJson)))), date),
            ZipArchive.Entry("notes.json", json(obj("notes" to JsonArray(keepsake.notes.map(::noteJson)))), date),
        )

        if (passphrase.isNullOrEmpty()) {
            return ZipArchive.write(inner + ZipArchive.Entry("README.txt", readme(protected = false).toByteArray(), date))
        }

        val salt = KeepsakeCrypto.randomSalt()
        val trimmedHint = hint?.trim()?.ifEmpty { null }
        val outer = KeepsakeManifest(
            bibleID = manifest.bibleID, generator = manifest.generator, createdAt = manifest.createdAt,
            exportID = manifest.exportID, minimumReaderVersion = manifest.minimumReaderVersion,
            encryption = KeepsakeManifest.Encryption(
                KeepsakeCrypto.ALGORITHM, KeepsakeCrypto.KDF, iterations,
                Base64.getEncoder().encodeToString(salt), PAYLOAD_NAME,
            ),
            passphraseHint = trimmedHint,
        )
        val outerData = json(manifestJson(outer))
        val key = KeepsakeCrypto.deriveKey(passphrase, salt, iterations)
        val sealed = KeepsakeCrypto.seal(ZipArchive.write(inner), key, outerData)
        return ZipArchive.write(listOf(
            ZipArchive.Entry("manifest.json", outerData, date),
            ZipArchive.Entry(PAYLOAD_NAME, sealed, date),
            ZipArchive.Entry("README.txt", readme(protected = true).toByteArray(), date),
        ))
    }

    /** The outer manifest, without a passphrase: enough to show a hint or ask for one. */
    fun peek(data: ByteArray): KeepsakeManifest = manifest(entries(data)).first

    fun decode(data: ByteArray, passphrase: String? = null): Keepsake {
        val files = entries(data)
        val (outer, outerData) = manifest(files)
        val encryption = outer.encryption ?: return contents(files, outer)

        if (encryption.algorithm != KeepsakeCrypto.ALGORITHM || encryption.kdf != KeepsakeCrypto.KDF) {
            throw KeepsakeException.NewerVersion(outer.formatVersion)
        }
        if (passphrase.isNullOrEmpty()) throw KeepsakeException.PassphraseRequired()
        val salt = runCatching { Base64.getDecoder().decode(encryption.salt) }.getOrNull()
        val sealed = files[encryption.payload]
        if (salt == null || sealed == null) throw KeepsakeException.Damaged("missing encrypted payload")
        val key = KeepsakeCrypto.deriveKey(passphrase, salt, encryption.iterations)
        val innerFiles = entries(KeepsakeCrypto.open(sealed, key, outerData))
        return contents(innerFiles, manifest(innerFiles).first)
    }

    // Pieces

    private fun entries(data: ByteArray): Map<String, ByteArray> = try {
        ZipArchive.read(data)
    } catch (_: ZipArchive.ZipException.NotAZip) {
        throw KeepsakeException.NotAKeepsake()
    } catch (e: ZipArchive.ZipException) {
        throw KeepsakeException.Damaged(e.message ?: "archive")
    }

    private fun manifest(files: Map<String, ByteArray>): Pair<KeepsakeManifest, ByteArray> {
        val data = files["manifest.json"] ?: throw KeepsakeException.NotAKeepsake()
        val manifest = try {
            parseManifest(parse(data))
        } catch (e: KeepsakeException) {
            throw e
        } catch (_: Exception) {
            throw KeepsakeException.Damaged("manifest")
        }
        if (manifest.format != KeepsakeManifest.FORMAT_IDENTIFIER) throw KeepsakeException.NotAKeepsake()
        if (manifest.minimumReaderVersion > KeepsakeManifest.SUPPORTED_READER_VERSION) {
            throw KeepsakeException.NewerVersion(manifest.formatVersion)
        }
        return manifest to data
    }

    private fun contents(files: Map<String, ByteArray>, manifest: KeepsakeManifest): Keepsake {
        val (highlights, notes) = try {
            val highlights = files["highlights.json"]?.let { data ->
                array(parse(data).obj()["highlights"]).map { parseHighlight(it.obj()) }
            } ?: emptyList()
            val notes = files["notes.json"]?.let { data ->
                array(parse(data).obj()["notes"]).map { parseNote(it.obj()) }
            } ?: emptyList()
            highlights to notes
        } catch (_: Exception) {
            throw KeepsakeException.Damaged("contents")
        }
        return Keepsake(
            // Drop anything that doesn't point at a real verse.
            manifest = manifest.copy(encryption = null, passphraseHint = null),
            highlights = highlights.filter { VerseRange.ref(it.verse) != null },
            notes = notes,
        )
    }

    // Writing JSON

    private fun manifestJson(m: KeepsakeManifest) = obj(
        "format" to JsonPrimitive(m.format),
        "formatVersion" to JsonPrimitive(m.formatVersion),
        "minimumReaderVersion" to JsonPrimitive(m.minimumReaderVersion),
        "exportID" to uuid(m.exportID),
        "bibleID" to uuid(m.bibleID),
        "createdAt" to date(m.createdAt),
        "generator" to JsonPrimitive(m.generator),
        "ownerName" to m.ownerName?.let(::JsonPrimitive),
        "dedication" to m.dedication?.let(::JsonPrimitive),
        "preferredTranslation" to m.preferredTranslation?.let(::JsonPrimitive),
        "dateRange" to m.dateRange?.let { obj("start" to date(it.start), "end" to date(it.end)) },
        "counts" to m.counts?.let { obj("highlights" to JsonPrimitive(it.highlights), "notes" to JsonPrimitive(it.notes)) },
        "encryption" to m.encryption?.let {
            obj(
                "algorithm" to JsonPrimitive(it.algorithm), "kdf" to JsonPrimitive(it.kdf),
                "iterations" to JsonPrimitive(it.iterations), "salt" to JsonPrimitive(it.salt),
                "payload" to JsonPrimitive(it.payload),
            )
        },
        "passphraseHint" to m.passphraseHint?.let(::JsonPrimitive),
    )

    private fun highlightJson(h: KeepsakeHighlight) = obj(
        "verse" to JsonPrimitive(h.verse),
        "color" to JsonPrimitive(h.color),
        "createdAt" to date(h.createdAt),
        // For people reading the JSON by hand; ignored when reading.
        "reference" to VerseRange.ref(h.verse)?.let { JsonPrimitive(VerseRange.of(it).display) },
    )

    private fun noteJson(n: KeepsakeNote) = obj(
        "id" to uuid(n.id),
        "title" to JsonPrimitive(n.title),
        "body" to JsonPrimitive(n.body),
        "passages" to JsonArray(n.passages.map { p ->
            obj("start" to JsonPrimitive(p.start), "end" to JsonPrimitive(p.end), "reference" to p.range?.display?.let(::JsonPrimitive))
        }),
        "createdAt" to date(n.createdAt),
        "updatedAt" to date(n.updatedAt),
        "origin" to JsonPrimitive(n.origin),
    )

    /** An object with its null fields left out, as Swift's `encodeIfPresent` does. */
    private fun obj(vararg fields: Pair<String, JsonElement?>): JsonObject =
        JsonObject(fields.mapNotNull { (k, v) -> v?.let { k to it } }.toMap())

    private fun uuid(id: UUID) = JsonPrimitive(id.toString().uppercase())

    private val iso8601Millis = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** Nearest millisecond (Swift rounds rather than truncating). */
    private fun date(instant: Instant): JsonPrimitive {
        val rounded = Instant.ofEpochSecond(instant.epochSecond).plusMillis(Math.round(instant.nano / 1_000_000.0))
        return JsonPrimitive(iso8601Millis.format(rounded))
    }

    /** Sorted keys, two-space indent, `" : "` — the bytes Swift's pretty-printing encoder writes. */
    internal fun json(element: JsonElement): ByteArray = StringBuilder().also { write(element, it, 0) }.toString().toByteArray()

    private fun write(element: JsonElement, out: StringBuilder, depth: Int) {
        fun indent(level: Int) = repeat(level) { out.append("  ") }
        when (element) {
            is JsonObject -> {
                if (element.isEmpty()) { out.append("{\n\n"); indent(depth); out.append('}'); return }
                out.append("{\n")
                element.entries.sortedBy { it.key }.forEachIndexed { i, (key, value) ->
                    indent(depth + 1)
                    out.append(JsonPrimitive(key).toString()).append(" : ")
                    write(value, out, depth + 1)
                    out.append(if (i < element.size - 1) ",\n" else "\n")
                }
                indent(depth); out.append('}')
            }
            is JsonArray -> {
                if (element.isEmpty()) { out.append("[\n\n"); indent(depth); out.append(']'); return }
                out.append("[\n")
                element.forEachIndexed { i, value ->
                    indent(depth + 1)
                    write(value, out, depth + 1)
                    out.append(if (i < element.size - 1) ",\n" else "\n")
                }
                indent(depth); out.append(']')
            }
            is JsonPrimitive -> out.append(element.toString())
        }
    }

    // Reading JSON — tolerant: every field but `format` may be missing, and unknown keys are ignored.

    private fun parse(data: ByteArray): JsonElement = Json.parseToJsonElement(String(data, Charsets.UTF_8))

    private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: error("expected an object")
    private fun array(element: JsonElement?): JsonArray = element as? JsonArray ?: error("expected an array")

    /** Present-and-null counts as absent, as `decodeIfPresent` does; the wrong type is an error. */
    private fun JsonObject.present(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

    private fun JsonObject.string(key: String): String? = present(key)?.let {
        val p = it as? JsonPrimitive
        require(p != null && p.isString) { "$key is not a string" }
        p.content
    }

    private fun JsonObject.int(key: String): Int? = present(key)?.let {
        val p = it as? JsonPrimitive
        require(p != null && !p.isString) { "$key is not a number" }
        p.intOrNull ?: error("$key is not an integer")
    }

    private fun JsonObject.uuid(key: String): UUID? = string(key)?.let(UUID::fromString)
    private fun JsonObject.date(key: String): Instant? = string(key)?.let(::parseDate)

    /** `2026-09-21T14:13:20.125Z`, with or without fractions, or with an offset. */
    internal fun parseDate(text: String): Instant =
        runCatching { Instant.parse(text) }.getOrNull() ?: OffsetDateTime.parse(text).toInstant()

    /** Swift's `Date.distantPast`, the stand-in for a missing date. */
    private val DISTANT_PAST: Instant = Instant.parse("0001-01-01T00:00:00Z")

    private fun parseManifest(element: JsonElement): KeepsakeManifest {
        val o = element.obj()
        val exportID = o.uuid("exportID") ?: UUID.randomUUID()
        return KeepsakeManifest(
            format = o.string("format") ?: error("no format"),
            formatVersion = o.int("formatVersion") ?: 1,
            minimumReaderVersion = o.int("minimumReaderVersion") ?: 1,
            exportID = exportID,
            bibleID = o.uuid("bibleID") ?: exportID,
            createdAt = o.date("createdAt") ?: Instant.now().truncatedTo(ChronoUnit.MILLIS),
            generator = o.string("generator") ?: "",
            ownerName = o.string("ownerName"),
            dedication = o.string("dedication"),
            preferredTranslation = o.string("preferredTranslation"),
            // A damaged summary is only a summary: Swift decodes these two with `try?`.
            dateRange = runCatching {
                o.present("dateRange")?.obj()?.let { KeepsakeManifest.DateRange(it.date("start")!!, it.date("end")!!) }
            }.getOrNull(),
            counts = runCatching {
                o.present("counts")?.obj()?.let { KeepsakeManifest.Counts(it.int("highlights")!!, it.int("notes")!!) }
            }.getOrNull(),
            encryption = o.present("encryption")?.obj()?.let {
                KeepsakeManifest.Encryption(it.string("algorithm")!!, it.string("kdf")!!, it.int("iterations")!!,
                    it.string("salt")!!, it.string("payload")!!)
            },
            passphraseHint = o.string("passphraseHint"),
        )
    }

    private fun parseHighlight(o: JsonObject) = KeepsakeHighlight(
        verse = o.int("verse") ?: error("no verse"),
        color = o.string("color") ?: "yellow",
        createdAt = o.date("createdAt") ?: DISTANT_PAST,
    )

    private fun parseNote(o: JsonObject): KeepsakeNote {
        val createdAt = o.date("createdAt") ?: DISTANT_PAST
        return KeepsakeNote(
            id = o.uuid("id") ?: UUID.randomUUID(),
            title = o.string("title") ?: "",
            body = o.string("body") ?: "",
            passages = o.present("passages")?.let { array(it) }?.map { p ->
                val passage = p.obj()
                val start = passage.int("start") ?: error("no start")
                KeepsakeNote.Passage(start, passage.int("end") ?: start)
            } ?: emptyList(),
            createdAt = createdAt,
            updatedAt = o.date("updatedAt") ?: createdAt,
            origin = o.string("origin") ?: "manual",
        )
    }

    // README

    /** Word for word the iOS app's README, so a keepsake explains itself the same way from either app. */
    internal fun readme(protected: Boolean): String {
        val text = StringBuilder(
            """
            LEGACY BIBLE KEEPSAKE
            =====================

            This file holds the highlights and notes someone made while reading the Bible in
            Scripture Alone, a free app for iPhone, iPad and Mac. Open it in Scripture Alone to
            read their Bible the way they marked it: their highlights in the text, their notes
            beside the verses. It is a read-only keepsake; nothing in it can be changed.

            The file is an ordinary ZIP archive. Rename it to end in .zip to look inside.

            """.trimIndent(),
        )
        if (protected) {
            text.append(
                "\n" + """
                This keepsake is protected with a passphrase. manifest.json describes how:
                PBKDF2-HMAC-SHA256 (with the salt and iteration count given) turns the passphrase
                into a 256-bit key, and payload.sealed is AES-256-GCM (12-byte nonce, then the
                ciphertext, then the 16-byte tag) with the exact bytes of manifest.json as
                associated data. Opened, it is a ZIP holding the files described below.

                """.trimIndent(),
            )
        }
        text.append(
            "\n" + """
            manifest.json    Whose Bible this is, a dedication, dates, the translation they read.
            highlights.json  Each highlighted verse. Verse numbers are book × 1,000,000 +
                             chapter × 1,000 + verse, with books numbered Genesis = 1 to
                             Revelation = 66 (so John 3:16 is 43003016).
            notes.json       Each note: title, text, the passages it is attached to, and dates.

            More about the format: https://wemiller.com/apps/scripture-alone/
            """.trimIndent(),
        )
        return text.toString()
    }
}
