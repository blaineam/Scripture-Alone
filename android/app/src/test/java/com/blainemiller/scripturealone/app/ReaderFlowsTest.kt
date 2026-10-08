package com.blainemiller.scripturealone.app

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blainemiller.scripturealone.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The reader on a phone, end to end: John 1 from the sealed ASV, the chapter arrows, selecting a
 * verse and what the selection bar does with it (highlight — drawn as rounded bands — favorite, note),
 * the verse-image designer and its Ctrl+S, and text handed over by another app.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ReaderFlowsTest : AppTest() {
    @get:Rule override val rule = createEmptyComposeRule()

    private val verse1 = "Verse 1. In the beginning was the Word"

    @Test fun theReaderOpensJohnOneAndTheArrowsTurnChapters() {
        launch()
        assertShown("John 1:1", desc(verse1))
        assertShown("the John 1 heading", desc("John 1", substring = false))
        tapDesc("Next Chapter")
        assertShown("John 2", desc("John 2", substring = false))
        assertShown("the go-to button naming John 2", desc("currently John 2"))
        tapDesc("Previous Chapter")
        assertShown("John 1 again", desc(verse1))
    }

    @Test fun aSelectedVerseIsHighlightedFavoritedAndListedInNotes() {
        launch()
        tapDesc(verse1)
        assertShown("the selection bar", desc("Clear Selection"))
        tapDesc("Highlight Yellow")
        // TalkBack hears the highlight on the verse; the reader draws it as one rounded band.
        val state = desc(verse1).onFirst().fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals("Highlighted yellow", state?.substringBefore(',')?.trim())

        // Highlighting ends the selection, as on iOS; select the verse again to favorite it.
        assertFalse("highlighting clears the selection", desc("Clear Selection").exists())
        tapDesc(verse1)
        tapDesc("Add to Favorites")
        // The library writes on its own thread; the bar follows when the store reports back.
        waitFor("the favorite to be saved") { desc("Remove from Favorites").exists() }
        tapDesc("Clear Selection")
        assertFalse("the selection bar closes", desc("Clear Selection").exists())

        tapDesc("Notes", substring = false)
        tapText("Highlights")
        waitFor("John 1:1 under Highlights") { text("John 1:1").exists() }
        tapText("Favorites")
        waitFor("John 1:1 under Favorites") { text("John 1:1").exists() }
    }

    @Test fun theVerseImageDesignerSavesOnCtrlS() {
        launch()
        tapDesc(verse1)
        tapDesc("Share", substring = false)
        tapText("Share Image…")
        assertShown("the designer's styles", text("Styles"))
        assertShown("the preview", desc("Preview:"))
        // A style, then Customize: the fine controls.
        tapText("Customize")
        assertShown("the background choices", text("Background"))
        assertShown("the text colour choices", text("Text Color"))
        tapText("Textured")
        // Ctrl+S saves, as the Save to Photos button does — and isn't passed on as a typed "s".
        val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_S, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        var handled = false
        scenario!!.onActivity { handled = it.dispatchKeyEvent(down) }
        assertTrue("Ctrl+S is the designer's", handled)
        waitFor("the save reporting back") { text("Saved to Photos").exists() || text("Can’t make the image.").exists() }
        assertTrue("the card was saved", text("Saved to Photos").exists())
    }

    @Test fun ctrlSOutsideTheDesignerIsNotTaken() {
        launch()
        val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_S, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        var handled = true
        scenario!!.onActivity { handled = it.dispatchKeyEvent(down) }
        assertFalse("with no designer open, Ctrl+S is nobody's", handled)
    }

    /** "Open in Scripture Alone" on a selection elsewhere (ACTION_PROCESS_TEXT → TextEntryActivity). */
    @Test fun textFromAnotherAppOpensItsReference() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        launch(
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_TEXT)
                .putExtra(Intent.EXTRA_TEXT, "Sunday's reading: Romans 8:28 — all things work together"),
            skipWelcome = true,
        )
        settle(3_000)
        assertShown("Romans 8", desc("currently Romans 8"))
    }
}
