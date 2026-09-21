package com.blainemiller.scripturealone.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.blainemiller.scripturealone.data.BundledTranslations
import com.blainemiller.scripturealone.data.Canon
import com.blainemiller.scripturealone.ui.navigation.GoToSheet
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The chapter reader: the rendered chapter full-bleed on the theme's page, with the chrome floating
 * over it in pills, as the iOS reader's Liquid Glass toolbars do. The passage title opens the Go To
 * sheet; the empty places are where Notes, Study, Listen and auto-scroll will go.
 */
@Composable
fun ReaderScreen(model: ReaderViewModel) {
    val palette = model.theme.palette(isSystemInDarkTheme()).accented(model.accent)
    val style = model.style(palette)
    var goTo by rememberSaveable { mutableStateOf(false) }

    // How far the Go To sheet has risen, 0…1. As on iOS, the reader behind a sheet recedes: it
    // scales back, rounds its corners and settles onto a black backdrop.
    val sheetProgress by animateFloatAsState(if (goTo) 1f else 0f, tween(320), label = "sheet")

    // Bar icons follow what is behind them, not the system theme: a Sepia page on a dark-mode phone
    // still needs dark icons, and with a sheet up the status bar sits over the black backdrop.
    val activity = LocalContext.current as? ComponentActivity
    val lightStatusIcons = palette.isDark || goTo
    LaunchedEffect(lightStatusIcons, palette.isDark) {
        fun style(dark: Boolean) = if (dark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        }
        activity?.enableEdgeToEdge(statusBarStyle = style(lightStatusIcons), navigationBarStyle = style(palette.isDark))
    }

    MaterialTheme(colorScheme = menuColors(palette)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val scale = 1f - SHEET_RECEDE * sheetProgress
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    translationY = SHEET_DROP.toPx() * sheetProgress
                    shape = RoundedCornerShape(SHEET_CORNER * sheetProgress)
                    clip = sheetProgress > 0f
                }
                .background(palette.page),
        ) {
            val chapter = model.chapter
            when {
                chapter != null && chapter.ref == model.location && chapter.translation.id == model.translationId ->
                    key(chapter.ref, chapter.translation.id) {
                        ChapterColumn(
                            rendered = remember(chapter, style) {
                                ChapterRenderer(style, ReaderTypography.fonts(style.size))
                                    .render(chapter.ref, chapter.layout, chapter.translation.copyright)
                            },
                            palette = palette,
                            scrollTarget = model.scrollTarget,
                            onScrolledToTarget = model::scrolledToTarget,
                            onTopVerse = model::updateTopVerse,
                            onSwipe = { forward -> if (forward) model.next() else model.previous() },
                            onAction = { if (it == ReaderAction.NEXT_CHAPTER) model.next() },
                        )
                    }
                model.loadError == null -> CircularProgressIndicator(
                    // Only ever seen on a first launch, while a database is copied out of the APK.
                    color = palette.secondary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.align(Alignment.Center).size(28.dp),
                )
                else -> Text(
                    "Can’t open this chapter.\n${model.loadError}",
                    color = palette.secondary,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
            TopBar(model, palette, onGoTo = { goTo = true })
            BottomBar(model, palette, Modifier.align(Alignment.BottomCenter))
        }

            // The Go To sheet, over a dimmed reader, rising from the bottom as an iOS sheet does.
            AnimatedVisibility(goTo, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.20f))
                        .clickable(interactionSource = null, indication = null) { goTo = false },
                )
            }
            AnimatedVisibility(
                goTo,
                enter = slideInVertically(tween(320)) { it },
                exit = slideOutVertically(tween(240)) { it },
            ) {
                GoToSheet(model, palette, onDismiss = { goTo = false })
            }
        }
    }
}

/**
 * The chapter as a lazy column of paragraphs. Horizontal insets follow `ChapterGeometry`: 22 pt on
 * a phone, 40 pt from 500 pt wide, and never a line longer than 700 pt. Top 20 and bottom 140 are
 * the iOS text container's insets, below the bars.
 *
 * Also the three things `ChapterTextView` does around the text: it scrolls a target verse to the top
 * (12 pt below the bars, as iOS leaves), reports the verse at the top as the reader scrolls, and
 * turns a horizontal swipe into the next or previous chapter.
 */
