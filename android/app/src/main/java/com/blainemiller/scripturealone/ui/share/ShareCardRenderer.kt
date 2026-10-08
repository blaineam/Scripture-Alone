package com.blainemiller.scripturealone.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import com.blainemiller.scripturealone.data.share.SharePassageText
import com.blainemiller.scripturealone.ui.reader.ReaderFontFamily
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws a verse card — `ShareCard.swift` — onto an `android.graphics.Canvas`, in card points
 * ([ShareCardMetrics]; 1080 on the long side). One drawing serves three uses: the designer's
 * preview (scaled into a Compose canvas), the exported PNG (at [EXPORT_SCALE]), and the card a share
 * link opens. The passage is measured with the same `StaticLayout` it is drawn with, so the fitter's
 * size is the size on the card.
 */
class ShareCardRenderer(private val context: Context) {

    private data class FaceKey(val family: ReaderFontFamily, val bold: Boolean, val italic: Boolean)
    private val faces = ConcurrentHashMap<FaceKey, Typeface>()

    private fun face(family: ReaderFontFamily, bold: Boolean = false, italic: Boolean = false): Typeface =
        faces.getOrPut(FaceKey(family, bold, italic)) { family.typeface(context, bold, italic) }

    /** The passage's wrapped height at [size] — the fitter's measurement. */
    fun passageHeight(passage: SharePassageText, size: Float, style: ShareStyle): Float =
        passageLayout(passage, size, style, ShareCardMetrics(style.aspect).textWidth, colors = null).height.toFloat()

    /** Fits [source] to [style]: the largest size that holds it, trimming whole verses if none does. */
    fun fit(source: ShareSource, style: ShareStyle): ShareCardFitter.Result = ShareCardFitter.fit(
        verses = source.verses, translation = source.translation, notice = source.cardNotice, style = style,
        rangesOf = source::rangesOf, measure = { passage, size -> passageHeight(passage, size, style) },
    )

    /**
     * Draws the card. The ground is [backdrop] (drawn ahead by [ShareBackdrop.bitmap]) stretched over
     * the card, or — with [pixelsPerPoint] — painted straight onto this canvas, or else its flat
     * colours. [colors] are the legible ones `ShareContrast.resolve` chose for the measured ground.
     */
    fun draw(
        canvas: Canvas,
        content: ShareCardContent,
        style: ShareStyle,
        colors: ShareColors = style.colors(ShareBackdrop.stats(style.background)),
        backdrop: Bitmap? = null,
        pixelsPerPoint: Float? = null,
    ) {
        val metrics = ShareCardMetrics(style.aspect)
        val background = style.background
        val w = metrics.width
        val h = metrics.height

        when {
            backdrop != null -> canvas.drawBitmap(backdrop, null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            pixelsPerPoint != null -> ShareBackdrop.draw(background, canvas, w, h, pixelsPerPoint)
            else -> {
                // Flat, or a top-to-bottom gradient through the stops.
                val ground = Paint(Paint.ANTI_ALIAS_FLAG)
                if (background.colors.size == 1) {
                    ground.color = argb(background.colors[0])
                } else {
                    val stops = background.colors.indices.map { it / (background.colors.size - 1f) }.toFloatArray()
                    ground.shader = LinearGradient(0f, 0f, 0f, h, background.colors.map(::argb).toIntArray(), stops, Shader.TileMode.CLAMP)
                }
                canvas.drawRect(0f, 0f, w, h, ground)
            }
        }

        if (background.hasFrame) {
            // `strokeBorder` inside a rectangle inset by 3.5% of the short side.
            val inset = metrics.short * 0.035f
            val line = 1.5f
            val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                setStyle(Paint.Style.STROKE)
                strokeWidth = line
                color = argb(colors.accent, 0.35f)
            }
            canvas.drawRect(inset + line / 2, inset + line / 2, w - inset - line / 2, h - inset - line / 2, frame)
        }

        val footer = style.wordmark || content.notice != null
        val left = metrics.horizontalPadding
        val width = metrics.textWidth
        val top = metrics.verticalPadding
        val bottom = h - metrics.verticalPadding

        // The footer sits at the bottom inside the padding; passage and reference are centred above it.
        val footerLayouts = if (footer) footerLayouts(content, style, metrics, width, colors) else emptyList()
        val footerHeight = if (footer) {
            max(metrics.wordmarkBlock, footerLayouts.sumOf { it.height.toDouble() }.toFloat() + FOOTER_SPACING * (footerLayouts.size - 1).coerceAtLeast(0))
        } else {
            0f
        }

        var passage = passageLayout(content.passage, content.fontSize, style, width, colors)
        val reference = referenceLayout(content, style, metrics, width, colors)
        val referenceBlock = metrics.referenceSize * 1.2f + ruleThickness(metrics) + metrics.referenceSize * 0.9f + reference.height
        val available = bottom - top - footerHeight
        // `minimumScaleFactor(0.6)`: a single verse too long even at the smallest size is drawn smaller.
        if (passage.height + referenceBlock > available) {
            val target = (available - referenceBlock).coerceAtLeast(1f)
            var size = content.fontSize
            while (size > content.fontSize * 0.6f) {
                size *= 0.96f
                passage = passageLayout(content.passage, size, style, width, colors)
                if (passage.height <= target) break
            }
        }
        val groupHeight = passage.height + referenceBlock
        var y = top + ((available - groupHeight) / 2f).coerceAtLeast(0f)
        canvas.save()
        canvas.translate(left, y)
        drawShadowed(canvas, passage, colors, content.fontSize)
        canvas.restore()
        y += passage.height

        // The rule: a capsule, accent at 70%.
        y += metrics.referenceSize * 1.2f
        val ruleHeight = ruleThickness(metrics)
        val ruleLeft = if (style.alignment == ShareAlignment.CENTER) left + (width - metrics.ruleWidth) / 2 else left
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb(colors.accent, 0.7f) }
        canvas.drawRoundRect(RectF(ruleLeft, y, ruleLeft + metrics.ruleWidth, y + ruleHeight), ruleHeight / 2, ruleHeight / 2, rule)
        y += ruleHeight + metrics.referenceSize * 0.9f

