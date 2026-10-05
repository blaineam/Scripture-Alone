import Foundation
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone

/// The reader model against the real ASV package: paging across books, the saved position, the
/// recent chapters and searches the Go To sheet lists, and the one place text becomes a quotation.
///
/// `ReaderModel` keeps its state in `UserDefaults.standard` (the host app's); each test puts back
/// what it found.
@MainActor
@Suite(.serialized)
final class ReaderModelTests {
    static let keys = ["recent", "recentSearches", "position", "translation"]
    let saved: [String: Any]

    init() {
        saved = Dictionary(uniqueKeysWithValues: Self.keys.compactMap { key in
            UserDefaults.standard.object(forKey: key).map { (key, $0) }
        })
        for key in Self.keys { UserDefaults.standard.removeObject(forKey: key) }
    }

    isolated deinit {
        for key in Self.keys { UserDefaults.standard.removeObject(forKey: key) }
        for (key, value) in saved { UserDefaults.standard.set(value, forKey: key) }
    }

    func asvModel() throws -> ReaderModel {
        let model = ReaderModel()
        model.selectTranslation("ASV", remember: false)
        try #require(model.translationID == "ASV", "the ASV package did not open: \(model.loadError ?? "")")
        return model
    }

    // MARK: Paging

    @Test func pagingCrossesBookBoundariesAndStopsAtTheEnds() throws {
        let model = try asvModel()
        model.show(ChapterRef(.genesis, 50))
        model.next()
        #expect(model.location == ChapterRef(.exodus, 1))
        model.previous()
        #expect(model.location == ChapterRef(.genesis, 50))

        model.show(ChapterRef(.revelation, 22))
        model.next()
        #expect(model.location == ChapterRef(.revelation, 22))
        model.show(ChapterRef(.genesis, 1))
        model.previous()
        #expect(model.location == ChapterRef(.genesis, 1))
    }

    @Test func showingAChapterLoadsItsLayout() throws {
        let model = try asvModel()
        model.show(ChapterRef(.psalms, 23))
        #expect(model.layoutChapter == ChapterRef(.psalms, 23))
        #expect(model.layout?.blocks.isEmpty == false)
        #expect(model.loadError == nil)
    }

    @Test func aVerseIsScrolledToAndSavedAsThePosition() throws {
        let model = try asvModel()
        model.show(ChapterRef(.john, 3), verse: 16)
        #expect(model.scrollTarget == VerseRef(.john, 3, 16).key)
        #expect(UserDefaults.standard.integer(forKey: "position") == VerseRef(.john, 3, 16).key)

        model.updateTopVerse(VerseRef(.john, 3, 20).key)
        #expect(UserDefaults.standard.integer(forKey: "position") == VerseRef(.john, 3, 20).key)
    }

    @Test func aNewModelOpensWhereTheLastOneLeftOff() throws {
        let model = try asvModel()
        model.show(ChapterRef(.romans, 8), verse: 28)
        let reopened = ReaderModel()
        #expect(reopened.location == ChapterRef(.romans, 8))
    }

    @Test func movingToAnotherChapterClearsTheSelection() throws {
        let model = try asvModel()
        model.show(ChapterRef(.john, 3))
        model.toggle(VerseRef(.john, 3, 16).key)
        #expect(model.selection == [VerseRef(.john, 3, 16).key])
        model.toggle(VerseRef(.john, 3, 16).key)
        #expect(model.selection.isEmpty)
        model.toggle(VerseRef(.john, 3, 16).key)
        model.show(ChapterRef(.john, 3), verse: 18)   // same chapter: kept
        #expect(!model.selection.isEmpty)
        model.next()
        #expect(model.selection.isEmpty)
    }

