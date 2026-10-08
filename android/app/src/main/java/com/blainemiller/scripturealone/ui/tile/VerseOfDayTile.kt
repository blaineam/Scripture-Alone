package com.blainemiller.scripturealone.ui.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.blainemiller.scripturealone.MainActivity
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.ui.widget.DailyVerseLibrary
import java.time.Instant

/**
 * A Quick Settings tile for the Verse of the Day — Android-only. The tile's subtitle is today's
 * reference ("Ps 23:1"), so a pull-down shows it; a tap opens the passage, selected, in the reader
 * (`scripturealone://today`, the shortcut's link).
 *
 * Chosen over a Listen/Continue tile: Listen already has its controls in the shade (the media
 * notification) while it plays, so a tile would only duplicate them, and starting speech from a tile
 * means starting a media service from the background, which Android restricts; a tile that changes
 * every day and opens the app on something new is the more useful one.
 */
class VerseOfDayTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_votd_label)
        val reference = DailyVerseLibrary.catalog(this)?.verse(Instant.now())?.range?.abbreviatedDisplay
        tile.subtitle = reference
        tile.contentDescription = reference?.let { getString(R.string.tile_votd_description, it) } ?: getString(R.string.tile_votd_label)
        // A shortcut, not a switch: nothing is on or off.
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TODAY_URL), this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun(open) else open()
    }

    companion object {
        const val TODAY_URL = "scripturealone://today"
    }
}
