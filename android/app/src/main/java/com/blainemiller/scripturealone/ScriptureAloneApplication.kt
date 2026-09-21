package com.blainemiller.scripturealone

import android.app.Application
import com.blainemiller.scripturealone.data.translations.TranslationLibrary

/**
 * Loads the reader's added translations — imported files, and the online ones their keys unlock —
 * before any activity starts, so a reader who left off in an imported or online translation is
 * restored to it rather than to the default.
 */
class ScriptureAloneApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TranslationLibrary.attach(this)
    }
}
