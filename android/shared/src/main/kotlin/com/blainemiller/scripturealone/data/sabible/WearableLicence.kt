package com.blainemiller.scripturealone.data.sabible

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * The wearables term ([PackagePolicy.wearables]), read where no key is held — `WearableLicence.swift`:
 * on the phone, deciding whether to send a package to the watch, and on the watch, sweeping out a
 * package that should never have arrived.
 *
 * These read the header **without verifying its signature**, and that is safe in both directions
 * because neither decision grants anything. A header edited to say "allowed" breaks its signature, so
 * the watch refuses to open what the phone then sends ([TranslationPackage.open] verifies first, then
 * checks the term). One edited to say "prohibited" only gets a file the attacker already held deleted.
 */
object WearableLicence {

    /** The policy in a package's header, unverified. Null when it is not a readable package. */
    fun unverifiedPolicy(input: InputStream): PackagePolicy? = try {
        val magic = SabibleFormat.MAGIC
        val preamble = readFully(input, magic.size + 6)
        if (preamble.size != magic.size + 6 || !preamble.copyOfRange(0, magic.size).contentEquals(magic)) {
            null
        } else {
            val length = ByteBuffer.wrap(preamble, magic.size + 2, 4).int.toLong() and 0xffffffffL
            if (length <= 0 || length > SabibleFormat.MAXIMUM_HEADER_BYTES) {
                null
            } else {
                val header = readFully(input, length.toInt())
                if (header.size != length.toInt()) null else PackageHeaderParser.parse(header).policy
            }
        }
    } catch (e: IOException) {
        null
    } catch (e: TranslationPackageException) {
        null
    }

    /** Up to [count] bytes — `readNBytes`, which Android has only from API 33. */
    private fun readFully(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n < 0) break
            read += n
        }
        return if (read == count) buffer else buffer.copyOf(read)
    }

    fun unverifiedPolicy(file: File): PackagePolicy? = try {
        file.inputStream().buffered().use(::unverifiedPolicy)
    } catch (e: IOException) {
        null
    }

    /**
     * Whether a package may go to, or stay on, a watch. An unreadable file may not: a watch could not
     * open it anyway, and "can't tell" must not become "send it".
     */
    fun allowsWearables(file: File): Boolean = unverifiedPolicy(file)?.allowsWearables ?: false

    /**
     * Whether the phone may send [file] to the watch: anything that isn't a sealed package (a compact
     * edition carries no such term), or a package whose terms let it go.
     */
    fun maySendToWatch(file: File, sealed: Boolean): Boolean = !sealed || allowsWearables(file)

    /**
     * Deletes every `.sabible` in [directory] whose terms keep it off wearables. Files that aren't
     * packages are left alone. Returns the files removed.
     */
    fun removeProhibitedPackages(directory: File): List<File> =
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".sabible") }
            .filter { file -> unverifiedPolicy(file)?.let { !it.allowsWearables } ?: false }
            .filter { it.delete() }
}
