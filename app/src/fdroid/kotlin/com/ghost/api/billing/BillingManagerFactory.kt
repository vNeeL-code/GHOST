package com.ghost.api.billing

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * F-Droid implementation: 100% free of proprietary Google Play Billing binaries.
 */
internal fun createBillingManager(context: Context): BillingManager {
    return FdroidBillingManager(context)
}

private class FdroidBillingManager(private val context: Context) : BillingManager {
    override val isOperatorPurchased: StateFlow<Boolean> = MutableStateFlow(true)
    override val formattedPrice: StateFlow<String> = MutableStateFlow("Free / FOSS")

    override fun startConnection() {}
    override fun launchPurchaseFlow(activity: Activity) {}
    override fun destroy() {}
}
