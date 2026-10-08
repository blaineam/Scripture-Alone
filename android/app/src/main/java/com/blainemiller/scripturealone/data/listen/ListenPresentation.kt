package com.blainemiller.scripturealone.data.listen

/**
 * How the Listen player shows: not at all, as the full Now Playing bar, or minimized into the bottom bar's
 * Listen button while reading goes on (the verse stays marked and the columns keep turning) — `ListenPresentation`
 * in `ScriptureAlone/Listen/ListenController.swift`.
 *
 * Every new listening session opens the full bar: it carries Stop, the silent-mode Unmute, the voice
 * and the speed, which a reader who has just pressed Listen may want. Minimizing lasts for the session
 * only — nothing is remembered.
 */
class ListenPresentation {
    enum class Mode { HIDDEN, EXPANDED, MINIMIZED }

    var mode: Mode = Mode.HIDDEN
        private set

    val isPresented: Boolean get() = mode != Mode.HIDDEN
    val isMinimized: Boolean get() = mode == Mode.MINIMIZED

    /** Listen started (the toolbar, a selection, a shortcut): the full bar, even if the player was minimized. */
    fun sessionStarted() {
        mode = Mode.EXPANDED
    }

    /** The reader minimized the bar. */
    fun minimize() {
        if (mode == Mode.EXPANDED) mode = Mode.MINIMIZED
    }

    /** The reader tapped the minimized player's Listen button. */
    fun expand() {
        if (mode == Mode.MINIMIZED) mode = Mode.EXPANDED
    }

    /** Listening stopped — Stop, the notification's stop, or the end of reading while minimized. */
    fun sessionEnded() {
        mode = Mode.HIDDEN
    }

    /** Something to tell the reader: a minimized player opens into the bar, where the notice shows. */
    fun noticeShown() {
        if (mode == Mode.MINIMIZED) mode = Mode.EXPANDED
    }

    /**
     * Reading came to its end. The full bar stays up, paused, so play reads it again; a minimized
     * player closes — unless there's a notice to show, which opens the bar instead. Returns whether
     * the session should stop.
     */
    fun passEndedCloses(withNotice: Boolean): Boolean {
        if (mode != Mode.MINIMIZED) return false
        if (withNotice) {
            mode = Mode.EXPANDED
            return false
        }
        return true
    }
}
