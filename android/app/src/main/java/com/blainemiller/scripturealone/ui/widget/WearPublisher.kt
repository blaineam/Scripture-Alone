package com.blainemiller.scripturealone.ui.widget

import android.content.Context
import android.os.ParcelFileDescriptor
import com.blainemiller.scripturealone.companion.LocaleBible
import com.blainemiller.scripturealone.companion.VerseSnapshot
import com.blainemiller.scripturealone.companion.WatchEditionBuilder
import com.blainemiller.scripturealone.data.assets.AssetLibrary
import com.blainemiller.scripturealone.data.assets.AssetPack
import com.blainemiller.scripturealone.companion.WearLink
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The phone's end of the Wearable Data Layer — `WatchLink.swift`. Tells the watch which translation the
 * reader is using, its accent colour and the favorites/highlights/notes snapshot, as data items the watch reads
 * whenever it next can (the semantics of WatchConnectivity's application context).
 *
 * The watch bundles the ASV, BSB and KJV, so only the choice travels for those. For one of the big-8
 * locales' Bibles ([LocaleBible]) the phone also writes the watch edition ([WatchEditions]) once its pack
 * is on the phone and sends it as an asset in a data item of its own ([publishEdition]) — iOS's
 * `transferFile`. Sending an *imported* translation's edition waits for import on Android.
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
