package com.blainemiller.scripturealone.data

import android.content.Context
import com.blainemiller.scripturealone.data.layout.ChapterLayout
import com.blainemiller.scripturealone.data.rights.TranslationRights
import com.blainemiller.scripturealone.data.rights.rights
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.sabible.ContentKey
import com.blainemiller.scripturealone.data.sabible.PublisherKeyring
import com.blainemiller.scripturealone.data.sabible.ScalarRange
import com.blainemiller.scripturealone.data.sabible.TranslationPackage
import com.blainemiller.scripturealone.data.sql.BundledSqlSource
import com.blainemiller.scripturealone.data.sql.SqlSource
import com.blainemiller.scripturealone.data.translations.TranslationLibrary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Who a translation is, as the reader shows it: the id in the switcher, the copyright in the footer —
 * and what its terms let the reader do with it ([rights]: a signed package's own grant, else the
 * licence line's), the one value the copy and share gates ask.
 */
data class TranslationInfo(
    val id: String,
    val name: String,
    val abbreviation: String,
    val copyright: String,
    val license: String = "",
    val rights: TranslationRights = TranslationRights.of(license, copyright),
)

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
 * The reads every plain store answers. Bundled, imported, and an online translation's cache all share
 * one schema — `meta(key, value)`, `chapters(book, chapter, verses, layout)`, `verses(id, text, red)` —
 * so one reader serves all three. Over [SqlSource], so the JVM tests run this exact code against real
 * files through JDBC.
 */
object StoreChapters {
    fun meta(db: SqlSource): Map<String, String> =
        db.query("SELECT key, value FROM meta") { it.text(0) to it.text(1) }.toMap()

    fun info(db: SqlSource, fallbackId: String): TranslationInfo {
        val meta = meta(db)
        val id = meta["id"] ?: fallbackId
        return TranslationInfo(
            id, meta["name"].orEmpty(), meta["abbreviation"] ?: id, meta["copyright"].orEmpty(), meta["license"].orEmpty(),
        )
    }

    fun layoutJson(db: SqlSource, ref: ChapterRef): String? =
        db.query("SELECT layout FROM chapters WHERE book = ? AND chapter = ?", ref.book, ref.chapter) { it.text(0) }
            .firstOrNull()

    /** Verses with keys in `first..last`, in order. */
    fun verses(db: SqlSource, first: Int, last: Int): List<ChapterVerse> =
        db.query("SELECT id, text, red FROM verses WHERE id BETWEEN ? AND ? ORDER BY id", first, last) { r ->
            val red = if (r.isNull(2)) null else r.text(2)
            ChapterVerse(VerseRef.fromKey(r.long(0).toInt()), r.text(1), ChapterRows.parseRed(red))
        }

    fun chapter(db: SqlSource, info: TranslationInfo, ref: ChapterRef): Chapter {
        val json = layoutJson(db, ref) ?: throw NoSuchElementException("${info.id} has no $ref")
        val range = VerseRef.chapterRange(ref.book, ref.chapter)
        return Chapter(info, ref, ChapterLayout.parse(json), verses(db, range.first, range.last))
    }
}

/** A plain bundled store — BSB.sqlite, KJV.sqlite — read through [BundledDatabase]. */
class SqliteChapterSource(private val context: Context, private val assetName: String) : ChapterSource {

    private fun <T> read(block: (SqlSource) -> T): T =
        BundledDatabase.withConnection(context, assetName) { block(BundledSqlSource(it)) }

    override val info: TranslationInfo by lazy { read { StoreChapters.info(it, assetName.substringBefore('.')) } }

    override fun contains(ref: ChapterRef): Boolean = read { StoreChapters.layoutJson(it, ref) } != null

    override fun chapter(ref: ChapterRef): Chapter = read { StoreChapters.chapter(it, info, ref) }
}

/**
 * A sealed `.sabible` translation — the bundled ASV. Each call decrypts one chapter and keeps
 * nothing, so the package's "never a whole Bible in the clear" property holds through this layer.
 */
class PackageChapterSource(private val pkg: TranslationPackage) : ChapterSource {

    override val info: TranslationInfo = pkg.translation.let {
        // A package's rights are its signed policy's, never derived from the licence line.
        TranslationInfo(
            it.id, it.name, it.abbreviation.ifEmpty { it.id }, it.copyright, it.license,
            rights = runCatching { pkg.policy.rights() }.getOrDefault(TranslationRights.LICENSED_DEFAULT.copy(maxQuotationVerses = 0)),
        )
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
 * Every translation the reader can open, in the switcher's order: the three that ship with the app,
 * then any the reader imported, then the online ones their keys unlock. ASV first: it is the default
 * on both platforms, and exists only as the sealed package.
 *
 * The name is historical. This is the seam the reader lists and opens translations through, so
 * imported and online translations come through it too — [TranslationLibrary] holds those — and the
 * reader needs no branch for them.
 */
object BundledTranslations {
    /** The translations inside the APK. */
    val bundled: List<String> = listOf("ASV", "BSB", "KJV")
    const val DEFAULT = "ASV"

    /** Everything the reader can switch to right now: [bundled], then imported, then online. */
    val ids: List<String> get() = bundled + TranslationLibrary.addedIds

    private val open = mutableMapOf<String, ChapterSource>()

    /**
     * Opens (once) and returns the source for [id]. Blocks on the first call; call off the main
     * thread. An online translation's source goes to the network for a chapter it hasn't cached.
     */
    @Synchronized
    fun source(context: Context, id: String): ChapterSource {
        if (id !in bundled) return TranslationLibrary.source(context, id)
        return open.getOrPut(id) {
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
}
