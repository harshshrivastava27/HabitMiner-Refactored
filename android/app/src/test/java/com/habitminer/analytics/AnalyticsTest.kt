package com.habitminer.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

private val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
private const val MIN = 60_000L

/** 2026-10-03 is a Saturday. */
private fun d(day: Int): LocalDate = LocalDate.of(2026, 10, 1).plusDays((day - 1).toLong())

private fun at(
    date: LocalDate,
    h: Int,
    m: Int = 0,
): Long = TimeUtil.at(date, h, m, ZONE)

private fun s(
    app: String,
    date: LocalDate,
    h: Int,
    m: Int,
    minutes: Long,
    cat: AppCategory = AppCategory.SOCIAL,
): UsageSession {
    val start = at(date, h, m)
    return UsageSession("pkg.${app.lowercase()}", app, cat, start, start + minutes * MIN, minutes * MIN)
}

private fun sample(
    time: Long,
    lux: Float? = null,
    motion: Float? = null,
    charging: Boolean = false,
    place: String? = null,
) = ContextSample(time, lux, motion, charging, isScreenOn = lux != null, proximityNear = null, batteryLevel = 50, place = place)

class CategoryMapperTest {
    @Test
    fun `maps common apps to friendly categories`() {
        assertEquals(AppCategory.VIDEO, CategoryMapper.categorize("com.drifty.app", "YT (drifty)", "OTHER"))
        assertEquals(AppCategory.GAMES, CategoryMapper.categorize("com.topgames.evony", "Evony", "GAMING"))
        assertEquals(AppCategory.MESSAGING, CategoryMapper.categorize("org.telegram.messenger", "Telegram"))
        assertEquals(AppCategory.BROWSING, CategoryMapper.categorize("com.brave.browser", "Brave"))
        assertEquals(AppCategory.PRODUCTIVITY, CategoryMapper.categorize("com.anthropic.claude", "Claude"))
        assertEquals(AppCategory.MUSIC, CategoryMapper.categorize("com.google.android.apps.youtube.music", "YouTube Music"))
        assertEquals(AppCategory.OTHER, CategoryMapper.categorize("com.example.thing", "Thing"))
    }

    @Test
    fun `falls back to the stored category`() {
        assertEquals(AppCategory.SHOPPING, CategoryMapper.categorize("com.example.store", "Store", "SHOPPING"))
    }

    @Test
    fun `recognises system noise`() {
        assertTrue(CategoryMapper.isSystemNoise("com.iqoo.secure", "Phone management"))
        assertTrue(CategoryMapper.isSystemNoise("com.vendor.x", "Phone management"))
        assertFalse(CategoryMapper.isSystemNoise("com.whatsapp", "WhatsApp"))
    }
}

class SessionGrouperTest {
    @Test
    fun `splits on gaps longer than five minutes and totals apps`() {
        val list =
            listOf(
                s("A", d(1), 10, 0, 10),
                s("B", d(1), 10, 12, 5),
                s("A", d(1), 10, 18, 2),
                s("C", d(1), 11, 0, 3),
            )
        val groups = SessionGrouper.group(list)
        assertEquals(2, groups.size)
        assertEquals(listOf("A" to 12 * MIN, "B" to 5 * MIN), groups[0].appTotals)
        assertEquals(17 * MIN, groups[0].totalMs)
    }
}

class SleepDetectorTest {
    private val wake = d(3)

    @Test
    fun `finds the overnight gap and uses context for confidence`() {
        val sessions =
            listOf(
                s("Evony", d(2), 22, 0, 60),
                s("Evony", d(2), 23, 30, 120), // until 01:30
                s("Snapchat", wake, 9, 0, 5),
                s("WhatsApp", wake, 10, 0, 5),
            )
        val samples = listOf(sample(at(wake, 3, 0), charging = true), sample(at(wake, 5, 0), charging = true))
        val est = SleepDetector.detect(sessions, emptyList(), samples, wake, at(wake, 18), ZONE)!!

        assertEquals(at(wake, 1, 30), est.sleepStart)
        assertEquals(at(wake, 9, 0), est.wakeTime)
        assertEquals(Confidence.HIGH, est.confidence)
        assertEquals("Evony", est.lastAppBeforeSleep)
        assertEquals("Snapchat", est.firstAppAfterWake)
        assertEquals(60 * MIN, est.preSleepUseMs)
    }

