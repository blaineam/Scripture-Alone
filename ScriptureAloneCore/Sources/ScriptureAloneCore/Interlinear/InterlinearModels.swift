import Foundation

// The value types `InterlinearStore` hands out. They are plain, `Sendable` and carry no reference
// back into the store, so a view model can hold a verse's words across actor hops.
//
// Everything here works as it stands on watchOS (no 64-bit arithmetic: the largest number in the
// format is a verse key, 66,022,021), so none of it needs the `#if !os(watchOS)` guard `Import/`
// uses. The watch simply never gets the database — see `InterlinearStore`'s header.

/// The language one interlinear word is written in.
public enum InterlinearLanguage: String, Hashable, Sendable, CaseIterable {
    case hebrew = "H", aramaic = "A", greek = "G"

    /// "Hebrew", "Aramaic", "Greek" — unlocalized; the UI localizes its own labels.
    public var name: String {
        switch self {
        case .hebrew: "Hebrew"
        case .aramaic: "Aramaic"
        case .greek: "Greek"
        }
    }

    /// Hebrew and Aramaic are written right to left; Greek is not.
    public var isRightToLeft: Bool { self != .greek }
}

/// A morphological parsing, interned in the database's `parsings` table.
public struct InterlinearParsing: Hashable, Sendable {
    /// The translation table's abbreviation, e.g. "V-Qal-Perf-3ms".
    public let code: String
    /// The expanded reading, e.g. "Verb - Qal - Perfect - third person masculine singular".
    public let description: String

    public init(code: String, description: String) {
        self.code = code
        self.description = description
    }
}

/// One original-language word of a verse, with the English the bundled BSB renders it as.
///
/// `english` can legitimately be empty: the translation tables render some words — Hebrew's direct
/// object marker אֵת, a Greek article the BSB folds into the following noun — with no English of
/// their own. That is a word to show on the interlinear line with a blank English cell, not a word
/// to drop.
public struct InterlinearWord: Hashable, Sendable, Identifiable {
    /// 0-based position in the verse, in English (BSB) reading order.
    public let position: Int
    /// The English this word renders, already resolved out of the BSB verse text. Empty when the
    /// BSB renders the word with nothing.
    public let english: String
    /// The Hebrew, Aramaic or Greek word, pointed and accented as its base text has it.
    public let original: String
    public let transliteration: String
    /// The decoded parsing, or nil for a row the tables leave unparsed.
    public let parsing: InterlinearParsing?
    /// The zero-padded Strong's number ("H0430", "G3056"), or nil for an untagged row.
    public let strongs: String?
    public let language: InterlinearLanguage
    /// 1-based position of this word in the *original*-language sentence. Over a whole verse these
    /// are a permutation of 1…n, which is what makes an interlinear line orderable both ways.
    public let originalOrder: Int
    /// Where `english` sits inside the BSB verse text, in UTF-16 units — the range to highlight.
    /// Nil when there is nothing to point at: a superscription word (the BSB keeps Psalm titles
    /// out of the verse text), a word the BSB renders with nothing, or a word of one of the seven
    /// verses whose records do not reproduce the BSB text exactly.
    public let range: NSRange?
    /// True for a word of a Psalm title or Zechariah 12:1's oracle heading. The BSB renders those
    /// as their own block above the verse, so they always come first and never carry a `range`.
    public let isSuperscription: Bool

    public var id: Int { position }

    /// The expanded parsing, or "" when the row carries none — the string to put under a word.
    public var parsingDescription: String { parsing?.description ?? "" }

    public init(position: Int, english: String, original: String, transliteration: String,
                parsing: InterlinearParsing?, strongs: String?, language: InterlinearLanguage,
                originalOrder: Int, range: NSRange?, isSuperscription: Bool) {
        self.position = position
        self.english = english
        self.original = original
        self.transliteration = transliteration
        self.parsing = parsing
        self.strongs = strongs
        self.language = language
        self.originalOrder = originalOrder
        self.range = range
        self.isSuperscription = isSuperscription
    }
}

