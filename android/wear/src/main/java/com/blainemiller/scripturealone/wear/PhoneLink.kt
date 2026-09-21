package com.blainemiller.scripturealone.wear

import android.content.Context
import android.util.Log
import com.blainemiller.scripturealone.companion.WearLink
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.util.concurrent.TimeUnit

/**
 * The watch's end of the Data Layer — `WatchPhoneLink` on the Apple Watch: which translation the reader
 * uses on the phone, and the phone's favorites/highlights/notes snapshot.
 *
 * The phone writes each as a data item at a fixed path ([WearLink]). A data item holds only its latest
 * value and reaches the watch whenever the two can talk — WatchConnectivity's application context, in
 * effect — so a stale value is never replayed after a newer one. The system starts this service for a
 * change even when the app isn't running; [catchUp] reads the current items at launch as well.
 */
class PhoneLinkService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type == DataEvent.TYPE_CHANGED) PhoneLink.apply(this, event.dataItem)
        }
    }
}

object PhoneLink {

    /** Applies one data item from the phone. Runs on a binder or IO thread, never the main one. */
    fun apply(context: Context, item: DataItem) {
        val bible = WatchBible.get(context)
        val path = item.uri.path ?: return
        val map = DataMapItem.fromDataItem(item).dataMap
        when (path) {
            WearLink.PATH_TRANSLATION -> {
                val id = map.getString(WearLink.KEY_TRANSLATION) ?: return
                if (!WearLink.isSafeId(id)) return
                bible.phoneChose(id, map.getDouble(WearLink.KEY_CHANGED_AT))
            }
            WearLink.PATH_SNAPSHOT -> {
                val asset = map.getAsset(WearLink.KEY_SNAPSHOT) ?: return
                val bytes = try {
                    Tasks.await(Wearable.getDataClient(context).getFdForAsset(asset), 30, TimeUnit.SECONDS)
                        .inputStream.use { it.readBytes() }
                } catch (e: Exception) {
                    Log.i("PhoneLink", "Snapshot not read: ${e.message}")
                    return
                }
                bible.receiveSnapshot(bytes.decodeToString())
            }
        }
    }

    /** Whatever the phone set while the watch app wasn't running. Blocking; call off the main thread. */
    fun catchUp(context: Context) {
        try {
            val items = Tasks.await(Wearable.getDataClient(context).dataItems, 30, TimeUnit.SECONDS)
            try {
                items.filter { it.uri.path?.startsWith(WearLink.PATH_PREFIX) == true }.forEach { apply(context, it) }
            } finally {
                items.release()
            }
        } catch (e: Exception) {
            // No Play services, no paired phone, a timeout: the watch keeps what it had.
            Log.i("PhoneLink", "No phone data: ${e.message}")
        }
    }
}
