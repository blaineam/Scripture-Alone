package com.blainemiller.scripturealone.data.listen

/**
 * Speech recognition writes "John 3:16" as "John 3 16", "John 3 colon 16" or "John chapter 3 verse 16 through 21".
 * This puts the punctuation back so the reference parser can read it (the counterpart of iOS `SpokenReference`).
 * English words; other languages' speech usually gives digits and the parser's own separators.
 */
object SpokenReference {
    /** True when the words ask for the day's reading in the reader's plan rather than a passage. */
    fun asksForToday(heard: String): Boolean {
        val lower = heard.lowercase()
        return "today" in lower || "reading plan" in lower || "daily reading" in lower
    }

    fun normalize(heard: String): String {
        fun isNumber(s: String) = s.isNotEmpty() && s.all(Char::isDigit)
        val words = heard.lowercase().replace(".", " . ").replace(":", " : ").replace("-", " - ")
            .split(" ").filter { it.isNotEmpty() && it != "chapter" && it != "chapters" }
        val tokens = mutableListOf<String>()
        words.forEachIndexed { i, word ->
            val between = i > 0 && i + 1 < words.size && isNumber(words[i - 1]) && isNumber(words[i + 1])
            when (word) {
                "colon", "verse", "verses" -> tokens += ":"
                "." -> if (between) tokens += ":"
                "through", "thru", "to", "until", "til" -> tokens += if (between) "-" else word
                else -> tokens += word
            }
        }
        fun joined(list: List<String>) = list.joinToString(" ").replace(" : ", ":").replace(" - ", "-")
        var start = tokens.size
        while (start > 0 && (isNumber(tokens[start - 1]) || tokens[start - 1] == "-")) start--
        if (start == 0) return joined(tokens)
        val tail = tokens.subList(start, tokens.size)
        val dash = tail.indexOf("-")
        val left = if (dash < 0) tail else tail.subList(0, dash)
        val right = if (dash < 0) emptyList() else tail.subList(dash + 1, tail.size)
        if ((left.size != 2 && left.size != 3) || right.size > 1) return joined(tokens)
        val verses = if (left.size == 3) "${left[1]}-${left[2]}" else left[1]
        val end = if (right.size == 1) "-${right[0]}" else ""
        return joined(tokens.subList(0, start) + "${left[0]}:$verses$end")
    }
}
