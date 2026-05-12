package com.storehop.app.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Play Billing Library 7.1.1 wrapper, scoped @Singleton. Started once from
 * [com.storehop.app.StorehopApplication.onCreate] via [start]; the rest of
 * the app observes [productDetails] for the upsell price and [purchases]
 * for entitlement state, and triggers purchases through [launchPurchase].
 *
 * ## Connection lifecycle
 *
 * Calling [start] opens a [BillingClient] connection. The connection is
 * sticky — Play recommends keeping it open for the app's lifetime. On
 * disconnect (rare, e.g. Play Store updates itself), the next operation
 * detects `!billingClient.isReady` and reconnects on demand. We log
 * connection failures but don't crash; UI gates can read from the
 * `cached_entitlement` DataStore key as a fallback while Billing
 * reconnects.
 *
 * ## Purchase flow
 *
 * 1. UI calls [launchPurchase] with the current Activity.
 * 2. Play's purchase sheet handles the transaction.
 * 3. [PurchasesUpdatedListener] fires with the result.
 * 4. On success → acknowledge the purchase (required within 3 days or
 *    Google auto-refunds) → re-query purchases → emit
 *    [PurchaseEvent.Purchased].
 * 5. [EntitlementRepository] observes [purchases] + [purchaseEvent] and
 *    flips the user-visible entitlement to [Entitlement.Premium].
 */
@Singleton
class BillingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val applicationScope: CoroutineScope,
) {
    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        handlePurchasesUpdated(result, purchases)
    }

    private val billingClient: BillingClient by lazy {
        BillingClient.newBuilder(context)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .build()
    }

    /** Cached `premium_lifetime` product details once Play returns them.
     *  Null until the first successful product query lands. UI reads
     *  [ProductDetails.OneTimePurchaseOfferDetails.formattedPrice] to
     *  show the Play-localized price (e.g. "$7.99" / "€7,99"). */
    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails.asStateFlow()

    /** Current purchases for [PRODUCT_ID_PREMIUM]. EntitlementRepository
     *  treats a non-empty PURCHASED list as a Premium signal. */
    private val _purchases = MutableStateFlow<List<Purchase>>(emptyList())
    val purchases: StateFlow<List<Purchase>> = _purchases.asStateFlow()

    /** Bus of one-shot purchase outcomes for UI snackbars / toasts. */
    private val _purchaseEvent = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 4)
    val purchaseEvent: SharedFlow<PurchaseEvent> = _purchaseEvent.asSharedFlow()

    /**
     * Idempotent. Opens the billing connection if not already open, then
     * refreshes product details + current purchases. Safe to call from
     * application onCreate and from any user-initiated retry path.
     */
    fun start() {
        applicationScope.launch { connectAndRefresh() }
    }

    private suspend fun connectAndRefresh() {
        if (!billingClient.isReady) {
            val result = startConnectionSuspending()
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "Billing connect failed: ${result.debugMessage}")
                return
            }
            Log.i(TAG, "Billing connection ready")
        }
        refreshProductDetails()
        refreshPurchases()
    }

    private suspend fun startConnectionSuspending(): BillingResult =
        suspendCancellableCoroutine { cont ->
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result)
                }

                override fun onBillingServiceDisconnected() {
                    // Not an error per se — the next operation will reconnect
                    // via `!billingClient.isReady` check. Logging at INFO so
                    // it's visible without being a real alarm.
                    Log.i(TAG, "Billing service disconnected")
                }
            })
        }

    private suspend fun refreshProductDetails() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID_PREMIUM)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        val result = billingClient.queryProductDetails(params)
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            _productDetails.value = result.productDetailsList?.firstOrNull()
        } else {
            Log.w(TAG, "Product query failed: ${result.billingResult.debugMessage}")
        }
    }

    private suspend fun refreshPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val result = billingClient.queryPurchasesAsync(params)
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            _purchases.value = result.purchasesList
        } else {
            Log.w(TAG, "Purchases query failed: ${result.billingResult.debugMessage}")
        }
    }

    /**
     * Launches the Play purchase sheet for the Premium product. No-op if
     * the product hasn't loaded yet (rare — connection establishes within
     * ~500 ms of start()).
     *
     * The user's Activity is required by Play to anchor the sheet's
     * window — passing the application context fails with an opaque
     * error inside Play's UI.
     */
    fun launchPurchase(activity: Activity) {
        val product = _productDetails.value ?: run {
            Log.w(TAG, "launchPurchase: productDetails not loaded yet")
            applicationScope.launch {
                _purchaseEvent.emit(PurchaseEvent.Failed("Product not ready"))
            }
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .build()
                )
            )
            .build()
        val result = billingClient.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "launchBillingFlow failed: ${result.debugMessage}")
            applicationScope.launch {
                _purchaseEvent.emit(PurchaseEvent.Failed(result.debugMessage))
            }
        }
    }

    /**
     * Re-query Play for past purchases. Used by the "Restore purchases"
     * link in Settings for users who bought on another device of the same
     * Google account. Idempotent — running it on a device that already
     * has the purchase is a no-op.
     */
    fun restorePurchases() {
        applicationScope.launch {
            connectAndRefresh()
            _purchaseEvent.emit(PurchaseEvent.Restored)
        }
    }

    private fun handlePurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    applicationScope.launch { processNewPurchase(purchase) }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                applicationScope.launch { _purchaseEvent.emit(PurchaseEvent.UserCancelled) }
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                applicationScope.launch {
                    refreshPurchases()
                    _purchaseEvent.emit(PurchaseEvent.AlreadyOwned)
                }
            }
            else -> {
                applicationScope.launch {
                    _purchaseEvent.emit(PurchaseEvent.Failed(result.debugMessage))
                }
            }
        }
    }

    private suspend fun processNewPurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            // PENDING — user used a payment method that requires async
            // approval (e.g. cash in some markets). We'll see the
            // transition to PURCHASED via a separate
            // PurchasesUpdatedListener callback.
            _purchaseEvent.emit(PurchaseEvent.Pending)
            return
        }
        if (!purchase.isAcknowledged) {
            val ack = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            val ackResult = billingClient.acknowledgePurchase(ack)
            if (ackResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "Ack failed: ${ackResult.debugMessage}; retrying once")
                billingClient.acknowledgePurchase(ack)
            }
        }
        refreshPurchases()
        _purchaseEvent.emit(PurchaseEvent.Purchased)
    }

    companion object {
        /** Stable product ID for the Premium tier. Configured in Play
         *  Console as a one-time in-app product. */
        const val PRODUCT_ID_PREMIUM = "premium_lifetime"
        private const val TAG = "BillingManager"
    }
}

/**
 * One-shot purchase outcomes. UI listens to [BillingManager.purchaseEvent]
 * and surfaces these as snackbars / toasts.
 */
sealed class PurchaseEvent {
    /** Purchase completed + acknowledged. Entitlement flips to Premium. */
    data object Purchased : PurchaseEvent()
    /** Purchase awaiting async approval (cash payment, etc.). */
    data object Pending : PurchaseEvent()
    /** User dismissed the Play purchase sheet. Silent. */
    data object UserCancelled : PurchaseEvent()
    /** User already owns the product — typically means another device's
     *  purchase was just synced. Refresh resolves it. */
    data object AlreadyOwned : PurchaseEvent()
    /** "Restore purchases" tap completed. */
    data object Restored : PurchaseEvent()
    /** Non-cancel failure (network, server error, etc.). */
    data class Failed(val reason: String?) : PurchaseEvent()
}
