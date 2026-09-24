package com.blainemiller.scripturealone.ui.translations

import androidx.compose.ui.semantics.Role
import com.blainemiller.scripturealone.ui.reader.takesTaps
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.BundledTranslations
import com.blainemiller.scripturealone.data.assets.AssetPack
import com.blainemiller.scripturealone.data.TranslationInfo
import com.blainemiller.scripturealone.data.catalog.CatalogCuration
import com.blainemiller.scripturealone.data.catalog.CatalogDownloader
import com.blainemiller.scripturealone.data.catalog.CatalogLanguageMatch
import com.blainemiller.scripturealone.data.catalog.CatalogTranslation
import com.blainemiller.scripturealone.data.catalog.EBibleCatalog
import com.blainemiller.scripturealone.data.catalog.importIdentity
import com.blainemiller.scripturealone.data.importer.BibleFileImporter
import com.blainemiller.scripturealone.data.importer.BibleImportResult
import com.blainemiller.scripturealone.data.importer.BundledStoreWriter
import com.blainemiller.scripturealone.data.importer.ImportCoverageReport
import com.blainemiller.scripturealone.data.importer.ImportedTranslationIdentity
import com.blainemiller.scripturealone.data.online.APIBibleClient
import com.blainemiller.scripturealone.data.online.APIBibleTranslation
import com.blainemiller.scripturealone.data.online.OnlineEntry
import com.blainemiller.scripturealone.data.online.OnlineProvider
import com.blainemiller.scripturealone.data.rights.TranslationRights
import com.blainemiller.scripturealone.data.translations.ImportedTranslation
import com.blainemiller.scripturealone.data.translations.RedLetterReference
import com.blainemiller.scripturealone.data.translations.TranslationLibrary
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import com.blainemiller.scripturealone.ui.reader.SheetColors
import com.blainemiller.scripturealone.ui.reader.glass
import com.blainemiller.scripturealone.ui.study.Cell
import com.blainemiller.scripturealone.ui.study.CellDivider
import com.blainemiller.scripturealone.ui.study.ContentUnavailable
import com.blainemiller.scripturealone.ui.study.GlassBackButton
import com.blainemiller.scripturealone.ui.study.GlassTextButton
import com.blainemiller.scripturealone.ui.study.GroupedSection
import com.blainemiller.scripturealone.ui.study.LabeledCell
import com.blainemiller.scripturealone.ui.study.ProminentButton
import com.blainemiller.scripturealone.ui.study.SheetTopBar
import com.blainemiller.scripturealone.ui.study.StudyStyle
import com.blainemiller.scripturealone.ui.study.loaded
import com.blainemiller.scripturealone.text.AppText
import com.blainemiller.scripturealone.text.countedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat

private enum class Page { MAIN, CATALOG, KEYS }

/**
 * Every translation on the device, and the ways to add one — `TranslationsView.swift`: the three
 * that ship; the online ones the reader's keys unlock; the ones they added; then Browse Free
 * Translations (eBible.org), Import a File (a USFM zip or a DRM-free ePub, through the system file
 * picker) and Online Translations (their own ESV / API.Bible key). About This Translation — which
 * iOS shows in its Appearance sheet — closes the list, with what the translation's terms allow.
 */
