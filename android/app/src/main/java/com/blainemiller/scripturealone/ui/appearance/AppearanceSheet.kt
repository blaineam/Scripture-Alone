package com.blainemiller.scripturealone.ui.appearance

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.ui.notes.PanelColors
import com.blainemiller.scripturealone.ui.notes.PanelGroup
import com.blainemiller.scripturealone.ui.notes.PanelHeader
import com.blainemiller.scripturealone.ui.notes.PanelSeparator
import com.blainemiller.scripturealone.ui.notes.Segmented
import com.blainemiller.scripturealone.ui.reader.ReaderAccent
import com.blainemiller.scripturealone.ui.reader.ReaderFontFamily
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderStyle
import com.blainemiller.scripturealone.ui.reader.ReaderTheme
import com.blainemiller.scripturealone.ui.reader.ReaderTypography
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import com.blainemiller.scripturealone.ui.reader.ReadingLayout
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Reading preferences — `ScriptureAlone/Reader/AppearanceView.swift` — with the chapter live behind
 * the sheet as a preview: theme, accent, text size and line spacing, layout, the seven faces and the
 * Show toggles, then Feedback & Support, About (with the font licences) and About This Translation.
 * Every setting is the reader's own persisted property, stored under the iOS key.
 *
 * Like the iOS sheet it opens at half height, leaving the text to watch change, and pulls up to full
 * height; a tap on the reader above it, a drag down or Back closes it.
 *
 * The text size is in points, drawn as sp, so the system font size scales it as Dynamic Type scales
 * the iOS size (`@ScaledMetric`); the Text section says so when that scale isn't 1.
 */
