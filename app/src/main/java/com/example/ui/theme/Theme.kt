package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.UISettings
import com.example.ui.theme.UiStrings

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

/**
 * 侧边栏专属色板。AI 可以通过修改 sidebarBackgroundColor 等字段来自定义侧边栏外观。
 */
data class SidebarColors(
    val background: Color,
    val onBackground: Color,
    val activeBackground: Color,
    val onActiveBackground: Color
)

val LocalSidebarColors = staticCompositionLocalOf {
    SidebarColors(
        background = Color(0xFFFFFBFE),
        onBackground = Color(0xFF1C1B1F),
        activeBackground = Color(0xFFEADDFF),
        onActiveBackground = Color(0xFF21005D)
    )
}

/**
 * 聊天气泡字体缩放比例，独立于全局 fontSizeScale，供聊天内容单独调整。
 * 通过 CompositionLocal 传递，ChatScreen 中直接读取使用。
 */
val LocalChatFontScale = staticCompositionLocalOf { 1.0f }

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

/**
 * 将 fontFamily 字符串标识符解析为 Compose [FontFamily]。
 * 不支持的值回退到 [FontFamily.Default]。
 */
fun resolveFontFamily(identifier: String): FontFamily = when (identifier.lowercase().trim()) {
    "serif"     -> FontFamily.Serif
    "monospace" -> FontFamily.Monospace
    "cursive"   -> FontFamily.Cursive
    else        -> FontFamily.Default  // "default" 及其他未知值
}

/**
 * 根据 [fontFamily] 和 [scale] 构建完整的 Material 3 [Typography]。
 * 仅缩放字号（fontSize / lineHeight），不改变字重和字距。
 */
fun buildTypography(fontFamily: FontFamily, scale: Float): Typography {
    val s = scale.coerceIn(0.75f, 1.5f)
    return Typography(
        displayLarge   = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal,   fontSize = (57 * s).sp, lineHeight = (64 * s).sp, letterSpacing = (-0.25).sp),
        displayMedium  = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal,   fontSize = (45 * s).sp, lineHeight = (52 * s).sp, letterSpacing = 0.sp),
        displaySmall   = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal,   fontSize = (36 * s).sp, lineHeight = (44 * s).sp, letterSpacing = 0.sp),
        headlineLarge  = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal,   fontSize = (32 * s).sp, lineHeight = (40 * s).sp, letterSpacing = 0.sp),
        headlineMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal,   fontSize = (28 * s).sp, lineHeight = (36 * s).sp, letterSpacing = 0.sp),
        headlineSmall  = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal,   fontSize = (24 * s).sp, lineHeight = (32 * s).sp, letterSpacing = 0.sp),
        titleLarge     = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal,   fontSize = (22 * s).sp, lineHeight = (28 * s).sp, letterSpacing = 0.sp),
        titleMedium    = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Medium,   fontSize = (16 * s).sp, lineHeight = (24 * s).sp, letterSpacing = 0.15.sp),
        titleSmall     = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Medium,   fontSize = (14 * s).sp, lineHeight = (20 * s).sp, letterSpacing = 0.1.sp),
        bodyLarge      = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal,   fontSize = (16 * s).sp, lineHeight = (24 * s).sp, letterSpacing = 0.5.sp),
        bodyMedium     = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal,   fontSize = (14 * s).sp, lineHeight = (20 * s).sp, letterSpacing = 0.25.sp),
        bodySmall      = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal,   fontSize = (12 * s).sp, lineHeight = (16 * s).sp, letterSpacing = 0.4.sp),
        labelLarge     = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Medium,   fontSize = (14 * s).sp, lineHeight = (20 * s).sp, letterSpacing = 0.1.sp),
        labelMedium    = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Medium,   fontSize = (12 * s).sp, lineHeight = (16 * s).sp, letterSpacing = 0.5.sp),
        labelSmall     = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Medium,   fontSize = (11 * s).sp, lineHeight = (16 * s).sp, letterSpacing = 0.5.sp),
    )
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

  val sidebarColors = SidebarColors(
    background = settings.sidebarBackgroundColor.parseOr(Color(0xFFFFFBFE)),
    onBackground = settings.sidebarOnBackgroundColor.parseOr(Color(0xFF1C1B1F)),
    activeBackground = settings.sidebarActiveColor.parseOr(Color(0xFFEADDFF)),
    onActiveBackground = settings.sidebarOnActiveColor.parseOr(Color(0xFF21005D))
  )

  // 字体：解析字体族 + 构建带缩放的 Typography
  val resolvedFontFamily = resolveFontFamily(settings.fontFamily)
  val typography = buildTypography(resolvedFontFamily, settings.fontSizeScale)

  CompositionLocalProvider(
    LocalUISettings provides settings,
    LocalCustomColors provides customColors,
    LocalSidebarColors provides sidebarColors,
    LocalChatFontScale provides settings.chatFontSizeScale.coerceIn(0.75f, 1.5f),
    LocalUiStrings provides UiStrings.fromJson(settings.uiStrings)
  ) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = typography,
      shapes = shapes,
      content = content
    )
  }
}