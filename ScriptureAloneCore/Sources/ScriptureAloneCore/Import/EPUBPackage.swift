// Importing is an iPhone, iPad and Mac feature: the watch has no file picker and no
// catalogue. It is also 32-bit (arm64_32), where the ZIP64 sentinel 0xFFFF_FFFF does not
// fit in an Int at all — so this code is not merely unused there, it cannot compile.
#if !os(watchOS)
import Foundation

/// Dublin Core metadata from the package document. The `rights` line is the one that matters most:
/// it is the publisher's copyright notice, and it has to survive into the imported store.
public struct EPUBMetadata: Sendable, Hashable, Codable {
    public var title: String?
    public var creator: String?
    public var publisher: String?
    public var rights: String?
    public var language: String?
    public var identifier: String?
    public var date: String?
    public var subjects: [String]

    public init(title: String? = nil, creator: String? = nil, publisher: String? = nil, rights: String? = nil,
                language: String? = nil, identifier: String? = nil, date: String? = nil, subjects: [String] = []) {
        self.title = title
        self.creator = creator
        self.publisher = publisher
        self.rights = rights
        self.language = language
        self.identifier = identifier
        self.date = date
        self.subjects = subjects
    }
}

public struct EPUBManifestItem: Sendable, Hashable, Codable, Identifiable {
    public let id: String
    /// Path inside the archive, resolved against the package document's directory.
    public let path: String
    public let mediaType: String
    public let properties: [String]

    public var isXHTML: Bool {
        mediaType.contains("xhtml") || mediaType.contains("text/html")
            || path.lowercased().hasSuffix(".xhtml") || path.lowercased().hasSuffix(".html") || path.lowercased().hasSuffix(".htm")
    }
}

/// An ePub opened for reading: container, package document, manifest, spine order and metadata.
///
/// Opening is refusal-first. Before anything is decompressed, the archive's *names* are checked for
/// protection artifacts, and a match ends the import there. The engine contains no decryption code
/// of any kind and never will: circumventing a technical protection measure is a separate federal
/// violation from copyright and is not excused by the user having bought the file. A file that
/// merely carries a visible watermark is not protected and opens normally.
public struct EPUBPackage: Sendable {
    public let metadata: EPUBMetadata
    public let manifest: [EPUBManifestItem]
    /// Reading order, resolved to manifest items (idrefs that name nothing are dropped).
    public let spine: [EPUBManifestItem]
    /// Path of the package document inside the archive.
    public let packagePath: String

    private let zip: ZipReader

    public init(url: URL) throws {
        let data: Data
        do {
            data = try Data(contentsOf: url, options: [.mappedIfSafe])
        } catch {
            throw BibleImportError.unreadableFile(error.localizedDescription)
        }
        try self.init(data: data)
    }

    public init(data: Data) throws {
        try self.init(zip: ZipReader(data: data))
    }

