package com.blainemiller.scripturealone.ui.listen

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blainemiller.scripturealone.data.listen.CarAction
import com.blainemiller.scripturealone.data.listen.SpokenReference
import com.blainemiller.scripturealone.data.listen.VoiceQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the media session does with a play request from a car's assistant — the request Android Auto sends for "Hey Google,
 * read John chapter 1 in Scripture Alone" (`onPlayFromSearch`, which media3 hands to `onAddMediaItems` as an item with no id).
 */
@RunWith(AndroidJUnit4::class)
class VoiceItemTest {
    private fun request(query: String?, extras: Bundle? = null): MediaItem = MediaItem.Builder()
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).setExtras(extras).build())
        .build()

    private fun planned(item: MediaItem): SpokenReference.Request {
        val action = CarAction.parse(voiceItem(item).mediaId) as CarAction.Spoken
        return SpokenReference.plan(VoiceQuery.decode(action.query))
    }

    @Test fun aSearchQueryBecomesASpokenId() {
        val item = voiceItem(request("read John chapter 1 in Scripture Alone"))
        assertEquals(CarAction.spokenId(listOf("read John chapter 1 in Scripture Alone")), item.mediaId)
        val read = planned(request("read John chapter 1 in Scripture Alone")) as SpokenReference.Request.Read
        assertEquals(listOf("John 1"), read.passages.map { it.display })
    }

    @Test fun theStructuredExtrasAreReadToo() {
        val extras = Bundle().apply { putString("android.intent.extra.title", "Psalm twenty three") }
        // The search query is only the app's name; the passage is in the title.
        val read = planned(request("Scripture Alone", extras)) as SpokenReference.Request.Read
        assertEquals(listOf("Psalms 23"), read.passages.map { it.display })
    }

    @Test fun aBarePlayContinuesWhereTheReaderLeftOff() {
        assertEquals(SpokenReference.Request.Resume, planned(request(null)))
        assertEquals(SpokenReference.Request.Resume, planned(request("")))
    }

    @Test fun aRowChosenInTheCarIsLeftAlone() {
        val row = MediaItem.Builder().setMediaId(CarAction.TODAY).build()
        assertSame(row, voiceItem(row))
        val withQuery = row.buildUpon().setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("ignored").build()).build()
        assertEquals(CarAction.TODAY, voiceItem(withQuery).mediaId)
    }
}
