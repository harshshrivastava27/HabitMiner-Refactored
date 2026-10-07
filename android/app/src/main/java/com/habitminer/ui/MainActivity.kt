@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.habitminer.engine.HabitViewModel
import com.habitminer.ui.theme.HabitMinerTheme
import dagger.hilt.android.AndroidEntryPoint

private const val OPEN_IMPORT = "import"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: HabitViewModel by viewModels()

    /** Where a notification asked us to go (check-in card or the Blueprint tab). */
    private val pendingOpen = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.checkPermissions()
        handleIntent(intent, fresh = savedInstanceState == null)

        setContent {
            HabitMinerTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Home.route
                var insightsTab by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
                val open by pendingOpen.collectAsStateWithLifecycle()

                fun go(route: String) {
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }

                androidx.compose.runtime.LaunchedEffect(open) {
                    when (open) {
                        com.habitminer.proactive.Notifier.OPEN_CHECKIN -> go(Screen.Home.route)
                        com.habitminer.proactive.Notifier.OPEN_INSIGHTS -> {
                            insightsTab = 2
                            go(Screen.Insights.route)
                        }
                        com.habitminer.proactive.Notifier.OPEN_DEVIATIONS -> {
                            insightsTab = 1
                            go(Screen.Insights.route)
                        }
                        OPEN_IMPORT -> go(Screen.Settings.route)
                    }
                    if (open != null) pendingOpen.value = null
                }

                Scaffold(
                    bottomBar = {
                        if (state.hasUsagePermission && state.hasRuntimePermissions && state.hasNotificationPermission) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ) {
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Home, contentDescription = "Today") },
                                    label = { Text("Today") },
                                    selected = currentRoute == Screen.Home.route,
                                    onClick = {
                                        navController.navigate(Screen.Home.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    colors =
                                        NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        ),
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.History, contentDescription = "History") },
                                    label = { Text("History") },
                                    selected = currentRoute == Screen.History.route,
                                    onClick = {
                                        navController.navigate(Screen.History.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    colors =
                                        NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        ),
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Psychology, contentDescription = "Insights") },
                                    label = { Text("Insights") },
                                    selected = currentRoute == Screen.Insights.route,
                                    onClick = {
                                        navController.navigate(Screen.Insights.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    colors =
                                        NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        ),
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Sensors, contentDescription = "Health") },
                                    label = { Text("Health") },
                                    selected = currentRoute == Screen.Health.route,
                                    onClick = {
                                        navController.navigate(Screen.Health.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    colors =
                                        NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        ),
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                                    label = { Text("Settings") },
                                    selected = currentRoute == Screen.Settings.route,
                                    onClick = {
                                        navController.navigate(Screen.Settings.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    colors =
                                        NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        ),
                                )
                            }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.background,
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier.padding(innerPadding),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = Screen.Home.route,
                            enterTransition = {
                                androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300)) +
                                    androidx.compose.animation.slideInHorizontally(
                                        animationSpec = androidx.compose.animation.core.tween(300),
                                        initialOffsetX = { 50 }
                                    )
                            },
                            exitTransition = {
                                androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(300)) +
                                    androidx.compose.animation.slideOutHorizontally(
                                        animationSpec = androidx.compose.animation.core.tween(300),
                                        targetOffsetX = { -50 }
                                    )
                            }
                        ) {
                            composable(Screen.Home.route) {
                                HomeScreen(state, viewModel, onOpenInsights = {
                                    insightsTab = 0
                                    go(Screen.Insights.route)
                                })
                            }
                            composable(Screen.History.route) { HistoryScreen(state, viewModel) }
                            composable(Screen.Insights.route) { InsightsScreen(state, viewModel, initialTab = insightsTab) }
                            composable(Screen.Settings.route) {
                                SettingsScreen(
                                    state = state,
                                    viewModel = viewModel,
                                    onOpenToday = { go(Screen.Home.route) },
                                )
                            }
                            composable(Screen.Health.route) {
                                HealthScreen(state = state)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkPermissions()
        viewModel.refreshInsights()
        // Take a fresh sensor reading so "Around you" reflects right now, not the last
        // scheduled reading (which can be up to 30 minutes old).
        com.habitminer.collection.MonitoringService.requestFreshReading(this)
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
                Intent.ACTION_SEND ->
                    androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
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
        if (open == com.habitminer.proactive.Notifier.OPEN_CHECKIN) {
            val promptedAt = intent.getLongExtra(com.habitminer.proactive.Notifier.EXTRA_PROMPTED_AT, -1L).takeIf { it > 0 }
            viewModel.openCheckIn(promptedAt)
        }
        pendingOpen.value = open
        // Consume the extra so a configuration change doesn't reopen it.
        intent.removeExtra(com.habitminer.proactive.Notifier.EXTRA_OPEN)
    }
}
