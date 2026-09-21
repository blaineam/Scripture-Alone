package com.blainemiller.scripturealone.data.userdata

import com.blainemiller.scripturealone.data.VerseRange
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/**
 * The reader's marks as observable state over a [UserDataStore] — what SwiftData's `@Query` gives the
 * iOS views. Every write runs on one background lane, in order, and then republishes what it changed,
 * so the screen always shows what is on disk.
 *
 * [open] is called on that lane, so opening the database never touches the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserData(
    private val scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val open: () -> UserDataStore,
) {
    private val lane = dispatcher.limitedParallelism(1)
    private var store: UserDataStore? = null

    private val _highlights = MutableStateFlow<List<Highlight>>(emptyList())
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    private val _favorites = MutableStateFlow<List<Favorite>>(emptyList())
    private val _loaded = MutableStateFlow(false)

    val highlights: StateFlow<List<Highlight>> = _highlights.asStateFlow()
    /** Most recently edited first. */
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()
    /** Newest first. */
    val favorites: StateFlow<List<Favorite>> = _favorites.asStateFlow()
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        write {
            _highlights.value = it.highlights()
            _notes.value = it.notes()
            _favorites.value = it.favorites()
            _loaded.value = true
        }
    }

    private fun write(block: (UserDataStore) -> Unit) {
        scope.launch(lane) {
            val s = store ?: open().also { store = it }
            block(s)
        }
    }

    fun highlight(keys: Collection<Int>, color: HighlightColor) = write {
        it.highlight(keys, color)
        _highlights.value = it.highlights()
    }

    fun removeHighlights(keys: Collection<Int>) = write {
        it.removeHighlights(keys)
        _highlights.value = it.highlights()
    }

    /** Saves [note] as is; callers set [Note.updatedAt] when the content changed. */
    fun save(note: Note) {
        // Shown at once: the editor types into this list, and a round trip per keystroke would lag.
        _notes.value = (listOf(note) + _notes.value.filter { it.id != note.id }).sortedByDescending { it.updatedAt }
        write { it.save(note) }
    }

    fun deleteNote(id: UUID) {
        _notes.value = _notes.value.filter { it.id != id }
        write { it.deleteNote(id) }
    }

    /** A new note on [anchors], saved and returned so the caller can open it — `createNoteFromSelection`. */
    fun newNote(anchors: List<VerseRange>, now: Instant = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)): Note {
        val note = Note(anchors = anchors.sortedWith(RANGE_ORDER), createdAt = now, updatedAt = now)
        save(note)
        return note
    }

    fun toggleFavorite(ranges: List<VerseRange>) = write {
        it.toggleFavorite(ranges)
        _favorites.value = it.favorites()
    }

    fun deleteFavorite(id: UUID) {
        _favorites.value = _favorites.value.filter { it.id != id }
        write { it.deleteFavorite(id) }
    }
}
