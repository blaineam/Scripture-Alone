import Foundation

/// The 66 books of the Protestant canon. Raw values are canonical ordinals and match
/// the `book` column in the bundled databases.
public enum BookID: Int, CaseIterable, Sendable, Codable, Hashable, Comparable, Identifiable {
    case genesis = 1, exodus, leviticus, numbers, deuteronomy, joshua, judges, ruth,
         firstSamuel, secondSamuel, firstKings, secondKings, firstChronicles, secondChronicles,
         ezra, nehemiah, esther, job, psalms, proverbs, ecclesiastes, songOfSolomon, isaiah,
         jeremiah, lamentations, ezekiel, daniel, hosea, joel, amos, obadiah, jonah, micah,
         nahum, habakkuk, zephaniah, haggai, zechariah, malachi,
         matthew, mark, luke, john, acts, romans, firstCorinthians, secondCorinthians,
         galatians, ephesians, philippians, colossians, firstThessalonians, secondThessalonians,
         firstTimothy, secondTimothy, titus, philemon, hebrews, james, firstPeter, secondPeter,
         firstJohn, secondJohn, thirdJohn, jude, revelation

    public var id: Int { rawValue }

    public static func < (lhs: BookID, rhs: BookID) -> Bool { lhs.rawValue < rhs.rawValue }

    public var info: BookInfo { BookInfo.all[rawValue - 1] }
    /// The book's name in the language of the Bible being read (`BookNames`); English by default.
    public var name: String { BookNames.name(self) ?? info.name }
    public var abbreviation: String { BookNames.abbreviation(self) ?? info.abbreviation }
    /// Always English — for stored text meant to be read anywhere (exports, keepsake references).
    public var englishName: String { info.name }
    public var code: String { info.code }
    public var chapterCount: Int { info.chapters }
    public var isNewTestament: Bool { rawValue >= BookID.matthew.rawValue }
    public var isSingleChapter: Bool { info.chapters == 1 }
}

public struct BookInfo: Sendable {
    public let code: String
    public let name: String
    public let abbreviation: String
    public let chapters: Int
    /// Lowercased, space-free spellings accepted by the parser (the name is always included).
    public let aliases: [String]
    public let group: BookGroup

