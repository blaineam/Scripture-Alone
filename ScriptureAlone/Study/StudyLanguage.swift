import Foundation

/// Which study content a reader sees, by the language the app is running in.
///
/// Some of Study mode exists only in English: the commentary (Calvin, Gill, Jamieson-Fausset-Brown)
/// and the lexicon's glosses and definitions. A reader using the app in another language would get
/// English prose in the middle of their own — so, by the owner's decision (docs/localization.md),
/// the commentary is hidden outside English, and the Hebrew and Greek show the words, their
/// transliteration, parsing code and Strong's number, without English definitions. Cross-references,
/// maps, the timeline and charts are the same for everyone (their text is translated).
enum StudyLanguage {
    /// Whether the app is running in English — the language the English-only content is in.
    static var isEnglish: Bool {
        (Bundle.main.preferredLocalizations.first ?? "en").hasPrefix("en")
    }
}
