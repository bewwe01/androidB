package dev.dbexplorer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.sp
import dev.dbexplorer.domain.model.ConnectionColor

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B5E8C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE5FF),
    onPrimaryContainer = Color(0xFF001D32),
    secondary = Color(0xFF51606F),
    tertiary = Color(0xFF67587A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF94CCFF),
    onPrimary = Color(0xFF003352),
    primaryContainer = Color(0xFF004B74),
    onPrimaryContainer = Color(0xFFCDE5FF),
    secondary = Color(0xFFB9C8DA),
    tertiary = Color(0xFFD3BFE6),
)

@Composable
fun DbExplorerTheme(darkTheme: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
}

/** Monospaced style for identifiers, types and SQL. */
val CodeTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)

/** Swatch for a connection's color tag; null for [ConnectionColor.NONE]. */
fun ConnectionColor.swatch(): Color? = when (this) {
    ConnectionColor.NONE -> null
    ConnectionColor.RED -> Color(0xFFE53935)
    ConnectionColor.ORANGE -> Color(0xFFFB8C00)
    ConnectionColor.YELLOW -> Color(0xFFFDD835)
    ConnectionColor.GREEN -> Color(0xFF43A047)
    ConnectionColor.BLUE -> Color(0xFF1E88E5)
    ConnectionColor.PURPLE -> Color(0xFF8E24AA)
}

val ProductionRed = Color(0xFFC62828)
