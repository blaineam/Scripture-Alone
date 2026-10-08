package com.blainemiller.scripturealone.ui.widget

import android.content.Context
import android.os.ParcelFileDescriptor
import com.blainemiller.scripturealone.companion.LocaleBible
import com.blainemiller.scripturealone.companion.VerseSnapshot
import com.blainemiller.scripturealone.companion.WatchEditionBuilder
import com.blainemiller.scripturealone.companion.WearImports
import com.blainemiller.scripturealone.data.translations.ImportedTranslation
import com.blainemiller.scripturealone.data.translations.TranslationLibrary
import com.blainemiller.scripturealone.data.assets.AssetLibrary
import com.blainemiller.scripturealone.data.assets.AssetPack
import com.blainemiller.scripturealone.companion.WearLink
import com.blainemiller.scripturealone.data.BundledDatabase
import com.blainemiller.scripturealone.data.BundledTranslations
import com.blainemiller.scripturealone.data.sabible.WearableLicence
import com.google.android.gms.tasks.Tasks
import android.net.Uri
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The phone's end of the Wearable Data Layer — `WatchLink.swift`. Tells the watch which translation the
 * reader is using, its accent colour and the favorites/highlights/notes snapshot, as data items the watch reads
 * whenever it next can (the semantics of WatchConnectivity's application context).
 *
 * The watch carries the NASB 2020 (before 1.1.1, the ASV, BSB and KJV), so only the choice travels for
 * it. For any other Bible the phone downloaded as a pack it sends the watch its copy once the pack is on
 * the phone, as an asset in a data item of its own ([publishEdition]) — iOS's `transferFile`: the ASV and
 * the NASB 1995 as their sealed packages, encrypted on the watch too; the BSB, the KJV and the big-8
 * locales' Bibles ([LocaleBible]) as a compact watch edition ([WatchEditions]). A 1.1.0 watch refuses
 * what it already carries. Every translation the reader imported travels the same way ([publishImports]), with
 * the list of imports, so one removed on the phone leaves the watch too.
 *
 * **Licensed off the watch.** A sealed translation whose publisher keeps it off wearables
 * (`PackagePolicy.wearables: prohibited`) is never sent ([keptOffWatch]): not its package — an item an
 * older app put is deleted — and not its text, which the favorites/highlights/notes snapshot carries
 * only as references when it is in that translation. The watch is told why it isn't there.
 *
 * Every call is best-effort: a phone without Google Play services, or with no watch, simply has no one
 * to tell, and the Data Layer delivers to a watch paired later on its own.
 */
object WearPublisher {

    /** What the watch app carries itself, so is never sent: the sealed NASB 2020 (`WatchBible.BUNDLED`). */
    private val WATCH_CARRIES = setOf("NASB2020")

    fun publishTranslation(context: Context, translation: String, changedAt: Double) {
        val item = translationItem(
            translation, changedAt, sealedPackage(context, translation),
            importedAbbreviation = TranslationLibrary.imported(translation)?.info?.abbreviation,
        )
        if (WidgetPrefs.published(context, "translation") == item.signature) return
        val request = PutDataMapRequest.create(WearLink.PATH_TRANSLATION).apply {
            dataMap.putString(WearLink.KEY_TRANSLATION, item.translation)
            dataMap.putDouble(WearLink.KEY_CHANGED_AT, item.changedAt)
            if (item.notForWatch) dataMap.putBoolean(WearLink.KEY_NOT_FOR_WATCH, true)
            item.label?.let { dataMap.putString(WearLink.KEY_TRANSLATION_LABEL, it) }
        }.asPutDataRequest().setUrgent()
        if (put(context) { Wearable.getDataClient(context).putDataItem(request) }) {
            WidgetPrefs.setPublished(context, "translation", item.signature)
        }
    }

    /**
     * What the translation item tells the watch about the reader's choice: whether its terms keep it off
     * the watch ([notForWatch] — the watch then says why it isn't there) and, when the reader sees it called
     * something other than its id, that name for the watch's footer ([label]).
     */
    internal data class TranslationItem(val translation: String, val changedAt: Double, val notForWatch: Boolean, val label: String?) {
        /** Unchanged since the last put means nothing to send. */
        val signature: String get() = "t:$translation@$changedAt" + (if (notForWatch) ":off" else "") + (label?.let { ":$it" } ?: "")
    }

    /**
     * [TranslationItem] for [translation]: [sealed] is its sealed package on this phone, if it has one;
     * [importedAbbreviation] the abbreviation of the import it is, if it is one.
     */
    internal fun translationItem(translation: String, changedAt: Double, sealed: File?, importedAbbreviation: String?): TranslationItem {
        val header = sealed?.let(WearableLicence::unverifiedHeader)
        // As [keptOffWatch]: a sealed package whose header can't be read doesn't go either.
        val notForWatch = sealed != null && header?.policy?.allowsWearables != true
        // What the reader sees it called here, for the watch's footer: the package's or the import's own.
        val label = header?.translation?.abbreviation?.takeIf { it.isNotBlank() }
            ?: importedAbbreviation?.takeIf { it.isNotBlank() }
            ?: translation
        return TranslationItem(translation, changedAt, notForWatch, label.takeIf { it != translation })
    }

    /** The reader picked another accent colour ([hex], 0xRRGGBB, its dark value); the watch follows. */
    fun publishAccent(context: Context, hex: Int) {
        val signature = "a:$hex"
        if (WidgetPrefs.published(context, "accent") == signature) return
        val request = PutDataMapRequest.create(WearLink.PATH_ACCENT).apply {
            dataMap.putInt(WearLink.KEY_ACCENT, hex)
        }.asPutDataRequest()
        if (put(context) { Wearable.getDataClient(context).putDataItem(request) }) {
            WidgetPrefs.setPublished(context, "accent", signature)
        }
    }

    /** The snapshot as an asset: it can pass the 100 KB a data item may carry inline. */
    fun publishSnapshot(context: Context, full: VerseSnapshot) {
        // Text in a translation licensed off wearables stays on the phone: the watch gets references.
        val snapshot = full.forWatch(translationKeptOffWatch = keptOffWatch(context, full.translation))
        val json = snapshot.copy(generatedAt = java.time.Instant.EPOCH).encoded()
        val signature = "s:${json.hashCode()}:${json.length}"
        if (WidgetPrefs.published(context, "snapshot") == signature) return
        val request = PutDataMapRequest.create(WearLink.PATH_SNAPSHOT).apply {
            dataMap.putAsset(WearLink.KEY_SNAPSHOT, Asset.createFromBytes(snapshot.encoded().toByteArray()))
            dataMap.putString(WearLink.KEY_TRANSLATION, snapshot.translation)
        }.asPutDataRequest()
        if (put(context) { Wearable.getDataClient(context).putDataItem(request) }) {
            WidgetPrefs.setPublished(context, "snapshot", signature)
        }
    }

    /**
     * Sends the watch [translation] if it is a Bible the phone downloaded as a pack and the pack is here —
     * `WatchLink.sendEditionIfNeeded`. Blocking (it may write a few megabytes); call off the main thread.
     *
     * Each edition is its own data item, so it is sent once per database: the Data Layer keeps it and
     * hands it to the watch whenever the watch can take it — after a reinstall too — and the watch skips
     * an asset whose digest it already holds. The NASB 2020 is in the watch app; an online translation
     * has no file (and its terms forbid storing it).
     */
    fun publishEdition(context: Context, translation: String) {
        if (translation in WATCH_CARRIES || !WearLink.isSafeId(translation)) return
        val pack = AssetPack.forTranslation(translation) ?: return
        if (!AssetLibrary.isAttached) AssetLibrary.attach(context)
        if (!AssetLibrary.isOnDevice(pack)) return
        val source = AssetLibrary.file(context, pack) ?: return
        // The package's own terms, read from the bytes that would go.
        if (!WearableLicence.maySendToWatch(source, sealed = pack.isSealed)) {
            withdrawEdition(context, translation)
            return
        }
        val format = if (pack.isSealed) "sealed" else WatchEditionBuilder.FORMAT
        val signature = "e:$format:${source.length()}:${source.lastModified()}"
        if (WidgetPrefs.published(context, "edition.$translation") == signature) return
        // Most phones have no watch: don't write megabytes for no one. A watch paired later gets it at
        // the next launch or translation change.
        if (!hasWatch(context)) return
        // A sealed package goes as it is; anything else as the compact edition written from it.
        val edition = File(File(context.cacheDir, "WatchEditions"), if (pack.isSealed) "$translation.sabible" else WatchEditionBuilder.fileName(translation))
        try {
            if (pack.isSealed) {
                edition.parentFile?.mkdirs()
                source.copyTo(edition, overwrite = true)
            } else {
                WatchEditions.write(source, edition)
            }
        } catch (e: Exception) {
            android.util.Log.i("WearPublisher", "Watch edition of $translation not written: ${e.message}")
            return
        }
        try {
            ParcelFileDescriptor.open(edition, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val request = PutDataMapRequest.create(WearLink.editionPath(translation)).apply {
                    dataMap.putAsset(WearLink.KEY_EDITION, Asset.createFromFd(fd))
                    dataMap.putString(WearLink.KEY_TRANSLATION, translation)
                }.asPutDataRequest().setUrgent()
                // The Data Layer has its own copy once the put completes.
                if (put(context) { Wearable.getDataClient(context).putDataItem(request) }) {
                    WidgetPrefs.setPublished(context, "edition.$translation", signature)
                }
            }
        } finally {
            edition.delete()
        }
    }

    /**
     * Keeps the watch holding every translation the reader imported — `WatchLink.importsChanged`.
     * Blocking (it may write megabytes); call off the main thread, one call at a time.
     *
     * Nothing is sent until the library has scanned its directory ([loaded]): its first value is an
     * empty placeholder, and an empty list would tell the watch to delete every import it holds. Then
     * the list of ids goes as its own data item; an import removed here has its edition item deleted
     * too, or the watch would find it again at its next launch; and each import whose terms allow
     * offline storage is sent unless the watch reports holding that version ([WearImports.decide]).
     *
     * Returns false when a send failed, for the caller to try again shortly — iOS retries a failed
     * `transferFile` the same way.
     */
    fun publishImports(context: Context, loaded: Boolean, imports: List<ImportedTranslation>): Boolean {
        val all = imports.map { WearImports.Import(it.id, it.info.rights.allowOfflineStorage) }
        val offered = WearImports.offered(loaded, all) ?: return true
        var ok = true
        val listSignature = "i:" + offered.joinToString(",")
        if (WidgetPrefs.published(context, "imports") != listSignature) {
            val request = PutDataMapRequest.create(WearLink.PATH_IMPORTS).apply {
                dataMap.putStringArray(WearLink.KEY_IMPORTS, offered.toTypedArray())
            }.asPutDataRequest().setUrgent()
            if (put(context) { Wearable.getDataClient(context).putDataItem(request) }) {
                WidgetPrefs.setPublished(context, "imports", listSignature)
            } else {
                ok = false
            }
        }
        // Editions put for imports that are gone: delete their items.
        val sent = WidgetPrefs.published(context, "importEditions")?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
        val sendable = WearImports.sendable(loaded, all)
        val keep = sendable.map { it.id }.toSet()
        val remaining = sent.toMutableSet()
        for (id in sent.filter { it !in keep }) {
            val uri = Uri.Builder().scheme("wear").path(WearLink.editionPath(id)).build()
            if (put(context) { Wearable.getDataClient(context).deleteDataItems(uri, DataClient.FILTER_LITERAL) }) {
                remaining -= id
                WidgetPrefs.setPublished(context, "import.$id", "")
            } else {
                ok = false
            }
        }
        if (remaining != sent.toSet()) WidgetPrefs.setPublished(context, "importEditions", remaining.sorted().joinToString(","))
        if (sendable.isEmpty()) return ok
        val held = held(context)
        for (entry in sendable) {
            val translation = imports.firstOrNull { it.id == entry.id } ?: continue
            if (!publishImport(context, translation, held)) ok = false
        }
        return ok
    }

    /** One import's edition, if the watch needs it. False only when a send was attempted and failed. */
    private fun publishImport(context: Context, translation: ImportedTranslation, held: WearImports.Held?): Boolean {
        val id = translation.id
        val source = translation.file
        val version = WearImports.version(fingerprint(source) ?: return true)
        val record = WidgetPrefs.published(context, "import.$id").orEmpty()
        val putVersion = record.substringBefore('@').ifEmpty { null }
        val putAt = record.substringAfter('@', "0").toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        if (WearImports.decide(id, version, held, putVersion, putAt, now) == WearImports.Decision.SKIP) return true
        if (!hasWatch(context)) return true
        val edition = File(File(context.cacheDir, "WatchEditions"), WatchEditionBuilder.fileName(id))
        try {
            WatchEditions.write(source, edition)
        } catch (e: Exception) {
            android.util.Log.i("WearPublisher", "Watch edition of $id not written: ${e.message}")
            return true
        }
        try {
            ParcelFileDescriptor.open(edition, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val request = PutDataMapRequest.create(WearLink.editionPath(id)).apply {
                    dataMap.putAsset(WearLink.KEY_EDITION, Asset.createFromFd(fd))
                    dataMap.putString(WearLink.KEY_TRANSLATION, id)
                    dataMap.putString(WearLink.KEY_KIND, WearLink.KIND_IMPORT)
                    dataMap.putString(WearLink.KEY_VERSION, version)
                    // A re-send must differ from what was put, or the watch sees no change.
                    dataMap.putLong(WearLink.KEY_SENT_AT, now)
                }.asPutDataRequest().setUrgent()
                if (!put(context) { Wearable.getDataClient(context).putDataItem(request) }) return false
                WidgetPrefs.setPublished(context, "import.$id", "$version@$now")
                val sent = WidgetPrefs.published(context, "importEditions")?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
                if (id !in sent) WidgetPrefs.setPublished(context, "importEditions", (sent + id).sorted().joinToString(","))
                return true
            }
        } finally {
            edition.delete()
        }
    }

    /**
     * Whether [translation]'s publisher keeps it off watches (`PackagePolicy.wearables`) — read from
     * the sealed package on this phone, the NASB 2020 in the app or a downloaded pack. Unverified, which
     * is safe: a header edited to "allowed" fails the watch's signature check, so nothing it sends opens.
     * A translation that isn't a sealed package carries no such term.
     */
    fun keptOffWatch(context: Context, translation: String): Boolean {
        val file = sealedPackage(context, translation) ?: return false
        return !WearableLicence.allowsWearables(file)
    }

    /** [translation]'s sealed package on this phone — the NASB 2020 in the app or a downloaded pack — or null. */
    private fun sealedPackage(context: Context, translation: String): File? = try {
        when {
            translation in BundledTranslations.LICENSED && BundledDatabase.hasAsset(context, "$translation.sabible") ->
                BundledDatabase.file(context, "$translation.sabible")
            else -> {
                val pack = AssetPack.forTranslation(translation)?.takeIf { it.isSealed }
                if (pack == null) {
                    null
                } else {
                    if (!AssetLibrary.isAttached) AssetLibrary.attach(context)
                    if (AssetLibrary.isOnDevice(pack)) AssetLibrary.file(context, pack) else null
                }
            }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Deletes the edition item an earlier app version put for [translation], so a watch paired or
     * reinstalled later can't receive a package its licence now keeps off wearables.
     */
    private fun withdrawEdition(context: Context, translation: String) {
        if (WidgetPrefs.published(context, "edition.$translation").isNullOrEmpty()) return
        val uri = Uri.Builder().scheme("wear").path(WearLink.editionPath(translation)).build()
        if (put(context) { Wearable.getDataClient(context).deleteDataItems(uri, DataClient.FILTER_LITERAL) }) {
            WidgetPrefs.setPublished(context, "edition.$translation", "")
        }
    }

    /**
     * What the watch last reported holding ([WearLink.PATH_HELD]), or null when it never has — an
     * older watch app, or no watch.
     */
    private fun held(context: Context): WearImports.Held? = try {
        val uri = Uri.Builder().scheme("wear").path(WearLink.PATH_HELD).build()
        val items = Tasks.await(Wearable.getDataClient(context).getDataItems(uri, DataClient.FILTER_LITERAL), 20, TimeUnit.SECONDS)
        try {
            items.firstOrNull()?.let { item ->
                val map = DataMapItem.fromDataItem(item).dataMap
                val versions = map.getDataMap(WearLink.KEY_VERSIONS)
                WearImports.Held(
                    ids = map.getStringArray(WearLink.KEY_EDITIONS)?.toSet().orEmpty(),
                    versions = versions?.keySet()?.mapNotNull { key -> versions.getString(key)?.let { key to it } }?.toMap().orEmpty(),
                )
            }
        } finally {
            items.release()
        }
    } catch (e: Exception) {
        null
    }

    private val fingerprints = mutableMapOf<String, Pair<String, String>>()

    /**
     * SHA-256 of an imported store — `ImportedBibleSync.fingerprint(of:)`. A store is written once and
     * never changed, so it is hashed once per file size and date.
     */
    @Synchronized
    private fun fingerprint(file: File): String? {
        val stamp = "${file.length()}:${file.lastModified()}"
        fingerprints[file.path]?.takeIf { it.first == stamp }?.let { return it.second }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }.also { fingerprints[file.path] = stamp to it }
        } catch (e: java.io.IOException) {
            null
        }
    }

    private fun hasWatch(context: Context): Boolean = try {
        Tasks.await(Wearable.getNodeClient(context).connectedNodes, 20, TimeUnit.SECONDS).isNotEmpty()
    } catch (e: Exception) {
        false
    }

    /** Runs a Play services call to completion off the main thread; false if it could not. */
    private fun put(context: Context, call: () -> com.google.android.gms.tasks.Task<*>): Boolean = try {
        Tasks.await(call(), 20, TimeUnit.SECONDS)
        true
    } catch (e: Exception) {
        // ApiException "Wearable.API is not available", a timeout, no Play services at all — all mean
        // "no watch to tell right now". Nothing to do but try again on the next change.
        android.util.Log.i("WearPublisher", "Not sent to the watch: ${e.message}")
        false
    }
}
