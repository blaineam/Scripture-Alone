package com.blainemiller.scripturealone.ui.share

import androidx.annotation.StringRes
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.share.SharePassageText
import com.blainemiller.scripturealone.data.share.ShareVerse
import com.blainemiller.scripturealone.text.AppText
import com.blainemiller.scripturealone.ui.reader.ReaderFontFamily
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A verse-card look — `ShareTemplate` in `ScriptureAlone/Share/ShareStyle.swift`. [raw] is the `tp`
 * name in share links, so the web and iOS render the same card; the colours are copied from Swift
 * (and `docs/share-links.md`), not re-tuned.
 */
enum class ShareTemplate(
    val raw: String,
    @StringRes private val titleRes: Int,
    /** Background, top to bottom. One colour = flat. */
    val background: List<Long>,
    val ink: Long,
    /** Reference, rule, verse numbers and wordmark. */
    val accent: Long,
    /** Words of Christ. */
    val red: Long,
) {
    PARCHMENT("parchment", R.string.share_template_parchment, listOf(0xF6EDD9, 0xEBDDBF), 0x3B2F20, 0x8A5A2B, 0xA12A1C),
    INK("ink", R.string.share_template_ink, listOf(0x14161A), 0xEDE8DF, 0xC9A45C, 0xFF7A6B),
    DAWN("dawn", R.string.share_template_dawn, listOf(0xF7D9C4, 0xEFB4A8, 0xA893CC), 0x2E2236, 0x6B4A6E, 0x9E1B32),
    NIGHT("night", R.string.share_template_night, listOf(0x0B1026, 0x1D2A57), 0xE9EDF8, 0xA9B8F0, 0xFF8A80),
    LINEN("linen", R.string.share_template_linen, listOf(0xF8F5EF), 0x2E2A25, 0x9C7A4E, 0xB0261B),
    STONE("stone", R.string.share_template_stone, listOf(0xDEDCD7, 0xC3C0B9), 0x26262A, 0x5A5A62, 0x9B2226),
    OLIVE("olive", R.string.share_template_olive, listOf(0x46512F, 0x2D3520), 0xF2EFDD, 0xD6C58C, 0xFFA48A),
    MINIMAL("minimal", R.string.share_template_minimal, listOf(0xFFFFFF), 0x111111, 0x6E6E6E, 0xC0392B);

    /** The template's name, as the designer shows it. */
    val title: String get() = AppText.get(titleRes)

    /** A hairline frame inset from the edge (the paper-like templates). */
    val hasFrame: Boolean get() = this == PARCHMENT || this == LINEN

    /** Light text on a dark ground. */
    val isDark: Boolean get() = this == INK || this == NIGHT || this == OLIVE

    companion object {
        fun fromRaw(raw: String?): ShareTemplate? = entries.firstOrNull { it.raw == raw }
    }
}

/** `ShareAspect`: layout size in points; the export renders at 2× (2160 px on the long side). */
enum class ShareAspect(val raw: String, @StringRes private val titleRes: Int, val width: Float, val height: Float) {
    SQUARE("square", R.string.share_aspect_square, 1080f, 1080f),
    STORY("story", R.string.share_aspect_story, 608f, 1080f),
    WIDE("wide", R.string.share_aspect_wide, 1080f, 608f);

    val title: String get() = AppText.get(titleRes)

    companion object {
        fun fromRaw(raw: String?): ShareAspect? = entries.firstOrNull { it.raw == raw }
    }
}

/** `ShareAlignment`. */
enum class ShareAlignment(val raw: String, @StringRes private val titleRes: Int) {
    LEADING("leading", R.string.share_alignment_leading), CENTER("center", R.string.share_alignment_center);

    val title: String get() = AppText.get(titleRes)

    companion object {
        fun fromRaw(raw: String?): ShareAlignment? = entries.firstOrNull { it.raw == raw }
    }
}

