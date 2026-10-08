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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.R
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import kotlin.math.ceil
import kotlin.math.max

/**
 * Designs a verse image on device — `ScriptureAlone/Share/ShareDesigner.swift`: a live card preview
 * (pinned above the controls on a phone, beside them on a wide screen), a row of ready-made Styles,
 * the shape, and — one tap deeper under Customize — the background, text colour, shadow, typeface,
 * alignment and what to show. Save to Photos, Share Link and Copy Link beneath; Done and Share Image
 * in the header.
 *
 * The design is remembered under iOS's `share.*` keys ([ShareStyle.save]). The card is drawn by
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
    var customizing by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onDone)

    // Fitting measures the passage a few dozen times: off the main thread, keyed by what changes it.
    LaunchedEffect(source, style) {
        fitted = Triple(source, style, withContext(Dispatchers.Default) { renderer.fit(source, style) })
    }
    // The colours that read on the ground as drawn (measured off the main thread; its own colours until then).
    val colors by produceState(style.colors(ShareBackdropStats.approximate(style.background)), style.background, style.ink, style.shadow) {
        value = withContext(Dispatchers.Default) { style.colors(ShareBackdrop.stats(style.background)) }
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
                // Drawn at the output size (2160 px on the long side), written out, then let go at once:
                // nothing holds the 18 MB card after its PNG exists.
                val bitmap = withContext(Dispatchers.Default) { renderer.bitmap(content, style) }
                try {
                    use(bitmap, source.filename(content))
                } finally {
                    bitmap.recycle()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An IOException from the file or photo library, a SecurityException from MediaStore.
                flash(e.message ?: context.getString(R.string.share_error_make))
            } finally {
                busy = false
            }
        }
    }

    // Ctrl+S (MainActivity): Save to Photos, as the button below does.
    val saveToPhotos by rememberUpdatedState {
        export { bitmap, name ->
            ShareExport.saveToPhotos(context, bitmap, name)
            flash(context.getString(R.string.share_saved_to_photos))
        }
    }
    LaunchedEffect(model) { model.designerSaves.collect { saveToPhotos() } }

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
            ) { Text(stringResource(R.string.common_done), color = palette.ink, fontSize = 17.sp) }
            val shareImage = stringResource(R.string.share_image)
            Text(
                shareImage, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
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
                    .semantics { contentDescription = shareImage },
                contentAlignment = Alignment.Center,
            ) {
                if (fit == null || busy) {
                    CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Icon(Icons.Outlined.IosShare, null, tint = palette.ink, modifier = Modifier.size(22.dp))
                }
            }
        }

        val controls: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                shown?.let(ShareCardFitter::trimNote)?.let {
                    Text(it, color = palette.secondary, fontSize = 13.sp, lineHeight = 17.sp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(R.string.share_styles), palette)
                    StyleStrip(style, palette) { model.shareStyle = style.applying(it) }
                }
                Flush { Segmented(ShareAspect.entries, style.aspect, { it.title }, palette) { model.shareStyle = style.copy(aspect = it) } }

                CustomizeRow(customizing, palette) { customizing = !customizing }
                if (customizing) {
                    FineControls(model, style, colors, source.hasRed, source.verses.size > 1, palette)
                }

                Text(stringResource(R.string.share_made_on_device), color = palette.secondary, fontSize = 12.sp, lineHeight = 16.sp)

                // Actions.
                val link = source.link(style)
                val savedToPhotos = stringResource(R.string.share_saved_to_photos)
                ActionButton(stringResource(R.string.share_save_to_photos), Icons.Outlined.SaveAlt, palette, enabled = fit != null && !busy) {
                    export { bitmap, name ->
                        ShareExport.saveToPhotos(context, bitmap, name)
                        flash(savedToPhotos)
                    }
                }
                if (link != null) {
                    val linkCopied = stringResource(R.string.share_link_copied)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionButton(stringResource(R.string.share_share_link), Icons.Outlined.Link, palette, Modifier.weight(1f)) { sendText(context, link, source.reference) }
                        ActionButton(stringResource(R.string.share_copy_link), Icons.Outlined.ContentCopy, palette, Modifier.weight(1f)) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(source.reference, link))
                            flash(linkCopied)
                        }
                    }
                    Text(
                        stringResource(R.string.share_link_explainer),
                        color = palette.secondary, fontSize = 12.sp, lineHeight = 16.sp,
                    )
                } else {
                    Text(
                        stringResource(if (source.linksAllowed) R.string.share_link_too_long else R.string.share_link_not_allowed),
                        color = palette.secondary, fontSize = 12.sp, lineHeight = 16.sp,
                    )
                }
                status?.let { Text(it, color = palette.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 720.dp) {
                // Wide: the preview beside the controls.
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Box(Modifier.weight(1f).padding(vertical = 16.dp)) {
                        Preview(renderer, shown?.content, style, maxHeight = 560f, reference = shown?.content?.reference ?: source.reference, colors = colors)
                    }
                    Column(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = nav + 24.dp, top = 8.dp)) {
                        controls()
                    }
                }
            } else {
                // A phone: the preview pinned, the controls scrolling beneath it.
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                        Preview(renderer, shown?.content, style, maxHeight = 300f, reference = shown?.content?.reference ?: source.reference, colors = colors)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.ink.copy(alpha = 0.08f)))
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                            .padding(top = 14.dp, bottom = nav + 24.dp),
                    ) { controls() }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, palette: ReaderPalette) {
    Text(title, color = palette.secondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
}

/** "Customize" and a chevron: opens the fine controls beneath. */
@Composable
private fun CustomizeRow(open: Boolean, palette: ReaderPalette, onToggle: () -> Unit) {
    val state = stringResource(if (open) R.string.share_customize_shown else R.string.share_customize_hidden)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = state },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Tune, null, tint = palette.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.share_customize), color = palette.ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Icon(
            Icons.Rounded.KeyboardArrowDown, null, tint = palette.secondary,
            modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = if (open) 180f else 0f },
        )
    }
}

