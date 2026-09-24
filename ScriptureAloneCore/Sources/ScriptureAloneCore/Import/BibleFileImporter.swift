// Importing is an iPhone, iPad and Mac feature: the watch has no file picker and no
// catalogue. It is also 32-bit (arm64_32), where the ZIP64 sentinel 0xFFFF_FFFF does not
// fit in an Int at all — so this code is not merely unused there, it cannot compile.
#if !os(watchOS)
import Foundation
import PDFKit

/// The kinds of file the engine reads. Both are ZIP containers, and both are refused outright if
/// they carry any protection artifact.
public enum ImportedFileFormat: String, Sendable, Hashable, Codable {
    /// A DRM-free ePub the user already owns.
    case epub
    /// A zip of USFM books — the shape eBible.org publishes.
    case usfmZip
    /// A typeset PDF with a text layer, read by its type sizes (`PDFBibleReader`).
    case pdf

    public var label: String {
        switch self {
        case .epub: "ePub"
        case .usfmZip: "USFM"
        case .pdf: "PDF"
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
        if Self.isPDF(url) {
            let document = try Self.openPDF(url)
            return BibleImportPreview(format: .pdf, identity: Self.suggestedIdentity(for: document, url: url),
                                      documentCount: document.pageCount)
        }
        return try preview(zip: try open(url))
    }

    /// Reads the file's scripture without writing anything — for callers that want to show the
    /// coverage report before committing.
    public func read(_ url: URL) throws -> (bible: ExtractedBible, preview: BibleImportPreview) {
        if Self.isPDF(url) {
            let document = try Self.openPDF(url)
            let bible = try PDFBibleReader(options: options).extract(from: document)
            return (bible, BibleImportPreview(format: .pdf, identity: Self.suggestedIdentity(for: document, url: url),
                                              documentCount: document.pageCount))
        }
        let zip = try open(url)
        switch try Self.format(of: zip) {
        case .epub:
            let package = try EPUBPackage(zip: zip)
            var bible = try BibleTextExtractor(options: options).extract(from: package)
            bible.study.publisher = package.metadata.publisher
            return (bible, BibleImportPreview(format: .epub,
                                              identity: Self.suggestedIdentity(for: package),
                                              documentCount: package.spine.count))
        case .pdf:
            // `format(of:)` reads ZIP containers only; a PDF is caught before a ZIP is opened.
            throw BibleImportError.notAZipArchive
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
    ///
    /// - Parameter redLetters: a translation that marks the words of Christ, to carry them over
    ///   from when the file marks none of its own (`inferRedLetters`). Verses that don't align
    ///   with it are left as they are.
    @discardableResult
    public func importBible(at url: URL, as identity: ImportedTranslationIdentity? = nil,
                            into directory: URL, redLetters: BibleStore? = nil) throws -> BibleImportResult {
        var (bible, preview) = try read(url)
        if let redLetters, options.redLetters {
            bible.inferRedLetters { ref in
                guard let verse = (try? redLetters.verses(in: VerseRange(ref, ref)))?.first else { return nil }
                return (verse.text, RedLetterInference.scalarSpans(verse.red, in: verse.text))
            }
        }
        let chosen = identity ?? preview.identity
        let storeURL = directory.appending(path: Self.storeFilename(for: chosen))
        let report = try ImportedBibleBuilder.write(bible, identity: chosen, to: storeURL)
        return BibleImportResult(storeURL: storeURL, format: preview.format, identity: chosen, report: report)
    }

    /// `IMPORT-XXXX.sqlite`, named like the bundled stores but never colliding with one.
    public static func storeFilename(for identity: ImportedTranslationIdentity) -> String {
        let safe = identity.id.filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
        return (safe.isEmpty ? "IMPORT" : safe) + ".sqlite"
    }

    // MARK: - Recognising the translation

    /// What the file says about itself, plus the translation its copyright page names.
    ///
    /// A study Bible is titled for the study Bible rather than the translation, and its metadata
    /// often says only "All rights reserved"; the translation — and so the terms a quotation from
    /// it is held to — is named on the copyright page. The name is kept; the abbreviation becomes
    /// the translation's, and a generic rights line gives way to the publisher's own notice.
    static func suggestedIdentity(for package: EPUBPackage) -> ImportedTranslationIdentity {
        var identity = ImportedTranslationIdentity.suggested(from: package.metadata)
        guard PublisherTerms.matching(abbreviation: identity.abbreviation, name: identity.name,
                                      copyright: identity.copyright) == nil,
              let terms = recognizedTerms(in: package) else { return identity }
        identity.abbreviation = terms.abbreviation
        if identity.copyright.count < 60 { identity.copyright = terms.notice }
        return identity
    }

    /// The first front-matter page naming a known translation: pages whose file name says
    /// "copyright" or "rights" first, then the opening pages of the book.
    static func recognizedTerms(in package: EPUBPackage) -> PublisherTerms? {
        let front = package.spine.prefix(16)
        let likely = front.filter { item in
            let name = item.path.lowercased()
            return name.contains("copy") || name.contains("rights") || name.contains("legal")
        }
        for item in likely + front.filter({ !likely.contains($0) }) {
            guard let xhtml = try? package.document(item) else { continue }
            let text = xhtml.replacing(/<[^>]+>/, with: " ")
            guard text.localizedCaseInsensitiveContains("copyright") || text.contains("©") else { continue }
            if let terms = PublisherTerms.matching(text: text) { return terms }
        }
        return nil
    }

    // MARK: - PDF

    /// By content, not extension: a PDF starts with "%PDF-".
    static func isPDF(_ url: URL) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        return (try? handle.read(upToCount: 5)).map { $0 == Data("%PDF-".utf8) } ?? false
    }

    /// Opens a PDF, refusing one that is password-locked or whose owner forbids copying its text —
    /// the PDF's own form of protection, honoured as the ePub kinds are.
    static func openPDF(_ url: URL) throws -> PDFDocument {
        guard let document = PDFDocument(url: url) else {
            throw BibleImportError.unsupportedFormat(String(localized: "the PDF could not be opened", bundle: .module, comment: "Completes “This app can’t read that file: %@”."))
        }
        if document.isLocked { throw BibleImportError.protectedByDRM(.pdfPassword) }
        if !document.allowsCopying { throw BibleImportError.protectedByDRM(.pdfCopyProtected) }
        return document
    }

    /// A PDF rarely names itself usefully (its title is often the layout file's). The translation
    /// its copyright page names is the better name; the document title, then the file name, follow.
    static func suggestedIdentity(for document: PDFDocument, url: URL) -> ImportedTranslationIdentity {
        let front = PDFBibleReader.frontMatter(of: document)
        let attributes = document.documentAttributes ?? [:]
        let title = (attributes[PDFDocumentAttribute.titleAttribute] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let terms = PublisherTerms.matching(text: front)
        let name = terms?.markers.first ?? title.flatMap { $0.isEmpty ? nil : $0 }
            ?? url.deletingPathExtension().lastPathComponent.replacingOccurrences(of: "_", with: " ")
        let copyrightLine = front.components(separatedBy: .newlines)
            .map { $0.replacingOccurrences(of: "\u{AD}", with: "").trimmingCharacters(in: .whitespaces) }
            .first { $0.contains("©") || $0.localizedCaseInsensitiveContains("copyright ") }
        let copyright = terms?.notice ?? copyrightLine ?? ""
        return ImportedTranslationIdentity(
            id: ImportedTranslationIdentity.identifier(for: (title ?? "") + name + (terms?.abbreviation ?? "")),
            name: name,
            abbreviation: terms?.abbreviation ?? ImportedTranslationIdentity.abbreviation(for: name),
            copyright: copyright,
            license: ImportedTranslationIdentity.unknownLicense,
            source: "Imported PDF")
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
            return BibleImportPreview(format: .epub, identity: Self.suggestedIdentity(for: package),
                                      documentCount: package.spine.count)
        case .pdf:
            // `format(of:)` reads ZIP containers only; a PDF is caught before a ZIP is opened.
            throw BibleImportError.notAZipArchive
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
            throw BibleImportError.notAnEPUB(String(localized: "it has no META-INF/container.xml", bundle: .module, comment: "Completes “That file isn’t a readable ePub: %@”. Keep META-INF/container.xml as is."))
        }
        throw BibleImportError.unsupportedFormat(String(localized: "it is neither an ePub nor a set of USFM books", bundle: .module, comment: "Completes “This app can’t read that file: %@”."))
    }
}

public extension ImportedTranslationIdentity {
    /// A starting point taken from a USFM zip's own copyright page and DBL metadata.
    static func suggested(from metadata: USFMMetadata) -> ImportedTranslationIdentity {
        let name = metadata.title?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? String(localized: "Imported Bible", bundle: .module, comment: "Suggested name for an imported Bible translation that has no title")
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
#endif
