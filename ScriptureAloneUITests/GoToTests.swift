import XCTest

/// Go To: the book grid, typed references, word search, topics and the crisis card.
final class GoToTests: ScriptureAloneUITestCase {
    private var search: XCUIElement { app.textFields["goto.search"] }
    private var goToBar: XCUIElement { app.navigationBars["Go To"] }

    private func openPicker() {
        passageButton.tap()
        assertExists(goToBar, "Go To didn't open")
        assertExists(search)
    }

    private func type(_ text: String) {
        search.tap()
        typeAndSettle(text, into: search)
    }

    func testBookGridOpensAChapter() {
        launch()
        openPicker()
        XCTAssertTrue(app.staticTexts["Old Testament"].exists)
        XCTAssertTrue(app.staticTexts["New Testament"].exists)
        let genesis = app.buttons["goto.book.1"]
        assertExists(genesis)
        XCTAssertEqual(genesis.label, "Genesis", "VoiceOver should hear the book's name once")
        genesis.tap()
        assertExists(app.navigationBars["Genesis"])
        app.buttons["Genesis chapter 50"].tap()
        waitForPassage("Genesis 50")
        waitForDisappearance(goToBar)
        wait(for: readerText, valueContaining: "Joseph")
    }

    func testTypedBookNameSuggestsTheBook() {
        launch()
        openPicker()
        type("Romans")
        let romans = app.buttons["goto.book.45"]
        assertExists(romans)
        XCTAssertEqual(romans.label, "Romans")
        romans.tap()
        assertExists(app.navigationBars["Romans"])
        app.buttons["Romans chapter 8"].tap()
        waitForPassage("Romans 8")
        waitForDisappearance(goToBar)
    }

    func testTypedReferenceGoesThere() {
        launch()
        openPicker()
        type("Rom 8:28")
        assertExists(app.staticTexts["Romans 8:28"], "the typed reference isn't offered")
        type("\n")
        waitForPassage("Romans 8")
        waitForDisappearance(goToBar)
        XCTAssertTrue(chapterText.contains("works all things together for the good"))
    }

    func testWordSearchListsVersesAndOpensOne() {
        launch()
        openPicker()
        type("Zacchaeus")
        let count = app.staticTexts.matching(NSPredicate(format: "label ENDSWITH %@", " verses")).firstMatch
        assertExists(count, "no search results")
        let hit = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Luke 19:")).firstMatch
        assertExists(hit)
        hit.tap()
        waitForPassage("Luke 19")
        waitForDisappearance(goToBar)
    }

    func testWordsNamingATopicOfferTheLifeTheme() {
        launch()
        openPicker()
        type("anxious")
        let card = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Anxiety")).firstMatch
        assertExists(card, "the Anxiety topic isn't offered")
        card.tap()
        assertExists(app.navigationBars.matching(NSPredicate(format: "identifier BEGINSWITH %@", "Anxiety")).firstMatch)
        let passage = app.buttons["topics.passage"].firstMatch
        assertExists(passage)
        passage.tap()
        waitForDisappearance(goToBar)
        let moved = XCTNSPredicateExpectation(predicate: NSPredicate(format: "NOT (label ENDSWITH %@)", " John 3"), object: passageButton)
        XCTAssertEqual(XCTWaiter().wait(for: [moved], timeout: 10), .completed, "the reader didn't move to the passage")
    }

    func testTopicsDirectory() {
        launch()
        openPicker()
        app.buttons["See All"].tap()
        assertExists(app.navigationBars["Topics"])
        let theme = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Anxiety")).firstMatch
        assertExists(theme)
        theme.tap()
        assertExists(app.buttons["topics.passage"].firstMatch)
        // Back to the directory, then Go To's own page.
        app.navigationBars.buttons.element(boundBy: 0).tap()
        assertExists(app.navigationBars["Topics"])
    }

    func testACrisisSearchShowsHelp() {
        launch()
        openPicker()
        type("suicide")
        assertExists(app.otherElements["topics.crisisCard"])
        XCTAssertTrue(app.staticTexts["You’re Not Alone"].exists)
        // No topic is offered over the card.
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Death")).firstMatch.exists)
    }

    func testCloseDismisses() {
        launch()
        openPicker()
        goToBar.buttons["Close"].tap()
        waitForDisappearance(goToBar)
        waitForPassage("John 3")
    }
}
