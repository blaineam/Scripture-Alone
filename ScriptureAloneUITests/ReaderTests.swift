import XCTest

/// The reader: the chapter, paging, the translation menu, auto-scroll.
final class ReaderTests: ScriptureAloneUITestCase {
    /// One launch: John 3 in the BSB, paging either way, then auto-scroll on and off.
    func testJohn3PagingAndAutoScroll() {
        launch()
        waitForPassage("John 3")
        XCTAssertTrue(chapterText.contains("For God so loved the world"), "John 3:16 isn't in the chapter text")
        XCTAssertTrue(chapterText.contains("Nicodemus"))
        XCTAssertEqual(button("reader.translationMenu").value as? String, "BSB")
        // Nothing is selected yet.
        XCTAssertFalse(app.otherElements["selection.bar"].exists)
        // In columns (iPad) the arrow turns John 3's spreads first.
        turnToLastSpread()
        button("reader.nextChapter").tap()
        waitForPassage("John 4")
        wait(for: readerText, valueContaining: "Samaritan")
        button("reader.previousChapter").tap()
        waitForPassage("John 3")
        wait(for: readerText, valueContaining: "Nicodemus")
        let control = button("reader.autoScroll")
        XCTAssertEqual(control.label, "Auto-Scroll")
        control.tap()
        wait(for: control, label: "Pause Scrolling")
        control.tap()
        wait(for: control, label: "Auto-Scroll")
    }

    /// In the column reader the next arrow says "Next Page" while there is a spread to turn to:
    /// turns them all, so the arrow goes to the next chapter (or, at the Bible's end, nowhere).
    func turnToLastSpread() {
        let next = button("reader.nextChapter")
        var turns = 0
        while next.label == "Next Page", turns < 30 {
            next.tap()
            turns += 1
            _ = waitUntil(timeout: 0.8) { false }
        }
    }

    func testGenesisOneHasNoPreviousChapter() {
        launch(chapter: "1:1")
        waitForPassage("Genesis 1")
        XCTAssertTrue(chapterText.contains("In the beginning God created the heavens and the earth"))
        XCTAssertFalse(button("reader.previousChapter").isEnabled)
        XCTAssertTrue(button("reader.nextChapter").isEnabled)
    }

    func testRevelation22HasNoNextChapter() {
        launch(chapter: "66:22")
        waitForPassage("Revelation 22")
        // In columns (iPad) the arrow turns the chapter's spreads; past the last there is nothing.
        turnToLastSpread()
        waitForPassage("Revelation 22")
        XCTAssertFalse(button("reader.nextChapter").isEnabled)
        XCTAssertTrue(button("reader.previousChapter").isEnabled)
    }

