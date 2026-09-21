package com.blainemiller.scripturealone.data.keepsake

import com.blainemiller.scripturealone.data.VerseRange
import java.time.Instant
import java.util.UUID

// A "Keepsake Bible" keepsake: a read-only snapshot of someone's highlights and notes, meant to be
// handed to family. Ported from `Keepsake/Keepsake.swift`; the file format is docs/heir-mode.md, and a
// keepsake made on either platform opens on the other.

/** Everything in a keepsake once opened. */
data class Keepsake(val manifest: KeepsakeManifest, val highlights: List<KeepsakeHighlight>, val notes: List<KeepsakeNote>) {

    val id: UUID get() = manifest.bibleID

    /** The manifest's counts and date range filled in from the contents. */
    fun withRefreshedSummary(): Keepsake {
        val dates = highlights.map { it.createdAt } + notes.flatMap { listOf(it.createdAt, it.updatedAt) }
        return copy(manifest = manifest.copy(
            counts = KeepsakeManifest.Counts(highlights.size, notes.size),
            dateRange = if (dates.isEmpty()) null else KeepsakeManifest.DateRange(dates.min(), dates.max()),
        ))
    }
}

data class KeepsakeManifest(
    val format: String = FORMAT_IDENTIFIER,
    /** The version of the writer that made the file. */
    val formatVersion: Int = CURRENT_VERSION,
    /**
     * The oldest reader that can open the file. Writers bump this only for changes old readers would
     * get wrong; additions alone never bump it (old readers ignore unknown keys).
     */
    val minimumReaderVersion: Int = 1,
    /** Unique per export. */
    val exportID: UUID = UUID.randomUUID(),
    /** Stable for one person's Bible across exports, so a newer keepsake replaces an older one. */
    val bibleID: UUID = UUID.randomUUID(),
    val createdAt: Instant = Instant.now(),
    val generator: String = "Scripture Alone",
    val ownerName: String? = null,
    val dedication: String? = null,
    /** Translation abbreviation the owner read in ("ASV"). */
    val preferredTranslation: String? = null,
    val dateRange: DateRange? = null,
    val counts: Counts? = null,
    /** Present only in the outer manifest of a protected keepsake. */
    val encryption: Encryption? = null,
    /** Shown before the passphrase is asked for. Stored in the clear. */
    val passphraseHint: String? = null,
) {
    data class Counts(val highlights: Int, val notes: Int)
    data class DateRange(val start: Instant, val end: Instant)
    data class Encryption(
        val algorithm: String,
        val kdf: String,
        val iterations: Int,
        /** Base64. */
        val salt: String,
        /** The archive entry holding the sealed contents. */
        val payload: String,
    )

    val isEncrypted: Boolean get() = encryption != null

    /** "Dad's Bible", or "A Keepsake Bible" when unnamed. */
    val displayTitle: String
        get() {
            val name = ownerName?.trim()
            if (name.isNullOrEmpty()) return "A Keepsake Bible"
            return if (name.endsWith("s") || name.endsWith("S")) "$name’ Bible" else "$name’s Bible"
        }

    companion object {
        const val FORMAT_IDENTIFIER = "com.blainemiller.scripturealone.legacy"
        /** The version this code writes. */
        const val CURRENT_VERSION = 1
        /** The newest `minimumReaderVersion` this code understands. */
        const val SUPPORTED_READER_VERSION = 1
    }
}

data class KeepsakeHighlight(
    val verse: Int,
    /** "yellow", "green", "blue", "pink", "purple". Readers show unknown names as yellow. */
    val color: String,
    val createdAt: Instant,
)

data class KeepsakeNote(
    val id: UUID = UUID.randomUUID(),
    val title: String,
    val body: String,
    val passages: List<Passage>,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** "manual" or "camera". */
    val origin: String = "manual",
) {
    data class Passage(val start: Int, val end: Int = start) {
        val range: VerseRange?
            get() {
                val a = VerseRange.ref(start) ?: return null
                val b = VerseRange.ref(end) ?: return null
                return VerseRange.of(a, b)
            }
    }

    /** Valid ranges, in canonical order. */
    val anchors: List<VerseRange>
        get() = passages.mapNotNull { it.range }.sortedWith(compareBy({ it.start.key }, { it.end.key }))

    val displayTitle: String
        get() = title.trim().ifEmpty { anchors.firstOrNull()?.display ?: "Untitled Note" }

    val anchorSummary: String get() = anchors.joinToString(" · ") { it.display }

    fun touches(book: Int, chapter: Int): Boolean {
        val base = book * 1_000_000 + chapter * 1_000
        return anchors.any { it.start.key <= base + 999 && it.end.key >= base }
    }

    companion object {
        fun of(title: String, body: String, anchors: List<VerseRange>, createdAt: Instant, updatedAt: Instant,
               origin: String = "manual", id: UUID = UUID.randomUUID()) =
            KeepsakeNote(id, title, body, anchors.map { Passage(it.start.key, it.end.key) }, createdAt, updatedAt, origin)
    }
}

/** Why a keepsake could not be opened; the messages are the ones the iOS app shows. */
sealed class KeepsakeException(message: String) : Exception(message) {
    class NotAKeepsake : KeepsakeException("This file isn’t a Keepsake Bible keepsake.")
    class Damaged(val detail: String) :
        KeepsakeException("This keepsake appears to be damaged ($detail). If you have another copy, try that one.")
    class NewerVersion(val version: Int) :
        KeepsakeException("This keepsake was made by a newer version of Scripture Alone. Update the app to open it.")
    class PassphraseRequired : KeepsakeException("This keepsake is protected with a passphrase.")
    class WrongPassphrase :
        KeepsakeException("That passphrase doesn’t open this keepsake. Check for capital letters and spaces, and try again.")
}
