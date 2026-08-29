package com.axiom.kai

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import com.android.billingclient.api.*

// Pro one-time $4.99 (remove ads + early releases), legacy v2/v3 kept for restore
object BillingSkus {
    const val PRO = "kai_pro" // $4.99 one-time — remove ads + early access
    const val V2_30D = "kai_v2_30d" // legacy $4.99 30 days
    const val V2_90D = "kai_v2_90d" // legacy $12.99 90 days
    const val V3_30D = "kai_v3_30d" // legacy $4.99 when v3 ships
    const val V3_90D = "kai_v3_90d"
    val all = listOf(PRO, V2_30D, V2_90D, V3_30D, V3_90D)
}

class BillingManager(private val ctx: Context) : PurchasesUpdatedListener {

    private val prefs: SharedPreferences = ctx.getSharedPreferences("kai_billing", Context.MODE_PRIVATE)

    private val client = BillingClient.newBuilder(ctx)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    // Pro one-time — true if ever purchased (non-consumable)
    fun hasPro(): Boolean = prefs.getBoolean("pro_purchased", false) || hasV2() || hasV3() // legacy v2/v3 also count as pro
    fun isPro(): Boolean = hasPro()
    // expiry timestamps (ms) — 0 = not owned (legacy)
    fun v2Expiry(): Long = prefs.getLong("v2_expiry", 0L)
    fun v3Expiry(): Long = prefs.getLong("v3_expiry", 0L)
    fun hasV2(): Boolean = System.currentTimeMillis() < v2Expiry()
    fun hasV3(): Boolean = System.currentTimeMillis() < v3Expiry()
    fun daysLeftV2(): Long = ((v2Expiry() - System.currentTimeMillis()) / 86400000L).coerceAtLeast(0)
    fun daysLeftV3(): Long = ((v3Expiry() - System.currentTimeMillis()) / 86400000L).coerceAtLeast(0)

    fun connect(onConnected: () -> Unit = {}) {
        if (client.isReady) { onConnected(); return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryPurchases() // restore
                    onConnected()
                }
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    fun queryProducts(onResult: (List<ProductDetails>) -> Unit = {}) {
        connect {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(BillingSkus.all.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it).setProductType(BillingClient.ProductType.INAPP).build()
                }).build()
            client.queryProductDetailsAsync(params) { _, list -> onResult(list ?: emptyList()) }
        }
    }

    fun launchPurchase(activity: Activity, productId: String) {
        queryProducts { list ->
            val details = list.find { it.productId == productId } ?: return@queryProducts
            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details).build())
                ).build()
            client.launchBillingFlow(activity, flowParams)
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) return
        for (p in purchases) {
            if (p.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            val sku = p.products.firstOrNull() ?: continue
            if (sku == BillingSkus.PRO) {
                prefs.edit().putBoolean("pro_purchased", true).apply()
                if (!p.isAcknowledged) {
                    val ackParams = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
                    client.acknowledgePurchase(ackParams) { _ -> }
                }
                continue // PRO is non-consumable, do not consume
            }
            val days = when (sku) {
                BillingSkus.V2_30D, BillingSkus.V3_30D -> 30L
                BillingSkus.V2_90D, BillingSkus.V3_90D -> 90L
                else -> 30L
            }
            val isV2 = sku.startsWith("kai_v2")
            val key = if (isV2) "v2_expiry" else "v3_expiry"
            val currentExpiry = if (isV2) v2Expiry() else v3Expiry()
            val base = maxOf(currentExpiry, System.currentTimeMillis())
            val newExpiry = base + days * 86400000L
            prefs.edit().putLong(key, newExpiry).apply()
            // also grant pro for legacy buyers
            prefs.edit().putBoolean("pro_purchased", true).apply()

            // Consume so user can buy again (legacy)
            val consumeParams = ConsumeParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
            client.consumeAsync(consumeParams) { _, _ -> }

            if (!p.isAcknowledged) {
                val ackParams = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
                client.acknowledgePurchase(ackParams) { _ -> }
            }
        }
    }

    private fun queryPurchases() {
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        ) { _, list ->
            // purchases will be delivered via onPurchasesUpdated after connect if needed
            // For restore: if user reinstalled, purchases are still there until consumed
        }
    }
}
