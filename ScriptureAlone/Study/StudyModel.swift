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

    /// Cross references and the list of study sources, which ship inside the app.
    ///
    /// Split out of the commentary database (`Tools/build_study.py`, `CrossReferences.sqlite`, 3.9
    /// MB) precisely so this never waits on a download: cross references are a core part of study,
    /// and hiding them behind 44 MB of commentary would hide them from most readers. It has the same
    /// schema as the commentary database minus the commentary, so the same `StudyStore` reads it.
    @ObservationIgnored private(set) lazy var crossReferenceStore: StudyStore? =
        Bundle.main.url(forResource: "CrossReferences", withExtension: "sqlite").flatMap { try? StudyStore(url: $0) }

    /// The commentary database, opened on first use.
    ///
    /// An on-demand Background Assets pack — 42 MB that many readers never open — so the file may
    /// not be on the device yet. Absence is therefore not a failure: it is "not downloaded", and
    /// `prepareStore()` fixes it. Only a file that exists and won't open is a real failure worth
    /// latching. Cross references do not come from here; see `crossReferenceStore`.
    var store: StudyStore? {
        if let loadedStore { return loadedStore }
        guard !loadFailed else { return nil }
        guard let url = AssetLibrary.shared.url(of: .commentary) else { return nil }
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
        guard await AssetLibrary.shared.ensure(.commentary) else { return false }
        return store != nil
    }

    /// What to tell the reader while they wait, or nil when there is nothing to wait for.
    var downloadState: AssetLibrary.State? {
        guard loadedStore == nil else { return nil }
        return AssetLibrary.shared.state(of: .commentary)
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
