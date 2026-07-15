package com.storehop.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.storehop.app.data.entity.ItemStoreXref
import kotlinx.coroutines.flow.Flow

/**
 * v0.7.0 access scope: queries filter by `householdId` (not `userId`).
 *
 * `userId` remains on each xref as creator/audit metadata — copied from
 * the parent item at insert time so cross-table ownership stays coherent
 * across session changes. `householdId` is what scopes who can see and
 * mutate the row. For single-member households both columns hold the
 * same value, so behaviour matches v0.6.x exactly.
 *
 * [setStoresForItem] takes both: `householdId` to scope the existing-row
 * lookup and to stamp on new rows, plus `userId` to carry the parent's
 * creator stamp through onto the freshly-inserted junction rows.
 */
@Dao
interface ItemStoreXrefDao {

    @Query(
        """
        SELECT storeId FROM item_store_xref
        WHERE itemId = :itemId AND deletedAt IS NULL
        """,
    )
    fun observeStoreIdsForItem(itemId: String): Flow<List<String>>

    @Query(
        """
        SELECT * FROM item_store_xref
        WHERE itemId = :itemId AND deletedAt IS NULL
        """,
    )
    suspend fun findForItem(itemId: String): List<ItemStoreXref>

    @Upsert
    suspend fun upsert(xref: ItemStoreXref)

    /**
     * Batch upsert for the pull side. Mappers stamp `pendingSync = false`.
     * Called inside [PullWriteDao]'s single transaction.
     */
    @Upsert
    suspend fun upsertFromCloud(rows: List<ItemStoreXref>)

    @Query(
        """
        UPDATE item_store_xref
        SET deletedAt = :now, updatedAt = :now, pendingSync = 1
        WHERE itemId = :itemId AND storeId = :storeId AND householdId = :householdId
        """,
    )
    suspend fun softDelete(householdId: String, itemId: String, storeId: String, now: Long)

    /**
     * Cascade-tombstone every live xref for an item. Used by the item soft-delete
     * flow so a deleted item doesn't leave xref orphans (the cross-store-sync
     * `ShoppingDao` query INNER JOINs through xrefs, so live xrefs against a
     * deleted item would surface ghost rows). Scoped by `householdId`.
     */
    @Query(
        """
        UPDATE item_store_xref
        SET deletedAt = :now, updatedAt = :now, pendingSync = 1
        WHERE itemId = :itemId AND householdId = :householdId AND deletedAt IS NULL
        """,
    )
    suspend fun softDeleteForItem(householdId: String, itemId: String, now: Long)

    /**
     * Cascade-tombstone every live xref pointing at a store. Used by the store
     * soft-delete flow so items previously tagged to the deleted store don't
     * still appear "sold at <tombstoned store>" in any relation that joins
     * items to stores through this junction.
     */
    @Query(
        """
        UPDATE item_store_xref
        SET deletedAt = :now, updatedAt = :now, pendingSync = 1
        WHERE storeId = :storeId AND householdId = :householdId AND deletedAt IS NULL
        """,
    )
    suspend fun softDeleteForStore(householdId: String, storeId: String, now: Long)

    /**
     * Restore the cascade-tombstoned xrefs for a store. Filters by the exact
     * `deletedAt` used during the cascade so a later, separate tombstoning of
     * a different row at the same time doesn't accidentally come back too.
     */
    @Query(
        """
        UPDATE item_store_xref
        SET deletedAt = NULL, updatedAt = :now, pendingSync = 1
        WHERE storeId = :storeId AND householdId = :householdId AND deletedAt = :deletedAt
        """,
    )
    suspend fun restoreCascadeForStore(householdId: String, storeId: String, deletedAt: Long, now: Long)

    /** Inverse of [softDeleteForItem]; same `deletedAt`-filter pattern. */
    @Query(
        """
        UPDATE item_store_xref
        SET deletedAt = NULL, updatedAt = :now, pendingSync = 1
        WHERE itemId = :itemId AND householdId = :householdId AND deletedAt = :deletedAt
        """,
    )
    suspend fun restoreCascadeForItem(householdId: String, itemId: String, deletedAt: Long, now: Long)

