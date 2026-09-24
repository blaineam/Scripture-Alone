import AppIntents

/// Siri phrases that work with no setup. An app gets ten; the rest of the intents (Open Note, Open
/// Favorite, Remove from Favorites, Is Favorite, New Note, Show Notes, Show Favorites) are in the
/// Shortcuts app's action list without a phrase of their own.
///
/// Phrases are localized in `Resources/AppShortcuts.xcstrings` — the one catalog the App Intents
/// metadata step reads them from.
nonisolated struct ScriptureAloneShortcuts: AppShortcutsProvider {
    static var shortcutTileColor: ShortcutTileColor { .orange }

    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: VerseOfTheDayIntent(),
            phrases: [
                "Get the verse of the day from \(.applicationName)",
                "What’s the verse of the day in \(.applicationName)",
                "\(.applicationName) verse of the day",
            ],
            shortTitle: LocalizedStringResource("Verse of the Day", comment: "App Shortcut tile title"),
            systemImageName: "sun.horizon"
        )
        AppShortcut(
            intent: GetVersesIntent(),
            phrases: [
                "Look up a verse in \(.applicationName)",
                "Get a Bible verse from \(.applicationName)",
            ],
            shortTitle: LocalizedStringResource("Get Verses", comment: "App Shortcut tile title"),
            systemImageName: "text.quote"
        )
        AppShortcut(
            intent: CreateVerseImageIntent(),
            phrases: [
                "Make a verse image with \(.applicationName)",
                "Create a verse image in \(.applicationName)",
            ],
            shortTitle: LocalizedStringResource("Verse Image", comment: "App Shortcut tile title"),
            systemImageName: "photo.on.rectangle"
        )
        AppShortcut(
            intent: OpenPassageIntent(),
            phrases: [
                "Open a passage in \(.applicationName)",
                "Go to a Bible passage in \(.applicationName)",
            ],
            shortTitle: LocalizedStringResource("Open Passage", comment: "App Shortcut tile title"),
            systemImageName: "book"
        )
        AppShortcut(
            intent: SearchBibleIntent(),
            phrases: [
                "Search the Bible in \(.applicationName)",
                "Search \(.applicationName)",
            ],
            shortTitle: LocalizedStringResource("Search Bible", comment: "App Shortcut tile title"),
            systemImageName: "magnifyingglass"
        )
        AppShortcut(
            intent: ContinueReadingIntent(),
            phrases: [
                "Continue reading in \(.applicationName)",
                "Pick up where I left off in \(.applicationName)",
                "Resume \(.applicationName)",
            ],
            shortTitle: LocalizedStringResource("Continue Reading", comment: "App Shortcut tile title"),
            systemImageName: "bookmark"
        )
        AppShortcut(
            intent: ListenToChapterIntent(),
            phrases: [
                "Listen to the Bible in \(.applicationName)",
                "Read the chapter aloud in \(.applicationName)",
            ],
            shortTitle: LocalizedStringResource("Listen", comment: "App Shortcut tile title"),
            systemImageName: "headphones"
        )
        AppShortcut(
            intent: CreateNoteIntent(),
            phrases: [
                "Create a note in \(.applicationName)",
                "Add a Bible note in \(.applicationName)",
            ],
            shortTitle: LocalizedStringResource("Create Note", comment: "App Shortcut tile title"),
            systemImageName: "square.and.pencil"
        )
        AppShortcut(
            intent: FindNotesIntent(),
            phrases: [
                "Find my notes in \(.applicationName)",
                "Search my notes in \(.applicationName)",
                "Show my notes in \(.applicationName)",
                "Pull up my notes in \(.applicationName)",
                "Open my notes in \(.applicationName)",
            ],
            shortTitle: LocalizedStringResource("Find Notes", comment: "App Shortcut tile title"),
            systemImageName: "note.text"
        )
        AppShortcut(
            intent: AddToFavoritesIntent(),
            phrases: [
                "Add a verse to my favorites in \(.applicationName)",
                "Favorite a verse in \(.applicationName)",
            ],
            shortTitle: LocalizedStringResource("Add to Favorites", comment: "App Shortcut tile title"),
            systemImageName: "heart"
        )
    }
}
