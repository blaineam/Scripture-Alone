package com.blainemiller.scripturealone.data.share

import com.blainemiller.scripturealone.data.reference.ReferenceDetector

/**
 * Text handed to the app from elsewhere — "Open in Scripture Alone" on a selection
 * (ACTION_PROCESS_TEXT) or Share › Scripture Alone (ACTION_SEND, text/plain) — turned into what to show.
 * Android-only: iOS has no equivalent.
 *
 * In order: one of the app's own links in the text; the references in it ([ReferenceDetector], every
 * language the app reads — "see John 3:16 and Rom 8:28" opens both); failing those, a search for the
 * words. Only ever something to look at — never a note, a favorite or Listen — since any app can send
 * text.
 */
object SharedText {
    /** Past this, the text is a passage of prose, not a search: its first [SEARCH_WORDS] words are searched. */
    const val MAX_SEARCH_LENGTH = 120
    const val SEARCH_WORDS = 8

    private val link = Regex("""(?:scripturealone://|https?://(?:www\.)?wemiller\.com/apps/scripture-alone)\S*""", RegexOption.IGNORE_CASE)

    fun command(text: String?): AppCommand? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        link.find(trimmed)?.value?.let { url ->
            val command = AppCommand.parse(url.trimEnd('.', ',', ')', ';', '"', '”', '’'))
            val readOnly = if (command?.needsTrust == true) command.readOnly else command
            if (readOnly != null) return readOnly
        }
        val passages = ReferenceDetector.detect(trimmed).map { it.passage }
        if (passages.isNotEmpty()) return AppCommand.Link(AppLink.Typed(passages))
        val words = if (trimmed.length <= MAX_SEARCH_LENGTH) trimmed.replace(Regex("""\s+"""), " ")
        else trimmed.split(Regex("""\s+""")).take(SEARCH_WORDS).joinToString(" ")
        return AppCommand.Link(AppLink.Search(words))
    }
}
