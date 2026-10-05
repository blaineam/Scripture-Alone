import XCTest

/// The Notes panel with the demo library: notes, highlights, favorites, filters, search, the editor,
/// export and Keepsake & Export.
final class NotesTests: ScriptureAloneUITestCase {
    private lazy var notes = NotesScreen(app: app)

    private func row(titled title: String) -> XCUIElement {
        notes.noteRows.matching(NSPredicate(format: "label CONTAINS %@", title)).firstMatch
    }

    /// One launch: the demo notes, then This Chapter narrowing them to John 3.
    func testDemoNotesAndThisChapter() {
        launch()
        notes.open(from: self)
        XCTAssertTrue(notes.scope.buttons["Notes"].isSelected)
        assertExists(row(titled: "Sunday sermon: No condemnation"))
        assertExists(row(titled: "Evening sermon: Born of the Spirit"))
        XCTAssertEqual(notes.noteRows.count, 2)
        notes.place.buttons["This Chapter"].tap()
        XCTAssertTrue(notes.place.buttons["This Chapter"].isSelected)
        waitForDisappearance(row(titled: "Sunday sermon"))
        XCTAssertTrue(row(titled: "Evening sermon").exists)
        XCTAssertEqual(notes.noteRows.count, 1)
    }

    func testHighlightsAndFavoritesScopes() {
        launch()
        notes.open(from: self)
        notes.show("Highlights")
        let highlights = app.buttons.matching(identifier: "highlights.row")
        assertExists(highlights.firstMatch)
        // In Bible order, each with its colour.
        XCTAssertTrue(highlights.firstMatch.label.hasPrefix("green, Psalms 23:1"), highlights.firstMatch.label)
        XCTAssertTrue(highlights.matching(NSPredicate(format: "label BEGINSWITH %@", "yellow, John 3:16–17")).firstMatch.exists)
        notes.show("Favorites")
        let favorites = app.buttons.matching(identifier: "favorites.row")
        assertExists(favorites.firstMatch)
        XCTAssertEqual(favorites.count, 4)
        XCTAssertTrue(favorites.matching(NSPredicate(format: "label BEGINSWITH %@", "Psalms 23:1")).firstMatch.exists)
    }

    func testSearchFindsANote() {
        launch()
        notes.open(from: self)
        assertExists(row(titled: "Sunday sermon"))
        let field = app.searchFields.firstMatch
        if !field.exists { app.swipeDown() }
        assertExists(field)
        field.tap()
        field.typeText("condemnation")
        waitForDisappearance(row(titled: "Evening sermon"))
        XCTAssertTrue(row(titled: "Sunday sermon").exists)
        XCTAssertEqual(notes.noteRows.count, 1)
    }

    func testNewNoteIsListedUnderItsTitle() {
        launch()
        notes.open(from: self)
        app.buttons["notes.new"].tap()
        let title = app.textFields["noteEditor.title"]
        assertExists(title)
        // A new note covers the chapter on screen.
        XCTAssertTrue(app.buttons["John 3"].exists || app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "John 3")).firstMatch.exists)
        title.tap()
        title.typeText("Midweek study")
        app.navigationBars.buttons["BackButton"].firstMatch.tap()
        assertExists(row(titled: "Midweek study"))
        XCTAssertEqual(notes.noteRows.count, 3)
    }

    func testDeletingANoteFromTheEditor() {
        launch()
        notes.open(from: self)
        row(titled: "Sunday sermon").tap()
        let more = app.buttons["noteEditor.more"]
        assertExists(more)
        XCTAssertEqual(app.textFields["noteEditor.title"].value as? String, "Sunday sermon: No condemnation")
        more.tap()
        app.buttons["Delete Note"].tap()
        // Confirm.
        let confirm = app.buttons.matching(NSPredicate(format: "label == %@", "Delete Note")).element(boundBy: 0)
        assertExists(confirm)
        confirm.tap()
        assertExists(notes.scope)
        waitForDisappearance(row(titled: "Sunday sermon"))
        XCTAssertEqual(notes.noteRows.count, 1)
    }

    func testAddingAPassageToANote() {
        launch()
        notes.open(from: self)
        row(titled: "Evening sermon").tap()
        let field = app.textFields["noteEditor.addPassage"]
        assertExists(field)
        XCTAssertTrue(app.buttons["Numbers 21:4–9"].exists)
        field.tap()
        field.typeText("Rom 5:8\n")
        assertExists(app.buttons["Romans 5:8"], "the typed passage wasn't added")
    }

    func testEmptyLibraryShowsNoNotesYet() {
        launch(["-noSeed"])
        notes.open(from: self)
        assertExists(app.staticTexts["No Notes Yet"])
        XCTAssertEqual(notes.noteRows.count, 0)
        notes.show("Favorites")
        assertExists(app.staticTexts["No Favorites Yet"])
        notes.show("Highlights")
        assertExists(app.staticTexts["No Highlights Yet"])
    }

    func testExportNotesSheet() {
        launch()
        notes.open(from: self)
        app.buttons["notes.export"].tap()
        let all = app.buttons["Export All Notes…"]
        assertExists(all)
        all.tap()
        // The sheet, not the system share sheet beyond it.
        assertExists(app.navigationBars.matching(NSPredicate(format: "identifier CONTAINS %@", "Export")).firstMatch,
                     "the export sheet didn't open")
    }

    func testKeepsakeAndExport() {
        launch()
        notes.open(from: self)
        app.buttons["notes.export"].tap()
        app.buttons["Keepsake & Export…"].tap()
        let bar = app.navigationBars["Keepsake & Export"]
        assertExists(bar)
        app.buttons["Create a Keepsake"].tap()
        assertExists(app.navigationBars["Create a Keepsake"])
        assertExists(app.textFields["Your name, as family knows you"].firstMatch.exists
                     ? app.textFields["Your name, as family knows you"].firstMatch
                     : app.textFields.firstMatch, "the keepsake form has no name field")
        app.navigationBars["Create a Keepsake"].buttons.element(boundBy: 0).tap()
        assertExists(bar)
        app.buttons["Bring Notes From Another App…"].tap()
        let bring = app.navigationBars["Bring Your Notes"]
        assertExists(bring)
        app.buttons["Paste Notes From Any App…"].tap()
        let paste = app.navigationBars["Paste Notes"]
        assertExists(paste)
        paste.buttons["Cancel"].tap()
        waitForDisappearance(paste)
        assertExists(bring)
    }
}
