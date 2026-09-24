package com.blainemiller.scripturealone.data.study

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.blainemiller.scripturealone.data.sql.BundledSqlSource
import com.blainemiller.scripturealone.data.translations.ImportedTranslation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The study material of every imported translation that brought some — `ImportedStudyLibrary.swift`.
 * Each is a commentary source beside the bundled commentators, and its pictures show in Context.
 *
 * Reloaded whenever the imported translations change ([reload], from `TranslationLibrary`), so a
 * removed translation takes its notes with it and a re-imported one is read afresh.
 */
object ImportedStudyLibrary {
    private class Open(val store: ImportedStudyStore, val connection: SQLiteConnection)

    private val driver = BundledSQLiteDriver()
    private var open: List<Open> = emptyList()

    private val _sources = MutableStateFlow<List<StudySource>>(emptyList())

    /** One commentary source per imported study Bible, by name. */
    val sources: StateFlow<List<StudySource>> = _sources

    val isEmpty: Boolean get() = _sources.value.isEmpty()

    @Synchronized
    fun store(sourceId: String): ImportedStudyStore? = open.firstOrNull { it.store.source.id == sourceId }?.store

    /** Every imported study store, for the pictures of a chapter. */
    @Synchronized
    fun stores(): List<ImportedStudyStore> = open.map { it.store }

    @Synchronized
    fun reload(imported: List<ImportedTranslation>) {
        open.forEach { runCatching { synchronized(it.connection) { it.connection.close() } } }
        open = imported.mapNotNull { translation ->
            val connection = runCatching { driver.open(translation.file.path, SQLITE_OPEN_READONLY) }.getOrNull() ?: return@mapNotNull null
            val store = ImportedStudyStore.open(BundledSqlSource(connection), translation.info)
            if (store == null) {
                connection.close()
                null
            } else {
                Open(store, connection)
            }
        }
        _sources.value = open.map { it.store.source }
    }
}
