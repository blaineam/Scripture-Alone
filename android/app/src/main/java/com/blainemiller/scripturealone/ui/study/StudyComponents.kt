package com.blainemiller.scripturealone.ui.study

import com.blainemiller.scripturealone.ui.reader.FitTitle
import com.blainemiller.scripturealone.ui.reader.CappedFontScale
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderTypography
import com.blainemiller.scripturealone.ui.reader.SheetColors
import com.blainemiller.scripturealone.ui.reader.glass

/**
 * The iOS system styles the Study panel and the Translations screen are built from, in the reader's
 * theme rather than Material's: an inset-grouped list (a slightly deeper background, rounded cells
 * in the page colour, uppercase footnote headers), a segmented control, the "content unavailable"
 * placeholder, and the Dynamic Type sizes — headline 17 semibold, body 17, callout 16, subheadline
 * 15, footnote 13, caption 12, caption2 11.
 */
object StudyStyle {
    /** `.systemGroupedBackground`: behind the cells — the page, a step deeper. */
    fun groupedBackground(p: ReaderPalette): Color =
        if (p.isDark) Color.White.copy(alpha = 0.035f).compositeOver(p.page) else Color.Black.copy(alpha = 0.04f).compositeOver(p.page)

    /** `.secondarySystemGroupedBackground`: a cell — the page itself on paper, lifted on a dark page. */
    fun cell(p: ReaderPalette): Color =
        if (p.isDark) Color.White.copy(alpha = 0.085f).compositeOver(p.page) else p.page

    fun separator(p: ReaderPalette): Color = SheetColors.separator(p).copy(alpha = if (p.isDark) 0.28f else 0.22f)

    /** `.tertiary` foreground — chevrons, placeholders. */
    fun tertiary(p: ReaderPalette): Color = p.secondary.copy(alpha = 0.55f)

    val headline = 17.sp
    val body = 17.sp
    val callout = 16.sp
    val subheadline = 15.sp
    val footnote = 13.sp
    val caption = 12.sp
    val caption2 = 11.sp

    /** `design: .serif` text — scripture and commentary — in the reader's own face. */
    fun serif(size: Float): FontFamily = ReaderTypography.sourceSerif(size)
}

/** A section of an inset-grouped list: an uppercase header, rounded cells, an optional footer. */
@Composable
fun GroupedSection(
    palette: ReaderPalette,
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (header != null) {
            Text(
                header.uppercase(), color = palette.secondary, fontSize = StudyStyle.footnote,
                letterSpacing = 0.3.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 7.dp).semantics { heading() },
            )
        } else {
            Spacer(Modifier.height(16.dp))
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(StudyStyle.cell(palette)),
            content = content,
        )
        if (footer != null) {
            Text(
                footer, color = palette.secondary, fontSize = StudyStyle.footnote, lineHeight = 17.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp),
            )
        }
    }
}

/** A hairline between cells, inset from the leading edge as iOS insets it. */
@Composable
fun CellDivider(palette: ReaderPalette, inset: Dp = 16.dp) {
    Box(Modifier.padding(start = inset).fillMaxWidth().height(0.5.dp).background(StudyStyle.separator(palette)))
}

/** One cell: padded content, optionally tappable, with a trailing chevron when it navigates. */
@Composable
fun Cell(
    palette: ReaderPalette,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = false,
    verticalPadding: Dp = 11.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = StudyStyle.tertiary(palette), modifier = Modifier.size(20.dp))
        }
    }
}

