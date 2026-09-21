package com.blainemiller.scripturealone.data.keepsake

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * A deliberately small ZIP reader and writer, ported from `Keepsake/ZipArchive.swift`, so a keepsake
 * stays a file anyone can open with the unzip tool on any computer, decades from now, without this app.
 *
 * Writing stores entries uncompressed (the JSON inside is small), laid out exactly as the Swift
 * writer lays them out. Reading accepts stored and DEFLATE entries, so a keepsake someone re-zipped
 * by hand still opens. No ZIP64, no encryption, no multi-disk archives.
 */
object ZipArchive {

    class Entry(val name: String, val data: ByteArray, val modified: Instant = Instant.now())

    sealed class ZipException(message: String) : Exception(message) {
        class NotAZip : ZipException("not a ZIP archive")
        class Truncated : ZipException("truncated")
        class UnsupportedCompression(val method: Int) : ZipException("unsupported compression $method")
        class ChecksumMismatch(val name: String) : ZipException("checksum mismatch in $name")
        class EntryTooLarge(val name: String) : ZipException("$name is too large")
    }

    /** Entries larger than this are refused when reading (a guard against decompression bombs). */
    const val MAXIMUM_ENTRY_SIZE = 256 * 1024 * 1024

    fun write(entries: List<Entry>): ByteArray {
        val output = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        for (entry in entries) {
            val name = entry.name.toByteArray(Charsets.UTF_8)
            val crc = crc32(entry.data)
            val (time, date) = dosTimestamp(entry.modified)
            val offset = output.size()

            output.le32(0x0403_4B50)
            output.le16(20)          // version needed
            output.le16(0x0800)      // UTF-8 names
            output.le16(0)           // stored
            output.le16(time)
            output.le16(date)
            output.le32(crc)
            output.le32(entry.data.size.toLong())
            output.le32(entry.data.size.toLong())
            output.le16(name.size)
            output.le16(0)           // extra length
            output.write(name)
            output.write(entry.data)

            central.le32(0x0201_4B50)
            central.le16(20)         // version made by
            central.le16(20)
            central.le16(0x0800)
            central.le16(0)
            central.le16(time)
            central.le16(date)
            central.le32(crc)
            central.le32(entry.data.size.toLong())
            central.le32(entry.data.size.toLong())
            central.le16(name.size)
            central.le16(0)          // extra
            central.le16(0)          // comment
            central.le16(0)          // disk
            central.le16(0)          // internal attributes
            central.le32(0)          // external attributes
            central.le32(offset.toLong())
            central.write(name)
        }
        val centralOffset = output.size()
        val centralBytes = central.toByteArray()
        output.write(centralBytes)
        output.le32(0x0605_4B50)
        output.le16(0)
        output.le16(0)
        output.le16(entries.size)
        output.le16(entries.size)
        output.le32(centralBytes.size.toLong())
        output.le32(centralOffset.toLong())
        output.le16(0)
        return output.toByteArray()
    }

    /** Every file entry by name (directories are skipped). */
    fun read(bytes: ByteArray): Map<String, ByteArray> {
        if (bytes.size < 22) throw ZipException.NotAZip()

        // End of central directory: scan back over a possible comment.
        var eocd = -1
        val lowest = maxOf(0, bytes.size - 22 - 65_535)
        var index = bytes.size - 22
        while (index >= lowest) {
            if (bytes.le32(index) == 0x0605_4B50L) { eocd = index; break }
            index--
        }
        if (eocd < 0) throw ZipException.NotAZip()
        val count = bytes.le16(eocd + 10)
        val centralOffset = bytes.le32(eocd + 16)

        val result = LinkedHashMap<String, ByteArray>()
        var cursor = centralOffset
        repeat(count) {
            if (cursor < 0 || cursor + 46 > bytes.size || bytes.le32(cursor.toInt()) != 0x0201_4B50L) throw ZipException.Truncated()
            val at = cursor.toInt()
            val method = bytes.le16(at + 10)
            val crc = bytes.le32(at + 16)
            val compressedSize = bytes.le32(at + 20)
            val size = bytes.le32(at + 24)
            val nameLength = bytes.le16(at + 28)
            val extraLength = bytes.le16(at + 30)
            val commentLength = bytes.le16(at + 32)
            val localOffset = bytes.le32(at + 42)
            if (at + 46 + nameLength > bytes.size) throw ZipException.Truncated()
            val name = String(bytes, at + 46, nameLength, Charsets.UTF_8)
            cursor += 46L + nameLength + extraLength + commentLength

            if (name.endsWith("/")) return@repeat
            if (size > MAXIMUM_ENTRY_SIZE || compressedSize > MAXIMUM_ENTRY_SIZE) throw ZipException.EntryTooLarge(name)

            if (localOffset + 30 > bytes.size || bytes.le32(localOffset.toInt()) != 0x0403_4B50L) throw ZipException.Truncated()
            val local = localOffset.toInt()
            val start = local + 30 + bytes.le16(local + 26) + bytes.le16(local + 28)
            if (start + compressedSize > bytes.size) throw ZipException.Truncated()
            val stored = bytes.copyOfRange(start, start + compressedSize.toInt())

            val contents = when (method) {
                0 -> stored
                8 -> inflate(stored, size.toInt())?.takeIf { it.size.toLong() == size }
                    ?: throw ZipException.ChecksumMismatch(name)
                else -> throw ZipException.UnsupportedCompression(method)
            }
            if (crc32(contents) != crc) throw ZipException.ChecksumMismatch(name)
            result[name] = contents
        }
        return result
    }

    internal fun crc32(data: ByteArray): Long = CRC32().apply { update(data) }.value

    /** Raw DEFLATE, as ZIP stores it; null if it doesn't inflate to at most [expected] bytes. */
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
                if (out.size() > expected) return null
            }
            if (inflater.finished()) out.toByteArray() else null
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    /** MS-DOS date and time in the device's zone, as the Swift writer stamps them. */
    private fun dosTimestamp(instant: Instant): Pair<Int, Int> {
        val c = instant.atZone(ZoneId.systemDefault())
        val year = c.year.coerceIn(1980, 2107)
        val time = (c.hour shl 11) or (c.minute shl 5) or (c.second / 2)
        val date = ((year - 1980) shl 9) or (c.monthValue shl 5) or c.dayOfMonth
        return time to date
    }

    private fun ByteArrayOutputStream.le16(value: Int) {
        write(value and 0xFF); write((value shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.le32(value: Long) {
        for (shift in 0 until 32 step 8) write(((value shr shift) and 0xFF).toInt())
    }

    private fun ByteArray.le16(i: Int): Int =
        if (i < 0 || i + 2 > size) 0 else (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.le32(i: Int): Long =
        if (i < 0 || i + 4 > size) 0 else (0 until 4).fold(0L) { acc, k -> acc or ((this[i + k].toLong() and 0xFF) shl (8 * k)) }
}
