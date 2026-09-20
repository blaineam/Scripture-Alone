import Foundation

/// One chapter as the reader lays it out: headings, prose paragraphs and poetry lines,
/// each carrying verse fragments. Decoded from the compact JSON the build tool writes.
public struct ChapterLayout: Decodable, Sendable {
    public let blocks: [Block]

    public init(blocks: [Block]) { self.blocks = blocks }

    /// A layout for text that arrived without structure — an online translation's API returns
    /// prose, not the paragraph and poetry marks a USFM store carries. One paragraph, every verse
    /// numbered, which is how the reader renders prose anyway.
    public static func prose(_ verses: [VerseText]) -> ChapterLayout {
        ChapterLayout(blocks: [Block(kind: .paragraph, fragments: verses.map {
            Fragment(verse: $0.ref.verse, numbered: true, text: $0.text)
        })])
    }

    enum CodingKeys: String, CodingKey { case blocks = "b" }

    public struct Block: Decodable, Sendable {
        public enum Kind: String, Decodable, Sendable {
            case heading = "s1", subheading = "s2", majorSection = "ms", parallel = "r", acrostic = "qa"
            case paragraph = "p", continuation = "m", embedded = "pmo", centered = "pc"
            case list1 = "li1", list2 = "li2", poetry1 = "q1", poetry2 = "q2", selah = "qr"
            case title = "d", stanzaBreak = "b"
            case unknown

            public init(from decoder: Decoder) throws {
                self = Kind(rawValue: try decoder.singleValueContainer().decode(String.self)) ?? .unknown
            }

            public var isHeading: Bool { [.heading, .subheading, .majorSection, .parallel, .acrostic].contains(self) }
            public var isPoetry: Bool { [.poetry1, .poetry2, .selah].contains(self) }
        }

        public let kind: Kind
        public let text: String?
        public let fragments: [Fragment]

        public init(kind: Kind, text: String? = nil, fragments: [Fragment] = []) {
            self.kind = kind
            self.text = text
            self.fragments = fragments
        }

        enum CodingKeys: String, CodingKey { case kind = "k", text = "t", fragments = "f" }

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            kind = try c.decode(Kind.self, forKey: .kind)
            text = try c.decodeIfPresent(String.self, forKey: .text)
            fragments = try c.decodeIfPresent([Fragment].self, forKey: .fragments) ?? []
        }
    }

    public struct Fragment: Decodable, Sendable {
        public let verse: Int
        /// The verse number is printed at the start of this fragment.
        public let numbered: Bool
        public let text: String
        public let spans: [Span]
        public let footnotes: [Footnote]

        public init(verse: Int, numbered: Bool, text: String,
                    spans: [Span] = [], footnotes: [Footnote] = []) {
            self.verse = verse
            self.numbered = numbered
            self.text = text
            self.spans = spans
            self.footnotes = footnotes
        }

        enum CodingKeys: String, CodingKey { case verse = "v", numbered = "n", text = "t", spans = "s", footnotes = "fn" }

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            verse = try c.decode(Int.self, forKey: .verse)
            numbered = (try c.decodeIfPresent(Int.self, forKey: .numbered) ?? 0) == 1
            text = try c.decode(String.self, forKey: .text)
            spans = try c.decodeIfPresent([Span].self, forKey: .spans) ?? []
            footnotes = try c.decodeIfPresent([Footnote].self, forKey: .footnotes) ?? []
        }
    }

    public struct Span: Decodable, Sendable {
        public enum Style: String, Sendable { case wordsOfChrist = "r", supplied = "i", smallCaps = "c" }
        /// Offsets in Unicode scalars.
        public let start: Int
        public let length: Int
        public let style: Style?

        public init(from decoder: Decoder) throws {
            var c = try decoder.unkeyedContainer()
            start = try c.decode(Int.self)
            length = try c.decode(Int.self)
            style = Style(rawValue: try c.decode(String.self))
        }
    }

    public struct Footnote: Decodable, Sendable {
        /// Offset in Unicode scalars where the marker sits.
        public let position: Int
        public let text: String

        public init(from decoder: Decoder) throws {
            var c = try decoder.unkeyedContainer()
            position = try c.decode(Int.self)
            text = try c.decode(String.self)
        }
    }
}

extension String {
    /// Converts a Unicode-scalar offset (what the build tool records) into a UTF-16 offset
    /// (what NSAttributedString uses).
    public func utf16Offset(ofScalar scalarOffset: Int) -> Int {
        var utf16 = 0
        var index = 0
        for scalar in unicodeScalars {
            if index == scalarOffset { break }
            utf16 += scalar.utf16.count
            index += 1
        }
        return utf16
    }
}