    @Test
    fun `a quick unlock in the night is a brief wake-up`() {
        val sessions = listOf(s("A", d(2), 23, 0, 30), s("B", wake, 9, 0, 5))
        val est = SleepDetector.detect(sessions, listOf(at(wake, 3, 0)), emptyList(), wake, at(wake, 18), ZONE)!!
        assertEquals(at(d(2), 23, 30), est.sleepStart)
        assertEquals(at(wake, 9, 0), est.wakeTime)
        assertEquals(1, est.briefWakes.size)
    }

    @Test
    fun `real phone use in the night splits it`() {
        val sessions = listOf(s("A", d(2), 23, 0, 30), s("C", wake, 3, 0, 20), s("B", wake, 9, 0, 5))
        val est = SleepDetector.detect(sessions, emptyList(), emptyList(), wake, at(wake, 18), ZONE)!!
        assertEquals(at(wake, 3, 20), est.sleepStart)
        assertTrue(est.briefWakes.isEmpty())
    }

    @Test
    fun `no estimate before waking up`() {
        val sessions = listOf(s("A", d(2), 23, 0, 30))
        assertNull(SleepDetector.detect(sessions, emptyList(), emptyList(), wake, at(wake, 6), ZONE))
    }

    @Test
    fun `summary averages bedtimes across midnight`() {
        val a = SleepEstimate(wake, at(d(2), 23, 30), at(wake, 7), Confidence.HIGH, 0, null, null, null)
        val b = SleepEstimate(d(4), at(wake, 23, 59).plus(MIN).plus(30 * MIN), at(d(4), 8), Confidence.HIGH, 0, null, null, null)
        val sum = SleepDetector.summarize(listOf(a, b), ZONE)!!
        assertEquals(0, sum.avgBedtimeMinutes)
    }
}

class PickupAnalyzerTest {
    @Test
    fun `classifies pickups after notifications`() {
        val day = d(1)
        val unlocks = listOf(at(day, 10, 0), at(day, 11, 0), at(day, 12, 0))
        val notifs = listOf(TimedEvent(at(day, 9, 59), "pkg.whatsapp"), TimedEvent(at(day, 11, 30), "pkg.whatsapp"))
        val sessions = listOf(s("WhatsApp", day, 10, 0, 3), s("Evony", day, 11, 0, 20))
        val stats = PickupAnalyzer.analyze(unlocks, notifs, sessions, at(day, 0), at(day, 23)) { "WhatsApp" }

        assertEquals(3, stats.total)
        assertEquals(1, stats.afterNotification)
        assertEquals(1, stats.quickChecks) // 12:00 unlock with no app use
        assertEquals("WhatsApp", stats.topTriggers.first().appName)
        assertEquals(2, stats.selfInitiated)
    }
}

class ContextInsightsTest {
    @Test
    fun `reports dark use for an app`() {
        val day = d(1)
        val sessions = (0 until 4).map { s("Evony", day, 20 + it, 0, 30, AppCategory.GAMES) }
        val samples = (0 until 4).map { sample(at(day, 20 + it, 15), lux = 2f, motion = 0.1f) }
        val insights = ContextInsights.compute(sessions, samples, ZONE)
        assertTrue(insights.any { it.kind == InsightKind.DARK && it.headline.contains("Evony") })
    }

    @Test
    fun `no sensor data means no light insight`() {
        val day = d(1)
        val sessions = (0 until 4).map { s("Evony", day, 20 + it, 0, 30) }
        val insights = ContextInsights.compute(sessions, emptyList(), ZONE)
        assertFalse(insights.any { it.kind == InsightKind.DARK })
    }
}

class BlueprintTest {
    @Test
    fun `heatmap splits sessions across hours`() {
        val today = d(7)
        val map = Heatmap.build(listOf(s("A", today, 10, 30, 60)), today, ZONE)
        assertEquals(30, map.minutes.last()[10])
        assertEquals(30, map.minutes.last()[11])
        assertEquals("A", map.topApps.last()[10])
        assertEquals(10, map.busiestHour)
    }

