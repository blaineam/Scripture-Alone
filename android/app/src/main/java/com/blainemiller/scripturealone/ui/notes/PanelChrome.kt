package com.blainemiller.scripturealone.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.SheetColors
import com.blainemiller.scripturealone.ui.reader.glass

/**
 * The grouped-list look of an iOS sheet (`.formStyle(.grouped)`, an inset-grouped `List`), derived
 * from the reader's page so a Sepia reader gets a Sepia panel: a slightly deeper background, and the
 * rows on rounded cards of the page colour.
 */
object PanelColors {
    fun background(palette: ReaderPalette): Color =
        if (palette.isDark) Color.White.copy(alpha = 0.03f).compositeOver(palette.page)
        else Color.Black.copy(alpha = 0.045f).compositeOver(palette.page)

    fun card(palette: ReaderPalette): Color =
        if (palette.isDark) Color.White.copy(alpha = 0.09f).compositeOver(palette.page) else palette.page
}

/** Leading glass control, centred title, trailing controls — the sheet's navigation bar. */
@Composable
fun PanelHeader(
    title: String,
    palette: ReaderPalette,
    back: Boolean,
    onLeading: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val surface = PanelColors.background(palette)
    Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp)) {
        if (back) {
            Box(
                Modifier.align(Alignment.CenterStart).size(44.dp)
                    .glass(palette, CircleShape, surface, lifted = true)
                    .clickable(onClick = onLeading)
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.ChevronLeft, null, tint = palette.ink, modifier = Modifier.size(28.dp))
            }
        } else {
            Box(
                Modifier.align(Alignment.CenterStart).height(44.dp)
                    .glass(palette, CircleShape, surface, lifted = true)
                    .clickable(onClick = onLeading)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Close", color = palette.ink, fontSize = 17.sp)
            }
        }
        Text(
            title, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 100.dp),
        )
        Row(
            Modifier.align(Alignment.CenterEnd).height(44.dp).glass(palette, CircleShape, surface, lifted = true),
            verticalAlignment = Alignment.CenterVertically,
            content = trailing,
        )
    }
}

/** One round control inside the header's trailing pill. */
@Composable
fun PanelHeaderIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, palette: ReaderPalette, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 48.dp, height = 44.dp).clip(CircleShape).clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = palette.ink, modifier = Modifier.size(24.dp))
    }
}

/** The glass search field — `.searchable`. */
@Composable
fun PanelSearchField(query: String, prompt: String, palette: ReaderPalette, onChange: (String) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(44.dp)
            .glass(palette, CircleShape, PanelColors.background(palette), lifted = true)
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = palette.secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = palette.ink, fontSize = 17.sp),
            cursorBrush = SolidColor(palette.accent),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            modifier = Modifier.weight(1f).semantics { contentDescription = prompt },
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(prompt, color = palette.secondary.copy(alpha = 0.8f), fontSize = 17.sp, maxLines = 1)
                    field()
                }
            },
        )
        if (query.isNotEmpty()) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable { onChange("") }.semantics { contentDescription = "Clear" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Cancel, null, tint = palette.secondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** A segmented control — `Picker(...).pickerStyle(.segmented)`: a grey track, the choice on a raised capsule. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, title: (T) -> String, palette: ReaderPalette, onSelect: (T) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(36.dp).clip(CircleShape)
            .background(SheetColors.tertiaryFill(palette)).padding(2.dp),
    ) {
        for (option in options) {
            val on = option == selected
            Box(
                Modifier.weight(1f).height(32.dp).clip(CircleShape)
                    .then(if (on) Modifier.background(PanelColors.card(palette).let { if (palette.isDark) Color.White.copy(alpha = 0.18f).compositeOver(it) else it }) else Modifier)
                    .clickable { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title(option), color = palette.ink, fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                )
            }
        }
    }
}

/** A rounded card of rows — one section of an inset-grouped list. */
@Composable
fun PanelGroup(palette: ReaderPalette, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(PanelColors.card(palette)),
        verticalArrangement = Arrangement.Top,
        content = content,
    )
}

/** A section title above a group — "Passages", "Note". */
@Composable
fun PanelSectionTitle(title: String, palette: ReaderPalette) {
    Text(
        title, color = palette.secondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 32.dp, top = 22.dp, bottom = 8.dp),
    )
}

/** A hairline between rows, inset from the leading edge as iOS insets it. */
@Composable
fun PanelSeparator(palette: ReaderPalette, inset: Boolean = true) {
    Box(
        Modifier.padding(start = if (inset) 18.dp else 0.dp).fillMaxWidth().height(0.5.dp)
            .background(SheetColors.separator(palette)),
    )
}

/** `ContentUnavailableView`: a large symbol, a title, and a line of explanation, centred. */
@Composable
fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    palette: ReaderPalette,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = palette.secondary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, color = palette.secondary, fontSize = 15.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
    }
}
