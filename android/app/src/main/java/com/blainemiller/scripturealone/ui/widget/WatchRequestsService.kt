package com.blainemiller.scripturealone.ui.widget

import android.util.Log
import com.blainemiller.scripturealone.companion.WearFavorites
import com.blainemiller.scripturealone.companion.WearLink
import com.blainemiller.scripturealone.data.userdata.BundledUserDatabase
import com.blainemiller.scripturealone.data.userdata.UserDataChanges
import com.blainemiller.scripturealone.data.userdata.UserDataStore
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What the watch asks of the phone — today, the heart on a verse ([WearFavorites]). On the Apple Watch
 * the tap writes the shared iCloud store; here the watch puts a request in the Data Layer and the
 * system starts this service for it, even with the app closed. The request is written into the reader's
 * library and then deleted, so it is taken once; the new snapshot reaches the watch the usual way
 * (`WidgetSync`, which watches the database file).
 */
class WatchRequestsService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val requests = events.filter { it.type == DataEvent.TYPE_CHANGED }.mapNotNull { event ->
            val item = event.dataItem
            val range = WearFavorites.range(item.uri.path ?: return@mapNotNull null) ?: return@mapNotNull null
            val map = DataMapItem.fromDataItem(item).dataMap
            item.uri to WearFavorites.Request(range, map.getBoolean(WearLink.KEY_FAVORITE), map.getLong(WearLink.KEY_AT))
        }
        if (requests.isEmpty()) return
        val file = File(filesDir, UserDataWidgetSource.FILE_NAME)
        var changed = false
        val db = BundledUserDatabase(file)
        try {
            val store = UserDataStore(db)
            for ((uri, request) in requests) {
                if (store.applyWatchFavorite(request) != WearFavorites.Action.NONE) changed = true
                try {
                    Tasks.await(Wearable.getDataClient(this).deleteDataItems(uri), 20, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    // Left in place it is applied again later, which changes nothing a second time.
                    Log.i("WatchRequests", "Request not cleared: ${e.message}")
                }
            }
        } finally {
            db.close()
        }
        if (changed) UserDataChanges.notifyExternalWrite()
    }
}
