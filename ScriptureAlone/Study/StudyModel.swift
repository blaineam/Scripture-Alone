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

    /// The study database, opened on first use.
    ///
    /// Commentary is an on-demand resource — 43.6 MB that many readers never open — so the file
    /// may not be on the device yet, and the system may purge it later. Absence is therefore not a
    /// failure: it is "not downloaded", and `prepare()` fixes it. Only a file that exists and
    /// won't open is a real failure worth latching.
    var store: StudyStore? {
        if let loadedStore { return loadedStore }
        guard !loadFailed else { return nil }
        guard let url = OnDemandLibrary.shared.url(of: .commentary) else { return nil }
        guard let store = try? StudyStore(url: url) else {
            loadFailed = true
            return nil
        }
        loadedStore = store
        return store
    }

    /// Downloads the commentary pack if it isn't on the device, then opens it.
    @discardableResult
    func prepareStore() async -> Bool {
        if store != nil { return true }
        guard await OnDemandLibrary.shared.ensure(.commentary) else { return false }
        return store != nil
    }

    /// What to tell the reader while they wait, or nil when there is nothing to wait for.
    var downloadState: OnDemandLibrary.State? {
        guard loadedStore == nil else { return nil }
        return OnDemandLibrary.shared.state(of: .commentary)
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
