import SwiftData

extension DataStore {
    /// The one store the app's windows, its App Intents and its Spotlight index all use. Siri and
    /// Shortcuts run intents inside the app's process, so they share this container rather than
    /// opening a second copy of the same database — two containers on one CloudKit-backed store
    /// would each think they own the sync.
    static let shared = makeContainer()
}
