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

    /// An iPhone in Silent mode (`-uiTestSilenced`: the Simulator has no switch) starts Listen muted,
    /// says so in the bar, and Unmute plays it aloud for the rest of the session.
    func testSilentModeStartsMutedUntilUnmute() throws {
        try XCTSkipIf(isPad, "iPhone only: an iPad has no ring/silent switch")
        launch(["-uiTestSilenced"])
        button("reader.listen").tap()
        let unmute = app.buttons["listen.unmute"]
        assertExists(unmute, "Silent mode didn't show the muted state")
        XCTAssertEqual(unmute.label, "Unmute")
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "listen-silent-mode"
        attachment.lifetime = .keepAlways
        add(attachment)
        unmute.tap()
        waitForDisappearance(unmute)
        // Pausing and playing again keeps the session aloud.
        app.buttons["Pause"].tap()
        app.buttons["Play"].tap()
        XCTAssertFalse(unmute.exists, "Unmute didn't hold for the session")
        app.buttons["Stop Listening"].tap()
        waitForDisappearance(app.buttons["Pause"])
        // A new session reads the switch again.
        button("reader.listen").tap()
        assertExists(app.buttons["listen.unmute"], "a new session forgot Silent mode")
        app.buttons["Stop Listening"].tap()
    }

    /// Minimize hides the bar behind a small pill while reading goes on: the pill names the verse
    /// being read and plays/pauses; tapping it brings the full bar back, where Stop ends listening.
    func testMinimizeKeepsListeningThenExpandAndStop() {
        launch()
        let listen = button("reader.listen")
        listen.tap()
        wait(for: listen, label: "Pause Listening")
        assertExists(app.buttons["Stop Listening"], "the Now Playing bar didn't appear")
        // Move on a few verses, so the pill has to follow the reading, not the chapter's start.
        for _ in 0..<3 { app.buttons["Next Verse"].tap() }
        attach("listen-expanded")

        let minimize = button("listen.minimize")
        assertExists(minimize)
        XCTAssertEqual(minimize.label, "Minimize player")
        minimize.tap()

        // The pill is up; the bar (and its Stop) is gone; reading is still playing.
        let pill = app.otherElements["listen.pill"]
        assertExists(pill, "minimizing didn't show the pill")
        let expand = button("listen.expand")
        assertExists(expand)
        XCTAssertEqual(expand.label, "Expand player")
        wait(for: expand, valueContaining: "John 3:")
        XCTAssertNotEqual(expand.value as? String, "John 3:1", "the pill doesn't follow the verse being read")
        waitForDisappearance(app.buttons["Stop Listening"])
        let playPause = button("listen.pill.playPause")
        XCTAssertEqual(playPause.label, "Pause", "minimizing stopped the reading")
        XCTAssertEqual(listen.label, "Pause Listening", "the session ended when the player was minimized")
        attach("listen-minimized")

        // Pause and play from the pill; the toolbar follows.
        playPause.tap()
        wait(for: playPause, label: "Play")
        wait(for: listen, label: "Listen")
        playPause.tap()
        wait(for: playPause, label: "Pause")
        wait(for: listen, label: "Pause Listening")

        // The toolbar's Listen is play/pause for the minimized session, not a new one.
        listen.tap()
        wait(for: playPause, label: "Play")
        listen.tap()
        wait(for: playPause, label: "Pause")

        if !isPad {
            // Landscape: the columns, with the pill clear in the corner.
            addTeardownBlock { @MainActor in XCUIDevice.shared.orientation = .portrait }
            rotate(to: .landscapeLeft)
            assertExists(pill)
            expand.tap()
            assertExists(app.buttons["Stop Listening"])
            // A verse on: the columns turn to the spread that holds it.
            app.buttons["Next Verse"].tap()
            attach("listen-expanded-landscape")
            button("listen.minimize").tap()
            assertExists(pill)
            wait(for: expand, valueContaining: "John 3:")
            attach("listen-minimized-landscape")
            rotate(to: .portrait)
        }

        expand.tap()
        assertExists(app.buttons["Stop Listening"], "the pill didn't open the bar")
        waitForDisappearance(pill)
        XCTAssertEqual(app.buttons["Pause"].label, "Pause", "expanding interrupted the reading")
        app.buttons["Stop Listening"].tap()
        waitForDisappearance(app.buttons["Pause"])
        XCTAssertFalse(pill.exists)
        XCTAssertEqual(listen.label, "Listen")

        // A new session opens the full bar, never the pill.
        listen.tap()
        assertExists(app.buttons["Stop Listening"])
        XCTAssertFalse(pill.exists, "a new session opened minimized")
        app.buttons["Stop Listening"].tap()
        waitForDisappearance(app.buttons["Pause"])
    }

    /// A swipe down on the bar minimizes it too.
    func testSwipingTheBarDownMinimizes() {
        launch()
        button("reader.listen").tap()
        let bar = app.otherElements["listen.bar"]
        assertExists(bar)
        bar.swipeDown()
        assertExists(app.otherElements["listen.pill"], "swiping down didn't minimize the bar")
        XCTAssertEqual(button("listen.pill.playPause").label, "Pause")
        button("listen.expand").tap()
        app.buttons["Stop Listening"].tap()
        waitForDisappearance(app.buttons["Pause"])
    }

    /// Silent mode's mute shows on the pill, which unmutes on its own.
    func testThePillUnmutesASilentSession() throws {
        try XCTSkipIf(isPad, "iPhone only: an iPad has no ring/silent switch")
        launch(["-uiTestSilenced"])
        button("reader.listen").tap()
        assertExists(app.buttons["listen.unmute"])
        button("listen.minimize").tap()
        let unmute = button("listen.pill.unmute")
        assertExists(unmute, "the pill doesn't show that Silent mode muted Listen")
        XCTAssertEqual(unmute.label, "Unmute")
        attach("listen-minimized-muted")
        unmute.tap()
        waitForDisappearance(unmute)
        XCTAssertEqual(button("listen.pill.playPause").label, "Pause")
        button("listen.expand").tap()
        XCTAssertFalse(app.buttons["listen.unmute"].exists, "Unmute on the pill didn't hold")
        app.buttons["Stop Listening"].tap()
        waitForDisappearance(app.buttons["Pause"])
    }

    private func attach(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Turns the device and waits out the rotation: snapshot frames are the final ones, so a tap
    /// while the page is still turning lands where the element will be, not where it is.
    private func rotate(to orientation: UIDeviceOrientation) {
        XCUIDevice.shared.orientation = orientation
        let wide = orientation.isLandscape
        XCTAssertTrue(waitUntil {
            let frame = app.windows.firstMatch.frame
            return (frame.width > frame.height) == wide
        }, "the app never turned")
        RunLoop.current.run(until: Date().addingTimeInterval(1.0))
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