/// One sense of a Strong's number, as STEPBible's TBESH / TBESG write it.
public struct LexiconSense: Hashable, Sendable {
    /// The dictionary form, e.g. "אֱלֹהִים", "λόγος".
    public let lemma: String
    public let transliteration: String
    /// STEPBible's morphology tag for the lemma, e.g. "H:N-M", "G:V".
    public let morphology: String
    /// The short gloss, e.g. "God", "word".
    public let gloss: String
    /// The fuller definition, already plain text; paragraphs are separated by newlines.
    public let definition: String

    public init(lemma: String, transliteration: String, morphology: String, gloss: String, definition: String) {
        self.lemma = lemma
        self.transliteration = transliteration
        self.morphology = morphology
        self.gloss = gloss
        self.definition = definition
    }

    /// Paragraphs of `definition`, blank lines dropped.
    public var lines: [String] {
        definition.components(separatedBy: "\n").filter { !$0.isEmpty }
    }
}

/// A Strong's number's lexicon entry: its sub-senses in the order STEPBible lists them.
///
/// Extended Strong's keys are collapsed to their base number at build time (H0430G becomes part of
/// H0430), which is why one number can have several senses — "God", "(LORD)-Elohe",
/// "(Gibeath)-elohim" are three senses of H0430.
public struct LexiconEntry: Hashable, Sendable, Identifiable {
    public let strongs: String
    public let senses: [LexiconSense]

    public var id: String { strongs }

    public init(strongs: String, senses: [LexiconSense]) {
        self.strongs = strongs
        self.senses = senses
    }

    /// The headline gloss: the first sense's.
    public var gloss: String { senses.first?.gloss ?? "" }
    /// The dictionary form to show as the entry's title.
    public var lemma: String { senses.first?.lemma ?? "" }
    public var transliteration: String { senses.first?.transliteration ?? "" }
    /// The fullest definition the entry carries — the first sense that has one.
    public var definition: String { senses.first { !$0.definition.isEmpty }?.definition ?? "" }
}

/// The attribution both sources ask for, read out of the database's `meta` table.
///
/// The BSB is public domain and merely appreciates credit; STEPBible's CC BY 4.0 *requires* credit
/// **and** a statement of changes. `requiredLines` is the set the UI must display somewhere the
/// reader can reach — pass it straight to a list and the licences are satisfied.
public struct InterlinearAttribution: Hashable, Sendable {
    /// Where the word-level alignment comes from (the BSB translation tables).
    public let words: String
    public let wordsLicense: String
    public let wordsLicenseURL: URL?
    public let wordsSourceURL: URL?
    /// Where the lexicon comes from (STEPBible TBESH + TBESG).
    public let lexicon: String
    public let lexiconLicense: String
    public let lexiconLicenseURL: URL?
    public let lexiconSourceURL: URL?
    /// The statement of changes CC BY 4.0 requires for the lexicon.
    public let lexiconChanges: String

    /// Every string that has to appear in the UI, in the order to show them.
    public var requiredLines: [String] { [words, lexicon, lexiconChanges] }
}

/// What the database says about itself — the counts the build recorded.
public struct InterlinearStatistics: Hashable, Sendable {
    /// Format version of the database ("1").
    public let version: String
    /// The date the sources were last checked, ISO-8601.
    public let checked: String
    /// The STEPBible commit the lexicons are pinned to.
    public let lexiconCommit: String
    /// Word records in the whole file.
    public let words: Int
    /// Records that carry a Strong's number.
    public let taggedWords: Int
    /// Distinct Strong's numbers used.
    public let strongsNumbers: Int
    /// Verses whose records reproduce the BSB text exactly, out of `verses`.
    public let versesAligned: Int
    public let verses: Int
}
