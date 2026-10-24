package com.blainemiller.scripturealone.data.plans

import com.blainemiller.scripturealone.data.canon.BookID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Plans and progress as JSON, by hand over the tree API (the app avoids the serialization compiler
 * plugin). The shape matches what iOS writes (`Plans/plans.json`) field for field, so the two stay
 * readable by each other's tests; `kind` is the lower-case name and book ids are the canonical ordinal.
 */
object PlanJson {
    data class State(val imported: List<ReadingPlan>, val progress: List<PlanProgress>, val primaryId: String?)

    private fun JsonObject.str(key: String) = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String) = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull

    fun encode(state: State): String = buildJsonObject {
        put("imported", buildJsonArray { state.imported.forEach { add(plan(it)) } })
        put("progress", buildJsonArray { state.progress.forEach { add(progress(it)) } })
        state.primaryId?.let { put("primaryID", it) }
    }.toString()

    fun decode(text: String): State? = runCatching {
        val o = Json.parseToJsonElement(text).jsonObject
        State(
            o["imported"]?.jsonArray.orEmpty().map { plan(it.jsonObject) },
            o["progress"]?.jsonArray.orEmpty().map { progress(it.jsonObject) },
            o.str("primaryID"),
        )
    }.getOrNull()

    fun plan(p: ReadingPlan): JsonObject = buildJsonObject {
        put("id", p.id)
        put("kind", p.kind.name.lowercase())
        put("title", p.title)
        put("summary", p.summary)
        put("days", buildJsonArray {
            p.days.forEach { d ->
                add(buildJsonObject {
                    put("index", d.index)
                    d.date?.let { put("date", it) }
                    d.label?.let { put("label", it) }
                    if (d.devotions.isNotEmpty()) put("devotions", buildJsonArray {
                        d.devotions.forEach { v ->
                            add(buildJsonObject {
                                put("heading", v.heading)
                                v.quote?.let { put("quote", it) }
                                put("text", v.text)
                            })
                        }
                    })
                    put("passages", buildJsonArray {
                        d.passages.forEach { q ->
                            add(buildJsonObject {
                                put("book", q.book.number)
                                put("startChapter", q.startChapter)
                                q.startVerse?.let { put("startVerse", it) }
                                put("endChapter", q.endChapter)
                                q.endVerse?.let { put("endVerse", it) }
                            })
                        }
                    })
                })
            }
        })
        if (p.annual) put("annual", true)
        put("skipWeekdays", buildJsonArray { p.skipWeekdays.sorted().forEach { add(JsonPrimitive(it)) } })
        p.source?.let { s ->
            put("source", buildJsonObject {
                put("siteURL", s.siteUrl)
                s.apiRoot?.let { put("apiRoot", it) }
                s.studyCategory?.let { put("studyCategory", it) }
                put("name", s.name)
                s.scheduleCategory?.let { put("scheduleCategory", it) }
            })
        }
    }

    /** A plan from its JSON text — the form the apps store and Spurgeon's plan ships in. */
    fun decodePlan(text: String): ReadingPlan = plan(Json.parseToJsonElement(text).jsonObject)

    fun plan(o: JsonObject): ReadingPlan = ReadingPlan(
        id = o.str("id")!!,
        kind = PlanKind.valueOf(o.str("kind")!!.uppercase()),
        title = o.str("title").orEmpty(),
        summary = o.str("summary").orEmpty(),
        days = o["days"]!!.jsonArray.map { d ->
            val day = d.jsonObject
            PlanDay(
                day.int("index")!!, day.str("date"), day.str("label"),
                day["passages"]!!.jsonArray.map { q ->
                    val pass = q.jsonObject
                    PlanPassage(BookID.of(pass.int("book")!!)!!, pass.int("startChapter")!!, pass.int("startVerse"),
                        pass.int("endChapter")!!, pass.int("endVerse"))
                },
                day["devotions"]?.jsonArray.orEmpty().map { v ->
                    val o = v.jsonObject
                    PlanDevotion(o.str("heading").orEmpty(), o.str("quote"), o.str("text").orEmpty())
                },
            )
        },
        skipWeekdays = o["skipWeekdays"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }.toSet(),
        source = (o["source"] as? JsonObject)?.let { s ->
            PlanSource(s.str("siteURL")!!, s.str("apiRoot"), s.int("studyCategory"), s.str("name").orEmpty(), s.int("scheduleCategory"))
        },
        annual = o["annual"]?.jsonPrimitive?.booleanOrNull ?: false,
    )

    fun progress(p: PlanProgress): JsonObject = buildJsonObject {
        put("planID", p.planId)
        put("startDate", p.startDate)
        put("followsCalendar", p.followsCalendar)
        put("completed", buildJsonArray { p.completed.sorted().forEach { add(JsonPrimitive(it)) } })
        if (p.reminderHour != null && p.reminderMinute != null) {
            put("reminder", buildJsonObject {
                put("hour", p.reminderHour)
                put("minute", p.reminderMinute)
            })
        }
    }

    fun progress(o: JsonObject): PlanProgress {
        val reminder = o["reminder"] as? JsonObject
        return PlanProgress(
            planId = o.str("planID")!!,
            startDate = o.str("startDate")!!,
            followsCalendar = o["followsCalendar"]?.jsonPrimitive?.booleanOrNull ?: false,
            completed = o["completed"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }.toSet(),
            reminderHour = reminder?.int("hour"),
            reminderMinute = reminder?.int("minute"),
        )
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
}
