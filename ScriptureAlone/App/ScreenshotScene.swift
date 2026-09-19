#if DEBUG
import Foundation
import ScriptureAloneCore

/// DEBUG-only App Store screenshot scenes. `Tools/capture_screenshots.sh` launches the app with
/// `-screenshotScene <name>` (plus `-inMemoryStore -seedDemoLibrary` for the demo library) and the
/// reader stages that screen through the same state the buttons set: no taps, no personal data.
/// Release builds compile none of this.
enum ScreenshotScene: String, CaseIterable {
    /// John 3 in the Light theme: red letters, the demo highlight on 3:16–17 and a note marker.
    case reader
    /// The passage picker: recent chapters and the book grid.
    case jump
    /// Study mode following John 3:16: ranked cross references.
    case study
    /// Maps & Timeline for Acts 13: Paul's first journey on the offline map.
    case maps
    /// A sermon slide imported as a photo, read on device into a new note.
    case sermonNotes = "sermon-notes"
    /// John 14 read aloud with a system voice: the Listen bar and the spoken verse.
    case listen
    /// The verse image designer on John 3:16.
    case share
    /// John 1 with the reading options open (the rig picks the theme).
    case themes

    static let current: ScreenshotScene? = {
        let arguments = ProcessInfo.processInfo.arguments
        guard let flag = arguments.firstIndex(of: "-screenshotScene"), arguments.indices.contains(flag + 1) else {
            return nil
        }
        return ScreenshotScene(rawValue: arguments[flag + 1])
    }()

    /// The sample slide the rig copies into the app's temporary directory. It goes through
    /// `SlideCapture.accept(data:)`, the same decode path a photo picked from Photos takes.
    static var sampleSlideData: Data? {
        try? Data(contentsOf: FileManager.default.temporaryDirectory.appending(path: "sample-slide.jpg"))
    }

    static func key(_ book: BookID, _ chapter: Int, _ verse: Int) -> Int {
        VerseRef(book, chapter, verse).key
    }
}
#endif
