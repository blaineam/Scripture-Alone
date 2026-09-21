package com.blainemiller.scripturealone.data.assets

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
    val title: String,
    /** Roughly what the reader downloads, for the explanation shown before and during it. */
    val megabytes: Int,
) {
    ASV("asv", "ASV.sabible", Delivery.INSTALL_TIME, "American Standard Version", 16),
    BSB("bsb", "BSB.sqlite", Delivery.INSTALL_TIME, "Berean Standard Bible", 15),
    KJV("kjv", "KJV.sqlite", Delivery.ON_DEMAND, "King James Version", 15),
    COMMENTARY("study_commentary", "Study.sqlite", Delivery.ON_DEMAND, "Commentary", 44),
    INTERLINEAR("study_interlinear", "Interlinear.sqlite", Delivery.ON_DEMAND, "Original Languages", 11),
    ;

    /** Play's delivery modes, by the names the Gradle `deliveryType` uses. */
    enum class Delivery(val gradleName: String) {
        INSTALL_TIME("install-time"),
        FAST_FOLLOW("fast-follow"),
        ON_DEMAND("on-demand"),
    }

    /** What the reader is waiting for, in their terms — iOS's `explanation`, word for word. */
    val explanation: String
        get() = when (this) {
            ASV, BSB, KJV -> "About $megabytes MB, downloaded once and kept for reading offline."
            COMMENTARY -> "Calvin, Gill and Jamieson-Fausset-Brown — about $megabytes MB, downloaded once and kept."
            INTERLINEAR -> "The Hebrew and Greek behind every word, with a lexicon — about $megabytes MB, downloaded once and kept."
        }

    val isTranslation: Boolean get() = this == ASV || this == BSB || this == KJV

    /** The translation this pack carries, or null for a study pack. */
    val translationId: String? get() = if (isTranslation) name else null

    companion object {
        /** The pack for a bundled translation, or null for anything else. */
        fun forTranslation(id: String): AssetPack? = entries.firstOrNull { it.isTranslation && it.name == id }

        /** The pack whose file is [name], or null for a file that ships in the base module. */
        fun forFile(name: String): AssetPack? = entries.firstOrNull { it.file == name }
    }
}

/** A database the app asked for whose pack isn't on the device yet. Never shown raw to a reader. */
class AssetPackMissingException(val pack: AssetPack) :
    IllegalStateException("The ${pack.title} hasn’t been downloaded yet.")
