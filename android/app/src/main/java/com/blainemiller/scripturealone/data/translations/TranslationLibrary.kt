package com.blainemiller.scripturealone.data.translations

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.blainemiller.scripturealone.data.BundledTranslations
import com.blainemiller.scripturealone.data.Chapter
import com.blainemiller.scripturealone.data.ChapterSource
import com.blainemiller.scripturealone.data.ChapterVerse
import com.blainemiller.scripturealone.data.StoreChapters
import com.blainemiller.scripturealone.data.TranslationInfo
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.online.BundledCacheDriver
import com.blainemiller.scripturealone.data.online.OnlineChapterLoader
import com.blainemiller.scripturealone.data.online.OnlineEntry
import com.blainemiller.scripturealone.data.online.BlockStoreKeyBackup
import com.blainemiller.scripturealone.data.online.OnlineKeyStore
import com.blainemiller.scripturealone.data.online.OnlineKeySync
import com.blainemiller.scripturealone.data.online.OnlineProvider
import com.blainemiller.scripturealone.data.online.UrlConnectionTransport
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.search.SearchHit
import com.blainemiller.scripturealone.data.search.VerseSearch
import com.blainemiller.scripturealone.data.sql.BundledSqlSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** A translation the reader imported: its store on disk and what the store says about itself. */
data class ImportedTranslation(val info: TranslationInfo, val file: File) {
    val id: String get() = info.id
}

/**
 * The translations the reader added — imported files and online translations — alongside the three
 * that ship. `ImportedLibrary`, `OnlineCatalog` and the translation half of `ReaderModel` on iOS.
 *
 * [BundledTranslations] is the seam the reader lists and opens through; it asks this object for
 * everything that isn't bundled, so an imported or online translation opens in the reader with no
 * branch for it there.
 *
 * Imported stores live in `filesDir/Translations`: they belong to the person who imported them, on
 * the device they imported them to. Online caches live in no-backup storage (`OnlineTranslations`),
 * because a cached chapter is a copy of someone else's text held under their terms, and must not be
 * carried off in a backup.
 */
object TranslationLibrary {

    data class State(val imported: List<ImportedTranslation> = emptyList(), val online: List<OnlineEntry> = emptyList())

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private lateinit var app: Context
    private val driver = BundledSQLiteDriver()
    private val openImported = mutableMapOf<String, FileChapterSource>()

    /** Ids of every added translation, imported first, as the reader's switcher lists them. */
    val addedIds: List<String>
        get() = _state.value.let { s -> s.imported.map { it.id } + s.online.map { it.id } }

