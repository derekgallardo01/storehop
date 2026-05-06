package com.storehop.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Warm neutrals + sage accent. Calm, high-legibility.
// Hex anchors locked in plan 1-for-colors-and-temporal-quill.md.

private val SageLight       = Color(0xFF5A7A5C)
private val SageOnLight     = Color(0xFFFFFFFF)
private val SageContainerL  = Color(0xFFD8E5D5)
private val SageOnContainerL = Color(0xFF1F3322)

private val WarmGrayL       = Color(0xFF6F6A60)
private val WarmOffWhite    = Color(0xFFFAF8F4)
private val WarmCharcoal    = Color(0xFF2A2825)
private val SurfaceVarL     = Color(0xFFEFECE6)
private val OutlineL        = Color(0xFFBDB8AE)
private val ErrorL          = Color(0xFFB3261E)

private val SageDark        = Color(0xFFA4C0A0)
private val SageOnDark      = Color(0xFF1F3322)
private val SageContainerD  = Color(0xFF3A5A3D)
private val SageOnContainerD = Color(0xFFD8E5D5)

private val WarmGrayD       = Color(0xFFCFC9BD)
private val WarmGrayOnD     = Color(0xFF3A352D)
private val WarmCharcoalBg  = Color(0xFF1A1815)
private val WarmCream       = Color(0xFFF0EDE6)
private val SurfaceD        = Color(0xFF252320)
private val SurfaceVarD     = Color(0xFF34312C)
private val OutlineD        = Color(0xFF6F6A60)
private val ErrorD          = Color(0xFFF2B8B5)

val LightColors = lightColorScheme(
    primary            = SageLight,
    onPrimary          = SageOnLight,
    primaryContainer   = SageContainerL,
    onPrimaryContainer = SageOnContainerL,
    secondary          = WarmGrayL,
    onSecondary        = SageOnLight,
    background         = WarmOffWhite,
    onBackground       = WarmCharcoal,
    surface            = Color(0xFFFFFFFF),
    onSurface          = WarmCharcoal,
    surfaceVariant     = SurfaceVarL,
    onSurfaceVariant   = WarmGrayL,
    outline            = OutlineL,
    error              = ErrorL,
    onError            = SageOnLight,
)

val DarkColors = darkColorScheme(
    primary            = SageDark,
    onPrimary          = SageOnDark,
    primaryContainer   = SageContainerD,
    onPrimaryContainer = SageOnContainerD,
    secondary          = WarmGrayD,
    onSecondary        = WarmGrayOnD,
    background         = WarmCharcoalBg,
    onBackground       = WarmCream,
    surface            = SurfaceD,
    onSurface          = WarmCream,
    surfaceVariant     = SurfaceVarD,
    onSurfaceVariant   = WarmGrayD,
    outline            = OutlineD,
    error              = ErrorD,
    onError            = WarmCharcoalBg,
)
