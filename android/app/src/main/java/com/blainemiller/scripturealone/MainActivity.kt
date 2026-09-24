package com.blainemiller.scripturealone

import com.blainemiller.scripturealone.ui.appearance.RatingPrompt
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.blainemiller.scripturealone.data.BundledTranslations
import com.blainemiller.scripturealone.data.Canon
import com.blainemiller.scripturealone.data.assets.AssetLibrary
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.ui.listen.ListenController
import com.blainemiller.scripturealone.ui.reader.ReaderScreen
import com.blainemiller.scripturealone.ui.reader.ReaderShortcuts
import com.blainemiller.scripturealone.ui.reader.SelectionActions
import com.blainemiller.scripturealone.ui.study.StudyHost
import com.blainemiller.scripturealone.ui.reader.ReaderTheme
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import com.blainemiller.scripturealone.ui.shortcuts.AppShortcuts

class MainActivity : ComponentActivity() {

    private val reader: ReaderViewModel by viewModels()

    /** Play's "download over mobile data?" dialog, for an asset pack Play is holding for Wi-Fi. */
    private val packConfirmation = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AssetLibrary.confirmationLauncher = packConfirmation
        if (savedInstanceState == null) {
            openFromIntent(intent)
            // A cold launch, for the review gate (MillerKit's `recordLaunch`).
            RatingPrompt.recordLaunch(this)
        }
        setContent {
            // Study, Compare and Translations are hosted around the reader (a side pane or sheet).
            StudyHost(reader) { panels ->
                ReaderScreen(
                    reader,
                    actions = SelectionActions(onOriginalLanguage = { panels.openOriginal(it.key) }),
                    onStudy = panels.toggleStudy,
                    onCompare = panels.openCompare,
                    onManageTranslations = panels.openTranslations,
                    // Listen: the toolbar button, the selection bar's Listen and the Now Playing bar.
                    listen = ListenController.get(this),
                    studyOpen = panels.studyOpen(),
                    studyCovers = panels.studyCovers(),
                    onStudyBack = panels.studyBack,
                    onMaps = panels.openMaps,
                )
            }
        }
    }

    /**
     * Hardware-keyboard shortcuts (`ReaderShortcuts`): taken before the views see them, so Ctrl+] turns
     * the page even while a text field has focus. Esc, when nothing on screen used it, goes Back — but
     * only to something that handles Back (a sheet, the selection), never out of the app.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            ReaderShortcuts.match(event.keyCode, event.metaState)?.let { command ->
                if (event.repeatCount == 0 || ReaderShortcuts.repeats(command)) reader.send(command)
                return true
            }
        }
        val handled = super.dispatchKeyEvent(event)
        if (!handled && event.keyCode == KeyEvent.KEYCODE_ESCAPE && event.action == KeyEvent.ACTION_UP &&
            !event.isCanceled && onBackPressedDispatcher.hasEnabledCallbacks()
        ) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return handled
    }

    /** The shortcuts in the system's keyboard shortcuts helper (Meta+/, or Search+/ on a Chromebook). */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        val shortcuts = ReaderShortcuts.all.map { KeyboardShortcutInfo(it.command.title, it.keyCode, it.modifiers) } +
            KeyboardShortcutInfo(getString(R.string.reader_clear_selection), KeyEvent.KEYCODE_ESCAPE, 0)
        data += KeyboardShortcutGroup("Scripture Alone", shortcuts)
    }

    override fun onDestroy() {
        if (AssetLibrary.confirmationLauncher === packConfirmation) AssetLibrary.confirmationLauncher = null
        super.onDestroy()
    }

    /** singleTop: a link, shortcut or search result while the reader is already up lands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromIntent(intent)
    }

    /**
     * Opens what the intent names — iOS's `onOpenURL` and `AppCommandCenter`. Any `AppCommand` URL:
     * a passage (`scripturealone://open?ref=John.3.16`, `…/passage/Ps.23`, stored keys, a typed
     * reference in any of the app's languages), a share link (`…#s=…`), a search, a note, the notes
     * list or favorites, and the shortcuts' Verse of the Day, Continue Reading and New Note. The web
     * page's `https://wemiller.com/apps/scripture-alone/` links arrive here as App Links: the
     * `/passage/<ref>` path everywhere, and `?ref=`, `#ref=` and `#s=` from Android 15, which can
     * match a query or fragment (the `WebLinks` alias in the manifest) — so the product page itself
     * is never taken over.
     *
     * Otherwise, launch extras — `book`, `chapter` (ints), `translation` and `theme` (names), and (debug builds
     * only) the `notesInSearch` / `favoritesInSearch` switches (booleans) — the development hook for going
     * straight to a chapter from `adb shell am start`. Anything out of range is ignored rather than
     * trusted.
     */
    private fun openFromIntent(intent: Intent?) {
        // A Keepsake Bible handed to the app (Files, Gmail, Downloads) — `onOpenURL` for a
        // `.scripturelegacy` file: shown, and its passphrase asked for, before it is added.
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data != null && data.scheme in setOf("content", "file")) {
            reader.legacy.pendingFile = data
            return
        }
        intent?.dataString?.let { url ->
            // A web page's link (CATEGORY_BROWSABLE) may open anything but change nothing: a new note
            // or a favorite from one only shows the passage. Shortcuts and App Actions aren't browsable.
            val browsable = intent.hasCategory(Intent.CATEGORY_BROWSABLE)
            if (intent.action == Intent.ACTION_VIEW && reader.openLink(url, browsable)) {
                AppShortcuts.reportUsed(this, url)
                return
            }
        }
        val extras = intent?.extras ?: return
        // "Notes in search" / "Favorites in search", for checking the system-search index from adb.
        // Debug builds only: this activity is exported, and a privacy switch must not be flippable
        // by another app's intent.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            if (extras.containsKey("notesInSearch")) reader.notesInSearch = extras.getBoolean("notesInSearch")
            if (extras.containsKey("favoritesInSearch")) reader.favoritesInSearch = extras.getBoolean("favoritesInSearch")
        }
        extras.getString("theme")?.let { name ->
            ReaderTheme.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { reader.theme = it }
        }
        val book = extras.getInt("book", 0)
        val translation = extras.getString("translation")?.uppercase()?.takeIf { it in BundledTranslations.ids }
        if (book in 1..Canon.books.size) {
            val chapter = extras.getInt("chapter", 1).coerceIn(1, Canon.book(book).chapterCount)
            reader.open(ChapterRef(book, chapter), translation ?: reader.translationId)
        } else if (translation != null) {
            reader.selectTranslation(translation)
        }
    }
}