    /** Called once from `Application.onCreate`, before the reader restores its translation. */
    fun attach(context: Context) {
        app = context.applicationContext
        reloadImported()
        refreshOnline()
        syncKeys()
    }

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Hands the reader's keys to Block Store and restores any it holds that this device lacks — iCloud
     * Keychain's part on iOS (`OnlineKeySync`). At launch and after the keys are edited. A restored key
     * brings its translations back into the switcher.
     */
    fun syncKeys() {
        background.launch {
            val result = runCatching { OnlineKeySync.sync(keys(), BlockStoreKeyBackup(app)) }.getOrNull() ?: return@launch
            if (result.restored.isNotEmpty()) refreshOnline()
            // Which providers, never the keys: a debug build's record of what the pass did.
            if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                Log.i("OnlineKeySync", "restored=${result.restored} backedUp=${result.backedUp} deleted=${result.deleted}")
            }
        }
    }

    val isAttached: Boolean get() = ::app.isInitialized

    fun importedDirectory(context: Context = app): File =
        File(context.applicationContext.filesDir, "Translations").apply { mkdirs() }

    fun keys(context: Context = app): OnlineKeyStore = OnlineKeyStore(context)

    val loader: OnlineChapterLoader by lazy {
        OnlineChapterLoader(
            directory = File(app.noBackupFilesDir, "OnlineTranslations"),
            driver = BundledCacheDriver(),
            transport = UrlConnectionTransport,
            keyFor = { keys().key(it) },
        )
    }

    // ---- Imported ---------------------------------------------------------------------------------

    /** Rescans the imported directory. A store that won't open is a half-written import: skipped. */
    @Synchronized
    fun reloadImported() {
        val files = importedDirectory().listFiles { f -> f.isFile && f.name.endsWith(".sqlite") }.orEmpty()
        val imported = files.mapNotNull { file ->
            val info = runCatching { withStore(file) { StoreChapters.info(it, file.nameWithoutExtension) } }.getOrNull()
                ?: return@mapNotNull null
            // Never shadow a bundled translation: the bundled one is the one the app vouches for.
            if (info.id in BundledTranslations.bundled) null else ImportedTranslation(info, file)
        }.distinctBy { it.id }.sortedBy { it.info.name.lowercase() }
        // A store replaced by a re-import must be re-opened, not read through its old connection.
        openImported.values.forEach { it.close() }
        openImported.clear()
        _state.value = _state.value.copy(imported = imported)
    }

    @Synchronized
    fun remove(translation: ImportedTranslation) {
        openImported.remove(translation.id)?.close()
        translation.file.delete()
        reloadImported()
    }

    private fun <T> withStore(file: File, block: (BundledSqlSource) -> T): T {
        val connection = driver.open(file.path, SQLITE_OPEN_READONLY)
        return try {
            block(BundledSqlSource(connection))
        } finally {
            connection.close()
        }
    }

    // ---- Online -----------------------------------------------------------------------------------

    /**
     * The online translations to offer: the ESV if a Crossway key exists, plus the remembered
     * API.Bible picks — but only while a key that could read them exists. Removing a key removes
     * its translations.
     */
    @Synchronized
    fun refreshOnline() {
        val keys = keys()
        val online = buildList {
            if (keys.hasKey(OnlineProvider.CROSSWAY)) add(OnlineEntry.ESV)
            if (keys.hasKey(OnlineProvider.API_BIBLE)) addAll(rememberedPicks())
        }.filter { it.id !in BundledTranslations.bundled }.distinctBy { it.id }
        _state.value = _state.value.copy(online = online)
    }

    /**
     * Which API.Bible translations the reader picked. Public catalogue ids and names — not
     * credentials — so plain preferences, as iOS keeps them in UserDefaults; without the key they
     * open nothing.
     */
    fun rememberedPicks(): List<OnlineEntry> {
        val raw = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PICKS, null) ?: return emptyList()
        return runCatching {
            (Json.parseToJsonElement(raw) as JsonArray).mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                fun s(key: String) = (o[key] as? JsonPrimitive)?.content
                OnlineEntry(s("id") ?: return@mapNotNull null, s("name") ?: "", OnlineProvider.API_BIBLE, s("remoteID") ?: return@mapNotNull null)
            }
        }.getOrDefault(emptyList())
    }

    fun rememberPicks(picks: List<OnlineEntry>) {
        val json = buildJsonArray {
            for (pick in picks.filter { it.provider == OnlineProvider.API_BIBLE }) {
                add(buildJsonObject { put("id", pick.id); put("name", pick.name); put("remoteID", pick.remoteId) })
            }
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PICKS, json.toString()).apply()
        refreshOnline()
    }

    // ---- Opening ----------------------------------------------------------------------------------

    fun imported(id: String): ImportedTranslation? = _state.value.imported.firstOrNull { it.id == id }
    fun online(id: String): OnlineEntry? = _state.value.online.firstOrNull { it.id == id }

    /** The source for an added translation. Throws for an id that is neither imported nor online. */
    @Synchronized
    fun source(context: Context, id: String): ChapterSource {
        imported(id)?.let { translation ->
            return openImported.getOrPut(id) { FileChapterSource(translation.file, translation.info, driver) }
        }
        online(id)?.let { return OnlineChapterSource(it, loader) }
        throw NoSuchElementException("No translation $id on this device")
    }

    /** What the reader would see in the switcher for [id]: bundled, imported or online. */
    fun kind(id: String): Kind = when {
        id in BundledTranslations.bundled -> Kind.BUNDLED
        imported(id) != null -> Kind.IMPORTED
        online(id) != null -> Kind.ONLINE
        else -> Kind.MISSING
    }

    enum class Kind { BUNDLED, IMPORTED, ONLINE, MISSING }

    /**
     * The verses of [kjvRange] in translation [id], for previews beside the text — cross references,
     * Compare. Never a network request: an online translation answers from what it has cached.
     * Blocking; call off the main thread.
     */
    fun verses(context: Context, id: String, kjvRange: VerseRange): List<ChapterVerse> {
        online(id)?.let { return loader.cachedVerses(it, kjvRange.start.key, kjvRange.end.key) }
        val source = runCatching { BundledTranslations.source(context, id) }.getOrNull() ?: return emptyList()
        // [kjvRange] is in KJV keys — cross references, notes and study data are keyed that way — and the
        // verses come back with the translation's own references (`VerseNumbering`).
        val range = runCatching { source.numbering }.getOrNull()?.nativeRange(kjvRange) ?: return emptyList()
        val out = mutableListOf<ChapterVerse>()
        var chapter = ChapterRef(range.start.book, range.start.chapter)
        while (chapter.book < range.end.book || (chapter.book == range.end.book && chapter.chapter <= range.end.chapter)) {
            val loaded = chapterCache.get(id, chapter) { runCatching { source.chapter(chapter) }.getOrNull() }
            loaded?.verses?.filterTo(out) { it.ref.key in range.start.key..range.end.key && it.ref.verse > 0 }
            chapter = com.blainemiller.scripturealone.data.Canon.next(chapter) ?: break
            if (out.size > 400) break // a preview, not a book
        }
        return out
    }

    /** A handful of recently read chapters, so a list of previews decrypts each chapter once. */
    private val chapterCache = object {
        private val map = LinkedHashMap<Pair<String, ChapterRef>, Chapter?>(16, 0.75f, true)

        @Synchronized
        fun get(id: String, ref: ChapterRef, load: () -> Chapter?): Chapter? {
            val key = id to ref
            if (map.containsKey(key)) return map[key]
            val value = load()
            map[key] = value
            while (map.size > 24) map.remove(map.keys.first())
            return value
        }
    }

    private const val PREFS = "translations"
    private const val PICKS = "onlineTranslations"
}

