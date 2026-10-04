package com.blainemiller.scripturealone.ui.guide

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.guide.UserGuide
import com.blainemiller.scripturealone.data.guide.UserGuidePackage
import com.blainemiller.scripturealone.data.guide.UserGuideStore
import com.blainemiller.scripturealone.ui.notes.PanelColors
import com.blainemiller.scripturealone.ui.notes.PanelHeader
import com.blainemiller.scripturealone.ui.notes.PanelHeaderIcon
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.takesTaps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

/** Where opening the guide has got to. */
private sealed interface GuideLoad {
    /** Looking for a copy on the device — a few milliseconds; nothing is shown. */
    data object Opening : GuideLoad
    data object Downloading : GuideLoad
    data object Failed : GuideLoad
    data class Ready(val copy: UserGuideStore.Copy) : GuideLoad
}

/** The guide as a full-height sheet over the reader (`FullSheet`): rounded top, the reader's page. */
@Composable
fun UserGuideSheet(palette: ReaderPalette, onDone: () -> Unit) {
    UserGuideScreen(
        palette, onDone,
        Modifier.clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).takesTaps(),
    )
}

/**
 * The User Guide, drawn natively from the downloaded package so it reflows to the screen and follows
 * the system font size: back, the chapters menu and Share over the cover, the contents and the
 * chapters, in a column no wider than a comfortable line.
 *
 * A copy on the device shows at once and is refreshed in the background; with none, the package is
 * downloaded first — and if that fails, a message and Try Again, never an endless spinner.
 */
