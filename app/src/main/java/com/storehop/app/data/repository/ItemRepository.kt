package com.storehop.app.data.repository

import com.storehop.app.data.db.relations.ItemWithCategoryAndStores
import kotlinx.coroutines.flow.Flow

interface ItemRepository {
    fun observeAll(): Flow<List<ItemWithCategoryAndStores>>
    fun observeById(id: String): Flow<ItemWithCategoryAndStores?>

    suspend fun addItem(
        name: String,
        categoryId: String?,
        storeIds: Set<String>,
        quantity: String? = null,
        notes: String? = null,
        isNeeded: Boolean = true,
        brand: String? = null,
        imageUrl: String? = null,
        isStaple: Boolean = false,
        isPriority: Boolean = false,
    ): String

    suspend fun updateItem(
        id: String,
        name: String,
        categoryId: String?,
        storeIds: Set<String>,
        quantity: String?,
        notes: String?,
        brand: String? = null,
        imageUrl: String? = null,
        isStaple: Boolean = false,
        isPriority: Boolean = false,
    )

    suspend fun softDelete(id: String)

    /**
     * Undo a previous [softDelete]. Reads the row's `deletedAt` and restores
     * the item plus every xref + PurchaseRecord row that was tombstoned at
     * that same instant. No-op when the row isn't currently tombstoned.
     */
    suspend fun undoSoftDelete(id: String)

    /**
     * Mark this item purchased. The user is at one specific [storeId], but
     * one purchase satisfies the need across every store the item is tagged
     * to: buying mozzarella at Lidl makes it disappear from the Aldi and
     * Pingo Doce lists too. Writes exactly one
     * [com.storehop.app.data.entity.PurchaseRecord] (the actual store of the
     * purchase) and returns the snapshot timestamp the caller should pass to
     * [undoPurchase] for a precise reversal. Returns null if the item isn't
     * owned by the live session.
     */
    suspend fun markPurchasedAtStore(itemId: String, storeId: String): Long?

    /**
     * Reverse the most recent [markPurchasedAtStore] for [itemId] at exactly
     * [snapshotTime] (the value [markPurchasedAtStore] returned). Restores
     * every xref the cascade flipped — those whose `lastPurchasedAt` matches
     * [snapshotTime] — back to needed, and soft-deletes the matching
     * PurchaseRecord. After undo, history shows no purchase: this is a true
     * "as if it never happened" rollback, not a state correction.
     */
    suspend fun undoPurchase(itemId: String, snapshotTime: Long)

    /**
     * Restore the (item, store) row to needed at that store. Used by
     * Shop-at-Store to manually un-check a single store after the snackbar
     * timeout — the cascade-undo above is the primary mis-tap path. No
     * PurchaseRecord is touched; this is a per-store state correction.
     */
    suspend fun markNeededAtStore(itemId: String, storeId: String)

    /**
     * Idempotently mark an existing item as needed at the given store.
     * Creates the xref if missing, upserts a live row when one exists (incl.
     * restoring from a tombstone), and flips `isNeeded` to true if it was
     * false. Used by the Shop-at-Store autocomplete: when the user picks an
     * existing item from the suggestion list, this is the action that
     * actually tags the item to the store. Also ensures the per-store
     * StoreCategoryOrder row exists so the category sorts into aisle order.
     */
    suspend fun tagItemToStore(itemId: String, storeId: String)

    /**
     * Find-or-create entry point used by the Shop-at-Store QuickAdd bar.
     * Trims input, then case-insensitive name-match against the user's
     * master library:
     *   - hit  → [tagItemToStore] for the existing id; returns that id.
     *   - miss → [addItem] with `storeIds = {storeId}`; returns the new id.
     *
     * Fixes the v0.5.6 bug where typing a name in the QuickAdd bar that
     * already existed created a duplicate Item in the master library
     * (uncategorized). [addItem]'s "always creates" semantics are preserved
     * for non-QuickAdd callers (ItemFormScreen, CSV import); the dedupe
     * lives only here.
     */
    suspend fun addItemFromQuickAdd(name: String, storeId: String): String

    /**
     * Distinct item IDs that have at least one alive xref with
     * `isNeeded = 1`. Powers the v0.6.1 Items-list +/− toggle: the screen
     * shows "−" when this set contains the item, "+" otherwise.
     */
    fun observeNeededItemIds(): Flow<Set<String>>

    /**
     * Mark this item needed at every store it's tagged to. The +/− toggle
     * on the Items list calls this on "+". The cross-store cascade design
     * (one trip clears the list everywhere) means the inverse "−" path
     * uses [markPurchasedAcrossAllStores] without writing a PurchaseRecord.
     */
    suspend fun markNeededAcrossAllStores(itemId: String)

    /**
     * Mark this item not-needed at every store it's tagged to. Pure list-
     * state action; does NOT write a PurchaseRecord (the user isn't at any
     * specific store -- they're on the master Items list).
     */
    suspend fun markPurchasedAcrossAllStores(itemId: String)

    /**
     * Idempotently tag a batch of items to a batch of stores in one
     * transaction. Add-only semantics: each (itemId, storeId) pair is
     * either already tagged (no-op + flips isNeeded back to true if it
     * was false) or gets a fresh alive xref (resurrecting a tombstone
     * by primary-key upsert when one is present). Stores not in
     * [storeIdsToAdd] are not touched — this is the bulk equivalent of
     * toggling more chips "on," not a replace-all.
     *
     * Powers the v0.8.1 bulk-tag UI from the Items list: long-press to
     * enter selection mode, pick N items, choose stores to apply. The
     * transaction wraps the whole batch so a partial failure can't
     * leave half the items tagged.
     *
     * No-op if either set is empty. Items not owned by the live
     * household are silently skipped (inherited from
     * [tagItemToStore]'s guard).
     */
    suspend fun bulkTagStoresForItems(itemIds: Set<String>, storeIdsToAdd: Set<String>)
}
