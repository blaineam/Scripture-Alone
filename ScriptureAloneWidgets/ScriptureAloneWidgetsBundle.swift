import SwiftUI
import WidgetKit

/// Home Screen, Lock Screen and Mac desktop widgets. They read the bundled Verse of the Day
/// list and the snapshot the app writes to the App Group — never the Bible databases or the
/// SwiftData store.
@main
struct ScriptureAloneWidgetsBundle: WidgetBundle {
    var body: some Widget {
        VerseOfDayWidget()
        FavoritesWidget()
    }
}
