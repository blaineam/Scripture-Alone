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

}
