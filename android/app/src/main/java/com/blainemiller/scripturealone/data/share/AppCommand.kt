package com.blainemiller.scripturealone.data.share

/**
 * Something the app was asked to do from outside the reader — a link, an app shortcut, a Google
 * Assistant App Action, a search result. The Android counterpart of `AppCommand` in
 * `ScriptureAlone/App/AppCommandCenter.swift`: every [AppLink], plus the things iOS does through App
 * Intents, which on Android arrive as `scripturealone://` URLs (shortcuts.xml can only carry intents):
 *
 * | URL | |
 * |---|---|
 * | `scripturealone://today` | Verse of the Day, opened and selected |
 * | `scripturealone://continue` | Back to where the reader left off |
 * | `scripturealone://new-note[?ref=…][&title=…]` | A new note on the passage, or on the chapter on screen |
 * | `scripturealone://favorite?ref=…` / `unfavorite?ref=…` | Add or remove a favorite, and show it |
 * | `scripturealone://listen[?ref=…]` | Read aloud: the chapter being read, or the passage named — `ListenToChapterIntent` |
 * | `scripturealone://verse-image[?ref=…]` | The verse-image designer on the passage, or on today's verse — `CreateVerseImageIntent` |
 * | `scripturealone://feature?name=<id>` | An App Actions feature (OPEN_APP_FEATURE): a shortcut id below |
 * | `…open?ref=…&translation=KJV` | Any passage link, read in the named translation |
 *
 * New notes and favorites change the reader's data, and Listen starts speaking, so [needsTrust] tells
 * the activity to refuse them from a web page (an intent with CATEGORY_BROWSABLE): there they only
 * open the passage.
 */
sealed class AppCommand {
    /** A link, read in [translation] (an abbreviation, as `BundledTranslations.ids`) when one is named. */
    data class Link(val link: AppLink, val translation: String? = null) : AppCommand()
    data object VerseOfTheDay : AppCommand()
    data object ContinueReading : AppCommand()
    /** A note on [passage]'s verses, or on the chapter on screen when null. */
    data class NewNote(val passage: AppLink?, val title: String? = null) : AppCommand()
    data class Favorite(val passage: AppLink, val add: Boolean) : AppCommand()
    /** Read aloud from [passage]'s first verse, or the chapter on screen from the top when null. */
    data class Listen(val passage: AppLink?) : AppCommand()
    /** The verse-image designer on [passage], or on today's Verse of the Day when null. */
    data class VerseImage(val passage: AppLink?) : AppCommand()

    /** Whether carrying this out writes to the reader's notes or favorites. */
    val changesData: Boolean get() = this is NewNote || this is Favorite

    /** Whether a web page may not ask for this: it changes the reader's data, or starts speaking. */
    val needsTrust: Boolean get() = changesData || this is Listen

    /** What to do instead when [changesData] is refused: just show the passage. */
    val readOnly: AppCommand?
        get() = when (this) {
            is NewNote -> passage?.let { Link(it) }
            is Favorite -> Link(passage)
            is Listen -> passage?.let { Link(it) }
            else -> this
        }

    companion object {
        /** The ids of the static shortcuts (res/xml/shortcuts.xml), which are also the OPEN_APP_FEATURE names. */
        const val FEATURE_TODAY = "verse_of_the_day"
        const val FEATURE_CONTINUE = "continue_reading"
        const val FEATURE_SEARCH = "search"
        const val FEATURE_NEW_NOTE = "new_note"
        const val FEATURE_FAVORITES = "favorites"
        const val FEATURE_NOTES = "notes"
        /** The Listen shortcut (dynamic, named for the chapter — `ui/shortcuts/AppShortcuts.kt`). */
        const val FEATURE_LISTEN = "listen"
        const val FEATURE_VERSE_IMAGE = "verse_image"

        fun parse(url: String): AppCommand? {
            val parts = LinkParts.of(url) ?: return null
            val translation = parts.query("translation")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            if (parts.scheme == ShareLinkPayload.SCHEME) {
                val head = (LinkParts.decode(parts.authority) ?: parts.authority).lowercase()
                fun passage() = parts.query("ref")?.let { AppLink.reference(it) }
                when (head) {
                    "today", "votd" -> return VerseOfTheDay
                    "continue" -> return ContinueReading
                    "new-note", "newnote" -> return NewNote(passage(), parts.query("title")?.trim()?.takeIf { it.isNotEmpty() })
                    "favorite" -> return passage()?.let { Favorite(it, add = true) }
                    "unfavorite" -> return passage()?.let { Favorite(it, add = false) }
                    "listen" -> return Listen(passage())
                    "verse-image", "verseimage", "image" -> return VerseImage(passage())
                    "feature" -> return feature(parts.query("name") ?: parts.path.trim('/'))
                }
            }
            return AppLink.parse(url)?.let { Link(it, translation) }
        }

        /** An OPEN_APP_FEATURE name — a shortcut id, or what the Assistant heard, loosely. */
        fun feature(name: String): AppCommand? = when (name.trim().lowercase().replace(' ', '_').replace('-', '_')) {
            FEATURE_TODAY, "today", "votd", "daily_verse" -> VerseOfTheDay
            FEATURE_CONTINUE, "continue", "resume" -> ContinueReading
            FEATURE_NEW_NOTE, "note" -> NewNote(null)
            FEATURE_FAVORITES, "favourites" -> Link(AppLink.Favorites)
            FEATURE_NOTES -> Link(AppLink.Notes)
            FEATURE_LISTEN, "listen_to_chapter", "read_aloud" -> Listen(null)
            FEATURE_VERSE_IMAGE, "create_verse_image", "image" -> VerseImage(null)
            // Search with nothing to search for yet: the Go To sheet, whose field searches.
            FEATURE_SEARCH -> Link(AppLink.Search(""))
            else -> null
        }
    }
}
