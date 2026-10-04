// The guide is an iPhone, iPad and Mac (and Android) feature; ZipReader isn't built for the watch.
#if !os(watchOS)
import CryptoKit
import Foundation

/// The User Guide as the apps draw it: the structured form of `docs/manual/content/<locale>.html`,
/// written by `Tools/manual_json.py` (whose docstring is the schema) and downloaded on demand as
/// `UserGuide-<code>.zip` — `guide.json` plus `images/` — from the `user-guide` GitHub release.
public struct UserGuide: Codable, Sendable, Equatable {
    public var schema: Int
    public var language: String
    public var title: String
    public var cover: Cover
    public var contents: String
    public var chapters: [Chapter]

    /// The newest schema this build can draw. A package with a higher number is ignored, so an
    /// old app never shows half a guide.
    public static let supportedSchema = 1

    public struct Cover: Codable, Sendable, Equatable {
        public var eyebrow: String
        public var title: String
        public var subtitle: String
        public var edition: String
        public var images: [String]
    }

    public struct Chapter: Codable, Sendable, Equatable, Identifiable {
        public var title: String
        public var summary: String
        public var lede: [Run]
        public var blocks: [Block]
        public var id: String { title }
    }

    /// A stretch of text with one set of marks.
    public struct Run: Codable, Sendable, Equatable {
        public var text: String
        public var bold: Bool
        /// The name of a button, menu or setting in the app.
        public var ui: Bool
        public var kbd: Bool
        public var code: Bool
        public var small: Bool
        public var link: String?
        public var lineBreak: Bool

        enum CodingKeys: String, CodingKey { case text, bold, ui, kbd, code, small, link, br }

        public init(text: String, bold: Bool = false, ui: Bool = false, kbd: Bool = false, code: Bool = false,
                    small: Bool = false, link: String? = nil, lineBreak: Bool = false) {
            self.text = text; self.bold = bold; self.ui = ui; self.kbd = kbd; self.code = code
            self.small = small; self.link = link; self.lineBreak = lineBreak
        }

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            text = try c.decode(String.self, forKey: .text)
            bold = try c.decodeIfPresent(Bool.self, forKey: .bold) ?? false
            ui = try c.decodeIfPresent(Bool.self, forKey: .ui) ?? false
            kbd = try c.decodeIfPresent(Bool.self, forKey: .kbd) ?? false
            code = try c.decodeIfPresent(Bool.self, forKey: .code) ?? false
            small = try c.decodeIfPresent(Bool.self, forKey: .small) ?? false
            link = try c.decodeIfPresent(String.self, forKey: .link)
            lineBreak = try c.decodeIfPresent(Bool.self, forKey: .br) ?? false
        }

