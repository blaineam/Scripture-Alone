package com.blainemiller.scripturealone.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.userdata.HighlightColor
import com.blainemiller.scripturealone.text.AppText
import kotlin.math.roundToInt

/**
 * How the chapter reads under TalkBack.
 *
 * iOS hands VoiceOver the whole chapter as one `UITextView`: the reader moves through it by line or
 * word with the rotor, and can't select a verse without sight. Here each verse is its own node — its
 * number and words, without the footnote letters — so swiping moves verse by verse, a double-tap
 * selects it as a tap does (bringing up the selection bar), a double-tap-and-hold extends the
 * selection to it, and its footnotes and note are custom actions. Book, chapter and section headings
 * are headings, so TalkBack's heading navigation jumps between sections.
 *
 * The verse nodes are invisible boxes laid over the paragraph's text where each verse is drawn; the
 * paragraph's own text is hidden from TalkBack so nothing is read twice. They carry semantics only —
 * no pointer input — so sighted taps still reach the text beneath.
 */
object ReaderAccessibility {

    /** One footnote letter in a verse: the letter as printed, the note, and where the letter is. */
    data class Footnote(val letter: String, val text: String, val offset: Int)

    /**
     * One verse's run of characters in a paragraph (a verse continued from the paragraph before is a
     * run too, unnumbered). [text] is what TalkBack reads.
     */
    data class VerseRun(
        val key: Int,
        val start: Int,
        val end: Int,
        val numbered: Boolean,
        val text: String,
        val footnotes: List<Footnote>,
        /** The notes on the verse, when its marker follows this run; and the marker's offset. */
        val noteIds: List<String>,
        val noteOffset: Int?,
    ) {
        val verse: Int get() = key % 1_000
    }

    private val leadingNumber = Regex("^\\d+ ")

    /** The verse runs of [paragraph], in reading order. Pure, so it is tested on the JVM. */
    fun runs(paragraph: RenderedParagraph): List<VerseRun> {
        val text = paragraph.text
        val footnotes = text.getStringAnnotations(ChapterRenderer.FOOTNOTE_TAG, 0, text.length)
        val markers = text.getStringAnnotations(ChapterRenderer.NOTE_TAG, 0, text.length)
        val numberedAt = paragraph.verses.map { it.offset }.toSet()
        return paragraph.verseSpans.map { span ->
            val own = footnotes.filter { it.start >= span.start && it.end <= span.end }
            val words = StringBuilder()
            var at = span.start
            for (note in own) {
                words.append(text.text, at, note.start)
                at = note.end
            }
            words.append(text.text, at, span.end)
            val numbered = span.start in numberedAt
            val clean = words.toString().let { if (numbered) it.replaceFirst(leadingNumber, "") else it }
                .replace(' ', ' ').replace(' ', ' ').trim()
            // The marker is appended right after the verse's last fragment, after a thin space.
            val marker = markers.firstOrNull { it.start in span.end..span.end + 1 }
            VerseRun(
                key = span.key, start = span.start, end = span.end, numbered = numbered, text = clean,
                footnotes = own.map { Footnote(text.text.substring(it.start, it.end), it.item, it.start) },
                noteIds = marker?.item?.split(',')?.filter { it.isNotEmpty() }.orEmpty(),
                noteOffset = marker?.start,
            )
        }
    }

    /** "Verse 16. For God so loved the world…"; a continued verse reads as its words alone. */
    fun label(run: VerseRun): String = if (run.numbered) AppText.get(R.string.reader_verse_label, run.verse, run.text) else run.text

    /** Selected is TalkBack's own state; the rest is said after the words. */
    fun state(key: Int, marks: VerseMarks, hasNote: Boolean): String? = buildList {
        HighlightColor.fromRaw(marks.highlights[key])?.let { add(AppText.get(highlightedState(it))) }
        if (hasNote) add(AppText.get(R.string.reader_state_has_note))
        if (marks.speaking == key) add(AppText.get(R.string.reader_state_being_read))
    }.takeIf { it.isNotEmpty() }?.joinToString(", ")

    /** Where the run is drawn, in the text's own coordinates: its lines, full width when it wraps. */
    fun bounds(run: VerseRun, layout: TextLayoutResult): Rect? {
        if (run.end <= run.start || run.end > layout.layoutInput.text.length) return null
        val first = layout.getLineForOffset(run.start)
        val last = layout.getLineForOffset(run.end - 1)
        val top = layout.getLineTop(first)
        val bottom = layout.getLineBottom(last)
        return if (first == last) {
            val a = layout.getBoundingBox(run.start)
            val b = layout.getBoundingBox(run.end - 1)
            Rect(minOf(a.left, b.left), top, maxOf(a.right, b.right), bottom)
        } else {
            Rect(0f, top, layout.size.width.toFloat(), bottom)
        }
    }
}

/**
 * The paragraph's verses as TalkBack nodes over its text (see [ReaderAccessibility]). [topInset] is the
 * space above the text inside the paragraph (its spacing before), in pixels.
 */
@Composable
internal fun VerseNodes(
    paragraph: RenderedParagraph,
    layout: TextLayoutResult?,
    topInset: Float,
    marks: VerseMarks,
    selectable: Boolean,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    onFootnote: (ReaderAccessibility.Footnote) -> Unit,
    onNotes: (List<String>, Int) -> Unit,
) {
    val text = layout ?: return
    val density = LocalDensity.current
    val selectVerse = stringResource(R.string.reader_action_select_verse)
    val deselectVerse = stringResource(R.string.reader_action_deselect_verse)
    val extendSelection = stringResource(R.string.reader_action_extend_selection)
    val showNote = stringResource(R.string.reader_action_show_note)
    val showNotes = stringResource(R.string.reader_action_show_notes)
    for (run in ReaderAccessibility.runs(paragraph)) {
        val box = ReaderAccessibility.bounds(run, text) ?: continue
        val selected = run.key in marks.selection
        val label = ReaderAccessibility.label(run)
        val state = ReaderAccessibility.state(run.key, marks, run.noteIds.isNotEmpty())
        Box(
            Modifier
                .offset { IntOffset(box.left.roundToInt(), (box.top + topInset).roundToInt()) }
                .size(with(density) { box.width.toDp() }, with(density) { box.height.toDp() })
                .semantics {
                    contentDescription = label
                    state?.let { stateDescription = it }
                    if (selectable) {
                        this.selected = selected
                        onClick(if (selected) deselectVerse else selectVerse) { onTap(run.key); true }
                        onLongClick(extendSelection) { onLongPress(run.key); true }
                    }
                    val actions = run.footnotes.map { note ->
                        CustomAccessibilityAction(AppText.get(R.string.reader_action_footnote, note.letter)) { onFootnote(note); true }
                    } + listOfNotNull(
                        run.noteOffset?.takeIf { run.noteIds.isNotEmpty() }?.let { at ->
                            CustomAccessibilityAction(if (run.noteIds.size == 1) showNote else showNotes) {
                                onNotes(run.noteIds, at); true
                            }
                        },
                    )
                    if (actions.isNotEmpty()) customActions = actions
                },
        )
    }
}
