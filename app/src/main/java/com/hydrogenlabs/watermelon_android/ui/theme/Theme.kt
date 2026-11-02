package com.hydrogenlabs.watermelon_android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Amber,
    secondary = Steel,
    tertiary = Amber,
    background = Slate,
    surface = Gunmetal,
    surfaceVariant = Graphite,
    onBackground = Color.White,
    onSurface = Color.White,
    outline = Border
)

@Composable
fun Watermelon_AndroidTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
