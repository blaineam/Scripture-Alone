package com.blainemiller.scripturealone.data.online

import com.blainemiller.scripturealone.data.Chapter
import com.blainemiller.scripturealone.data.ChapterVerse
import com.blainemiller.scripturealone.data.StoreChapters
import com.blainemiller.scripturealone.data.TranslationInfo
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import java.io.File

/**
 * One online translation the reader has set up: the ESV behind a Crossway key, or one of the
 * API.Bible translations their key can read. [remoteId] is the provider's own identifier ("esv", or
 * API.Bible's opaque Bible id).
 */
data class OnlineEntry(val id: String, val name: String, val provider: OnlineProvider, val remoteId: String) {
    /** The identity the cache writes into its `meta` row, and so what the reader's footer prints. */
    val translation: OnlineTranslation
        get() = if (provider == OnlineProvider.CROSSWAY) {
            OnlineTranslation.ESV
        } else {
            OnlineTranslation(id, name, id, provider.copyrightNotice, provider.licenseSummary)
        }

    /** The cache's file name: letters and digits of [id] only, as iOS names it. */
    val cacheFileName: String get() = id.filter { it.isLetterOrDigit() }.ifEmpty { "ONLINE" } + ".sqlite"

    companion object {
        /** The ESV is offered whenever a Crossway key exists; Crossway serves exactly one text. */
        val ESV = OnlineEntry("ESV", "English Standard Version", OnlineProvider.CROSSWAY, "esv")
    }
}

/**
 * Fetches chapters for the translations the app may not ship, and files them in each translation's
 * [OnlineChapterCache] — `OnlineTextLoader.swift`.
 *
 * The cache is a real store in the bundled schema, so once a chapter is in it the reader opens it like
 * any other translation. **The publisher's terms are enforced by the cache, not here**: every write is
 * checked against the 500-verse ceiling, evicting least-recently-read chapters first.
 *
 * Every dependency is a parameter, so the whole path — request, parse, cache, read back — runs on the
 * JVM against captured responses: [transport] is the network, [driver] is SQLite, [keyFor] the
 * reader's stored keys.
 *
 * Blocking; call from a background dispatcher.
 */
class OnlineChapterLoader(
    private val directory: File,
    private val driver: CacheDatabaseDriver,
    private val transport: HttpTransport,
    private val keyFor: (OnlineProvider) -> String?,
    private val verseLimit: Int = EsvTerms.CACHE_VERSE_LIMIT,
) {
    fun cacheFile(entry: OnlineEntry): File = File(directory.apply { mkdirs() }, entry.cacheFileName)

    fun cache(entry: OnlineEntry): OnlineChapterCache =
        OnlineChapterCache(cacheFile(entry), entry.translation, driver, verseLimit)

    /**
     * The chapter, from the cache when it's there (no request at all), otherwise fetched with the
     * reader's key, filed in the cache, and read back from it — so what the reader shows is exactly
     * what the cache holds.
     */
    fun chapter(entry: OnlineEntry, ref: ChapterRef): Chapter {
        val cache = cache(entry)
        if (cache.contains(ref)) {
            runCatching { cache.markRead(ref) }
        } else {
            val key = keyFor(entry.provider) ?: throw OnlineFailure.NeedsKey(entry.provider)
            val passage = when (entry.provider) {
                OnlineProvider.CROSSWAY -> ESVClient(key, transport).chapter(ref)
                OnlineProvider.API_BIBLE -> APIBibleClient(key, entry.remoteId, transport).chapter(ref)
            }
            // The chapter's own shape goes in with it: poetry lines, psalm titles, headings and the
            // words of Christ, all of which the plain-text endpoints had rendered away.
            cache.store(passage.verses, ref, passage.blocks)
        }
        // The cache swaps its file on every write, so it is opened fresh for each read.
        return driver.openReadOnly(cache.file).use { db ->
            StoreChapters.chapter(db, info(entry), ref)
        }
    }

    /**
     * Verses already on the device, for previews (cross references, compare) — never a request. An
     * online translation's text is fetched only for the chapter the reader actually opens.
     */
    fun cachedVerses(entry: OnlineEntry, first: Int, last: Int): List<ChapterVerse> {
        val file = cacheFile(entry)
        if (!file.exists()) return emptyList()
        return runCatching { driver.openReadOnly(file).use { StoreChapters.verses(it, first, last) } }.getOrDefault(emptyList())
    }

    /** Drops everything cached for the translation — when the reader removes their key. */
    fun clear(entry: OnlineEntry) {
        if (cacheFile(entry).exists()) cache(entry).clear()
    }

    companion object {
        fun info(entry: OnlineEntry): TranslationInfo = entry.translation.let {
            TranslationInfo(it.id, it.name, it.abbreviation, it.copyright, it.license)
        }
    }
}
