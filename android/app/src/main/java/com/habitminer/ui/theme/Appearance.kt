package com.habitminer.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

data class Appearance(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Off by default: HabitMiner's own colours, with the wallpaper as an option. */
    val wallpaperColors: Boolean = false,
)

/** Light/dark choice and wallpaper colours, saved on the phone. */
@Singleton
class AppearanceSettings
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        private val _appearance = MutableStateFlow(read())
        val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

        fun setThemeMode(mode: ThemeMode) {
            prefs.edit().putString(KEY_MODE, mode.name).apply()
            _appearance.value = read()
            applyToSystem(mode)
        }

        fun setWallpaperColors(enabled: Boolean) {
            prefs.edit().putBoolean(KEY_WALLPAPER, enabled).apply()
            _appearance.value = read()
        }

        /**
         * On Android 12+ the app's own night mode also colours the splash screen and window
         * background, so there's no light flash before a dark app draws.
         */
        fun applyToSystem(mode: ThemeMode = _appearance.value.themeMode) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager ?: return
            runCatching {
                ui.setApplicationNightMode(
                    when (mode) {
                        ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                        ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                        ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                    },
                )
            }
        }

        private fun read(): Appearance =
            Appearance(
                themeMode = runCatching { ThemeMode.valueOf(prefs.getString(KEY_MODE, null) ?: ThemeMode.SYSTEM.name) }.getOrDefault(ThemeMode.SYSTEM),
                wallpaperColors = prefs.getBoolean(KEY_WALLPAPER, false),
            )

        companion object {
            private const val KEY_MODE = "theme_mode"
            private const val KEY_WALLPAPER = "wallpaper_colors"
        }
    }
