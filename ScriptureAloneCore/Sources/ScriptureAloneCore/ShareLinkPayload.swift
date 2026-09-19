import Foundation

/// A passage flattened for sharing: verses joined with a space, each prefixed by its number
/// when there is more than one ("16 For God… 17 For God…"). Words of Christ and verse numbers
/// are UTF-16 ranges into `text`, so they line up with `NSString` and JavaScript strings.
public struct SharePassageText: Hashable, Sendable {
    public var text: String
    public var red: [NSRange]
    public var numbers: [NSRange]

    public init(text: String, red: [NSRange] = [], numbers: [NSRange] = []) {
        self.text = text
        self.red = red
        self.numbers = numbers
    }

    /// Numbers are added only when `numbered` and there is more than one verse. A verse that
    /// starts a new chapter mid-passage is numbered "4:1" so the break stays readable.
    public init(verses: [VerseText], numbered: Bool = true) {
        let showNumbers = numbered && verses.count > 1
        var text = ""
        var red: [NSRange] = []
        var numbers: [NSRange] = []
        var previous: VerseRef?
        for verse in verses {
            if !text.isEmpty { text += " " }
            if showNumbers {
                let label = previous.map { $0.chapterKey != verse.ref.chapterKey } == true
                    ? "\(verse.ref.chapter):\(verse.ref.verse)"
                    : "\(verse.ref.verse)"
                numbers.append(NSRange(location: text.utf16.count, length: label.utf16.count))
                text += label + " "
            }
            let offset = text.utf16.count
            for range in verse.red where range.length > 0 {
                let shifted = NSRange(location: offset + range.location, length: range.length)
                if let last = red.last, NSMaxRange(last) == shifted.location {
                    red[red.count - 1].length += shifted.length
                } else {
                    red.append(shifted)
                }
            }
            text += verse.text
            previous = verse.ref
        }
        self.init(text: text, red: red, numbers: numbers)
    }
}

/// The contract for share links: `https://wemiller.com/apps/scripture-alone/#s=<payload>`, where
/// the payload is base64url (no padding) of compact UTF-8 JSON. The passage rides in the URL
/// fragment, which browsers never send to a server. See `docs/share-links.md`.
public struct ShareLinkPayload: Hashable, Sendable {
    public static let version = 1
    public static let webBase = "https://wemiller.com/apps/scripture-alone/"
    public static let webHost = "wemiller.com"
    public static let webPath = "/apps/scripture-alone"
    public static let scheme = "scripturealone"
    /// Longest `t` (in characters) a link carries; longer passages are shared as an image only.
    public static let maxTextLength = 1_500
    /// Decoding refuses anything wildly larger than a link we would ever make.
    static let maxDecodedTextLength = 6_000

    /// "John 3:16–17"
    public var reference: String
    /// Verse ranges in storage form, comma separated: "43003016-43003017" or "43003016-43003017,43003020-43003020".
    public var keys: String
    /// Translation abbreviation ("ASV").
    public var translation: String
    public var text: String
    /// Words of Christ as UTF-16 ranges into `text`.
    public var red: [NSRange]
    /// Card template ("parchment"), typeface ("serif") and aspect ("square"). Optional; the web falls back to defaults.
    public var template: String?
    public var font: String?
    public var aspect: String?

    public init(reference: String, keys: String, translation: String, text: String, red: [NSRange] = [],
                template: String? = nil, font: String? = nil, aspect: String? = nil) {
        self.reference = reference
        self.keys = keys
        self.translation = translation
        self.text = text
        self.red = red
        self.template = template
        self.font = font
        self.aspect = aspect
    }

    public init(ranges: [VerseRange], reference: String, translation: String, passage: SharePassageText,
                template: String? = nil, font: String? = nil, aspect: String? = nil) {
        self.init(reference: reference, keys: ranges.map(\.storageString).joined(separator: ","),
                  translation: translation, text: passage.text, red: passage.red,
                  template: template, font: font, aspect: aspect)
    }

    /// The verse ranges named by `keys`. A lone key ("43003016") is one verse.
    public var ranges: [VerseRange] {
        keys.split(separator: ",").compactMap { part -> VerseRange? in
            let part = String(part).trimmingCharacters(in: .whitespaces)
            if let range = VerseRange(storageString: part) { return range }
            return Int(part).flatMap(VerseRef.init(key:)).map { VerseRange($0) }
        }
    }

    /// Whether the passage is short enough to travel in a link.
    public var fitsInLink: Bool { text.count <= Self.maxTextLength }

    // MARK: Encoding

    public enum DecodingError: Error, Equatable {
        case missingPayload
        case notBase64
        case malformed
        case unsupportedVersion(Int)
        case tooLong
    }

