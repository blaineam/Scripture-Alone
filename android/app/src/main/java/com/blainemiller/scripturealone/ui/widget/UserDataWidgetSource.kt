package com.blainemiller.scripturealone.ui.widget

import android.content.Context
import android.os.FileObserver
import com.blainemiller.scripturealone.companion.VerseSnapshot.Companion.FavoriteInput
import com.blainemiller.scripturealone.companion.VerseSnapshot.Companion.HighlightInput
import com.blainemiller.scripturealone.companion.VerseSnapshot.Companion.NoteInput
import com.blainemiller.scripturealone.data.userdata.BundledUserDatabase
import com.blainemiller.scripturealone.data.userdata.UserDataStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * The reader's real library for the widgets and the watch: the highlights, notes and favorites store
 * (`data/userdata/`), read through its own connection.
 *
 * The reader writes through its `UserData` on another connection, and nothing tells the rest of the
 * process when it does — so, as a widget extension reads a file the app rewrites, this watches the
 * database's files (the WAL takes every write) and re-reads after each change. That also catches a
 * change made by anything else that writes the store: an import, a keepsake, a future sync. WAL mode
 * means these reads never block, or are blocked by, the reader's writes.
 */
class UserDataWidgetSource(context: Context) : WidgetContentSource {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    override val library: Flow<WidgetLibrary> = callbackFlow {
        val observer = object : FileObserver(file.parentFile!!, MODIFY or CLOSE_WRITE or MOVED_TO or CREATE or DELETE) {
            override fun onEvent(event: Int, path: String?) {
                // The database and its WAL only: a read touches the `-shm` index, and reacting to
                // that would have every read trigger the next.
                if (path == FILE_NAME || path == "$FILE_NAME-wal") trySend(Unit)
            }
        }
        observer.startWatching()
        trySend(Unit)
        awaitClose { observer.stopWatching() }
    }
        .conflate()
        .mapNotNull { read() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    private var store: UserDataStore? = null

    /** The library now; null (skip this change) if the store can't be read at this instant. */
    private suspend fun read(): WidgetLibrary? {
        // No file yet: the reader has never marked a verse. Don't create it — the reader's store does.
        if (!file.exists()) return WidgetLibrary.EMPTY
        repeat(3) { attempt ->
            try {
                val s = store ?: UserDataStore(BundledUserDatabase(file)).also { store = it }
                return libraryOf(s)
            } catch (e: Exception) {
                // A first open racing the reader's own first migration can find the database busy.
                store = null
                delay(250L * (attempt + 1))
            }
        }
        return null
    }

    companion object {
        /** The reader's store — `ReaderViewModel.userData`'s file. */
        const val FILE_NAME = "userdata.sqlite"

        /** The store's rows as the snapshot's inputs: one highlight per verse, every note anchor. */
        fun libraryOf(store: UserDataStore): WidgetLibrary = WidgetLibrary(
            favorites = store.favorites().map { FavoriteInput(it.range, it.createdAt) },
            highlights = store.highlights().map { HighlightInput(it.verseKey, it.color, it.createdAt) },
            notes = store.notes().map { NoteInput(it.title, it.anchors, it.updatedAt, it.body) },
        )
    }
}
