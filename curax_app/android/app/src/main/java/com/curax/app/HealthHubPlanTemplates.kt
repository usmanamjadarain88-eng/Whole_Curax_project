package com.curax.app

import android.content.Context
import java.util.Locale

object HealthHubPlanTemplates {

    val ORDER: List<String> = listOf(
        "general",
        "bp",
        "diabetes",
        "medication",
        "behavior",
        "custom",
    )

    fun labels(ctx: Context): List<String> = ORDER.map { labelForSlug(ctx, it) }

    fun labelForSlug(ctx: Context, slug: String): String {
        val s = slug.lowercase(Locale.US)
        val r = ctx.resources
        return when (s) {
            "general" -> r.getString(R.string.health_type_general)
            "bp" -> r.getString(R.string.health_type_bp)
            "diabetes" -> r.getString(R.string.health_type_diabetes)
            "medication" -> r.getString(R.string.health_type_medication)
            "behavior" -> r.getString(R.string.health_type_behavior)
            "custom" -> r.getString(R.string.health_type_custom)
            else -> slug.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
    }

    /** Pairs of title and notes (notes may be empty). */
    fun suggestions(ctx: Context, slug: String): List<Pair<String, String>> {
        val r = ctx.resources
        return when (slug.lowercase(Locale.US)) {
            "bp" -> listOf(
                r.getString(R.string.plan_suggest_bp_morning_title) to r.getString(R.string.plan_suggest_bp_morning_notes),
                r.getString(R.string.plan_suggest_bp_salt_title) to r.getString(R.string.plan_suggest_bp_salt_notes),
            )
            "diabetes" -> listOf(
                r.getString(R.string.plan_suggest_diabetes_glucose_title) to r.getString(R.string.plan_suggest_diabetes_glucose_notes),
                r.getString(R.string.plan_suggest_diabetes_walk_title) to r.getString(R.string.plan_suggest_diabetes_walk_notes),
            )
            "medication" -> listOf(
                r.getString(R.string.plan_suggest_med_dose_title) to r.getString(R.string.plan_suggest_med_dose_notes),
                r.getString(R.string.plan_suggest_med_refill_title) to r.getString(R.string.plan_suggest_med_refill_notes),
            )
            "behavior" -> listOf(
                r.getString(R.string.plan_suggest_behavior_water_title) to r.getString(R.string.plan_suggest_behavior_water_notes),
                r.getString(R.string.plan_suggest_behavior_sleep_title) to r.getString(R.string.plan_suggest_behavior_sleep_notes),
            )
            "custom" -> emptyList()
            else -> listOf(
                r.getString(R.string.plan_suggest_general_walk_title) to r.getString(R.string.plan_suggest_general_walk_notes),
            )
        }
    }
}
