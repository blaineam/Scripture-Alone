package com.blainemiller.scripturealone.data.catalog

import java.text.Collator
import java.util.Locale

/**
 * Picks the translations worth showing someone first, from their own device settings. Ported from
 * `ScriptureAloneCore/Catalog/CatalogLanguageMatch.swift`.
 *
 * eBible identifies languages by ISO 639-3 ("eng", "spa", "cmn"); devices hand us BCP-47 tags
 * ("en-US", "zh-Hans-CN"). On Apple the bridge is `Locale.LanguageCode.identifier(.alpha3)` and
 * `Locale.Language.maximalIdentifier`. Java's `Locale` has neither a matching alpha-3 table (it passes
 * any three letters through, where Apple answers nil for a code it doesn't know) nor likely-subtags,
 * and Android's ICU can't run in a JVM unit test — so both answers come from [LocaleTables], which
 * was generated from Apple's own `Locale` and therefore agrees with the iOS app code for code.
 */
object CatalogLanguageMatch {

    /**
     * The device's languages as BCP-47 tags. Java's `Locale` knows only one; the Android layer should
     * pass `LocaleList.getAdjustedDefault().toLanguageTags().split(",")`, the equivalent of Apple's
     * `Locale.preferredLanguages`.
     */
    fun deviceLanguages(): List<String> = listOf(Locale.getDefault().toLanguageTag())

    /**
     * The reader's languages in ISO 639-3, most-preferred first, de-duplicated.
     *
     * Reads the *preferred language list*, not just the current locale: someone whose phone is in
     * English but who also reads Tagalog has both, in their own order of preference.
     */
    fun preferredLanguageCodes(identifiers: List<String> = deviceLanguages()): List<String> {
        val seen = HashSet<String>()
        val out = mutableListOf<String>()
        for (identifier in identifiers) {
            val code = languageCode(identifier) ?: continue
            // Already three letters (ISO 639-3) or convertible from two.
            val alpha3 = LocaleTables.alpha3(code)?.lowercase() ?: continue
            if (alpha3.isEmpty() || !seen.add(alpha3)) continue
            out += alpha3
        }
        return out
    }

    /**
     * The written form the reader expects, when their language has more than one — "Hans" or "Hant"
     * for Chinese. Null when the tag doesn't say and the language doesn't need it.
     */
    fun preferredScript(identifier: String = deviceLanguages().firstOrNull() ?: ""): String? {
        scriptSubtag(identifier)?.let { return it }
        // A bare "zh" means neither form in particular; the likely-subtags table can still resolve a default.
        val code = languageCode(identifier) ?: return null
        return LocaleTables.likelyScript(code)
    }

    /** The two halves of the catalogue: what this reader probably wants, and everything else. */
    data class Split(val mine: List<CatalogTranslation>, val other: List<CatalogTranslation>)

    /**
     * Splits the catalogue into what this reader probably wants and everything else.
     *
     * Both halves are ordered the same way — complete Bibles before New Testaments, then by size — but
     * the first half is also ordered by how high the language sits in the reader's own preference list,
     * so a bilingual device sees its first language first.
     */
    fun split(
        entries: List<CatalogTranslation>,
        preferred: List<String> = preferredLanguageCodes(),
        script: String? = preferredScript(),
    ): Split {
        // `Dictionary(uniqueKeysWithValues:)` would trap on a repeated code; preferredLanguageCodes
        // never repeats one, and a caller that does gets the first position rather than a crash.
        val rank = HashMap<String, Int>()
        preferred.forEachIndexed { index, code -> rank.putIfAbsent(code, index) }
        val mine = mutableListOf<CatalogTranslation>()
        val other = mutableListOf<CatalogTranslation>()
        for (entry in entries) {
            if (rank[entry.languageCode.lowercase()] != null) mine += entry else other += entry
        }
        val collator = titleCollator()
        mine.sortWith { a, b ->
            val ra = rank[a.languageCode.lowercase()] ?: Int.MAX_VALUE
            val rb = rank[b.languageCode.lowercase()] ?: Int.MAX_VALUE
            if (ra != rb) return@sortWith ra.compareTo(rb)
            // Within one language, the reader's own script wins — a Simplified Chinese device should
            // not have to scroll past Traditional editions to find its own.
            if (script != null) {
                val sa = entryMatches(a, script)
                val sb = entryMatches(b, script)
                if (sa != sb) return@sortWith if (sa) -1 else 1
            }
            bigger(a, b, collator)
        }
        other.sortWith { a, b -> bigger(a, b, collator) }
        return Split(mine, other)
    }

    /** Everything, ordered with the reader's languages first. For a single list rather than two sections. */
    fun ordered(
        entries: List<CatalogTranslation>,
        preferred: List<String> = preferredLanguageCodes(),
        script: String? = preferredScript(),
    ): List<CatalogTranslation> {
        val parts = split(entries, preferred, script)
        return parts.mine + parts.other
    }

    private fun entryMatches(entry: CatalogTranslation, script: String): Boolean = entry.script.equals(script, ignoreCase = true)

    /** `localizedCaseInsensitiveCompare`: the current locale's collation, ignoring case but not accents. */
    private fun titleCollator(): Collator = Collator.getInstance().apply { strength = Collator.SECONDARY }

    /** Complete Bibles first, then the most complete text, then a stable name order. As a comparator. */
    private fun bigger(a: CatalogTranslation, b: CatalogTranslation, collator: Collator): Int {
        if (a.bookCount != b.bookCount) return b.bookCount.compareTo(a.bookCount)
        if (a.verseCount != b.verseCount) return b.verseCount.compareTo(a.verseCount)
        return collator.compare(a.title, b.title)
    }

    // MARK: Reading a tag

    private fun subtags(identifier: String): List<String> = identifier.split('-', '_')

    /** The language subtag, lowercased: "en" from "en-US", "zh" from "zh_Hans_CN". Null when there is none. */
    internal fun languageCode(identifier: String): String? {
        val first = subtags(identifier).firstOrNull() ?: return null
        if (first.length !in 2..8 || !first.all { it in 'a'..'z' || it in 'A'..'Z' }) return null
        return first.lowercase()
    }

    /** A four-letter script subtag straight after the language, title-cased: "Hans" from "zh-hans-CN". */
    internal fun scriptSubtag(identifier: String): String? {
        val candidate = subtags(identifier).getOrNull(1) ?: return null
        if (candidate.length != 4 || !candidate.all { it in 'a'..'z' || it in 'A'..'Z' }) return null
        return candidate.substring(0, 1).uppercase() + candidate.substring(1).lowercase()
    }
}
