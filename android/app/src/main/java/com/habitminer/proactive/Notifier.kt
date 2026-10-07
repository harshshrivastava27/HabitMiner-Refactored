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
import androidx.core.app.RemoteInput
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
import com.habitminer.repository.TodayInsight
import com.habitminer.ui.MainActivity
import java.time.ZoneId

/**
 * Posts everything HabitMiner says outside the app.
 *
 * One channel per kind, so each can be silenced on its own in system settings: quick
 * questions, the insight of the day and summaries (both silent), nudges and reminders, and app
 * updates. Every notification hides its text on the lock screen, expires when it stops being
 * relevant and offers at most three actions.
 */
object Notifier {
    const val CHANNEL_QUESTIONS = "questions"
    const val CHANNEL_INSIGHTS = "insights"
    const val CHANNEL_SUMMARIES = "summaries"
    const val CHANNEL_NUDGES = "nudges"
    const val CHANNEL_UPDATES = "updates"

    /** Channels from earlier builds, replaced by the ones above. */
    private val OLD_CHANNELS = listOf("checkins", "weekly_digest", "deviations")

    const val ID_CHECKIN = 2001
    const val ID_NUDGE = 2002
    const val ID_DIGEST = 2003
    const val ID_NAP = 2004
    const val ID_PERIOD = 2005
    const val ID_DEVIATIONS = 2006
    const val ID_INSIGHT = 2007
    const val ID_GOAL = 2008
    const val ID_UPDATE = 2009
    const val ID_NOTIFICATION_ASK = 2010

    const val EXTRA_OPEN = "open"
    const val OPEN_CHECKIN = "checkin"
    const val OPEN_INSIGHTS = "insights"
    const val OPEN_DEVIATIONS = "deviations"
    const val OPEN_SLEEP = "sleep"
    const val OPEN_STORY = "story"
    const val OPEN_TRENDS = "trends"
    const val OPEN_GOALS = "goals"
    const val EXTRA_PROMPTED_AT = "prompted_at"
    const val EXTRA_INSIGHT_KEY = "insight_key"

