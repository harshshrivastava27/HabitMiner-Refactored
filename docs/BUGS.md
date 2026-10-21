# Reported bugs

Bugs found while using HabitMiner Extended day to day. Each entry is logged when it's
reported and left open until it's fixed; nothing here has been changed in the code yet.

| ID | Reported | Area | Summary | Status |
|----|----------|------|---------|--------|
| B1 | 16 Oct 2026 | Sleep | A quiet alarm that went unheard was taken as waking up | Open |
| B2 | 18 Oct 2026 | Screen time | Today's total misses use the heatmap shows; day totals are off | Open |
| B3 | 18 Oct 2026 | Insights | "Used your phone while on the move 307 times" is far too high | Open |
| B4 | 21 Oct 2026 | Naps | A nap after Reddit wasn't found, no nap notification, and naps can't be added by hand | Open |

---

## B1. A ringing alarm counts as waking up

**Reported:** Fri 16 Oct 2026
**Area:** Sleep (also screen time and the hourly heatmap)
**Status:** Open, not fixed yet

**What happened**
- Sleep showed last night as **4h 49m, about 03:20 to 08:10**, with "alarm set for 08:10"
  given as evidence.
- The alarm rang at 08:10 at a volume too low to hear, so I slept through it. It kept
  re-ringing after each snooze. History shows the Clock app in front three times:
  08:10–08:20, 08:30–08:40 and 08:50–09:00 (about 9 minutes each).
- I actually got up around **09:10**, when I first used the phone (Clock, Gmail, Snapchat,
  09:10–09:29).

**Expected**
- Woke around 09:10. That makes the night about 5h 50m.
- The alarm screens shouldn't count as screen time. Today they added about 27 minutes of
  "Clock" use, and the heatmap shows Friday 08:00–09:00 as "29 min, mostly Clock".

**Likely cause** (from reading the code, not yet confirmed)
- The alarm screen appears over the lock screen, so Android logs the Clock app as being in
  front even though nobody unlocked the phone. The session builder counts that as app use.
- `SleepDetector` only treats a wake-up as brief (part of the night) when Clock use is 5
  minutes or less (`ActivityCluster.isBrief`). An alarm that rings for its full ~10 minutes
  goes over that, so the night ends at the first ring.
- The next-alarm signal then backs up the wrong wake time ("alarm set for 08:10") instead
  of questioning it.

**Ideas for the fix** (later)
- Don't count foreground time while the lock screen is still showing (no unlock before it)
  as use, for sleep and for screen time. Alarm and timer screens are the main case.
- Recognise a ringing alarm: the Clock app in front over the lock screen at a set alarm
  time, repeating at snooze intervals.
- End the night at the first real unlock after the alarm, not at the alarm itself.
- Add a test with this exact night: asleep 03:20, alarm 08:10 with two snoozes, up at 09:10.

---

## B2. Screen time total doesn't match the heatmap

**Reported:** Sun 18 Oct 2026 (noticed over the previous few days)
**Area:** Screen time on Today, the notification, the widget, the Trends curve and day totals
**Status:** Open, not fixed yet