/** Everything the designer lets you change, as one value — `ShareStyle`. */
data class ShareStyle(
    val template: ShareTemplate = ShareTemplate.PARCHMENT,
    val aspect: ShareAspect = ShareAspect.SQUARE,
    val family: ReaderFontFamily = ReaderFontFamily.DEFAULT,
    val alignment: ShareAlignment = ShareAlignment.CENTER,
    val redLetters: Boolean = true,
    val verseNumbers: Boolean = true,
    val wordmark: Boolean = true,
)

/** What a card says, after fitting — `ShareCardContent`. */
data class ShareCardContent(
    val passage: SharePassageText,
    /** "John 3:16–17" */
    val reference: String,
    /** "ASV" */
    val translation: String,
    /** The translation's required notice (licensed translations only; public-domain texts need none). */
    val notice: String?,
    /** Point size of the passage text, chosen by [ShareCardFitter]. */
    val fontSize: Float,
)

/**
 * Proportions of a card, derived from its size so the three aspects share one design —
 * `ShareCardMetrics`, mirrored in `docs/share-links.md` for the web renderer.
 */
data class ShareCardMetrics(val width: Float, val height: Float) {
    constructor(aspect: ShareAspect) : this(aspect.width, aspect.height)

    val short: Float get() = min(width, height)
    val horizontalPadding: Float get() = width * 0.09f
    val verticalPadding: Float get() = height * 0.09f
    val referenceSize: Float get() = max(18f, short * 0.034f)
    val wordmarkSize: Float get() = max(14f, short * 0.024f)
    val ruleWidth: Float get() = referenceSize * 1.6f
    val textWidth: Float get() = width - horizontalPadding * 2
    /** Rule, gap and reference line under the passage. */
    val referenceBlock: Float get() = referenceSize * 3.6f
    val wordmarkBlock: Float get() = wordmarkSize * 2.4f
    val maxFontSize: Float get() = sqrt(width * height) * 0.066f
    val minFontSize: Float get() = max(20f, short * 0.022f)
    val lineSpacingRatio: Float get() = 0.28f

    fun textHeight(footer: Boolean): Float =
        height - verticalPadding * 2 - referenceBlock - (if (footer) wordmarkBlock else 0f)

    companion object {
        const val NUMBER_SCALE = 0.55f
        const val NUMBER_RISE = 0.32f
    }
}

/**
 * Chooses the largest type that fits, and trims long passages to whole verses when even the smallest
 * size can't hold them — `ShareCardFitter`. The measurement itself is a parameter ([measure]: the
 * wrapped height of a passage at a size, in card points), so the search is tested on the JVM and the
 * app measures with the same `StaticLayout` the card draws with.
 */
object ShareCardFitter {
    /** More than this many verses stops being a card and becomes a page. */
    const val MAX_VERSES = 12

    data class Result(val content: ShareCardContent, val shownVerses: Int, val totalVerses: Int) {
        val trimmed: Boolean get() = shownVerses < totalVerses
    }

    fun fit(
        verses: List<ShareVerse>,
        translation: String,
        notice: String?,
        style: ShareStyle,
        rangesOf: (List<ShareVerse>) -> List<VerseRange>,
        measure: (SharePassageText, Float) -> Float,
    ): Result {
        val metrics = ShareCardMetrics(style.aspect)
        val footer = style.wordmark || notice != null
        val height = metrics.textHeight(footer)
        var count = min(verses.size, MAX_VERSES)
        var chosen: Pair<SharePassageText, Float>? = null
        while (count > 0) {
            val passage = SharePassageText.of(verses.take(count), numbered = style.verseNumbers)
            val size = largestFittingSize(metrics, height) { measure(passage, it) }
            if (size != null) {
                chosen = passage to size
                break
            }
            if (count == 1) break
            count -= 1
        }
        // A single verse too long for the smallest size still renders; the card scales it down.
        val (passage, size) = chosen
            ?: (SharePassageText.of(verses.take(1), numbered = style.verseNumbers) to metrics.minFontSize)
        val shown = max(1, count)
        val reference = rangesOf(verses.take(shown)).joinToString(", ") { it.display }
        return Result(ShareCardContent(passage, reference, translation, notice, size), shown, verses.size)
    }

