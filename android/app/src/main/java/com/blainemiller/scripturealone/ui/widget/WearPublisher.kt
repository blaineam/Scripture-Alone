package com.blainemiller.scripturealone.ui.widget

import android.content.Context
import android.os.ParcelFileDescriptor
import com.blainemiller.scripturealone.companion.LocaleBible
import com.blainemiller.scripturealone.companion.VerseSnapshot
import com.blainemiller.scripturealone.companion.WatchEditionBuilder
import com.blainemiller.scripturealone.companion.WearImports
import com.blainemiller.scripturealone.data.translations.ImportedTranslation
import com.blainemiller.scripturealone.data.assets.AssetLibrary
import com.blainemiller.scripturealone.data.assets.AssetPack
import com.blainemiller.scripturealone.companion.WearLink
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
 * The watch bundles the ASV, BSB and KJV, so only the choice travels for those. For one of the big-8
 * locales' Bibles ([LocaleBible]) the phone also writes the watch edition ([WatchEditions]) once its pack
 * is on the phone and sends it as an asset in a data item of its own ([publishEdition]) — iOS's
 * `transferFile`. Every translation the reader imported travels the same way ([publishImports]), with
 * the list of imports, so one removed on the phone leaves the watch too.
 *
 * Every call is best-effort: a phone without Google Play services, or with no watch, simply has no one
 * to tell, and the Data Layer delivers to a watch paired later on its own.
 */
object WearPublisher {

    fun publishTranslation(context: Context, translation: String, changedAt: Double) {
        val signature = "t:$translation@$changedAt"
        if (WidgetPrefs.published(context, "translation") == signature) return
        val request = PutDataMapRequest.create(WearLink.PATH_TRANSLATION).apply {
            dataMap.putString(WearLink.KEY_TRANSLATION, translation)
            dataMap.putDouble(WearLink.KEY_CHANGED_AT, changedAt)
        }.asPutDataRequest().setUrgent()
        if (put(context) { Wearable.getDataClient(context).putDataItem(request) }) {
            WidgetPrefs.setPublished(context, "translation", signature)
        }
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
    fun publishSnapshot(context: Context, snapshot: VerseSnapshot) {
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
     * Sends the watch the edition of [translation] if it is a locale Bible whose pack is on the phone —
     * `WatchLink.sendEditionIfNeeded`. Blocking (it may write a few megabytes); call off the main thread.
     *
     * Each edition is its own data item, so it is sent once per database: the Data Layer keeps it and
     * hands it to the watch whenever the watch can take it — after a reinstall too — and the watch skips
     * an asset whose digest it already holds. The English Bibles are bundled on the watch; an online
     * translation has no file (and its terms forbid storing it).
     */
    fun publishEdition(context: Context, translation: String) {
        if (!LocaleBible.isLocaleBible(translation) || !WearLink.isSafeId(translation)) return
        val pack = AssetPack.forTranslation(translation) ?: return
        if (!AssetLibrary.isAttached) AssetLibrary.attach(context)
        if (!AssetLibrary.isOnDevice(pack)) return
        val source = AssetLibrary.file(context, pack) ?: return
        val signature = "e:${WatchEditionBuilder.FORMAT}:${source.length()}:${source.lastModified()}"
        if (WidgetPrefs.published(context, "edition.$translation") == signature) return
        // Most phones have no watch: don't write megabytes for no one. A watch paired later gets it at
        // the next launch or translation change.
        if (!hasWatch(context)) return
        val edition = File(File(context.cacheDir, "WatchEditions"), WatchEditionBuilder.fileName(translation))
        try {
            WatchEditions.write(source, edition)
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
