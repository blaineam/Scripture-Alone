package com.blainemiller.scripturealone.data.appsearch

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appsearch.app.AppSearchSchema
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.GenericDocument
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.SearchSpec
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.platformstorage.PlatformStorage
import androidx.concurrent.futures.await
import com.blainemiller.scripturealone.data.share.AppLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** A note as the system's search sees it — `NoteEntity` on iOS. */
data class SearchableNote(val id: UUID, val title: String, val body: String, val passages: String, val modifiedMillis: Long)

/** A favorited passage as the system's search sees it — `FavoriteVerseEntity`. [id] is its storage string. */
data class SearchableFavorite(val id: String, val reference: String, val text: String, val translation: String, val url: String)

/**
 * The reader's notes and favorites in the device's search — Spotlight's part on iOS
 * (`ScriptureAlone/Spotlight/SpotlightSync.swift`), through AndroidX AppSearch on the platform's own
 * store (Android 12+), where the system's search surfaces can show documents an app marks as displayable.
 *
 * Both are off until the reader turns them on in Settings ("Notes in search", "Favorites in search"):
 * a note can be private. Each kind is its own namespace, rebuilt whole on every change (a few hundred
 * notes is cheap, and it can never leave a deleted note behind) and emptied the moment its toggle goes
 * off. A document carries its `scripturealone://` link in `url`, so a result opens right to the note
 * or the selected verse.
 *
 * Before Android 12 there is no platform store, and an index only this app could read would find
 * nothing its own Notes search doesn't — so nothing is indexed there.
 */
