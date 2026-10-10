package com.ghost.api.billing

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Common billing contract for unlocking Operator Pass features.
 * Implementation is flavor-gated:
 * - Play Store (`play`): Google Play Billing Client 7.x
 * - F-Droid (`fdroid`): Pure FOSS No-Op stub
 * - Patreon (`patreon`): Fully unlocked No-Op stub
 */
interface BillingManager {
    val isOperatorPurchased: StateFlow<Boolean>
    val formattedPrice: StateFlow<String>

    fun startConnection()
    fun launchPurchaseFlow(activity: Activity)
    fun destroy()

    companion object {
        const val PRODUCT_ID_OPERATOR_PASS = "ghost_operator_pass"

        fun create(context: Context): BillingManager = createBillingManager(context.applicationContext)
    }
}
