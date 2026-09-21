package com.blainemiller.scripturealone.data.importer

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * The smallest ZIP reader that can open an ePub, ported from `Import/ZipReader.swift`.
 *
 * It indexes the central directory up front and inflates one entry at a time, so the engine can
 * answer "does this archive contain `META-INF/encryption.xml`?" — and refuse — without decompressing
 * a single byte of content. That is why this is not
 * [com.blainemiller.scripturealone.data.keepsake.ZipArchive]: the keepsake reader eagerly inflates
 * every entry, has no ZIP64, and ignores the encryption bit — the right shape for a keepsake, the
 * wrong one here.
 *
 * Stored (method 0) and DEFLATE (method 8) entries only, which is all an ePub may contain. ZIP64
 * sizes and offsets are understood. There is no decryption path: an archive whose entries are
 * encrypted is refused, not unlocked.
 */
class ZipReader(private val bytes: ByteArray) {

    /** Sizes and offsets are `Long` because ZIP64 values can exceed `Int`, as Swift's 64-bit `Int` holds them. */
    data class Entry(
        val name: String,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val localHeaderOffset: Long,
        val isEncrypted: Boolean,
    )

    val entries: List<Entry>
    private val index: Map<String, Int>

    val names: List<String> get() = entries.map { it.name }

    fun entry(named: String): Entry? = index[named]?.let { entries[it] }

    fun contains(name: String): Boolean = index.containsKey(name)

    init {
        if (bytes.size < 22) throw BibleImportError.NotAZipArchive()

        // End of central directory, scanning back over a possible trailing comment.
        var eocd = -1
        val lowest = maxOf(0, bytes.size - 22 - 65_535)
        var cursor = bytes.size - 22
        while (cursor >= lowest) {
            if (le32(cursor) == 0x0605_4B50L) { eocd = cursor; break }
            cursor--
        }
        if (eocd < 0) throw BibleImportError.NotAZipArchive()

        var count = le16(eocd + 10).toLong()
        var centralOffset = le32(eocd + 16)

        // ZIP64: the 32-bit fields saturate and the real ones live in the ZIP64 records.
        if ((count == 0xFFFFL || centralOffset == 0xFFFF_FFFFL) && eocd >= 20) {
            val locator = eocd - 20
            if (le32(locator) == 0x0706_4B50L) {
                val record = le64(locator + 8)
                if (record >= 0 && record + 56 <= bytes.size && le32(record.toInt()) == 0x0606_4B50L) {
                    count = le64(record.toInt() + 32)
                    centralOffset = le64(record.toInt() + 48)
                }
            }
        }
        if (count < 0 || count >= 500_000 || centralOffset < 0 || centralOffset > bytes.size) {
            throw BibleImportError.DamagedArchive("the central directory is out of range")
        }

        val found = ArrayList<Entry>(count.toInt())
        val byName = HashMap<String, Int>()
        var walk = centralOffset.toInt()
        repeat(count.toInt()) {
            if (walk + 46 > bytes.size || le32(walk) != 0x0201_4B50L) {
                throw BibleImportError.DamagedArchive("the central directory is truncated")
            }
            val flags = le16(walk + 8)
            val method = le16(walk + 10)
            val crc = le32(walk + 16)
            var compressed = le32(walk + 20)
            var size = le32(walk + 24)
            val nameLength = le16(walk + 28)
            val extraLength = le16(walk + 30)
            val commentLength = le16(walk + 32)
            var localOffset = le32(walk + 42)
            if (walk.toLong() + 46 + nameLength + extraLength + commentLength > bytes.size) {
                throw BibleImportError.DamagedArchive("the central directory is truncated")
            }
            val name = String(bytes, walk + 46, nameLength, Charsets.UTF_8)

            if (size == 0xFFFF_FFFFL || compressed == 0xFFFF_FFFFL || localOffset == 0xFFFF_FFFFL) {
                val extraStart = walk + 46 + nameLength
                var field = extraStart
                while (field + 4 <= extraStart + extraLength) {
                    val tag = le16(field)
                    val length = le16(field + 2)
                    if (tag == 0x0001) {
                        var value = field + 4
                        if (size == 0xFFFF_FFFFL && value + 8 <= field + 4 + length) { size = le64(value); value += 8 }
                        if (compressed == 0xFFFF_FFFFL && value + 8 <= field + 4 + length) { compressed = le64(value); value += 8 }
                        if (localOffset == 0xFFFF_FFFFL && value + 8 <= field + 4 + length) localOffset = le64(value)
                        break
                    }
                    field += 4 + length
                }
            }
            walk += 46 + nameLength + extraLength + commentLength

            if (name.endsWith("/")) return@repeat
            val entry = Entry(name, method, crc, compressed, size, localOffset, isEncrypted = flags and 1 == 1)
            byName[name] = found.size
            found.add(entry)
        }
        if (found.isEmpty()) throw BibleImportError.DamagedArchive("the archive has no files")
        entries = found
        index = byName
    }

