import XCTest

/// Study following John 3:16: cross references, commentary, context (maps, timeline, charts).
final class StudyTests: ScriptureAloneUITestCase {
    private var tabs: XCUIElement { app.segmentedControls["study.tabs"] }

    private func openStudy(_ select: String = "43:3:16", chapter: String = "43:3") {
        launch(["-uiTestSelect", select], chapter: chapter)
        assertExists(app.otherElements["selection.bar"])
        button("reader.studyButton").tap()
        assertExists(tabs, "Study didn't open")
    }

    func testCrossReferencesForJohn316() {
        openStudy()
        assertExists(app.staticTexts["John 3:16"])
        XCTAssertTrue(tabs.buttons["References"].isSelected)
        let rows = app.buttons.matching(identifier: "study.crossReference")
        assertExists(rows.firstMatch, "no cross references")
        let target = rows.firstMatch.label
        XCTAssertFalse(target.isEmpty)
        rows.firstMatch.tap()
        // Study follows the reference it jumped to: a "Back to John 3:16" button.
        assertExists(app.buttons["Back to John 3:16"])
    }

    func testCommentaryTab() {
        openStudy()
        tabs.buttons["Commentary"].tap()
        XCTAssertTrue(tabs.buttons["Commentary"].isSelected)
        // Either the downloaded commentary or the offer to download it — never an empty pane.
        let commentary = app.buttons["Where these commentators stand"]
        let download = app.buttons["Download"]
        XCTAssertTrue(commentary.waitForExistence(timeout: 10) || download.exists, "the commentary pane is empty")
        if commentary.exists {
            commentary.tap()
            assertExists(app.navigationBars["The commentators"])
        }
    }

    func testContextMapTimelineAndCharts() {
        openStudy("44:13:4", chapter: "44:13")
        tabs.buttons["Context"].tap()
        let context = app.segmentedControls["study.context.tabs"]
        assertExists(context)
        context.buttons["Timeline"].tap()
        XCTAssertTrue(context.buttons["Timeline"].isSelected)
        context.buttons["Charts"].tap()
        // Acts 13 suggests Paul's journeys first.
        let journeys = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Paul's Missionary Journeys")).firstMatch
        assertExists(journeys)
        journeys.tap()
        let chart = app.navigationBars["Paul's Missionary Journeys"]
        assertExists(chart)
        chart.buttons["BackButton"].tap()
        waitForDisappearance(chart)
        context.buttons["Map"].tap()
        XCTAssertTrue(context.buttons["Map"].isSelected)
        context.buttons["Overview"].tap()
        XCTAssertTrue(context.buttons["Overview"].isSelected)
    }

    func testAboutStudyResources() {
        openStudy()
        app.buttons["About Study Resources"].tap()
        assertExists(app.navigationBars["Study Resources"])
    }

    func testStudyTurnsOff() {
        openStudy()
        button("reader.studyButton").tap()
        waitForDisappearance(tabs)
    }

    func testOnIPadNotesAndStudyShareTheColumn() throws {
        try XCTSkipUnless(isPad, "iPad only: the inspector column")
        openStudy()
        button("reader.notesButton").tap()
        assertExists(app.segmentedControls["notes.scope"])
        waitForDisappearance(tabs)
        button("reader.studyButton").tap()
        assertExists(tabs)
        waitForDisappearance(app.segmentedControls["notes.scope"])
    }
}

/// Original Language: the word-by-word view of one verse.
final class InterlinearTests: ScriptureAloneUITestCase {
    func testWordByWordForJohn316() {
        launch(["-uiTestSelect", "43:3:16"])
        let bar = app.otherElements["selection.bar"]
        assertExists(bar)
        bar.buttons["Original Language"].tap()
        let title = app.navigationBars["John 3:16"]
        assertExists(title)
        assertExists(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Berean Standard Bible")).firstMatch)
        title.buttons["Done"].tap()
        waitForDisappearance(title)
    }

    func testNotOfferedForSeveralVerses() {
        launch(["-uiTestSelect", "43:3:16-17"])
        let bar = app.otherElements["selection.bar"]
        assertExists(bar)
        XCTAssertFalse(bar.buttons["Original Language"].exists)
    }
}
