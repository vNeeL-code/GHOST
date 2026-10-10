package com.ghost.api.billing

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.android.billingclient.api.*
import com.ghost.api.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Play Store flavor implementation: Google Play Billing Client 7.x
 * Connects directly to Google Play In-App Billing for the Operator Pass one-time purchase.
 */
internal fun createBillingManager(context: Context): BillingManager {
    return PlayBillingManager(context)
}

private class PlayBillingManager(private val context: Context) : BillingManager, PurchasesUpdatedListener {

    private val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    private val _isOperatorPurchased = MutableStateFlow(Constants.isOperatorTier(prefs))
    override val isOperatorPurchased: StateFlow<Boolean> = _isOperatorPurchased.asStateFlow()

    private val _formattedPrice = MutableStateFlow("£3.50")
    override val formattedPrice: StateFlow<String> = _formattedPrice.asStateFlow()

    private var productDetails: ProductDetails? = null
    private var isConnecting = false

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    override fun startConnection() {
        if (billingClient.isReady || isConnecting) return
        isConnecting = true

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                isConnecting = false
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Timber.i("Google Play Billing client connected successfully")
                    queryProductDetails()
                    queryActivePurchases()
                } else {
                    Timber.w("Billing setup failed: code=${billingResult.responseCode}, msg=${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                isConnecting = false
                Timber.w("Billing service disconnected — will reconnect on next purchase attempt")
            }
        })
    }

    private fun queryProductDetails() {
        if (!billingClient.isReady) return

        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(BillingManager.PRODUCT_ID_OPERATOR_PASS)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val details = productDetailsList.firstOrNull { it.productId == BillingManager.PRODUCT_ID_OPERATOR_PASS }
                if (details != null) {
                    productDetails = details
                    val price = details.oneTimePurchaseOfferDetails?.formattedPrice
                    if (!price.isNullOrEmpty()) {
                        _formattedPrice.value = price
                        Timber.i("Operator Pass localized price resolved: $price")
                    }
                } else {
                    Timber.w("Operator Pass product ${BillingManager.PRODUCT_ID_OPERATOR_PASS} not found in Play Console catalog")
                }
            } else {
                Timber.w("queryProductDetails failed: ${billingResult.debugMessage}")
            }
        }
    }

    private fun queryActivePurchases() {
        if (!billingClient.isReady) return

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                handlePurchases(purchases)
            } else {
                Timber.w("queryPurchasesAsync failed: ${billingResult.debugMessage}")
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases != null) {
                    handlePurchases(purchases)
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Timber.i("User canceled billing flow")
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Timber.i("Item already owned — unlocking Operator Pass")
                unlockOperatorTier()
                Toast.makeText(context, "Operator Pass already owned! Unlocked.", Toast.LENGTH_SHORT).show()
            }
            else -> {
                Timber.w("Billing flow returned error: code=${billingResult.responseCode}, msg=${billingResult.debugMessage}")
                Toast.makeText(context, "Purchase failed: ${billingResult.debugMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handlePurchases(purchases: List<Purchase>) {
        for (purchase in purchases) {
            if (purchase.products.contains(BillingManager.PRODUCT_ID_OPERATOR_PASS) &&
                purchase.purchaseState == Purchase.PurchaseState.PURCHASED
            ) {
                if (!purchase.isAcknowledged) {
                    val ackParams = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                    billingClient.acknowledgePurchase(ackParams) { ackResult ->
                        if (ackResult.responseCode == BillingClient.BillingResponseCode.OK) {
                            Timber.i("Purchase acknowledged successfully")
                            unlockOperatorTier()
                        } else {
                            Timber.w("Failed to acknowledge purchase: ${ackResult.debugMessage}")
                        }
                    }
                } else {
                    unlockOperatorTier()
                }
            }
        }
    }

    private fun unlockOperatorTier() {
        prefs.edit().putBoolean(Constants.PREF_IS_OPERATOR_TIER, true).apply()
        _isOperatorPurchased.value = true
    }

    override fun launchPurchaseFlow(activity: Activity) {
        if (!billingClient.isReady) {
            Toast.makeText(activity, "Connecting to Google Play... Please try again.", Toast.LENGTH_SHORT).show()
            startConnection()
            return
        }

        val details = productDetails
        if (details == null) {
            Toast.makeText(activity, "Loading store catalog... Please try again in a moment.", Toast.LENGTH_SHORT).show()
            queryProductDetails()
            return
        }

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .build()
        )

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Timber.w("launchBillingFlow failed: code=${result.responseCode}, msg=${result.debugMessage}")
            Toast.makeText(activity, "Error launching store: ${result.debugMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun destroy() {
        if (billingClient.isReady) {
            billingClient.endConnection()
        }
    }
}
