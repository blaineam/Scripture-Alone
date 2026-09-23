package com.blainemiller.scripturealone.data.share

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.reference.Passage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/** `AppLinkRoutingTests` in `OSISReferenceTests.swift`, case for case, then the Android-only commands. */
class AppLinkRoutingTest {
    private fun link(url: String) = AppLink.parse(url)

    @Test fun keepsTheStoredKeyForm() {
        assertEquals(AppLink.Open(listOf(VerseRange.of(VerseRef(43, 3, 16), VerseRef(43, 3, 17)))), link("scripturealone://open?ref=43003016-43003017"))
        assertEquals(AppLink.Open(listOf(VerseRange.of(VerseRef(43, 3, 16)))), link("scripturealone://open?ref=43003016"))
    }

    @Test fun opensOSIS() {
        val john316 = AppLink.Osis(listOf(Passage.of(BookID.JOHN, 3, 16)))
        assertEquals(john316, link("scripturealone://open?ref=John.3.16"))
        assertEquals(john316, link("scripturealone://open?ref=urn:osis:John.3.16"))
        assertEquals(john316, link("scripturealone://open?ref=osis:John.3.16"))
        assertEquals(john316, link("scripturealone://passage/John.3.16"))
        assertEquals(john316, link("scripturealone://passage/urn:osis:John.3.16"))
        assertEquals(AppLink.Osis(listOf(Passage.of(BookID.PSALMS, 23))), link("scripturealone://open?ref=Ps.23"))
        assertEquals(AppLink.Osis(listOf(Passage.of(BookID.GENESIS, 1, 1, 1, 3))), link("scripturealone://open?ref=Gen.1.1-Gen.1.3"))
        // The link a previous run found not moving a running reader.
        assertEquals(AppLink.Osis(listOf(Passage.of(BookID.ACTS, 13, 4))), link("scripturealone://open?ref=Acts.13.4"))
        assertEquals(
            AppLink.Osis(listOf(Passage.of(BookID.FIRST_CORINTHIANS, 13, 4, 13, 7))),
            link("scripturealone://open?ref=1Cor.13.4-1Cor.13.7"),
        )
    }

    @Test fun opensPlainReferencesInAnyLanguage() {
        val john316 = AppLink.Typed(listOf(Passage.of(BookID.JOHN, 3, 16)))
        assertEquals(john316, link("scripturealone://open?ref=John%203:16"))
        assertEquals(john316, AppLink.reference("John 3:16", language = "en"))
        assertEquals(john316, AppLink.reference("Jean 3:16", language = "fr"))
        assertEquals(john316, AppLink.reference("Johannes 3,16", language = "de"))
        assertEquals(john316, AppLink.reference("约翰福音 3:16", language = "zh-Hans"))
        assertEquals(john316, AppLink.reference("요한복음 3:16", language = "ko"))
        assertEquals(AppLink.Typed(listOf(Passage.of(BookID.ROMANS, 8, 28, 8, 39))), link("scripturealone://passage/Rom%208:28-39"))
        // Unencoded, as `adb shell am start -d` or a pasted link may deliver it.
        assertEquals(john316, link("scripturealone://open?ref=John 3:16"))
        assertEquals(john316, link("scripturealone://open?ref=%E7%BA%A6%E7%BF%B0%E7%A6%8F%E9%9F%B3%203:16"))
    }

    @Test fun opensOtherPlaces() {
        assertEquals(AppLink.Search("love one another"), link("scripturealone://search?q=love%20one%20another"))
        assertEquals(AppLink.Search("love one another"), link("scripturealone://search?q=love+one+another"))
        val id = UUID.randomUUID()
        assertEquals(AppLink.NoteLink(id), link("scripturealone://note/$id"))
        assertEquals(AppLink.NoteLink(id), link("scripturealone://note/${id.toString().uppercase()}"))
        assertEquals(AppLink.NoteLink(id), link(AppLink.noteUrl(id)))
        assertEquals(AppLink.Notes, link("scripturealone://notes"))
        assertEquals(AppLink.Favorites, link("scripturealone://favorites"))
        assertNull(link("scripturealone://note/not-a-uuid"))
        assertNull(link("scripturealone://note/1-1-1-1-1"))
        assertNull(link("scripturealone://search"))
        assertNull(link("scripturealone://somewhere"))
    }

