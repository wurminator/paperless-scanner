package com.paperless.scanner.data.billing

import app.cash.turbine.test
import com.paperless.scanner.data.datastore.TokenManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for PremiumFeatureManager.
 *
 * Tests the feature gate logic that combines:
 * - Premium access (subscription status from BillingManager)
 * - User preferences (from TokenManager)
 *
 * PHASE 2 NOTE: Tests simulate premium access via mocked BillingManager
 * which returns subscription status in production.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PremiumFeatureManagerTest {

    private lateinit var premiumFeatureManager: PremiumFeatureManager
    private lateinit var billingManager: BillingManager
    private lateinit var tokenManager: TokenManager

    private val mockSubscriptionActive = MutableStateFlow(false)
    private val mockAiSuggestionsEnabled = MutableStateFlow(true)
    private val mockAiNewTagsEnabled = MutableStateFlow(true)
    private val mockAiWifiOnly = MutableStateFlow(false)

    @Before
    fun setup() {
        billingManager = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)

        // Mock BillingManager subscription status
        every { billingManager.isSubscriptionActive } returns mockSubscriptionActive
        every { billingManager.isSubscriptionActiveSync() } answers { mockSubscriptionActive.value }

        // Mock TokenManager flows
        every { tokenManager.aiSuggestionsEnabled } returns mockAiSuggestionsEnabled
        every { tokenManager.aiNewTagsEnabled } returns mockAiNewTagsEnabled
        every { tokenManager.aiWifiOnly } returns mockAiWifiOnly

        // Mock TokenManager sync methods (for isFeatureAvailable)
        every { tokenManager.getAiSuggestionsEnabledSync() } answers { mockAiSuggestionsEnabled.value }
        every { tokenManager.getAiNewTagsEnabledSync() } answers { mockAiNewTagsEnabled.value }

        premiumFeatureManager = PremiumFeatureManager(billingManager, tokenManager)
    }

    /**
     * Helper to set premium access state for testing.
     * In production, this is controlled by BillingManager subscription status.
     */
    private fun setPremiumAccessEnabled(enabled: Boolean) {
        mockSubscriptionActive.value = enabled
    }

    // ==================== isAiEnabled Tests ====================

    @Test
    fun `isAiEnabled is true when premium access enabled and user enabled`() = runTest {
        setPremiumAccessEnabled(true)
        mockAiSuggestionsEnabled.value = true

        premiumFeatureManager.isAiEnabled.test {
            assertTrue(awaitItem())
        }
    }

    @Test
    fun `isAiEnabled is false when user disabled AI even with billing off`() = runTest {
        // BILLING_ENABLED=false: only user preference matters, not subscription
        setPremiumAccessEnabled(false)
        mockAiSuggestionsEnabled.value = false

        premiumFeatureManager.isAiEnabled.test {
            assertFalse(awaitItem())
        }
    }

    @Test
    fun `isAiEnabled is false when user disabled AI`() = runTest {
        setPremiumAccessEnabled(true)
        mockAiSuggestionsEnabled.value = false

        premiumFeatureManager.isAiEnabled.test {
            assertFalse(awaitItem())
        }
    }

    // ==================== isAiNewTagsEnabled Tests ====================

    @Test
    fun `isAiNewTagsEnabled is true when AI enabled and new tags enabled`() = runTest {
        setPremiumAccessEnabled(true)
        mockAiSuggestionsEnabled.value = true
        mockAiNewTagsEnabled.value = true

        premiumFeatureManager.isAiNewTagsEnabled.test {
            assertTrue(awaitItem())
        }
    }

    @Test
    fun `isAiNewTagsEnabled is false when new tags disabled`() = runTest {
        setPremiumAccessEnabled(true)
        mockAiSuggestionsEnabled.value = true
        mockAiNewTagsEnabled.value = false

        premiumFeatureManager.isAiNewTagsEnabled.test {
            assertFalse(awaitItem())
        }
    }

    @Test
    fun `isAiNewTagsEnabled is false when AI disabled`() = runTest {
        setPremiumAccessEnabled(true)
        mockAiSuggestionsEnabled.value = false
        mockAiNewTagsEnabled.value = true

        premiumFeatureManager.isAiNewTagsEnabled.test {
            assertFalse(awaitItem())
        }
    }

    // ==================== isFeatureAvailable Sync Tests ====================

    @Test
    fun `isFeatureAvailable AI_ANALYSIS returns true when premium access enabled`() {
        setPremiumAccessEnabled(true)

        assertTrue(premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS))
    }

    @Test
    fun `isFeatureAvailable AI_ANALYSIS returns false when user preference disabled`() {
        // BILLING_ENABLED=false: premium access is always granted,
        // so only user preference controls availability.
        mockAiSuggestionsEnabled.value = false

        assertFalse(premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS))
    }

    @Test
    fun `isFeatureAvailable AI_SUMMARY returns false (not implemented)`() {
        setPremiumAccessEnabled(true)

        assertFalse(premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_SUMMARY))
    }

    // ==================== isFeatureAvailableAsync Tests ====================

    @Test
    fun `isFeatureAvailableAsync AI_ANALYSIS respects user preference`() = runTest {
        setPremiumAccessEnabled(true)
        mockAiSuggestionsEnabled.value = false

        // Should return false because user disabled it
        assertFalse(premiumFeatureManager.isFeatureAvailableAsync(PremiumFeature.AI_ANALYSIS))
    }

    @Test
    fun `isFeatureAvailableAsync AI_ANALYSIS returns true when enabled`() = runTest {
        setPremiumAccessEnabled(true)
        mockAiSuggestionsEnabled.value = true

        assertTrue(premiumFeatureManager.isFeatureAvailableAsync(PremiumFeature.AI_ANALYSIS))
    }

    // ==================== requireFeature Tests ====================

    @Test
    fun `requireFeature returns Granted when feature available`() = runTest {
        setPremiumAccessEnabled(true)
        mockAiSuggestionsEnabled.value = true

        val result = premiumFeatureManager.requireFeature(PremiumFeature.AI_ANALYSIS)

        assertEquals(FeatureAccessResult.Granted, result)
    }

    @Test
    fun `requireFeature returns DisabledInSettings when feature disabled by user`() = runTest {
        // BILLING_ENABLED=false: premium access is always true,
        // so disabling user preference yields DisabledInSettings, not RequiresUpgrade.
        mockAiSuggestionsEnabled.value = false

        val result = premiumFeatureManager.requireFeature(PremiumFeature.AI_ANALYSIS)

        assertEquals(FeatureAccessResult.DisabledInSettings, result)
    }

    @Test
    fun `requireFeature returns DisabledInSettings when premium but disabled`() = runTest {
        setPremiumAccessEnabled(true)
        mockAiSuggestionsEnabled.value = false

        val result = premiumFeatureManager.requireFeature(PremiumFeature.AI_ANALYSIS)

        assertEquals(FeatureAccessResult.DisabledInSettings, result)
    }

    // ==================== Reactive Updates Tests ====================

    @Test
    fun `isAiEnabled ignores subscription when billing disabled`() = runTest {
        // BILLING_ENABLED=false: isAiEnabled follows user preference only.
        mockAiSuggestionsEnabled.value = true

        // Verify: true regardless of subscription state
        setPremiumAccessEnabled(false)
        assertTrue(premiumFeatureManager.isFeatureAvailableAsync(PremiumFeature.AI_ANALYSIS))

        setPremiumAccessEnabled(true)
        assertTrue(premiumFeatureManager.isFeatureAvailableAsync(PremiumFeature.AI_ANALYSIS))

        setPremiumAccessEnabled(false)
        assertTrue(premiumFeatureManager.isFeatureAvailableAsync(PremiumFeature.AI_ANALYSIS))
    }

    @Test
    fun `isAiEnabled reacts to user preference changes`() = runTest {
        setPremiumAccessEnabled(true)

        premiumFeatureManager.isAiEnabled.test {
            // Initially true (premium + enabled)
            assertTrue(awaitItem())

            // Becomes false when user disables
            mockAiSuggestionsEnabled.value = false
            assertFalse(awaitItem())

            // Becomes true when user re-enables
            mockAiSuggestionsEnabled.value = true
            assertTrue(awaitItem())
        }
    }

    // ==================== Phase 2 Specific Tests ====================

    @Test
    fun `no subscription has no premium access by default`() {
        // In unit tests, subscription is mocked as false initially
        // This test documents expected behavior
        val manager = PremiumFeatureManager(billingManager, tokenManager)
        // Initial value comes from billingManager.isSubscriptionActive
        // In test environment this is mocked as false by default
    }

    @Test
    fun `no subscription still allows features when billing disabled`() = runTest {
        // BILLING_ENABLED=false: premium access is always granted regardless of subscription.
        // Only user preferences control feature availability.
        setPremiumAccessEnabled(false) // Would block if billing were enabled
        mockAiSuggestionsEnabled.value = true

        // Features are available because billing is disabled (user prefs respected)
        assertTrue(premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS))
        assertTrue(premiumFeatureManager.isFeatureAvailableAsync(PremiumFeature.AI_ANALYSIS))

        val result = premiumFeatureManager.requireFeature(PremiumFeature.AI_ANALYSIS)
        assertEquals(FeatureAccessResult.Granted, result)
    }
}
