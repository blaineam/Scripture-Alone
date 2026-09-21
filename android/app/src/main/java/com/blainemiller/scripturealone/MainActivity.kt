package com.blainemiller.scripturealone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.blainemiller.scripturealone.data.BundledTranslations
import com.blainemiller.scripturealone.data.Canon
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.ui.listen.ListenController
import com.blainemiller.scripturealone.ui.reader.ReaderScreen
import com.blainemiller.scripturealone.ui.reader.SelectionActions
import com.blainemiller.scripturealone.ui.study.StudyHost
import com.blainemiller.scripturealone.ui.reader.ReaderTheme
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel

class MainActivity : ComponentActivity() {

    private val reader: ReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openFromIntent(intent)
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
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromIntent(intent)
    }

    /**
     * Opens what the intent names. A link — `scripturealone://open?ref=…`, or a share link
     * (`scripturealone://…#s=…`, or the web page's `https://wemiller.com/apps/scripture-alone/#s=…`
     * if one is handed to the app directly) — goes to the passage and selects it, as iOS's
     * `onOpenURL` does. The https page is deliberately *not* claimed as an App Link (Android can't
     * match the fragment, and a path match would take over the product page); the web page offers
     * "Open in Scripture Alone" through the custom scheme instead.
     *
     * Otherwise, launch extras — `book`, `chapter` (ints), `translation` and `theme` (names) — the
     * development hook for going straight to a chapter from `adb shell am start`. Anything out of
     * range is ignored rather than trusted.
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
            if (intent.action == Intent.ACTION_VIEW && reader.openLink(url)) return
        }
        val extras = intent?.extras ?: return
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
