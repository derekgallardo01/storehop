package com.storehop.app.ui.shop

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StoreColorPaletteTest {

    @Test fun `STORE_COLOR_PALETTE contains 12 unique opaque brand colors`() {
        assertThat(STORE_COLOR_PALETTE).hasSize(12)
        assertThat(STORE_COLOR_PALETTE.distinct()).hasSize(12)
        STORE_COLOR_PALETTE.forEach { colorArgb ->
            // Alpha channel must be 0xFF (opaque).
            assertThat(colorArgb ushr 24).isEqualTo(0xFF)
        }
    }

    @Test fun `normalizeStoreColorArgb forces 0xFF alpha on 24-bit RGB ints`() {
        assertThat(normalizeStoreColorArgb(0x1565C0)).isEqualTo(0xFF1565C0.toInt())
        assertThat(normalizeStoreColorArgb(0xFF1565C0.toInt())).isEqualTo(0xFF1565C0.toInt())
        assertThat(normalizeStoreColorArgb(0x000000)).isEqualTo(0xFF000000.toInt())
        assertThat(storeColorIntToComposeColor(0x1565C0)).isEqualTo(Color(0xFF1565C0))
        assertThat(storeColorIntToComposeColor(0xFF1565C0.toInt())).isEqualTo(Color(0xFF1565C0))
    }

    @Test fun `formatStoreHexColor and parseStoreHexColor round-trip all palette colors`() {
        STORE_COLOR_PALETTE.forEach { colorArgb ->
            val hex = formatStoreHexColor(colorArgb)
            assertThat(hex).matches("^#[0-9A-F]{6}$")
            assertThat(parseStoreHexColor(hex)).isEqualTo(colorArgb)
        }
    }

    @Test fun `parseStoreHexColor accepts leading hash, no hash, lowercase, and whitespace`() {
        val expected = 0xFF1565C0.toInt()
        assertThat(parseStoreHexColor("#1565C0")).isEqualTo(expected)
        assertThat(parseStoreHexColor("1565C0")).isEqualTo(expected)
        assertThat(parseStoreHexColor("  #1565c0  ")).isEqualTo(expected)
        assertThat(parseStoreHexColor("1565c0")).isEqualTo(expected)
    }

    @Test fun `parseStoreHexColor returns null for empty, wrong-length, or non-hex inputs`() {
        assertThat(parseStoreHexColor("")).isNull()
        assertThat(parseStoreHexColor("   ")).isNull()
        assertThat(parseStoreHexColor("#")).isNull()
        assertThat(parseStoreHexColor("#123")).isNull()
        assertThat(parseStoreHexColor("#12345")).isNull()
        assertThat(parseStoreHexColor("#1234567")).isNull()
        assertThat(parseStoreHexColor("#12345G")).isNull()
        assertThat(parseStoreHexColor("not-a-color")).isNull()
    }

    @Test fun `storeTileContentColor returns white on dark brand colors and dark charcoal on light colors`() {
        // Dark brand colors -> white text.
        assertThat(isLightStoreColor(0xFF1565C0.toInt())).isFalse() // Lidl blue
        assertThat(storeTileContentColor(0xFF1565C0.toInt())).isEqualTo(Color(0xFFFFFFFF))

        assertThat(isLightStoreColor(0xFFD32F2F.toInt())).isFalse() // Continente red
        assertThat(storeTileContentColor(0xFFD32F2F.toInt())).isEqualTo(Color(0xFFFFFFFF))

        assertThat(isLightStoreColor(0xFF2E7D32.toInt())).isFalse() // Pingo Doce green
        assertThat(storeTileContentColor(0xFF2E7D32.toInt())).isEqualTo(Color(0xFFFFFFFF))

        assertThat(isLightStoreColor(0xFF000000.toInt())).isFalse() // Black
        assertThat(storeTileContentColor(0xFF000000.toInt())).isEqualTo(Color(0xFFFFFFFF))

        // Light brand colors -> dark charcoal text.
        assertThat(isLightStoreColor(0xFFF9A825.toInt())).isTrue()  // Warm gold/yellow
        assertThat(storeTileContentColor(0xFFF9A825.toInt())).isEqualTo(Color(0xFF1A1815))

        assertThat(isLightStoreColor(0xFFFFFFFF.toInt())).isTrue()  // White
        assertThat(storeTileContentColor(0xFFFFFFFF.toInt())).isEqualTo(Color(0xFF1A1815))

        assertThat(isLightStoreColor(0xFFC8E6C9.toInt())).isTrue()  // Pastel mint
        assertThat(storeTileContentColor(0xFFC8E6C9.toInt())).isEqualTo(Color(0xFF1A1815))
    }

    @Test fun `argbToHsv and hsvToArgb round-trip palette colors, primary hues, black, and white`() {
        val testColors = STORE_COLOR_PALETTE + listOf(
            0xFF000000.toInt(), // Black (delta = 0, max = 0)
            0xFFFFFFFF.toInt(), // White (delta = 0, max = 1)
            0xFFFF0000.toInt(), // Sector 0 (Red)
            0xFF80FF00.toInt(), // Sector 1 (Chartreuse)
            0xFF00FF80.toInt(), // Sector 2 (Spring green)
            0xFF0080FF.toInt(), // Sector 3 (Azure)
            0xFF8000FF.toInt(), // Sector 4 (Violet)
            0xFFFF0080.toInt(), // Sector 5 (Rose)
        )
        testColors.forEach { colorArgb ->
            val hsv = argbToHsv(colorArgb)
            val roundTripped = hsvToArgb(hsv[0], hsv[1], hsv[2])
            assertThat(formatStoreHexColor(roundTripped)).isEqualTo(formatStoreHexColor(colorArgb))
        }
    }
}
