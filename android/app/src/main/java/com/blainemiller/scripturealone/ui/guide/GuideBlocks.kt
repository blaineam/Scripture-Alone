package com.blainemiller.scripturealone.ui.guide

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.data.guide.UserGuide
import com.blainemiller.scripturealone.data.guide.UserGuide.Block
import com.blainemiller.scripturealone.data.guide.UserGuideStore
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * The guide's colours: the print design's (`docs/manual/template.html` — accent #a0662a, accent-soft
 * #f4e6d4, panel #f3efe9) on the reader's page and ink, with dark counterparts.
 */
internal class GuideColors(palette: ReaderPalette) {
    val dark = palette.isDark
    val page = palette.page
    val ink = palette.ink
    val secondary = palette.secondary
    val accent = if (dark) Color(0xFFE0B872) else Color(0xFFA0662A)
    val accentSoft = if (dark) accent.copy(alpha = 0.18f).compositeOver(page) else Color(0xFFF4E6D4)
    val panel = if (dark) Color.White.copy(alpha = 0.07f).compositeOver(page) else Color(0xFFF3EFE9)
    val rule = if (dark) Color.White.copy(alpha = 0.12f) else Color(0xFFE6DDD2)
    val warn = if (dark) Color(0xFFFF6F61).copy(alpha = 0.16f).compositeOver(page) else Color(0xFFFBEAEA)
    val warnLabel = if (dark) Color(0xFFFF8A80) else Color(0xFFAA3333)
    val onAccent = if (dark) Color(0xFF1D1B18) else Color.White
}

/** What every block needs: colours, the package's images, and whether the column is wide. */
internal class GuideEnv(val colors: GuideColors, val copy: UserGuideStore.Copy, val wide: Boolean)

internal val LocalGuide = staticCompositionLocalOf<GuideEnv> { error("no guide") }

private const val BODY_SP = 16
private const val PHONE_ASPECT = 559f / 1149f
private const val WATCH_ASPECT = 396f / 640f

// MARK: Inline text

/**
 * A guide paragraph's runs as one [AnnotatedString]: bold; a `ui` name as semibold accent text on
 * accent-soft; `kbd` and `code` in monospace on the panel colour; `small` smaller; a link the
 * browser opens; `br` a line break.
 */
internal fun inlineText(runs: List<UserGuide.Run>, colors: GuideColors): AnnotatedString = buildAnnotatedString {
    for (run in runs) {
        if (run.lineBreak) {
            append("\n")
            continue
        }
        var style = SpanStyle()
        if (run.bold) style = style.merge(SpanStyle(fontWeight = FontWeight.Bold))
        if (run.ui) style = style.merge(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.accent, background = colors.accentSoft))
        if (run.kbd || run.code) style = style.merge(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.9.em, background = colors.panel))
        if (run.small) style = style.merge(SpanStyle(fontSize = 0.85.em))
        // A narrow no-break space either side, so a highlighted name doesn't touch its background's edge.
        val text = if (run.ui || run.kbd) " ${run.text} " else run.text
        val link = run.link?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        if (link != null) {
            val linkStyle = TextLinkStyles(SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline))
            withLink(LinkAnnotation.Url(link, linkStyle)) { withStyle(style) { append(text) } }
        } else {
            withStyle(style) { append(text) }
        }
    }
}

@Composable
internal fun GuideText(
    runs: List<UserGuide.Run>,
    modifier: Modifier = Modifier,
    size: Int = BODY_SP,
    color: Color? = null,
    weight: FontWeight? = null,
    align: TextAlign? = null,
) {
    val colors = LocalGuide.current.colors
    Text(
        inlineText(runs, colors),
        modifier = modifier,
        color = color ?: colors.ink,
        fontSize = size.sp,
        lineHeight = (size * 1.45f).sp,
        fontWeight = weight,
        textAlign = align,
    )
}

// MARK: Blocks

@Composable
internal fun GuideBlocks(blocks: List<Block>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (block in blocks) GuideBlock(block)
    }
}