    /**
     * Replace the set of stores an item is tagged to.
     * Tombstones any xref no longer in [storeIds] and upserts the new set.
     * `householdId` scopes who owns the row (the parent item's householdId);
     * `userId` is the parent's creator-stamp copied onto the new junction rows.
     *
     * v0.9: retained stores (already alive AND still selected) used to be left
     * completely untouched, so a store stranded at `isNeeded = 0` by a prior
     * cross-store purchase cascade stayed invisible on that store's list even
     * after the user re-saved the item with the store still ticked — the
     * "Aldi is selected but the item never appears" bug Mike reported. We now
     * converge `isNeeded` across the final store set to the item's current
     * global needed state: if the item is needed at ANY alive store it's
     * needed at all of them; if it's fully purchased everywhere we leave it
     * purchased (so editing a bought item's name doesn't resurrect it onto
     * every list).
     */
    @Transaction
    suspend fun setStoresForItem(
        itemId: String,
        storeIds: Set<String>,
        householdId: String,
        userId: String,
        now: Long,
    ) {
        val existing = findForItem(itemId)
        val existingIds = existing.map { it.storeId }.toSet()
        val toRemove = existingIds - storeIds
        val toAdd = storeIds - existingIds
        // Global needed state, evaluated from the alive xrefs BEFORE we mutate
        // anything. A newly-created item (no existing xrefs) counts as needed
        // so its first stores land on the list, matching the old
        // upsert-with-isNeeded=true default.
        val neededGlobally = existing.isEmpty() || existing.any { it.isNeeded }
        toRemove.forEach { softDelete(householdId, itemId, it, now) }
        toAdd.forEach { storeId ->
            upsert(
                ItemStoreXref(
                    itemId = itemId,
                    storeId = storeId,
                    userId = userId,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                    isNeeded = neededGlobally,
                    householdId = householdId,
                ),
            )
        }
        // Converge retained stores that are stranded at isNeeded = 0 while the
        // item is needed globally. Only touch the stranded ones so we don't
        // needlessly re-dirty already-correct rows for sync.
        if (neededGlobally) {
            existing.asSequence()
                .filter { it.storeId in storeIds && !it.isNeeded }
                .forEach { markNeededAtStore(householdId, itemId, it.storeId, now) }
        }
    }

    @androidx.room.Query("SELECT * FROM item_store_xref WHERE householdId = :householdId AND pendingSync = 1")
    fun observePendingPush(householdId: String): kotlinx.coroutines.flow.Flow<List<ItemStoreXref>>

    /** v0.7.1: row-count of pending pushes for the Force-sync-now UX. */
    @androidx.room.Query("SELECT COUNT(*) FROM item_store_xref WHERE householdId = :householdId AND pendingSync = 1")
    fun countPendingPush(householdId: String): kotlinx.coroutines.flow.Flow<Int>

    /**
     * v0.8.0.4: composite-PK snapshot of pendingSync = 1 xrefs in the
     * household. Read by [PullWriteDao] to filter cloud xrefs that
     * would otherwise resurrect local edits.
     */
    @androidx.room.Query(
        "SELECT itemId, storeId FROM item_store_xref WHERE householdId = :householdId AND pendingSync = 1"
    )
    suspend fun pendingPushKeys(householdId: String): List<XrefKey>

    @androidx.room.Query(
        """
        UPDATE item_store_xref SET pendingSync = 0
        WHERE itemId = :itemId AND storeId = :storeId AND householdId = :householdId
        """,
    )
    suspend fun markPushed(householdId: String, itemId: String, storeId: String)

    /**
     * Mark this single (itemId, storeId) xref purchased at this store only.
     * Used by [markNeededAtStore]'s mirror — the per-store state-correction
     * path. The Shop-at-Store check-off cascades via
     * [markPurchasedAcrossAllStores] instead, which writes the same flips to
     * every store the item is tagged to.
     */
    @Query(
        """
        UPDATE item_store_xref
        SET isNeeded = 0,
            lastPurchasedAt = :now,
            updatedAt = :now,
            pendingSync = 1
        WHERE itemId = :itemId AND storeId = :storeId AND householdId = :householdId
        """,
    )
    suspend fun markPurchasedAtStore(householdId: String, itemId: String, storeId: String, now: Long)

    /**
     * Cascade-mark every live xref for [itemId] as purchased: a single
     * shopping trip (one trip to one store) satisfies the need across every
     * store the item is tagged to. Used by the Shop-at-Store check-off so
     * that buying mozzarella at Lidl makes it disappear from the Aldi and
     * Pingo Doce lists too, matching how shopping-list apps usually behave.
     *
     * All affected rows share the same `lastPurchasedAt = :now`, which is
     * the precision marker [restorePurchaseAcrossAllStores] uses to undo a
     * cascade without touching unrelated history.
     */
    @Query(
        """
        UPDATE item_store_xref
        SET isNeeded = 0,
            lastPurchasedAt = :now,
            updatedAt = :now,
            pendingSync = 1
        WHERE itemId = :itemId AND householdId = :householdId AND deletedAt IS NULL
        """,
    )
    suspend fun markPurchasedAcrossAllStores(householdId: String, itemId: String, now: Long)

