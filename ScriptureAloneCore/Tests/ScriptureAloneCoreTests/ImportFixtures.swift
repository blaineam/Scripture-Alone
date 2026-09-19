import Foundation
@testable import ScriptureAloneCore

/// Synthetic ePub and USFM archives, built in the test bundle.
///
/// Every scrap of scripture here is either the American Standard Version (1901, public domain) or
/// invented for the test. No copyrighted translation appears in this repository.
enum ImportFixtures {
    // MARK: - ZIP

    struct ZipEntry {
        var name: String
        var data: Data
        var deflate: Bool

        init(_ name: String, _ text: String, deflate: Bool = false) {
            self.name = name
            self.data = Data(text.utf8)
            self.deflate = deflate
        }

        init(_ name: String, data: Data, deflate: Bool = false) {
            self.name = name
            self.data = data
            self.deflate = deflate
        }
    }

    /// A minimal ZIP, with per-entry choice of stored or DEFLATE, so the reader's inflate path is
    /// exercised by real compressed bytes rather than by a stored entry pretending.
    static func zip(_ entries: [ZipEntry]) -> Data {
        var output = Data()
        var central = Data()
        for entry in entries {
            let name = Data(entry.name.utf8)
            let crc = CRC32.checksum(entry.data)
            var payload = entry.data
            var method: UInt16 = 0
            if entry.deflate, let compressed = try? (entry.data as NSData).compressed(using: .zlib) as Data {
                payload = compressed
                method = 8
            }
            let offset = UInt32(output.count)

            output.append(le32: 0x0403_4B50)
            output.append(le16: 20)
            output.append(le16: 0x0800)
            output.append(le16: method)
            output.append(le16: 0)
            output.append(le16: 0)
            output.append(le32: crc)
            output.append(le32: UInt32(payload.count))
            output.append(le32: UInt32(entry.data.count))
            output.append(le16: UInt16(name.count))
            output.append(le16: 0)
            output.append(name)
            output.append(payload)

            central.append(le32: 0x0201_4B50)
            central.append(le16: 20)
            central.append(le16: 20)
            central.append(le16: 0x0800)
            central.append(le16: method)
            central.append(le16: 0)
            central.append(le16: 0)
            central.append(le32: crc)
            central.append(le32: UInt32(payload.count))
            central.append(le32: UInt32(entry.data.count))
            central.append(le16: UInt16(name.count))
            central.append(le16: 0)
            central.append(le16: 0)
            central.append(le16: 0)
            central.append(le16: 0)
            central.append(le32: 0)
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

    // MARK: - ePub

    struct Document {
        var path: String
        var xhtml: String

        init(_ path: String, _ body: String) {
            self.path = path
            self.xhtml = """
                <?xml version="1.0" encoding="utf-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head><title>\(path)</title></head>
                <body>
                \(body)
                </body></html>
                """
        }
    }

    static let defaultMetadata: [String: String] = [
        "title": "Antique Standard Bible",
        "creator": "A Committee",
        "publisher": "Example Press",
        "rights": "Text is in the public domain. Typesetting © 2026 Example Press.",
        "language": "en",
        "identifier": "urn:isbn:9780000000001",
    ]

    /// A whole ePub: mimetype, container, package document and the documents given, in spine order.
    static func epub(documents: [Document],
                     metadata: [String: String] = defaultMetadata,
                     extra: [ZipEntry] = [],
                     opfOverride: String? = nil,
                     deflate: Bool = false) -> Data {
        var entries: [ZipEntry] = [ZipEntry("mimetype", "application/epub+zip")]
        entries.append(ZipEntry("META-INF/container.xml", """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
            """))
        let manifest = documents.enumerated().map { index, document in
            "<item id=\"d\(index)\" href=\"\(document.path)\" media-type=\"application/xhtml+xml\"/>"
        }.joined(separator: "\n    ")
        let spine = documents.indices.map { "<itemref idref=\"d\($0)\"/>" }.joined(separator: "\n    ")
        let dublinCore = metadata.sorted { $0.key < $1.key }
            .map { "<dc:\($0.key)>\(escape($0.value))</dc:\($0.key)>" }
            .joined(separator: "\n    ")
        let opf = opfOverride ?? """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                \(dublinCore)
              </metadata>
              <manifest>
                \(manifest)
              </manifest>
              <spine>
                \(spine)
              </spine>
            </package>
            """
        entries.append(ZipEntry("OEBPS/content.opf", opf, deflate: deflate))
        for document in documents {
            entries.append(ZipEntry("OEBPS/\(document.path)", document.xhtml, deflate: deflate))
        }
        entries.append(contentsOf: extra)
        return zip(entries)
    }

    static func escape(_ text: String) -> String {
        text.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
    }

    // MARK: - USFM

    static func usfmZip(_ books: [(name: String, usfm: String)],
                        copyright: String? = "Copyright © 2026 Example Press. Released into the Public Domain.",
                        metadataXML: String? = nil) -> Data {
        var entries = books.map { ZipEntry($0.name, $0.usfm, deflate: true) }
        if let copyright {
            entries.append(ZipEntry("copr.htm", """
                <html><body><p>\(escape(copyright))</p></body></html>
                """))
        }
        if let metadataXML {
            entries.append(ZipEntry("metadata.xml", metadataXML))
        }
        return zip(entries)
    }

    // MARK: - Files on disk

    static func write(_ data: Data, named name: String) throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "scripture-import-tests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appending(path: name)
        try data.write(to: url)
        return url
    }

    static func scratchDirectory() throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "scripture-import-store-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}

private extension Data {
    mutating func append(le16 value: UInt16) { append(contentsOf: [UInt8(value & 0xFF), UInt8(value >> 8)]) }
    mutating func append(le32 value: UInt32) {
        append(contentsOf: [UInt8(value & 0xFF), UInt8((value >> 8) & 0xFF), UInt8((value >> 16) & 0xFF), UInt8(value >> 24)])
    }
}
