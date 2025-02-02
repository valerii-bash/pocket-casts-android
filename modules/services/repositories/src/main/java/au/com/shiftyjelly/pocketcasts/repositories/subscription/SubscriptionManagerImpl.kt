package au.com.shiftyjelly.pocketcasts.repositories.subscription

import android.app.Activity
import android.content.Context
import au.com.shiftyjelly.pocketcasts.models.to.SubscriptionStatus
import au.com.shiftyjelly.pocketcasts.models.type.Subscription
import au.com.shiftyjelly.pocketcasts.models.type.Subscription.Companion.PATRON_MONTHLY_PRODUCT_ID
import au.com.shiftyjelly.pocketcasts.models.type.Subscription.Companion.PATRON_YEARLY_PRODUCT_ID
import au.com.shiftyjelly.pocketcasts.models.type.Subscription.Companion.PLUS_MONTHLY_PRODUCT_ID
import au.com.shiftyjelly.pocketcasts.models.type.Subscription.Companion.PLUS_YEARLY_PRODUCT_ID
import au.com.shiftyjelly.pocketcasts.models.type.SubscriptionFrequency
import au.com.shiftyjelly.pocketcasts.models.type.SubscriptionMapper
import au.com.shiftyjelly.pocketcasts.models.type.SubscriptionPlatform
import au.com.shiftyjelly.pocketcasts.models.type.SubscriptionPricingPhase
import au.com.shiftyjelly.pocketcasts.models.type.SubscriptionTier
import au.com.shiftyjelly.pocketcasts.models.type.SubscriptionType
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncManager
import au.com.shiftyjelly.pocketcasts.servers.sync.SubscriptionPurchaseRequest
import au.com.shiftyjelly.pocketcasts.servers.sync.SubscriptionResponse
import au.com.shiftyjelly.pocketcasts.servers.sync.SubscriptionStatusResponse
import au.com.shiftyjelly.pocketcasts.utils.Optional
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResult
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchaseHistoryParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryPurchaseHistory
import com.android.billingclient.api.queryPurchasesAsync
import com.jakewharton.rxrelay2.BehaviorRelay
import com.jakewharton.rxrelay2.PublishRelay
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx2.await
import timber.log.Timber

private const val SUBSCRIPTION_REPLACEMENT_MODE_NOT_SET = -1

