package com.blainemiller.scripturealone.data

import android.content.Context
import com.blainemiller.scripturealone.data.layout.ChapterLayout
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.sabible.ContentKey
import com.blainemiller.scripturealone.data.sabible.PublisherKeyring
import com.blainemiller.scripturealone.data.sabible.ScalarRange
import com.blainemiller.scripturealone.data.sabible.TranslationPackage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Who a translation is, as the reader shows it: the id in the switcher, the copyright in the footer. */
data class TranslationInfo(val id: String, val name: String, val abbreviation: String, val copyright: String)

/** One verse's text and its words-of-Christ ranges, still in **Unicode scalars** as stored. */
data class ChapterVerse(val ref: VerseRef, val text: String, val red: List<ScalarRange>)

/**
 * One chapter, ready to render: its parsed layout, plus the plain verse rows the layout was built
 * from (for copy, search and Listen later — the renderer itself draws only from [layout], as on iOS).
 */
data class Chapter(
    val translation: TranslationInfo,
    val ref: ChapterRef,
    val layout: ChapterLayout,
    val verses: List<ChapterVerse>,
)

/**
 * Anything the reader can draw a chapter from — `ChapterTextSource` on iOS. A plain SQLite store
 * and a sealed package answer the same questions, so the reader never branches on which it has.
 *
 * Calls may block on disk and decryption; callers keep them off the main thread.
 */
interface ChapterSource {
    val info: TranslationInfo
    fun contains(ref: ChapterRef): Boolean
    fun chapter(ref: ChapterRef): Chapter
}

/**
 * The decoding both sources share, kept free of Android types so the JVM tests run the exact code
 * the app does against the shipped databases (the tests read rows through JDBC; the app through the
 * bundled SQLite driver — the SQL is two trivial selects, the decoding is what could diverge).
 */
object ChapterRows {
    /** `verses.red` — JSON `[[start, length], …]` in scalars, or NULL. A malformed pair is skipped, as in Swift. */
    fun parseRed(json: String?): List<ScalarRange> {
        if (json.isNullOrBlank()) return emptyList()
        val root = try {
            Json.parseToJsonElement(json)
        } catch (e: IllegalArgumentException) {
            return emptyList()
        }
        return (root as? JsonArray)?.mapNotNull { pair ->
            val numbers = (pair as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull } ?: return@mapNotNull null
            if (numbers.size != 2 || numbers.any { it == null }) null else ScalarRange(numbers[0]!!, numbers[1]!!)
        } ?: emptyList()
    }
}

/**
 * A plain bundled store — BSB.sqlite, KJV.sqlite — read through [BundledDatabase]. The schema is the
 * one every Bible database shares: `meta(key, value)`, `chapters(book, chapter, verses, layout)`,
 * `verses(id, text, red)`.
 */
class SqliteChapterSource(private val context: Context, private val assetName: String) : ChapterSource {

    override val info: TranslationInfo by lazy {
        val meta = BundledDatabase.withConnection(context, assetName) { db ->
            db.prepare("SELECT key, value FROM meta").use { st ->
                buildMap { while (st.step()) put(st.getText(0), st.getText(1)) }
            }
        }
        val id = meta["id"] ?: assetName.substringBefore('.')
        TranslationInfo(id, meta["name"].orEmpty(), meta["abbreviation"] ?: id, meta["copyright"].orEmpty())
    }

    override fun contains(ref: ChapterRef): Boolean = layoutJson(ref) != null

    override fun chapter(ref: ChapterRef): Chapter {
        val json = layoutJson(ref) ?: throw NoSuchElementException("${info.id} has no $ref")
        val range = VerseRef.chapterRange(ref.book, ref.chapter)
        val verses = BundledDatabase.withConnection(context, assetName) { db ->
            db.prepare("SELECT id, text, red FROM verses WHERE id BETWEEN ? AND ? ORDER BY id").use { st ->
                st.bindLong(1, range.first.toLong())
                st.bindLong(2, range.last.toLong())
                buildList {
                    while (st.step()) {
                        val red = if (st.isNull(2)) null else st.getText(2)
                        add(ChapterVerse(VerseRef.fromKey(st.getLong(0).toInt()), st.getText(1), ChapterRows.parseRed(red)))
                    }
                }
            }
        }
        return Chapter(info, ref, ChapterLayout.parse(json), verses)
    }

    private fun layoutJson(ref: ChapterRef): String? = BundledDatabase.withConnection(context, assetName) { db ->
        db.prepare("SELECT layout FROM chapters WHERE book = ? AND chapter = ?").use { st ->
            st.bindLong(1, ref.book.toLong())
            st.bindLong(2, ref.chapter.toLong())
            if (st.step()) st.getText(0) else null
        }
    }
}

/**
 * A sealed `.sabible` translation — the bundled ASV. Each call decrypts one chapter and keeps
 * nothing, so the package's "never a whole Bible in the clear" property holds through this layer.
 */
class PackageChapterSource(private val pkg: TranslationPackage) : ChapterSource {

    override val info: TranslationInfo = pkg.translation.let {
        TranslationInfo(it.id, it.name, it.abbreviation.ifEmpty { it.id }, it.copyright)
    }

    override fun contains(ref: ChapterRef): Boolean = pkg.contains(ref)

    override fun chapter(ref: ChapterRef): Chapter {
        val packaged = pkg.chapter(ref)
        return Chapter(
            translation = info,
            ref = ref,
            layout = ChapterLayout.parse(packaged.layoutJson),
            verses = packaged.verses.map { ChapterVerse(it.ref, it.text, it.red) },
        )
    }
}

/**
 * The translations that ship with the app, in the switcher's order. ASV first: it is the default on
 * both platforms, and exists only as the sealed package.
 */
object BundledTranslations {
    val ids: List<String> = listOf("ASV", "BSB", "KJV")
    const val DEFAULT = "ASV"

    private val open = mutableMapOf<String, ChapterSource>()

    /** Opens (once) and returns the source for [id]. Blocks on the first call; call off the main thread. */
    @Synchronized
    fun source(context: Context, id: String): ChapterSource = open.getOrPut(id) {
        val app = context.applicationContext
        when (id) {
            "ASV" -> {
                val key = app.assets.open("bundled-signing.pub").use { it.readBytes() }
                PackageChapterSource(
                    TranslationPackage.open(
                        BundledDatabase.file(app, "ASV.sabible"),
                        PublisherKeyring(listOf(key)),
                        ContentKey.derive(ContentKey.BUNDLED_SEED, "ASV"),
                    ),
                )
            }
            else -> SqliteChapterSource(app, "$id.sqlite")
        }
    }
}