    /** The binary search of `largestFittingSize`: 12 halvings between the metrics' bounds, floored. */
    fun largestFittingSize(metrics: ShareCardMetrics, height: Float, heightAt: (Float) -> Float): Float? {
        // A little slack for the difference between measurement and drawing, as on iOS.
        fun fits(size: Float) = kotlin.math.ceil(heightAt(size)) <= height * 0.95f
        var low = metrics.minFontSize
        var high = metrics.maxFontSize
        if (!fits(low)) return null
        if (fits(high)) return high
        repeat(12) {
            val mid = (low + high) / 2
            if (fits(mid)) low = mid else high = mid
        }
        return floor(low)
    }

    /** Where a passage too long for one card is trimmed, said plainly — the designer's note. */
    fun trimNote(result: Result): String? = if (!result.trimmed) null else
        AppText.get(R.string.share_trim_note, result.shownVerses, result.totalVerses, result.content.reference)
}

/**
 * Verse numbers in a share link's text, found as `docs/share-links.md` tells the web to: the numbers
 * expected from `k`, in order, each matched as a token at the start of the text or after a space and
 * followed by a space. A verse that opens a new chapter is "c:v". Anything that doesn't match stays
 * plain text — a link written by another app, or one whose text doesn't number its verses, simply
 * shows no styled numbers.
 */
object ShareLinkNumbers {
    fun find(text: String, ranges: List<VerseRange>): List<IntRange> {
        val starts = ranges.map { it.start }
        if (starts.isEmpty()) return emptyList()
        // Only a passage of more than one verse is numbered.
        if (ranges.size == 1 && ranges[0].start == ranges[0].end) return emptyList()
        val found = mutableListOf<IntRange>()
        var position = 0
        var book = starts[0].book
        var chapter = starts[0].chapter
        var verse = starts[0].verse
        var nextRange = 1

        fun tokenAt(at: Int, token: String): Boolean =
            (at == 0 || text[at - 1] == ' ') && text.startsWith(token, at) &&
                at + token.length < text.length && text[at + token.length] == ' '

        // The first verse's number opens the text.
        if (!tokenAt(0, "$verse")) return emptyList()
        found += 0 until "$verse".length
        position = "$verse".length + 1
        while (position < text.length && found.size < 2_000) {
            // The candidates for the next number: the next verse, the next chapter's first, the next range's start.
            val candidates = buildList {
                add(Triple("${verse + 1}", chapter, verse + 1))
                add(Triple("${chapter + 1}:1", chapter + 1, 1))
                starts.getOrNull(nextRange)?.let { s ->
                    if (s.book == book && s.chapter == chapter) add(Triple("${s.verse}", s.chapter, s.verse))
                    else add(Triple("${s.chapter}:${s.verse}", s.chapter, s.verse))
                }
            }
            var matched: Pair<Int, Triple<String, Int, Int>>? = null
            var search = position
            while (search < text.length && matched == null) {
                val space = text.indexOf(' ', search)
                if (space < 0) break
                val at = space + 1
                for (candidate in candidates) {
                    if (tokenAt(at, candidate.first)) {
                        matched = at to candidate
                        break
                    }
                }
                search = at
            }
            val (at, candidate) = matched ?: break
            found += at until at + candidate.first.length
            val jumpedRange = starts.getOrNull(nextRange)?.let { it.chapter == candidate.second && it.verse == candidate.third } == true
            if (jumpedRange) {
                book = starts[nextRange].book
                nextRange += 1
            }
            chapter = candidate.second
            verse = candidate.third
            position = at + candidate.first.length + 1
        }
        return found
    }
}
