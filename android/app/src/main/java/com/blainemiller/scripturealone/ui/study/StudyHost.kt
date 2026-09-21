package com.blainemiller.scripturealone.ui.study

import androidx.compose.ui.semantics.clearAndSetSemantics
import com.blainemiller.scripturealone.ui.reader.takesTaps
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import com.blainemiller.scripturealone.ui.reader.SheetColors
import com.blainemiller.scripturealone.ui.translations.CompareSheet
import com.blainemiller.scripturealone.ui.translations.TranslationsSheet
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * What the reader can ask of the panels this host owns. [openStudy] is the `(verseKey) -> Unit` entry
 * point for the Study panel; [toggleStudy] is the top bar's Study button, which (as on iOS) picks up
 * the selected verse; [openOriginal] is the selection bar's Original Language action.
 */
class ReaderPanels(
    val openStudy: (verseKey: Int) -> Unit,
    val toggleStudy: () -> Unit,
    val openOriginal: (verseKey: Int) -> Unit,
    val openTranslations: () -> Unit,
    val openCompare: () -> Unit,
    /** Whether the Study panel is up (state-backed: reading it recomposes on a change). */
    val studyOpen: () -> Boolean,
    /** Study's back trail — the Back to… button, ⌥⌘[. */
    val studyBack: () -> Unit,
    /** Maps & Timeline for the chapter — ⇧⌘M: Study on its Context tab. */
    val openMaps: () -> Unit,
)

/**
 * Hosts the reader together with the Study panel, the Translations screen and Compare.
 *
 * Study sits **beside** the text on a wide screen (a tablet, as iOS's inspector on iPad) and in a
 * **resizable bottom sheet** on a phone, which — like iOS's `presentationBackgroundInteraction(
 * .enabled(upThrough: .fraction(0.45)))` — leaves the text readable and tappable behind it until it
 * is pulled up to full height.
 */
@Composable
fun StudyHost(reader: ReaderViewModel, content: @Composable (ReaderPanels) -> Unit) {
    val context = LocalContext.current
    val study = remember { StudyModel(context) }
    var showTranslations by rememberSaveable { mutableStateOf(false) }
    var showCompare by rememberSaveable { mutableStateOf(false) }
    val palette = reader.theme.palette(isSystemInDarkTheme()).accented(reader.accent)
    val panels = remember {
        ReaderPanels(
            openStudy = study::open,
            toggleStudy = {
                if (study.isOpen) {
                    study.close()
                } else {
                    // The verse selected, else the one the panel last showed in this chapter, else the
                    // chapter's first — `StudyModel.turnOn(selection:)`.
                    val here = reader.location
                    val key = reader.selection.maxOrNull()
                        ?: study.verse?.takeIf { it.book == here.book && it.chapter == here.chapter }?.key
                        ?: VerseRef(here.book, here.chapter, 1).key
                    study.open(key)
                }
            },
            openOriginal = { key ->
                study.select(StudyTab.ORIGINAL)
                study.open(key)
            },
            openTranslations = { showTranslations = true },
            openCompare = { showCompare = true },
            studyOpen = { study.isOpen },
            studyBack = { if (study.isOpen) study.back(reader) },
            openMaps = {
                val here = reader.location
                study.select(StudyTab.CONTEXT)
                study.open(reader.selection.maxOrNull() ?: study.verse?.takeIf { it.book == here.book && it.chapter == here.chapter }?.key
                    ?: VerseRef(here.book, here.chapter, 1).key)
            },
        )
    }

    // Study doesn't change what a tap does — a tap still selects — the panel simply follows the verse
    // most recently selected, as on iOS.
    LaunchedEffect(reader.selection) {
        if (study.isOpen) reader.selection.maxOrNull()?.let(study::follow)
    }

    // The development hook, beside MainActivity's: `--ei study <verseKey>` opens Study on a verse,
    // `--es panel translations|compare` opens that screen, `--es studyTab context` picks a tab.
    var consumedLaunch by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (consumedLaunch) return@LaunchedEffect
        consumedLaunch = true
        val extras = (context as? Activity)?.intent?.extras ?: return@LaunchedEffect
        extras.getString("studyTab")?.let { name -> StudyTab.entries.firstOrNull { it.name.equals(name, true) }?.let(study::select) }
        extras.getInt("study", 0).takeIf { it > 0 }?.let(study::open)
        when (extras.getString("panel")) {
            "translations" -> showTranslations = true
            "compare" -> showCompare = true
        }
    }

    // Translations and Compare cover everything: modal to TalkBack, so nothing behind takes focus.
    val covered = showTranslations || showCompare
    val behind = if (covered) Modifier.clearAndSetSemantics {} else Modifier
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val wide = maxWidth >= 700.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().then(behind)) { content(panels) }
                AnimatedVisibility(
                    study.isOpen,
                    enter = slideInHorizontally(tween(260)) { it },
                    exit = slideOutHorizontally(tween(220)) { it },
                ) {
                    Box(
                        Modifier.width(380.dp).fillMaxHeight().background(SheetColors.surface(palette))
                            .windowInsetsPadding(WindowInsets.statusBars),
                    ) {
                        // The inspector's leading edge: a hairline, as iOS draws it.
                        Box(Modifier.fillMaxHeight().width(0.5.dp).background(SheetColors.separator(palette)))
                        StudyPanel(study, reader, palette, isSheet = false, onClose = study::close)
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize().then(behind)) {
                content(panels)
                StudySheet(study, reader, palette)
            }
        }

        FullSheet(showCompare, onDismiss = { showCompare = false }) {
            CompareSheet(reader, palette, onClose = { showCompare = false })
        }
        FullSheet(showTranslations, onDismiss = { showTranslations = false }) {
            TranslationsSheet(reader, palette, onClose = { showTranslations = false })
        }
    }
}

