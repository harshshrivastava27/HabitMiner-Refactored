@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.habitminer.engine.HabitViewModel
import com.habitminer.goals.GoalsStore
import com.habitminer.ui.goals.GoalsScreen
import com.habitminer.ui.sleep.SleepScreen
import com.habitminer.ui.status.StatusNavigation
import com.habitminer.ui.status.StatusScreen
import com.habitminer.ui.theme.Appearance
import com.habitminer.ui.theme.AppearanceSettings
import com.habitminer.ui.theme.HabitMinerTheme
import com.habitminer.ui.theme.ThemeMode
import com.habitminer.ui.today.TodayNavigation
import com.habitminer.ui.today.TodayScreen
import com.habitminer.ui.trends.AppDetailRoute
import com.habitminer.ui.trends.AppDetailViewModel
import com.habitminer.ui.trends.TrendsScreen
import com.habitminer.ui.trends.TrendsTab
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private const val OPEN_IMPORT = "import"

/** The five tabs. Settings sits behind the gear on Today. */
private enum class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    TODAY("today", "Today", Icons.Rounded.Today),
    TRENDS("trends", "Trends", Icons.Rounded.Insights),
    SLEEP("sleep", "Sleep", Icons.Rounded.Bedtime),
    GOALS("goals", "Goals", Icons.Rounded.Flag),
    STATUS("status", "Status", Icons.Rounded.Sensors),
}

