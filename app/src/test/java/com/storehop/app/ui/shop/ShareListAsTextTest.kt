package com.storehop.app.ui.shop

import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.relations.ShoppingRow
import org.junit.Test

class ShareListAsTextTest {

    private fun row(name: String, brand: String? = null) =
        ShoppingRow(
            itemId = "id-$name",
            itemName = name,
            quantity = null,
            notes = null,
            isNeeded = true,
            brand = brand,
            imageUrl = null,
            isPriority = false,
            isStaple = false,
            categoryId = null,
            categoryName = null,
            categoryNameKey = null,
            categoryIcon = null,
            displayOrder = null,
        )

    @Test fun `header is emitted verbatim and trailing whitespace is trimmed`() {
        val text = buildShareListText(
            header = "StoreHop — shopping at Lidl",
            sections = listOf(
                "Produce" to listOf(row("Bananas")),
            ),
        )
        assertThat(text).startsWith("StoreHop — shopping at Lidl")
        // No trailing blank line at the end of the payload.
        assertThat(text).doesNotMatch("(?s).*\\n\\s*$")
    }

    @Test fun `section labels are uppercased and rows are dash-prefixed in section order`() {
        val text = buildShareListText(
            header = "StoreHop — shopping at Continente",
            sections = listOf(
                "Produce" to listOf(row("Bananas"), row("Tomatoes")),
                "Dairy & Eggs" to listOf(row("Milk"), row("Eggs")),
            ),
        )
        // Sections come out in the order given, with their labels UPPER.
        val produceIdx = text.indexOf("PRODUCE")
        val dairyIdx = text.indexOf("DAIRY & EGGS")
        assertThat(produceIdx).isAtLeast(0)
        assertThat(dairyIdx).isGreaterThan(produceIdx)
        assertThat(text).contains("- Bananas")
        assertThat(text).contains("- Milk")
    }

    @Test fun `brand is rendered in parentheses when present`() {
        val text = buildShareListText(
            header = "StoreHop — shopping at Lidl",
            sections = listOf(
                "Dairy" to listOf(row("Milk", brand = "Mimosa"), row("Eggs")),
            ),
        )
        assertThat(text).contains("- Milk (Mimosa)")
        // No spurious empty parens for items without a brand.
        assertThat(text).contains("- Eggs")
        assertThat(text).doesNotContain("- Eggs ()")
    }

    @Test fun `blank brand string is treated as no brand`() {
        val text = buildShareListText(
            header = "StoreHop — shopping at Lidl",
            sections = listOf(
                "Pantry" to listOf(row("Rice", brand = "   ")),
            ),
        )
        assertThat(text).contains("- Rice")
        assertThat(text).doesNotContain("(   )")
    }
}
