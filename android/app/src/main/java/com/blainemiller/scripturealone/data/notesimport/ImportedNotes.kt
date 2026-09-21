package com.blainemiller.scripturealone.data.notesimport

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef

/**
 * What any note import produces, whatever it was read from. Ported from
 * `ScriptureAloneCore/Import/ImportedNotes.swift`.
 *
 * One shape for every source, so the screen that shows a reader what was found, the code that writes
 * it into the library, and the de-duplication that makes a second import harmless are written once.
 * A new source is a new parser and nothing else.
 *
 * The lists are mutable because the parsers build one of these up as they read, as the Swift struct's
 * `var` arrays are.
 */
data class ImportedNotes(
    val highlights: MutableList<Highlight> = mutableListOf(),
    val verseNotes: MutableList<Note> = mutableListOf(),
    val journals: MutableList<Note> = mutableListOf(),
    val saved: MutableList<VerseRange> = mutableListOf(),
    /** Lines whose reference could not be resolved, verbatim, so they can be shown rather than lost. */
    val unresolved: MutableList<String> = mutableListOf(),
) {
    /** A highlight, reduced to what this app can hold: one verse, one of five colors. */
    data class Highlight(
        val verse: VerseRef,
        /** One of [highlightColorNames]: yellow, green, blue, pink, purple. */
        val color: String,
        /** The translation it was made in, where the source says. Kept for the summary only. */
        val translation: String? = null,
    )

    /** A note. [range] is null for an entry attached to no verse — a journal page, a sermon. */
    data class Note(
        val range: VerseRange?,
        val title: String,
        val body: String,
        val translation: String? = null,
    )

    val isEmpty: Boolean
        get() = highlights.isEmpty() && verseNotes.isEmpty() && journals.isEmpty() && saved.isEmpty()

    val total: Int get() = highlights.size + verseNotes.size + journals.size + saved.size

    companion object {
        /** The color names an import may produce — the highlight colors' raw values in the app. */
        val highlightColorNames: Set<String> = setOf("yellow", "green", "blue", "pink", "purple")
    }
}

/**
 * Why an import could not be read. Shared, because a reader's mistake is the same mistake whichever
 * file they picked.
 */
enum class NoteImportError(val description: String) {
    NOT_AN_ARCHIVE("That file isn't a zip archive."),
    NOT_A_LIFE_BIBLE_EXPORT(
        "That doesn't look like a Life Bible export. Look for LifeBibleData.zip, from " +
            "Settings → Advanced → Export your data.",
    ),
    NOTHING_TO_IMPORT("That export has no notes, highlights or saved verses in it."),
    NOTHING_RECOGNISED(
        "Nothing in that looked like a Bible reference. Each note needs to start with one — " +
            "“John 3:16”, say — so it can be attached to the right verse.",
    ),
}

/** Thrown by the importers; [error] says which of the reader-facing failures it was. */
class NoteImportException(val error: NoteImportError) : Exception(error.description)
