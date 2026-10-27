package com.blainemiller.scripturealone.ui.listen

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** `CarLibrary.play` on its own, with no media session in the way: a chapter id must read that chapter. */
@RunWith(AndroidJUnit4::class)
class CarPlayDirectTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val main = Handler(Looper.getMainLooper())

    private fun <T> onMain(block: () -> T): T {
        var out: Result<T>? = null
        val latch = CountDownLatch(1)
        main.post { out = runCatching(block); latch.countDown() }
        assertTrue(latch.await(20, TimeUnit.SECONDS))
        return out!!.getOrThrow()
    }

    @After fun stop() { onMain { ListenController.get(context).stop() } }

    @Test fun aChapterIdReadsThatChapter() {
        val ok = runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { CarLibrary.play(context, "play:chapter:43:3") } }
        assertTrue(ok)
        var title = ""
        repeat(20) { if (title != "John 3:1") { Thread.sleep(500); title = onMain { ListenController.get(context).nowPlayingTitle } } }
        assertEquals("John 3:1", title)
    }
}
