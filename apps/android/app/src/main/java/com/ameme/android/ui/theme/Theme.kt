package com.ameme.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val MemoryTeal = Color(0xFF0D6B5B)
private val MemoryTealDark = Color(0xFF72D6B5)

private val LightColors = lightColorScheme(
    primary = MemoryTeal,
    secondary = Color(0xFF45665D),
    tertiary = Color(0xFF3F6374),
)

private val DarkColors = darkColorScheme(
    primary = MemoryTealDark,
    secondary = Color(0xFFACCFC3),
    tertiary = Color(0xFFA7CDDF),
)

@Composable
fun AmemeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
