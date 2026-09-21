package com.blainemiller.scripturealone

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import com.blainemiller.scripturealone.data.BundledTranslations
import com.blainemiller.scripturealone.data.Canon
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.ui.reader.ReaderScreen
import com.blainemiller.scripturealone.ui.reader.ReaderTheme
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel

class MainActivity : ComponentActivity() {

    private val reader: ReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openFromIntent(intent)
        setContent {
            val dark = reader.theme.palette(isSystemInDarkTheme()).isDark
            // Status and navigation bar icons follow the reader's page, not the system theme: a
            // Sepia page on a dark-mode phone still needs dark icons.
            LaunchedEffect(dark) {
                val bars = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            ReaderScreen(reader)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromIntent(intent)
    }

    /**
     * Opens a chapter named by launch extras — `book`, `chapter` (ints), `translation` and `theme`
     * (names). The development hook for going straight to a passage from `adb shell am start`; the
     * `scripturealone://` deep links come later and will route here too. Anything out of range is
     * ignored rather than trusted.
     */
    private fun openFromIntent(intent: Intent?) {
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