class SystemSearchIndex(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("systemSearch", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    val isAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Puts [notes] in the index, or — null, the toggle off — takes every note out. */
    suspend fun syncNotes(notes: List<SearchableNote>?) {
        val hash = StableHash()
        notes?.forEach { note -> hash.add(note.id.toString()); hash.add(note.title); hash.add(note.body); hash.add(note.passages) }
        val fingerprint = if (notes == null) "off" else "on-${notes.size}-${hash.value}"
        sync(NAMESPACE_NOTES, fingerprint) {
            notes.orEmpty().map { note ->
                GenericDocument.Builder<GenericDocument.Builder<*>>(NAMESPACE_NOTES, note.id.toString(), TYPE_NOTE)
                    .setPropertyString(NAME, note.title)
                    .setPropertyString(TEXT, note.body)
                    .setPropertyString(PASSAGES, note.passages)
                    .setPropertyString(URL, AppLink.noteUrl(note.id))
                    .setCreationTimestampMillis(note.modifiedMillis)
                    .build()
            }
        }
    }

    /** Puts [favorites] in the index, or — null, the toggle off — takes every favorite out. */
    suspend fun syncFavorites(favorites: List<SearchableFavorite>?) {
        val hash = StableHash()
        favorites?.forEach { hash.add(it.id); hash.add(it.reference); hash.add(it.text) }
        val fingerprint = if (favorites == null) "off" else "on-${favorites.size}-${hash.value}"
        sync(NAMESPACE_FAVORITES, fingerprint) {
            favorites.orEmpty().map { favorite ->
                GenericDocument.Builder<GenericDocument.Builder<*>>(NAMESPACE_FAVORITES, favorite.id, TYPE_FAVORITE)
                    .setPropertyString(NAME, favorite.reference)
                    .setPropertyString(TEXT, favorite.text)
                    .setPropertyString(TRANSLATION, favorite.translation)
                    .setPropertyString(URL, favorite.url)
                    .build()
            }
        }
    }

    /**
     * The links of the documents in [namespace] matching [query] — what a system search result would
     * open. For checking the index from a debug build; the app's own searches don't go through here.
     */
    suspend fun links(namespace: String, query: String = ""): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        return withSession { session ->
            val spec = SearchSpec.Builder().addFilterNamespaces(namespace).setResultCountPerPage(100).build()
            val page = session.search(query, spec).nextPageAsync.await()
            page.mapNotNull { it.genericDocument.getPropertyString(URL) }
        }
    }

    /**
     * Rebuilds [namespace] from [documents] unless the index already holds what [fingerprint] names —
     * a launch with nothing changed does no work. The fingerprint is saved only once the write lands.
     */
    private suspend fun sync(namespace: String, fingerprint: String, documents: () -> List<GenericDocument>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        lock.withLock {
            if (prefs.getString(namespace, null) == fingerprint) return
            try {
                val count = withSession { session ->
                    session.removeAsync("", SearchSpec.Builder().addFilterNamespaces(namespace).build()).await()
                    val docs = documents()
                    if (docs.isNotEmpty()) {
                        val result = session.putAsync(PutDocumentsRequest.Builder().addGenericDocuments(docs).build()).await()
                        check(result.isSuccess) { "put failed: ${result.failures.values.firstOrNull()?.errorMessage}" }
                    }
                    session.requestFlushAsync().await()
                    if (debug) {
                        // Read back what a search would find, for checking a round trip with logcat.
                        val spec = SearchSpec.Builder().addFilterNamespaces(namespace).setResultCountPerPage(100).build()
                        val found = session.search("", spec).nextPageAsync.await().mapNotNull { it.genericDocument.getPropertyString(URL) }
                        Log.d(TAG, "$namespace: ${docs.size} indexed, search finds ${found.size}: ${found.take(5)}")
                    }
                    docs.size
                }
                prefs.edit().putString(namespace, fingerprint).apply()
                if (debug) Log.d(TAG, "$namespace: $count indexed")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "$namespace index failed: ${e.message}")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun <T> withSession(block: suspend (AppSearchSession) -> T): T = withContext(Dispatchers.IO) {
        val session = PlatformStorage.createSearchSessionAsync(
            PlatformStorage.SearchContext.Builder(context, DATABASE).build(),
        ).await()
        try {
            session.setSchemaAsync(schema).await()
            block(session)
        } finally {
            session.close()
        }
    }

    companion object {
        private const val TAG = "SystemSearchIndex"
        private const val DATABASE = "scripture_alone"
        const val NAMESPACE_NOTES = "notes"
        const val NAMESPACE_FAVORITES = "favorites"
        private const val TYPE_NOTE = "Note"
        private const val TYPE_FAVORITE = "FavoriteVerse"
        private const val NAME = "name"
        private const val TEXT = "text"
        private const val PASSAGES = "passages"
        private const val TRANSLATION = "translation"
        private const val URL = "url"

        private fun indexed(name: String) = AppSearchSchema.StringPropertyConfig.Builder(name)
            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
            .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
            .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
            .build()

        /** Stored, not searched: a link is what a result opens, not something to match. */
        private fun stored(name: String) = AppSearchSchema.StringPropertyConfig.Builder(name)
            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
            .build()

        private val schema: SetSchemaRequest by lazy {
            SetSchemaRequest.Builder()
                .addSchemas(
                    AppSearchSchema.Builder(TYPE_NOTE)
                        .addProperty(indexed(NAME)).addProperty(indexed(TEXT)).addProperty(indexed(PASSAGES)).addProperty(stored(URL))
                        .build(),
                    AppSearchSchema.Builder(TYPE_FAVORITE)
                        .addProperty(indexed(NAME)).addProperty(indexed(TEXT)).addProperty(indexed(TRANSLATION)).addProperty(stored(URL))
                        .build(),
                )
                // The point of the index: results the system's search may show. (Off, nothing is in it.)
                .setSchemaTypeDisplayedBySystem(TYPE_NOTE, true)
                .setSchemaTypeDisplayedBySystem(TYPE_FAVORITE, true)
                .setForceOverride(true)
                .build()
        }
    }
}

/**
 * FNV-1a: stable across launches, unlike `hashCode` of a list, so a stored fingerprint still means
 * something the next time the app opens — `StableHash` in SpotlightSync.swift.
 */
internal class StableHash {
    var value: ULong = 0xcbf29ce484222325uL
        private set

    fun add(string: String) {
        for (byte in string.toByteArray(Charsets.UTF_8)) {
            value = value xor byte.toUByte().toULong()
            value *= PRIME
        }
        value = value xor 0xFFuL   // field separator
        value *= PRIME
    }

    private companion object {
        const val PRIME = 0x100000001b3uL
    }
}