@Singleton
class SubscriptionManagerImpl @Inject constructor(
    private val syncManager: SyncManager,
    private val productDetailsInterceptor: ProductDetailsInterceptor,
    private val settings: Settings,
    private val subscriptionMapper: SubscriptionMapper,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : SubscriptionManager,
    PurchasesUpdatedListener,
    AcknowledgePurchaseResponseListener {

    private var cachedSubscriptionStatus: SubscriptionStatus?
        get() = settings.cachedSubscriptionStatus.value
        set(value) = settings.cachedSubscriptionStatus.set(value, updateModifiedAt = false)

    private var subscriptionStatus = BehaviorRelay.create<Optional<SubscriptionStatus>>().apply {
        val cachedStatus = cachedSubscriptionStatus
        if (cachedStatus != null) {
            accept(Optional.of(cachedStatus))
        } else {
            accept(Optional.of(null))
        }
    }

    private lateinit var billingClient: BillingClient
    private val productDetails = BehaviorRelay.create<ProductDetailsState>()
    private val purchaseEvents = PublishRelay.create<PurchaseEvent>()
    private val subscriptionChangedEvents = PublishRelay.create<SubscriptionChangedEvent>()
    private var hasOfferEligible = HashMap<Subscription.SubscriptionTier, Boolean>()

    override fun signOut() {
        clearCachedStatus()
    }

    override fun observeSubscriptionChangeEvents(): Flowable<SubscriptionChangedEvent> {
        return subscriptionChangedEvents.toFlowable(BackpressureStrategy.LATEST).distinctUntilChanged()
    }

    override fun observeProductDetails(): Flowable<ProductDetailsState> {
        return productDetails.toFlowable(BackpressureStrategy.LATEST)
    }

    override fun observePurchaseEvents(): Flowable<PurchaseEvent> {
        return purchaseEvents.toFlowable(BackpressureStrategy.LATEST)
    }

    override fun observeSubscriptionStatus(): Flowable<Optional<SubscriptionStatus>> {
        return subscriptionStatus.toFlowable(BackpressureStrategy.LATEST)
    }

    override fun subscriptionTier(): Flow<SubscriptionTier> {
        return observeSubscriptionStatus().asFlow().map { status ->
            (status.get() as? SubscriptionStatus.Paid)?.tier ?: SubscriptionTier.NONE
        }.distinctUntilChanged()
    }

    override fun getSubscriptionStatus(allowCache: Boolean): Single<SubscriptionStatus> {
        val cache = cachedSubscriptionStatus
        if (cache != null && allowCache) {
            return Single.just(cache)
        }

        return syncManager.subscriptionStatus()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .map {
                it.toStatus()
            }
            .doOnSuccess {
                subscriptionStatus.accept(Optional.of(it))
                val oldStatus = cachedSubscriptionStatus
                if (oldStatus != it) {
                    if (it is SubscriptionStatus.Paid && oldStatus is SubscriptionStatus.Free) {
                        subscriptionChangedEvents.accept(SubscriptionChangedEvent.AccountUpgradedToPlus)
                    } else if (it is SubscriptionStatus.Free && oldStatus is SubscriptionStatus.Paid) {
                        subscriptionChangedEvents.accept(SubscriptionChangedEvent.AccountDowngradedToFree)
                    }
                }
                cachedSubscriptionStatus = it

                if (!it.isPocketCastsChampion && it is SubscriptionStatus.Paid && it.platform == SubscriptionPlatform.GIFT) { // This account is a trial account
                    settings.setTrialFinishedSeen(false) // Make sure on expiry we show the trial finished dialog
                }
            }
    }

    override fun connectToGooglePlay(context: Context) {
        val params = PendingPurchasesParams.newBuilder()
            .enablePrepaidPlans()
            .enableOneTimeProducts()
            .build()
        billingClient = BillingClient.newBuilder(context)
            .enablePendingPurchases(params)
            .setListener(this)
            .build()
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Timber.d("Connected to google play")
                    loadProducts()
                } else {
                    Timber.e("Couldn't set up billing connection: ${billingResult.debugMessage}")
                    loadProducts()
                }
            }

            override fun onBillingServiceDisconnected() {
                // Try to restart the connection on the next request to
                // Google Play by calling the startConnection() method.
                Timber.d("Disconnected from Google Play")
            }
        })
    }

    override fun loadProducts() {
        val productList =
            mutableListOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PLUS_MONTHLY_PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build(),
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PLUS_YEARLY_PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build(),
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PATRON_MONTHLY_PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build(),
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PATRON_YEARLY_PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build(),
            )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            val (result, products) = productDetailsInterceptor.intercept(billingResult, productDetailsList)
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                Timber.d("Billing products loaded")
                productDetails.accept(ProductDetailsState.Loaded(products))

                refreshPurchases()
            } else {
                productDetails.accept(ProductDetailsState.Error(result.debugMessage))
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                handlePurchase(purchase)
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            purchaseEvents.accept(PurchaseEvent.Cancelled(billingResult.responseCode))
        } else {
            if (billingResult.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                applicationScope.launch {
                    val purchasesResult = getPurchases()
                    if (purchasesResult == null) {
                        LogBuffer.e(LogBuffer.TAG_SUBSCRIPTIONS, "unable to update purchase because billing result returned null purchases")
                        return@launch
                    }

                    if (purchasesResult.purchasesList.isNotEmpty()) {
                        val existingPurchase = purchasesResult.purchasesList.first()

                        try {
                            sendPurchaseToServer(existingPurchase)
                        } catch (e: Exception) {
                            LogBuffer.e(LogBuffer.TAG_SUBSCRIPTIONS, e, "Could not send purchase info")
                            val failureEvent = PurchaseEvent.Failure(
                                e.message ?: "Unknown error",
                                billingResult.responseCode,
                            )
                            purchaseEvents.accept(failureEvent)
                        }
                    } else {
                        LogBuffer.e(LogBuffer.TAG_SUBSCRIPTIONS, "Subscription purchase returned already owned but we couldn't load it")
                        val failureEvent = PurchaseEvent.Failure(
                            purchasesResult.billingResult.debugMessage,
                            billingResult.responseCode,
                        )
                        purchaseEvents.accept(failureEvent)
                    }
                }
            } else {
                LogBuffer.e(LogBuffer.TAG_SUBSCRIPTIONS, "Could not purchase subscription: ${billingResult.debugMessage}")
                val failureEvent = PurchaseEvent.Failure(billingResult.debugMessage, billingResult.responseCode)
                purchaseEvents.accept(failureEvent)
            }
        }
    }

    override fun onAcknowledgePurchaseResponse(billingResult: BillingResult) {
        Timber.d("Purchase ack")
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            LogBuffer.i(LogBuffer.TAG_SUBSCRIPTIONS, "Purchase ack OK")
        } else {
            LogBuffer.e(LogBuffer.TAG_SUBSCRIPTIONS, "Purchase ack FAILED. code= ${billingResult.responseCode} message=${billingResult.debugMessage}")
        }
    }

    override fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            Timber.d("Purchase: ${purchase.purchaseToken}")
            // Grant entitlement to the user.
            applicationScope.launch {
                try {
                    sendPurchaseToServer(purchase)
                    // Acknowledge the purchase if it hasn't already been acknowledged.
                    if (!purchase.isAcknowledged) {
                        val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.purchaseToken)
                            .build()
                        billingClient.acknowledgePurchase(acknowledgePurchaseParams, this@SubscriptionManagerImpl)
                    }
                    purchase.products.map {
                        Subscription.SubscriptionTier.fromProductId(it.toString())
                    }.distinct().forEach {
                        updateOfferEligible(it, false)
                    }
                } catch (e: Exception) {
                    LogBuffer.e(LogBuffer.TAG_SUBSCRIPTIONS, e, "Could not send purchase info")
                    purchaseEvents.accept(PurchaseEvent.Failure(e.message ?: "Unknown error", null))
                }
            }
        }
    }

    override suspend fun sendPurchaseToServer(purchase: Purchase) {
        if (purchase.products.size != 1) {
            LogBuffer.e(LogBuffer.TAG_SUBSCRIPTIONS, "expected 1 product when sending purchase to server, but there were ${purchase.products.size}")
        }

        val response = syncManager.subscriptionPurchase(SubscriptionPurchaseRequest(purchase.purchaseToken, purchase.products.first())).await()
        val newStatus = response.toStatus()
        cachedSubscriptionStatus = newStatus
        subscriptionStatus.accept(Optional.of(newStatus))
        purchaseEvents.accept(PurchaseEvent.Success)
    }

    override fun refreshPurchases() {
        if (!billingClient.isReady) return

        applicationScope.launch {
            updateOfferEligibilityIfPurchaseHistoryExists()
        }

        val queryPurchasesParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient.queryPurchasesAsync(queryPurchasesParams) { _, purchases ->
            purchases.forEach {
                if (!it.isAcknowledged) { // Purchase was purchased in the play store, or in the background somehow
                    handlePurchase(it)
                }
            }
        }
    }

    private suspend fun updateOfferEligibilityIfPurchaseHistoryExists() {
        val queryPurchaseHistoryParams = QueryPurchaseHistoryParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val result = billingClient.queryPurchaseHistory(queryPurchaseHistoryParams)
        result.purchaseHistoryRecordList?.forEach {
            it.products.map { productId ->
                Subscription.SubscriptionTier.fromProductId(productId)
            }.distinct().forEach { tier ->
                updateOfferEligible(tier, false)
            }
        }
    }

    override suspend fun getPurchases(): PurchasesResult? {
        if (!billingClient.isReady) return null

        val queryPurchasesParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        return billingClient.queryPurchasesAsync(params = queryPurchasesParams)
    }

    override fun launchBillingFlow(activity: Activity, productDetails: ProductDetails, offerToken: String) {
        applicationScope.launch {
            val productDetailsParams =
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .setOfferToken(offerToken)
                    .build()
            val productDetailsParamsList = listOf(productDetailsParams)
            var billingFlowParams =
                BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(productDetailsParamsList)

            settings.cachedSubscriptionStatus.value?.let { subscriptionStatus ->
                val replacementMode = getSubscriptionReplacementMode(
                    subscribedPlanStatus = subscriptionStatus,
                    newPlanDetails = productDetails,
                )

                if (replacementMode != SUBSCRIPTION_REPLACEMENT_MODE_NOT_SET) {
                    val purchasesResult = getPurchases()
                    if (purchasesResult == null) {
                        LogBuffer.e(
                            LogBuffer.TAG_SUBSCRIPTIONS,
                            "unable to upgrade plan because billing result returned null purchases",
                        )
                        return@launch
                    }

                    if (purchasesResult.purchasesList.isNotEmpty()) {
                        val existingPurchase = purchasesResult.purchasesList.first()
                        billingFlowParams = billingFlowParams.setSubscriptionUpdateParams(
                            BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                                .setOldPurchaseToken(existingPurchase.purchaseToken)
                                /* User is changing subscription entitlement, proration rate applied at runtime (https://rb.gy/e876y) */
                                .setSubscriptionReplacementMode(replacementMode)
                                .build(),
                        )
                    }
                }
            }
            billingClient.launchBillingFlow(activity, billingFlowParams.build())
        }
    }

    override fun getCachedStatus(): SubscriptionStatus? {
        return subscriptionStatus.value?.get()
    }

    override fun clearCachedStatus() {
        cachedSubscriptionStatus = null
        subscriptionStatus.accept(Optional.empty())
    }
    override fun isOfferEligible(tier: Subscription.SubscriptionTier): Boolean = (hasOfferEligible[tier] ?: true)
    override fun updateOfferEligible(tier: Subscription.SubscriptionTier, eligible: Boolean) {
        hasOfferEligible[tier] = eligible
    }
    override fun getDefaultSubscription(
        subscriptions: List<Subscription>,
        tier: Subscription.SubscriptionTier?,
        frequency: SubscriptionFrequency?,
    ): Subscription? {
        val subscriptionTier = tier ?: Subscription.SubscriptionTier.PLUS
        val subscriptionFrequency = frequency ?: SubscriptionFrequency.YEARLY

        val tierSubscriptions = subscriptions.filter { it.tier == subscriptionTier }
        val withOffers = tierSubscriptions.filterIsInstance<Subscription.WithOffer>()

        return withOffers.find {
            it.recurringPricingPhase is SubscriptionPricingPhase.Months
        } ?: tierSubscriptions.firstOrNull {
            when (subscriptionFrequency) {
                SubscriptionFrequency.MONTHLY -> it.recurringPricingPhase is SubscriptionPricingPhase.Months
                SubscriptionFrequency.YEARLY -> it.recurringPricingPhase is SubscriptionPricingPhase.Years
                SubscriptionFrequency.NONE -> throw IllegalStateException("Unknown subscription frequency found")
            }
        } ?: tierSubscriptions.firstOrNull() // If no matching subscription is found, select first available one
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun freeTrialForSubscriptionTierFlow(subscriptionTier: Subscription.SubscriptionTier) = this
        .observeProductDetails()
        .asFlow()
        .transformLatest { productDetails ->
            val subscriptions = when (productDetails) {
                is ProductDetailsState.Error -> null
                is ProductDetailsState.Loaded -> productDetails.productDetails.mapNotNull { productDetailsState ->
                    subscriptionMapper.mapFromProductDetails(
                        productDetails = productDetailsState,
                        isOfferEligible = isOfferEligible(
                            Subscription.SubscriptionTier.fromProductId(productDetailsState.productId),
                        ),
                    )
                }
            } ?: emptyList()

            val filteredOffer = Subscription.filterOffers(subscriptions)
            val updatedSubscriptions = filteredOffer.filter { it.tier == subscriptionTier }
            val defaultSubscription = getDefaultSubscription(
                subscriptions = updatedSubscriptions,
                tier = subscriptionTier,
            )
            emit(
                FreeTrial(
                    subscriptionTier = subscriptionTier,
                    exists = defaultSubscription?.offerPricingPhase != null,
                ),
            )
        }
}

