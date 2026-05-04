package com.paperless.scanner.data.billing

import androidx.annotation.VisibleForTesting
import com.paperless.scanner.BuildConfig
import com.paperless.scanner.data.datastore.TokenManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PremiumFeatureManager @Inject constructor(
    private val billingManager: BillingManager,
    private val tokenManager: TokenManager
) {

    companion object {
        /**
         * Set to `true` to re-enable Google Play Billing & subscription gating.
         * When `false`, all Premium features are unlocked — no billing required.
         */
        const val BILLING_ENABLED = false
    }

    /**
     * Whether premium features are accessible.
     * BILLING_ENABLED=false → always true (all features unlocked).
     * BILLING_ENABLED=true  → based on actual subscription status.
     */
    val isPremiumAccessEnabled: Flow<Boolean> =
        if (BILLING_ENABLED) billingManager.isSubscriptionActive else flowOf(true)

    /**
     * Sync check for premium access status.
     */
    private fun isPremiumAccessEnabledSync(): Boolean =
        if (BILLING_ENABLED) billingManager.isSubscriptionActiveSync() else true

    /**
     * Whether AI suggestions are enabled and available.
     * Combines:
     * - Active Premium subscription (from BillingManager)
     * - User preference (AI suggestions enabled in settings)
     *
     * PHASE 2 (ACTIVE): Requires actual subscription.
     */
    val isAiEnabled: Flow<Boolean> =
        if (BILLING_ENABLED) {
            combine(
                billingManager.isSubscriptionActive,
                tokenManager.aiSuggestionsEnabled
            ) { hasAccess, userEnabled ->
                hasAccess && userEnabled
            }
        } else {
            // Billing disabled: only respect user preference
            tokenManager.aiSuggestionsEnabled
        }

    /**
     * Whether AI can suggest new tags.
     * Requires Premium + AI enabled + new tags preference.
     */
    val isAiNewTagsEnabled: Flow<Boolean> = combine(
        isAiEnabled,
        tokenManager.aiNewTagsEnabled
    ) { aiEnabled, newTagsEnabled ->
        aiEnabled && newTagsEnabled
    }

    /**
     * Whether AI features should only work on WiFi.
     */
    val aiWifiOnly: Flow<Boolean> = tokenManager.aiWifiOnly

    /**
     * Check if a specific Premium feature is available.
     * Synchronous version for immediate decision-making.
     *
     * PHASE 2 (ACTIVE): Uses billingManager.isSubscriptionActiveSync()
     *
     * @param feature The feature to check
     * @return true if feature is available (subscription + preference enabled)
     */
    fun isFeatureAvailable(feature: PremiumFeature): Boolean {
        if (!BILLING_ENABLED) {
            // All features unlocked — only respect user preferences
            return when (feature) {
                PremiumFeature.AI_ANALYSIS -> isAiSuggestionsEnabledSync()
                PremiumFeature.AI_NEW_TAGS -> isAiSuggestionsEnabledSync() && isAiNewTagsEnabledSync()
                PremiumFeature.AI_SUMMARY -> isAiSuggestionsEnabledSync()
            }
        }
        return when (feature) {
            PremiumFeature.AI_ANALYSIS -> {
                isPremiumAccessEnabledSync() && isAiSuggestionsEnabledSync()
            }
            PremiumFeature.AI_NEW_TAGS -> {
                isPremiumAccessEnabledSync() && isAiSuggestionsEnabledSync() && isAiNewTagsEnabledSync()
            }
            PremiumFeature.AI_SUMMARY -> {
                false // Not implemented yet — enable when ready
            }
        }
    }

    /**
     * Sync check for AI suggestions enabled (user preference).
     */
    private fun isAiSuggestionsEnabledSync(): Boolean {
        return tokenManager.getAiSuggestionsEnabledSync()
    }

    /**
     * Sync check for AI new tags enabled (user preference).
     */
    private fun isAiNewTagsEnabledSync(): Boolean {
        return tokenManager.getAiNewTagsEnabledSync()
    }

    /**
     * Sync check for AI WiFi-only preference.
     */
    private fun isAiWifiOnlySync(): Boolean {
        return runBlocking {
            tokenManager.aiWifiOnly.first()
        }
    }

    /**
     * Check if a feature is available (async version).
     * Use this when you need to respect user preferences.
     *
     * @param feature The feature to check
     * @return true if feature is available
     */
    suspend fun isFeatureAvailableAsync(feature: PremiumFeature): Boolean {
        return when (feature) {
            PremiumFeature.AI_ANALYSIS -> {
                isAiEnabled.first()
            }
            PremiumFeature.AI_NEW_TAGS -> {
                isAiNewTagsEnabled.first()
            }
            PremiumFeature.AI_SUMMARY -> {
                false // Not implemented yet
            }
        }
    }

    /**
     * Require a Premium feature or return a result indicating upgrade needed.
     * Useful for feature-gated actions.
     *
     * PHASE 2 (ACTIVE): Checks actual subscription status.
     *
     * @return FeatureAccessResult indicating access or upgrade required
     */
    suspend fun requireFeature(feature: PremiumFeature): FeatureAccessResult {
        return if (isFeatureAvailableAsync(feature)) {
            FeatureAccessResult.Granted
        } else {
            if (isPremiumAccessEnabledSync()) {
                // Has active subscription but feature disabled in settings
                FeatureAccessResult.DisabledInSettings
            } else {
                // No active subscription
                FeatureAccessResult.RequiresUpgrade
            }
        }
    }

}

/**
 * Result of feature access check.
 */
sealed class FeatureAccessResult {
    /** Feature is available and can be used */
    data object Granted : FeatureAccessResult()

    /** User has subscription but disabled feature in settings */
    data object DisabledInSettings : FeatureAccessResult()

    /** User needs to upgrade to Premium */
    data object RequiresUpgrade : FeatureAccessResult()
}
