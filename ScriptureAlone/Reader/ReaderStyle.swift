import SwiftUI
#if os(iOS)
import UIKit
typealias PlatformFont = UIFont
typealias PlatformColor = UIColor
typealias PlatformImage = UIImage
#else
import AppKit
typealias PlatformFont = NSFont
typealias PlatformColor = NSColor
typealias PlatformImage = NSImage
#endif

/// UserDefaults keys for reading preferences (per device, deliberately not synced — a
/// phone and a Mac want different sizes).
enum SettingsKey {
    static let theme = "reader.theme"
    static let fontFamily = "reader.fontFamily"
    static let fontSize = "reader.fontSize"
    static let lineSpacing = "reader.lineSpacing"
    static let layout = "reader.layout"
    static let redLetters = "reader.redLetters"
    static let verseNumbers = "reader.verseNumbers"
    static let headings = "reader.headings"
    static let footnotes = "reader.footnotes"
    static let autoScrollSpeed = "reader.autoScrollSpeed"
}

enum ReaderTheme: String, CaseIterable, Identifiable {
    case system, light, sepia, dark, black

    var id: String { rawValue }

    var title: String {
        switch self {
        case .system: "Auto"
        case .light: "Light"
        case .sepia: "Sepia"
        case .dark: "Dark"
        case .black: "Black"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light, .sepia: .light
        case .dark, .black: .dark
        }
    }

    /// Page and ink colors. `system` follows the current appearance.
    func palette(for scheme: ColorScheme) -> ReaderPalette {
        switch self {
        case .sepia:
            ReaderPalette(page: PlatformColor(hex: 0xF5EDDC), ink: PlatformColor(hex: 0x3A2E1F),
                          secondary: PlatformColor(hex: 0x86725A), red: PlatformColor(hex: 0xA12A1C),
                          accent: PlatformColor(hex: 0x8A5A2B), isDark: false)
        case .black:
            ReaderPalette(page: PlatformColor(hex: 0x000000), ink: PlatformColor(hex: 0xD9D6D0),
                          secondary: PlatformColor(hex: 0x8C8983), red: PlatformColor(hex: 0xFF7A6B),
                          accent: PlatformColor(hex: 0xE0B872), isDark: true)
        case .dark:
            ReaderPalette.dark
        case .light:
            ReaderPalette.light
        case .system:
            scheme == .dark ? .dark : .light
        }
    }
}

struct ReaderPalette: Equatable {
    let page: PlatformColor
    let ink: PlatformColor
    let secondary: PlatformColor
    let red: PlatformColor
    let accent: PlatformColor
    let isDark: Bool

    static let light = ReaderPalette(page: PlatformColor(hex: 0xFDFCFA), ink: PlatformColor(hex: 0x1D1B18),
                                     secondary: PlatformColor(hex: 0x7A756D), red: PlatformColor(hex: 0xB0261B),
                                     accent: PlatformColor(hex: 0x9A6B2F), isDark: false)
    static let dark = ReaderPalette(page: PlatformColor(hex: 0x17181A), ink: PlatformColor(hex: 0xE6E3DD),
                                    secondary: PlatformColor(hex: 0x9A968F), red: PlatformColor(hex: 0xFF6F61),
                                    accent: PlatformColor(hex: 0xE0B872), isDark: true)
}

enum FontFamily: String, CaseIterable, Identifiable {
    case newYork, sanFrancisco, charter, iowan, georgia, palatino, avenir

    var id: String { rawValue }

    var title: String {
        switch self {
        case .newYork: "New York"
        case .sanFrancisco: "San Francisco"
        case .charter: "Charter"
        case .iowan: "Iowan Old Style"
        case .georgia: "Georgia"
        case .palatino: "Palatino"
        case .avenir: "Avenir Next"
        }
    }

    private var postScriptName: String? {
        switch self {
        case .newYork, .sanFrancisco: nil
        case .charter: "Charter-Roman"
        case .iowan: "IowanOldStyle-Roman"
        case .georgia: "Georgia"
        case .palatino: "Palatino-Roman"
        case .avenir: "AvenirNext-Regular"
        }
    }

