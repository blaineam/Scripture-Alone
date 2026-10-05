import XCTest

/// The Apple Watch app with `-UITestMode`: the demo library (4 favorites, 2 notes, highlights) and
/// the BSB edition installed as if the phone had sent it — never the licensed translation.
@MainActor
final class WatchUITests: XCTestCase {
    var app: XCUIApplication!

    override func setUp() async throws {
        continueAfterFailure = false
    }

    override func tearDown() async throws {
        app?.terminate()
        app = nil
    }

    /// A failure prints what was on screen, so a red run says why without a rerun.
    nonisolated override func record(_ issue: XCTIssue) {
        // XCTest records issues on the main thread.
        nonisolated(unsafe) let test = self
        let tree = MainActor.assumeIsolated { test.app?.debugDescription }
        if let tree { print("UI-HIERARCHY-BEGIN \(name)\n\(tree)\nUI-HIERARCHY-END") }
        super.record(issue)
    }

    @discardableResult
    private func launch(_ extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-UITestMode", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"] + extra
        app.launch()
        self.app = app
        return app
    }

    private func assertExists(_ element: XCUIElement, timeout: TimeInterval = 10, _ message: String = "",
                              file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(element.waitForExistence(timeout: timeout), message.isEmpty ? "\(element) never appeared" : message,
                      file: file, line: line)
    }

    private func row(_ prefix: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", prefix)).firstMatch
    }

    /// Scrolls the list until `element` can be tapped (watch screens are short).
    /// Brings a lazily built row into the hierarchy: swipes on until it exists, looking further down
    /// the list first and then back up. A tap then scrolls it into view by itself.
    private func reveal(_ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        if element.waitForExistence(timeout: 3) { return }
        for _ in 0..<8 where !element.exists { app.swipeUp() }
        for _ in 0..<8 where !element.exists { app.swipeDown() }
        XCTAssertTrue(element.exists, "\(element) isn't in the list", file: file, line: line)
    }

    /// Home rows are built as the list scrolls; scrolls back to the top first.
    private func homeRow(_ prefix: String) -> XCUIElement {
        let element = row(prefix)
        reveal(element)
        return element
    }

    func testHomeShowsTheLibraryAndTranslation() {
        launch()
        assertExists(app.navigationBars["Scripture Alone"])
        XCTAssertEqual(homeRow("Favorites").label, "Favorites, 4", "four demo favorites")
        app.swipeUp()
        XCTAssertEqual(homeRow("Notes").label, "Notes, 2", "two demo notes")
        let highlights = homeRow("Highlights").label
        XCTAssertTrue(highlights.hasPrefix("Highlights, ") && highlights.contains(where: \.isNumber), highlights)
        XCTAssertEqual(homeRow("Translation").label, "Translation, BSB", "the watch reads the BSB")
    }

    func testVerseOfTheDayOpensTheVerse() {
        launch()
        let card = homeRow("Verse of the Day")
        // "Verse of the Day, <reference>, <text>": both are there.
        XCTAssertGreaterThanOrEqual(card.label.components(separatedBy: ", ").count, 3, card.label)
        card.tap()
        reveal(app.buttons["Speak"])
    }

    func testFavoritesListAndVerse() {
        launch()
        homeRow("Favorites").tap()
        assertExists(app.navigationBars["Favorites"])
        let psalm = row("Psalms 23:1")
        reveal(psalm)
        XCTAssertTrue(psalm.label.contains("shepherd"), "Psalm 23:1 without its text: \(psalm.label)")
        psalm.tap()
        assertExists(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "The LORD is my shepherd")).firstMatch)
        // Favorited: the heart offers to remove it.
        reveal(app.buttons["Remove from Favorites"])
        let attribution = app.staticTexts["Berean Standard Bible"]
        reveal(attribution)
    }

    func testNotesListAndNote() {
        launch()
        homeRow("Notes").tap()
        assertExists(app.navigationBars["Notes"])
        let note = row("Sunday sermon")
        reveal(note)
        reveal(row("Evening sermon"))
        note.tap()
        assertExists(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Life in the Spirit")).firstMatch)
        reveal(row("Romans 8:1"))
    }

    func testHighlightsList() {
        launch()
        homeRow("Highlights").tap()
        assertExists(app.navigationBars["Highlights"])
        // In Bible order, with the verse text in the BSB.
        let psalm = row("Psalms 23:1")
        reveal(psalm)
        XCTAssertTrue(psalm.label.contains("The LORD is my shepherd"), psalm.label)
        psalm.tap()
        assertExists(app.staticTexts["Psalms 23:1"])
    }

    func testBooksChapterAndVerse() {
        launch()
        homeRow("Read").tap()
        assertExists(app.navigationBars["Books"])
        let john = app.buttons["John"]
        reveal(john)
        john.tap()
        let three = app.buttons["Chapter 3"]
        reveal(three)
        three.tap()
        let verse = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "For God so loved")).firstMatch
        reveal(verse)
        verse.tap()
        assertExists(app.staticTexts["John 3:16"])
    }

    func testTranslationPickerChecksTheBSB() {
        launch()
        homeRow("Translation").tap()
        assertExists(app.navigationBars["Translation"])
        let bsb = row("BSB")
        reveal(bsb)
        XCTAssertTrue(bsb.label.contains("Selected") || bsb.images["Selected"].exists
                      || bsb.descendants(matching: .any)["Selected"].exists,
                      "the BSB isn't the chosen translation: \(bsb.label)")
    }

    func testLinkOpensTheVerse() {
        launch(["-watchRoute", "scripturealone://open?ref=43003016-43003017"])
        assertExists(app.staticTexts["John 3:16–17"])
        assertExists(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "For God so loved")).firstMatch)
    }

    func testRouteToFavorites() {
        launch(["-watchRoute", "favorites"])
        assertExists(app.navigationBars["Favorites"])
        reveal(row("Romans 8:38"))
    }
}