    @Test
    fun `typical day uses comparable days and percentiles`() {
        // History starts on Oct 1 (skipped as partial). Weekend days Oct 3 and 4 each 60 min at 10:00.
        val sessions =
            listOf(s("A", d(1), 10, 0, 5), s("A", d(2), 10, 0, 30)) +
                listOf(d(3), d(4)).map { s("A", it, 10, 0, 60) } +
                listOf(d(10)).map { s("A", it, 8, 0, 15) } // Oct 10 is a Saturday (today)
        val today = d(10)
        val curve = TypicalDay.build(sessions, today, at(today, 12), ZONE)!!
        assertTrue(curve.selection.sameDayType)
        assertEquals(2, curve.selection.days.size)
        assertEquals(60, curve.median[24])
        assertEquals(15, curve.today.last().second)
    }

    @Test
    fun `percentile interpolates`() {
        assertEquals(15, TypicalDay.percentile(listOf(10, 20), 0.5))
        assertEquals(7, TypicalDay.percentile(listOf(7), 0.25))
    }

    @Test
    fun `clusters heavy nights and light mornings into two types`() {
        val sessions = mutableListOf(s("A", d(1), 9, 0, 5))
        for (i in 2..5) sessions.add(s("Game", d(i), 0, 0, 240))
        for (i in 6..9) sessions.add(s("Mail", d(i), 9, 0, 30))
        val types = DayTypeClusterer.cluster(sessions, d(10), ZONE)!!
        assertEquals(2, types.types.size)
        assertEquals("Busier days", types.types[0].name)
        assertTrue(types.types[0].description.contains("extra use 00–04"))
        assertEquals("Quieter days (weekdays)", types.types[1].name)
        assertEquals(types.dayToType[d(2)], types.dayToType[d(3)])
    }

    @Test
    fun `near-identical days are reported as one consistent type`() {
        val sessions = mutableListOf(s("A", d(1), 9, 0, 5))
        for (i in 2..9) sessions.add(s("Game", d(i), 0, 0, 120L + (i % 3) * 5L))
        val types = DayTypeClusterer.cluster(sessions, d(10), ZONE)!!
        assertEquals(1, types.types.size)
        assertEquals("Consistent days", types.types.single().name)
    }

    @Test
    fun `week comparison averages per day`() {
        val today = LocalDate.of(2026, 10, 20)
        val sessions = mutableListOf<UsageSession>()
        sessions.add(s("A", today.minusDays(15), 9, 0, 5)) // partial first day
        for (i in 8..14) sessions.add(s("A", today.minusDays(i.toLong()), 10, 0, 60))
        for (i in 1..7) sessions.add(s("A", today.minusDays(i.toLong()), 10, 0, 120))
        val cmp = WeekComparer.compare(sessions, today, ZONE)!!
        assertEquals(120 * MIN, cmp.currentPerDayMs)
        assertEquals(60 * MIN, cmp.previousPerDayMs)
        assertEquals("A", cmp.biggestMovers.first().name)
    }
}

class PredictabilityTest {
    @Test
    fun `a perfectly regular sequence beats the baselines`() {
        val apps = listOf("A", "B", "C")
        val sessions = mutableListOf<UsageSession>()
        for (day in 1..10) {
            var minute = 0
            repeat(30) { i ->
                sessions.add(s(apps[i % 3], d(day), 10 + minute / 60, minute % 60, 1))
                minute += 2
            }
        }
        val result = PredictabilityEvaluator.evaluate(sessions, at(d(10), 23), ZONE)!!
        assertEquals(1f, result.hitRate, 0.001f)
        assertTrue(result.hitRate > result.mostUsedBaseline)
        assertTrue(result.hitRate > result.randomBaseline)
    }

    @Test
    fun `too little data gives no score`() {
        assertNull(PredictabilityEvaluator.evaluate(listOf(s("A", d(1), 10, 0, 1)), at(d(1), 23), ZONE))
    }
}

