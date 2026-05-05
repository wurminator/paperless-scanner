package com.paperless.scanner.data.analytics

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper service for Firebase Analytics and Crashlytics.
 * Performance Monitoring disabled in debug builds (no real Firebase project).
 *
 * All data collection is:
 * - Anonymized (no PII collected)
 * - GDPR-compliant (respects user consent)
 * - Disabled by default until user grants consent
 */
@Singleton
class AnalyticsService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val firebaseAnalytics: FirebaseAnalytics by lazy {
        Firebase.analytics
    }

    private var isEnabled = false

    companion object {
        private const val TAG = "AnalyticsService"
    }

    /**
     * Enable or disable all analytics collection.
     * Should be called based on user consent.
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        firebaseAnalytics.setAnalyticsCollectionEnabled(enabled)
        Firebase.crashlytics.setCrashlyticsCollectionEnabled(enabled)
        // Performance collection disabled in debug builds

        Log.d(TAG, "Analytics collection ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Track an analytics event.
     * Events are only sent if analytics is enabled.
     */
    fun trackEvent(event: AnalyticsEvent) {
        if (!isEnabled) {
            Log.d(TAG, "Event '${event.name}' skipped (analytics disabled)")
            return
        }

        val bundle = Bundle().apply {
            event.params.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Double -> putDouble(key, value)
                    is Boolean -> putBoolean(key, value)
                    else -> putString(key, value.toString())
                }
            }
        }

        firebaseAnalytics.logEvent(event.name, bundle)
        Log.d(TAG, "Event tracked: ${event.name} with params: ${event.params}")
    }

    /**
     * Track a screen view.
     */
    fun trackScreen(screenName: String, screenClass: String? = null) {
        if (!isEnabled) return

        val bundle = Bundle().apply {
            putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
            screenClass?.let { putString(FirebaseAnalytics.Param.SCREEN_CLASS, it) }
        }

        firebaseAnalytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, bundle)
        Log.d(TAG, "Screen tracked: $screenName")
    }

    fun setUserProperty(name: String, value: String?) {
        if (!isEnabled) return
        firebaseAnalytics.setUserProperty(name, value)
    }

    fun logError(throwable: Throwable, message: String? = null) {
        if (!isEnabled) return
        message?.let { Firebase.crashlytics.log(it) }
        Firebase.crashlytics.recordException(throwable)
        Log.e(TAG, "Error logged: ${message ?: throwable.message}", throwable)
    }

    fun logMessage(message: String) {
        if (!isEnabled) return
        Firebase.crashlytics.log(message)
    }

    fun isAnalyticsEnabled(): Boolean = isEnabled

    fun initializeCrashlyticsKeys(
        serverUrl: String?,
        appVersion: String,
        versionCode: Int,
        subscriptionStatus: String,
        isOffline: Boolean
    ) {
        if (!isEnabled) {
            Log.d(TAG, "Crashlytics keys skipped (analytics disabled)")
            return
        }

        setServerUrlHash(serverUrl)
        Firebase.crashlytics.setCustomKey("app_version", appVersion)
        Firebase.crashlytics.setCustomKey("version_code", versionCode)
        Firebase.crashlytics.setCustomKey("subscription_status", subscriptionStatus)
        Firebase.crashlytics.setCustomKey("is_offline", isOffline)

        Log.d(TAG, "Crashlytics keys initialized: version=$appVersion, code=$versionCode")
    }

    fun updateOfflineState(isOffline: Boolean) {
        if (!isEnabled) return
        Firebase.crashlytics.setCustomKey("is_offline", isOffline)
    }

    fun updateCrashlyticsSubscriptionStatus(status: String) {
        if (!isEnabled) return
        Firebase.crashlytics.setCustomKey("subscription_status", status)
    }

    private fun setServerUrlHash(serverUrl: String?) {
        val hash = hashServerUrl(serverUrl)
        Firebase.crashlytics.setCustomKey("server_url_hash", hash)
    }

    private fun hashServerUrl(url: String?): String {
        if (url.isNullOrBlank()) return "none"
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(url.toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hash server URL", e)
            "error"
        }
    }

    // ==================== AI-Specific User Properties ====================

    fun setSubscriptionStatus(status: String) {
        setUserProperty("subscription_status", status)
    }

    fun setAiCallsThisMonth(callCount: Int) {
        setUserProperty("ai_calls_this_month", callCount.toString())
        val isHeavyUser = callCount > 100
        setUserProperty("ai_heavy_user", if (isHeavyUser) "true" else "false")
    }

    fun trackAiFeatureUsage(
        featureType: String,
        inputTokens: Int,
        outputTokens: Int,
        subscriptionType: String
    ) {
        val costUsd = AiCostCalculator.calculateCost(inputTokens, outputTokens)
        trackEvent(
            AnalyticsEvent.AiFeatureUsed(
                featureType = featureType,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                estimatedCostUsd = costUsd,
                subscriptionType = subscriptionType,
                success = true
            )
        )
    }

    fun trackAiFeatureFailure(
        featureType: String,
        inputTokens: Int,
        subscriptionType: String
    ) {
        val costUsd = if (inputTokens > 0) {
            AiCostCalculator.calculateCost(inputTokens, 0)
        } else {
            0.0
        }
        trackEvent(
            AnalyticsEvent.AiFeatureUsed(
                featureType = featureType,
                inputTokens = inputTokens,
                outputTokens = 0,
                estimatedCostUsd = costUsd,
                subscriptionType = subscriptionType,
                success = false
            )
        )
    }
}

object AiCostCalculator {
    private const val INPUT_COST_PER_MILLION = 0.30
    private const val OUTPUT_COST_PER_MILLION = 2.50

    fun calculateCost(inputTokens: Int, outputTokens: Int): Double {
        val inputCost = (inputTokens.toDouble() / 1_000_000) * INPUT_COST_PER_MILLION
        val outputCost = (outputTokens.toDouble() / 1_000_000) * OUTPUT_COST_PER_MILLION
        return inputCost + outputCost
    }

    fun calculateInputCost(inputTokens: Int): Double {
        return (inputTokens.toDouble() / 1_000_000) * INPUT_COST_PER_MILLION
    }

    fun getAverageCostPerCall(): Double {
        return calculateCost(inputTokens = 1500, outputTokens = 200)
    }
}
