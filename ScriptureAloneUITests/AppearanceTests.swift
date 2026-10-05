import XCTest

/// Aa: themes, accents, text size, the Show toggles, fonts, and the rows to other screens.
final class AppearanceTests: ScriptureAloneUITestCase {
    private func openAppearance() {
        button("reader.appearanceButton").tap()
        assertExists(app.buttons["Sepia theme"], "Appearance didn't open")
    }

    /// One launch: a theme, an accent, text size and a font.
    func testThemeAccentTextSizeAndFont() {
        launch()
        openAppearance()
        let sepia = app.buttons["Sepia theme"]
        XCTAssertFalse(sepia.isSelected)
        sepia.tap()
        XCTAssertTrue(sepia.isSelected)
        let accents = app.buttons.matching(NSPredicate(format: "label ENDSWITH %@", " accent"))
        XCTAssertGreaterThan(accents.count, 1)
        let other = accents.allElementsBoundByIndex.first { !$0.isSelected }!
        other.tap()
        XCTAssertTrue(other.isSelected)
        let size = app.sliders["Text Size"]
        assertExists(size)
        XCTAssertEqual(size.value as? String, "19 points")
        app.buttons["Larger"].tap()
        wait(for: size, valueContaining: "20 points")
        app.buttons["Smaller"].tap()
        app.buttons["Smaller"].tap()
        wait(for: size, valueContaining: "18 points")
        let newYork = app.buttons["appearance.font.newYork"]
        let georgia = app.buttons["appearance.font.georgia"]
        scrollTo(georgia)
        XCTAssertTrue(newYork.isSelected)
        georgia.tap()
        XCTAssertTrue(georgia.isSelected)
        XCTAssertFalse(newYork.isSelected)
    }

    func testVerseNumbersToggleChangesTheText() {
        launch()
        XCTAssertTrue(verseNumberPrecedes("For God so loved", in: chapterText), "John 3:16 is drawn without its number")
        openAppearance()
        let toggle = app.switches["appearance.verseNumbers"]
        scrollTo(toggle)
        XCTAssertEqual(toggle.value as? String, "1")
        toggle.switches.firstMatch.tap()
        wait(for: toggle, valueContaining: "0")
        XCTAssertTrue(waitUntil {
            let text = self.chapterText
            return text.contains("For God so loved") && !self.verseNumberPrecedes("For God so loved", in: text)
        }, "verse numbers are still drawn")
    }

    /// Whether a verse number comes right before `phrase` (the reader puts a space between them).
    private func verseNumberPrecedes(_ phrase: String, in text: String) -> Bool {
        guard let range = text.range(of: phrase) else { return false }
        let before = text[..<range.lowerBound].reversed().drop { $0.isWhitespace }
        return before.first?.isNumber == true
    }

    func testAboutThisTranslationNamesTheBSB() {
        launch()
        openAppearance()
        let name = app.staticTexts["Berean Standard Bible"]
        scrollTo(name)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] %@", "public domain")).firstMatch.exists,
                      "the BSB's copyright line is missing")
    }

    func testUserGuideRowShowsTheOfflineState() {
        launch()
        openAppearance()
        let row = app.buttons["User Guide"]
        scrollTo(row)
        row.tap()
        assertExists(app.navigationBars["User Guide"])
        assertExists(app.buttons["Try Again"], "a UI test has no network: the guide should offer Try Again")
        app.navigationBars["User Guide"].buttons["Done"].tap()
        waitForDisappearance(app.navigationBars["User Guide"])
    }
}

/// The User Guide's one-time card.
final class UserGuidePromptTests: ScriptureAloneUITestCase {
    func testPromptOffersReadAndSkip() {
        launch(["-userGuidePromptOnly"], waitForText: false)
        let title = app.staticTexts["Welcome to Scripture Alone"]
        assertExists(title)
        XCTAssertTrue(app.buttons["Read the Guide"].exists)
        app.buttons["Skip"].tap()
        waitForDisappearance(title)
    }

    func testReadOpensTheGuide() {
        launch(["-userGuidePromptOnly"], waitForText: false)
        app.buttons["Read the Guide"].tap()
        assertExists(app.navigationBars["User Guide"])
    }

    func testNoPromptOverTheReaderInATest() {
        launch()
        // The card would come up 1.5 s after the reader settles; a UI test run counts as automated.
        XCTAssertFalse(app.staticTexts["Welcome to Scripture Alone"].waitForExistence(timeout: 4))
    }
}
