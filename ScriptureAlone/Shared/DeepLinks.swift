import SwiftUI
import ScriptureAloneCore

extension View {
    /// Opens `scripturealone://open?ref=<startKey>-<endKey>` — from the widgets, and from shared
    /// links — at the passage. (The share feature registers the same scheme; if both land, keep
    /// one handler.)
    func scriptureDeepLinks() -> some View { modifier(ScriptureDeepLinks()) }
}

private struct ScriptureDeepLinks: ViewModifier {
    @Environment(ReaderModel.self) private var model

    func body(content: Content) -> some View {
        content.onOpenURL { url in
            guard let range = ScriptureLink.range(from: url) else { return }
            model.go(to: range.start)
        }
    }
}
