import XCTest

/// The verse-image designer, reached from the selection's Share menu.
final class ShareTests: ScriptureAloneUITestCase {
    func testShareImageDesigner() {
        launch(["-uiTestSelect", "43:3:16"])
        let bar = app.otherElements["selection.bar"]
        assertExists(bar)
        bar.buttons["Share"].firstMatch.tap()
        app.buttons["Share Image…"].tap()
        let title = app.navigationBars["Share Image"]
        assertExists(title)
        assertExists(app.otherElements.matching(NSPredicate(format: "label BEGINSWITH %@", "Preview: John 3:16")).firstMatch,
                     "the card preview doesn't name John 3:16")
        let shape = app.segmentedControls["share.shape"]
        assertExists(shape)
        shape.buttons["Wide"].tap()
        XCTAssertTrue(shape.buttons["Wide"].isSelected)
        let night = app.buttons["share.style.night"]
        XCTAssertFalse(night.isSelected)
        night.tap()
        XCTAssertTrue(night.isSelected)
        title.buttons["Done"].tap()
        waitForDisappearance(title)
    }

    /// The primary flow: pick a ready-made style, open Customize for the shadow, text color and
    /// background, then export through the share sheet. Each look is attached for review.
    func testPickingAStyleCustomizingAndExporting() {
        launch(["-uiTestSelect", "43:3:16-17"])
        let bar = app.otherElements["selection.bar"]
        assertExists(bar)
        bar.buttons["Share"].firstMatch.tap()
        app.buttons["Share Image…"].tap()
        let title = app.navigationBars["Share Image"]
        assertExists(title)
        let preview = app.otherElements["share.preview"]
        assertExists(preview)

        for id in ["watercolor", "glow", "bokeh", "lattice", "grain", "parchment"] {
            let style = app.buttons["share.style.\(id)"]
            assertExists(style)
            style.tap()
            XCTAssertTrue(style.isSelected, "\(id) isn't selected")
            XCTAssertTrue(preview.label.hasPrefix("Preview: John 3:16"), preview.label)
            attach("share-style-\(id)")
        }

        // One tap deeper: shadow, text color and background.
        button("share.customize").tap()
        let shadow = app.segmentedControls["share.shadow"]
        assertExists(shadow)
        shadow.buttons["Strong"].tap()
        XCTAssertTrue(shadow.buttons["Strong"].isSelected)
        button("share.ink.automatic").tap()
        XCTAssertTrue(button("share.ink.automatic").isSelected)
        let navy = app.buttons["Navy"]
        if navy.waitForExistence(timeout: 3) {
            navy.tap()
            XCTAssertTrue(navy.isSelected)
        }
        let mist = button("share.background.mist")
        assertExists(mist)
        mist.tap()
        XCTAssertTrue(mist.isSelected)
        XCTAssertTrue(navy.isSelected, "a picked text color should survive a new background")
        attach("share-customized")

        // Export: the share sheet opens with the image.
        let export = button("share.export")
        XCTAssertTrue(export.waitForExistence(timeout: 15), "the image never finished rendering")
        export.tap()
        let sheet = app.otherElements["ActivityListView"].firstMatch
        let shown = sheet.waitForExistence(timeout: 10) || app.navigationBars["UIActivityContentView"].waitForExistence(timeout: 2)
        XCTAssertTrue(shown, "the share sheet didn't open")
        attach("share-sheet")
        // The system share sheet stays up: tearing the app down closes it (dismissing it by
        // accessibility query has hung the test runner). Remembering the design is a unit test
        // (`ShareStyleTests.theDesignIsRememberedBetweenLaunches`).
    }

