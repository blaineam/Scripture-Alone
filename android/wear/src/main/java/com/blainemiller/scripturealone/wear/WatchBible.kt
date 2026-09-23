package com.blainemiller.scripturealone.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.blainemiller.scripturealone.companion.TranslationChoice
import com.blainemiller.scripturealone.companion.VerseSnapshot
import com.blainemiller.scripturealone.data.canon.BookNames
import com.blainemiller.scripturealone.data.daily.DailyVerse
import com.blainemiller.scripturealone.data.daily.DailyVerseCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * The Bible the watch reads, in whichever translation the reader chose — `WatchBible` on the Apple
 * Watch — and the favorites, highlights and notes the phone last sent.
 *
 * **Editions.** The watch bundles a compact edition of each translation the phone bundles: ASV, BSB and
 * KJV, about 4.5 MB each, copied from the iOS sources at build time.
 *
 * **Which one is shown.** The most recent choice the watch can actually show wins: the reader picking
 * one here, or the phone reporting a switch there ([TranslationChoice]). A phone choice the watch has no
 * edition of — an online translation — is remembered but not applied.
 *
 * **The library.** On the Apple Watch, favorites and notes arrive through the same iCloud database as
 * the phone's. Android has no such store to share, so the phone sends its widget snapshot over the Data
 * Layer ([PhoneLinkService]); the watch shows it read-only.
 */
class WatchBible private constructor(private val app: Context) {

    data class Edition(val id: String, val name: String)

    data class State(
        val translation: String,
        /** The phone's translation, even when the watch can't show it, for the picker to explain. */
        val phoneTranslation: String?,
        val snapshot: VerseSnapshot?,
    )

    private val prefs = app.getSharedPreferences("watch", Context.MODE_PRIVATE)
    private val opened = mutableMapOf<String, WatchEdition>()

    init {
        // Books are named in the language of the Bible being read, or the device's for a Bible without
        // one (docs/localization.md). The watch's editions are all English today, so it is always the
        // device's language: a French watch reads "Jean 3" over the ASV's text, as the phone does.
        //
        // TODO(localization): the big-8 Bibles have no watch editions yet (iOS's watch has none either).
        // When `Tools/build_companion_data.py` builds them, they must carry `kjv_map` and books keyed by
        // native numbers, `WatchEdition` must read by native keys and convert through `VerseNumbering`
        // (the phone's snapshot is in KJV keys), and this should name books in the edition's language.
        BookNames.use(java.util.Locale.getDefault().toLanguageTag())
    }

    /** Bundled editions, in the picker's order. */
    val editions: List<Edition> = BUNDLED.map { Edition(it, NAMES.getValue(it)) }

    private val _state = MutableStateFlow(State(resolve(), prefs.getString(Keys.PHONE, null), readSnapshot()))
    val state: StateFlow<State> = _state.asStateFlow()

    val translation: String get() = _state.value.translation

    /** The reader picked a translation on the watch. */
    fun choose(id: String, now: Instant = Instant.now()) {
        prefs.edit().putString(Keys.CHOICE, id).putString(Keys.CHOICE_AT, (now.toEpochMilli() / 1000.0).toString()).apply()
        publish()
    }

    /** The phone reported the translation the reader switched to, and when. */
    fun phoneChose(id: String, at: Double) {
        prefs.edit().putString(Keys.PHONE, id).putString(Keys.PHONE_AT, at.toString()).apply()
        publish()
    }

    /** The phone sent its library. A snapshot that doesn't decode is ignored, never half-applied. */
    fun receiveSnapshot(json: String) {
        if (VerseSnapshot.decode(json) == null) return
        val partial = File(snapshotFile.parentFile, "${snapshotFile.name}.partial")
        try {
            partial.writeText(json)
            if (!partial.renameTo(snapshotFile)) {
                snapshotFile.delete()
                partial.renameTo(snapshotFile)
            }
        } catch (e: IOException) {
            return
        }
        publish()
    }