@Composable
internal fun GuideBlock(block: Block, modifier: Modifier = Modifier) {
    val env = LocalGuide.current
    val colors = env.colors
    when (block) {
        is Block.Paragraph -> if (block.fine) {
            GuideText(block.inline, modifier.fillMaxWidth(), size = 13, color = colors.secondary, align = TextAlign.Center)
        } else {
            GuideText(block.inline, modifier)
        }
        is Block.Heading -> GuideText(
            block.inline, modifier.padding(top = 10.dp).semantics { heading() },
            size = 19, weight = FontWeight.SemiBold,
        )
        is Block.Bullets -> Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (item in block.items) {
                Row {
                    Text("•", color = colors.accent, fontSize = BODY_SP.sp, lineHeight = (BODY_SP * 1.45f).sp, modifier = Modifier.width(18.dp))
                    GuideText(item, Modifier.weight(1f))
                }
            }
        }
        is Block.Steps -> StepList(block, modifier)
        is Block.Table -> GuideTable(block, modifier)
        is Block.Callout -> Callout(block, modifier)
        is Block.FigureBlock -> GuideFigure(block.figure, modifier.fillMaxWidth())
        is Block.Feature -> Feature(block, modifier)
        is Block.MockBlock -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { GuideMock(block.mock) }
        Block.Unknown -> Unit
    }
}

@Composable
private fun StepList(block: Block.Steps, modifier: Modifier) {
    val colors = LocalGuide.current.colors
    // The circle grows with the text, so a large font's number still fits.
    val circle = (26 * LocalDensity.current.fontScale.coerceIn(1f, 1.8f)).dp
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        block.items.forEachIndexed { index, step ->
            Row {
                Box(Modifier.size(circle).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                    Text(
                        String.format(Locale.getDefault(), "%d", index + 1),
                        color = colors.onAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(12.dp))
                GuideBlocks(step, Modifier.weight(1f).padding(top = 1.dp))
            }
        }
    }
}

@Composable
private fun GuideTable(block: Block.Table, modifier: Modifier) {
    val env = LocalGuide.current
    val colors = env.colors
    val columns = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return
    if (env.wide) {
        // Side by side: the header in bold over a rule, the first column narrower when there are two.
        val weights = if (columns == 2) listOf(0.36f, 0.64f) else List(columns) { 1f }
        Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel).padding(horizontal = 14.dp, vertical = 6.dp)) {
            if (block.header.isNotEmpty()) {
                Row(Modifier.padding(vertical = 8.dp)) {
                    for (c in 0 until columns) {
                        GuideText(block.header.getOrElse(c) { emptyList() }, Modifier.weight(weights[c]).padding(end = 12.dp), size = 14, weight = FontWeight.Bold)
                    }
                }
            }
            block.rows.forEach { row ->
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
                Row(Modifier.padding(vertical = 8.dp)) {
                    for (c in 0 until columns) {
                        GuideText(
                            row.getOrElse(c) { emptyList() }, Modifier.weight(weights[c]).padding(end = 12.dp),
                            size = 15, weight = if (c == 0) FontWeight.SemiBold else null,
                        )
                    }
                }
            }
        }
    } else {
        // Narrow: each row a card — its first cell as the label, the others below it (named by their
        // column when there are more than one).
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            block.rows.forEach { row ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    row.firstOrNull()?.let { GuideText(it, size = 15, weight = FontWeight.SemiBold) }
                    for (c in 1 until row.size) {
                        if (columns > 2) block.header.getOrNull(c)?.let { GuideText(it, size = 12, color = colors.secondary, weight = FontWeight.SemiBold) }
                        GuideText(row[c], size = 15)
                    }
                }
            }
        }
    }
}

@Composable
private fun Callout(block: Block.Callout, modifier: Modifier) {
    val colors = LocalGuide.current.colors
    val (background, labelColor) = when (block.style) {
        UserGuide.CalloutStyle.TIP -> colors.accentSoft to colors.accent
        UserGuide.CalloutStyle.NOTE -> colors.panel to colors.accent
        UserGuide.CalloutStyle.WARN -> colors.warn to colors.warnLabel
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(background).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (block.label.isNotEmpty()) {
            Text(
                block.label.uppercase(Locale.getDefault()), color = labelColor, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 0.12.em,
            )
        }
        GuideBlocks(block.blocks)
    }
}