@Composable
fun TranslationsSheet(reader: ReaderViewModel, palette: ReaderPalette, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(Page.MAIN) }
    var importing by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Double?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf<BibleImportResult?>(null) }
    var pendingRemoval by remember { mutableStateOf<ImportedTranslation?>(null) }
    val surface = SheetColors.surface(palette)

    /** Imports [file] off the main thread, then shows what it got. */
    fun runImport(file: File, name: String, identity: ImportedTranslationIdentity?, cleanUp: Boolean) {
        importing = name
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // A file that marks no words of Christ can take them from a translation that
                    // does, when one is on the device (only verses that align closely are marked).
                    BibleFileImporter(BundledStoreWriter.opener)
                        .importBible(file, identity, TranslationLibrary.importedDirectory(context), RedLetterReference.lookup(context))
                }.also {
                    if (cleanUp) file.delete()
                    TranslationLibrary.reloadImported()
                }
            }
            importing = null
            progress = null
            result.onSuccess {
                finished = it
                page = Page.MAIN
            }.onFailure { failure = it.message ?: it.javaClass.simpleName }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = displayName(context, uri) ?: AppText.get(R.string.translations_import_unnamed_file)
        importing = name
        scope.launch {
            // The picker hands over a content URI; the importer reads a file, so it is copied into
            // the app's cache first and deleted after.
            val copy = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(context.cacheDir, "import-${System.nanoTime()}")
                    context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                    file
                }
            }
            copy.onSuccess { runImport(it, name, null, cleanUp = true) }
                .onFailure { importing = null; failure = AppText.get(R.string.translations_import_read_failed, it.message.orEmpty()) }
        }
    }

    BackHandler {
        when {
            finished != null -> finished = null
            page != Page.MAIN -> page = Page.MAIN
            else -> onClose()
        }
    }

    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(surface)
            .takesTaps(),
    ) {
        when {
            finished != null -> ImportSummary(finished!!, palette) { finished = null }
            page == Page.CATALOG -> CatalogPage(palette, onBack = { page = Page.MAIN }) { translation ->
                importing = translation.title
                progress = null
                scope.launch {
                    val file = withContext(Dispatchers.IO) {
                        runCatching {
                            CatalogDownloader.download(translation, File(context.cacheDir, "catalog")) { fraction ->
                                scope.launch { progress = fraction }
                            }
                        }
                    }
                    file.onSuccess { runImport(it, translation.title, translation.importIdentity, cleanUp = true) }
                        .onFailure { importing = null; failure = it.message ?: AppText.get(R.string.translations_download_failed) }
                }
            }
            page == Page.KEYS -> OnlineKeysPage(reader, palette) { page = Page.MAIN }
            else -> MainPage(
                reader, palette, onClose,
                onCatalog = { page = Page.CATALOG },
                onImport = { picker.launch(arrayOf("application/zip", "application/epub+zip", "application/x-zip-compressed", "application/octet-stream")) },
                onKeys = { page = Page.KEYS },
                onRemove = { pendingRemoval = it },
            )
        }

        importing?.let { name -> ProgressOverlay(name, progress, palette) }
        failure?.let { message ->
            Alert(stringResource(R.string.translations_add_failed_title), message, palette, confirm = stringResource(R.string.common_ok), onConfirm = { failure = null })
        }
        pendingRemoval?.let { entry ->
            Alert(
                stringResource(R.string.translations_remove_title), stringResource(R.string.translations_remove_message), palette,
                confirm = stringResource(R.string.common_remove), destructive = true, onCancel = { pendingRemoval = null },
                onConfirm = {
                    // Never leave the reader pointing at a store that no longer exists.
                    if (reader.translationId == entry.id) reader.selectTranslation(BundledTranslations.DEFAULT)
                    TranslationLibrary.remove(entry)
                    pendingRemoval = null
                },
            )
        }
    }
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