    @Test func selectionBecomesContiguousRanges() throws {
        let model = try asvModel()
        model.show(ChapterRef(.john, 3))
        for verse in [16, 17, 18, 21] { model.toggle(VerseRef(.john, 3, verse).key) }
        #expect(model.selectedRanges == [VerseRange(VerseRef(.john, 3, 16), VerseRef(.john, 3, 18)),
                                         VerseRange(VerseRef(.john, 3, 21))])
    }

    // MARK: Recent chapters

    @Test func recentChaptersAreMostRecentFirstDeduplicatedAndCappedAtTwelve() throws {
        let model = try asvModel()
        model.show(ChapterRef(.genesis, 1))
        for chapter in 1...14 { model.show(ChapterRef(.psalms, chapter)) }
        #expect(model.recent.count == 12)
        #expect(model.recent.first == ChapterRef(.psalms, 13), "the chapter left most recently comes first")
        #expect(!model.recent.contains(ChapterRef(.genesis, 1)), "the oldest falls off")

        model.show(ChapterRef(.psalms, 5))
        #expect(model.recent.first == ChapterRef(.psalms, 14))
        model.show(ChapterRef(.john, 1))
        #expect(model.recent.first == ChapterRef(.psalms, 5))
        #expect(model.recent.filter { $0 == ChapterRef(.psalms, 5) }.count == 1)

        // Persisted as verse keys, and read back by the next model.
        #expect(ReaderModel().recent == model.recent)
    }

    // MARK: Recent searches

    @Test func recentSearchesAreTrimmedDeduplicatedAndCapped() throws {
        let model = try asvModel()
        model.clearRecentSearches()
        model.rememberSearch("  grace  ")
        model.rememberSearch("a")                 // too short to be a search
        model.rememberSearch("   ")
        #expect(model.recentSearches == ["grace"])

        model.rememberSearch("Peace")
        model.rememberSearch("GRÂCE")             // same words, case and accents aside: moves up, not duplicated
        #expect(model.recentSearches == ["GRÂCE", "Peace"])

        for i in 0..<15 { model.rememberSearch("search \(i)") }
        #expect(model.recentSearches.count == 12)
        #expect(model.recentSearches.first == "search 14")

        model.forgetSearch("search 14")
        #expect(model.recentSearches.first == "search 13")
        #expect(UserDefaults.standard.stringArray(forKey: "recentSearches") == model.recentSearches)

        model.clearRecentSearches()
        #expect(model.recentSearches.isEmpty)
        #expect(ReaderModel().recentSearches.isEmpty)
    }

    // MARK: Quotation

    @Test func aQuotationCarriesTheReferenceAndTranslation() throws {
        let model = try asvModel()
        let quote = model.quotation(for: [VerseRange(VerseRef(.john, 3, 16))])
        #expect(quote.hasPrefix("For God so loved the world"))
        #expect(quote.contains("— John 3:16 (ASV)"))

        let two = model.quotation(for: [VerseRange(VerseRef(.john, 3, 16), VerseRef(.john, 3, 17))])
        #expect(two.hasPrefix("16 For God so loved the world"))
        #expect(two.contains(" 17 "))
        #expect(two.contains("— John 3:16–17 (ASV)"))
        #expect(model.verseCount(in: [VerseRange(VerseRef(.john, 3, 16), VerseRef(.john, 3, 17))]) == 2)
    }

    /// The ASV is public domain: nothing is refused, and its rights say so.
    @Test func publicDomainTextMayAlwaysBeQuoted() throws {
        let model = try asvModel()
        let wholeBook = VerseRange(VerseRef(.jude, 1, 1), VerseRef(.jude, 1, 25))
        #expect(model.mayQuote([wholeBook]))
        #expect(model.quotationRefusal(for: [wholeBook]) == nil)
        #expect(model.translationAbbreviation == "ASV")
    }

    // MARK: Links (scripturealone://, urn:osis, share links)

    func open(_ string: String, in model: ReaderModel) throws {
        let url = try #require(URL(string: string))
        let link = try #require(AppLink(url: url), "\(string) is not a link")
        model.open(link)
    }

    @Test func aVerseLinkOpensTheChapterAndSelectsTheVerses() throws {
        let model = try asvModel()
        try open("scripturealone://open?ref=45008001-45008004", in: model)
        #expect(model.location == ChapterRef(.romans, 8))
        #expect(model.selection == Set((1...4).map { VerseRef(.romans, 8, $0).key }))
        #expect(model.scrollTarget == VerseRef(.romans, 8, 1).key, "verse 1 is brought up too")
    }

    @Test func anOSISLinkSelectsItsVerse() throws {
        let model = try asvModel()
        try open("scripturealone://passage/urn:osis:John.3.16", in: model)
        #expect(model.location == ChapterRef(.john, 3))
        #expect(model.selection == [VerseRef(.john, 3, 16).key])
    }

    @Test func aWholeChapterLinkOpensWithoutSelecting() throws {
        let model = try asvModel()
        model.show(ChapterRef(.john, 3))
        model.toggle(VerseRef(.john, 3, 16).key)
        try open("scripturealone://open?ref=Ps.23", in: model)
        #expect(model.location == ChapterRef(.psalms, 23))
        #expect(model.selection.isEmpty)
    }

    @Test func aTypedReferenceLinkOpensItsRange() throws {
        let model = try asvModel()
        try open("scripturealone://passage/Rom%208:28-30", in: model)
        #expect(model.location == ChapterRef(.romans, 8))
        #expect(model.selection == Set((28...30).map { VerseRef(.romans, 8, $0).key }))
    }

    @Test func aShareLinkOpensWhatItQuotes() throws {
        let model = try asvModel()
        let ranges = [VerseRange(VerseRef(.john, 14, 5), VerseRef(.john, 14, 6))]
        let payload = ShareLinkPayload(ranges: ranges, reference: "John 14:5–6", translation: "ASV",
                                       passage: SharePassageText(verses: try model.source!.verses(in: ranges[0])))
        model.open(.share(payload))
        #expect(model.location == ChapterRef(.john, 14))
        #expect(model.selection == [VerseRef(.john, 14, 5).key, VerseRef(.john, 14, 6).key])
    }

    @Test func linksThatAreNotPassagesLeaveTheReaderWhereItIs() throws {
        let model = try asvModel()
        model.show(ChapterRef(.john, 1))
        try open("scripturealone://search?q=love", in: model)
        try open("scripturealone://notes", in: model)
        #expect(model.location == ChapterRef(.john, 1))
        #expect(AppLink(url: URL(string: "scripturealone://somewhere")!) == nil)
    }

    @Test func continueReadingGoesBackToTheSavedPosition() throws {
        let model = try asvModel()
        model.show(ChapterRef(.isaiah, 40), verse: 31)
        model.show(ChapterRef(.genesis, 1))
        UserDefaults.standard.set(VerseRef(.isaiah, 40, 31).key, forKey: "position")
        NSUbiquitousKeyValueStore.default.set(VerseRef(.isaiah, 40, 31).key, forKey: "position")
        model.continueReading()
        #expect(model.location == ChapterRef(.isaiah, 40))
        #expect(model.scrollTarget == VerseRef(.isaiah, 40, 31).key)
    }

    @Test func verseKeysWalkAcrossAChapterBoundary() throws {
        let model = try asvModel()
        let source = try #require(model.source)
        let keys = ReaderModel.verseKeys(in: VerseRange(VerseRef(.john, 3, 35), VerseRef(.john, 4, 2)), store: source)
        #expect(keys == [VerseRef(.john, 3, 35).key, VerseRef(.john, 3, 36).key, VerseRef(.john, 4, 1).key, VerseRef(.john, 4, 2).key])
    }

}
