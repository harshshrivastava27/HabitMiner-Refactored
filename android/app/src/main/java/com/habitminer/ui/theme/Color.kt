package com.habitminer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * HabitMiner Extended palette: neutral surfaces so colour means something, and one vivid hue
 * per measure, used everywhere that measure appears (rings, numbers, bars, icons):
 *
 *   screen time  hot pink-red     pickups  lime        phone-free  cyan
 *   sleep        violet           steps    orange      now         yellow
 *
 * Dark is true black with lifted grey cards, so the hues glow; light is white cards on a soft
 * grey with deeper versions of the same hues for text. The chrome (buttons, links, switches)
 * takes the screen-time pink, the app's own subject.
 */

internal val VividLight =
    lightColorScheme(
        primary = Color(0xFFE0174F),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFE1E8),
        onPrimaryContainer = Color(0xFF5E0021),
        inversePrimary = Color(0xFFFF7FA0),
        secondary = Color(0xFF5B5B66),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFEDEDF2),
        onSecondaryContainer = Color(0xFF17171C),
        tertiary = Color(0xFFB07800),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFF0C2),
        onTertiaryContainer = Color(0xFF3A2800),
        background = Color(0xFFF2F2F6),
        onBackground = Color(0xFF0E0E12),
        surface = Color(0xFFF2F2F6),
        onSurface = Color(0xFF0E0E12),
        surfaceVariant = Color(0xFFE6E6EC),
        onSurfaceVariant = Color(0xFF66666F),
        surfaceTint = Color(0xFFE0174F),
        inverseSurface = Color(0xFF2A2A30),
        inverseOnSurface = Color(0xFFF2F2F6),
        error = Color(0xFFD42A1F),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFE2DF),
        onErrorContainer = Color(0xFF4A0802),
        outline = Color(0xFFC4C4CC),
        outlineVariant = Color(0xFFDDDDE3),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFDCDCE2),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFFFFF),
        surfaceContainer = Color(0xFFF8F8FB),
        surfaceContainerHigh = Color(0xFFFFFFFF),
        surfaceContainerHighest = Color(0xFFE9E9EF),
    )

internal val VividDark =
    darkColorScheme(
        primary = Color(0xFFFF3D71),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF4D0F24),
        onPrimaryContainer = Color(0xFFFFB3C6),
        inversePrimary = Color(0xFFE0174F),
        secondary = Color(0xFFB4B4BE),
        onSecondary = Color(0xFF1A1A1F),
        secondaryContainer = Color(0xFF2E2E34),
        onSecondaryContainer = Color(0xFFF4F4F8),
        tertiary = Color(0xFFFFD60A),
        onTertiary = Color(0xFF2A2100),
        tertiaryContainer = Color(0xFF4A3B00),
        onTertiaryContainer = Color(0xFFFFEB8A),
        background = Color(0xFF000000),
        onBackground = Color(0xFFF5F5F7),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFF5F5F7),
        surfaceVariant = Color(0xFF2C2C31),
        onSurfaceVariant = Color(0xFF9D9DA7),
        surfaceTint = Color(0xFFFF3D71),
        inverseSurface = Color(0xFFF2F2F6),
        inverseOnSurface = Color(0xFF1C1C1F),
        error = Color(0xFFFF6B5E),
        onError = Color(0xFF3B0500),
        errorContainer = Color(0xFF5C150E),
        onErrorContainer = Color(0xFFFFDAD5),
        outline = Color(0xFF4A4A51),
        outlineVariant = Color(0xFF2C2C31),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF38383E),
        surfaceDim = Color(0xFF000000),
        surfaceContainerLowest = Color(0xFF0B0B0D),
        surfaceContainerLow = Color(0xFF1B1B1F),
        surfaceContainer = Color(0xFF202024),
        surfaceContainerHigh = Color(0xFF1F1F23),
        surfaceContainerHighest = Color(0xFF2C2C31),
    )

/**
 * A measure's colour: [start] to [end] is the gradient for rings and bars (darker to brighter),
 * [text] is the readable version for numbers and labels on the current background.
 */
@Immutable
data class Hue(
    val start: Color,
    val end: Color,
    val text: Color,
) {
    /** The flat colour for small marks. */
    val solid: Color get() = end
}

/**
 * Fixed colours for each kind of data, the same with or without wallpaper colours, so sleep is
 * always violet and screen time always pink wherever they appear.
 */
@Immutable
data class DataColors(
    val screenHue: Hue,
    val pickupsHue: Hue,
    val freeHue: Hue,
    val sleepHue: Hue,
    val stepsHue: Hue,
    val notificationsHue: Hue,
    val nowHue: Hue,
    val goodHue: Hue,
    /** Flat colours (kept for charts and older code). */
    val screen: Color,
    val sleep: Color,
    val nap: Color,
    val activity: Color,
    val pickups: Color,
    val notifications: Color,
    val now: Color,
    /** The usual-day reference line or range. */
    val usual: Color,
    val track: Color,
    val good: Color,
    val caution: Color,
    val alert: Color,
    /** Six distinct colours for app categories and day types, in a fixed order. */
    val series: List<Color>,
    /** Ring and bar tracks: the hue at this alpha. */
    val trackAlpha: Float,
)

