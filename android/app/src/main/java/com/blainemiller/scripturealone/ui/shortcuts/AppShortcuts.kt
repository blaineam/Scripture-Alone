package com.blainemiller.scripturealone.ui.shortcuts

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.blainemiller.scripturealone.MainActivity
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.share.AppCommand
import com.blainemiller.scripturealone.data.share.AppLink

/**
 * The app shortcuts — the iOS app's App Shortcuts that make sense from a launcher. Static ones
 * (res/xml/shortcuts.xml): Verse of the Day, Continue Reading, Search, New Note, Favorites and Create
 * Verse Image. One dynamic one, **Listen** (`ListenToChapterIntent`), named for the chapter being read
 * ("Listen to John 3") and kept current as the reader moves ([updateListen]) — a static label could
 * only say "Listen", and the chapter is the useful part. All are plain deep links; this also tells the
 * system when one was used, which is what it ranks shortcut suggestions by.
 */
object AppShortcuts {
    /** The shortcut id a URL stands for, if it is one of the static shortcuts. */
    fun shortcutId(url: String): String? = when (val command = AppCommand.parse(url)) {
        AppCommand.VerseOfTheDay -> AppCommand.FEATURE_TODAY
        AppCommand.ContinueReading -> AppCommand.FEATURE_CONTINUE
        is AppCommand.Listen -> AppCommand.FEATURE_LISTEN.takeIf { command.passage == null }
        is AppCommand.VerseImage -> AppCommand.FEATURE_VERSE_IMAGE.takeIf { command.passage == null }
        is AppCommand.NewNote -> AppCommand.FEATURE_NEW_NOTE.takeIf { command.passage == null }
        is AppCommand.Link -> when (val link = command.link) {
            AppLink.Favorites -> AppCommand.FEATURE_FAVORITES
            is AppLink.Search -> AppCommand.FEATURE_SEARCH.takeIf { link.words.isEmpty() }
            else -> null
        }
        else -> null
    }

    fun reportUsed(context: Context, url: String) {
        val id = shortcutId(url) ?: return
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, id) }
    }

    /** The Listen shortcut's link: the chapter on screen, which is where the app reopens. */
    const val LISTEN_URL = "scripturealone://listen"

    private var listenLabel: String? = null

    /**
     * Names the Listen shortcut for [chapter] ("John 3", in the reader's language) — pushed, so it
     * replaces the last one and keeps the launcher's ranking. Cheap to call on every chapter change: an
     * unchanged label is not pushed again.
     */
    fun updateListen(context: Context, chapter: String) {
        if (chapter.isEmpty() || listenLabel == chapter) return
        listenLabel = chapter
        val app = context.applicationContext
        val shortcut = ShortcutInfoCompat.Builder(app, AppCommand.FEATURE_LISTEN)
            .setShortLabel(app.getString(R.string.shortcut_listen_short))
            .setLongLabel(app.getString(R.string.shortcut_listen_long, chapter))
            .setIcon(IconCompat.createWithResource(app, R.drawable.ic_shortcut_listen))
            .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse(LISTEN_URL)).setClass(app, MainActivity::class.java))
            // "Listen to the Bible in Scripture Alone" — the App Action, as the static shortcuts carry it.
            .addCapabilityBinding(
                "actions.intent.OPEN_APP_FEATURE", "feature",
                listOf(app.getString(R.string.assistant_listen_1), app.getString(R.string.assistant_listen_2)),
            )
            .setRank(0)
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(app, shortcut) }
    }
}