    private func attach(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

/// Listen, with UI-test mode's silent speaker: the bar and the transport, no audio.
final class ListenTests: ScriptureAloneUITestCase {
    func testToolbarListenShowsTheBarAndPauses() {
        launch()
        let listen = button("reader.listen")
        XCTAssertEqual(listen.label, "Listen")
        listen.tap()
        wait(for: listen, label: "Pause Listening")
        let pause = app.buttons["Pause"]
        assertExists(pause, "the Now Playing bar didn't appear")
        pause.tap()
        assertExists(app.buttons["Play"])
        wait(for: listen, label: "Listen")
        app.buttons["Play"].tap()
        wait(for: listen, label: "Pause Listening")
        app.buttons["Next Verse"].tap()
        app.buttons["Stop Listening"].tap()
        waitForDisappearance(app.buttons["Pause"])
        XCTAssertEqual(listen.label, "Listen")
    }

    func testListeningToTheSelection() {
        launch(["-uiTestSelect", "43:3:16-17"])
        let bar = app.otherElements["selection.bar"]
        assertExists(bar)
        bar.buttons["Listen"].tap()
        assertExists(app.buttons["Pause"])
        assertExists(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "John 3:16")).firstMatch)
        app.buttons["Stop Listening"].tap()
        waitForDisappearance(app.buttons["Pause"])
    }
}

/// A sermon slide read on device into a new note (`-uiTestSlide`: the simulator has no camera).
final class SlideTests: ScriptureAloneUITestCase {
    func testSampleSlideOpensTheReview() {
        launch(["-uiTestSlide"])
        NotesScreen(app: app).open(from: self)
        let review = app.navigationBars["New Note"]
        assertExists(review, timeout: 20)
        assertExists(app.images["Photo of the slide"])
        // On-device text recognition runs to the end. What it reads differs between simulators (the
        // iPhone finds the slide's passages, the iPad has found none), so only the finished review is
        // asserted: the reading message gone, and passages listed or the note that none were found.
        waitForDisappearance(app.staticTexts["Reading the slide…"], timeout: 30)
        let chip = app.buttons["slideReview.passage"].firstMatch
        let none = app.staticTexts["No passages were found on the slide."]
        // The slide's heading becomes the note's title.
        let title = app.textFields["Note title"]
        assertExists(title)
        XCTAssertFalse((title.value as? String ?? "").isEmpty, "no title was read from the slide")
        let form = app.collectionViews.containing(NSPredicate(format: "label == %@", "Photo of the slide")).firstMatch
        for _ in 0..<6 where !(chip.exists || none.exists) { form.swipeUp() }
        XCTAssertTrue(chip.exists || none.exists, "the review shows neither passages nor that there were none")
        review.buttons["Cancel"].tap()
        waitForDisappearance(review)
    }
}

/// Family sharing through FamilyDebug's stand-ins: CloudKit is never touched.
final class FamilyTests: ScriptureAloneUITestCase {
    func testOwnerSeesTheirFamily() {
        launch(["-fakeFamilyOwner", "-familyScene", "owner"])
        assertExists(app.navigationBars["Share with Family"])
        // What family will see: the demo library's counts.
        assertExists(app.staticTexts["Notes, 2"])
        XCTAssertTrue(app.staticTexts["Favorites, 4"].exists)
        let anna = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Anna Miller")).firstMatch
        scrollTo(anna)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Sam Miller")).firstMatch.exists)
        let stop = app.buttons["Stop Sharing…"]
        scrollTo(stop)
        stop.tap()
        assertExists(app.buttons["Stop Sharing"], "stopping isn't confirmed first")
    }

    func testASharedBibleIsListed() {
        launch(["-fakeSharedBible", "live", "-familyScene", "library"])
        assertExists(app.navigationBars["Keepsake & Export"])
        assertExists(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Dad")).firstMatch)
    }

    func testReadingASharedBible() {
        launch(["-fakeSharedBible", "live", "-familyScene", "reader"])
        assertExists(app.buttons["Return to My Bible"])
        assertExists(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Reading")).firstMatch)
        app.buttons["Return to My Bible"].tap()
        waitForDisappearance(app.buttons["Return to My Bible"])
    }
}
