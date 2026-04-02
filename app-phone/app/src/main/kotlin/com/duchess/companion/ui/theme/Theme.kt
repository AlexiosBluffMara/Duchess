package com.duchess.companion.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val ConstructionOrange = Color(0xFFE65100)
private val ConstructionOrangeLight = Color(0xFFFF833A)
private val ConstructionOrangeDark = Color(0xFFAC1900)
private val SafetyYellow = Color(0xFFFFD600)

private val LightColorScheme = lightColorScheme(
    primary = ConstructionOrange,
    onPrimary = Color.White,
    primaryContainer = ConstructionOrangeLight,
    onPrimaryContainer = Color(0xFF3E0400),
    secondary = SafetyYellow,
    onSecondary = Color.Black,
    error = Color(0xFFB3261E),
    onError = Color.White,
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F)
)

private val DarkColorScheme = darkColorScheme(
    primary = ConstructionOrangeLight,
    onPrimary = Color(0xFF5F1600),
    primaryContainer = ConstructionOrange,
    onPrimaryContainer = Color(0xFFFFDBD0),
    secondary = SafetyYellow,
    onSecondary = Color.Black,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5)
)

@Composable
fun DuchessTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DuchessTypography,
        content = content
    )
}
