package com.habitminer.collection

import com.habitminer.data.AppUsageEntity
import com.habitminer.domain.AppIdentityResolver
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock

class UsageDataCollectorTest {
    private val mockResolver = mock<AppIdentityResolver>()
    private val collector = UsageDataCollector(mock(), mockResolver)

    @Test
    fun `merges contiguous sessions of the same package within 5 minutes`() {
        val now = 1000000L
        val session1 =
            AppUsageEntity(
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                appCategory = "COMMUNICATION",
                startTime = now,
                endTime = now + 60_000,
                durationMs = 60_000,
                timeSlot = "EVENING",
                dayType = "WEEKDAY",
            )
        val session2 =
            AppUsageEntity(
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                appCategory = "COMMUNICATION",
                startTime = now + 120_000,
                endTime = now + 180_000,
                durationMs = 60_000,
                timeSlot = "EVENING",
                dayType = "WEEKDAY",
            )

        val merged = collector.mergeContiguousSessions(listOf(session1, session2))

        assertEquals(1, merged.size)
        assertEquals("com.whatsapp", merged[0].packageName)
        assertEquals(now, merged[0].startTime)
        assertEquals(now + 180_000, merged[0].endTime)
        // Only the time in front counts: 1 min + 1 min, not the 3-minute span.
        assertEquals(120_000, merged[0].durationMs)
    }

    @Test
    fun `does not merge sessions of different packages`() {
        val now = 1000000L
        val session1 =
            AppUsageEntity(
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                appCategory = "COMMUNICATION",
                startTime = now,
                endTime = now + 60_000,
                durationMs = 60_000,
                timeSlot = "EVENING",
                dayType = "WEEKDAY",
            )
        val session2 =
            AppUsageEntity(
                packageName = "com.reddit.frontpage",
                appName = "Reddit",
                appCategory = "SOCIAL",
                startTime = now + 65_000,
                endTime = now + 120_000,
                durationMs = 55_000,
                timeSlot = "EVENING",
                dayType = "WEEKDAY",
            )

        val merged = collector.mergeContiguousSessions(listOf(session1, session2))

        assertEquals(2, merged.size)
        assertEquals("com.whatsapp", merged[0].packageName)
        assertEquals("com.reddit.frontpage", merged[1].packageName)
    }

    @Test
    fun `does not merge sessions of same package if gap is more than 5 minutes`() {
        val now = 1000000L
        val session1 =
            AppUsageEntity(
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                appCategory = "COMMUNICATION",
                startTime = now,
                endTime = now + 60_000,
                durationMs = 60_000,
                timeSlot = "EVENING",
                dayType = "WEEKDAY",
            )
        val session2 =
            AppUsageEntity(
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                appCategory = "COMMUNICATION",
                startTime = now + 400_000,
                endTime = now + 460_000,
                durationMs = 60_000,
                timeSlot = "EVENING",
                dayType = "WEEKDAY",
            )

        val merged = collector.mergeContiguousSessions(listOf(session1, session2))

        assertEquals(2, merged.size)
    }
}