    /// The fragment payload (base64url, no padding).
    public func encoded() throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return Self.base64URLEncode(try encoder.encode(self))
    }

    public func webURL() throws -> URL {
        guard let url = URL(string: "\(Self.webBase)#s=\(try encoded())") else { throw DecodingError.malformed }
        return url
    }

    public init(encoded payload: String) throws {
        guard let data = Self.base64URLDecode(payload) else { throw DecodingError.notBase64 }
        let decoded: ShareLinkPayload
        do {
            decoded = try JSONDecoder().decode(ShareLinkPayload.self, from: data)
        } catch let error as DecodingError {
            throw error
        } catch {
            throw DecodingError.malformed
        }
        self = decoded
    }

    /// Reads the `s=` parameter from a URL's fragment (`#s=…`, also `#…&s=…`).
    public init(url: URL) throws {
        guard let fragment = url.fragment(percentEncoded: false),
              let value = fragment.split(separator: "&").first(where: { $0.hasPrefix("s=") })?.dropFirst(2),
              !value.isEmpty
        else { throw DecodingError.missingPayload }
        try self.init(encoded: String(value))
    }

    static func base64URLEncode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func base64URLDecode(_ string: String) -> Data? {
        var base64 = string.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = base64.count % 4
        if remainder == 1 { return nil }
        if remainder > 0 { base64 += String(repeating: "=", count: 4 - remainder) }
        return Data(base64Encoded: base64)
    }
}

extension ShareLinkPayload: Codable {
    enum CodingKeys: String, CodingKey {
        case v, ref, k, tr, t, red, tp, f, a
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(Self.version, forKey: .v)
        try container.encode(reference, forKey: .ref)
        try container.encode(keys, forKey: .k)
        try container.encode(translation, forKey: .tr)
        try container.encode(text, forKey: .t)
        if !red.isEmpty { try container.encode(red.map { [$0.location, $0.length] }, forKey: .red) }
        try container.encodeIfPresent(template, forKey: .tp)
        try container.encodeIfPresent(font, forKey: .f)
        try container.encodeIfPresent(aspect, forKey: .a)
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let version = try container.decode(Int.self, forKey: .v)
        guard version == Self.version else { throw DecodingError.unsupportedVersion(version) }
        let text = try container.decode(String.self, forKey: .t)
        guard text.count <= Self.maxDecodedTextLength else { throw DecodingError.tooLong }
        // Drop malformed or out-of-bounds ranges rather than failing the whole link.
        let length = text.utf16.count
        let pairs = try container.decodeIfPresent([[Int]].self, forKey: .red) ?? []
        let red = pairs.compactMap { pair -> NSRange? in
            guard pair.count == 2, pair[0] >= 0, pair[1] > 0, pair[0] < length else { return nil }
            return NSRange(location: pair[0], length: min(pair[1], length - pair[0]))
        }
        self.init(reference: try container.decode(String.self, forKey: .ref),
                  keys: try container.decodeIfPresent(String.self, forKey: .k) ?? "",
                  translation: try container.decodeIfPresent(String.self, forKey: .tr) ?? "",
                  text: text, red: red,
                  template: try container.decodeIfPresent(String.self, forKey: .tp),
                  font: try container.decodeIfPresent(String.self, forKey: .f),
                  aspect: try container.decodeIfPresent(String.self, forKey: .a))
    }
}

/// A URL the app knows how to open.
public enum AppLink: Hashable, Sendable {
    /// A share link (web or custom scheme with a `#s=` fragment): show the passage and its card.
    case share(ShareLinkPayload)
    /// `scripturealone://open?ref=43003016-43003017`: go to the passage and select it.
    case open([VerseRange])

    public init?(url: URL) {
        let scheme = url.scheme?.lowercased()
        let isWeb = (scheme == "https" || scheme == "http")
            && [ShareLinkPayload.webHost, "www.\(ShareLinkPayload.webHost)"].contains(url.host()?.lowercased() ?? "")
            && url.path().hasPrefix(ShareLinkPayload.webPath)
        let isCustom = scheme == ShareLinkPayload.scheme
        guard isWeb || isCustom else { return nil }

        if let payload = try? ShareLinkPayload(url: url), !payload.ranges.isEmpty {
            self = .share(payload)
            return
        }
        guard isCustom,
              let ref = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == "ref" })?.value
        else { return nil }
        let ranges = ShareLinkPayload(reference: "", keys: ref, translation: "", text: "").ranges
        guard !ranges.isEmpty else { return nil }
        self = .open(ranges)
    }

    /// `scripturealone://open?ref=43003016-43003017`
    public static func openURL(for ranges: [VerseRange]) -> URL? {
        var components = URLComponents()
        components.scheme = ShareLinkPayload.scheme
        components.host = "open"
        components.queryItems = [URLQueryItem(name: "ref", value: ranges.map(\.storageString).joined(separator: ","))]
        return components.url
    }
}