    /** RemoteInput key for typed check-in answers. */
    const val KEY_REPLY = "checkin_reply"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        OLD_CHANNELS.forEach { runCatching { nm.deleteNotificationChannel(it) } }
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_QUESTIONS, "Quick questions", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "\"What are you doing?\", \"Were you asleep?\" and \"What's going on?\". They share a limit of three a day with everything else."
                },
                NotificationChannel(CHANNEL_INSIGHTS, "Insight of the day", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "At most one a day, only when something about your day stands out. Silent."
                },
                NotificationChannel(CHANNEL_SUMMARIES, "Summaries", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "The evening note on unusual days and the Sunday Weekly Story. Silent."
                },
                NotificationChannel(CHANNEL_NUDGES, "Nudges and reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Late-night and long-stretch nudges, and the app-time reminders you set in Goals"
                },
                NotificationChannel(CHANNEL_UPDATES, "App updates", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "When a new version of HabitMiner Extended is out"
                },
            ),
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

    fun channelEnabled(
        context: Context,
        channel: String,
    ): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun openApp(
        context: Context,
        requestCode: Int,
        open: String?,
        promptedAt: Long? = null,
        insightKey: String? = null,
    ): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (open != null) putExtra(EXTRA_OPEN, open)
                if (promptedAt != null) putExtra(EXTRA_PROMPTED_AT, promptedAt)
                if (insightKey != null) putExtra(EXTRA_INSIGHT_KEY, insightKey)
            }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun broadcast(
        context: Context,
        requestCode: Int,
        action: String,
        key: String,
        value: String,
        keys: Array<String>? = null,
        promptedAt: Long? = null,
        mutable: Boolean = false,
    ): PendingIntent {
        val intent =
            Intent(context, CheckInReceiver::class.java).apply {
                this.action = action
                putExtra(CheckInReceiver.EXTRA_KEY, key)
                putExtra(CheckInReceiver.EXTRA_VALUE, value)
                if (keys != null) putExtra(CheckInReceiver.EXTRA_KEYS, keys)
                if (promptedAt != null) putExtra(EXTRA_PROMPTED_AT, promptedAt)
            }
        // A direct reply needs a mutable intent so the system can add the typed text.
        val flags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    /** A plain stand-in shown on the lock screen instead of the real text. */
    private fun publicVersion(
        context: Context,
        channel: String,
        text: String,
    ) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(com.habitminer.R.drawable.ic_stat_habitminer)
        .setContentTitle("HabitMiner")
        .setContentText(text)
        .build()

    private fun base(
        context: Context,
        channel: String,
        lockScreenText: String,
    ) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(com.habitminer.R.drawable.ic_stat_habitminer)
        .setAutoCancel(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(publicVersion(context, channel, lockScreenText))

    @Suppress("MissingPermission")
    private fun post(
        context: Context,
        id: Int,
        builder: NotificationCompat.Builder,
    ) {
        if (!canPost(context)) return
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    /**
     * "What are you doing?" with your two likeliest answers for this time of day as buttons,
     * and a reply field for anything else.
     */
    fun postCheckIn(
        context: Context,
        promptedAt: Long,
        likely: List<CheckInOption> = listOf(CheckInOption.STUDYING, CheckInOption.RELAXING),
    ) {
        val reply =
            RemoteInput.Builder(KEY_REPLY)
                .setLabel("What are you doing?")
                .build()
        val replyAction =
            NotificationCompat.Action.Builder(
                0,
                "Something else",
                broadcast(context, 109, CheckInReceiver.ACTION_ANSWER, "", "reply", promptedAt = promptedAt, mutable = true),
            ).addRemoteInput(reply).setAllowGeneratedReplies(false).build()
        val builder =
            base(context, CHANNEL_QUESTIONS, "A quick question")
                .setContentTitle("Quick check-in")
                .setContentText("What are you doing right now? One tap helps HabitMiner learn.")
                .setContentIntent(openApp(context, 10, OPEN_CHECKIN, promptedAt))
                .setTimeoutAfter(45 * 60 * 1000L)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
        likely.take(2).forEachIndexed { i, option ->
            builder.addAction(0, option.label, broadcast(context, 100 + i, CheckInReceiver.ACTION_ANSWER, "", option.key, promptedAt = promptedAt))
        }
        builder.addAction(replyAction)
        post(context, ID_CHECKIN, builder)
    }

    fun postNudge(
        context: Context,
        nudge: Nudge,
    ) {
        val builder =
            base(context, CHANNEL_NUDGES, "A nudge from HabitMiner")
                .setContentTitle(nudge.title)
                .setContentText(nudge.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(nudge.body))
                .setContentIntent(openApp(context, 12, null))
                .setTimeoutAfter(30 * 60 * 1000L)
        post(context, ID_NUDGE, builder)
    }

    /** The insight of the day, with "why this" and Useful / Fewer like this buttons. */
    fun postInsight(
        context: Context,
        insight: TodayInsight,
    ) {
        val open =
            when {
                insight.open == "sleep" -> OPEN_SLEEP
                insight.open == "story" -> OPEN_STORY
                insight.open == "trends" -> OPEN_TRENDS
                insight.open.startsWith("app:") -> insight.open
                else -> OPEN_DEVIATIONS
            }
        val text = "${insight.body}\n\nWhy this: ${insight.why}"
        val builder =
            base(context, CHANNEL_INSIGHTS, "Your insight of the day")
                .setContentTitle(insight.title)
                .setContentText(insight.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp(context, 18, open, insightKey = insight.key))
                .setTimeoutAfter(6 * 60 * 60 * 1000L)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .addAction(0, "Useful", broadcast(context, 180, CheckInReceiver.ACTION_INSIGHT, insight.key, "useful"))
                .addAction(0, "Fewer like this", broadcast(context, 181, CheckInReceiver.ACTION_INSIGHT, insight.key, "fewer"))
        post(context, ID_INSIGHT, builder)
    }

    fun postDigest(
        context: Context,
        digest: DigestBuilder.Digest,
    ) {
        val style = NotificationCompat.InboxStyle()
        digest.lines.forEach { style.addLine(it) }
        val builder =
            base(context, CHANNEL_SUMMARIES, "Your Weekly Story is ready")
                .setContentTitle(digest.title)
                .setContentText(digest.lines.firstOrNull() ?: "Tap to see your week")
                .setStyle(style)
                .setContentIntent(openApp(context, 13, OPEN_STORY))
                .setTimeoutAfter(24 * 60 * 60 * 1000L)
                .addAction(0, "Open Weekly Story", openApp(context, 130, OPEN_STORY))
        post(context, ID_DIGEST, builder)
    }

    fun postNapQuestion(
        context: Context,
        nap: NapCandidate,
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val range = "${Format.clock(nap.start, zone)}–${Format.clock(nap.end, zone)}"
        val why = if (nap.evidence.isEmpty()) "Your phone was untouched." else "Your phone was untouched: ${nap.evidence.joinToString(", ")}."
        val builder =
            base(context, CHANNEL_QUESTIONS, "A quick question")
                .setContentTitle("Were you asleep $range?")
                .setContentText(why)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$why One tap helps HabitMiner learn your naps."))
                .setContentIntent(openApp(context, 14, OPEN_SLEEP))
                .setTimeoutAfter(6 * 60 * 60 * 1000L)
                .addAction(0, "Yes, napping", broadcast(context, 140, CheckInReceiver.ACTION_NAP, nap.key, "asleep"))
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
            base(context, CHANNEL_QUESTIONS, "A quick question")
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$body Days you label are kept out of your usual pattern."))
                .setContentIntent(openApp(context, 15, OPEN_DEVIATIONS))
                .setTimeoutAfter(12 * 60 * 60 * 1000L)
                .addAction(0, PeriodOption.EXAMS.label, broadcast(context, 150, CheckInReceiver.ACTION_PERIOD, shift.key, PeriodOption.EXAMS.key))
                .addAction(0, PeriodOption.TRAVEL.label, broadcast(context, 151, CheckInReceiver.ACTION_PERIOD, shift.key, PeriodOption.TRAVEL.key))
                .addAction(0, "Something else", openApp(context, 16, OPEN_DEVIATIONS))
        post(context, ID_PERIOD, builder)
    }

    /**
     * The 21:00 note: today's notable differences, with today's insight folded in when it
     * wasn't sent on its own.
     */
    fun postDeviationSummary(
        context: Context,
        summary: DeviationSummary.Summary,
        insight: TodayInsight? = null,
    ) {
        val style = NotificationCompat.InboxStyle()
        insight?.let { style.addLine(it.title) }
        summary.lines.forEach { style.addLine(it) }
        val keys = summary.keys.toTypedArray()
        val builder =
            base(context, CHANNEL_SUMMARIES, "Your evening summary")
                .setContentTitle(summary.title)
                .setContentText(summary.lines.firstOrNull()?.substringAfter(": ") ?: "Tap to see what was different")
                .setStyle(style)
                .setContentIntent(openApp(context, 17, OPEN_DEVIATIONS))
                .setTimeoutAfter(12 * 60 * 60 * 1000L)
                .addAction(0, "Expected", broadcast(context, 170, CheckInReceiver.ACTION_DEVIATION, "", "EXPECTED", keys))
                .addAction(0, "Unusual", broadcast(context, 171, CheckInReceiver.ACTION_DEVIATION, "", "UNUSUAL", keys))
        post(context, ID_DEVIATIONS, builder)
    }

    /** "15 min on Instagram today", with a snooze. */
    fun postGoalReminder(
        context: Context,
        title: String,
        body: String,
        packageName: String?,
        snoozeKey: String,
    ) {
        val builder =
            base(context, CHANNEL_NUDGES, "A reminder you set")
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(openApp(context, 19, OPEN_GOALS))
                .setTimeoutAfter(60 * 60 * 1000L)
                .addAction(0, "Snooze 15 min", broadcast(context, 190, CheckInReceiver.ACTION_GOAL_SNOOZE, snoozeKey, "15"))
                .addAction(0, "OK", broadcast(context, 191, CheckInReceiver.ACTION_GOAL_SNOOZE, snoozeKey, "0"))
        post(context, ID_GOAL, builder)
    }

    fun cancel(
        context: Context,
        id: Int,
    ) = NotificationManagerCompat.from(context).cancel(id)

    fun cancelCheckIn(context: Context) = NotificationManagerCompat.from(context).cancel(ID_CHECKIN)
}