/** A full-height sheet over a dimmed reader, rising from the bottom as an iOS sheet does. */
@Composable
fun FullSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                .takesTaps(onDismiss),
        )
    }
    AnimatedVisibility(visible, enter = slideInVertically(tween(320)) { it }, exit = slideOutVertically(tween(240)) { it }) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(top = 10.dp)) {
            content()
        }
    }
}

/**
 * The phone's Study sheet: two detents, 45% and full height. The grabber and the header drag it;
 * the content scrolls rather than resizing the sheet (`presentationContentInteraction(.scrolls)`).
 * At 45% the reader behind stays live; at full height it is dimmed, and a tap there dismisses.
 */
@Composable
private fun StudySheet(study: StudyModel, reader: ReaderViewModel, palette: com.blainemiller.scripturealone.ui.reader.ReaderPalette) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val full = constraints.maxHeight.toFloat()
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val largeTop = with(density) { (statusTop + 10.dp).toPx() }
        val mediumTop = full * 0.55f
        val top = remember { Animatable(full) }
        var expanded by remember { mutableStateOf(false) }

        LaunchedEffect(study.isOpen) {
            if (study.isOpen) {
                expanded = false
                top.animateTo(mediumTop, tween(320))
            } else {
                top.animateTo(full, tween(240))
            }
        }
        // A pushed map, timeline or chart wants the room.
        LaunchedEffect(study.routes.size) {
            if (study.isOpen && study.routes.isNotEmpty() && !expanded) {
                expanded = true
                top.animateTo(largeTop, tween(280))
            }
        }

        fun settle(velocity: Float) {
            scope.launch {
                val y = top.value
                val target = when {
                    velocity > 1800f && y > mediumTop - 40 -> full
                    y > mediumTop + (full - mediumTop) * 0.35f -> full
                    velocity < -1200f -> largeTop
                    velocity > 1200f -> mediumTop
                    y < (largeTop + mediumTop) / 2 -> largeTop
                    else -> mediumTop
                }
                expanded = target == largeTop
                top.animateTo(target, tween(260))
                if (target == full) study.close()
            }
        }

        BackHandler(study.isOpen) {
            when {
                study.routes.isNotEmpty() -> study.pop()
                else -> study.close()
            }
        }

        if (study.isOpen && expanded) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f))
                    .takesTaps { study.close() },
            )
        }
        if (top.value < full) {
            val dragState = rememberDraggableState { delta -> scope.launch { top.snapTo((top.value + delta).coerceIn(largeTop, full)) } }
            val surface = SheetColors.surface(palette)
            Column(
                Modifier
                    .offset { IntOffset(0, top.value.roundToInt()) }
                    .fillMaxWidth()
                    .height(with(density) { (full - largeTop).toDp() })
                    .shadow(18.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.22f))
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(surface)
                    .takesTaps(),
            ) {
                // The grabber and the header above the tabs are the handle.
                Column(Modifier.draggable(dragState, Orientation.Vertical, onDragStopped = { settle(it) })) {
                    Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 36.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(palette.secondary.copy(alpha = 0.4f)))
                    }
                }
                // Room for the visible part of the sheet only, so its content ends above the fold.
                val visibleHeight = with(density) { (full - top.value).coerceAtLeast(0f).toDp() }
                Box(Modifier.fillMaxWidth().height(visibleHeight)) {
                    StudyPanel(
                        study, reader, palette, isSheet = true, onClose = study::close,
                        headerModifier = Modifier.draggable(dragState, Orientation.Vertical, onDragStopped = { settle(it) }),
                    )
                }
            }
        }
    }
}
