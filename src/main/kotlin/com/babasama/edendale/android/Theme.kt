package com.babasama.edendale.android

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Bebas Neue drives display titles and section headers. */
private val BebasNeue = FontFamily(
    Font(R.font.bebas_neue_regular, weight = FontWeight.Normal),
)

/** Inter (variable) carries titles, body, and labels; weights map to wght axis. */
private val Inter = FontFamily(
    Font(R.font.inter_variable, weight = FontWeight.Normal),
    Font(R.font.inter_variable, weight = FontWeight.Medium),
    Font(R.font.inter_variable, weight = FontWeight.SemiBold),
    Font(R.font.inter_variable, weight = FontWeight.Bold),
    Font(R.font.inter_italic_variable, weight = FontWeight.Normal, style = FontStyle.Italic),
    Font(R.font.inter_italic_variable, weight = FontWeight.SemiBold, style = FontStyle.Italic),
)

/** Exact Android counterparts of the Apple asset-catalog semantic colors. */
object EdendaleColors {
    val Background = Color(0xFF0A0A0F)
    val Surface = Color(0xFF1F1F25)
    val SurfaceLow = Color(0xFF131318)
    val SurfaceHigh = Color(0xFF2A292F)
    val Gold = Color(0xFFF4BE5D)
    val GoldDeep = Color(0xFFC9973A)
    val OnGold = Color(0xFF422C00)
    val TextPrimary = Color(0xFFE4E1E9)
    val TextSecondary = Color(0xFFD3C4B1)
    val Outline = Color(0xFF4F4537)
    val OutlineBright = Color(0xFF9C8F7D)
    val HeatLow = Color(0xFF1D3A4A)
    val HeatMid = Color(0xFF2B678A)
    val QrCodeBackground = Color(0xFFFFFFFF)
}

object EdendaleRadii {
    /** Controls and buttons — Apple's Theme.Radius.soft. */
    const val Soft = 4
    /** Inset grouped form containers — Apple's Theme.Radius.card. */
    const val Group = 8
    const val Card = 12
    const val Hero = 20
}

private val EdendaleDarkColorScheme: ColorScheme = darkColorScheme(
    primary = EdendaleColors.Gold,
    onPrimary = EdendaleColors.OnGold,
    primaryContainer = EdendaleColors.GoldDeep,
    onPrimaryContainer = EdendaleColors.OnGold,
    secondary = EdendaleColors.TextSecondary,
    onSecondary = EdendaleColors.Background,
    secondaryContainer = EdendaleColors.SurfaceHigh,
    onSecondaryContainer = EdendaleColors.TextPrimary,
    tertiary = EdendaleColors.OutlineBright,
    onTertiary = EdendaleColors.Background,
    background = EdendaleColors.Background,
    onBackground = EdendaleColors.TextPrimary,
    surface = EdendaleColors.Surface,
    onSurface = EdendaleColors.TextPrimary,
    surfaceDim = EdendaleColors.SurfaceLow,
    surfaceContainerLowest = EdendaleColors.Background,
    surfaceContainerLow = EdendaleColors.SurfaceLow,
    surfaceContainer = EdendaleColors.Surface,
    surfaceContainerHigh = EdendaleColors.SurfaceHigh,
    surfaceContainerHighest = EdendaleColors.SurfaceHigh,
    surfaceVariant = EdendaleColors.SurfaceHigh,
    onSurfaceVariant = EdendaleColors.TextSecondary,
    outline = EdendaleColors.Outline,
    outlineVariant = EdendaleColors.Outline,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val EdendaleTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = BebasNeue,
        fontWeight = FontWeight.Normal,
        fontSize = 72.sp,
        lineHeight = 74.sp,
        letterSpacing = 1.5.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = BebasNeue,
        fontWeight = FontWeight.Normal,
        fontSize = 54.sp,
        lineHeight = 58.sp,
        letterSpacing = 1.2.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = BebasNeue,
        fontWeight = FontWeight.Normal,
        fontSize = 42.sp,
        lineHeight = 46.sp,
        letterSpacing = 1.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = BebasNeue,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = 1.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = BebasNeue,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = .8.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = BebasNeue,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = .8.sp,
    ),
    titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 1.2.sp,
    ),
)

@Composable
fun EdendaleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EdendaleDarkColorScheme,
        typography = EdendaleTypography,
        content = content,
    )
}
