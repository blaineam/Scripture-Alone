package com.blainemiller.scripturealone.ui.share

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.rights.TranslationRights
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.share.SharePassageText
import com.blainemiller.scripturealone.data.share.ShareLinkPayload
import com.blainemiller.scripturealone.data.share.ShareVerse
import com.blainemiller.scripturealone.data.userdata.Selection

/**
 * A passage to share, read from one translation — `ShareSource` in `ShareSupport.swift`.
 *
 * [rights] are the translation's terms, asked before an image is offered at all (see [imagesAllowed])
 * and before a link is made ([linksAllowed]). [linkStyle] is a share link's template, typeface and
 * aspect, which become the designer's starting point when a link opened it.
 */
data class ShareSource(
    val ranges: List<VerseRange>,
    val verses: List<ShareVerse>,
    /** "ASV" — shown after the reference. */
    val translation: String,
    /** The translation's required notice; null for public-domain texts. */
    val notice: String?,
    val rights: TranslationRights,
    private val verseCount: (ChapterRef) -> Int,
    val linkStyle: ShareLinkPayload? = null,
    /**
     * [ranges] as the translation numbers them, for the reference printed on the card. [ranges] are KJV
     * keys — what a link carries — and differ only for a Bible with its own numbering (Louis Segond's
     * Psalm 51:12 is the KJV's 51:10; see `VerseNumbering`).
     */
    val displayRanges: List<VerseRange> = ranges,
) {
    val reference: String get() = displayRanges.joinToString(", ") { it.display }

    val hasRed: Boolean get() = verses.any { it.red.isNotEmpty() }

    /** The verse ranges of the first [shown] verses — the card's reference once the fitter trims. */
    fun rangesOf(shown: List<ShareVerse>): List<VerseRange> = Selection.ranges(shown.map { it.ref.key }, verseCount)

    /**
     * Whether an image of this passage may be made: the translation must permit verse images and
     * the passage must be within its quotation limit — the same `permits`/`mayQuote` pair iOS asks.
     */
    val imagesAllowed: Boolean
        get() = rights.permits(TranslationRights.Permission.VERSE_IMAGES) && rights.mayQuote(verses.size)

    /**
     * Links carry the text itself, so — as on iOS — they're offered only for texts that need no
     * notice (public domain) and whose terms permit sharing at all.
     */
    val linksAllowed: Boolean
        get() = notice == null && rights.permits(TranslationRights.Permission.SHARE) && rights.mayQuote(verses.size)

    /** The share link for the whole passage with the designer's choices, or null (not allowed, or too long). */
    fun link(style: ShareStyle): String? {
        if (!linksAllowed) return null
        var passage = SharePassageText.of(verses)
        if (!style.redLetters) passage = passage.copy(red = emptyList())
        val payload = ShareLinkPayload.of(
            ranges, reference, translation, passage,
            template = style.template.raw, font = style.family.shareToken, aspect = style.aspect.raw,
        )
        return if (payload.fitsInLink) payload.webUrl() else null
    }

    /** "John 3.16-17 ASV.png" — colons and dashes file systems dislike swapped out, as `ShareRenderer.filename`. */
    fun filename(content: ShareCardContent): String {
        val reference = content.reference.replace(":", ".").replace("–", "-").replace("/", "-")
        return "$reference ${content.translation}.png"
    }
}
