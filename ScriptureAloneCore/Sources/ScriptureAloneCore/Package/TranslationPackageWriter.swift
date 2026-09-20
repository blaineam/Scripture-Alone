import Foundation
import CryptoKit

/// Builds a `.sabible` package. The reference implementation of the format in Swift, beside
/// `Tools/package_translation.py`, which is what a publisher actually runs on their own machine with
/// their own keys.
///
/// Two implementations of one format is deliberate: a format described only by the code that reads
/// it is a format nobody else can produce. The test suite builds packages with this writer and reads
/// the tool's packages with the same reader, so a divergence between the two shows up as a failing
/// test rather than as a package that will not open on a publisher's desk.
///
/// The layout has one subtlety worth stating, because both implementations must do it. Each chapter's
/// associated data contains the SHA-256 of the header, and the header contains each chapter's offset
/// and length — so the body cannot be sealed before the header exists, and the header cannot be
/// written before the body is laid out. It resolves because AES-GCM's combined form is always
/// plaintext + 28 bytes: the writer computes every length arithmetically, writes the header, hashes
/// it, and only then seals. If a sealed blob ever came out a different size, that assumption broke
/// and the writer refuses rather than emitting a package whose index is a lie.
public struct TranslationPackageWriter: Sendable {
    public struct SourceVerse: Sendable {
        public let key: Int
        public let text: String
        /// Words of Christ as `[scalarStart, scalarLength]` pairs — the same form the SQLite store's
        /// `red` column keeps.
        public let redScalarPairs: [[Int]]

        public init(key: Int, text: String, redScalarPairs: [[Int]] = []) {
            self.key = key
            self.text = text
            self.redScalarPairs = redScalarPairs
        }
    }

    public struct SourceChapter: Sendable {
        public let ref: ChapterRef
        public let verses: Int
        /// The compact layout JSON, byte for byte as the store keeps it.
        public let layoutJSON: String
        public let verseRows: [SourceVerse]

        public init(ref: ChapterRef, verses: Int, layoutJSON: String, verseRows: [SourceVerse]) {
            self.ref = ref
            self.verses = verses
            self.layoutJSON = layoutJSON
            self.verseRows = verseRows
        }
    }

    public enum WriterError: Error, LocalizedError {
        case noChapters
        case sealedSizeChanged
        case headerTooLarge(Int)

        public var errorDescription: String? {
            switch self {
            case .noChapters: "A package needs at least one chapter."
            case .sealedSizeChanged: "The sealed size didn’t match the index; the package was not written."
            case .headerTooLarge(let bytes): "The header is \(bytes) bytes, more than the format allows."
            }
        }
    }

    public static func data(identity: PackagedTranslationIdentity,
                            policy: PackagePolicy,
                            chapters: [SourceChapter],
                            contentKey: SymmetricKey,
                            signingKey: Curve25519.Signing.PrivateKey,
                            packageID: String = UUID().uuidString,
                            createdAt: Date = Date(),
                            buildIndex: Bool = true) throws -> Data {
        guard !chapters.isEmpty else { throw WriterError.noChapters }
        let ordered = chapters.sorted { $0.ref < $1.ref }

        // Pass one: the plaintext of each chapter, and therefore its sealed length and offset.
        var plaintexts: [(ref: ChapterRef, data: Data)] = []
        var entries: [PackagedChapterEntry] = []
        var offset = 0
        for chapter in ordered {
            let payload = TranslationPackage.ChapterPayload(
                layout: chapter.layoutJSON,
                verses: chapter.verseRows.map {
                    TranslationPackage.ChapterPayload.Verse(i: $0.key, t: $0.text,
                                                            r: $0.redScalarPairs.isEmpty ? nil : $0.redScalarPairs)
                })
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.withoutEscapingSlashes]
            let plaintext = try encoder.encode(payload)
            let length = plaintext.count + TranslationPackageFormat.sealedChapterOverhead
            entries.append(PackagedChapterEntry(book: chapter.ref.book.rawValue, chapter: chapter.ref.chapter,
                                                verses: chapter.verses, offset: offset, length: length))
            plaintexts.append((chapter.ref, plaintext))
            offset += length
        }