private val ScreenD = Hue(Color(0xFFFF1F5A), Color(0xFFFF6A93), Color(0xFFFF4D7D))
private val PickupsD = Hue(Color(0xFF8BE000), Color(0xFFD2FF45), Color(0xFFB8F53A))
private val FreeD = Hue(Color(0xFF00B9F2), Color(0xFF6EF0FF), Color(0xFF3FDCFB))
private val SleepD = Hue(Color(0xFF6A55FF), Color(0xFFB09CFF), Color(0xFF9D8CFF))
private val StepsD = Hue(Color(0xFFFF6A00), Color(0xFFFFB640), Color(0xFFFF9A2E))
private val NotifD = Hue(Color(0xFFB040FF), Color(0xFFE08CFF), Color(0xFFD27BFF))
private val NowD = Hue(Color(0xFFFFC300), Color(0xFFFFE45C), Color(0xFFFFD60A))
private val GoodD = Hue(Color(0xFF1DBF5E), Color(0xFF5CF08F), Color(0xFF43DE7C))

private val ScreenL = Hue(Color(0xFFE3104A), Color(0xFFFF4F7E), Color(0xFFD5104A))
private val PickupsL = Hue(Color(0xFF5DB800), Color(0xFFA6E61C), Color(0xFF3F8F00))
private val FreeL = Hue(Color(0xFF0098D4), Color(0xFF33D3F5), Color(0xFF0088B0))
private val SleepL = Hue(Color(0xFF5038F0), Color(0xFF9580FF), Color(0xFF4E36E0))
private val StepsL = Hue(Color(0xFFF05A00), Color(0xFFFFA62B), Color(0xFFCC5A00))
private val NotifL = Hue(Color(0xFF9A2BE8), Color(0xFFCB78FF), Color(0xFF8A22D0))
private val NowL = Hue(Color(0xFFF2A900), Color(0xFFFFD43B), Color(0xFFA67400))
private val GoodL = Hue(Color(0xFF17A34A), Color(0xFF4AD97D), Color(0xFF138A3E))

internal val DataColorsLight =
    DataColors(
        screenHue = ScreenL,
        pickupsHue = PickupsL,
        freeHue = FreeL,
        sleepHue = SleepL,
        stepsHue = StepsL,
        notificationsHue = NotifL,
        nowHue = NowL,
        goodHue = GoodL,
        screen = Color(0xFFF0245C),
        sleep = Color(0xFF6A52F5),
        nap = Color(0xFFB3A4FF),
        activity = Color(0xFFF57A0F),
        pickups = Color(0xFF6CC400),
        notifications = Color(0xFFA43BEA),
        now = Color(0xFFF2B400),
        usual = Color(0xFF8A8A94),
        track = Color(0xFFEBEBF0),
        good = Color(0xFF17A34A),
        caution = Color(0xFFE08600),
        alert = Color(0xFFD42A1F),
        series =
            listOf(
                Color(0xFFF0245C),
                Color(0xFF0A9BE0),
                Color(0xFFF57A0F),
                Color(0xFFA43BEA),
                Color(0xFF17A34A),
                Color(0xFF6A52F5),
            ),
        trackAlpha = 0.16f,
    )

internal val DataColorsDark =
    DataColors(
        screenHue = ScreenD,
        pickupsHue = PickupsD,
        freeHue = FreeD,
        sleepHue = SleepD,
        stepsHue = StepsD,
        notificationsHue = NotifD,
        nowHue = NowD,
        goodHue = GoodD,
        screen = Color(0xFFFF3D71),
        sleep = Color(0xFF8C78FF),
        nap = Color(0xFFC6BAFF),
        activity = Color(0xFFFF8E1F),
        pickups = Color(0xFFADF03A),
        notifications = Color(0xFFC968FF),
        now = Color(0xFFFFD60A),
        usual = Color(0xFF8E8E98),
        track = Color(0xFF26262B),
        good = Color(0xFF43DE7C),
        caution = Color(0xFFFFB020),
        alert = Color(0xFFFF5A4E),
        series =
            listOf(
                Color(0xFFFF3D71),
                Color(0xFF3FC8FF),
                Color(0xFFFF8E1F),
                Color(0xFFC968FF),
                Color(0xFF43DE7C),
                Color(0xFF8C78FF),
            ),
        trackAlpha = 0.22f,
    )

val LocalDataColors = staticCompositionLocalOf { DataColorsLight }

/** Whether the current scheme is the dark one (for charts that tune opacity). */
val LocalIsDark = staticCompositionLocalOf { false }

internal fun ColorScheme.withVividSurfaces(dark: Boolean): ColorScheme {
    // Wallpaper colours tint the chrome (buttons, selection); surfaces and data colours stay neutral and fixed.
    val base = if (dark) VividDark else VividLight
    return copy(
        background = base.background,
        onBackground = base.onBackground,
        surface = base.surface,
        onSurface = base.onSurface,
        surfaceVariant = base.surfaceVariant,
        onSurfaceVariant = base.onSurfaceVariant,
        surfaceContainerLowest = base.surfaceContainerLowest,
        surfaceContainerLow = base.surfaceContainerLow,
        surfaceContainer = base.surfaceContainer,
        surfaceContainerHigh = base.surfaceContainerHigh,
        surfaceContainerHighest = base.surfaceContainerHighest,
        outline = base.outline,
        outlineVariant = base.outlineVariant,
        error = base.error,
        onError = base.onError,
        errorContainer = base.errorContainer,
        onErrorContainer = base.onErrorContainer,
    )
}

// Kept for code that hasn't moved to DataColors yet.
val StatusSuccess = Color(0xFF17A34A)
val StatusWarning = Color(0xFFE08600)
val StatusError = Color(0xFFD42A1F)
