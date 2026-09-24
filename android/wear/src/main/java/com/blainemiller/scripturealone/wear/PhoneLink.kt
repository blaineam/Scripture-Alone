package com.blainemiller.scripturealone.wear

import android.content.Context
import android.util.Log
import com.blainemiller.scripturealone.companion.WearLink
import com.google.android.gms.tasks.Tasks
import android.net.Uri
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.util.concurrent.TimeUnit

/**
 * The watch's end of the Data Layer — `WatchPhoneLink` on the Apple Watch: which translation the reader
 * uses on the phone, the accent colour chosen there, the phone's favorites/highlights/notes snapshot, and the editions of the locale
 * Bibles the watch doesn't bundle and of every translation the reader imported (one data item each,
 * [WearLink.PATH_EDITION_PREFIX]), with the list of imports ([WearLink.PATH_IMPORTS]) that removes one
 * deleted on the phone. The watch reports back what it holds ([reportHeld]).
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
    fun apply(context: Context, item: DataItem, report: Boolean = true) {
        val bible = WatchBible.get(context)
        val path = item.uri.path ?: return
        val map = DataMapItem.fromDataItem(item).dataMap
        when (path) {
            WearLink.PATH_TRANSLATION -> {
                val id = map.getString(WearLink.KEY_TRANSLATION) ?: return
                if (!WearLink.isSafeId(id)) return
                bible.phoneChose(id, map.getDouble(WearLink.KEY_CHANGED_AT))
            }
            WearLink.PATH_ACCENT -> bible.phoneAccent(map.getInt(WearLink.KEY_ACCENT))
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
            WearLink.PATH_IMPORTS -> {
                val offered = map.getStringArray(WearLink.KEY_IMPORTS)?.filter(WearLink::isSafeId) ?: return
                val before = bible.held().ids
                bible.phoneImports(offered)
                // An import newly on the list whose edition is already in the Data Layer — it arrived
                // before the list and was set aside — is taken now.
                for (id in offered.filter { it !in before }) fetchEdition(context, id)
                if (report) reportHeld(context)
            }
            else -> {
                val id = WearLink.editionId(path) ?: return
                val asset = map.getAsset(WearLink.KEY_EDITION) ?: return
                val isImport = map.getString(WearLink.KEY_KIND) == WearLink.KIND_IMPORT
                // catchUp re-reads every item at each launch: a few megabytes only when they changed.
                if (bible.holdsEdition(id, asset.digest)) return
                val received = try {
                    Tasks.await(Wearable.getDataClient(context).getFdForAsset(asset), 120, TimeUnit.SECONDS)
                        .inputStream.use { bible.receiveEdition(id, it, asset.digest, map.getString(WearLink.KEY_VERSION), isImport) }
                } catch (e: Exception) {
                    Log.i("PhoneLink", "Edition $id not read: ${e.message}")
                    false
                }
                if (received && report) reportHeld(context)
            }
        }
    }

    /** Applies the phone's edition item for [id], if the Data Layer holds one. */
    private fun fetchEdition(context: Context, id: String) {
        try {
            val uri = Uri.Builder().scheme("wear").path(WearLink.editionPath(id)).build()
            val items = Tasks.await(Wearable.getDataClient(context).getDataItems(uri, DataClient.FILTER_LITERAL), 30, TimeUnit.SECONDS)
            try {
                items.forEach { apply(context, it, report = false) }
            } finally {
                items.release()
            }
        } catch (e: Exception) {
            Log.i("PhoneLink", "Edition $id not found: ${e.message}")
        }
    }

    /**
     * Tells the phone which editions the watch holds, and the version of each — `reportEditions` — so
     * it sends one the watch lacks (after a reinstall, say) or holds an older copy of. A data item
     * holds its latest value, so an unchanged report raises nothing on the phone.
     */
    fun reportHeld(context: Context) {
        val held = WatchBible.get(context).held()
        val request = PutDataMapRequest.create(WearLink.PATH_HELD).apply {
            dataMap.putStringArray(WearLink.KEY_EDITIONS, held.ids.sorted().toTypedArray())
            dataMap.putDataMap(WearLink.KEY_VERSIONS, DataMap().apply { held.versions.forEach { (id, version) -> putString(id, version) } })
        }.asPutDataRequest()
        try {
            Tasks.await(Wearable.getDataClient(context).putDataItem(request), 30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.i("PhoneLink", "Holdings not reported: ${e.message}")
        }
    }

    /** Whatever the phone set while the watch app wasn't running. Blocking; call off the main thread. */
    fun catchUp(context: Context) {
        try {
            val items = Tasks.await(Wearable.getDataClient(context).dataItems, 30, TimeUnit.SECONDS)
            try {
                // The list of imports first, so an edition of an import removed since is set aside.
                items.filter { it.uri.path?.startsWith(WearLink.PATH_PREFIX) == true }
                    .sortedBy { if (it.uri.path == WearLink.PATH_IMPORTS) 0 else 1 }
                    .forEach { apply(context, it, report = false) }
            } finally {
                items.release()
            }
            reportHeld(context)
        } catch (e: Exception) {
            // No Play services, no paired phone, a timeout: the watch keeps what it had.
            Log.i("PhoneLink", "No phone data: ${e.message}")
        }
    }
}