    /**
     * Restore the (itemId, storeId) row to needed at this store. Used to
     * undo a mis-tap in the Shop-at-Store screen. lastPurchasedAt is left
     * intact -- the prior purchase still happened in history.
     */
    @Query(
        """
        UPDATE item_store_xref
        SET isNeeded = 1,
            updatedAt = :now,
            pendingSync = 1
        WHERE itemId = :itemId AND storeId = :storeId AND householdId = :householdId
        """,
    )
    suspend fun markNeededAtStore(householdId: String, itemId: String, storeId: String, now: Long)

    /**
     * Inverse of [markPurchasedAcrossAllStores], filtered by exact
     * `lastPurchasedAt` so concurrent or older purchases of the same item
     * aren't accidentally restored. Restores the xrefs to `isNeeded = 1`
     * and clears `lastPurchasedAt`, since the snackbar-undo flow also
     * deletes the matching `PurchaseRecord` — it's "as if it never
     * happened," not a state correction.
     */
    @Query(
        """
        UPDATE item_store_xref
        SET isNeeded = 1,
            lastPurchasedAt = NULL,
            updatedAt = :now,
            pendingSync = 1
        WHERE itemId = :itemId AND householdId = :householdId
            AND lastPurchasedAt = :lastPurchasedAt
            AND isNeeded = 0
        """,
    )
    suspend fun restorePurchaseAcrossAllStores(
        householdId: String,
        itemId: String,
        lastPurchasedAt: Long,
        now: Long,
    )

    /**
     * Set every alive xref for [itemId] to needed. Used by the v0.6.1
     * Items-list "+" tap: when the user adds an item from the master list,
     * we mark it needed at every store it's tagged to. Cross-store cascade
     * (one trip clears the list everywhere) means the inverse "−" tap can
     * use the existing [markPurchasedAcrossAllStores] without writing a
     * PurchaseRecord — this is a list-state action, not a purchase event.
     */
    @Query(
        """
        UPDATE item_store_xref
        SET isNeeded = 1,
            lastPurchasedAt = NULL,
            updatedAt = :now,
            pendingSync = 1
        WHERE itemId = :itemId AND householdId = :householdId AND deletedAt IS NULL
        """,
    )
    suspend fun markNeededAcrossAllStores(householdId: String, itemId: String, now: Long)

    /**
     * Distinct item IDs that have at least one alive xref with `isNeeded = 1`
     * for the given household. Powers the v0.6.1 +/− toggle on the Items
     * list: the screen shows "−" when the item is on the list at any
     * tagged store, "+" otherwise.
     */
    @Query(
        """
        SELECT DISTINCT itemId FROM item_store_xref
        WHERE householdId = :householdId AND deletedAt IS NULL AND isNeeded = 1
        """,
    )
    fun observeNeededItemIds(householdId: String): Flow<List<String>>

    /**
     * v0.9 repair pass (Force Sync now): converge every item to global-needed.
     * For any item that is needed at ≥1 alive store, flip every OTHER alive
     * store for that item to needed too. Heals rows stranded at `isNeeded = 0`
     * by the pre-v0.9 asymmetric un-check (which only restored the store you
     * were standing in). Idempotent — items needed nowhere are left purchased,
     * and already-converged items match nothing. Flipped rows are flagged
     * `pendingSync = 1` so the same Force-Sync flush pushes them up.
     *
     * Returns the number of rows repaired.
     */
    @Query(
        """
        UPDATE item_store_xref
        SET isNeeded = 1, updatedAt = :now, pendingSync = 1
        WHERE householdId = :householdId
          AND deletedAt IS NULL
          AND isNeeded = 0
          AND itemId IN (
            SELECT itemId FROM item_store_xref
            WHERE householdId = :householdId AND deletedAt IS NULL AND isNeeded = 1
          )
        """,
    )
    suspend fun repairStrandedNeededLinks(householdId: String, now: Long): Int
}

/**
 * v0.8.0.4: composite primary-key tuple for [ItemStoreXrefDao.pendingPushKeys].
 * Used by [PullWriteDao] to filter cloud xrefs against a Set of local
 * pending keys before upsert — preserves the user's most recent intent
 * when a pull races a push.
 */
data class XrefKey(val itemId: String, val storeId: String)
