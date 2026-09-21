package com.blainemiller.scripturealone.ui.appearance

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Decides *whether* to ask for a review — MillerKit's `RatingManager`, rule for rule:
 *
 * 1. Attempts have a cooldown (120 days), not a one-shot flag: Play, like StoreKit, may show nothing
 *    at all, and burning the only attempt on a suppressed call would mean never asking again.
 * 2. Success paths only — never after an error, never on first launch.
 * 3. Earned, not timed: 10 launches, 7 days, and 3 completed things the app is for.
 * 4. Never twice for the same version.
 *
 * Pure over [Store], so the gates are unit-tested.
 */
class RatingGate(
    private val store: Store,
    private val version: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val gates: Gates = Gates(),
) {
    /** The few values the gate keeps — SharedPreferences in the app, a map in tests. */
    interface Store {
        fun long(key: String): Long?
        fun putLong(key: String, value: Long)
        fun string(key: String): String?
        fun putString(key: String, value: String)
    }

    data class Gates(
        val minimumLaunches: Int = 10,
        val minimumDays: Int = 7,
        val minimumSignificantActions: Int = 3,
        val cooldownMillis: Long = TimeUnit.DAYS.toMillis(120),
    )

    val launches: Long get() = store.long(LAUNCHES) ?: 0
    val significantActions: Long get() = store.long(ACTIONS) ?: 0
    val attempts: Long get() = store.long(ATTEMPTS) ?: 0

    /** Once per cold launch. */
    fun recordLaunch() {
        if (store.long(FIRST_LAUNCH) == null) store.putLong(FIRST_LAUNCH, now())
        store.putLong(LAUNCHES, launches + 1)
    }

    /** When the reader *completes* something — a keepsake made, notes brought in, an export written. */
    fun recordSignificantAction(count: Int = 1) {
        store.putLong(ACTIONS, significantActions + count)
    }

    val shouldRequestReview: Boolean
        get() {
            if (launches < gates.minimumLaunches) return false
            val first = store.long(FIRST_LAUNCH) ?: return false
            if (TimeUnit.MILLISECONDS.toDays(now() - first) < gates.minimumDays) return false
            if (significantActions < gates.minimumSignificantActions) return false
            if (store.string(LAST_VERSION) == version) return false
            val last = store.long(LAST_ATTEMPT)
            if (last != null && now() - last < gates.cooldownMillis) return false
            return true
        }

    /** Only alongside actually asking Play: it starts the cooldown; Play never says whether it showed. */
    fun recordAttempt() {
        store.putLong(LAST_ATTEMPT, now())
        store.putLong(ATTEMPTS, attempts + 1)
        store.putString(LAST_VERSION, version)
    }

    private companion object {
        // MillerKit's key names, so the two platforms' gate state reads the same in a debugger.
        const val LAUNCHES = "millerkit.rating.launches"
        const val FIRST_LAUNCH = "millerkit.rating.firstLaunchDate"
        const val ACTIONS = "millerkit.rating.significantActions"
        const val LAST_ATTEMPT = "millerkit.rating.lastAttemptDate"
        const val ATTEMPTS = "millerkit.rating.attemptCount"
        const val LAST_VERSION = "millerkit.rating.lastPromptedVersion"
    }
}

/**
 * Asking for a review through Play In-App Review — StoreKit's `requestReview` behind MillerKit's
 * `requestReviewAfterSuccess`. The gate is [RatingGate]; the ask comes a beat after the success lands.
 *
 * **Until the app is installed from a Play listing this does nothing:** Play only shows its review
 * card to an app it installed, so on a sideloaded or debug build the request completes with nothing
 * on screen (and the attempt still counts, as on iOS, where StoreKit never says either).
 */
object RatingPrompt {
    private fun gate(context: Context): RatingGate {
        val prefs = context.applicationContext.getSharedPreferences("rating", Context.MODE_PRIVATE)
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        return RatingGate(PrefsStore(prefs), version)
    }

    fun recordLaunch(context: Context) = gate(context).recordLaunch()

    fun recordSignificantAction(context: Context) = gate(context).recordSignificantAction()

    /**
     * Records a completed action and, if every gate is met, asks Play for its review card. Call from
     * the success screen, never after a failure.
     */
    suspend fun afterSuccess(context: Context) {
        val gate = gate(context)
        gate.recordSignificantAction()
        if (!gate.shouldRequestReview) return
        val activity = context.findActivity() ?: return
        gate.recordAttempt()
        // A beat after the success UI lands: asking mid-animation reads as an interruption.
        delay(1_200)
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnSuccessListener { info ->
            if (!activity.isFinishing && !activity.isDestroyed) manager.launchReviewFlow(activity, info)
        }
    }

    /** Whether Google Play installed this copy — only then does a Play link or review card mean anything. */
    fun installedFromPlay(context: Context): Boolean = runCatching {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        installer == "com.android.vending"
    }.getOrDefault(false)

    private class PrefsStore(private val prefs: SharedPreferences) : RatingGate.Store {
        override fun long(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0) else null
        override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
        override fun string(key: String): String? = prefs.getString(key, null)
        override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