class PatternGrouperTest {
    @Test
    fun `merges the same sequence across time slots`() {
        val rows =
            listOf(
                PatternRow("Snapchat → Telegram", "NIGHT", "WEEKDAY", 10, 1f, 5L),
                PatternRow("Snapchat → Telegram", "EVENING", "WEEKDAY", 8, 0.8f, 9L),
                PatternRow("WhatsApp → Snapchat", "EVENING", "WEEKDAY", 9, 0.9f, 3L),
            )
        val groups = PatternGrouper.group(rows)
        assertEquals(2, groups.size)
        val first = groups.first()
        assertEquals("Snapchat → Telegram", first.sequence)
        assertEquals("Weekday evenings & nights", first.whenText)
        assertEquals("Seen on 10 of the last 10 weekday nights", first.evidenceText)
        assertEquals(9L, first.lastSeen)
    }
}

class PromptPolicyTest {
    private val day = d(7) // Wednesday

    @Test
    fun `check-ins respect hours, windows and the daily cap`() {
        assertTrue(PromptPolicy.canSendCheckIn(true, true, emptyList(), at(day, 10), ZONE))
        assertFalse(PromptPolicy.canSendCheckIn(true, true, emptyList(), at(day, 23), ZONE))
        assertFalse(PromptPolicy.canSendCheckIn(true, false, emptyList(), at(day, 10), ZONE))
        assertFalse(PromptPolicy.canSendCheckIn(false, true, emptyList(), at(day, 10), ZONE))
        // Already one in this window.
        val sent = listOf(SentPrompt(PromptKind.CHECK_IN, at(day, 9, 5)))
        assertFalse(PromptPolicy.canSendCheckIn(true, true, sent, at(day, 12), ZONE))
        // Daily cap reached.
        val three = listOf(9, 13, 17).map { SentPrompt(PromptKind.NUDGE, at(day, it)) }
        assertFalse(PromptPolicy.canSendCheckIn(true, true, three, at(day, 20), ZONE))
    }

    @Test
    fun `late-night leisure stretch triggers a nudge`() {
        val sessions = listOf(s("Evony", day, 23, 0, 15, AppCategory.GAMES), s("Evony", day, 23, 16, 15, AppCategory.GAMES))
        val nudge = PromptPolicy.nudgeFor(true, sessions, sample(at(day, 23, 20), lux = 1f), emptyList(), at(day, 23, 31), ZONE)
        assertNotNull(nudge)
        assertEquals("late_night", nudge!!.key)
        assertTrue(nudge.body.contains("dark"))
    }

    @Test
    fun `work stretch does not trigger a nudge`() {
        val sessions = listOf(s("Docs", day, 23, 0, 40, AppCategory.PRODUCTIVITY))
        assertNull(PromptPolicy.nudgeFor(true, sessions, null, emptyList(), at(day, 23, 41), ZONE))
    }

    @Test
    fun `digest goes out once on Sunday evening`() {
        val sunday = d(4)
        assertTrue(PromptPolicy.shouldSendDigest(true, null, at(sunday, 19, 30), ZONE))
        assertFalse(PromptPolicy.shouldSendDigest(true, null, at(sunday, 18, 0), ZONE))
        assertFalse(PromptPolicy.shouldSendDigest(true, at(sunday, 19, 5), at(sunday, 20, 0), ZONE))
        assertTrue(PromptPolicy.shouldSendDigest(true, at(sunday, 19, 5), at(sunday.plusDays(7), 19, 30), ZONE))
    }
}

class PolicyAndFormatTest {
    @Test
    fun `sensing mode follows screen, motion and battery`() {
        assertEquals(SensingMode.ACTIVE, SensingPolicy.choose(true, true, false, 80))
        assertEquals(SensingMode.NORMAL, SensingPolicy.choose(true, false, false, 80))
        assertEquals(SensingMode.IDLE, SensingPolicy.choose(false, null, false, 80))
        assertEquals(SensingMode.NORMAL, SensingPolicy.choose(false, null, true, 80))
        assertEquals(SensingMode.LOW_BATTERY, SensingPolicy.choose(true, true, false, 10))
    }

