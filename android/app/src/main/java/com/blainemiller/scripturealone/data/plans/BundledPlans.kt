package com.blainemiller.scripturealone.data.plans

import android.content.Context

/** The plans that ship in the app's assets — Spurgeon's *Morning and Evening*, the same JSON iOS bundles (`Tools/build_spurgeon.py`). */
object BundledPlans {
    private val files = listOf("SpurgeonMorningEvening.json")

    fun load(context: Context): List<ReadingPlan> = files.mapNotNull { name ->
        runCatching { PlanJson.decodePlan(context.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }) }.getOrNull()
    }
}
