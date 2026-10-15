package com.storehop.app.ui.shop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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

/**
 * Converts an ARGB [Int] into HSV components `[hue, saturation, value]` where
 * `hue` is in `0f..360f` and `saturation` / `value` are in `0f..1f`.
 */
fun argbToHsv(colorArgb: Int): FloatArray {
    val r = ((colorArgb ushr 16) and 0xFF) / 255f
    val g = ((colorArgb ushr 8) and 0xFF) / 255f
    val b = (colorArgb and 0xFF) / 255f

    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min

    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta).mod(6f))
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    val saturation = if (max == 0f) 0f else delta / max
    return floatArrayOf(hue, saturation, max)
}

/**
 * Converts HSV components (`hue` in `0f..360f`, `saturation` in `0f..1f`,
 * `value` in `0f..1f`) into an opaque `0xFFRRGGBB` ARGB [Int].
 */
fun hsvToArgb(hue: Float, saturation: Float, value: Float): Int {
    val h = hue.mod(360f)
    val s = saturation.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)

    val c = v * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = v - c

    val (r1, g1, b1) = when (h.toInt() / 60) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }

    val r = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255)
    val g = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255)
    val b = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
    return normalizeStoreColorArgb((r shl 16) or (g shl 8) or b)
}
