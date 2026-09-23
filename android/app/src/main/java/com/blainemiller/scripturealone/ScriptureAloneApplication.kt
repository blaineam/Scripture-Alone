package com.blainemiller.scripturealone

import android.app.Application
import com.blainemiller.scripturealone.data.assets.AssetLibrary
import com.blainemiller.scripturealone.data.canon.BookNames
import com.blainemiller.scripturealone.data.translations.TranslationLibrary

/**
 * Loads the reader's added translations — imported files, and the online ones their keys unlock —
 * before any activity starts, so a reader who left off in an imported or online translation is
 * restored to it rather than to the default.
 */
class ScriptureAloneApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Which asset packs are on the device — before the reader decides what it can open.
        AssetLibrary.attach(this)
        TranslationLibrary.attach(this)
        // Book names in the device's language until the reader opens a Bible with a language of its
        // own — so widgets drawn in a process with no reader name books as the reader would.
        BookNames.use(java.util.Locale.getDefault().toLanguageTag())
    }
}
