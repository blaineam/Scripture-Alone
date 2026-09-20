import Foundation

/// The publisher's terms, as they appear in a package header: data the app enforces, not a promise
/// in a PDF. The header is plaintext and signed, so anyone may read the terms without a key and
/// nobody may change them without the publisher's signing key.
///
/// This is the wire form — booleans, a count and an ISO 8601 date string, chosen so the same JSON
/// can be written by `Tools/package_translation.py` on a publisher's own machine and read here.
/// `rights` turns it into the single `TranslationRights` value the app gates on, so a packaged
/// translation is judged by the same code that judges the bundled ASV.
public struct PackagePolicy: Hashable, Sendable, Codable {
    public var allowCopy: Bool
    public var allowShare: Bool
    public var allowVerseImages: Bool
    public var allowNotesExport: Bool
    public var allowExternalHandoff: Bool
    public var allowOfflineStorage: Bool
    public var maxQuotationVerses: Int
    /// ISO 8601, either a date (`2027-01-01`) or a timestamp (`2027-01-01T00:00:00Z`). Nil for a
    /// grant that does not lapse.
    public var expires: String?

    public init(allowCopy: Bool = true, allowShare: Bool = true, allowVerseImages: Bool = true,
                allowNotesExport: Bool = true, allowExternalHandoff: Bool = false,
                allowOfflineStorage: Bool = true,
                maxQuotationVerses: Int = TranslationInfo.quotationVerseLimit,
                expires: String? = nil) {
        self.allowCopy = allowCopy
        self.allowShare = allowShare
        self.allowVerseImages = allowVerseImages
        self.allowNotesExport = allowNotesExport
        self.allowExternalHandoff = allowExternalHandoff
        self.allowOfflineStorage = allowOfflineStorage
        self.maxQuotationVerses = maxQuotationVerses
        self.expires = expires
    }

    /// A missing key in the JSON must never read as "allowed". Decoding therefore defaults every
    /// permission to the most restrictive value and the quotation cap to zero: a policy an older
    /// tool wrote without a field the app now knows about grants nothing new, and a truncated
    /// policy object grants nothing at all.
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        allowCopy = try container.decodeIfPresent(Bool.self, forKey: .allowCopy) ?? false
        allowShare = try container.decodeIfPresent(Bool.self, forKey: .allowShare) ?? false
        allowVerseImages = try container.decodeIfPresent(Bool.self, forKey: .allowVerseImages) ?? false
        allowNotesExport = try container.decodeIfPresent(Bool.self, forKey: .allowNotesExport) ?? false
        allowExternalHandoff = try container.decodeIfPresent(Bool.self, forKey: .allowExternalHandoff) ?? false
        allowOfflineStorage = try container.decodeIfPresent(Bool.self, forKey: .allowOfflineStorage) ?? false
        maxQuotationVerses = try container.decodeIfPresent(Int.self, forKey: .maxQuotationVerses) ?? 0
        expires = try container.decodeIfPresent(String.self, forKey: .expires)
    }

    /// Reading, and nothing else: the strictest terms a publisher could grant while still having an
    /// app. The starting point for the packaging tool, which turns permissions *on* by flag.
    public static let readingOnly = PackagePolicy(allowCopy: false, allowShare: false,
                                                 allowVerseImages: false, allowNotesExport: false,
                                                 allowExternalHandoff: false, allowOfflineStorage: true,
                                                 maxQuotationVerses: 0)

    /// The publishers' own standard permissions, as published by Crossway, Lockman and Holman:
    /// quotation with attribution up to a few hundred verses, no licence to pass the text to other
    /// software. What the demonstration packages carry.
    public static let publisherStandard = PackagePolicy()

    /// The one value the app gates on. Throws if `expires` is present but unreadable: an expiry the
    /// app cannot parse must stop the package, never quietly become "no expiry".
    public func rights() throws -> TranslationRights {
        var expiryDate: Date?
        if let expires, !expires.isEmpty {
            guard let parsed = Self.date(fromISO8601: expires) else {
                throw TranslationPackageError.damagedHeader("expires isn’t an ISO 8601 date: \(expires)")
            }
            expiryDate = parsed
        }
        return TranslationRights(allowCopy: allowCopy,
                                 allowShare: allowShare,
                                 allowVerseImages: allowVerseImages,
                                 allowNotesExport: allowNotesExport,
                                 allowExternalHandoff: allowExternalHandoff,
                                 allowOfflineStorage: allowOfflineStorage,
                                 maxQuotationVerses: maxQuotationVerses,
                                 expires: expiryDate)
    }

    /// `2027-01-01T00:00:00Z` or the bare `2027-01-01`, which means midnight UTC that morning.
    static func date(fromISO8601 text: String) -> Date? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if let date = try? Date(trimmed, strategy: .iso8601) { return date }
        if let date = try? Date(trimmed, strategy: .iso8601.year().month().day()) { return date }
        return nil
    }

    static func iso8601(_ date: Date) -> String {
        date.formatted(.iso8601)
    }
}

public extension PackagePolicy {
    /// The policy a `TranslationRights` value expresses, for round-tripping and for the packaging
    /// tool's `--policy` file. `Int.max` becomes the unlimited cap it already is.
    init(_ rights: TranslationRights) {
        self.init(allowCopy: rights.allowCopy,
                  allowShare: rights.allowShare,
                  allowVerseImages: rights.allowVerseImages,
                  allowNotesExport: rights.allowNotesExport,
                  allowExternalHandoff: rights.allowExternalHandoff,
                  allowOfflineStorage: rights.allowOfflineStorage,
                  maxQuotationVerses: rights.maxQuotationVerses,
                  expires: rights.expires.map(PackagePolicy.iso8601))
    }
}