    @Test
    fun `places get sensible names`() {
        val day = d(7)
        val samples =
            listOf(1, 2, 3).map { sample(at(day, it), place = "h1") } +
                listOf(10, 11, 12).map { sample(at(day, it), place = "c1") } +
                listOf(sample(at(day, 20), place = "x1"))
        val names = PlaceInference.suggestNames(samples, ZONE)
        assertEquals("Home", names["h1"])
        assertEquals("Campus", names["c1"])
        assertEquals("Place 1", names["x1"])
    }

    @Test
    fun `durations read naturally`() {
        assertEquals("2h 5m", Format.duration(125 * MIN))
        assertEquals("45m", Format.duration(45 * MIN))
        assertEquals("3h", Format.duration(180 * MIN))
        assertEquals("<1m", Format.duration(20_000))
        assertEquals("23:30", Format.clockFromMinutes(-30))
    }
}

class StepMathTest {
    private val day = LocalDate.of(2026, 10, 3)

    @Test
    fun `counts steps through the day and across a reboot`() {
        var st = StepMath.advance(null, day, 10_000)
        assertEquals(0L, st.stepsToday)
        st = StepMath.advance(st, day, 10_250)
        assertEquals(250L, st.stepsToday)
        st = StepMath.advance(st, day, 40) // rebooted: counter restarted
        assertEquals(290L, st.stepsToday)
        st = StepMath.advance(st, day, 100)
        assertEquals(350L, st.stepsToday)
    }

    @Test
    fun `new day starts from yesterday's last reading, but not after a long gap`() {
        val yesterday = StepMath.DayState(day.minusDays(1), base = 0, carried = 0, last = 5_000)
        assertEquals(120L, StepMath.advance(yesterday, day, 5_120).stepsToday)
        val stale = StepMath.DayState(day.minusDays(3), base = 0, carried = 0, last = 5_000)
        assertEquals(0L, StepMath.advance(stale, day, 9_000).stepsToday)
    }

    @Test
    fun `delta between readings handles first reading and reboot`() {
        assertEquals(0L, StepMath.delta(null, 500))
        assertEquals(30L, StepMath.delta(500, 530))
        assertEquals(12L, StepMath.delta(530, 12))
    }
}

class MotionFusionTest {
    @Test
    fun `steps lift a steady-hand accelerometer reading to moving`() {
        // Walking while holding the phone steady: low variance, but the step counter saw walking.
        assertEquals(Motion.STILL, ContextLabels.motion(0.1f, 0))
        assertEquals(Motion.MOVING, ContextLabels.motion(0.1f, 90))
        assertEquals(Motion.ACTIVE, ContextLabels.motion(0.1f, 320))
        assertTrue(ContextLabels.motionFromSteps(0.1f, 90))
        assertFalse(ContextLabels.motionFromSteps(1.5f, 90))
    }

    @Test
    fun `steps never pull the accelerometer down, and few steps alone stay unknown`() {
        assertEquals(Motion.ACTIVE, ContextLabels.motion(6f, 30))
        assertEquals(Motion.MOVING, ContextLabels.motion(1f, null))
        assertNull(ContextLabels.motion(null, 5))
        assertNull(ContextLabels.motion(null, -1))
        assertEquals(Motion.MOVING, ContextLabels.motion(null, 60))
    }

    @Test
    fun `sample overload uses recent steps`() {
        val s = ContextSample(0L, 50f, 0.05f, false, true, null, 80, recentSteps = 150)
        assertEquals(Motion.MOVING, ContextLabels.motion(s))
    }
}

class RecentStepsTest {
    private fun p(
        at: Long,
        c: Long,
    ) = StepMath.StepPoint(at, c)

    @Test
    fun `counts steps inside the window from the last report before it`() {
        val pts = listOf(p(0, 1_000), p(60_000, 1_040), p(150_000, 1_100), p(200_000, 1_180))
        // Window (80s, 200s]: baseline is the 60s report (1 040) -> 140 steps.
        assertEquals(140, StepMath.stepsInWindow(pts, 200_000, 120_000))
    }

