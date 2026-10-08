#if DEBUG
import Foundation
import ScriptureAloneCore
#if os(iOS)
import UIKit
#endif

/// DEBUG-only: `-UITestMode` makes a launch deterministic for the XCUITest suites
/// (ScriptureAloneUITests on iPhone and iPad, ScriptureAloneWatchUITests on the watch).
/// Release builds compile none of this, and every call site is inside `#if DEBUG`.
///
/// It is an umbrella over the existing debug flags rather than a second set of them:
/// - implies `-inMemoryStore` and `-seedDemoLibrary` (`-noSeed` opts out, for the empty states);
/// - starts every launch from a fresh install's settings, so what one test changes (text size, a
///   theme, the reading position) never leaks into the next. Launch-argument values such as
///   `-translation BSB` live in the argument domain and still apply;
/// - counts as an automated run, so the User Guide's first-launch card never pops up;
/// - no iCloud key-value store, WatchConnectivity or CKSyncEngine, no network: the eBible.org
///   catalogue is a three-entry fixture and the User Guide behaves as if offline;
/// - Listen runs its transport without speaking (no voice lookup, no audio);
/// - animations off on iOS.
///
/// Hooks for what a UI test can't do reliably by touch:
/// - `-uiTestSelect 43:3:16[-17]` selects verses (book:chapter:verse[-verse]) once the chapter is
///   up, through the same path a tap on the text takes;
/// - `-uiTestSlide` sends the sample sermon slide (bundled into Debug builds) through
///   `SlideCapture.accept(data:)` when Notes opens — the simulator has no camera.
///
/// The watch installs its BSB edition (also copied into Debug builds only) as if the phone had sent
/// it, and reads it: the watch never depends on a licensed translation in a test.
nonisolated enum UITestMode {
    private static var arguments: [String] { ProcessInfo.processInfo.arguments }

    static let isOn = ProcessInfo.processInfo.arguments.contains("-UITestMode")

    /// The demo library (`DemoLibrary`), unless the test asked for an empty one.
    static var seedsDemoLibrary: Bool { isOn && !arguments.contains("-noSeed") }

    /// `-uiTestSilenced`: Listen reads the phone as being in Silent mode (the Simulator has no switch).
    static var silenced: Bool { isOn && arguments.contains("-uiTestSilenced") }

    /// `-uiTestListenAdvances`: Listen's silent speaker moves on a verse every 1.5 s while playing, as
    /// speech would, so a test can see the marker, the toolbar and the columns follow the reading.
    static var listenAdvances: Bool { isOn && arguments.contains("-uiTestListenAdvances") }

    /// The sample sermon slide goes through the photo-import path when Notes opens.
    static var importsSampleSlide: Bool { isOn && arguments.contains("-uiTestSlide") }

    private static func value(after flag: String) -> String? {
        guard let index = arguments.firstIndex(of: flag), arguments.indices.contains(index + 1) else { return nil }
        return arguments[index + 1]
    }

    /// `-uiTestSelect 43:3:16-17`: the chapter, and the verses in it to select.
    static var selection: (chapter: ChapterRef, verses: ClosedRange<Int>)? {
        guard isOn, let raw = value(after: "-uiTestSelect") else { return nil }
        let parts = raw.split(separator: ":")
        guard parts.count == 3, let bookNumber = Int(parts[0]), let book = BookID(rawValue: bookNumber),
              let chapter = Int(parts[1]) else { return nil }
        let verses = parts[2].split(separator: "-").compactMap { Int($0) }
        guard let first = verses.first, first > 0 else { return nil }
        return (ChapterRef(book, chapter), first...max(first, verses.last ?? first))
    }

    /// Once, before anything reads a setting.
    @MainActor static func prepare() {
        guard isOn else { return }
        if let id = Bundle.main.bundleIdentifier {
            UserDefaults.standard.removePersistentDomain(forName: id)
        }
        #if os(iOS)
        UIView.setAnimationsEnabled(false)
        #endif
    }

    /// The sample slide copied into Debug builds (`docs/appstore-screenshots/sample-slide.jpg`).
    static var sampleSlideData: Data? {
        Bundle.main.url(forResource: "sample-slide", withExtension: "jpg").flatMap { try? Data(contentsOf: $0) }
    }

    /// Stands in for eBible.org's catalogue: three complete English Bibles from the curated list,
    /// in the published CSV's own shape, read by the real parser.
    static let catalogCSV = """
    translationId,languageCode,languageName,languageNameInEnglish,title,shortTitle,Copyright,Redistributable,downloadable,OTbooks,NTbooks,OTverses,NTverses,textDirection,script
    engbsb,eng,English,English,Berean Standard Bible,BSB,Public Domain,True,True,39,27,23145,7957,ltr,Latin
    eng-kjv2006,eng,English,English,King James Version,KJV,Public Domain,True,True,39,27,23145,7957,ltr,Latin
    engDBY,eng,English,English,Darby Translation,Darby,Public Domain,True,True,39,27,23145,7957,ltr,Latin
    """
}
#endif
