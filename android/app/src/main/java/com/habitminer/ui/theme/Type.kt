package com.habitminer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.habitminer.R

/*
 * One family, Mona Sans (GitHub, SIL Open Font License), in three widths:
 * - Mona Sans for reading text,
 * - Mona Sans Display for headings,
 * - Mona Sans Display Expanded for numbers, so a figure like "2h 14m" reads as the thing
 *   the screen is about. Numbers use tabular figures so digits don't jump as they change.
 */

val MonaSans =
    FontFamily(
        Font(R.font.mona_sans_regular, FontWeight.Normal),
        Font(R.font.mona_sans_medium, FontWeight.Medium),
        Font(R.font.mona_sans_semibold, FontWeight.SemiBold),
        Font(R.font.mona_sans_semibold, FontWeight.Bold),
    )

val MonaSansDisplay =
    FontFamily(
        Font(R.font.mona_sans_display_semibold, FontWeight.SemiBold),
        Font(R.font.mona_sans_display_semibold, FontWeight.Bold),
        Font(R.font.mona_sans_semibold, FontWeight.Medium),
        Font(R.font.mona_sans_regular, FontWeight.Normal),
    )

val MonaSansExpanded =
    FontFamily(
        Font(R.font.mona_sans_expanded_medium, FontWeight.Medium),
        Font(R.font.mona_sans_expanded_medium, FontWeight.Normal),
        Font(R.font.mona_sans_expanded_semibold, FontWeight.SemiBold),
        Font(R.font.mona_sans_expanded_semibold, FontWeight.Bold),
    )

private const val TABULAR = "tnum"

val HabitMinerTypography =
    Typography(
        displayLarge = TextStyle(fontFamily = MonaSansExpanded, fontWeight = FontWeight.SemiBold, fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-1.5).sp, fontFeatureSettings = TABULAR),
        displayMedium = TextStyle(fontFamily = MonaSansExpanded, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 50.sp, letterSpacing = (-1).sp, fontFeatureSettings = TABULAR),
        displaySmall = TextStyle(fontFamily = MonaSansExpanded, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp, fontFeatureSettings = TABULAR),
        headlineLarge = TextStyle(fontFamily = MonaSansDisplay, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.4).sp),
        headlineMedium = TextStyle(fontFamily = MonaSansDisplay, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
        headlineSmall = TextStyle(fontFamily = MonaSansDisplay, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
        titleLarge = TextStyle(fontFamily = MonaSans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = TextStyle(fontFamily = MonaSans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
        titleSmall = TextStyle(fontFamily = MonaSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontFamily = MonaSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = MonaSans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontFamily = MonaSans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontFamily = MonaSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = TextStyle(fontFamily = MonaSans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
        labelSmall = TextStyle(fontFamily = MonaSans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    )

/** Number styles beyond the Material slots. */
object NumberStyles {
    /** The one big number on a screen. */
    val hero = TextStyle(fontFamily = MonaSansExpanded, fontWeight = FontWeight.SemiBold, fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-1.5).sp, fontFeatureSettings = TABULAR)

    /** Units next to the hero number ("h", "m"), smaller so the figures lead. */
    val heroUnit = TextStyle(fontFamily = MonaSansExpanded, fontWeight = FontWeight.Medium, fontSize = 30.sp, letterSpacing = (-0.5).sp)

    /** Secondary figures in a row of stats. */
    val stat = TextStyle(fontFamily = MonaSansExpanded, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp, fontFeatureSettings = TABULAR)

    /** Inline figures in rows and charts. */
    val small = TextStyle(fontFamily = MonaSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = TABULAR)
}