    @Test
    fun `no reports in the window means no steps, no reports at all means unknown`() {
        val pts = listOf(p(0, 1_000), p(10_000, 1_030))
        assertEquals(0, StepMath.stepsInWindow(pts, 500_000, 120_000))
        assertNull(StepMath.stepsInWindow(emptyList(), 500_000, 120_000))
    }

    @Test
    fun `without a baseline only steps between reports in the window count`() {
        val pts = listOf(p(400_000, 5_000), p(430_000, 5_060))
        assertEquals(60, StepMath.stepsInWindow(pts, 450_000, 120_000))
    }

    @Test
    fun `a reboot inside the window is handled`() {
        val pts = listOf(p(0, 9_000), p(100_000, 9_050), p(150_000, 20))
        assertEquals(70, StepMath.stepsInWindow(pts, 160_000, 120_000))
    }

    @Test
    fun `trim keeps one baseline before the cutoff`() {
        val pts = listOf(p(0, 1), p(10, 2), p(900_000, 3), p(1_000_000, 4))
        val kept = StepMath.trim(pts, 1_000_000, 600_000)
        assertEquals(listOf(p(10, 2), p(900_000, 3), p(1_000_000, 4)), kept)
    }
}

class MotionMathTest {
    @Test
    fun `drops warm-up readings that replay a cached value`() {
        // A replayed resting value for 300 ms, then real walking oscillation.
        val warm = (0 until 15).map { (it * 20L) to 9.81f }
        val walk = (0 until 100).map { (300L + it * 20L) to (9.81f + 2f * kotlin.math.sin(it * 0.6f)) }
        val stats = MotionMath.stats(warm + walk, warmUpMs = 300)!!
        assertEquals(100, stats.count)
        assertEquals(Motion.MOVING, ContextLabels.motion(stats.variance))
    }

    @Test
    fun `identical accelerometer readings are rejected but a flat gyro is allowed`() {
        val flat = (0 until 50).map { (400L + it * 20L) to 9.81f }
        assertNull(MotionMath.stats(flat, warmUpMs = 300))
        assertNotNull(MotionMath.stats(flat.map { it.first to 0f }, warmUpMs = 300, rejectConstant = false))
    }

    @Test
    fun `too few readings after warm-up give no result`() {
        val few = (0 until 5).map { (400L + it * 20L) to (9.8f + it * 0.1f) }
        assertNull(MotionMath.stats(few, warmUpMs = 300))
    }
}

private fun stepsSample(
    time: Long,
    steps: Int,
    lux: Float? = null,
) = ContextSample(time, lux, null, false, isScreenOn = lux != null, proximityNear = null, batteryLevel = 50, stepsSinceLast = steps)

private fun clock(
    date: LocalDate,
    h: Int,
    m: Int,
): UsageSession {
    val start = at(date, h, m)
    return UsageSession("com.android.deskclock", "Clock", AppCategory.TOOLS, start, start + MIN, MIN)
}

class SleepWakeUpTest {
    private val day = d(5)

    private fun night(): List<UsageSession> =
        listOf(
            s("A", d(4), 23, 0, 30),
            s("A", day, 3, 0, 30),
            s("B", day, 7, 20, 1),
            s("C", day, 7, 50, 1),
            clock(day, 8, 10),
            s("A", day, 8, 40, 20),
        )

    @Test
    fun `brief wake-ups and an alarm don't end the night, walking does`() {
        val samples = listOf(stepsSample(at(day, 6, 0), 0), stepsSample(at(day, 8, 0), 0), stepsSample(at(day, 8, 35), 200))
        val est = SleepDetector.detect(night(), emptyList(), samples, day, at(day, 20), ZONE)!!
        assertEquals(at(day, 3, 30), est.sleepStart)
        assertEquals(at(day, 8, 10), est.wakeTime)
        assertEquals(2, est.briefWakes.size)
        assertEquals((4 * 60 + 40 - 2) * MIN, est.durationMs)
    }

    @Test
    fun `steps right after the first wake-up mean you got up`() {
        val samples = listOf(stepsSample(at(day, 7, 40), 150))
        val est = SleepDetector.detect(night(), emptyList(), samples, day, at(day, 20), ZONE)!!
        assertEquals(at(day, 7, 20), est.wakeTime)
        assertTrue(est.briefWakes.isEmpty())
    }
}