@Composable
fun AppearanceSheet(model: ReaderViewModel, palette: ReaderPalette, visible: Boolean, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var licences by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val full = constraints.maxHeight.toFloat()
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val largeTop = with(density) { (statusTop + 10.dp).toPx() }
        val mediumTop = full * 0.47f
        val top = remember { Animatable(full) }
        var expanded by remember { mutableStateOf(false) }

        LaunchedEffect(visible) {
            if (visible) {
                expanded = false
                top.animateTo(mediumTop, tween(320))
            } else {
                licences = false
                top.animateTo(full, tween(240))
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
                if (target == full) onDismiss()
            }
        }
        BackHandler(visible) { if (licences) licences = false else onDismiss() }

        if (top.value < full) {
            // Above the sheet the reader stays visible; a tap there closes it, as on iOS.
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = if (expanded) 0.22f else 0f))
                    .clickable(interactionSource = null, indication = null, onClick = onDismiss),
            )
            val dragState = rememberDraggableState { delta -> scope.launch { top.snapTo((top.value + delta).coerceIn(largeTop, full)) } }
            val surface = PanelColors.background(palette)
            Column(
                Modifier
                    .offset { IntOffset(0, top.value.roundToInt()) }
                    .fillMaxWidth()
                    .height(with(density) { (full - largeTop).toDp() })
                    .shadow(18.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.22f))
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(surface)
                    .clickable(interactionSource = null, indication = null) {},
            ) {
                Box(
                    Modifier.fillMaxWidth().height(22.dp).draggable(dragState, Orientation.Vertical, onDragStopped = { settle(it) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(width = 36.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(palette.secondary.copy(alpha = 0.4f)))
                }
                val visibleHeight = with(density) { (full - top.value).coerceAtLeast(0f).toDp() } - 22.dp
                Box(Modifier.fillMaxWidth().height(visibleHeight.coerceAtLeast(0.dp))) {
                    if (licences) {
                        FontLicences(palette, onBack = { licences = false })
                    } else {
                        AppearanceForm(model, palette, onLicences = {
                            licences = true
                            scope.launch {
                                expanded = true
                                top.animateTo(largeTop, tween(260))
                            }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceForm(model: ReaderViewModel, palette: ReaderPalette, onLicences: () -> Unit) {
    val context = LocalContext.current
    val fontScale = LocalDensity.current.fontScale
    // Each swatch shows its theme as it would look now — Auto follows the device, as `palette(for: colorScheme)`.
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = nav + 24.dp)) {
        // Themes.
        PanelGroup(palette, Modifier.padding(top = 6.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (option in ReaderTheme.entries) {
                    ThemeSwatch(option, option == model.theme, option.palette(systemDark), palette, Modifier.weight(1f)) {
                        model.theme = option
                    }
                }
            }
        }

        // Accent.
        SectionTitle("Accent", palette)
        PanelGroup(palette) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                for (option in ReaderAccent.entries) AccentSwatch(option, option == model.accent, palette) { model.accent = option }
            }
        }
        SectionFooter("Colours verse numbers, links and the app’s controls.", palette)

        // Text.
        SectionTitle("Text", palette)
        PanelGroup(palette) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val range = ReaderStyle.SIZE_RANGE
                GlyphButton("A", 15.sp, "Smaller", palette) { model.fontSize = (model.fontSize - 1f).coerceIn(range) }
                SheetSlider(
                    model.fontSize, range, palette, label = "Text Size", valueText = "${model.fontSize.roundToInt()} points",
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp), step = 1f,
                ) { model.fontSize = it }
                GlyphButton("A", 24.sp, "Larger", palette) { model.fontSize = (model.fontSize + 1f).coerceIn(range) }
            }
            PanelSeparator(palette)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                LinesGlyph(tight = true, palette)
                SheetSlider(
                    model.lineSpacing, ReaderStyle.LINE_SPACING_RANGE, palette, label = "Line Spacing",
                    valueText = String.format(Locale.US, "%.2f", model.lineSpacing),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                ) { model.lineSpacing = Math.round(it * 100f) / 100f }
                LinesGlyph(tight = false, palette)
            }
            PanelSeparator(palette)
            Box(Modifier.padding(vertical = 10.dp)) {
                Segmented(ReadingLayout.entries, model.layout, { it.title }, palette) { model.layout = it }
            }
        }
        if (fontScale != 1f) {
            SectionFooter(
                "${model.fontSize.roundToInt()} pt, then scaled by the system font size (×${String.format(Locale.US, "%.2f", fontScale).trimEnd('0').trimEnd('.')}), as Dynamic Type scales it on iPhone.",
                palette,
            )
        }

        // Font.
        SectionTitle("Font", palette)
        PanelGroup(palette) {
            ReaderFontFamily.entries.forEachIndexed { index, option ->
                if (index > 0) PanelSeparator(palette)
                FontRow(option, option == model.fontFamily, palette) { model.fontFamily = option }
            }
        }
        SectionFooter("Open-licensed faces standing in for the iPhone’s, which Apple licenses for its own devices only.", palette)

        // Show.
        SectionTitle("Show", palette)
        PanelGroup(palette) {
            SwitchRow("Words of Christ in Red", model.redLetters, palette) { model.redLetters = it }
            PanelSeparator(palette)
            SwitchRow("Verse Numbers", model.verseNumbers, palette) { model.verseNumbers = it }
            PanelSeparator(palette)
            SwitchRow("Section Headings", model.headings, palette) { model.headings = it }
            PanelSeparator(palette)
            SwitchRow("Footnotes", model.footnotes, palette) { model.footnotes = it }
        }

        // Feedback & Support — MillerKit's SupportSection.
        SectionTitle("Feedback & Support", palette)
        PanelGroup(palette) {
            val translation = model.translationId
            LinkRow("Report an Issue", palette, mail = true) { Support.email(context, "Bug Report", translation) }
            PanelSeparator(palette)
            LinkRow("Suggest a Feature", palette, mail = true) { Support.email(context, "Feature Request", translation) }
            PanelSeparator(palette)
            LinkRow("Ask a Question", palette, mail = true) { Support.email(context, "Question", translation) }
        }
        SectionFooter(
            "I can’t fix what I don’t know about. If something is broken, confusing, or missing, email me — one person reads every message, and a fix for you is a fix for everyone.",
            palette,
        )
        PanelGroup(palette, Modifier.padding(top = 18.dp)) {
            LinkRow("My Other Apps", palette, subtitle = "Built by one person, same care") { Support.open(context, Support.PORTFOLIO) }
        }

        // About — MillerKit's AboutSection, with the font licences.
        SectionTitle("About", palette)
        PanelGroup(palette) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Version", color = palette.ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Text(Support.version(context), color = palette.secondary, fontSize = 17.sp)
            }
            PanelSeparator(palette)
            LinkRow("Privacy Policy", palette) { Support.open(context, Support.PRIVACY) }
            PanelSeparator(palette)
            LinkRow("App Website", palette) { Support.open(context, Support.PAGE) }
            PanelSeparator(palette)
            LinkRow("Font Licences", palette, push = true, onClick = onLicences)
        }
        SectionFooter("No accounts, no tracking, no ads — nothing you do in Scripture Alone is sent anywhere unless you send it yourself.", palette)

        // About This Translation.
        model.chapter?.translation?.let { info ->
            SectionTitle("About This Translation", palette)
            PanelGroup(palette) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(info.name, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(info.copyright.ifBlank { "Public domain." }, color = palette.secondary, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
internal fun SectionTitle(title: String, palette: ReaderPalette) {
    Text(
        title, color = palette.secondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 32.dp, top = 22.dp, bottom = 8.dp),
    )
}

@Composable
internal fun SectionFooter(text: String, palette: ReaderPalette) {
    Text(
        text, color = palette.secondary, fontSize = 13.sp, lineHeight = 17.sp,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
    )
}

/** A theme's page and ink, "Aa" in the serif, the chosen one ringed in the accent — `themeSwatch`. */
@Composable
private fun ThemeSwatch(
    option: ReaderTheme, chosen: Boolean, swatch: ReaderPalette, palette: ReaderPalette, modifier: Modifier, onClick: () -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "${option.title} theme"
                selected = chosen
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(swatch.page)
                .border(
                    if (chosen) 2.5.dp else 1.dp,
                    if (chosen) palette.accent else palette.ink.copy(alpha = 0.15f),
                    RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("Aa", color = swatch.ink, fontSize = 17.sp, fontFamily = ReaderTypography.sourceSerif(17f))
        }
        Text(option.title, color = palette.ink, fontSize = 12.sp)
    }
}

/** A 30-dp swatch at its light value, a check on the chosen one — `accentSwatch`. */
@Composable
private fun AccentSwatch(option: ReaderAccent, chosen: Boolean, palette: ReaderPalette, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "${option.title} accent"
                selected = chosen
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(option.swatch)
                .border(if (chosen) 2.5.dp else 1.dp, palette.ink.copy(alpha = if (chosen) 0.55f else 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (chosen) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

/** The face's own name, set in it at 18 sp, with the iPhone face it stands in for beneath. */
@Composable
private fun FontRow(option: ReaderFontFamily, chosen: Boolean, palette: ReaderPalette, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
            .semantics { selected = chosen }
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(option.title, color = palette.ink, fontSize = 18.sp, fontFamily = option.fontFamily(18f))
            Text("For ${option.iosTitle}", color = palette.secondary, fontSize = 12.sp)
        }
        if (chosen) Icon(Icons.Rounded.Check, "Selected", tint = palette.accent, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun GlyphButton(glyph: String, size: androidx.compose.ui.unit.TextUnit, label: String, palette: ReaderPalette, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = palette.accent, fontSize = size, fontFamily = FontFamily.SansSerif)
    }
}

/** `line.3.horizontal`, drawn close or open: three rules for tighter and looser lines. */
@Composable
private fun LinesGlyph(tight: Boolean, palette: ReaderPalette) {
    val gap = if (tight) 4.dp else 6.dp
    Column(Modifier.width(22.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
        repeat(3) { Box(Modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(palette.secondary)) }
    }
}

@Composable
private fun LinkRow(
    title: String,
    palette: ReaderPalette,
    subtitle: String? = null,
    mail: Boolean = false,
    push: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.ink, fontSize = 17.sp)
            if (subtitle != null) Text(subtitle, color = palette.secondary, fontSize = 12.sp)
        }
        val icon = when {
            push -> Icons.Rounded.ChevronRight
            mail -> Icons.Outlined.Email
            else -> Icons.AutoMirrored.Rounded.OpenInNew
        }
        Icon(icon, null, tint = palette.secondary, modifier = Modifier.size(if (push) 22.dp else 16.dp))
    }
}

/** The suite's shared links and the feedback email — MillerKit's `SuiteApp` values for Scripture Alone. */
internal object Support {
    const val EMAIL = "apps@wemiller.com"
    const val PRIVACY = "https://wemiller.com/privacy/"
    const val PAGE = "https://wemiller.com/apps/scripture-alone/"
    const val PORTFOLIO = "https://wemiller.com/apps/"

    fun version(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("—")

    fun open(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Unit
        }
    }

    /** A feedback email, subject "Scripture Alone — [kind]", with the diagnostics iOS attaches. */
    fun email(context: Context, kind: String, translation: String) {
        val body = buildString {
            append("\n\n")
            append("Translation: $translation\n\n")
            append("——————————————\n")
            append("These details help me diagnose it — please leave them in:\n")
            append("App: ${version(context)}\n")
            append("System: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Language: ${Locale.getDefault().toLanguageTag()}\n")
            append("That’s everything attached — no identifiers, no location, no logs. Delete any line you’d rather not send.")
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "Scripture Alone — $kind")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Unit
        }
    }
}

/** The header shared by the sheet's pushed pages. */
@Composable
internal fun PushedHeader(title: String, palette: ReaderPalette, onBack: () -> Unit) {
    PanelHeader(title, palette, back = true, onLeading = onBack)
}
