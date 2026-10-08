import Foundation
import AppIntents
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone

/// The reading intents Siri and Shortcuts run — performed here as Shortcuts would, against the
/// bundled ASV: Verse of the Day, Get Verses, Create Verse Image (every 1.1.4 style), and the intents
/// that hand the reader a command; plus the translation query Shortcuts' pickers use.
@MainActor
struct ReadingIntentsTests {
    func asv() -> TranslationEntity { TranslationEntity(id: "ASV", name: "American Standard Version") }

    @Test func verseOfTheDayAnswersWithoutOpeningTheApp() async throws {
        _ = try await VerseOfTheDayIntent().perform()
    }

    @Test func getVersesQuotesAPassage() async throws {
        let intent = GetVersesIntent()
        intent.passage = "John 3:16-17"
        intent.translation = asv()
        _ = try await intent.perform()
    }

    @Test(arguments: ["Hezekiah 4:1", "", "the sermon on the mount"])
    func getVersesRefusesAPassageThatIsNotThere(passage: String) async {
        let intent = GetVersesIntent()
        intent.passage = passage
        intent.translation = asv()
        await #expect(throws: ScriptureIntentError.self) { _ = try await intent.perform() }
    }

    @Test func aTranslationThatIsNotHereIsRefused() async {
        let intent = GetVersesIntent()
        intent.passage = "John 3:16"
        intent.translation = TranslationEntity(id: "NOPE", name: "Nope")
        await #expect(throws: ScriptureIntentError.self) { _ = try await intent.perform() }
    }

    /// Every design Shortcuts offers is one of the designer's own styles — a template whose name
    /// matched none would silently make the reader's last design instead.
    @Test func everyVerseImageDesignIsADesignerStyle() {
        for template in VerseImageTemplate.allCases {
            #expect(ShareBackground(rawValue: template.rawValue) != nil, "\(template) is no ShareBackground")
        }
        // …and Shortcuts offers every style the designer has (1.1.4: "any of the styles").
        for style in ShareBackground.allCases {
            #expect(VerseImageTemplate(rawValue: style.rawValue) != nil, "Shortcuts can't make the \(style) style")
        }
        for shape in VerseImageShape.allCases {
            #expect(ShareAspect(rawValue: shape.rawValue) != nil, "\(shape) is no ShareAspect")
        }
    }

    @Test(arguments: [VerseImageTemplate.parchment, .night, .watercolor, .minimal])
    func createVerseImageMakesAPNG(design: VerseImageTemplate) async throws {
        let intent = CreateVerseImageIntent()
        intent.passage = "Psalm 23:1"
        intent.translation = asv()
        intent.design = design
        intent.shape = .square
        _ = try await intent.perform()
    }

    @Test func createVerseImageRefusesAPassageThatIsNotThere() async {
        let intent = CreateVerseImageIntent()
        intent.passage = "Hezekiah 4:1"
        intent.translation = asv()
        await #expect(throws: ScriptureIntentError.self) { _ = try await intent.perform() }
    }

    /// The intents that open something post a command for the reader (which may take it at once).
    @Test func openingIntentsHandTheReaderACommand() async throws {
        let center = AppCommandCenter.shared
        let open = OpenPassageIntent()
        open.passage = "Romans 8:28"
        _ = try await open.perform()
        let search = SearchBibleIntent()
        search.query = "love one another"
        _ = try await search.perform()
        _ = try await ContinueReadingIntent().perform()
        _ = try await ShowFavoritesIntent().perform()
        _ = try await ShowNotesIntent().perform()
        _ = try await OpenVerseLinkIntent(ranges: [VerseRange(VerseRef(.john, 3, 16), VerseRef(.john, 3, 16))]).perform()
        #expect(center.openedFromOutside)
        _ = center.take()
    }

    @Test func openingAPassageThatIsNotThereIsRefused() async {
        let open = OpenPassageIntent()
        open.passage = "Hezekiah 4:1"
        await #expect(throws: ScriptureIntentError.self) { _ = try await open.perform() }
    }

    @Test func theTranslationPickerFindsTheBundledBibles() async throws {
        let query = TranslationQuery()
        let suggested = try await query.suggestedEntities()
        #expect(suggested.contains { $0.id == "ASV" })
        #expect(try await query.entities(for: ["ASV", "NOPE"]).map(\.id) == ["ASV"])
        #expect(try await query.entities(matching: "american").contains { $0.id == "ASV" })
        #expect(try await query.entities(matching: "zzz-not-a-bible").isEmpty)
    }

    @Test func theAppShortcutsAreDeclared() {
        #expect(!ScriptureAloneShortcuts.appShortcuts.isEmpty)
    }
}
