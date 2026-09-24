import Foundation

/// A publisher's own published terms for quoting its translation without asking — what the app
/// holds a quotation to, and the notice that must travel with it.
///
/// Every figure here is copied from the publisher's permissions page (`source`), checked on the
/// date in `checked`. Rules that depend on the work a quotation ends up in ("no more than 25% of
/// the total text of the work") can't be measured from inside the app and are left to the reader;
/// the rules that can be — how many verses, a whole book, how much of one book — are enforced.
///
/// A translation that matches nothing here keeps `TranslationRights.licensedDefault`: the lowest
/// limit any of these publishers sets, with the file's own copyright line as the notice.
public struct PublisherTerms: Sendable, Hashable {
    /// The abbreviation it is quoted under, and the one the notice uses.
    public let abbreviation: String
    /// Other abbreviations a file might carry for the same text ("NASB95").
    public let aliases: [String]
    /// Words that identify the translation in a file's name or copyright page, most specific first.
    public let markers: [String]
    /// Most verses in one quotation, or nil for no count limit.
    public let maxVerses: Int?
    public let allowsCompleteBook: Bool
    /// Largest share of any one book a quotation may be, when the publisher sets one.
    public let maxShareOfBook: Double?
    /// Text may be shared out of the app at all — social posts, messages, the share sheet.
    public let allowsSharing: Bool
    /// Verses may be set into an image. Several publishers license stand-alone verse art separately.
    public let allowsVerseImages: Bool
    /// The notice that must accompany a quotation.
    public let notice: String
    public let source: String
    public let checked: String
    /// The abbreviated notice a publisher accepts in non-salable media — a verse image, a post.
    public var shortNotice: String? = nil

    /// What the app's gates ask, under these terms.
    public var rights: TranslationRights {
        TranslationRights(allowCopy: true, allowShare: allowsSharing, allowVerseImages: allowsSharing && allowsVerseImages,
                          allowNotesExport: true, allowExternalHandoff: false, allowOfflineStorage: true,
                          maxQuotationVerses: maxVerses ?? TranslationRights.unlimitedQuotation)
    }

    /// The terms for a translation, from its abbreviation first and then the words of its name and
    /// copyright line. Nil when the translation is none of these.
    public static func matching(abbreviation: String, name: String, copyright: String) -> PublisherTerms? {
        let key = Self.key(abbreviation)
        if let exact = all.first(where: { Self.key($0.abbreviation) == key || $0.aliases.contains { Self.key($0) == key } }) {
            return exact
        }
        return matching(text: name + "\n" + copyright)
    }

    /// The terms whose translation a passage of text names — a file's copyright page.
    public static func matching(text: String) -> PublisherTerms? {
        let folded = text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
        // `all` lists the more specific names first: "New Revised Standard" before "Revised
        // Standard", "Holman Christian Standard" before "Christian Standard".
        return all.first { terms in
            terms.markers.contains { folded.contains($0.folding(options: [.caseInsensitive], locale: nil)) }
        }
    }

    private static func key(_ abbreviation: String) -> String {
        abbreviation.uppercased().filter { $0.isLetter || $0.isNumber }
    }

    // MARK: - The table

