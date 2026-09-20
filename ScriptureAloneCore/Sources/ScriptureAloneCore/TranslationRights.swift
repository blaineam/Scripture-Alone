import Foundation

/// What may be done with a translation's text beyond reading it on this device.
///
/// Reading, searching, highlighting, noting and listening are local and always allowed — those
/// are what any reader does with a text you hold. What needs a rule is text *leaving* the device:
/// a share link carries the verses inside the URL, an export writes them to a file someone else
/// can open, and the Mi Speaks hand-off passes them to a different app which renders a recording.
///
/// There is exactly one answer to "may this text leave?", and it is this struct. A translation that
/// arrived in a signed package carries the publisher's own answer (`TranslationInfo.grantedRights`);
/// every other translation has one derived from its licence line. Everything downstream — the share
/// sheet, the verse image, the notes export, the hand-off to another app — asks the same value the
/// same way, so a packaged translation and the public-domain ASV are judged by one code path.
public struct TranslationRights: Hashable, Sendable, Codable {
    /// Copy the verses to the clipboard.
    public var allowCopy: Bool
    /// Share the verses as text — the share sheet and share links, which carry the text itself.
    public var allowShare: Bool
    /// Render the verses into an image to post.
    public var allowVerseImages: Bool
    /// Write the verses into an exported notes file (text or PDF).
    public var allowNotesExport: Bool
    /// Hand the verses to another app to render — the Mi Speaks path. Not quotation: it copies the
    /// text into software the publisher never licensed and leaves a recording behind.
    public var allowExternalHandoff: Bool
    /// Keep the text on the device at all. A publisher may licence reading without local storage;
    /// a package that forbids it must not be copied into the library.
    public var allowOfflineStorage: Bool
    /// Most verses that may leave in one quotation. `TranslationRights.unlimitedQuotation` for a
    /// text that needs no permission.
    public var maxQuotationVerses: Int
    /// When the grant lapses. Nil for a grant that does not.
    public var expires: Date?

    public init(allowCopy: Bool, allowShare: Bool, allowVerseImages: Bool, allowNotesExport: Bool,
                allowExternalHandoff: Bool, allowOfflineStorage: Bool, maxQuotationVerses: Int,
                expires: Date? = nil) {
        self.allowCopy = allowCopy
        self.allowShare = allowShare
        self.allowVerseImages = allowVerseImages
        self.allowNotesExport = allowNotesExport
        self.allowExternalHandoff = allowExternalHandoff
        self.allowOfflineStorage = allowOfflineStorage
        self.maxQuotationVerses = maxQuotationVerses
        self.expires = expires
    }

    /// No cap at all. Not a very large number that some future verse count could reach: the whole
    /// Protestant canon is 31,102 verses, and this is larger than any quotation can be.
    public static let unlimitedQuotation = Int.max

    /// A text that needs nobody's permission: the bundled ASV, BSB and KJV, and any import whose
    /// licence says public domain.
    public static let publicDomain = TranslationRights(allowCopy: true, allowShare: true,
                                                       allowVerseImages: true, allowNotesExport: true,
                                                       allowExternalHandoff: true, allowOfflineStorage: true,
                                                       maxQuotationVerses: unlimitedQuotation)

    /// What a copyrighted text may do without a specific grant — the publishers' own standard
    /// permissions, which allow quotation with attribution up to a published limit, and say nothing
    /// that would permit handing the text to other software. An imported file whose licence is
    /// unknown lands here too: "unknown" must behave like "licensed", never like "free".
    public static let licensedDefault = TranslationRights(allowCopy: true, allowShare: true,
                                                          allowVerseImages: true, allowNotesExport: true,
                                                          allowExternalHandoff: false, allowOfflineStorage: true,
                                                          maxQuotationVerses: TranslationInfo.quotationVerseLimit)

    public func hasExpired(asOf now: Date = Date()) -> Bool {
        guard let expires else { return false }
        return now >= expires
    }

    /// An expired grant permits nothing. A package past its date refuses to open at all
    /// (`TranslationPackageError.expired`); this is the second gate, for a translation already open
    /// when the date passes.
    public func permits(_ permission: KeyPath<TranslationRights, Bool>, asOf now: Date = Date()) -> Bool {
        !hasExpired(asOf: now) && self[keyPath: permission]
    }

    /// Whether a quotation of this many verses may leave the device.
    public func mayQuote(verseCount: Int, asOf now: Date = Date()) -> Bool {
        guard !hasExpired(asOf: now) else { return false }
        return verseCount <= maxQuotationVerses
    }
}

public extension TranslationInfo {
    /// True when the text itself may be passed on without a publisher's permission.
    ///
    /// This reads the licence line and nothing else. It is a fact about the text, not a permission:
    /// a public-domain text delivered in a package is still governed by that package's policy, so
    /// the app gates on `rights`, never on this.
    var isPublicDomain: Bool {
        if license.localizedCaseInsensitiveContains("public domain") { return true }
        if copyright.localizedCaseInsensitiveContains("public domain") { return true }
        // No copyright line at all means the bundled texts, which are all public domain.
        return copyright.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// The one value every gate asks. A signed package's policy if there is one; otherwise the
    /// licence line, read the way the app has always read it.
    var rights: TranslationRights {
        grantedRights ?? (isPublicDomain ? .publicDomain : .licensedDefault)
    }

    /// The line that must travel with a quotation from this translation, or nil when none is
    /// required.
    var attributionNotice: String? {
        isPublicDomain ? nil : copyright.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Publishers' standard permissions converge on a few hundred verses before written
    /// permission is needed; Crossway, Lockman and Holman all publish a limit in this range, and
    /// the lowest of them governs what the app offers. A package may set its own limit instead.
    static let quotationVerseLimit = 500

    /// Whether a quotation of this many verses may be written to a file or a link.
    func mayQuote(verseCount: Int) -> Bool { rights.mayQuote(verseCount: verseCount) }

    /// Handing text to another app to render is not quotation — it copies the text into software
    /// the publisher never licensed and leaves a recording behind. Allowed only for texts that
    /// need no permission, or where a publisher granted it by name.
    var mayHandOffToOtherApps: Bool { rights.permits(\.allowExternalHandoff) }

    var mayCopy: Bool { rights.permits(\.allowCopy) }
    var mayShareText: Bool { rights.permits(\.allowShare) }
    var mayRenderVerseImage: Bool { rights.permits(\.allowVerseImages) }
    var mayExportNotesWithVerses: Bool { rights.permits(\.allowNotesExport) }
    var mayStoreOffline: Bool { rights.permits(\.allowOfflineStorage) }
}
