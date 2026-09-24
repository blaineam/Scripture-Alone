package com.blainemiller.scripturealone.companion

/**
 * Which imported translations the phone sends the watch, and when — the decisions in `WatchLink.swift`
 * (`importsChanged`, `sendEditionIfNeeded`) and the watch's `removeImports(notIn:)`, kept apart from the
 * Data Layer so they are proven on the JVM.
 */
object WearImports {

    /** An imported translation as the phone's library reports it. */
    data class Import(val id: String, val allowsOfflineStorage: Boolean)

    /** What the watch last reported holding: every received edition, and the version of each that has one. */
    data class Held(val ids: Set<String>, val versions: Map<String, String>)

    /**
     * The import ids to tell the watch about, or null while the library hasn't reported yet — the
     * library's first value is an empty placeholder, and an empty list sent then would tell the watch
     * to delete every import it holds, only to be sent them all again. Ids that can't be a file name on
     * the watch are left out, as they can never be sent.
     */
    fun offered(loaded: Boolean, imports: List<Import>): List<String>? =
        if (!loaded) null else imports.map { it.id }.filter(WearLink::isSafeId).distinct().sorted()

    /**
     * The imports whose edition may go to the watch: a safe id, and terms that let the text be stored
     * offline. None before the library has loaded.
     */
    fun sendable(loaded: Boolean, imports: List<Import>): List<Import> =
        if (!loaded) emptyList() else imports.filter { it.allowsOfflineStorage && WearLink.isSafeId(it.id) }.distinctBy { it.id }

    /**
     * An import's version: the edition format and a fingerprint of the store. The format is part of it,
     * so a new edition schema re-sends what the watch holds in the old one.
     */
    fun version(fingerprint: String, format: Int = WatchEditionBuilder.FORMAT): String = "f$format-$fingerprint"

    enum class Decision {
        /** The watch holds this version, or the Data Layer is already carrying it. */
        SKIP,

        /** Write the edition and put it. */
        SEND,

        /** Put again what was already put — the watch reports it lacks it — so it is delivered anew. */
        RESEND,
    }

    /**
     * Whether import [id] at [version] needs sending. [held] is the watch's last report (null when it
     * has never reported — an older watch app, or none yet); [put] is the version this phone last put
     * in the Data Layer and [putAt] when, in milliseconds. A version put in the last [settle]
     * milliseconds is left to arrive rather than put again.
     */
    fun decide(
        id: String,
        version: String,
        held: Held?,
        put: String?,
        putAt: Long,
        now: Long,
        settle: Long = SETTLE_MILLIS,
    ): Decision {
        if (held != null && held.versions[id] == version) return Decision.SKIP
        if (put != version) return Decision.SEND
        // Put already: the Data Layer delivers it, even to a watch paired later. Only a report that the
        // watch lacks it (or holds another version) after it had time to arrive prompts a second put.
        if (held == null || now - putAt < settle) return Decision.SKIP
        return Decision.RESEND
    }

    /** How long an edition is given to arrive before the watch's report that it lacks it counts. */
    const val SETTLE_MILLIS = 10 * 60 * 1000L

    /**
     * The imports the watch should drop: those it received as imports that the phone's [offered] list
     * no longer has. A language Bible the phone sent isn't governed by the list.
     */
    fun toRemove(receivedImports: Set<String>, offered: Collection<String>): Set<String> = receivedImports - offered.toSet()

    /**
     * Whether the watch should take an import edition of [id]: always before any list has arrived (an
     * older phone app sends none); otherwise only when the phone's list still has it — an edition item
     * the phone couldn't delete must not bring a removed import back.
     */
    fun accepts(id: String, offered: Collection<String>?): Boolean = offered == null || id in offered
}