@Composable
private fun ChapterColumn(
    rendered: RenderedChapter,
    palette: ReaderPalette,
    scrollTarget: Int?,
    onScrolledToTarget: () -> Unit,
    onTopVerse: (Int) -> Unit,
    onSwipe: (forward: Boolean) -> Unit,
    onAction: (ReaderAction) -> Unit,
) {
    val state = rememberLazyListState()
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current
    // Each paragraph's text layout, by index — not state: read only from effects, never to draw.
    val layouts = remember(rendered) { HashMap<Int, TextLayoutResult>() }
    val target by rememberUpdatedState(scrollTarget)
    val report by rememberUpdatedState(onTopVerse)

    LaunchedEffect(rendered, scrollTarget) {
        val key = scrollTarget ?: return@LaunchedEffect
        val (index, offset) = rendered.locate(key) ?: run {
            onScrolledToTarget()
            return@LaunchedEffect
        }
        state.scrollToItem(index)
        // The verse's line within its paragraph is known only once the paragraph has been laid out.
        withTimeoutOrNull(1_000) {
            snapshotFlow { state.layoutInfo.visibleItemsInfo.any { it.index == index } }.first { it }
        }
        layouts[index]?.let { layout ->
            val lineTop = layout.getLineTop(layout.getLineForOffset(offset))
            val before = with(density) { rendered.paragraphs[index].spaceBefore.sp.toPx() }
            val lead = with(density) { 12.dp.toPx() }
            state.scrollToItem(index, (before + lineTop - lead).roundToInt().coerceAtLeast(0))
        }
        onScrolledToTarget()
    }

    // The verse at the top: the first verse beginning at or after the top line, as iOS reports it.
    LaunchedEffect(rendered) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .map { (index, offset) ->
                val before = with(density) { rendered.paragraphs.getOrNull(index)?.spaceBefore?.sp?.toPx() ?: 0f }
                topVerse(rendered, index, offset - before, layouts[index])
            }
            .distinctUntilChanged()
            .collect { key -> if (key != null && target == null) report(key) }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(Unit) {
            // Horizontal only past the touch slop, and only if the list hasn't already claimed the
            // gesture as a vertical scroll — so paging never fights reading.
            var travel = 0f
            val threshold = 64.dp.toPx()
            detectHorizontalDragGestures(
                onDragStart = { travel = 0f },
                onDragEnd = { if (abs(travel) > threshold) onSwipe(travel < 0) },
                onHorizontalDrag = { change, amount ->
                    travel += amount
                    change.consume()
                },
            )
        },
    ) {
        val margin = if (maxWidth < 500.dp) 22.dp else 40.dp
        val inset = maxOf(margin, (maxWidth - 700.dp) / 2)
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = inset, end = inset, top = status + BAR_HEIGHT + 20.dp, bottom = nav + 140.dp),
        ) {
            itemsIndexed(rendered.paragraphs) { index, paragraph ->
                Paragraph(paragraph, palette, onAction, onLayout = { layouts[index] = it })
            }
        }
    }
}

/**
 * The verse to report for a scroll position: in paragraph [index], the first verse starting at or
 * after the line at [y] (px into the paragraph's text); failing that, the next paragraph's first
 * verse; and past the last verse (the footer), the last one.
 */
private fun topVerse(rendered: RenderedChapter, index: Int, y: Float, layout: TextLayoutResult?): Int? {
    val paragraphs = rendered.paragraphs
    val here = paragraphs.getOrNull(index) ?: return null
    val lineStart = if (layout != null && y > 0) layout.getLineStart(layout.getLineForVerticalPosition(y)) else 0
    here.verses.firstOrNull { it.offset >= lineStart }?.let { return it.key }
    for (i in index + 1 until paragraphs.size) paragraphs[i].verses.firstOrNull()?.let { return it.key }
    return paragraphs.lastOrNull { it.verses.isNotEmpty() }?.verses?.last()?.key
}