@Composable
fun UserGuideScreen(palette: ReaderPalette, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { UserGuideStore.forContext(context) }
    val language = remember { UserGuideStore.currentLanguage }
    var attempt by remember { mutableIntStateOf(0) }
    var load by remember { mutableStateOf<GuideLoad>(GuideLoad.Opening) }
    BackHandler(onBack = onDone)

    LaunchedEffect(attempt) {
        val held = (load as? GuideLoad.Ready)?.copy ?: store.heldAsync(language)
        if (held != null) {
            load = GuideLoad.Ready(held)
            // Quietly: offline, or an index that can't be read, leaves the held copy in place.
            try {
                store.refresh(language)?.let { load = GuideLoad.Ready(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Unit
            }
            return@LaunchedEffect
        }
        load = GuideLoad.Downloading
        load = try {
            (store.refresh(language) ?: store.heldAsync(language))?.let { GuideLoad.Ready(it) } ?: GuideLoad.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            GuideLoad.Failed
        }
    }

    val colors = remember(palette) { GuideColors(palette) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val ready = load as? GuideLoad.Ready
    // Where each chapter starts in the list, for the contents and the chapters menu.
    val starts = remember(ready?.copy) { ready?.copy?.guide?.let(::chapterStarts).orEmpty() }
    fun jump(chapter: Int) {
        starts.getOrNull(chapter)?.let { scope.launch { listState.scrollToItem(it) } }
    }

    Column(modifier.fillMaxSize().background(palette.page)) {
        val title = stringResource(R.string.user_guide_title)
        PanelHeader(title, palette, back = true, onLeading = onDone) {
            ready?.let { ChaptersMenu(it.copy.guide, palette, ::jump) }
            PanelHeaderIcon(Icons.Outlined.IosShare, stringResource(R.string.common_share), palette) {
                share(context, language, ready?.copy?.guide?.title ?: title)
            }
        }
        when (val current = load) {
            GuideLoad.Opening -> Unit
            GuideLoad.Downloading -> Message {
                CircularProgressIndicator(color = colors.secondary, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                Text(
                    stringResource(R.string.user_guide_downloading), color = colors.secondary, fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )
            }
            GuideLoad.Failed -> Message {
                Text(
                    stringResource(R.string.user_guide_download_failed), color = colors.ink, fontSize = 17.sp,
                    textAlign = TextAlign.Center, lineHeight = 24.sp,
                )
                Box(
                    Modifier.clip(CircleShape).background(colors.accent)
                        .clickable(role = Role.Button) { attempt++ }
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                ) {
                    Text(stringResource(R.string.common_try_again), color = colors.onAccent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            is GuideLoad.Ready -> GuideList(current.copy, colors, listState, ::jump)
        }
    }
}

@Composable
private fun Message(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) { content() }
    }
}

/** The trailing pill's chapters button and its menu. */
@Composable
private fun ChaptersMenu(guide: UserGuide, palette: ReaderPalette, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PanelHeaderIcon(Icons.AutoMirrored.Rounded.FormatListBulleted, stringResource(R.string.user_guide_chapters), palette) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = PanelColors.card(palette)) {
            guide.chapters.forEachIndexed { index, chapter ->
                DropdownMenuItem(
                    text = {
                        Row {
                            Text(
                                String.format(Locale.getDefault(), "%d", index + 1),
                                color = palette.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(30.dp),
                            )
                            Text(chapter.title, color = palette.ink, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    onClick = {
                        open = false
                        onPick(index)
                    },
                )
            }
        }
    }
}

/** One row of the guide's list. Blocks are items of their own so a long chapter is laid out lazily. */
private sealed interface GuideItem {
    data object Cover : GuideItem
    data object Contents : GuideItem
    data class ChapterHead(val index: Int, val chapter: UserGuide.Chapter) : GuideItem
    data class Body(val block: UserGuide.Block) : GuideItem
}

private fun items(guide: UserGuide): List<GuideItem> = buildList {
    add(GuideItem.Cover)
    add(GuideItem.Contents)
    guide.chapters.forEachIndexed { index, chapter ->
        add(GuideItem.ChapterHead(index, chapter))
        chapter.blocks.filter { it != UserGuide.Block.Unknown }.forEach { add(GuideItem.Body(it)) }
    }
}

private fun chapterStarts(guide: UserGuide): List<Int> =
    items(guide).withIndex().filter { it.value is GuideItem.ChapterHead }.map { it.index }

@Composable
private fun GuideList(
    copy: UserGuideStore.Copy,
    colors: GuideColors,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onChapter: (Int) -> Unit,
) {
    val guide = copy.guide
    val rows = remember(guide) { items(guide) }
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val env = GuideEnv(colors, copy, wide = maxWidth >= 600.dp)
        ProvideGuide(env) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(top = 4.dp, bottom = nav + 40.dp),
            ) {
                itemsIndexed(rows) { _, row ->
                    // A readable measure, centred on a tablet.
                    val column = Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 20.dp)
                    when (row) {
                        GuideItem.Cover -> GuideCover(guide.cover, column.padding(top = 4.dp))
                        GuideItem.Contents -> Contents(guide, colors, onChapter, column.padding(top = 28.dp))
                        is GuideItem.ChapterHead -> ChapterHead(row.index, row.chapter, colors, column.padding(top = 44.dp, bottom = 4.dp))
                        is GuideItem.Body -> GuideBlock(row.block, column.padding(top = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Contents(guide: UserGuide, colors: GuideColors, onChapter: (Int) -> Unit, modifier: Modifier) {
    Column(modifier) {
        val heading = guide.contents.ifEmpty { stringResource(R.string.user_guide_contents) }
        Text(
            heading, color = colors.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
        )
        guide.chapters.forEachIndexed { index, chapter ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onChapter(index) }.padding(vertical = 12.dp),
            ) {
                Text(
                    String.format(Locale.getDefault(), "%d", index + 1),
                    color = colors.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(chapter.title, color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    if (chapter.summary.isNotEmpty()) {
                        Text(chapter.summary, color = colors.secondary, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterHead(index: Int, chapter: UserGuide.Chapter, colors: GuideColors, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
        Text(
            String.format(Locale.getDefault(), "%d", index + 1),
            color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.15.em,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            chapter.title, color = colors.ink, fontSize = 27.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        if (chapter.lede.isNotEmpty()) GuideText(chapter.lede, Modifier.padding(top = 4.dp), size = 18, color = colors.secondary)
    }
}

/** Share: the printable PDF of the guide in this language, as a link. */
private fun share(context: Context, language: String, title: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .putExtra(Intent.EXTRA_TEXT, UserGuidePackage.pdfUrl(language))
    try {
        context.startActivity(Intent.createChooser(send, title))
    } catch (_: ActivityNotFoundException) {
        // Nowhere to send it; the button does nothing.
    }
}
