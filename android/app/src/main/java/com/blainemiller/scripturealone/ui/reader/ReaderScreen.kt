package com.blainemiller.scripturealone.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.data.BundledTranslations
import com.blainemiller.scripturealone.data.Canon

/**
 * The chapter reader: the rendered chapter full-bleed on the theme's page, with the chrome floating
 * over it in pills, as the iOS reader's Liquid Glass toolbars do. Only what this stage needs is here
 * — the passage title, the translation switcher, a minimal appearance menu and chapter paging; the
 * empty places are where Notes, Study, Listen and auto-scroll will go.
 */
@Composable
fun ReaderScreen(model: ReaderViewModel) {
    val palette = model.theme.palette(isSystemInDarkTheme()).accented(model.accent)
    val style = ReaderStyle(palette = palette, layout = model.layout)

    MaterialTheme(colorScheme = menuColors(palette)) {
        Box(Modifier.fillMaxSize().background(palette.page)) {
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
            TopBar(model, palette)
            BottomBar(model, palette, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * The chapter as a lazy column of paragraphs. Horizontal insets follow `ChapterGeometry`: 22 pt on
 * a phone, 40 pt from 500 pt wide, and never a line longer than 700 pt. Top 20 and bottom 140 are
 * the iOS text container's insets, below the bars.
 */
@Composable
private fun ChapterColumn(rendered: RenderedChapter, palette: ReaderPalette, onAction: (ReaderAction) -> Unit) {
    val state = rememberLazyListState()
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margin = if (maxWidth < 500.dp) 22.dp else 40.dp
        val inset = maxOf(margin, (maxWidth - 700.dp) / 2)
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = inset, end = inset, top = status + BAR_HEIGHT + 20.dp, bottom = nav + 140.dp),
        ) {
            itemsIndexed(rendered.paragraphs) { _, paragraph ->
                Paragraph(paragraph, palette, onAction)
            }
        }
    }
}

@Composable
private fun Paragraph(p: RenderedParagraph, palette: ReaderPalette, onAction: (ReaderAction) -> Unit) {
    val density = LocalDensity.current
    fun Float.spDp(): Dp = with(density) { this@spDp.sp.toDp() }
    var modifier = Modifier
        .fillMaxWidth()
        .padding(top = p.spaceBefore.spDp(), bottom = p.spaceAfter.spDp(), end = p.endIndent.spDp())
    p.action?.let { action ->
        modifier = modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            onAction(action)
        }
    }
    Text(
        text = p.text,
        modifier = modifier,
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
}

private val BAR_HEIGHT = 64.dp

/**
 * The top chrome: the passage centred, the translation and appearance in one pill on the right —
 * the iOS arrangement. A fade from the page colour lets the text pass beneath it legibly, standing in
 * for the scroll-edge effect of iOS 26's glass.
 */
@Composable
private fun TopBar(model: ReaderViewModel, palette: ReaderPalette) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to palette.page, 0.75f to palette.page, 1f to palette.page.copy(alpha = 0f)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(BAR_HEIGHT + 12.dp),
    ) {
        Row(
            Modifier.align(Alignment.Center).semantics { contentDescription = "Passage, ${Canon.display(model.location)}" },
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
    val lift = if (palette.isDark) Color.White else Color.Black
    Row(
        modifier
            .height(52.dp)
            .clip(CircleShape)
            .background(lift.copy(alpha = if (palette.isDark) 0.07f else 0.045f).compositeOver(palette.page.copy(alpha = 0.92f)))
            .border(0.5.dp, lift.copy(alpha = if (palette.isDark) 0.12f else 0.08f), CircleShape)
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
        }
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
