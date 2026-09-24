package com.blainemiller.scripturealone.data.topics

import android.content.Context
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.BundledDatabase
import com.blainemiller.scripturealone.data.ChapterVerse
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.sql.BundledSqlSource
import com.blainemiller.scripturealone.data.translations.TranslationLibrary
import com.blainemiller.scripturealone.text.AppLanguage

/**
 * The two halves of the Topics directory — `TopicsLibrary` in `TopicsDirectory.swift`.
 *
 * The life themes are for everyone, in every language: their passages are references, drawn in
 * whatever translation is being read. Nave's Topical Bible is English prose about English words, so
 * like the commentaries it is shown only to readers using the app in English ([AppLanguage.isEnglish]).
 */
object TopicsLibrary {
    @Volatile private var catalog: Pair<String, LifeThemeCatalog>? = null
    @Volatile private var index: TopicalIndex? = null

    /** The themes offered first in Go To, before anyone has typed — the same as iOS. */
    val FEATURED = listOf("anxiety", "fear", "grief", "loneliness", "depression", "guilt", "peace", "hope", "strength", "guidance")

    /**
     * The life themes in the app's language; read again when the reader changes the app's language,
     * which leaves this process running.
     */
    @Synchronized
    fun catalog(context: Context): LifeThemeCatalog {
        val language = AppLanguage.current
        catalog?.takeIf { it.first == language }?.let { return it.second }
        val english = runCatching {
            LifeThemeCatalog.parse(context.assets.open("LifeThemes.json").use { it.readBytes().toString(Charsets.UTF_8) })
        }.getOrDefault(LifeThemeCatalog.EMPTY)
        val resources = context.resources
        val localized = english.localized(
            resources.getStringArray(R.array.life_theme_groups).toList(),
            resources.getStringArray(R.array.life_theme_names).toList(),
            resources.getStringArray(R.array.life_theme_descriptions).toList(),
            resources.getStringArray(R.array.life_theme_synonyms).toList(),
        )
        catalog = language to localized
        return localized
    }

    /** Nave's Topical Bible, opened on first use (1.2 MB, 5,300 topic names read up front). */
    @Synchronized
    fun index(context: Context): TopicalIndex? = index ?: runCatching {
        TopicalIndex(BundledSqlSource(BundledDatabase.withConnection(context, "Topics.sqlite") { it }))
    }.getOrNull().also { index = it }

    /** Nave's, when the reader should see it. */
    fun visibleIndex(context: Context): TopicalIndex? = if (AppLanguage.isEnglish) index(context) else null

    /**
     * A passage (KJV keys) in [translation]. An online translation has only the chapters already read
     * on the device, so a passage from any other is fetched through the reader's key and cached like
     * any chapter read. Blocking; call off the main thread. Empty when it can't be had.
     */
    fun passage(context: Context, translation: String, range: VerseRange): List<ChapterVerse> {
        val local = TranslationLibrary.verses(context, translation, range)
        if (local.isNotEmpty() || TranslationLibrary.online(translation) == null) return local
        runCatching { TranslationLibrary.source(context, translation).chapter(ChapterRef(range.start.book, range.start.chapter)) }
        return TranslationLibrary.verses(context, translation, range)
    }
}