@Composable
private fun MainPage(
    reader: ReaderViewModel,
    palette: ReaderPalette,
    onClose: () -> Unit,
    onCatalog: () -> Unit,
    onImport: () -> Unit,
    onKeys: () -> Unit,
    onRemove: (ImportedTranslation) -> Unit,
) {
    val surface = SheetColors.surface(palette)
    val library by TranslationLibrary.state.collectAsState()
    val bundled = loaded(Unit) { context ->
        // A pack not downloaded yet can't be opened for its name: it is listed under its pack's title,
        // and choosing it fetches it (`ReaderViewModel.selectTranslation`).
        BundledTranslations.bundled.mapNotNull { id ->
            runCatching { BundledTranslations.source(context, id).info }.getOrNull()
                ?: AssetPack.forTranslation(id)?.let { TranslationInfo(id, it.title, id, "") }
        }
    }
    val current = reader.chapter?.translation
    Column(Modifier.fillMaxSize()) {
        SheetTopBar(
            stringResource(R.string.translations_title), palette,
            trailing = { GlassTextButton(stringResource(R.string.common_done), palette, surface, bold = true, tint = palette.accent, onClick = onClose) },
        )
        Column(
            Modifier.fillMaxSize().background(StudyStyle.groupedBackground(palette)).verticalScroll(rememberScrollState()),
        ) {
            GroupedSection(palette, header = stringResource(R.string.translations_section_included)) {
                val infos = bundled ?: BundledTranslations.bundled.map { TranslationInfo(it, it, it, "") }
                infos.forEachIndexed { i, info ->
                    if (i > 0) CellDivider(palette)
                    TranslationRow(info.name, info.id, null, palette, info.id == reader.translationId) { reader.selectTranslation(info.id) }
                }
            }
            if (library.online.isNotEmpty()) {
                GroupedSection(
                    palette, header = stringResource(R.string.translations_section_online),
                    footer = stringResource(R.string.translations_section_online_footer),
                ) {
                    library.online.forEachIndexed { i, entry ->
                        if (i > 0) CellDivider(palette)
                        TranslationRow(entry.name, entry.id, stringResource(R.string.translations_online_note), palette, entry.id == reader.translationId) {
                            reader.selectTranslation(entry.id)
                        }
                    }
                }
            }
            if (library.imported.isNotEmpty()) {
                GroupedSection(palette, header = stringResource(R.string.translations_section_added)) {
                    library.imported.forEachIndexed { i, entry ->
                        if (i > 0) CellDivider(palette)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                TranslationRow(
                                    entry.info.name, entry.info.abbreviation, entry.info.copyright, palette, entry.id == reader.translationId,
                                    onLongClick = { onRemove(entry) },
                                ) {
                                    reader.selectTranslation(entry.id)
                                }
                            }
                            val removeLabel = stringResource(R.string.translations_remove_named, entry.info.name)
                            Box(
                                Modifier.padding(end = 8.dp).size(40.dp).clip(CircleShape).clickable(role = Role.Button) { onRemove(entry) }
                                    .semantics { contentDescription = removeLabel },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Outlined.Delete, null, tint = palette.red, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            GroupedSection(
                palette, header = stringResource(R.string.translations_section_add),
                footer = stringResource(R.string.translations_section_add_footer),
            ) {
                ActionRow(Icons.Outlined.Language, stringResource(R.string.translations_browse_free), palette, onCatalog)
                CellDivider(palette, 52.dp)
                ActionRow(Icons.Outlined.Folder, stringResource(R.string.translations_import_file), palette, onImport)
                CellDivider(palette, 52.dp)
                ActionRow(Icons.Outlined.Key, stringResource(R.string.translations_online_keys), palette, onKeys)
            }
            current?.let { info -> AboutTranslation(info, palette) }
            Spacer(Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 32.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranslationRow(
    name: String,
    abbreviation: String,
    note: String?,
    palette: ReaderPalette,
    selected: Boolean,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        // A long press offers what the row's own button does (removing an import), as iOS's context
        // menu does beside its swipe.
        Modifier.fillMaxWidth().combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = palette.ink, fontSize = StudyStyle.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Text(abbreviation, color = palette.secondary, fontSize = StudyStyle.caption, fontFamily = FontFamily.Monospace)
            }
            if (!note.isNullOrEmpty()) {
                Text(note, color = palette.secondary, fontSize = StudyStyle.caption2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.width(30.dp), contentAlignment = Alignment.CenterEnd) {
            if (selected) Icon(Icons.Rounded.Check, stringResource(R.string.translations_reading), tint = palette.accent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, palette: ReaderPalette, onClick: () -> Unit) {
    Cell(palette, onClick = onClick) {
        Icon(icon, null, tint = palette.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, color = palette.accent, fontSize = StudyStyle.body)
    }
}

/** The translation being read: its name, its notice, and — plainly — what its terms allow. */
@Composable
private fun AboutTranslation(info: TranslationInfo, palette: ReaderPalette) {
    val rights = info.rights
    val sharing = when {
        rights.hasExpired() -> stringResource(R.string.translations_about_expired)
        rights.maxQuotationVerses == TranslationRights.UNLIMITED_QUOTATION -> stringResource(R.string.translations_about_free)
        else -> {
            val limit = countedString(
                R.string.translations_about_limit_one, R.string.translations_about_limit_other,
                rights.maxQuotationVerses.toInt(), rights.maxQuotationVerses,
            )
            if (!rights.allowExternalHandoff) stringResource(R.string.translations_about_limit_no_handoff, limit) else limit
        }
    }
    GroupedSection(palette, header = stringResource(R.string.translations_about_title), footer = sharing) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(info.name, color = palette.ink, fontSize = StudyStyle.headline, fontWeight = FontWeight.SemiBold)
            if (info.copyright.isNotEmpty()) Text(info.copyright, color = palette.secondary, fontSize = StudyStyle.footnote)
        }
    }
}

// ---- eBible.org ------------------------------------------------------------------------------------

private sealed interface CatalogState {
    data object Loading : CatalogState
    class Loaded(val translations: List<CatalogTranslation>) : CatalogState
    class Failed(val message: String) : CatalogState
}

/**
 * The free translations eBible.org publishes — `CatalogView.swift`. English only, from the curated
 * allowlist the app stands behind; nothing is fetched until the reader opens this, and nothing is
 * downloaded until they pick one.
 */
@Composable
private fun CatalogPage(palette: ReaderPalette, onBack: () -> Unit, onPick: (CatalogTranslation) -> Unit) {
    val surface = SheetColors.surface(palette)
    var attempt by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<CatalogState>(CatalogState.Loading) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(attempt) {
        state = CatalogState.Loading
        state = withContext(Dispatchers.IO) {
            runCatching { EBibleCatalog.fetch() }.fold(
                { all -> CatalogState.Loaded(CatalogLanguageMatch.ordered(all.filter(CatalogCuration::isCurated))) },
                { CatalogState.Failed(it.message ?: AppText.get(R.string.translations_catalog_unreadable)) },
            )
        }
    }
    Column(Modifier.fillMaxSize()) {
        SheetTopBar(stringResource(R.string.translations_catalog_title), palette, leading = { GlassBackButton(palette, surface, onBack) })
        when (val s = state) {
            CatalogState.Loading -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.translations_catalog_loading), color = palette.secondary, fontSize = StudyStyle.subheadline)
            }
            is CatalogState.Failed -> ContentUnavailable(Icons.Outlined.WifiOff, stringResource(R.string.translations_catalog_failed_title), s.message, palette, Modifier.padding(top = 40.dp)) {
                ProminentButton(stringResource(R.string.common_try_again), palette) { attempt++ }
            }
            is CatalogState.Loaded -> {
                SearchField(query, palette, surface) { query = it }
                val needle = query.trim()
                val shown = if (needle.isEmpty()) s.translations else s.translations.filter {
                    listOf(it.title, it.languageName, it.languageNameInEnglish, it.shortTitle).any { f -> f.contains(needle, ignoreCase = true) }
                }
                Column(Modifier.fillMaxSize().background(StudyStyle.groupedBackground(palette)).verticalScroll(rememberScrollState())) {
                    GroupedSection(
                        palette, header = if (needle.isEmpty()) stringResource(R.string.translations_catalog_offered) else stringResource(R.string.translations_catalog_results),
                        footer = if (needle.isEmpty()) stringResource(R.string.translations_catalog_footer) else null,
                    ) {
                        shown.forEachIndexed { i, t ->
                            if (i > 0) CellDivider(palette)
                            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(t) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(t.title, color = palette.ink, fontSize = StudyStyle.body)
                                Text(stringResource(R.string.translations_catalog_language_scope, t.languageNameInEnglish, t.scope), color = palette.secondary, fontSize = StudyStyle.caption)
                                Text(t.copyright, color = palette.secondary, fontSize = StudyStyle.caption2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, palette: ReaderPalette, surface: Color, onChange: (String) -> Unit) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).fillMaxWidth().height(44.dp)
            .glass(palette, CircleShape, surface).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = palette.secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            query, onChange, singleLine = true, textStyle = TextStyle(color = palette.ink, fontSize = 17.sp),
            cursorBrush = SolidColor(palette.accent), modifier = Modifier.weight(1f),
            decorationBox = { field ->
                if (query.isEmpty()) Text(stringResource(R.string.translations_catalog_search_placeholder), color = palette.secondary, fontSize = 17.sp)
                field()
            },
        )
    }
}

// ---- Online keys ----------------------------------------------------------------------------------

/**
 * Where the reader puts their own API keys and picks which translations to add — `OnlineKeysView.swift`.
 * The app never ships a key: both providers' free tiers are per-key allowances meant for one person.
 * Keys are kept encrypted by the Android Keystore ([com.blainemiller.scripturealone.data.online.OnlineKeyStore])
 * and carried to the reader's other devices by Block Store ([com.blainemiller.scripturealone.data.online.OnlineKeySync]).
 */
@Composable
private fun OnlineKeysPage(reader: ReaderViewModel, palette: ReaderPalette, onDone: () -> Unit) {
    val context = LocalContext.current
    val surface = SheetColors.surface(palette)
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val keys = remember { TranslationLibrary.keys(context) }
    val entry = remember { mutableStateMapOf<OnlineProvider, String>() }
    val stored = remember { mutableStateMapOf<OnlineProvider, Boolean>() }
    var available by remember { mutableStateOf<List<APIBibleTranslation>>(emptyList()) }
    val chosen = remember { mutableStateMapOf<String, Boolean>() }
    var checking by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            for (provider in OnlineProvider.entries) {
                val key = keys.key(provider)
                entry[provider] = key.orEmpty()
                stored[provider] = key != null
            }
            TranslationLibrary.rememberedPicks().forEach { chosen[it.remoteId] = true }
        }
    }

    fun loadAvailable() {
        val key = entry[OnlineProvider.API_BIBLE]?.trim().orEmpty()
        if (key.isEmpty()) return
        checking = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { APIBibleClient(key).availableTranslations() } }
            checking = false
            result.onSuccess { list -> available = list.sortedBy { it.name.lowercase() } }
                .onFailure { failure = it.message ?: AppText.get(R.string.translations_keys_api_bible_unreachable) }
        }
    }

    fun save() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val before = TranslationLibrary.state.value.online
                for (provider in OnlineProvider.entries) keys.set(entry[provider].orEmpty(), provider)
                val picks = if (available.isEmpty()) {
                    TranslationLibrary.rememberedPicks()
                } else {
                    available.filter { chosen[it.id] == true }.map { t ->
                        OnlineEntry(t.abbreviation.ifEmpty { t.id }.uppercase(), t.name, OnlineProvider.API_BIBLE, t.id)
                    }
                }
                TranslationLibrary.rememberPicks(picks)
                TranslationLibrary.syncKeys()
                // A translation whose key is gone takes its cached text with it.
                val after = TranslationLibrary.state.value.online.map { it.id }.toSet()
                before.filter { it.id !in after }.forEach { runCatching { TranslationLibrary.loader.clear(it) } }
            }
            if (reader.translationId !in BundledTranslations.ids) reader.selectTranslation(BundledTranslations.DEFAULT)
            onDone()
        }
    }

    Column(Modifier.fillMaxSize()) {
        SheetTopBar(
            stringResource(R.string.translations_keys_title), palette,
            leading = { GlassTextButton(stringResource(R.string.common_cancel), palette, surface, onClick = onDone) },
            trailing = { GlassTextButton(stringResource(R.string.common_done), palette, surface, bold = true, tint = palette.accent, onClick = ::save) },
        )
        Column(Modifier.fillMaxSize().background(StudyStyle.groupedBackground(palette)).verticalScroll(rememberScrollState())) {
            for (provider in OnlineProvider.entries) {
                GroupedSection(palette, header = provider.title, footer = provider.explanation) {
                    KeyField(entry[provider].orEmpty(), stringResource(R.string.translations_keys_field_description, provider.title), palette) { entry[provider] = it }
                    CellDivider(palette)
                    Cell(palette, onClick = { uri.openUri(provider.signupUrl) }) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = palette.accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.translations_keys_get_free), color = palette.accent, fontSize = StudyStyle.body)
                    }
                    if (stored[provider] == true) {
                        CellDivider(palette)
                        Cell(palette, onClick = {
                            entry[provider] = ""
                            if (provider == OnlineProvider.API_BIBLE) { available = emptyList(); chosen.clear() }
                        }) {
                            Icon(Icons.Outlined.Delete, null, tint = palette.red, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.translations_keys_remove), color = palette.red, fontSize = StudyStyle.body)
                        }
                    }
                    if (provider == OnlineProvider.API_BIBLE && entry[provider].orEmpty().isNotBlank()) {
                        CellDivider(palette)
                        when {
                            checking -> Cell(palette) {
                                CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(R.string.translations_keys_checking), color = palette.ink, fontSize = StudyStyle.callout)
                            }
                            available.isEmpty() -> Cell(palette, onClick = ::loadAvailable) {
                                Icon(Icons.Outlined.Refresh, null, tint = palette.accent, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(R.string.translations_keys_check), color = palette.accent, fontSize = StudyStyle.body)
                            }
                            else -> available.forEachIndexed { i, t ->
                                if (i > 0) CellDivider(palette)
                                Cell(palette, onClick = { chosen[t.id] = chosen[t.id] != true }) {
                                    Column(Modifier.weight(1f)) {
                                        Text(t.name, color = palette.ink, fontSize = StudyStyle.body)
                                        Text(t.language, color = palette.secondary, fontSize = StudyStyle.caption)
                                    }
                                    if (chosen[t.id] == true) Icon(Icons.Rounded.Check, stringResource(R.string.translations_keys_chosen), tint = palette.accent, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
    failure?.let { Alert(stringResource(R.string.translations_keys_failed_title), it, palette, confirm = stringResource(R.string.common_ok), onConfirm = { failure = null }) }
}

/** A secure field: the key is masked, never autocorrected, never suggested. */
@Composable
private fun KeyField(value: String, label: String, palette: ReaderPalette, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = palette.ink, fontSize = 17.sp),
            cursorBrush = SolidColor(palette.accent),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            decorationBox = { field ->
                if (value.isEmpty()) Text(stringResource(R.string.translations_keys_placeholder), color = palette.secondary.copy(alpha = 0.7f), fontSize = 17.sp)
                field()
            },
        )
    }
}

// ---- Import summary --------------------------------------------------------------------------------

/**
 * What the import actually got — `ImportSummaryView`. Shown always: a clean import is one line, and
 * an incomplete one says so rather than pretending.
 */
@Composable
private fun ImportSummary(result: BibleImportResult, palette: ReaderPalette, onDone: () -> Unit) {
    val surface = SheetColors.surface(palette)
    val report = result.report
    val gaps = report.books.filter { !it.isComplete }
    Column(Modifier.fillMaxSize()) {
        SheetTopBar(stringResource(R.string.translations_summary_title), palette, trailing = { GlassTextButton(stringResource(R.string.common_done), palette, surface, bold = true, tint = palette.accent, onClick = onDone) })
        Column(Modifier.fillMaxSize().background(StudyStyle.groupedBackground(palette)).verticalScroll(rememberScrollState())) {
            GroupedSection(palette, footer = result.identity.copyright) {
                LabeledCell(palette, stringResource(R.string.translations_summary_translation), result.identity.name)
                CellDivider(palette)
                LabeledCell(palette, stringResource(R.string.translations_summary_books), NumberFormat.getIntegerInstance().format(report.books.size))
                CellDivider(palette)
                LabeledCell(palette, stringResource(R.string.translations_summary_verses), NumberFormat.getIntegerInstance().format(report.totalVerses))
                CellDivider(palette)
                LabeledCell(palette, stringResource(R.string.translations_summary_quoting), quotingTerms(result))
            }
            if (gaps.isEmpty() && report.booksMissing.isEmpty()) {
                GroupedSection(palette) {
                    Cell(palette) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF34A853), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.translations_summary_clean), color = Color(0xFF2E8B57), fontSize = StudyStyle.body)
                    }
                }
            } else {
                GroupedSection(
                    palette, header = stringResource(R.string.translations_summary_gaps),
                    footer = stringResource(R.string.translations_summary_gaps_footer),
                ) {
                    gaps.forEachIndexed { i, book ->
                        if (i > 0) CellDivider(palette)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 9.dp)) {
                            Text(book.book.displayName, color = palette.ink, fontSize = StudyStyle.body)
                            Text(gapSummary(book), color = palette.secondary, fontSize = StudyStyle.caption)
                        }
                    }
                    if (report.booksMissing.isNotEmpty()) {
                        if (gaps.isNotEmpty()) CellDivider(palette)
                        Text(
                            stringResource(R.string.translations_summary_missing, report.booksMissing.joinToString(", ") { it.displayName }),
                            color = palette.secondary, fontSize = StudyStyle.caption, modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

/**
 * Says what is actually absent. A book can be flagged with every chapter present — the WEB omits
 * verses like Luke 17:36 — and "24 of 24 chapters" under a "Gaps" heading reads as a bug.
 */
internal fun gapSummary(book: ImportCoverageReport.BookCoverage): String {
    if (book.missingChapters.isNotEmpty()) {
        return AppText.plural(
            R.string.translations_gap_chapters_one, R.string.translations_gap_chapters_other,
            book.chaptersExpected, book.chaptersFound, book.chaptersExpected,
        )
    }
    val refs = book.chaptersWithGaps.flatMap { c -> c.missingVerses.map { "${c.chapter}:$it" } }
    if (refs.isEmpty()) return AppText.plural(R.string.translations_gap_verses_one, R.string.translations_gap_verses_other, book.versesFound, book.versesFound)
    val extra = refs.size - minOf(refs.size, 4)
    val shown = refs.take(4).joinToString(", ")
    return if (extra > 0) AppText.get(R.string.translations_summary_missing_more, shown, extra)
    else AppText.get(R.string.translations_summary_missing, shown)
}

/**
 * Which publisher's terms copying and sharing will follow, so a reader can see the importer recognised
 * the translation — or that it didn't, and the cautious default applies.
 */
internal fun quotingTerms(result: BibleImportResult): String {
    val identity = result.identity
    val license = identity.license
    val copyright = identity.copyright
    if (TranslationRights.isPublicDomain(license, copyright)) return AppText.get(R.string.translations_quoting_public_domain)
    val terms = TranslationRights.publisherTerms(license, copyright, identity.abbreviation, identity.name)
        ?: return AppText.get(R.string.translations_quoting_unrecognised, TranslationRights.QUOTATION_VERSE_LIMIT.toInt())
    val limit = terms.maxVerses ?: return AppText.get(R.string.translations_quoting_terms_unlimited)
    return AppText.get(R.string.translations_quoting_terms_limit, limit)
}

// ---- Overlays ---------------------------------------------------------------------------------------

@Composable
private fun ProgressOverlay(name: String, progress: Double?, palette: ReaderPalette) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)).takesTaps(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(32.dp).widthIn(max = 320.dp).clip(RoundedCornerShape(16.dp)).background(SheetColors.popover(palette)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (progress != null && progress < 1.0) {
                LinearProgressIndicator(
                    progress = { progress.toFloat() }, color = palette.accent, trackColor = SheetColors.tertiaryFill(palette),
                    modifier = Modifier.width(180.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.translations_progress_downloading, name), color = palette.ink, fontSize = StudyStyle.callout, textAlign = TextAlign.Center)
            } else {
                CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.translations_progress_reading, name), color = palette.ink, fontSize = StudyStyle.callout, textAlign = TextAlign.Center)
                Text(stringResource(R.string.translations_progress_note), color = palette.secondary, fontSize = StudyStyle.caption, textAlign = TextAlign.Center)
            }
        }
    }
}

