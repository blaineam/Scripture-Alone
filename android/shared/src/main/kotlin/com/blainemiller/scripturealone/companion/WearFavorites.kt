package com.blainemiller.scripturealone.companion

import com.blainemiller.scripturealone.data.VerseRange

/**
 * Favoriting a verse on the watch — `WatchVerseView.toggleFavorite` on the Apple Watch, where the watch
 * writes the shared SwiftData store itself. Android's watch holds no store of its own (it shows the
 * phone's snapshot), so the tap travels to the phone as a request ([WearLink.PATH_FAVORITE_PREFIX]) and
 * the phone's library takes it; the next snapshot brings it back. Until then the watch shows the state
 * the reader asked for ([shown]). Kept apart from the Data Layer so it is proven on the JVM.
 */
object WearFavorites {

    /** The reader on the watch wants [range] to be (or not be) a favorite, as of [at] (milliseconds). */
    data class Request(val range: VerseRange, val favorite: Boolean, val at: Long)

    fun path(range: VerseRange): String = WearLink.PATH_FAVORITE_PREFIX + range.storageString

    /** The passage a request path names, or null for any other path or a malformed one. */
    fun range(path: String): VerseRange? {
        if (!path.startsWith(WearLink.PATH_FAVORITE_PREFIX)) return null
        val raw = path.removePrefix(WearLink.PATH_FAVORITE_PREFIX)
        if (raw.isEmpty() || raw.length > 32 || !raw.all { it.isDigit() || it == '-' }) return null
        return VerseRange.parse(raw)?.takeIf { it.storageString == raw }
    }

    enum class Action { ADD, REMOVE, NONE }

    /**
     * What the phone does with [request], given the favorite it already holds for exactly that passage
     * and when that was made ([existingAt], milliseconds; null when there is none). Asking for what is
     * already so does nothing; so does a removal older than a favorite made on the phone since — the
     * newer choice wins, as everywhere else between the two.
     */
    fun decide(request: Request, existingAt: Long?): Action = when {
        request.favorite && existingAt == null -> Action.ADD
        request.favorite -> Action.NONE
        existingAt == null -> Action.NONE
        existingAt > request.at -> Action.NONE
        else -> Action.REMOVE
    }

    /**
     * Whether the watch shows [range] as a favorite: the reader's own request while it is pending,
     * otherwise what the phone's snapshot says.
     */
    fun shown(range: VerseRange, snapshotFavorites: Set<String>, pending: Map<String, Boolean>): Boolean =
        pending[range.storageString] ?: (range.storageString in snapshotFavorites)

    /**
     * The requests still pending once the phone's snapshot arrives: one the snapshot already agrees
     * with has been taken and is dropped.
     */
    fun settle(pending: Map<String, Boolean>, snapshotFavorites: Set<String>): Map<String, Boolean> =
        pending.filter { (range, favorite) -> (range in snapshotFavorites) != favorite }
}
