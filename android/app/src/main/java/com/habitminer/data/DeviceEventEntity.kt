package com.habitminer.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "device_events", indices = [Index(value = ["eventType", "timestamp"])])
data class DeviceEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventType: String,
    val packageName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    /** Extra detail for some event types, see [com.habitminer.collection.DeviceEvents]. */
    val detail: String? = null,
)
