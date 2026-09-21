package com.blainemiller.scripturealone.data.sabible

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** Who the translation is. Plaintext, and covered by the signature. */
data class PackagedTranslationIdentity(
    val id: String,
    val name: String,
    val abbreviation: String,
    val publisher: String,
    val copyright: String,
    val license: String,
)

/** Which primitives sealed and signed the package, and which keys — named rather than assumed. */
data class PackageCryptoParameters(
    val cipher: String,
    val signature: String,
    val aad: String,
    val keyId: String,
    val publisherKeyId: String,
)

/** Where one chapter's sealed blob sits in the body. Signed, so the body cannot be rearranged. */
data class PackagedChapterEntry(
    val book: Int,
    val chapter: Int,
    val verses: Int,
    /** Bytes from the start of the body. */
    val offset: Long,
    val length: Int,
) {
    val ref: ChapterRef get() = ChapterRef(book, chapter)
}

/**
 * The publisher's terms, as data the app enforces. Mirrors `PackagePolicy.swift`.
 *
 * **A missing field never reads as "allowed."** Every permission defaults to false and the
 * quotation cap to zero, exactly as the Swift decoder does, so a policy written by an older tool —
 * or a truncated one — grants nothing it did not say. `maxQuotationVerses` is a [Long] because the
 * shipped ASV carries Swift's `Int.max` (9223372036854775807) for "unlimited".
 */
data class PackagePolicy(
    val allowCopy: Boolean,
    val allowShare: Boolean,
    val allowVerseImages: Boolean,
    val allowNotesExport: Boolean,
    val allowExternalHandoff: Boolean,
    val allowOfflineStorage: Boolean,
    val maxQuotationVerses: Long,
    val expires: String?,
) {
    /**
     * The expiry as an instant, or null for a grant that does not lapse. Throws if `expires` is
     * present but unreadable: an expiry the app cannot parse must stop the package, never quietly
     * become "no expiry".
     */
    fun expiryInstant(): Instant? {
        val text = expires?.trim()
        if (text.isNullOrEmpty()) return null
        return parseIso8601(text)
            ?: throw TranslationPackageException.DamagedHeader("expires isn't an ISO 8601 date: $expires")
    }

    companion object {
        /** `2027-01-01T00:00:00Z`, or the bare `2027-01-01`, which means midnight UTC that morning. */
        internal fun parseIso8601(text: String): Instant? {
            try {
                return OffsetDateTime.parse(text).toInstant()
            } catch (e: DateTimeParseException) {
            }
            return try {
                LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant()
            } catch (e: DateTimeParseException) {
                null
            }
        }
    }
}

/** The whole plaintext header. The search index parameters are not read — see [PackageHeaderParser]. */
data class TranslationPackageHeader(
    val format: Int,
    /** Distinguishes this build of the package from every other; bound into each chapter. */
    val packageId: String,
    val createdAt: String,
    val translation: PackagedTranslationIdentity,
    val policy: PackagePolicy,
    val crypto: PackageCryptoParameters,
    val chapters: List<PackagedChapterEntry>,
)

/**
 * Decodes header and chapter JSON with the same strictness as Swift's `JSONDecoder`: a required
 * field that is missing or of the wrong type is a damaged header, not a default.
 *
 * Through `kotlinx.serialization`'s JSON tree rather than `org.json`, because `org.json` is part of
 * the Android framework and exists only as throwing stubs on the JVM — this reader has to run in a
 * plain unit test against the real package. The tree API needs no compiler plugin.
 *
 * The header's `index` (the sealed search index's parameters) is deliberately ignored: search is not
 * ported yet, and nothing on the chapter path depends on it. It is still covered by the signature,
 * since the signature is over the raw bytes, not over what this parser keeps.
 */
internal object PackageHeaderParser {

