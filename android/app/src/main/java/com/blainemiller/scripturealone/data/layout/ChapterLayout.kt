package com.blainemiller.scripturealone.data.layout

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * One chapter as the reader lays it out: headings, prose paragraphs and poetry lines, each carrying
 * verse fragments. Decoded from the compact `chapters.layout` JSON the build tool writes — the same
 * string on both platforms — so this mirrors `ScriptureAloneCore/ChapterLayout.swift` field for field.
 *
 *     {"b": [{"k": "p", "t"?: heading text, "f"?: [{"v": 3, "n"?: 1, "t": "…",
 *                                                  "s"?: [[start, length, "r"|"i"|"c"]],
 *                                                  "fn"?: [[position, "note"]]}]}]}
 *
 * Span starts, lengths and footnote positions are **Unicode scalar** counts, exactly as stored. They
 * are deliberately not converted here: the conversion to UTF-16 happens once, where an
 * `AnnotatedString` is built (see [utf16Offset]), so no layer holds two meanings of "offset".
 */
data class ChapterLayout(val blocks: List<Block>) {

    data class Block(val kind: Kind, val text: String? = null, val fragments: List<Fragment> = emptyList())

    /** The USFM paragraph and heading markers the build tool keeps. Unknown markers are skipped, not fatal. */
    enum class Kind(val code: String) {
        HEADING("s1"), SUBHEADING("s2"), MAJOR_SECTION("ms"), PARALLEL("r"), ACROSTIC("qa"),
        PARAGRAPH("p"), CONTINUATION("m"), EMBEDDED("pmo"), CENTERED("pc"),
        LIST1("li1"), LIST2("li2"), POETRY1("q1"), POETRY2("q2"), SELAH("qr"),
        TITLE("d"), STANZA_BREAK("b"),
        UNKNOWN("");

        val isHeading: Boolean get() = this in HEADINGS
        val isPoetry: Boolean get() = this == POETRY1 || this == POETRY2 || this == SELAH

        companion object {
            private val HEADINGS = setOf(HEADING, SUBHEADING, MAJOR_SECTION, PARALLEL, ACROSTIC)
            private val byCode = entries.filter { it != UNKNOWN }.associateBy { it.code }

            /** As Swift's `Kind(rawValue:) ?? .unknown`: a marker a newer build tool adds is ignored. */
            fun of(code: String): Kind = byCode[code] ?: UNKNOWN
        }
    }

    data class Fragment(
        val verse: Int,
        /** The verse number is printed at the start of this fragment. */
        val numbered: Boolean,
        val text: String,
        val spans: List<Span> = emptyList(),
        val footnotes: List<Footnote> = emptyList(),
    )

    /** [start] and [length] in Unicode scalars. [style] is null for a style this build doesn't know. */
    data class Span(val start: Int, val length: Int, val style: Style?) {
        enum class Style(val code: String) {
            WORDS_OF_CHRIST("r"), SUPPLIED("i"), SMALL_CAPS("c");

            companion object {
                fun of(code: String): Style? = entries.firstOrNull { it.code == code }
            }
        }
    }

    /** [position] is the Unicode-scalar offset where the marker sits. */
    data class Footnote(val position: Int, val text: String)

    companion object {
        /**
         * Decodes a layout string. Throws [LayoutFormatException] where Swift's `JSONDecoder` would
         * throw — a missing verse number or text, a span that isn't `[Int, Int, String]` — so a chapter
         * that fails on one platform fails on the other instead of rendering differently.
         */
        fun parse(json: String): ChapterLayout {
            val root = try {
                Json.parseToJsonElement(json)
            } catch (e: IllegalArgumentException) {
                throw LayoutFormatException("not JSON", e)
            }
            val blocks = root.asObject("layout").requireField("b").asArray("b").map(::block)
            return ChapterLayout(blocks)
        }

        /**
         * A layout for text that arrived without structure: one paragraph, every verse numbered —
         * `ChapterLayout.prose` on iOS, for sources that carry verses but no layout.
         */
        fun prose(verses: List<Pair<Int, String>>): ChapterLayout =
            ChapterLayout(listOf(Block(Kind.PARAGRAPH, fragments = verses.map { (v, t) -> Fragment(v, true, t) })))

        private fun block(element: JsonElement): Block {
            val o = element.asObject("block")
            val kind = Kind.of(o.requireField("k").asString("k"))
            val text = o.optional("t")?.asString("t")
            val fragments = o.optional("f")?.asArray("f")?.map(::fragment) ?: emptyList()
            return Block(kind, text, fragments)
        }

        private fun fragment(element: JsonElement): Fragment {
            val o = element.asObject("fragment")
            return Fragment(
                verse = o.requireField("v").asInt("v"),
                numbered = (o.optional("n")?.asInt("n") ?: 0) == 1,
                text = o.requireField("t").asString("t"),
                spans = o.optional("s")?.asArray("s")?.map(::span) ?: emptyList(),
                footnotes = o.optional("fn")?.asArray("fn")?.map(::footnote) ?: emptyList(),
            )
        }

        private fun span(element: JsonElement): Span {
            val a = element.asArray("span")
            if (a.size < 3) throw LayoutFormatException("a span has ${a.size} members, not 3")
            return Span(a[0].asInt("span start"), a[1].asInt("span length"), Span.Style.of(a[2].asString("span style")))
        }

        private fun footnote(element: JsonElement): Footnote {
            val a = element.asArray("footnote")
            if (a.size < 2) throw LayoutFormatException("a footnote has ${a.size} members, not 2")
            return Footnote(a[0].asInt("footnote position"), a[1].asString("footnote text"))
        }

        private fun JsonElement.asObject(what: String): JsonObject =
            this as? JsonObject ?: throw LayoutFormatException("$what is not an object")

        private fun JsonElement.asArray(what: String): JsonArray =
            this as? JsonArray ?: throw LayoutFormatException("$what is not an array")

        private fun JsonElement.asString(what: String): String =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw LayoutFormatException("$what is not a string")

        private fun JsonElement.asInt(what: String): Int =
            (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                ?: throw LayoutFormatException("$what is not an integer")

        private fun JsonObject.requireField(name: String): JsonElement =
            this[name]?.takeIf { it !is JsonNull } ?: throw LayoutFormatException("missing \"$name\"")

        /** `decodeIfPresent`: an absent key and an explicit null both mean "not there". */
        private fun JsonObject.optional(name: String): JsonElement? = this[name]?.takeIf { it !is JsonNull }
    }
}

class LayoutFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
