package com.habitminer.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Each insight of the day that was picked (DB v10, Extended): what it said, why it was picked,
 * how it was delivered and what you thought of it. Drives the "fewer like this" learning and
 * is part of the export.
 */
@Entity(tableName = "insight_log", indices = [Index(value = ["date"])])
data class InsightLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** When it was picked. */
    val timestamp: Long,
    /** The day it's about, yyyy-MM-dd. */
    val date: String,
    val family: String,
    val key: String,
    val title: String,
    val body: String,
    /** What it was compared with. */
    val why: String,
    /** Where tapping it goes (see [com.habitminer.analytics.Insight.open]). */
    val open: String,
    /** How unusual, roughly in robust standard deviations. */
    val effect: Float,
    /** Picking score (effect × rarity × recency). */
    val score: Float,
    /** Estimated chance it would be picked among today's candidates. */
    val probability: Float,
    /** When it was sent as a notification, or null if it was only shown in the app. */
    val notifiedAt: Long? = null,
    /** "useful" or "fewer". */
    val feedback: String? = null,
    val feedbackAt: Long? = null,
    /** When you opened it from the notification or the card. */
    val openedAt: Long? = null,
)

@Dao
interface InsightLogDao {
    @Insert
    suspend fun insert(row: InsightLogEntity): Long

    @Query("SELECT * FROM insight_log WHERE date >= :fromDate ORDER BY timestamp ASC")
    suspend fun since(fromDate: String): List<InsightLogEntity>

    @Query("SELECT * FROM insight_log WHERE date = :date ORDER BY timestamp DESC LIMIT 1")
    suspend fun forDate(date: String): InsightLogEntity?

    @Query("SELECT * FROM insight_log ORDER BY timestamp ASC")
    suspend fun all(): List<InsightLogEntity>

    @Query("UPDATE insight_log SET notifiedAt = :at WHERE id = :id")
    suspend fun markNotified(
        id: Long,
        at: Long,
    )

    @Query("UPDATE insight_log SET feedback = :feedback, feedbackAt = :at WHERE `key` = :key")
    suspend fun setFeedback(
        key: String,
        feedback: String?,
        at: Long,
    )

    @Query("UPDATE insight_log SET openedAt = :at WHERE `key` = :key AND openedAt IS NULL")
    suspend fun markOpened(
        key: String,
        at: Long,
    )

    @Query("DELETE FROM insight_log WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM insight_log")
    suspend fun deleteAll()
}
