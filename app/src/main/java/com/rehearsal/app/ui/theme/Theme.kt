package com.rehearsal.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.rehearsal.app.data.StemId

object Daw {
    val bg = Color(0xFF07080C)
    val surface = Color(0xFF12141B)
    val surfaceHigh = Color(0xFF1A1D27)
    val line = Color(0xFF2A3040)
    val text = Color(0xFFF2F3F7)
    val muted = Color(0xFF8B93A7)
    val accent = Color(0xFF8B7CFF)
    val play = Color(0xFF6C5CE7)
    val muteOn = Color(0xFFFF4D6A)
    val soloOn = Color(0xFFFFC857)
    val vocals = Color(0xFFFF5C8A)
    val drums = Color(0xFFFFB020)
    val bass = Color(0xFF2EE6A6)
    val other = Color(0xFF8B7CFF)
    val cardShape = RoundedCornerShape(18.dp)
    val pillShape = RoundedCornerShape(12.dp)

    fun stem(id: StemId): Color = when (id) {
        StemId.VOCALS -> vocals
        StemId.DRUMS -> drums
        StemId.BASS -> bass
        StemId.OTHER -> other
    }
}

private val DawScheme = darkColorScheme(
    primary = Daw.accent,
    onPrimary = Color(0xFF160F33),
    primaryContainer = Color(0xFF3D2E7C),
    onPrimaryContainer = Color(0xFFE8E2FF),
    secondary = Daw.bass,
    background = Daw.bg,
    onBackground = Daw.text,
    surface = Daw.surface,
    onSurface = Daw.text,
    surfaceVariant = Daw.surfaceHigh,
    onSurfaceVariant = Daw.muted,
    outline = Daw.line,
    error = Daw.muteOn,
    tertiary = Daw.drums,
)

@Composable
fun RehearsalTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }
    MaterialTheme(
        colorScheme = DawScheme,
        content = content,
    )
}
