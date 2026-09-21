package com.blainemiller.scripturealone.ui.export

import com.blainemiller.scripturealone.ui.reader.FitTitle
import com.blainemiller.scripturealone.ui.reader.CappedFontScale
import com.blainemiller.scripturealone.ui.reader.takesTaps
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.ui.notes.PanelColors
import com.blainemiller.scripturealone.ui.notes.PanelHeader
import com.blainemiller.scripturealone.ui.reader.ReaderPalette

// The pieces of a SwiftUI `Form` in `.grouped` style that the export, keepsake and import sheets are
// made of, in the look the Notes panel and Appearance sheet already use (`PanelGroup`, `PanelHeader`).

/** A full-height sheet: rounded top, the grouped background, a navigation bar and a scrolling form. */
@Composable
fun FormSheet(
    title: String,
    palette: ReaderPalette,
    leading: String = "Done",
    back: Boolean = false,
    onLeading: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(PanelColors.background(palette))
            .takesTaps(),
    ) {
        if (back) {
            PanelHeader(title, palette, back = true, onLeading = onLeading)
        } else {
            LabelledHeader(title, leading, palette, onLeading)
        }
        val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom = nav + 32.dp),
            content = content,
        )
    }
}

/** [PanelHeader] with its leading control labelled ("Done", "Cancel") rather than "Close". */
@Composable
private fun LabelledHeader(title: String, leading: String, palette: ReaderPalette, onLeading: () -> Unit) = CappedFontScale {
    Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp)) {
        Box(
            Modifier.align(Alignment.CenterStart).height(44.dp).clip(RoundedCornerShape(22.dp))
                .background(com.blainemiller.scripturealone.ui.reader.SheetColors.buttonFill(palette))
                .clickable(role = Role.Button, onClick = onLeading).padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) { Text(leading, color = palette.ink, fontSize = 17.sp) }
        FitTitle(
            title, palette.ink, 17.sp,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 100.dp).semantics { heading() },
        )
    }
}

/** A section's header — "Export 12 Notes", "From You"; empty, just the gap between sections. */
@Composable
fun FormHeader(text: String, palette: ReaderPalette) {
    if (text.isEmpty()) {
        Spacer(Modifier.height(24.dp))
        return
    }
    Text(
        text, color = palette.secondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 22.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** A section's footer, beneath its card. */
@Composable
fun FormFooter(text: String, palette: ReaderPalette, color: Color = palette.secondary) {
    Text(
        text, color = color, fontSize = 13.sp, lineHeight = 17.sp,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
    )
}

/** A tappable row in the accent — a `Button` in a `Form`. [busy] puts a spinner at the end. */
@Composable
fun FormButton(
    title: String,
    palette: ReaderPalette,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    bold: Boolean = false,
    busy: Boolean = false,
    color: Color = palette.accent,
    push: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) color else palette.secondary.copy(alpha = 0.55f)
        if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(
            title, color = if (push && enabled) palette.ink else tint, fontSize = 17.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f),
        )
        if (busy) CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        if (push) Icon(Icons.Rounded.ChevronRight, null, tint = palette.secondary, modifier = Modifier.size(22.dp))
    }
}

/** A label and its value — `LabeledContent`. */
@Composable
fun FormValue(label: String, value: String, palette: ReaderPalette, icon: ImageVector? = null, dim: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = palette.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(label, color = palette.ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, color = if (dim) palette.secondary else palette.ink.copy(alpha = 0.85f), fontSize = 17.sp)
    }
}

/** One choice of an inline picker: title, a line of detail, a check when chosen — `.pickerStyle(.inline)`. */
@Composable
fun FormChoice(title: String, detail: String?, chosen: Boolean, palette: ReaderPalette, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { selected = chosen }
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.ink, fontSize = 17.sp)
            if (detail != null) Text(detail, color = palette.secondary, fontSize = 13.sp)
        }
        if (chosen) Icon(Icons.Rounded.Check, "Selected", tint = palette.accent, modifier = Modifier.size(22.dp))
    }
}

/** A menu picker row — `Picker` in a `Form`: the label, then the choice and ⌃⌄, opening a menu. */
@Composable
fun <T> FormPicker(label: String, options: List<T>, selected: T?, title: (T) -> String, palette: ReaderPalette, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { open = true }
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .semantics { contentDescription = "$label, ${selected?.let(title).orEmpty()}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = palette.ink, fontSize = 17.sp)
            Spacer(Modifier.width(12.dp))
            Text(
                selected?.let(title).orEmpty(), color = palette.secondary, fontSize = 17.sp, maxLines = 1,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Icon(Icons.Rounded.UnfoldMore, null, tint = palette.secondary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(title(option), color = palette.ink, fontSize = 15.sp) },
                    trailingIcon = { if (option == selected) Icon(Icons.Rounded.Check, null, tint = palette.accent) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** A text field in a form row. [shown] false masks it (a passphrase being entered to open a keepsake). */
@Composable
fun FormField(
    value: String,
    placeholder: String,
    palette: ReaderPalette,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minHeight: Int = 0,
    monospace: Boolean = false,
    shown: Boolean = true,
    words: Boolean = true,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Default,
    onDone: () -> Unit = {},
    /** What TalkBack calls the field when it has no placeholder (a label printed above it). */
    label: String? = null,
    onChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = TextStyle(
            color = palette.ink, fontSize = 17.sp, lineHeight = 22.sp,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        ),
        cursorBrush = SolidColor(palette.accent),
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            capitalization = if (words) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            autoCorrectEnabled = words,
            keyboardType = if (shown) KeyboardType.Text else KeyboardType.Password,
            imeAction = imeAction,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }, onGo = { onDone() }),
        modifier = modifier.fillMaxWidth().heightIn(min = maxOf(minHeight, 52).dp).padding(horizontal = 18.dp, vertical = 15.dp)
            .semantics { (label ?: placeholder.takeIf { it.isNotEmpty() })?.let { contentDescription = it } },
        decorationBox = { field ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, color = palette.secondary.copy(alpha = 0.7f), fontSize = 17.sp)
                }
                field()
            }
        },
    )
}

/** A determinate bar for the passphrase's key derivation, in the accent. */
@Composable
fun FormProgress(progress: Float, label: String, palette: ReaderPalette) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text(label, color = palette.secondary, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress }, color = palette.accent, trackColor = palette.secondary.copy(alpha = 0.2f),
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        )
    }
}
