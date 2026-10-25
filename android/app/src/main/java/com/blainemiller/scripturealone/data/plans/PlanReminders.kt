package com.blainemiller.scripturealone.data.plans

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.blainemiller.scripturealone.MainActivity
import com.blainemiller.scripturealone.R
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** The app's plan services: one store and one study cache, in the app's files directory (Auto Backup carries `plans/plans.json`, not the saved study). */
object PlanServices {
    @Volatile private var store: PlanStore? = null
    @Volatile private var cache: StudyCache? = null

    fun store(context: Context): PlanStore = store ?: synchronized(this) {
        store ?: PlanStore(File(context.applicationContext.filesDir, "plans"), BundledPlans.load(context.applicationContext)).also { store = it }
    }

    /** The importer with PDFBox reading a church's yearly plan PDF. */
    fun importer(context: Context): PlanImporter = PlanImporter(HttpUrlFetcher(), PlanPdfText.extractor(context))

    fun studyCache(context: Context): StudyCache = cache ?: synchronized(this) {
        cache ?: StudyCache(File(context.applicationContext.filesDir, "plan-studies")).also { cache = it }
    }
}

/** A reminder due at [at]: "Today's reading: [display]". Pure — `PlanReminders.requests` on iOS. */
data class PlanReminder(val planId: String, val planTitle: String, val at: LocalDateTime, val display: String)

object PlanReminderRules {
    const val HORIZON_DAYS = 14

    /** The reminders for the next [HORIZON_DAYS]: each plan with a reminder time, each unread scheduled day, those still ahead of [now]. */
    fun upcoming(store: PlanStore, now: LocalDateTime): List<PlanReminder> {
        val out = mutableListOf<PlanReminder>()
        for (progress in store.progress) {
            val hour = progress.reminderHour ?: continue
            val minute = progress.reminderMinute ?: continue
            val schedule = store.schedule(progress.planId) ?: continue
            for (offset in 0 until HORIZON_DAYS) {
                val date: LocalDate = now.toLocalDate().plusDays(offset.toLong())
                val index = schedule.dayIndex(date) ?: continue
                if (index in progress.completed) continue
                val at = LocalDateTime.of(date, LocalTime.of(hour, minute))
                if (at <= now) continue
                out += PlanReminder(progress.planId, schedule.plan.title, at, schedule.plan.days[index].display)
            }
        }
        return out.sortedBy { it.at }
    }

    /** Reminders that came due in the last [graceHours] — what a late (inexact) alarm still owes. */
    fun due(store: PlanStore, now: LocalDateTime, graceHours: Long = 6): List<PlanReminder> {
        val from = now.minusHours(graceHours)
        val out = mutableListOf<PlanReminder>()
        for (progress in store.progress) {
            val hour = progress.reminderHour ?: continue
            val minute = progress.reminderMinute ?: continue
            val schedule = store.schedule(progress.planId) ?: continue
            val date = now.toLocalDate()
            val index = schedule.dayIndex(date) ?: continue
            val at = LocalDateTime.of(date, LocalTime.of(hour, minute))
            if (index in progress.completed || at > now || at < from) continue
            out += PlanReminder(progress.planId, schedule.plan.title, at, schedule.plan.days[index].display)
        }
        return out
    }
}

/**
 * Daily reminders: one inexact alarm armed for the next reminder, which posts the notification and arms
 * the one after. Inexact on purpose — no exact-alarm permission for a reading prompt — and re-armed at
 * boot and whenever the app opens or progress changes. The notification opens the plans screen.
 */
object PlanReminders {
    const val CHANNEL = "plan_reminders"
    const val ACTION_FIRE = "com.blainemiller.scripturealone.action.PLAN_REMINDER"

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, PlanReminderReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Arms the alarm for the next reminder, or cancels it when there is none. */
    fun refresh(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val next = PlanReminderRules.upcoming(PlanServices.store(app), now).firstOrNull()
        if (next == null) {
            alarms.cancel(pending(app))
            return
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pending(app))
    }

    fun post(context: Context, reminders: List<PlanReminder>) {
        val app = context.applicationContext
        if (reminders.isEmpty()) return
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.plans_notif_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            app, 1, Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("scripturealone://plans"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        for ((i, r) in reminders.withIndex()) {
            val n = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_listen_notification)
                .setContentTitle(PlanServices.store(app).plan(r.planId)?.let(PlanText::title) ?: r.planTitle)
                .setContentText(app.getString(R.string.plans_notif_body, r.display))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(app).notify(2000 + i, n) } // no permission: nothing shown
        }
    }
}

class PlanReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val now = LocalDateTime.now()
        if (intent.action == PlanReminders.ACTION_FIRE) {
            PlanReminders.post(context, PlanReminderRules.due(PlanServices.store(context), now))
        }
        PlanReminders.refresh(context, now)
    }
}
