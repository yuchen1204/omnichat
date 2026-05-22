package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.data.UISettings

val LocalUISettings = staticCompositionLocalOf { UISettings() }

/**
 * 扩展色板（Material 3 之外的语义色）。AI 通过 `adjust_ui` 工具可以修改其中所有项。
 */
data class CustomColors(
    val success: Color,
    val warning: Color,
    val info: Color,
    val accent: Color,
)

val LocalCustomColors = staticCompositionLocalOf {
    CustomColors(
        success = Color(0xFF34C759),
        warning = Color(0xFFFF9800),
        info = Color(0xFF007AFF),
        accent = Color(0xFFFF9500),
    )
}

private val DarkColorScheme =
  darkColorScheme(primary = Purple80, secondary = PurpleGrey80, tertiary = Pink80)

private val LightColorScheme =
  lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
  )

/**
 * 解析 #RRGGBB / #RRGGBBAA 字符串为 [Color]。无法解析时返回 [Color.Unspecified]。
 */
fun String.toComposeColor(): Color {
  return try {
    Color(android.graphics.Color.parseColor(this))
  } catch (e: Exception) {
    Color.Unspecified
  }
}

private fun String.parseOr(fallback: Color): Color {
  val parsed = this.toComposeColor()
  return if (parsed != Color.Unspecified) parsed else fallback
}

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = true,
  uiSettings: UISettings? = null,
  content: @Composable () -> Unit,
) {
  val settings = uiSettings ?: UISettings()
  val hasUserOverride = uiSettings != null && uiSettings.updatedAt > 0

  val colorScheme =
    when {
      // AI 自定义优先：用所有 UISettings 字段构建完整 lightColorScheme
      hasUserOverride -> {
        lightColorScheme(
          primary = settings.primaryColor.parseOr(Purple40),
          onPrimary = settings.onPrimaryColor.parseOr(Color.White),
          primaryContainer = settings.primaryContainerColor.parseOr(Color(0xFFEADDFF)),
          onPrimaryContainer = settings.onPrimaryContainerColor.parseOr(Color(0xFF21005D)),
          secondary = settings.secondaryColor.parseOr(PurpleGrey40),
          onSecondary = settings.onSecondaryColor.parseOr(Color.White),
          secondaryContainer = settings.secondaryContainerColor.parseOr(Color(0xFFE8DEF8)),
          onSecondaryContainer = settings.onSecondaryContainerColor.parseOr(Color(0xFF1D192B)),
          tertiary = settings.tertiaryColor.parseOr(Pink40),
          onTertiary = settings.onTertiaryColor.parseOr(Color.White),
          background = settings.backgroundColor.parseOr(Color(0xFFFFFBFE)),
          onBackground = settings.onBackgroundColor.parseOr(Color(0xFF1C1B1F)),
          surface = settings.surfaceColor.parseOr(Color(0xFFFFFBFE)),
          onSurface = settings.onSurfaceColor.parseOr(Color(0xFF1C1B1F)),
          surfaceVariant = settings.surfaceVariantColor.parseOr(Color(0xFFE7E0EC)),
          onSurfaceVariant = settings.onSurfaceVariantColor.parseOr(Color(0xFF49454F)),
          outline = settings.outlineColor.parseOr(Color(0xFF79747E)),
          outlineVariant = settings.outlineVariantColor.parseOr(Color(0xFFCAC4D0)),
          error = settings.errorColor.parseOr(Color(0xFFB3261E)),
          onError = settings.onErrorColor.parseOr(Color.White),
          errorContainer = settings.errorContainerColor.parseOr(Color(0xFFF9DEDC)),
          onErrorContainer = settings.onErrorContainerColor.parseOr(Color(0xFF410E0B)),
        )
      }

      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  val shapes = Shapes(
    small = RoundedCornerShape(settings.cornerRadiusDp.dp / 2),
    medium = RoundedCornerShape(settings.cornerRadiusDp.dp),
    large = RoundedCornerShape(settings.cornerRadiusDp.dp * 1.5f)
  )

  val customColors = CustomColors(
    success = settings.successColor.parseOr(Color(0xFF34C759)),
    warning = settings.warningColor.parseOr(Color(0xFFFF9800)),
    info = settings.infoColor.parseOr(Color(0xFF007AFF)),
    accent = settings.accentColor.parseOr(Color(0xFFFF9500)),
  )

  CompositionLocalProvider(
    LocalUISettings provides settings,
    LocalCustomColors provides customColors
  ) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = Typography,
      shapes = shapes,
      content = content
    )
  }
}
