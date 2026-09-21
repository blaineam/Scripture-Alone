package com.blainemiller.scripturealone.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.rights.TranslationRights
import com.blainemiller.scripturealone.data.userdata.HighlightColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The callbacks for the selection bar's actions that other parts of the app will own. Each is inert
 * until wired: Listen (read the selection aloud), Original Language (word by word, one verse), and
 * Study and Compare, which the reader's chrome offers.
 */
data class SelectionActions(
    val onListen: (List<VerseRange>) -> Unit = {},
    val onOriginalLanguage: (VerseRef) -> Unit = {},
)

/**
 * Appears while verses are selected — `ScriptureAlone/Reader/SelectionBar.swift`. The reference, Listen
 * and Clear on top; below, in iOS's order: five highlight colours, Remove Highlight, a divider,
 * Favorite, Add Note, Copy, Original Language (one verse only) and Share. Every control takes an equal
 * share of the row, so it fits any phone.
 *
 * Copy is gated on the translation's rights — `permits(COPY)` and `mayQuote` — and Share on
 * `mayQuote`, disabled rather than hidden, with the reason spelled out beneath.
 */
@Composable
fun SelectionBar(
    model: ReaderViewModel,
    palette: ReaderPalette,
    isFavorite: Boolean,
    actions: SelectionActions,
    onNote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ranges = model.selectedRanges
    val rights = model.rights
    val mayQuote = model.mayQuote()
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            // Opaquer than the toolbar pills: Android has no backdrop blur, and the verses beneath would
            // read through a 92% fill where iOS's glass blurs them away.
            .glass(palette, RoundedCornerShape(26.dp), lifted = true, opacity = 0.985f)
            // Swallows taps between the controls, so they don't fall through and select a verse.
            .clickable(interactionSource = null, indication = null) {}
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ranges.joinToString(", ") { it.display }, color = palette.ink, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier.clip(CircleShape).clickable { actions.onListen(ranges) }.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Headphones, null, tint = palette.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("Listen", color = palette.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable { model.clearSelection() }
                    .semantics { contentDescription = "Clear Selection" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, null, tint = palette.secondary, modifier = Modifier.size(19.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            for (color in HighlightColor.entries) {
                BarCell("Highlight ${color.title}", onClick = { model.highlightSelection(color) }) {
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(swatch(color))
                            .border(1.dp, palette.ink.copy(alpha = 0.12f), CircleShape),
                    )
                }
            }
            BarIcon(ReaderIcons.Eraser, "Remove Highlight", palette) { model.removeSelectedHighlights() }
            Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(24.dp).background(SheetColors.separator(palette)))
            BarCell(if (isFavorite) "Remove from Favorites" else "Add to Favorites", onClick = { model.toggleFavoriteSelection() }) {
                Icon(
                    if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null,
                    tint = if (isFavorite) Color(0xFFFF3B30) else palette.ink, modifier = Modifier.size(24.dp),
                )
            }
            BarIcon(ReaderIcons.SquareAndPencil, "Add Note", palette, onClick = onNote)
            BarIcon(
                if (copied) Icons.Rounded.Check else Icons.Outlined.ContentCopy, "Copy", palette,
                enabled = mayQuote && rights.permits(TranslationRights.Permission.COPY),
            ) {
                scope.launch {
                    val text = model.quotation(ranges)
                    if (text.isEmpty()) return@launch
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(ranges.joinToString(", ") { it.display }, text))
                    copied = true
                    delay(1_200)
                    copied = false
                }
            }
            val single = model.selection.singleOrNull()
            if (single != null) {
                BarIcon(Icons.Outlined.Translate, "Original Language", palette) {
                    actions.onOriginalLanguage(VerseRef.fromKey(single))
                }
            }
            ShareMenu(model, palette, ranges, enabled = mayQuote)
        }
        if (!mayQuote) {
            Text(
                quotationLimitNotice(rights, model.translationAbbreviation), color = palette.secondary, fontSize = 12.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Says whose limit it is and what it is, because "this doesn't work" is not an explanation. */
internal fun quotationLimitNotice(rights: TranslationRights, abbreviation: String): String {
    val limit = rights.maxQuotationVerses
    val name = abbreviation.ifEmpty { "This translation" }
    if (limit <= 0) return "$name can’t be quoted outside the app."
    return "$name allows up to $limit verses in one quotation. Select fewer to copy or share."
}

/** A highlight colour's swatch, at full strength — `HighlightColor.swatch`. */
fun swatch(color: HighlightColor): Color = Color(0xFF000000 or color.rgb)

/** A highlight as drawn over the text: the swatch at 42% on a light page, 34% on a dark one. */
fun highlightFill(color: HighlightColor, isDark: Boolean): Color = swatch(color).copy(alpha = color.alpha(isDark))

/** One flexible slot in the action row: shrinks with the bar, never below a 28-dp target. */
@Composable
private fun RowScope.BarCell(label: String, enabled: Boolean = true, onClick: () -> Unit, content: @Composable () -> Unit) =
    Cell(Modifier.weight(1f), label, enabled, onClick, content)

@Composable
private fun Cell(modifier: Modifier, label: String, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxWidth().widthIn(min = 28.dp).heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun RowScope.BarIcon(icon: ImageVector, label: String, palette: ReaderPalette, enabled: Boolean = true, onClick: () -> Unit) {
    BarCell(label, enabled, onClick) {
        Icon(icon, null, tint = if (enabled) palette.ink else palette.secondary.copy(alpha = 0.45f), modifier = Modifier.size(24.dp))
    }
}

/**
 * The share button — `ShareMenu` in `ShareSupport.swift`: Share Image…, Share Text and Copy Link, plus
 * Share Link… for Android's share sheet. The image designer isn't built yet, so its item is shown
 * disabled. The link is worked out as the menu opens; a passage too long for a link, or a
 * translation whose terms keep links off, says so instead.
 */
@Composable
private fun RowScope.ShareMenu(model: ReaderViewModel, palette: ReaderPalette, ranges: List<VerseRange>, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf<String?>(null) }
    val selection = model.selection
    LaunchedEffect(selection, model.translationId) { link = model.shareLink(ranges) }
    val shareAllowed = model.rights.permits(TranslationRights.Permission.SHARE)
    val reference = ranges.joinToString(", ") { it.display }

    fun send(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, reference)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }

    Box(Modifier.weight(1f)) {
        Cell(Modifier, "Share", enabled, onClick = { open = true }) {
            Icon(
                if (copied) Icons.Rounded.Check else Icons.Outlined.IosShare, null,
                tint = if (enabled) palette.ink else palette.secondary.copy(alpha = 0.45f), modifier = Modifier.size(24.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem("Share Image…", palette, enabled = false) {}
            MenuItem("Share Text", palette, enabled = shareAllowed) {
                open = false
                scope.launch { model.quotation(ranges).takeIf { it.isNotEmpty() }?.let(::send) }
            }
            val url = link
            MenuItem("Copy Link", palette, enabled = url != null) {
                open = false
                url ?: return@MenuItem
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(reference, url))
                scope.launch {
                    copied = true
                    delay(1_200)
                    copied = false
                }
            }
            MenuItem("Share Link…", palette, enabled = url != null) {
                open = false
                url?.let(::send)
            }
            if (url == null) {
                Text(
                    if (!shareAllowed) "Links aren’t available for this translation" else "Too long for a link — share the image",
                    color = palette.secondary, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).widthIn(max = 240.dp),
                )
            }
        }
    }
}

@Composable
private fun MenuItem(title: String, palette: ReaderPalette, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title, color = if (enabled) palette.ink else palette.secondary.copy(alpha = 0.6f), fontSize = 15.sp) },
        enabled = enabled,
        onClick = onClick,
    )
}
