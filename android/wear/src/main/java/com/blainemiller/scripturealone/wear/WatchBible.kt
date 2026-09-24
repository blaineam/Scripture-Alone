package com.blainemiller.scripturealone.wear

import android.content.ComponentName
import android.content.Context
import android.os.LocaleList
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.blainemiller.scripturealone.companion.TranslationChoice
import com.blainemiller.scripturealone.companion.VerseSnapshot
import com.blainemiller.scripturealone.companion.WatchEditionBuilder
import com.blainemiller.scripturealone.companion.WearLink
import com.blainemiller.scripturealone.data.VerseNumbering
import com.blainemiller.scripturealone.data.canon.BookNames
import com.blainemiller.scripturealone.data.daily.DailyVerse
import com.blainemiller.scripturealone.data.daily.DailyVerseCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.time.Instant

/**
 * The Bible the watch reads, in whichever translation the reader chose — `WatchBible` on the Apple
 * Watch — and the favorites, highlights and notes the phone last sent.
 *
 * **Editions.** The watch bundles a compact edition of each English translation the phone bundles: ASV,
 * BSB and KJV, about 4.5 MB each, copied from the iOS sources at build time. The big-8 locales' Bibles
 * (docs/localization.md) are not bundled — eight would be 40 MB on every watch — so the phone writes the
 * edition of the one the reader uses and sends it over the Data Layer ([PhoneLink]); it lands in
 * [receivedDirectory] and reads through the very same code, carrying its `kjv_map` and `meta.language`.
 *
 * **Which one is shown.** The most recent choice the watch can actually show wins: the reader picking
 * one here, or the phone reporting a switch there ([TranslationChoice]). A phone choice the watch has no
 * edition of — an online translation, or a locale Bible still on its way — is remembered, and applied
 * when its edition arrives.
 *
 * **Numbers and names.** A locale Bible keeps its own verse numbers; what is stored or handed over stays
 * in KJV keys ([WatchEdition.numbering]). Books are named in the edition's language, or the device's for
 * an English Bible — so a French watch reads "Jean 3" over the Segond, and over the ASV too.
 *
 * **The library.** On the Apple Watch, favorites and notes arrive through the same iCloud database as
 * the phone's. Android has no such store to share, so the phone sends its widget snapshot over the Data
 * Layer ([PhoneLinkService]); the watch shows it read-only.
 */
class WatchBible private constructor(private val app: Context) {

    data class Edition(
        val id: String,
        val name: String,
        val bundled: Boolean,
        /** `meta.language`; null for the English Bibles. */
        val language: String? = null,
    )

    data class State(
        val translation: String,
        /** The phone's translation, even when the watch can't show it, for the picker to explain. */
        val phoneTranslation: String?,
        val snapshot: VerseSnapshot?,
        /** Bundled editions, then those the phone sent, in the picker's order. */
        val editions: List<Edition> = emptyList(),
        /** Whether [translation] is someone's choice rather than the fallback. */
        val chosen: Boolean = false,
        /** The reader's accent colour from the phone, 0xRRGGBB; null until the phone has said. */
        val accent: Int? = null,
    ) {
        val edition: Edition? get() = editions.firstOrNull { it.id == translation }
    }

    private val prefs = app.getSharedPreferences("watch", Context.MODE_PRIVATE)
    private val opened = mutableMapOf<String, WatchEdition>()

    private val _state: MutableStateFlow<State>
    val state: StateFlow<State>

    init {
        val editions = readEditions()
        val (translation, chosen) = resolve(editions)
        _state = MutableStateFlow(State(translation, prefs.getString(Keys.PHONE, null), readSnapshot(), editions, chosen, accent()))
        state = _state.asStateFlow()
        nameBooks()
    }

    /** Every edition the watch can show, in the picker's order. */
    val editions: List<Edition> get() = _state.value.editions

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

    /** The phone reported the reader's accent colour (0xRRGGBB, its dark-page value). */
    fun phoneAccent(hex: Int) {
        if (hex <= 0 || hex > 0xFFFFFF || hex == accent()) return
        prefs.edit().putInt(Keys.ACCENT, hex).apply()
        publish()
    }

