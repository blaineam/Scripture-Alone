import SwiftUI
import ScriptureAloneCore

/// Colors and text helpers shared by the widgets, the watch complications and the watch app.
/// Plain SwiftUI — no WidgetKit — so the watch app can compile it too.
nonisolated enum VerseStyling {
    struct Palette {
        let pageTop: Color
        let pageBottom: Color
        let ink: Color
        let secondaryInk: Color
        let accent: Color
        let wordsOfChrist: Color
    }

    /// A warm page, sunrise to parchment, matching the reader's Sepia theme and the app icon.
    static func palette(_ scheme: ColorScheme) -> Palette {
        switch scheme {
        case .dark:
            Palette(pageTop: Color(hex: 0x2E2219), pageBottom: Color(hex: 0x1B1410),
                    ink: Color(hex: 0xF3E9DC), secondaryInk: Color(hex: 0xC9B9A6),
                    accent: Color(hex: 0xE0B872), wordsOfChrist: Color(hex: 0xFF8A7A))
        default:
            Palette(pageTop: Color(hex: 0xFFF3DF), pageBottom: Color(hex: 0xF7DDBC),
                    ink: Color(hex: 0x33261A), secondaryInk: Color(hex: 0x6E5A45),
                    accent: Color(hex: 0x9A6B2F), wordsOfChrist: Color(hex: 0xB0271C))
        }
    }

    /// The reader's five highlight colors, by stored name.
    static func highlight(_ name: String?) -> Color {
        switch name {
        case "green": Color(hex: 0x8CD48A)
        case "blue": Color(hex: 0x7FB8F0)
        case "pink": Color(hex: 0xF29BB8)
        case "purple": Color(hex: 0xB9A2EC)
        default: Color(hex: 0xF7D154)
        }
    }

    /// Text with the words of Christ colored. `red` holds Unicode-scalar offsets, as the Bible
    /// databases and DailyVerses.json store them. Pass `redColor: nil` for plain text (tinted
    /// and accented renderings, or when red letters are off).
    static func attributed(_ text: String, red: [Range<Int>], redColor: Color?) -> AttributedString {
        guard let redColor, !red.isEmpty else { return AttributedString(text) }
        let scalars = Array(text.unicodeScalars)
        func slice(_ lo: Int, _ hi: Int) -> String {
            var view = String.UnicodeScalarView()
            view.append(contentsOf: scalars[lo..<hi])
            return String(view)
        }
        var result = AttributedString()
        var cursor = 0
        for span in red.sorted(by: { $0.lowerBound < $1.lowerBound }) {
            let lo = min(max(span.lowerBound, cursor), scalars.count)
            let hi = min(span.upperBound, scalars.count)
            guard lo < hi else { continue }
            if cursor < lo { result += AttributedString(slice(cursor, lo)) }
            var spoken = AttributedString(slice(lo, hi))
            spoken.foregroundColor = redColor
            result += spoken
            cursor = hi
        }
        if cursor < scalars.count { result += AttributedString(slice(cursor, scalars.count)) }
        return result
    }

    /// "Jehovah is my shepherd; I shall…" — the opening of a passage for tight spaces.
    static func openingWords(_ text: String, maxWords: Int = 6) -> String {
        let words = text.split(separator: " ", omittingEmptySubsequences: true)
        guard words.count > maxWords else { return text }
        let head = words.prefix(maxWords).joined(separator: " ")
        return head.trimmingCharacters(in: .punctuationCharacters) + "…"
    }
}

extension Color {
    nonisolated init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255)
    }
}
