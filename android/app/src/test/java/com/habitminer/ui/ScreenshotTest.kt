package com.habitminer.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.habitminer.analytics.CheckInOption
import com.habitminer.analytics.TimeUtil
import com.habitminer.engine.HabitActions
import com.habitminer.ui.theme.HabitMinerTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone

/**
 * Renders each main screen with realistic sample data and saves a PNG to
 * app/build/screenshots. CI publishes them to the `ci-screenshots` branch for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-xhdpi", application = Application::class)
class ScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private object NoActions : HabitActions {
        override fun checkPermissions() = Unit

        override fun loadHistoricalData() = Unit

        override fun answerCheckIn(option: CheckInOption) = Unit

        override fun dismissCheckIn() = Unit

        override fun giveDeviationFeedback(
            key: String,
            value: String,
        ) = Unit

        override fun answerNap(
            key: String,
            asleep: Boolean,
        ) = Unit

        override fun labelPeriod(
            key: String,
            from: java.time.LocalDate,
            value: String,
        ) = Unit

        override fun selectHistoryDate(timeInMillis: Long) = Unit
    }

    private lateinit var data: SampleData.Data

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(SampleData.zone))
        // A Saturday evening, like the screenshots this redesign started from.
        val now = TimeUtil.at(java.time.LocalDate.of(2026, 10, 3), 18, 45, SampleData.zone)
        data = SampleData.build(now)
    }

    private fun shoot(
        name: String,
        content: @Composable () -> Unit,
    ) {
        rule.setContent {
            HabitMinerTheme(darkTheme = true, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val out = File("build/screenshots/$name.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun today() = shoot("1_today") { HomeScreen(data.state, NoActions) }

    @Test
    fun todayWithCheckIn() =
        shoot("2_today_checkin") {
            HomeScreen(data.state.copy(pendingCheckInPromptedAt = data.now), NoActions)
        }

    @Test
    fun history() = shoot("3_history") { HistoryScreen(data.state, NoActions) }

    @Test
    fun insightsRoutines() = shoot("4_insights_routines") { InsightsScreen(data.state, NoActions, initialTab = 0) }

    @Test
    fun insightsDeviations() = shoot("5_insights_deviations") { InsightsScreen(data.state, NoActions, initialTab = 1) }

    @Test
    fun insightsBlueprint() = shoot("6_insights_blueprint") { InsightsScreen(data.state, NoActions, initialTab = 2) }

    @Test
    fun health() {
        // Robolectric devices have no sensors; register the five the app uses so the
        // Health screen renders like it does on a phone.
        // System services are per context, so register on both the app and the activity.
        val contexts =
            listOf<android.content.Context>(
                androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>(),
                rule.activity,
            )
        for (context in contexts) {
            val sensorManager = context.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
            val shadow = org.robolectric.Shadows.shadowOf(sensorManager)
            listOf(
                android.hardware.Sensor.TYPE_ACCELEROMETER,
                android.hardware.Sensor.TYPE_GYROSCOPE,
                android.hardware.Sensor.TYPE_LIGHT,
                android.hardware.Sensor.TYPE_PROXIMITY,
                android.hardware.Sensor.TYPE_STEP_COUNTER,
            ).forEach { type ->
                if (sensorManager.getDefaultSensor(type) == null) {
                    shadow.addSensor(org.robolectric.shadows.ShadowSensor.newInstance(type))
                }
            }
        }
        shoot("7_health") { HealthScreen(data.state) }
    }
}