/**
 * An imported store on disk. Written once by the importer and never changed, so one read-only
 * connection serves every read; [close] when the file is removed or replaced.
 */
class FileChapterSource(
    private val file: File,
    override val info: TranslationInfo,
    driver: BundledSQLiteDriver,
) : ChapterSource {
    private val connection: SQLiteConnection = driver.open(file.path, SQLITE_OPEN_READONLY)
    private val source = BundledSqlSource(connection)

    override fun contains(ref: ChapterRef): Boolean = StoreChapters.layoutJson(source, ref) != null

    override fun chapter(ref: ChapterRef): Chapter = StoreChapters.chapter(source, info, ref)

    /** The FTS5 index the importer wrote (`BundledStoreWriter`), the same one a bundled store carries. */
    override fun search(query: String, limit: Int): List<SearchHit> =
        synchronized(connection) { VerseSearch(source).search(query, limit) }

    fun close() = synchronized(connection) { connection.close() }
}

/**
 * An online translation: any chapter can be asked for; the loader answers from the cache or fetches
 * it with the reader's key. The reader's footer prints [info]'s copyright — the publisher's required
 * notice — under every chapter.
 */
class OnlineChapterSource(private val entry: OnlineEntry, private val loader: OnlineChapterLoader) : ChapterSource {
    override val info: TranslationInfo = OnlineChapterLoader.info(entry)
    override fun contains(ref: ChapterRef): Boolean = true
    override fun chapter(ref: ChapterRef): Chapter = loader.chapter(entry, ref)

    /** At the provider, with the reader's key — one request, as on iOS. */
    override fun search(query: String, limit: Int): List<SearchHit> = loader.search(entry, query, limit)
}