/** An iOS-style alert: a title, a message, and one or two buttons divided by hairlines. */
@Composable
internal fun Alert(
    title: String,
    message: String,
    palette: ReaderPalette,
    confirm: String,
    destructive: Boolean = false,
    onCancel: (() -> Unit)? = null,
    onConfirm: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)).takesTaps(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(290.dp).clip(RoundedCornerShape(16.dp)).background(SheetColors.popover(palette))) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = palette.ink, fontSize = StudyStyle.headline, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(message, color = palette.ink, fontSize = StudyStyle.footnote, textAlign = TextAlign.Center)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(SheetColors.separator(palette)))
            Row(Modifier.fillMaxWidth().height(46.dp)) {
                if (onCancel != null) {
                    AlertButton(stringResource(R.string.common_cancel), palette.accent, FontWeight.Normal, Modifier.weight(1f), onCancel)
                    Box(Modifier.width(0.5.dp).height(46.dp).background(SheetColors.separator(palette)))
                }
                AlertButton(confirm, if (destructive) palette.red else palette.accent, FontWeight.SemiBold, Modifier.weight(1f), onConfirm)
            }
        }
    }
}

@Composable
private fun AlertButton(title: String, color: Color, weight: FontWeight, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(46.dp).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(title, color = color, fontSize = StudyStyle.body, fontWeight = weight)
    }
}
