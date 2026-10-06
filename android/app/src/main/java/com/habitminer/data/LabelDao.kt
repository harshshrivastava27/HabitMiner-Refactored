package com.habitminer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LabelDao {
    @Insert
    suspend fun insert(label: UserLabelEntity): Long

    @Insert
    suspend fun insertAll(labels: List<UserLabelEntity>)

    /** "KIND:timestamp" for every label, used to skip duplicates on import. */
    @Query("SELECT kind || ':' || timestamp FROM user_labels")
    suspend fun getKeys(): List<String>

    @Query("SELECT * FROM user_labels ORDER BY timestamp DESC")
    fun getAll(): Flow<List<UserLabelEntity>>

    @Query("SELECT * FROM user_labels WHERE kind = :kind ORDER BY timestamp DESC")
    fun getByKind(kind: String): Flow<List<UserLabelEntity>>

    @Query("DELETE FROM user_labels WHERE kind = :kind AND refKey = :refKey")
    suspend fun deleteByRef(
        kind: String,
        refKey: String,
    )

    @Query("SELECT * FROM user_labels WHERE kind = :kind ORDER BY timestamp DESC")
    suspend fun getByKindOnce(kind: String): List<UserLabelEntity>

    @Query("UPDATE user_labels SET contextJson = :json WHERE kind = :kind AND refKey = :refKey")
    suspend fun updateContext(
        kind: String,
        refKey: String,
        json: String,
    )

    @Query("DELETE FROM user_labels WHERE timestamp < :timestampMs")
    suspend fun deleteOlderThan(timestampMs: Long)

    @Query("DELETE FROM user_labels")
    suspend fun deleteAll()
}

@Dao
interface PlaceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(place: PlaceEntity)

    @Query("UPDATE places SET lastSeen = :time WHERE placeHash = :placeHash")
    suspend fun touch(
        placeHash: String,
        time: Long,
    )

    @Query("UPDATE places SET label = :label WHERE placeHash = :placeHash")
    suspend fun rename(
        placeHash: String,
        label: String?,
    )

    @Query("SELECT * FROM places ORDER BY lastSeen DESC")
    fun getAll(): Flow<List<PlaceEntity>>

    @Query("DELETE FROM places")
    suspend fun deleteAll()
}
