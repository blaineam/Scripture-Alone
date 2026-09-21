package com.blainemiller.scripturealone.ui.keepsake

import com.blainemiller.scripturealone.data.keepsake.Keepsake
import com.blainemiller.scripturealone.data.keepsake.KeepsakeArchive
import com.blainemiller.scripturealone.data.keepsake.KeepsakeManifest
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Keepsakes this person has been given — `LegacyLibrary.swift`: plain files in the app's own storage,
 * deliberately apart from the reader's highlights and notes so the two never mix. Each is stored
 * unprotected (the passphrase guards the file in transit, not the copy already opened here) and named
 * by the keepsake's Bible id, so a newer keepsake of the same Bible replaces the older one.
 *
 * Plain JVM code over a directory, so the tests run it; callers keep it off the main thread.
 */
class KeepsakeLibrary(private val directory: File) {

    data class Entry(val id: UUID, val manifest: KeepsakeManifest, val addedAt: Instant) {
        val title: String get() = manifest.displayTitle
    }

    /** Added, or replacing the copy made at [previous]. */
    data class AddResult(val previous: Instant?)

    @Volatile var entries: List<Entry> = emptyList()
        private set
    private val keepsakes = mutableMapOf<UUID, Keepsake>()

    init {
        directory.mkdirs()
    }

    @Synchronized fun keepsake(id: UUID): Keepsake? = keepsakes[id]

    fun entry(id: UUID): Entry? = entries.firstOrNull { it.id == id }

    /** The stored copy, unprotected — for "Share a Copy". */
    fun file(id: UUID): File = File(directory, "${id.toString().uppercase()}.${KeepsakeArchive.FILE_EXTENSION}")

    @Synchronized
    fun add(keepsake: Keepsake): AddResult {
        val previous = entry(keepsake.id)?.manifest?.createdAt
        val data = KeepsakeArchive.encode(keepsake)
        val target = file(keepsake.id)
        val temp = File(directory, target.name + ".tmp")
        temp.writeBytes(data)
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) throw java.io.IOException("Can’t keep this keepsake on the device.")
        }
        reload()
        return AddResult(previous)
    }

    @Synchronized
    fun remove(id: UUID) {
        file(id).delete()
        reload()
    }

    /** Reads every keepsake in the folder; one that no longer opens is skipped, as on iOS. */
    @Synchronized
    fun reload(): List<Entry> {
        keepsakes.clear()
        val list = mutableListOf<Entry>()
        for (file in directory.listFiles().orEmpty()) {
            if (file.extension != KeepsakeArchive.FILE_EXTENSION) continue
            val keepsake = runCatching { KeepsakeArchive.decode(file.readBytes()) }.getOrNull() ?: continue
            keepsakes[keepsake.id] = keepsake
            list += Entry(keepsake.id, keepsake.manifest, Instant.ofEpochMilli(file.lastModified()))
        }
        entries = list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        return entries
    }
}
