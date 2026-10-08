package com.blainemiller.scripturealone.app

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutManager
import android.net.Uri
import android.service.quicksettings.Tile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blainemiller.scripturealone.MainActivity
import com.blainemiller.scripturealone.ui.share.TextEntryActivity
import com.blainemiller.scripturealone.ui.shortcuts.AppShortcuts
import com.blainemiller.scripturealone.ui.tile.VerseOfDayTile
import com.blainemiller.scripturealone.ui.widget.DailyVerseLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * The Android-only ways into the app (1.1.4 parity pass), on Robolectric: Open in Scripture Alone and
 * Share › Scripture Alone for text (the TextEntryActivity trampoline), the Verse of the Day Quick
 * Settings tile, and the dynamic Listen shortcut named for the chapter.
 */
@RunWith(AndroidJUnit4::class)
class SystemEntryPointsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun trampoline(intent: Intent): Intent? {
        val controller = Robolectric.buildActivity(TextEntryActivity::class.java, intent).create()
        assertTrue("the trampoline never stays", controller.get().isFinishing)
        return shadowOf(app).nextStartedActivity
    }

    @Test fun selectedTextGoesToTheReaderInItsOwnTask() {
        val started = trampoline(Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, "see John 3:16"))
        assertNotNull(started)
        assertEquals(MainActivity::class.java.name, started!!.component?.className)
        assertEquals(MainActivity.ACTION_OPEN_TEXT, started.action)
        assertEquals("see John 3:16", started.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue("never in the sending app's task", started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun sharedTextIsBoundedAndBlankTextOpensNothing() {
        val long = "word ".repeat(2_000)
        val started = trampoline(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, long))
        assertEquals(TextEntryActivity.MAX_LENGTH, started!!.getStringExtra(Intent.EXTRA_TEXT)!!.length)
        assertNull("blank text opens nothing", trampoline(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "   ")))
        assertNull("another action opens nothing", trampoline(Intent(Intent.ACTION_VIEW)))
    }

    @Test fun theTileShowsTodaysReferenceAndIsNeverASwitch() {
        val service = Robolectric.setupService(VerseOfDayTile::class.java)
        service.onStartListening()
        val tile: Tile = service.qsTile
        val today = DailyVerseLibrary.catalog(app)?.verse(Instant.now())?.range?.abbreviatedDisplay
        assertNotNull("the catalogue ships in the app", today)
        assertEquals("Verse of the Day", tile.label)
        assertEquals(today, tile.subtitle)
        assertTrue(tile.contentDescription.toString().contains(today!!))
        assertEquals(Tile.STATE_INACTIVE, tile.state)
    }

    /** Below Android 14 the tile hands the system an Intent; the shade collapses onto today's passage. */
    @Test @Config(sdk = [33])
    fun tappingTheTileOpensTodaysPassage() {
        val service = Robolectric.setupService(VerseOfDayTile::class.java)
        service.onClick()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Uri.parse(VerseOfDayTile.TODAY_URL), started.data)
        assertEquals(MainActivity::class.java.name, started.component?.className)
    }

    @Test fun theListenShortcutIsNamedForTheChapterAndPushedOnlyWhenItChanges() {
        val manager = app.getSystemService(ShortcutManager::class.java)
        AppShortcuts.updateListen(app, "John 3")
        val listen = manager.dynamicShortcuts.single()
        assertEquals("Listen to John 3", listen.longLabel)
        assertEquals(Uri.parse(AppShortcuts.LISTEN_URL), listen.intent?.data)
        AppShortcuts.updateListen(app, "")
        assertEquals("an empty name changes nothing", "Listen to John 3", manager.dynamicShortcuts.single().longLabel)
        AppShortcuts.updateListen(app, "Romans 8")
        assertEquals("Listen to Romans 8", manager.dynamicShortcuts.single().longLabel)
    }
}
