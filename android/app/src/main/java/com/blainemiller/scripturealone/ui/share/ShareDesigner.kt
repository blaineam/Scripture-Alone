package com.blainemiller.scripturealone.ui.share

import com.blainemiller.scripturealone.ui.reader.takesTaps
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.ui.appearance.SwitchRow
import com.blainemiller.scripturealone.ui.notes.PanelColors
import com.blainemiller.scripturealone.ui.notes.Segmented
import com.blainemiller.scripturealone.ui.reader.ReaderFontFamily
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import com.blainemiller.scripturealone.ui.reader.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlin.math.min

/**
 * Designs a verse image on device — `ScriptureAlone/Share/ShareDesigner.swift`: a live card preview,
 * then the eight templates, the three shapes, the typeface, alignment and what to show, and Save to
 * Photos, Share Link and Copy Link beneath; Done and Share Image in the header.
 *
 * Every choice is remembered under iOS's `share.*` keys (on the reader's model). The card is drawn by
 * [ShareCardRenderer] — the same drawing for the preview, the PNG and a share link's card — and the
 * PNG is made fresh at 2× when it is shared or saved, so a stale image can never go out.
 */
@Composable
fun ShareDesigner(model: ReaderViewModel, source: ShareSource, palette: ReaderPalette, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val renderer = remember { ShareCardRenderer(context.applicationContext) }
    val style = model.shareStyle
    // The fit and the style and passage it was made for: only a fit for what is chosen now can be
    // shared (the RenderKey of iOS), while the preview keeps showing the last one until the next lands.
    var fitted by remember { mutableStateOf<Triple<ShareSource, ShareStyle, ShareCardFitter.Result>?>(null) }
    val fit = fitted?.takeIf { it.first == source && it.second == style }?.third
    val shown = fitted?.third
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    BackHandler(onBack = onDone)

    // Fitting measures the passage a few dozen times: off the main thread, keyed by what changes it.
    LaunchedEffect(source, style) {
        fitted = Triple(source, style, withContext(Dispatchers.Default) { renderer.fit(source, style) })
    }

    fun flash(message: String) {
        status = message
        scope.launch {
            delay(2_500)
            if (status == message) status = null
        }
    }

    /** Renders the current card at 2× and hands it to [use]; errors become the status line. */
    fun export(use: suspend (android.graphics.Bitmap, String) -> Unit) {
        val content = fit?.content ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.Default) { renderer.bitmap(content, style) }
                use(bitmap, source.filename(content))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An IOException from the file or photo library, a SecurityException from MediaStore.
                flash(e.message ?: "Can’t make the image.")
            } finally {
                busy = false
            }
        }
    }

    val surface = PanelColors.background(palette)
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(surface)
            .takesTaps(),
    ) {
        // Done · Share Image · share.
        Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp)) {
            Box(
                Modifier.align(Alignment.CenterStart).height(44.dp).glass(palette, CircleShape, surface, lifted = true)
                    .clickable(role = Role.Button, onClick = onDone).padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Done", color = palette.ink, fontSize = 17.sp) }
            Text(
                "Share Image", color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center),
            )
            Box(
                Modifier.align(Alignment.CenterEnd).size(44.dp).glass(palette, CircleShape, surface, lifted = true)
                    .clickable(enabled = fit != null && !busy, role = Role.Button) {
                        export { bitmap, name ->
                            val uri = ShareExport.write(context, bitmap, name)
                            context.startActivity(ShareExport.shareIntent(uri, fit?.content?.reference ?: name))
                        }
                    }
                    .semantics { contentDescription = "Share Image" },
                contentAlignment = Alignment.Center,
            ) {
                if (fit == null || busy) {
                    CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Icon(Icons.Outlined.IosShare, null, tint = palette.ink, modifier = Modifier.size(22.dp))
                }
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = nav + 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Preview(renderer, shown?.content, style, maxHeight = 400f, reference = shown?.content?.reference ?: source.reference)
            shown?.let(ShareCardFitter::trimNote)?.let {
                Text(it, color = palette.secondary, fontSize = 13.sp, lineHeight = 17.sp)
            }

            TemplateStrip(style, palette) { model.shareTemplate = it }
            Flush { Segmented(ShareAspect.entries, style.aspect, { it.title }, palette) { model.shareAspect = it } }
            TypefaceRow(style.family, palette) { model.shareFamily = it }
            Flush { Segmented(ShareAlignment.entries, style.alignment, { it.title }, palette) { model.shareAlignment = it } }

            Column {
                SwitchRow("Words of Christ in red", style.redLetters && source.hasRed, palette, enabled = source.hasRed, inset = 0.dp) {
                    model.shareRedLetters = it
                }
                if (source.verses.size > 1) {
                    SwitchRow("Verse numbers", style.verseNumbers, palette, inset = 0.dp) { model.shareVerseNumbers = it }
                }
                SwitchRow("Scripture Alone wordmark", style.wordmark, palette, inset = 0.dp) { model.shareWordmark = it }
            }

            Text("Text sizes itself to fit. Made on this device — nothing is uploaded.", color = palette.secondary, fontSize = 12.sp, lineHeight = 16.sp)

            // Actions.
            val link = source.link(style)
            ActionButton("Save to Photos", Icons.Outlined.SaveAlt, palette, enabled = fit != null && !busy) {
                export { bitmap, name ->
                    ShareExport.saveToPhotos(context, bitmap, name)
                    flash("Saved to Photos")
                }
            }
            if (link != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Share Link", Icons.Outlined.Link, palette, Modifier.weight(1f)) { sendText(context, link, source.reference) }
                    ActionButton("Copy Link", Icons.Outlined.ContentCopy, palette, Modifier.weight(1f)) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(source.reference, link))
                        flash("Link copied")
                    }
                }
                Text(
                    "The link carries the verse itself, so the card rebuilds in any browser. No server stores it.",
                    color = palette.secondary, fontSize = 12.sp, lineHeight = 16.sp,
                )
            } else {
                Text(
                    if (source.linksAllowed) "This passage is too long for a link — share the image or the text instead."
                    else "Links aren’t available for this translation — share the image instead.",
                    color = palette.secondary, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
            status?.let { Text(it, color = palette.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/**
 * Cancels the 16-dp side inset [Segmented] draws for sheets that have none of their own, so it lines
 * up with the rest of the designer's column.
 */
@Composable
private fun Flush(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().layout { measurable, constraints ->
        val extra = 32.dp.roundToPx()
        val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
        layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
    }) { content() }
}

private fun sendText(context: Context, text: String, subject: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    context.startActivity(Intent.createChooser(intent, null))
}

/**
 * The card, scaled to fit the width and [maxHeight] dp, rounded 14 and shadowed — the designer's
 * `preview`. Drawn by the same [ShareCardRenderer] as the exported PNG, into a Compose canvas.
 */
@Composable
fun Preview(renderer: ShareCardRenderer, content: ShareCardContent?, style: ShareStyle, maxHeight: Float, reference: String) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val scale = min(maxWidth.value / style.aspect.width, maxHeight / style.aspect.height)
        val width = (style.aspect.width * scale).dp
        val height = (style.aspect.height * scale).dp
        Box(
            Modifier.size(width, height)
                .shadow(14.dp, RoundedCornerShape(14.dp), ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
                .clip(RoundedCornerShape(14.dp))
                .background(templateBrush(style.template))
                .semantics { contentDescription = "Preview: $reference, ${style.template.title}" },
        ) {
            if (content != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val factor = size.width / style.aspect.width
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        native.save()
                        native.scale(factor, factor)
                        renderer.draw(native, content, style)
                        native.restore()
                    }
                }
            }
        }
    }
}