    public static let all: [PublisherTerms] = [
        PublisherTerms(
            abbreviation: "ESV", aliases: [], markers: ["English Standard Version"],
            maxVerses: 500, allowsCompleteBook: false, maxShareOfBook: 0.5,
            allowsSharing: true, allowsVerseImages: true,
            notice: "Scripture quotations are from the ESV® Bible (The Holy Bible, English Standard Version®), © 2001 by Crossway, a publishing ministry of Good News Publishers. Used by permission. All rights reserved.",
            source: "https://www.crossway.org/permissions/", checked: "2026-09-23", shortNotice: "(ESV)"),
        PublisherTerms(
            abbreviation: "HCSB", aliases: [], markers: ["Holman Christian Standard"],
            maxVerses: 250, allowsCompleteBook: false, maxShareOfBook: nil,
            allowsSharing: true, allowsVerseImages: true,
            notice: "Scripture quotations marked HCSB are taken from the Holman Christian Standard Bible®, Copyright © 1999, 2000, 2002, 2003 by Holman Bible Publishers. Used by permission. Holman Christian Standard Bible®, Holman CSB®, and HCSB® are federally registered trademarks of Holman Bible Publishers.",
            source: "https://ssl.bhpublishinggroup.com/hcsb/Holman_CSB_copyright_policy.pdf", checked: "2026-09-23"),
        PublisherTerms(
            abbreviation: "CSB", aliases: [], markers: ["Christian Standard Bible"],
            maxVerses: 1000, allowsCompleteBook: false, maxShareOfBook: nil,
            allowsSharing: true, allowsVerseImages: true,
            notice: "Scripture quotations marked CSB have been taken from the Christian Standard Bible®, Copyright © 2017 by Holman Bible Publishers. Used by permission. Christian Standard Bible® and CSB® are federally registered trademarks of Holman Bible Publishers.",
            source: "https://csbible.com/about-the-csb/permissions/", checked: "2026-09-23"),
        PublisherTerms(
            abbreviation: "NIV", aliases: [], markers: ["New International Version"],
            maxVerses: 500, allowsCompleteBook: false, maxShareOfBook: 0.5,
            // Stand-alone verse art needs Zondervan's permission.
            allowsSharing: true, allowsVerseImages: false,
            notice: "Scripture quotations taken from The Holy Bible, New International Version® NIV®. Copyright © 1973, 1978, 1984, 2011 by Biblica, Inc.™ Used by permission. All rights reserved worldwide.",
            source: "https://www.harpercollinschristian.com/permissions/", checked: "2026-09-23"),
        PublisherTerms(
            abbreviation: "NKJV", aliases: [], markers: ["New King James"],
            maxVerses: 500, allowsCompleteBook: false, maxShareOfBook: 0.5,
            // Stand-alone verse art needs Thomas Nelson's permission.
            allowsSharing: true, allowsVerseImages: false,
            notice: "Scripture taken from the New King James Version®. Copyright © 1982 by Thomas Nelson. Used by permission. All rights reserved.",
            source: "https://www.harpercollinschristian.com/permissions/", checked: "2026-09-23"),
        PublisherTerms(
            abbreviation: "NASB", aliases: ["NASB2020", "NASB20"], markers: ["New American Standard Bible, Copyright © 1960, 1971, 1977, 1995, 2020", "2020 by The Lockman"],
            maxVerses: 1000, allowsCompleteBook: false, maxShareOfBook: nil,
            allowsSharing: true, allowsVerseImages: true,
            notice: "Scripture quotations taken from the (NASB®) New American Standard Bible®, Copyright © 1960, 1971, 1977, 1995, 2020 by The Lockman Foundation. Used by permission. All rights reserved. www.Lockman.org",
            source: "https://www.lockman.org/permission-to-quote-copyright-trademark-information/", checked: "2026-09-23"),
        PublisherTerms(
            abbreviation: "NASB1995", aliases: ["NASB95", "NAS"], markers: ["New American Standard"],
            maxVerses: 1000, allowsCompleteBook: false, maxShareOfBook: nil,
            allowsSharing: true, allowsVerseImages: true,
            notice: "Scripture quotations taken from the (NASB®) New American Standard Bible®, Copyright © 1960, 1971, 1977, 1995 by The Lockman Foundation. Used by permission. All rights reserved. www.Lockman.org",
            source: "https://www.lockman.org/permission-to-quote-copyright-trademark-information/", checked: "2026-09-23"),
        PublisherTerms(
            abbreviation: "AMP", aliases: [], markers: ["Amplified"],
            maxVerses: 1000, allowsCompleteBook: false, maxShareOfBook: nil,
            allowsSharing: true, allowsVerseImages: true,
            notice: "Scripture quotations taken from the Amplified® Bible (AMP), Copyright © 2015 by The Lockman Foundation. Used by permission. www.Lockman.org",
            source: "https://www.lockman.org/permission-to-quote-copyright-trademark-information/", checked: "2026-09-23"),
        PublisherTerms(
            abbreviation: "LSB", aliases: [], markers: ["Legacy Standard"],
            maxVerses: 1000, allowsCompleteBook: false, maxShareOfBook: nil,
            allowsSharing: true, allowsVerseImages: true,
            notice: "Scripture quotations taken from the (LSB®) Legacy Standard Bible®, Copyright © 2021 by The Lockman Foundation. Used by permission. All rights reserved. Managed in partnership with Three Sixteen Publishing Inc. LSBible.org and 316publishing.com.",
            source: "https://lsbible.org/permission-to-quote-the-lsb/", checked: "2026-09-23"),
        PublisherTerms(
            abbreviation: "NLT", aliases: [], markers: ["New Living Translation"],
            maxVerses: 500, allowsCompleteBook: false, maxShareOfBook: nil,
            // Artwork needs Tyndale's permission.
            allowsSharing: true, allowsVerseImages: false,
            notice: "Scripture quotations are taken from the Holy Bible, New Living Translation, copyright ©1996, 2004, 2015 by Tyndale House Foundation. Used by permission of Tyndale House Publishers, Carol Stream, Illinois 60188. All rights reserved.",
            source: "https://www.tyndale.com/permissions", checked: "2026-09-23", shortNotice: "(NLT)"),
        PublisherTerms(
            abbreviation: "MSG", aliases: [], markers: ["The Message", "Eugene H. Peterson"],
            maxVerses: 500, allowsCompleteBook: false, maxShareOfBook: nil,
            // Free use covers print and eBooks only; anything online or audio needs NavPress's permission.
            allowsSharing: false, allowsVerseImages: false,
            notice: "All Scripture quotations are taken from The Message, copyright © 1993, 2002, 2018 by Eugene H. Peterson. Used by permission of NavPress. All rights reserved. Represented by Tyndale House Publishers.",
            source: "https://www.navpress.com/permissions", checked: "2026-09-23"),
        PublisherTerms(
            abbreviation: "NRSVUE", aliases: ["NRSVue"], markers: ["New Revised Standard Version Updated", "NRSVue"],
            maxVerses: 499, allowsCompleteBook: false, maxShareOfBook: nil,
            // The free-use guidelines "do not apply to Phone Applications, or other New Media Platforms".
            allowsSharing: false, allowsVerseImages: false,
            notice: "Scripture quotations are taken from the New Revised Standard Version Updated Edition. Copyright © 2021 National Council of Churches of Christ in the United States of America. Used by permission. All rights reserved worldwide.",
            source: "https://www.friendshippress.org/pages/nrsvue-quick-faq", checked: "2026-09-23"),
        PublisherTerms(
            abbreviation: "NRSV", aliases: [], markers: ["New Revised Standard"],
            maxVerses: 499, allowsCompleteBook: false, maxShareOfBook: nil,
            allowsSharing: false, allowsVerseImages: false,
            notice: "New Revised Standard Version Bible, copyright © 1989 National Council of the Churches of Christ in the United States of America. Used by permission. All rights reserved worldwide.",
            source: "https://www.friendshippress.org/pages/nrsvue-quick-faq", checked: "2026-09-23"),
        PublisherTerms(
            abbreviation: "RSV", aliases: [], markers: ["Revised Standard Version"],
            maxVerses: 499, allowsCompleteBook: false, maxShareOfBook: nil,
            allowsSharing: false, allowsVerseImages: false,
            notice: "Revised Standard Version of the Bible, copyright © 1946, 1952, and 1971 National Council of the Churches of Christ in the United States of America. Used by permission. All rights reserved worldwide.",
            source: "https://www.friendshippress.org/pages/nrsvue-quick-faq", checked: "2026-09-23"),
        PublisherTerms(
            abbreviation: "NET", aliases: [], markers: ["NET Bible", "Biblical Studies Press"],
            maxVerses: nil, allowsCompleteBook: true, maxShareOfBook: nil,
            allowsSharing: true, allowsVerseImages: true,
            notice: "Scripture quoted by permission. Quotations designated (NET) are from the NET Bible® copyright ©1996, 2019 by Biblical Studies Press, L.L.C. http://netbible.com All rights reserved.",
            source: "https://netbible.com/copyright/", checked: "2026-09-23", shortNotice: "(NET)"),
        PublisherTerms(
            abbreviation: "CEB", aliases: [], markers: ["Common English Bible"],
            maxVerses: nil, allowsCompleteBook: false, maxShareOfBook: nil,
            allowsSharing: true, allowsVerseImages: true,
            notice: "Scripture quotations from the COMMON ENGLISH BIBLE. © Copyright 2011 COMMON ENGLISH BIBLE. All rights reserved. Used by permission. (www.CommonEnglishBible.com).",
            source: "https://www.commonenglishbible.com/permissions", checked: "2026-09-23"),
    ]
}

