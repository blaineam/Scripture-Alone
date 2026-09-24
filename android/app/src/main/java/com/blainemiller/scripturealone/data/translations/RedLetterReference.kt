package com.blainemiller.scripturealone.data.translations

import android.content.Context
import com.blainemiller.scripturealone.data.BundledTranslations
import com.blainemiller.scripturealone.data.ChapterVerse
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.assets.AssetLibrary
import com.blainemiller.scripturealone.data.assets.AssetPack
import com.blainemiller.scripturealone.data.importer.ScalarSpan
import com.blainemiller.scripturealone.data.sabible.ChapterRef

/**
 * Where an import that marks no words of Christ can take them from: the first bundled English
 * translation on the device that marks them — the BSB, then the KJV (`ImportedLibrary.swift`). Only
 * verses that align closely are marked (`RedLetterInference`).
 */
object RedLetterReference {
    /** Reads a verse's text and red spans (Unicode scalars), a chapter at a time; null when neither translation is here. */
    fun lookup(context: Context): ((VerseRef) -> Pair<String, List<ScalarSpan>>?)? {
        val pack = listOf(AssetPack.BSB, AssetPack.KJV).firstOrNull { AssetLibrary.isOnDevice(it) } ?: return null
        val id = pack.translationId ?: return null
        val source = runCatching { BundledTranslations.source(context, id) }.getOrNull() ?: return null
        val chapters = HashMap<Pair<Int, Int>, Map<Int, ChapterVerse>>()
        return { ref ->
            val verses = chapters.getOrPut(ref.book to ref.chapter) {
                runCatching { source.chapter(ChapterRef(ref.book, ref.chapter)).verses.associateBy { it.ref.verse } }.getOrDefault(emptyMap())
            }
            verses[ref.verse]?.let { verse -> verse.text to verse.red.map { ScalarSpan(it.start, it.length) } }
        }
    }
}
