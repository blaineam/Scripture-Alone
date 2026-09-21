package com.blainemiller.scripturealone.ui.study

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.blainemiller.scripturealone.data.BundledDatabase
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.context.Basemap
import com.blainemiller.scripturealone.data.context.ChartInfo
import com.blainemiller.scripturealone.data.context.ContextStore
import com.blainemiller.scripturealone.data.context.Era
import com.blainemiller.scripturealone.data.context.MapLabel
import com.blainemiller.scripturealone.data.context.Place
import com.blainemiller.scripturealone.data.context.TimelineEvent
import com.blainemiller.scripturealone.data.sql.BundledSqlSource
import com.blainemiller.scripturealone.data.study.InterlinearStore
import com.blainemiller.scripturealone.data.study.StudyStore
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel

/** The Study panel's tabs. iOS has three and opens the original languages in a sheet of its own; here it is a fourth tab. */
enum class StudyTab(val title: String, val shortTitle: String) {
    CROSS_REFERENCES("Cross References", "References"),
    COMMENTARY("Commentary", "Commentary"),
    ORIGINAL("Original Languages", "Original"),
    CONTEXT("Context", "Context"),
}

/** Where the panel has navigated within itself — iOS's `NavigationStack` destinations. */
sealed interface StudyRoute {
    data object Sources : StudyRoute
    data class Viewer(val tab: ViewerTab, val chartId: String? = null) : StudyRoute
    data class Chart(val id: String) : StudyRoute
    data class PlaceDetail(val place: Place) : StudyRoute
    data object Credits : StudyRoute

    /** `ContextViewerRequest.Tab`. */
    enum class ViewerTab(val title: String) { MAP("Map"), TIMELINE("Timeline"), CHARTS("Charts") }
}

/**
 * Study mode's state — `StudyModel.swift`: whether the panel is open, which verse it follows, and
 * the trail of cross-reference jumps so the reader can find their way back. A tap still selects a
 * verse for highlighting and notes; the panel simply follows the verse most recently handed to it.
 */
class StudyModel(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("study", Context.MODE_PRIVATE)

    var isOpen by mutableStateOf(false)
        private set
    var tab by mutableStateOf(StudyTab.entries.firstOrNull { it.name == prefs.getString("tab", null) } ?: StudyTab.CROSS_REFERENCES)
        private set

    /** The verse the panel is showing. */
    var verse by mutableStateOf<VerseRef?>(null)
        private set

    /** Verses to return to, most recent last. */
    var history by mutableStateOf<List<VerseRef>>(emptyList())
        private set

    /** Pushed destinations inside the panel. */
    val routes = mutableStateListOf<StudyRoute>()

    /** `study.commentarySource`, as iOS stores it. */
    var commentarySource by mutableStateOf(prefs.getString("commentarySource", "calvin") ?: "calvin")
        private set

    fun select(tab: StudyTab) {
        this.tab = tab
        routes.clear()
        prefs.edit().putString("tab", tab.name).apply()
    }

    fun selectCommentary(id: String) {
        commentarySource = id
        prefs.edit().putString("commentarySource", id).apply()
    }

    /** The entry point: opens the panel on [verseKey], or moves an open panel to it. */
    fun open(verseKey: Int) {
        isOpen = true
        follow(verseKey)
    }

    fun close() {
        isOpen = false
        routes.clear()
    }

    /** A new verse to follow. Not a jump, so it leaves no trail. */
    fun follow(verseKey: Int) {
        val ref = VerseRange.ref(verseKey) ?: return
        if (ref == verse) return
        verse = ref
        routes.clear()
    }

    /** Opens a cross reference: remembers where we were, moves the reader and the panel there. */
    fun jump(range: VerseRange, reader: ReaderViewModel) {
        val here = verse
        if (here != null && here != range.start) history = (history + here).takeLast(30)
        verse = range.start
        routes.clear()
        reader.go(range.start)
    }

    /** Returns to the verse before the last jump. */
    fun back(reader: ReaderViewModel) {
        val previous = history.lastOrNull() ?: return
        history = history.dropLast(1)
        verse = previous
        reader.go(previous)
    }

    fun push(route: StudyRoute) {
        routes += route
    }

    fun pop() {
        if (routes.isNotEmpty()) routes.removeAt(routes.lastIndex)
    }
}

/**
 * The study databases, opened once for the app on first use. All of them ship inside the APK (iOS
 * downloads commentary and the interlinear as on-demand packs; Android has no equivalent free of a
 * Play asset-delivery setup, so they are bundled). Each open copies the file out of the APK the first
 * time, so call these off the main thread.
 */
object StudyLibrary {
    @Volatile private var crossRefs: StudyStore? = null
    @Volatile private var commentary: StudyStore? = null
    @Volatile private var interlinear: InterlinearStore? = null
    @Volatile private var contextStore: ContextStore? = null
    @Volatile private var basemapValue: Basemap? = null
    @Volatile private var contextData: ContextData? = null

    private fun source(context: Context, name: String) =
        BundledSqlSource(BundledDatabase.withConnection(context, name) { it })

    /**
     * Cross references and the list of study sources — `CrossReferences.sqlite`, split out of the
     * commentary database so the strongest links never wait on 44 MB of commentary.
     */
    @Synchronized
    fun crossReferences(context: Context): StudyStore? = crossRefs ?: runCatching {
        StudyStore(source(context, "CrossReferences.sqlite"))
    }.getOrNull().also { crossRefs = it }

    @Synchronized
    fun commentary(context: Context): StudyStore? = commentary ?: runCatching {
        StudyStore(source(context, "Study.sqlite"))
    }.getOrNull().also { commentary = it }

    @Synchronized
    fun interlinear(context: Context): InterlinearStore? = interlinear ?: runCatching {
        InterlinearStore(source(context, "Interlinear.sqlite"))
    }.getOrNull().also { interlinear = it }

    @Synchronized
    fun context(context: Context): ContextStore? = contextStore ?: runCatching {
        ContextStore(source(context, "Context.sqlite"))
    }.getOrNull().also { contextStore = it }

    @Synchronized
    fun basemap(context: Context): Basemap? = basemapValue ?: runCatching {
        Basemap(context.assets.open("Basemap.bin").use { it.readBytes() })
    }.getOrNull().also { basemapValue = it }

    /** What every map and timeline draws from, read once — `ContextLibrary`'s lazy properties. */
    class ContextData(
        val labels: List<MapLabel>,
        val eras: List<Era>,
        val events: List<TimelineEvent>,
        val charts: List<ChartInfo>,
        /** The most-mentioned places, drawn faintly for orientation. */
        val prominentPlaces: List<Place>,
    )

    /** The context data if it has been read already; never reads. */
    fun contextDataOrNull(): ContextData? = contextData

    @Synchronized
    fun contextData(context: Context): ContextData? = contextData ?: context(context)?.let { store ->
        runCatching {
            ContextData(store.labels(), store.eras(), store.events(), store.charts(), store.prominentPlaces(limit = 500))
        }.getOrNull()
    }.also { contextData = it }
}

/**
 * Loads a value off the main thread for as long as [key] is unchanged — `.task(id:)`. Null while
 * loading, and after a failure.
 */
@Composable
fun <K, T> loaded(key: K, load: (Context) -> T?): T? {
    val context = LocalContext.current.applicationContext
    val state = produceState<T?>(null, key) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { load(context) }.getOrNull() }
    }
    return state.value
}
