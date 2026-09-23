package com.blainemiller.scripturealone.data.assets

import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.text.AppText

/**
 * Content delivered as Google Play asset packs rather than inside the app's base module —
 * `AssetPack` in `ScriptureAlone/App/AssetLibrary.swift`, where the same files are Background Assets
 * packs.
 *
 * **Why packs at all.** Google Play caps the base module's compressed download at 200 MB, and the
 * databases are most of this app's weight. Play hosts asset packs for free, as Apple hosts Background
 * Assets, so there is still no server.
 *
 * | Pack | Delivery | Why |
 * |---|---|---|
 * | ASV | install-time | The default translation: a fresh install reads offline on first launch. |
 * | BSB | install-time | On iOS it is on demand; here it arrives with the install, because Study's original-language words are keyed to its text. |
 * | KJV | on-demand | Fetched the first time the reader chooses it, as on iOS. |
 * | Commentary, Original Languages | on-demand | 55 MB many readers never open, as on iOS. |
 * | The big-8 locales' Bibles (CUVS, BUNGO, LUT1912, LSG, RVR1909, KRV, BLIVRE, RIV1927) | on-demand | Each the whole 66-book Protestant canon; the one for the device's language is fetched at first launch, without ever blocking it (docs/localization.md). |
 *
 * The pack modules live in `android/packs/<packName>/build.gradle.kts`, which sync each file from the
 * iOS resources at build time; `AssetPackDefinitionsTest` holds this table to those build files.
 */
enum class AssetPack(
    /** Play's pack name — the Gradle module's `packName`. Underscores: Play refuses a dash. */
    val packName: String,
    /** The one file in the pack, at its root, and the name it keeps once copied out. */
    val file: String,
    val delivery: Delivery,
    /** The Bible's own name; the study packs' titles come from the reader's language ([title]). */
    private val fixedTitle: String,
    /** Roughly what the reader downloads, for the explanation shown before and during it. */
    val megabytes: Int,
    /** The language this Bible is for, as a BCP 47 tag — which device languages it is chosen for. */
    val locale: String? = null,
) {
    ASV("asv", "ASV.sabible", Delivery.INSTALL_TIME, "American Standard Version", 16),
    BSB("bsb", "BSB.sqlite", Delivery.INSTALL_TIME, "Berean Standard Bible", 15),
    KJV("kjv", "KJV.sqlite", Delivery.ON_DEMAND, "King James Version", 15),
    COMMENTARY("study_commentary", "Study.sqlite", Delivery.ON_DEMAND, "Commentary", 44),
    INTERLINEAR("study_interlinear", "Interlinear.sqlite", Delivery.ON_DEMAND, "Original Languages", 11),

    // The big-8 locales' Bibles (docs/localization.md), each titled with its own name, in its own
    // language, as its readers know it — iOS's `AssetPack` cases of the same names.
    CUVS("cuvs", "CUVS.sqlite", Delivery.ON_DEMAND, "和合本（新标点）", 17, locale = "zh-Hans"),
    BUNGO("bungo", "BUNGO.sqlite", Delivery.ON_DEMAND, "文語訳聖書", 20, locale = "ja"),
    LUT1912("lut1912", "LUT1912.sqlite", Delivery.ON_DEMAND, "Lutherbibel 1912", 14, locale = "de"),
    LSG("lsg", "LSG.sqlite", Delivery.ON_DEMAND, "Louis Segond 1910", 15, locale = "fr"),
    RVR1909("rvr1909", "RVR1909.sqlite", Delivery.ON_DEMAND, "Reina-Valera 1909", 13, locale = "es"),
    KRV("krv", "KRV.sqlite", Delivery.ON_DEMAND, "개역한글", 20, locale = "ko"),
    BLIVRE("blivre", "BLIVRE.sqlite", Delivery.ON_DEMAND, "Bíblia Livre", 13, locale = "pt-BR"),
    RIV1927("riv1927", "RIV1927.sqlite", Delivery.ON_DEMAND, "Riveduta 1927", 14, locale = "it"),
    ;

    /** Play's delivery modes, by the names the Gradle `deliveryType` uses. */
    enum class Delivery(val gradleName: String) {
        INSTALL_TIME("install-time"),
        FAST_FOLLOW("fast-follow"),
        ON_DEMAND("on-demand"),
    }

    /** What the pack is called on screen: a Bible's own name, or the study pack's name in the reader's language. */
    val title: String
        get() = when (this) {
            COMMENTARY -> AppText.get(R.string.data_pack_commentary_title)
            INTERLINEAR -> AppText.get(R.string.data_pack_interlinear_title)
            else -> fixedTitle
        }

    /** What the reader is waiting for, in their terms — iOS's `explanation`, word for word. */
    val explanation: String
        get() = when (this) {
            COMMENTARY -> AppText.get(R.string.data_pack_commentary_explanation, megabytes)
            INTERLINEAR -> AppText.get(R.string.data_pack_interlinear_explanation, megabytes)
            else -> AppText.get(R.string.data_pack_bible_explanation, megabytes)
        }

    val isTranslation: Boolean get() = this != COMMENTARY && this != INTERLINEAR

    /** The translation this pack carries (its `meta.id`), or null for a study pack. */
    val translationId: String? get() = if (isTranslation) name else null

    companion object {
        /**
         * Every translation delivered as a pack, in the order the Translations screen lists them, after
         * the ASV — iOS's `AssetPack.translations`.
         */
        val translations: List<AssetPack> = listOf(BSB, KJV, CUVS, BUNGO, LUT1912, LSG, RVR1909, KRV, BLIVRE, RIV1927)

        /**
         * The Bible for the first of the reader's preferred languages that has one, or null for English
         * and every language without one — `AssetPack.bible(forPreferredLanguages:)`. Simplified Chinese
         * only: the 和合本 here is the simplified-script edition, and a Traditional reader should not be
         * handed it unasked.
         */
        fun bible(forPreferredLanguages: List<String>): AssetPack? {
            for (tag in forPreferredLanguages) {
                val locale = java.util.Locale.forLanguageTag(tag.replace('_', '-'))
                val code = locale.language.takeIf { it.isNotEmpty() } ?: continue
                if (code == "en") return null
                if (code == "zh") {
                    // Likely subtags: Taiwan, Hong Kong and Macao write Traditional, elsewhere Simplified.
                    val script = locale.script.takeIf { it.isNotEmpty() }
                        ?: if (locale.country in setOf("TW", "HK", "MO")) "Hant" else "Hans"
                    if (script == "Hans") return CUVS
                    continue
                }
                translations.firstOrNull { it.locale?.substringBefore('-') == code }?.let { return it }
            }
            return null
        }

        /** The pack for a bundled translation, or null for anything else. */
        fun forTranslation(id: String): AssetPack? = entries.firstOrNull { it.isTranslation && it.name == id }

        /** The pack whose file is [name], or null for a file that ships in the base module. */
        fun forFile(name: String): AssetPack? = entries.firstOrNull { it.file == name }
    }
}

/** A database the app asked for whose pack isn't on the device yet. Never shown raw to a reader. */
class AssetPackMissingException(val pack: AssetPack) :
    IllegalStateException("The ${pack.title} hasn’t been downloaded yet.")