    init(zip: ZipReader) throws {
        try Self.refuseIfProtected(zip)
        self.zip = zip

        // The mimetype entry is the ePub's own declaration of what it is.
        if let mimetype = zip.entry(named: "mimetype") {
            let declared = try String(decoding: zip.data(for: mimetype), as: UTF8.self)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard declared == "application/epub+zip" else {
                throw BibleImportError.notAnEPUB("its mimetype is “\(declared)”")
            }
        }

        guard zip.contains("META-INF/container.xml") else {
            throw BibleImportError.notAnEPUB("it has no META-INF/container.xml")
        }
        let containerXML = try Self.text(zip.data(for: "META-INF/container.xml"))
        guard let rootPath = Self.rootfilePath(containerXML) else {
            throw BibleImportError.notAnEPUB("its container names no package document")
        }
        guard zip.contains(rootPath) else {
            throw BibleImportError.notAnEPUB("its package document (\(rootPath)) is missing")
        }
        packagePath = rootPath

        let opf = try Self.text(zip.data(for: rootPath))
        let parsed = try Self.parsePackage(opf, base: Self.directory(of: rootPath))
        metadata = parsed.metadata
        manifest = parsed.manifest
        let byID = Dictionary(parsed.manifest.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        var order = parsed.spine.compactMap { byID[$0] }
        if order.isEmpty {
            // A malformed spine should not cost us the whole book: fall back to manifest order.
            order = parsed.manifest.filter(\.isXHTML)
        }
        spine = order.filter { $0.isXHTML && zip.contains($0.path) }
    }

    /// The decoded text of one document in the archive.
    public func document(_ item: EPUBManifestItem) throws -> String {
        Self.text(try zip.data(for: item.path))
    }

    /// Every stylesheet the manifest lists, decoded. Unreadable ones are skipped: styling is a hint.
    public var stylesheets: [String] {
        manifest.filter { $0.mediaType == "text/css" }.compactMap { try? document($0) }
    }

    /// Every file name in the archive, for diagnostics. Reading a name decompresses nothing.
    public var entryNames: [String] { zip.names }

    // MARK: - Refusal

    /// Protection artifacts, matched on archive *names* only — nothing is decompressed to decide this.
    static func protectionEvidence(names: [String]) -> DRMEvidence? {
        for name in names {
            let lowered = name.lowercased()
            let leaf = lowered.split(separator: "/").last.map(String.init) ?? lowered
            switch leaf {
            case "encryption.xml": return .encryptionManifest
            case "rights.xml": return .adobeADEPT
            case "license.lcpl": return .readiumLCP
            case "sinf.xml": return .appleFairPlay
            default: break
            }
            if leaf.hasSuffix(".adept") || lowered.contains("adept") { return .adobeADEPT }
            if leaf.hasSuffix(".lcpl") { return .readiumLCP }
        }
        return nil
    }

    private static func refuseIfProtected(_ zip: ZipReader) throws {
        if let evidence = protectionEvidence(names: zip.names) {
            throw BibleImportError.protectedByDRM(evidence)
        }
        if let encrypted = zip.entries.first(where: \.isEncrypted) {
            _ = encrypted
            throw BibleImportError.protectedByDRM(.zipEntryEncryption)
        }
    }

    // MARK: - Container and package parsing

    static func rootfilePath(_ containerXML: String) -> String? {
        for event in XMLScanner.scan(containerXML) {
            guard case .start(let tag) = event, tag.name == "rootfile" else { continue }
            if let path = tag.attribute("full-path"), !path.isEmpty {
                return normalizePath(path)
            }
        }
        return nil
    }

    struct ParsedPackage {
        var metadata = EPUBMetadata()
        var manifest: [EPUBManifestItem] = []
        var spine: [String] = []
    }

    static func parsePackage(_ opf: String, base: String) throws -> ParsedPackage {
        var parsed = ParsedPackage()
        var section = ""            // "metadata" | "manifest" | "spine"
        var metaField: String?      // the dc:* element whose text we are collecting
        var metaText = ""

        func commitMetaField() {
            guard let field = metaField else { return }
            let value = metaText.trimmingCharacters(in: .whitespacesAndNewlines)
            metaField = nil
            metaText = ""
            guard !value.isEmpty else { return }
            switch field {
            case "title": parsed.metadata.title = parsed.metadata.title ?? value
            case "creator": parsed.metadata.creator = parsed.metadata.creator ?? value
            case "publisher": parsed.metadata.publisher = parsed.metadata.publisher ?? value
            case "rights": parsed.metadata.rights = parsed.metadata.rights ?? value
            case "language": parsed.metadata.language = parsed.metadata.language ?? value
            case "identifier": parsed.metadata.identifier = parsed.metadata.identifier ?? value
            case "date": parsed.metadata.date = parsed.metadata.date ?? value
            case "subject": parsed.metadata.subjects.append(value)
            default: break
            }
        }

        for event in XMLScanner.scan(opf) {
            switch event {
            case .start(let tag):
                switch tag.name {
                case "metadata", "manifest", "spine":
                    section = tag.name
                case "meta" where section == "metadata":
                    // Adobe ADEPT announces itself here even when rights.xml was stripped.
                    let name = (tag.attribute("name") ?? "").lowercased()
                    let property = (tag.attribute("property") ?? "").lowercased()
                    if name.hasPrefix("adept.") || property.hasPrefix("adept.") {
                        throw BibleImportError.protectedByDRM(.adobeADEPT)
                    }
                case "item" where section == "manifest":
                    guard let id = tag.attribute("id"), let href = tag.attribute("href") else { break }
                    let properties = (tag.attribute("properties") ?? "")
                        .split(whereSeparator: \.isWhitespace).map(String.init)
                    parsed.manifest.append(EPUBManifestItem(id: id, path: resolve(base: base, href: href),
                                                            mediaType: tag.attribute("media-type") ?? "",
                                                            properties: properties))
                case "itemref" where section == "spine":
                    if let idref = tag.attribute("idref") { parsed.spine.append(idref) }
                case "title", "creator", "publisher", "rights", "language", "identifier", "date", "subject":
                    if section == "metadata" {
                        commitMetaField()
                        metaField = tag.name
                        metaText = ""
                    }
                default:
                    break
                }
            case .text(let text):
                if metaField != nil { metaText += text }
            case .end(let name):
                if name == metaField { commitMetaField() }
                if name == "metadata" || name == "manifest" || name == "spine" { section = "" }
            }
        }
        commitMetaField()
        guard !parsed.manifest.isEmpty else {
            throw BibleImportError.notAnEPUB("its package document lists no files")
        }
        return parsed
    }

    // MARK: - Paths

    static func directory(of path: String) -> String {
        guard let slash = path.lastIndex(of: "/") else { return "" }
        return String(path[...slash])
    }

    /// Resolves a manifest href against the package document's directory, dropping any fragment
    /// and percent-decoding the name so it matches the archive's own entry names.
    static func resolve(base: String, href: String) -> String {
        var target = href
        if let hash = target.firstIndex(of: "#") { target = String(target[..<hash]) }
        target = target.removingPercentEncoding ?? target
        if target.hasPrefix("/") { return normalizePath(String(target.dropFirst())) }
        return normalizePath(base + target)
    }

    /// Collapses "." and ".." segments.
    static func normalizePath(_ path: String) -> String {
        var stack: [String] = []
        for piece in path.split(separator: "/", omittingEmptySubsequences: true) {
            switch piece {
            case ".": continue
            case "..": _ = stack.popLast()
            default: stack.append(String(piece))
            }
        }
        return stack.joined(separator: "/")
    }

    static func text(_ data: Data) -> String {
        // The explicit-endian UTF-16 decoders keep the byte-order mark as a U+FEFF character; it is
        // not text, so it goes (the UTF-8 decoder already drops its own).
        if data.count >= 2, data[data.startIndex] == 0xFF, data[data.startIndex + 1] == 0xFE {
            return dropByteOrderMark(String(data: data, encoding: .utf16LittleEndian) ?? String(decoding: data, as: UTF8.self))
        }
        if data.count >= 2, data[data.startIndex] == 0xFE, data[data.startIndex + 1] == 0xFF {
            return dropByteOrderMark(String(data: data, encoding: .utf16BigEndian) ?? String(decoding: data, as: UTF8.self))
        }
        if let utf8 = String(data: data, encoding: .utf8) { return utf8 }
        return String(data: data, encoding: .isoLatin1) ?? String(decoding: data, as: UTF8.self)
    }

    private static func dropByteOrderMark(_ text: String) -> String {
        text.unicodeScalars.first == "\u{FEFF}" ? String(String.UnicodeScalarView(text.unicodeScalars.dropFirst())) : text
    }
}
#endif