private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_STORY = "story"
private const val ROUTE_APP = "app/{${AppDetailViewModel.ARG_PACKAGE}}"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: HabitViewModel by viewModels()

    @Inject lateinit var appearanceSettings: AppearanceSettings

    @Inject lateinit var goalsStore: GoalsStore

    @Inject lateinit var widgetUpdater: com.habitminer.widget.WidgetUpdater

    /** Where a notification or a shared file asked us to go. */
    private val pendingOpen = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.checkPermissions()
        handleIntent(intent, fresh = savedInstanceState == null)

        setContent {
            val appearance by appearanceSettings.appearance.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark =
                when (appearance.themeMode) {
                    ThemeMode.SYSTEM -> systemDark
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            // Status and navigation bar icons follow the app's theme, not the system's.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            HabitMinerTheme(darkTheme = dark, wallpaperColors = appearance.wallpaperColors) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    App(appearance)
                }
            }
        }
    }

    @Composable
    private fun App(appearance: Appearance) {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val goals by goalsStore.goals.collectAsStateWithLifecycle()
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route ?: Tab.TODAY.route
        val open by pendingOpen.collectAsStateWithLifecycle()
        val importLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.importData(uri) }

        if (!state.hasUsagePermission || !state.hasRuntimePermissions || (!state.hasNotificationPermission && !state.notificationAccessSkipped)) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                PermissionScreen(
                    hasUsage = state.hasUsagePermission,
                    hasRuntime = state.hasRuntimePermissions,
                    hasNotification = state.hasNotificationPermission,
                    onRequestUsage = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                    onRuntimePermissionsGranted = { viewModel.checkPermissions() },
                    onRequestNotification = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    onImport = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    onSkipNotification = { viewModel.skipNotificationAccess() },
                )
            }
            return
        }

        LaunchedEffect(open) {
            when (open) {
                com.habitminer.proactive.Notifier.OPEN_CHECKIN, "today" -> nav.goTab(Tab.TODAY.route)
                com.habitminer.proactive.Notifier.OPEN_INSIGHTS, com.habitminer.proactive.Notifier.OPEN_TRENDS ->
                    nav.goTab("${Tab.TRENDS.route}?tab=${TrendsTab.OVERVIEW.name}")
                com.habitminer.proactive.Notifier.OPEN_DEVIATIONS -> nav.goTab("${Tab.TRENDS.route}?tab=${TrendsTab.CHANGES.name}")
                com.habitminer.proactive.Notifier.OPEN_SLEEP -> nav.goTab(Tab.SLEEP.route)
                com.habitminer.proactive.Notifier.OPEN_GOALS -> nav.goTab(Tab.GOALS.route)
                com.habitminer.proactive.Notifier.OPEN_STORY -> nav.navigate(ROUTE_STORY) { launchSingleTop = true }
                OPEN_IMPORT -> nav.navigate(ROUTE_SETTINGS) { launchSingleTop = true }
                else -> if (open?.startsWith("app:") == true) nav.openApp(open!!.removePrefix("app:"))
            }
            if (open != null) pendingOpen.value = null
        }

        val currentTab = Tab.entries.firstOrNull { route.startsWith(it.route) }
        // A bottom bar on phones, a rail on wider screens.
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                Tab.entries.forEach { tab ->
                    item(
                        selected = currentTab == tab,
                        onClick = { nav.goTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            NavHost(
                navController = nav,
                startDestination = Tab.TODAY.route,
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
            ) {
                composable(Tab.TODAY.route) {
                    TodayScreen(
                        state,
                        viewModel,
                        object : TodayNavigation {
                            override fun openSettings() = nav.navigate(ROUTE_SETTINGS) { launchSingleTop = true }

                            override fun openSleep() = nav.goTab(Tab.SLEEP.route)

                            override fun openApps() = nav.goTab("${Tab.TRENDS.route}?tab=${TrendsTab.APPS.name}")

                            override fun openChanges() = nav.goTab("${Tab.TRENDS.route}?tab=${TrendsTab.CHANGES.name}")

                            override fun openApp(packageName: String) = nav.openApp(packageName)
                        },
                        intention = goals.intentionFor(java.time.LocalDate.now()),
                    )
                }
                composable(
                    "${Tab.TRENDS.route}?tab={tab}",
                    arguments =
                        listOf(
                            navArgument("tab") {
                                type = NavType.StringType
                                defaultValue = TrendsTab.OVERVIEW.name
                            },
                        ),
                ) { backStack ->
                    val tab = runCatching { TrendsTab.valueOf(backStack.arguments?.getString("tab") ?: "") }.getOrDefault(TrendsTab.OVERVIEW)
                    TrendsScreen(state, viewModel, initialTab = tab, onOpenApp = { nav.openApp(it) }, onOpenStory = { nav.navigate(ROUTE_STORY) { launchSingleTop = true } })
                }
                composable(Tab.SLEEP.route) { SleepScreen(state, viewModel) }
                composable(Tab.GOALS.route) {
                    GoalsScreen(
                        goals = goals,
                        apps = state.appSummaries,
                        dailyTotals = state.dailyTotals,
                        todayMs = state.todayScreenTimeMs,
                        onChange = { change -> goalsStore.update(change) },
                        onOpenApp = { nav.openApp(it) },
                    )
                }
                composable(Tab.STATUS.route) {
                    StatusScreen(
                        state,
                        object : StatusNavigation {
                            override fun openPermissions() {
                                when {
                                    !state.hasUsagePermission -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                    !state.hasNotificationPermission -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                    else ->
                                        startActivity(
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)),
                                        )
                                }
                            }
                        },
                    )
                }
                composable(ROUTE_SETTINGS) {
                    SettingsScreen(
                        state = state,
                        viewModel = viewModel,
                        appearance = appearance,
                        onThemeMode = { appearanceSettings.setThemeMode(it) },
                        onWallpaperColors = { appearanceSettings.setWallpaperColors(it) },
                        onBack = { nav.popBackStack() },
                        onOpenToday = { nav.goTab(Tab.TODAY.route) },
                    )
                }
                composable(ROUTE_APP, arguments = listOf(navArgument(AppDetailViewModel.ARG_PACKAGE) { type = NavType.StringType })) {
                    AppDetailRoute(onBack = { nav.popBackStack() })
                }
                composable(ROUTE_STORY) { com.habitminer.ui.story.StoryRoute(onBack = { nav.popBackStack() }) }
            }
        }
    }

    private fun NavHostController.goTab(route: String) {
        navigate(route) {
            popUpTo(graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    private fun NavHostController.openApp(packageName: String) {
        navigate("app/${android.net.Uri.encode(packageName)}") { launchSingleTop = true }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkPermissions()
        viewModel.refreshInsights()
        // A fresh reading so the surroundings shown are current (at most every 5 minutes).
        com.habitminer.collection.MonitoringService.requestFreshReading(this)
    }

    override fun onStop() {
        super.onStop()
        // Leave the home-screen widget showing what the app just showed.
        lifecycleScope.launch { runCatching { widgetUpdater.refresh(force = true) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, fresh = true)
    }

    private fun handleIntent(
        intent: Intent?,
        fresh: Boolean,
    ) {
        // An export ZIP shared from the original HabitMiner (or opened from a file manager).
        // Only on a fresh delivery, so rotating the screen doesn't import it twice.
        val shared =
            when (intent?.action) {
                Intent.ACTION_SEND -> androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
                Intent.ACTION_VIEW -> intent.data
                else -> null
            }
        if (shared != null) {
            if (fresh) {
                viewModel.importData(shared)
                pendingOpen.value = OPEN_IMPORT
            }
            return
        }
        val open = intent?.getStringExtra(com.habitminer.proactive.Notifier.EXTRA_OPEN) ?: return
        intent.getStringExtra(com.habitminer.proactive.Notifier.EXTRA_INSIGHT_KEY)?.let { viewModel.insightOpened(it) }
        if (open == com.habitminer.proactive.Notifier.OPEN_CHECKIN) {
            val promptedAt = intent.getLongExtra(com.habitminer.proactive.Notifier.EXTRA_PROMPTED_AT, -1L).takeIf { it > 0 }
            viewModel.openCheckIn(promptedAt)
        }
        pendingOpen.value = open
        intent.removeExtra(com.habitminer.proactive.Notifier.EXTRA_OPEN)
    }

    companion object {
        // The scrims Android uses behind 3-button navigation.
        private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
