package com.habitminer.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * HabitMiner's own resource use, from Android's per-app health stats (DB v10, Extended).
 * Values are running totals as Android reports them; usage is the difference between rows
 * (a total that went down means the counters were reset, e.g. after a reboot).
 */
@Entity(tableName = "self_health", indices = [Index(value = ["timestamp"])])
data class SelfHealthEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val cpuUserMs: Long,
    val cpuSystemMs: Long,
    /** Time HabitMiner held partial wake locks (kept the CPU awake). */
    val wakelockMs: Long,
    /** Time HabitMiner had sensors switched on, all sensors together. */
    val sensorMs: Long,
    /** Radio energy in mA·ms, when the phone reports it (many report 0). */
    val mobileMams: Long,
    val wifiMams: Long,
    val bluetoothMams: Long,
    val batteryLevel: Int,
    /** Battery charge counter in µAh, when reported; with [batteryLevel] it gives the battery's capacity. */
    val chargeCounterUah: Long? = null,
)

@Dao
interface SelfHealthDao {
    @Insert
    suspend fun insert(row: SelfHealthEntity)

    @Query("SELECT * FROM self_health WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun since(since: Long): List<SelfHealthEntity>

    @Query("SELECT * FROM self_health ORDER BY timestamp DESC LIMIT 1")
    suspend fun latest(): SelfHealthEntity?

    @Query("DELETE FROM self_health WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM self_health")
    suspend fun deleteAll()
}
