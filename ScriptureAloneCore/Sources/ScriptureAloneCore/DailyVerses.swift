import Foundation

/// One Verse of the Day passage with its text in every bundled translation, as written by
/// Tools/build_companion_data.py into DailyVerses.json.
public struct DailyVerse: Codable, Hashable, Sendable, Identifiable {
    /// Verse range storage form, "43003016-43003016".
    public let ref: String
    public let theme: String
    public let text: [String: String]
    /// Words of Christ per translation: [start, length] in Unicode scalars into `text`.
    public let red: [String: [[Int]]]?
    /// The theme in the big-8 languages, keyed "zh-Hans", "ja", "de", "fr", "es", "ko", "pt-BR", "it"
    /// (Tools/build_companion_data.py). Absent in an older catalog.
    public let themes: [String: String]?

    public init(ref: String, theme: String, text: [String: String], red: [String: [[Int]]]? = nil,
                themes: [String: String]? = nil) {
        self.ref = ref
        self.theme = theme
        self.text = text
        self.red = red
        self.themes = themes
    }

    /// The theme in the language the app is running in, or English.
    public var localizedTheme: String { theme(in: Bundle.main.preferredLocalizations.first ?? "en") }

    /// The theme for a BCP 47 language tag: an exact match ("pt-BR"), then the language ("pt" for
    /// "pt-PT", "zh-Hans" for "zh-Hans-CN"), then English.
    public func theme(in tag: String) -> String {
        guard let themes else { return theme }
        if let exact = themes[tag] { return exact }
        let language = Locale.Language(identifier: tag)
        if let code = language.languageCode?.identifier {
            if code == "zh" { return language.script?.identifier == "Hant" ? theme : themes["zh-Hans"] ?? theme }
            if let match = themes.first(where: { $0.key.split(separator: "-").first.map(String.init) == code }) {
                return match.value
            }
        }
        return theme
    }

    public var id: String { ref }
    public var range: VerseRange? { VerseRange(storageString: ref) }

    /// The passage in `translation`, falling back to the ASV, then any translation present.
    public func text(in translation: String) -> String {
        text[translation] ?? text[DailyVerseCatalog.fallbackTranslation] ?? text.values.sorted().first ?? ""
    }

    /// Red-letter spans for `translation` as ranges of Unicode scalar offsets.
    public func redRanges(in translation: String) -> [Range<Int>] {
        let key = text[translation] == nil ? DailyVerseCatalog.fallbackTranslation : translation
        return (red?[key] ?? []).compactMap { pair in
            guard pair.count == 2, pair[0] >= 0, pair[1] > 0 else { return nil }
            return pair[0]..<(pair[0] + pair[1])
        }
    }
}

/// The curated Verse of the Day list and the rule that picks a day's passage.
///
/// The pick depends only on the local calendar date: every device shows the same passage on
/// the same day, offline, with no server. Days are counted from 1 January 2000 and stepped
/// through the list by a stride coprime with its length, so each passage appears exactly once
/// per cycle and consecutive days jump around the canon.
public struct DailyVerseCatalog: Codable, Sendable {
    public static let fallbackTranslation = "ASV"
    public static let resourceName = "DailyVerses"

    public let version: Int
    public let translations: [String]
    public let verses: [DailyVerse]

    public init(version: Int = 1, translations: [String], verses: [DailyVerse]) {
        self.version = version
        self.translations = translations
        self.verses = verses
    }

    public init(data: Data) throws {
        self = try JSONDecoder().decode(DailyVerseCatalog.self, from: data)
    }

    /// The passage for the local calendar day containing `date`.
    public func verse(on date: Date, calendar: Calendar = .current) -> DailyVerse? {
        guard !verses.isEmpty else { return nil }
        return verses[Self.index(on: date, count: verses.count, calendar: calendar)]
    }

    /// Index into a list of `count` passages for the local calendar day containing `date`.
    public static func index(on date: Date, count: Int, calendar: Calendar = .current) -> Int {
        precondition(count > 0)
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        let day = dayNumber(year: parts.year ?? 2000, month: parts.month ?? 1, day: parts.day ?? 1)
        return index(forDay: day, count: count)
    }

    /// Index for a day number (days since 2000-01-01). A multiplicative step by a stride coprime
    /// with `count` is a permutation of 0..<count.
    public static func index(forDay day: Int, count: Int) -> Int {
        precondition(count > 0)
        let d = ((day % count) + count) % count
        return (d * stride(for: count)) % count
    }

    /// The smallest step at or above ~0.382 × count (the golden-ratio complement, which scatters
    /// neighbors well) that shares no factor with `count`.
    public static func stride(for count: Int) -> Int {
        guard count > 2 else { return 1 }
        var step = max(1, Int((Double(count) * 0.382).rounded()))
        while gcd(step, count) != 1 { step += 1 }
        return step
    }

    /// Days from 2000-01-01 to a proleptic Gregorian date — pure arithmetic, so daylight saving
    /// and time zones can't shift it (Howard Hinnant's days_from_civil).
    public static func dayNumber(year: Int, month: Int, day: Int) -> Int {
        let y = month <= 2 ? year - 1 : year
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let mp = (month + 9) % 12
        let doy = (153 * mp + 2) / 5 + day - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468 - 10_957
    }

    /// The next local midnight strictly after `date` — when widgets roll to the next passage.
    public static func nextMidnight(after date: Date, calendar: Calendar = .current) -> Date {
        let start = calendar.startOfDay(for: date)
        return calendar.date(byAdding: .day, value: 1, to: start) ?? date.addingTimeInterval(86_400)
    }

    private static func gcd(_ a: Int, _ b: Int) -> Int { b == 0 ? a : gcd(b, a % b) }
}
