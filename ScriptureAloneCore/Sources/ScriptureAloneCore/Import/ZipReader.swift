// Importing is an iPhone, iPad and Mac feature: the watch has no file picker and no
// catalogue. It is also 32-bit (arm64_32), where the ZIP64 sentinel 0xFFFF_FFFF does not
// fit in an Int at all — so this code is not merely unused there, it cannot compile.
#if !os(watchOS)
import Compression
import Foundation

/// The smallest ZIP reader that can open an ePub.
///
/// It indexes the central directory up front and inflates one entry at a time, so the engine can
/// answer "does this archive contain `META-INF/encryption.xml`?" — and refuse — without
/// decompressing a single byte of content. (`ZipArchive`, the keepsake reader, eagerly inflates
/// every entry; that is the right shape for a keepsake and the wrong one here.)
///
/// Stored (method 0) and DEFLATE (method 8) entries only, which is all an ePub may contain.
/// ZIP64 sizes and offsets are understood. There is no decryption path: an archive whose entries
/// are encrypted is refused, not unlocked.
struct ZipReader: Sendable {
    struct Entry: Sendable, Hashable {
        var name: String
        var method: UInt16
        var crc: UInt32
        var compressedSize: Int
        var size: Int
        var localHeaderOffset: Int
        var isEncrypted: Bool
    }

    /// Entries larger than this are refused (a guard against decompression bombs). The largest
    /// single XHTML file in a whole-Bible ePub is a few megabytes.
    static let maximumEntrySize = 96 * 1024 * 1024

    private let bytes: Data
    let entries: [Entry]
    private let index: [String: Int]

    var names: [String] { entries.map(\.name) }

    func entry(named name: String) -> Entry? {
        index[name].map { entries[$0] }
    }

    func contains(_ name: String) -> Bool { index[name] != nil }

    init(data: Data) throws {
        bytes = data
        guard data.count >= 22 else { throw BibleImportError.notAZipArchive }

        // End of central directory, scanning back over a possible trailing comment.
        var eocd = -1
        let lowest = max(0, data.count - 22 - 65_535)
        var cursor = data.count - 22
        while cursor >= lowest {
            if data.le32(cursor) == 0x0605_4B50 { eocd = cursor; break }
            cursor -= 1
        }
        guard eocd >= 0 else { throw BibleImportError.notAZipArchive }

        var count = Int(data.le16(eocd + 10))
        var centralOffset = Int(data.le32(eocd + 16))

        // ZIP64: the 32-bit fields saturate and the real ones live in the ZIP64 records.
        if count == 0xFFFF || centralOffset == 0xFFFF_FFFF, eocd >= 20 {
            let locator = eocd - 20
            if data.le32(locator) == 0x0706_4B50 {
                let record = Self.int(data.le64(locator + 8))
                if record >= 0, record <= data.count - 56, data.le32(record) == 0x0606_4B50 {
                    count = Self.int(data.le64(record + 32))
                    centralOffset = Self.int(data.le64(record + 48))
                }
            }
        }
        guard count >= 0, count < 500_000, centralOffset >= 0, centralOffset <= data.count else {
            throw BibleImportError.damagedArchive("the central directory is out of range")
        }

        var found: [Entry] = []
        var byName: [String: Int] = [:]
        found.reserveCapacity(count)
        var walk = centralOffset
        for _ in 0..<count {
            guard walk + 46 <= data.count, data.le32(walk) == 0x0201_4B50 else {
                throw BibleImportError.damagedArchive("the central directory is truncated")
            }
            let flags = data.le16(walk + 8)
            let method = data.le16(walk + 10)
            let crc = data.le32(walk + 16)
            var compressed = Int(data.le32(walk + 20))
            var size = Int(data.le32(walk + 24))
            let nameLength = Int(data.le16(walk + 28))
            let extraLength = Int(data.le16(walk + 30))
            let commentLength = Int(data.le16(walk + 32))
            var localOffset = Int(data.le32(walk + 42))
            guard walk + 46 + nameLength + extraLength + commentLength <= data.count else {
                throw BibleImportError.damagedArchive("the central directory is truncated")
            }
            let name = data.string(walk + 46, nameLength)

            if size == 0xFFFF_FFFF || compressed == 0xFFFF_FFFF || localOffset == 0xFFFF_FFFF {
                let extraStart = walk + 46 + nameLength
                var field = extraStart
                while field + 4 <= extraStart + extraLength {
                    let tag = data.le16(field)
                    let length = Int(data.le16(field + 2))
                    if tag == 0x0001 {
                        var value = field + 4
                        if size == 0xFFFF_FFFF, value + 8 <= field + 4 + length { size = Self.int(data.le64(value)); value += 8 }
                        if compressed == 0xFFFF_FFFF, value + 8 <= field + 4 + length { compressed = Self.int(data.le64(value)); value += 8 }
                        if localOffset == 0xFFFF_FFFF, value + 8 <= field + 4 + length { localOffset = Self.int(data.le64(value)) }
                        break
                    }
                    field += 4 + length
                }
            }
            walk += 46 + nameLength + extraLength + commentLength

            guard !name.hasSuffix("/") else { continue }
            let entry = Entry(name: name, method: method, crc: crc, compressedSize: compressed,
                              size: size, localHeaderOffset: localOffset, isEncrypted: flags & 1 == 1)
            byName[name] = found.count
            found.append(entry)
        }
        guard !found.isEmpty else { throw BibleImportError.damagedArchive("the archive has no files") }
        entries = found
        index = byName
    }