/** A template's ground as a Compose brush — the strip's swatches, and the preview before it's drawn. */
fun templateBrush(template: ShareTemplate): Brush {
    val colors = template.background.map { Color(ShareCardRenderer.argb(it)) }
    return if (colors.size == 1) Brush.verticalGradient(listOf(colors[0], colors[0])) else Brush.verticalGradient(colors)
}

/** Eight 56-dp swatches with "Aa" in the chosen face and the template's ink — `templateStrip`. */
@Composable
private fun TemplateStrip(style: ShareStyle, palette: ReaderPalette, onSelect: (ShareTemplate) -> Unit) {
    val family = remember(style.family) { style.family.fontFamily(17f) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(ShareTemplate.entries) { item ->
            val chosen = item == style.template
            Column(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { onSelect(item) }
                    .semantics {
                        contentDescription = item.title
                        selected = chosen
                    }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(templateBrush(item))
                        .border(
                            if (chosen) 3.dp else 1.dp,
                            if (chosen) palette.accent else palette.ink.copy(alpha = 0.12f),
                            RoundedCornerShape(10.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Aa", color = Color(ShareCardRenderer.argb(item.ink)), fontSize = 17.sp, fontFamily = family)
                }
                Text(item.title, color = if (chosen) palette.ink else palette.secondary, fontSize = 11.sp)
            }
        }
    }
}

/** "Typeface" and a menu of the seven faces, each in itself — the designer's typeface `Picker`. */
@Composable
private fun TypefaceRow(family: ReaderFontFamily, palette: ReaderPalette, onSelect: (ReaderFontFamily) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Typeface", color = palette.ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { open = true }.padding(horizontal = 8.dp, vertical = 6.dp)
                    .semantics { contentDescription = "Typeface, ${family.title}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(family.title, color = palette.accent, fontSize = 17.sp, fontFamily = family.fontFamily(17f))
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.UnfoldMore, null, tint = palette.accent, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (option in ReaderFontFamily.entries) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.title, color = palette.ink, fontSize = 16.sp, fontFamily = option.fontFamily(16f))
                                Text("For ${option.iosTitle}", color = palette.secondary, fontSize = 11.sp)
                            }
                        },
                        trailingIcon = {
                            if (option == family) Icon(Icons.Rounded.Check, "Selected", tint = palette.accent)
                        },
                        onClick = {
                            open = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

/** A full-width glass button with a symbol — `.buttonStyle(.glass)`. */
@Composable
private fun ActionButton(
    title: String,
    icon: ImageVector,
    palette: ReaderPalette,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier.height(48.dp).glass(palette, CircleShape, PanelColors.background(palette), lifted = true)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) palette.ink else palette.secondary.copy(alpha = 0.5f)
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = tint, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}
