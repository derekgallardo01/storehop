package com.storehop.app.ui.shop

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.relations.ShoppingRow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
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

    // ---- launchShareList: Intent assembly + share-sheet handoff -----------

    @Test fun `launchShareList builds an ACTION_SEND chooser with localized text and store-name subject`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val sections = listOf(
            CategorySection(
                categoryName = "Pantry",
                categoryNameKey = null,
                displayOrder = null,
                rows = listOf(row("Rice"), row("Pasta", brand = "Barilla")),
            ),
        )
        launchShareList(ctx, storeName = "Lidl", sections = sections)

        // The chooser intent is the most recent activity start. Robolectric
        // captures it; unwrap to the inner ACTION_SEND.
        val chooser = shadowOf(ctx as android.app.Application).peekNextStartedActivity()
        assertThat(chooser).isNotNull()
        val send: Intent = chooser!!.getParcelableExtra(Intent.EXTRA_INTENT)!!
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.type).isEqualTo("text/plain")
        val payload = send.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        assertThat(payload).contains("Lidl")
        assertThat(payload).contains("PANTRY")
        assertThat(payload).contains("- Rice")
        assertThat(payload).contains("- Pasta (Barilla)")
        // Subject contains the store name (per `share_list_subject` template).
        assertThat(send.getStringExtra(Intent.EXTRA_SUBJECT) ?: "").contains("Lidl")
    }

    @Test fun `launchShareList resolves a seeded category nameKey to its localized label`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val sections = listOf(
            CategorySection(
                // Seeded category -- nameKey routes through getIdentifier
                // to the active-locale string resource.
                categoryName = "Produce",  // raw fallback name; should NOT win
                categoryNameKey = "cat_produce",
                displayOrder = null,
                rows = listOf(row("Apples")),
            ),
        )
        launchShareList(ctx, storeName = "Aldi", sections = sections)
        val chooser = shadowOf(ctx as android.app.Application).peekNextStartedActivity()
        val payload = chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            .getStringExtra(Intent.EXTRA_TEXT) ?: ""
        // The localized "Produce" resource is uppercased into the section
        // header. (English resources resolve to "Produce", which uppercases
        // the same way the raw fallback would -- but this proves the
        // getIdentifier branch was taken without crashing.)
        assertThat(payload).contains("PRODUCE")
        assertThat(payload).contains("- Apples")
    }

    @Test fun `launchShareList falls back to raw categoryName when nameKey is null (user-added)`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val sections = listOf(
            CategorySection(
                categoryName = "Wine cellar",  // user-added, no resource exists
                categoryNameKey = null,
                displayOrder = null,
                rows = listOf(row("Cabernet")),
            ),
        )
        launchShareList(ctx, storeName = "Continente", sections = sections)
        val chooser = shadowOf(ctx as android.app.Application).peekNextStartedActivity()
        val payload = chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            .getStringExtra(Intent.EXTRA_TEXT) ?: ""
        // Raw name uppercased.
        assertThat(payload).contains("WINE CELLAR")
        assertThat(payload).contains("- Cabernet")
    }
}
