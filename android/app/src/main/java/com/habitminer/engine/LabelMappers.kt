package com.habitminer.engine

import com.habitminer.analytics.LabelledPeriod
import com.habitminer.analytics.PeriodOption
import com.habitminer.data.UserLabelEntity
import java.time.LocalDate

/** Turns stored labels into what the analytics need: set-aside periods, confirmed naps, answers. */
object LabelMappers {
    private val fromRe = Regex("\"from\":\"([0-9-]+)\"")
    private val untilRe = Regex("\"until\":\"([0-9-]+)\"")

    /** Periods you labelled (exams, travel…), kept out of "usual". "Nothing special" is not one. */
    fun periods(labels: List<UserLabelEntity>): List<LabelledPeriod> =
        labels.filter { it.kind == UserLabelEntity.KIND_PERIOD }.mapNotNull { l ->
            val option = PeriodOption.fromKey(l.value)
            if (option != null && !option.setsAside) return@mapNotNull null
            val json = l.contextJson ?: return@mapNotNull null
            val from = fromRe.find(json)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            val until = untilRe.find(json)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: from
            LabelledPeriod(from, maxOf(from, until), option?.label ?: l.value)
        }

    /** The stored [UserLabelEntity.refKey] of a period answer → its "from" date. */
    fun periodFrom(label: UserLabelEntity): LocalDate? =
        label.contextJson?.let { json -> fromRe.find(json)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } }

    /** Naps you confirmed, as (start, end). */
    fun confirmedNaps(labels: List<UserLabelEntity>): List<Pair<Long, Long>> =
        labels.filter { it.kind == UserLabelEntity.KIND_NAP && it.value == UserLabelEntity.NAP_ASLEEP }
            .mapNotNull { l -> napRange(l.refKey) }

    fun napRange(key: String?): Pair<Long, Long>? {
        val parts = key?.split('|') ?: return null
        if (parts.size != 3 || parts[0] != "nap") return null
        val start = parts[1].toLongOrNull() ?: return null
        val end = parts[2].toLongOrNull() ?: return null
        return start to end
    }

    /** Nights you set yourself, latest answer per night. Values are "start|end" or "none". */
    fun sleepFixes(labels: List<UserLabelEntity>): List<com.habitminer.analytics.SleepFix> =
        labels.filter { it.kind == UserLabelEntity.KIND_SLEEP_FIX }
            .sortedBy { it.timestamp }
            .mapNotNull { l ->
                val date = l.refKey?.removePrefix("sleep|")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
                if (l.value == UserLabelEntity.SLEEP_NONE) return@mapNotNull com.habitminer.analytics.SleepFix(date, 0L, 0L, notSleep = true)
                val parts = l.value.split('|')
                val start = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val end = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                if (end <= start) null else com.habitminer.analytics.SleepFix(date, start, end)
            }
            .associateBy { it.wakeDate }.values.toList()

    fun sleepFixKey(wakeDate: LocalDate): String = "sleep|$wakeDate"

    private val pairRe = Regex("\\[(\\d+),(\\d+)]")

    /** Nights copied from Health Connect, one per wake date. */
    fun healthNights(
        labels: List<UserLabelEntity>,
        zone: java.time.ZoneId,
    ): List<com.habitminer.analytics.SleepFix> =
        healthRows(labels, nap = false).map { (start, end, json) ->
            val awake = pairRe.findAll(json).map { it.groupValues[1].toLong() to it.groupValues[2].toLong() }.toList()
            com.habitminer.analytics.SleepFix(
                com.habitminer.analytics.TimeUtil.dateOf(end, zone), start, end,
                source = com.habitminer.analytics.SleepSource.HEALTH_CONNECT, awake = awake,
            )
        }.groupBy { it.wakeDate }.values.map { list -> list.maxBy { it.end - it.start } }

    /** Naps copied from Health Connect, as (start, end). */
    fun healthNaps(labels: List<UserLabelEntity>): List<Pair<Long, Long>> = healthRows(labels, nap = true).map { (a, b, _) -> a to b }

    private fun healthRows(
        labels: List<UserLabelEntity>,
        nap: Boolean,
    ): List<Triple<Long, Long, String>> =
        labels.filter { it.kind == UserLabelEntity.KIND_SLEEP_HEALTH }.mapNotNull { l ->
            val json = l.contextJson.orEmpty()
            if (json.contains("\"nap\":true") != nap) return@mapNotNull null
            val parts = l.value.split('|')
            val a = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val b = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            if (b > a) Triple(a, b, json) else null
        }

    /** Nap and period answers by key ("nap|…" → asleep/awake, "period|…" → exams/…). */
    fun answers(labels: List<UserLabelEntity>): Map<String, String> =
        labels.filter { (it.kind == UserLabelEntity.KIND_NAP || it.kind == UserLabelEntity.KIND_PERIOD) && it.refKey != null }
            .associate { it.refKey!! to it.value }
}
