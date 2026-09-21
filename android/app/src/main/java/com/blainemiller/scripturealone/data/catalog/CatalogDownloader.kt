package com.blainemiller.scripturealone.data.catalog

import com.blainemiller.scripturealone.data.importer.ImportedTranslationIdentity
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Downloads one translation's USFM zip from eBible.org — `CatalogDownloader.swift`.
 *
 * The app makes no network request on its own: this runs only when someone taps a translation in
 * the catalogue. Nothing about the device is sent — it is a plain GET for a public file.
 */
object CatalogDownloader {

    sealed class Failure(message: String) : IOException(message) {
        class Http(val code: Int) : Failure("eBible.org returned HTTP $code for that translation.")
        class Empty : Failure("That download arrived empty.")
    }

    /**
     * Streams the zip into [directory], reporting 0…1 where the server states a length (null where
     * it doesn't). The caller deletes the file once imported; the importer copies what it needs.
     * Blocking — call off the main thread.
     */
    fun download(translation: CatalogTranslation, directory: File, progress: (Double?) -> Unit = {}): File {
        val connection = URL(translation.downloadURL).openConnection() as HttpURLConnection
        val destination = File(directory.apply { mkdirs() }, "${translation.id}-${UUID.randomUUID()}.zip")
        try {
            connection.connectTimeout = 60_000
            connection.readTimeout = 60_000
            val status = connection.responseCode
            if (status !in 200 until 300) throw Failure.Http(status)
            val expected = connection.contentLengthLong
            var written = 0L
            var lastReported = 0L
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        written += n
                        // A few times a second is plenty; every chunk would flood the UI.
                        val now = System.nanoTime()
                        if (now - lastReported > 100_000_000L) {
                            lastReported = now
                            progress(if (expected > 0) minOf(1.0, written.toDouble() / expected) else null)
                        }
                    }
                }
            }
            if (written == 0L) throw Failure.Empty()
            progress(1.0)
            return destination
        } catch (e: Exception) {
            destination.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * What to call a translation downloaded from the catalogue — `CatalogTranslation+Identity.swift`.
 *
 * The engine's own guess comes from the file's `copr.htm`, which for eBible zips is a whole page of
 * navigation and boilerplate. The catalogue row has the publisher's actual title and a one-line
 * licence, so when the download came from there, that wins.
 */
val CatalogTranslation.importIdentity: ImportedTranslationIdentity
    get() = ImportedTranslationIdentity(
        id = id.uppercase(),
        name = title.ifEmpty { id },
        abbreviation = CatalogIdentity.abbreviation(shortTitle, id),
        copyright = copyright.ifEmpty { "Source: eBible.org" },
        license = copyright,
        source = "eBible.org",
    )

object CatalogIdentity {
    private val skip = setOf("in", "of", "the", "and", "a", "an", "with", "for", "to")

    /**
     * A short tag for the toolbar. eBible's "shortTitle" repeats the full name, so the tag comes from
     * the title's initials, skipping the words nobody abbreviates: World English Bible → WEB, American
     * Standard Version (1901) → ASV, Bible in Basic English → BBE.
     */
    fun abbreviation(shortTitle: String, id: String): String {
        val words = shortTitle.replace("(", " ").replace(")", " ")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { word -> word.isNotEmpty() && word.first().isLetter() && word.lowercase() !in skip }
        val initials = words.joinToString("") { it.first().toString() }.uppercase()
        if (initials.length in 2..6) return initials
        val letters = id.filter { it.isLetterOrDigit() }
        return if (letters.isEmpty()) "IMPORT" else letters.uppercase()
    }
}