@Composable
private fun Paragraph(
    p: RenderedParagraph,
    palette: ReaderPalette,
    onAction: (ReaderAction) -> Unit,
    onLayout: (TextLayoutResult) -> Unit,
) {
    val density = LocalDensity.current
    fun Float.spDp(): Dp = with(density) { this@spDp.sp.toDp() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var footnote by remember { mutableStateOf<Footnote?>(null) }
    val notes = remember(p.text) { p.text.getStringAnnotations(ChapterRenderer.FOOTNOTE_TAG, 0, p.text.length) }

    var modifier = Modifier
        .fillMaxWidth()
        .padding(top = p.spaceBefore.spDp(), bottom = p.spaceAfter.spDp(), end = p.endIndent.spDp())
    p.action?.let { action ->
        modifier = modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            onAction(action)
        }
    }
    if (notes.isNotEmpty()) {
        modifier = modifier.pointerInput(notes) {
            // The letters are small; a tap anywhere within a finger's reach of one opens it.
            val reach = 18.dp.toPx()
            detectTapGestures { tap ->
                val text = layout ?: return@detectTapGestures
                val hit = notes.map { it to text.getBoundingBox(it.start) }
                    .filter { (_, box) -> box.inflate(reach).contains(tap) }
                    .minByOrNull { (_, box) -> (box.center - tap).getDistanceSquared() }
                    ?: return@detectTapGestures
                footnote = Footnote(hit.first.item, hit.second)
            }
        }
    }
    Box {
        Text(
            text = p.text,
            modifier = modifier,
            onTextLayout = {
                layout = it
                onLayout(it)
            },
            style = TextStyle(
                color = palette.ink,
                textAlign = p.align,
                lineHeight = p.lineHeight.sp,
                textIndent = TextIndent(firstLine = p.firstLineIndent.sp, restLine = p.restLineIndent.sp),
                // Every line the same height, the space shared above and below the glyphs, and nothing
                // trimmed at a paragraph's edges — so the gaps between paragraphs are exactly the
                // spacing the renderer asked for, as in TextKit.
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
            ),
        )
        footnote?.let { note ->
            val top = with(density) { p.spaceBefore.sp.toPx() }
            FootnotePopover(note.text, note.anchor.translate(0f, top), palette) { footnote = null }
        }
    }
}

/** A tapped footnote: its text and the letter's box, in the paragraph's text coordinates. */
private data class Footnote(val text: String, val anchor: Rect)

/**
 * The note in a small rounded card beside its letter, with an arrow pointing at it — the iOS popover,
 * which on a phone is still a popover (`presentationCompactAdaptation(.popover)`). Below the letter
 * when it fits, above when it doesn't; at most 320 wide, the popover's ideal width. Any tap outside
 * dismisses it.
 *
 * The card sits inside a transparent margin because a popup's window is exactly as large as its
 * content: without room around the card, its shadow is clipped away and it melts into a light page.
 */
@Composable
private fun FootnotePopover(text: String, anchor: Rect, palette: ReaderPalette, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val gap = with(density) { 4.dp.roundToPx() }
    val margin = with(density) { 12.dp.roundToPx() }
    val shade = with(density) { SHADOW_ROOM.roundToPx() }
    /** Where the provider put the popup: the arrow's x within it, and whether the card is below. */
    var placement by remember { mutableStateOf(0f to true) }
    val position = remember(anchor) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
            ): IntOffset {
                val cardHeight = popupContentSize.height - 2 * shade
                val centerX = anchorBounds.left + anchor.center.x.roundToInt()
                val x = (centerX - popupContentSize.width / 2)
                    .coerceIn(margin - shade, maxOf(margin - shade, windowSize.width - popupContentSize.width - margin + shade))
                val belowTop = anchorBounds.top + anchor.bottom.roundToInt() + gap
                val aboveTop = anchorBounds.top + anchor.top.roundToInt() - gap - cardHeight
                val below = belowTop + cardHeight <= windowSize.height - margin * 8 || aboveTop < margin * 8
                placement = (centerX - x).toFloat() to below
                return IntOffset(x, (if (below) belowTop else aboveTop) - shade)
            }
        }
    }
    val surface = SheetColors.popover(palette)
    val edge = (if (palette.isDark) Color.White else Color.Black).copy(alpha = 0.10f)
    val shape = RoundedCornerShape(14.dp)
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(
            Modifier
                .padding(SHADOW_ROOM)
                .widthIn(max = 320.dp)
                .drawBehind {
                    // The arrow, drawn under the card so the card's hairline doesn't cross it.
                    val (arrowX, below) = placement
                    val x = (arrowX - SHADOW_ROOM.toPx()).coerceIn(20.dp.toPx(), size.width - 20.dp.toPx())
                    val w = 9.dp.toPx()
                    val h = ARROW.toPx()
                    val path = Path().apply {
                        if (below) {
                            moveTo(x - w, h + 1f); lineTo(x, 0f); lineTo(x + w, h + 1f)
                        } else {
                            moveTo(x - w, size.height - h - 1f); lineTo(x, size.height); lineTo(x + w, size.height - h - 1f)
                        }
                        close()
                    }
                    drawPath(path, surface)
                    drawPath(path, edge, style = Stroke(width = 0.5.dp.toPx()))
                }
                .padding(top = if (placement.second) ARROW else 0.dp, bottom = if (placement.second) 0.dp else ARROW)
                .drawBehind {
                    // A blurred shadow painted under the card. An elevation shadow inside a popup
                    // window is lit as if from far above and comes out faint on a light page; iOS's
                    // popover shadow is soft, wide and plainly visible, so draw that one directly.
                    val radius = 14.dp.toPx()
                    drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = surface.toArgb() // opaque: a transparent paint casts no shadow
                            setShadowLayer(18.dp.toPx(), 0f, 6.dp.toPx(),
                                Color.Black.copy(alpha = if (palette.isDark) 0.55f else 0.22f).toArgb())
                        }
                        canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint)
                    }
                }
                .clip(shape)
                .background(surface)
                .border(0.5.dp, edge, shape)
                .clickable(interactionSource = null, indication = null, onClick = onDismiss)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(text, color = palette.ink, fontSize = 16.sp, lineHeight = 21.sp)
        }
    }
}

