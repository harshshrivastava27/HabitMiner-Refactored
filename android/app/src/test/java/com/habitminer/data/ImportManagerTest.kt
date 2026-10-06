package com.habitminer.data

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** End-to-end import into a real (in-memory) Room database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImportManagerTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var importer: ImportManager

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        importer = ImportManager(context, db.appUsageDao(), db.contextDao(), db.labelDao(), db.placeDao(), db.deviceEventDao())
    }

    @After
    fun tearDown() = db.close()

    private fun zip(vararg files: Pair<String, String>): Uri {
        val out = File.createTempFile("habitminer_export", ".zip", context.cacheDir)
        ZipOutputStream(out.outputStream()).use { z ->
            for ((name, body) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return Uri.fromFile(out)
    }

    // Exactly the columns v1.1's ExportManager wrote (no wifiPlace / sensingMs yet).
    private val usageCsv =
        "id,packageName,appName,appCategory,startTime,endTime,durationMs,timeSlot,dayType,previousPackageName,isHistorical\n" +
            "111,com.snapchat.android,Snapchat,SOCIAL,1000,61000,60000,NIGHT,WEEKDAY,,true\n" +
            "222,com.example.app,\"Name, with comma\",OTHER,70000,130000,60000,NIGHT,WEEKDAY,com.snapchat.android,false\n"

    private val snapshotsCsv =
        "id,timestamp,accelMean,accelVariance,accelStd,accelMin,accelMax,accelEnergy," +
            "gyroMean,gyroVariance,gyroStd,gyroMin,gyroMax,gyroEnergy,lightLux,proximityNear," +
            "stepsSinceLastSnapshot,batteryLevel,isCharging,isScreenOn,unlockCount,notificationsLastHour\n" +
            "1,5000,9.8,0.1,0.3,9.5,10.1,96.0,0.1,0.01,0.1,0.0,0.2,0.05,40.0,false,10,80,false,true,3,1\n" +
            "2,6000,-1.0,-1.0,-1.0,-1.0,-1.0,-1.0,-1.0,-1.0,-1.0,-1.0,-1.0,-1.0,-1.0,,-1,79,true,false,3,-1\n"

    @Test
    fun `imports a v1_1 export and a second import adds no duplicates`() =
        runBlocking {
            val uri = zip("app_usage_1.csv" to usageCsv, "context_snapshots_1.csv" to snapshotsCsv, "habits_1.csv" to "id\n1\n")

            val first = importer.importZip(uri)
            assertEquals(2, first.usage)
            assertEquals(2, first.snapshots)
            assertEquals(2, first.filesRead)

            val second = importer.importZip(uri)
            assertEquals(0, second.snapshots)
            assertEquals(2, db.appUsageDao().getUsageCount().first())
            assertEquals(2, db.contextDao().getSnapshotCount().first())

            val usage = db.appUsageDao().getAllUsage().first().associateBy { it.id }
            assertEquals("Name, with comma", usage.getValue(222).appName)
            assertNull(usage.getValue(111).previousPackageName)
            val snaps = db.contextDao().getAllSnapshots().first().associateBy { it.timestamp }
            assertEquals(40f, snaps.getValue(5000).lightLux, 0.001f)
            assertNull(snaps.getValue(6000).proximityNear)
            assertEquals(true, snaps.getValue(6000).isCharging)
        }

    @Test
    fun `imports labels and places from a v1_2 export`() =
        runBlocking {
            val labels =
                "id,timestamp,kind,value,refKey,promptedAt,contextJson\n" +
                    "1,9000,CHECK_IN,studying,,8900,\"{\"\"lastApp\"\":\"\"com.whatsapp\"\"}\"\n" +
                    "2,9500,DEVIATION_FEEDBACK,EXPECTED,2026-10-03|NEW_BEHAVIOR|WEEKEND_MORNING|Meet,,\n"
            val places = "placeHash,label,firstSeen,lastSeen\nabc123,Hostel,100,900\n"
            val result = importer.importZip(zip("labels_1.csv" to labels, "places_1.csv" to places))

            assertEquals(2, result.labels)
            assertEquals(1, result.places)
            val stored = db.labelDao().getAll().first().associateBy { it.kind }
            assertEquals("{\"lastApp\":\"com.whatsapp\"}", stored.getValue("CHECK_IN").contextJson)
            assertEquals("2026-10-03|NEW_BEHAVIOR|WEEKEND_MORNING|Meet", stored.getValue("DEVIATION_FEEDBACK").refKey)
            assertEquals("Hostel", db.placeDao().getAll().first().single().label)

            // Importing the same labels again is a no-op.
            assertEquals(0, importer.importZip(zip("labels_1.csv" to labels)).labels)
        }
}