@Composable
private fun Feature(block: Block.Feature, modifier: Modifier) {
    val env = LocalGuide.current
    // Beside the text it takes a fixed column — the mock's frame would otherwise claim the whole row.
    val visualWidth = if (env.wide) Modifier.width(if (block.mock != null) 290.dp else 240.dp) else Modifier
    val visual: (@Composable () -> Unit)? = if (block.figure == null && block.mock == null) null else {
        {
            Column(visualWidth, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                block.figure?.let { GuideFigure(it) }
                block.mock?.let { GuideMock(it) }
            }
        }
    }
    if (env.wide && visual != null) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (block.flip) visual()
            GuideBlocks(block.blocks, Modifier.weight(1f))
            if (!block.flip) visual()
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            GuideBlocks(block.blocks)
            if (visual != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { visual() }
        }
    }
}

@Composable
internal fun GuideFigure(figure: UserGuide.Figure, modifier: Modifier = Modifier) {
    val env = LocalGuide.current
    val phone = figure.device == UserGuide.Device.PHONE
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        GuideImage(
            env.copy.image(figure.image),
            width = if (phone) 240.dp else 150.dp,
            placeholderAspect = if (phone) PHONE_ASPECT else WATCH_ASPECT,
            description = figure.caption.ifEmpty { null },
        )
        if (figure.caption.isNotEmpty()) {
            // The image already carries the caption for TalkBack; this is for the eye.
            Text(
                figure.caption, color = env.colors.secondary, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp).widthIn(max = 260.dp).clearAndSetSemantics {},
            )
        }
    }
}

// MARK: Images

/** Decoded package images, so scrolling back up doesn't decode them again. Keyed by path and date. */
private object GuideImageCache {
    val bitmaps = LruCache<String, ImageBitmap>(24)
}

@Composable
internal fun GuideImage(file: File, width: Dp, placeholderAspect: Float, description: String?, modifier: Modifier = Modifier) {
    val key = "${file.path}@${file.lastModified()}"
    val bitmap by produceState(GuideImageCache.bitmaps.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
            }?.also { GuideImageCache.bitmaps.put(key, it) }
        }
    }
    val aspect = bitmap?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: placeholderAspect
    val semantics = if (description != null) {
        Modifier.semantics {
            contentDescription = description
            role = Role.Image
        }
    } else {
        Modifier.clearAndSetSemantics {}
    }
    Box(modifier.width(width).aspectRatio(aspect).then(semantics)) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

// MARK: Mock app screen

/** iOS's grouped list colours, light and dark, for the drawn app screens. */
private class MockColors(dark: Boolean) {
    val frame = if (dark) Color(0xFF48484A) else Color(0xFF1B1714)
    val background = if (dark) Color(0xFF000000) else Color(0xFFF2F1F6)
    val group = if (dark) Color(0xFF1C1C1E) else Color.White
    val ink = if (dark) Color.White else Color(0xFF111111)
    val gray = if (dark) Color(0xFF8E8E93) else Color(0xFF6D6D72)
    val placeholder = if (dark) Color(0xFF636366) else Color(0xFFB0B0B5)
    val divider = if (dark) Color(0xFF38383A) else Color(0xFFD8D8DC)
    val red = if (dark) Color(0xFFFF453A) else Color(0xFFFF3B30)
}

/**
 * A drawn app screen: a phone-like rounded frame around a grouped list — the bar (accent leading and
 * trailing buttons around a title), section headers, rows and footers.
 */
@Composable
internal fun GuideMock(mock: UserGuide.Mock, modifier: Modifier = Modifier) {
    val env = LocalGuide.current
    val colors = env.colors
    val m = MockColors(colors.dark)
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier.widthIn(max = 320.dp).fillMaxWidth().clip(shape).border(3.dp, m.frame, shape).background(m.background)
            .padding(horizontal = 10.dp, vertical = 14.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(mock.bar.leading, color = colors.accent, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterStart), maxLines = 1)
            Text(
                mock.bar.title, color = m.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
            )
            Text(
                mock.bar.trailing, color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterEnd), maxLines = 1,
            )
        }
        for (section in mock.sections) {
            section.header?.let {
                Text(
                    it.uppercase(Locale.getDefault()), color = m.gray, fontSize = 11.sp,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            if (section.header == null) Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(m.group)) {
                section.rows.forEachIndexed { index, row ->
                    if (index > 0) Box(Modifier.padding(start = 12.dp).fillMaxWidth().height(0.5.dp).background(m.divider))
                    MockRow(row, colors, m)
                }
            }
            section.footer?.let {
                Text(it, color = m.gray, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp))
            }
        }
    }
}