    func testSwitchingTranslationToKJV() {
        launch()
        button("reader.translationMenu").tap()
        let kjv = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "KJV")).firstMatch
        assertExists(kjv)
        kjv.tap()
        wait(for: readerText, valueContaining: "only begotten")
        XCTAssertEqual(button("reader.translationMenu").value as? String, "KJV")
        waitForPassage("John 3")
    }

    /// A phone held sideways reads a chapter in two columns. They run from just below the top bar to
    /// just above the bottom one — a short screen can't spare a band of empty page — and stay clear of
    /// both bars' buttons. (TextKit never splits a line between two columns, so no line is clipped.)
    func testLandscapeColumnsFillTheHeight() throws {
        try XCTSkipIf(isPad, "iPhone only: an iPad keeps its columns in either orientation")
        XCUIDevice.shared.orientation = .landscapeLeft
        addTeardownBlock { @MainActor in XCUIDevice.shared.orientation = .portrait }
        launch(chapter: "49:2")
        waitForPassage("Ephesians 2")
        // Any current iPhone sideways holds two columns at the default text size (`ReaderColumns.count`),
        // a Pro as well as a Pro Max; the test used to skip phones under 420 points tall sideways.
        let columns = app.textViews.matching(identifier: "reader.text")
        XCTAssertTrue(waitUntil { columns.count >= 2 }, "landscape didn't set the chapter in columns")
        let window = app.windows.firstMatch.frame
        let shown = (0..<columns.count).map { columns.element(boundBy: $0).frame }
            .filter { $0.minX >= window.minX && $0.maxX <= window.maxX }
        XCTAssertEqual(shown.count, 2, "two columns should be on screen: \(shown)")
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "landscape-ephesians-2"
        attachment.lifetime = .keepAlways
        add(attachment)
        guard let column = shown.first else { return }
        let top = [passageButton, button("reader.notesButton"), button("reader.appearanceButton")].map(\.frame.maxY).max() ?? 0
        let bottom = min(button("reader.previousChapter").frame.minY, button("reader.listen").frame.minY)
        XCTAssertGreaterThanOrEqual(column.minY, top, "the text starts under the top bar's buttons")
        XCTAssertLessThanOrEqual(column.minY - top, 28, "a band of empty page between the top bar and the text")
        XCTAssertLessThanOrEqual(column.maxY, bottom, "the text runs under the bottom bar's buttons")
        XCTAssertLessThanOrEqual(bottom - column.maxY, 28, "a band of empty page above the bottom bar")
    }

    /// The Now Playing bar floats over the columns: the page keeps both columns at their full height
    /// while it is up, rather than giving up room (or falling back to one column) for it.
    func testLandscapeColumnsStayWhileListening() throws {
        XCUIDevice.shared.orientation = .landscapeLeft
        addTeardownBlock { @MainActor in XCUIDevice.shared.orientation = .portrait }
        launch(chapter: "49:2")
        waitForPassage("Ephesians 2")
        let columns = app.textViews.matching(identifier: "reader.text")
        XCTAssertTrue(waitUntil { columns.count >= 2 }, "landscape didn't set the chapter in columns")
        let before = columns.element(boundBy: 0).frame
        button("reader.listen").tap()
        assertExists(app.buttons["Pause"], "the Now Playing bar didn't appear")
        XCTAssertTrue(waitUntil { columns.count >= 2 }, "listening sent the chapter back to one column")
        XCTAssertEqual(columns.element(boundBy: 0).frame.height, before.height, accuracy: 1,
                       "the columns gave up room to the Now Playing bar")
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "landscape-listening"
        attachment.lifetime = .keepAlways
        add(attachment)
        app.buttons["Stop Listening"].tap()
    }

    /// In the column reader the bottom bar's arrows turn spreads ("Next Page"), and change the chapter
    /// only past the last spread; upright, in the scrolling reader, they change the chapter.
    func testArrowsTurnSpreadsInColumns() throws {
        try XCTSkipIf(isPad, "iPhone run: the iPad suite covers its own columns")
        XCUIDevice.shared.orientation = .landscapeLeft
        addTeardownBlock { @MainActor in XCUIDevice.shared.orientation = .portrait }
        launch(chapter: "49:2")
        waitForPassage("Ephesians 2")
        let columns = app.textViews.matching(identifier: "reader.text")
        XCTAssertTrue(waitUntil { columns.count >= 2 }, "landscape didn't set the chapter in columns")
        let previous = button("reader.previousChapter"), next = button("reader.nextChapter")
        wait(for: next, label: "Next Page")
        wait(for: previous, label: "Previous Chapter")
        next.tap()
        waitForPassage("Ephesians 2")
        wait(for: previous, label: "Previous Page")
        previous.tap()
        wait(for: previous, label: "Previous Chapter")
        // On to the last spread, then one more: the next chapter.
        var turns = 0
        while next.label == "Next Page", turns < 30 {
            next.tap()
            turns += 1
            _ = waitUntil(timeout: 0.8) { false }
        }
        XCTAssertGreaterThanOrEqual(turns, 1, "Ephesians 2 should take more than one spread")
        wait(for: next, label: "Next Chapter")
        next.tap()
        waitForPassage("Ephesians 3")
        // Upright: one scrolling column, and the arrows change the chapter.
        XCUIDevice.shared.orientation = .portrait
        wait(for: next, label: "Next Chapter")
        next.tap()
        waitForPassage("Ephesians 4")
    }
}