    /** Inflates one entry. Nothing is decompressed until this is called. */
    fun data(name: String): ByteArray {
        val entry = entry(name) ?: throw BibleImportError.DamagedArchive("$name is not in the archive")
        return data(entry)
    }

    fun data(entry: Entry): ByteArray {
        // There is no decryption path, by design.
        if (entry.isEncrypted) throw BibleImportError.ProtectedByDRM(DRMEvidence.ZIP_ENTRY_ENCRYPTION)
        if (entry.size < 0 || entry.compressedSize < 0 ||
            entry.size > MAXIMUM_ENTRY_SIZE || entry.compressedSize > MAXIMUM_ENTRY_SIZE
        ) throw BibleImportError.EntryTooLarge(entry.name)
        val header = entry.localHeaderOffset
        if (header < 0 || header + 30 > bytes.size || le32(header.toInt()) != 0x0403_4B50L) {
            throw BibleImportError.DamagedArchive("${entry.name} has no local header")
        }
        val start = header.toInt() + 30 + le16(header.toInt() + 26) + le16(header.toInt() + 28)
        if (start < 0 || start + entry.compressedSize > bytes.size) {
            throw BibleImportError.DamagedArchive("${entry.name} runs past the end of the archive")
        }
        val stored = bytes.copyOfRange(start, start + entry.compressedSize.toInt())

        val contents = when (entry.method) {
            0 -> stored
            8 -> inflate(stored, entry.size.toInt())
                ?: throw BibleImportError.DamagedArchive("${entry.name} could not be decompressed")
            else -> throw BibleImportError.DamagedArchive("${entry.name} uses an unsupported compression method")
        }
        if (contents.size.toLong() != entry.size) throw BibleImportError.DamagedArchive("${entry.name} is the wrong size")
        if (CRC32().apply { update(contents) }.value != entry.crc) {
            throw BibleImportError.DamagedArchive("${entry.name} failed its checksum")
        }
        return contents
    }

    /**
     * Raw DEFLATE (Apple's `.zlib` algorithm is the same headerless stream); null if it doesn't
     * inflate. Stops one byte past [expected] rather than inflating a bomb in full: the size check
     * that follows fails either way, but this one never holds more than the entry claimed.
     */
    private fun inflate(data: ByteArray, expected: Int): ByteArray? {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(expected)
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
                if (out.size() > expected) return out.toByteArray()
            }
            if (inflater.finished()) out.toByteArray() else null
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    // Little-endian reads that answer 0 out of range, as Swift's `Data` helpers do.
    private fun byte(offset: Int): Long = if (offset < 0 || offset >= bytes.size) 0 else (bytes[offset].toLong() and 0xFF)
    private fun le16(offset: Int): Int = if (offset < 0 || offset + 2 > bytes.size) 0 else (byte(offset) or (byte(offset + 1) shl 8)).toInt()
    private fun le32(offset: Int): Long = if (offset < 0 || offset + 4 > bytes.size) 0 else
        byte(offset) or (byte(offset + 1) shl 8) or (byte(offset + 2) shl 16) or (byte(offset + 3) shl 24)
    /** Swift converts the `UInt64` to `Int`; a value past `Int64.max` traps there and goes negative here, where the range guards catch it. */
    private fun le64(offset: Int): Long = if (offset < 0 || offset + 8 > bytes.size) 0 else le32(offset) or (le32(offset + 4) shl 32)

    companion object {
        /**
         * Entries larger than this are refused (a guard against decompression bombs). The largest
         * single XHTML file in a whole-Bible ePub is a few megabytes.
         */
        const val MAXIMUM_ENTRY_SIZE = 96L * 1024 * 1024
    }
}
