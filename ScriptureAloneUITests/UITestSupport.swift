import XCTest
import UIKit

/// Shared launching and waiting for the iPhone/iPad UI tests.
///
/// Every launch passes `-UITestMode` (ScriptureAlone/App/UITestMode.swift, DEBUG only): an in-memory
/// store seeded with the demo library, a fresh install's settings, no iCloud, no network, a silent
/// Listen, animations off. Always the Berean Standard Bible, in English: never a licensed text.
@MainActor
class ScriptureAloneUITestCase: XCTestCase {
    var app: XCUIApplication!

    override func setUp() async throws {
        continueAfterFailure = false
    }

    override func tearDown() async throws {
        app?.terminate()
        app = nil
    }

    /// A failure prints what was on screen, so a red run says why without a rerun.
    nonisolated override func record(_ issue: XCTIssue) {
        // XCTest records issues on the main thread.
        nonisolated(unsafe) let test = self
        let tree = MainActor.assumeIsolated { test.app?.debugDescription }
        if let tree { print("UI-HIERARCHY-BEGIN \(name)\n\(tree)\nUI-HIERARCHY-END") }
        super.record(issue)
    }

    /// Launches on John 3 in the BSB (or `chapter`, "book:chapter") and waits for its text.
    @discardableResult
    func launch(_ extra: [String] = [], chapter: String = "43:3", waitForText: Bool = true) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-UITestMode", "-translation", "BSB", "-openChapter", chapter,
                               "-AppleLanguages", "(en)", "-AppleLocale", "en_US"] + extra
        app.launch()
        self.app = app
        if waitForText {
            XCTAssertTrue(readerText.waitForExistence(timeout: 20), "the chapter text never appeared")
        }
        return app
    }

    var isPad: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    var readerText: XCUIElement { app.textViews["reader.text"].firstMatch }

    /// The whole chapter as the text view holds it (every column holds the same storage).
    var chapterText: String { (readerText.value as? String) ?? "" }

    func button(_ id: String) -> XCUIElement { app.buttons[id].firstMatch }

    /// The passage title in the toolbar: "Go to passage, currently John 3".
    var passageButton: XCUIElement { button("reader.passageButton") }

    func waitForPassage(_ display: String, file: StaticString = #filePath, line: UInt = #line) {
        wait(for: passageButton, label: "Go to passage, currently \(display)", file: file, line: line)
    }

    /// Waits for an element's label to become `label`.
    func wait(for element: XCUIElement, label: String, timeout: TimeInterval = 10,
              file: StaticString = #filePath, line: UInt = #line) {
        let predicate = NSPredicate(format: "label == %@", label)
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: element)
        let result = XCTWaiter().wait(for: [expectation], timeout: timeout)
        XCTAssertEqual(result, .completed, "label stayed “\(element.exists ? element.label : "<missing>")”, wanted “\(label)”",
                       file: file, line: line)
    }

    /// Waits for an element's value to contain `text`.
    func wait(for element: XCUIElement, valueContaining text: String, timeout: TimeInterval = 10,
              file: StaticString = #filePath, line: UInt = #line) {
        let predicate = NSPredicate(format: "value CONTAINS %@", text)
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: element)
        let result = XCTWaiter().wait(for: [expectation], timeout: timeout)
        XCTAssertEqual(result, .completed, "value never contained “\(text)”", file: file, line: line)
    }

    /// Waits for an element to go away.
    func waitForDisappearance(_ element: XCUIElement, timeout: TimeInterval = 10,
                              file: StaticString = #filePath, line: UInt = #line) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: element)
        XCTAssertEqual(XCTWaiter().wait(for: [expectation], timeout: timeout), .completed,
                       "\(element) is still on screen", file: file, line: line)
    }

    /// Swipes a list or form (built lazily: rows below the fold don't exist yet) until the element is on
    /// screen. The frontmost list is the last one in the hierarchy.
    func scrollTo(_ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        let lists = app.collectionViews
        XCTAssertTrue(lists.firstMatch.waitForExistence(timeout: 10), "no list to scroll", file: file, line: line)
        let list = lists.element(boundBy: max(0, lists.count - 1))
        for _ in 0..<14 where !(element.exists && element.isHittable) { list.swipeUp() }
        XCTAssertTrue(element.exists && element.isHittable, "\(element) is off screen", file: file, line: line)
    }

    /// Polls `condition` on the main run loop (no sleeping thread) until it holds or time runs out.
    func waitUntil(timeout: TimeInterval = 10, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        return condition()
    }

    func assertExists(_ element: XCUIElement, timeout: TimeInterval = 10, _ message: String = "",
                      file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(element.waitForExistence(timeout: timeout), message.isEmpty ? "\(element) never appeared" : message,
                      file: file, line: line)
    }
}
