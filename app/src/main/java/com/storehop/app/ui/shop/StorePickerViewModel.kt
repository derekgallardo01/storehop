package com.storehop.app.ui.shop

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storehop.app.R
import com.storehop.app.data.repository.ShoppingRepository
import com.storehop.app.data.repository.StorePickerRow
import com.storehop.app.data.repository.StoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Snapshot of the critical-needs banner's state. Composed by the VM and
 * consumed by the banner composable. `byStore` lists only stores that have at
 * least one critical item, in displayOrder.
 */
data class CriticalBannerState(
    val totalCount: Int,
    val topStoreName: String,
    val topStoreCount: Int,
    val singleStore: Boolean,
    val byStore: List<Pair<String, List<String>>>,
)

/**
 * Snapshot of the "Buy Today!" banner state. Same shape as
 * [CriticalBannerState] but sourced from the transient buy-today flag and,
 * unlike Critical, it does NOT exclude one-off stores — urgency applies to any
 * kind of trip. `byStore` lists only stores with at least one buy-today item,
 * in displayOrder.
 */
data class BuyTodayBannerState(
    val totalCount: Int,
    val topStoreName: String,
    val topStoreCount: Int,
    val singleStore: Boolean,
    val byStore: List<Pair<String, List<String>>>,
)

@HiltViewModel
class StorePickerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val shoppingRepository: ShoppingRepository,
    private val storeRepository: StoreRepository,
    sessionTracker: ShoppingSessionTracker,
) : ViewModel() {

    /**
     * The picker anchors the trip on first view -- if the user opens the app
     * and stares at the picker before tapping into a store, that's the start
     * of their trip. Subsequent purchases (within the process) are gated
     * against this same anchor so a non-staple bought at Lidl still surfaces
     * an "✓ All set" badge on Aldi where it was also tagged.
     */
    private val sessionStartMs: Long = sessionTracker.sessionStartMs()

    val rows: StateFlow<List<StorePickerRow>> = shoppingRepository
        .observeStorePickerRows(sessionStartMs)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * Routing-aware summary of priority+needed items across stores. Null when
     * nothing critical is needed (banner hidden). When present, names the
     * single store that covers the most criticals so the user knows where to
     * shop first; ties resolve to whichever store comes earlier in the user's
     * manual drag order, since `rows` arrives sorted by displayOrder and
     * maxByOrNull picks the first match on equal counts. The full per-store
     * breakdown is carried for the banner's tap-to-expand detail view.
     */
    val criticalSummary: StateFlow<CriticalBannerState?> = rows
        .map { all ->
            // v0.9: skip one-off stores in the critical-needs banner.
            // The banner is for "I might forget this on a grocery run";
            // one-off purchases (couch, drying rack) aren't grocery runs.
            val withCriticals = all.filter {
                !it.store.isOneOff && it.criticalItemNames.isNotEmpty()
            }
            if (withCriticals.isEmpty()) return@map null
            val total = withCriticals.flatMap { it.criticalItemNames }.distinct().size
            val top = withCriticals.maxByOrNull { it.criticalItemNames.size }!!
            CriticalBannerState(
                totalCount = total,
                topStoreName = top.store.name,
                topStoreCount = top.criticalItemNames.size,
                singleStore = withCriticals.size == 1,
                byStore = withCriticals.map { it.store.name to it.criticalItemNames },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /**
     * v0.9 "Buy Today!" summary across stores. Null when nothing is flagged
     * (banner hidden). Same routing hint as [criticalSummary] — names the
     * store covering the most buy-today items so the user knows where to head
     * first — but one-off stores ARE included here (a "Buy today" dog bed at a
     * one-off pet store is still due today). Answers "what must I get today,
     * and where?" independently of the Critical-count ranking.
     */
    val buyTodaySummary: StateFlow<BuyTodayBannerState?> = rows
        .map { all ->
            val withBuyToday = all.filter { it.buyTodayItemNames.isNotEmpty() }
            if (withBuyToday.isEmpty()) return@map null
            val total = withBuyToday.flatMap { it.buyTodayItemNames }.distinct().size
            val top = withBuyToday.maxByOrNull { it.buyTodayItemNames.size }!!
            BuyTodayBannerState(
                totalCount = total,
                topStoreName = top.store.name,
                topStoreCount = top.buyTodayItemNames.size,
                singleStore = withBuyToday.size == 1,
                byStore = withBuyToday.map { it.store.name to it.buyTodayItemNames },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /**
     * Persist the new picker order. Called once when the user releases a
     * drag, with the full top-to-bottom list of store ids as currently
     * laid out on screen. Repository wraps the rewrite in a transaction.
     */
    fun commitOrder(orderedIds: List<String>) {
        viewModelScope.launch { storeRepository.reorderStores(orderedIds) }
    }

    /**
     * Add a new store. Returns null on success, or an error message the
     * dialog can show inline (empty name, duplicate name -- the repo throws
     * IllegalArgumentException for both, with a user-readable message).
     * Successful adds append to the bottom of the picker via the repo's
     * `nextDisplayOrder` allocation; the user can drag from there.
     */
    suspend fun addStore(name: String, isOneOff: Boolean = false): String? {
        val trimmed = name.trim()
        // Validate locally before the repo so we can return localized strings
        // (the repo throws English messages for log diagnostics; we map
        // post-throw errors to the duplicate-name path since that's the only
        // IllegalArgumentException the empty-pre-check leaves on the table).
        if (trimmed.isEmpty()) return appContext.getString(R.string.error_store_name_empty)
        return try {
            storeRepository.addStore(name = trimmed, isOneOff = isOneOff)
            null
        } catch (e: IllegalArgumentException) {
            appContext.getString(R.string.error_store_name_duplicate, trimmed)
        } catch (e: Exception) {
            appContext.getString(R.string.error_could_not_add_store)
        }
    }

    /**
     * v0.9: toggle a store's one-off flag from the row's overflow menu or
     * an edit sheet. Idempotent; the repo skips the write if the value is
     * unchanged.
     */
    fun setStoreOneOff(id: String, isOneOff: Boolean) {
        viewModelScope.launch {
            storeRepository.setOneOff(id, isOneOff)
        }
    }

    /**
     * Rename an existing store. Returns null on success or a localized error
     * string the rename dialog renders inline.
     */
    suspend fun renameStore(id: String, name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return appContext.getString(R.string.error_store_name_empty)
        return try {
            storeRepository.rename(id, trimmed)
            null
        } catch (e: IllegalArgumentException) {
            appContext.getString(R.string.error_store_name_duplicate, trimmed)
        } catch (e: Exception) {
            appContext.getString(R.string.error_could_not_rename_store)
        }
    }

    /**
     * Soft-delete a store. The repo cascade-tombstones every xref + SCO row
     * pointing at it -- items previously tagged here lose this store but
     * stay in the user's master list. Fire-and-forget; the upstream Flow
     * will drop the row from the picker on the next emission.
     */
    fun deleteStore(id: String) {
        viewModelScope.launch { storeRepository.softDelete(id) }
    }

    /**
     * Reverse a recent [deleteStore]. Restores the store row plus every
     * cascade-tombstoned xref + SCO row tagged to that exact deletion
     * timestamp -- so all the items that used to be at this store come
     * back tagged to it.
     */
    fun undoDeleteStore(id: String) {
        viewModelScope.launch { storeRepository.undoSoftDelete(id) }
    }
}