private fun getSubscriptionReplacementMode(
    subscribedPlanStatus: SubscriptionStatus,
    newPlanDetails: ProductDetails,
) = when {
    /* Since upgrading to a more expensive tier, recommended replacement mode is CHARGE_PRORATED_PRICE (https://rb.gy/acghw) */
    subscribedPlanStatus is SubscriptionStatus.Paid &&
        subscribedPlanStatus.tier == SubscriptionTier.PLUS &&
        subscribedPlanStatus.platform == SubscriptionPlatform.ANDROID &&
        newPlanDetails.productId in listOf(PATRON_MONTHLY_PRODUCT_ID, PATRON_YEARLY_PRODUCT_ID)
    -> BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE

    /* Since upgrading from shorter to longer billing period within same Plus tier, recommended replacement mode is CHARGE_FULL_PRICE (https://rb.gy/8h4adz) */
    subscribedPlanStatus is SubscriptionStatus.Paid &&
        subscribedPlanStatus.tier == SubscriptionTier.PLUS &&
        subscribedPlanStatus.platform == SubscriptionPlatform.ANDROID &&
        subscribedPlanStatus.frequency == SubscriptionFrequency.MONTHLY &&
        newPlanDetails.productId == PLUS_YEARLY_PRODUCT_ID -> BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_FULL_PRICE

    /* Since upgrading from shorter to longer billing period within same Patron tier, recommended replacement mode is CHARGE_FULL_PRICE (https://rb.gy/8h4adz) */
    subscribedPlanStatus is SubscriptionStatus.Paid &&
        subscribedPlanStatus.tier == SubscriptionTier.PATRON &&
        subscribedPlanStatus.platform == SubscriptionPlatform.ANDROID &&
        subscribedPlanStatus.frequency == SubscriptionFrequency.MONTHLY &&
        newPlanDetails.productId == PATRON_YEARLY_PRODUCT_ID -> BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_FULL_PRICE

    /* Since downgrading to less expensive plan, recommended replacement mode is DEFERRED (https://rb.gy/8y0wx0) */
    subscribedPlanStatus is SubscriptionStatus.Paid &&
        subscribedPlanStatus.platform == SubscriptionPlatform.ANDROID &&
        subscribedPlanStatus.frequency == SubscriptionFrequency.YEARLY &&
        newPlanDetails.productId in listOf(PLUS_MONTHLY_PRODUCT_ID, PATRON_MONTHLY_PRODUCT_ID) -> BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.DEFERRED

    else -> SUBSCRIPTION_REPLACEMENT_MODE_NOT_SET
}

