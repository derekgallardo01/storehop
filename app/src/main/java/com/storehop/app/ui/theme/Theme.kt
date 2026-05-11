package com.storehop.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun StorehopTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors

    // Edge-to-edge is enabled in MainActivity, so the status bar is transparent and content
    // draws under it. We only need to flip the icon tint so they read against the chosen scheme.
    // Cast is defensive: Paparazzi screenshot tests host the Compose tree under a non-Activity
    // context, and view.isInEditMode doesn't reliably gate them out. A null-safe cast lets the
    // theme render cleanly in headless contexts; the SideEffect just no-ops there.
    val view = LocalView.current
    val activity = view.context as? Activity
    if (!view.isInEditMode && activity != null) {
        SideEffect {
            WindowCompat.getInsetsController(activity.window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = StorehopTypography,
        shapes = StorehopShapes,
        content = content,
    )
}
