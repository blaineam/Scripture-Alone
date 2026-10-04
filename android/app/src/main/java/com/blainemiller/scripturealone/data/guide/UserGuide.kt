package com.blainemiller.scripturealone.data.guide

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The User Guide as the app draws it — `UserGuide.swift` in ScriptureAloneCore: the structured form of
 * `docs/manual/content/<locale>.html`, written by `Tools/manual_json.py` (whose docstring is the
 * schema) and downloaded on demand as `UserGuide-<code>.zip` — `guide.json` plus `images/` — from the
 * `user-guide` GitHub release ([UserGuidePackage]).
 */
data class UserGuide(
    val schema: Int,
    val language: String,
    val title: String,
    val cover: Cover,
    /** "Contents", in the guide's language — the heading over the chapter list. */
    val contents: String,
    val chapters: List<Chapter>,
) {
    data class Cover(val eyebrow: String, val title: String, val subtitle: String, val edition: String, val images: List<String>)

    data class Chapter(val title: String, val summary: String, val lede: List<Run>, val blocks: List<Block>)

    /** A stretch of text with one set of marks. */
    data class Run(
        val text: String,
        val bold: Boolean = false,
        /** The name of a button, menu or setting in the app. */
        val ui: Boolean = false,
        val kbd: Boolean = false,
        val code: Boolean = false,
        val small: Boolean = false,
        val link: String? = null,
        val lineBreak: Boolean = false,
    )

    enum class Device { PHONE, WATCH }

    data class Figure(val image: String, val device: Device, val caption: String)

    /** A drawn app screen (a grouped list), used where a setup step needs a picture in any language. */
    data class Mock(val bar: Bar, val sections: List<Section>) {
        data class Bar(val leading: String, val title: String, val trailing: String)
        data class Section(val header: String?, val rows: List<Row>, val footer: String?)
        data class Row(
            val text: String,
            val style: Style,
            val detail: String? = null,
            val icon: String? = null,
            val checked: Boolean = false,
            val highlight: Boolean = false,
        )
        enum class Style { PLAIN, LINK, FIELD, DESTRUCTIVE }
    }

    enum class CalloutStyle { TIP, NOTE, WARN }

    sealed interface Block {
        data class Paragraph(val inline: List<Run>, val fine: Boolean = false) : Block
        data class Heading(val inline: List<Run>) : Block
        data class Bullets(val items: List<List<Run>>) : Block
        data class Steps(val items: List<List<Block>>) : Block
        data class Table(val header: List<List<Run>>, val rows: List<List<List<Run>>>) : Block
        data class Callout(val style: CalloutStyle, val label: String, val blocks: List<Block>) : Block
        data class FigureBlock(val figure: Figure) : Block
        data class Feature(val figure: Figure?, val mock: Mock?, val blocks: List<Block>, val flip: Boolean) : Block
        data class MockBlock(val mock: Mock) : Block
        /** A kind this build doesn't know, from a newer guide of the same schema: skipped. */
        data object Unknown : Block
    }

    /** Every image the guide names: the cover's, and each figure's, however deep. */
    val imageNames: Set<String>
        get() {
            val names = cover.images.toMutableSet()
            fun walk(blocks: List<Block>) {
                for (block in blocks) when (block) {
                    is Block.FigureBlock -> names += block.figure.image
                    is Block.Feature -> {
                        block.figure?.let { names += it.image }
                        walk(block.blocks)
                    }
                    is Block.Callout -> walk(block.blocks)
                    is Block.Steps -> block.items.forEach(::walk)
                    else -> Unit
                }
            }
            chapters.forEach { walk(it.blocks) }
            return names
        }

    companion object {
        /**
         * The newest schema this build can draw. A package with a higher number is refused, so an old
         * app never shows half a guide.
         */
        const val SUPPORTED_SCHEMA = 1

        /**
         * Decodes `guide.json`. A missing required field fails, as `JSONDecoder` does; a block of a
         * type this build doesn't know is [Block.Unknown]. A [schema][UserGuide.schema] above
         * [SUPPORTED_SCHEMA] is refused before anything else is read.
         */
        @Throws(GuideFormatException::class)
        fun decode(json: String): UserGuide {
            val root = try {
                Json.parseToJsonElement(json)
            } catch (e: IllegalArgumentException) {
                throw GuideFormatException("guide.json is not JSON: ${e.message}")
            }.obj("guide")
            val schema = root.int("schema")
            if (schema > SUPPORTED_SCHEMA) throw UnsupportedSchemaException(schema)
            val cover = root.req("cover").obj("cover")
            return UserGuide(
                schema = schema,
                language = root.string("language"),
                title = root.string("title"),
                cover = Cover(
                    eyebrow = cover.string("eyebrow"),
                    title = cover.string("title"),
                    subtitle = cover.string("subtitle"),
                    edition = cover.string("edition"),
                    images = cover.req("images").arr("images").map { it.str("image") },
                ),
                contents = root.string("contents"),
                chapters = root.req("chapters").arr("chapters").map { element ->
                    val chapter = element.obj("chapter")
                    Chapter(
                        title = chapter.string("title"),
                        summary = chapter.string("summary"),
                        lede = runs(chapter.req("lede")),
                        blocks = blocks(chapter.req("blocks")),
                    )
                },
            )
        }

        private fun runs(element: JsonElement): List<Run> = element.arr("inline").map { item ->
            val run = item.obj("run")
            Run(
                text = run.string("text"),
                bold = run.flag("bold"),
                ui = run.flag("ui"),
                kbd = run.flag("kbd"),
                code = run.flag("code"),
                small = run.flag("small"),
                link = run.optString("link"),
                lineBreak = run.flag("br"),
            )
        }

        private fun blocks(element: JsonElement): List<Block> = element.arr("blocks").map(::block)

        private fun block(element: JsonElement): Block {
            val o = element.obj("block")
            return when (o.string("type")) {
                "paragraph" -> Block.Paragraph(runs(o.req("inline")), fine = o.flag("fine"))
                "heading" -> Block.Heading(runs(o.req("inline")))
                "list" -> Block.Bullets(o.req("items").arr("items").map(::runs))
                "steps" -> Block.Steps(o.req("items").arr("items").map(::blocks))
                "table" -> Block.Table(
                    header = o.opt("header")?.arr("header")?.map(::runs).orEmpty(),
                    rows = o.req("rows").arr("rows").map { row -> row.arr("row").map(::runs) },
                )
                "callout" -> Block.Callout(
                    style = when (val style = o.string("style")) {
                        "tip" -> CalloutStyle.TIP
                        "note" -> CalloutStyle.NOTE
                        "warn" -> CalloutStyle.WARN
                        else -> throw GuideFormatException("unknown callout style \"$style\"")
                    },
                    label = o.optString("label").orEmpty(),
                    blocks = blocks(o.req("blocks")),
                )
                "figure" -> Block.FigureBlock(figure(o))
                "feature" -> Block.Feature(
                    figure = o.opt("figure")?.let { figure(it.obj("figure")) },
                    mock = o.opt("mock")?.let { mock(it.obj("mock")) },
                    blocks = blocks(o.req("blocks")),
                    flip = o.flag("flip"),
                )
                "mock" -> Block.MockBlock(mock(o))
                else -> Block.Unknown
            }
        }

        private fun figure(o: JsonObject) = Figure(
            image = o.string("image"),
            device = when (val device = o.string("device")) {
                "phone" -> Device.PHONE
                "watch" -> Device.WATCH
                else -> throw GuideFormatException("unknown device \"$device\"")
            },
            caption = o.string("caption"),
        )

        private fun mock(o: JsonObject): Mock {
            val bar = o.req("bar").obj("bar")
            return Mock(
                bar = Mock.Bar(bar.string("leading"), bar.string("title"), bar.string("trailing")),
                sections = o.req("sections").arr("sections").map { element ->
                    val section = element.obj("section")
                    Mock.Section(
                        header = section.optString("header"),
                        rows = section.req("rows").arr("rows").map { rowElement ->
                            val row = rowElement.obj("row")
                            Mock.Row(
                                text = row.string("text"),
                                style = when (val style = row.string("style")) {
                                    "plain" -> Mock.Style.PLAIN
                                    "link" -> Mock.Style.LINK
                                    "field" -> Mock.Style.FIELD
                                    "destructive" -> Mock.Style.DESTRUCTIVE
                                    else -> throw GuideFormatException("unknown row style \"$style\"")
                                },
                                detail = row.optString("detail"),
                                icon = row.optString("icon"),
                                checked = row.flag("checked"),
                                highlight = row.flag("highlight"),
                            )
                        },
                        footer = section.optString("footer"),
                    )
                },
            )
        }

        // The JSON tree, read strictly: a wrong shape is a GuideFormatException, never a crash.
        private fun JsonElement.obj(what: String): JsonObject =
            this as? JsonObject ?: throw GuideFormatException("$what is not an object")

        private fun JsonElement.arr(what: String): JsonArray =
            this as? JsonArray ?: throw GuideFormatException("$what is not an array")

        private fun JsonElement.str(what: String): String =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw GuideFormatException("$what is not a string")

        private fun JsonObject.opt(key: String): JsonElement? = this[key]?.takeIf { it !is JsonNull }

        private fun JsonObject.req(key: String): JsonElement = opt(key) ?: throw GuideFormatException("missing \"$key\"")

        private fun JsonObject.string(key: String): String = req(key).str(key)

        private fun JsonObject.optString(key: String): String? = opt(key)?.str(key)

        private fun JsonObject.int(key: String): Int =
            (req(key) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw GuideFormatException("\"$key\" is not a number")

        private fun JsonObject.flag(key: String): Boolean {
            val value = opt(key) ?: return false
            return (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: throw GuideFormatException("\"$key\" is not true or false")
        }
    }
}

/** A `guide.json` (or package) this build can't read. */
open class GuideFormatException(message: String) : Exception(message)

/** A guide written for a newer app — `PackageError.unsupportedSchema`. */
class UnsupportedSchemaException(val schema: Int) : GuideFormatException("guide schema $schema is newer than this app reads")
