import Foundation

/// What may be done with a translation's text beyond reading it on this device.
///
/// Reading, searching, highlighting, noting and listening are local and always allowed — those
/// are what any reader does with a text you hold. What needs a rule is text *leaving* the device:
/// a share link carries the verses inside the URL, an export writes them to a file someone else
/// can open, and the Mi Speaks hand-off passes them to a different app which renders a recording.
///
/// The rule is the licence, not where the file came from: an imported public-domain WEB is as
/// free as the bundled ASV, and a licensed translation is restricted whether it arrived by import,
/// by download, or one day by agreement with its publisher.
public extension TranslationInfo {
    /// True when the text itself may be passed on without a publisher's permission.
    var isPublicDomain: Bool {
        if license.localizedCaseInsensitiveContains("public domain") { return true }
        if copyright.localizedCaseInsensitiveContains("public domain") { return true }
        // No copyright line at all means the bundled texts, which are all public domain.
        return copyright.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// The line that must travel with a quotation from this translation, or nil when none is
    /// required.
    var attributionNotice: String? {
        isPublicDomain ? nil : copyright.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Publishers' standard permissions converge on a few hundred verses before written
    /// permission is needed; Crossway, Lockman and Holman all publish a limit in this range, and
    /// the lowest of them governs what the app offers.
    static let quotationVerseLimit = 500

    /// Whether a quotation of this many verses may be written to a file or a link.
    func mayQuote(verseCount: Int) -> Bool {
        isPublicDomain || verseCount <= Self.quotationVerseLimit
    }

    /// Handing text to another app to render is not quotation — it copies the text into software
    /// the publisher never licensed and leaves a recording behind. Allowed only for texts that
    /// need no permission.
    var mayHandOffToOtherApps: Bool { isPublicDomain }
}