class NapDetectorTest {
    private val day = d(5)
    private val sessions = listOf(s("A", day, 13, 0, 10), s("B", day, 15, 0, 5))

    @Test
    fun `a still afternoon gap ending in a dark room is a likely nap`() {
        val samples = listOf(stepsSample(at(day, 14, 0), 0), stepsSample(at(day, 15, 1), 0, lux = 3f))
        val naps = NapDetector.candidates(sessions, emptyList(), samples, day, at(day, 18), ZONE, null)
        assertEquals(1, naps.size)
        assertEquals(at(day, 13, 10), naps[0].start)
        assertEquals(at(day, 15, 0), naps[0].end)
        assertEquals(Confidence.HIGH, naps[0].confidence)
    }

    @Test
    fun `walking during the gap is not a nap`() {
        val samples = listOf(stepsSample(at(day, 14, 0), 500))
        assertTrue(NapDetector.candidates(sessions, emptyList(), samples, day, at(day, 18), ZONE, null).isEmpty())
    }

    @Test
    fun `nap question is asked once, soon after waking`() {
        val nap = NapCandidate(at(day, 13, 10), at(day, 15, 0), Confidence.HIGH, emptyList())
        assertTrue(PromptPolicy.canAskNap(true, true, nap, emptySet(), emptyList(), at(day, 15, 20), ZONE))
        assertFalse(PromptPolicy.canAskNap(true, true, nap, setOf(nap.key), emptyList(), at(day, 15, 20), ZONE))
        assertFalse(PromptPolicy.canAskNap(true, true, nap, emptySet(), listOf(SentPrompt(PromptKind.NAP, at(day, 15, 5))), at(day, 15, 20), ZONE))
        assertFalse(PromptPolicy.canAskNap(true, true, nap, emptySet(), emptyList(), at(day, 19, 0), ZONE))
        assertFalse(PromptPolicy.canAskNap(true, true, nap.copy(confidence = Confidence.LOW), emptySet(), emptyList(), at(day, 15, 20), ZONE))
    }
}

class DeviationFinderTest {
    /** 18 regular days (A 10–13, B 20–21), two quieter days, then a nearly empty today. */
    private fun history(): List<UsageSession> {
        val out = mutableListOf<UsageSession>()
        for (day in 1..20) {
            out += s("A", d(day), 10, 0, if (day >= 19) 60L else 180L)
            out += s("B", d(day), 20, 0, 60)
        }
        out += s("B", d(21), 12, 0, 10)
        return out
    }

    private val now = at(d(21), 18)

    @Test
    fun `a much quieter day and a missing app are flagged`() {
        val report = DeviationFinder.find(history(), emptyList(), emptyList(), d(21), now, ZONE)
        val today = report.days.filter { it.date == d(21) }
        assertTrue(today.any { it.kind == DeviationKind.LESS_USE && it.partialDay })
        assertTrue(today.any { it.kind == DeviationKind.APP_DROP && it.subject == "A" })
        assertTrue(today.first { it.kind == DeviationKind.LESS_USE }.notable)
    }

    @Test
    fun `several quieter days in a row become a routine change`() {
        val shift = DeviationFinder.find(history(), emptyList(), emptyList(), d(21), now, ZONE).shift!!
        assertEquals(d(19), shift.since)
        assertTrue(shift.less)
        assertEquals("A", shift.appChanges.first().app)
    }

    @Test
    fun `a labelled period explains its days and keeps them out of usual`() {
        val periods = listOf(LabelledPeriod(d(19), d(21), "Exams"))
        val report = DeviationFinder.find(history(), emptyList(), emptyList(), d(21), now, ZONE, periods)
        assertEquals("Exams", report.shift?.label)
        val today = report.days.filter { it.date == d(21) }
        assertTrue(today.isNotEmpty())
        assertTrue(today.all { it.explainedBy == "Exams" && !it.notable })
    }