**What happened**
- At 01:24 on Sunday, Today showed **23m** screen time ("29m less than a usual weekend
  day by now"). The Today bar chart and the Trends "Today" line agreed with 23m.
- The heatmap on Trends showed **Sunday 00:00–01:00: 60 min, mostly Stremio** for the
  same hour. I was watching Stremio that whole time.
- Yesterday's total was also clearly wrong.

**Expected**
- Every screen shows the same number for the same time: about 60m by 01:00 here.
- A day's total counts the time actually used during that day.

**Likely cause** (from reading the code, not yet confirmed)
- The headline total, the Today bars, the notification, the widget and the "Today" curve
  only count sessions that **started** today (`getTodayUsage` and `getUsageSince` filter on
  `startTime >= startOfDay`), and each session's full length goes to the day it started.
- The heatmap instead splits every session across the hours it actually covered
  (`Heatmap.build`), so it gets this right.
- So a Stremio session that started before midnight and ran past it was missing from
  Sunday's total, while all of it, including the part after midnight, went to Saturday.
  That would make Saturday too high and Sunday too low. Late-night sessions are common for
  me, so day totals have been off by a lot.
- Possibly adding to it: merging repeat visits to the same app keeps the first visit's
  start time, so a visit after midnight can be folded into a session from before it. Long
  sessions are also cut off at 4 hours (`MAX_OPEN_SESSION_MS`), which a long film could hit.

**Ideas for the fix** (later)
- Work out every total by clipping sessions to the day (and hour), the way the heatmap
  does, and query sessions that overlap the day rather than ones that start in it.
- Use the same per-day figure for history, "usual day", goals, Weekly Story and exports.
- Check against Digital Wellbeing for a day with a session across midnight.
- Add a test: a session 23:30 to 01:00 adds 30m to one day and 60m to the next.

---

## B3. "Used your phone while on the move 307 times" is far too high

**Spotted:** Sun 18 Oct 2026, in the Today screenshot for B2; confirmed wrong the same night
**Area:** The "Worth knowing" insight on Today
**Status:** Open, not fixed yet

**What it showed**
- "Used your phone while on the move 307 times. Mostly Snapchat. Detected with the motion
  sensor."

**Why it looks wrong**
- 307 times in a week is far more than the walking or travelling I actually did. It's
  probably counting every reading or session where the motion sensor saw any movement,
  such as moving the phone around in my hand, instead of times I was really walking or
  in a vehicle.

**Likely cause** (from reading the code, not yet confirmed)
- `ContextInsights` counts **app sessions**, not trips: every session whose nearest sensor
  reading wasn't "still" adds one. 307 is roughly how many app opens (mostly short
  Snapchat ones) had a "moving" reading nearby.
- Readings are taken when the phone is unlocked or an app is opened, which is exactly
  when it's being picked up and handled. So the accelerometer often reads "moving" while
  I'm sitting down.
- The nearest reading can also be some distance in time from the session it's applied to.

**Ideas for the fix** (later)
- Count distinct stretches of moving (for example 5 minutes or more), not sessions.
- Only call it moving when the step counter shows steps or Android's activity recognition
  says walking, running, cycling or in a vehicle. Ignore accelerometer movement from
  handling the phone.
- Only use a reading taken close to the session.

---

## B4. Missed nap, no nap notification, and no way to add naps

**Reported:** Wed 21 Oct 2026
**Area:** Naps (Sleep screen, Today, notifications)
**Status:** Open, not fixed yet

**What happened**
- On Wednesday I slept after using Reddit (heatmap: 10:00–11:00, 15 min, mostly Reddit).
  The phone was barely touched for a few hours after that: 11:00–12:00 had 4 min (Google),
  12:00–13:00 nothing, 13:00–14:00 had 2 min (WhatsApp), 14:00–15:00 had 7 min (Instagram).
  No nap was recorded.
- I never got a "Were you asleep?" notification. The only way to see or answer nap
  questions is to keep opening the app.
- There's no way to add a nap myself, let alone more than one in a day.

**Expected**
- A nap from roughly 11:00 to early afternoon is found, or at least asked about.
- A notification asks about it soon after I pick the phone up again.
- I can add naps by hand, several per day, and edit or remove them, like fixing a night.

**Likely cause** (from reading the code, not yet confirmed)
- `NapDetector` joins a nap across a pickup only if the pickup is "brief": at most 3 min of
  use within 10 min (`ActivityCluster.isBrief`). The 4 min of Google at 11:xx is over that,
  so the nap is cut there. The pieces left over may each be under the 45 min minimum, or the
  trimming of "dozing off" gaps at the start (under 30 min) may eat the rest.
- A candidate with low confidence (for example no darkness or charging seen when I picked
  the phone up) is never shown on Today and never notified.
- The notification only goes out if every one of these holds: the screen is on when the
  background check runs, it's within 3 hours of the nap ending, it isn't quiet hours, it's
  a "receptive" moment, check-in questions are on, and the shared limit of 3 a day isn't
  used up. Only the most recent unanswered nap is ever asked about.
- Adding sleep by hand exists only for nights (`FixNightSheet`), not for naps.

**Ideas for the fix** (later)
- Allow a few minutes of quick use inside a nap (checking a message), as long as there are
  no steps and the gaps on both sides are long.
- Still ask about low-confidence candidates, just with softer wording.
- Ask right after the next unlock following the nap, with nap questions not held back by
  the daily limit, and show any unanswered naps on Today and on Sleep.
- Add an "Add a nap" sheet on Sleep (start and end time, any day this week), allowing
  several per day, editable and removable. Hand-added naps should count like confirmed ones.
- Checked: this week's "Naps 7, 15h 51m in total" is correct, so the nap totals are fine.
