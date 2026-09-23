package com.blainemiller.scripturealone.ui.shortcuts

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import com.blainemiller.scripturealone.data.share.AppCommand
import com.blainemiller.scripturealone.data.share.AppLink

/**
 * The static app shortcuts (res/xml/shortcuts.xml) — Verse of the Day, Continue Reading, Search, New
 * Note and Favorites, the iOS app's App Shortcuts that make sense from a launcher. They are plain deep
 * links; this tells the system when one was used (from the launcher, the Assistant, or the same link
 * from anywhere else), which is what it ranks shortcut suggestions by.
 */
object AppShortcuts {
    /** The shortcut id a URL stands for, if it is one of the static shortcuts. */
    fun shortcutId(url: String): String? = when (val command = AppCommand.parse(url)) {
        AppCommand.VerseOfTheDay -> AppCommand.FEATURE_TODAY
        AppCommand.ContinueReading -> AppCommand.FEATURE_CONTINUE
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
}
