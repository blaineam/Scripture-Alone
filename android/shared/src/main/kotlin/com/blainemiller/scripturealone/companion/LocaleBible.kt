package com.blainemiller.scripturealone.companion

import java.util.Locale

/**
 * The big-8 locales' Bibles (docs/localization.md) and the language each is for — shared by the phone
 * (`AssetPack`, which delivers them as Play asset packs and picks one at first launch) and the watch
 * (which gets them from the phone as editions, and shows the reader's language in Verse of the Day).
 * iOS keeps the same table on `AssetPack.locale`.
 */
object LocaleBible {
    /** Translation id → the BCP 47 language it is for, in the Translations screen's order. */
    val LOCALES: Map<String, String> = linkedMapOf(
        "CUVS" to "zh-Hans",
        "BUNGO" to "ja",
        "LUT1912" to "de",
        "LSG" to "fr",
        "RVR1909" to "es",
        "KRV" to "ko",
        "BLIVRE" to "pt-BR",
        "RIV1927" to "it",
    )

    /** Whether [id] is one of the eight — a Bible the watch doesn't bundle, which the phone sends it. */
    fun isLocaleBible(id: String): Boolean = id in LOCALES

    /**
     * The Bible for the first of the reader's preferred languages that has one, or null for English and
     * every language without one — `AssetPack.bible(forPreferredLanguages:)`. Simplified Chinese only:
     * the 和合本 here is the simplified-script edition, and a Traditional reader should not be handed it
     * unasked.
     */
    fun forPreferredLanguages(tags: List<String>): String? {
        for (tag in tags) {
            val locale = Locale.forLanguageTag(tag.replace('_', '-'))
            val code = locale.language.takeIf { it.isNotEmpty() } ?: continue
            if (code == "en") return null
            if (code == "zh") {
                // Likely subtags: Taiwan, Hong Kong and Macao write Traditional, elsewhere Simplified.
                val script = locale.script.takeIf { it.isNotEmpty() }
                    ?: if (locale.country in setOf("TW", "HK", "MO")) "Hant" else "Hans"
                if (script == "Hans") return "CUVS"
                continue
            }
            LOCALES.entries.firstOrNull { it.value.substringBefore('-') == code }?.let { return it.key }
        }
        return null
    }
}
