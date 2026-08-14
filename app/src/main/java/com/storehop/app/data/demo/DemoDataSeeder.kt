package com.storehop.app.data.demo

import com.storehop.app.data.dao.PurchaseRecordDao
import com.storehop.app.data.entity.PurchaseRecord
import com.storehop.app.data.repository.CategoryRepository
import com.storehop.app.data.repository.ItemRepository
import com.storehop.app.data.repository.StoreCategoryOrderRepository
import com.storehop.app.data.repository.StoreRepository
import com.storehop.app.data.util.HouseholdSessionProvider
import com.storehop.app.data.util.IdGenerator
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.flow.first
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DEBUG / marketing-only helper that fills the app with a curated, realistic
 * dataset so landing-page screenshots and the walkthrough video look complete.
 *
 * A fresh install ships with empty shelves — [com.storehop.app.data.db.DatabaseSeeder]
 * seeds only stores/categories/aisles, never items or purchase history — so
 * this builds everything on top via the public repositories (which keep the
 * xref / store-category-order / ownership invariants correct). Purchase history
 * is the one low-level exception: it's inserted straight through
 * [PurchaseRecordDao] with backdated `purchasedAt` so the Statistics screen
 * shows weeks of trend, because the repository purchase path can only stamp
 * "now".
 *
 * Reused by three consumers: the debug-only Settings loader, the
 * `ScreenshotTourTest`, and the `DemoFlowTest` recorder. It is only ever
 * invoked behind `BuildConfig.DEBUG` (Settings) or from instrumented tests, so
 * R8 strips it from the release build.
 *
 * Items intentionally carry no `imageUrl` — the UI renders its coloured
 * initial-avatar, which keeps the demo free of copyrighted brand imagery.
 */
