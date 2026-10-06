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

    /** Nap and period answers by key ("nap|…" → asleep/awake, "period|…" → exams/…). */
    fun answers(labels: List<UserLabelEntity>): Map<String, String> =
        labels.filter { (it.kind == UserLabelEntity.KIND_NAP || it.kind == UserLabelEntity.KIND_PERIOD) && it.refKey != null }
            .associate { it.refKey!! to it.value }
}