    func font(size: CGFloat) -> PlatformFont {
        let system = PlatformFont.systemFont(ofSize: size)
        switch self {
        case .newYork:
            #if os(iOS)
            return system.fontDescriptor.withDesign(.serif).map { UIFont(descriptor: $0, size: size) } ?? system
            #else
            return system.fontDescriptor.withDesign(.serif).flatMap { NSFont(descriptor: $0, size: size) } ?? system
            #endif
        case .sanFrancisco:
            return system
        default:
            return postScriptName.flatMap { PlatformFont(name: $0, size: size) } ?? system
        }
    }

    /// SwiftUI font for previews in the appearance picker.
    func swiftUIFont(size: CGFloat) -> Font {
        switch self {
        case .newYork: .system(size: size, design: .serif)
        case .sanFrancisco: .system(size: size)
        default: .custom(postScriptName ?? "", fixedSize: size)
        }
    }
}

enum ReadingLayout: String, CaseIterable, Identifiable {
    case paragraphs, verses
    var id: String { rawValue }
    var title: String { self == .paragraphs ? "Paragraphs" : "Verse by Verse" }
}

/// Everything the renderer needs to know about presentation, as one comparable value.
struct ReaderStyle: Equatable {
    var family: FontFamily
    var size: CGFloat
    var lineSpacing: CGFloat
    var layout: ReadingLayout
    var redLetters: Bool
    var verseNumbers: Bool
    var headings: Bool
    var footnotes: Bool
    var palette: ReaderPalette
    /// Identifies the palette for render caching ("sepia-light").
    var paletteID: String
}

enum HighlightColor: String, CaseIterable, Identifiable {
    case yellow, green, blue, pink, purple

    var id: String { rawValue }

    var swatch: Color { Color(platformColor(isDark: false, alpha: 1)) }

    func platformColor(isDark: Bool, alpha: CGFloat? = nil) -> PlatformColor {
        let hex: UInt32 = switch self {
        case .yellow: 0xF7D154
        case .green: 0x8CD48A
        case .blue: 0x7FB8F0
        case .pink: 0xF29BB8
        case .purple: 0xB9A2EC
        }
        return PlatformColor(hex: hex).withAlphaComponent(alpha ?? (isDark ? 0.34 : 0.42))
    }
}

extension PlatformColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}

extension PlatformFont {
    func withTraits(italic: Bool = false, bold: Bool = false) -> PlatformFont {
        #if os(iOS)
        var traits = fontDescriptor.symbolicTraits
        if italic { traits.insert(.traitItalic) }
        if bold { traits.insert(.traitBold) }
        return fontDescriptor.withSymbolicTraits(traits).map { UIFont(descriptor: $0, size: pointSize) } ?? self
        #else
        var traits = fontDescriptor.symbolicTraits
        if italic { traits.insert(.italic) }
        if bold { traits.insert(.bold) }
        return NSFont(descriptor: fontDescriptor.withSymbolicTraits(traits), size: pointSize) ?? self
        #endif
    }

    /// Lowercase letters drawn as small capitals ("Lord" → Lᴏʀᴅ).
    var smallCaps: PlatformFont {
        #if os(iOS)
        let settings: [[UIFontDescriptor.FeatureKey: Int]] = [[.type: kLowerCaseType, .selector: kLowerCaseSmallCapsSelector]]
        return UIFont(descriptor: fontDescriptor.addingAttributes([.featureSettings: settings]), size: pointSize)
        #else
        let settings: [[NSFontDescriptor.FeatureKey: Int]] = [[.typeIdentifier: kLowerCaseType, .selectorIdentifier: kLowerCaseSmallCapsSelector]]
        return NSFont(descriptor: fontDescriptor.addingAttributes([.featureSettings: settings]), size: pointSize) ?? self
        #endif
    }
}