/// Why a selection may not leave the app as a quotation.
public enum QuotationRefusal: Sendable, Hashable {
    /// The translation's terms allow no quotation of this kind at all.
    case notPermitted
    case tooManyVerses(limit: Int)
    case wholeBook(BookID)
    /// More of one book than the terms allow; `percent` is the most they do.
    case tooMuchOfBook(BookID, percent: Int)
}

public extension TranslationInfo {
    /// The publisher terms this translation is held to, when it is one the app knows.
    var publisherTerms: PublisherTerms? {
        guard grantedRights == nil, !isPublicDomain else { return nil }
        return PublisherTerms.matching(abbreviation: abbreviation, name: name, copyright: copyright)
    }

    /// Whether these verses may leave the app together, and if not, why not.
    ///
    /// - Parameter chapterVerses: how many verses a chapter has in this translation, for the
    ///   whole-book and share-of-a-book rules. Books whose size can't be learned are held to the
    ///   verse limit alone.
    func quotationRefusal(for verses: [VerseRef], chapterVerses: (ChapterRef) -> Int) -> QuotationRefusal? {
        let rights = self.rights
        guard !rights.hasExpired(), rights.maxQuotationVerses > 0 else { return .notPermitted }
        let count = Set(verses).count
        if count > rights.maxQuotationVerses { return .tooManyVerses(limit: rights.maxQuotationVerses) }
        guard let terms = publisherTerms, !terms.allowsCompleteBook || terms.maxShareOfBook != nil else { return nil }
        let byBook = Dictionary(grouping: Set(verses), by: \.book)
        for (book, selected) in byBook.sorted(by: { $0.key < $1.key }) {
            let total = (1...book.chapterCount).reduce(0) { $0 + chapterVerses(ChapterRef(book, $1)) }
            guard total > 0 else { continue }
            if !terms.allowsCompleteBook, selected.count >= total { return .wholeBook(book) }
            if let share = terms.maxShareOfBook, Double(selected.count) > Double(total) * share {
                return .tooMuchOfBook(book, percent: Int((share * 100).rounded()))
            }
        }
        return nil
    }
}

public extension ChapterTextSource {
    /// Whether these ranges may leave the app together as one quotation, under this translation's
    /// terms, and if not, why not.
    func quotationRefusal(for ranges: [VerseRange]) -> QuotationRefusal? {
        let verses = ranges.flatMap { (try? self.verses(in: $0)) ?? [] }.map(\.ref)
        return info.quotationRefusal(for: verses, chapterVerses: { self.verseCount($0) })
    }
}
