package com.blainemiller.scripturealone.data.plans

import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.text.AppText

/**
 * What the reader sees a plan called — `PlanText.swift`. The model keeps English titles (stored, and a
 * plan's id is what identifies it); screens and notifications show these, in the reader's language. A book
 * plan is named by its book, in the language the Bible is read in.
 */
object PlanText {
    fun title(plan: ReadingPlan): String = when (plan.kind) {
        PlanKind.CANONICAL -> AppText.get(R.string.plans_name_canonical)
        PlanKind.CHRONOLOGICAL -> AppText.get(R.string.plans_name_chronological)
        PlanKind.BOOK -> plan.days.firstOrNull()?.passages?.firstOrNull()?.book?.displayName ?: plan.title
        PlanKind.IMPORTED -> plan.title
    }

    fun summary(plan: ReadingPlan): String = when (plan.kind) {
        PlanKind.CANONICAL -> AppText.get(R.string.plans_summary_canonical)
        PlanKind.CHRONOLOGICAL -> AppText.get(R.string.plans_summary_chronological)
        PlanKind.BOOK -> AppText.get(R.string.plans_summary_book, title(plan))
        PlanKind.IMPORTED -> AppText.get(R.string.plans_summary_import, plan.source?.name ?: plan.title)
    }
}