        public func encode(to encoder: Encoder) throws {
            var c = encoder.container(keyedBy: CodingKeys.self)
            try c.encode(text, forKey: .text)
            if bold { try c.encode(true, forKey: .bold) }
            if ui { try c.encode(true, forKey: .ui) }
            if kbd { try c.encode(true, forKey: .kbd) }
            if code { try c.encode(true, forKey: .code) }
            if small { try c.encode(true, forKey: .small) }
            try c.encodeIfPresent(link, forKey: .link)
            if lineBreak { try c.encode(true, forKey: .br) }
        }
    }

    public struct Figure: Codable, Sendable, Equatable {
        public enum Device: String, Codable, Sendable { case phone, watch }
        public var image: String
        public var device: Device
        public var caption: String
    }

    /// A drawn app screen (a grouped list), used where a setup step needs a picture in any language.
    public struct Mock: Codable, Sendable, Equatable {
        public struct Bar: Codable, Sendable, Equatable {
            public var leading: String
            public var title: String
            public var trailing: String
        }
        public struct Section: Codable, Sendable, Equatable {
            public var header: String?
            public var rows: [Row]
            public var footer: String?
        }
        public struct Row: Codable, Sendable, Equatable {
            public enum Style: String, Codable, Sendable { case plain, link, field, destructive }
            public var text: String
            public var style: Style
            public var detail: String?
            public var icon: String?
            public var checked: Bool?
            public var highlight: Bool?
        }
        public var bar: Bar
        public var sections: [Section]
    }

    public enum CalloutStyle: String, Codable, Sendable { case tip, note, warn }

    public indirect enum Block: Codable, Sendable, Equatable {
        case paragraph([Run], fine: Bool)
        case heading([Run])
        case list([[Run]])
        case steps([[Block]])
        case table(header: [[Run]], rows: [[[Run]]])
        case callout(style: CalloutStyle, label: String, blocks: [Block])
        case figure(Figure)
        case feature(figure: Figure?, mock: Mock?, blocks: [Block], flip: Bool)
        case mock(Mock)
        /// A kind this build doesn't know, from a newer guide of the same schema: skipped.
        case unknown

        enum CodingKeys: String, CodingKey {
            case type, inline, fine, items, header, rows, style, label, blocks, image, device, caption
            case figure, mock, flip, bar, sections
        }

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            switch try c.decode(String.self, forKey: .type) {
            case "paragraph":
                self = .paragraph(try c.decode([Run].self, forKey: .inline),
                                  fine: try c.decodeIfPresent(Bool.self, forKey: .fine) ?? false)
            case "heading":
                self = .heading(try c.decode([Run].self, forKey: .inline))
            case "list":
                self = .list(try c.decode([[Run]].self, forKey: .items))
            case "steps":
                self = .steps(try c.decode([[Block]].self, forKey: .items))
            case "table":
                self = .table(header: try c.decodeIfPresent([[Run]].self, forKey: .header) ?? [],
                              rows: try c.decode([[[Run]]].self, forKey: .rows))
            case "callout":
                self = .callout(style: try c.decode(CalloutStyle.self, forKey: .style),
                                label: try c.decodeIfPresent(String.self, forKey: .label) ?? "",
                                blocks: try c.decode([Block].self, forKey: .blocks))
            case "figure":
                self = .figure(try Figure(from: decoder))
            case "feature":
                self = .feature(figure: try c.decodeIfPresent(Figure.self, forKey: .figure),
                                mock: try c.decodeIfPresent(Mock.self, forKey: .mock),
                                blocks: try c.decode([Block].self, forKey: .blocks),
                                flip: try c.decodeIfPresent(Bool.self, forKey: .flip) ?? false)
            case "mock":
                self = .mock(try Mock(from: decoder))
            default:
                self = .unknown
            }
        }

        public func encode(to encoder: Encoder) throws {
            var c = encoder.container(keyedBy: CodingKeys.self)
            switch self {
            case .paragraph(let runs, let fine):
                try c.encode("paragraph", forKey: .type); try c.encode(runs, forKey: .inline)
                if fine { try c.encode(true, forKey: .fine) }
            case .heading(let runs):
                try c.encode("heading", forKey: .type); try c.encode(runs, forKey: .inline)
            case .list(let items):
                try c.encode("list", forKey: .type); try c.encode(items, forKey: .items)
            case .steps(let items):
                try c.encode("steps", forKey: .type); try c.encode(items, forKey: .items)
            case .table(let header, let rows):
                try c.encode("table", forKey: .type); try c.encode(header, forKey: .header)
                try c.encode(rows, forKey: .rows)
            case .callout(let style, let label, let blocks):
                try c.encode("callout", forKey: .type); try c.encode(style, forKey: .style)
                try c.encode(label, forKey: .label); try c.encode(blocks, forKey: .blocks)
            case .figure(let figure):
                try c.encode("figure", forKey: .type); try c.encode(figure.image, forKey: .image)
                try c.encode(figure.device, forKey: .device); try c.encode(figure.caption, forKey: .caption)
            case .feature(let figure, let mock, let blocks, let flip):
                try c.encode("feature", forKey: .type); try c.encodeIfPresent(figure, forKey: .figure)
                try c.encodeIfPresent(mock, forKey: .mock); try c.encode(blocks, forKey: .blocks)
                if flip { try c.encode(true, forKey: .flip) }
            case .mock(let mock):
                try c.encode("mock", forKey: .type); try c.encode(mock.bar, forKey: .bar)
                try c.encode(mock.sections, forKey: .sections)
            case .unknown:
                try c.encode("unknown", forKey: .type)
            }
        }
    }
}

/// Where the packages live, and how one is checked and unpacked.
public enum UserGuidePackage {
    /// The release the CI workflow (`.github/workflows/user-guide.yml`) publishes to.
    public static let releaseBase = URL(string: "https://github.com/blaineam/Scripture-Alone/releases/download/user-guide/")!
    public static let indexURL = releaseBase.appendingPathComponent("UserGuide-index.json")

    /// The guide languages, as the packages are named.
    public static let languages = ["en", "zh-Hans", "ja", "de", "fr", "es", "ko", "pt-BR", "it"]

    /// The package to fetch for a set of preferred languages (the app's localizations, best
    /// first): the first one with a guide, English failing that.
    public static func language(for preferred: [String]) -> String {
        for raw in preferred {
            let l = raw.replacingOccurrences(of: "_", with: "-")
            if l.hasPrefix("zh") { return "zh-Hans" }
            if l.hasPrefix("pt") { return "pt-BR" }
            let base = String(l.prefix(while: { $0 != "-" }))
            if languages.contains(base) { return base }
        }
        return "en"
    }

    /// The device a guide edition is written for: each speaks of the reader's own device.
    public enum Device: String, Sendable, CaseIterable { case iphone, ipad, mac }

    /// The package name for a language on a device: `en` for iPhone (the name every Apple device
    /// read before editions existed), `ipad-en`, `mac-en`. Android's are `android-en`.
    public static func edition(language: String, device: Device) -> String {
        device == .iphone ? language : "\(device.rawValue)-\(language)"
    }

