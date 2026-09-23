package com.blainemiller.scripturealone.companion

/**
 * The Wearable Data Layer vocabulary shared by the phone (`ui/widget/WearPublisher`) and the watch
 * (`wear/PhoneLink`) — `WatchLinkKeys.swift` on iOS. Compiled into both so the two cannot drift.
 *
 * Where the iPhone uses WatchConnectivity's application context ("only the latest value, delivered
 * the next time the watch app runs"), Android uses a Data Layer *data item* at a fixed path, which has
 * the same semantics: one current value per path, synced whenever the devices can reach each other.
 */
object WearLink {
    /** Phone → watch: the translation the reader is using on the phone, and when they switched to it. */
    const val PATH_TRANSLATION = "/scripturealone/translation"

    /** Phone → watch: the [VerseSnapshot] JSON — favorites, highlights and notes — as an asset. */
    const val PATH_SNAPSHOT = "/scripturealone/snapshot"

    /**
     * Phone → watch: the watch edition ([WatchEditionBuilder]) of a Bible the watch doesn't bundle — one
     * of the big-8 locales' ([LocaleBible]) — at `PATH_EDITION_PREFIX + id`, as an asset. One item per
     * translation, so a watch reinstalled later still finds every edition the phone has sent; the
     * iPhone's `transferFile` of the same file.
     */
    const val PATH_EDITION_PREFIX = "/scripturealone/edition/"

    /** Prefix every path shares; the watch's listener filters on it. */
    const val PATH_PREFIX = "/scripturealone"

    /** The edition asset. */
    const val KEY_EDITION = "edition"

    fun editionPath(id: String): String = PATH_EDITION_PREFIX + id

    /** The translation an edition path carries, or null for any other path or an unsafe id. */
    fun editionId(path: String): String? =
        path.takeIf { it.startsWith(PATH_EDITION_PREFIX) }?.removePrefix(PATH_EDITION_PREFIX)?.takeIf(::isSafeId)

    /** A translation identifier. */
    const val KEY_TRANSLATION = "translation"

    /**
     * When the reader switched to it on the phone, in seconds since 1970. Recorded at the switch, not
     * at send time, so a phone launch that merely re-reports an old choice cannot override a newer
     * pick made on the watch.
     */
    const val KEY_CHANGED_AT = "changedAt"

    /** The snapshot asset. */
    const val KEY_SNAPSHOT = "snapshot"

    /**
     * A translation identifier is also a file name on the watch, so it has to be a safe one: no
     * separators, no dots, nothing that could climb out of the directory.
     */
    fun isSafeId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 64 && id.all { it.code < 128 && (it.isLetterOrDigit() || it == '_' || it == '-') }
}

/**
 * Which translation the watch shows — `WatchBible.resolve` on the Apple Watch.
 *
 * The most recent choice the watch can actually show wins: the reader picking one on the watch, or the
 * phone reporting that they switched there. So switching to the KJV on the phone moves the watch too,
 * and picking the BSB on the watch keeps it until the phone changes again. A phone choice the watch has
 * no edition of (an online translation, whose terms forbid storing it) is remembered but not applied.
 */
object TranslationChoice {
    const val FALLBACK = "ASV"

    /** A choice and when it was made, in seconds since 1970. */
    data class Pick(val id: String, val at: Double)

    /**
     * [available] in the picker's order. On a tie the watch's own choice wins, as Swift's `max(by:)`
     * keeps the first of equal elements.
     */
    fun resolve(available: List<String>, watch: Pick?, phone: Pick?): String {
        val candidates = listOfNotNull(watch, phone).filter { it.id in available }
        var best: Pick? = null
        for (candidate in candidates) if (best == null || candidate.at > best.at) best = candidate
        return best?.id ?: if (FALLBACK in available) FALLBACK else available.firstOrNull() ?: FALLBACK
    }
}
