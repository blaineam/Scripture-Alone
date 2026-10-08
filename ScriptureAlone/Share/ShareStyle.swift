import SwiftUI

/// The eight card looks share links know by name (`tp`), so the web renders a close card (see
/// docs/share-links.md — keep the colors there in step with these). The app has more grounds than
/// the web; each maps to the nearest of these (`ShareBackground.linkTemplate`).
nonisolated enum ShareTemplate: String, CaseIterable, Identifiable, Sendable {
    case parchment, ink, dawn, night, linen, stone, olive, minimal

    var id: String { rawValue }

    var background: ShareBackground { ShareBackground(rawValue: rawValue) ?? .parchment }
}

/// How a ground is painted beneath its texture.
nonisolated enum ShareGradient: Equatable, Sendable {
    /// Top to bottom.
    case vertical
    /// Top-left to bottom-right.
    case diagonal
    /// From a point a little above the centre, outwards.
    case radial
}

/// What a ground is made of, drawn procedurally by `ShareBackdrop` (no image files, nothing licensed).
nonisolated enum ShareTexture: Equatable, Sendable {
    /// Flat or gradient, with only an invisible dither so gradients don't band.
    case clean
    /// Mottled paper with a few fibres.
    case paper
    /// A fine plain weave.
    case linen
    /// A coarser over-and-under weave.
    case canvas
    /// Film grain over a soft glow.
    case grain
    /// Three pigments washed toward the corners, with darker tide-mark edges.
    case watercolor(pigments: [UInt32])
    /// Out-of-focus discs of light.
    case bokeh(lights: [UInt32])
    /// Warm light leaking in from the edges.
    case glow
    /// A fine triangular lattice that fades out behind the text.
    case lattice(line: UInt32)
    /// Topographic contour lines that fade out behind the text.
    case contour(line: UInt32)
}

