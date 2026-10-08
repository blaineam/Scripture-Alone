package com.blainemiller.scripturealone.data.share

import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.reference.Passage
import com.blainemiller.scripturealone.ui.shortcuts.AppShortcuts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Listen and Create Verse Image commands (shortcuts, App Actions) and text shared into the app. */
class AppCommandOutsideTest {
    private val john316 = AppLink.Typed(listOf(Passage.of(BookID.JOHN, 3, 16)))

    @Test fun listenParses() {
        assertEquals(AppCommand.Listen(null), AppCommand.parse("scripturealone://listen"))
        assertEquals(AppCommand.Listen(john316), AppCommand.parse("scripturealone://listen?ref=John%203:16"))
        assertEquals(AppCommand.Listen(null), AppCommand.parse("scripturealone://feature?name=listen"))
        assertEquals(AppCommand.Listen(null), AppCommand.feature("Listen to chapter"))
    }

    @Test fun verseImageParses() {
        assertEquals(AppCommand.VerseImage(null), AppCommand.parse("scripturealone://verse-image"))
        assertEquals(AppCommand.VerseImage(john316), AppCommand.parse("scripturealone://verse-image?ref=John%203:16"))
        assertEquals(AppCommand.VerseImage(null), AppCommand.parse("scripturealone://feature?name=verse_image"))
        assertEquals(AppCommand.VerseImage(null), AppCommand.feature("create verse image"))
    }

    @Test fun aWebPageCannotStartListen() {
        val listen = AppCommand.Listen(john316)
        assertTrue(listen.needsTrust)
        assertFalse(listen.changesData)
        assertEquals(AppCommand.Link(john316), listen.readOnly)
        assertNull(AppCommand.Listen(null).readOnly)
        // A verse image changes nothing and speaks nothing: allowed.
        assertFalse(AppCommand.VerseImage(null).needsTrust)
    }

    @Test fun shortcutsReportTheirIds() {
        assertEquals(AppCommand.FEATURE_LISTEN, AppShortcuts.shortcutId(AppShortcuts.LISTEN_URL))
        assertEquals(AppCommand.FEATURE_VERSE_IMAGE, AppShortcuts.shortcutId("scripturealone://verse-image"))
        assertNull(AppShortcuts.shortcutId("scripturealone://verse-image?ref=John%203:16"))
    }

    @Test fun sharedTextOpensTheReferencesInIt() {
        assertEquals(AppCommand.Link(john316), SharedText.command("  John 3:16 "))
        val both = SharedText.command("Read John 3:16 and Rom 8:28 this week") as AppCommand.Link
        assertEquals(
            listOf(Passage.of(BookID.JOHN, 3, 16), Passage.of(BookID.ROMANS, 8, 28)),
            (both.link as AppLink.Typed).passages,
        )
        // Another language the app reads.
        val french = SharedText.command("Jean 3:16") as AppCommand.Link
        assertEquals(listOf(Passage.of(BookID.JOHN, 3, 16)), (french.link as AppLink.Typed).passages)
    }

    @Test fun sharedTextFallsBackToSearch() {
        assertEquals(AppCommand.Link(AppLink.Search("love one another")), SharedText.command("love   one\nanother"))
        val long = "word ".repeat(60)
        assertEquals(AppCommand.Link(AppLink.Search(List(SharedText.SEARCH_WORDS) { "word" }.joinToString(" "))), SharedText.command(long))
        assertNull(SharedText.command("   "))
        assertNull(SharedText.command(null))
    }

    @Test fun sharedTextNeverChangesData() {
        // An app's own link in shared text is followed, but only to look.
        val fav = SharedText.command("look: scripturealone://favorite?ref=John%203:16")
        assertEquals(AppCommand.Link(john316), fav)
        assertEquals(AppCommand.Link(AppLink.Favorites), SharedText.command("scripturealone://favorites"))
        assertEquals(AppCommand.Link(john316), SharedText.command("scripturealone://listen?ref=John%203:16"))
    }
}
