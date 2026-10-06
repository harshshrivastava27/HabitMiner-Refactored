package com.habitminer.proactive

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.habitminer.analytics.CheckInOption
import com.habitminer.analytics.DeviationFinder
import com.habitminer.analytics.DeviationSummary
import com.habitminer.analytics.DigestBuilder
import com.habitminer.analytics.Format
import com.habitminer.analytics.NapCandidate
import com.habitminer.analytics.Nudge
import com.habitminer.analytics.PeriodOption
import com.habitminer.analytics.RoutineShift
import java.time.ZoneId
import com.habitminer.ui.MainActivity

/** Posts check-ins, nudges, nap and routine-change questions, the evening summary and the weekly digest. */
object Notifier {
    const val CHANNEL_CHECKINS = "checkins"
    const val CHANNEL_NUDGES = "nudges"
    const val CHANNEL_DIGEST = "weekly_digest"
    const val CHANNEL_DEVIATIONS = "deviations"

    const val ID_CHECKIN = 2001
    const val ID_NUDGE = 2002
    const val ID_DIGEST = 2003
    const val ID_NAP = 2004
    const val ID_PERIOD = 2005
    const val ID_DEVIATIONS = 2006

    const val EXTRA_OPEN = "open"
    const val OPEN_CHECKIN = "checkin"
    const val OPEN_INSIGHTS = "insights"
    const val OPEN_DEVIATIONS = "deviations"
    const val EXTRA_PROMPTED_AT = "prompted_at"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CHECKINS, "Quick check-ins", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Up to 3 short \"what are you doing?\" questions a day, used to check the app's guesses"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_NUDGES, "Gentle nudges", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A heads-up after long stretches of scrolling or gaming, especially late at night"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DIGEST, "Weekly summary", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A short summary of your week, every Sunday evening"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DEVIATIONS, "Unusual days", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "An evening note on days that were clearly different from usual, so you can say if it was expected"
            },
        )
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun openApp(
        context: Context,
        requestCode: Int,
        open: String?,
        promptedAt: Long? = null,
    ): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (open != null) putExtra(EXTRA_OPEN, open)
                if (promptedAt != null) putExtra(EXTRA_PROMPTED_AT, promptedAt)
            }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun answer(
        context: Context,
        option: CheckInOption,
        promptedAt: Long,
    ): PendingIntent {
        val intent =
            Intent(context, CheckInReceiver::class.java).apply {
                action = CheckInReceiver.ACTION_ANSWER
                putExtra(CheckInReceiver.EXTRA_VALUE, option.key)
                putExtra(EXTRA_PROMPTED_AT, promptedAt)
            }
        return PendingIntent.getBroadcast(
            context,
            100 + option.ordinal,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    @Suppress("MissingPermission")
    private fun post(
        context: Context,
        id: Int,
        builder: NotificationCompat.Builder,
    ) {
        if (!canPost(context)) return
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    fun postCheckIn(
        context: Context,
        promptedAt: Long,
    ) {
        val builder =
            NotificationCompat.Builder(context, CHANNEL_CHECKINS)
                .setSmallIcon(android.R.drawable.ic_menu_help)
                .setContentTitle("Quick check-in")
                .setContentText("What are you doing right now? One tap helps HabitMiner learn.")
                .setContentIntent(openApp(context, 10, OPEN_CHECKIN, promptedAt))
                .setAutoCancel(true)
                .setTimeoutAfter(45 * 60 * 1000L)
                .addAction(0, "${CheckInOption.STUDYING.emoji} Studying", answer(context, CheckInOption.STUDYING, promptedAt))
                .addAction(0, "${CheckInOption.RELAXING.emoji} Relaxing", answer(context, CheckInOption.RELAXING, promptedAt))
                .addAction(0, "More…", openApp(context, 11, OPEN_CHECKIN, promptedAt))
        post(context, ID_CHECKIN, builder)
    }

    fun postNudge(
        context: Context,
        nudge: Nudge,
    ) {
        val builder =
            NotificationCompat.Builder(context, CHANNEL_NUDGES)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle(nudge.title)
                .setContentText(nudge.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(nudge.body))
                .setContentIntent(openApp(context, 12, null))
                .setAutoCancel(true)
        post(context, ID_NUDGE, builder)
    }

    fun postDigest(
        context: Context,
        digest: DigestBuilder.Digest,
    ) {
        val style = NotificationCompat.InboxStyle()
        digest.lines.forEach { style.addLine(it) }
        val builder =
            NotificationCompat.Builder(context, CHANNEL_DIGEST)
                .setSmallIcon(android.R.drawable.ic_menu_week)
                .setContentTitle(digest.title)
                .setContentText(digest.lines.firstOrNull() ?: "Tap to see your week")
                .setStyle(style)
                .setContentIntent(openApp(context, 13, OPEN_INSIGHTS))
                .setAutoCancel(true)
        post(context, ID_DIGEST, builder)
    }

    private fun broadcast(
        context: Context,
        requestCode: Int,
        action: String,
        key: String,
        value: String,
        keys: Array<String>? = null,
    ): PendingIntent {
        val intent =
            Intent(context, CheckInReceiver::class.java).apply {
                this.action = action
                putExtra(CheckInReceiver.EXTRA_KEY, key)
                putExtra(CheckInReceiver.EXTRA_VALUE, value)
                if (keys != null) putExtra(CheckInReceiver.EXTRA_KEYS, keys)
            }
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun postNapQuestion(
        context: Context,
        nap: NapCandidate,
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val range = "${Format.clock(nap.start, zone)}–${Format.clock(nap.end, zone)}"
        val why = if (nap.evidence.isEmpty()) "Your phone was untouched." else "Your phone was untouched: ${nap.evidence.joinToString(", ")}."
        val builder =
            NotificationCompat.Builder(context, CHANNEL_CHECKINS)
                .setSmallIcon(android.R.drawable.ic_menu_help)
                .setContentTitle("Were you asleep $range?")
                .setContentText(why)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$why One tap helps HabitMiner learn your naps."))
                .setContentIntent(openApp(context, 14, null))
                .setAutoCancel(true)
                .setTimeoutAfter(6 * 60 * 60 * 1000L)
                .addAction(0, "😴 Yes, napping", broadcast(context, 140, CheckInReceiver.ACTION_NAP, nap.key, "asleep"))
                .addAction(0, "No", broadcast(context, 141, CheckInReceiver.ACTION_NAP, nap.key, "awake"))
        post(context, ID_NAP, builder)
    }

    fun postPeriodQuestion(
        context: Context,
        shift: RoutineShift,
    ) {
        val pct = kotlin.math.abs(shift.change * 100).toInt()
        val title = "Your routine has changed since ${DeviationFinder.shortDate(shift.since)}"
        val body = "About $pct% ${if (shift.less) "less" else "more"} phone time than usual. What's going on?"
        val builder =
            NotificationCompat.Builder(context, CHANNEL_CHECKINS)
                .setSmallIcon(android.R.drawable.ic_menu_help)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$body Days you label are kept out of your usual pattern."))
                .setContentIntent(openApp(context, 15, OPEN_DEVIATIONS))
                .setAutoCancel(true)
                .addAction(0, "${PeriodOption.EXAMS.emoji} Exams", broadcast(context, 150, CheckInReceiver.ACTION_PERIOD, shift.key, PeriodOption.EXAMS.key))
                .addAction(0, "${PeriodOption.TRAVEL.emoji} Travel", broadcast(context, 151, CheckInReceiver.ACTION_PERIOD, shift.key, PeriodOption.TRAVEL.key))
                .addAction(0, "Other…", openApp(context, 16, OPEN_DEVIATIONS))
        post(context, ID_PERIOD, builder)
    }

    fun postDeviationSummary(
        context: Context,
        summary: DeviationSummary.Summary,
    ) {
        val style = NotificationCompat.InboxStyle()
        summary.lines.forEach { style.addLine(it) }
        val keys = summary.keys.toTypedArray()
        val builder =
            NotificationCompat.Builder(context, CHANNEL_DEVIATIONS)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle(summary.title)
                .setContentText(summary.lines.firstOrNull()?.substringAfter(": ") ?: "Tap to see what was different")
                .setStyle(style)
                .setContentIntent(openApp(context, 17, OPEN_DEVIATIONS))
                .setAutoCancel(true)
                .addAction(0, "Expected", broadcast(context, 170, CheckInReceiver.ACTION_DEVIATION, "", "EXPECTED", keys))
                .addAction(0, "Unusual", broadcast(context, 171, CheckInReceiver.ACTION_DEVIATION, "", "UNUSUAL", keys))
        post(context, ID_DEVIATIONS, builder)
    }

    fun cancel(
        context: Context,
        id: Int,
    ) = NotificationManagerCompat.from(context).cancel(id)

    fun cancelCheckIn(context: Context) = NotificationManagerCompat.from(context).cancel(ID_CHECKIN)
}