/// A card ground. Each has a curated text color, accent and red that are tested for contrast
/// against the ground as drawn (`ShareStyleTests`, and `ShareStyleTest.kt` on Android). Raw values
/// are stored in settings; the first eight templates' names are among them.
nonisolated enum ShareBackground: String, CaseIterable, Identifiable, Sendable {
    case parchment, watercolor, glow, linen, night, bokeh, dawn, lattice, mist, grain, contour, dusk,
         canvas, sage, tide, ink, stone, sand, blush, olive, minimal

    var id: String { rawValue }

    var title: String {
        switch self {
        case .parchment: String(localized: "Parchment", comment: "Name of a share-card design")
        case .watercolor: String(localized: "Watercolor", comment: "Name of a share-card design")
        case .glow: String(localized: "Golden Hour", comment: "Name of a share-card design: warm light leaking in at the edges")
        case .linen: String(localized: "Linen", comment: "Name of a share-card design")
        case .night: String(localized: "Night", comment: "Name of a share-card design")
        case .bokeh: String(localized: "Bokeh", comment: "Name of a share-card design: soft out-of-focus circles of light")
        case .dawn: String(localized: "Dawn", comment: "Name of a share-card design")
        case .lattice: String(localized: "Lattice", comment: "Name of a share-card design: a fine geometric line pattern")
        case .mist: String(localized: "Mist", comment: "Name of a share-card design: a soft pale blue-grey")
        case .grain: String(localized: "Film", comment: "Name of a share-card design: dark, with film grain")
        case .contour: String(localized: "Contour", comment: "Name of a share-card design: topographic map lines")
        case .dusk: String(localized: "Dusk", comment: "Name of a share-card design")
        case .canvas: String(localized: "Canvas", comment: "Name of a share-card design: woven painter's canvas")
        case .sage: String(localized: "Sage", comment: "Name of a share-card design: a soft grey-green")
        case .tide: String(localized: "Tide", comment: "Name of a share-card design: deep sea-green")
        case .ink: String(localized: "Ink", comment: "Name of a share-card design")
        case .stone: String(localized: "Stone", comment: "Name of a share-card design")
        case .sand: String(localized: "Sand", comment: "Name of a share-card design")
        case .blush: String(localized: "Blush", comment: "Name of a share-card design: a soft warm pink")
        case .olive: String(localized: "Olive", comment: "Name of a share-card design")
        case .minimal: String(localized: "Minimal", comment: "Name of a share-card design")
        }
    }

    /// Ground colors, in gradient order. One color = flat.
    var colors: [UInt32] {
        switch self {
        case .parchment: [0xF6EDD9, 0xEBDDBF]
        case .watercolor: [0xFBF8F3]
        case .glow: [0x2A1A14, 0x130D0A]
        case .linen: [0xF8F5EF]
        case .night: [0x0B1026, 0x1D2A57]
        case .bokeh: [0x141A36, 0x2A1B3F]
        case .dawn: [0xF7D9C4, 0xEFB4A8, 0xA893CC]
        case .lattice: [0xEFF3EC]
        case .mist: [0xE9EFF4, 0xDCE4EC]
        case .grain: [0x3A342E, 0x1A1815]
        case .contour: [0xEFF1F4, 0xE1E6EC]
        case .dusk: [0x2E335F, 0x8E4B69]
        case .canvas: [0xEAE4D8]
        case .sage: [0xDFE8D7, 0xD3DEC9]
        case .tide: [0x0E3A46, 0x2F6A5E]
        case .ink: [0x14161A]
        case .stone: [0xDEDCD7, 0xC3C0B9]
        case .sand: [0xF2EADC, 0xE7DAC5]
        case .blush: [0xF7E8E3, 0xEED7D0]
        case .olive: [0x46512F, 0x2D3520]
        case .minimal: [0xFFFFFF]
        }
    }

    var gradient: ShareGradient {
        switch self {
        case .bokeh, .dusk, .tide: .diagonal
        case .grain: .radial
        default: .vertical
        }
    }

    var texture: ShareTexture {
        switch self {
        case .parchment: .paper
        case .watercolor: .watercolor(pigments: [0x8FB3D9, 0xA9CBB7, 0xE8BFA8])
        case .glow: .glow
        case .linen: .linen
        case .bokeh: .bokeh(lights: [0xFFC67A, 0xFF8FB1, 0x8FD3FF])
        case .lattice: .lattice(line: 0x4F6B55)
        case .grain: .grain
        case .contour: .contour(line: 0x5D6B80)
        case .canvas: .canvas
        default: .clean
        }
    }

    var isTextured: Bool { texture != .clean }

    /// The curated text color: the automatic choice whenever the reader hasn't picked one.
    var ink: UInt32 {
        switch self {
        case .parchment: 0x3B2F20
        case .watercolor: 0x22334A
        case .glow: 0xFFF3E3
        case .linen: 0x2E2A25
        case .night: 0xE9EDF8
        case .bokeh: 0xFFFFFF
        case .dawn: 0x2E2236
        case .lattice: 0x1F3326
        case .mist: 0x1E2B3A
        case .grain: 0xF3EADB
        case .contour: 0x1E2633
        case .dusk: 0xFFF4EE
        case .canvas: 0x2A2620
        case .sage: 0x223321
        case .tide: 0xF1F7F2
        case .ink: 0xEDE8DF
        case .stone: 0x26262A
        case .sand: 0x3A2E22
        case .blush: 0x4A2630
        case .olive: 0xF2EFDD
        case .minimal: 0x111111
        }
    }

    /// Reference, rule, verse numbers and wordmark.
    var accent: UInt32 {
        switch self {
        case .parchment: 0x8A5A2B
        case .watercolor: 0x4C6A8A
        case .glow: 0xFFC98F
        case .linen: 0x8E6E44
        case .night: 0xA9B8F0
        case .bokeh: 0xFFE0B8
        case .dawn: 0x6B4A6E
        case .lattice: 0x4A6650
        case .mist: 0x55697F
        case .grain: 0xD8B98A
        case .contour: 0x55637A
        case .dusk: 0xF6C7B6
        case .canvas: 0x75634A
        case .sage: 0x52684D
        case .tide: 0xA8D8C6
        case .ink: 0xC9A45C
        case .stone: 0x5A5A62
        case .sand: 0x80603E
        case .blush: 0x8E5260
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
        case .glow, .grain: 0xFF9C85
        case .bokeh: 0xFFA99E
        case .dusk: 0xFFB0A0
        case .tide: 0xFFB4A2
        case .watercolor, .lattice, .canvas, .sage, .sand, .blush: 0xA3271F
        case .mist, .contour: 0xA8322A
        }
    }

    /// A hairline frame inset from the edge (the paper-like grounds).
    var hasFrame: Bool { self == .parchment || self == .linen || self == .canvas }

    /// Light text on this ground (judged from its colors, before texture).
    var isDark: Bool { ShareContrast.luminance(ShareContrast.average(colors)) < 0.3 }

    /// The text colors offered for this ground besides its own: five that suit it.
    var palette: [ShareInkSwatch] {
        let swatches = isDark ? ShareInkSwatch.forDark : ShareInkSwatch.forLight
        return Array(swatches.filter { $0.hex != ink }.prefix(5))
    }

    /// The share-link template the web draws for this ground — the ground itself, or the nearest.
    var linkTemplate: ShareTemplate {
        if let template = ShareTemplate(rawValue: rawValue) { return template }
        switch self {
        case .watercolor, .canvas: return .linen
        case .glow, .grain: return .ink
        case .bokeh, .dusk, .tide: return .night
        case .lattice, .contour, .mist, .sage: return .stone
        case .sand: return .parchment
        case .blush: return .dawn
        default: return .parchment
        }
    }

    /// The ready-made style that goes with this ground: its shadow, typeface and alignment.
    var presetShadow: ShareShadow {
        switch self {
        case .watercolor, .glow, .grain, .dusk, .tide: .soft
        case .bokeh: .strong
        default: .none
        }
    }

    var presetAlignment: ShareAlignment {
        switch self {
        case .mist, .grain, .contour, .tide, .minimal: .leading
        default: .center
        }
    }

    /// The `FontFamily` raw value of the ready-made style's typeface.
    var presetFamily: String {
        switch self {
        case .parchment, .night, .dusk, .olive: "newYork"
        case .watercolor, .glow, .sand: "palatino"
        case .linen, .sage: "iowan"
        case .bokeh, .dawn, .mist, .tide, .blush: "avenir"
        case .lattice, .minimal: "sanFrancisco"
        case .grain, .contour, .stone: "charter"
        case .canvas, .ink: "georgia"
        }
    }

    static let clean: [ShareBackground] = allCases.filter { !$0.isTextured }
    static let textured: [ShareBackground] = allCases.filter(\.isTextured)
}