    fun parse(bytes: ByteArray): TranslationPackageHeader {
        val root = try {
            Json.parseToJsonElement(strictUtf8(bytes))
        } catch (e: CharacterCodingException) {
            throw TranslationPackageException.DamagedHeader("header is not UTF-8")
        } catch (e: IllegalArgumentException) {
            // kotlinx reports malformed JSON as SerializationException, a subclass of this.
            throw TranslationPackageException.DamagedHeader(e.message ?: "malformed JSON")
        }
        val header = root.obj("header")
        val translation = header.field("translation").obj("translation")
        val crypto = header.field("crypto").obj("crypto")
        val policy = header.field("policy").obj("policy")
        return TranslationPackageHeader(
            format = header.int("format"),
            packageId = header.string("packageID"),
            createdAt = header.string("createdAt"),
            translation = PackagedTranslationIdentity(
                id = translation.string("id"),
                name = translation.string("name"),
                abbreviation = translation.string("abbreviation"),
                publisher = translation.string("publisher"),
                copyright = translation.string("copyright"),
                license = translation.string("license"),
            ),
            policy = PackagePolicy(
                allowCopy = policy.optionalBoolean("allowCopy") ?: false,
                allowShare = policy.optionalBoolean("allowShare") ?: false,
                allowVerseImages = policy.optionalBoolean("allowVerseImages") ?: false,
                allowNotesExport = policy.optionalBoolean("allowNotesExport") ?: false,
                allowExternalHandoff = policy.optionalBoolean("allowExternalHandoff") ?: false,
                allowOfflineStorage = policy.optionalBoolean("allowOfflineStorage") ?: false,
                maxQuotationVerses = policy.optionalLong("maxQuotationVerses") ?: 0L,
                expires = policy.optionalString("expires"),
            ),
            crypto = PackageCryptoParameters(
                cipher = crypto.string("cipher"),
                signature = crypto.string("signature"),
                aad = crypto.string("aad"),
                keyId = crypto.string("keyID"),
                publisherKeyId = crypto.string("publisherKeyID"),
            ),
            chapters = header.field("chapters").array("chapters").map { element ->
                val entry = element.obj("chapter entry")
                PackagedChapterEntry(
                    book = entry.int("book"),
                    chapter = entry.int("chapter"),
                    verses = entry.int("verses"),
                    offset = entry.long("offset"),
                    length = entry.int("length"),
                )
            },
        )
    }

    /**
     * Rejects malformed UTF-8 rather than substituting U+FFFD, as Swift's `JSONDecoder` does. A
     * replacement character in a verse would be a silent corruption; this makes it a refusal.
     */
    fun strictUtf8(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    // Typed accessors. Each throws DamagedHeader naming the field, so a refusal says what was wrong.

    fun JsonElement.obj(what: String): JsonObject =
        this as? JsonObject ?: throw TranslationPackageException.DamagedHeader("$what is not an object")

    fun JsonElement.array(what: String): JsonArray =
        this as? JsonArray ?: throw TranslationPackageException.DamagedHeader("$what is not an array")

    fun JsonObject.field(name: String): JsonElement =
        this[name] ?: throw TranslationPackageException.DamagedHeader("missing $name")

    fun JsonObject.string(name: String): String {
        val value = field(name)
        if (value !is JsonPrimitive || !value.isString) {
            throw TranslationPackageException.DamagedHeader("$name is not a string")
        }
        return value.content
    }

    fun JsonObject.long(name: String): Long {
        val value = field(name)
        return (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            ?: throw TranslationPackageException.DamagedHeader("$name is not an integer")
    }

    fun JsonObject.int(name: String): Int {
        val value = long(name)
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
            throw TranslationPackageException.DamagedHeader("$name is out of range")
        }
        return value.toInt()
    }

    /** Absent or `null` is "not stated"; present with the wrong type is damage, as in Swift. */
    private fun JsonObject.present(name: String): JsonPrimitive? {
        val value = this[name] ?: return null
        if (value is JsonNull) return null
        return value as? JsonPrimitive ?: throw TranslationPackageException.DamagedHeader("$name has the wrong type")
    }

    fun JsonObject.optionalBoolean(name: String): Boolean? {
        val value = present(name) ?: return null
        return value.takeIf { !it.isString }?.booleanOrNull
            ?: throw TranslationPackageException.DamagedHeader("$name is not a boolean")
    }

    fun JsonObject.optionalLong(name: String): Long? {
        val value = present(name) ?: return null
        return value.takeIf { !it.isString }?.longOrNull
            ?: throw TranslationPackageException.DamagedHeader("$name is not an integer")
    }

    fun JsonObject.optionalString(name: String): String? {
        val value = present(name) ?: return null
        if (!value.isString) throw TranslationPackageException.DamagedHeader("$name is not a string")
        return value.content
    }
}