        // The index sits after the chapters in the same body, and its buckets are laid out the same
        // way: length known before anything is sealed, because each one is bound to the finished
        // header too.
        var bucketPlaintexts: [Data] = []
        var bucketEntries: [PackagedBucketEntry] = []
        if buildIndex {
            bucketPlaintexts = PackageSearchIndex.buildBuckets(
                chapters: ordered, contentKey: contentKey, translationID: identity.id,
                parameters: (buckets: PackageSearchIndex.defaultBucketCount,
                             prefixMin: PackageSearchIndex.defaultPrefixMin,
                             prefixMax: PackageSearchIndex.defaultPrefixMax,
                             padding: PackageSearchIndex.defaultPadding))
            for (bucket, plaintext) in bucketPlaintexts.enumerated() {
                let length = plaintext.count + TranslationPackageFormat.sealedChapterOverhead
                bucketEntries.append(PackagedBucketEntry(bucket: bucket, offset: offset, length: length))
                offset += length
            }
        }

        let header = TranslationPackageHeader(packageID: packageID,
                                              createdAt: createdAt.formatted(.iso8601),
                                              translation: identity,
                                              policy: policy,
                                              crypto: PackageCryptoParameters(
                                                keyID: TranslationPackageKeys.contentKeyID(contentKey),
                                                publisherKeyID: TranslationPackageKeys.publisherKeyID(
                                                    signingKey.publicKey.rawRepresentation)),
                                              chapters: entries,
                                              index: buildIndex ? PackageIndexParameters(entries: bucketEntries) : nil)
        let headerEncoder = JSONEncoder()
        headerEncoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let headerBytes = try headerEncoder.encode(header)
        guard headerBytes.count <= TranslationPackageFormat.maximumHeaderBytes else {
            throw WriterError.headerTooLarge(headerBytes.count)
        }
        let headerDigest = Data(SHA256.hash(data: headerBytes))
        let signature = try signingKey.signature(for: headerBytes)

        // Pass two: seal each chapter, bound to the header that now exists.
        var body = Data()
        for (index, chapter) in plaintexts.enumerated() {
            let associated = TranslationPackage.associatedData(packageID: packageID,
                                                              translationID: identity.id,
                                                              chapter: chapter.ref,
                                                              headerDigest: headerDigest)
            let box = try AES.GCM.seal(chapter.data, using: contentKey, authenticating: associated)
            guard let sealed = box.combined, sealed.count == entries[index].length else {
                throw WriterError.sealedSizeChanged
            }
            body.append(sealed)
        }

        for (bucket, plaintext) in bucketPlaintexts.enumerated() {
            let associated = TranslationPackage.indexAssociatedData(packageID: packageID,
                                                                    translationID: identity.id,
                                                                    bucket: bucket,
                                                                    headerDigest: headerDigest)
            let box = try AES.GCM.seal(plaintext, using: contentKey, authenticating: associated)
            guard let sealed = box.combined, sealed.count == bucketEntries[bucket].length else {
                throw WriterError.sealedSizeChanged
            }
            body.append(sealed)
        }

        var file = Data()
        file.append(TranslationPackageFormat.magic)
        file.append(bigEndian: TranslationPackageFormat.version)
        file.append(bigEndian: UInt32(headerBytes.count))
        file.append(headerBytes)
        file.append(bigEndian: UInt16(signature.count))
        file.append(signature)
        file.append(body)
        return file
    }
}

extension Data {
    mutating func append(bigEndian value: UInt16) {
        append(UInt8(truncatingIfNeeded: value >> 8))
        append(UInt8(truncatingIfNeeded: value))
    }

    mutating func append(bigEndian value: UInt32) {
        for shift in [24, 16, 8, 0] { append(UInt8(truncatingIfNeeded: value >> UInt32(shift))) }
    }
}
