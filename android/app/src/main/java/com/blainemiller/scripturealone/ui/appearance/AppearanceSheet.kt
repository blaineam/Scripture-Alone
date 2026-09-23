package com.blainemiller.scripturealone.ui.appearance

import com.blainemiller.scripturealone.ui.reader.CappedFontScale
import com.blainemiller.scripturealone.ui.reader.takesTaps
import androidx.compose.ui.semantics.heading
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
import androidx.compose.ui.res.stringResource
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
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.text.countedString
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
fun AppearanceSheet(
    model: ReaderViewModel,
    palette: ReaderPalette,
    visible: Boolean,
    onDismiss: () -> Unit,
    /** Keepsake & Export — `LegacyAndExportRow`, which opens its own sheet. */
    onKeepsake: () -> Unit = {},
) {
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
                    .takesTaps(onDismiss),
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
                    .takesTaps(),
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
                        AppearanceForm(model, palette, onKeepsake = onKeepsake, onLicences = {
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
private fun AppearanceForm(model: ReaderViewModel, palette: ReaderPalette, onKeepsake: () -> Unit, onLicences: () -> Unit) {
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
        SectionTitle(stringResource(R.string.appearance_accent), palette)
        PanelGroup(palette) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                for (option in ReaderAccent.entries) AccentSwatch(option, option == model.accent, palette) { model.accent = option }
            }
        }
        SectionFooter(stringResource(R.string.appearance_accent_footer), palette)

        // Text.
        SectionTitle(stringResource(R.string.appearance_text), palette)
        PanelGroup(palette) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val range = ReaderStyle.SIZE_RANGE
                GlyphButton("A", 15.sp, stringResource(R.string.appearance_smaller), palette) { model.fontSize = (model.fontSize - 1f).coerceIn(range) }
                val points = model.fontSize.roundToInt()
                SheetSlider(
                    model.fontSize, range, palette, label = stringResource(R.string.appearance_text_size),
                    valueText = countedString(R.string.appearance_points_one, R.string.appearance_points_other, points, points),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp), step = 1f,
                ) { model.fontSize = it }
                GlyphButton("A", 24.sp, stringResource(R.string.appearance_larger), palette) { model.fontSize = (model.fontSize + 1f).coerceIn(range) }
            }
            PanelSeparator(palette)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                LinesGlyph(tight = true, palette)
                SheetSlider(
                    model.lineSpacing, ReaderStyle.LINE_SPACING_RANGE, palette, label = stringResource(R.string.appearance_line_spacing),
                    valueText = String.format(Locale.getDefault(), "%.2f", model.lineSpacing),
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
            val scale = java.text.NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = 2 }.format(fontScale)
            SectionFooter(stringResource(R.string.appearance_font_scale_footer, model.fontSize.roundToInt(), scale), palette)
        }

        // Font.
        SectionTitle(stringResource(R.string.appearance_font), palette)
        PanelGroup(palette) {
            ReaderFontFamily.entries.forEachIndexed { index, option ->
                if (index > 0) PanelSeparator(palette)
                FontRow(option, option == model.fontFamily, palette) { model.fontFamily = option }
            }
        }
        SectionFooter(stringResource(R.string.appearance_font_footer), palette)

        // Show.
        SectionTitle(stringResource(R.string.appearance_show), palette)
        PanelGroup(palette) {
            SwitchRow(stringResource(R.string.appearance_red_letters), model.redLetters, palette) { model.redLetters = it }
            PanelSeparator(palette)
            SwitchRow(stringResource(R.string.appearance_verse_numbers), model.verseNumbers, palette) { model.verseNumbers = it }
            PanelSeparator(palette)
            SwitchRow(stringResource(R.string.appearance_section_headings), model.headings, palette) { model.headings = it }
            PanelSeparator(palette)
            SwitchRow(stringResource(R.string.appearance_footnotes), model.footnotes, palette) { model.footnotes = it }
        }

        // Search — iOS's Spotlight section: notes and favorites in the device's search, off by default.
        // Only where there is a system index to put them in (Android 12+).
        if (model.systemSearch.isAvailable) {
            SectionTitle(stringResource(R.string.appearance_search), palette)
            PanelGroup(palette) {
                SwitchRow(stringResource(R.string.appearance_notes_in_search), model.notesInSearch, palette) { model.notesInSearch = it }
                PanelSeparator(palette)
                SwitchRow(stringResource(R.string.appearance_favorites_in_search), model.favoritesInSearch, palette) { model.favoritesInSearch = it }
            }
            SectionFooter(stringResource(R.string.appearance_search_footer), palette)
        }

        // Keepsake & Export — `Section { LegacyAndExportRow() }`.
        PanelGroup(palette, Modifier.padding(top = 18.dp)) {
            LinkRow(stringResource(R.string.keepsake_settings_title), palette, push = true, onClick = onKeepsake)
        }

        // Feedback & Support — MillerKit's SupportSection.
        SectionTitle(stringResource(R.string.appearance_feedback), palette)
        PanelGroup(palette) {
            val translation = model.translationId
            LinkRow(stringResource(R.string.appearance_report_issue), palette, mail = true) {
                Support.email(context, context.getString(R.string.appearance_email_bug), translation)
            }
            PanelSeparator(palette)
            LinkRow(stringResource(R.string.appearance_suggest_feature), palette, mail = true) {
                Support.email(context, context.getString(R.string.appearance_email_feature), translation)
            }
            PanelSeparator(palette)
            LinkRow(stringResource(R.string.appearance_ask_question), palette, mail = true) {
                Support.email(context, context.getString(R.string.appearance_email_question), translation)
            }
        }
        SectionFooter(
            stringResource(R.string.appearance_feedback_footer),
            palette,
        )
        // MillerKit's LoveThisAppSection: Rate opens the store's review page, as iOS's row opens the App
        // Store's write-review page — somewhere the reader can actually write, which Play's in-app card
        // (quota-limited, maybe shown, maybe not) can't promise. Only in a copy Play installed: until
        // there is a listing, the link would lead nowhere.
        val fromPlay = remember { RatingPrompt.installedFromPlay(context) }
        PanelGroup(palette, Modifier.padding(top = 18.dp)) {
            if (fromPlay) {
                LinkRow(stringResource(R.string.appearance_rate), palette) { Support.rate(context) }
                PanelSeparator(palette)
            }
            LinkRow(stringResource(R.string.appearance_other_apps), palette, subtitle = stringResource(R.string.appearance_other_apps_subtitle)) { Support.open(context, Support.PORTFOLIO) }
        }
        if (fromPlay) {
            SectionFooter(
                stringResource(R.string.appearance_rate_footer),
                palette,
            )
        }

        // About — MillerKit's AboutSection, with the font licences.
        SectionTitle(stringResource(R.string.appearance_about), palette)
        PanelGroup(palette) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.appearance_version), color = palette.ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Text(Support.version(context), color = palette.secondary, fontSize = 17.sp)
            }
            PanelSeparator(palette)
            LinkRow(stringResource(R.string.appearance_privacy_policy), palette) { Support.open(context, Support.PRIVACY) }
            PanelSeparator(palette)
            LinkRow(stringResource(R.string.appearance_app_website), palette) { Support.open(context, Support.PAGE) }
            PanelSeparator(palette)
            LinkRow(stringResource(R.string.appearance_font_licences), palette, push = true, onClick = onLicences)
        }
        SectionFooter(stringResource(R.string.appearance_about_footer), palette)

        // About This Translation.
        model.chapter?.translation?.let { info ->
            SectionTitle(stringResource(R.string.appearance_about_translation), palette)
            PanelGroup(palette) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(info.name, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(info.copyright.ifBlank { stringResource(R.string.appearance_public_domain) }, color = palette.secondary, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
internal fun SectionTitle(title: String, palette: ReaderPalette) {
    Text(
        title, color = palette.secondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 32.dp, top = 22.dp, bottom = 8.dp).semantics { heading() },
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
) = CappedFontScale {
    val description = stringResource(R.string.appearance_theme_description, option.title)
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
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
        Text(option.title, color = palette.ink, fontSize = 12.sp, maxLines = 1, softWrap = false)
    }
}

/** A 30-dp swatch at its light value, a check on the chosen one — `accentSwatch`. */
@Composable
private fun AccentSwatch(option: ReaderAccent, chosen: Boolean, palette: ReaderPalette, onClick: () -> Unit) {
    val description = stringResource(R.string.appearance_accent_description, option.title)
    Box(
        Modifier.size(38.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
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
            Text(stringResource(R.string.share_font_for, option.iosTitle), color = palette.secondary, fontSize = 12.sp)
        }
        if (chosen) Icon(Icons.Rounded.Check, stringResource(R.string.share_selected), tint = palette.accent, modifier = Modifier.size(22.dp))
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

    /** The Play listing's page, in the Play Store app where there is one — iOS's `writeReviewURL`. */
    fun rate(context: Context) {
        val id = context.packageName
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")).setPackage("com.android.vending"))
        } catch (_: ActivityNotFoundException) {
            open(context, "https://play.google.com/store/apps/details?id=$id")
        }
    }

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
            append("${context.getString(R.string.appearance_email_translation)}: $translation\n\n")
            append("——————————————\n")
            append(context.getString(R.string.appearance_email_details)).append("\n")
            append("${context.getString(R.string.appearance_email_app)}: ${version(context)}\n")
            append("${context.getString(R.string.appearance_email_system)}: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("${context.getString(R.string.appearance_email_device)}: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("${context.getString(R.string.appearance_email_language)}: ${Locale.getDefault().toLanguageTag()}\n")
            append(context.getString(R.string.appearance_email_footer))
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.appearance_email_subject, kind))
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
