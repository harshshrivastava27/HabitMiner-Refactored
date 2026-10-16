# Reported bugs

Bugs found while using HabitMiner Extended day to day. Each entry is logged when it's
reported and left open until it's fixed; nothing here has been changed in the code yet.

| ID | Reported | Area | Summary | Status |
|----|----------|------|---------|--------|
| B1 | 16 Oct 2026 | Sleep | A quiet alarm that went unheard was taken as waking up | Open |

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
