import XCTest

/// Selecting verses (`-uiTestSelect`, the path a tap takes) and the selection bar's actions.
final class SelectionTests: ScriptureAloneUITestCase {
    private var bar: XCUIElement { app.otherElements["selection.bar"] }

    private func launchSelecting(_ verses: String = "43:3:16") {
        launch(["-uiTestSelect", verses])
        assertExists(bar)
    }

    func testSelectionBarNamesThePassageAndClears() {
        launchSelecting("43:3:16-17")
        assertExists(bar.staticTexts["John 3:16–17"])
        bar.buttons["Clear Selection"].tap()
        waitForDisappearance(bar)
    }

    func testHighlightingShowsInHighlights() {
        launchSelecting()
        bar.buttons["selection.highlight.blue"].tap()
        waitForDisappearance(bar)
        let notes = NotesScreen(app: app)
        notes.open(from: self)
        notes.show("Highlights")
        let row = app.buttons.matching(identifier: "highlights.row")
            .matching(NSPredicate(format: "label CONTAINS[c] %@ AND label CONTAINS %@", "blue", "John 3:16")).firstMatch
        assertExists(row, "the blue John 3:16 highlight isn't listed")
    }

    func testRemovingAHighlight() {
        // John 3:17 is yellow in the demo library.
        launchSelecting("43:3:17")
        bar.buttons["Remove Highlight"].tap()
        waitForDisappearance(bar)
        let notes = NotesScreen(app: app)
        notes.open(from: self)
        notes.show("Highlights")
        assertExists(app.buttons.matching(identifier: "highlights.row").firstMatch)
        let rows = app.buttons.matching(identifier: "highlights.row")
        XCTAssertFalse(rows.matching(NSPredicate(format: "label CONTAINS %@", "John 3:17")).firstMatch.exists)
        XCTAssertTrue(rows.matching(NSPredicate(format: "label CONTAINS %@", "John 3:16")).firstMatch.exists)
    }

    func testFavoritingAddsToFavorites() {
        launchSelecting()
        let favorite = bar.buttons["Add to Favorites"]
        assertExists(favorite)
        favorite.tap()
        assertExists(bar.buttons["Remove from Favorites"])
        bar.buttons["Clear Selection"].tap()
        let notes = NotesScreen(app: app)
        notes.open(from: self)
        notes.show("Favorites")
        let rows = app.buttons.matching(identifier: "favorites.row")
        assertExists(rows.matching(NSPredicate(format: "label BEGINSWITH %@", "John 3:16")).firstMatch)
        XCTAssertEqual(rows.count, 5, "four demo favorites and John 3:16")
    }

    func testCopyConfirms() {
        launchSelecting()
        let copy = bar.buttons["selection.copy"]
        XCTAssertTrue(copy.isEnabled)
        copy.tap()
        // The checkmark shows for a moment (1.2 s), so it's read straight away rather than polled.
        XCTAssertEqual(copy.value as? String, "Copied")
    }

    func testAddNoteOpensTheEditorOnThePassage() {
        launchSelecting()
        bar.buttons["Add Note"].tap()
        assertExists(app.textFields["noteEditor.title"])
        assertExists(app.buttons["John 3:16"], "the new note isn't anchored to John 3:16")
        waitForDisappearance(bar)
    }
}

/// The Notes panel: a sheet on iPhone, beside the text on iPad.
@MainActor
struct NotesScreen {
    let app: XCUIApplication

    var scope: XCUIElement { app.segmentedControls["notes.scope"] }
    var place: XCUIElement { app.segmentedControls["notes.place"] }

    func open(from test: ScriptureAloneUITestCase) {
        app.buttons["reader.notesButton"].tap()
        test.assertExists(scope, "the Notes panel didn't open")
    }

    func show(_ name: String) {
        scope.buttons[name].tap()
        XCTAssertTrue(scope.buttons[name].isSelected)
    }

    var noteRows: XCUIElementQuery { app.buttons.matching(identifier: "notes.row") }
}