/** Background, text colour, shadow, typeface, alignment and what to show — one tap below the Styles. */
@Composable
private fun FineControls(
    model: ReaderViewModel,
    style: ShareStyle,
    colors: ShareColors,
    hasRed: Boolean,
    severalVerses: Boolean,
    palette: ReaderPalette,
) {
    var picking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.share_background), palette)
            // A new ground keeps the typeface and shadow; the text colour follows it unless one was picked.
            BackgroundRow(stringResource(R.string.share_background_clean), ShareBackground.CLEAN, style.background, palette) {
                model.shareStyle = style.copy(background = it)
            }
            BackgroundRow(stringResource(R.string.share_background_textured), ShareBackground.TEXTURED, style.background, palette) {
                model.shareStyle = style.copy(background = it)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.share_text_color), palette)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InkChip(style.background.ink, style.ink == null, stringResource(R.string.share_ink_automatic), palette, automatic = true) {
                    model.shareStyle = style.copy(ink = null)
                }
                for (swatch in style.background.palette) {
                    InkChip(swatch.hex, style.ink == swatch.hex, swatch.name, palette) { model.shareStyle = style.copy(ink = swatch.hex) }
                }
                val custom = style.ink?.takeIf { ink -> style.background.palette.none { it.hex == ink } }
                CustomChip(custom, palette) { picking = true }
            }
            if (colors.adjusted) {
                Text(stringResource(R.string.share_ink_adjusted), color = palette.secondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.share_shadow), palette)
            Flush { Segmented(ShareShadow.entries, style.shadow, { it.title }, palette) { model.shareStyle = style.copy(shadow = it) } }
        }

        TypefaceRow(style.family, palette) { model.shareStyle = style.copy(family = it) }
        Flush { Segmented(ShareAlignment.entries, style.alignment, { it.title }, palette) { model.shareStyle = style.copy(alignment = it) } }

        Column {
            SwitchRow(stringResource(R.string.share_red_letters), style.redLetters && hasRed, palette, enabled = hasRed, inset = 0.dp) {
                model.shareStyle = style.copy(redLetters = it)
            }
            if (severalVerses) {
                SwitchRow(stringResource(R.string.share_verse_numbers), style.verseNumbers, palette, inset = 0.dp) {
                    model.shareStyle = style.copy(verseNumbers = it)
                }
            }
            SwitchRow(stringResource(R.string.share_wordmark), style.wordmark, palette, inset = 0.dp) { model.shareStyle = style.copy(wordmark = it) }
        }
    }
    if (picking) {
        ColorPickerDialog(style.ink ?: style.background.ink, palette, onDismiss = { picking = false }) {
            picking = false
            model.shareStyle = model.shareStyle.copy(ink = it)
        }
    }
}

