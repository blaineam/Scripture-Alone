import SwiftUI
import ScriptureAloneCore

/// Placeholder for the Context tab (maps, timelines and charts), which lands separately and
/// replaces this file. `verse` is the verse the Study panel is showing, when there is one.
struct StudyContextView: View {
    let chapter: ChapterRef
    let verse: VerseRef?

    var body: some View {
        ContentUnavailableView {
            Label("Maps & Timelines", systemImage: "map")
        } description: {
            Text("Maps, timelines and charts for \(chapter.display) are on the way.")
        }
    }
}
