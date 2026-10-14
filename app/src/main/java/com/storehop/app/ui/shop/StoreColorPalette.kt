package com.storehop.app.ui.shop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.util.Locale

/**
 * Curated store-brand background colors offered as one-tap swatches when
 * creating or customizing a store tile. Each entry is a full-alpha (`0xFF`)
 * ARGB [Int]. Users can also enter any custom 6-digit `#RRGGBB` hex code or
 * reset to `null` (the theme's default card surface).
 */
val STORE_COLOR_PALETTE: List<Int> = listOf(
    0xFFD32F2F.toInt(), // Brand Red (Continente, Target, Auchan, Trader Joe's)
    0xFFC2185B.toInt(), // Berry / Magenta (Liberty, T-Mobile)
    0xFF7B1FA2.toInt(), // Royal Purple (Well's)
    0xFF283593.toInt(), // Deep Navy / Indigo (Aldi, Costco)
    0xFF1565C0.toInt(), // Brand Blue (Lidl, Walmart, Carrefour)
    0xFF0277BD.toInt(), // Ocean Blue (Tesco, Kroger)
    0xFF00695C.toInt(), // Deep Teal (Normal, Pharmacia)
    0xFF2E7D32.toInt(), // Forest Green (Pingo Doce, Leroy Merlin, Whole Foods)
    0xFF33691E.toInt(), // Fresh Olive Green (Produce / Sprouts)
    0xFFF9A825.toInt(), // Warm Gold / Yellow (Glovo, Ikea)
    0xFFD84315.toInt(), // Deep Brand Orange (Home Depot, Sainsbury's)
    0xFF5D4037.toInt(), // Warm Brown / Espresso (Bakery, Butcher)
)

private val DarkTileContent = Color(0xFF1A1815)
private val LightTileContent = Color(0xFFFFFFFF)

/**
 * Forces the alpha channel of [colorArgb] to `0xFF` so stored 24-bit RGB or
 * 32-bit ARGB integers always render as an opaque tile fill.
 */
fun normalizeStoreColorArgb(colorArgb: Int): Int =
    colorArgb or 0xFF000000.toInt()

/**
 * Converts a stored `Store.colorArgb` [Int] into an opaque Compose [Color].
 */
fun storeColorIntToComposeColor(colorArgb: Int): Color =
    Color(normalizeStoreColorArgb(colorArgb))

/**
 * Returns `true` when [colorArgb] has high enough relative luminance that dark
 * text (`#1A1815`) achieves better WCAG contrast than white text (`#FFFFFF`).
 *
 * The mathematical crossover where `(L + 0.05) / (0.009 + 0.05)` exceeds
 * `1.05 / (L + 0.05)` is around `L ≈ 0.20–0.25`. Using `0.25f` keeps saturated
 * brand reds, blues, greens, and purples with crisp white text while bright
 * yellows, golds, and pastels switch to dark charcoal text.
 */
fun isLightStoreColor(colorArgb: Int): Boolean =
    storeColorIntToComposeColor(colorArgb).luminance() >= 0.25f

/**
 * Contrast-safe foreground [Color] for text and icons rendered on top of a
 * store tile with background [colorArgb].
 */
fun storeTileContentColor(colorArgb: Int): Color =
    if (isLightStoreColor(colorArgb)) DarkTileContent else LightTileContent

/**
 * Formats [colorArgb] as an uppercase 6-digit `#RRGGBB` hex string.
 */
fun formatStoreHexColor(colorArgb: Int): String =
    String.format(Locale.US, "#%06X", colorArgb and 0xFFFFFF)

/**
 * Parses a user-supplied hex color string (`"#RRGGBB"` or `"RRGGBB"`) into an
 * opaque ARGB [Int], or returns `null` if [input] is not a valid 6-digit hex
 * color.
 */
fun parseStoreHexColor(input: String): Int? {
    val cleaned = input.trim().removePrefix("#")
    if (cleaned.length != 6) return null
    val rgb = cleaned.toIntOrNull(16) ?: return null
    return normalizeStoreColorArgb(rgb)
}
