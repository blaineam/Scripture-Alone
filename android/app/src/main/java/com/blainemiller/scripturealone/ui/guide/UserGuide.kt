package com.blainemiller.scripturealone.ui.guide

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.shadow
import androidx.core.content.FileProvider
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.text.AppLanguage
import com.blainemiller.scripturealone.ui.export.ExportFiles
import com.blainemiller.scripturealone.ui.notes.PanelColors
import com.blainemiller.scripturealone.ui.notes.PanelHeader
import com.blainemiller.scripturealone.ui.notes.PanelHeaderIcon
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.takesTaps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The bundled user guide — `ScriptureAlone/App/UserGuide.swift`: a PDF per app language, built by
 * `Tools/build_manual.py` and copied into `assets/manual/` at build time (`syncBundledData`), so it
 * reads offline like everything else.
 */
object UserGuide {
    /** The assets folder the guides are in. */
    const val ASSET_DIR = "manual"

    /** The `cache-path` in `res/xml/share_paths.xml` the open guide is copied to. */
    private const val CACHE_DIR = "user_guide"

    /** A guide's file name for an app language tag ("en", "zh-Hans", "pt-BR"…). */
    fun fileName(language: String) = "UserGuide-$language.pdf"

    /**
     * The guide to open for the app [language] ("en" or one of [AppLanguage.SUPPORTED]) out of the
     * [available] file names: that language's, else English's; null when neither is there.
     */
    fun pick(language: String, available: Collection<String>): String? =
        listOf(language, "en").map(::fileName).firstOrNull { it in available }

    /** The guide's file name in the language the app is running in, English failing that. */
    fun current(context: Context): String? {
        val available = runCatching { context.assets.list(ASSET_DIR)?.toList() }.getOrNull().orEmpty()
        return pick(AppLanguage.current, available)
    }

    /**
     * [name] copied out of the assets into the cache, where `PdfRenderer` can seek in it and the
     * `FileProvider` can hand it to the share sheet. Written beside and renamed, so a share target
     * still reading an earlier copy never sees a half-written file.
     */
    suspend fun stage(context: Context, name: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val target = File(dir, name)
        val partial = File(dir, "$name.partial")
        context.assets.open("$ASSET_DIR/$name").use { input -> partial.outputStream().use { input.copyTo(it) } }
        if (!partial.renameTo(target)) throw IOException("Can't stage $name")
        target
    }

    /** The share sheet for a staged guide. */
    fun share(context: Context, file: File, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.shareimages", file)
        try {
            context.startActivity(ExportFiles.shareIntent(listOf(uri), ExportFiles.PDF, title))
        } catch (_: ActivityNotFoundException) {
            // Nothing on the device takes a PDF; the button simply does nothing, as there is nowhere to send it.
        }
    }
}

/**
 * The open guide: a `PdfRenderer` over the staged file and each page's size in points. The renderer
 * opens one page at a time, so every use goes through [lock].
 */
private class GuideDocument(val file: File, private val descriptor: ParcelFileDescriptor, private val renderer: PdfRenderer) {
    val lock = Mutex()
    /** Width over height of each page, read once up front so a page not yet drawn holds its place. */
    val aspects: List<Float> = (0 until renderer.pageCount).map { index ->
        renderer.openPage(index).use { page -> page.width.toFloat() / page.height.coerceAtLeast(1) }
    }

    /** Page [index] drawn [width] pixels wide on white (a PDF page is transparent where nothing is printed). */
    suspend fun render(index: Int, width: Int): Bitmap = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed) throw IOException("The guide is closed")
            renderer.openPage(index).use { page ->
                val height = (width.toFloat() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(AndroidColor.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    /** Set under [lock] once closed; a page asked for after that fails rather than touch a closed renderer. */
    private var closed = false

    /** Closes the renderer and the file once no page is being drawn. */
    fun closeLater() {
        CoroutineScope(Dispatchers.IO).launch {
            lock.withLock {
                if (!closed) {
                    closed = true
                    renderer.close()
                    descriptor.close()
                }
            }
        }
    }

    companion object {
        fun open(file: File): GuideDocument {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return try {
                GuideDocument(file, descriptor, PdfRenderer(descriptor))
            } catch (error: Exception) {
                descriptor.close()
                throw error
            }
        }
    }
}

/** What the sheet shows while the guide opens, once it has, or when it can't. */
private sealed class GuideState {
    data object Loading : GuideState()
    data object Missing : GuideState()
    class Open(val document: GuideDocument) : GuideState()
}

/**
 * The guide as a full-height sheet — `UserGuideView`: back, the title, and Share, over the pages as a
 * continuous column scaled to the sheet's width. Pages are drawn as they scroll into view and dropped
 * as they leave it, so only the few on screen are held as bitmaps.
 */
@Composable
fun UserGuideSheet(palette: ReaderPalette, onDone: () -> Unit) {
    val context = LocalContext.current
    val title = stringResource(R.string.user_guide_title)
    BackHandler(onBack = onDone)

    val state by produceState<GuideState>(GuideState.Loading) {
        val opened = runCatching {
            val name = UserGuide.current(context) ?: return@runCatching GuideState.Missing
            val file = UserGuide.stage(context, name)
            GuideState.Open(withContext(Dispatchers.IO) { GuideDocument.open(file) })
        }.getOrDefault(GuideState.Missing)
        value = opened
        // Closed once the sheet goes — after any page still being drawn, which holds the lock.
        awaitDispose { (opened as? GuideState.Open)?.document?.closeLater() }
    }
    val open = state as? GuideState.Open

    Column(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(PanelColors.background(palette))
            .takesTaps(),
    ) {
        PanelHeader(title, palette, back = true, onLeading = onDone) {
            val shareLabel = stringResource(R.string.common_share)
            PanelHeaderIcon(Icons.Outlined.IosShare, shareLabel, palette) {
                open?.let { UserGuide.share(context, it.document.file, title) }
            }
        }
        when (val current = state) {
            GuideState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.secondary)
            }
            GuideState.Missing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.user_guide_unavailable), color = palette.secondary, fontSize = 17.sp)
            }
            is GuideState.Open -> GuidePages(current.document)
        }
    }
}

@Composable
private fun GuidePages(document: GuideDocument) {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = 12.dp
        // Drawn at the column's width in pixels — sharp on the screen, and no bigger than it needs to be.
        val widthPx = with(LocalDensity.current) { (maxWidth - side * 2).roundToPx() }.coerceIn(1, 2400)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = 4.dp, bottom = nav + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(document.aspects) { index, aspect ->
                GuidePage(document, index, aspect, widthPx)
            }
        }
    }
}

@Composable
private fun GuidePage(document: GuideDocument, index: Int, aspect: Float, widthPx: Int) {
    val bitmap by produceState<Bitmap?>(null, document, index, widthPx) {
        value = runCatching { document.render(index, widthPx) }.getOrNull()
    }
    val label = stringResource(R.string.user_guide_page, index + 1, document.aspects.size)
    Box(
        Modifier.fillMaxWidth().aspectRatio(aspect)
            .shadow(2.dp, RoundedCornerShape(4.dp))
            .background(Color.White)
            .semantics { contentDescription = label },
    ) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillWidth) }
    }
}
