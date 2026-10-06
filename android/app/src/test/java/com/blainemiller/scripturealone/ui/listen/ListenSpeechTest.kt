package com.blainemiller.scripturealone.ui.listen

import com.blainemiller.scripturealone.data.listen.ListenQueue
import com.blainemiller.scripturealone.data.listen.VoiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The 1.1.0-rc.5 ANR (Play vitals, vivo V2249, Android 15): a Previous tap on the Now Playing bar
 * blocked the main thread in `TextToSpeech.stop`, waiting on a lock the engine's connection thread held.
 * Here the engine's stop blocks for seconds, as that engine's did, and the transport must not care:
 * every skip returns at once, quick skips coalesce instead of queueing a stop-and-speak each, and the
 * verse read in the end is the last one asked for.
 */
class ListenSpeechTest {

    /** A speech engine whose `stop` takes [stopMillis] — a vendor engine holding its lock. */
    private class SlowEngine(private val stopMillis: Long) : ListenEngine {
        /** Everything the engine was told, in order: "stop", "speak <id>", "rate …", "voice …", "lang …". */
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val threads: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        @Volatile var stops = 0

        private fun log(call: String) {
            calls += call
            threads += Thread.currentThread().name
        }

        override fun voices() = listOf(VoiceInfo("en-us-x-iol-local", "en-US", 400, requiresNetwork = false))
        override fun useVoice(id: String): Boolean { log("voice $id"); return true }
        override fun useLanguage(locale: Locale) = log("lang ${locale.toLanguageTag()}")
        override fun setRate(rate: Float) = log("rate $rate")
        override fun speak(text: String, utteranceId: String) = log("speak $utteranceId")
        override fun pause(millis: Long, utteranceId: String) = Unit
        override fun stop() {
            log("stop")
            stops++
            Thread.sleep(stopMillis)
        }
        override fun shutdown() = log("shutdown")

        /** The utterances spoken after the last stop: what is actually being read now. */
        fun speakingNow(): List<String> = synchronized(calls) {
            calls.drop(calls.lastIndexOf("stop") + 1).filter { it.startsWith("speak ") }.map { it.removePrefix("speak ") }
        }
    }

    /** A chapter as [ListenQueue.chapterItems] builds it: the announcement, then verses 1–[count]. */
    private fun chapter(count: Int) =
        listOf(ListenQueue.Item(0, "John, chapter 3.")) + (1..count).map { ListenQueue.Item(43_003_000 + it, "Verse $it.") }

    private fun plan(generation: Int, items: List<ListenQueue.Item>, from: Int) = ListenSpeech.Plan(
        generation = generation, items = items, from = from, rate = 1f, voiceId = null,
        voiceLanguage = "en", homeRegion = "US", allowNetwork = false, fallbackLocale = Locale.US,
    )

    private fun started(engine: SlowEngine): ListenSpeech {
        val speech = ListenSpeech { onInit -> engine.also { onInit(true) } }
        val up = CountDownLatch(1)
        speech.start { voices -> assertNotNull(voices); up.countDown() }
        assertTrue(up.await(5, TimeUnit.SECONDS))
        return speech
    }

    /**
     * What `ListenController.skip` → `begin` does with the engine: stop what's playing ([ListenSpeech.silence]
     * from `stopOutput`), then read from the target ([ListenSpeech.play] from `speakFrom`) — with the
     * target from [ListenQueue.skip], as the controller has it.
     */
    private class Transport(val speech: ListenSpeech, val items: List<ListenQueue.Item>) {
        var current = 1
        var generation = 0

        fun skip(delta: Int) {
            val target = ListenQueue.skip(items, current, delta) as? ListenQueue.Skip.To ?: return
            speech.silence()
            current = target.index
            generation++
            speech.play(
                ListenSpeech.Plan(generation, items, current, 1f, null, "en", "US", false, Locale.US),
            )
        }
        fun previousVerse() = skip(-1)
        fun nextVerse() = skip(1)
    }

    @Test
    fun skipsReturnAtOnceWhileTheEngineIsStuckInStop() {
        val engine = SlowEngine(stopMillis = 1_500)
        val speech = started(engine)
        val items = chapter(20)
        val transport = Transport(speech, items)
        speech.play(plan(0, items, 1))

        // Ten taps, back and forth — the accessibility action fires them as fast as this loop.
        val taps = listOf(1, 1, 1, -1, 1, 1, -1, -1, 1, 1)
        for (delta in taps) {
            val began = System.nanoTime()
            if (delta < 0) transport.previousVerse() else transport.nextVerse()
            val tookMillis = (System.nanoTime() - began) / 1_000_000
            assertTrue("a skip blocked the caller for $tookMillis ms", tookMillis < 100)
        }
        assertEquals(5, transport.current) // 1 +3 -1 +2 -2 +2

        assertTrue(speech.awaitIdle(15_000))
        // Read from the last verse asked for, by the last request's generation.
        val now = engine.speakingNow()
        assertEquals(ListenSpeech.utteranceId(transport.generation, 5), now.first())
        assertEquals(items.size - 5, now.size)
        // Coalesced: the first play's stop, then one more for everything the taps asked — not ten.
        assertTrue("engine stopped ${engine.stops} times for ten taps", engine.stops <= 3)
        // And never on the caller's thread.
        assertEquals(setOf("listen-speech"), engine.threads.toSet())
    }

    @Test
    fun previousThenNextEndsOnTheLastVerseRequested() {
        val engine = SlowEngine(stopMillis = 400)
        val speech = started(engine)
        val items = chapter(5)
        val transport = Transport(speech, items)
        transport.current = 3
        transport.previousVerse() // 2
        transport.previousVerse() // 1
        transport.previousVerse() // 1 — before the first verse is the first verse, never the announcement
        transport.nextVerse()     // 2
        assertTrue(speech.awaitIdle(10_000))
        assertEquals(ListenSpeech.utteranceId(transport.generation, 2), engine.speakingNow().first())
    }

    @Test
    fun aSilenceAfterAPlayLeavesNothingReading() {
        val engine = SlowEngine(stopMillis = 300)
        val speech = started(engine)
        speech.play(plan(1, chapter(3), 1))
        speech.silence()
        assertTrue(speech.awaitIdle(10_000))
        assertEquals(emptyList<String>(), engine.speakingNow())
    }

    @Test
    fun utteranceIdsRoundTrip() {
        assertEquals(7 to 12, ListenSpeech.parseUtterance(ListenSpeech.utteranceId(7, 12)))
        assertEquals(null, ListenSpeech.parseUtterance("7:pause"))
        assertEquals(null, ListenSpeech.parseUtterance(null))
    }
}