/** A ground drawn small (off the main thread, cached), its flat colours until then. */
@Composable
private fun BackdropSwatch(background: ShareBackground, size: Dp, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val px = with(density) { size.toPx() }
    val image by produceState<ImageBitmap?>(null, background, px) {
        value = withContext(Dispatchers.Default) { ShareBackdrop.bitmap(background, 1080f, 1080f, px / 1080f).asImageBitmap() }
    }
    Box(modifier.size(size).background(backgroundBrush(background))) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
    }
}

/** The ready-made styles: each ground with "Aa" in its typeface, text colour and shadow — `styleStrip`. */
@Composable
private fun StyleStrip(style: ShareStyle, palette: ReaderPalette, onSelect: (ShareBackground) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = max(0, ShareBackground.entries.indexOf(style.background) - 1))
    val hint = stringResource(R.string.share_style_hint)
    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(ShareBackground.entries) { item ->
            val chosen = item == style.background
            val name = item.title
            Column(
                Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onSelect(item) }
                    .semantics {
                        contentDescription = name
                        selected = chosen
                        onClick(label = hint) { onSelect(item); true }
                    }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val ink = Color(ShareCardRenderer.argb(item.ink))
                val glow = ShareContrast.luminance(item.ink) <= 0.4
                val layer = item.presetShadow.layers(20f, glow).firstOrNull()
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))
                        .border(if (chosen) 3.dp else 1.dp, if (chosen) palette.accent else palette.ink.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    BackdropSwatch(item, 64.dp)
                    Text(
                        "Aa", fontSize = 20.sp, fontFamily = remember(item) { item.presetFamily.fontFamily(20f) },
                        style = TextStyle(
                            color = ink,
                            shadow = layer?.let { Shadow(Color(if (glow) 0xFFFFFFFF else 0xFF000000).copy(alpha = it.opacity), Offset(0f, it.y), it.radius * 2) },
                        ),
                    )
                }
                Text(name, color = if (chosen) palette.ink else palette.secondary, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun BackgroundRow(
    title: String,
    items: List<ShareBackground>,
    chosen: ShareBackground,
    palette: ReaderPalette,
    onSelect: (ShareBackground) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = palette.secondary, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (item in items) {
                val on = item == chosen
                val name = item.title
                Box(
                    Modifier.padding(2.dp).size(44.dp).clip(RoundedCornerShape(9.dp))
                        .border(if (on) 3.dp else 1.dp, if (on) palette.accent else palette.ink.copy(alpha = 0.12f), RoundedCornerShape(9.dp))
                        .clickable(role = Role.Button) { onSelect(item) }
                        .semantics {
                            contentDescription = name
                            selected = on
                        },
                ) { BackdropSwatch(item, 44.dp) }
            }
        }
    }
}

@Composable
private fun InkChip(hex: Long, on: Boolean, name: String, palette: ReaderPalette, automatic: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .border(if (on) 2.5.dp else 0.dp, if (on) palette.accent else Color.Transparent, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = name
                selected = on
            }
            .padding(3.dp).clip(CircleShape).background(Color(ShareCardRenderer.argb(hex)))
            .border(1.dp, palette.ink.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (automatic) {
            Icon(
                Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(16.dp),
                tint = if (ShareContrast.luminance(hex) > 0.4) Color(0xFF111111) else Color.White,
            )
        }
    }
}

/** Any colour at all: a rainbow ring, filled with the picked colour once there is one. */
@Composable
private fun CustomChip(custom: Long?, palette: ReaderPalette, onClick: () -> Unit) {
    val description = if (custom != null) {
        stringResource(R.string.share_ink_selected_custom, "#%06X".format(custom))
    } else {
        stringResource(R.string.share_ink_custom)
    }
    val rainbow = Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red))
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .border(if (custom != null) 2.5.dp else 0.dp, if (custom != null) palette.accent else Color.Transparent, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                selected = custom != null
            }
            .padding(3.dp).clip(CircleShape).background(rainbow).padding(4.dp).clip(CircleShape)
            .background(custom?.let { Color(ShareCardRenderer.argb(it)) } ?: PanelColors.background(palette)),
    )
}

