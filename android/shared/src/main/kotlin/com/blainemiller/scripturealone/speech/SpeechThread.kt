package com.blainemiller.scripturealone.speech

import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Every call into a speech engine — Android's `TextToSpeech` on the phone and the watch — made on one
 * thread of its own, never the UI's.
 *
 * Why: `TextToSpeech`'s calls (`stop`, `speak`, `setLanguage`, `getVoices`, `shutdown`…) all take one
 * internal lock, and its own connection thread holds that lock while it binds and talks to the engine's
 * service. Some engines hold it for seconds. Called from the main thread, a Previous tap waited on that
 * lock until the system called it an ANR (Play vitals, 1.1.0-rc.5: `TextToSpeech.stop` ←
 * `ListenController.stopOutput` ← `previousVerse`). Here the UI only queues requests and returns.
 *
 * Order is kept — the requests run one at a time, in the order they were made — with one exception
 * that is the point: back-to-back [say] requests coalesce. Five quick Previous taps while the engine
 * is stuck in a `stop` run as one stop and one speak of the verse the reader ended on, not five
 * stop-and-speaks in a row; and a [say] whose turn comes while a newer one is already waiting stops
 * the engine and skips its own speaking.
 *
 * [E] is the engine (`TextToSpeech` itself in the apps, a fake in the tests), so this class is plain
 * Kotlin and runs on the JVM.
 *
 * @param create builds the engine on this thread; it must call `onInit` once, from any thread, with
 *   whether the engine started (`TextToSpeech.OnInitListener`).
 * @param stop the engine's `stop`, run first by every [say].
 * @param release the engine's `shutdown`.
 */
class SpeechThread<E : Any>(
    name: String,
    private val create: (onInit: (Boolean) -> Unit) -> E,
    private val stop: (E) -> Unit,
    private val release: (E) -> Unit,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, name).apply { isDaemon = true }
    },
) {
    private sealed interface Request<T>

    /** Stop, then [speak] (if any) — unless another [Say] is waiting by then. */
    private class Say<T>(val speak: ((T) -> Unit)?) : Request<T>
    private class Run<T>(val action: (T) -> Unit) : Request<T>
    private class Start<T>(val onReady: (T?) -> Unit) : Request<T>
    private class Shutdown<T> : Request<T>

    // Shared between the callers' threads and the speech thread; guarded by [lock].
    private val lock = Any()
    private val pending = ArrayDeque<Request<E>>()
    private var draining = false

    // The speech thread's own; touched only there.
    private var engine: E? = null
    private var ready = false
    /** Counts engines built, so a late init from one already shut down is ignored. */
    private var builds = 0
    private val waitingForInit = mutableListOf<(E?) -> Unit>()

    /**
     * Starts the engine if it isn't already, then calls [onReady] on the speech thread with it — or
     * with null if it failed to start, after which the next [start] tries again.
     */
    fun start(onReady: (E?) -> Unit = {}) = enqueue(Start(onReady))

    /**
     * Stops whatever is being spoken, then runs [speak] on the speech thread (if given and the engine
     * is up). Replaces a [say] still waiting its turn, so rapid requests coalesce into the last one.
     */
    fun say(speak: ((E) -> Unit)? = null) = enqueue(Say(speak))

    /** Stops speaking. */
    fun silence() = say(null)

    /** Runs [action] on the speech thread, in order with everything else, once the engine is up. */
    fun run(action: (E) -> Unit) = enqueue(Run(action))

    /** Stops and releases the engine; a later [start] builds a new one. */
    fun shutdown() = enqueue(Shutdown())

    /** Releases the engine and ends the thread: this speaker is done for good. */
    fun close() {
        shutdown()
        executor.shutdown()
    }

    /** For tests: waits until everything requested so far has run. */
    fun awaitIdle(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            synchronized(lock) { if (!draining && pending.isEmpty()) return true }
            Thread.sleep(2)
        }
        return false
    }

    private fun enqueue(request: Request<E>) {
        val schedule: Boolean
        synchronized(lock) {
            // Coalesce: a say still waiting at the back of the queue is replaced by this one.
            if (request is Say<*> && pending.peekLast() is Say<*>) pending.pollLast()
            pending.addLast(request)
            schedule = !draining
            draining = true
        }
        if (schedule) executor.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val request = next() ?: return
            runCatching { perform(request) }
        }
    }

    /** The next request to perform, or null — and the drain over — when there is none to run now. */
    private fun next(): Request<E>? = synchronized(lock) {
        if (engine != null && !ready && pending.isNotEmpty()) {
            // Waiting on the engine's init: everything after it waits too, in order — unless a
            // shutdown is waiting, which makes what comes before it moot.
            if (pending.none { it is Shutdown<*> }) {
                draining = false
                return@synchronized null
            }
            while (pending.peekFirst() !is Shutdown<*>) {
                (pending.pollFirst() as? Start<E>)?.let { waitingForInit += it.onReady }
            }
        }
        pending.pollFirst().also { if (it == null) draining = false }
    }

    private fun perform(request: Request<E>) {
        when (request) {
            is Start<E> -> {
                val current = engine
                when {
                    current != null && ready -> request.onReady(current)
                    else -> {
                        waitingForInit += request.onReady
                        if (current == null) build()
                    }
                }
            }
            is Say<E> -> {
                val current = engine ?: return
                stop(current)
                if (request.speak == null) return
                // A newer say is already queued: it will stop this at once, so don't start it.
                if (synchronized(lock) { pending.any { it is Say<*> } }) return
                request.speak.invoke(current)
            }
            is Run<E> -> engine?.let(request.action)
            is Shutdown<*> -> {
                val current = engine
                val wasReady = ready
                engine = null
                ready = false
                if (current != null) {
                    if (wasReady) runCatching { stop(current) }
                    runCatching { release(current) }
                }
                waitingForInit.toList().also { waitingForInit.clear() }.forEach { runCatching { it(null) } }
            }
        }
    }

    private fun build() {
        ready = false
        val build = ++builds
        val built = runCatching {
            // The engine calls back on a thread of its own; closed meanwhile, there's nothing to tell.
            create { ok -> runCatching { executor.execute { initialized(build, ok) } } }
        }.getOrNull()
        if (built == null) {
            waitingForInit.toList().also { waitingForInit.clear() }.forEach { it(null) }
            return
        }
        engine = built
    }

    /** On the speech thread: the engine's init came back. */
    private fun initialized(build: Int, ok: Boolean) {
        if (build != builds) return
        val current = engine ?: return
        if (ok) {
            ready = true
        } else {
            engine = null
            runCatching { release(current) }
        }
        val waiting = waitingForInit.toList()
        waitingForInit.clear()
        for (callback in waiting) runCatching { callback(if (ok) current else null) }
        // Whatever queued up behind the init runs now.
        val resume = synchronized(lock) {
            if (draining || pending.isEmpty()) false else { draining = true; true }
        }
        if (resume) drain()
    }
}
