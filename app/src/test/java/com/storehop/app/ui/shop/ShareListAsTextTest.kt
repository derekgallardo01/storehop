package com.storehop.app.ui.shop

import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.relations.ShoppingRow
import org.junit.Test

class ShareListAsTextTest {

    private fun row(name: String, brand: String? = null, category: String? = null) =
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
            categoryId = category?.let { "cat-$it" },
            categoryName = category,
            categoryIcon = null,
            displayOrder = null,
        )

    @Test fun `header includes the store name and trailing whitespace is trimmed`() {
        val text = buildShareListText(
            storeName = "Lidl",
            sections = listOf(
                CategorySection(
                    categoryName = "Produce",
                    displayOrder = 0,
                    rows = listOf(row("Bananas")),
                ),
            ),
        )
        assertThat(text).startsWith("StoreHop — shopping at Lidl")
        // No trailing blank line at the end of the payload.
        assertThat(text).doesNotMatch("(?s).*\\n\\s*$")
    }

    @Test fun `categories are uppercased and rows are dash-prefixed in section order`() {
        val text = buildShareListText(
            storeName = "Continente",
            sections = listOf(
                CategorySection(
                    categoryName = "Produce",
                    displayOrder = 0,
                    rows = listOf(row("Bananas"), row("Tomatoes")),
                ),
                CategorySection(
                    categoryName = "Dairy & Eggs",
                    displayOrder = 1,
                    rows = listOf(row("Milk"), row("Eggs")),
                ),
            ),
        )
        // Sections come out in the order given, with their headers UPPER.
        val produceIdx = text.indexOf("PRODUCE")
        val dairyIdx = text.indexOf("DAIRY & EGGS")
        assertThat(produceIdx).isAtLeast(0)
        assertThat(dairyIdx).isGreaterThan(produceIdx)
        assertThat(text).contains("- Bananas")
        assertThat(text).contains("- Milk")
    }

    @Test fun `brand is rendered in parentheses when present`() {
        val text = buildShareListText(
            storeName = "Lidl",
            sections = listOf(
                CategorySection(
                    categoryName = "Dairy",
                    displayOrder = 0,
                    rows = listOf(row("Milk", brand = "Mimosa"), row("Eggs")),
                ),
            ),
        )
        assertThat(text).contains("- Milk (Mimosa)")
        // No spurious empty parens for items without a brand.
        assertThat(text).contains("- Eggs")
        assertThat(text).doesNotContain("- Eggs ()")
    }

    @Test fun `blank brand string is treated as no brand`() {
        val text = buildShareListText(
            storeName = "Lidl",
            sections = listOf(
                CategorySection(
                    categoryName = "Pantry",
                    displayOrder = 0,
                    rows = listOf(row("Rice", brand = "   ")),
                ),
            ),
        )
        assertThat(text).contains("- Rice")
        assertThat(text).doesNotContain("(   )")
    }
}
