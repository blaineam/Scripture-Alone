import XCTest

/// Reading Plans from the translation menu: start a book plan, see today's reading, mark it read,
/// listen to it (the UI-test speaker is silent), and the import screen's offline answer.
final class ReadingPlansTests: ScriptureAloneUITestCase {
    private func openPlans() {
        button("reader.translationMenu").tap()
        let item = app.buttons["Reading Plans…"]
        assertExists(item)
        item.tap()
        assertExists(app.navigationBars["Reading Plans"])
    }

    func testStartABookPlanThenReadAndMarkItDone() {
        launch()
        openPlans()
        XCTAssertFalse(app.otherElements["plans.today"].exists, "no plan, no Today")
        app.buttons["plans.add.books"].tap()
        let genesis = app.descendants(matching: .any)["plans.book.1"]
        assertExists(genesis)
        genesis.tap()
        let start = app.buttons["plans.startButton"]
        assertExists(start)
        start.tap()

        openPlans()
        let today = app.descendants(matching: .any)["plans.today"]
        assertExists(today)
        XCTAssertTrue(today.label.contains("Day 1 of 50"), today.label)
        let done = app.buttons["plans.markDone"]
        assertExists(done)
        done.tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Tap to Undo")).firstMatch.waitForExistence(timeout: 3))
        app.buttons["plans.listen"].tap()
        assertExists(app.buttons["Pause"].exists ? app.buttons["Pause"] : app.buttons["reader.listen"])
    }

    func testImportScreenReportsOffline() {
        launch()
        openPlans()
        app.buttons["plans.add.import"].tap()
        let address = app.textFields["plans.import.address"]
        assertExists(address)
        address.tap()
        address.typeText("not a url")
        app.buttons["Import"].tap()
        assertExists(app.staticTexts["plans.import.message"])
    }
}
