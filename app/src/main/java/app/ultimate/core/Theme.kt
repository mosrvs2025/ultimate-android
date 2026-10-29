package app.ultimate.core

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

private val Light = lightColorScheme(
    primary = Color(0xFF5B3FD6), onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF), onPrimaryContainer = Color(0xFF1B0063),
    secondaryContainer = Color(0xFFDCE8F8), tertiary = Color(0xFF006B5B), tertiaryContainer = Color(0xFFB4F0E0),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFCABEFF), onPrimary = Color(0xFF2E0A9B),
    primaryContainer = Color(0xFF4323B4), onPrimaryContainer = Color(0xFFE6DEFF),
    secondaryContainer = Color(0xFF3B4858), tertiary = Color(0xFF7FD4C1), tertiaryContainer = Color(0xFF005045),
)

@Composable
fun UltimateTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
