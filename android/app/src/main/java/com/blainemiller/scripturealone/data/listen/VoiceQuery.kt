package com.blainemiller.scripturealone.data.listen

/**
 * What a car's assistant says it heard. "Hey Google, read John chapter 1 in Scripture Alone" reaches the media session as a
 * play-from-search request: the words in the search query and, when the assistant understood some of it as a kind of media,
 * the same words again in the structured extras (`EXTRA_MEDIA_TITLE`, `…_ALBUM`, `…_PLAYLIST`, `…_ARTIST`). Which one carries
 * the reference varies — a book is an "album" to one phrase and a "title" to another — so every non-blank one is kept, in that
 * order, and [SpokenReference.plan] reads them until one is a passage.
 */
object VoiceQuery {
    /** The extras a play-from-search request may carry its words in, after the search query itself. */
    val extraKeys = listOf(
        "android.intent.extra.title", "android.intent.extra.album", "android.intent.extra.playlist", "android.intent.extra.artist",
    )

    /** An unprintable separator, so a phrase can hold any punctuation (a media id is only text). */
    private const val SEPARATOR = '\u001F'

    /** The distinct non-blank phrases: the search query first, then the extras in [extraKeys] order. */
    fun candidates(query: String?, extras: Map<String, String?>): List<String> =
        (listOf(query) + extraKeys.map { extras[it] }).mapNotNull { it?.trim() }.filter(String::isNotEmpty).distinct()

    fun encode(phrases: List<String>): String = phrases.joinToString(SEPARATOR.toString())

    fun decode(encoded: String): List<String> = encoded.split(SEPARATOR).map(String::trim).filter(String::isNotEmpty)
}
