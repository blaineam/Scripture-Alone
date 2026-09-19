import SwiftUI

/// A verse-card look. Raw values are the `tp` names in share links, so the web renders the same
/// card (see docs/share-links.md — keep the colors there in step with these).
enum ShareTemplate: String, CaseIterable, Identifiable {
    case parchment, ink, dawn, night, linen, stone, olive, minimal

    var id: String { rawValue }

    var title: String {
        switch self {
        case .parchment: "Parchment"
        case .ink: "Ink"
        case .dawn: "Dawn"
        case .night: "Night"
        case .linen: "Linen"
        case .stone: "Stone"
        case .olive: "Olive"
        case .minimal: "Minimal"
        }
    }

    /// Background, top to bottom. One color = flat.
    var background: [UInt32] {
        switch self {
        case .parchment: [0xF6EDD9, 0xEBDDBF]
        case .ink: [0x14161A]
        case .dawn: [0xF7D9C4, 0xEFB4A8, 0xA893CC]
        case .night: [0x0B1026, 0x1D2A57]
        case .linen: [0xF8F5EF]
        case .stone: [0xDEDCD7, 0xC3C0B9]
        case .olive: [0x46512F, 0x2D3520]
        case .minimal: [0xFFFFFF]
        }
    }

    var ink: UInt32 {
        switch self {
        case .parchment: 0x3B2F20
        case .ink: 0xEDE8DF
        case .dawn: 0x2E2236
        case .night: 0xE9EDF8
        case .linen: 0x2E2A25
        case .stone: 0x26262A
        case .olive: 0xF2EFDD
        case .minimal: 0x111111
        }
    }

    /// Reference, rule, verse numbers and wordmark.
    var accent: UInt32 {
        switch self {
        case .parchment: 0x8A5A2B
        case .ink: 0xC9A45C
        case .dawn: 0x6B4A6E
        case .night: 0xA9B8F0
        case .linen: 0x9C7A4E
        case .stone: 0x5A5A62
        case .olive: 0xD6C58C
        case .minimal: 0x6E6E6E
        }
    }

    /// Words of Christ.
    var red: UInt32 {
        switch self {
        case .parchment: 0xA12A1C
        case .ink: 0xFF7A6B
        case .dawn: 0x9E1B32
        case .night: 0xFF8A80
        case .linen: 0xB0261B
        case .stone: 0x9B2226
        case .olive: 0xFFA48A
        case .minimal: 0xC0392B
        }
    }

    /// A hairline frame inset from the edge (paper-like templates).
    var hasFrame: Bool { self == .parchment || self == .linen }

    var backgroundStyle: AnyShapeStyle {
        let colors = background.map { Color(PlatformColor(hex: $0)) }
        return colors.count == 1
            ? AnyShapeStyle(colors[0])
            : AnyShapeStyle(LinearGradient(colors: colors, startPoint: .top, endPoint: .bottom))
    }
}

enum ShareAspect: String, CaseIterable, Identifiable {
    case square, story, wide

    var id: String { rawValue }

    var title: String {
        switch self {
        case .square: "Square"
        case .story: "Story"
        case .wide: "Wide"
        }
    }

    /// Layout size in points; the export renders at 2× (2160 px on the long side).
    var size: CGSize {
        switch self {
        case .square: CGSize(width: 1080, height: 1080)
        case .story: CGSize(width: 608, height: 1080)
        case .wide: CGSize(width: 1080, height: 608)
        }
    }
}

enum ShareAlignment: String, CaseIterable, Identifiable {
    case leading, center

    var id: String { rawValue }
    var title: String { self == .leading ? "Left" : "Centered" }
    var textAlignment: TextAlignment { self == .leading ? .leading : .center }
    var horizontal: HorizontalAlignment { self == .leading ? .leading : .center }
    var frameAlignment: Alignment { self == .leading ? .leading : .center }
}

extension FontFamily {
    /// The `f` token in share links; the web maps it to a font stack.
    var shareToken: String {
        switch self {
        case .newYork: "serif"
        case .sanFrancisco: "sans"
        case .charter: "charter"
        case .iowan: "iowan"
        case .georgia: "georgia"
        case .palatino: "palatino"
        case .avenir: "avenir"
        }
    }

    init?(shareToken: String) {
        guard let family = FontFamily.allCases.first(where: { $0.shareToken == shareToken }) else { return nil }
        self = family
    }
}

/// Everything the designer lets you change, as one value.
struct ShareStyle: Equatable {
    var template: ShareTemplate = .parchment
    var aspect: ShareAspect = .square
    var family: FontFamily = .newYork
    var alignment: ShareAlignment = .center
    var redLetters = true
    var verseNumbers = true
    var wordmark = true
}

/// Remembered designer choices (per device).
enum ShareSettingsKey {
    static let template = "share.template"
    static let aspect = "share.aspect"
    static let family = "share.fontFamily"
    static let alignment = "share.alignment"
    static let redLetters = "share.redLetters"
    static let verseNumbers = "share.verseNumbers"
    static let wordmark = "share.wordmark"
}