private val SHADOW_ROOM = 20.dp

/** The reader behind an open sheet, as iOS draws it: 8% smaller, 10 dp lower, corners rounded. */
private const val SHEET_RECEDE = 0.08f
private val SHEET_DROP = 10.dp
private val SHEET_CORNER = 12.dp
private val ARROW = 8.dp

private val BAR_HEIGHT = 64.dp

/**
 * The top chrome: the passage centred, the translation and appearance in one pill on the right —
 * the iOS arrangement. A fade from the page colour lets the text pass beneath it legibly, standing in
 * for the scroll-edge effect of iOS 26's glass.
 */
@Composable
private fun TopBar(model: ReaderViewModel, palette: ReaderPalette, onGoTo: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to palette.page, 0.75f to palette.page, 1f to palette.page.copy(alpha = 0f)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(BAR_HEIGHT + 12.dp),
    ) {
        Row(
            Modifier.align(Alignment.Center)
                .clip(RoundedCornerShape(22.dp))
                .clickable(onClick = onGoTo)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { contentDescription = "Passage, ${Canon.display(model.location)}. Go To" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                Canon.display(model.location),
                color = palette.ink,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = palette.secondary, modifier = Modifier.size(22.dp))
        }
        Pill(palette, Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) {
            TranslationButton(model, palette)
            AppearanceButton(model, palette)
        }
    }
}

@Composable
private fun BottomBar(model: ReaderViewModel, palette: ReaderPalette, modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to palette.page.copy(alpha = 0f), 0.35f to palette.page.copy(alpha = 0.85f), 1f to palette.page))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Pill(palette) {
                PillIcon(Icons.Rounded.ChevronLeft, "Previous Chapter", palette, enabled = Canon.previous(model.location) != null) {
                    model.previous()
                }
            }
            Pill(palette) {
                PillIcon(Icons.Rounded.ChevronRight, "Next Chapter", palette, enabled = Canon.next(model.location) != null) {
                    model.next()
                }
            }
        }
    }
}

/**
 * One capsule of controls. A single translucent fill and one hairline — never a background inside a
 * background — lifted off the page the way glass reads over it.
 */
@Composable
private fun Pill(palette: ReaderPalette, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier
            .height(52.dp)
            .glass(palette, CircleShape)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun PillIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    palette: ReaderPalette,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(width = 48.dp, height = 44.dp).clip(RoundedCornerShape(22.dp)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (enabled) palette.accent else palette.secondary.copy(alpha = 0.5f), modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun TranslationButton(model: ReaderViewModel, palette: ReaderPalette) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).clickable { open = true }.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(model.translationId, color = palette.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { contentDescription = "Translation, ${model.translationId}" })
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (id in BundledTranslations.ids) {
                MenuChoice(id, selected = id == model.translationId, palette) {
                    open = false
                    model.selectTranslation(id)
                }
            }
        }
    }
}