@Composable
private fun MockRow(row: UserGuide.Mock.Row, colors: GuideColors, m: MockColors) {
    val color = when (row.style) {
        UserGuide.Mock.Style.LINK -> colors.accent
        UserGuide.Mock.Style.FIELD -> m.placeholder
        UserGuide.Mock.Style.DESTRUCTIVE -> m.red
        UserGuide.Mock.Style.PLAIN -> m.ink
    }
    val highlight = if (row.highlight) {
        Modifier.padding(2.dp).clip(RoundedCornerShape(8.dp)).background(colors.accentSoft)
            .border(2.dp, colors.accent, RoundedCornerShape(8.dp))
    } else {
        Modifier
    }
    Row(
        Modifier.fillMaxWidth().then(highlight).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        row.icon?.let {
            Text(it, fontSize = 14.sp, modifier = Modifier.width(24.dp).clearAndSetSemantics {}, textAlign = TextAlign.Center)
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(row.text, color = if (row.highlight && row.style != UserGuide.Mock.Style.LINK) m.ink else color, fontSize = 14.sp)
            row.detail?.let { Text(it, color = m.gray, fontSize = 11.sp) }
        }
        if (row.checked) Text("✓", color = colors.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

// MARK: Cover

/** The cover: the print cover's warm gradient and glow, eyebrow, title, subtitle, and the phones as a fan. */
@Composable
internal fun GuideCover(cover: UserGuide.Cover, modifier: Modifier = Modifier) {
    val env = LocalGuide.current
    val dark = env.colors.dark
    val stops = if (dark) {
        arrayOf(0f to Color(0xFF4A3220), 0.38f to Color(0xFF5E3C1E), 1f to Color(0xFF2A1A0C))
    } else {
        arrayOf(0f to Color(0xFFFBE7C8), 0.38f to Color(0xFFF2C58F), 1f to Color(0xFFB86A2C))
    }
    val ink = if (dark) Color(0xFFF6E7D2) else Color(0xFF2B1A0C)
    val glow = if (dark) Color(0x40FFD9A0) else Color(0xF2FFF7E6)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(colorStops = stops))
            .drawBehind {
                val radius = size.width * 0.7f
                drawCircle(
                    Brush.radialGradient(listOf(glow, glow.copy(alpha = 0f)), center = Offset(size.width * 0.95f, size.height * 0.55f), radius = radius),
                    radius = radius, center = Offset(size.width * 0.95f, size.height * 0.55f),
                )
            }
            .padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 20.dp),
    ) {
        Text(
            cover.eyebrow.uppercase(Locale.getDefault()), color = ink.copy(alpha = 0.75f), fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 0.18.em,
        )
        Text(
            cover.title, color = ink, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        Text(cover.subtitle, color = ink.copy(alpha = 0.85f), fontSize = 17.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 8.dp))
        PhoneFan(cover.images, Modifier.padding(top = 24.dp))
        if (cover.edition.isNotEmpty()) {
            Text(cover.edition, color = ink.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

/** Up to three phones, the middle one larger and in front, the others tilted out behind it. */
@Composable
private fun PhoneFan(images: List<String>, modifier: Modifier) {
    val env = LocalGuide.current
    val shown = images.take(3)
    if (shown.isEmpty()) return
    val middle = 138.dp
    val side = 116.dp
    Box(modifier.fillMaxWidth().height(middle / PHONE_ASPECT + 8.dp), contentAlignment = Alignment.BottomCenter) {
        if (shown.size == 1) {
            GuideImage(env.copy.image(shown[0]), middle, PHONE_ASPECT, null)
            return@Box
        }
        val sides = listOf(shown[0] to -1) + (shown.getOrNull(2)?.let { listOf(it to 1) } ?: emptyList())
        for ((name, direction) in sides) {
            GuideImage(
                env.copy.image(name), side, PHONE_ASPECT, null,
                Modifier.offset(x = (direction * 92).dp, y = (-4).dp).rotate(direction * 7f),
            )
        }
        GuideImage(env.copy.image(shown[1]), middle, PHONE_ASPECT, null)
    }
}

/** Provides [env] to the blocks drawn inside [content]. */
@Composable
internal fun ProvideGuide(env: GuideEnv, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalGuide provides env, content = content)
