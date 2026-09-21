package com.blainemiller.scripturealone.ui.widget

import android.content.Context
import com.blainemiller.scripturealone.companion.VerseSnapshot
import com.blainemiller.scripturealone.companion.WearLink
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit

/**
 * The phone's end of the Wearable Data Layer — `WatchLink.swift`. Tells the watch which translation the
 * reader is using and sends it the favorites/highlights/notes snapshot, as data items the watch reads
 * whenever it next can (the semantics of WatchConnectivity's application context).
 *
 * The watch bundles the ASV, BSB and KJV, so only the choice travels for those. Sending an *imported*
 * translation's watch edition (`WatchEdition` + a file transfer on iOS) waits for import on Android.
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