sealed class ProductDetailsState {
    data class Loaded(val productDetails: List<ProductDetails>) : ProductDetailsState()
    data class Error(val message: String) : ProductDetailsState()
}

sealed class PurchaseEvent {
    object Success : PurchaseEvent()
    data class Cancelled(@BillingClient.BillingResponseCode val responseCode: Int) : PurchaseEvent()
    data class Failure(
        val errorMessage: String,
        @BillingClient.BillingResponseCode val responseCode: Int?,
    ) : PurchaseEvent()
}

sealed class SubscriptionChangedEvent {
    object AccountUpgradedToPlus : SubscriptionChangedEvent()
    object AccountDowngradedToFree : SubscriptionChangedEvent()
}

data class FreeTrial(
    val subscriptionTier: Subscription.SubscriptionTier,
    val exists: Boolean = false,
)

private fun SubscriptionStatusResponse.toStatus(): SubscriptionStatus {
    val originalPlatform = SubscriptionPlatform.entries.getOrNull(platform) ?: SubscriptionPlatform.NONE

    val subs = subscriptions?.map { it.toSubscription() } ?: emptyList()
    subs.getOrNull(index)?.isPrimarySubscription = true // Mark the subscription that the server says is the main one
    return if (paid == 0) {
        SubscriptionStatus.Free(expiryDate, giftDays, originalPlatform, subs)
    } else {
        val freq = SubscriptionFrequency.entries.getOrNull(frequency) ?: SubscriptionFrequency.NONE
        val enumType = SubscriptionType.entries.getOrNull(type) ?: SubscriptionType.NONE
        val enumTier = SubscriptionTier.fromString(tier, enumType)
        SubscriptionStatus.Paid(expiryDate ?: Date(), autoRenewing, giftDays, freq, originalPlatform, subs, enumType, enumTier, index)
    }
}

private fun SubscriptionResponse.toSubscription(): SubscriptionStatus.Subscription {
    val enumType = SubscriptionType.entries.getOrNull(type) ?: SubscriptionType.NONE
    val enumTier = SubscriptionTier.fromString(tier, enumType)
    val freq = SubscriptionFrequency.entries.getOrNull(frequency) ?: SubscriptionFrequency.NONE
    return SubscriptionStatus.Subscription(enumType, enumTier, freq, expiryDate, autoRenewing, updateUrl)
}
