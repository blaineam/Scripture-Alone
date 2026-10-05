import XCTest

/// The translation menu's screens: Compare, Manage Translations, the free catalogue (a fixture —
/// no network in a UI test) and Online Translations.
final class TranslationsTests: ScriptureAloneUITestCase {
    private func openMenuItem(_ title: String) {
        button("reader.translationMenu").tap()
        let item = app.buttons[title]
        assertExists(item)
        item.tap()
    }

    func testCompareShowsTwoTranslationsSideBySide() {
        launch()
        openMenuItem("Compare Translations…")
        let close = app.buttons["Close"]
        assertExists(close)
        // The BSB on the left, beside the ASV until another is chosen.
        let picker = app.buttons["BSB, ASV"]
        assertExists(picker)
        assertExists(app.staticTexts["Now there was a man of the Pharisees named Nicodemus, a leader of the Jews."])
        picker.tap()
        let kjv = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "KJV")).firstMatch
        assertExists(kjv)
        kjv.tap()
        assertExists(app.buttons["BSB, KJV"])
        assertExists(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "The same came to Jesus by night")).firstMatch,
                     "the KJV column is missing")
        XCTAssertTrue(app.staticTexts["Now there was a man of the Pharisees named Nicodemus, a leader of the Jews."].exists)
        close.tap()
        waitForDisappearance(close)
    }

    /// One launch: the included Bibles, the free catalogue (a fixture), then Online Translations.
    func testManageTranslationsCatalogueAndKeys() {
        launch()
        openMenuItem("Manage Translations…")
        assertExists(app.navigationBars["Translations"])
        for name in ["Berean Standard Bible", "King James Version", "American Standard Version"] {
            assertExists(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", name)).firstMatch, "\(name) isn't listed")
        }
        XCTAssertTrue(app.buttons["Browse Free Translations…"].exists)
        XCTAssertTrue(app.buttons["Import a File…"].exists)
        app.buttons["Browse Free Translations…"].tap()
        assertExists(app.navigationBars["Free Translations"])
        for title in ["Berean Standard Bible", "King James Version", "Darby Translation"] {
            assertExists(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", title)).firstMatch, "\(title) isn't offered")
        }
        XCTAssertFalse(app.staticTexts["Couldn't reach eBible.org"].exists)
        app.navigationBars["Free Translations"].buttons["Close"].tap()
        waitForDisappearance(app.navigationBars["Free Translations"])
        XCTAssertTrue(app.navigationBars["Translations"].exists)
        app.buttons["Online Translations…"].tap()
        let bar = app.navigationBars["Online Translations"]
        assertExists(bar)
        XCTAssertGreaterThan(app.secureTextFields.count + app.textFields.count, 0, "no key field")
        XCTAssertFalse(app.buttons["Remove Key"].exists, "a UI test has no saved keys")
        bar.buttons["Cancel"].tap()
        waitForDisappearance(bar)
        app.navigationBars["Translations"].buttons["Done"].tap()
        waitForDisappearance(app.navigationBars["Translations"])
    }

}
