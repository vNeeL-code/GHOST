package com.ghost.api.billing

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Patreon supporter flavor implementation: Operator Pass is force unlocked.
 */
internal fun createBillingManager(context: Context): BillingManager {
    return PatreonBillingManager(context)
}

private class PatreonBillingManager(private val context: Context) : BillingManager {
    override val isOperatorPurchased: StateFlow<Boolean> = MutableStateFlow(true)
    override val formattedPrice: StateFlow<String> = MutableStateFlow("Supporter Tier")

    override fun startConnection() {}
    override fun launchPurchaseFlow(activity: Activity) {}
    override fun destroy() {}
}