extension ShareBackground {
    /// The flat look of the ground (a stand-in until its texture is drawn).
    var backgroundStyle: AnyShapeStyle {
        let colors = colors.map { Color(PlatformColor(hex: $0)) }
        if colors.count == 1 { return AnyShapeStyle(colors[0]) }
        switch gradient {
        case .vertical: return AnyShapeStyle(LinearGradient(colors: colors, startPoint: .top, endPoint: .bottom))
        case .diagonal: return AnyShapeStyle(LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing))
        case .radial: return AnyShapeStyle(RadialGradient(colors: colors, center: UnitPoint(x: 0.5, y: 0.42), startRadius: 0, endRadius: 700))
        }
    }
}

/// A named text color in the designer's palette.
nonisolated struct ShareInkSwatch: Identifiable, Equatable, Sendable {
    let hex: UInt32
    let name: String
    var id: UInt32 { hex }

    static var forLight: [ShareInkSwatch] {
        [ShareInkSwatch(hex: 0x111111, name: String(localized: "Black", comment: "Reader colour theme name")),
         ShareInkSwatch(hex: 0x1F2F4A, name: String(localized: "Navy", comment: "Text color for a verse image")),
         ShareInkSwatch(hex: 0x24402F, name: String(localized: "Forest", comment: "Text color for a verse image: deep green")),
         ShareInkSwatch(hex: 0x6B1E2A, name: String(localized: "Burgundy", comment: "Text color for a verse image")),
         ShareInkSwatch(hex: 0x4E3A26, name: String(localized: "Sepia", comment: "Reader colour theme name"))]
    }

    static var forDark: [ShareInkSwatch] {
        [ShareInkSwatch(hex: 0xFFFFFF, name: String(localized: "White", comment: "Text color for a verse image")),
         ShareInkSwatch(hex: 0xF5EBD7, name: String(localized: "Cream", comment: "Text color for a verse image")),
         ShareInkSwatch(hex: 0xE9C77F, name: String(localized: "Gold", comment: "Text color for a verse image")),
         ShareInkSwatch(hex: 0xCFE0F7, name: String(localized: "Sky", comment: "Text color for a verse image: pale blue")),
         ShareInkSwatch(hex: 0xF6CFD0, name: String(localized: "Rose", comment: "Text color for a verse image: pale pink"))]
    }
}