/** A small menu over the theme, accent and layout — a stand-in for the Appearance sheet. */
@Composable
private fun AppearanceButton(model: ReaderViewModel, palette: ReaderPalette) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).clickable { open = true }.padding(horizontal = 12.dp)
                .semantics { contentDescription = "Appearance" },
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("A", color = palette.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 11.dp))
            Text("A", color = palette.accent, fontSize = 23.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 7.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuHeader("Theme", palette)
            for (theme in ReaderTheme.entries) {
                MenuChoice(theme.title, theme == model.theme, palette) { model.theme = theme }
            }
            HorizontalDivider(color = palette.secondary.copy(alpha = 0.25f))
            MenuHeader("Accent", palette)
            for (accent in ReaderAccent.entries) {
                MenuChoice(accent.title, accent == model.accent, palette, swatch = accent.color(palette.isDark)) {
                    model.accent = accent
                }
            }
            HorizontalDivider(color = palette.secondary.copy(alpha = 0.25f))
            MenuHeader("Layout", palette)
            for (layout in ReadingLayout.entries) {
                MenuChoice(layout.title, layout == model.layout, palette) { model.layout = layout }
            }
            HorizontalDivider(color = palette.secondary.copy(alpha = 0.25f))
            MenuHeader("Text Size", palette)
            SizeStepper(model, palette)
            MenuHeader("Line Spacing", palette)
            SpacingStepper(model, palette)
            HorizontalDivider(color = palette.secondary.copy(alpha = 0.25f))
            MenuHeader("Show", palette)
            MenuChoice("Words of Christ in Red", model.redLetters, palette) { model.redLetters = !model.redLetters }
            MenuChoice("Verse Numbers", model.verseNumbers, palette) { model.verseNumbers = !model.verseNumbers }
            MenuChoice("Headings", model.headings, palette) { model.headings = !model.headings }
            MenuChoice("Footnotes", model.footnotes, palette) { model.footnotes = !model.footnotes }
        }
    }
}

/** Smaller and larger by 1 pt within 12–40 — the Appearance sheet's size slider, stepped. */
@Composable
private fun SizeStepper(model: ReaderViewModel, palette: ReaderPalette) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val range = ReaderStyle.SIZE_RANGE
        PillIcon(Icons.Rounded.Remove, "Smaller Text", palette, enabled = model.fontSize > range.start) {
            model.fontSize = (model.fontSize - 1f).coerceIn(range)
        }
        Text(
            "${model.fontSize.roundToInt()} pt", color = palette.ink, fontSize = 15.sp,
            textAlign = TextAlign.Center, modifier = Modifier.width(64.dp),
        )
        PillIcon(Icons.Rounded.Add, "Larger Text", palette, enabled = model.fontSize < range.endInclusive) {
            model.fontSize = (model.fontSize + 1f).coerceIn(range)
        }
    }
}

/**
 * Tighter and looser by 0.1 within 1.0–2.0 — the Appearance sheet's spacing slider, stepped.
 * Snapped to tenths so repeated taps never drift into 1.4999….
 */
@Composable
private fun SpacingStepper(model: ReaderViewModel, palette: ReaderPalette) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val range = ReaderStyle.LINE_SPACING_RANGE
        fun step(by: Float) {
            model.lineSpacing = (Math.round((model.lineSpacing + by) * 10f) / 10f).coerceIn(range)
        }
        PillIcon(Icons.Rounded.Remove, "Tighter Lines", palette, enabled = model.lineSpacing > range.start + 0.001f) { step(-0.1f) }
        Text(
            String.format(java.util.Locale.US, "%.1f×", model.lineSpacing), color = palette.ink, fontSize = 15.sp,
            textAlign = TextAlign.Center, modifier = Modifier.width(64.dp),
        )
        PillIcon(Icons.Rounded.Add, "Looser Lines", palette, enabled = model.lineSpacing < range.endInclusive - 0.001f) { step(0.1f) }
    }
}

@Composable
private fun MenuHeader(title: String, palette: ReaderPalette) {
    Text(
        title.uppercase(), color = palette.secondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun MenuChoice(title: String, selected: Boolean, palette: ReaderPalette, swatch: Color? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (swatch != null) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(swatch))
                    Spacer(Modifier.width(10.dp))
                }
                Text(title, color = palette.ink, fontSize = 15.sp)
            }
        },
        trailingIcon = { if (selected) Icon(Icons.Rounded.Check, "Selected", tint = palette.accent) },
        onClick = onClick,
    )
}

/** Material's menus, recoloured to the reader's page so they read as the same surface. */
private fun menuColors(palette: ReaderPalette) = if (palette.isDark) {
    darkColorScheme(
        primary = palette.accent, surface = lerpToward(palette.page, Color.White, 0.08f), onSurface = palette.ink,
        surfaceContainer = lerpToward(palette.page, Color.White, 0.08f), background = palette.page,
    )
} else {
    lightColorScheme(
        primary = palette.accent, surface = lerpToward(palette.page, Color.Black, 0.02f), onSurface = palette.ink,
        surfaceContainer = lerpToward(palette.page, Color.Black, 0.02f), background = palette.page,
    )
}

private fun lerpToward(from: Color, to: Color, amount: Float) = to.copy(alpha = amount).compositeOver(from)