    private fun publish() {
        val before = _state.value.translation
        _state.value = State(resolve(), prefs.getString(Keys.PHONE, null), readSnapshot())
        if (before != _state.value.translation) refreshSurfaces(app)
    }

    private fun resolve(): String = TranslationChoice.resolve(
        available = BUNDLED,
        watch = pick(Keys.CHOICE, Keys.CHOICE_AT),
        phone = pick(Keys.PHONE, Keys.PHONE_AT),
    )

    private fun pick(id: String, at: String): TranslationChoice.Pick? {
        val value = prefs.getString(id, null) ?: return null
        return TranslationChoice.Pick(value, prefs.getString(at, null)?.toDoubleOrNull() ?: 0.0)
    }

    private val snapshotFile: File get() = File(app.filesDir, VerseSnapshot.FILE_NAME)

    private fun readSnapshot(): VerseSnapshot? = try {
        snapshotFile.takeIf { it.exists() }?.readText()?.let(VerseSnapshot::decode)
    } catch (e: IOException) {
        null
    }

    /**
     * The edition for [id] (the current translation by default). The first open copies it out of the
     * APK — Android can't open an asset in place — so call off the main thread.
     */
    @Synchronized
    fun edition(id: String = translation): WatchEdition = opened.getOrPut(id) {
        WatchEdition(id, AndroidEditionRows(copyOut("$id-Watch.sqlite")))
    }

    /** Copies an asset into no-backup storage once per build, written aside and moved into place. */
    private fun copyOut(asset: String): File {
        val dir = File(app.noBackupFilesDir, "editions").apply { mkdirs() }
        val target = File(dir, asset)
        val stamp = File(dir, "$asset.version")
        val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode.toString()
        if (target.exists() && stamp.exists() && stamp.readText() == version) return target
        val partial = File(dir, "$asset.partial")
        app.assets.open(asset).use { input -> partial.outputStream().use { input.copyTo(it, 1 shl 20) } }
        if (!partial.renameTo(target)) {
            target.delete()
            check(partial.renameTo(target)) { "Could not move $asset into place" }
        }
        stamp.writeText(version)
        return target
    }

    private object Keys {
        const val CHOICE = "watch.translation.choice"
        const val CHOICE_AT = "watch.translation.choiceAt"
        const val PHONE = "watch.translation.phone"
        const val PHONE_AT = "watch.translation.phoneAt"
    }

    companion object {
        /** Translations with a compact edition in the watch's APK. */
        val BUNDLED = listOf("ASV", "BSB", "KJV")
        private val NAMES = mapOf(
            "ASV" to "American Standard Version",
            "BSB" to "Berean Standard Bible",
            "KJV" to "King James Version",
        )

        @Volatile private var shared: WatchBible? = null

        fun get(context: Context): WatchBible = shared ?: synchronized(this) {
            shared ?: WatchBible(context.applicationContext).also { shared = it }
        }

        /** Asks the tile and the complication to redraw — after a translation change. */
        fun refreshSurfaces(context: Context) {
            TileService.getUpdater(context).requestUpdate(VerseOfDayTileService::class.java)
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, VerseComplicationService::class.java))
                .requestUpdateAll()
        }
    }
}

/** The bundled Verse of the Day list, decoded once — `DailyVerseLibrary.swift`. */
object WatchDaily {
    @Volatile private var cached: DailyVerseCatalog? = null

    fun catalog(context: Context): DailyVerseCatalog? = cached ?: synchronized(this) {
        cached ?: try {
            context.assets.open(DailyVerseCatalog.ASSET_NAME).use { it.readBytes().decodeToString() }
                .let(DailyVerseCatalog::parse).also { cached = it }
        } catch (e: IOException) {
            null
        }
    }

    fun verse(context: Context, at: Instant = Instant.now()): DailyVerse? = catalog(context)?.verse(at)
}
