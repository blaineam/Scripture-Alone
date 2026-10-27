package com.blainemiller.scripturealone.data.listen

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceQueryTest {
    private val title = "android.intent.extra.title"
    private val album = "android.intent.extra.album"
    private val playlist = "android.intent.extra.playlist"

    @Test fun theSearchQueryComesFirstThenTheExtrasInOrder() {
        assertEquals(
            listOf("john chapter 1", "John", "Scripture Alone"),
            VoiceQuery.candidates("john chapter 1", mapOf(album to "Scripture Alone", title to "John")),
        )
    }

    @Test fun blankAndRepeatedPhrasesAreDropped() {
        assertEquals(listOf("Psalm 23"), VoiceQuery.candidates("  ", mapOf(title to "Psalm 23", album to "Psalm 23", playlist to null)))
        assertEquals(emptyList<String>(), VoiceQuery.candidates(null, emptyMap()))
        assertEquals(emptyList<String>(), VoiceQuery.candidates("", mapOf(title to "")))
    }

    @Test fun phrasesSurviveAMediaId() {
        val phrases = listOf("John 3:16", "play: something; else")
        assertEquals(phrases, VoiceQuery.decode(VoiceQuery.encode(phrases)))
        assertEquals(emptyList<String>(), VoiceQuery.decode(""))
    }

    @Test fun aSpokenIdCarriesEveryPhraseThroughTheActionParser() {
        val id = CarAction.spokenId(listOf("John 3:16", "John"))
        val action = CarAction.parse(id) as CarAction.Spoken
        assertEquals(listOf("John 3:16", "John"), VoiceQuery.decode(action.query))
        // A bare "play": an empty request, which is "continue".
        assertEquals(emptyList<String>(), VoiceQuery.decode((CarAction.parse(CarAction.spokenId(emptyList())) as CarAction.Spoken).query))
    }
}