        canvas.save()
        canvas.translate(left, y)
        drawShadowed(canvas, reference, colors, metrics.referenceSize)
        canvas.restore()

        if (footer) {
            var fy = bottom - footerLayouts.sumOf { it.height.toDouble() }.toFloat() - FOOTER_SPACING * (footerLayouts.size - 1)
            for (layout in footerLayouts) {
                canvas.save()
                canvas.translate(left, fy)
                drawShadowed(canvas, layout, colors, metrics.wordmarkSize)
                canvas.restore()
                fy += layout.height + FOOTER_SPACING
            }
        }
    }

    /**
     * The card as a bitmap at [scale] — 2× for export (2160 px on the long side). Drawn straight at
     * that output size, so there is one bitmap and no scaled copy; the caller recycles it once written.
     */
    fun bitmap(content: ShareCardContent, style: ShareStyle, scale: Float = EXPORT_SCALE): Bitmap {
        val (w, h) = outputSize(style.aspect, scale)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        // The ground painted at the output size, so grain and weave land one per output pixel.
        draw(canvas, content, style, pixelsPerPoint = scale)
        return bitmap
    }

    /**
     * Draws [layout] with the card's text shadow (`ShareShadow.layers`): one layer for Soft, the wide
     * one then the tight one for Strong.
     */
    private fun drawShadowed(canvas: Canvas, layout: StaticLayout, colors: ShareColors, size: Float) {
        val layers = colors.shadow.layers(size, colors.glow)
        if (layers.isEmpty()) {
            layout.draw(canvas)
            return
        }
        val paint = layout.paint
        for (layer in layers.reversed()) {
            paint.setShadowLayer(max(0.5f, layer.radius), 0f, layer.y, argb(colors.shadowColor, layer.opacity))
            layout.draw(canvas)
        }
        paint.clearShadowLayer()
    }

    private fun ruleThickness(metrics: ShareCardMetrics) = max(2f, metrics.referenceSize * 0.08f)

    private fun alignment(style: ShareStyle) =
        if (style.alignment == ShareAlignment.CENTER) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL

    private fun passageLayout(passage: SharePassageText, size: Float, style: ShareStyle, width: Float, colors: ShareColors?): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face(style.family)
            textSize = size
            fontVariationSettings = style.family.variationSettings(size)
            color = argb(colors?.ink ?: 0)
        }
        val text = SpannableString(passage.text)
        if (colors != null && style.redLetters) {
            for (range in passage.red) {
                if (range.first < 0 || range.last >= text.length) continue
                text.setSpan(ForegroundColorSpan(argb(colors.red)), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        for (range in passage.numbers) {
            if (range.first < 0 || range.last >= text.length) continue
            val end = range.last + 1
            text.setSpan(AbsoluteSizeSpan((size * ShareCardMetrics.NUMBER_SCALE).roundToInt()), range.first, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(RiseSpan(size * ShareCardMetrics.NUMBER_RISE), range.first, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (colors != null) text.setSpan(ForegroundColorSpan(argb(colors.accent)), range.first, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, ceil(width).toInt())
            .setAlignment(alignment(style))
            .setLineSpacing(size * LINE_SPACING_RATIO, 1f)
            .setIncludePad(false)
            .build()
    }

    /** "JOHN 3:16  ·  ASV": bold, tracked 0.12 em, accent; two lines at most, shrunk to fit as iOS's 0.5 scale factor. */
    private fun referenceLayout(content: ShareCardContent, style: ShareStyle, metrics: ShareCardMetrics, width: Float, colors: ShareColors): StaticLayout {
        val text = "${content.reference.uppercase()}  ·  ${content.translation}"
        var size = metrics.referenceSize
        while (true) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = face(style.family, bold = true)
                textSize = size
                fontVariationSettings = style.family.variationSettings(size, bold = true)
                letterSpacing = 0.12f
                color = argb(colors.accent)
            }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, ceil(width).toInt())
                .setAlignment(alignment(style))
                .setIncludePad(false)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            val fits = layout.lineCount <= 2 && (0 until layout.lineCount).none { layout.getEllipsisCount(it) > 0 }
            if (fits || size <= metrics.referenceSize * 0.5f) return layout
            size *= 0.92f
        }
    }

    private fun footerLayouts(content: ShareCardContent, style: ShareStyle, metrics: ShareCardMetrics, width: Float, colors: ShareColors): List<StaticLayout> {
        val layouts = mutableListOf<StaticLayout>()
        content.notice?.let { notice ->
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.DEFAULT
                textSize = metrics.wordmarkSize * 0.7f
                color = argb(colors.ink, 0.7f)
            }
            layouts += StaticLayout.Builder.obtain(notice, 0, notice.length, paint, ceil(width).toInt())
                .setAlignment(alignment(style)).setIncludePad(false).setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END).build()
        }
        if (style.wordmark) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = face(style.family, italic = true)
                textSize = metrics.wordmarkSize
                fontVariationSettings = style.family.variationSettings(metrics.wordmarkSize)
                color = argb(colors.accent, 0.75f)
            }
            layouts += StaticLayout.Builder.obtain(WORDMARK, 0, WORDMARK.length, paint, ceil(width).toInt())
                .setAlignment(alignment(style)).setIncludePad(false).build()
        }
        return layouts
    }

    /** Raises a run by [rise] points — the verse numbers' `baselineOffset`. */
    private class RiseSpan(private val rise: Float) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) {
            tp.baselineShift -= rise.roundToInt()
        }

        override fun updateMeasureState(tp: TextPaint) {
            tp.baselineShift -= rise.roundToInt()
        }
    }

    companion object {
        /** Cards are laid out 1080 pt on the long side; exported at 2× — 2160 px. */
        const val EXPORT_SCALE = 2f

        /** The exported card's pixels: the aspect's card points at [scale] — 2160×2160, 1216×2160, 2160×1216. */
        fun outputSize(aspect: ShareAspect, scale: Float = EXPORT_SCALE): Pair<Int, Int> =
            (aspect.width * scale).roundToInt() to (aspect.height * scale).roundToInt()
        const val WORDMARK = "Scripture Alone"
        private const val FOOTER_SPACING = 4f
        private val LINE_SPACING_RATIO = ShareCardMetrics(ShareAspect.SQUARE).lineSpacingRatio

        fun argb(rgb: Long, alpha: Float = 1f): Int =
            ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (rgb and 0xFFFFFF).toInt()
    }
}
