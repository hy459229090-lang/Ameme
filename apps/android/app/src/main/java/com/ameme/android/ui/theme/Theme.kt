package com.ameme.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MemoryTeal = Color(0xFF0D6B5B)
private val MemoryTealDark = Color(0xFF72D6B5)

private val LightColors = lightColorScheme(
    primary = MemoryTeal,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA8F2D8),
    onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF45665D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC9E9DD),
    onSecondaryContainer = Color(0xFF08211B),
    tertiary = Color(0xFF526179),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDAE2FF),
    onTertiaryContainer = Color(0xFF0E1B33),
    background = Color(0xFFFCF9F5),
    onBackground = Color(0xFF1B1C1A),
    surface = Color(0xFFFCF9F5),
    onSurface = Color(0xFF1B1C1A),
    surfaceVariant = Color(0xFFE3E8E3),
    onSurfaceVariant = Color(0xFF434844),
    outline = Color(0xFF737874),
    outlineVariant = Color(0xFFC3C8C3),
    surfaceBright = Color(0xFFFFFCF8),
    surfaceDim = Color(0xFFDDDAD6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F3EF),
    surfaceContainer = Color(0xFFF1EEEA),
    surfaceContainerHigh = Color(0xFFEBE8E4),
    surfaceContainerHighest = Color(0xFFE5E2DE),
)

private val DarkColors = darkColorScheme(
    primary = MemoryTealDark,
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF005142),
    onPrimaryContainer = Color(0xFF91F5D2),
    secondary = Color(0xFFACCFC3),
    onSecondary = Color(0xFF17372F),
    secondaryContainer = Color(0xFF2E4E46),
    onSecondaryContainer = Color(0xFFC8EBDE),
    tertiary = Color(0xFFBAC7E4),
    onTertiary = Color(0xFF243149),
    tertiaryContainer = Color(0xFF3B4861),
    onTertiaryContainer = Color(0xFFDAE2FF),
    background = Color(0xFF101412),
    onBackground = Color(0xFFE1E4E0),
    surface = Color(0xFF101412),
    onSurface = Color(0xFFE1E4E0),
    surfaceVariant = Color(0xFF404844),
    onSurfaceVariant = Color(0xFFC0C9C4),
    outline = Color(0xFF8A938E),
    outlineVariant = Color(0xFF404844),
    surfaceBright = Color(0xFF363A38),
    surfaceDim = Color(0xFF101412),
    surfaceContainerLowest = Color(0xFF0B0F0D),
    surfaceContainerLow = Color(0xFF181C1A),
    surfaceContainer = Color(0xFF1C201E),
    surfaceContainerHigh = Color(0xFF272B29),
    surfaceContainerHighest = Color(0xFF323634),
)

private val AmemeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val AmemeTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun AmemeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context).withAmemeIdentity(darkTheme = true)
        dynamicColor -> dynamicLightColorScheme(context).withAmemeIdentity(darkTheme = false)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AmemeTypography,
        shapes = AmemeShapes,
        content = content,
    )
}

private fun androidx.compose.material3.ColorScheme.withAmemeIdentity(
    darkTheme: Boolean,
): androidx.compose.material3.ColorScheme {
    val brand = if (darkTheme) DarkColors else LightColors
    return copy(
        primary = brand.primary,
        onPrimary = brand.onPrimary,
        primaryContainer = brand.primaryContainer,
        onPrimaryContainer = brand.onPrimaryContainer,
        background = brand.background,
        onBackground = brand.onBackground,
        surface = brand.surface,
        onSurface = brand.onSurface,
        surfaceVariant = brand.surfaceVariant,
        onSurfaceVariant = brand.onSurfaceVariant,
        outline = brand.outline,
        outlineVariant = brand.outlineVariant,
        surfaceBright = brand.surfaceBright,
        surfaceDim = brand.surfaceDim,
        surfaceContainerLowest = brand.surfaceContainerLowest,
        surfaceContainerLow = brand.surfaceContainerLow,
        surfaceContainer = brand.surfaceContainer,
        surfaceContainerHigh = brand.surfaceContainerHigh,
        surfaceContainerHighest = brand.surfaceContainerHighest,
    )
}