    static let all: [BookInfo] = {
        // (code, name, abbreviation, chapters, aliases, group)
        let rows: [(String, String, String, Int, [String], BookGroup)] = [
            ("GEN", "Genesis", "Gen", 50, ["gen", "ge", "gn"], .law),
            ("EXO", "Exodus", "Exod", 40, ["exod", "exo", "ex"], .law),
            ("LEV", "Leviticus", "Lev", 27, ["lev", "le", "lv"], .law),
            ("NUM", "Numbers", "Num", 36, ["num", "nu", "nm", "nb"], .law),
            ("DEU", "Deuteronomy", "Deut", 34, ["deut", "deu", "dt"], .law),
            ("JOS", "Joshua", "Josh", 24, ["josh", "jos", "jsh"], .history),
            ("JDG", "Judges", "Judg", 21, ["judg", "jdg", "jg", "jdgs"], .history),
            ("RUT", "Ruth", "Ruth", 4, ["rut", "ru", "rth"], .history),
            ("1SA", "1 Samuel", "1 Sam", 31, ["1sam", "1sa", "1sm"], .history),
            ("2SA", "2 Samuel", "2 Sam", 24, ["2sam", "2sa", "2sm"], .history),
            ("1KI", "1 Kings", "1 Kgs", 22, ["1kgs", "1ki", "1kg", "1kin"], .history),
            ("2KI", "2 Kings", "2 Kgs", 25, ["2kgs", "2ki", "2kg", "2kin"], .history),
            ("1CH", "1 Chronicles", "1 Chr", 29, ["1chr", "1ch", "1chron"], .history),
            ("2CH", "2 Chronicles", "2 Chr", 36, ["2chr", "2ch", "2chron"], .history),
            ("EZR", "Ezra", "Ezra", 10, ["ezr"], .history),
            ("NEH", "Nehemiah", "Neh", 13, ["neh", "ne"], .history),
            ("EST", "Esther", "Esth", 10, ["esth", "est", "es"], .history),
            ("JOB", "Job", "Job", 42, ["jb"], .wisdom),
            ("PSA", "Psalms", "Ps", 150, ["ps", "psa", "psalm", "pss", "psm", "pslm"], .wisdom),
            ("PRO", "Proverbs", "Prov", 31, ["prov", "pro", "prv", "pr"], .wisdom),
            ("ECC", "Ecclesiastes", "Eccl", 12, ["eccl", "ecc", "ec", "eccles", "qoh"], .wisdom),
            ("SNG", "Song of Solomon", "Song", 8, ["song", "sng", "sos", "songofsongs", "canticles"], .wisdom),
            ("ISA", "Isaiah", "Isa", 66, ["isa"], .majorProphets),
            ("JER", "Jeremiah", "Jer", 52, ["jer", "jr"], .majorProphets),
            ("LAM", "Lamentations", "Lam", 5, ["lam"], .majorProphets),
            ("EZK", "Ezekiel", "Ezek", 48, ["ezek", "ezk", "eze"], .majorProphets),
            ("DAN", "Daniel", "Dan", 12, ["dan", "dn"], .majorProphets),
            ("HOS", "Hosea", "Hos", 14, ["hos"], .minorProphets),
            ("JOL", "Joel", "Joel", 3, ["jol", "jl"], .minorProphets),
            ("AMO", "Amos", "Amos", 9, ["amo"], .minorProphets),
            ("OBA", "Obadiah", "Obad", 1, ["obad", "oba", "ob"], .minorProphets),
            ("JON", "Jonah", "Jonah", 4, ["jnh"], .minorProphets),
            ("MIC", "Micah", "Mic", 7, ["mic", "mc"], .minorProphets),
            ("NAM", "Nahum", "Nah", 3, ["nah", "nam"], .minorProphets),
            ("HAB", "Habakkuk", "Hab", 3, ["hab", "hb"], .minorProphets),
            ("ZEP", "Zephaniah", "Zeph", 3, ["zeph", "zep", "zp"], .minorProphets),
            ("HAG", "Haggai", "Hag", 2, ["hag", "hg"], .minorProphets),
            ("ZEC", "Zechariah", "Zech", 14, ["zech", "zec", "zc"], .minorProphets),
            ("MAL", "Malachi", "Mal", 4, ["mal", "ml"], .minorProphets),
            ("MAT", "Matthew", "Matt", 28, ["matt", "mat", "mt"], .gospels),
            ("MRK", "Mark", "Mark", 16, ["mrk", "mk", "mr"], .gospels),
            ("LUK", "Luke", "Luke", 24, ["luk", "lk"], .gospels),
            ("JHN", "John", "John", 21, ["jhn", "jn", "joh"], .gospels),
            ("ACT", "Acts", "Acts", 28, ["act", "ac"], .history),
            ("ROM", "Romans", "Rom", 16, ["rom", "ro", "rm"], .paul),
            ("1CO", "1 Corinthians", "1 Cor", 16, ["1cor", "1co"], .paul),
            ("2CO", "2 Corinthians", "2 Cor", 13, ["2cor", "2co"], .paul),
            ("GAL", "Galatians", "Gal", 6, ["gal", "ga"], .paul),
            ("EPH", "Ephesians", "Eph", 6, ["eph", "ephes"], .paul),
            ("PHP", "Philippians", "Phil", 4, ["phil", "php", "pp"], .paul),
            ("COL", "Colossians", "Col", 4, ["col"], .paul),
            ("1TH", "1 Thessalonians", "1 Thess", 5, ["1thess", "1th", "1thes"], .paul),
            ("2TH", "2 Thessalonians", "2 Thess", 3, ["2thess", "2th", "2thes"], .paul),
            ("1TI", "1 Timothy", "1 Tim", 6, ["1tim", "1ti", "1tm"], .paul),
            ("2TI", "2 Timothy", "2 Tim", 4, ["2tim", "2ti", "2tm"], .paul),
            ("TIT", "Titus", "Titus", 3, ["tit"], .paul),
            ("PHM", "Philemon", "Phlm", 1, ["phlm", "phm", "philem", "pm"], .paul),
            ("HEB", "Hebrews", "Heb", 13, ["heb"], .general),
            ("JAS", "James", "Jas", 5, ["jas", "jm", "jam"], .general),
            ("1PE", "1 Peter", "1 Pet", 5, ["1pet", "1pe", "1pt"], .general),
            ("2PE", "2 Peter", "2 Pet", 3, ["2pet", "2pe", "2pt"], .general),
            ("1JN", "1 John", "1 John", 5, ["1jn", "1jo", "1jhn", "1joh"], .general),
            ("2JN", "2 John", "2 John", 1, ["2jn", "2jo", "2jhn", "2joh"], .general),
            ("3JN", "3 John", "3 John", 1, ["3jn", "3jo", "3jhn", "3joh"], .general),
            ("JUD", "Jude", "Jude", 1, ["jud", "jd"], .general),
            ("REV", "Revelation", "Rev", 22, ["rev", "rv", "revelations", "apocalypse"], .prophecy),
        ]
        return rows.map { code, name, abbreviation, chapters, aliases, group in
            let key = BookInfo.normalize(name)
            return BookInfo(code: code, name: name, abbreviation: abbreviation, chapters: chapters,
                            aliases: [key] + aliases.filter { $0 != key }, group: group)
        }
    }()

    /// Lowercases, drops periods and spaces: "1 Cor." -> "1cor".
    static func normalize(_ string: String) -> String {
        string.lowercased().filter { !$0.isWhitespace && $0 != "." }
    }
}

public enum BookGroup: String, Sendable, CaseIterable {
    case law, history, wisdom, majorProphets, minorProphets, gospels, paul, general, prophecy

    public var title: String {
        switch self {
        case .law: String(localized: "Law", bundle: .module, comment: "Section heading grouping books of the Bible")
        case .history: String(localized: "History", bundle: .module, comment: "Section heading grouping books of the Bible")
        case .wisdom: String(localized: "Wisdom & Poetry", bundle: .module, comment: "Section heading grouping books of the Bible")
        case .majorProphets: String(localized: "Major Prophets", bundle: .module, comment: "Section heading grouping books of the Bible")
        case .minorProphets: String(localized: "Minor Prophets", bundle: .module, comment: "Section heading grouping books of the Bible")
        case .gospels: String(localized: "Gospels", bundle: .module, comment: "Section heading grouping books of the Bible")
        case .paul: String(localized: "Paul’s Letters", bundle: .module, comment: "Section heading grouping books of the Bible")
        case .general: String(localized: "General Letters", bundle: .module, comment: "Section heading grouping books of the Bible")
        case .prophecy: String(localized: "Prophecy", bundle: .module, comment: "Section heading grouping books of the Bible")
        }
    }
}