    /// Inflates one entry. Nothing is decompressed until this is called.
    func data(for name: String) throws -> Data {
        guard let entry = entry(named: name) else {
            throw BibleImportError.damagedArchive("\(name) is not in the archive")
        }
        return try data(for: entry)
    }

    func data(for entry: ZipReader.Entry) throws -> Data {
        // There is no decryption path, by design.
        guard !entry.isEncrypted else { throw BibleImportError.protectedByDRM(.zipEntryEncryption) }
        guard entry.size >= 0, entry.compressedSize >= 0,
              entry.size <= Self.maximumEntrySize, entry.compressedSize <= Self.maximumEntrySize else {
            throw BibleImportError.entryTooLarge(entry.name)
        }
        let header = entry.localHeaderOffset
        guard header >= 0, header <= bytes.count - 30, bytes.le32(header) == 0x0403_4B50 else {
            throw BibleImportError.damagedArchive("\(entry.name) has no local header")
        }
        let start = header + 30 + Int(bytes.le16(header + 26)) + Int(bytes.le16(header + 28))
        guard start >= 0, start + entry.compressedSize <= bytes.count else {
            throw BibleImportError.damagedArchive("\(entry.name) runs past the end of the archive")
        }
        let stored = bytes.subdata(in: (bytes.startIndex + start)..<(bytes.startIndex + start + entry.compressedSize))

        let contents: Data
        switch entry.method {
        case 0:
            contents = stored
        case 8:
            guard let inflated = Self.inflate(stored, limit: entry.size) else {
                throw BibleImportError.damagedArchive("\(entry.name) could not be decompressed")
            }
            contents = inflated
        default:
            throw BibleImportError.damagedArchive("\(entry.name) uses an unsupported compression method")
        }
        guard contents.count == entry.size else {
            throw BibleImportError.damagedArchive("\(entry.name) is the wrong size")
        }
        guard CRC32.checksum(contents) == entry.crc else {
            throw BibleImportError.damagedArchive("\(entry.name) failed its checksum")
        }
        return contents
    }

    /// A ZIP64 field as an `Int`. A value of 2^63 or more cannot be a real size or offset (and
    /// `Int(_:)` would trap on it), so it becomes -1, which every caller refuses.
    static func int(_ value: UInt64) -> Int {
        Int(exactly: value) ?? -1
    }

    /// Raw DEFLATE (Apple's `.zlib` is the headerless stream), inflated in chunks. It stops as soon
    /// as the output passes `limit` — the size the entry declared — so a header that lies about
    /// the size cannot make it inflate a bomb in full; the size check that follows then fails.
    /// Nil when the stream is not valid DEFLATE or ends early.
    static func inflate(_ input: Data, limit: Int) -> Data? {
        let stream = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { stream.deallocate() }
        guard compression_stream_init(stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else {
            return nil
        }
        defer { compression_stream_destroy(stream) }

        let chunk = 64 * 1024
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: chunk)
        defer { buffer.deallocate() }
        var output = Data()
        return input.withUnsafeBytes { (source: UnsafeRawBufferPointer) -> Data? in
            let empty = UnsafeMutablePointer<UInt8>.allocate(capacity: 1)
            defer { empty.deallocate() }
            stream.pointee.src_ptr = UnsafePointer(source.bindMemory(to: UInt8.self).baseAddress ?? UnsafePointer(empty))
            stream.pointee.src_size = source.count
            while true {
                stream.pointee.dst_ptr = buffer
                stream.pointee.dst_size = chunk
                let status = compression_stream_process(stream, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
                let produced = chunk - stream.pointee.dst_size
                if produced > 0 { output.append(buffer, count: produced) }
                if output.count > limit { return output }
                switch status {
                case COMPRESSION_STATUS_END:
                    return output
                case COMPRESSION_STATUS_OK:
                    // No progress with all input consumed: the stream was cut short.
                    if produced == 0, stream.pointee.src_size == 0 { return nil }
                default:
                    return nil
                }
            }
        }
    }
}

private extension Data {
    func byte(_ offset: Int) -> UInt8 {
        guard offset >= 0, offset < count else { return 0 }
        return self[startIndex + offset]
    }

    func le16(_ offset: Int) -> UInt16 {
        guard offset >= 0, offset + 2 <= count else { return 0 }
        return UInt16(byte(offset)) | UInt16(byte(offset + 1)) << 8
    }

    func le32(_ offset: Int) -> UInt32 {
        guard offset >= 0, offset + 4 <= count else { return 0 }
        return UInt32(byte(offset)) | UInt32(byte(offset + 1)) << 8
            | UInt32(byte(offset + 2)) << 16 | UInt32(byte(offset + 3)) << 24
    }

    func le64(_ offset: Int) -> UInt64 {
        guard offset >= 0, offset + 8 <= count else { return 0 }
        return UInt64(le32(offset)) | UInt64(le32(offset + 4)) << 32
    }

    func string(_ offset: Int, _ length: Int) -> String {
        guard offset >= 0, length >= 0, offset + length <= count else { return "" }
        let slice = self[(startIndex + offset)..<(startIndex + offset + length)]
        return String(decoding: slice, as: UTF8.self)
    }
}
#endif
