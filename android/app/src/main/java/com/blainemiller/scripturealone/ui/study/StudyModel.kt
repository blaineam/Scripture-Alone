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
import androidx.annotation.StringRes
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.context.MapLabel
import com.blainemiller.scripturealone.data.context.Place
import com.blainemiller.scripturealone.data.context.TimelineEvent
import com.blainemiller.scripturealone.data.sql.BundledSqlSource
import com.blainemiller.scripturealone.data.study.ImportedStudyLibrary
import com.blainemiller.scripturealone.data.study.InterlinearStore
import com.blainemiller.scripturealone.data.study.StudyStore
import com.blainemiller.scripturealone.text.AppLanguage
import com.blainemiller.scripturealone.text.AppText
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel

/** The Study panel's tabs. iOS has three and opens the original languages in a sheet of its own; here it is a fourth tab. */
enum class StudyTab(@StringRes private val titleRes: Int, @StringRes private val shortTitleRes: Int) {
    CROSS_REFERENCES(R.string.study_tab_cross_references, R.string.study_tab_cross_references_short),
    COMMENTARY(R.string.study_tab_commentary, R.string.study_tab_commentary_short),
    ORIGINAL(R.string.study_tab_original, R.string.study_tab_original_short),
    CONTEXT(R.string.study_tab_context, R.string.study_tab_context_short),
    ;

    val title: String get() = AppText.get(titleRes)
    val shortTitle: String get() = AppText.get(shortTitleRes)

    companion object {
        /**
         * The tabs this reader gets — `StudyTab.available`: the bundled commentary (Calvin, Gill, JFB)
         * is English-only, so it is offered only in English (docs/localization.md, owner's decision) —
         * unless the reader imported study material of their own, which is in whatever language they chose.
         */
        val available: List<StudyTab>
            get() = if (AppLanguage.isEnglish || !ImportedStudyLibrary.isEmpty) entries else entries.filter { it != COMMENTARY }

        /** [tab] if this reader gets it, else the first tab. */
        fun offered(tab: StudyTab): StudyTab = if (tab in available) tab else CROSS_REFERENCES
    }
}

/** Where the panel has navigated within itself — iOS's `NavigationStack` destinations. */
sealed interface StudyRoute {
    data object Sources : StudyRoute
    data class Viewer(val tab: ViewerTab, val chartId: String? = null) : StudyRoute
    data class Chart(val id: String) : StudyRoute
    data class PlaceDetail(val place: Place) : StudyRoute
    data object Credits : StudyRoute

    /** `ContextViewerRequest.Tab`. */
    enum class ViewerTab(@StringRes private val titleRes: Int) {
        MAP(R.string.study_viewer_map), TIMELINE(R.string.study_viewer_timeline), CHARTS(R.string.study_viewer_charts);

        val title: String get() = AppText.get(titleRes)
    }
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
    var tab by mutableStateOf(
        StudyTab.offered(StudyTab.entries.firstOrNull { it.name == prefs.getString("tab", null) } ?: StudyTab.CROSS_REFERENCES),
    )
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
        // Not a tab this reader has (the commentary outside English): stays where it is.
        if (tab !in StudyTab.available) return
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
 * The study databases, opened once for the app on first use. Cross references, context and the map
 * ship in the app; commentary and the interlinear are on-demand Play asset packs, as on iOS, so
 * [commentary] and [interlinear] are null until the reader has downloaded them (`PackDownload`), and
 * are tried again on the next call. Each open copies its file out the first time, so call these off
 * the main thread.
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

    /**
     * The context in the app's language; opened again (and the data re-read) when the reader changes
     * the app's language, which leaves this process running.
     */
    @Synchronized
    fun context(context: Context): ContextStore? {
        val language = AppLanguage.studyLanguage
        contextStore?.takeIf { it.requestedLanguage == language }?.let { return it }
        contextData = null
        return runCatching { ContextStore(source(context, "Context.sqlite"), language) }.getOrNull().also { contextStore = it }
    }

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
    fun contextData(context: Context): ContextData? = contextData?.takeIf { contextStore?.requestedLanguage == AppLanguage.studyLanguage }
        ?: context(context)?.let { store ->
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
