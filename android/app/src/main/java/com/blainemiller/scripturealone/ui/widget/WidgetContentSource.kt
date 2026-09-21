package com.blainemiller.scripturealone.ui.widget

import android.content.Context
import android.content.pm.ApplicationInfo
import com.blainemiller.scripturealone.companion.VerseSnapshot.Companion.FavoriteInput
import com.blainemiller.scripturealone.companion.VerseSnapshot.Companion.HighlightInput
import com.blainemiller.scripturealone.companion.VerseSnapshot.Companion.NoteInput
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import java.time.Duration
import java.time.Instant

/**
 * Where the Favorites & Notes widget — and the watch — get the reader's library.
 *
 * The favorites, highlights and notes store lives elsewhere (`data/userdata/`); this is the whole of
 * what the widgets need from it, so the two meet only here. An implementation returns the rows as
 * they are stored — one highlight per verse, every anchor of a note — and [WidgetSync] turns them into
 * a [com.blainemiller.scripturealone.companion.VerseSnapshot] exactly as `WidgetSnapshotSync.swift` does
 * on iOS: highlights merged into ranges, newest first, at most 60 of each kind, text in the reader's
 * translation.
 *
 * Connect a store with [WidgetContent.install], at process start (an `Application.onCreate` or an
 * androidx.startup `Initializer`), since a widget update can start the process without any activity.
 */
interface WidgetContentSource {
    /**
     * The library now, then again after every change — here, or arriving from sync. A Room DAO's
     * `Flow`s combined is the natural shape. Collected off the main thread; bursts are coalesced.
     */
    val library: Flow<WidgetLibrary>
}

/** The rows [WidgetContentSource.library] emits: the inputs of `VerseSnapshot.build`. */
data class WidgetLibrary(
    val favorites: List<FavoriteInput> = emptyList(),
    /** One row per verse, as highlights are stored. */
    val highlights: List<HighlightInput> = emptyList(),
    /** Title, every anchor, last-edited date and body. */
    val notes: List<NoteInput> = emptyList(),
) {
    companion object {
        val EMPTY = WidgetLibrary()
    }
}

/** Which [WidgetContentSource] the widgets read. */
object WidgetContent {
    private val installed = MutableStateFlow<WidgetContentSource?>(null)
    private val demo = MutableStateFlow<Boolean?>(null)

    /** Connects the reader's library. Safe to call more than once; the latest wins. */
    fun install(source: WidgetContentSource) {
        installed.value = source
    }

    /**
     * The source to read: the installed one; else, in a debuggable build with the demo library
     * switched on ([WidgetDemoReceiver]), [DemoWidgetContentSource]; else [EmptyWidgetContentSource].
     */
    fun resolve(context: Context): WidgetContentSource = pick(context, installed.value, demoOn(context))

    /** [resolve], again whenever a source is installed or the demo is switched. */
    fun sources(context: Context): Flow<WidgetContentSource> {
        demoOn(context)
        return combine(installed, demo) { source, on -> pick(context, source, on == true) }.distinctUntilChanged()
    }

    fun setDemoLibrary(context: Context, on: Boolean) {
        WidgetPrefs.setDemoLibrary(context, on)
        demo.value = on
    }

    private fun demoOn(context: Context): Boolean =
        demo.value ?: WidgetPrefs.demoLibrary(context).also { demo.compareAndSet(null, it) }

    private fun pick(context: Context, source: WidgetContentSource?, demoOn: Boolean): WidgetContentSource =
        source ?: if (demoOn && isDebuggable(context)) DemoWidgetContentSource() else EmptyWidgetContentSource

    private fun isDebuggable(context: Context) =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}

/** No library yet: the widget shows its empty state, as on a fresh iOS install. */
object EmptyWidgetContentSource : WidgetContentSource {
    override val library: Flow<WidgetLibrary> = flowOf(WidgetLibrary.EMPTY)
}

/**
 * A fixed library for tests, previews and emulator screenshots — `DemoLibrary.swift`'s content,
 * verse for verse, so the two platforms' widgets can be compared side by side. Never personal data.
 */
class DemoWidgetContentSource(private val now: Instant = Instant.now()) : WidgetContentSource {
    override val library: Flow<WidgetLibrary> = flowOf(demo(now))

    companion object {
        private fun ref(book: BookID, chapter: Int, verse: Int) = VerseRef(book.number, chapter, verse)
        private fun range(a: VerseRef, b: VerseRef = a) = VerseRange(a, b)

        fun demo(now: Instant): WidgetLibrary {
            val favorites = listOf(
                range(ref(BookID.ROMANS, 8, 38), ref(BookID.ROMANS, 8, 39)),
                range(ref(BookID.PSALMS, 23, 1)),
                range(ref(BookID.JOHN, 14, 6)),
                range(ref(BookID.ISAIAH, 40, 31)),
            ).mapIndexed { offset, r -> FavoriteInput(r, now.minusSeconds(60L * offset)) }
            val highlights = listOf(
                HighlightInput(ref(BookID.JOHN, 3, 16).key, "yellow", now),
                HighlightInput(ref(BookID.JOHN, 3, 17).key, "yellow", now),
                HighlightInput(ref(BookID.PHILIPPIANS, 4, 13).key, "blue", now),
                HighlightInput(ref(BookID.PSALMS, 23, 1).key, "green", now),
                HighlightInput(ref(BookID.JOHN, 14, 6).key, "purple", now),
            )
            val notes = listOf(
                NoteInput(
                    title = "Sunday sermon: No condemnation",
                    anchors = listOf(range(ref(BookID.ROMANS, 8, 1), ref(BookID.ROMANS, 8, 17))),
                    date = now.minus(Duration.ofDays(7)),
                    body = "Life in the Spirit. Verse 1 is the hinge — everything after it flows from “no condemnation.”",
                ),
                NoteInput(
                    title = "Evening sermon: Born of the Spirit",
                    anchors = listOf(
                        range(ref(BookID.JOHN, 3, 1), ref(BookID.JOHN, 3, 21)),
                        range(ref(BookID.NUMBERS, 21, 4), ref(BookID.NUMBERS, 21, 9)),
                    ),
                    date = now.minus(Duration.ofDays(3)),
                    body = "• Nicodemus comes by night (v. 2)\n• “You must be born anew” — the Spirit’s work, not ours\n" +
                        "• The serpent in the wilderness points to the cross (Numbers 21:8–9)",
                ),
            )
            return WidgetLibrary(favorites, highlights, notes)
        }
    }
}
