package com.blainemiller.scripturealone.ui.listen

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Android Auto's way in, on a device: a `MediaBrowser` (what the car is) connects to `ListenPlaybackService`, browses the
 * library root → Books → Old Testament, and chooses a chapter — which must start Listen reading it. The JVM test
 * (`CarCatalogTest`) proves the tree's shape; this proves the service serves it and that a chosen row plays.
 */
@RunWith(AndroidJUnit4::class)
class CarLibraryServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val main = Handler(Looper.getMainLooper())
    private var browser: MediaBrowser? = null

    private fun <T> onMain(block: () -> T): T {
        var out: Result<T>? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post { out = runCatching(block); latch.countDown() }
        assertTrue(latch.await(20, TimeUnit.SECONDS))
        return out!!.getOrThrow()
    }

    @Before fun connect() {
        val token = SessionToken(context, ComponentName(context, ListenPlaybackService::class.java))
        val future = onMain { MediaBrowser.Builder(context, token).buildAsync() }
        browser = future.get(30, TimeUnit.SECONDS)
    }

    @After fun disconnect() {
        onMain { ListenController.get(context).stop() }
        browser?.let { b -> onMain { b.release() } }
    }

    private fun children(parent: String): List<MediaItem> =
        onMain { browser!!.getChildren(parent, 0, 100, null) }.get(20, TimeUnit.SECONDS).value!!.toList()

    @Test fun theLibraryIsBrowsableAndAChosenChapterPlays() {
        val root = onMain { browser!!.getLibraryRoot(null) }.get(20, TimeUnit.SECONDS).value!!
        assertEquals("root", root.mediaId)
        val top = children("root")
        assertEquals(3, top.size)
        assertTrue(top.all { it.mediaMetadata.isBrowsable == true })
        val books = children("books")
        assertEquals(2, books.size)
        val ot = children("ot")
        assertTrue("an Old Testament list fits a car", ot.size in 1..50)
        // A single-chapter book plays; a chapter plays: choose John 3.
        val john = children("book:43")
        assertTrue(john.isNotEmpty())
        val chapter = MediaItem.Builder().setMediaId("play:chapter:43:3").setMediaMetadata(MediaMetadata.Builder().setIsPlayable(true).build()).build()
        onMain { browser!!.setMediaItem(chapter); browser!!.prepare(); browser!!.play() }
        val listen = ListenController.get(context)
        var presented = false
        repeat(40) { if (!presented) { Thread.sleep(500); presented = onMain { listen.isPresented } } }
        assertTrue("choosing a chapter in the car starts Listen", presented)
    }
}
