import Foundation

/// A deliberately small ZIP reader and writer, so a keepsake stays a file anyone can open with
/// the unzip tool on any computer, decades from now, without this app.
///
/// Writing stores entries uncompressed (the JSON inside is small). Reading accepts stored and
/// DEFLATE entries, so a keepsake someone re-zipped by hand still opens. No ZIP64, no
/// encryption, no multi-disk archives — none of which a keepsake needs.
public enum ZipArchive {
    public struct Entry: Sendable, Equatable {
        public var name: String
        public var data: Data
        public var modified: Date

        public init(name: String, data: Data, modified: Date = .now) {
            self.name = name
            self.data = data
            self.modified = modified
        }
    }

    public enum ZipError: Error, Equatable {
        case notAZip
        case truncated
        case unsupportedCompression(UInt16)
        case checksumMismatch(String)
        case entryTooLarge(String)
    }

    /// Entries larger than this are refused when reading (a guard against decompression bombs).
    public static let maximumEntrySize = 256 * 1024 * 1024

    // MARK: Writing

    public static func write(_ entries: [Entry]) -> Data {
        var output = Data()
        var central = Data()
        for entry in entries {
            let name = Data(entry.name.utf8)
            let crc = CRC32.checksum(entry.data)
            let (time, date) = dosTimestamp(entry.modified)
            let offset = UInt32(output.count)

            output.append(le32: 0x0403_4B50)
            output.append(le16: 20)          // version needed
            output.append(le16: 0x0800)      // UTF-8 names
            output.append(le16: 0)           // stored
            output.append(le16: time)
            output.append(le16: date)
            output.append(le32: crc)
            output.append(le32: UInt32(entry.data.count))
            output.append(le32: UInt32(entry.data.count))
            output.append(le16: UInt16(name.count))
            output.append(le16: 0)           // extra length
            output.append(name)
            output.append(entry.data)

            central.append(le32: 0x0201_4B50)
            central.append(le16: 20)         // version made by
            central.append(le16: 20)
            central.append(le16: 0x0800)
            central.append(le16: 0)
            central.append(le16: time)
            central.append(le16: date)
            central.append(le32: crc)
            central.append(le32: UInt32(entry.data.count))
            central.append(le32: UInt32(entry.data.count))
            central.append(le16: UInt16(name.count))
            central.append(le16: 0)          // extra
            central.append(le16: 0)          // comment
            central.append(le16: 0)          // disk
            central.append(le16: 0)          // internal attributes
            central.append(le32: 0)          // external attributes
            central.append(le32: offset)
            central.append(name)
        }
        let centralOffset = UInt32(output.count)
        output.append(central)
        output.append(le32: 0x0605_4B50)
        output.append(le16: 0)
        output.append(le16: 0)
        output.append(le16: UInt16(entries.count))
        output.append(le16: UInt16(entries.count))
        output.append(le32: UInt32(central.count))
        output.append(le32: centralOffset)
        output.append(le16: 0)
        return output
    }

    // MARK: Reading

    /// Every file entry by name (directories are skipped).
    public static func read(_ archive: Data) throws -> [String: Data] {
        let bytes = [UInt8](archive)
        guard bytes.count >= 22 else { throw ZipError.notAZip }

        // End of central directory: scan back over a possible comment.
        var eocd = -1
        let lowest = max(0, bytes.count - 22 - 65_535)
        var index = bytes.count - 22
        while index >= lowest {
            if bytes.le32(at: index) == 0x0605_4B50 { eocd = index; break }
            index -= 1
        }
        guard eocd >= 0 else { throw ZipError.notAZip }
        let count = Int(bytes.le16(at: eocd + 10))
        let centralOffset = Int(bytes.le32(at: eocd + 16))

        var result: [String: Data] = [:]
        var cursor = centralOffset
        for _ in 0..<count {
            guard cursor + 46 <= bytes.count, bytes.le32(at: cursor) == 0x0201_4B50 else { throw ZipError.truncated }
            let method = bytes.le16(at: cursor + 10)
            let crc = bytes.le32(at: cursor + 16)
            let compressedSize = Int(bytes.le32(at: cursor + 20))
            let size = Int(bytes.le32(at: cursor + 24))
            let nameLength = Int(bytes.le16(at: cursor + 28))
            let extraLength = Int(bytes.le16(at: cursor + 30))
            let commentLength = Int(bytes.le16(at: cursor + 32))
            let localOffset = Int(bytes.le32(at: cursor + 42))
            guard cursor + 46 + nameLength <= bytes.count else { throw ZipError.truncated }
            let name = String(decoding: bytes[(cursor + 46)..<(cursor + 46 + nameLength)], as: UTF8.self)
            cursor += 46 + nameLength + extraLength + commentLength

            if name.hasSuffix("/") { continue }
            guard size <= maximumEntrySize, compressedSize <= maximumEntrySize else { throw ZipError.entryTooLarge(name) }

            guard localOffset + 30 <= bytes.count, bytes.le32(at: localOffset) == 0x0403_4B50 else { throw ZipError.truncated }
            let localName = Int(bytes.le16(at: localOffset + 26))
            let localExtra = Int(bytes.le16(at: localOffset + 28))
            let start = localOffset + 30 + localName + localExtra
            guard start + compressedSize <= bytes.count else { throw ZipError.truncated }
            let stored = Data(bytes[start..<(start + compressedSize)])

            let contents: Data
            switch method {
            case 0:
                contents = stored
            case 8:
                guard let inflated = try? (stored as NSData).decompressed(using: .zlib) as Data,
                      inflated.count == size else { throw ZipError.checksumMismatch(name) }
                contents = inflated
            default:
                throw ZipError.unsupportedCompression(method)
            }
            guard CRC32.checksum(contents) == crc else { throw ZipError.checksumMismatch(name) }
            result[name] = contents
        }
        return result
    }

    // MARK: Helpers

    private static func dosTimestamp(_ date: Date) -> (UInt16, UInt16) {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let c = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        let year = max(1980, min(2107, c.year ?? 1980))
        let time = UInt16((c.hour ?? 0) << 11 | (c.minute ?? 0) << 5 | (c.second ?? 0) / 2)
        let day = UInt16((year - 1980) << 9 | (c.month ?? 1) << 5 | (c.day ?? 1))
        return (time, day)
    }
}

enum CRC32 {
    private static let table: [UInt32] = (0..<256).map { n in
        var c = UInt32(n)
        for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    static func checksum(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data { crc = table[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8) }
        return crc ^ 0xFFFF_FFFF
    }
}

private extension Data {
    mutating func append(le16 value: UInt16) { append(contentsOf: [UInt8(value & 0xFF), UInt8(value >> 8)]) }
    mutating func append(le32 value: UInt32) {
        append(contentsOf: [UInt8(value & 0xFF), UInt8((value >> 8) & 0xFF), UInt8((value >> 16) & 0xFF), UInt8(value >> 24)])
    }
}

private extension Array where Element == UInt8 {
    func le16(at i: Int) -> UInt16 {
        guard i + 2 <= count else { return 0 }
        return UInt16(self[i]) | UInt16(self[i + 1]) << 8
    }
    func le32(at i: Int) -> UInt32 {
        guard i + 4 <= count else { return 0 }
        return UInt32(self[i]) | UInt32(self[i + 1]) << 8 | UInt32(self[i + 2]) << 16 | UInt32(self[i + 3]) << 24
    }
}
