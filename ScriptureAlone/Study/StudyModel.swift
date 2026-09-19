import Foundation
import Observation
import ScriptureAloneCore

/// Study mode's state for one window: whether it's on, which verse the panel is showing,
/// and the trail of cross-reference jumps so the reader can find their way back.
///
/// Study mode doesn't change what a tap does — a tap still selects the verse for highlighting
/// and notes — the panel simply follows the most recently tapped verse.
@Observable
final class StudyModel {
    var isOn = false
    var tab: StudyTab = .crossReferences
    /// The verse the panel is showing.
    private(set) var verse: VerseRef?
    /// Verses to return to, most recent last.
    private(set) var history: [VerseRef] = []

    @ObservationIgnored private var loadedStore: StudyStore?
    @ObservationIgnored private var loadFailed = false

    /// The bundled study database, opened on first use.
    var store: StudyStore? {
        if let loadedStore { return loadedStore }
        guard !loadFailed else { return nil }
        guard let url = Bundle.main.url(forResource: "Study", withExtension: "sqlite"),
              let store = try? StudyStore(url: url) else {
            loadFailed = true
            return nil
        }
        loadedStore = store
        return store
    }

    /// A tap in the text while study mode is on.
    func follow(_ verseKey: Int) {
        guard let ref = VerseRef(key: verseKey), ref != verse else { return }
        verse = ref
    }

    /// Opens a cross reference: remembers where we were, moves the reader and the panel there.
    func jump(to range: VerseRange, reader: ReaderModel) {
        if let verse, verse != range.start { history.append(verse) }
        history = Array(history.suffix(30))
        verse = range.start
        reader.go(to: range.start)
    }

    /// Returns to the verse before the last jump.
    func back(reader: ReaderModel) {
        guard let previous = history.popLast() else { return }
        verse = previous
        reader.go(to: previous)
    }

    func turnOn(selection: Set<Int>) {
        isOn = true
        // Pick up a verse the reader already had selected.
        if let key = selection.max() { follow(key) }
    }
}