    private fun accent(): Int? = prefs.getInt(Keys.ACCENT, 0).takeIf { it > 0 }

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

    /** Whether the edition of [id] the phone sent as the asset with [digest] is already here. */
    fun holdsEdition(id: String, digest: String?): Boolean =
        digest != null && prefs.getString(Keys.digest(id), null) == digest && receivedFile(id).exists()

    /**
     * The phone sent the watch edition of [id]. Written aside, checked — it opens, it is the Bible it
     * claims to be, it has Genesis 1 — and only then moved into place; anything else is dropped, and the
     * watch keeps what it had. Blocking; call off the main thread.
     */
    fun receiveEdition(id: String, input: InputStream, digest: String?): Boolean {
        if (!WearLink.isSafeId(id) || id in BUNDLED) return false
        val dir = receivedDirectory(app).apply { mkdirs() }
        val partial = File(dir, ".${WatchEditionBuilder.fileName(id)}.partial")
        try {
            partial.outputStream().use { input.copyTo(it, 1 shl 20) }
        } catch (e: IOException) {
            partial.delete()
            return false
        }
        val info = try {
            val edition = WatchEdition(id, AndroidEditionRows(partial))
            try {
                if (edition.meta["id"] != id || edition.verseCount(1, 1) == 0) null else edition.name to edition.language
            } finally {
                edition.close()
            }
        } catch (e: RuntimeException) {
            null
        }
        if (info == null) {
            partial.delete()
            return false
        }
        synchronized(this) {
            // Dropped, not closed: a screen may be mid-query on it. The new file is a new inode, so the
            // old handle keeps reading the old copy until it is collected.
            opened.remove(id)
            val target = receivedFile(id)
            if (!partial.renameTo(target)) {
                target.delete()
                if (!partial.renameTo(target)) return false
            }
            prefs.edit()
                .putString(Keys.name(id), info.first)
                .putString(Keys.language(id), info.second.orEmpty())
                .putString(Keys.digest(id), digest)
                .apply()
        }
        publish()
        return true
    }

    private fun publish() {
        val before = _state.value
        val editions = readEditions()
        val (translation, chosen) = resolve(editions)
        _state.value = State(translation, prefs.getString(Keys.PHONE, null), readSnapshot(), editions, chosen, accent())
        nameBooks()
        if (before.translation != translation || before.editions != editions) refreshSurfaces(app)
    }

    /**
     * Books are named in the language of the Bible being read, or the device's for a Bible without one
     * (docs/localization.md) — `BookNames.use(language: store?.language ?? …)` on the Apple Watch.
     */
    private fun nameBooks() {
        BookNames.use(_state.value.edition?.language ?: Locale.getDefault().toLanguageTag())
    }

    private fun resolve(editions: List<Edition>): Pair<String, Boolean> {
        val available = editions.map { it.id }
        val watch = pick(Keys.CHOICE, Keys.CHOICE_AT)
        val phone = pick(Keys.PHONE, Keys.PHONE_AT)
        val id = TranslationChoice.resolve(available = available, watch = watch, phone = phone)
        return id to listOfNotNull(watch, phone).any { it.id == id }
    }

    private fun pick(id: String, at: String): TranslationChoice.Pick? {
        val value = prefs.getString(id, null) ?: return null
        return TranslationChoice.Pick(value, prefs.getString(at, null)?.toDoubleOrNull() ?: 0.0)
    }

