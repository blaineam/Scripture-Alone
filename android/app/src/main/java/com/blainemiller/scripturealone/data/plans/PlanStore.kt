package com.blainemiller.scripturealone.data.plans

import java.io.File
import java.time.LocalDate

/** What to read today in the reader's plan — `TodaysReading` on iOS. UI-free: Listen, a notification and a shortcut ask the same question. */
data class TodaysReading(
    val planId: String,
    val planTitle: String,
    val day: PlanDay,
    val dayNumber: Int,
    val totalDays: Int,
    /** An older day that was missed, offered because today has no reading. */
    val isCatchUp: Boolean,
    val isDone: Boolean,
) {
    val passages: List<PlanPassage> get() = day.passages
    /** The plan's own writing for the day (Spurgeon's morning and evening), when it has any. */
    val devotions: List<PlanDevotion> get() = day.devotions
    val display: String get() = day.display
}

/**
 * The reader's plans: the ones imported, and where they are in each plan they started. Two small
 * lists in one JSON file (`PlanStore.swift`'s `plans.json`); a plan holds only references and progress
 * only day numbers. Not synced between devices: Android keeps nothing in a cloud (docs/android-parity.md).
 */
class PlanStore(
    private val dir: File,
    /** Plans that ship in the app (Spurgeon's *Morning and Evening*), read from assets. */
    val bundled: List<ReadingPlan> = emptyList(),
    private val clock: () -> LocalDate = { LocalDate.now() },
) {
    var imported: List<ReadingPlan> = emptyList(); private set
    var progress: List<PlanProgress> = emptyList(); private set
    var primaryId: String? = null; private set

    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private val file get() = File(dir, "plans.json")

    init {
        file.takeIf { it.isFile }?.let { f -> PlanJson.decode(f.readText()) }?.let {
            imported = it.imported
            progress = it.progress
            primaryId = it.primaryId ?: it.progress.firstOrNull()?.planId
        }
        rollOverAnnualPlans()
    }

    /**
     * The shipped plans this reader is offered. Their text is English, so a device set to another language
     * isn't offered them (docs/localization.md: English-only commentary is hidden outside English).
     */
    fun offeredBundled(language: String = java.util.Locale.getDefault().language): List<ReadingPlan> =
        if (language != "en") emptyList() else bundled.filter { plan -> progress.none { it.planId == plan.id } }

    /** An annual plan starts over each January: the year's progress is forgotten on the first open of a new year. */
    fun rollOverAnnualPlans(now: LocalDate = clock()) {
        var changed = false
        progress = progress.map { p ->
            val next = plan(p.planId)?.let { p.rolledOver(it, now) }
            if (next != null) changed = true
            next ?: p
        }
        if (changed) save()
    }

    fun plan(id: String): ReadingPlan? = imported.firstOrNull { it.id == id } ?: bundled.firstOrNull { it.id == id } ?: PlanCatalog.plan(id)
    fun progress(planId: String): PlanProgress? = progress.firstOrNull { it.planId == planId }
    fun schedule(planId: String): PlanSchedule? {
        val plan = plan(planId) ?: return null
        return PlanSchedule(plan, progress(planId) ?: return null)
    }

    /** The plans the reader has started, the primary one first. */
    val started: List<ReadingPlan>
        get() = progress.mapNotNull { plan(it.planId) }.sortedBy { if (it.id == primaryId) 0 else 1 }

    fun today(): LocalDate = clock()

    /** Today's reading in the primary plan; failing that, in any plan the reader started. */
    fun todaysReading(now: LocalDate = clock()): TodaysReading? {
        val ids = listOfNotNull(primaryId) + progress.map { it.planId }.filter { it != primaryId }
        for (id in ids) {
            val schedule = schedule(id) ?: continue
            val day = schedule.reading(now) ?: continue
            val isToday = (schedule.today(now) as? PlanToday.Reading)?.day?.index == day.index
            return TodaysReading(id, schedule.plan.title, day, day.index + 1, schedule.plan.days.size, !isToday,
                day.index in schedule.progress.completed)
        }
        return null
    }

    /** Starts a plan (or restarts it, forgetting what was done) and makes it the primary one. */
    fun start(plan: ReadingPlan, on: LocalDate = clock(), followsCalendar: Boolean = false) {
        if (plan.days.isEmpty()) return
        val reminder = progress(plan.id)
        progress = progress.filter { it.planId != plan.id } + PlanProgress(
            plan.id, on.toString(), followsCalendar && plan.isDated, emptySet(), reminder?.reminderHour, reminder?.reminderMinute)
        primaryId = plan.id
        save()
    }

    fun stop(planId: String, studyCache: StudyCache? = null) {
        imported.firstOrNull { it.id == planId }?.source?.let { studyCache?.discard(it) }
        progress = progress.filter { it.planId != planId }
        imported = imported.filter { it.id != planId }
        if (primaryId == planId) primaryId = progress.firstOrNull()?.planId
        save()
    }

    fun makePrimary(planId: String) {
        if (progress(planId) == null) return
        primaryId = planId
        save()
    }

    fun setDone(done: Boolean, day: Int, planId: String) {
        val p = progress(planId) ?: return
        progress = progress.map { if (it.planId == planId) p.copy(completed = if (done) p.completed + day else p.completed - day) else it }
        save()
    }

    fun setReminder(planId: String, hour: Int?, minute: Int?) {
        val p = progress(planId) ?: return
        progress = progress.map { if (it.planId == planId) p.copy(reminderHour = hour, reminderMinute = minute) else it }
        save()
    }

    /** Keeps an imported plan; re-importing the same source replaces its days and keeps progress. */
    fun add(plan: ReadingPlan) {
        imported = imported.filter { it.id != plan.id } + plan
        save()
    }

    /** When the history of a followed site was last read for a projection, so a site with no earlier pass isn't asked at every launch. */
    fun projectionTried(planId: String): LocalDate? = runCatching {
        File(dir, "projection-tried.txt").takeIf { it.isFile }?.readLines().orEmpty()
            .map { it.split(" ") }.firstOrNull { it[0] == planId }?.get(1)?.let(LocalDate::parse)
    }.getOrNull()

    fun markProjectionTried(planId: String, on: LocalDate) {
        runCatching {
            dir.mkdirs()
            val others = File(dir, "projection-tried.txt").takeIf { it.isFile }?.readLines().orEmpty().filter { !it.startsWith("$planId ") }
            File(dir, "projection-tried.txt").writeText((others + "$planId $on").joinToString("\n"))
        }
    }

    private fun save() {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "plans.json.tmp")
            tmp.writeText(PlanJson.encode(PlanJson.State(imported, progress, primaryId)))
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        }
        listeners.toList().forEach { it() }
    }
}

/** The last weekly study a reader opened, kept on the device for offline reading — `StudyCache.swift`. One per source. */
class StudyCache(private val dir: File) {
    private fun file(source: PlanSource) =
        File(dir, java.lang.Long.toUnsignedString(PlanFeed.stableHash(source.siteUrl), 36) + ".txt")

    fun save(post: StudyPost, source: PlanSource) {
        runCatching {
            dir.mkdirs()
            file(source).writeText(listOf(post.title, post.date, post.link.orEmpty()).joinToString("\n") + "\n\n" + post.text)
        }
    }

    fun load(source: PlanSource): StudyPost? = runCatching {
        val raw = file(source).takeIf { it.isFile }?.readText() ?: return null
        val head = raw.substringBefore("\n\n").split("\n")
        StudyPost(head[0], head.getOrElse(1) { "" }, head.getOrNull(2)?.ifEmpty { null }, raw.substringAfter("\n\n"))
    }.getOrNull()

    fun discard(source: PlanSource) { file(source).delete() }
}

data class StudyPost(val title: String, val date: String, val link: String?, val text: String)