    @Test fun opensWebPassages() {
        val john316 = AppLink.Osis(listOf(Passage.of(BookID.JOHN, 3, 16)))
        assertEquals(john316, link("https://wemiller.com/apps/scripture-alone/?ref=John.3.16"))
        assertEquals(john316, link("https://wemiller.com/apps/scripture-alone/#ref=John.3.16"))
        assertEquals(john316, link("https://wemiller.com/apps/scripture-alone/passage/John.3.16"))
        assertEquals(john316, link("https://www.wemiller.com/apps/scripture-alone/passage/urn:osis:John.3.16"))
        // Only the passage forms on the web; the app's own places stay on the custom scheme.
        assertNull(link("https://wemiller.com/apps/scripture-alone/notes"))
        assertNull(link("https://example.com/apps/scripture-alone/?ref=John.3.16"))
        assertNull(link("https://wemiller.com/apps/other/?ref=John.3.16"))
    }

    @Test fun parsesAndroidCommands() {
        assertEquals(AppCommand.VerseOfTheDay, AppCommand.parse("scripturealone://today"))
        assertEquals(AppCommand.ContinueReading, AppCommand.parse("scripturealone://continue"))
        assertEquals(AppCommand.NewNote(null), AppCommand.parse("scripturealone://new-note"))
        assertEquals(AppCommand.NewNote(null, "Sermon"), AppCommand.parse("scripturealone://new-note?title=Sermon"))
        val john316 = AppLink.Osis(listOf(Passage.of(BookID.JOHN, 3, 16)))
        assertEquals(AppCommand.NewNote(john316), AppCommand.parse("scripturealone://new-note?ref=John.3.16"))
        assertEquals(AppCommand.Favorite(john316, add = true), AppCommand.parse("scripturealone://favorite?ref=John.3.16"))
        assertEquals(AppCommand.Favorite(john316, add = false), AppCommand.parse("scripturealone://unfavorite?ref=John.3.16"))
        assertNull(AppCommand.parse("scripturealone://favorite"))
        assertEquals(AppCommand.Link(john316, "KJV"), AppCommand.parse("scripturealone://open?ref=John.3.16&translation=kjv"))
        assertEquals(AppCommand.Link(AppLink.Favorites), AppCommand.parse("scripturealone://favorites"))
        // App Actions' OPEN_APP_FEATURE: the shortcut ids, and looser words.
        assertEquals(AppCommand.VerseOfTheDay, AppCommand.parse("scripturealone://feature?name=verse_of_the_day"))
        assertEquals(AppCommand.Link(AppLink.Search("")), AppCommand.parse("scripturealone://feature?name=search"))
        assertEquals(AppCommand.Link(AppLink.Favorites), AppCommand.parse("scripturealone://feature?name=Favorites"))
        assertEquals(AppCommand.NewNote(null), AppCommand.parse("scripturealone://feature?name=new%20note"))
        assertNull(AppCommand.parse("scripturealone://feature?name=teleport"))
        // Web links are passages only.
        assertEquals(AppCommand.Link(john316), AppCommand.parse("https://wemiller.com/apps/scripture-alone/#ref=John.3.16"))
        assertNull(AppCommand.parse("https://wemiller.com/apps/scripture-alone/today"))
    }

    @Test fun webPagesCannotChangeData() {
        val john316 = AppLink.Osis(listOf(Passage.of(BookID.JOHN, 3, 16)))
        val favorite = AppCommand.Favorite(john316, add = false)
        assertEquals(true, favorite.changesData)
        assertEquals(AppCommand.Link(john316), favorite.readOnly)
        assertNull(AppCommand.NewNote(null).readOnly)
        assertEquals(false, AppCommand.VerseOfTheDay.changesData)
    }
}