@Singleton
class DemoDataSeeder @Inject constructor(
    private val storeRepository: StoreRepository,
    private val categoryRepository: CategoryRepository,
    private val itemRepository: ItemRepository,
    private val storeCategoryOrderRepository: StoreCategoryOrderRepository,
    private val purchaseRecordDao: PurchaseRecordDao,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val session: UserSessionProvider,
    private val householdSession: HouseholdSessionProvider,
) {

    /** One curated store. */
    private data class StoreSpec(val key: String, val name: String, val colorArgb: Int, val oneOff: Boolean = false)

    /** One curated item. `stores`/`category` reference the keys below. */
    private data class ItemSpec(
        val name: String,
        val category: String,
        val stores: List<String>,
        val brand: String? = null,
        val staple: Boolean = false,
        val priority: Boolean = false,
        val buyToday: Boolean = false,
        /** Mark purchased "now" so it renders checked-off in the store list. */
        val purchased: Boolean = false,
    )

    private val stores = listOf(
        StoreSpec("continente", "Continente", 0xFF1565C0.toInt()),
        StoreSpec("pingo", "Pingo Doce", 0xFF2E7D32.toInt()),
        StoreSpec("lidl", "Lidl", 0xFFF9A825.toInt()),
        StoreSpec("aldi", "Aldi", 0xFF00838F.toInt()),
        StoreSpec("farmacia", "Farmácia", 0xFFC62828.toInt(), oneOff = true),
    )

    // Ordered — the list order becomes each store's default aisle order.
    private val categories = listOf(
        "Produce", "Bakery", "Dairy", "Meat & Fish",
        "Frozen", "Pantry", "Household", "Toiletries",
    )

    private val items = listOf(
        // Produce
        ItemSpec("Bananas", "Produce", listOf("continente", "pingo")),
        ItemSpec("Baby Spinach", "Produce", listOf("continente"), brand = "Florette"),
        ItemSpec("Avocados", "Produce", listOf("pingo", "lidl")),
        ItemSpec("Tomatoes", "Produce", listOf("continente"), purchased = true),
        // Bakery
        ItemSpec("Sourdough Bread", "Bakery", listOf("continente", "pingo"), staple = true),
        ItemSpec("Croissants", "Bakery", listOf("lidl")),
        // Dairy
        ItemSpec("Whole Milk", "Dairy", listOf("continente", "pingo", "aldi"), brand = "Mimosa", staple = true),
        ItemSpec("Greek Yogurt", "Dairy", listOf("continente"), brand = "Fage"),
        ItemSpec("Butter", "Dairy", listOf("pingo"), staple = true),
        ItemSpec("Eggs", "Dairy", listOf("continente", "aldi"), staple = true, purchased = true),
        // Meat & Fish
        ItemSpec("Chicken Breasts", "Meat & Fish", listOf("continente", "pingo")),
        ItemSpec("Salmon Fillets", "Meat & Fish", listOf("continente")),
        ItemSpec("Ground Beef", "Meat & Fish", listOf("aldi")),
        // Frozen
        ItemSpec("Chicken Wings - Frozen", "Frozen", listOf("lidl", "aldi")),
        ItemSpec("Garden Peas", "Frozen", listOf("lidl")),
        ItemSpec("Ice Cream", "Frozen", listOf("continente"), brand = "Olá"),
        // Pantry
        ItemSpec("Coffee", "Pantry", listOf("continente", "pingo"), brand = "Delta", staple = true),
        ItemSpec("Olive Oil", "Pantry", listOf("continente"), brand = "Gallo"),
        ItemSpec("Spaghetti", "Pantry", listOf("lidl", "aldi"), purchased = true),
        ItemSpec("Basmati Rice", "Pantry", listOf("continente")),
        ItemSpec("Sugar", "Pantry", listOf("pingo", "aldi")),
        // Household
        ItemSpec("Toilet Paper", "Household", listOf("continente", "pingo"), brand = "Renova", buyToday = true),
        ItemSpec("Dish Soap", "Household", listOf("continente"), brand = "Fairy"),
        ItemSpec("Trash Bags", "Household", listOf("lidl")),
        ItemSpec("Laundry Detergent", "Household", listOf("aldi"), brand = "Skip", priority = true),
        // Toiletries
        ItemSpec("Toothpaste", "Toiletries", listOf("continente"), brand = "Colgate"),
        ItemSpec("Shampoo", "Toiletries", listOf("pingo")),
        ItemSpec("Razors", "Toiletries", listOf("continente"), brand = "Gillette"),
        ItemSpec("Ibuprofen", "Toiletries", listOf("farmacia"), brand = "Brufen", priority = true, buyToday = true),
    )

    /**
     * Wipe any existing alive content, then build the curated demo dataset in
     * the current session scope. Safe to run on an empty DB (screenshot tests)
     * or on the seeded debug app (archives the shipped stores/categories first
     * so only the demo set shows).
     */
    suspend fun seed() {
        clear()

        val categoryIds = categories.associateWith { name ->
            categoryRepository.addCategory(name = name, icon = null)
        }
        val storeIds = stores.associate { spec ->
            spec.key to storeRepository.addStore(spec.name, spec.colorArgb, spec.oneOff)
        }

        // Give each store a sensible aisle order (the category list order).
        val orderedCategoryIds = categories.mapNotNull { categoryIds[it] }
        storeIds.values.forEach { storeId ->
            storeCategoryOrderRepository.reorderCategoriesForStore(storeId, orderedCategoryIds)
        }

        val itemIdByName = LinkedHashMap<String, String>()
        for (spec in items) {
            val itemId = itemRepository.addItem(
                name = spec.name,
                categoryId = categoryIds[spec.category],
                storeIds = spec.stores.mapNotNull { storeIds[it] }.toSet(),
                brand = spec.brand,
                isStaple = spec.staple,
                isPriority = spec.priority,
                isBuyToday = spec.buyToday,
            )
            itemIdByName[spec.name] = itemId
            if (spec.purchased) {
                // Renders struck-through in the store list (session-purchased
                // window) and contributes a "now" purchase to the stats.
                val storeId = spec.stores.firstNotNullOfOrNull { storeIds[it] }
                if (storeId != null) itemRepository.markPurchasedAtStore(itemId, storeId)
            }
        }

        seedPurchaseHistory(itemIdByName, storeIds)
    }

    /**
     * Backdated purchase history so Statistics shows weeks of trend, top items,
     * per-store and per-category bars. Deterministic (no RNG) so screenshots are
     * reproducible. Skips silently when there's no active session.
     */
    private suspend fun seedPurchaseHistory(
        itemIdByName: Map<String, String>,
        storeIdByKey: Map<String, String>,
    ) {
        val userId = session.currentUserId() ?: return
        val householdId = householdSession.currentHouseholdId() ?: userId
        val now = clock.millis()
        val day = 24L * 60 * 60 * 1000

        // Items that get bought often (drive "top items" + the trend line),
        // paired with the store they're usually bought at.
        val recurring = listOf(
            "Whole Milk" to "continente",
            "Eggs" to "continente",
            "Sourdough Bread" to "pingo",
            "Bananas" to "continente",
            "Coffee" to "pingo",
            "Butter" to "pingo",
            "Chicken Breasts" to "continente",
            "Greek Yogurt" to "continente",
            "Spaghetti" to "lidl",
            "Ice Cream" to "continente",
        )

        // Spread ~12 weeks of shopping trips with an organic (but deterministic,
        // so screenshots are reproducible) cadence: not every day is a trip, and
        // trip sizes vary — so the "daily activity" chart reads like a real user
        // instead of a regular sawtooth.
        var records = 0
        for (dayAgo in 1..84) {
            val roll = hash(dayAgo)
            // Shop ~4 days in 7; skip the rest.
            if (roll % 7 < 3) continue
            val tripSize = 2 + (hash(dayAgo * 31 + 7) % 6) // 2..7 items this trip
            val chosen = LinkedHashSet<Int>()
            var k = 0
            while (chosen.size < tripSize && k < tripSize * 3) {
                chosen.add(hash(dayAgo * 17 + k) % recurring.size)
                k++
            }
            for ((slot, idx) in chosen.withIndex()) {
                val (itemName, storeKey) = recurring[idx]
                val itemId = itemIdByName[itemName] ?: continue
                val storeId = storeIdByKey[storeKey] ?: continue
                // Stagger within the day so records don't share a timestamp.
                val purchasedAt = now - dayAgo * day + slot * 37L * 60 * 1000
                purchaseRecordDao.insert(
                    PurchaseRecord(
                        id = ids.newId(),
                        itemId = itemId,
                        storeId = storeId,
                        purchasedAt = purchasedAt,
                        userId = userId,
                        createdAt = now,
                        updatedAt = now,
                        deletedAt = null,
                        pendingSync = true,
                        householdId = householdId,
                    ),
                )
                records++
            }
        }
    }

    /** Deterministic non-negative pseudo-random — reproducible history layout. */
    private fun hash(seed: Int): Int {
        var x = seed * 1103515245 + 12345
        x = x xor (x ushr 16)
        x *= -2048144789
        x = x xor (x ushr 13)
        return x and 0x7fffffff
    }

    /**
     * Remove demo (and any other) alive content so a re-seed starts clean:
     * soft-delete every alive item and archive every alive store + category.
     * A fresh install still restores the shipped seed; this is debug-only.
     */
    suspend fun clear() {
        itemRepository.observeAll().first().forEach { itemRepository.softDelete(it.item.id) }
        storeRepository.observeAll(includeArchived = false).first().forEach {
            storeRepository.setArchived(it.id, true)
        }
        categoryRepository.observeAll(includeArchived = false).first().forEach {
            categoryRepository.setArchived(it.id, true)
        }
    }
}
