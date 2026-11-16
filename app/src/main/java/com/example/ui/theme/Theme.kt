package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkSecurityColorScheme =
  darkColorScheme(
    primary = Emerald500,
    onPrimary = Obsidian950,
    primaryContainer = Emerald900,
    onPrimaryContainer = Emerald400,
    secondary = Cyan400,
    onSecondary = Obsidian950,
    secondaryContainer = Cyan900,
    onSecondaryContainer = Cyan400,
    tertiary = Amber500,
    onTertiary = Obsidian950,
    tertiaryContainer = Amber900,
    onTertiaryContainer = Amber500,
    error = Crimson500,
    onError = Color.White,
    errorContainer = Crimson900,
    onErrorContainer = Color(0xFFFCA5A5),
    background = Obsidian950,
    onBackground = TextPrimaryDark,
    surface = Obsidian900,
    onSurface = TextPrimaryDark,
    surfaceVariant = Slate850,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLow = Slate850,
    surfaceContainer = Slate800,
    surfaceContainerHigh = Slate700,
    outline = Slate700,
    outlineVariant = Color(0xFF1E293B),
  )

private val LightSecurityColorScheme =
  lightColorScheme(
    primary = Emerald600,
    onPrimary = PureWhite,
    primaryContainer = Emerald100,
    onPrimaryContainer = Emerald900,
    secondary = Cyan700,
    onSecondary = PureWhite,
    secondaryContainer = Cyan100,
    onSecondaryContainer = Cyan900,
    tertiary = Amber500,
    onTertiary = Obsidian950,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Amber900,
    error = Crimson600,
    onError = PureWhite,
    errorContainer = Crimson100,
    onErrorContainer = Crimson900,
    background = Arctic50,
    onBackground = TextPrimaryLight,
    surface = PureWhite,
    onSurface = TextPrimaryLight,
    surfaceVariant = Arctic100,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerLow = PureWhite,
    surfaceContainer = Arctic100,
    surfaceContainerHigh = Color(0xFFD8E2EE),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
  )

val TerraGuardShapes =
  Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
  )

@Composable
fun TerraGuardTheme(
  darkTheme: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkSecurityColorScheme else LightSecurityColorScheme

  MaterialTheme(
    colorScheme = colorScheme,
    typography = TerraGuardTypography,
    shapes = TerraGuardShapes,
    content = content,
  )
}