    @Test
    fun `evening summary only between 21 and 23, once`() {
        val dev = DayDeviation(d(21), DeviationKind.LESS_USE, "Quieter day", "x", "ALL", 3f, now, false)
        assertFalse(PromptPolicy.canSendDeviationSummary(true, listOf(dev), emptyList(), at(d(21), 20), ZONE))
        assertTrue(PromptPolicy.canSendDeviationSummary(true, listOf(dev), emptyList(), at(d(21), 21, 30), ZONE))
        assertFalse(
            PromptPolicy.canSendDeviationSummary(true, listOf(dev), listOf(SentPrompt(PromptKind.DEVIATIONS, at(d(21), 21))), at(d(21), 21, 30), ZONE),
        )
    }
}

class NextAppModelTest {
    @Test
    fun `share sheets and pickers are skipped when learning what comes next`() {
        val sessions = mutableListOf<UsageSession>()
        for (day in 1..6) {
            var minute = 0
            repeat(10) {
                sessions += s("A", d(day), 10 + minute / 60, minute % 60, 1)
                val pick = at(d(day), 10 + (minute + 1) / 60, (minute + 1) % 60)
                sessions += UsageSession("com.google.android.photopicker", "Photo picker", AppCategory.TOOLS, pick, pick + MIN, MIN)
                sessions += s("B", d(day), 10 + (minute + 2) / 60, (minute + 2) % 60, 1)
                minute += 4
            }
        }
        val after = sessions.filter { it.appName == "A" }.maxOf { it.end } + 30_000
        val guess = NextAppModel.predict(sessions.filter { it.start < after - 30_000 || it.appName == "A" }, after, ZONE)
        assertEquals("B", guess.first().appName)
        assertTrue(guess.none { it.appName == "Photo picker" })
    }
}

class SleepDaysTest {
    private fun night(
        wake: LocalDate,
        hours: Int,
    ) = SleepEstimate(wake, at(wake, 2), at(wake, 2 + hours), Confidence.HIGH, 0, null, null, null)

    @Test
    fun `naps are added to the day they happen, counted by wake date`() {
        val nights = listOf(night(d(4), 6), night(d(5), 4))
        val naps = listOf(at(d(5), 14) to at(d(5), 15, 30))
        val days = SleepDays.build(nights, naps, d(5), 7, ZONE)
        assertEquals(2, days.size)
        val today = days.last()
        assertEquals(4 * 60 * MIN, today.nightMs)
        assertEquals(90 * MIN, today.napMs)
        assertEquals((4 * 60 + 90) * MIN, today.totalMs)
        val week = SleepDays.summarize(days, ZONE)!!
        assertEquals(((6 * 60 + 5 * 60 + 30) * MIN) / 2, week.avgTotalMs)
        assertEquals(1, week.napCount)
    }

    @Test
    fun `a day with only a nap still counts`() {
        val days = SleepDays.build(emptyList(), listOf(at(d(5), 14) to at(d(5), 15)), d(5), 7, ZONE)
        assertEquals(1, days.size)
        assertNull(days[0].night)
        assertEquals(60 * MIN, days[0].totalMs)
    }

    // ---- Unlock merge (Extended) --------------------------------------------------------

    @Test
    fun unlockMergeCountsAnUnlockSeenByBothRecordsOnce() {
        val system = listOf(1_000_000L, 2_000_000L)
        val receiver = listOf(1_004_000L, 3_000_000L)
        assertEquals(listOf(1_000_000L, 2_000_000L, 3_000_000L), UnlockMerge.merge(system, receiver))
    }

    @Test
    fun unlockMergeKeepsEitherListWhenTheOtherIsEmpty() {
        assertEquals(listOf(5L, 9L), UnlockMerge.merge(emptyList(), listOf(5L, 9L)))
        assertEquals(listOf(5L, 9L), UnlockMerge.merge(listOf(5L, 9L), emptyList()))
    }

    @Test
    fun unlockMergeKeepsReceiverUnlocksFarFromSystemOnes() {
        val system = listOf(100_000L)
        val receiver = listOf(10_000L, 100_010L, 200_000L)
        assertEquals(listOf(10_000L, 100_000L, 200_000L), UnlockMerge.merge(system, receiver))
    }
}