/** The label/value row of a `LabeledContent`. */
@Composable
fun LabeledCell(palette: ReaderPalette, label: String, value: String) {
    Cell(palette) {
        // The label keeps its width; the value takes the rest and wraps under a large font size.
        Text(label, color = palette.ink, fontSize = StudyStyle.body)
        Spacer(Modifier.width(12.dp))
        Text(value, color = palette.secondary, fontSize = StudyStyle.body, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/**
 * A segmented control, as `.pickerStyle(.segmented)` draws it: a grey track and a raised thumb that
 * slides to the chosen segment.
 */
@Composable
fun SegmentedPicker(
    options: List<String>,
    selected: Int,
    palette: ReaderPalette,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) = CappedFontScale {
    BoxWithConstraints(
        modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(9.dp)).background(SheetColors.tertiaryFill(palette)),
    ) {
        val segment = maxWidth / options.size.coerceAtLeast(1)
        val thumbX by animateDpAsState(segment * selected.coerceAtLeast(0), tween(220), label = "segment")
        val thumb = if (palette.isDark) Color(0xFF636366).copy(alpha = 0.85f).compositeOver(palette.page) else Color.White
        Box(
            Modifier.offset(x = thumbX).width(segment).fillMaxHeight().padding(2.dp)
                .shadow(if (palette.isDark) 0.dp else 3.dp, RoundedCornerShape(7.dp), clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.14f))
                .clip(RoundedCornerShape(7.dp))
                .background(thumb),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, title ->
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(interactionSource = null, indication = null) { onSelect(index) }
                        .semantics {
                            role = Role.Tab
                            this.selected = index == selected
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // Shrinks to fit (to 70%) before truncating, so a larger font size keeps whole words.
                    var scale by remember(title) { mutableStateOf(1f) }
                    Text(
                        title, color = palette.ink, fontSize = 13.sp * scale,
                        fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1, softWrap = false,
                        overflow = if (scale > 0.7f) TextOverflow.Clip else TextOverflow.Ellipsis,
                        onTextLayout = { if (it.didOverflowWidth && scale > 0.7f) scale -= 0.05f },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

/** `ContentUnavailableView`: a large grey symbol, a bold title, a short description, actions. */
@Composable
fun ContentUnavailable(
    icon: ImageVector,
    title: String,
    description: String?,
    palette: ReaderPalette,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = palette.secondary, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = palette.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (description != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                description, color = palette.secondary, fontSize = StudyStyle.subheadline, lineHeight = 20.sp,
                textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        actions()
    }
}

/** A borderless text button in the accent colour — `.buttonStyle(.borderless)`. */
@Composable
fun AccentButton(
    title: String,
    palette: ReaderPalette,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    fontSize: TextUnit = StudyStyle.subheadline,
    weight: FontWeight = FontWeight.Normal,
    onClick: () -> Unit,
) {
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = palette.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(title, color = palette.accent, fontSize = fontSize, fontWeight = weight)
    }
}

/** A capsule button filled with the accent — `.borderedProminent`. */
@Composable
fun ProminentButton(title: String, palette: ReaderPalette, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.clip(CircleShape).background(if (enabled) palette.accent else palette.secondary.copy(alpha = 0.3f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = if (palette.isDark) Color.Black else Color.White, fontSize = StudyStyle.subheadline, fontWeight = FontWeight.SemiBold)
    }
}

/** A capsule button tinted with the accent — `.bordered`. */
@Composable
fun BorderedButton(title: String, palette: ReaderPalette, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(CircleShape).background(palette.accent.copy(alpha = 0.14f))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = palette.accent, fontSize = StudyStyle.subheadline, fontWeight = FontWeight.Medium)
    }
}

/**
 * A sheet's top bar, as a navigation bar with inline title: glass buttons at either end (a Close or
 * a back chevron on the left; anything on the right), the title centred.
 */
@Composable
fun SheetTopBar(
    title: String,
    palette: ReaderPalette,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) = CappedFontScale {
    Box(modifier.fillMaxWidth().height(60.dp).padding(horizontal = 14.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) { leading?.invoke() }
        FitTitle(
            title, palette.ink, StudyStyle.headline,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 100.dp).semantics { heading() },
        )
        Box(Modifier.align(Alignment.CenterEnd)) { trailing?.invoke() }
    }
}

/** A round glass button holding one symbol. */
@Composable
fun GlassIconButton(icon: ImageVector, label: String, palette: ReaderPalette, surface: Color, tint: Color = palette.ink, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).glass(palette, CircleShape, surface, lifted = true).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** A glass capsule holding a word — Close, Done, Cancel. */
@Composable
fun GlassTextButton(title: String, palette: ReaderPalette, surface: Color, bold: Boolean = false, tint: Color = palette.ink, onClick: () -> Unit) {
    Box(
        Modifier.height(44.dp).glass(palette, CircleShape, surface, lifted = true).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = tint, fontSize = StudyStyle.body, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** The back chevron in a glass circle. */
@Composable
fun GlassBackButton(palette: ReaderPalette, surface: Color, onClick: () -> Unit) =
    GlassIconButton(Icons.Rounded.ChevronLeft, "Back", palette, surface, onClick = onClick)

/** A section title with a symbol — `Label(title, systemImage:)` in `.title3.weight(.semibold)`. */
@Composable
fun SymbolHeading(icon: ImageVector, title: String, palette: ReaderPalette) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { heading() }) {
        Icon(icon, null, tint = palette.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = palette.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Body text in the reader's serif, as `.font(.system(.body, design: .serif))`. */
fun serifStyle(size: Float, color: Color, lineHeight: Float = size * 1.38f) = TextStyle(
    fontFamily = StudyStyle.serif(size), fontSize = size.sp, lineHeight = lineHeight.sp, color = color,
)