/// A shadow under the card's text — a dark shadow under light text, a light halo around dark text.
nonisolated enum ShareShadow: String, CaseIterable, Identifiable, Sendable {
    case none, soft, strong

    var id: String { rawValue }

    var title: String {
        switch self {
        case .none: String(localized: "None", comment: "Verse-image text shadow: off")
        case .soft: String(localized: "Soft", comment: "Verse-image text shadow: a gentle one")
        case .strong: String(localized: "Strong", comment: "Verse-image text shadow: a pronounced one")
        }
    }

    /// The layers drawn under text of `size` points: blur radius and downward offset in points, opacity.
    func layers(size: CGFloat, glow: Bool) -> [(radius: CGFloat, y: CGFloat, opacity: Double)] {
        switch self {
        case .none: []
        case .soft: [(size * 0.10, glow ? 0 : size * 0.025, glow ? 0.75 : 0.55)]
        case .strong: [(size * 0.035, glow ? 0 : size * 0.015, glow ? 0.9 : 0.75),
                       (size * 0.16, glow ? 0 : size * 0.04, glow ? 0.8 : 0.6)]
        }
    }
}

enum ShareAspect: String, CaseIterable, Identifiable {
    case square, story, wide

    var id: String { rawValue }

    var title: String {
        switch self {
        case .square: String(localized: "Square", comment: "Share-card shape")
        case .story: String(localized: "Story", comment: "Share-card shape")
        case .wide: String(localized: "Wide", comment: "Share-card shape")
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
    var title: String {
        self == .leading ? String(localized: "Left", comment: "Text alignment") : String(localized: "Centered", comment: "Text alignment")
    }
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
    var background: ShareBackground = .parchment
    /// The text color the reader picked, or nil for the ground's own (picked afresh when the ground changes).
    var ink: UInt32?
    var shadow: ShareShadow = .none
    var aspect: ShareAspect = .square
    var family: FontFamily = .newYork
    var alignment: ShareAlignment = .center
    var redLetters = true
    var verseNumbers = true
    var wordmark = true

    /// The share-link template (`tp`).
    var template: ShareTemplate { background.linkTemplate }

    /// A ready-made style: the ground with its own text color, shadow, typeface and alignment.
    /// Shape and what to show stay as they are.
    mutating func apply(_ look: ShareBackground) {
        background = look
        ink = nil
        shadow = look.presetShadow
        family = FontFamily(rawValue: look.presetFamily) ?? .newYork
        alignment = look.presetAlignment
    }

    /// The colors the card is drawn with, made legible against `stats` (see `ShareContrast.resolve`).
    func colors(on stats: ShareBackdropStats) -> ShareColors {
        ShareContrast.resolve(background: background, ink: ink, shadow: shadow, stats: stats)
    }
}

/// The colors and shadow a card is actually drawn with.
nonisolated struct ShareColors: Equatable, Sendable {
    var ink: UInt32
    var accent: UInt32
    var red: UInt32
    var shadow: ShareShadow
    /// Black under light text, white around dark text.
    var shadowColor: UInt32
    /// The text color or shadow was changed to keep the text readable.
    var adjusted: Bool

    var glow: Bool { shadowColor == 0xFFFFFF }
}

/// The ground as text sees it: its average, and its lightest and darkest patches (each the average
/// of a ninth by a ninth of the card), measured from the drawn backdrop (`ShareBackdrop.stats`).
nonisolated struct ShareBackdropStats: Equatable, Sendable {
    var mean: UInt32
    var lightest: UInt32
    var darkest: UInt32
}

/// WCAG contrast, and the rules that keep a card's text readable on its ground.
nonisolated enum ShareContrast {
    /// Body text: WCAG AA for normal-size text (the card's text is large, which needs only 3:1).
    static let target = 4.5
    /// The worst patch of a textured ground; the accent and red.
    static let minimum = 3.0

    static func luminance(_ hex: UInt32) -> Double {
        func channel(_ value: UInt32) -> Double {
            let c = Double(value & 0xFF) / 255
            return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(hex >> 16) + 0.7152 * channel(hex >> 8) + 0.0722 * channel(hex)
    }

    static func ratio(_ a: UInt32, _ b: UInt32) -> Double {
        let (la, lb) = (luminance(a), luminance(b))
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /// `a` moved `t` (0…1) of the way to `b`, per channel.
    static func mix(_ a: UInt32, _ b: UInt32, _ t: Double) -> UInt32 {
        func channel(_ shift: UInt32) -> UInt32 {
            let x = Double((a >> shift) & 0xFF), y = Double((b >> shift) & 0xFF)
            return UInt32(max(0, min(255, (x + (y - x) * t).rounded()))) << shift
        }
        return channel(16) | channel(8) | channel(0)
    }

    static func average(_ colors: [UInt32]) -> UInt32 {
        guard !colors.isEmpty else { return 0 }
        func channel(_ shift: UInt32) -> UInt32 {
            (colors.reduce(0) { $0 + (($1 >> shift) & 0xFF) } / UInt32(colors.count)) << shift
        }
        return channel(16) | channel(8) | channel(0)
    }

    /// Contrast against the average ground, and against its worst patch (or the average, if lower).
    static func check(_ ink: UInt32, _ stats: ShareBackdropStats) -> (mean: Double, worst: Double) {
        let mean = ratio(ink, stats.mean)
        return (mean, min(mean, ratio(ink, stats.lightest), ratio(ink, stats.darkest)))
    }

    /// The candidate with the most contrast on the ground's worst patch.
    static func bestInk(on stats: ShareBackdropStats, from candidates: [UInt32]) -> UInt32 {
        candidates.max { check($0, stats).worst < check($1, stats).worst } ?? 0x111111
    }

    /// `color` nudged toward black or white (whichever the ground contrasts more) until it reaches
    /// `target` against the average ground, keeping as much of its hue as it can.
    static func nudge(_ color: UInt32, toward target: Double, on stats: ShareBackdropStats) -> UInt32 {
        guard ratio(color, stats.mean) < target else { return color }
        let pole: UInt32 = ratio(0xFFFFFF, stats.mean) >= ratio(0x000000, stats.mean) ? 0xFFFFFF : 0x000000
        for step in 1...20 {
            let candidate = mix(color, pole, Double(step) * 0.05)
            if ratio(candidate, stats.mean) >= target { return candidate }
        }
        return pole
    }

    /// The colors a card is drawn with.
    ///
    /// - With no picked color, the ground's curated ones (tested to pass on their own).
    /// - A picked color that reaches 4.5:1 on the average ground is kept as it is; one under that is
    ///   nudged toward black or white until it does.
    /// - If a patch of a textured ground is still under 3:1, a soft shadow is added.
    /// - Accent and red are nudged to 3:1.
    static func resolve(background: ShareBackground, ink picked: UInt32?, shadow chosen: ShareShadow,
                        stats: ShareBackdropStats) -> ShareColors {
        var ink = picked ?? background.ink
        var shadow = chosen
        var adjusted = false
        if check(ink, stats).mean < target {
            ink = nudge(ink, toward: target, on: stats)
            adjusted = true
        }
        if check(ink, stats).worst < minimum, shadow == .none {
            shadow = .soft
            adjusted = true
        }
        // The reference and verse numbers follow a picked color, a little toward the ground.
        let accent = nudge(picked.map { mix($0, stats.mean, 0.22) } ?? background.accent, toward: minimum, on: stats)
        let red = nudge(background.red, toward: minimum, on: stats)
        let shadowColor: UInt32 = luminance(ink) > 0.4 ? 0x000000 : 0xFFFFFF
        return ShareColors(ink: ink, accent: accent, red: red, shadow: shadow, shadowColor: shadowColor, adjusted: adjusted)
    }
}

/// Remembered designer choices (per device, synced by `SettingsSync`).
enum ShareSettingsKey {
    /// The ground (`ShareBackground`). Before 1.1.4 this was one of the eight templates, which are
    /// still grounds of the same names.
    static let template = "share.template"
    /// The picked text color as "RRGGBB", or empty for the ground's own.
    static let ink = "share.ink"
    static let shadow = "share.shadow"
    static let aspect = "share.aspect"
    static let family = "share.fontFamily"
    static let alignment = "share.alignment"
    static let redLetters = "share.redLetters"
    static let verseNumbers = "share.verseNumbers"
    static let wordmark = "share.wordmark"
}

extension ShareStyle {
    /// The last design, as remembered in `defaults`.
    init(defaults: UserDefaults) {
        self.init()
        background = defaults.string(forKey: ShareSettingsKey.template).flatMap(ShareBackground.init(rawValue:)) ?? .parchment
        ink = defaults.string(forKey: ShareSettingsKey.ink).flatMap(Self.hex(from:))
        shadow = defaults.string(forKey: ShareSettingsKey.shadow).flatMap(ShareShadow.init(rawValue:)) ?? .none
        aspect = defaults.string(forKey: ShareSettingsKey.aspect).flatMap(ShareAspect.init(rawValue:)) ?? .square
        family = defaults.string(forKey: ShareSettingsKey.family).flatMap(FontFamily.init(rawValue:)) ?? .newYork
        alignment = defaults.string(forKey: ShareSettingsKey.alignment).flatMap(ShareAlignment.init(rawValue:)) ?? .center
        redLetters = defaults.object(forKey: ShareSettingsKey.redLetters) as? Bool ?? true
        verseNumbers = defaults.object(forKey: ShareSettingsKey.verseNumbers) as? Bool ?? true
        wordmark = defaults.object(forKey: ShareSettingsKey.wordmark) as? Bool ?? true
    }

    /// Remembers this design in `defaults` (the designer does after every change).
    func save(to defaults: UserDefaults) {
        defaults.set(background.rawValue, forKey: ShareSettingsKey.template)
        defaults.set(ink.map(Self.hexString) ?? "", forKey: ShareSettingsKey.ink)
        defaults.set(shadow.rawValue, forKey: ShareSettingsKey.shadow)
        defaults.set(aspect.rawValue, forKey: ShareSettingsKey.aspect)
        defaults.set(family.rawValue, forKey: ShareSettingsKey.family)
        defaults.set(alignment.rawValue, forKey: ShareSettingsKey.alignment)
        defaults.set(redLetters, forKey: ShareSettingsKey.redLetters)
        defaults.set(verseNumbers, forKey: ShareSettingsKey.verseNumbers)
        defaults.set(wordmark, forKey: ShareSettingsKey.wordmark)
    }

    static func hexString(_ hex: UInt32) -> String { String(format: "%06X", hex & 0xFFFFFF) }

    static func hex(from string: String) -> UInt32? {
        guard string.count == 6, let value = UInt32(string, radix: 16) else { return nil }
        return value
    }
}