    /** The bundled editions, then the received ones by id. */
    private fun readEditions(): List<Edition> {
        val bundled = BUNDLED.map { Edition(it, NAMES.getValue(it), bundled = true) }
        val suffix = WatchEditionBuilder.fileName("")
        val received = receivedDirectory(app).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(suffix) && !it.name.startsWith(".") }
            .mapNotNull { file ->
                val id = file.name.removeSuffix(suffix)
                if (!WearLink.isSafeId(id) || id in BUNDLED) return@mapNotNull null
                val name = prefs.getString(Keys.name(id), null)
                if (name != null) return@mapNotNull Edition(id, name, bundled = false, language = prefs.getString(Keys.language(id), null)?.ifEmpty { null })
                // A file put here some other way (a restore, a debug push): read its meta once.
                try {
                    val edition = WatchEdition(id, AndroidEditionRows(file))
                    try {
                        val result = Edition(id, edition.name, bundled = false, language = edition.language)
                        prefs.edit().putString(Keys.name(id), result.name).putString(Keys.language(id), result.language.orEmpty()).apply()
                        result
                    } finally {
                        edition.close()
                    }
                } catch (e: RuntimeException) {
                    null
                }
            }
            .sortedBy { it.id }
        return bundled + received
    }

    private fun receivedFile(id: String): File = File(receivedDirectory(app), WatchEditionBuilder.fileName(id))

    private val snapshotFile: File get() = File(app.filesDir, VerseSnapshot.FILE_NAME)

    private fun readSnapshot(): VerseSnapshot? = try {
        snapshotFile.takeIf { it.exists() }?.readText()?.let(VerseSnapshot::decode)
    } catch (e: IOException) {
        null
    }

    /**
     * The edition for [id] (the current translation by default). The first open of a bundled one copies
     * it out of the APK — Android can't open an asset in place — so call off the main thread.
     */
    @Synchronized
    fun edition(id: String = translation): WatchEdition = opened.getOrPut(id) {
        val received = receivedFile(id)
        if (id !in BUNDLED && received.exists()) {
            WatchEdition(id, AndroidEditionRows(received))
        } else {
            WatchEdition(id, AndroidEditionRows(copyOut(WatchEditionBuilder.fileName(if (id in BUNDLED) id else TranslationChoice.FALLBACK))))
        }
    }

    /** The edition of [id] if the watch has it, else null. Call off the main thread. */
    fun editionIfPresent(id: String): WatchEdition? = if (editions.any { it.id == id }) edition(id) else null

    /**
     * The translation Verse of the Day shows ([WatchVerseOfDay.translation]): the one being read, unless
     * that is English on a device whose language has a Bible of its own — the reader sees no English.
     */
    fun dailyTranslation(catalog: DailyVerseCatalog?): String = WatchVerseOfDay.translation(
        current = translation,
        available = catalog?.translations.orEmpty(),
        languages = LocaleList.getDefault().toLanguageTags().split(','),
    )

    /** Today's passage as the app, the tile and the complication show it. Call off the main thread. */
    fun verseOfDay(at: Instant = Instant.now()): WatchVerseOfDay? {
        val catalog = WatchDaily.catalog(app)
        val shown = dailyTranslation(catalog)
        return WatchVerseOfDay.at(catalog, at, shown, numbering = numbering(shown))
    }

    /** Today and the next days, for the complication's timeline. Call off the main thread. */
    fun verseOfDayWeek(from: Instant = Instant.now()): List<Triple<Instant, Instant, WatchVerseOfDay>> {
        val catalog = WatchDaily.catalog(app)
        val shown = dailyTranslation(catalog)
        return WatchVerseOfDay.week(catalog, from, shown, numbering = numbering(shown))
    }

    /** [id]'s numbering if the watch holds a locale edition of it; the English Bibles number as the KJV. */
    private fun numbering(id: String): VerseNumbering =
        if (id in BUNDLED) VerseNumbering.IDENTITY else editionIfPresent(id)?.numbering ?: VerseNumbering.IDENTITY

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
        const val ACCENT = "watch.accent"
        fun name(id: String) = "watch.edition.$id.name"
        fun language(id: String) = "watch.edition.$id.language"
        fun digest(id: String) = "watch.edition.$id.digest"
    }

    companion object {
        /** Translations with a compact edition in the watch's APK. */
        val BUNDLED = listOf("ASV", "BSB", "KJV")
        private val NAMES = mapOf(
            "ASV" to "American Standard Version",
            "BSB" to "Berean Standard Bible",
            "KJV" to "King James Version",
        )

        /**
         * Where editions the phone sent are kept: the app's files, not its cache, which the system may
         * clear — `WatchBible.receivedDirectory` (Documents) on the Apple Watch.
         */
        fun receivedDirectory(context: Context): File = File(context.filesDir, "editions")

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
