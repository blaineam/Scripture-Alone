package com.blainemiller.scripturealone.ui.keepsake

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.blainemiller.scripturealone.data.keepsake.Keepsake
import com.blainemiller.scripturealone.data.keepsake.KeepsakeNote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Notes to export and what to call them — `ExportSelection`, and the sheet's `preferredTranslation`. */
data class ExportRequest(val notes: List<KeepsakeNote>, val title: String, val preferredTranslation: String? = null)

/**
 * The keepsake side of the reader — `LegacySession` and `LegacySupport` on iOS: which keepsake, if any,
 * is being read; the library of keepsakes received; and which of this slice's sheets is up (Keepsake &
 * Export, a keepsake file being opened, a notes export, the notes import).
 *
 * The reader asks [reading] for whose marks to draw. [open] and [close] switch to the keepsake's own
 * translation and back, as iOS does, through the callbacks the reader supplies.
 */
class LegacySession(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    val library = KeepsakeLibrary(File(app.filesDir, "Keepsakes"))

    /** The library's entries, as Compose state; reloaded off the main thread. */
    var entries by mutableStateOf<List<KeepsakeLibrary.Entry>>(emptyList())
        private set

    /** Someone else's Bible, being read. */
    var reading by mutableStateOf<Keepsake?>(null)
        private set
    private var translationBefore: String? = null

    /** Keepsake & Export, from Appearance and the Notes panel's Export menu. */
    var settingsOpen by mutableStateOf(false)
    /** A `.scripturelegacy` file handed to the app or picked in Keepsake & Export — `KeepsakeImportRequest`. */
    var pendingFile by mutableStateOf<Uri?>(null)
    /** An export being prepared — the notes and their title. */
    var export by mutableStateOf<ExportRequest?>(null)
    /** Bring Your Notes (Life Bible, paste, CSV). */
    var importing by mutableStateOf(false)

    init {
        refresh()
    }

    fun refresh() {
        scope.launch { entries = withContext(Dispatchers.IO) { library.reload() } }
    }

    suspend fun add(keepsake: Keepsake): KeepsakeLibrary.AddResult {
        val result = withContext(Dispatchers.IO) { library.add(keepsake) }
        entries = library.entries
        return result
    }

    fun remove(id: UUID, close: () -> Unit) {
        if (reading?.id == id) close()
        scope.launch { entries = withContext(Dispatchers.IO) { library.remove(id); library.entries } }
    }

    /**
     * Starts reading [keepsake]: the reader's selection cleared, and its translation switched to the one
     * the keepsake was read in when this device has it — [translation] is the reader's current one,
     * [select] switches.
     */
    fun open(keepsake: Keepsake, translation: String, available: List<String>, select: (String) -> Unit) {
        if (reading == null) translationBefore = translation
        reading = keepsake
        settingsOpen = false
        val preferred = keepsake.manifest.preferredTranslation
        if (preferred != null && preferred != translation && preferred in available) select(preferred)
    }

    /** Back to the reader's own Bible, in the translation they had before. */
    fun close(translation: String, select: (String) -> Unit) {
        reading = null
        translationBefore?.let { if (it != translation) select(it) }
        translationBefore = null
    }

    /** A stable id for this person's own Bible, so each new keepsake replaces the last — `LegacyIdentity`. */
    val bibleID: UUID
        get() {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY_BIBLE_ID, null)?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull()?.let { return it } }
            val id = UUID.randomUUID()
            prefs.edit().putString(KEY_BIBLE_ID, id.toString().uppercase()).apply()
            return id
        }

    /** The create form's remembered fields — iOS's `legacy.ownerName`, `legacy.dedication`, `legacy.translation`. */
    fun remembered(key: String): String = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "").orEmpty()

    fun remember(key: String, value: String) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }

    companion object {
        const val PREFS = "legacy"
        const val KEY_BIBLE_ID = "legacy.bibleID"
        const val KEY_OWNER = "legacy.ownerName"
        const val KEY_DEDICATION = "legacy.dedication"
        const val KEY_TRANSLATION = "legacy.translation"
    }
}
