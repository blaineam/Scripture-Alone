package com.blainemiller.scripturealone.ui.listen

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What Google Assistant does for "play John chapter 1 on Scripture Alone", on a device: it connects to the app's media
 * browser service through the legacy `MediaBrowserCompat` and calls `playFromSearch` with the words it heard. The
 * reading must start, naming the passage. (Whether the assistant makes the call at all is Google's; this proves ours.)
 */
@RunWith(AndroidJUnit4::class)
class CarVoiceServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val main = Handler(Looper.getMainLooper())
    private var browser: MediaBrowserCompat? = null

    @After fun disconnect() {
        main.post { ListenController.get(context).stop(); browser?.disconnect() }
    }

    private fun spoken(words: String): String {
        val connected = CountDownLatch(1)
        var controller: MediaControllerCompat? = null
        main.post {
            browser = MediaBrowserCompat(context, ComponentName(context, ListenPlaybackService::class.java),
                object : MediaBrowserCompat.ConnectionCallback() {
                    override fun onConnected() {
                        controller = MediaControllerCompat(context, browser!!.sessionToken)
                        connected.countDown()
                    }
                }, null).also { it.connect() }
        }
        assertTrue("connects like the car", connected.await(30, TimeUnit.SECONDS))
        main.post { controller!!.transportControls.playFromSearch(words, null) }
        val listen = ListenController.get(context)
        var title = ""
        repeat(40) {
            if (title.isEmpty()) {
                Thread.sleep(500)
                val latch = CountDownLatch(1)
                main.post { title = if (listen.isPresented) listen.nowPlayingTitle else ""; latch.countDown() }
                latch.await(5, TimeUnit.SECONDS)
            }
        }
        return title
    }

    @Test fun aSpokenChapterStartsReading() {
        assertEquals("John 1:1", spoken("john chapter 1"))
    }
}
