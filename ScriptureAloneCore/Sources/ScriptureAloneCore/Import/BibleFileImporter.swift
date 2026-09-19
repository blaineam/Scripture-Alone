import Foundation

/// The kinds of file the engine reads. Both are ZIP containers, and both are refused outright if
/// they carry any protection artifact.
public enum ImportedFileFormat: String, Sendable, Hashable, Codable {
    /// A DRM-free ePub the user already owns.
    case epub
    /// A zip of USFM books — the shape eBible.org publishes.
    case usfmZip

    public var label: String {
        switch self {
        case .epub: "ePub"
        case .usfmZip: "USFM"
        }
    }
}

/// What a file turned out to be, and what it says about itself, without parsing its scripture.
public struct BibleImportPreview: Sendable {
    public let format: ImportedFileFormat
    /// A starting point for the import sheet. The user is expected to confirm the name and the
    /// copyright line; nothing here is trusted.
    public let identity: ImportedTranslationIdentity
    /// Spine documents (ePub) or USFM books (zip).
    public let documentCount: Int
    /// True when the file itself carried a copyright line. When false the UI must collect one:
    /// `ImportedBibleBuilder` refuses to write a store with nothing to attribute.
    public var hasCopyright: Bool { !identity.copyright.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}

public struct BibleImportResult: Sendable {
    public let storeURL: URL
    public let format: ImportedFileFormat
    public let identity: ImportedTranslationIdentity
    public let report: ImportCoverageReport
}

/// One entry point: hand it a file URL and it works out what the file is, reads it, and writes a
/// store `BibleStore` can open.
///
/// Nothing here touches the main actor and nothing here touches the network. Importing a whole
/// Bible takes seconds, so call it from a background task:
///
/// ```swift
/// let result = try await Task.detached {
///     try BibleFileImporter().importBible(at: url, into: directory)
/// }.value
/// ```
///
/// The engine writes one local store file and nothing else: no export, no sharing, no sync. An
/// imported text is for the user who imported it, on the device they imported it to.
public struct BibleFileImporter: Sendable {
    public var options: BibleTextExtractor.Options

    public init(options: BibleTextExtractor.Options = BibleTextExtractor.Options()) {
        self.options = options
    }

    /// Looks at the file and says what it is. Refuses protected files before reading any content.
    public func preview(_ url: URL) throws -> BibleImportPreview {
        try preview(zip: try open(url))
    }

    /// Reads the file's scripture without writing anything — for callers that want to show the
    /// coverage report before committing.
    public func read(_ url: URL) throws -> (bible: ExtractedBible, preview: BibleImportPreview) {
        let zip = try open(url)
        switch try Self.format(of: zip) {
        case .epub:
            let package = try EPUBPackage(zip: zip)
            let bible = try BibleTextExtractor(options: options).extract(from: package)
            return (bible, BibleImportPreview(format: .epub,
                                              identity: .suggested(from: package.metadata),
                                              documentCount: package.spine.count))
        case .usfmZip:
            let package = try USFMPackage(zip: zip)
            let bible = try USFMImporter(options: options).extract(from: package)
            return (bible, BibleImportPreview(format: .usfmZip,
                                              identity: .suggested(from: package.metadata),
                                              documentCount: package.files.count))
        }
    }

    /// Reads the file and writes the store. `identity` overrides what the file said about itself —
    /// the import sheet is expected to pass the name and copyright line the user confirmed.
    @discardableResult
    public func importBible(at url: URL, as identity: ImportedTranslationIdentity? = nil,
                            into directory: URL) throws -> BibleImportResult {
        let (bible, preview) = try read(url)
        let chosen = identity ?? preview.identity
        let storeURL = directory.appending(path: Self.storeFilename(for: chosen))
        let report = try ImportedBibleBuilder.write(bible, identity: chosen, to: storeURL)
        return BibleImportResult(storeURL: storeURL, format: preview.format, identity: chosen, report: report)
    }

    /// `IMPORT-XXXX.sqlite`, beside the bundled `ASV.sqlite` naming but never colliding with it.
    public static func storeFilename(for identity: ImportedTranslationIdentity) -> String {
        let safe = identity.id.filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
        return (safe.isEmpty ? "IMPORT" : safe) + ".sqlite"
    }

    // MARK: - Sniffing

    private func open(_ url: URL) throws -> ZipReader {
        let data: Data
        do {
            data = try Data(contentsOf: url, options: [.mappedIfSafe])
        } catch {
            throw BibleImportError.unreadableFile(error.localizedDescription)
        }
        return try ZipReader(data: data)
    }

    private func preview(zip: ZipReader) throws -> BibleImportPreview {
        switch try Self.format(of: zip) {
        case .epub:
            let package = try EPUBPackage(zip: zip)
            return BibleImportPreview(format: .epub, identity: .suggested(from: package.metadata),
                                      documentCount: package.spine.count)
        case .usfmZip:
            let package = try USFMPackage(zip: zip)
            return BibleImportPreview(format: .usfmZip, identity: .suggested(from: package.metadata),
                                      documentCount: package.files.count)
        }
    }

    /// Decides by structure, not by file extension — a `.zip` holding an ePub is an ePub. The
    /// protection check comes first, so a protected file is refused as protected rather than as an
    /// unreadable format, and no content is decompressed either way.
    static func format(of zip: ZipReader) throws -> ImportedFileFormat {
        if let evidence = EPUBPackage.protectionEvidence(names: zip.names) {
            throw BibleImportError.protectedByDRM(evidence)
        }
        if zip.entries.contains(where: \.isEncrypted) {
            throw BibleImportError.protectedByDRM(.zipEntryEncryption)
        }
        if zip.contains("META-INF/container.xml") { return .epub }
        if zip.names.contains(where: { $0.lowercased().hasSuffix(".usfm") || $0.lowercased().hasSuffix(".sfm") }) {
            return .usfmZip
        }
        if zip.names.contains(where: { $0.lowercased().hasSuffix(".xhtml") || $0.lowercased().hasSuffix(".html") }) {
            throw BibleImportError.notAnEPUB("it has no META-INF/container.xml")
        }
        throw BibleImportError.unsupportedFormat("it is neither an ePub nor a set of USFM books")
    }
}

public extension ImportedTranslationIdentity {
    /// A starting point taken from a USFM zip's own copyright page and DBL metadata.
    static func suggested(from metadata: USFMMetadata) -> ImportedTranslationIdentity {
        let name = metadata.title?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "Imported Bible"
        let copyright = metadata.copyright?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return ImportedTranslationIdentity(
            id: identifier(for: metadata.identifier ?? metadata.abbreviation ?? name),
            name: name,
            abbreviation: metadata.abbreviation?.uppercased().nilIfEmpty ?? abbreviation(for: name),
            copyright: copyright,
            license: metadata.license?.nilIfEmpty ?? ImportedTranslationIdentity.unknownLicense,
            source: "Imported USFM")
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