    /// The package to read: the device's own edition when the index lists it, else the plain
    /// language (the iPhone edition), so a newer app never waits on a package CI hasn't built yet.
    public static func entryName(edition: String, language: String, index: Index) -> String {
        index.packages[edition] != nil ? edition : language
    }

    /// `language` is a package name: a language code or an edition (`ipad-en`).
    public static func packageURL(language: String) -> URL {
        releaseBase.appendingPathComponent("UserGuide-\(language).zip")
    }

    public static func pdfURL(language: String) -> URL {
        releaseBase.appendingPathComponent("UserGuide-\(language).pdf")
    }

    /// `UserGuide-index.json`: each package's SHA-256, to tell whether a held copy is current.
    public struct Index: Codable, Sendable {
        public struct Entry: Codable, Sendable { public var sha256: String; public var size: Int }
        public var schema: Int
        public var packages: [String: Entry]
    }

    public enum PackageError: Error, Equatable {
        case noGuide
        case unsupportedSchema(Int)
        case unsafeName(String)
    }

    /// Unpacks a downloaded package into `directory` (replacing what was there) and returns the
    /// guide it holds. Only `guide.json` and flat `images/<name>` entries are written; any other
    /// name — a path climbing out with `..`, say — fails the whole package.
    @discardableResult
    public static func unpack(_ data: Data, into directory: URL) throws -> UserGuide {
        let zip = try ZipReader(data: data)
        guard zip.contains("guide.json") else { throw PackageError.noGuide }
        let guide = try JSONDecoder().decode(UserGuide.self, from: try zip.data(for: "guide.json"))
        guard guide.schema <= UserGuide.supportedSchema else { throw PackageError.unsupportedSchema(guide.schema) }

        let fm = FileManager.default
        let staging = directory.deletingLastPathComponent()
            .appendingPathComponent(".\(directory.lastPathComponent)-\(UUID().uuidString)")
        try fm.createDirectory(at: staging.appendingPathComponent("images"), withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: staging) }
        for entry in zip.entries where !entry.name.hasSuffix("/") {
            let name = entry.name
            let safeImage = name.hasPrefix("images/") && !name.dropFirst(7).contains("/")
                && !name.contains("..") && name.count > 7
            guard name == "guide.json" || safeImage else { throw PackageError.unsafeName(name) }
            try zip.data(for: entry).write(to: staging.appendingPathComponent(name), options: .atomic)
        }
        if fm.fileExists(atPath: directory.path) { try fm.removeItem(at: directory) }
        try fm.createDirectory(at: directory.deletingLastPathComponent(), withIntermediateDirectories: true)
        try fm.moveItem(at: staging, to: directory)
        return guide
    }

    /// A package's SHA-256 as the index writes it: lowercase hex.
    public static func sha256(of data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// Whether downloaded bytes are the package the index describes.
    public static func verify(_ data: Data, sha256 expected: String) -> Bool {
        !expected.isEmpty && sha256(of: data) == expected.lowercased()
    }

    /// Whether the index lists a different package for `language` than the copy held (whose
    /// SHA-256 is `held`, nil when there is none). A language the index doesn't list is left alone.
    /// `language` is the package name read (see `entryName`).
    public static func needsUpdate(held: String?, index: Index, language: String) -> Bool {
        guard index.schema <= UserGuide.supportedSchema, let entry = index.packages[language] else { return false }
        return held?.lowercased() != entry.sha256.lowercased()
    }

    /// The guide unpacked in `directory`, if there is a readable one.
    public static func load(from directory: URL) -> UserGuide? {
        guard let data = try? Data(contentsOf: directory.appendingPathComponent("guide.json")),
              let guide = try? JSONDecoder().decode(UserGuide.self, from: data),
              guide.schema <= UserGuide.supportedSchema else { return nil }
        return guide
    }
}
/// The one-time invitation to read the guide, offered once per install (existing users once
/// after updating) and never over a launch that came in for something else.
public enum UserGuidePrompt {
    /// The `UserDefaults` flag set once the invitation has been shown.
    public static let shownKey = "userGuidePromptShown"

    /// Whether to offer the guide now.
    /// - Parameters:
    ///   - alreadyShown: the flag at `shownKey`.
    ///   - openedForSomethingElse: the app was opened by a link, intent, Spotlight result or keepsake.
    ///   - automated: a test run or a staged screenshot scene.
    public static func shouldOffer(alreadyShown: Bool, openedForSomethingElse: Bool, automated: Bool) -> Bool {
        !alreadyShown && !openedForSomethingElse && !automated
    }

    /// True while XCTest or Swift Testing is driving the process.
    public static var isTestRun: Bool {
        let environment = ProcessInfo.processInfo.environment
        return environment["XCTestConfigurationFilePath"] != nil || environment["XCTestBundlePath"] != nil
            || environment["XCTestSessionIdentifier"] != nil || NSClassFromString("XCTestCase") != nil
    }
}
#endif
