package com.habitminer.repository

import com.habitminer.data.AppUsageDao
import com.habitminer.data.ContextDao
import com.habitminer.data.ContextSnapshotEntity
import com.habitminer.data.DeviceEventDao
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ContextRepositoryTest {
    private val mockUsageDao = mock<AppUsageDao>()
    private val mockContextDao = mock<ContextDao>()
    private val mockDeviceEventDao = mock<DeviceEventDao>()

    private val repository =
        ContextRepository(
            appUsageDao = mockUsageDao,
            contextDao = mockContextDao,
            deviceEventDao = mockDeviceEventDao,
            batchDao = mock(),
        )

    @Test
    fun `shouldSkipContextCollection returns true when latest snapshot is less than 10 minutes old`() =
        runBlocking {
            val now = System.currentTimeMillis()
            val recentSnapshot = createSnapshot(timestamp = now - (5 * 60 * 1000L))

            whenever(mockContextDao.getLatestSnapshotOnce()).thenReturn(recentSnapshot)

            assertTrue(repository.shouldSkipContextCollection())
        }

    @Test
    fun `shouldSkipContextCollection returns false when latest snapshot is older than 10 minutes`() =
        runBlocking {
            val now = System.currentTimeMillis()
            val oldSnapshot = createSnapshot(timestamp = now - (15 * 60 * 1000L))

            whenever(mockContextDao.getLatestSnapshotOnce()).thenReturn(oldSnapshot)

            assertFalse(repository.shouldSkipContextCollection())
        }

    @Test
    fun `shouldSkipContextCollection returns false when no snapshot exists`() =
        runBlocking {
            whenever(mockContextDao.getLatestSnapshotOnce()).thenReturn(null)

            assertFalse(repository.shouldSkipContextCollection())
        }

    private fun createSnapshot(timestamp: Long) =
        ContextSnapshotEntity(
            id = 1,
            timestamp = timestamp,
            accelMean = 0f,
            accelVariance = 0f,
            accelStd = 0f,
            accelMin = 0f,
            accelMax = 0f,
            accelEnergy = 0f,
            gyroMean = 0f,
            gyroVariance = 0f,
            gyroStd = 0f,
            gyroMin = 0f,
            gyroMax = 0f,
            gyroEnergy = 0f,
            lightLux = 0f,
            proximityNear = null,
            stepsSinceLastSnapshot = 0,
            batteryLevel = 100,
            isCharging = false,
            isScreenOn = true,
            unlockCount = 0,
            notificationsLastHour = 0,
        )
}