/** Hue, saturation and brightness sliders with a live swatch — Android's stand-in for SwiftUI's `ColorPicker`. */
@Composable
private fun ColorPickerDialog(initial: Long, palette: ReaderPalette, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val hsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(ShareCardRenderer.argb(initial), it) }
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var brightness by remember { mutableFloatStateOf(hsv[2]) }
    val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)).toLong() and 0xFFFFFF
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelColors.card(palette),
        title = { Text(stringResource(R.string.share_ink_custom), color = palette.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(ShareCardRenderer.argb(color)))
                        .border(1.dp, palette.ink.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
                )
                LabeledSlider(stringResource(R.string.share_color_hue), hue, 0f..360f, palette) { hue = it }
                LabeledSlider(stringResource(R.string.share_color_saturation), saturation, 0f..1f, palette) { saturation = it }
                LabeledSlider(stringResource(R.string.share_color_brightness), brightness, 0f..1f, palette) { brightness = it }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text(stringResource(R.string.common_done), color = palette.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = palette.accent) } },
    )
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, palette: ReaderPalette, onChange: (Float) -> Unit) {
    Column {
        Text(label, color = palette.secondary, fontSize = 12.sp)
        Slider(
            value, onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent),
            modifier = Modifier.semantics { contentDescription = label },
        )
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
 * `preview`. Drawn by the same [ShareCardRenderer] as the exported PNG, into a Compose canvas, over
 * the ground drawn at the preview's own pixel size off the main thread.
 */
@Composable
fun Preview(
    renderer: ShareCardRenderer,
    content: ShareCardContent?,
    style: ShareStyle,
    maxHeight: Float,
    reference: String,
    colors: ShareColors = style.colors(ShareBackdropStats.approximate(style.background)),
) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val scale = min(maxWidth.value / style.aspect.width, maxHeight / style.aspect.height)
        val width = (style.aspect.width * scale).dp
        val height = (style.aspect.height * scale).dp
        val pixels = with(LocalDensity.current) { width.toPx() }
        // Rounded up to 64 px so small layout changes don't redraw the ground.
        val ppp = (ceil(pixels / 64f) * 64f) / style.aspect.width
        val backdrop by produceState<android.graphics.Bitmap?>(null, style.background, style.aspect, ppp) {
            value = withContext(Dispatchers.Default) { ShareBackdrop.bitmap(style.background, style.aspect.width, style.aspect.height, ppp) }
        }
        val description = stringResource(R.string.share_preview_description, reference, style.background.title)
        Box(
            Modifier.size(width, height)
                .shadow(14.dp, RoundedCornerShape(14.dp), ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
                .clip(RoundedCornerShape(14.dp))
                .background(backgroundBrush(style.background))
                .semantics { contentDescription = description },
        ) {
            if (content != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val factor = size.width / style.aspect.width
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        native.save()
                        native.scale(factor, factor)
                        renderer.draw(native, content, style, colors, backdrop = backdrop)
                        native.restore()
                    }
                }
            }
        }
    }
}

/** A ground's flat colours as a Compose brush — the swatches and the preview before the ground is drawn. */
fun backgroundBrush(background: ShareBackground): Brush {
    val colors = background.colors.map { Color(ShareCardRenderer.argb(it)) }
    return if (colors.size == 1) Brush.verticalGradient(listOf(colors[0], colors[0])) else Brush.verticalGradient(colors)
}

/** "Typeface" and a menu of the seven faces, each in itself — the designer's typeface `Picker`. */
@Composable
private fun TypefaceRow(family: ReaderFontFamily, palette: ReaderPalette, onSelect: (ReaderFontFamily) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.share_typeface), color = palette.ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Box {
            val description = stringResource(R.string.share_typeface_description, family.title)
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { open = true }.padding(horizontal = 8.dp, vertical = 6.dp)
                    .semantics { contentDescription = description },
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
                                Text(stringResource(R.string.share_font_for, option.iosTitle), color = palette.secondary, fontSize = 11.sp)
                            }
                        },
                        trailingIcon = {
                            if (option == family) Icon(Icons.Rounded.Check, stringResource(R.string.share_selected), tint = palette.accent)
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
