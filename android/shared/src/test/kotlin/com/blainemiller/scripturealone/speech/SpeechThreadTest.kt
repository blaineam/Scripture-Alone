package com.blainemiller.scripturealone.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** [SpeechThread]: order kept, says coalesced, nothing run on the caller's thread, init waited for. */
class SpeechThreadTest {

    private class Engine(val stopMillis: Long = 0) {
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val threads: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        fun record(s: String) { log += s; threads += Thread.currentThread().name }
    }

    private fun thread(engine: Engine, initOk: Boolean = true, initLater: AtomicReference<(Boolean) -> Unit>? = null) =
        SpeechThread(
            name = "speech-test",
            create = { onInit -> if (initLater != null) initLater.set(onInit) else onInit(initOk); engine },
            stop = { e -> e.record("stop"); Thread.sleep(e.stopMillis) },
            release = { e -> e.record("release") },
        )

    @Test
    fun saysCoalesceAndCallersNeverWait() {
        val engine = Engine(stopMillis = 1_000)
        val speech = thread(engine)
        speech.start()
        speech.say { it.record("speak 0") }
        Thread.sleep(50) // the first say is now inside its slow stop
        val began = System.nanoTime()
        for (i in 1..20) speech.say { it.record("speak $i") }
        assertTrue((System.nanoTime() - began) / 1_000_000 < 100)
        assertTrue(speech.awaitIdle(10_000))
        // The first say stopped and stood down; the twenty became one.
        assertEquals(listOf("stop", "stop", "speak 20"), engine.log)
        assertEquals(setOf("speech-test"), engine.threads.toSet())
    }

    @Test
    fun runsKeepTheirOrderAroundSays() {
        val engine = Engine()
        val speech = thread(engine)
        speech.start()
        speech.run { it.record("a") }
        speech.say { it.record("speak") }
        speech.run { it.record("b") }
        assertTrue(speech.awaitIdle(5_000))
        assertEquals(listOf("a", "stop", "speak", "b"), engine.log)

    }

    @Test
    fun aSpeakWithASilenceQueuedBehindItStandsDown() {
        // The say is inside its (slow) stop when the silence arrives: it stops, and doesn't speak.
        val engine = Engine(stopMillis = 400)
        val speech = thread(engine)
        speech.start()
        speech.say { it.record("speak") }
        Thread.sleep(50)
        speech.run { it.record("c") }
        speech.silence()
        assertTrue(speech.awaitIdle(5_000))
        assertEquals(listOf("stop", "c", "stop"), engine.log)
    }

    @Test
    fun requestsWaitForTheEnginesInit() {
        val engine = Engine()
        val init = AtomicReference<(Boolean) -> Unit>()
        val speech = thread(engine, initLater = init)
        val ready = CountDownLatch(1)
        speech.start { ready.countDown() }
        speech.say { it.record("speak") }
        Thread.sleep(100)
        assertEquals(emptyList<String>(), engine.log)
        init.get()(true)
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        assertTrue(speech.awaitIdle(5_000))
        assertEquals(listOf("stop", "speak"), engine.log)
    }

    @Test
    fun aFailedInitReportsNullAndTheNextStartTriesAgain() {
        val engine = Engine()
        var ok = false
        val speech = SpeechThread(
            name = "speech-test",
            create = { onInit -> onInit(ok); engine },
            stop = { e -> e.record("stop") },
            release = { e -> e.record("release") },
        )
        val first = AtomicReference<Any?>("unset")
        val firstDone = CountDownLatch(1)
        speech.start { first.set(it); firstDone.countDown() }
        assertTrue(firstDone.await(5, TimeUnit.SECONDS))
        assertNull(first.get())
        speech.say { it.record("speak") } // no engine: nothing to do
        assertTrue(speech.awaitIdle(5_000))
        assertEquals(listOf("release"), engine.log)

        ok = true
        val second = CountDownLatch(1)
        speech.start { if (it != null) second.countDown() }
        assertTrue(second.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun closeReleasesEvenWhileInitIsPending() {
        val engine = Engine()
        val init = AtomicReference<(Boolean) -> Unit>()
        val speech = thread(engine, initLater = init)
        val told = AtomicReference<Any?>("unset")
        speech.start { told.set(it) }
        speech.say { it.record("speak") }
        speech.close()
        Thread.sleep(200)
        assertEquals(listOf("release"), engine.log)
        assertNull(told.get())
        init.get()(true) // late: ignored, and nothing throws
    }
}
